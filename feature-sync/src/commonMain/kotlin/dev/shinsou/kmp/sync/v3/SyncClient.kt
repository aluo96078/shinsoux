package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.flow.StateFlow

/**
 * What the app's screens use of sync v3 (0007 B4), as a bundle: the account, its devices, the
 * manual sync and the work hashes titles are shared and reviewed by. [SyncV3Service] implements
 * it; the lifecycle, background and package hooks stay on the service, which only the composition
 * root holds. 0008: code depends on the narrow interfaces it is made of instead
 * (ServiceInterfaceSizeTest lists the files still naming it).
 */
public interface SyncClient : SyncAccount, SyncWorks, SyncSharing, SyncReviews

/** The sync status the screens show: signed in or not, the server's features, the last sync. */
public interface SyncStatus {
    public val state: StateFlow<SyncUiState>
}

/** The account and its devices, and asking for a sync: the sync settings. */
public interface SyncAccount : SyncStatus {
    /** Validates [input], fetches `/.well-known/shinsou` and returns the address plus instance info. */
    public suspend fun discover(input: String): Result<Pair<SyncServerAddress, WireInstance>>

    public suspend fun login(address: SyncServerAddress, username: String, password: String): Result<Unit>

    @Suppress("LongParameterList") // The fields of the registration form, as the server's register call takes them.
    public suspend fun register(
        address: SyncServerAddress,
        username: String,
        password: String,
        email: String?,
        inviteCode: String?,
        setupCode: String?,
    ): Result<Unit>

    /** Signs this device out on the server (best effort) and forgets all sync state. */
    public suspend fun logout()

    public suspend fun listDevices(): Result<List<WireDevice>>

    public suspend fun renameDevice(id: String, name: String): Result<WireDevice>

    public suspend fun signOutDevice(id: String, isCurrent: Boolean): Result<Unit>

    /** Asks the server which optional features it offers and remembers the answer. */
    public suspend fun refreshFeatures(): Result<WireFeatures>

    /** The "Sync now" button. */
    public suspend fun syncFromUser(): ManualSyncResult

    /** What this device knows about packages changed: ask for an automatic cycle. */
    public fun sourcesChanged()
}

/** The work hashes titles are shared and reviewed by, and the reading other devices hold back. */
public interface SyncWorks : SyncStatus {
    /** The work hash to share [manga] by, or null when it cannot be shared. */
    public fun shareableWork(manga: Manga): String?

    /** The work hash to read reviews of [manga] by, or null for titles without a portable identity. */
    public fun reviewableWork(manga: Manga): String?

    /** The work hash of a source title by its source and remote id. */
    public fun sourceWorkHash(sourceKey: SourceKey, remotePublicationId: String): String?

    /**
     * When each of [mangas] was last read on another device, by manga id, for titles whose synced
     * reading is held back. Reads the local store only, but blocks: never call it on the UI thread.
     */
    public fun pendingLastReadTimes(mangas: Collection<Manga>): Map<Long, Long>
}

/** Sharing titles with other accounts, and the people this account follows or blocks. */
public interface SyncSharing {
    /** Shares [workHash] with another account. Uploads pending changes first so the server knows the work. */
    public suspend fun shareWork(workHash: String, recipientId: String, message: String): Result<WireShare>

    /** Accepting adds the work to the library on the server; a sync brings it to this device. */
    public suspend fun acceptShare(id: String): Result<WireShare>

    public suspend fun listShares(sent: Boolean, offset: Int, limit: Int = SyncV3Service.SOCIAL_PAGE_SIZE): Result<WireSharePage>

    /** Received shares still waiting for an answer. */
    public suspend fun pendingShares(): Result<Int>

    public suspend fun dismissShare(id: String): Result<WireShare>

    /** Removes a received share from the inbox, or withdraws one that was sent. */
    public suspend fun deleteShare(id: String): Result<Unit>

    /** Accounts whose username starts with [query]; a leading `@` is ignored. */
    public suspend fun searchUsers(query: String, limit: Int = SyncV3Service.SOCIAL_PAGE_SIZE): Result<List<WireUserSummary>>

    public suspend fun listRelation(relation: SyncRelation, offset: Int, limit: Int = SyncV3Service.SOCIAL_PAGE_SIZE): Result<WireUserPage>

    public suspend fun setFollowing(userId: String, following: Boolean): Result<Unit>

    /** Blocking also removes follows in both directions and dismisses that account's open shares. */
    public suspend fun setBlocked(userId: String, blocked: Boolean): Result<Unit>

    /** Who may share with this account: `everyone`, `following` or `nobody`. */
    public suspend fun receiveSharesFrom(): Result<String>

    public suspend fun setReceiveSharesFrom(value: String): Result<String>
}

/** Reviews of a work and their replies. */
public interface SyncReviews {
    /** Writes or edits the caller's review; pending library changes are uploaded first. */
    public suspend fun writeReview(workHash: String, rating: Int?, body: String, spoiler: Boolean): Result<WireReview>

    /** The work page: average rating, review count and the caller's own review. */
    public suspend fun workPage(workHash: String): Result<WireWorkPage>

    /** [sort] is `newest` or `helpful`. */
    public suspend fun listReviews(
        workHash: String,
        sort: String,
        offset: Int,
        limit: Int = SyncV3Service.REVIEW_PAGE_SIZE,
    ): Result<WireReviewPage>

    public suspend fun deleteReview(workHash: String): Result<Unit>

    /** Idempotent: sets or clears the caller's "helpful" vote and returns the current count. */
    public suspend fun setHelpful(reviewId: String, helpful: Boolean): Result<WireVote>

    public suspend fun listReplies(reviewId: String, offset: Int, limit: Int = SyncV3Service.REVIEW_PAGE_SIZE): Result<WireReplyPage>

    public suspend fun writeReply(reviewId: String, body: String): Result<WireReply>

    public suspend fun deleteReply(replyId: String): Result<Unit>

    /** [reason] is `spam`, `harassment`, `spoiler`, `illegal` or `other`. */
    public suspend fun reportReview(reviewId: String, reason: String, note: String): Result<WireReport>

    public suspend fun reportReply(replyId: String, reason: String, note: String): Result<WireReport>
}
