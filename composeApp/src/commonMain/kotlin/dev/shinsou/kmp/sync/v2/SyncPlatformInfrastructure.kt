package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.persistence.SyncInstallationStore
import dev.shinsou.kmp.sync.trust.DeviceDirectoryPinStore

/**
 * Platform-owned durable boundaries required by the v2 sync client.
 *
 * Implementations must keep secrets out of [SyncSessionStore] and [SyncStatePersistence], and
 * [close] must release any opened database driver. [statePersistence] is a factory-style accessor
 * so merely constructing the app graph does not open the local replica database before sync is
 * configured.
 */
interface SyncPlatformInfrastructure {
    val installationStore: SyncInstallationStore
    val sessionStore: SyncSessionStore
    val secretStore: SyncSecretStore
    val deviceDirectoryPinStore: DeviceDirectoryPinStore
    val platform: String
    val deviceDisplayName: String

    fun statePersistence(): SyncStatePersistence

    fun close()
}
