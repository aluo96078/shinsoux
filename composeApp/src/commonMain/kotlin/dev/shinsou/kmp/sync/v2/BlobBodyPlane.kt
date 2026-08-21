package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.BlobPublishReceipt
import dev.shinsou.kmp.content.BlobReadLease
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobReincarnationEvidence
import dev.shinsou.kmp.content.ContentBlobReincarnationTerminalKind
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentBlobStoreException
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.rights.ContentOperation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public const val BLOB_BODY_PROTOCOL_VERSION: Int = 2
public const val BLOB_BODY_SCHEMA_VERSION: Int = 2
public const val DEFAULT_BLOB_BODY_CHUNK_BYTES: Int = 1024 * 1024
/** Exact Worker capability; reserving a larger plan can never succeed. */
public const val MAX_BLOB_BODY_CHUNKS: Int = 1_024
public const val MAX_BLOB_BODY_CIPHERTEXT_BYTES: Long = 128L * 1024 * 1024

/** Exact local authority and lifecycle incarnation of one restart-resumable transfer. */
@Serializable
public data class BlobTransferKeyV2(
    val instanceId: String,
    val workspaceId: String,
    val blobId: String,
    val generation: Long,
) : Comparable<BlobTransferKeyV2> {
    init {
        requireCanonicalContentUuid(instanceId, "Blob transfer instance id")
        requireCanonicalContentUuid(workspaceId, "Blob transfer workspace id")
        requireCanonicalContentUuid(blobId, "Blob transfer blob id")
        require(generation > 0) { "Blob transfer generation must be positive" }
    }

    override fun compareTo(other: BlobTransferKeyV2): Int = compareValuesBy(
        this,
        other,
        BlobTransferKeyV2::instanceId,
        BlobTransferKeyV2::workspaceId,
        BlobTransferKeyV2::blobId,
        BlobTransferKeyV2::generation,
    )

    public fun requireBoundTo(session: SyncSession) {
        require(instanceId == session.instanceId && workspaceId == session.workspaceId) {
            "Blob transfer belongs to another sync authority"
        }
    }
}

@Serializable
public data class EncryptedBlobChunkPlanV2(
    val index: Int,
    val ciphertextByteSize: Int,
    val ciphertextSha256Base64Url: String,
) {
    init {
        require(index >= 0) { "Blob chunk index cannot be negative" }
        require(ciphertextByteSize >= BLOB_AEAD_TAG_BYTES) { "Blob chunk ciphertext is too small" }
        requireCanonicalSha256Base64Url(ciphertextSha256Base64Url, "Blob chunk ciphertext hash")
    }
}

public data class EncryptedBlobChunkV2(
    val plan: EncryptedBlobChunkPlanV2,
    val ciphertext: BinaryData,
) {
    init {
        require(ciphertext.size == plan.ciphertextByteSize) { "Blob chunk size does not match its plan" }
    }
}

/** Plaintext metadata is encrypted as one small manifest under the per-blob DEK. */
@Serializable
public data class BlobPrivateManifestV2(
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val blob: BlobRef,
    val chunkPlaintextByteSizes: List<Int>,
) {
    init {
        require(schemaVersion == BLOB_BODY_SCHEMA_VERSION) { "Unsupported private blob manifest schema" }
        blob.validate()
        require(chunkPlaintextByteSizes.size <= MAX_BLOB_BODY_CHUNKS) { "Blob has too many chunks" }
        require(chunkPlaintextByteSizes.all { it in 1..RemoteBlobBodyManifestRefV2.MAX_CHUNK_BYTES }) {
            "Private blob manifest contains an invalid chunk size"
        }
        require(chunkPlaintextByteSizes.sumOf(Int::toLong) == blob.byteSize) {
            "Private blob manifest size does not match the blob reference"
        }
    }
}

@Serializable
public data class EncryptedBlobPrivateManifestV2(
    val nonceBase64Url: String,
    val ciphertextBase64Url: String,
    val ciphertextSha256Base64Url: String,
    val ciphertextByteSize: Int,
) {
    init {
        requireCanonicalBase64Url(nonceBase64Url, "Private blob manifest nonce")
        requireCanonicalBase64Url(ciphertextBase64Url, "Private blob manifest ciphertext")
        requireCanonicalSha256Base64Url(ciphertextSha256Base64Url, "Private blob manifest hash")
        require(ciphertextByteSize >= BLOB_AEAD_TAG_BYTES) { "Private blob manifest ciphertext is too small" }
    }
}

/** Durable, device-local upload identity. It contains no plaintext DEK. */
@Serializable
public data class BlobUploadIntentV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val transferKey: BlobTransferKeyV2,
    val manifestId: String,
    val blob: BlobRef,
    val keyEpoch: Int,
    val chunkSizeBytes: Int,
    val dekEnvelope: BlobDekEnvelopeV2,
    val createdAtEpochMillis: Long,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION) {
            "Unsupported blob upload intent version"
        }
        requireCanonicalContentUuid(manifestId, "Blob upload manifest id")
        blob.validate()
        require(transferKey.blobId == blob.blobId) { "Blob upload transfer identity mismatch" }
        require(keyEpoch > 0 && dekEnvelope.keyEpoch == keyEpoch && dekEnvelope.blobId == blob.blobId) {
            "Blob upload key envelope does not match the intent"
        }
        require(chunkSizeBytes in RemoteBlobBodyManifestRefV2.MIN_CHUNK_BYTES..
            RemoteBlobBodyManifestRefV2.MAX_CHUNK_BYTES) { "Blob upload chunk size is invalid" }
        require(createdAtEpochMillis >= 0) { "Blob upload intent time cannot be negative" }
        val chunkCount = if (blob.byteSize == 0L) 0L else (blob.byteSize + chunkSizeBytes - 1) / chunkSizeBytes
        require(chunkCount <= MAX_BLOB_BODY_CHUNKS) { "Blob upload has too many chunks" }
        require(blob.byteSize + chunkCount * BLOB_AEAD_TAG_BYTES <= MAX_BLOB_BODY_CIPHERTEXT_BYTES) {
            "Blob upload exceeds the Worker ciphertext limit"
        }
    }
}

