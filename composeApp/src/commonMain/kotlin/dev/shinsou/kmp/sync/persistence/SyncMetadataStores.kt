package dev.shinsou.kmp.sync.persistence

import dev.shinsou.kmp.sync.v2.SyncSecretKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SYNC_INSTALLATION_FORMAT_VERSION = 1

/**
 * Device-local identity created once for an installation. It is deliberately separate from a
 * configured [dev.shinsou.kmp.sync.v2.SyncSession], so setup and invite flows have a stable device
 * identity before a workspace exists.
 */
@Serializable
data class SyncInstallationIdentity(
    val formatVersion: Int = SYNC_INSTALLATION_FORMAT_VERSION,
    val installationId: String,
    val deviceId: String,
) {
    init {
        require(formatVersion == SYNC_INSTALLATION_FORMAT_VERSION) {
            "Unsupported sync installation identity format"
        }
        require(installationId.isCanonicalUuid()) { "Installation id must be a canonical UUID" }
        require(deviceId.isCanonicalUuid()) { "Device id must be a canonical UUID" }
        require(installationId != deviceId) { "Installation and device ids must be independently generated" }
    }
}

/** Missing storage creates an identity; malformed or unreadable storage must never rotate it. */
interface SyncInstallationStore {
    suspend fun loadOrCreate(): SyncInstallationIdentity
}

class SyncMetadataCorruptException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class SyncMetadataUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal fun newSyncInstallationIdentity(randomUuid: () -> String): SyncInstallationIdentity {
    val installationId = randomUuid().lowercase()
    var deviceId = randomUuid().lowercase()
    var retries = 0
    while (deviceId == installationId && retries < 3) {
        deviceId = randomUuid().lowercase()
        retries++
    }
    return SyncInstallationIdentity(
        installationId = installationId,
        deviceId = deviceId,
    )
}

/** Stable account/map identity. Unlike [SyncSecretKey.redactedName], this must be collision-free. */
internal fun SyncSecretKey.storageIdentifier(): String = when (this) {
    SyncSecretKey.DeviceCredential -> "device-credential"
    SyncSecretKey.PendingBootstrapSecret -> "pending-bootstrap-secret"
    SyncSecretKey.PendingInvitePayload -> "pending-invite-payload"
    SyncSecretKey.PendingPairingPayload -> "pending-pairing-payload"
    SyncSecretKey.DeviceSigningPrivateKey -> "device-signing-private-key"
    SyncSecretKey.DeviceWrappingPrivateKey -> "device-wrapping-private-key"
    is SyncSecretKey.WorkspaceEpochKey -> "workspace-epoch:${workspaceId.length}:$workspaceId:$epoch"
    SyncSecretKey.RecoverySigningPrivateKey -> "recovery-signing-private-key"
    SyncSecretKey.RecoveryWrappingPrivateKey -> "recovery-wrapping-private-key"
    SyncSecretKey.PendingRecoverySigningPrivateKey -> "pending-recovery-signing-private-key"
    SyncSecretKey.PendingRecoveryWrappingPrivateKey -> "pending-recovery-wrapping-private-key"
    SyncSecretKey.AccessToken -> "access-token"
    is SyncSecretKey.WorkspaceCapability -> "workspace-capability:${workspaceId.length}:$workspaceId"
}

internal val SyncMetadataJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}

private fun String.isCanonicalUuid(): Boolean {
    if (length != 36 || this != lowercase()) return false
    val hyphens = setOf(8, 13, 18, 23)
    return indices.all { index ->
        if (index in hyphens) this[index] == '-'
        else this[index] in '0'..'9' || this[index] in 'a'..'f'
    }
}
