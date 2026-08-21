package dev.shinsou.kmp.sync.v2

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Device-local, restart-safe intent for one small encrypted-body lifecycle operation.
 *
 * Random envelopes, tombstone identities, checkpoint acknowledgements, and Worker receipts must
 * survive independently of the metadata outbox. Persisting the exact values here lets a retry
 * replay the same request after response loss instead of inventing conflicting cryptographic
 * material.
 */
@Serializable
public sealed interface DurableBlobLifecycleIntentV2 {
    public val instanceId: String
    public val workspaceId: String
    public val blobId: String
    public val generation: Long
    /** Persisted scheduler rotation; denied/failing work cannot remain permanently first. */
    public val attemptCount: Long

    @Serializable
    @SerialName("envelope_rewrap")
    public data class EnvelopeRewrap(
        val prepared: PreparedBlobEnvelopeRewrapV2,
        val committedMutation: BlobDekEnvelopeRewrappedV2? = null,
        override val attemptCount: Long = 0,
    ) : DurableBlobLifecycleIntentV2 {
        override val instanceId: String get() = prepared.instanceId
        override val workspaceId: String get() = prepared.workspaceId
        override val blobId: String get() = prepared.blobId
        override val generation: Long get() = prepared.generation

        init {
            require(attemptCount >= 0) { "Blob lifecycle attempt count cannot be negative" }
            committedMutation?.let { mutation ->
                require(mutation.blobId == prepared.blobId) {
                    "Durable re-wrap mutation targets another blob"
                }
                require(mutation.manifestId == prepared.request.manifestId) {
                    "Durable re-wrap mutation targets another manifest"
                }
                require(mutation.generation == prepared.generation) {
                    "Durable re-wrap mutation targets another blob generation"
                }
                require(
                    mutation.envelope.blobId == prepared.request.envelope.blobId &&
                        mutation.envelope.keyEpoch == prepared.request.envelope.keyEpoch &&
                        mutation.envelope.previousEnvelopeSha256Base64Url ==
                        prepared.request.envelope.previousEnvelopeSha256Base64Url,
                ) {
                    "Durable re-wrap result differs from its prepared epoch or envelope chain"
                }
            }
        }
    }

