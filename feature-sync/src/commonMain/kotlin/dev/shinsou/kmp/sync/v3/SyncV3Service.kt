package dev.shinsou.kmp.sync.v3

import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.contract.AccountPackage
import dev.shinsou.kmp.contract.AccountRepository
import dev.shinsou.kmp.files.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

private enum class CycleOutcome { Success, Transient, Permanent }

/**
 * Sync v3 client: account and device management, dirty tracking, push, pull, realtime stream and
 * projection of server records onto the local library. See ADR-0003 and the server's
 * `docs/spec/sync-semantics.md`.
 *
 * A facade: it owns [cycleMutex] and the order of a cycle ([cycleBody]); the work is done by
 * collaborators that each own their state ([SyncClockKeeper], [SyncSessionTokens], [SyncTriggers],
 * [SyncDirtyTracker], [SyncPuller], [SyncStatePublisher]) or none ([SyncPromoter], [SyncPusher],
 * [SyncSocialClient]).
 */
public class SyncV3Service internal constructor(
    private val repository: ShinsouRepository,
    private val store: SyncV3Store,
    private val identity: SyncIdentityResolver,
    private val legacyKeys: LegacySourceKeyCache,
    private val api: SyncApi,
    private val keyValues: KeyValueStore,
    private val scope: CoroutineScope,
    private val device: SyncDeviceInfo,
    private val resolveLegacySource: suspend (Long) -> SourceKey?,
    private val resolveCreatableSource: suspend (packageId: String, sourceId: String) -> SourceKey?,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val pushDebounceMillis: Long = PUSH_DEBOUNCE_MILLIS,
    private val autoSyncAfterSignIn: Boolean = true,
    automaticIntervalMillis: Long = MIN_AUTOMATIC_INTERVAL_MILLIS,
    private val sourceInfo: suspend () -> Map<Pair<String, String>, SyncSourceInfo> = { emptyMap() },
) : SyncClient {
    private val context = SyncV3Context(repository, store, identity, api, nowMillis)
    private val publisher = SyncStatePublisher(context)
    override val state: StateFlow<SyncUiState> = publisher.state

    /**
     * Repositories the account knows about (live server records), as suggestions for this device.
     * Merging them never changes this device's own repository list; empty when signed out.
     */
    public val accountRepositories: StateFlow<List<AccountRepository>> = publisher.accountRepositories

    /**
     * Packages the account has used (live `package` records), so this device can offer to install
     * them. Empty when signed out or when the packages switch is off here.
     */
    public val accountPackages: StateFlow<List<AccountPackage>> = publisher.accountPackages

    private val cycleMutex = Mutex()
    private val clocks = SyncClockKeeper(store, nowMillis)
    private val timing = SyncCycleTiming()
    private val tokens: SyncSessionTokens = SyncSessionTokens(
        context,
        keyValues,
        onRefreshed = { triggers.restartStreamIfActive() },
        onExpired = {
            triggers.stopBackgroundWork()
            publisher.update { it.copy(signedIn = false, sessionExpired = true, syncing = false, streamConnected = false) }
        },
    )
    private val triggers: SyncTriggers = SyncTriggers(
        context,
        scope,
        tokens,
        publisher,
        SyncTriggerSettings(pushDebounceMillis, automaticIntervalMillis, timing) { runCycle(manual = false) },
    )
    private val dirty = SyncDirtyTracker(context, clocks, triggers::signalDirty)
    private val promoter = SyncPromoter(context) { dirty.installedPackages }
    private val puller = SyncPuller(context, tokens, clocks, SyncPullHooks(resolveCreatableSource, triggers::signalDirty))
    private val pusher = SyncPusher(context, tokens, puller)
    private val social = SyncSocialClient(context, tokens)
    @Volatile private var lastApplySources: String? = null

    private val baseUrl: String get() = context.baseUrl

    // ---- startup ----------------------------------------------------------------------------

    /** Restores a stored session: installs the dirty tracker and schedules the first sync. */
    public suspend fun start(): Unit = offCaller {
        if (store.meta(META_USER_ID) == null) {
            publisher.publishState()
            return@offCaller
        }
        // Edits keep being tracked after the session expired, so signing in again uploads them.
        activateSession()
        publisher.publishState()
        if (store.meta(META_SIGNED_IN) == "1") scope.launch { refreshFeatures() }
        if (triggers.foreground && store.meta(META_SIGNED_IN) == "1") scope.launch { syncQueued() }
    }

    private suspend fun activateSession() {
        val deviceId = store.meta(META_SYNC_DEVICE_ID) ?: return
        clocks.install(deviceId)
        dirty.ensureSeeded()
        dirty.installObserver()
        if (store.meta(META_SIGNED_IN) != "1") return
        triggers.startDebounce()
        triggers.startStreamIfInForeground()
    }

    // ---- account ----------------------------------------------------------------------------

    /** Validates [input], fetches `/.well-known/shinsou` and returns the address plus instance info. */
    override suspend fun discover(input: String): Result<Pair<SyncServerAddress, WireInstance>> = offCallerCatching {
        val address = SyncNetworkPolicy.parse(input)
        val instance = api.instance(address.baseUrl)
        if (instance.apiVersions.min > 1 || instance.apiVersions.max < 1) {
            throw SyncApiException(426, "unsupported_api_version", "Server does not support API version 1")
        }
        address to instance
    }

    override suspend fun login(address: SyncServerAddress, username: String, password: String): Result<Unit> = offCallerCatching {
        val session = api.login(address.baseUrl, WireLoginRequest(username.trim(), password, deviceInfo()))
        completeSignIn(address, session)
    }

    override suspend fun register(
        address: SyncServerAddress,
        username: String,
        password: String,
        email: String?,
        inviteCode: String?,
        setupCode: String?,
    ): Result<Unit> = offCallerCatching {
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

    private suspend fun completeSignIn(address: SyncServerAddress, session: WireSession): Unit = offCaller {
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
            tokens.saveTokens(session.accessToken, session.accessTokenExpiresAt, session.refreshToken)
            activateSession()
        }
        publisher.publishState()
        scope.launch { refreshFeatures() }
        if (autoSyncAfterSignIn) scope.launch { syncQueued() }
    }

    /** Signs this device out on the server (best effort) and forgets all sync state. */
    override suspend fun logout(): Unit = offCaller {
        val url = store.meta(META_SERVER_URL)
        // Signing out locally never depends on the server: an expired session simply skips the call.
        val token = runCatchingCancellable { tokens.accessTokenOrRefresh() }.getOrNull()
        if (url != null && token != null) {
            runCatchingCancellable { api.logout(url, token) }
                .onFailure { Log.warn("Sync", it) { "Signing out on the sync server failed; signing out locally" } }
        }
        cycleMutex.withLock {
            triggers.stopBackgroundWork()
            tokens.forgetTokens()
            dirty.removeObserver { store.clearAll() }
        }
        publisher.publishState()
    }

    override suspend fun listDevices(): Result<List<WireDevice>> = offCallerCatching {
        val url = baseUrl
        tokens.authed { api.listDevices(url, it) }
    }

    override suspend fun renameDevice(id: String, name: String): Result<WireDevice> = offCallerCatching {
        val url = baseUrl
        val renamed = tokens.authed { api.renameDevice(url, it, id, name.trim()) }
        if (renamed.current) {
            store.setMeta(META_DEVICE_NAME, renamed.name)
            publisher.publishState()
        }
        renamed
    }

    override suspend fun signOutDevice(id: String, isCurrent: Boolean): Result<Unit> = offCallerCatching {
        if (isCurrent) {
            logout()
        } else {
            val url = baseUrl
            tokens.authed { api.signOutDevice(url, it, id) }
        }
    }

    // ---- sharing ----------------------------------------------------------------------------

    /** Asks the server which optional features it offers and remembers the answer. */
    override suspend fun refreshFeatures(): Result<WireFeatures> = offCallerCatching {
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
        if (stored) publisher.publishState()
        features
    }

    /**
     * The work hash to share [manga] by, or null when it cannot be shared: it has to be in the
     * library and come from a source (local-only titles have no identity another account can use).
     */
    override fun shareableWork(manga: Manga): String? {
        if (!manga.favorite) return null
        // A title without a portable identity cannot be shared; that is an answer, not a failure.
        return (runCatching { identity.workOf(manga) }.getOrNull() as? SyncedWork.Sourced)?.recordKey
    }

    /** Shares [workHash] with another account. Uploads pending changes first so the server knows the work. */
    override suspend fun shareWork(workHash: String, recipientId: String, message: String): Result<WireShare> = offCallerCatching {
        val url = baseUrl
        uploadBeforeShare()
        val text = message.trim().take(MAX_SHARE_MESSAGE).ifEmpty { null }
        tokens.authed { api.createShare(url, it, WireShareRequest(recipientId, workHash, text)) }
    }

    private suspend fun uploadBeforeShare() {
        // Ask the store, not the published state: a title just added to the library is marked
        // dirty before the state is republished. The store is only read under the cycle lock.
        val pending = cycleMutex.withLock { context.pendingLocalCount() }
        if (pending > 0 && !syncQueued()) {
            throw SyncApiException(0, "unknown_work", "Pending changes could not be uploaded before sharing")
        }
    }

    /** Accepting adds the work to the library on the server; a sync brings it to this device. */
    override suspend fun acceptShare(id: String): Result<WireShare> = offCallerCatching {
        val url = baseUrl
        val share = tokens.authed { api.answerShare(url, it, id, "accept") }
        scope.launch { syncQueued() }
        share
    }

    override suspend fun listShares(sent: Boolean, offset: Int, limit: Int): Result<WireSharePage> =
        offCaller { social.listShares(sent, offset, limit) }

    /** Received shares still waiting for an answer. */
    override suspend fun pendingShares(): Result<Int> =
        offCaller { social.pendingShares() }

    override suspend fun dismissShare(id: String): Result<WireShare> =
        offCaller { social.dismissShare(id) }

    /** Removes a received share from the inbox, or withdraws one that was sent. */
    override suspend fun deleteShare(id: String): Result<Unit> =
        offCaller { social.deleteShare(id) }

    /** Accounts whose username starts with [query]; a leading `@` is ignored. */
    override suspend fun searchUsers(query: String, limit: Int): Result<List<WireUserSummary>> =
        offCaller { social.searchUsers(query, limit) }

    override suspend fun listRelation(relation: SyncRelation, offset: Int, limit: Int): Result<WireUserPage> =
        offCaller { social.listRelation(relation, offset, limit) }

    override suspend fun setFollowing(userId: String, following: Boolean): Result<Unit> =
        offCaller { social.setFollowing(userId, following) }

    /** Blocking also removes follows in both directions and dismisses that account's open shares. */
    override suspend fun setBlocked(userId: String, blocked: Boolean): Result<Unit> =
        offCaller { social.setBlocked(userId, blocked) }

    /** Who may share with this account: `everyone`, `following` or `nobody`. */
    override suspend fun receiveSharesFrom(): Result<String> =
        offCaller { social.receiveSharesFrom() }

    override suspend fun setReceiveSharesFrom(value: String): Result<String> =
        offCaller { social.setReceiveSharesFrom(value) }

    // ---- reviews ----------------------------------------------------------------------------

    /**
     * The work hash to read reviews of [manga] by, or null for titles without a portable identity
     * (local-only imports, uninstalled legacy sources). Unlike [shareableWork] this does not need
     * the title to be in the library: anyone may read reviews, only writing needs the library.
     */
    override fun reviewableWork(manga: Manga): String? =
        // A title without a portable identity has no reviews; that is an answer, not a failure.
        (runCatching { identity.workOf(manga) }.getOrNull() as? SyncedWork.Sourced)?.recordKey

    /**
     * The work hash of a source title by its source and remote id, for detail pages that show a
     * source's title directly. It is the hash [reviewableWork] gives once the title is in the library.
     */
    override fun sourceWorkHash(sourceKey: SourceKey, remotePublicationId: String): String? =
        identity.sourceWorkOf(sourceKey, remotePublicationId)?.recordKey

    /**
     * Reading of a source title that other devices synced but this device holds back until it
     * stores the chapters it is about, so the title's page can show it meanwhile. Reads the local
     * store only, but blocks: never call it on the UI thread. Null when nothing is held back.
     */
    public fun pendingReading(sourceKey: SourceKey, remotePublicationId: String): SyncPendingReading? {
        val work = identity.sourceWorkOf(sourceKey, remotePublicationId) ?: return null
        return runCatching { SyncPendingReading.of(store.readingWaitingForUnit(work.recordKey, context.disabledKinds())) }
            .onFailure { Log.warn("Sync", it) { "Reading the synced progress held back for a title failed" } }
            .getOrNull()
    }

    /**
     * When each of [mangas] was last read on another device, by manga id, for titles whose synced
     * reading is held back until this device stores the chapter ([pendingReading]). Reads the local
     * store only, but blocks: never call it on the UI thread.
     */
    override fun pendingLastReadTimes(mangas: Collection<Manga>): Map<Long, Long> {
        val times = runCatching { store.lastReadWaitingForUnit(context.disabledKinds()) }
            .onFailure { Log.warn("Sync", it) { "Reading the synced reading times held back failed" } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyMap()
        return mangas.mapNotNull { manga ->
            // A title without a portable identity has no synced reading; that is an answer.
            val work = runCatching { identity.workOf(manga) }.getOrNull() ?: return@mapNotNull null
            times[work.recordKey]?.let { manga.id to it }
        }.toMap()
    }

    /**
     * Writes or edits the caller's review. Pending library changes are uploaded first, so the
     * server knows the work and the title is in the account's library (422 unknown_work otherwise).
     */
    override suspend fun writeReview(workHash: String, rating: Int?, body: String, spoiler: Boolean): Result<WireReview> = offCallerCatching {
        val url = baseUrl
        val pending = cycleMutex.withLock { context.pendingLocalCount() }
        if (pending > 0) syncQueued()
        val input = WireReviewInput(rating, body.trim().take(MAX_REVIEW_BODY), spoiler)
        tokens.authed { api.putReview(url, it, workHash, input) }
    }

    /** The work page: average rating, review count and the caller's own review. 404 when the server does not know the work. */
    override suspend fun workPage(workHash: String): Result<WireWorkPage> =
        offCaller { social.workPage(workHash) }

    /** [sort] is `newest` or `helpful`. */
    override suspend fun listReviews(
        workHash: String,
        sort: String,
        offset: Int,
        limit: Int,
    ): Result<WireReviewPage> =
        offCaller { social.listReviews(workHash, sort, offset, limit) }

    override suspend fun deleteReview(workHash: String): Result<Unit> =
        offCaller { social.deleteReview(workHash) }

    /** Idempotent: sets or clears the caller's "helpful" vote and returns the current count. */
    override suspend fun setHelpful(reviewId: String, helpful: Boolean): Result<WireVote> =
        offCaller { social.setHelpful(reviewId, helpful) }

    override suspend fun listReplies(reviewId: String, offset: Int, limit: Int): Result<WireReplyPage> =
        offCaller { social.listReplies(reviewId, offset, limit) }

    override suspend fun writeReply(reviewId: String, body: String): Result<WireReply> =
        offCaller { social.writeReply(reviewId, body) }

    override suspend fun deleteReply(replyId: String): Result<Unit> =
        offCaller { social.deleteReply(replyId) }

    /** [reason] is `spam`, `harassment`, `spoiler`, `illegal` or `other`. */
    override suspend fun reportReview(reviewId: String, reason: String, note: String): Result<WireReport> =
        offCaller { social.reportReview(reviewId, reason, note) }

    override suspend fun reportReply(replyId: String, reason: String, note: String): Result<WireReport> =
        offCaller { social.reportReply(replyId, reason, note) }

    // ---- lifecycle --------------------------------------------------------------------------

    /** The app came to the foreground: open the stream and catch up. */
    public suspend fun onForeground() {
        // Recorded before the hop off the caller: the lifecycle calls keep the order they were
        // made in, so a background flush started first cannot close the stream this one keeps.
        triggers.setForeground(true)
        offCaller { startForegroundWork() }
    }

    private suspend fun startForegroundWork() {
        if (store.meta(META_SIGNED_IN) != "1") return
        // Not if the app left the screen again before this ran.
        triggers.startStreamIfInForeground()
        scope.launch { syncQueued() }
    }

    /** Whether the live stream is open (or reconnecting); for tests. */
    internal val streamOpen: Boolean get() = triggers.streamOpen

    /** Whether the app is on the screen as the lifecycle calls said; for tests. */
    internal val inForeground: Boolean get() = triggers.foreground

    /** The app is going away from the screen: flush pending pushes and close the stream. */
    public suspend fun onBackground() {
        triggers.setForeground(false) // Before the hop, as in onForeground.
        offCaller { flushBeforeBackground() }
    }

    private suspend fun flushBeforeBackground() {
        if (store.meta(META_SIGNED_IN) == "1") {
            withTimeoutOrNull(BACKGROUND_FLUSH_TIMEOUT_MILLIS) { runCycle(manual = false) }
        }
        // The app may have come back while the flush ran: the stream it kept stays open.
        triggers.closeStreamIfInBackground()
    }

    /**
     * Entry point for WorkManager / BGTask: one bounded sync. Only [SyncBackgroundResult.RetryLater]
     * is worth an early retry; a failure that needs the user (or a fix) waits for the next period.
     */
    public suspend fun runBackgroundSync(): SyncBackgroundResult = offCaller {
        if (store.meta(META_SIGNED_IN) != "1") return@offCaller SyncBackgroundResult.Completed
        val outcome = withTimeoutOrNull(BACKGROUND_SYNC_TIMEOUT_MILLIS) { runCycleOutcome(manual = true) }
        when (outcome) {
            CycleOutcome.Success -> SyncBackgroundResult.Completed
            CycleOutcome.Transient, null -> SyncBackgroundResult.RetryLater // null: ran out of time
            CycleOutcome.Permanent -> SyncBackgroundResult.Failed
        }
    }

    /**
     * A forced cycle that waits for the one in progress, for follow-ups that must run after it (the
     * foreground catch-up, an accepted share, uploading before a share or review). A sync the user
     * asks for goes through [syncFromUser], which does not queue behind a background cycle.
     */
    internal suspend fun syncQueued(): Boolean = offCaller { runCycle(manual = true) }

    /**
     * The "Sync now" button. A background cycle may hold the lock for minutes; rather than wait
     * behind it, the press gives up after a short wait and reports [ManualSyncResult.InProgress].
     */
    override suspend fun syncFromUser(): ManualSyncResult = offCaller {
        val owner = Any()
        when {
            store.meta(META_SIGNED_IN) != "1" -> ManualSyncResult.Failed
            !lockForUser(owner) -> ManualSyncResult.InProgress
            else -> try {
                if (lockedCycle(manual = true) == CycleOutcome.Success) ManualSyncResult.Synced else ManualSyncResult.Failed
            } finally {
                cycleMutex.unlock(owner)
            }
        }
    }

    /**
     * Waits a short while for the cycle lock. The timeout (or the caller going away) can land after
     * lock() has already taken it, so the owner token, not how the wait ended, says whether it is held.
     */
    private suspend fun lockForUser(owner: Any): Boolean {
        var held = false
        try {
            withTimeoutOrNull(MANUAL_LOCK_WAIT_MILLIS) { cycleMutex.lock(owner) }
            held = cycleMutex.holdsLock(owner)
        } finally {
            // Cancelled after the lock was taken: give it back rather than keep it forever.
            if (!held && cycleMutex.holdsLock(owner)) cycleMutex.unlock(owner)
        }
        return held
    }

    /**
     * What this device knows about packages changed (a repository list arrived, a package was
     * installed): ask for an automatic cycle so titles waiting for a description get one.
     */
    override fun sourcesChanged() {
        triggers.signalDirty()
    }

    /**
     * A "sync items" switch changed. What a kind accumulated while it was off is still waiting
     * (local edits stay marked, server records stay unapplied), so turning it on only needs a
     * cycle; turning it off takes effect from the next one. The lists derived from the account
     * are republished at once.
     */
    public fun kindsChanged() {
        publisher.publishState()
        triggers.signalDirty()
    }

    /**
     * The packages installed on this device (its install keys and where they came from) changed.
     * Newly installed packages are recorded on the account; a package that was uninstalled is
     * deliberately not removed from it, as with repositories: the account's list is "what this
     * account has used", and removing here must not take it away from the other devices.
     */
    public fun packagesChanged(installed: List<AccountPackage>) {
        dirty.installedPackages = installed
        if (store.meta(META_USER_ID) == null || !clocks.isInstalled()) return
        dirty.markPackages()
        triggers.signalDirty()
    }

    /**
     * Removes [url] from the account: other devices stop receiving it as a suggestion, while
     * devices that already added it keep it. Marked with a fresh clock so it also beats the live
     * record other devices hold; adding the repository again later revives it.
     */
    public suspend fun forgetRepository(url: String): Unit = offCaller {
        if (store.meta(META_SIGNED_IN) != "1" || url.isBlank()) return@offCaller
        dirty.markRepositoryForgotten(url)
        triggers.signalDirty()
    }

    /** Re-evaluates server records that were waiting for a title, chapter or source (no network). */
    public suspend fun retryPendingRemote(): Unit = offCaller {
        if (store.meta(META_SIGNED_IN) != "1") return@offCaller
        cycleMutex.withLock {
            try {
                legacyKeys.refresh(repository.currentSnapshot.mangas.mapTo(HashSet()) { it.source }, resolveLegacySource)
                puller.applyUnapplied(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Throwable) {
                // The next full cycle reports the failure.
                Log.warn("Sync", logged) { "Applying pending remote records failed; the next sync cycle retries" }
            }
        }
        publisher.publishState()
    }

    /**
     * Runs [block] on the service's own dispatcher, keeping the caller's job (and so its
     * cancellation). Cycles look publications up in the content store, which must never run on
     * the UI thread ([dev.shinsou.kmp.content.ContentSqlThreadGuard] throws there in debug
     * builds); these entry points are called from UI coroutines.
     */
    private suspend fun <T> offCaller(block: suspend () -> T): T =
        withContext(scope.coroutineContext.minusKey(Job)) { block() }

    /** [offCaller] for the entry points that report failures as a [Result]. */
    private suspend inline fun <R> offCallerCatching(crossinline block: suspend () -> R): Result<R> =
        offCaller { runCatchingCancellable { block() } }

    public fun close() {
        triggers.stopBackgroundWork()
    }

    /** The server announced that the account reached [seq]. */
    internal fun onRemoteSeq(seq: Long) {
        triggers.onRemoteSeq(seq)
    }

    // ---- sync cycle -------------------------------------------------------------------------

    private suspend fun runCycle(manual: Boolean): Boolean = runCycleOutcome(manual) == CycleOutcome.Success

    /** One background cycle, as the scheduler runs it (no forced re-evaluation); for tests. */
    internal suspend fun runAutomaticCycleForTest(): Boolean = runCycle(manual = false)

    private suspend fun runCycleOutcome(manual: Boolean): CycleOutcome {
        if (store.meta(META_SIGNED_IN) != "1") return CycleOutcome.Permanent
        return cycleMutex.withLock { lockedCycle(manual) }
    }

    /** One cycle; the caller holds [cycleMutex]. */
    private suspend fun lockedCycle(manual: Boolean): CycleOutcome {
        if (store.meta(META_SIGNED_IN) != "1") return CycleOutcome.Permanent
        publisher.update { it.copy(syncing = true) }
        timing.lastCycleStartedAt = nowMillis()
        return try {
            if (!clocks.isInstalledUnlocked()) activateSession() // a foreground sync can beat start()
            cycleBody(force = manual)
            timing.consecutiveFailures = 0
            timing.nextAutomaticAttemptAt = 0
            store.setMeta(META_LAST_SYNC, nowMillis().toString())
            store.setMeta(META_ERROR, null)
            CycleOutcome.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SyncApiException) {
            recordFailure(error.userMessage(), error.retryAfterSeconds)
            if (error.isTransient) CycleOutcome.Transient else CycleOutcome.Permanent
        } catch (logged: Throwable) {
            // Not an API failure: something unexpected, worth its stack trace.
            Log.warn("Sync", logged) { "A sync cycle failed unexpectedly" }
            recordFailure(logged.message ?: logged::class.simpleName.orEmpty(), null)
            CycleOutcome.Permanent
        } finally {
            publisher.publishState(syncing = false)
        }
    }

    private fun recordFailure(message: String, retryAfterSeconds: Int?) {
        timing.consecutiveFailures++
        val backoff = (retryAfterSeconds?.times(1000L)
            ?: (FAILURE_BACKOFF_MIN_MILLIS shl (timing.consecutiveFailures - 1).coerceAtMost(8)))
            .coerceAtMost(FAILURE_BACKOFF_MAX_MILLIS)
        timing.nextAutomaticAttemptAt = nowMillis() + backoff
        store.setMeta(META_ERROR, message)
    }

    private suspend fun cycleBody(force: Boolean) {
        legacyKeys.refresh(repository.currentSnapshot.mangas.mapTo(HashSet()) { it.source }, resolveLegacySource)
        dirty.ensureSeeded()
        // Read every cycle: installing a package later is what makes its titles describable.
        identity.sources = try {
            sourceInfo()
        } catch (error: CancellationException) {
            throw error
        } catch (logged: Exception) {
            Log.warn("Sync", logged) { "Reading installed source information failed; describing works without it" }
            emptyMap()
        }
        val described = pusher.sourcesFingerprint(identity.sources)
        promoter.promoteAll()
        if (store.meta(META_RESYNC) == "1") {
            puller.resync()
        } else {
            try {
                puller.pullAll()
            } catch (error: SyncApiException) {
                if (error.isResyncRequired) puller.resync() else throw error
            }
        }
        // After the pull: the clock may only come down to what the server is known to hold, and a
        // push whose answer was lost is only known about once it has been pulled.
        clocks.rebaseClockIfAhead { puller.pullAll(fromZero = true) }
        var pushed = 0
        try {
            pushed = pusher.pushAll()
        } catch (error: SyncApiException) {
            if (error.isResyncRequired) {
                puller.resync()
                pushed = pusher.pushAll()
            } else {
                throw error
            }
        }
        // Checked after the pull: a title that arrived without its source reopens the question.
        if (described != null && store.meta(META_DESCRIPTORS) != described) pusher.refreshDescriptors(described)
        if (pushed > 0) puller.pullAll()
        // A package installed since the last attempt can make titles waiting for their source
        // applicable even though nothing in the library changed.
        val sourcesMoved = described != lastApplySources
        puller.applyUnapplied(force || sourcesMoved)
        lastApplySources = described
    }

    /** Public for the app module (R5-1): [create]; the rest stays internal to :feature-sync. */
    public companion object {
        internal const val META_SERVER_URL = "server_url"
        internal const val META_INSECURE_LAN = "insecure_lan"
        internal const val META_USER_ID = "user_id"
        internal const val META_USERNAME = "username"
        internal const val META_DEVICE_ID = "device_id"
        internal const val META_SYNC_DEVICE_ID = "sync_device_id"
        internal const val META_DEVICE_NAME = "device_name"
        internal const val META_SIGNED_IN = "signed_in"
        internal const val META_CURSOR = "cursor"
        internal const val META_LAST_SYNC = "last_sync_at"
        internal const val META_ERROR = "last_error"
        internal const val META_HLC = "hlc"
        internal const val META_ACCESS_EXPIRES = "access_expires_at"
        internal const val META_INITIAL_DONE = "initial_done"
        internal const val META_SEEDED_KINDS = "seeded_kinds"
        internal const val SEEDED_KINDS_VERSION = "4"
        internal const val META_RESYNC = "resync"
        internal const val META_DESCRIPTORS = "descriptors"
        internal const val DESCRIPTORS_VERSION = "3"
        internal const val DESCRIPTOR_CHUNK = 200
        internal const val META_SHARING = "sharing_enabled"
        internal const val META_REVIEWS = "reviews_enabled"

        internal const val KEY_ACCESS_TOKEN = "sync.v3.access_token"
        internal const val KEY_REFRESH_TOKEN = "sync.v3.refresh_token"

        internal val APPLY_ORDER = listOf(
            SyncKinds.CATEGORY,
            SyncKinds.LIBRARY,
            SyncKinds.UNIT,
            SyncKinds.HISTORY,
            SyncKinds.PROGRESS,
            SyncKinds.REPOSITORY,
            SyncKinds.SETTING,
            SyncKinds.PACKAGE,
        )

        /** What the server accepts as a package key (records.md); an install key fits. */
        internal val PACKAGE_KEY = Regex("^[A-Za-z0-9._~-]{1,128}$")
        internal val PACKAGE_FIELDS = listOf("repositoryUrl", "name", "version", "versionCode", "signingKeyFingerprint")

        internal const val PUSH_DEBOUNCE_MILLIS = 2_000L
        internal const val MIN_AUTOMATIC_INTERVAL_MILLIS = 5_000L
        internal const val MAX_PUSH_RECORDS = 500
        internal const val MAX_PUSH_CHARS = 800_000
        internal const val PROMOTE_CHUNK = 500
        internal const val APPLY_CHUNK = 500
        internal const val APPLY_ATTEMPTS = 5
        internal const val MARK_CHUNK = 5_000
        internal const val TOKEN_REFRESH_MARGIN_MILLIS = 60_000L
        internal const val SOCIAL_PAGE_SIZE = 20
        internal const val MAX_SHARE_MESSAGE = 500
        internal const val REVIEW_PAGE_SIZE = 20
        internal const val MAX_REVIEW_BODY = 5000
        internal const val MAX_REPLY_BODY = 2000
        internal const val MAX_REPORT_NOTE = 500
        internal const val BACKGROUND_FLUSH_TIMEOUT_MILLIS = 15_000L
        internal const val BACKGROUND_SYNC_TIMEOUT_MILLIS = 8 * 60_000L
        internal const val MANUAL_LOCK_WAIT_MILLIS = 2_000L
        internal const val STREAM_BACKOFF_MIN_MILLIS = 1_000L
        internal const val STREAM_BACKOFF_MAX_MILLIS = 60_000L
        internal const val STREAM_STABLE_MILLIS = 10_000L
        internal const val FAILURE_BACKOFF_MIN_MILLIS = 5_000L
        internal const val FAILURE_BACKOFF_MAX_MILLIS = 15 * 60_000L

        /** Builds the service on the shared content database; null-safe pieces come from the composition. */
        public fun create(
            repository: ShinsouRepository,
            driver: SqlDriver,
            keyValues: KeyValueStore,
            http: io.ktor.client.HttpClient,
            scope: CoroutineScope,
            device: SyncDeviceInfo,
            publications: (PublicationKey) -> Publication?,
            resolveLegacySource: suspend (Long) -> SourceKey?,
            resolveCreatableSource: suspend (String, String) -> SourceKey?,
            pushDebounceMillis: Long = PUSH_DEBOUNCE_MILLIS,
            sourceInfo: suspend () -> Map<Pair<String, String>, SyncSourceInfo> = { emptyMap() },
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
                sourceInfo = sourceInfo,
            )
        }
    }
}
