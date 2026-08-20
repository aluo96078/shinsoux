package dev.shinsou.kmp.sync.provisioning

import dev.shinsou.kmp.sync.v2.CloudflareSyncUiState
import dev.shinsou.kmp.sync.v2.SyncAdminQuota
import dev.shinsou.kmp.sync.v2.SyncAdminUsage
import dev.shinsou.kmp.sync.v2.EphemeralSyncPayload
import dev.shinsou.kmp.sync.v2.RecoveryClaimReceipt
import dev.shinsou.kmp.sync.v2.SyncEngineState
import dev.shinsou.kmp.sync.v2.SyncEntityKey
import dev.shinsou.kmp.sync.v2.SyncMaterializationDiagnostics
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.persistence.SyncInstallationIdentity
import kotlinx.coroutines.flow.StateFlow

enum class SyncOneTimeAction(val wireName: String) {
    SETUP("setup"),
    INVITE("invite"),
    PAIR("pair"),
    EMERGENCY_RESET("emergency-reset"),
}

/** A parsed one-time payload. Secrets remain redacted even if this object reaches a debugger. */
data class ParsedSyncOneTimePayload(
    val action: SyncOneTimeAction,
    val endpoint: String,
    val instanceId: String?,
    val sessionId: String?,
    val secret: EphemeralSyncPayload?,
    val userId: String? = null,
    val workspaceId: String? = null,
) {
    override fun toString(): String =
        "ParsedSyncOneTimePayload(action=$action, endpoint=$endpoint, instanceId=$instanceId, " +
            "sessionId=$sessionId, secret=${if (secret == null) "absent" else "REDACTED"})"
}

data class ProvisioningCapabilities(
    val instanceId: String,
    val protocolVersion: Int,
    val minReaderVersion: Int,
    val minWriterVersion: Int,
    val schemaVersion: Int,
    val minSchemaReaderVersion: Int,
    val minSchemaWriterVersion: Int,
    val realtime: Boolean,
) {
    fun isCompatibleWith(
        protocolReaderVersion: Int,
        protocolWriterVersion: Int,
        schemaReaderVersion: Int,
        schemaWriterVersion: Int,
    ): Boolean =
        protocolVersion > 0 &&
            minReaderVersion in 1..protocolVersion &&
            minWriterVersion in 1..protocolVersion &&
            schemaVersion > 0 &&
            minSchemaReaderVersion in 1..schemaVersion &&
            minSchemaWriterVersion in 1..schemaVersion &&
            protocolReaderVersion > 0 &&
            protocolWriterVersion > 0 &&
            schemaReaderVersion > 0 &&
            schemaWriterVersion > 0 &&
            protocolReaderVersion >= minReaderVersion &&
            protocolReaderVersion >= protocolVersion &&
            protocolWriterVersion in minWriterVersion..protocolVersion &&
            schemaReaderVersion >= minSchemaReaderVersion &&
            schemaReaderVersion >= schemaVersion &&
            schemaWriterVersion in minSchemaWriterVersion..schemaVersion
}

data class ProvisioningDeviceRegistration(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val signingPublicKey: String,
    val wrappingPublicKey: String,
    val deviceToken: EphemeralSyncPayload,
)

data class ProvisioningInitialKeys(
    val keyCommitment: String,
    val deviceWrappedKey: String,
    val deviceEnvelopeSignature: String,
    val recoverySigningPublicKey: String,
    val recoveryWrappingPublicKey: String,
    val recoveryWrappedKey: String,
    /** Recovery-root co-signature over the immutable initial device identity. */
    val recoveryDeviceTrustSignature: String,
)

data class InitialWorkspaceClaim(
    val bootstrapOrInviteSecret: EphemeralSyncPayload,
    val userId: String,
    val workspaceId: String,
    val displayName: String,
    val device: ProvisioningDeviceRegistration,
    val initialKeys: ProvisioningInitialKeys,
    val claimSignature: String,
)

data class InitialWorkspaceClaimResult(
    val instanceId: String,
    val userId: String,
    val workspaceId: String,
    val deviceId: String,
    val keyEpoch: Int,
)

