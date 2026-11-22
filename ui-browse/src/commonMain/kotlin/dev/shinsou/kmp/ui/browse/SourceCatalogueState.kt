package dev.shinsou.kmp.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.plugin.contracts.SourceWebChallengeCapability
import dev.shinsou.kmp.plugin.contracts.SourceWebChallengeRequest
import dev.shinsou.kmp.domain.model.sourceFailureMarker
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.plugin.contracts.BrowseFilter
import dev.shinsou.kmp.plugin.contracts.BrowseManga
import dev.shinsou.kmp.plugin.contracts.BrowsePage
import dev.shinsou.kmp.plugin.contracts.BrowseSource
import dev.shinsou.kmp.plugin.contracts.ExtensionPublicationCatalog
import dev.shinsou.kmp.plugin.contracts.SourceCatalog
import dev.shinsou.kmp.plugin.contracts.SourceWebSession
import dev.shinsou.kmp.plugin.contracts.toBrowseFilterV2
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.ui.challenge.WebChallengeCapture
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SOURCE_HTTP_CHALLENGE_MARKER = "SHINSOU_SOURCE_HTTP_CHALLENGE"

/**
 * Where a screen's state holder runs its work: results are applied in [scope] (the screen's, on
 * the UI thread), plugin and store work runs on [workDispatcher].
 */
internal class ScreenWork(
    val scope: CoroutineScope,
    val workDispatcher: CoroutineDispatcher,
    /** Lets a pressed state or a loading frame reach the screen before the work begins. */
    val awaitFrame: suspend () -> Unit,
    /** The language the screen is shown in now. */
    val strings: () -> ShinsouStrings,
) {
    /** The user-facing message of [failure]. */
    fun describe(failure: Throwable): String = failure.localizedDiagnosticMessage(strings())
}

/** Immutable snapshot of the exact catalogue operation that encountered a typed challenge. */
internal data class SourceCatalogueRequest(
    val source: BrowseSource,
    val search: String,
    val mode: SourceCatalogueMode,
    val filters: List<BrowseFilter>?,
    val requestedPage: Int,
    val append: Boolean,
)

internal data class CatalogueWebChallenge(
    val catalogueRequest: SourceCatalogueRequest,
    val browserRequest: SourceWebChallengeRequest,
)

/** What [SourceCatalogueState] needs of the extension bridge. */
internal interface SourceCatalogueBackend {
    /** The page [request] asks for, as the source returns it (not merged with earlier pages). */
    suspend fun page(request: SourceCatalogueRequest): BrowsePage

    suspend fun isWebChallengeAvailable(sourceId: Long): Boolean

    suspend fun webChallenge(sourceId: Long): SourceWebChallengeRequest?

    suspend fun importWebChallenge(sourceId: Long, capture: WebChallengeCapture)

    suspend fun cancelWebChallenge(capability: SourceWebChallengeCapability)
}

/** [SourceCatalogueBackend] of the bridge: v2 sources by their exact key, legacy ones by id. */
internal class BridgeCatalogueBackend(
    private val catalog: SourceCatalog,
    private val publications: ExtensionPublicationCatalog,
    private val webSession: SourceWebSession,
) : SourceCatalogueBackend {
    override suspend fun page(request: SourceCatalogueRequest): BrowsePage {
        val source = request.source
        val sourceKey = source.sourceKey ?: return legacyPage(request)
        // Extension pages are numbered from zero; the screen counts from one.
        val page = request.requestedPage - 1
        val options = BrowseOptionsV2(filters = request.filters.orEmpty().map { it.toBrowseFilterV2() })
        val result = when {
            request.mode == SourceCatalogueMode.Favorites -> {
                val optionKey = source.favoriteBrowseOptionKey ?: error("Source does not expose a favorites catalogue")
                publications.browseSourceV2(
                    sourceKey = sourceKey,
                    options = BrowseOptionsV2(mapOf(optionKey to source.favoriteBrowseOptionValue)),
                    page = page,
                )
            }
            request.mode == SourceCatalogueMode.Latest && request.search.isBlank() ->
                publications.latestSourceV2(sourceKey, page)
            request.search.isNotBlank() -> publications.searchSourceV2(sourceKey, request.search, page, options = options)
            else -> publications.browseSourceV2(sourceKey, options = options, page = page)
        }
        return BrowsePage(
            items = result.items.map { publication -> publication.toBrowseManga(source) },
            hasNextPage = result.hasNextPage,
        )
    }

    private suspend fun legacyPage(request: SourceCatalogueRequest): BrowsePage = when {
        request.mode == SourceCatalogueMode.Latest && request.search.isBlank() ->
            catalog.browseSourceLatest(request.source.id, request.requestedPage)
        request.mode == SourceCatalogueMode.Favorites -> catalog.browseSourceFavorites(request.source.id, request.requestedPage)
        else -> catalog.browseSource(
            sourceId = request.source.id,
            query = request.search,
            page = request.requestedPage,
            filters = request.filters,
        )
    }

    override suspend fun isWebChallengeAvailable(sourceId: Long): Boolean = webSession.isSourceWebChallengeAvailable(sourceId)

    override suspend fun webChallenge(sourceId: Long): SourceWebChallengeRequest? = webSession.sourceWebChallenge(sourceId)

    override suspend fun importWebChallenge(sourceId: Long, capture: WebChallengeCapture) =
        webSession.importSourceWebChallengeSession(
            sourceId = sourceId,
            capability = capture.capability,
            cookies = capture.cookies,
            userAgent = capture.userAgent,
            localStorage = capture.localStorage,
        )

    override suspend fun cancelWebChallenge(capability: SourceWebChallengeCapability) =
        webSession.cancelSourceWebChallenge(capability)
}

