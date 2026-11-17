package dev.shinsou.kmp.content

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.domain.model.PublicationKey
import kotlin.time.Clock
import dev.shinsou.kmp.domain.content.BlobRef
import dev.shinsou.kmp.domain.content.deepImmutableSnapshot

// R4-4 (docs/architecture/0006): moved out of ContentBlobStore.kt without changing it.

/** Deterministic in-memory implementation used by common tests and staging callers. */
public class InMemoryContentBlobStore(
    override val maximumBlobSizeBytes: Long = DEFAULT_MAXIMUM_BLOB_SIZE_BYTES,
    private val blobIdFactory: () -> String = { "" },
    private val commitTokenFactory: () -> String = { "" },
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    public val configuredStoreInstanceId: String? = null,
) : ContentBlobStore {
    /** Stable identity used to reject receipts crossing store instances. */
    public val storeInstanceId: String = configuredStoreInstanceId?.takeIf(String::isNotBlank)
        ?: "memory-store-${hashCode().toUInt().toString(16)}"
    private val stateMutex = SynchronousLock()
    private var lockHeld = false
    private val blobs = LinkedHashMap<String, StoredBlob>()
    private val attachments = LinkedHashMap<String, AttachmentLedger>()
    private val auxiliaryAttachments = LinkedHashMap<String, AuxiliaryAttachmentLedger>()
    private val published = LinkedHashMap<String, PendingReceipt>()
    private val activeReaders = LinkedHashSet<StateReader>()
    private val liveStages = LinkedHashSet<ContentBlobStage>()
    private var nextBlobSequence = 1L
    private var nextCommitSequence = 1L
    private var nextIncarnationValue = 1L
    private var durability: ContentBlobDurability? = null
    private var deferredHydration: (() -> DeferredAttachmentHydration)? = null
    override var currentGeneration: Long = 0
        private set

    internal val isRestartSafe: Boolean get() = durability != null

    init {
        require(maximumBlobSizeBytes > 0) { "Maximum blob size must be positive" }
        require(maximumBlobSizeBytes <= Int.MAX_VALUE.toLong()) {
            "In-memory maximum blob size must fit a ByteArray"
        }
    }

    internal constructor(
        maximumBlobSizeBytes: Long,
        blobIdFactory: () -> String,
        commitTokenFactory: () -> String,
        clock: () -> Long,
        configuredStoreInstanceId: String,
        durability: ContentBlobDurability,
    ) : this(
        maximumBlobSizeBytes = maximumBlobSizeBytes,
        blobIdFactory = blobIdFactory,
        commitTokenFactory = commitTokenFactory,
        clock = clock,
        configuredStoreInstanceId = configuredStoreInstanceId,
    ) {
        require(durability.storeInstanceId == storeInstanceId) {
            "Durable blob store identity does not match the in-memory participant"
        }
        this.durability = durability
        restoreDurableBlobs(durability.loadMetadata())
    }

    override fun beginStage(expectedSizeBytes: Long?, mediaType: String): ContentBlobStage = withStateLock {
        require(expectedSizeBytes == null || expectedSizeBytes >= 0) {
            "Expected blob size must be non-negative"
        }
        expectedSizeBytes?.let(::checkSize)
        require(mediaType.isNotBlank() && mediaType.length <= 256 && mediaType.none(Char::isISOControl)) {
            "Blob media type must be printable and bounded"
        }
        val stage = durability
            ?.beginStage(expectedSizeBytes, mediaType, maximumBlobSizeBytes)
            ?.let(::DurableStage)
            ?: MemoryStage(expectedSizeBytes, mediaType)
        stage.also(liveStages::add)
    }

    override fun publish(candidate: PendingBlob): BlobPublishReceipt = withStateLock {
        val memoryPending = candidate as? MemoryPending
        val durablePending = candidate as? DurablePending
        val stage = memoryPending?.stage ?: durablePending?.stage
            ?: throw ContentBlobStoreException.InvalidStage("Candidate belongs to another blob store")
        if (!stage.isSealed || stage !in liveStages) {
            throw ContentBlobStoreException.InvalidStage("Blob candidate is not a live sealed stage")
        }
        val reference = candidate.reference
        val candidateBytes = memoryPending?.bytes
        val commitToken = nextCommitToken()
        require(published[commitToken] == null) { "Blob commit token factory returned a duplicate token" }
        val nextGeneration = checkedAdd(currentGeneration, 1)
        val publishedAt = clock().also { require(it >= 0) { "Blob clock must be non-negative" } }
        val existing = blobs[reference.blobId]
        if (existing != null) {
            if (existing.lifecycleState == BlobLifecycleState.DISCOVERED_ORPHAN) {
                throw ContentBlobStoreException.InvalidStage("A discovered orphan cannot be republished before sweep")
            }
            if (published.values.any {
                    it.receipt.reference.blobId == reference.blobId &&
                        it.receipt.incarnation == existing.incarnation
                }
            ) {
                throw ContentBlobStoreException.InvalidStage("Blob already has an unconsumed publish receipt")
            }
            if (existing.reference != reference ||
                (candidateBytes != null && !payloadBytesLocked(existing).contentEquals(candidateBytes)) ||
                (durablePending != null && !isIntact(existing))
            ) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
        } else {
            checkSize(reference.byteSize)
        }
        // All potentially throwing checks happen before visibility changes.  This is important
        // for a failed token factory or clock: `put` must never leave a blob without its receipt.
        val stored = if (existing == null) {
            StoredBlob(
                reference = reference,
                bytes = candidateBytes?.copyOf(),
                incarnation = nextIncarnation().also { require(it > 0) },
                generation = nextGeneration,
                publishedAtEpochMillis = publishedAt,
                lifecycleState = BlobLifecycleState.AVAILABLE,
                discoveredAtEpochMillis = null,
            )
        } else {
            // A crash-recovered identical blob gets a fresh publication clock/generation.  It
            // cannot be collected using the age of its previous, lost receipt.
            existing.copy(
                bytes = candidateBytes?.copyOf(),
                generation = nextGeneration,
                publishedAtEpochMillis = publishedAt,
                lifecycleState = BlobLifecycleState.AVAILABLE,
                discoveredAtEpochMillis = null,
            )
        }
        // Persistence happens before the in-process receipt becomes observable. A process death
        // after this write leaves a complete, receipt-less orphan which is discovered only by an
        // explicit recovery boundary after restart.
        if (durablePending != null) {
            val persistence = durability
                ?: throw ContentBlobStoreException.InvalidStage("Durable stage lost its storage adapter")
            persistence.publish(stored.toDurableBlob(includePayload = false), durablePending.handle)
        } else {
            durability?.upsert(stored.toDurableBlob(includePayload = true))
        }
        blobs[reference.blobId] = stored
        liveStages.remove(stage)
        when (stage) {
            is MemoryStage -> stage.published = true
            is DurableStage -> stage.markPublishedLocked()
        }
        currentGeneration = nextGeneration
        val receipt = BlobPublishReceipt(
            storeInstanceId = storeInstanceId,
            commitToken = commitToken,
            reference = reference,
            incarnation = stored.incarnation,
            generation = nextGeneration,
            publishedAtEpochMillis = publishedAt,
        )
        published[commitToken] = PendingReceipt(receipt)
        receipt
    }

    override fun claimExistingVerified(reference: BlobRef): BlobPublishReceipt? = withStateLock {
        reference.validate()
        val stored = blobs[reference.blobId] ?: return null
        if (stored.reference != reference) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }
        val matchingPending = published.values.filter {
            it.receipt.reference == stored.reference && it.receipt.incarnation == stored.incarnation
        }
        check(matchingPending.size <= 1) { "A blob incarnation has multiple pending receipts" }
        // A pending receipt was issued only after this exact immutable incarnation passed full
        // verification. Return it before touching durable payload bytes again; final transaction
        // validation still re-verifies every receipt immediately before attachment.
        matchingPending.singleOrNull()?.let { return it.receipt }
        if (!isIntact(stored)) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }

        val commitToken = nextCommitToken()
        require(published[commitToken] == null) { "Blob commit token factory returned a duplicate token" }
        val nextGeneration = checkedAdd(currentGeneration, 1)
        val claimedAt = clock().also { require(it >= 0) { "Blob clock must be non-negative" } }
        val claimed = stored.copy(
            generation = nextGeneration,
            publishedAtEpochMillis = claimedAt,
            lifecycleState = BlobLifecycleState.AVAILABLE,
            discoveredAtEpochMillis = null,
        )
        // Payload verification above reads only this exact blob. Persisting the claim updates
        // lifecycle metadata without rewriting or reloading the body column.
        durability?.upsert(claimed.toDurableBlob(includePayload = false))
        blobs[reference.blobId] = claimed
        currentGeneration = nextGeneration
        val receipt = BlobPublishReceipt(
            storeInstanceId = storeInstanceId,
            commitToken = commitToken,
            reference = claimed.reference,
            incarnation = claimed.incarnation,
            generation = claimed.generation,
            publishedAtEpochMillis = claimedAt,
        )
        published[commitToken] = PendingReceipt(receipt)
        receipt
    }

    /** Low-level contract-test seam; production attachment must use SharedContentTransactionStore. */
    internal fun attach(published: BlobPublishReceipt, attachment: ManifestAttachment): Unit = withStateLock {
        val record = attachment.deepImmutableSnapshot().asBlobAttachment()
        validateAtomicAttachmentsLocked(listOf(published), listOf(record))
        consumeAtomicAttachmentsLocked(listOf(published), listOf(record))
    }

    internal fun attach(published: BlobPublishReceipt, attachment: BlobAttachment): Unit =
        attach(published, ManifestAttachment(attachment.owner, attachment.manifest))

    override fun attached(
        owner: ContentManifestOwner,
        manifestId: String,
        contentRevision: Long,
    ): BlobAttachment? = withStateLock {
        attachedLocked(owner, manifestId, contentRevision)
    }

    override fun planRecovery(boundary: RecoveryBoundary): BlobRecoveryPlan = withStateLock {
        val candidates = ArrayList<BlobRecoveryCandidate>()
        val discoveredOrphans = ArrayList<BlobRecoveryCandidate>()
        val protected = LinkedHashMap<String, BlobRecoveryProtection>()
        blobs.entries.forEach { (id, initial) ->
            var stored = initial
            when {
                hasPendingReceiptLocked(stored) -> protected[id] = BlobRecoveryProtection.PENDING_RECEIPT
                isAttachedLocked(stored) -> protected[id] = BlobRecoveryProtection.ATTACHED
                isPinnedLocked(stored) -> protected[id] = BlobRecoveryProtection.ACTIVE_READER
                stored.generation > boundary.safetyCutoffGeneration ->
                    protected[id] = BlobRecoveryProtection.AFTER_CUTOFF
                else -> {
                    // Publication age is not orphan age.  A blob becomes eligible only after
                    // recovery has observed it without a receipt/reference and recorded a
                    // discovery timestamp.  This prevents a crash immediately before the
                    // shared transaction from turning an old publish timestamp into a GC vote.
                    val wasAlreadyDiscovered = stored.lifecycleState == BlobLifecycleState.DISCOVERED_ORPHAN
                    if (!wasAlreadyDiscovered) {
                        val discoveredAt = clock().also {
                            require(it >= 0) { "Blob clock must be non-negative" }
                        }
                        stored = stored.copy(
                            lifecycleState = BlobLifecycleState.DISCOVERED_ORPHAN,
                            discoveredAtEpochMillis = discoveredAt,
                        )
                        durability?.upsert(stored.toDurableBlob(includePayload = false))
                        blobs[id] = stored
                    }
                    val discoveredAt = stored.discoveredAtEpochMillis ?: stored.publishedAtEpochMillis
                    val candidate = BlobRecoveryCandidate(
                        reference = stored.reference,
                        incarnation = stored.incarnation,
                        discoveredAtEpochMillis = discoveredAt,
                        generation = stored.generation,
                    )
                    discoveredOrphans += candidate
                    if (!wasAlreadyDiscovered) {
                        protected[id] = BlobRecoveryProtection.DISCOVERED_ORPHAN
                    } else if (ageMillis(discoveredAt, boundary.nowEpochMillis) < boundary.minimumAgeMillis) {
                        protected[id] = BlobRecoveryProtection.TOO_YOUNG
                    } else {
                        candidates += candidate
                    }
                }
            }
        }
        BlobRecoveryPlan(
            storeInstanceId = storeInstanceId,
            boundary = boundary,
            candidates = candidates.toList(),
            protectedBlobs = protected.toMap(),
            discoveredOrphans = discoveredOrphans.toList(),
        )
    }

    override fun sweepRecovery(plan: BlobRecoveryPlan): Int = withStateLock {
        if (plan.storeInstanceId != storeInstanceId) {
            throw ContentBlobStoreException.RecoveryPlanInvalid("Recovery plan belongs to another store instance")
        }
        val plannedIds = plan.candidates.mapTo(hashSetOf()) { it.reference.blobId }
        if (plannedIds.size != plan.candidates.size) {
            throw ContentBlobStoreException.RecoveryPlanInvalid("Recovery plan contains duplicate blob ids")
        }
        var removed = 0
        plan.candidates.forEach { candidate ->
            val stored = blobs[candidate.reference.blobId] ?: return@forEach
            val exactIncarnation = stored.incarnation == candidate.incarnation
            val exactReference = stored.reference == candidate.reference
            val exactGeneration = stored.generation == candidate.generation
            val exactDiscovery = stored.discoveredAtEpochMillis == candidate.discoveredAtEpochMillis
            val discoveredAt = stored.discoveredAtEpochMillis ?: stored.publishedAtEpochMillis
            val stillSafe = exactIncarnation && exactReference &&
                exactGeneration && exactDiscovery &&
                stored.lifecycleState == BlobLifecycleState.DISCOVERED_ORPHAN &&
                !hasPendingReceiptLocked(stored) && !isAttachedLocked(stored) && !isPinnedLocked(stored) &&
                stored.generation <= plan.boundary.safetyCutoffGeneration &&
                ageMillis(discoveredAt, plan.boundary.nowEpochMillis) >=
                    plan.boundary.minimumAgeMillis
            if (stillSafe) {
                durability?.delete(stored.toDurableBlob(includePayload = false))
                blobs.remove(candidate.reference.blobId)
                check(published.values.none {
                    it.receipt.reference == candidate.reference && it.receipt.incarnation == candidate.incarnation
                }) { "A swept blob retained a pending receipt" }
                removed++
            }
        }
        removed
    }

    override fun deleteUnreferencedNow(refs: List<BlobRef>): Int = withStateLock {
        var removed = 0
        refs.distinctBy(BlobRef::blobId).forEach { reference ->
            val stored = blobs[reference.blobId] ?: return@forEach
            val stillSafe = stored.reference == reference &&
                !hasPendingReceiptLocked(stored) && !isAttachedLocked(stored) && !isPinnedLocked(stored) &&
                stored.generation <= currentGeneration
            if (!stillSafe) return@forEach
            // Best effort, SQL row first and file second inside the durability adapter. A failure
            // leaves at worst an orphan file/row that the age-gated recovery pass collects, so the
            // in-memory entry is dropped either way and a re-download starts from a clean slate.
            val deleted = try {
                durability?.delete(stored.toDurableBlob(includePayload = false))
                true
            } catch (_: Exception) {
                false
            }
            blobs.remove(reference.blobId)
            if (deleted) removed++
        }
        removed
    }

    override fun openRead(reference: BlobRef): BlobReadLease? = withStateLock {
        reference.validate()
        val stored = blobs[reference.blobId] ?: return null
        if (stored.reference != reference) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }
        if (stored.lifecycleState == BlobLifecycleState.DISCOVERED_ORPHAN) {
            throw ContentBlobStoreException.InvalidStage("A discovered orphan cannot acquire a read lease")
        }
        val reader = stored.bytes?.let { payload ->
            if (!isIntact(stored, payload)) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            MemoryReader(stored.reference, stored.incarnation, payload.copyOf())
        } ?: durability?.openRead(stored.toDurableBlob(includePayload = false))?.let { handle ->
            DurableReader(stored.reference, stored.incarnation, handle)
        } ?: throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        reader.also(activeReaders::add)
    }

    public val count: Int get() = withStateLock { blobs.size }

    /** Number of unconsumed local receipts; useful for recovery diagnostics and tests. */
    public val pendingReceiptCount: Int get() = withStateLock { published.size }

    /** Snapshot of the private receipt ledger; scalar copies are intentionally not usable. */
    public fun pendingReceipts(): List<BlobPublishReceipt> = withStateLock { published.values.map { it.receipt } }

    public fun lifecycleState(reference: BlobRef): BlobLifecycleState? = withStateLock {
        blobs[reference.blobId]?.takeIf { it.reference == reference }?.lifecycleState
    }

    /** Models a process death after immutable publish but before the shared transaction. */
    public fun simulateProcessCrashAndRecover() = withStateLock {
        liveStages.clear()
        activeReaders.toList().forEach(StateReader::forceCloseLocked)
        activeReaders.clear()
        published.clear()
        val discoveredAt = clock().also { require(it >= 0) { "Blob clock must be non-negative" } }
        blobs.keys.toList().forEach { id ->
            val stored = requireNotNull(blobs[id])
            blobs[id] = if (isCommittedReferenceLocked(stored.reference, stored.incarnation)) {
                stored.copy(lifecycleState = BlobLifecycleState.AVAILABLE, discoveredAtEpochMillis = null)
            } else {
                stored.copy(
                    lifecycleState = BlobLifecycleState.DISCOVERED_ORPHAN,
                    discoveredAtEpochMillis = discoveredAt,
                )
            }
        }
    }

    internal fun <T> withExclusiveTransaction(block: () -> T): T = withStateLock(block)

    internal fun snapshotForTransactionLocked(): ContentBlobRollback {
        requireLockHeld()
        val blobSnapshot = blobs.mapValuesTo(LinkedHashMap()) { (_, stored) ->
            stored.copy(bytes = stored.bytes?.copyOf())
        }
        val attachmentSnapshot = attachments.mapValuesTo(LinkedHashMap()) { (_, ledger) ->
            ledger.copy(record = copyAttachment(ledger.record), incarnations = ledger.incarnations.toMap())
        }
        val auxiliaryAttachmentSnapshot = auxiliaryAttachments.mapValuesTo(LinkedHashMap()) { (_, ledger) ->
            ledger.copy(record = copyAuxiliaryAttachment(ledger.record), incarnations = ledger.incarnations.toMap())
        }
        val publishedSnapshot = LinkedHashMap(published)
        return ContentBlobRollback {
            requireLockHeld()
            blobs.clear(); blobs.putAll(blobSnapshot)
            attachments.clear(); attachments.putAll(attachmentSnapshot)
            auxiliaryAttachments.clear(); auxiliaryAttachments.putAll(auxiliaryAttachmentSnapshot)
            published.clear(); published.putAll(publishedSnapshot)
        }
    }

    /**
     * Installs durable attachment rows into a freshly opened in-memory participant.  This is
     * intentionally separate from receipt validation: a SQL row is already committed, so its
     * attachment must be hydrated even when the newly published receipt is only a deduplicated
     * capability for an existing blob. Exact references are verified before any ledger entry
     * changes; payload digest verification is deferred until [ContentBlobStore.openRead].
     */
    internal fun hydrateAttachmentsLocked(
        attachmentsToHydrate: List<BlobAttachment>,
        auxiliaryAttachmentsToHydrate: List<AuxiliaryBlobAttachment> = emptyList(),
    ) {
        requireLockHeld()
        val keys = HashSet<String>()
        val manifestSnapshots = attachmentsToHydrate.map { attachment ->
            val snapshot = attachment.deepImmutableSnapshot()
            if (!keys.add(snapshot.attachmentKey)) {
                throw ContentBlobStoreException.InvalidStage("Manifest attachments must be unique")
            }
            val previous = attachments[snapshot.attachmentKey]?.record
            if (previous != null && previous != snapshot) {
                throw ContentBlobStoreException.AttachmentConflict(snapshot.attachmentKey)
            }
            snapshot.blobs.forEach { reference ->
                val stored = blobs[reference.blobId]
                    ?: throw ContentBlobStoreException.InvalidStage(
                        "Blob ${reference.blobId} is not available for attachment hydration",
                    )
                // Payload integrity is intentionally deferred to openRead. Cold-start attachment
                // hydration validates exact immutable metadata without reading every body.
                if (stored.reference != reference) {
                    throw ContentBlobStoreException.CorruptBlob(reference.blobId)
                }
            }
            snapshot
        }
        keys.clear()
        val auxiliarySnapshots = auxiliaryAttachmentsToHydrate.map { attachment ->
            val snapshot = attachment.deepImmutableSnapshot()
            if (!keys.add(snapshot.attachmentKey)) {
                throw ContentBlobStoreException.InvalidStage("Auxiliary attachments must be unique")
            }
            val previous = auxiliaryAttachments[snapshot.attachmentKey]?.record
            if (previous != null && previous != snapshot) {
                throw ContentBlobStoreException.AttachmentConflict(snapshot.attachmentKey)
            }
            snapshot.blobs.forEach { reference ->
                val stored = blobs[reference.blobId]
                    ?: throw ContentBlobStoreException.InvalidStage(
                        "Blob ${reference.blobId} is not available for attachment hydration",
                    )
                if (stored.reference != reference) {
                    throw ContentBlobStoreException.CorruptBlob(reference.blobId)
                }
            }
            snapshot
        }
        // A committed SQL attachment is the durable reference authority. If recovery discovered
        // its blob before attachment hydration, restore it to AVAILABLE before installing the
        // in-memory ledger so GC cannot retain a stale orphan classification.
        (manifestSnapshots.flatMap(BlobAttachment::blobs) +
            auxiliarySnapshots.flatMap(AuxiliaryBlobAttachment::blobs))
            .distinctBy(BlobRef::blobId)
            .forEach { reference ->
            val stored = requireNotNull(blobs[reference.blobId])
            if (stored.lifecycleState == BlobLifecycleState.DISCOVERED_ORPHAN) {
                val restored = stored.copy(
                    lifecycleState = BlobLifecycleState.AVAILABLE,
                    discoveredAtEpochMillis = null,
                )
                durability?.upsert(restored.toDurableBlob(includePayload = false))
                blobs[reference.blobId] = restored
            }
        }
        manifestSnapshots.forEach { snapshot ->
            val incarnations = snapshot.blobs.associate { reference ->
                reference.blobId to requireNotNull(blobs[reference.blobId]).incarnation
            }
            attachments[snapshot.attachmentKey] = AttachmentLedger(
                copyAttachment(snapshot),
                incarnations,
            )
        }
        auxiliarySnapshots.forEach { snapshot ->
            val incarnations = snapshot.blobs.associate { reference ->
                reference.blobId to requireNotNull(blobs[reference.blobId]).incarnation
            }
            auxiliaryAttachments[snapshot.attachmentKey] = AuxiliaryAttachmentLedger(
                copyAuxiliaryAttachment(snapshot),
                incarnations,
            )
        }
    }

    /**
     * Drops the derived in-memory manifest ledger before a verified portable-state replacement.
     * Immutable payloads are retained and become ordinary orphan candidates when the replacement
     * graph no longer references them. The caller must hold a rollback snapshot and replace the
     * matching SQLite rows in the same transaction.
     */
    internal fun clearManifestAttachmentsLocked() {
        requireLockHeld()
        attachments.clear()
    }

    /**
     * Publication-scoped counterpart to [clearManifestAttachmentsLocked]. The enclosing content
     * transaction persists the matching metadata deletion and a removed-reference lifecycle
     * intent before releasing this lock.
     */
    internal fun detachManifestAttachmentsLocked(
        publicationKey: PublicationKey,
    ): List<BlobAttachment> {
        requireLockHeld()
        publicationKey.validate()
        val keys = attachments.entries
            .filter { (_, ledger) -> ledger.record.owner.publicationKey == publicationKey }
            .map { it.key }
        return keys.map { key ->
            copyAttachment(requireNotNull(attachments.remove(key)).record)
        }.sortedBy(BlobAttachment::attachmentKey)
    }

    /** Exact publication-scoped manifest view for semantic replica replay validation. */
    internal fun manifestAttachmentsLocked(
        publicationKey: PublicationKey,
    ): List<BlobAttachment> {
        requireLockHeld()
        publicationKey.validate()
        return attachments.values
            .asSequence()
            .map(AttachmentLedger::record)
            .filter { it.owner.publicationKey == publicationKey }
            .map(::copyAttachment)
            .sortedBy(BlobAttachment::attachmentKey)
            .toList()
    }

    /** Reads an attachment while the participant lock is already held. */
    internal fun attachedLocked(
        owner: ContentManifestOwner,
        manifestId: String,
        contentRevision: Long,
    ): BlobAttachment? {
        requireLockHeld()
        owner.validate()
        require(PublicationKey.isPortableUuid(manifestId)) { "Attachment manifest id must be a portable UUID" }
        require(contentRevision >= 0) { "Attachment content revision must be non-negative" }
        val key = "${owner.scopeKey}/$manifestId/$contentRevision"
        return attachments[key]?.record?.let(::copyAttachment)
    }

    internal fun auxiliaryAttachedLocked(attachmentKey: String): AuxiliaryBlobAttachment? {
        requireLockHeld()
        require(attachmentKey.isNotBlank() && attachmentKey.length <= 4_096) {
            "Auxiliary attachment key must be non-blank and bounded"
        }
        require(attachmentKey.none(Char::isISOControl) && attachmentKey.none(Char::isWhitespace)) {
            "Auxiliary attachment key contains unsafe characters"
        }
        return auxiliaryAttachments[attachmentKey]?.record?.let(::copyAuxiliaryAttachment)
    }

    /** Validate all receipt/attachment relations without mutating the store. */
    internal fun validateAtomicAttachmentsLocked(
        receipts: List<BlobPublishReceipt>,
        attachments: List<BlobAttachment>,
        auxiliaryAttachments: List<AuxiliaryBlobAttachment> = emptyList(),
    ) {
        requireLockHeld()
        if (receipts.isEmpty() && attachments.isEmpty() && auxiliaryAttachments.isEmpty()) return
        val receiptTokens = HashSet<String>()
        val receiptByBlobId = LinkedHashMap<String, BlobPublishReceipt>()
        receipts.forEach { receipt ->
            if (receipt.storeInstanceId != storeInstanceId) {
                throw ContentBlobStoreException.ForeignReceipt(storeInstanceId, receipt.storeInstanceId)
            }
            if (!receiptTokens.add(receipt.commitToken) || receiptByBlobId.put(receipt.reference.blobId, receipt) != null) {
                throw ContentBlobStoreException.InvalidStage("Blob receipts must have unique tokens and blob ids")
            }
            val pending = published[receipt.commitToken]
                ?: throw ContentBlobStoreException.ReceiptConsumed(receipt.commitToken)
            if (pending.receipt !== receipt) {
                throw ContentBlobStoreException.ReceiptMismatch(receipt.commitToken)
            }
            val stored = blobs[receipt.reference.blobId]
                ?: throw ContentBlobStoreException.ReceiptConsumed(receipt.commitToken)
            if (stored.reference != receipt.reference || stored.incarnation != receipt.incarnation ||
                stored.lifecycleState != BlobLifecycleState.AVAILABLE || !isIntact(stored)
            ) {
                throw ContentBlobStoreException.ReceiptMismatch(receipt.commitToken)
            }
        }
        val attachmentKeys = HashSet<String>()
        val attachmentRefs = LinkedHashMap<String, BlobRef>()
        attachments.forEach { attachment ->
            if (!attachmentKeys.add(attachment.attachmentKey)) {
                throw ContentBlobStoreException.InvalidStage("Manifest attachments must be unique")
            }
            attachment.blobs.forEach { reference ->
                // MutableMap.putIfAbsent is JVM-only; keep this common-source check explicit.
                val prior = attachmentRefs[reference.blobId]
                if (prior != null && prior != reference) {
                    throw ContentBlobStoreException.InvalidStage("Shared blob references must match in full")
                }
                if (prior == null) attachmentRefs[reference.blobId] = reference
                val stored = blobs[reference.blobId]
                    ?: throw ContentBlobStoreException.InvalidStage("Blob ${reference.blobId} is not published")
                if (stored.reference != reference || stored.lifecycleState != BlobLifecycleState.AVAILABLE ||
                    !isIntact(stored)
                ) {
                    throw ContentBlobStoreException.CorruptBlob(reference.blobId)
                }
            }
            val previous = this.attachments[attachment.attachmentKey]?.record
            if (previous != null && previous != attachment) {
                throw ContentBlobStoreException.AttachmentConflict(attachment.attachmentKey)
            }
        }
        attachmentKeys.clear()
        auxiliaryAttachments.forEach { attachment ->
            if (!attachmentKeys.add(attachment.attachmentKey)) {
                throw ContentBlobStoreException.InvalidStage("Auxiliary attachments must be unique")
            }
            attachment.blobs.forEach { reference ->
                val prior = attachmentRefs[reference.blobId]
                if (prior != null && prior != reference) {
                    throw ContentBlobStoreException.InvalidStage("Shared blob references must match in full")
                }
                if (prior == null) attachmentRefs[reference.blobId] = reference
                val stored = blobs[reference.blobId]
                    ?: throw ContentBlobStoreException.InvalidStage("Blob ${reference.blobId} is not published")
                if (stored.reference != reference || stored.lifecycleState != BlobLifecycleState.AVAILABLE ||
                    !isIntact(stored)
                ) {
                    throw ContentBlobStoreException.CorruptBlob(reference.blobId)
                }
            }
            val previous = this.auxiliaryAttachments[attachment.attachmentKey]?.record
            if (previous != null && previous != attachment) {
                throw ContentBlobStoreException.AttachmentConflict(attachment.attachmentKey)
            }
        }
        val newRefs = attachmentRefs.filterValues { reference ->
            val stored = requireNotNull(blobs[reference.blobId])
            !isCommittedReferenceLocked(reference, stored.incarnation)
        }
        // Every newly published reference needs a matching capability.  A capability for an
        // already committed, fully verified reference is also valid (for example, a dedupe
        // republish raced a second manifest); it is consumed but never required for sharing.
        val missingReceipts = newRefs.keys - receiptByBlobId.keys
        val receiptsWithoutAttachment = receiptByBlobId.keys - attachmentRefs.keys
        val mismatchedReceipts = receiptByBlobId.any { (id, receipt) ->
            attachmentRefs[id] != receipt.reference
        }
        if (missingReceipts.isNotEmpty() || receiptsWithoutAttachment.isNotEmpty() || mismatchedReceipts) {
            throw ContentBlobStoreException.InvalidStage(
                "Blob receipts must match full attached references; new references require a receipt",
            )
        }
    }

    /**
     * Validates receipt capabilities without requiring a new manifest attachment.  A durable SQL
     * attachment may already exist after reopening the process; a newly published/deduplicated
     * receipt still has to be authenticated and consumed even when there is no new attachment row
     * for it to install.
     */
    internal fun validateReceiptsOnlyLocked(receipts: List<BlobPublishReceipt>) {
        requireLockHeld()
        val tokens = HashSet<String>()
        val blobIds = HashSet<String>()
        receipts.forEach { receipt ->
            if (receipt.storeInstanceId != storeInstanceId) {
                throw ContentBlobStoreException.ForeignReceipt(storeInstanceId, receipt.storeInstanceId)
            }
            if (!tokens.add(receipt.commitToken) || !blobIds.add(receipt.reference.blobId)) {
                throw ContentBlobStoreException.InvalidStage("Blob receipts must have unique tokens and blob ids")
            }
            val pending = published[receipt.commitToken]
                ?: throw ContentBlobStoreException.ReceiptConsumed(receipt.commitToken)
            if (pending.receipt !== receipt) {
                throw ContentBlobStoreException.ReceiptMismatch(receipt.commitToken)
            }
            val stored = blobs[receipt.reference.blobId]
                ?: throw ContentBlobStoreException.ReceiptConsumed(receipt.commitToken)
            if (stored.reference != receipt.reference || stored.incarnation != receipt.incarnation ||
                stored.lifecycleState != BlobLifecycleState.AVAILABLE || !isIntact(stored)
            ) {
                throw ContentBlobStoreException.ReceiptMismatch(receipt.commitToken)
            }
        }
    }

    /** Consume all receipts and install all attachments after [validateAtomicAttachments]. */
    internal fun consumeAtomicAttachmentsLocked(
        receipts: List<BlobPublishReceipt>,
        attachments: List<BlobAttachment>,
        auxiliaryAttachments: List<AuxiliaryBlobAttachment> = emptyList(),
        afterAttachment: (() -> Unit)? = null,
    ) {
        requireLockHeld()
        attachments.forEach { attachment ->
            val incarnations = attachment.blobs.associate { reference ->
                reference.blobId to requireNotNull(blobs[reference.blobId]).incarnation
            }
            this.attachments[attachment.attachmentKey] = AttachmentLedger(copyAttachment(attachment), incarnations)
            afterAttachment?.invoke()
        }
        auxiliaryAttachments.forEach { attachment ->
            val incarnations = attachment.blobs.associate { reference ->
                reference.blobId to requireNotNull(blobs[reference.blobId]).incarnation
            }
            this.auxiliaryAttachments[attachment.attachmentKey] = AuxiliaryAttachmentLedger(
                copyAuxiliaryAttachment(attachment),
                incarnations,
            )
            afterAttachment?.invoke()
        }
        receipts.forEach { published.remove(it.commitToken) }
    }

    /** Consumes already-authenticated receipts when SQL already contains the attachment row. */
    internal fun consumeReceiptsLocked(receipts: List<BlobPublishReceipt>) {
        requireLockHeld()
        validateReceiptsOnlyLocked(receipts)
        receipts.forEach { published.remove(it.commitToken) }
    }

    private fun ageMillis(publishedAt: Long, now: Long): Long {
        if (now <= publishedAt) return 0
        return now - publishedAt
    }

    private fun checkSize(size: Long) {
        if (size < 0 || size > maximumBlobSizeBytes) {
            throw ContentBlobStoreException.SizeLimitExceeded(size, maximumBlobSizeBytes)
        }
    }

    private fun isIntact(stored: StoredBlob): Boolean = stored.bytes?.let { payload ->
        isIntact(stored, payload)
    } ?: durability?.verifyPayload(stored.toDurableBlob(includePayload = false)) == true

    private fun isIntact(stored: StoredBlob, payload: ByteArray): Boolean =
        payload.size.toLong() == stored.reference.byteSize &&
            Sha256.hex(payload) == stored.reference.plaintextDigest

    private fun payloadBytesLocked(stored: StoredBlob): ByteArray {
        requireLockHeld()
        stored.bytes?.let { return it }
        return durability?.readPayload(stored.toDurableBlob(includePayload = false))
            ?: throw ContentBlobStoreException.CorruptBlob(stored.reference.blobId)
    }

    internal fun runStorageMaintenanceSlice(
        request: ContentBlobStorageMaintenanceRequest,
    ): ContentBlobStorageMaintenanceResult = withStateLock {
        durability?.runStorageMaintenanceSlice(request) ?: ContentBlobStorageMaintenanceResult()
    }

    private fun hasPendingReceiptLocked(stored: StoredBlob): Boolean {
        requireLockHeld()
        return published.values.any {
            it.receipt.reference == stored.reference && it.receipt.incarnation == stored.incarnation
        }
    }

    private fun isPinnedLocked(stored: StoredBlob): Boolean {
        requireLockHeld()
        return activeReaders.any {
            it.reference == stored.reference && it.incarnation == stored.incarnation && !it.closedLocked
        }
    }

    private fun isAttachedLocked(stored: StoredBlob): Boolean {
        requireLockHeld()
        return isCommittedReferenceLocked(stored.reference, stored.incarnation)
    }

    private fun isCommittedReferenceLocked(reference: BlobRef, incarnation: Long): Boolean {
        requireLockHeld()
        return attachments.values.any { ledger ->
            ledger.incarnations[reference.blobId] == incarnation && reference in ledger.record.blobs
        } || auxiliaryAttachments.values.any { ledger ->
            ledger.incarnations[reference.blobId] == incarnation && reference in ledger.record.blobs
        }
    }

    private fun copyAttachment(source: BlobAttachment): BlobAttachment =
        source.deepImmutableSnapshot()

    private fun copyAuxiliaryAttachment(source: AuxiliaryBlobAttachment): AuxiliaryBlobAttachment =
        source.deepImmutableSnapshot()

    internal fun deferAttachmentHydration(load: () -> DeferredAttachmentHydration) {
        stateMutex.lock()
        try {
            check(!lockHeld) { "Blob store lock re-entry is forbidden" }
            // A store reopened over the same blob store reads the same durable rows: the latest
            // registration replaces a pending one; an applied ledger is re-validated on load.
            deferredHydration = load
        } finally {
            stateMutex.unlock()
        }
    }

    private inline fun <T> withStateLock(block: () -> T): T {
        stateMutex.lock()
        return try {
            check(!lockHeld) { "Blob store lock re-entry is forbidden" }
            lockHeld = true
            try {
                // Every reader and writer of the ledger takes this lock first, so the deferred
                // durable ledger is in place before anything (including unreferenced-blob
                // collection) can consult it. A failed load stays pending and fails this caller.
                deferredHydration?.let { load ->
                    deferredHydration = null
                    try {
                        val loaded = load()
                        hydrateAttachmentsLocked(loaded.attachments, loaded.auxiliaryAttachments)
                    } catch (failure: Throwable) {
                        deferredHydration = load
                        throw failure
                    }
                }
                block()
            } finally {
                lockHeld = false
            }
        } finally {
            stateMutex.unlock()
        }
    }

    private fun requireLockHeld() {
        check(lockHeld) { "Blob transaction participant requires the store lock" }
    }

    private fun nextBlobId(): String {
        val supplied = blobIdFactory()
        if (supplied.isNotBlank()) return supplied
        val sequence = nextBlobSequence++
        require(sequence <= 0xffffffffffffL) { "In-memory blob id sequence exhausted" }
        return "00000000-0000-4000-8000-${sequence.toString(16).padStart(12, '0')}"
    }

    private fun nextCommitToken(): String {
        val supplied = commitTokenFactory()
        if (supplied.isNotBlank()) return supplied
        return "memory-commit-${nextCommitSequence++}"
    }

    private fun nextIncarnation(): Long {
        val value = nextIncarnationValue
        nextIncarnationValue = checkedAdd(nextIncarnationValue, 1)
        return value
    }

    private data class StoredBlob(
        val reference: BlobRef,
        /** Resident for new/in-memory publishes; null for lazily reopened durable rows. */
        val bytes: ByteArray?,
        val incarnation: Long,
        val generation: Long,
        val publishedAtEpochMillis: Long,
        val lifecycleState: BlobLifecycleState,
        val discoveredAtEpochMillis: Long?,
    )

    private fun StoredBlob.toDurableBlob(includePayload: Boolean): DurableContentBlob = DurableContentBlob(
        reference = reference,
        bytes = if (includePayload) bytes?.copyOf() else null,
        incarnation = incarnation,
        generation = generation,
        publishedAtEpochMillis = publishedAtEpochMillis,
        lifecycleState = lifecycleState,
        discoveredAtEpochMillis = discoveredAtEpochMillis,
    )

    private fun restoreDurableBlobs(restored: List<DurableContentBlob>) = withStateLock {
        check(blobs.isEmpty() && attachments.isEmpty() && auxiliaryAttachments.isEmpty() && published.isEmpty()) {
            "Durable blobs can only be restored into a fresh participant"
        }
        val ids = HashSet<String>()
        var maximumGeneration = 0L
        var maximumIncarnation = 0L
        restored.forEach { durable ->
            val snapshot = durable.immutableCopy()
            if (!ids.add(snapshot.reference.blobId)) {
                throw ContentBlobStoreException.CorruptBlob(snapshot.reference.blobId)
            }
            blobs[snapshot.reference.blobId] = StoredBlob(
                reference = snapshot.reference,
                bytes = snapshot.bytes?.copyOf(),
                incarnation = snapshot.incarnation,
                generation = snapshot.generation,
                publishedAtEpochMillis = snapshot.publishedAtEpochMillis,
                lifecycleState = snapshot.lifecycleState,
                discoveredAtEpochMillis = snapshot.discoveredAtEpochMillis,
            )
            maximumGeneration = maxOf(maximumGeneration, snapshot.generation)
            maximumIncarnation = maxOf(maximumIncarnation, snapshot.incarnation)
        }
        currentGeneration = maximumGeneration
        nextIncarnationValue = checkedAdd(maximumIncarnation, 1L)
    }

    private data class PendingReceipt(val receipt: BlobPublishReceipt)

    private data class AttachmentLedger(
        val record: BlobAttachment,
        val incarnations: Map<String, Long>,
    )

    private data class AuxiliaryAttachmentLedger(
        val record: AuxiliaryBlobAttachment,
        val incarnations: Map<String, Long>,
    )

    private inner class MemoryStage(
        override val expectedSizeBytes: Long?,
        private val mediaType: String,
    ) : ContentBlobStage {
        private val chunks = ArrayList<ByteArray>()
        override var bytesWritten: Long = 0
            private set
        override var isSealed: Boolean = false
            private set
        var published: Boolean = false

        override fun append(chunk: ByteArray): Unit = withStateLock {
            if (isSealed || published) throw ContentBlobStoreException.InvalidStage("Stage is closed")
            if (chunk.isEmpty()) return
            val nextSize = try {
                checkedAdd(bytesWritten, chunk.size.toLong())
            } catch (_: ArithmeticException) {
                throw ContentBlobStoreException.SizeLimitExceeded(Long.MAX_VALUE, maximumBlobSizeBytes)
            }
            checkSize(nextSize)
            if (expectedSizeBytes != null && nextSize > expectedSizeBytes) {
                throw ContentBlobStoreException.SizeMismatch(expectedSizeBytes, nextSize)
            }
            chunks += chunk.copyOf()
            bytesWritten = nextSize
        }

        override fun seal(expected: BlobRef?): PendingBlob = withStateLock {
            if (isSealed || published) throw ContentBlobStoreException.InvalidStage("Stage is already closed")
            if (expectedSizeBytes != null && bytesWritten != expectedSizeBytes) {
                throw ContentBlobStoreException.SizeMismatch(expectedSizeBytes, bytesWritten)
            }
            val bytes = ByteArray(bytesWritten.toInt())
            var offset = 0
            chunks.forEach { chunk -> chunk.copyInto(bytes, offset); offset += chunk.size }
            val digest = Sha256.hex(bytes)
            val reference = expected ?: BlobRef(
                blobId = nextBlobId(),
                schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
                digestAlgorithm = BlobRef.SHA_256,
                plaintextDigest = digest,
                byteSize = bytesWritten,
                mediaType = mediaType,
            )
            reference.validate()
            if (reference.byteSize != bytesWritten) {
                throw ContentBlobStoreException.SizeMismatch(reference.byteSize, bytesWritten)
            }
            if (reference.plaintextDigest != digest) {
                throw ContentBlobStoreException.DigestMismatch(reference.plaintextDigest, digest)
            }
            if (reference.mediaType != mediaType) {
                throw ContentBlobStoreException.InvalidStage("Blob media type does not match its stage")
            }
            isSealed = true
            MemoryPending(this, reference, bytes)
        }

        override fun abort(): Unit = withStateLock {
            if (!published) {
                chunks.clear()
                liveStages.remove(this)
                isSealed = true
            }
        }
    }

    private data class MemoryPending(
        val stage: MemoryStage,
        override val reference: BlobRef,
        val bytes: ByteArray,
    ) : PendingBlob

    private inner class DurableStage(
        private val handle: DurableContentBlobStageHandle,
    ) : ContentBlobStage {
        private var published = false
        override val expectedSizeBytes: Long? get() = handle.expectedSizeBytes
        override val bytesWritten: Long get() = handle.bytesWritten
        override val isSealed: Boolean get() = handle.isSealed

        override fun append(chunk: ByteArray): Unit = withStateLock {
            if (published || handle.isSealed) throw ContentBlobStoreException.InvalidStage("Stage is closed")
            handle.append(chunk)
        }

        override fun seal(expected: BlobRef?): PendingBlob = withStateLock {
            if (published || handle.isSealed) {
                throw ContentBlobStoreException.InvalidStage("Stage is already closed")
            }
            DurablePending(this, handle.seal(expected, ::nextBlobId))
        }

        override fun abort(): Unit = withStateLock {
            if (!published) {
                handle.abort()
                liveStages.remove(this)
            }
        }

        fun markPublishedLocked() {
            requireLockHeld()
            published = true
        }
    }

    private data class DurablePending(
        val stage: DurableStage,
        val handle: DurableContentBlobPendingHandle,
    ) : PendingBlob {
        override val reference: BlobRef get() = handle.reference
    }

    private interface StateReader : BlobReadLease {
        val incarnation: Long
        val closedLocked: Boolean
        fun forceCloseLocked()
    }

    private inner class MemoryReader(
        override val reference: BlobRef,
        override val incarnation: Long,
        private val bytes: ByteArray,
    ) : StateReader {
        private var offset = 0
        private var closed = false

        override val closedLocked: Boolean get() = closed
        override val isPinned: Boolean get() = withStateLock { !closed }
        override val isClosed: Boolean get() = withStateLock { closed }

        override fun readChunk(maxBytes: Int): ByteArray? = withStateLock {
            if (closed) throw ContentBlobStoreException.InvalidStage("Reader is closed")
            require(maxBytes > 0) { "Read chunk size must be positive" }
            if (offset >= bytes.size) return@withStateLock null
            val remaining = bytes.size - offset
            val end = if (maxBytes >= remaining) bytes.size else offset + maxBytes
            bytes.copyOfRange(offset, end).also { offset = end }
        }

        override fun pin(): BlobReadLease = withStateLock { this }

        override fun close(): Unit = withStateLock {
            if (!closed) {
                closed = true
                activeReaders.remove(this)
            }
        }

        override fun forceCloseLocked() {
            requireLockHeld()
            closed = true
        }
    }

    private inner class DurableReader(
        override val reference: BlobRef,
        override val incarnation: Long,
        private val handle: DurableContentBlobReadHandle,
    ) : StateReader {
        private var closed = false
        override val closedLocked: Boolean get() = closed
        override val isPinned: Boolean get() = withStateLock { !closed }
        override val isClosed: Boolean get() = withStateLock { closed }

        override fun readChunk(maxBytes: Int): ByteArray? = withStateLock {
            if (closed) throw ContentBlobStoreException.InvalidStage("Reader is closed")
            require(maxBytes > 0) { "Read chunk size must be positive" }
            handle.readChunk(maxBytes)
        }

        override fun pin(): BlobReadLease = withStateLock { this }

        override fun close(): Unit = withStateLock {
            if (!closed) {
                closed = true
                handle.close()
                activeReaders.remove(this)
            }
        }

        override fun forceCloseLocked() {
            requireLockHeld()
            if (!closed) {
                closed = true
                handle.close()
            }
        }
    }

    public companion object {
        public const val DEFAULT_MAXIMUM_BLOB_SIZE_BYTES: Long = 64L * 1024L * 1024L
    }
}
