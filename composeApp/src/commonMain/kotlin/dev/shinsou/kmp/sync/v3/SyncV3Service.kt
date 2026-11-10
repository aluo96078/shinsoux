package dev.shinsou.kmp.sync.v3

import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.data.SnapshotMutationObserver
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.PluginKeyValueStore
import dev.shinsou.kmp.sync.v2.HlcTimestamp
import dev.shinsou.kmp.sync.v2.HybridLogicalClock
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Name, platform and version this device reports to the server. */
public data class SyncDeviceInfo(val defaultName: String, val platform: String, val appVersion: String)

/** What a background sync run tells the platform scheduler. */
public enum class SyncBackgroundResult {
    /** Synced, or nothing to do (signed out). */
    Completed,

    /** Network trouble, rate limit, server error or out of time: an early retry can succeed. */
    RetryLater,

    /** Needs the user or a fix (expired session, rejected request): wait for the next period. */
    Failed,
}

private enum class CycleOutcome { Success, Transient, Permanent }

/** Everything the Settings page shows about sync. */
public data class SyncUiState(
    val signedIn: Boolean = false,
    /** True after the server rejected our refresh token; the user has to sign in again. */
    val sessionExpired: Boolean = false,
    val serverUrl: String? = null,
    val insecureLan: Boolean = false,
    val username: String? = null,
    val deviceName: String? = null,
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    /** Local edits not pushed yet. */
    val pendingCount: Int = 0,
    /** Server records that cannot be applied here yet (unknown title, chapter or source). */
    val pendingRemoteCount: Int = 0,
    /** Library titles without a portable identity (for example a legacy source that is not installed). */
    val unsyncableCount: Int = 0,
    val rejectedCount: Int = 0,
    val streamConnected: Boolean = false,
    val lastError: String? = null,
    /** The server lets accounts share works with each other (last known answer). */
    val sharingEnabled: Boolean = false,
    /** The server offers work reviews (last known answer). */
    val reviewsEnabled: Boolean = false,
)

/** The three lists of people an account keeps. */
public enum class SyncRelation(internal val path: String) {
    Following("following"),
    Followers("followers"),
    Blocks("blocks"),
}

/**
 * Sync v3 client: account and device management, dirty tracking, push, pull, realtime stream and
 * projection of server records onto the local library. See ADR-0003 and the server's
 * `docs/spec/sync-semantics.md`.
 */
