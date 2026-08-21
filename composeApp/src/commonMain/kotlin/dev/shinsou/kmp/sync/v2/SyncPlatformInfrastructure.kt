package dev.shinsou.kmp.sync.v2

import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.sync.persistence.SyncInstallationStore
import dev.shinsou.kmp.sync.trust.DeviceDirectoryPinStore

/**
 * Platform-owned durable boundaries required by the v2 sync client.
 *
 * Implementations must keep secrets out of [SyncSessionStore] and [SyncStatePersistence], and
 * [close] must release the single platform-owned database driver. Sync persistence and the M1
 * content foundation receive that exact driver so schema/transaction coordination cannot drift
 * into independently owned local databases.
 */
interface SyncPlatformInfrastructure {
    val installationStore: SyncInstallationStore
    val sessionStore: SyncSessionStore
    val secretStore: SyncSecretStore
    val deviceDirectoryPinStore: DeviceDirectoryPinStore
    val platform: String
    val deviceDisplayName: String

    fun statePersistence(): SyncStatePersistence

    /** Unified local SQLite authority; callers must never close the returned driver directly. */
    fun contentDriver(): SqlDriver

    /**
     * App-private root for immutable content bodies.
     *
     * This path is device-local adapter state: it must never enter a BlobRef, backup, checkpoint,
     * or sync event. The content foundation stores only an opaque file name beside SQLite
     * lifecycle metadata.
     */
    fun contentBlobDirectory(): String

    fun close()
}
