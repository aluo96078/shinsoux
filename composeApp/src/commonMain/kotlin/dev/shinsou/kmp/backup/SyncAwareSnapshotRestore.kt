package dev.shinsou.kmp.backup

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.sync.v2.SyncProvider
import dev.shinsou.kmp.sync.v2.SyncSessionStore
import dev.shinsou.kmp.sync.v2.SyncWorkspaceDeparture

enum class SnapshotRestoreTarget {
    /** Convert the selected replacement into durable v2 mutations/tombstones. */
    ALL_SYNCED_DEVICES,

    /** Leave the workspace first, then use the legacy device-local replacement path. */
    THIS_DEVICE_ONLY,
}

/**
 * The single entry point for interactive/automatic restore and reset when v2 may be configured.
 * Callers must present both targets to the user instead of silently selecting one.
 */
class SyncAwareSnapshotRestore(
    private val repository: ShinsouRepository,
    private val sessionStore: SyncSessionStore,
    private val workspaceDeparture: SyncWorkspaceDeparture,
) {
    suspend fun restoreBackup(
        envelope: BackupEnvelope,
        selection: RestoreSelection = RestoreSelection.All,
        restoredAt: Long = envelope.createdAt,
        target: SnapshotRestoreTarget,
    ): RestoreResult {
        val result = SnapshotBackupService.restore(
            current = repository.currentSnapshot,
            envelope = envelope,
            selection = selection,
            restoredAt = restoredAt,
        )
        val persisted = replace(result.snapshot, target)
        return result.copy(snapshot = persisted)
    }

    suspend fun importSnapshot(encoded: String, target: SnapshotRestoreTarget): AppSnapshot =
        replace(ShinsouRepository.decodeSnapshot(encoded), target)

    suspend fun reset(target: SnapshotRestoreTarget): AppSnapshot = replace(AppSnapshot(), target)

    suspend fun cloudflareConfigured(): Boolean =
        sessionStore.load()?.provider == SyncProvider.CLOUDFLARE_V2

    private suspend fun replace(snapshot: AppSnapshot, target: SnapshotRestoreTarget): AppSnapshot {
        val configured = cloudflareConfigured()
        return when {
            !configured -> repository.replaceSnapshot(snapshot)
            target == SnapshotRestoreTarget.ALL_SYNCED_DEVICES ->
                repository.replaceSnapshotAcrossSyncedDevices(snapshot)

            else -> {
                workspaceDeparture.leaveWorkspace()
                check(!cloudflareConfigured()) { "Workspace departure did not clear the active sync session" }
                repository.replaceSnapshot(snapshot)
            }
        }
    }
}
