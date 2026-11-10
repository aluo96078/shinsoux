package dev.shinsou.kmp.app

import dev.shinsou.kmp.content.LegacyOutboxDraft
import dev.shinsou.kmp.content.SharedContentTransactionStore
import kotlinx.coroutines.yield

/** What one [ContentOutboxDiscarder.discardPending] pass removed. */
public data class ContentOutboxDiscardResult(
    val outboxDrafts: Int,
    val blobSyncJobs: Int,
)

/**
 * Empties the content outbox left behind by earlier app versions.
 *
 * Those versions wrote a sync draft with every content commit, for a sync service that never
 * shipped. Nothing writes drafts any more (sync marks changed records itself, see
 * [dev.shinsou.kmp.sync.v3.SyncDiff]), but an upgraded install still holds the old rows, and
 * blob-sync jobs are still queued by local imports. This discards both, one bounded page at a time.
 */
public class ContentOutboxDiscarder(
    private val store: SharedContentTransactionStore<LegacyOutboxDraft>,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize > 0) { "Outbox discard page size must be positive" }
    }

    public suspend fun discardPending(): ContentOutboxDiscardResult {
        var drafts = 0
        while (store.pendingOutboxCount() > 0) {
            // Acknowledged drafts leave the store, so the first page is always the next one.
            val page = store.pendingOutboxPage(afterDraftId = null, limit = pageSize)
            if (page.isEmpty()) break
            val removed = store.acknowledgeOutbox(page.mapTo(LinkedHashSet(), LegacyOutboxDraft::draftId))
            drafts += removed
            if (removed == 0) break
            yield()
        }
        val jobs = store.pendingBlobSyncJobs()
        val removedJobs = if (jobs.isEmpty()) {
            0
        } else {
            store.acknowledgeBlobSyncJobs(jobs.mapTo(LinkedHashSet()) { it.jobId })
        }
        return ContentOutboxDiscardResult(outboxDrafts = drafts, blobSyncJobs = removedJobs)
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 256
    }
}