    @Serializable
    @SerialName("reference_tombstone")
    public data class ReferenceTombstone(
        val handle: BlobTombstoneHandleV2,
        /** Exact stable checkpoint whose verified state contains this absent reference. */
        val referenceCheckpoint: RetainedCheckpointDescriptor,
        val createdOnWorker: Boolean = false,
        /** ACTIVE for a canonical live tombstone; terminal values prevent unsafe ack/GC. */
        val creationDisposition: BlobTombstoneDispositionV2? = null,
        val acknowledgement: BlobTombstoneAckRequestV2? = null,
        val acknowledgementCommitted: Boolean = false,
        /** Exact signed presence=true assertion, persisted before its first network request. */
        val revival: BlobReferenceRevivalRequestV2? = null,
        /** Exact Worker outcome, persisted before the local intent is removed or reported. */
        val revivalResult: BlobReferenceRevivalResultV2? = null,
        val gcReceipt: BlobGcReceiptV2? = null,
        override val attemptCount: Long = 0,
    ) : DurableBlobLifecycleIntentV2 {
        override val instanceId: String get() = handle.instanceId
        override val workspaceId: String get() = handle.workspaceId
        override val blobId: String get() = handle.blobId
        override val generation: Long get() = handle.generation

        init {
            require(attemptCount >= 0) { "Blob lifecycle attempt count cannot be negative" }
            require(referenceCheckpoint.throughWorkspaceSeq == handle.referenceThroughWorkspaceSeq) {
                "Blob tombstone checkpoint does not match its reference boundary"
            }
            require(createdOnWorker == (creationDisposition != null)) {
                "Canonical tombstone disposition must be persisted with Worker creation"
            }
            require(createdOnWorker == (handle.executeAfterEpochMillis != null)) {
                "Only a Worker-canonical tombstone has a safety-window boundary"
            }
            acknowledgement?.let { request ->
                require(createdOnWorker) { "A blob tombstone must exist before acknowledgement" }
                require(creationDisposition == BlobTombstoneDispositionV2.ACTIVE) {
                    "A cancelled or deleting blob tombstone cannot be acknowledged"
                }
                require(request.tombstoneId == handle.tombstoneId) {
                    "Durable tombstone acknowledgement identity mismatch"
                }
                require(request.throughWorkspaceSeq >= handle.referenceThroughWorkspaceSeq) {
                    "Durable tombstone acknowledgement does not cover the reference deletion"
                }
            }
            require(!acknowledgementCommitted || acknowledgement != null) {
                "Committed tombstone acknowledgement bytes are missing"
            }
            revival?.let { request ->
                require(createdOnWorker && creationDisposition == BlobTombstoneDispositionV2.ACTIVE) {
                    "Only an active Worker tombstone can receive revival proof"
                }
                require(
                    request.tombstoneId == handle.tombstoneId &&
                        request.blobId == handle.blobId &&
                        request.manifestId == handle.manifestId,
                ) { "Durable blob revival identity mismatch" }
                require(request.throughWorkspaceSeq > handle.referenceThroughWorkspaceSeq) {
                    "Durable blob revival does not advance past removal"
                }
            }
            revivalResult?.let { result ->
                require(revival != null) { "Blob revival result has no durable signed request" }
                require(
                    result.tombstoneId == handle.tombstoneId &&
                        result.blobId == handle.blobId &&
                        result.manifestId == handle.manifestId,
                ) { "Durable blob revival result identity mismatch" }
            }
            gcReceipt?.let { receipt ->
                require(acknowledgementCommitted) {
                    "Blob GC cannot complete before a durable checkpoint acknowledgement"
                }
                require(revivalResult == null) {
                    "A revived blob tombstone cannot also complete GC locally"
                }
                require(receipt.blobId == handle.blobId) { "Blob GC receipt identity mismatch" }
            }
        }

        public val completed: Boolean
            get() = gcReceipt != null ||
                revivalResult != null ||
                creationDisposition == BlobTombstoneDispositionV2.CANCELLED ||
                creationDisposition == BlobTombstoneDispositionV2.REUPLOAD_REQUIRED
    }
}

/** Durable local journal. Implementations must reject regression or replacement of exact intents. */
public interface BlobLifecycleJournalV2 {
    public suspend fun entries(instanceId: String, workspaceId: String): List<DurableBlobLifecycleIntentV2>

    public suspend fun load(
        instanceId: String,
        workspaceId: String,
        blobId: String,
    ): DurableBlobLifecycleIntentV2?

    public suspend fun save(intent: DurableBlobLifecycleIntentV2)

    /** Removes only the exact value observed by the caller; a concurrent advance must survive. */
    public suspend fun remove(intent: DurableBlobLifecycleIntentV2): Boolean

    /** Idempotently removes every row owned by one departed remote authority. */
    public suspend fun clearAuthority(instanceId: String, workspaceId: String): Int
}

/** Test/preview implementation with the same monotonic transition rules as SQLite production. */
public class InMemoryBlobLifecycleJournalV2 : BlobLifecycleJournalV2 {
    private val mutex = Mutex()
    private val values = linkedMapOf<BlobLifecycleJournalKeyV2, DurableBlobLifecycleIntentV2>()

    override suspend fun entries(
        instanceId: String,
        workspaceId: String,
    ): List<DurableBlobLifecycleIntentV2> = mutex.withLock {
        validateLifecycleTenant(instanceId, workspaceId)
        values.entries
            .asSequence()
            .filter { it.key.instanceId == instanceId && it.key.workspaceId == workspaceId }
            .sortedBy { it.key.blobId }
            .map(Map.Entry<BlobLifecycleJournalKeyV2, DurableBlobLifecycleIntentV2>::value)
            .toList()
    }

    override suspend fun load(
        instanceId: String,
        workspaceId: String,
        blobId: String,
    ): DurableBlobLifecycleIntentV2? = mutex.withLock {
        values[BlobLifecycleJournalKeyV2(instanceId, workspaceId, blobId)]
    }

    override suspend fun save(intent: DurableBlobLifecycleIntentV2) {
        mutex.withLock {
            val key = intent.journalKey()
            values[key]?.requireMonotonicTransitionTo(intent)
            values[key] = intent
        }
    }