@Serializable
public data class BlobUploadReservationRequestV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val blobId: String,
    val manifestId: String,
    val keyEpoch: Int,
    val chunkSizeBytes: Int,
    val expectedBodyCiphertextBytes: Long,
    val expectedManifestCiphertextBytes: Int,
    val manifestCiphertextSha256Base64Url: String,
    val chunks: List<EncryptedBlobChunkPlanV2>,
    val initialDekEnvelope: BlobDekEnvelopeV2,
    /** Client-local binding; intentionally omitted from the Worker's strict JSON request. */
    @kotlinx.serialization.Transient
    val transferKey: BlobTransferKeyV2? = null,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION)
        requireCanonicalContentUuid(blobId, "Blob reservation id")
        requireCanonicalContentUuid(manifestId, "Blob reservation manifest id")
        transferKey?.let { key ->
            require(key.blobId == blobId) { "Blob reservation transfer identity mismatch" }
        }
        require(keyEpoch > 0 && initialDekEnvelope.keyEpoch == keyEpoch && initialDekEnvelope.blobId == blobId)
        require(chunkSizeBytes in RemoteBlobBodyManifestRefV2.MIN_CHUNK_BYTES..
            RemoteBlobBodyManifestRefV2.MAX_CHUNK_BYTES)
        require(chunks.size <= MAX_BLOB_BODY_CHUNKS && chunks.indices.all { chunks[it].index == it }) {
            "Blob reservation chunk plan must be contiguous"
        }
        require(expectedBodyCiphertextBytes == chunks.sumOf { it.ciphertextByteSize.toLong() }) {
            "Blob reservation body size does not match its chunk plan"
        }
        require(expectedBodyCiphertextBytes <= MAX_BLOB_BODY_CIPHERTEXT_BYTES) {
            "Blob reservation exceeds the Worker ciphertext limit"
        }
        require(expectedManifestCiphertextBytes >= BLOB_AEAD_TAG_BYTES)
        requireCanonicalSha256Base64Url(manifestCiphertextSha256Base64Url, "Reserved private manifest hash")
    }

    public val totalReservedBytes: Long
        get() = expectedBodyCiphertextBytes + expectedManifestCiphertextBytes
}

@Serializable
public data class BlobChunkReceiptV2(
    val index: Int,
    val ciphertextByteSize: Int,
    val ciphertextSha256Base64Url: String,
) {
    init {
        EncryptedBlobChunkPlanV2(index, ciphertextByteSize, ciphertextSha256Base64Url)
    }

    public fun matches(plan: EncryptedBlobChunkPlanV2): Boolean =
        index == plan.index && ciphertextByteSize == plan.ciphertextByteSize &&
            ciphertextSha256Base64Url == plan.ciphertextSha256Base64Url
}

@Serializable
public data class BlobUploadSessionV2(
    val sessionId: String,
    val blobId: String,
    val manifestId: String,
    val keyEpoch: Int,
    val expiresAtEpochMillis: Long,
    val reservedBytes: Long,
    val receivedChunks: List<BlobChunkReceiptV2> = emptyList(),
) {
    init {
        requireCanonicalContentUuid(sessionId, "Blob upload session id")
        requireCanonicalContentUuid(blobId, "Blob upload session blob id")
        requireCanonicalContentUuid(manifestId, "Blob upload session manifest id")
        require(keyEpoch > 0 && expiresAtEpochMillis >= 0 && reservedBytes > 0)
        require(receivedChunks.map(BlobChunkReceiptV2::index).distinct().size == receivedChunks.size) {
            "Blob upload session contains duplicate receipts"
        }
    }
}

@Serializable
public data class BlobManifestCommitRequestV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val encryptedPrivateManifest: EncryptedBlobPrivateManifestV2,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION)
    }
}

@Serializable
public data class BlobBodyCommitReceiptV2(
    val receiptId: String,
    val sessionId: String,
    val manifest: RemoteBlobBodyManifestRefV2,
) {
    init {
        requireCanonicalContentUuid(receiptId, "Blob body receipt id")
        requireCanonicalContentUuid(sessionId, "Blob body receipt session id")
        require(manifest.commitReceiptId == receiptId) { "Blob body commit receipt identity mismatch" }
    }
}

@Serializable
public data class CommittedEncryptedBlobManifestV2(
    val remote: RemoteBlobBodyManifestRefV2,
    val chunks: List<EncryptedBlobChunkPlanV2>,
    val encryptedPrivateManifest: EncryptedBlobPrivateManifestV2,
    val dekEnvelopes: List<BlobDekEnvelopeV2>,
) {
    init {
        require(chunks.size == remote.chunkCount && chunks.indices.all { chunks[it].index == it })
        require(chunks.sumOf { it.ciphertextByteSize.toLong() } == remote.bodyCiphertextByteSize)
        require(encryptedPrivateManifest.ciphertextSha256Base64Url ==
            remote.manifestCiphertextSha256Base64Url)
        require(encryptedPrivateManifest.ciphertextByteSize.toLong() == remote.manifestCiphertextByteSize)
        require(dekEnvelopes.isNotEmpty() && dekEnvelopes.all { it.blobId == remote.blobId })
    }
}

