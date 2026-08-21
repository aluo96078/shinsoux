package dev.shinsou.kmp.content

import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock

/** Fail-closed errors raised by the blob boundary. */
public sealed class ContentBlobStoreException(message: String) : IllegalArgumentException(message) {
    public class SizeLimitExceeded(public val actual: Long, public val maximum: Long) :
        ContentBlobStoreException("Blob size $actual exceeds maximum $maximum")

    public class DigestMismatch(public val expected: String, public val actual: String) :
        ContentBlobStoreException("Blob digest mismatch: expected $expected, got $actual")

    public class SizeMismatch(public val expected: Long, public val actual: Long) :
        ContentBlobStoreException("Blob size mismatch: expected $expected, got $actual")

    public class InvalidStage(message: String) : ContentBlobStoreException(message)

    public class CorruptBlob(public val blobId: String) :
        ContentBlobStoreException("Stored blob $blobId failed integrity verification")

    public class AttachmentConflict(public val ownerId: String) :
        ContentBlobStoreException("Immutable attachment already exists for owner $ownerId")

    public class ReceiptConsumed(public val commitToken: String) :
        ContentBlobStoreException("Blob publish receipt $commitToken has already been consumed")

    public class ForeignReceipt(public val expectedStoreInstanceId: String, public val actualStoreInstanceId: String) :
        ContentBlobStoreException("Blob publish receipt belongs to another store instance")

    public class ReceiptMismatch(public val commitToken: String) :
        ContentBlobStoreException("Blob publish receipt $commitToken does not match the published blob")

    public class RecoveryPlanInvalid(message: String) : ContentBlobStoreException(message)
}

/** A bounded streaming write that is not visible until [seal] and store publication. */
public interface ContentBlobStage {
    public val expectedSizeBytes: Long?
    public val bytesWritten: Long
    public val isSealed: Boolean
    public fun append(chunk: ByteArray)
    public fun seal(expected: BlobRef? = null): PendingBlob
    public fun abort()
}

/** Opaque sealed candidate accepted by [ContentBlobStore.publish]. */
public interface PendingBlob {
    public val reference: BlobRef
}

/**
 * A local-only, one-use receipt for a published blob.
 *
 * This deliberately is not `@Serializable` and is not a data class.  Store implementations
 * must authenticate the object identity as well as [storeInstanceId]; copying its scalar fields
 * must never manufacture a second usable receipt.  The token is only an in-process transaction
 * capability and must not be placed in a manifest, snapshot, sync event, or backup.
 */
public class BlobPublishReceipt internal constructor(
    public val storeInstanceId: String,
    public val commitToken: String,
    public val reference: BlobRef,
    public val incarnation: Long,
    public val generation: Long,
    public val publishedAtEpochMillis: Long,
) {
    init {
        require(storeInstanceId.isNotBlank()) { "Blob store instance id must not be blank" }
        require(commitToken.isNotBlank()) { "Blob commit token must not be blank" }
        require(incarnation > 0) { "Blob incarnation must be positive" }
        require(generation >= 0) { "Blob commit generation must be non-negative" }
        require(publishedAtEpochMillis >= 0) { "Blob publication timestamp must be non-negative" }
        reference.validate()
    }

    override fun toString(): String =
        "BlobPublishReceipt(storeInstanceId=$storeInstanceId, commitToken=$commitToken, " +
            "reference=${reference.blobId}, generation=$generation)"
}

/** Source compatibility for the M0 draft API; this remains local-only and non-serializable. */
public typealias PublishedBlob = BlobPublishReceipt

/**
 * A read lease pins an immutable blob until it is closed.  [close] and [pin] are idempotent so
 * cancellation/finally paths can safely race without releasing the same pin twice.
 */
public interface BlobReadLease {
    public val reference: BlobRef
    public val isPinned: Boolean
    public val isClosed: Boolean
    /** Returns the next bounded chunk, or null after EOF. */
    public fun readChunk(maxBytes: Int = DEFAULT_READ_CHUNK_BYTES): ByteArray?
    public fun pin(): BlobReadLease
    public fun close()

