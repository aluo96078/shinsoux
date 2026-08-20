package dev.shinsou.kmp.sync.v2

import kotlinx.coroutines.flow.StateFlow

data class SyncDeviceSummary(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val currentDevice: Boolean,
    val revoked: Boolean,
    val lastSeenAtMillis: Long?,
)

data class SyncPairingCandidate(
    val pairingId: String,
    val displayName: String,
    val platform: String,
    val confirmationCode: String,
    val expiresAtMillis: Long,
)

/** In-memory only. Its string representation never reveals a setup/invite/pair/recovery secret. */
class EphemeralSyncPayload(private val raw: String) {
    init {
        require(raw.isNotBlank())
    }

    fun <T> use(block: (String) -> T): T = block(raw)
    suspend fun <T> useSuspending(block: suspend (String) -> T): T = block(raw)

    override fun toString(): String = "EphemeralSyncPayload(REDACTED)"
}

data class SyncShareRequest(
    val title: String,
    val payload: EphemeralSyncPayload,
    val confirmationCode: String? = null,
    val expiresAtMillis: Long,
) {
    init {
        require(title.isNotBlank() && expiresAtMillis >= 0)
        confirmationCode?.let { require(it.isNotBlank()) }
    }
}

data class SyncDeploymentRequest(
    val deployUrl: String,
    val bootstrapSecret: EphemeralSyncPayload,
) {
    init {
        require(deployUrl.startsWith("https://"))
    }
}

data class RecoveryKitExport(
    val suggestedFileName: String,
    val encoded: EphemeralSyncPayload,
)

data class SyncAdminQuota(
    val maxUsers: Int,
    val maxWorkspacesPerUser: Int,
    val maxDevicesPerUser: Int,
    val maxWorkspaceBytes: Long,
    val maxEventBytes: Int,
    val maxCheckpointBytes: Int,
) {
    init {
        require(maxUsers > 0 && maxWorkspacesPerUser > 0 && maxDevicesPerUser > 0)
        require(maxWorkspaceBytes > 0)
        require(maxEventBytes in 1_024..SYNC_PROTOCOL_MAX_EVENT_BYTES)
        require(maxCheckpointBytes in 1_048_576..SYNC_PROTOCOL_MAX_CHECKPOINT_BYTES)
        require(maxEventBytes <= maxWorkspaceBytes && maxCheckpointBytes <= maxWorkspaceBytes)
    }
}

const val SYNC_PROTOCOL_MAX_EVENT_BYTES: Int = 32 * 1_024
const val SYNC_PROTOCOL_MAX_CHECKPOINT_BYTES: Int = 32 * 1_024 * 1_024

data class SyncAdminUsageTotals(
    val activeUsers: Int,
    val activeDevices: Int,
    val activeWorkspaces: Int,
    val committedBytes: Long,
    val reservedBytes: Long,
)

data class SyncAdminWorkspaceUsage(
    val workspaceId: String,
    val status: String,
    val headSequence: Long,
    val committedBytes: Long,
    val reservedBytes: Long,
    val maximumBytes: Long,
)

data class SyncAdminDailyUsage(
    val day: String,
    val eventsWritten: Long,
    val eventBytesWritten: Long,
    val checkpointsWritten: Long,
    val checkpointBytesWritten: Long,
)

/** Instance-level metadata only. It intentionally contains no event/checkpoint payload. */
data class SyncAdminUsage(
    val generatedAtMillis: Long,
    val quota: SyncAdminQuota,
    val totals: SyncAdminUsageTotals,
    val workspaces: List<SyncAdminWorkspaceUsage>,
    val daily: List<SyncAdminDailyUsage>,
)

data class CloudflareSyncUiState(
    val status: SyncSessionStatus = SyncSessionStatus.NOT_CONFIGURED,
    /** Public Cloudflare deployment page used while the instance has not been claimed yet. */
    val deploymentUrl: String? = null,
    val endpoint: String? = null,
    val deviceDisplayName: String? = null,
    val phase: SyncEnginePhase = SyncEnginePhase.STOPPED,
    val cursor: Long = 0,
    val remoteHead: Long = 0,
    val pendingDrafts: Int = 0,
    val pendingUploads: Int = 0,
    val lastSuccessfulSyncAtMillis: Long? = null,
    val devices: List<SyncDeviceSummary> = emptyList(),
    val pairingCandidates: List<SyncPairingCandidate> = emptyList(),
    val materializationIssues: List<MaterializationIssue> = emptyList(),
    val repositoryTrustConfirmations: List<RepositoryTrustConfirmation> = emptyList(),
    val adminUsage: SyncAdminUsage? = null,
    val activeShare: SyncShareRequest? = null,
    val busy: Boolean = false,
    val diagnostic: String? = null,
) {
    val configured: Boolean
        get() = status != SyncSessionStatus.NOT_CONFIGURED

    val ready: Boolean
        get() = status == SyncSessionStatus.READY
}

/** Provider-neutral settings facade; provisioning secrets never enter AppSnapshot or routes. */
interface CloudflareSyncUiController {
    val state: StateFlow<CloudflareSyncUiState>

    suspend fun beginDeployment(): SyncDeploymentRequest
    suspend fun submitOneTimeLinkOrCode(value: String)
    suspend fun createUserInvite(): SyncShareRequest
    suspend fun createDevicePairing(): SyncShareRequest
    suspend fun approvePairing(pairingId: String, approved: Boolean)
    suspend fun dismissShare()
    suspend fun syncNow()
    suspend fun retryMaterialization()
    suspend fun repairIdentityCollision(key: SyncEntityKey)
    suspend fun acceptRepositoryTrust(baseUrl: String, proposedFingerprint: String)
    suspend fun rejectRepositoryTrust(baseUrl: String, proposedFingerprint: String)
    suspend fun refreshDevices()
    suspend fun refreshAdminUsage()
    suspend fun updateAdminQuota(quota: SyncAdminQuota)
    suspend fun revokeDevice(deviceId: String)
    suspend fun exportRecoveryKit(): RecoveryKitExport
    suspend fun importRecoveryKit(encoded: String)
    suspend fun leaveWorkspace()
}