/** Tracks only the newest catalogue request and cannot let an older job clear its replacement. */
internal class CatalogueJobController {
    private var activeJob: Job? = null

    internal fun replace(job: Job) {
        activeJob?.cancel()
        activeJob = job
    }

    internal fun clear(job: Job) {
        if (activeJob === job) activeJob = null
    }

    internal fun cancel() {
        activeJob?.cancel()
        activeJob = null
    }
}

/**
 * R4-3 (docs/architecture/0006): the open source's catalogue — its pages, loading and failure,
 * and the recovery through the source's browser challenge when the source answered with one.
 * Only the newest request may change the state; [activeSourceIdentity] tells whether the source a
 * result belongs to is still open.
 */
internal class SourceCatalogueState(
    private val backend: SourceCatalogueBackend,
    private val work: ScreenWork,
    private val activeSourceIdentity: () -> String?,
) {
    var page by mutableStateOf(BrowsePage())
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var challengeAvailable by mutableStateOf(false)
        private set
    var challengePreparing by mutableStateOf(false)
        private set
    var challenge by mutableStateOf<CatalogueWebChallenge?>(null)
        private set

    /** The request a browser challenge would retry; null when none is offered. */
    var pendingRecovery by mutableStateOf<SourceCatalogueRequest?>(null)
        private set

    /** The last page loaded, counted from one. */
    var pageNumber by mutableStateOf(1)
        private set

    private var requestToken = 0
    private val jobs = CatalogueJobController()

    /** A source was opened: its catalogue starts empty. */
    fun reset() {
        page = BrowsePage()
        pageNumber = 1
    }

    /**
     * Invalidates completion handlers before cancellation so an old request cannot mutate the
     * loading state after the catalogue has closed or another source has been selected.
     */
    fun cancelLoad() {
        requestToken += 1
        jobs.cancel()
        loading = false
        loadingMore = false
    }

    /** Withdraws the challenge on offer (and the browser capability already issued for it). */
    fun clearRecovery() {
        challengeAvailable = false
        challengePreparing = false
        pendingRecovery = null
        challenge?.browserRequest?.capability?.let(::cancelInBackground)
        challenge = null
    }

    /** The screen is leaving: the load stops and an issued browser capability is withdrawn. */
    fun dispose() {
        jobs.cancel()
        challenge?.browserRequest?.capability?.let(::cancelInBackground)
    }

    /** Loads the first page of [source] for [search], [mode] and [filters]. */
    fun browse(source: BrowseSource, search: String, mode: SourceCatalogueMode, filters: List<BrowseFilter>?) =
        load(SourceCatalogueRequest(source, search, mode, filters, requestedPage = 1, append = false))

    /** Appends the next page. */
    fun loadMore(source: BrowseSource, search: String, mode: SourceCatalogueMode, filters: List<BrowseFilter>?) =
        load(SourceCatalogueRequest(source, search, mode, filters, requestedPage = pageNumber + 1, append = true))

    fun load(request: SourceCatalogueRequest) {
        if (request.append && (loading || loadingMore)) return
        val token = requestToken + 1
        requestToken = token
        if (request.append) {
            loadingMore = true
        } else {
            // Discard queued source work immediately. JavaScriptCore serializes each source, so
            // leaving old jobs queued would make the newest query wait behind obsolete requests.
            jobs.cancel()
            loading = true
            loadingMore = false
        }
        error = null
        challengeAvailable = false
        challengePreparing = false
        pendingRecovery = null
        val existingItems = if (request.append) page.items else emptyList()
        val job = work.scope.launch(start = CoroutineStart.LAZY) {
            val runningJob = currentCoroutineContext()[Job]
            try {
                // The source and loading state were updated above; render that shell before source I/O.
                work.awaitFrame()
                runCatchingCancellable { withContext(work.workDispatcher) { backend.page(request) } }
                    .onSuccess { loaded ->
                        if (requestToken == token) {
                            page = if (request.append) {
                                loaded.copy(items = (existingItems + loaded.items).distinctBy(BrowseManga::identityKey))
                            } else {
                                loaded
                            }
                            pageNumber = request.requestedPage
                        }
                    }
                    .onFailure { failure -> if (requestToken == token) onLoadFailure(request, token, failure) }
            } finally {
                if (requestToken == token) {
                    loading = false
                    loadingMore = false
                }
                runningJob?.let(jobs::clear)
            }
        }
        jobs.replace(job)
        job.start()
    }

    private suspend fun onLoadFailure(request: SourceCatalogueRequest, token: Int, failure: Throwable) {
        // A replacement search must not present the previous query's results as its own.
        // Pagination failures keep already loaded items and the footer.
        if (!request.append) page = BrowsePage()
        error = work.describe(failure)
        val challenged = failure.sourceFailureMarker() == SOURCE_HTTP_CHALLENGE_MARKER &&
            activeSourceIdentity() == request.source.identityKey
        if (!challenged) return
        pendingRecovery = request
        val available = runCatchingCancellable {
            withContext(work.workDispatcher) { backend.isWebChallengeAvailable(request.source.id) }
        }
            // A failed probe answers the same as an unavailable one: no web view.
            .onFailure { Log.warn("Browse", it) { "Checking a source's web challenge failed; offering none" } }
            .getOrDefault(false)
        if (requestToken == token && pendingRecovery === request && activeSourceIdentity() == request.source.identityKey) {
            challengeAvailable = available
        }
    }

    /** Opens the source's browser to pass the challenge of the failed request. */
    fun openWebChallenge() {
        val recovery = pendingRecovery?.takeIf { challengeAvailable && !challengePreparing } ?: return
        if (activeSourceIdentity() != recovery.source.identityKey) clearRecovery() else startWebChallenge(recovery)
    }

    private fun startWebChallenge(recovery: SourceCatalogueRequest) {
        challengePreparing = true
        work.scope.launch {
            work.awaitFrame()
            var issued: SourceWebChallengeRequest? = null
            var delivered = false
            try {
                runCatchingCancellable {
                    withContext(work.workDispatcher) { backend.webChallenge(recovery.source.id).also { issued = it } }
                }.onSuccess { request ->
                    if (request != null && pendingRecovery === recovery && activeSourceIdentity() == recovery.source.identityKey) {
                        challenge = CatalogueWebChallenge(recovery, request)
                        delivered = true
                    } else if (pendingRecovery === recovery) {
                        challengeAvailable = false
                    }
                }.onFailure { failure ->
                    if (pendingRecovery === recovery) {
                        error = work.describe(failure)
                        challengeAvailable = false
                    }
                }
            } finally {
                if (pendingRecovery === recovery) challengePreparing = false
                if (!delivered) issued?.capability?.let { withContext(NonCancellable) { backend.cancelWebChallenge(it) } }
            }
        }
    }

    /**
     * The browser passed [challenge] and captured its session. The dialog goes at once: the opaque
     * authority is one-shot, so neither a double click nor a late platform callback can start a
     * duplicate import. A successful import retries the failed request once.
     */
    fun importWebChallenge(challenge: CatalogueWebChallenge, capture: WebChallengeCapture) {
        if (this.challenge !== challenge) return
        this.challenge = null
        challengePreparing = true
        work.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var imported = false
            try {
                runCatchingCancellable {
                    withContext(work.workDispatcher) {
                        backend.importWebChallenge(challenge.catalogueRequest.source.id, capture)
                    }
                }.onSuccess { imported = true }.onFailure { failure ->
                    if (pendingRecovery === challenge.catalogueRequest) {
                        error = work.describe(failure)
                        challengeAvailable = false
                    }
                }
            } finally {
                challengePreparing = false
                // Import consumes valid grants. Cancellation or an early validation failure must
                // consume them too, and cancellation itself cannot skip it.
                withContext(NonCancellable) { backend.cancelWebChallenge(challenge.browserRequest.capability) }
            }
            if (imported) retryAfterChallenge(challenge.catalogueRequest)
        }
    }

    private fun retryAfterChallenge(retry: SourceCatalogueRequest) {
        if (pendingRecovery !== retry || activeSourceIdentity() != retry.source.identityKey) return
        // Consume this recovery before dispatch; a repeated typed challenge creates a fresh
        // explicit offer, never an automatic retry loop.
        pendingRecovery = null
        challengeAvailable = false
        load(retry)
    }

    /** The browser was closed without passing [challenge]: its capability is withdrawn. */
    fun dismissWebChallenge(challenge: CatalogueWebChallenge) {
        if (this.challenge !== challenge) return
        this.challenge = null
        cancelInBackground(challenge.browserRequest.capability)
    }

    /**
     * Enters the revocation at once, then lets its small manager critical section finish even when
     * the screen's scope is being cancelled.
     */
    private fun cancelInBackground(capability: SourceWebChallengeCapability) {
        work.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { backend.cancelWebChallenge(capability) }
        }
    }
}
