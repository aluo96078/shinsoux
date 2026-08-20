package dev.shinsou.kmp.sync.v2

/** Result of one idempotent retention pass. */
data class SyncKeyRetentionResult(
    val recoveryBaseKeyEpoch: Int,
    val prunedKeyEpochs: Set<Int> = emptySet(),
    val outboxBlockedKeyEpochs: Set<Int> = emptySet(),
)

/**
 * Advances local key retention only from an authenticated bootstrap that exposes at least two
 * server-verified stable checkpoints. The server list is promotion ordered (newest first), so the
 * second item is the recovery base even when random checkpoint IDs sort differently.
 *
 * Secret deletion and LocalSyncStore cannot share a transaction. [planKeyEpochPruning] therefore
 * commits a durable intent first. Each epoch is then removed from the strict secret store before
 * its metadata and intent entry are atomically removed. Replaying [resumePendingPruning] after any
 * crash is safe, including a crash between those two deletion steps.
 */
class SyncKeyRetentionCoordinator(
    private val localStore: LocalSyncStore,
    private val secretStore: SyncSecretStore,
    private val nowMillis: () -> Long,
) {
    suspend fun reconcile(
        session: SyncSession,
        bootstrap: BootstrapResponse,
    ): SyncKeyRetentionResult {
        require(session.workspaceId.isNotBlank()) { "Key retention requires a workspace" }

        // A previously authenticated and durably planned deletion remains resumable even before
        // the network is reachable or a newer bootstrap can be accepted.
        val resumed = resumePendingPruning(session.workspaceId)
        if (bootstrap.retainedStableCheckpoints.size < MINIMUM_STABLE_CHECKPOINTS) return resumed

        validateBootstrap(session, bootstrap)
        val requiredEpochs = bootstrap.requiredKeyEpochs

        // This is deliberately a complete preflight before *any* local retention metadata moves.
        // Missing, unavailable, and corrupt required keys all fail closed through requireSecret.
        requiredEpochs.sorted().forEach { epoch ->
            secretStore.requireSecret(SyncSecretKey.WorkspaceEpochKey(session.workspaceId, epoch))
        }

        val recoveryBase = bootstrap.retainedStableCheckpoints[RECOVERY_BASE_INDEX]
        val before = localStore.readState()
        if (recoveryBase.keyEpoch < before.recoveryBaseKeyEpoch) {
            throw SyncInvariantViolation("Authenticated recovery-base key epoch attempted to move backwards")
        }

        val timestamp = nowMillis()
        require(timestamp >= 0) { "Key retention timestamp cannot be negative" }
        localStore.transaction {
            requiredEpochs.sorted().forEach { epoch ->
                val key = SyncSecretKey.WorkspaceEpochKey(session.workspaceId, epoch)
                val expectedStatus = if (epoch == bootstrap.activeKeyEpoch) {
                    KeyEpochStatus.ACTIVE
                } else {
                    KeyEpochStatus.RETAINED
                }
                val existing = state().keyEpochs[epoch]
                if (existing == null || existing.status != expectedStatus) {
                    retainKeyEpoch(
                        existing?.copy(status = expectedStatus)
                            ?: KeyEpochMetadata(epoch, key.redactedName, expectedStatus, timestamp),
                    )
                }
            }
            if (state().activeKeyEpoch != bootstrap.activeKeyEpoch) {
                throw SyncInvariantViolation("Bootstrap active epoch was not installed atomically")
            }
            planKeyEpochPruning(recoveryBase.keyEpoch, requiredEpochs)
        }

        val drained = resumePendingPruning(session.workspaceId)
        return SyncKeyRetentionResult(
            recoveryBaseKeyEpoch = drained.recoveryBaseKeyEpoch,
            prunedKeyEpochs = resumed.prunedKeyEpochs + drained.prunedKeyEpochs,
            outboxBlockedKeyEpochs = drained.outboxBlockedKeyEpochs,
        )
    }

    /** Continues a durable pruning intent without needing a fresh network response. */
    suspend fun resumePendingPruning(workspaceId: String): SyncKeyRetentionResult {
        require(workspaceId.isNotBlank()) { "Key retention requires a workspace" }
        val pruned = mutableSetOf<Int>()

        while (true) {
            val snapshot = localStore.readState()
            val intent = snapshot.keyEpochPruningIntent
                ?: return SyncKeyRetentionResult(snapshot.recoveryBaseKeyEpoch, pruned.toSet())
            val outboxEpochs = snapshot.sealedOutbox.values.mapTo(mutableSetOf()) { it.keyEpoch }
            val nextEpoch = intent.pendingEpochs.firstOrNull { epoch ->
                epoch in snapshot.keyEpochs && epoch !in snapshot.serverRequiredKeyEpochs &&
                    epoch != snapshot.activeKeyEpoch && epoch !in outboxEpochs
            }
            if (nextEpoch == null) {
                val blocked = intent.pendingEpochs.filterTo(mutableSetOf()) { it in outboxEpochs }
                return SyncKeyRetentionResult(snapshot.recoveryBaseKeyEpoch, pruned.toSet(), blocked)
            }

            val secretKey = SyncSecretKey.WorkspaceEpochKey(workspaceId, nextEpoch)
            deleteSecretIfPresent(secretKey)
            val metadataRemoved = localStore.transaction {
                val currentIntent = state().keyEpochPruningIntent
                when {
                    currentIntent == null || nextEpoch !in currentIntent.pendingEpochs -> false
                    nextEpoch !in prunableKeyEpochs() -> throw SyncInvariantViolation(
                        "A key epoch became retained after its durable deletion intent began",
                    )
                    else -> {
                        removeKeyEpochMetadata(nextEpoch)
                        true
                    }
                }
            }
            if (metadataRemoved) pruned += nextEpoch
        }
    }

    private suspend fun deleteSecretIfPresent(key: SyncSecretKey.WorkspaceEpochKey) {
        when (val existing = secretStore.read(key)) {
            is SyncSecretReadResult.Available,
            is SyncSecretReadResult.Corrupt,
            -> secretStore.delete(key)

            SyncSecretReadResult.Missing -> Unit // Expected after a crash before metadata commit.
            is SyncSecretReadResult.Unavailable -> throw SyncSecretAccessException.Unavailable(
                key,
                existing.diagnostic,
            )
        }
    }

    private fun validateBootstrap(session: SyncSession, bootstrap: BootstrapResponse) {
        if (bootstrap.headSeq < 0 || bootstrap.activeKeyEpoch <= 0 ||
            bootstrap.activeKeyEpoch != session.activeKeyEpoch
        ) {
            throw SyncInvariantViolation("Key retention bootstrap does not match the active session")
        }
        if (bootstrap.activeKeyEpoch !in bootstrap.requiredKeyEpochs) {
            throw SyncInvariantViolation("Bootstrap omitted the active epoch from requiredKeyEpochs")
        }
        if (bootstrap.requiredKeyEpochs.any { it <= 0 || it > bootstrap.activeKeyEpoch }) {
            throw SyncInvariantViolation("Bootstrap contains an invalid required key epoch")
        }
        val retained = bootstrap.retainedStableCheckpoints
        if (retained.map { it.checkpointId }.distinct().size != retained.size) {
            throw SyncInvariantViolation("Bootstrap repeated a retained stable checkpoint id")
        }
        if (retained.any { it.throughWorkspaceSeq > bootstrap.headSeq || it.keyEpoch > bootstrap.activeKeyEpoch }) {
            throw SyncInvariantViolation("Retained stable checkpoint exceeds the bootstrap boundary")
        }
        if (retained.zipWithNext().any { (newer, older) ->
                newer.throughWorkspaceSeq < older.throughWorkspaceSeq
            }
        ) {
            throw SyncInvariantViolation("Retained stable checkpoints are not in server promotion order")
        }
        if (retained.any { it.keyEpoch !in bootstrap.requiredKeyEpochs }) {
            throw SyncInvariantViolation("Bootstrap omitted a retained stable checkpoint key epoch")
        }
    }

    private companion object {
        const val MINIMUM_STABLE_CHECKPOINTS = 2
        const val RECOVERY_BASE_INDEX = 1
    }
}
