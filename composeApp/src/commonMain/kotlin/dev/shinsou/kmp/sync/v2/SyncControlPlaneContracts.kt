package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.trust.DeviceDirectoryWire

data class RotationRecipient(
    val deviceId: String,
    val authEpoch: Long,
    val wrappingPublicKeyBase64Url: String,
) {
    init {
        require(deviceId.isNotBlank() && authEpoch > 0 && wrappingPublicKeyBase64Url.isNotBlank())
    }
}

data class RotationRecoveryRecipient(
    val authEpoch: Long,
    val wrappingPublicKeyBase64Url: String,
) {
    init {
        require(authEpoch > 0 && wrappingPublicKeyBase64Url.isNotBlank())
    }
}

/** Immutable server snapshot used by the single-proposer key-rotation protocol. */
data class KeyRotationLease(
    val rotationId: String,
    val workspaceId: String,
    val fromEpoch: Int,
    val toEpoch: Int,
    val proposerDeviceId: String,
    val proposerDeviceAuthEpoch: Long,
    val membershipAuthEpoch: Long,
    val recipients: List<RotationRecipient>,
    val recovery: RotationRecoveryRecipient,
    val expiresAtMillis: Long,
) {
    init {
        require(rotationId.isNotBlank() && workspaceId.isNotBlank() && proposerDeviceId.isNotBlank())
        require(fromEpoch > 0 && toEpoch == fromEpoch + 1)
        require(proposerDeviceAuthEpoch > 0 && membershipAuthEpoch > 0 && expiresAtMillis > 0)
        require(recipients.isNotEmpty() && recipients.map(RotationRecipient::deviceId).distinct().size == recipients.size)
    }
}

data class RotationDeviceEnvelope(
    val deviceId: String,
    val wrappedKeyBase64Url: String,
)

data class RotationCommitRequest(
    val manifestCborBase64Url: String,
    val manifestSignatureBase64Url: String,
    val deviceEnvelopes: List<RotationDeviceEnvelope>,
    val recoveryWrappedKeyBase64Url: String,
)

data class RotationCommitReceipt(
    val rotationId: String,
    val workspaceId: String,
    val fromEpoch: Int,
    val activeKeyEpoch: Int,
    val keyCommitmentBase64Url: String,
    val status: String,
)

data class RotationAcknowledgementReceipt(
    val rotationId: String,
    val deviceId: String,
    val acknowledged: Boolean,
)

/** Exact committed manifest evidence. Duplicate decoded fields are checked against the CBOR client-side. */
data class CommittedRotationEvidence(
    val manifestCborBase64Url: String,
    val manifestSignatureBase64Url: String,
    val proposerDeviceId: String,
    val proposerSigningPublicKeyBase64Url: String,
    val recipientEnvelopeHashes: Map<String, String>,
    val recipientAuthEpochs: Map<String, Long>,
    val recoveryEnvelopeHashBase64Url: String,
    val status: String,
)

data class DeviceWorkspaceKeyEnvelope(
    val keyEpoch: Int,
    val rotationId: String,
    val keyCommitmentBase64Url: String,
    val wrappedKeyBase64Url: String,
    val wrappedByDeviceId: String,
    val signatureBase64Url: String,
    val rotationEvidence: CommittedRotationEvidence? = null,
)

data class WorkspaceKeyBootstrap(
    val workspaceId: String,
    val activeKeyEpoch: Int,
    val envelopes: List<DeviceWorkspaceKeyEnvelope>,
    val deviceDirectory: DeviceDirectoryWire,
    /** Authoritative control-plane gate; only a verified true value permits proposing an epoch. */
    val rotationRequired: Boolean = false,
)

data class RecoveryEpochKeyEnvelope(
    val keyEpoch: Int,
    val keyCommitmentBase64Url: String,
    val recoveryWrappedKeyBase64Url: String,
) {
    init {
        require(keyEpoch > 0 && keyCommitmentBase64Url.isNotBlank() && recoveryWrappedKeyBase64Url.isNotBlank())
    }
}

data class RecoveryWorkspaceChallenge(
    val workspaceId: String,
    val keyEpoch: Int,
    val keyCommitmentBase64Url: String,
    val recoveryWrappedKeyBase64Url: String,
    /** Historical epochs still required by a retained checkpoint or its event tail. */
    val retainedKeyEnvelopes: List<RecoveryEpochKeyEnvelope> = emptyList(),
) {
    init {
        require(workspaceId.isNotBlank() && keyEpoch > 0)
        require(keyCommitmentBase64Url.isNotBlank() && recoveryWrappedKeyBase64Url.isNotBlank())
        require(retainedKeyEnvelopes.all { it.keyEpoch < keyEpoch })
        require(retainedKeyEnvelopes.map(RecoveryEpochKeyEnvelope::keyEpoch).distinct().size == retainedKeyEnvelopes.size)
    }
}

