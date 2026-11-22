package dev.shinsou.kmp.content.blob

import dev.shinsou.kmp.crypto.Sha256
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import kotlin.time.Clock
import dev.shinsou.kmp.domain.content.BlobRef
import dev.shinsou.kmp.domain.content.ContentManifest
import dev.shinsou.kmp.domain.content.deepImmutableSnapshot
import dev.shinsou.kmp.content.transaction.AuxiliaryBlobAttachment
import dev.shinsou.kmp.content.transaction.SharedContentTransactionStore

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

/** The durable attachment ledger a deferred hydration installs. */
internal class DeferredAttachmentHydration(
    val attachments: List<BlobAttachment>,
    val auxiliaryAttachments: List<AuxiliaryBlobAttachment>,
)

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

    /**
     * Best-effort immediate removal of bodies the caller just detached from their last manifest.
     * Every candidate is re-checked against the same guards as [sweepRecovery] (attached anywhere,
     * pending receipt, active reader), so a content-addressed blob that is still shared survives.
     * Returns how many were deleted; anything skipped is left to the age-gated orphan recovery.
     */
    public fun deleteUnreferencedNow(refs: List<BlobRef>): Int = 0

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
    /**
     * Registers the durable attachment ledger to be loaded by whichever caller next takes the
     * store lock, before it reads anything. Lets the host open without decoding every stored
     * manifest on its first frame.
     */
    fun deferAttachmentHydration(load: () -> DeferredAttachmentHydration)
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

/** Common-source checked addition; java.lang.Math is unavailable to Kotlin/Native. */
internal fun checkedAdd(first: Long, second: Long): Long {
    require(first >= 0 && second >= 0) { "Checked addition only accepts non-negative values" }
    if (second > Long.MAX_VALUE - first) throw ArithmeticException("Long overflow")
    return first + second
}
