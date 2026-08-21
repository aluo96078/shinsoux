package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.SharedContentTransactionStore

public data class ContentSyncOutboxDrainResult(
    val inspected: Int,
    val installed: Int,
    val replayed: Int,
    val acknowledged: Int,
    val remaining: Int,
)

/**
 * Restart-safe bridge between the shared content transaction outbox and LocalSyncStore.
 *
 * The content commit is authority for import atomicity. Draining is deliberately at-least-once:
 * LocalSyncStore commits the re-clocked event first, then the content row is acknowledged. A
 * crash in between is recognized by the deterministic op id on the next pass. No network or body
 * work runs here, and callers schedule this bridge only on a background dispatcher.
 */
public class ContentSyncOutboxDrainBridge(
    private val contentStore: SharedContentTransactionStore<SyncDraft>,
    private val localStore: LocalSyncStore,
    private val deviceId: String,
    private val nowMillis: () -> Long,
) {
    init {
        require(deviceId.isNotBlank()) { "Content outbox bridge requires a device id" }
    }

    public suspend fun drain(maxDrafts: Int = DEFAULT_MAX_DRAFTS): ContentSyncOutboxDrainResult {
        require(maxDrafts > 0) { "Content outbox drain limit must be positive" }
        val pending = contentStore.pendingOutbox()
            .sortedWith(compareBy<SyncDraft>(SyncDraft::createdAtMillis, SyncDraft::draftId))
            .take(maxDrafts)
        var installed = 0
        var replayed = 0
        var acknowledged = 0
        pending.forEach { source ->
            val wasInstalled = localStore.transaction {
                val current = state()
                if (source.event.opId in current.replica.appliedOpIds) {
                    false
                } else {
                    val wallMillis = nowMillis()
                    val localHlc = nextLocalHlc(deviceId, wallMillis)
                    applyLocalEvent(
                        event = source.event.copy(hlc = localHlc),
                        nowMillis = wallMillis,
                        // Imported content operations retain their deterministic identity. They
                        // must not be folded into a pre-existing UI coalescing draft.
                        coalescingKey = null,
                    )
                    true
                }
            }
            if (wasInstalled) installed++ else replayed++
            acknowledged += contentStore.acknowledgeOutbox(setOf(source.draftId))
        }
        return ContentSyncOutboxDrainResult(
            inspected = pending.size,
            installed = installed,
            replayed = replayed,
            acknowledged = acknowledged,
            remaining = contentStore.pendingOutbox().size,
        )
    }

    public companion object {
        public const val DEFAULT_MAX_DRAFTS: Int = 32
    }
}