    override suspend fun remove(intent: DurableBlobLifecycleIntentV2): Boolean = mutex.withLock {
        val key = intent.journalKey()
        if (values[key] != intent) return@withLock false
        values.remove(key)
        true
    }

    override suspend fun clearAuthority(instanceId: String, workspaceId: String): Int = mutex.withLock {
        validateLifecycleTenant(instanceId, workspaceId)
        val keys = values.keys.filter { key ->
            key.instanceId == instanceId && key.workspaceId == workspaceId
        }
        keys.forEach(values::remove)
        keys.size
    }
}

internal data class BlobLifecycleJournalKeyV2(
    val instanceId: String,
    val workspaceId: String,
    val blobId: String,
) {
    init {
        validateLifecycleTenant(instanceId, workspaceId)
        requireCanonicalContentUuid(blobId, "Blob lifecycle journal blob id")
    }
}

internal fun DurableBlobLifecycleIntentV2.journalKey(): BlobLifecycleJournalKeyV2 =
    BlobLifecycleJournalKeyV2(instanceId, workspaceId, blobId)

internal fun DurableBlobLifecycleIntentV2.nextAttempt(): DurableBlobLifecycleIntentV2 = when (this) {
    is DurableBlobLifecycleIntentV2.EnvelopeRewrap -> copy(attemptCount = attemptCount + 1)
    is DurableBlobLifecycleIntentV2.ReferenceTombstone -> copy(attemptCount = attemptCount + 1)
}

internal fun DurableBlobLifecycleIntentV2.requireMonotonicTransitionTo(
    next: DurableBlobLifecycleIntentV2,
) {
    require(journalKey() == next.journalKey()) { "Blob lifecycle intent identity cannot change" }
    require(generation == next.generation) { "Blob lifecycle generation cannot change" }
    require(next.attemptCount >= attemptCount) { "Blob lifecycle attempt count cannot regress" }
    when {
        this is DurableBlobLifecycleIntentV2.EnvelopeRewrap &&
            next is DurableBlobLifecycleIntentV2.EnvelopeRewrap -> {
            require(prepared == next.prepared) { "Prepared blob re-wrap is immutable" }
            committedMutation?.let { require(it == next.committedMutation) {
                "Committed blob re-wrap mutation is immutable"
            } }
        }

        this is DurableBlobLifecycleIntentV2.ReferenceTombstone &&
            next is DurableBlobLifecycleIntentV2.ReferenceTombstone -> {
            require(referenceCheckpoint == next.referenceCheckpoint) {
                "Blob tombstone reference checkpoint is immutable"
            }
            if (!createdOnWorker && next.createdOnWorker) {
                require(handle.hasSameRemovalBoundary(next.handle)) {
                    "Worker canonical tombstone changed the removal boundary"
                }
            } else {
                require(handle == next.handle) { "Canonical blob tombstone is immutable" }
            }
            require(!createdOnWorker || next.createdOnWorker) { "Blob tombstone stage cannot regress" }
            creationDisposition?.let { require(it == next.creationDisposition) {
                "Worker tombstone disposition is immutable"
            } }
            acknowledgement?.let { require(it == next.acknowledgement) {
                "Prepared tombstone acknowledgement is immutable"
            } }
            require(!acknowledgementCommitted || next.acknowledgementCommitted) {
                "Blob tombstone acknowledgement stage cannot regress"
            }
            revival?.let { require(it == next.revival) {
                "Prepared blob revival proof is immutable"
            } }
            revivalResult?.let { require(it == next.revivalResult) {
                "Blob revival result is immutable"
            } }
            gcReceipt?.let { require(it == next.gcReceipt) { "Blob GC receipt is immutable" } }
        }

        else -> error("One blob cannot replace an unfinished lifecycle operation with another kind")
    }
}

internal fun validateLifecycleTenant(instanceId: String, workspaceId: String) {
    requireCanonicalContentUuid(instanceId, "Blob lifecycle journal instance id")
    requireCanonicalContentUuid(workspaceId, "Blob lifecycle journal workspace id")
}