@Serializable
public data class BlobEnvelopeRewrapRequestV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val manifestId: String,
    val envelope: BlobDekEnvelopeV2,
    val checkpointEvidence: BlobRewrapCheckpointEvidenceV2,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION)
        requireCanonicalContentUuid(manifestId, "Blob re-wrap manifest id")
        require(envelope.previousEnvelopeSha256Base64Url != null) {
            "Blob re-wrap must chain from a previous envelope"
        }
    }
}

@Serializable
public enum class BlobEnvelopeRetentionStatusV2 {
    @SerialName("current")
    CURRENT,

    @SerialName("retained")
    RETAINED,
}

/**
 * Authenticated Worker evidence used to recover a response-lost envelope after key rotation.
 *
 * The response includes the exact winning envelope and its original stable-checkpoint evidence;
 * clients must publish that value through the normal metadata outbox before attempting a newer
 * re-wrap. It never contains the plaintext DEK.
 */
@Serializable
public data class BlobEnvelopeRecoveryV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val manifestId: String,
    val envelope: BlobDekEnvelopeV2,
    val checkpointEvidence: BlobRewrapCheckpointEvidenceV2?,
    val status: BlobEnvelopeRetentionStatusV2,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION) {
            "Unsupported recovered blob envelope version"
        }
        requireCanonicalContentUuid(manifestId, "Recovered blob envelope manifest id")
        require((envelope.previousEnvelopeSha256Base64Url == null) == (checkpointEvidence == null)) {
            "Recovered re-wrap envelope must retain its checkpoint evidence"
        }
    }
}

@Serializable
public data class BlobReferenceTombstoneRequestV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val tombstoneId: String,
    val blobId: String,
    val manifestId: String,
    val throughWorkspaceSeq: Long,
    val createdAtEpochMillis: Long,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION)
        requireCanonicalContentUuid(tombstoneId, "Blob tombstone id")
        requireCanonicalContentUuid(blobId, "Blob tombstone blob id")
        requireCanonicalContentUuid(manifestId, "Blob tombstone manifest id")
        require(throughWorkspaceSeq >= 0 && createdAtEpochMillis >= 0)
    }
}

@Serializable
public enum class BlobTombstoneDispositionV2 {
    /** The canonical tombstone is still eligible for acknowledgement and server-side GC. */
    @SerialName("active")
    ACTIVE,

    /** A newer live reference cancelled the tombstone before its irreversible GC claim. */
    @SerialName("cancelled")
    CANCELLED,

    /** GC already crossed the irreversible claim boundary; the body must be uploaded again. */
    @SerialName("reupload_required")
    REUPLOAD_REQUIRED,
}

/** Worker winner for one removal boundary, including a canonical handle chosen during a race. */
@Serializable
public data class BlobTombstoneCreationResultV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val handle: BlobTombstoneHandleV2,
    val disposition: BlobTombstoneDispositionV2,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION)
        require(handle.executeAfterEpochMillis != null) {
            "A Worker tombstone result must contain its safety-window boundary"
        }
    }
}

/**
 * Signed assertion that a retained stable checkpoint contains a newer live blob reference.
 *
 * The Worker cannot inspect E2EE checkpoint plaintext. It validates the active device signature,
 * retained checkpoint identity, and strictly newer sequence; the client verifies presence=true
 * before signing these exact bytes.
 */
@Serializable
public data class BlobReferenceRevivalRequestV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val tombstoneId: String,
    val blobId: String,
    val manifestId: String,
    val checkpointId: String,
    val checkpointCiphertextSha256Base64Url: String,
    val throughWorkspaceSeq: Long,
    val signatureBase64Url: String,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION)
        requireCanonicalContentUuid(tombstoneId, "Blob revival tombstone id")
        requireCanonicalContentUuid(blobId, "Blob revival blob id")
        requireCanonicalContentUuid(manifestId, "Blob revival manifest id")
        requireCanonicalContentUuid(checkpointId, "Blob revival checkpoint id")
        requireCanonicalSha256Base64Url(
            checkpointCiphertextSha256Base64Url,
            "Blob revival checkpoint hash",
        )
        require(throughWorkspaceSeq >= 0)
        requireCanonicalBase64Url(signatureBase64Url, "Blob revival signature")
    }
}

/** Typed result: cancellation is possible only before the Worker enters DELETING. */
@Serializable
public data class BlobReferenceRevivalResultV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val tombstoneId: String,
    val blobId: String,
    val manifestId: String,
    val disposition: BlobTombstoneDispositionV2,
    val cancelledAtEpochMillis: Long? = null,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION)
        requireCanonicalContentUuid(tombstoneId, "Blob revival tombstone id")
        requireCanonicalContentUuid(blobId, "Blob revival blob id")
        requireCanonicalContentUuid(manifestId, "Blob revival manifest id")
        require(disposition != BlobTombstoneDispositionV2.ACTIVE) {
            "A blob revival result cannot leave the tombstone active"
        }
        require((disposition == BlobTombstoneDispositionV2.CANCELLED) == (cancelledAtEpochMillis != null)) {
            "Only a cancelled tombstone has a cancellation timestamp"
        }
        cancelledAtEpochMillis?.let { require(it >= 0) }
    }
}

