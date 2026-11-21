package dev.shinsou.kmp.content

import kotlinx.coroutines.yield

/** What one [ContentOutboxDiscarder.discardPending] pass removed. */
internal data class ContentOutboxDiscardResult(
    val outboxDrafts: Int,
    val blobSyncJobs: Int,
)

/**
 * Empties the content outbox left behind by earlier app versions.
 *
 * Those versions wrote a sync draft with every content commit, for a sync service that never
 * shipped. Nothing writes drafts any more (sync marks changed records itself, see
 * `SyncDiff` in :feature-sync), but an upgraded install still holds the old rows, and
 * blob-sync jobs are still queued by local imports. This discards both, one bounded page at a time.
 */
internal class ContentOutboxDiscarder(
    private val store: SharedContentTransactionStore<LegacyOutboxDraft>,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize > 0) { "Outbox discard page size must be positive" }
    }

    suspend fun discardPending(): ContentOutboxDiscardResult {
        var drafts = 0
        var more = store.pendingOutboxCount() > 0
        while (more) {
            // Acknowledged drafts leave the store, so the first page is always the next one.
            val page = store.pendingOutboxPage(afterDraftId = null, limit = pageSize)
            val removed = if (page.isEmpty()) {
                0
            } else {
                store.acknowledgeOutbox(page.mapTo(LinkedHashSet(), LegacyOutboxDraft::draftId))
            }
            drafts += removed
            // An empty page or a page nothing was removed from ends the pass.
            more = removed != 0
            if (more) {
                yield()
                more = store.pendingOutboxCount() > 0
            }
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
