package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.MAX_REPLY_BODY
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.MAX_REPORT_NOTE

/**
 * The account's social calls that need nothing but the session: shares, follows and blocks,
 * reviews, replies and reports. [SyncV3Service] keeps the ones that wait for a sync cycle.
 */
internal class SyncSocialClient(
    private val context: SyncV3Context,
    private val tokens: SyncSessionTokens,
) {
    private val api = context.api

    suspend fun listShares(sent: Boolean, offset: Int, limit: Int): Result<WireSharePage> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.listShares(url, it, sent, offset, limit) }
    }

    /** Received shares still waiting for an answer. */
    suspend fun pendingShares(): Result<Int> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.pendingShares(url, it) }
    }

    suspend fun dismissShare(id: String): Result<WireShare> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.answerShare(url, it, id, "dismiss") }
    }

    /** Removes a received share from the inbox, or withdraws one that was sent. */
    suspend fun deleteShare(id: String): Result<Unit> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.deleteShare(url, it, id) }
    }

    /** Accounts whose username starts with [query]; a leading `@` is ignored. */
    suspend fun searchUsers(query: String, limit: Int): Result<List<WireUserSummary>> = runCatchingCancellable {
        val q = query.trim().removePrefix("@")
        if (q.isEmpty()) return@runCatchingCancellable emptyList()
        val url = context.baseUrl
        tokens.authed { api.searchUsers(url, it, q, limit) }
    }

    suspend fun listRelation(relation: SyncRelation, offset: Int, limit: Int): Result<WireUserPage> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.listRelation(url, it, relation.path, offset, limit) }
    }

    suspend fun setFollowing(userId: String, following: Boolean): Result<Unit> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.setRelation(url, it, SyncRelation.Following.path, userId, following) }
    }

    /** Blocking also removes follows in both directions and dismisses that account's open shares. */
    suspend fun setBlocked(userId: String, blocked: Boolean): Result<Unit> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.setRelation(url, it, SyncRelation.Blocks.path, userId, blocked) }
    }

    /** Who may share with this account: `everyone`, `following` or `nobody`. */
    suspend fun receiveSharesFrom(): Result<String> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.sharingPreferences(url, it) }.receiveFrom
    }

    suspend fun setReceiveSharesFrom(value: String): Result<String> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.setSharingPreferences(url, it, value) }.receiveFrom
    }

    /** The work page: average rating, review count and the caller's own review. 404 when the server does not know the work. */
    suspend fun workPage(workHash: String): Result<WireWorkPage> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.workPage(url, it, workHash) }
    }

    /** [sort] is `newest` or `helpful`. */
    suspend fun listReviews(
        workHash: String,
        sort: String,
        offset: Int,
        limit: Int,
    ): Result<WireReviewPage> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.listReviews(url, it, workHash, sort, offset, limit) }
    }

    suspend fun deleteReview(workHash: String): Result<Unit> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.deleteReview(url, it, workHash) }
    }

    /** Idempotent: sets or clears the caller's "helpful" vote and returns the current count. */
    suspend fun setHelpful(reviewId: String, helpful: Boolean): Result<WireVote> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.setHelpful(url, it, reviewId, helpful) }
    }

    suspend fun listReplies(reviewId: String, offset: Int, limit: Int): Result<WireReplyPage> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.listReplies(url, it, reviewId, offset, limit) }
    }

    suspend fun writeReply(reviewId: String, body: String): Result<WireReply> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.createReply(url, it, reviewId, body.trim().take(MAX_REPLY_BODY)) }
    }

    suspend fun deleteReply(replyId: String): Result<Unit> = runCatchingCancellable {
        val url = context.baseUrl
        tokens.authed { api.deleteReply(url, it, replyId) }
    }

    /** [reason] is `spam`, `harassment`, `spoiler`, `illegal` or `other`. */
    suspend fun reportReview(reviewId: String, reason: String, note: String): Result<WireReport> = runCatchingCancellable {
        val url = context.baseUrl
        val text = note.trim().take(MAX_REPORT_NOTE).ifEmpty { null }
        tokens.authed { api.report(url, it, "reviews", reviewId, WireReportInput(reason, text)) }
    }

    suspend fun reportReply(replyId: String, reason: String, note: String): Result<WireReport> = runCatchingCancellable {
        val url = context.baseUrl
        val text = note.trim().take(MAX_REPORT_NOTE).ifEmpty { null }
        tokens.authed { api.report(url, it, "replies", replyId, WireReportInput(reason, text)) }
    }
}