@Serializable
public data class BlobTombstoneAckRequestV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val tombstoneId: String,
    val checkpointId: String,
    val checkpointCiphertextSha256Base64Url: String,
    val throughWorkspaceSeq: Long,
    val signatureBase64Url: String,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION)
        requireCanonicalContentUuid(tombstoneId, "Blob tombstone acknowledgement id")
        requireCanonicalContentUuid(checkpointId, "Blob tombstone acknowledgement checkpoint id")
        requireCanonicalSha256Base64Url(
            checkpointCiphertextSha256Base64Url,
            "Blob tombstone acknowledgement checkpoint hash",
        )
        require(throughWorkspaceSeq >= 0)
        requireCanonicalBase64Url(signatureBase64Url, "Blob tombstone acknowledgement signature")
    }
}

@Serializable
public data class BlobGcRequestV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val blobId: String,
    val tombstoneId: String,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION)
        requireCanonicalContentUuid(blobId, "Blob GC id")
        requireCanonicalContentUuid(tombstoneId, "Blob GC tombstone id")
    }
}

@Serializable
public data class BlobGcReceiptV2(
    val receiptId: String,
    val blobId: String,
    val deletedObjectCount: Int,
    val deletedCiphertextBytes: Long,
    val completedAtEpochMillis: Long,
) {
    init {
        requireCanonicalContentUuid(receiptId, "Blob GC receipt id")
        requireCanonicalContentUuid(blobId, "Blob GC receipt blob id")
        require(deletedObjectCount >= 0 && deletedCiphertextBytes >= 0 && completedAtEpochMillis >= 0)
    }
}

public interface CloudflareBlobBodyApiV2 {
    public suspend fun reserveUpload(
        session: SyncSession,
        capability: WorkspaceCapability,
        request: BlobUploadReservationRequestV2,
    ): BlobUploadSessionV2

    public suspend fun uploadStatus(
        session: SyncSession,
        capability: WorkspaceCapability,
        uploadSessionId: String,
    ): BlobUploadSessionV2

    public suspend fun uploadChunk(
        session: SyncSession,
        capability: WorkspaceCapability,
        uploadSessionId: String,
        chunk: EncryptedBlobChunkV2,
    ): BlobChunkReceiptV2

    public suspend fun commitUpload(
        session: SyncSession,
        capability: WorkspaceCapability,
        uploadSessionId: String,
        request: BlobManifestCommitRequestV2,
    ): BlobBodyCommitReceiptV2

    public suspend fun downloadManifest(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
    ): CommittedEncryptedBlobManifestV2

    public suspend fun downloadChunk(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
        chunk: EncryptedBlobChunkPlanV2,
    ): BinaryData

    public suspend fun rewrapEnvelope(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
        request: BlobEnvelopeRewrapRequestV2,
    ): BlobDekEnvelopeV2

    public suspend fun recoverEnvelope(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
        keyEpoch: Int,
    ): BlobEnvelopeRecoveryV2 = throw UnsupportedOperationException(
        "This blob body transport does not support durable envelope recovery",
    )

    public suspend fun createTombstone(
        session: SyncSession,
        capability: WorkspaceCapability,
        request: BlobReferenceTombstoneRequestV2,
    ): BlobTombstoneCreationResultV2

    public suspend fun reviveBlobReference(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
        request: BlobReferenceRevivalRequestV2,
    ): BlobReferenceRevivalResultV2

    public suspend fun acknowledgeTombstone(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
        request: BlobTombstoneAckRequestV2,
    )

    public suspend fun garbageCollect(
        session: SyncSession,
        capability: WorkspaceCapability,
        request: BlobGcRequestV2,
    ): BlobGcReceiptV2
}

public interface BlobBodyCryptoV2 {
    /** Legacy v2 source-compatible entry point, safely bound to generation one of this session. */
    public suspend fun createUploadIntent(
        session: SyncSession,
        blob: BlobRef,
        keyEpoch: Int,
        chunkSizeBytes: Int,
        createdAtEpochMillis: Long,
    ): BlobUploadIntentV2 = createUploadIntent(
        session,
        BlobTransferKeyV2(session.instanceId, session.workspaceId, blob.blobId, 1),
        blob,
        keyEpoch,
        chunkSizeBytes,
        createdAtEpochMillis,
    )

    public suspend fun createUploadIntent(
        session: SyncSession,
        transferKey: BlobTransferKeyV2,
        blob: BlobRef,
        keyEpoch: Int,
        chunkSizeBytes: Int,
        createdAtEpochMillis: Long,
    ): BlobUploadIntentV2

    public suspend fun encryptChunk(
        session: SyncSession,
        intent: BlobUploadIntentV2,
        chunkIndex: Int,
        plaintext: ByteArray,
    ): EncryptedBlobChunkV2

    public suspend fun encryptPrivateManifest(
        session: SyncSession,
        intent: BlobUploadIntentV2,
        manifest: BlobPrivateManifestV2,
    ): EncryptedBlobPrivateManifestV2

    public suspend fun decryptPrivateManifest(
        session: SyncSession,
        encrypted: EncryptedBlobPrivateManifestV2,
        envelope: BlobDekEnvelopeV2,
        manifestId: String,
    ): BlobPrivateManifestV2

    public suspend fun decryptChunk(
        session: SyncSession,
        manifestId: String,
        envelope: BlobDekEnvelopeV2,
        chunk: EncryptedBlobChunkV2,
        expectedPlaintextBytes: Int,
    ): ByteArray

    public suspend fun rewrapDek(
        session: SyncSession,
        previous: BlobDekEnvelopeV2,
        targetKeyEpoch: Int,
    ): BlobDekEnvelopeV2
}