    public companion object {
        public const val DEFAULT_READ_CHUNK_BYTES: Int = 64 * 1024
    }
}

/** Compatibility name retained for callers of the first M0 draft. */
public typealias ContentBlobReader = BlobReadLease

/** Portable, fully scoped manifest owner. */
@kotlinx.serialization.Serializable
public data class ContentManifestOwner(
    val publicationKey: PublicationKey,
    val acquisitionId: String,
    val unitKey: UnitKey,
) {
    init { validate() }

    public fun validate() {
        publicationKey.validate()
        unitKey.validate()
        require(unitKey.publicationKey == publicationKey) {
            "Manifest unit must belong to the declared publication"
        }
        require(PublicationKey.isPortableUuid(acquisitionId)) {
            "Manifest acquisition id must be a portable UUID"
        }
    }

    public val scopeKey: String
        get() = "${publicationKey.value}/$acquisitionId/${unitKey.value}"

    /** The acquisition id is a UUID in the persisted publication model. */
    public val acquisitionUuid: String get() = acquisitionId
}

/** Transactional manifest-to-blob attachment/ledger record. */
@kotlinx.serialization.Serializable
public data class BlobAttachment(
    val owner: ContentManifestOwner,
    val manifest: ContentManifest,
) {
    public val manifestId: String get() = manifest.manifestId
    public val contentRevision: Long get() = manifest.contentRevision
    public val blobs: List<BlobRef> get() = manifest.referencedBlobs

    init {
        owner.validate()
        manifest.validate()
        require(blobs.map(BlobRef::blobId).distinct().size == blobs.size) {
            "Attachment blob ids must be unique"
        }
        blobs.forEach(BlobRef::validate)
    }

    public val attachmentKey: String
        get() = "${owner.scopeKey}/$manifestId/$contentRevision"

    internal fun deepImmutableSnapshot(): BlobAttachment =
        BlobAttachment(owner, manifest.deepImmutableSnapshot())
}

/**
 * A manifest attachment derives its blob set from the immutable content manifest.  There is no
 * second caller-supplied list that could silently omit an EPUB stylesheet/font or text resource.
 */
@kotlinx.serialization.Serializable
public data class ManifestAttachment(
    val owner: ContentManifestOwner,
    val manifest: ContentManifest,
) {
    init {
        owner.validate()
        manifest.validate()
    }

    public val manifestId: String get() = manifest.manifestId
    public val contentRevision: Long get() = manifest.contentRevision
    public val blobs: List<BlobRef> get() = manifest.referencedBlobs
    public val attachmentKey: String get() = "${owner.scopeKey}/$manifestId/$contentRevision"

    public fun asBlobAttachment(): BlobAttachment =
        BlobAttachment(owner, manifest.deepImmutableSnapshot())

    internal fun deepImmutableSnapshot(): ManifestAttachment =
        ManifestAttachment(owner, manifest.deepImmutableSnapshot())
}

/** Explicit recovery input.  There is intentionally no default safety cutoff. */
public data class RecoveryBoundary(
    val safetyCutoffGeneration: Long,
    val nowEpochMillis: Long,
    val minimumAgeMillis: Long,
) {
    init {
        require(safetyCutoffGeneration >= 0) { "Recovery generation cutoff must be non-negative" }
        require(nowEpochMillis >= 0) { "Recovery timestamp must be non-negative" }
        require(minimumAgeMillis >= 0) { "Recovery minimum age must be non-negative" }
    }

    public val committedGeneration: Long get() = safetyCutoffGeneration
    public val minAgeMillis: Long get() = minimumAgeMillis
}

public enum class BlobRecoveryProtection {
    PENDING_RECEIPT,
    ACTIVE_READER,
    /** The blob has been discovered as an orphan but has not reached the age boundary yet. */
    DISCOVERED_ORPHAN,
    TOO_YOUNG,
    ATTACHED,
    AFTER_CUTOFF,
}

public enum class BlobLifecycleState {
    AVAILABLE,
    DISCOVERED_ORPHAN,
}