data class ProvisioningInvite(
    val inviteId: String,
    val secret: EphemeralSyncPayload,
    val expiresAtMillis: Long,
)

data class ProvisioningPairing(
    val pairingId: String,
    val secret: EphemeralSyncPayload,
    val transcriptNonce: String,
    val expiresAtMillis: Long,
)

data class ProvisioningPairingCandidateInput(
    val pairingId: String,
    val secret: EphemeralSyncPayload,
    val device: ProvisioningDeviceRegistration,
)

data class ProvisioningPairingCandidate(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val signingPublicKey: String,
    val wrappingPublicKey: String,
    val tokenCommitment: String,
)

data class ProvisioningPairingTranscript(
    val pairingId: String,
    val workspaceId: String,
    val sponsorDeviceId: String,
    val sponsorSigningPublicKey: String,
    val sponsorWrappingPublicKey: String,
    val transcriptNonce: String,
    val candidateDeviceId: String,
    val candidateDisplayName: String,
    val candidatePlatform: String,
    val candidateSigningPublicKey: String,
    val candidateWrappingPublicKey: String,
    val candidateTokenHash: String,
    val expiresAtMillis: Long,
)

data class ProvisioningRetainedCheckpoint(
    val checkpointId: String,
    val throughWorkspaceSeq: Long,
    val keyEpoch: Int,
    val ciphertextSha256: String,
)

data class ProvisioningPairKeyRequirements(
    val requiredKeyEpochs: List<Int>,
    val activeKeyEpoch: Int,
    val headSeq: Long,
    val retainedStableCheckpoints: List<ProvisioningRetainedCheckpoint>,
    val recoveryBaseCheckpointId: String?,
    val recoveryBaseThroughWorkspaceSeq: Long,
)

data class ProvisioningKeyEnvelope(
    val keyEpoch: Int,
    val keyCommitment: String,
    val wrappedKey: String,
    val wrappedByDeviceId: String? = null,
    val signature: String,
)

data class ProvisioningPairApprovalEvidence(
    val attestorDeviceId: String,
    val attestorPublicKey: String,
    val signatureDomain: String,
    val manifestJson: String,
    val signature: String,
)

data class ProvisioningPairActivation(
    val userId: String,
    val workspaceId: String,
    val deviceId: String,
    val deviceAuthEpoch: Long,
    val membershipAuthEpoch: Long,
    val activeKeyEpoch: Int,
    val keyEnvelopes: List<ProvisioningKeyEnvelope>,
    val approval: ProvisioningPairApprovalEvidence,
)

enum class ProvisioningPairingStatus {
    OPEN,
    CANDIDATE,
    APPROVED,
    CANCELLED,
}

data class ProvisioningPairingView(
    val pairingId: String,
    val workspaceId: String,
    val sponsorDeviceId: String,
    val sponsorSigningPublicKey: String,
    val sponsorWrappingPublicKey: String,
    val transcriptNonce: String,
    val status: ProvisioningPairingStatus,
    val expiresAtMillis: Long,
    val candidate: ProvisioningPairingCandidate?,
    val confirmationCode: String?,
    val keyRequirements: ProvisioningPairKeyRequirements?,
    val activation: ProvisioningPairActivation?,
)

data class ProvisioningPairApproval(
    val approved: Boolean,
    val envelopes: List<ProvisioningKeyEnvelope> = emptyList(),
    val approvalSignature: String? = null,
)

data class ProvisioningDevice(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val status: String,
    val lastSeenAtMillis: Long?,
)

data class ProvisioningRevocationWorkspaceBinding(
    val workspaceId: String,
    val revokedAtKeyEpoch: Int,
    val directoryEpochAfterRevocation: Long,
    val currentActiveKeyEpoch: Int,
    val currentRotationRequired: Boolean,
    val coveringRotationId: String? = null,
    val coveringProposerDeviceId: String? = null,
)

/** Permanent, exact operation receipt. No field contains workspace ciphertext or key material. */
data class ProvisioningDeviceRevocationReceipt(
    val revocationId: String,
    val actorDeviceId: String,
    val revokedDeviceId: String,
    val committedAtMillis: Long,
    val workspaceBindings: List<ProvisioningRevocationWorkspaceBinding>,
)

