package dev.shinsou.kmp.content

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
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

    /**
     * Claims an exact, already durable blob for a new local metadata transaction.
     *
     * This is the restart boundary for background download/materialization workers: receipts are
     * intentionally process-local, while a verified payload may already have reached durable
     * storage before the process stopped. Implementations must verify only [reference]'s exact
     * payload, must not scan or materialize unrelated bodies, and must return the same object while
     * an unconsumed receipt for that incarnation is still pending. A missing blob returns null.
     */
    public fun claimExistingVerified(reference: BlobRef): BlobPublishReceipt?

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

/**
 * Internal participant used by a metadata store while its SQLite transaction is open.
 *
 * Receipt identity remains process-local, while a restart-safe implementation reconstructs
 * immutable blob metadata before SQL attachment rows are hydrated and fetches exact payloads only
 * through [ContentBlobStore.openRead]. Keeping this contract separate from the public blob API
 * prevents callers from attaching content outside [SharedContentTransactionStore].
 */
internal interface ContentBlobTransactionParticipant {
    /** True only when lifecycle metadata and on-demand payload access survive process restart. */
    val isRestartSafe: Boolean

    fun <T> withExclusiveTransaction(block: () -> T): T
    fun snapshotForTransactionLocked(): ContentBlobRollback
    fun hydrateAttachmentsLocked(
        attachmentsToHydrate: List<BlobAttachment>,
        auxiliaryAttachmentsToHydrate: List<AuxiliaryBlobAttachment> = emptyList(),
    )
    /** Clears only the portable manifest-reference ledger inside an enclosing rollback boundary. */
    fun clearManifestAttachmentsLocked()
    /** Detaches exactly one publication graph and returns its former immutable reference ledger. */
    fun detachManifestAttachmentsLocked(publicationKey: PublicationKey): List<BlobAttachment>
    fun attachedLocked(
        owner: ContentManifestOwner,
        manifestId: String,
        contentRevision: Long,
    ): BlobAttachment?
    fun auxiliaryAttachedLocked(attachmentKey: String): AuxiliaryBlobAttachment?
    fun validateAtomicAttachmentsLocked(
        receipts: List<BlobPublishReceipt>,
        attachments: List<BlobAttachment>,
        auxiliaryAttachments: List<AuxiliaryBlobAttachment> = emptyList(),
    )
    fun validateReceiptsOnlyLocked(receipts: List<BlobPublishReceipt>)
    fun consumeAtomicAttachmentsLocked(
        receipts: List<BlobPublishReceipt>,
        attachments: List<BlobAttachment>,
        auxiliaryAttachments: List<AuxiliaryBlobAttachment> = emptyList(),
        afterAttachment: (() -> Unit)? = null,
    )
    fun consumeReceiptsLocked(receipts: List<BlobPublishReceipt>)
}

internal fun interface ContentBlobRollback {
    fun rollback()
}

/** Durable representation used only between the state machine and its platform persistence. */
internal data class DurableContentBlob(
    val reference: BlobRef,
    /** Null for cold-start metadata snapshots; payload bytes are fetched through durability on demand. */
    val bytes: ByteArray?,
    val incarnation: Long,
    val generation: Long,
    val publishedAtEpochMillis: Long,
    val lifecycleState: BlobLifecycleState,
    val discoveredAtEpochMillis: Long?,
) {
    init {
        reference.validate()
        bytes?.let {
            require(it.size.toLong() == reference.byteSize) { "Durable blob size does not match its reference" }
        }
        require(incarnation > 0) { "Durable blob incarnation must be positive" }
        require(generation >= 0) { "Durable blob generation must be non-negative" }
        require(publishedAtEpochMillis >= 0) { "Durable blob publication timestamp must be non-negative" }
        when (lifecycleState) {
            BlobLifecycleState.AVAILABLE -> require(discoveredAtEpochMillis == null) {
                "Available durable blob cannot carry orphan discovery time"
            }
            BlobLifecycleState.DISCOVERED_ORPHAN -> require(discoveredAtEpochMillis != null) {
                "Discovered durable orphan must carry discovery time"
            }
        }
    }

    fun immutableCopy(): DurableContentBlob = copy(bytes = bytes?.copyOf())
}

/** Low-level streaming handles owned by a restart-safe durability adapter. */
internal interface DurableContentBlobStageHandle {
    val expectedSizeBytes: Long?
    val bytesWritten: Long
    val isSealed: Boolean
    fun append(chunk: ByteArray)
    fun seal(expected: BlobRef?, blobIdFactory: () -> String): DurableContentBlobPendingHandle
    fun abort()
}

internal interface DurableContentBlobPendingHandle {
    val stage: DurableContentBlobStageHandle
    val reference: BlobRef
}