public interface BlobTransferJournalV2 {
    public suspend fun loadIntent(key: BlobTransferKeyV2): BlobUploadIntentV2?
    public suspend fun saveIntent(intent: BlobUploadIntentV2)
    public suspend fun loadCommitted(key: BlobTransferKeyV2): BlobBodyCommitReceiptV2?
    /** Stable identities used to finish cleanup after a crash between job ack and journal removal. */
    public suspend fun committedKeys(instanceId: String, workspaceId: String): List<BlobTransferKeyV2>
    public suspend fun markCommitted(key: BlobTransferKeyV2, receipt: BlobBodyCommitReceiptV2)
    public suspend fun removeIntent(key: BlobTransferKeyV2)
    /** Removes the intent and receipt only after the matching metadata draft is durable. */
    public suspend fun removeCompleted(key: BlobTransferKeyV2)

    /** Durable round-robin cursor used by the bounded background scheduler. */
    public suspend fun loadSchedulingCursor(instanceId: String, workspaceId: String): String? = null
    public suspend fun saveSchedulingCursor(instanceId: String, workspaceId: String, jobId: String) = Unit

    /** Idempotently removes transfer rows and scheduler state for a departed authority. */
    public suspend fun clearAuthority(instanceId: String, workspaceId: String): Int
}

public class InMemoryBlobTransferJournalV2 : BlobTransferJournalV2 {
    private val mutex = Mutex()
    private val intents = mutableMapOf<BlobTransferKeyV2, BlobUploadIntentV2>()
    private val committed = mutableMapOf<BlobTransferKeyV2, BlobBodyCommitReceiptV2>()
    private val schedulingCursors = mutableMapOf<Pair<String, String>, String>()

    override suspend fun loadIntent(key: BlobTransferKeyV2): BlobUploadIntentV2? = locked { intents[key] }

    override suspend fun saveIntent(intent: BlobUploadIntentV2) = locked {
        val key = intent.transferKey
        val existing = intents[key]
        require(existing == null || existing == intent) { "A different upload intent already exists for this blob" }
        intents[key] = intent
    }

    override suspend fun loadCommitted(key: BlobTransferKeyV2): BlobBodyCommitReceiptV2? = locked {
        committed[key]
    }

    override suspend fun committedKeys(
        instanceId: String,
        workspaceId: String,
    ): List<BlobTransferKeyV2> = locked {
        committed.keys.filter { it.instanceId == instanceId && it.workspaceId == workspaceId }.sorted()
    }

    override suspend fun markCommitted(key: BlobTransferKeyV2, receipt: BlobBodyCommitReceiptV2) = locked {
        require(receipt.manifest.blobId == key.blobId)
        require(intents[key]?.transferKey == key) { "A blob commit receipt requires its exact durable intent" }
        val existing = committed[key]
        require(existing == null || existing == receipt) { "A different blob commit receipt is already durable" }
        committed[key] = receipt
        // Keep the encrypted-DEK intent beside the receipt. Rebuilding the metadata mutation after
        // a process death must not require a new envelope or lose the exact committed identity.
    }

    override suspend fun removeIntent(key: BlobTransferKeyV2) = locked {
        require(key !in committed) { "A committed upload must be cleared with removeCompleted" }
        intents.remove(key)
        Unit
    }

    override suspend fun removeCompleted(key: BlobTransferKeyV2) = locked {
        require(key in committed) { "Cannot complete an upload without a durable receipt" }
        intents.remove(key)
        committed.remove(key)
        Unit
    }

    override suspend fun loadSchedulingCursor(instanceId: String, workspaceId: String): String? = locked {
        schedulingCursors[instanceId to workspaceId]
    }

    override suspend fun saveSchedulingCursor(instanceId: String, workspaceId: String, jobId: String) = locked {
        validateLifecycleTenant(instanceId, workspaceId)
        require(jobId.isNotBlank()) { "Blob scheduling cursor cannot be blank" }
        schedulingCursors[instanceId to workspaceId] = jobId
    }

    override suspend fun clearAuthority(instanceId: String, workspaceId: String): Int = locked {
        validateLifecycleTenant(instanceId, workspaceId)
        val keys = (intents.keys + committed.keys).filter {
            it.instanceId == instanceId && it.workspaceId == workspaceId
        }.toSet()
        keys.forEach { key ->
            intents.remove(key)
            committed.remove(key)
        }
        schedulingCursors.remove(instanceId to workspaceId)
        keys.size
    }