/** Enrollment root supplied to the runtime before any remote event/checkpoint verification. */
sealed interface ProvisioningTrustContext {
    data class InitialSelfAnchor(
        val deviceId: String,
        val signingPublicKey: String,
        val wrappingPublicKey: String,
        val recoverySigningPublicKey: String,
    ) : ProvisioningTrustContext

    data class PairingSponsorAnchor(
        val sponsorDeviceId: String,
        val sponsorSigningPublicKey: String,
        val sponsorWrappingPublicKey: String,
        val confirmationCode: String,
        val approvalEvidence: ProvisioningPairApprovalEvidence,
    ) : ProvisioningTrustContext

    data class RecoveryAnchor(
        val deviceId: String,
        val signingPublicKey: String,
        val wrappingPublicKey: String,
        val recoverySigningPublicKey: String,
    ) : ProvisioningTrustContext
}

/** Exact Worker identity/control-plane contract; no method returns a raw access token. */
interface CloudflareProvisioningApi {
    suspend fun capabilities(endpoint: String): ProvisioningCapabilities

    suspend fun claimSetup(endpoint: String, claim: InitialWorkspaceClaim): InitialWorkspaceClaimResult

    suspend fun redeemInvite(endpoint: String, claim: InitialWorkspaceClaim): InitialWorkspaceClaimResult

    /** Consumes an operator-created, one-time handoff only after the exact R2 purge is verified. */
    suspend fun claimEmergencyReset(
        endpoint: String,
        resetId: String,
        claim: InitialWorkspaceClaim,
    ): InitialWorkspaceClaimResult = throw SyncProvisioningException("emergency_reset_handoff_unsupported")

    /** Authenticates the staged device and proves a previously ambiguous claim committed. */
    suspend fun reconcileInitialClaim(session: SyncSession): InitialWorkspaceClaimResult?

    /**
     * Authenticates the replacement device and returns its exact committed recovery receipt.
     * A null result means the pending recovery claim is not known to have committed.
     */
    suspend fun reconcileRecoveryClaim(session: SyncSession): RecoveryClaimReceipt? = null

    suspend fun createInvite(session: SyncSession, ttlSeconds: Int = 86_400): ProvisioningInvite

    suspend fun createPairing(session: SyncSession): ProvisioningPairing

    suspend fun submitPairingCandidate(
        endpoint: String,
        candidate: ProvisioningPairingCandidateInput,
    ): ProvisioningPairingView

    suspend fun pairingAsCandidate(
        endpoint: String,
        pairingId: String,
        secret: EphemeralSyncPayload,
    ): ProvisioningPairingView

    suspend fun pairingAsSponsor(session: SyncSession, pairingId: String): ProvisioningPairingView

    suspend fun approvePairing(
        session: SyncSession,
        pairingId: String,
        approval: ProvisioningPairApproval,
    )

    suspend fun listDevices(session: SyncSession): List<ProvisioningDevice>

    /** Instance metadata available only to an authenticated instance administrator. */
    suspend fun adminUsage(session: SyncSession): SyncAdminUsage =
        throw SyncProvisioningException("admin_required")

    /** Replay-protected control mutation; the Worker atomically rejects unsafe decreases. */
    suspend fun updateAdminQuota(session: SyncSession, quota: SyncAdminQuota): SyncAdminUsage =
        throw SyncProvisioningException("admin_required")

    /**
     * Idempotent revoke bound to a caller-generated operation id. Implementations must never
     * treat an unrelated prior revocation of [deviceId] as success.
     */
    suspend fun revokeDevice(
        session: SyncSession,
        deviceId: String,
        revocationId: String,
    ): ProvisioningDeviceRevocationReceipt =
        throw SyncProvisioningException("revocation_receipt_unsupported")

    /** Returns null only when this exact operation id has not committed. */
    suspend fun deviceRevocationReceipt(
        session: SyncSession,
        revocationId: String,
    ): ProvisioningDeviceRevocationReceipt? =
        throw SyncProvisioningException("revocation_receipt_unsupported")
}