public data class BlobRecoveryCandidate(
    val reference: BlobRef,
    val incarnation: Long,
    /** Discovery time is distinct from publication time for crash-recovered orphans. */
    val discoveredAtEpochMillis: Long = 0,
    /** Publication generation prevents a plan from deleting a later state of the same id. */
    val generation: Long = 0,
) {
    init {
        reference.validate()
        require(incarnation > 0) { "Recovery candidate incarnation must be positive" }
        require(discoveredAtEpochMillis >= 0) { "Orphan discovery timestamp must be non-negative" }
        require(generation >= 0) { "Recovery candidate generation must be non-negative" }
    }
}

public data class BlobRecoveryPlan(
    val storeInstanceId: String,
    val boundary: RecoveryBoundary,
    val candidates: List<BlobRecoveryCandidate>,
    val protectedBlobs: Map<String, BlobRecoveryProtection>,
    /** Discovered but not-yet-aged candidates are surfaced separately from sweep candidates. */
    val discoveredOrphans: List<BlobRecoveryCandidate> = emptyList(),
) {
    public val candidateReferences: List<BlobRef> get() = candidates.map(BlobRecoveryCandidate::reference)
    public val agedDiscoveredOrphans: List<BlobRecoveryCandidate> get() = candidates
}

/**
 * Blob storage boundary. Production implementations may back this with files/object storage and
 * a shared-SQLite transaction participant. The stage/publish/attach split gives them a durable
 * commit point; in-memory tests model the same ordering and orphan safety rules.
 */
public interface ContentBlobStore {
    public val maximumBlobSizeBytes: Long
    public val currentGeneration: Long

    public fun beginStage(
        expectedSizeBytes: Long? = null,
        mediaType: String = BlobRef.DEFAULT_MEDIA_TYPE,
    ): ContentBlobStage

    public fun publish(candidate: PendingBlob): BlobPublishReceipt

    public fun attached(
        owner: ContentManifestOwner,
        manifestId: String,
        contentRevision: Long,
    ): BlobAttachment?

    public fun attached(owner: ContentManifestOwner, manifest: ContentManifest): BlobAttachment? {
        manifest.validate()
        return attached(owner, manifest.manifestId, manifest.contentRevision)
    }

    /** Builds an explicit recovery plan; no implicit cutoff is ever used. */
    public fun planRecovery(boundary: RecoveryBoundary): BlobRecoveryPlan

    /** Applies a previously planned sweep, re-checking pins and pending receipts at sweep time. */
    public fun sweepRecovery(plan: BlobRecoveryPlan): Int

    /** Lists unreferenced blobs selected by an explicit recovery boundary. */
    public fun recoverOrphans(boundary: RecoveryBoundary): List<BlobRef> =
        planRecovery(boundary).candidateReferences

    /** Deletes only safe orphans selected by an explicit recovery boundary. */
    public fun garbageCollectOrphans(boundary: RecoveryBoundary): Int =
        sweepRecovery(planRecovery(boundary))

    /**
     * Legacy no-argument calls have no safe recovery boundary.  They intentionally do nothing;
     * callers must opt into [RecoveryBoundary] before deleting data.
     */
    @Deprecated("Supply an explicit RecoveryBoundary")
    public fun recoverOrphans(): List<BlobRef> = emptyList()

    /** Compatibility overload for the draft M0 API; age/pending policy is explicit internally. */
    @Deprecated("Supply an explicit RecoveryBoundary")
    public fun recoverOrphans(safetyCutoffGeneration: Long): List<BlobRef> =
        recoverOrphans(
            RecoveryBoundary(
                safetyCutoffGeneration = safetyCutoffGeneration,
                nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
                minimumAgeMillis = 0,
            ),
        )

    @Deprecated("Supply an explicit RecoveryBoundary")
    public fun garbageCollectOrphans(): Int = 0