internal interface DurableContentBlobReadHandle {
    fun readChunk(maxBytes: Int): ByteArray?
    fun close()
}

/** Persistence hook. Implementations must make each upsert/delete durable before returning. */
internal interface ContentBlobDurability {
    val storeInstanceId: String
    /** Loads only bounded metadata. Implementations must not materialize payload columns here. */
    fun loadMetadata(): List<DurableContentBlob>
    /** Reads one exact immutable incarnation when a caller opens or verifies that blob. */
    fun readPayload(blob: DurableContentBlob): ByteArray?
    fun beginStage(
        expectedSizeBytes: Long?,
        mediaType: String,
        maximumBlobSizeBytes: Long,
    ): DurableContentBlobStageHandle? = null
    fun publish(blob: DurableContentBlob, pending: DurableContentBlobPendingHandle) {
        throw ContentBlobStoreException.InvalidStage("Durability adapter does not accept streaming stages")
    }
    /** Returns a handle only after verifying this exact immutable incarnation's complete body. */
    fun openRead(blob: DurableContentBlob): DurableContentBlobReadHandle? = null
    fun verifyPayload(blob: DurableContentBlob): Boolean = readPayload(blob)?.let { payload ->
        payload.size.toLong() == blob.reference.byteSize &&
            Sha256.hex(payload) == blob.reference.plaintextDigest
    } == true
    fun upsert(blob: DurableContentBlob)
    fun delete(blob: DurableContentBlob)

    /**
     * Runs one explicitly background-only, bounded representation-maintenance slice.
     *
     * Implementations must not enumerate files or read inline payload columns during
     * [loadMetadata]. A slice may copy only [ContentBlobStorageMaintenanceRequest.maximumBytes]
     * body bytes and inspect only [ContentBlobStorageMaintenanceRequest.maximumFiles] directory
     * entries. The caller holds the blob state-machine lock, so a file cannot be mistaken for an
     * orphan while publish/receipt/attachment state is changing.
     */
    fun runStorageMaintenanceSlice(
        request: ContentBlobStorageMaintenanceRequest,
    ): ContentBlobStorageMaintenanceResult = ContentBlobStorageMaintenanceResult()
}

/** Internal policy passed only by the idle/background recovery coordinator. */
internal data class ContentBlobStorageMaintenanceRequest(
    val nowEpochMillis: Long,
    val minimumAgeMillis: Long,
    val maximumBytes: Int,
    val maximumFiles: Int,
) {
    init {
        require(nowEpochMillis >= 0) { "Blob maintenance timestamp must be non-negative" }
        require(minimumAgeMillis >= 0) { "Blob maintenance minimum age must be non-negative" }
        require(maximumBytes > 0) { "Blob maintenance byte budget must be positive" }
        require(maximumFiles > 0) { "Blob maintenance file budget must be positive" }
    }
}

/** Bounded diagnostics for SQL/file representation maintenance. */
internal data class ContentBlobStorageMaintenanceResult(
    val migratedInlineBytes: Int = 0,
    val completedInlineMigrations: Int = 0,
    val scannedFiles: Int = 0,
    val discoveredUnknownFiles: Int = 0,
    val removedUnknownFiles: Int = 0,
) {
    init {
        require(migratedInlineBytes >= 0)
        require(completedInlineMigrations >= 0)
        require(scannedFiles >= 0)
        require(discoveredUnknownFiles >= 0)
        require(removedUnknownFiles >= 0)
        require(discoveredUnknownFiles <= scannedFiles)
        require(removedUnknownFiles <= scannedFiles)
    }

    operator fun plus(other: ContentBlobStorageMaintenanceResult): ContentBlobStorageMaintenanceResult =
        ContentBlobStorageMaintenanceResult(
            migratedInlineBytes = migratedInlineBytes + other.migratedInlineBytes,
            completedInlineMigrations = completedInlineMigrations + other.completedInlineMigrations,
            scannedFiles = scannedFiles + other.scannedFiles,
            discoveredUnknownFiles = discoveredUnknownFiles + other.discoveredUnknownFiles,
            removedUnknownFiles = removedUnknownFiles + other.removedUnknownFiles,
        )
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

    private inline fun <T> withStateLock(block: () -> T): T {
        stateMutex.lock()
        return try {
            check(!lockHeld) { "Blob store lock re-entry is forbidden" }
            lockHeld = true
            try {
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

/** Common-source checked addition; java.lang.Math is unavailable to Kotlin/Native. */
private fun checkedAdd(first: Long, second: Long): Long {
    require(first >= 0 && second >= 0) { "Checked addition only accepts non-negative values" }
    if (second > Long.MAX_VALUE - first) throw ArithmeticException("Long overflow")
    return first + second
}