/**
 * Runtime-owned activation boundary. Initial setup/invite must seed the complete local snapshot,
 * upload an encrypted initial checkpoint, download it again, and verify it before returning.
 */
interface SyncProvisioningActivationGate {
    val engineState: StateFlow<SyncEngineState>?
    val materializationDiagnostics: StateFlow<SyncMaterializationDiagnostics>?
        get() = null

    suspend fun seedSnapshotAndVerifyInitialCheckpoint(
        linkingSession: SyncSession,
        trustContext: ProvisioningTrustContext.InitialSelfAnchor,
    )

    suspend fun verifyPairedWorkspaceAndCatchUp(
        linkingSession: SyncSession,
        trustContext: ProvisioningTrustContext.PairingSponsorAnchor,
    )

    suspend fun verifyRecoveredWorkspaceAndCatchUp(
        linkingSession: SyncSession,
        trustContext: ProvisioningTrustContext.RecoveryAnchor,
    )

    suspend fun syncNow()

    suspend fun retryMaterialization() {
        throw SyncProvisioningException("materialization_repair_unavailable")
    }

    suspend fun repairIdentityCollision(key: SyncEntityKey) {
        throw SyncProvisioningException("materialization_repair_unavailable")
    }

    suspend fun acceptRepositoryTrust(baseUrl: String, proposedFingerprint: String) {
        throw SyncProvisioningException("repository_trust_review_unavailable")
    }

    suspend fun rejectRepositoryTrust(baseUrl: String, proposedFingerprint: String) {
        throw SyncProvisioningException("repository_trust_review_unavailable")
    }

    suspend fun rotateAfterRevocation(session: SyncSession, revokedDeviceId: String)

    /** Returns only after a verified rotation (local or remote) and replacement checkpoint. */
    suspend fun reconcileDeviceRevocation(
        session: SyncSession,
        receipt: ProvisioningDeviceRevocationReceipt,
    ) {
        rotateAfterRevocation(session, receipt.revokedDeviceId)
    }

    suspend fun rotateAfterRecovery(session: SyncSession)

    suspend fun leaveWorkspace()
}

data class RecoveryUiActivation(
    val readySession: SyncSession,
    val replacementKit: EphemeralSyncPayload,
)

/**
 * Recovery coordinator owned by runtime. It returns only after claim, directory pin, catch-up,
 * immediate key rotation, and a replacement checkpoint have all been verified.
 */
fun interface SyncRecoveryUiDelegate {
    suspend fun recoverAndVerify(
        encodedKit: EphemeralSyncPayload,
        installation: SyncInstallationIdentity,
        deviceDisplayName: String,
        platform: String,
    ): RecoveryUiActivation

    /** Resumes a durable recovery LINKING state after process death or an ambiguous response. */
    suspend fun resumePendingRecovery(session: SyncSession): RecoveryUiActivation? = null
}

/** Fixed, non-secret deployment configuration supplied by the composition root. */
data class CloudflareProvisioningConfiguration(
    val deployUrl: String,
    val userDisplayName: String,
    val deviceDisplayName: String,
    val platform: String,
    val pairingPollMillis: Long = 1_000,
) {
    init {
        require(deployUrl.startsWith("https://"))
        require(userDisplayName.isNotBlank() && deviceDisplayName.isNotBlank())
        require(platform in setOf("android", "ios", "macos", "windows", "other"))
        require(pairingPollMillis in 100..30_000)
    }
}

class SyncProvisioningException(
    val safeCode: String,
    cause: Throwable? = null,
) : IllegalStateException(safeCode, cause) {
    init {
        require(SAFE_CODE.matches(safeCode))
    }

    companion object {
        private val SAFE_CODE = Regex("^[a-z0-9_]{1,80}$")
    }
}

internal fun CloudflareSyncUiState.withSafeDiagnostic(code: String): CloudflareSyncUiState =
    copy(diagnostic = code.takeIf { it.matches(Regex("^[a-z0-9_]{1,80}$")) } ?: "provisioning_failed")