    @Deprecated("Supply an explicit RecoveryBoundary")
    public fun garbageCollectOrphans(safetyCutoffGeneration: Long): Int =
        garbageCollectOrphans(
            RecoveryBoundary(
                safetyCutoffGeneration = safetyCutoffGeneration,
                nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
                minimumAgeMillis = 0,
            ),
        )

    public fun openRead(reference: BlobRef): BlobReadLease?

    public fun read(reference: BlobRef): ByteArray? {
        reference.validate()
        // Check the representable result bound before consulting the implementation's size
        // policy.  A caller must never cause an implementation to allocate a ByteArray for a
        // reference that cannot fit in one, even when a backend advertises a larger limit.
        if (reference.byteSize > Int.MAX_VALUE.toLong()) {
            throw ContentBlobStoreException.SizeLimitExceeded(reference.byteSize, Int.MAX_VALUE.toLong())
        }
        if (reference.byteSize > maximumBlobSizeBytes) {
            throw ContentBlobStoreException.SizeLimitExceeded(reference.byteSize, maximumBlobSizeBytes)
        }
        val reader = openRead(reference) ?: return null
        return try {
            val chunks = ArrayList<ByteArray>()
            var total = 0L
            while (true) {
                val chunk = reader.readChunk() ?: break
                require(chunk.isNotEmpty()) { "Blob reader returned an empty chunk before EOF" }
                total = checkedAdd(total, chunk.size.toLong())
                if (total > maximumBlobSizeBytes) {
                    throw ContentBlobStoreException.SizeLimitExceeded(total, maximumBlobSizeBytes)
                }
                if (total > Int.MAX_VALUE.toLong()) {
                    throw ContentBlobStoreException.SizeLimitExceeded(total, Int.MAX_VALUE.toLong())
                }
                chunks += chunk.copyOf()
            }
            ByteArray(total.toInt()).also { output ->
                var offset = 0
                chunks.forEach { chunk -> chunk.copyInto(output, offset); offset += chunk.size }
            }
        } finally {
            reader.close()
        }
    }

    public fun contains(reference: BlobRef): Boolean {
        val reader = openRead(reference) ?: return false
        reader.close()
        return true
    }
    public fun verify(reference: BlobRef): Boolean = read(reference)?.let {
        it.size.toLong() == reference.byteSize && Sha256.hex(it) == reference.plaintextDigest
    } ?: false

    /** Small-body helper; it still follows stage -> verify -> publish. */
    public fun put(bytes: ByteArray, mediaType: String = BlobRef.DEFAULT_MEDIA_TYPE): PublishedBlob {
        val stage = beginStage(bytes.size.toLong(), mediaType)
        return try {
            stage.append(bytes)
            publish(stage.seal())
        } catch (error: Throwable) {
            stage.abort()
            throw error
        }
    }

