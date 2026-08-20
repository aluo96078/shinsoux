package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.SnapshotMutationObserver
import dev.shinsou.kmp.data.SnapshotReplacementGuard
import dev.shinsou.kmp.data.SnapshotReplacementOrigin
import dev.shinsou.kmp.sync.trust.DeviceDirectoryPinStore

class DirectSnapshotReplacementBlockedException : IllegalStateException(
    "Cloudflare sync is active. Choose either restore/reset on all synced devices or leave the workspace first.",
)

class SyncMutationBoundaryUnavailableException : IllegalStateException(
    "Cloudflare sync is not ready. Local synchronized changes are blocked to prevent data loss.",
)

/** Installed whenever Cloudflare owns the workspace but no verified writer can journal changes. */
object FailClosedSyncMutationObserver : SnapshotMutationObserver {
    override suspend fun beforeCommit(previous: AppSnapshot, next: AppSnapshot) {
        var nextId = 0L
        val synchronizedChanges = SnapshotMutationPlanner(
            previous = previous,
            next = next,
            initialIdentityMap = SyncIdentityMap(),
            idGenerator = SyncPortableIdGenerator { "blocked-${++nextId}" },
            forceFull = false,
        ).build().mutations
        if (synchronizedChanges.isNotEmpty()) throw SyncMutationBoundaryUnavailableException()
    }
}

/** Enforces that legacy snapshot replacement never bypasses v2 clocks, drafts, or tombstones. */
class CloudflareSnapshotReplacementGuard(
    private val sessionStore: SyncSessionStore,
) : SnapshotReplacementGuard {
    override suspend fun beforeReplace(
        origin: SnapshotReplacementOrigin,
        previous: AppSnapshot,
        requested: AppSnapshot,
    ) {
        val session = sessionStore.load()
        val cloudflareConfigured = session?.provider == SyncProvider.CLOUDFLARE_V2
        when (origin) {
            SnapshotReplacementOrigin.DIRECT -> {
                if (cloudflareConfigured) throw DirectSnapshotReplacementBlockedException()
            }

            SnapshotReplacementOrigin.SYNCHRONIZED_BULK -> {
                check(cloudflareConfigured && session?.status == SyncSessionStatus.READY) {
                    "A synchronized bulk replacement requires a ready Cloudflare workspace"
                }
            }

            SnapshotReplacementOrigin.SYNC_MATERIALIZER -> {
                check(cloudflareConfigured) {
                    "A sync materializer cannot replace the snapshot without a Cloudflare workspace"
                }
            }
        }
    }
}

fun interface SyncWorkspaceDeparture {
    /** Stops producers, deletes workspace/device credentials and makes direct local restore safe. */
    suspend fun leaveWorkspace()
}

/**
 * Local half of leaving a workspace. The optional callback should stop the engine and detach its
 * repository observer before any secret is deleted. A remote self-revoke may be attempted by that
 * callback, but local departure remains an explicit separate user action.
 */
class LocalSyncWorkspaceDeparture(
    private val sessionStore: SyncSessionStore,
    private val secretStore: SyncSecretStore,
    private val localStore: LocalSyncStore,
    private val deviceDirectoryPinStore: DeviceDirectoryPinStore? = null,
    private val stopMutationProducers: suspend () -> Unit = {},
    private val afterDeparture: suspend () -> Unit = {},
) : SyncWorkspaceDeparture {
    override suspend fun leaveWorkspace() {
        val session = sessionStore.load() ?: return
        stopMutationProducers()
        val local = localStore.readState()
        val epochs = buildSet {
            add(local.activeKeyEpoch)
            add(local.recoveryBaseKeyEpoch)
            addAll(local.keyEpochs.keys)
        }
        epochs.forEach { epoch ->
            secretStore.delete(SyncSecretKey.WorkspaceEpochKey(session.workspaceId, epoch))
        }
        secretStore.delete(SyncSecretKey.WorkspaceCapability(session.workspaceId))
        secretStore.delete(SyncSecretKey.AccessToken)
        secretStore.delete(SyncSecretKey.DeviceCredential)
        secretStore.delete(SyncSecretKey.PendingBootstrapSecret)
        secretStore.delete(SyncSecretKey.PendingInvitePayload)
        secretStore.delete(SyncSecretKey.PendingPairingPayload)
        secretStore.delete(SyncSecretKey.DeviceSigningPrivateKey)
        secretStore.delete(SyncSecretKey.DeviceWrappingPrivateKey)
        secretStore.delete(SyncSecretKey.RecoverySigningPrivateKey)
        secretStore.delete(SyncSecretKey.RecoveryWrappingPrivateKey)
        secretStore.delete(SyncSecretKey.PendingRecoverySigningPrivateKey)
        secretStore.delete(SyncSecretKey.PendingRecoveryWrappingPrivateKey)
        localStore.transaction { resetForWorkspaceDeparture() }
        deviceDirectoryPinStore?.clear(session.workspaceId)
        sessionStore.clear()
        afterDeparture()
    }
}