@OptIn(ExperimentalUuidApi::class, FlowPreview::class)
public class SyncV3Service internal constructor(
    private val repository: ShinsouRepository,
    private val store: SyncV3Store,
    private val identity: SyncIdentityResolver,
    private val legacyKeys: LegacySourceKeyCache,
    private val api: SyncApi,
    private val keyValues: PluginKeyValueStore,
    private val scope: CoroutineScope,
    private val device: SyncDeviceInfo,
    private val resolveLegacySource: suspend (Long) -> SourceKey?,
    private val resolveCreatableSource: suspend (packageId: String, sourceId: String) -> SourceKey?,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val pushDebounceMillis: Long = PUSH_DEBOUNCE_MILLIS,
    private val autoSyncAfterSignIn: Boolean = true,
    private val automaticIntervalMillis: Long = MIN_AUTOMATIC_INTERVAL_MILLIS,
) {
    private val mutableState = MutableStateFlow(SyncUiState())
    public val state: StateFlow<SyncUiState> = mutableState.asStateFlow()

    private val cycleMutex = Mutex()
    private val authMutex = Mutex()
    private val clockLock = SynchronousLock()
    private var clock: HybridLogicalClock? = null
    private var accessToken: String? = null
    private var accessExpiresAt: Long = 0
    private var streamJob: Job? = null
    private var debounceJob: Job? = null
    @Volatile private var foreground = false
    @Volatile private var consecutiveFailures = 0
    @Volatile private var lastCycleStartedAt = 0L
    @Volatile private var nextAutomaticAttemptAt = 0L
    @Volatile private var lastApplyRevision = -1L
    private val dirtySignal = Channel<Unit>(Channel.CONFLATED)
    private var remoteJob: Job? = null
    private val remoteSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var remoteSeq = 0L
    private val observerMutex = Mutex()
    private val dirtyObserver = DirtyObserver()
    private val observerInstalled = MutableStateFlow(false)

    private val baseUrl: String get() = store.meta(META_SERVER_URL) ?: error("Not signed in")
    private val cursor: Long get() = store.meta(META_CURSOR)?.toLongOrNull() ?: 0L

    // ---- startup ----------------------------------------------------------------------------

    /** Restores a stored session: installs the dirty tracker and schedules the first sync. */
    public suspend fun start() {
        if (store.meta(META_USER_ID) == null) {
            publishState()
            return
        }
        // Edits keep being tracked after the session expired, so signing in again uploads them.
        activateSession()
        publishState()
        if (store.meta(META_SIGNED_IN) == "1") scope.launch { refreshFeatures() }
        if (foreground && store.meta(META_SIGNED_IN) == "1") scope.launch { syncNow() }
    }

    private suspend fun activateSession() {
        val deviceId = store.meta(META_SYNC_DEVICE_ID) ?: return
        clockLock.withLock { clock = buildClock(deviceId) }
        if (store.meta(META_INITIAL_DONE) != "1") markEverythingDirty()
        installObserver()
        if (store.meta(META_SIGNED_IN) != "1") return
        startDebounce()
        if (foreground) startStream()
    }

    private fun buildClock(deviceId: String): HybridLogicalClock {
        val stored = store.meta(META_HLC)?.let { runCatching { SyncClock.parse(it) }.getOrNull() }
        val initial = if (stored != null) HlcTimestamp(stored.millis, stored.counter, deviceId) else HlcTimestamp(0, 0, deviceId)
        return HybridLogicalClock(deviceId, initial, nowMillis)
    }

    /**
     * Idempotent, and serialised with [removeObserver]: restoring a session while it is being
     * signed out cannot leave a tracker behind, because the account is re-checked under the lock
     * that sign-out holds while it forgets the account.
     */
    private suspend fun installObserver() = observerMutex.withLock {
        if (observerInstalled.value || store.meta(META_USER_ID) == null) return@withLock
        repository.setMutationObserver(dirtyObserver)
        observerInstalled.value = true
    }

    /** Removes the tracker, then runs [afterRemoval] before another install can observe the state. */
    private suspend fun removeObserver(afterRemoval: () -> Unit = {}) = observerMutex.withLock {
        if (observerInstalled.value) {
            repository.setMutationObserver(null)
            observerInstalled.value = false
        }
        afterRemoval()
    }

    // ---- account ----------------------------------------------------------------------------

    /** Validates [input], fetches `/.well-known/shinsou` and returns the address plus instance info. */
    public suspend fun discover(input: String): Result<Pair<SyncServerAddress, WireInstance>> = runCatching {
        val address = SyncNetworkPolicy.parse(input)
        val instance = api.instance(address.baseUrl)
        if (instance.apiVersions.min > 1 || instance.apiVersions.max < 1) {
            throw SyncApiException(426, "unsupported_api_version", "Server does not support API version 1")
        }
        address to instance
    }

    public suspend fun login(address: SyncServerAddress, username: String, password: String): Result<Unit> = runCatching {
        val session = api.login(address.baseUrl, WireLoginRequest(username.trim(), password, deviceInfo()))
        completeSignIn(address, session)
    }

    public suspend fun register(
        address: SyncServerAddress,
        username: String,
        password: String,
        email: String?,
        inviteCode: String?,
        setupCode: String?,
    ): Result<Unit> = runCatching {
        val session = api.register(
            address.baseUrl,
            WireRegisterRequest(
                username = username.trim().lowercase(),
                password = password,
                email = email?.trim()?.takeIf { it.isNotEmpty() },
                inviteCode = inviteCode?.trim()?.takeIf { it.isNotEmpty() },
                setupCode = setupCode?.trim()?.takeIf { it.isNotEmpty() },
                device = deviceInfo(),
            ),
        )
        completeSignIn(address, session)
    }

    private fun deviceInfo() = WireDeviceInfo(
        name = store.meta(META_DEVICE_NAME) ?: device.defaultName,
        platform = device.platform,
        appVersion = device.appVersion,
    )

    private suspend fun completeSignIn(address: SyncServerAddress, session: WireSession) {
        cycleMutex.withLock {
            val sameAccount = store.meta(META_USER_ID) == session.user.id && store.meta(META_SERVER_URL) == address.baseUrl
            if (!sameAccount) store.clearAll()
            store.setMeta(META_SERVER_URL, address.baseUrl)
            store.setMeta(META_INSECURE_LAN, if (address.insecureLan) "1" else "0")
            store.setMeta(META_USER_ID, session.user.id)
            store.setMeta(META_USERNAME, session.user.username)
            store.setMeta(META_DEVICE_ID, session.device.id)
            store.setMeta(META_SYNC_DEVICE_ID, session.device.syncDeviceId)
            store.setMeta(META_DEVICE_NAME, session.device.name)
            store.setMeta(META_SIGNED_IN, "1")
            store.setMeta(META_ERROR, null)
            saveTokens(session.accessToken, session.accessTokenExpiresAt, session.refreshToken)
            activateSession()
        }
        publishState()
        scope.launch { refreshFeatures() }
        if (autoSyncAfterSignIn) scope.launch { syncNow() }
    }

    /** Signs this device out on the server (best effort) and forgets all sync state. */
    public suspend fun logout() {
        val url = store.meta(META_SERVER_URL)
        val token = runCatching { accessTokenOrRefresh() }.getOrNull()
        if (url != null && token != null) runCatching { api.logout(url, token) }
        cycleMutex.withLock {
            stopBackgroundWork()
            keyValues.remove(KEY_ACCESS_TOKEN)
            keyValues.remove(KEY_REFRESH_TOKEN)
            accessToken = null
            removeObserver { store.clearAll() }
        }
        publishState()
    }

    public suspend fun listDevices(): Result<List<WireDevice>> = runCatching {
        val url = baseUrl
        authed { api.listDevices(url, it) }
    }

    public suspend fun renameDevice(id: String, name: String): Result<WireDevice> = runCatching {
        val url = baseUrl
        val renamed = authed { api.renameDevice(url, it, id, name.trim()) }
        if (renamed.current) {
            store.setMeta(META_DEVICE_NAME, renamed.name)
            publishState()
        }
        renamed
    }

    public suspend fun signOutDevice(id: String, isCurrent: Boolean): Result<Unit> = runCatching {
        if (isCurrent) {
            logout()
        } else {
            val url = baseUrl
            authed { api.signOutDevice(url, it, id) }
        }
    }

    // ---- sharing ----------------------------------------------------------------------------

    /** Asks the server which optional features it offers and remembers the answer. */
    public suspend fun refreshFeatures(): Result<WireFeatures> = runCatching {
        val url = baseUrl
        val features = api.instance(url).features
        // The store is only written under the cycle lock, never beside a running sync cycle.
        // Signing out or switching servers while the request was in flight makes the answer stale.
        val stored = cycleMutex.withLock {
            val current = store.meta(META_SIGNED_IN) == "1" && store.meta(META_SERVER_URL) == url
            if (current) {
                store.setMeta(META_SHARING, if (features.sharing) "1" else "0")
                store.setMeta(META_REVIEWS, if (features.reviews) "1" else "0")
            }
            current
        }
        if (stored) publishState()
        features
    }

    /**
     * The work hash to share [manga] by, or null when it cannot be shared: it has to be in the
     * library and come from a source (local-only titles have no identity another account can use).
     */
    public fun shareableWork(manga: Manga): String? {
        if (!manga.favorite) return null
        val work = runCatching { identity.workOf(manga) }.getOrNull() as? SyncedWork.Sourced ?: return null
        return work.recordKey
    }

    /** Shares [workHash] with another account. Uploads pending changes first so the server knows the work. */
    public suspend fun shareWork(workHash: String, recipientId: String, message: String): Result<WireShare> = runCatching {
        val url = baseUrl
        // Ask the store, not the published state: a title just added to the library is marked
        // dirty before the state is republished. The store is only read under the cycle lock.
        val pending = cycleMutex.withLock { store.dirtyRefCount() + store.dirtyRecordCount() }
        if (pending > 0 && !syncNow()) {
            throw SyncApiException(0, "unknown_work", "Pending changes could not be uploaded before sharing")
        }
        val text = message.trim().take(MAX_SHARE_MESSAGE).ifEmpty { null }
        authed { api.createShare(url, it, WireShareRequest(recipientId, workHash, text)) }
    }

    public suspend fun listShares(sent: Boolean, offset: Int, limit: Int = SOCIAL_PAGE_SIZE): Result<WireSharePage> = runCatching {
        val url = baseUrl
        authed { api.listShares(url, it, sent, offset, limit) }
    }

    /** Received shares still waiting for an answer. */
    public suspend fun pendingShares(): Result<Int> = runCatching {
        val url = baseUrl
        authed { api.pendingShares(url, it) }
    }

    /** Accepting adds the work to the library on the server; a sync brings it to this device. */
    public suspend fun acceptShare(id: String): Result<WireShare> = runCatching {
        val url = baseUrl
        val share = authed { api.answerShare(url, it, id, "accept") }
        scope.launch { syncNow() }
        share
    }

    public suspend fun dismissShare(id: String): Result<WireShare> = runCatching {
        val url = baseUrl
        authed { api.answerShare(url, it, id, "dismiss") }
    }

    /** Removes a received share from the inbox, or withdraws one that was sent. */
    public suspend fun deleteShare(id: String): Result<Unit> = runCatching {
        val url = baseUrl
        authed { api.deleteShare(url, it, id) }
    }

    /** Accounts whose username starts with [query]; a leading `@` is ignored. */
    public suspend fun searchUsers(query: String, limit: Int = SOCIAL_PAGE_SIZE): Result<List<WireUserSummary>> = runCatching {
        val q = query.trim().removePrefix("@")
        if (q.isEmpty()) return@runCatching emptyList()
        val url = baseUrl
        authed { api.searchUsers(url, it, q, limit) }
    }

    public suspend fun listRelation(relation: SyncRelation, offset: Int, limit: Int = SOCIAL_PAGE_SIZE): Result<WireUserPage> = runCatching {
        val url = baseUrl
        authed { api.listRelation(url, it, relation.path, offset, limit) }
    }

    public suspend fun setFollowing(userId: String, following: Boolean): Result<Unit> = runCatching {
        val url = baseUrl
        authed { api.setRelation(url, it, SyncRelation.Following.path, userId, following) }
    }

    /** Blocking also removes follows in both directions and dismisses that account's open shares. */
    public suspend fun setBlocked(userId: String, blocked: Boolean): Result<Unit> = runCatching {
        val url = baseUrl
        authed { api.setRelation(url, it, SyncRelation.Blocks.path, userId, blocked) }
    }

    /** Who may share with this account: `everyone`, `following` or `nobody`. */
    public suspend fun receiveSharesFrom(): Result<String> = runCatching {
        val url = baseUrl
        authed { api.sharingPreferences(url, it) }.receiveFrom
    }

    public suspend fun setReceiveSharesFrom(value: String): Result<String> = runCatching {
        val url = baseUrl
        authed { api.setSharingPreferences(url, it, value) }.receiveFrom
    }

    // ---- reviews ----------------------------------------------------------------------------

    /**
     * The work hash to read reviews of [manga] by, or null for titles without a portable identity
     * (local-only imports, uninstalled legacy sources). Unlike [shareableWork] this does not need
     * the title to be in the library: anyone may read reviews, only writing needs the library.
     */
    public fun reviewableWork(manga: Manga): String? =
        (runCatching { identity.workOf(manga) }.getOrNull() as? SyncedWork.Sourced)?.recordKey

    /**
     * The work hash of a source title by its source and remote id, for detail pages that show a
     * source's title directly. It is the hash [reviewableWork] gives once the title is in the library.
     */
    public fun sourceWorkHash(sourceKey: SourceKey, remotePublicationId: String): String? =
        identity.sourceWorkOf(sourceKey, remotePublicationId)?.recordKey

    /** The work page: average rating, review count and the caller's own review. 404 when the server does not know the work. */
    public suspend fun workPage(workHash: String): Result<WireWorkPage> = runCatching {
        val url = baseUrl
        authed { api.workPage(url, it, workHash) }
    }

    /** [sort] is `newest` or `helpful`. */
    public suspend fun listReviews(
        workHash: String,
        sort: String,
        offset: Int,
        limit: Int = REVIEW_PAGE_SIZE,
    ): Result<WireReviewPage> = runCatching {
        val url = baseUrl
        authed { api.listReviews(url, it, workHash, sort, offset, limit) }
    }

    /**
     * Writes or edits the caller's review. Pending library changes are uploaded first, so the
     * server knows the work and the title is in the account's library (422 unknown_work otherwise).
     */
    public suspend fun writeReview(workHash: String, rating: Int?, body: String, spoiler: Boolean): Result<WireReview> = runCatching {
        val url = baseUrl
        val pending = cycleMutex.withLock { store.dirtyRefCount() + store.dirtyRecordCount() }
        if (pending > 0) syncNow()
        val input = WireReviewInput(rating, body.trim().take(MAX_REVIEW_BODY), spoiler)
        authed { api.putReview(url, it, workHash, input) }
    }

    public suspend fun deleteReview(workHash: String): Result<Unit> = runCatching {
        val url = baseUrl
        authed { api.deleteReview(url, it, workHash) }
    }

    /** Idempotent: sets or clears the caller's "helpful" vote and returns the current count. */
    public suspend fun setHelpful(reviewId: String, helpful: Boolean): Result<WireVote> = runCatching {
        val url = baseUrl
        authed { api.setHelpful(url, it, reviewId, helpful) }
    }

    public suspend fun listReplies(reviewId: String, offset: Int, limit: Int = REVIEW_PAGE_SIZE): Result<WireReplyPage> = runCatching {
        val url = baseUrl
        authed { api.listReplies(url, it, reviewId, offset, limit) }
    }

    public suspend fun writeReply(reviewId: String, body: String): Result<WireReply> = runCatching {
        val url = baseUrl
        authed { api.createReply(url, it, reviewId, body.trim().take(MAX_REPLY_BODY)) }
    }

    public suspend fun deleteReply(replyId: String): Result<Unit> = runCatching {
        val url = baseUrl
        authed { api.deleteReply(url, it, replyId) }
    }

    /** [reason] is `spam`, `harassment`, `spoiler`, `illegal` or `other`. */
    public suspend fun reportReview(reviewId: String, reason: String, note: String): Result<WireReport> = runCatching {
        val url = baseUrl
        val text = note.trim().take(MAX_REPORT_NOTE).ifEmpty { null }
        authed { api.report(url, it, "reviews", reviewId, WireReportInput(reason, text)) }
    }

    public suspend fun reportReply(replyId: String, reason: String, note: String): Result<WireReport> = runCatching {
        val url = baseUrl
        val text = note.trim().take(MAX_REPORT_NOTE).ifEmpty { null }
        authed { api.report(url, it, "replies", replyId, WireReportInput(reason, text)) }
    }

    // ---- tokens -----------------------------------------------------------------------------

    private suspend fun saveTokens(access: String, expiresAt: Long, refresh: String) {
        // The refresh token rotates on every use: persist it before anything else can fail.
        keyValues.putString(KEY_REFRESH_TOKEN, refresh)
        keyValues.putString(KEY_ACCESS_TOKEN, access)
        store.setMeta(META_ACCESS_EXPIRES, expiresAt.toString())
        accessToken = access
        accessExpiresAt = expiresAt
    }

    private suspend fun accessTokenOrRefresh(): String = authMutex.withLock {
        val cached = accessToken ?: keyValues.getString(KEY_ACCESS_TOKEN)?.also {
            accessToken = it
            accessExpiresAt = store.meta(META_ACCESS_EXPIRES)?.toLongOrNull() ?: 0L
        }
        if (cached != null && accessExpiresAt - TOKEN_REFRESH_MARGIN_MILLIS > nowMillis()) cached else refreshLocked()
    }

    private suspend fun refreshLocked(): String {
        val url = store.meta(META_SERVER_URL) ?: throw SyncApiException(401, "unauthorized", "Not signed in")
        val refreshToken = keyValues.getString(KEY_REFRESH_TOKEN)
        if (refreshToken == null) {
            expireSession()
            throw SyncApiException(401, "unauthorized", "Signed out")
        }
        val tokens = try {
            api.refresh(url, refreshToken)
        } catch (error: SyncApiException) {
            if (error.status == 401 || error.code == "refresh_token_reused") expireSession()
            throw error
        }
        saveTokens(tokens.accessToken, tokens.accessTokenExpiresAt, tokens.refreshToken)
        // The stream authenticates once when it opens; reconnect so it follows the new token.
        if (streamJob?.isActive == true) {
            startStream(restart = true)
        }
        return tokens.accessToken
    }

    private suspend fun forceRefresh(rejectedToken: String): String = authMutex.withLock {
        // Another caller may already have refreshed while this one waited for the lock.
        val current = accessToken
        if (current != null && current != rejectedToken && accessExpiresAt > nowMillis()) current else refreshLocked()
    }

    private suspend fun <T> authed(block: suspend (String) -> T): T {
        val token = accessTokenOrRefresh()
        return try {
            block(token)
        } catch (error: SyncApiException) {
            if (!error.isUnauthorized) throw error
            block(forceRefresh(token))
        }
    }

    /** The refresh token is dead (reused or revoked): keep local data, ask for a new sign-in. */
    private suspend fun expireSession() {
        keyValues.remove(KEY_REFRESH_TOKEN)
        keyValues.remove(KEY_ACCESS_TOKEN)
        accessToken = null
        store.setMeta(META_SIGNED_IN, "0")
        stopBackgroundWork()
        mutableState.update { it.copy(signedIn = false, sessionExpired = true, syncing = false, streamConnected = false) }
    }

    // ---- lifecycle --------------------------------------------------------------------------

    /** The app came to the foreground: open the stream and catch up. */
    public suspend fun onForeground() {
        foreground = true
        if (store.meta(META_SIGNED_IN) != "1") return
        startStream()
        scope.launch { syncNow() }
    }

    /** The app is going away from the screen: flush pending pushes and close the stream. */
    public suspend fun onBackground() {
        foreground = false
        if (store.meta(META_SIGNED_IN) == "1") {
            withTimeoutOrNull(BACKGROUND_FLUSH_TIMEOUT_MILLIS) { runCycle(manual = false) }
        }
        streamJob?.cancel()
        streamJob = null
        mutableState.update { it.copy(streamConnected = false) }
    }

    /**
     * Entry point for WorkManager / BGTask: one bounded sync. Only [SyncBackgroundResult.RetryLater]
     * is worth an early retry; a failure that needs the user (or a fix) waits for the next period.
     */
    public suspend fun runBackgroundSync(): SyncBackgroundResult {
        if (store.meta(META_SIGNED_IN) != "1") return SyncBackgroundResult.Completed
        val outcome = withTimeoutOrNull(BACKGROUND_SYNC_TIMEOUT_MILLIS) { runCycleOutcome(manual = true) }
        return when (outcome) {
            CycleOutcome.Success -> SyncBackgroundResult.Completed
            CycleOutcome.Transient, null -> SyncBackgroundResult.RetryLater // null: ran out of time
            CycleOutcome.Permanent -> SyncBackgroundResult.Failed
        }
    }

    public suspend fun syncNow(): Boolean = runCycle(manual = true)

    /** Re-evaluates server records that were waiting for a title, chapter or source (no network). */
    public suspend fun retryPendingRemote() {
        if (store.meta(META_SIGNED_IN) != "1") return
        cycleMutex.withLock {
            try {
                legacyKeys.refresh(repository.currentSnapshot.mangas.mapTo(HashSet()) { it.source }, resolveLegacySource)
                applyUnapplied(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The next full cycle reports the failure.
            }
        }
        publishState()
    }

    private fun stopBackgroundWork() {
        streamJob?.cancel()
        streamJob = null
        debounceJob?.cancel()
        debounceJob = null
        remoteJob?.cancel()
        remoteJob = null
    }

    public fun close() {
        stopBackgroundWork()
    }

    /** Starts the two automatic triggers: debounced local edits and server notifications. */
    private fun startDebounce() {
        if (debounceJob?.isActive != true) {
            debounceJob = scope.launch {
                dirtySignal.receiveAsFlow().debounce(pushDebounceMillis).collect {
                    // Reading turns pages every few seconds: never run automatic cycles back to back.
                    val wait = lastCycleStartedAt + automaticIntervalMillis - nowMillis()
                    if (wait > 0) delay(wait)
                    if (nowMillis() >= nextAutomaticAttemptAt) runCycle(manual = false)
                }
            }
        }
        if (remoteJob?.isActive != true) {
            remoteJob = scope.launch {
                // One consumer of a conflated channel: at most one cycle running and one waiting,
                // however many notifications arrive meanwhile.
                remoteSignal.receiveAsFlow().collect {
                    if (remoteSeq > cursor) runCycle(manual = false)
                }
            }
        }
    }

    /** The server announced that the account reached [seq]. */
    internal fun onRemoteSeq(seq: Long) {
        if (seq > remoteSeq) remoteSeq = seq
        remoteSignal.trySend(Unit)
    }

    private fun startStream(restart: Boolean = false) {
        if (!restart && streamJob?.isActive == true) return
        streamJob?.cancel()
        streamJob = scope.launch {
            var backoff = STREAM_BACKOFF_MIN_MILLIS
            while (true) {
                val connectedAt = nowMillis()
                try {
                    val url = store.meta(META_SERVER_URL) ?: return@launch
                    val token = accessTokenOrRefresh()
                    mutableState.update { it.copy(streamConnected = true) }
                    api.stream(url, token) { seq -> onRemoteSeq(seq) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: SyncApiException) {
                    if (error.isUnauthorized) runCatching { forceRefresh(accessToken.orEmpty()) }
                } catch (_: Throwable) {
                    // Reconnect below.
                }
                mutableState.update { it.copy(streamConnected = false) }
                if (store.meta(META_SIGNED_IN) != "1") return@launch
                if (nowMillis() - connectedAt > STREAM_STABLE_MILLIS) backoff = STREAM_BACKOFF_MIN_MILLIS
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(STREAM_BACKOFF_MAX_MILLIS)
            }
        }
    }

    // ---- dirty tracking ---------------------------------------------------------------------

    private fun nextClock(): String = clockLock.withLock {
        val c = clock ?: error("Sync clock is not initialised")
        val tick = c.tick()
        SyncClock.format(tick)
    }

    private fun persistClock() {
        val last = clockLock.withLock { clock?.current() } ?: return
        store.setMeta(META_HLC, SyncClock.format(last))
    }

    private fun clockAt(millis: Long): String {
        val deviceId = clockLock.withLock { clock?.deviceId } ?: error("Sync clock is not initialised")
        return SyncClock.format(HlcTimestamp(millis.coerceIn(0, nowMillis()), 0, deviceId))
    }

    private fun observeRemote(clocks: Collection<String>) {
        if (clocks.isEmpty()) return
        clockLock.withLock {
            val c = clock ?: return
            clocks.mapNotNull { runCatching { SyncClock.parse(it) }.getOrNull() }.maxOrNull()?.let { c.observe(it) }
        }
        persistClock()
    }

    private suspend fun markEverythingDirty() {
        val snapshot = repository.currentSnapshot
        val entries = SyncDiff.markAll(snapshot, ::clockAt)
        entries.chunked(MARK_CHUNK).forEach { store.mark(it) }
        persistClock()
        store.setMeta(META_INITIAL_DONE, "1")
    }

    private inner class DirtyObserver : SnapshotMutationObserver {
        private val env = object : SyncDiffEnv {
            override fun nextClock(): String = this@SyncV3Service.nextClock()

            override fun tombstoneWork(manga: Manga): Pair<String, String?>? {
                val work = identity.workOf(manga)
                if (work != null) return work.recordKey to (work as? SyncedWork.Sourced)?.key?.hash
                return store.rememberedWork(manga.id)
            }

            override fun categoryUuid(localId: Long): String? = store.categoryUuid(localId)

            override fun forgetCategory(localId: Long) = store.unbindCategory(localId)
        }

        override suspend fun beforeCommit(previous: AppSnapshot, next: AppSnapshot) {
            try {
                val entries = SyncDiff.diff(previous, next, env)
                if (entries.isNotEmpty()) {
                    store.mark(entries)
                    persistClock()
                    dirtySignal.trySend(Unit)
                }
                if (next.chapters.size > previous.chapters.size) dirtySignal.trySend(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Never fail the user's edit because sync bookkeeping broke; the next full
                // sign-in style resweep covers it.
                store.setMeta(META_ERROR, "dirty tracking failed: ${error.message}")
                store.setMeta(META_INITIAL_DONE, "0")
            }
        }
    }

    // ---- sync cycle -------------------------------------------------------------------------

    private suspend fun runCycle(manual: Boolean): Boolean = runCycleOutcome(manual) == CycleOutcome.Success

    private suspend fun runCycleOutcome(manual: Boolean): CycleOutcome {
        if (store.meta(META_SIGNED_IN) != "1") return CycleOutcome.Permanent
        return cycleMutex.withLock {
            if (store.meta(META_SIGNED_IN) != "1") return@withLock CycleOutcome.Permanent
            mutableState.update { it.copy(syncing = true) }
            lastCycleStartedAt = nowMillis()
            try {
                if (clock == null) activateSession() // a foreground sync can beat start()
                cycleBody(force = manual)
                consecutiveFailures = 0
                nextAutomaticAttemptAt = 0
                store.setMeta(META_LAST_SYNC, nowMillis().toString())
                store.setMeta(META_ERROR, null)
                CycleOutcome.Success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SyncApiException) {
                recordFailure(error.userMessage(), error.retryAfterSeconds)
                if (error.isTransient) CycleOutcome.Transient else CycleOutcome.Permanent
            } catch (error: Throwable) {
                recordFailure(error.message ?: error::class.simpleName.orEmpty(), null)
                CycleOutcome.Permanent
            } finally {
                publishState(syncing = false)
            }
        }
    }

    private fun recordFailure(message: String, retryAfterSeconds: Int?) {
        consecutiveFailures++
        val backoff = (retryAfterSeconds?.times(1000L)
            ?: (FAILURE_BACKOFF_MIN_MILLIS shl (consecutiveFailures - 1).coerceAtMost(8)))
            .coerceAtMost(FAILURE_BACKOFF_MAX_MILLIS)
        nextAutomaticAttemptAt = nowMillis() + backoff
        store.setMeta(META_ERROR, message)
    }

    private suspend fun cycleBody(force: Boolean) {
        legacyKeys.refresh(repository.currentSnapshot.mangas.mapTo(HashSet()) { it.source }, resolveLegacySource)
        if (store.meta(META_INITIAL_DONE) != "1") markEverythingDirty()
        promoteAll()
        if (store.meta(META_RESYNC) == "1") {
            resync()
        } else {
            try {
                pullAll()
            } catch (error: SyncApiException) {
                if (error.isResyncRequired) resync() else throw error
            }
        }
        var pushed = 0
        try {
            pushed = pushAll()
        } catch (error: SyncApiException) {
            if (error.isResyncRequired) {
                resync()
                pushed = pushAll()
            } else {
                throw error
            }
        }
        if (pushed > 0) pullAll()
        applyUnapplied(force)
    }

    // ---- promote ----------------------------------------------------------------------------

    /** Turns observed edits (by local ref) into key-addressed records with their current values. */
    private fun promoteAll() {
        val refs = store.dirtyRefs(Int.MAX_VALUE)
        if (refs.isEmpty()) return
        val snapshot = repository.currentSnapshot
        identity.resetCache()
        val outgoing = SyncOutgoing(snapshot, identity, ::categoryKeyFor)
        // Tombstones first: a title removed and re-added under a new local id must end up live.
        val (gone, present) = refs.partition { (kind, ref) -> entityMissing(kind, ref, outgoing) }
        (gone + present).chunked(PROMOTE_CHUNK).forEach { chunk ->
            store.transaction { chunk.forEach { (kind, ref) -> promoteRef(kind, ref, outgoing) } }
        }
    }

    private fun entityMissing(kind: String, ref: String, out: SyncOutgoing): Boolean {
        val id = ref.substring(2).toLongOrNull() ?: return true
        return when (kind) {
            SyncKinds.CATEGORY -> id !in out.categoryById
            SyncKinds.UNIT -> id !in out.chapterById
            else -> id !in out.mangaById
        }
    }

    private fun categoryKeyFor(localId: Long): String? {
        if (localId == Category.Default.id) return "default"
        return store.categoryUuid(localId) ?: Uuid.random().toString().also { store.bindCategory(localId, it) }
    }

    private fun promoteRef(kind: String, ref: String, out: SyncOutgoing) {
        val rows = store.dirtyFor(kind, ref)
        if (rows.isEmpty()) return
        val id = ref.substring(2).toLongOrNull() ?: run { store.deleteDirty(rows); return }
        when (kind) {
            SyncKinds.CATEGORY -> {
                val category = out.categoryById[id]
                if (category == null) return tombstone(kind, rows)
                val key = categoryKeyFor(id) ?: return
                promoteInto(kind, key, rows, null, null, null, live = true) { field -> out.categoryValue(category, field) }
            }
            SyncKinds.UNIT -> {
                val chapter = out.chapterById[id]
                if (chapter == null) { store.deleteDirty(rows); return }
                val manga = out.mangaById[chapter.mangaId]
                val work = manga?.let(identity::workOf)
                if (manga == null || work == null) {
                    // Chapters of an unresolved title stay dirty and are counted as unsyncable.
                    if (manga == null) store.deleteDirty(rows)
                    return
                }
                if (work !is SyncedWork.Sourced) { store.deleteDirty(rows); return }
                val unitId = identity.unitRemoteId(manga, work, chapter) ?: return
                store.rememberWork(manga.id, work.recordKey, work.key.hash)
                val workJson = SyncWireJson.encodeToString(WireWork.serializer(), identity.describe(manga, work))
                promoteInto(kind, work.key.unitHash(unitId), rows, work.key.hash, unitId, workJson, live = true) { field ->
                    out.unitValue(chapter, field)
                }
            }
            else -> { // library, history, progress
                val manga = out.mangaById[id]
                if (manga == null) return tombstone(kind, rows)
                val work = identity.workOf(manga) ?: return
                val hash = (work as? SyncedWork.Sourced)?.key?.hash
                store.rememberWork(manga.id, work.recordKey, hash)
                val workJson = (work as? SyncedWork.Sourced)?.let {
                    SyncWireJson.encodeToString(WireWork.serializer(), identity.describe(manga, it))
                }
                val live = when (kind) {
                    SyncKinds.LIBRARY -> manga.favorite
                    else -> out.historyValue(manga, work, "lastReadAt") != null
                }
                promoteInto(kind, work.recordKey, rows, null, null, workJson, live) { field ->
                    when (kind) {
                        SyncKinds.LIBRARY -> out.libraryValue(manga, field)
                        SyncKinds.HISTORY -> out.historyValue(manga, work, field)
                        else -> out.progressValue(manga, work, field)
                    }
                }
                if (kind == SyncKinds.LIBRARY && work is SyncedWork.Local && manga.favorite) {
                    promoteLocalLibraryExtras(work.recordKey, manga, rows)
                }
            }
        }
    }

    private fun promoteInto(
        kind: String,
        key: String,
        rows: List<DirtyEntry>,
        workHash: String?,
        unitRemoteId: String?,
        workJson: String?,
        live: Boolean,
        valueOf: (String) -> JsonElement?,
    ) {
        val existing = store.loadRecord(kind, key)
        if (existing == null && !live) {
            // Never synced and already gone: there is nothing to delete on the server.
            store.deleteDirty(rows)
            return
        }
        var state = existing?.state ?: LwwMerge.LocalState()
        val fields = LinkedHashMap(state.fields)
        var deleted = state.deleted
        var cleared = state.cleared
        for (row in rows) {
            if (row.field == DirtyEntry.DELETED_FIELD) {
                val known = deleted
                val newer = known == null || LwwMerge.compare(row.clock, known.clock) > 0
                if (!newer) continue
                if (!live) {
                    deleted = LwwMerge.LocalDeleted(true, row.clock, dirty = true)
                    if (cleared == null || LwwMerge.compare(row.clock, cleared) > 0) cleared = row.clock
                } else if (known?.value == true) {
                    deleted = LwwMerge.LocalDeleted(false, row.clock, dirty = true)
                }
                continue
            }
            if (!live) continue
            val value = valueOf(row.field) ?: continue
            val current = fields[row.field]
            if (current != null) {
                if (LwwMerge.compare(current.clock, row.clock) >= 0) continue
                if (!current.dirty && current.value == value) continue
            }
            fields[row.field] = LwwMerge.LocalField(value, row.clock, dirty = true)
        }
        if (cleared != null) {
            val floor = cleared
            fields.entries.removeAll { LwwMerge.compare(it.value.clock, floor) <= 0 }
        }
        state = LwwMerge.LocalState(fields, deleted, cleared)
        val record = (existing ?: StoredRecord(kind, key)).copy(
            workHash = workHash ?: existing?.workHash,
            unitRemoteId = unitRemoteId ?: existing?.unitRemoteId,
            workJson = workJson ?: existing?.workJson,
            state = state,
            rejectReason = null,
            rejectedAt = null,
        )
        store.saveRecord(record)
        store.deleteDirty(rows)
    }

    /** `local:` library records carry title/author/kind themselves (records.md §2). */
    private fun promoteLocalLibraryExtras(key: String, manga: Manga, rows: List<DirtyEntry>) {
        val record = store.loadRecord(SyncKinds.LIBRARY, key) ?: return
        val clock = rows.map { it.clock }.maxWithOrNull { a, b -> LwwMerge.compare(a, b) } ?: return
        val author = manga.author?.trim()?.takeIf { it.isNotEmpty() }
        val extras: Map<String, JsonElement> = mapOf(
            "title" to JsonPrimitive(manga.title.trim().ifEmpty { "Untitled" }.truncated(SyncIdentityResolver.MAX_TITLE_CHARS)),
            "author" to (author?.let { JsonPrimitive(it.truncated(SyncIdentityResolver.MAX_TITLE_CHARS)) } ?: JsonNull),
            "contentKind" to JsonPrimitive(identity.contentKindOf(manga, SyncedWork.Local(key.removePrefix("local:")))),
        )
        val fields = LinkedHashMap(record.state.fields)
        var changed = false
        for ((name, value) in extras) {
            val current = fields[name]
            if (current != null && current.value == value) continue
            if (current != null && LwwMerge.compare(current.clock, clock) >= 0) continue
            fields[name] = LwwMerge.LocalField(value, clock, dirty = true)
            changed = true
        }
        if (changed) store.saveRecord(record.copy(state = record.state.copy(fields = fields)))
    }

    private fun tombstone(kind: String, rows: List<DirtyEntry>) {
        for (row in rows) {
            val key = row.tombKey
            if (row.field == DirtyEntry.DELETED_FIELD && row.aux == "true" && key != null) {
                val existing = store.loadRecord(kind, key)
                if (existing != null) {
                    val known = existing.state.deleted
                    if (known == null || LwwMerge.compare(row.clock, known.clock) > 0) {
                        val cleared = existing.state.cleared.let { c -> if (c == null || LwwMerge.compare(row.clock, c) > 0) row.clock else c }
                        val remaining = existing.state.fields.filterValues { LwwMerge.compare(it.clock, cleared) > 0 }
                        store.saveRecord(
                            existing.copy(
                                state = LwwMerge.LocalState(remaining, LwwMerge.LocalDeleted(true, row.clock, dirty = true), cleared),
                                rejectReason = null,
                                rejectedAt = null,
                            ),
                        )
                    }
                }
            }
        }
        store.deleteDirty(rows)
    }

    // ---- pull -------------------------------------------------------------------------------

    private suspend fun pullAll(fromZero: Boolean = false, seen: MutableSet<String>? = null) {
        var since = if (fromZero) 0L else cursor
        val url = baseUrl
        while (true) {
            val page = authed { api.changes(url, it, since, SyncApi.PAGE_SIZE) }
            ingest(page.records, page.nextSince)
            seen?.let { set -> page.records.forEach { set += seenKey(it.kind, it.key) } }
            since = page.nextSince
            if (!page.hasMore) break
        }
    }

    private fun seenKey(kind: String, key: String) = "$kind\u0000$key"

    /** Merges server states into the sync tables; projection onto the library happens later. */
    private fun ingest(records: List<WireRecord>, cursorAfter: Long?) {
        val remoteClocks = ArrayList<String>()
        store.transaction {
            for (wr in records) {
                val previous = store.loadRecord(wr.kind, wr.key)
                val remote = runCatching { wr.toRecordState() }.getOrNull() ?: continue
                val merged = LwwMerge.applyRemote(previous?.state, remote)
                remoteClocks += remote.fields.values.map { it.clock }
                remote.deleted?.let { remoteClocks += it.clock }
                val needsProjection = previous == null || previous.unapplied || projectionDiffers(previous.state, merged)
                store.saveRecord(
                    StoredRecord(
                        kind = wr.kind,
                        key = wr.key,
                        workHash = wr.work?.hash ?: previous?.workHash,
                        unitRemoteId = wr.unitRemoteId ?: previous?.unitRemoteId,
                        workJson = wr.work?.let { SyncWireJson.encodeToString(WireWork.serializer(), it) } ?: previous?.workJson,
                        state = merged,
                        seq = wr.seq,
                        unapplied = needsProjection,
                        pendingReason = if (needsProjection && previous?.unapplied != true) null else previous?.pendingReason,
                        rejectReason = if (merged.hasDirty()) previous?.rejectReason else null,
                        rejectedAt = if (merged.hasDirty()) previous?.rejectedAt else null,
                    ),
                )
            }
            cursorAfter?.let { store.setMeta(META_CURSOR, it.toString()) }
        }
        observeRemote(remoteClocks)
    }

    private fun projectionDiffers(a: LwwMerge.LocalState, b: LwwMerge.LocalState): Boolean {
        if (a.isLive != b.isLive) return true
        if (a.fields.size != b.fields.size) return true
        for ((name, field) in b.fields) if (a.fields[name]?.value != field.value) return true
        return false
    }

    /** 410: pull everything again and drop local records the server no longer has. */
    private suspend fun resync() {
        store.setMeta(META_RESYNC, "1")
        val seen = HashSet<String>()
        pullAll(fromZero = true, seen = seen)
        val pruned = ArrayList<Pair<String, String>>()
        for (kind in APPLY_ORDER) {
            for (key in store.recordKeys(kind)) {
                if (seenKey(kind, key) in seen) continue
                val record = store.loadRecord(kind, key) ?: continue
                if (record.state.hasDirty()) continue // edited since the last sync: it is pushed again
                val tick = nextClock()
                store.saveRecord(
                    record.copy(
                        state = LwwMerge.LocalState(emptyMap(), LwwMerge.LocalDeleted(true, tick, dirty = false), tick),
                        unapplied = true,
                        pendingReason = null,
                    ),
                )
                pruned += kind to key
            }
        }
        applyUnapplied()
        pruned.forEach { (kind, key) -> store.deleteRecord(kind, key) }
        store.setMeta(META_RESYNC, null)
    }

    // ---- push -------------------------------------------------------------------------------

    private suspend fun pushAll(): Int {
        var applied = 0
        val url = baseUrl
        while (true) {
            val batch = store.pushableRecords(MAX_PUSH_RECORDS, nowMillis(), ::retryAfterMillis)
            if (batch.isEmpty()) break
            val (request, sent) = buildPush(batch)
            if (sent.isEmpty()) break
            val response = authed { api.push(url, it, request) }
            val byKey = response.results.associateBy { it.kind to it.key }
            val ingestible = ArrayList<WireRecord>()
            var progressed = 0
            for (record in sent) {
                val result = byKey[record.kind to record.key]
                when {
                    result == null -> reject(record, "no_result")
                    result.result.startsWith("rejected:") -> reject(record, result.result.removePrefix("rejected:"))
                    else -> {
                        if (result.result == "applied") applied++
                        result.record?.let { ingestible += it } ?: reject(record, "no_record")
                        progressed++
                    }
                }
            }
            if (ingestible.isNotEmpty()) ingest(ingestible, null)
            if (progressed == 0) break
        }
        return applied
    }

    private fun reject(record: StoredRecord, reason: String) {
        store.saveRecord(record.copy(rejectReason = reason, rejectedAt = nowMillis()))
    }

    private fun retryAfterMillis(reason: String): Long? = when (reason) {
        "clock_skew", "unknown_work", "no_result", "no_record", "missing_work" -> 10 * 60_000L
        "quota_exceeded" -> 60 * 60_000L
        else -> null // invalid_*: wait for a new local edit instead of hammering the server
    }

    private fun buildPush(batch: List<StoredRecord>): Pair<WirePushRequest, List<StoredRecord>> {
        val works = LinkedHashMap<String, WireWork>()
        val records = ArrayList<WirePushRecord>()
        val sent = ArrayList<StoredRecord>()
        var budget = 0
        for (record in batch) {
            val workJson = record.workJson
            val needsWork = record.kind != SyncKinds.CATEGORY && !record.key.startsWith("local:")
            val work = workJson?.let { runCatching { SyncWireJson.decodeFromString(WireWork.serializer(), it) }.getOrNull() }
            if (needsWork && work == null) {
                reject(record, "missing_work")
                continue
            }
            val dirtyFields = record.state.fields.filterValues { it.dirty }
            val deleted = record.state.deleted?.takeIf { it.dirty }
            if (dirtyFields.isEmpty() && deleted == null) continue
            val size = dirtyFields.entries.sumOf { it.key.length + it.value.value.toString().length + it.value.clock.length } +
                (workJson?.length ?: 0) + 128
            if (sent.isNotEmpty() && budget + size > MAX_PUSH_CHARS) break
            budget += size
            work?.let { if (it.hash !in works) works[it.hash] = it }
            records += WirePushRecord(
                kind = record.kind,
                key = record.key,
                work = if (record.kind == SyncKinds.UNIT) record.workHash else null,
                unitRemoteId = if (record.kind == SyncKinds.UNIT) record.unitRemoteId else null,
                fields = if (dirtyFields.isEmpty()) null else JsonObject(dirtyFields.mapValues { it.value.value }),
                clocks = if (dirtyFields.isEmpty()) null else dirtyFields.mapValues { it.value.clock },
                deleted = deleted?.let { WireDeleted(it.value, it.clock) },
            )
            sent += record
        }
        return WirePushRequest(Uuid.random().toString(), cursor, works.values.toList(), records) to sent
    }

    // ---- apply ------------------------------------------------------------------------------

    /** Projects merged-but-unapplied records onto the library, parking those that cannot apply yet. */
    private suspend fun applyUnapplied(force: Boolean = true) {
        val unapplied = store.unappliedCount()
        if (unapplied == 0) return
        // Everything left is waiting for a title, chapter or source and nothing changed locally
        // since the last attempt: re-evaluating (an O(library) index build) cannot succeed.
        if (!force && unapplied == store.pendingRemoteCount() && repository.currentSnapshot.revision == lastApplyRevision) return
        val startSnapshot = repository.currentSnapshot
        identity.resetCache()
        val baseIndex = SyncApplier.buildWorkIndex(startSnapshot, identity)
        var starved = false
        for (kind in APPLY_ORDER) {
            var after = ""
            while (true) {
                val chunk = store.unappliedRecords(kind, after, APPLY_CHUNK)
                if (chunk.isEmpty()) break
                after = chunk.last().key
                if (!applyChunk(chunk, baseIndex)) starved = true
            }
        }
        if (starved) {
            // Local edits kept winning the snapshot race. The records stay marked unapplied; ask
            // for another automatic cycle instead of waiting for an unrelated trigger, and make
            // sure that cycle does not take the "nothing changed" shortcut above.
            lastApplyRevision = -1L
            dirtySignal.trySend(Unit)
        } else {
            lastApplyRevision = repository.currentSnapshot.revision
        }
    }

    /** False when every attempt lost the race against a local edit; the chunk stays unapplied. */
    private suspend fun applyChunk(chunk: List<StoredRecord>, baseIndex: MutableMap<String, Long>): Boolean {
        val creatable = if (chunk.first().kind == SyncKinds.LIBRARY) creatableFor(chunk, baseIndex) else emptyMap()
        repeat(APPLY_ATTEMPTS) {
            val snapshot = repository.currentSnapshot
            identity.resetCache()
            val index = HashMap(baseIndex)
            val context = SyncApplyContext(
                identity = identity,
                categoryBindings = store.allCategoryBindings().entries.associate { it.value to it.key },
                creatable = creatable,
                dirtyFields = { kind, ref -> store.dirtyFor(kind, ref).mapTo(HashSet()) { it.field } },
                nowMillis = nowMillis(),
            )
            val result = SyncApplier(snapshot, context, index).apply(chunk)
            if (result.changed && repository.replaceSnapshotFromSyncIfRevision(snapshot.revision, result.snapshot) == null) {
                return@repeat // a local edit won the race: recompute against the newer snapshot
            }
            store.transaction {
                for (record in chunk) {
                    val outcome = result.outcomes[record.kind to record.key]
                    store.setApplied(record.kind, record.key, (outcome as? ApplyOutcome.Pending)?.reason)
                    if (record.kind == SyncKinds.LIBRARY && outcome == ApplyOutcome.Applied) {
                        index[record.key]?.let { store.rememberWork(it, record.key, record.key.takeIf { k -> !k.startsWith("local:") }) }
                    }
                }
                result.newBindings.forEach { (uuid, localId) -> store.bindCategory(localId, uuid) }
                result.removedBindings.forEach { uuid -> store.categoryLocalId(uuid)?.let(store::unbindCategory) }
            }
            baseIndex.putAll(index)
            return true
        }
        return false
    }

    private suspend fun creatableFor(chunk: List<StoredRecord>, index: Map<String, Long>): Map<String, SourceKey> {
        val out = HashMap<String, SourceKey>()
        val perSource = HashMap<Pair<String, String>, SourceKey?>()
        for (record in chunk) {
            if (!record.state.isLive || record.key in index || record.key.startsWith("local:")) continue
            val work = record.workJson?.let { runCatching { SyncWireJson.decodeFromString(WireWork.serializer(), it) }.getOrNull() } ?: continue
            val source = perSource.getOrPut(work.packageId to work.sourceId) {
                runCatching { resolveCreatableSource(work.packageId, work.sourceId) }.getOrNull()
            } ?: continue
            out[record.key] = source
        }
        return out
    }

    // ---- state ------------------------------------------------------------------------------

    private fun publishState(syncing: Boolean = mutableState.value.syncing) {
        val signedIn = store.meta(META_SIGNED_IN) == "1"
        val snapshot = repository.currentSnapshot
        val unsyncable = if (signedIn) snapshot.mangas.count { it.favorite && runCatching { identity.workOf(it) }.getOrNull() == null } else 0
        mutableState.update { previous ->
            previous.copy(
                signedIn = signedIn,
                sessionExpired = if (signedIn) false else previous.sessionExpired,
                serverUrl = store.meta(META_SERVER_URL),
                insecureLan = store.meta(META_INSECURE_LAN) == "1",
                username = store.meta(META_USERNAME),
                deviceName = store.meta(META_DEVICE_NAME),
                syncing = syncing,
                lastSyncAt = store.meta(META_LAST_SYNC)?.toLongOrNull(),
                pendingCount = if (signedIn) store.dirtyRefCount() + store.dirtyRecordCount() else 0,
                pendingRemoteCount = if (signedIn) store.pendingRemoteCount() else 0,
                unsyncableCount = unsyncable,
                rejectedCount = if (signedIn) store.rejectedCount() else 0,
                lastError = store.meta(META_ERROR),
                sharingEnabled = signedIn && store.meta(META_SHARING) == "1",
                reviewsEnabled = signedIn && store.meta(META_REVIEWS) == "1",
            )
        }
    }

    private fun SyncApiException.userMessage(): String = when (code) {
        "network" -> "Network error: $message"
        "rate_limited" -> "Rate limited, retrying later"
        else -> "$code: $message"
    }

    internal companion object {
        const val META_SERVER_URL = "server_url"
        const val META_INSECURE_LAN = "insecure_lan"
        const val META_USER_ID = "user_id"
        const val META_USERNAME = "username"
        const val META_DEVICE_ID = "device_id"
        const val META_SYNC_DEVICE_ID = "sync_device_id"
        const val META_DEVICE_NAME = "device_name"
        const val META_SIGNED_IN = "signed_in"
        const val META_CURSOR = "cursor"
        const val META_LAST_SYNC = "last_sync_at"
        const val META_ERROR = "last_error"
        const val META_HLC = "hlc"
        const val META_ACCESS_EXPIRES = "access_expires_at"
        const val META_INITIAL_DONE = "initial_done"
        const val META_RESYNC = "resync"
        const val META_SHARING = "sharing_enabled"
        const val META_REVIEWS = "reviews_enabled"

        const val KEY_ACCESS_TOKEN = "sync.v3.access_token"
        const val KEY_REFRESH_TOKEN = "sync.v3.refresh_token"

        val APPLY_ORDER = listOf(SyncKinds.CATEGORY, SyncKinds.LIBRARY, SyncKinds.UNIT, SyncKinds.HISTORY, SyncKinds.PROGRESS)

        const val PUSH_DEBOUNCE_MILLIS = 2_000L
        const val MIN_AUTOMATIC_INTERVAL_MILLIS = 5_000L
        const val MAX_PUSH_RECORDS = 500
        const val MAX_PUSH_CHARS = 800_000
        const val PROMOTE_CHUNK = 500
        const val APPLY_CHUNK = 500
        const val APPLY_ATTEMPTS = 5
        const val MARK_CHUNK = 5_000
        const val TOKEN_REFRESH_MARGIN_MILLIS = 60_000L
        const val SOCIAL_PAGE_SIZE = 20
        const val MAX_SHARE_MESSAGE = 500
        const val REVIEW_PAGE_SIZE = 20
        const val MAX_REVIEW_BODY = 5000
        const val MAX_REPLY_BODY = 2000
        const val MAX_REPORT_NOTE = 500
        const val BACKGROUND_FLUSH_TIMEOUT_MILLIS = 15_000L
        const val BACKGROUND_SYNC_TIMEOUT_MILLIS = 8 * 60_000L
        const val STREAM_BACKOFF_MIN_MILLIS = 1_000L
        const val STREAM_BACKOFF_MAX_MILLIS = 60_000L
        const val STREAM_STABLE_MILLIS = 10_000L
        const val FAILURE_BACKOFF_MIN_MILLIS = 5_000L
        const val FAILURE_BACKOFF_MAX_MILLIS = 15 * 60_000L

        /** Builds the service on the shared content database; null-safe pieces come from the composition. */
        fun create(
            repository: ShinsouRepository,
            driver: SqlDriver,
            keyValues: PluginKeyValueStore,
            http: io.ktor.client.HttpClient,
            scope: CoroutineScope,
            device: SyncDeviceInfo,
            publications: (PublicationKey) -> Publication?,
            resolveLegacySource: suspend (Long) -> SourceKey?,
            resolveCreatableSource: suspend (String, String) -> SourceKey?,
            pushDebounceMillis: Long = PUSH_DEBOUNCE_MILLIS,
        ): SyncV3Service {
            val legacy = LegacySourceKeyCache()
            return SyncV3Service(
                repository = repository,
                store = SyncV3Store(driver),
                identity = SyncIdentityResolver(publications, legacy::lookup),
                legacyKeys = legacy,
                api = SyncApi(http),
                keyValues = keyValues,
                scope = scope,
                device = device,
                resolveLegacySource = resolveLegacySource,
                resolveCreatableSource = resolveCreatableSource,
                pushDebounceMillis = pushDebounceMillis,
            )
        }
    }
}

/** Synchronous view of the legacy-source lookup, refreshed from the (suspending) plugin manager. */
internal class LegacySourceKeyCache {
    private val lock = SynchronousLock()
    private var keys: Map<Long, SourceKey?> = emptyMap()

    fun lookup(sourceId: Long): SourceKey? = lock.withLock { keys[sourceId] }

    suspend fun refresh(sourceIds: Set<Long>, resolve: suspend (Long) -> SourceKey?) {
        val next = HashMap<Long, SourceKey?>()
        for (id in sourceIds) {
            if (id == 0L) continue
            next[id] = runCatching { resolve(id) }.getOrNull()
        }
        lock.withLock { keys = next }
    }

    /** Test hook. */
    fun put(sourceId: Long, key: SourceKey?) {
        lock.withLock { keys = keys + (sourceId to key) }
    }
}
