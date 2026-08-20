package dev.shinsou.kmp.sync.v2

import kotlinx.serialization.Serializable

@Serializable
enum class SyncCheckpointCompression {
    LZ4_BLOCK_V1,
}

const val SYNC_CHECKPOINT_MAX_UNCOMPRESSED_BYTES: Int = 32 * 1_024 * 1_024

@Serializable
data class SyncCheckpointHeader(
    val envelopeVersion: Int = 1,
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val schemaVersion: Int = SYNC_STATE_SCHEMA_VERSION,
    val cipherSuite: SyncCipherSuite,
    val nonceBase64Url: String,
    val instanceId: String,
    val workspaceId: String,
    val checkpointId: String,
    val deviceId: String,
    val throughWorkspaceSeq: Long,
    val keyEpoch: Int,
    val previousStableCiphertextSha256Base64Url: String?,
    val compression: SyncCheckpointCompression,
    val uncompressedSize: Int,
    val ciphertextSha256Base64Url: String,
) {
    init {
        require(envelopeVersion > 0 && protocolVersion > 0 && schemaVersion > 0)
        require(nonceBase64Url.isNotBlank())
        require(listOf(instanceId, workspaceId, checkpointId, deviceId, ciphertextSha256Base64Url).all { it.isNotBlank() })
        require(throughWorkspaceSeq >= 0 && keyEpoch > 0)
        require(uncompressedSize in 1..SYNC_CHECKPOINT_MAX_UNCOMPRESSED_BYTES) {
            "Checkpoint uncompressed size exceeds the client hard limit"
        }
    }
}

@Serializable
data class EncryptedSyncCheckpoint(
    val header: SyncCheckpointHeader,
    val authenticatedHeaderBase64Url: String,
    val ciphertextBase64Url: String,
    val signatureBase64Url: String,
) {
    init {
        require(authenticatedHeaderBase64Url.isNotBlank())
        require(ciphertextBase64Url.isNotBlank())
        require(signatureBase64Url.isNotBlank())
    }
}

data class VerifiedSyncCheckpoint(
    val header: SyncCheckpointHeader,
    val state: SyncState,
    /** Canonical, uncompressed plaintext bytes; retained for byte-for-byte replay validation. */
    val canonicalState: BinaryData,
) {
    init {
        require(header.throughWorkspaceSeq == state.throughWorkspaceSeq) { "Checkpoint cursor mismatch" }
        require(header.keyEpoch == state.keyEpoch) { "Checkpoint key epoch mismatch" }
        require(header.schemaVersion == state.schemaVersion) { "Checkpoint schema mismatch" }
        require(header.previousStableCiphertextSha256Base64Url == state.previousStableCheckpointHash) {
            "Checkpoint chain hash mismatch"
        }
    }
}

data class CheckpointInstallResult(
    val installedThroughWorkspaceSeq: Long,
    val rebasedPendingOperationCount: Int,
    val state: LocalSyncStoreState,
)

object CheckpointInstaller {
    /**
     * Builds in temporary immutable state first. The LocalSyncStore is touched only after complete
     * replay to the fixed head succeeds, so an invalid checkpoint or event page leaves it unchanged.
     */
    suspend fun install(
        store: LocalSyncStore,
        checkpoint: VerifiedSyncCheckpoint,
        tail: List<CommittedSyncEvent>,
        fixedRemoteHead: Long,
        codec: SyncEventCodec,
    ): CheckpointInstallResult {
        require(fixedRemoteHead >= checkpoint.header.throughWorkspaceSeq) { "Remote head predates checkpoint" }
        val canonical = codec.canonicalCheckpointState(checkpoint.state.normalized())
        if (canonical != checkpoint.canonicalState) {
            throw SyncInvariantViolation("Checkpoint plaintext is not canonical or changed after verification")
        }

        var temporary = checkpoint.state.normalized()
        tail.sortedBy { it.workspaceSeq }.forEach { event -> temporary = SyncReducer.reduceCommitted(temporary, event) }
        if (temporary.throughWorkspaceSeq != fixedRemoteHead) {
            throw SyncSequenceGapException(temporary.throughWorkspaceSeq + 1, fixedRemoteHead)
        }

        val before = store.readState()
        val pendingCount = (before.drafts.values.map { it.event.opId } +
            before.sealedOutbox.values.map { it.logicalEvent.opId }).distinct().size
        store.transaction {
            installCheckpointAndRebase(temporary, fixedRemoteHead)
        }
        return CheckpointInstallResult(
            installedThroughWorkspaceSeq = fixedRemoteHead,
            rebasedPendingOperationCount = pendingCount,
            state = store.readState(),
        )
    }

    /** Validator path used before acknowledging a candidate checkpoint. */
    fun verifyCandidateByReplay(
        previousStable: VerifiedSyncCheckpoint?,
        candidate: VerifiedSyncCheckpoint,
        completeTailThroughCandidate: List<CommittedSyncEvent>,
        codec: SyncEventCodec,
    ) {
        val previousHash = previousStable?.header?.ciphertextSha256Base64Url
        if (candidate.header.previousStableCiphertextSha256Base64Url != previousHash) {
            throw SyncInvariantViolation("Candidate does not extend the supplied stable checkpoint")
        }
        var independentlyReplayed = previousStable?.state?.copy(
            keyEpoch = candidate.header.keyEpoch,
            previousStableCheckpointHash = previousHash,
        )?.normalized() ?: SyncState(
            schemaVersion = candidate.header.schemaVersion,
            keyEpoch = candidate.header.keyEpoch,
            throughWorkspaceSeq = 0,
            previousStableCheckpointHash = null,
        )
        var expectedSequence = independentlyReplayed.throughWorkspaceSeq + 1
        completeTailThroughCandidate.forEach {
            if (it.workspaceSeq != expectedSequence) {
                throw SyncSequenceGapException(expectedSequence, it.workspaceSeq)
            }
            independentlyReplayed = SyncReducer.reduceCommitted(independentlyReplayed, it)
            expectedSequence += 1
        }
        if (independentlyReplayed.throughWorkspaceSeq != candidate.state.throughWorkspaceSeq) {
            throw SyncInvariantViolation("Candidate replay did not reach its fixed watermark")
        }
        val expectedBytes = codec.canonicalCheckpointState(independentlyReplayed.normalized())
        if (expectedBytes != candidate.canonicalState) {
            throw SyncInvariantViolation("Candidate checkpoint differs from an independent full replay")
        }
    }
}