    private suspend fun <T> locked(block: () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

public class CommittedBlobUploadV2 internal constructor(
    public val transferKey: BlobTransferKeyV2,
    public val blob: BlobRef,
    public val remoteManifest: RemoteBlobBodyManifestRefV2,
    public val dekEnvelope: BlobDekEnvelopeV2,
    public val receipt: BlobBodyCommitReceiptV2,
) {
    init {
        require(transferKey.blobId == blob.blobId)
        require(blob.blobId == remoteManifest.blobId && blob.blobId == dekEnvelope.blobId)
        require(receipt.manifest == remoteManifest)
    }

    /** Only a verified, durable commit receipt can cross into metadata event construction. */
    internal fun toMutation(
        reincarnation: ContentBlobReincarnationEvidence? = null,
    ): SyncMutation = if (reincarnation == null) {
        BlobReferenceCommitV2(blob, remoteManifest, dekEnvelope, transferKey.generation)
    } else {
        require(transferKey.generation == reincarnation.previousGeneration + 1) {
            "Committed reincarnation generation differs from its terminal evidence"
        }
        BlobReferenceReincarnationCommitV2(
            blob = blob,
            remoteManifest = remoteManifest,
            initialDekEnvelope = dekEnvelope,
            generation = transferKey.generation,
            evidence = BlobReincarnationEvidenceV2(
                previousManifestId = reincarnation.previousManifestId,
                tombstoneId = reincarnation.tombstoneId,
                terminalKind = when (reincarnation.terminalKind) {
                    ContentBlobReincarnationTerminalKind.REUPLOAD_REQUIRED ->
                        BlobReincarnationTerminalKindV2.REUPLOAD_REQUIRED
                    ContentBlobReincarnationTerminalKind.GC_COMPLETED ->
                        BlobReincarnationTerminalKindV2.GC_COMPLETED
                },
                gcReceiptId = reincarnation.gcReceiptId,
            ),
        )
    }
}

/** Two-pass, restart-resumable uploader. Blob sync is always background-safe and rights-gated. */
public class EncryptedBlobUploaderV2(
    private val blobStore: ContentBlobStore,
    private val bodyApi: CloudflareBlobBodyApiV2,
    private val crypto: BlobBodyCryptoV2,
    private val journal: BlobTransferJournalV2,
    private val operationGate: ContentOperationGate,
    private val nowEpochMillis: () -> Long,
) {
    public suspend fun upload(
        session: SyncSession,
        capability: WorkspaceCapability,
        blob: BlobRef,
        access: ContentAccessRequest,
        chunkSizeBytes: Int = DEFAULT_BLOB_BODY_CHUNK_BYTES,
        generation: Long = 1,
    ): CommittedBlobUploadV2 {
        blob.validate()
        require(capability.binding.workspaceId == session.workspaceId &&
            capability.binding.deviceId == session.deviceId) { "Blob upload capability is not bound to this session" }
        require(capability.binding.keyEpoch == session.activeKeyEpoch) {
            "Blob upload requires a current-epoch capability"
        }
        val transferKey = BlobTransferKeyV2(
            instanceId = session.instanceId,
            workspaceId = session.workspaceId,
            blobId = blob.blobId,
            generation = generation,
        )
        val selectedChunkSize = selectBlobUploadChunkSize(blob.byteSize, chunkSizeBytes)
        val gatedAccess = access.withBlobBytes(blob.byteSize)
        operationGate.requireAllowed(gatedAccess, ContentOperation.SYNC_BLOB)

        journal.loadCommitted(transferKey)?.let { committed ->
            require(committed.manifest.blobId == blob.blobId)
            val intent = journal.loadIntent(transferKey)
            val envelope = intent?.dekEnvelope
                ?: throw IllegalStateException("Committed blob journal lost its DEK envelope")
            require(intent.transferKey == transferKey && intent.blob == blob) {
                "Committed blob journal belongs to another authority or generation"
            }
            return CommittedBlobUploadV2(transferKey, blob, committed.manifest, envelope, committed)
        }

        val intent = journal.loadIntent(transferKey) ?: crypto.createUploadIntent(
            session = session,
            transferKey = transferKey,
            blob = blob,
            keyEpoch = session.activeKeyEpoch,
            chunkSizeBytes = selectedChunkSize,
            createdAtEpochMillis = nowEpochMillis(),
        ).also { journal.saveIntent(it) }
        require(
            intent.transferKey == transferKey && intent.blob == blob &&
                intent.keyEpoch == session.activeKeyEpoch,
        ) {
            "A pending blob upload belongs to different content or key epoch"
        }

        val prepared = buildPlan(session, intent, gatedAccess)
        currentCoroutineContext().ensureActive()
        var uploadSession = operationGate.executeSuspending(gatedAccess, ContentOperation.SYNC_BLOB) {
            bodyApi.reserveUpload(session, capability, prepared.reservation)
        }
        validateSession(uploadSession, prepared.reservation)
        uploadSession = operationGate.executeSuspending(gatedAccess, ContentOperation.SYNC_BLOB) {
            bodyApi.uploadStatus(session, capability, uploadSession.sessionId)
        }
        validateSession(uploadSession, prepared.reservation)
        uploadMissingChunks(session, capability, intent, prepared, uploadSession, gatedAccess)

        currentCoroutineContext().ensureActive()
        val receipt = operationGate.executeSuspending(gatedAccess, ContentOperation.SYNC_BLOB) {
            bodyApi.commitUpload(
                session,
                capability,
                uploadSession.sessionId,
                BlobManifestCommitRequestV2(encryptedPrivateManifest = prepared.encryptedManifest),
            )
        }
        validateCommit(receipt, intent, prepared)
        // Durability precedes construction of the sync mutation. A crash before this point simply
        // resumes the idempotent Worker session; a crash after it reuses the exact commit receipt.
        journal.markCommitted(transferKey, receipt)
        return CommittedBlobUploadV2(transferKey, blob, receipt.manifest, intent.dekEnvelope, receipt)
    }

    private suspend fun buildPlan(
        session: SyncSession,
        intent: BlobUploadIntentV2,
        access: ContentAccessRequest,
    ): PreparedUpload {
        val plans = ArrayList<EncryptedBlobChunkPlanV2>()
        val plaintextSizes = ArrayList<Int>()
        withBlobReader(intent.blob) { reader ->
            var index = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                operationGate.requireAllowed(access, ContentOperation.SYNC_BLOB)
                val plaintext = reader.readChunk(intent.chunkSizeBytes) ?: break
                try {
                    currentCoroutineContext().ensureActive()
                    require(plaintext.isNotEmpty()) { "Blob reader returned an empty chunk before EOF" }
                    val encrypted = crypto.encryptChunk(session, intent, index, plaintext)
                    currentCoroutineContext().ensureActive()
                    plans += encrypted.plan
                    plaintextSizes += plaintext.size
                } finally {
                    plaintext.fill(0)
                }
                index += 1
                // Crypto and local blob readers are permitted to complete synchronously on every
                // platform. Explicitly yield between chunks so a foreground edge can cancel this
                // low-priority pass even on a single-threaded dispatcher.
                yield()
            }
        }
        currentCoroutineContext().ensureActive()
        require(plaintextSizes.sumOf(Int::toLong) == intent.blob.byteSize) {
            "Local blob changed while planning its upload"
        }
        val privateManifest = BlobPrivateManifestV2(blob = intent.blob, chunkPlaintextByteSizes = plaintextSizes)
        val encryptedManifest = crypto.encryptPrivateManifest(session, intent, privateManifest)
        currentCoroutineContext().ensureActive()
        val reservation = BlobUploadReservationRequestV2(
            blobId = intent.blob.blobId,
            manifestId = intent.manifestId,
            keyEpoch = intent.keyEpoch,
            chunkSizeBytes = intent.chunkSizeBytes,
            expectedBodyCiphertextBytes = plans.sumOf { it.ciphertextByteSize.toLong() },
            expectedManifestCiphertextBytes = encryptedManifest.ciphertextByteSize,
            manifestCiphertextSha256Base64Url = encryptedManifest.ciphertextSha256Base64Url,
            chunks = plans,
            initialDekEnvelope = intent.dekEnvelope,
            transferKey = intent.transferKey,
        )
        return PreparedUpload(reservation, encryptedManifest, plaintextSizes)
    }

    private suspend fun uploadMissingChunks(
        session: SyncSession,
        capability: WorkspaceCapability,
        intent: BlobUploadIntentV2,
        prepared: PreparedUpload,
        uploadSession: BlobUploadSessionV2,
        access: ContentAccessRequest,
    ) {
        val receipts = uploadSession.receivedChunks.associateBy(BlobChunkReceiptV2::index)
        for ((index, receipt) in receipts) {
            currentCoroutineContext().ensureActive()
            val expected = prepared.reservation.chunks.getOrNull(index)
                ?: throw IllegalStateException("Worker returned a receipt outside the exact chunk plan")
            require(receipt.matches(expected)) { "Worker returned a conflicting chunk receipt" }
            yield()
        }
        withBlobReader(intent.blob) { reader ->
            var index = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                operationGate.requireAllowed(access, ContentOperation.SYNC_BLOB)
                val plaintext = reader.readChunk(intent.chunkSizeBytes) ?: break
                val encrypted = try {
                    currentCoroutineContext().ensureActive()
                    require(plaintext.size == prepared.plaintextSizes[index]) {
                        "Local blob changed after its upload plan was reserved"
                    }
                    crypto.encryptChunk(session, intent, index, plaintext).also {
                        currentCoroutineContext().ensureActive()
                    }
                } finally {
                    plaintext.fill(0)
                }
                require(encrypted.plan == prepared.reservation.chunks[index]) {
                    "Blob encryption is not restart deterministic"
                }
                if (index !in receipts) {
                    val receipt = operationGate.executeSuspending(access, ContentOperation.SYNC_BLOB) {
                        bodyApi.uploadChunk(session, capability, uploadSession.sessionId, encrypted)
                    }
                    require(receipt.matches(encrypted.plan)) { "Worker returned a mismatched chunk receipt" }
                }
                index += 1
                yield()
            }
            require(index == prepared.reservation.chunks.size) { "Local blob ended before its reserved plan" }
        }
    }

