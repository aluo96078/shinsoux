package dev.shinsou.kmp.app

import dev.shinsou.kmp.content.SharedContentTransactionStore
import dev.shinsou.kmp.sync.v2.SyncDraft
import kotlinx.coroutines.yield

/** What one [ContentOutboxDiscarder.discardPending] pass removed. */
public data class ContentOutboxDiscardResult(
    val outboxDrafts: Int,
    val blobSyncJobs: Int,
)

/**
 * Keeps the content outbox empty while the app has no sync service.
 *
 * Content producers still write sync drafts and blob-sync jobs atomically with their commits: the
 * commit journal fingerprints each batch including its outbox, so a re-committed id must carry the
 * same drafts it carried the first time. Nothing drains them any more, so this discards them
 * after the fact, one bounded page at a time, using the same acknowledgement a delivered draft got.
 */
public class ContentOutboxDiscarder(
    private val store: SharedContentTransactionStore<SyncDraft>,
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
            val removed = store.acknowledgeOutbox(page.mapTo(LinkedHashSet(), SyncDraft::draftId))
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