/** The challenge secret is redacted by [SecretMaterial] and never enters logs or DTO toString output. */
data class RecoveryChallenge(
    val challengeId: String,
    val challenge: SecretMaterial,
    val expiresAtMillis: Long,
    val workspaces: List<RecoveryWorkspaceChallenge>,
) {
    init {
        require(challengeId.isNotBlank() && expiresAtMillis > 0 && workspaces.isNotEmpty())
        require(workspaces.map(RecoveryWorkspaceChallenge::workspaceId).distinct().size == workspaces.size)
    }
}

data class RecoveryDeviceRegistration(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val signingPublicKeyBase64Url: String,
    val wrappingPublicKeyBase64Url: String,
    val deviceCredential: SecretMaterial,
)

data class RecoveryWorkspaceClaimEnvelope(
    val workspaceId: String,
    val keyEpoch: Int,
    val keyCommitmentBase64Url: String,
    val deviceWrappedKeyBase64Url: String,
    val deviceEnvelopeSignatureBase64Url: String,
    /** Exact active + retained epoch keyring rewrapped to the replacement Recovery Kit. */
    val replacementRecoveryEnvelopes: List<RecoveryEpochKeyEnvelope>,
) {
    init {
        require(replacementRecoveryEnvelopes.isNotEmpty())
        require(replacementRecoveryEnvelopes.map(RecoveryEpochKeyEnvelope::keyEpoch).distinct().size ==
            replacementRecoveryEnvelopes.size)
        require(replacementRecoveryEnvelopes.any {
            it.keyEpoch == keyEpoch && it.keyCommitmentBase64Url == keyCommitmentBase64Url
        })
    }
}

data class RecoveryClaimRequest(
    val instanceId: String,
    val userId: String,
    val challengeId: String,
    val challenge: SecretMaterial,
    val device: RecoveryDeviceRegistration,
    val previousRecoverySigningPublicKeyBase64Url: String,
    val newRecoverySigningPublicKeyBase64Url: String,
    val newRecoveryWrappingPublicKeyBase64Url: String,
    /** Successor-root co-signature over the predecessor root and recovered device identity. */
    val replacementRecoveryTrustSignatureBase64Url: String,
    val workspaceEnvelopes: List<RecoveryWorkspaceClaimEnvelope>,
    val signatureBase64Url: String,
)

data class RecoveryClaimReceipt(
    val claimId: String,
    val userId: String,
    val deviceId: String,
    val rotationRequiredWorkspaceIds: List<String>,
    val workspaceBindings: List<RecoveryWorkspaceBinding> = emptyList(),
)

data class RecoveryWorkspaceBinding(
    val workspaceId: String,
    val deviceAuthEpoch: Long,
    val membershipAuthEpoch: Long,
    val activeKeyEpoch: Int,
) {
    init {
        require(workspaceId.isNotBlank())
        require(deviceAuthEpoch > 0 && membershipAuthEpoch > 0 && activeKeyEpoch > 0)
    }
}

interface SyncControlPlaneApi {
    suspend fun createRotationLease(
        session: SyncSession,
        capability: WorkspaceCapability,
        rotationId: String,
        fromEpoch: Int,
        signatureBase64Url: String,
    ): KeyRotationLease

    suspend fun commitRotation(
        session: SyncSession,
        capability: WorkspaceCapability,
        rotationId: String,
        request: RotationCommitRequest,
    ): RotationCommitReceipt

    suspend fun acknowledgeRotation(
        session: SyncSession,
        capability: WorkspaceCapability,
        rotationId: String,
        keyCommitmentBase64Url: String,
        signatureBase64Url: String,
    ): RotationAcknowledgementReceipt

    suspend fun workspaceKeyBootstrap(
        session: SyncSession,
        capability: WorkspaceCapability,
    ): WorkspaceKeyBootstrap

    suspend fun createRecoveryChallenge(endpoint: String, userId: String): RecoveryChallenge

    suspend fun claimRecovery(endpoint: String, request: RecoveryClaimRequest): RecoveryClaimReceipt
}

sealed class SyncControlPlaneException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    class Protocol(message: String, cause: Throwable? = null) : SyncControlPlaneException(message, cause)
    class KeyMismatch(message: String, cause: Throwable? = null) : SyncControlPlaneException(message, cause)
    class Trust(message: String, cause: Throwable? = null) : SyncControlPlaneException(message, cause)
    class PendingOperation(message: String) : SyncControlPlaneException(message)
}