    public fun put(reference: BlobRef, bytes: ByteArray): PublishedBlob {
        val stage = beginStage(reference.byteSize, reference.mediaType)
        return try {
            stage.append(bytes)
            publish(stage.seal(reference))
        } catch (error: Throwable) {
            stage.abort()
            throw error
        }
    }
}

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
    private val stateMutex = Mutex()
    private var lockHeld = false
    private val blobs = LinkedHashMap<String, StoredBlob>()
    private val attachments = LinkedHashMap<String, AttachmentLedger>()
    private val published = LinkedHashMap<String, PendingReceipt>()
    private val activeReaders = LinkedHashSet<MemoryReader>()
    private val liveStages = LinkedHashSet<MemoryStage>()
    private var nextBlobSequence = 1L
    private var nextCommitSequence = 1L
    private var nextIncarnationValue = 1L
    override var currentGeneration: Long = 0
        private set

    init {
        require(maximumBlobSizeBytes > 0) { "Maximum blob size must be positive" }
        require(maximumBlobSizeBytes <= Int.MAX_VALUE.toLong()) {
            "In-memory maximum blob size must fit a ByteArray"
        }
    }

    override fun beginStage(expectedSizeBytes: Long?, mediaType: String): ContentBlobStage = withStateLock {
        require(expectedSizeBytes == null || expectedSizeBytes >= 0) {
            "Expected blob size must be non-negative"
        }
        expectedSizeBytes?.let(::checkSize)
        require(mediaType.isNotBlank() && mediaType.length <= 256 && mediaType.none(Char::isISOControl)) {
            "Blob media type must be printable and bounded"
        }
        MemoryStage(expectedSizeBytes, mediaType).also(liveStages::add)
    }

    override fun publish(candidate: PendingBlob): BlobPublishReceipt = withStateLock {
        val pending = candidate as? MemoryPending
            ?: throw ContentBlobStoreException.InvalidStage("Candidate belongs to another blob store")
        if (!pending.stage.isSealed || pending.stage !in liveStages) {
            throw ContentBlobStoreException.InvalidStage("Blob candidate is not a live sealed stage")
        }
        val commitToken = nextCommitToken()
        require(published[commitToken] == null) { "Blob commit token factory returned a duplicate token" }
        val nextGeneration = checkedAdd(currentGeneration, 1)
        val publishedAt = clock().also { require(it >= 0) { "Blob clock must be non-negative" } }
        val existing = blobs[pending.reference.blobId]
        if (existing != null) {
            if (existing.lifecycleState == BlobLifecycleState.DISCOVERED_ORPHAN) {
                throw ContentBlobStoreException.InvalidStage("A discovered orphan cannot be republished before sweep")
            }
            if (published.values.any {
                    it.receipt.reference.blobId == pending.reference.blobId &&
                        it.receipt.incarnation == existing.incarnation
                }
            ) {
                throw ContentBlobStoreException.InvalidStage("Blob already has an unconsumed publish receipt")
            }
            if (existing.reference != pending.reference || !existing.bytes.contentEquals(pending.bytes)) {
                throw ContentBlobStoreException.CorruptBlob(pending.reference.blobId)
            }
        } else {
            checkSize(pending.bytes.size.toLong())
        }
        // All potentially throwing checks happen before visibility changes.  This is important
        // for a failed token factory or clock: `put` must never leave a blob without its receipt.
        val stored = if (existing == null) {
            StoredBlob(
                reference = pending.reference,
                bytes = pending.bytes.copyOf(),
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
                generation = nextGeneration,
                publishedAtEpochMillis = publishedAt,
                lifecycleState = BlobLifecycleState.AVAILABLE,
                discoveredAtEpochMillis = null,
            )
        }
        blobs[pending.reference.blobId] = stored
        liveStages.remove(pending.stage)
        pending.stage.published = true
        currentGeneration = nextGeneration
        val receipt = BlobPublishReceipt(
            storeInstanceId = storeInstanceId,
            commitToken = commitToken,
            reference = pending.reference,
            incarnation = stored.incarnation,
            generation = nextGeneration,
            publishedAtEpochMillis = publishedAt,
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
                blobs.remove(candidate.reference.blobId)
                check(published.values.none {
                    it.receipt.reference == candidate.reference && it.receipt.incarnation == candidate.incarnation
                }) { "A swept blob retained a pending receipt" }
                removed++
            }
        }
        removed
    }

    override fun openRead(reference: BlobRef): BlobReadLease? = withStateLock {
        reference.validate()
        val stored = blobs[reference.blobId] ?: return null
        if (stored.reference != reference || !isIntact(stored)) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }
        if (stored.lifecycleState == BlobLifecycleState.DISCOVERED_ORPHAN) {
            throw ContentBlobStoreException.InvalidStage("A discovered orphan cannot acquire a read lease")
        }
        MemoryReader(stored.reference, stored.incarnation, stored.bytes.copyOf()).also(activeReaders::add)
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
        activeReaders.toList().forEach(MemoryReader::forceCloseLocked)
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

    internal fun snapshotForTransactionLocked(): InMemoryBlobRollback {
        requireLockHeld()
        val blobSnapshot = blobs.mapValuesTo(LinkedHashMap()) { (_, stored) -> stored.copy(bytes = stored.bytes.copyOf()) }
        val attachmentSnapshot = attachments.mapValuesTo(LinkedHashMap()) { (_, ledger) ->
            ledger.copy(record = copyAttachment(ledger.record), incarnations = ledger.incarnations.toMap())
        }
        val publishedSnapshot = LinkedHashMap(published)
        return InMemoryBlobRollback {
            requireLockHeld()
            blobs.clear(); blobs.putAll(blobSnapshot)
            attachments.clear(); attachments.putAll(attachmentSnapshot)
            published.clear(); published.putAll(publishedSnapshot)
        }
    }

    /**
     * Installs durable attachment rows into a freshly opened in-memory participant.  This is
     * intentionally separate from receipt validation: a SQL row is already committed, so its
     * attachment must be hydrated even when the newly published receipt is only a deduplicated
     * capability for an existing blob.  All blobs are verified before any ledger entry changes.
     */
    internal fun hydrateAttachmentsLocked(attachmentsToHydrate: List<BlobAttachment>) {
        requireLockHeld()
        val keys = HashSet<String>()
        val snapshots = attachmentsToHydrate.map { attachment ->
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
                if (stored.reference != reference || stored.lifecycleState != BlobLifecycleState.AVAILABLE ||
                    !isIntact(stored)
                ) {
                    throw ContentBlobStoreException.CorruptBlob(reference.blobId)
                }
            }
            snapshot
        }
        snapshots.forEach { snapshot ->
            val incarnations = snapshot.blobs.associate { reference ->
                reference.blobId to requireNotNull(blobs[reference.blobId]).incarnation
            }
            attachments[snapshot.attachmentKey] = AttachmentLedger(
                copyAttachment(snapshot),
                incarnations,
            )
        }
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

    /** Validate all receipt/attachment relations without mutating the store. */
    internal fun validateAtomicAttachmentsLocked(
        receipts: List<BlobPublishReceipt>,
        attachments: List<BlobAttachment>,
    ) {
        requireLockHeld()
        if (receipts.isEmpty() && attachments.isEmpty()) return
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

    private fun isIntact(stored: StoredBlob): Boolean =
        stored.bytes.size.toLong() == stored.reference.byteSize &&
            Sha256.hex(stored.bytes) == stored.reference.plaintextDigest

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
        }
    }

    private fun copyAttachment(source: BlobAttachment): BlobAttachment =
        source.deepImmutableSnapshot()

    private inline fun <T> withStateLock(block: () -> T): T {
        if (!stateMutex.tryLock()) {
            throw ContentBlobStoreException.InvalidStage("Concurrent blob-store access must retry")
        }
        check(!lockHeld) { "Blob store lock re-entry is forbidden" }
        lockHeld = true
        return try {
            block()
        } finally {
            lockHeld = false
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
        val bytes: ByteArray,
        val incarnation: Long,
        val generation: Long,
        val publishedAtEpochMillis: Long,
        val lifecycleState: BlobLifecycleState,
        val discoveredAtEpochMillis: Long?,
    )

    private data class PendingReceipt(val receipt: BlobPublishReceipt)

    private data class AttachmentLedger(
        val record: BlobAttachment,
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

    private inner class MemoryReader(
        override val reference: BlobRef,
        val incarnation: Long,
        private val bytes: ByteArray,
    ) : BlobReadLease {
        private var offset = 0
        private var closed = false

        val closedLocked: Boolean get() = closed
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

        fun forceCloseLocked() {
            requireLockHeld()
            closed = true
        }
    }

    public companion object {
        public const val DEFAULT_MAXIMUM_BLOB_SIZE_BYTES: Long = 64L * 1024L * 1024L
    }
}

internal class InMemoryBlobRollback internal constructor(
    private val rollbackAction: () -> Unit,
) {
    internal fun rollback() = rollbackAction()
}

/** Common-source checked addition; java.lang.Math is unavailable to Kotlin/Native. */
private fun checkedAdd(first: Long, second: Long): Long {
    require(first >= 0 && second >= 0) { "Checked addition only accepts non-negative values" }
    if (second > Long.MAX_VALUE - first) throw ArithmeticException("Long overflow")
    return first + second
}