    private fun validateSession(session: BlobUploadSessionV2, request: BlobUploadReservationRequestV2) {
        require(session.blobId == request.blobId && session.manifestId == request.manifestId &&
            session.keyEpoch == request.keyEpoch && session.reservedBytes == request.totalReservedBytes) {
            "Worker returned a blob upload session for a different reservation"
        }
    }

    private fun validateCommit(
        receipt: BlobBodyCommitReceiptV2,
        intent: BlobUploadIntentV2,
        prepared: PreparedUpload,
    ) {
        val remote = receipt.manifest
        require(remote.blobId == intent.blob.blobId && remote.manifestId == intent.manifestId)
        require(remote.manifestCiphertextSha256Base64Url ==
            prepared.encryptedManifest.ciphertextSha256Base64Url)
        require(remote.manifestCiphertextByteSize == prepared.encryptedManifest.ciphertextByteSize.toLong())
        require(remote.bodyCiphertextByteSize == prepared.reservation.expectedBodyCiphertextBytes)
        require(remote.chunkCount == prepared.reservation.chunks.size &&
            remote.chunkSizeBytes == intent.chunkSizeBytes)
    }

    private inline fun <T> withBlobReader(blob: BlobRef, block: (BlobReadLease) -> T): T {
        val reader = blobStore.openRead(blob) ?: throw ContentBlobStoreException.CorruptBlob(blob.blobId)
        return try {
            block(reader)
        } finally {
            reader.close()
        }
    }

    private data class PreparedUpload(
        val reservation: BlobUploadReservationRequestV2,
        val encryptedManifest: EncryptedBlobPrivateManifestV2,
        val plaintextSizes: List<Int>,
    )
}

/** Downloads, verifies, decrypts, and publishes bytes through the local blob state machine. */
public class EncryptedBlobDownloaderV2(
    private val blobStore: ContentBlobStore,
    private val bodyApi: CloudflareBlobBodyApiV2,
    private val crypto: BlobBodyCryptoV2,
    private val operationGate: ContentOperationGate,
) {
    public suspend fun download(
        session: SyncSession,
        capability: WorkspaceCapability,
        synced: SyncedBlobReferenceRecord,
        access: ContentAccessRequest,
    ): BlobPublishReceipt {
        require(synced.isRemotelyAvailable) { "Blob metadata does not describe a committed remote body" }
        val expectedBlob = requireNotNull(synced.blob).value
        val expectedRemote = requireNotNull(synced.remoteManifest).value
        val gatedAccess = access.withBlobBytes(expectedBlob.byteSize)
        operationGate.requireAllowed(gatedAccess, ContentOperation.SYNC_BLOB)
        operationGate.requireAllowed(gatedAccess, ContentOperation.OFFLINE_STORE)
        val committed = operationGate.executeSuspending(gatedAccess, ContentOperation.SYNC_BLOB) {
            bodyApi.downloadManifest(session, capability, expectedBlob.blobId)
        }
        require(committed.remote == expectedRemote) { "Remote blob manifest differs from authenticated sync metadata" }
        val envelope = committed.dekEnvelopes
            .filter { it.keyEpoch <= session.activeKeyEpoch }
            .maxByOrNull(BlobDekEnvelopeV2::keyEpoch)
            ?: throw IllegalStateException("No retained DEK envelope can open the remote blob")
        val privateManifest = crypto.decryptPrivateManifest(
            session,
            committed.encryptedPrivateManifest,
            envelope,
            committed.remote.manifestId,
        )
        currentCoroutineContext().ensureActive()
        require(privateManifest.blob == expectedBlob) { "Decrypted blob metadata differs from its synced reference" }
        require(privateManifest.chunkPlaintextByteSizes.size == committed.chunks.size)

        val stage = blobStore.beginStage(expectedBlob.byteSize, expectedBlob.mediaType)
        return try {
            for ((index, plan) in committed.chunks.withIndex()) {
                currentCoroutineContext().ensureActive()
                operationGate.requireAllowed(gatedAccess, ContentOperation.SYNC_BLOB)
                operationGate.requireAllowed(gatedAccess, ContentOperation.OFFLINE_STORE)
                val ciphertext = operationGate.executeSuspending(gatedAccess, ContentOperation.SYNC_BLOB) {
                    bodyApi.downloadChunk(session, capability, expectedBlob.blobId, plan)
                }
                currentCoroutineContext().ensureActive()
                require(ciphertext.size == plan.ciphertextByteSize) { "Downloaded blob chunk size mismatch" }
                val plaintext = crypto.decryptChunk(
                    session = session,
                    manifestId = committed.remote.manifestId,
                    envelope = envelope,
                    chunk = EncryptedBlobChunkV2(plan, ciphertext),
                    expectedPlaintextBytes = privateManifest.chunkPlaintextByteSizes[index],
                )
                try {
                    currentCoroutineContext().ensureActive()
                    operationGate.execute(gatedAccess, ContentOperation.OFFLINE_STORE) { stage.append(plaintext) }
                } finally {
                    plaintext.fill(0)
                }
                yield()
            }
            currentCoroutineContext().ensureActive()
            operationGate.execute(gatedAccess, ContentOperation.OFFLINE_STORE) {
                blobStore.publish(stage.seal(expectedBlob))
            }
        } catch (failure: Throwable) {
            stage.abort()
            throw failure
        }
    }
}

private fun ContentAccessRequest.withBlobBytes(bytes: Long): ContentAccessRequest = copy(
    context = context.copy(offlineBytes = bytes),
)

/** Selects geometry that is guaranteed to fit the Worker's exact 1024/128 MiB limits. */
internal fun selectBlobUploadChunkSize(
    plaintextBytes: Long,
    preferredChunkBytes: Int = DEFAULT_BLOB_BODY_CHUNK_BYTES,
): Int {
    require(plaintextBytes >= 0) { "Blob plaintext size cannot be negative" }
    require(preferredChunkBytes in RemoteBlobBodyManifestRefV2.MIN_CHUNK_BYTES..
        RemoteBlobBodyManifestRefV2.MAX_CHUNK_BYTES) { "Preferred blob chunk size is invalid" }
    if (plaintextBytes == 0L) return preferredChunkBytes
    require(plaintextBytes < MAX_BLOB_BODY_CIPHERTEXT_BYTES) {
        "Blob plaintext cannot fit the Worker's 128 MiB ciphertext limit"
    }

    val ciphertextHeadroom = MAX_BLOB_BODY_CIPHERTEXT_BYTES - plaintextBytes
    val maximumChunksByCiphertext = ciphertextHeadroom / BLOB_AEAD_TAG_BYTES
    require(maximumChunksByCiphertext > 0) {
        "Blob plaintext leaves no room for authenticated chunk tags"
    }
    val maximumChunks = minOf(MAX_BLOB_BODY_CHUNKS.toLong(), maximumChunksByCiphertext)
    val minimumChunkBytes = ((plaintextBytes + maximumChunks - 1) / maximumChunks)
        .coerceAtLeast(RemoteBlobBodyManifestRefV2.MIN_CHUNK_BYTES.toLong())
    require(minimumChunkBytes <= RemoteBlobBodyManifestRefV2.MAX_CHUNK_BYTES) {
        "Blob cannot fit the Worker's chunk-count limit"
    }
    val selected = maxOf(preferredChunkBytes.toLong(), minimumChunkBytes).toInt()
    val chunkCount = (plaintextBytes + selected - 1) / selected
    require(chunkCount <= MAX_BLOB_BODY_CHUNKS &&
        plaintextBytes + chunkCount * BLOB_AEAD_TAG_BYTES <= MAX_BLOB_BODY_CIPHERTEXT_BYTES) {
        "Blob cannot fit the Worker's encrypted-body limits"
    }
    return selected
}

internal const val BLOB_AEAD_TAG_BYTES: Int = 16
