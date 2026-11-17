package dev.shinsou.kmp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.ui.components.ShinsouLoadingIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import dev.shinsou.kmp.sync.v3.SyncV3Service
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.contract.BrowseCallbacks
import dev.shinsou.kmp.contract.BrowseExtension
import dev.shinsou.kmp.contract.BrowseFilter
import dev.shinsou.kmp.contract.BrowseManga
import dev.shinsou.kmp.contract.BrowsePage
import dev.shinsou.kmp.contract.BrowseSource
import dev.shinsou.kmp.plugin.ExtensionArtifactNotReviewedException
import dev.shinsou.kmp.plugin.sourceFailureMarker
import dev.shinsou.kmp.plugin.toBrowseFilterV2
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.contract.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.ExtensionFavoriteDestination
import dev.shinsou.kmp.ui.extensionFavoriteDestination
import dev.shinsou.kmp.features.ContentFeatureRuntime
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.ui.ReaderVolumeKeyRouter
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.ui.challenge.SourceWebChallengeDialog
import dev.shinsou.kmp.ui.components.CoverImage
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.components.SearchField
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.localizedSourceFailure
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BrowseSection {
    Sources,
    Extensions,
    Migration,
}

internal enum class SourceCatalogueMode {
    Popular,
    Latest,
    Favorites,
}

private const val SOURCE_HTTP_CHALLENGE_MARKER = "SHINSOU_SOURCE_HTTP_CHALLENGE"

/** Immutable snapshot of the exact catalogue operation that encountered a typed challenge. */
private data class SourceCatalogueRequest(
    val source: BrowseSource,
    val search: String,
    val mode: SourceCatalogueMode,
    val filters: List<BrowseFilter>?,
    val requestedPage: Int,
    val append: Boolean,
)

private data class CatalogueWebChallenge(
    val catalogueRequest: SourceCatalogueRequest,
    val browserRequest: SourceWebChallengeRequest,
)

internal data class SourceSearchResult(
    val source: BrowseSource,
    val items: List<BrowseManga> = emptyList(),
    val totalCount: Int = items.size,
    val errorMessage: String? = null,
)

internal data class SourceSearchUpdate(
    val sourceIndex: Int,
    val result: SourceSearchResult,
)

internal const val GLOBAL_SEARCH_MAX_CONCURRENCY: Int = 4
internal const val GLOBAL_SEARCH_VISIBLE_RESULTS: Int = 10

private data class PendingPluginEventGrant(
    val extension: BrowseExtension,
    val review: dev.shinsou.kmp.plugin.events.PluginEventGrantReview,
)

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
 * Owns the browse overlay stack so a child publication cannot outlive the source/search surface
 * that opened it. Desktop keeps parent surfaces mounted beside publication details, which makes
 * it especially important that dismissing a parent also dismisses its child.
 */
internal data class BrowseOverlayState(
    val activeSource: BrowseSource? = null,
    val globalSearchVisible: Boolean = false,
    val activeV2Publication: BrowseManga? = null,
    val activeV2Reader: Boolean = false,
) {
    val hasOverlay: Boolean
        get() = activeSource != null || globalSearchVisible || activeV2Publication != null

    fun openSource(source: BrowseSource): BrowseOverlayState = copy(
        activeSource = source,
        globalSearchVisible = false,
        activeV2Publication = null,
        activeV2Reader = false,
    )

    fun openGlobalSearch(): BrowseOverlayState = copy(
        activeSource = null,
        globalSearchVisible = true,
        activeV2Publication = null,
        activeV2Reader = false,
    )

    fun openPublication(publication: BrowseManga): BrowseOverlayState = copy(
        activeV2Publication = publication,
        activeV2Reader = false,
    )

    fun setReaderVisible(visible: Boolean): BrowseOverlayState = copy(activeV2Reader = visible)

    fun closePublication(): BrowseOverlayState = copy(
        activeV2Publication = null,
        activeV2Reader = false,
    )

    fun closeSource(): BrowseOverlayState = copy(
        activeSource = null,
        activeV2Publication = null,
        activeV2Reader = false,
    )

    fun closeGlobalSearch(): BrowseOverlayState = copy(
        globalSearchVisible = false,
        activeV2Publication = null,
        activeV2Reader = false,
    )

    fun closeAll(): BrowseOverlayState = BrowseOverlayState()
}

@Composable
fun BrowseScreen(
    callbacks: BrowseCallbacks,
    sync: SyncV3Service? = null,
    initialSection: BrowseSection = BrowseSection.Sources,
    showNsfw: Boolean,
    enabledLanguages: Set<String>,
    pinnedSourceIds: Set<Long>,
    onSourcePinnedChange: (sourceId: Long, pinned: Boolean) -> Unit,
    onOpenManga: (BrowseManga) -> Unit,
    onImportDocument: suspend (acceptedExtensions: Set<String>) -> ImportedDocument? = { null },
    contentFeatures: ContentFeatureRuntime? = null,
    copyText: (label: String, text: String) -> Boolean = { _, _ -> false },
    readerSettings: ReaderSettings = ReaderSettings(),
    onReaderSettingsChange: (ReaderSettings) -> Unit = {},
    openExternalUrl: (String) -> Unit = {},
    shareText: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    systemBackRequest: Long = 0L,
    backGestureProgress: Float = 0f,
    onBackAvailabilityChanged: (Boolean) -> Unit = {},
    /** Closes a legacy detail route owned by a source/search parent outside this composable. */
    onParentDismissed: () -> Unit = {},
    onReaderVisibilityChanged: (Boolean) -> Unit = {},
    onReaderProgress: (
        title: String,
        unitTitle: String,
        locator: ReadingLocator,
        pageIndex: Int,
        pageCount: Int?,
    ) -> Unit = { _, _, _, _, _ -> },
    onReaderProgressFlushed: suspend () -> Unit = {},
    volumeKeyRouter: ReaderVolumeKeyRouter? = null,
    /** App-owned library mutation for every extension v2 publication. */
    onToggleLocalLibrary: suspend (BrowseManga, RemotePublicationV2, Boolean) -> Unit = { _, _, _ -> },
    isLocalLibraryFavorite: (BrowseManga) -> Boolean = { false },
    /** Stable v2 source identities corresponding to [BrowseSettings.pinnedSourceKeys]. */
    pinnedSourceKeys: Set<String> = emptySet(),
    onSourcePinnedKeyChange: (sourceKey: String, pinned: Boolean) -> Unit = { _, _ -> },
) {
    val strings = LocalShinsouStrings.current
    val snapshot by callbacks.state.collectAsState()
    val sourceRefreshInvalidations by callbacks.sourceRefreshInvalidations.collectAsState()
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(initialSection) }
    var query by remember { mutableStateOf("") }
    var overlays by remember { mutableStateOf(BrowseOverlayState()) }
    var catalogueLoginSourceIdentity by remember { mutableStateOf<String?>(null) }
    // A reader is owned by ExtensionV2PublicationPane. Keep a monotonically increasing
    // request so a system back gesture can ask that child to dispose its materialized session
    // before the parent changes the layout back to the publication detail pane.
    var v2ReaderBackRequest by remember { mutableStateOf(0L) }
    var page by remember { mutableStateOf(BrowsePage()) }
    var catalogueLoading by remember { mutableStateOf(false) }
    var catalogueLoadingMore by remember { mutableStateOf(false) }
    var catalogueError by remember { mutableStateOf<String?>(null) }
    var catalogueChallengeAvailable by remember { mutableStateOf(false) }
    var catalogueChallengePreparing by remember { mutableStateOf(false) }
    var catalogueChallenge by remember { mutableStateOf<CatalogueWebChallenge?>(null) }
    var pendingCatalogueRecovery by remember { mutableStateOf<SourceCatalogueRequest?>(null) }
    var cataloguePageNumber by remember { mutableStateOf(1) }
    var catalogueRequestToken by remember { mutableStateOf(0) }
    val catalogueJobs = remember { CatalogueJobController() }
    var pendingPluginEventGrant by remember { mutableStateOf<PendingPluginEventGrant?>(null) }
    /** ADR-0002 §6.3: an update asking for something new, reviewed before it is installed. */
    var pendingUpdateReview by remember {
        mutableStateOf<Pair<BrowseExtension, dev.shinsou.kmp.contract.UpdatePermissionPreview>?>(null)
    }
    // Source settings → permissions (ADR-0002 §6.5): the granted plugin whose items are being edited.
    var editingPluginPermissions by remember {
        mutableStateOf<Pair<BrowseExtension, dev.shinsou.kmp.contract.InstalledPluginPermissions>?>(null)
    }
    var pendingRepositoryTrust by remember { mutableStateOf<dev.shinsou.kmp.contract.RepositoryTrustPrompt?>(null) }
    var packageSecurity by remember { mutableStateOf<dev.shinsou.kmp.contract.PackageSecuritySnapshot?>(null) }
    val extensionOperations = remember(scope) {
        ExtensionOperationRunner(scope, beforeWork = { withFrameNanos { } })
    }
    var handledSystemBackRequest by remember { mutableStateOf(systemBackRequest) }
    val operationSnackbar = remember { SnackbarHostState() }
    val hasBrowseOverlay = overlays.hasOverlay
    val currentBackAvailabilityCallback = rememberUpdatedState(onBackAvailabilityChanged)
    val currentReaderVisibilityCallback = rememberUpdatedState(onReaderVisibilityChanged)

    DisposableEffect(Unit) {
        onDispose { currentReaderVisibilityCallback.value(false) }
    }

    LaunchedEffect(snapshot.extensions.map { Triple(it.id, it.version, it.installed) }, extensionOperations.active) {
        if (pendingPluginEventGrant == null && extensionOperations.active == null) {
            try {
                val pending = withContext(Dispatchers.Default) {
                    // A package pre-installed from the account is reviewed on first access (opening one of
                    // its titles, or switching it on here), not the moment this page appears.
                    snapshot.extensions.filter { it.installed && !it.preinstalledFromAccount }
                        .firstNotNullOfOrNull { extension ->
                            callbacks.pendingPluginEventGrantReview(extension.id)?.let { review ->
                                PendingPluginEventGrant(extension, review)
                            }
                        }
                }
                // A click can claim an operation before recomposition cancels this effect.
                if (extensionOperations.active == null && pendingPluginEventGrant == null) {
                    pendingPluginEventGrant = pending
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                operationSnackbar.showSnackbar(error.localizedDiagnosticMessage(strings))
            }
        }
    }

    fun cancelCatalogueLoad() {
        // Invalidate completion handlers before cancellation so an old request cannot mutate the
        // loading state after the catalogue has closed or another source has been selected.
        catalogueRequestToken += 1
        catalogueJobs.cancel()
        catalogueLoading = false
        catalogueLoadingMore = false
    }

    fun clearCatalogueRecovery() {
        catalogueChallengeAvailable = false
        catalogueChallengePreparing = false
        pendingCatalogueRecovery = null
        catalogueChallenge?.browserRequest?.capability?.let { capability ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { callbacks.cancelSourceWebChallenge(capability) }
            }
        }
        catalogueChallenge = null
    }

    fun closeActiveSource() {
        val hadSource = overlays.activeSource != null
        cancelCatalogueLoad()
        clearCatalogueRecovery()
        overlays = overlays.closeSource()
        currentReaderVisibilityCallback.value(false)
        if (hadSource) onParentDismissed()
    }

    fun closeGlobalSearch() {
        val hadSearch = overlays.globalSearchVisible
        overlays = overlays.closeGlobalSearch()
        currentReaderVisibilityCallback.value(false)
        if (hadSearch) onParentDismissed()
    }

    LaunchedEffect(hasBrowseOverlay) {
        currentBackAvailabilityCallback.value(hasBrowseOverlay)
    }

    DisposableEffect(Unit) {
        onDispose {
            catalogueJobs.cancel()
            catalogueChallenge?.browserRequest?.capability?.let { capability ->
                // The Compose scope is being cancelled. Enter the revocation synchronously, then
                // let its small manager critical section finish despite that cancellation.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    withContext(NonCancellable) { callbacks.cancelSourceWebChallenge(capability) }
                }
            }
            currentBackAvailabilityCallback.value(false)
        }
    }

    LaunchedEffect(systemBackRequest) {
        if (systemBackRequest != handledSystemBackRequest) {
            handledSystemBackRequest = systemBackRequest
            when {
                overlays.activeV2Reader -> {
                    v2ReaderBackRequest += 1
                }
                overlays.activeV2Publication != null -> {
                    overlays = overlays.closePublication()
                    currentReaderVisibilityCallback.value(false)
                }
                overlays.activeSource != null -> closeActiveSource()
                overlays.globalSearchVisible -> closeGlobalSearch()
            }
        }
    }

    fun launchOperation(block: suspend () -> Unit) {
        scope.launch {
            // Let the pressed/ripple state reach the display before dispatching plugin work.
            withFrameNanos { }
            try {
                withContext(Dispatchers.Default) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                operationSnackbar.showSnackbar(error.localizedDiagnosticMessage(strings))
            }
        }
    }

    fun <T> launchExtensionOperation(
        extension: BrowseExtension,
        labelKey: String,
        onSuccess: (T) -> Unit = {},
        work: suspend () -> T,
    ): Boolean = extensionOperations.start(
        operation = ExtensionOperation(extension.id, labelKey),
        work = work,
        onSuccess = onSuccess,
        onError = { error -> operationSnackbar.showSnackbar(error.localizedDiagnosticMessage(strings)) },
    )

    fun openManga(item: BrowseManga) {
        if (item.sourceKey != null) {
            overlays = overlays.openPublication(item)
            return
        }
        // Let the app route to an immediate loading preview. Resolving source metadata can be
        // network-bound and must not hold the browse surface in place until it completes.
        onOpenManga(item)
    }

    fun loadCatalogue(
        source: BrowseSource,
        search: String,
        mode: SourceCatalogueMode,
        filters: List<BrowseFilter>?,
        requestedPage: Int,
        append: Boolean,
    ) {
        if (append && (catalogueLoading || catalogueLoadingMore)) return
        val exactRequest = SourceCatalogueRequest(
            source = source,
            search = search,
            mode = mode,
            filters = filters,
            requestedPage = requestedPage,
            append = append,
        )
        val requestToken = catalogueRequestToken + 1
        catalogueRequestToken = requestToken
        if (append) {
            catalogueLoadingMore = true
        } else {
            // Discard queued source work immediately. JavaScriptCore serializes each source, so
            // leaving old jobs queued would make the newest query wait behind obsolete requests.
            catalogueJobs.cancel()
            catalogueLoading = true
            catalogueLoadingMore = false
        }
        catalogueError = null
        catalogueChallengeAvailable = false
        catalogueChallengePreparing = false
        pendingCatalogueRecovery = null
        val existingItems = if (append) page.items else emptyList()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val runningJob = currentCoroutineContext()[Job]
            // activeSource/loading were updated above; render that shell before source I/O.
            try {
                withFrameNanos { }
                val result = withContext(Dispatchers.Default) {
                    val loaded = if (source.sourceKey != null) {
                    val v2Options = BrowseOptionsV2(
                        filters = filters.orEmpty().map { it.toBrowseFilterV2() },
                    )
                    val v2Page = when {
                            mode == SourceCatalogueMode.Favorites -> {
                                val optionKey = source.favoriteBrowseOptionKey
                                    ?: error("Source does not expose a favorites catalogue")
                                callbacks.browseSourceV2(
                                    sourceKey = source.sourceKey,
                                    options = BrowseOptionsV2(
                                        mapOf(optionKey to source.favoriteBrowseOptionValue),
                                    ),
                                    page = requestedPage - 1,
                                )
                            }
                            mode == SourceCatalogueMode.Latest && search.isBlank() ->
                                callbacks.latestSourceV2(source.sourceKey, requestedPage - 1)
                            search.isNotBlank() ->
                                callbacks.searchSourceV2(
                                    source.sourceKey,
                                    search,
                                    requestedPage - 1,
                                    options = v2Options,
                                )
                            else -> callbacks.browseSourceV2(
                                source.sourceKey,
                                options = v2Options,
                                page = requestedPage - 1,
                            )
                        }
                        BrowsePage(
                            items = v2Page.items.map { publication -> publication.toBrowseManga(source) },
                            hasNextPage = v2Page.hasNextPage,
                        )
                    } else if (mode == SourceCatalogueMode.Latest && search.isBlank()) {
                        callbacks.browseSourceLatest(source.id, requestedPage)
                    } else if (mode == SourceCatalogueMode.Favorites) {
                        callbacks.browseSourceFavorites(source.id, requestedPage)
                    } else {
                        callbacks.browseSource(
                            sourceId = source.id,
                            query = search,
                            page = requestedPage,
                            filters = filters,
                        )
                    }
                    if (append) {
                        loaded.copy(
                            items = (existingItems + loaded.items)
                                .distinctBy(BrowseManga::identityKey),
                        )
                    } else {
                        loaded
                    }
                }
                if (catalogueRequestToken == requestToken) {
                    page = result
                    cataloguePageNumber = requestedPage
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (catalogueRequestToken == requestToken) {
                    // A replacement search must not present the previous query's results as
                    // its own. Pagination failures keep already loaded items and the footer.
                    if (!append) page = BrowsePage()
                    catalogueError = error.localizedDiagnosticMessage(strings)
                    if (
                        error.sourceFailureMarker() == SOURCE_HTTP_CHALLENGE_MARKER &&
                        overlays.activeSource?.identityKey == source.identityKey
                    ) {
                        pendingCatalogueRecovery = exactRequest
                        val available = try {
                            withContext(Dispatchers.Default) {
                                callbacks.isSourceWebChallengeAvailable(source.id)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            false
                        }
                        if (
                            catalogueRequestToken == requestToken &&
                            pendingCatalogueRecovery === exactRequest &&
                            overlays.activeSource?.identityKey == source.identityKey
                        ) {
                            catalogueChallengeAvailable = available
                        }
                    }
                }
            } finally {
                if (catalogueRequestToken == requestToken) {
                    catalogueLoading = false
                    catalogueLoadingMore = false
                }
                runningJob?.let(catalogueJobs::clear)
            }
        }
        catalogueJobs.replace(job)
        job.start()
    }

    fun openCatalogueWebChallenge() {
        val recovery = pendingCatalogueRecovery ?: return
        if (!catalogueChallengeAvailable || catalogueChallengePreparing) return
        if (overlays.activeSource?.identityKey != recovery.source.identityKey) {
            clearCatalogueRecovery()
            return
        }
        catalogueChallengePreparing = true
        scope.launch {
            withFrameNanos { }
            var issuedRequest: SourceWebChallengeRequest? = null
            var delivered = false
            try {
                val request = withContext(Dispatchers.Default) {
                    callbacks.sourceWebChallenge(recovery.source.id).also { issuedRequest = it }
                }
                if (
                    request == null ||
                    pendingCatalogueRecovery !== recovery ||
                    overlays.activeSource?.identityKey != recovery.source.identityKey
                ) {
                    if (pendingCatalogueRecovery === recovery) catalogueChallengeAvailable = false
                } else {
                    catalogueChallenge = CatalogueWebChallenge(recovery, request)
                    delivered = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (pendingCatalogueRecovery === recovery) {
                    catalogueError = error.localizedDiagnosticMessage(strings)
                    catalogueChallengeAvailable = false
                }
            } finally {
                if (pendingCatalogueRecovery === recovery) catalogueChallengePreparing = false
                if (!delivered) {
                    issuedRequest?.capability?.let { capability ->
                        withContext(NonCancellable) {
                            callbacks.cancelSourceWebChallenge(capability)
                        }
                    }
                }
            }
        }
    }

    // ShinsouComposition.start owns the initial extension refresh on every platform. Re-entering
    // this composition must not repeat that network/package scan; the toolbar remains the retry path.
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = strings.browse,
                subtitle = when (section) {
                    BrowseSection.Sources -> strings.sources
                    BrowseSection.Extensions -> strings.extensions
                    BrowseSection.Migration -> strings.migration
                },
                actions = {
                    if (section == BrowseSection.Sources) {
                        IconButton(
                            onClick = {
                                cancelCatalogueLoad()
                                overlays = overlays.openGlobalSearch()
                            },
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = strings.text("Search all sources"))
                        }
                    }
                    IconButton(
                        onClick = { launchOperation { callbacks.refresh() } },
                        enabled = !snapshot.isRefreshing && extensionOperations.active == null,
                    ) {
                        if (snapshot.isRefreshing) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, strings.refresh)
                        }
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrowseSection.entries.forEach { option ->
                    FilterChip(
                        selected = section == option,
                        onClick = {
                            val parentWasOpen = overlays.activeSource != null || overlays.globalSearchVisible
                            cancelCatalogueLoad()
                            clearCatalogueRecovery()
                            overlays = overlays.closeAll()
                            currentReaderVisibilityCallback.value(false)
                            if (parentWasOpen) onParentDismissed()
                            section = option
                            query = ""
                        },
                        label = {
                            Text(
                                when (option) {
                                    BrowseSection.Sources -> strings.sources
                                    BrowseSection.Extensions -> strings.extensions
                                    BrowseSection.Migration -> strings.migration
                                },
                            )
                        },
                    )
                }
            }
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = strings.search,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
            )
            when (section) {
                BrowseSection.Sources -> SourcesPane(
                    repositories = snapshot.repositories,
                    sources = snapshot.sources.filter {
                        (showNsfw || !it.isNsfw) &&
                            (enabledLanguages.isEmpty() || it.language in enabledLanguages)
                    }.filter { it.name.contains(query, ignoreCase = true) },
                    onToggle = { source, enabled ->
                        launchOperation {
                            source.sourceKey?.let { callbacks.setSourceEnabledV2(it, enabled) }
                                ?: callbacks.setSourceEnabled(source.id, enabled)
                        }
                    },
                        pinnedSourceIds = pinnedSourceIds,
                        pinnedSourceKeys = pinnedSourceKeys,
                        onSourcePinnedChange = onSourcePinnedChange,
                        onSourcePinnedKeyChange = onSourcePinnedKeyChange,
                    callbacks = callbacks,
                    onImportDocument = onImportDocument,
                    onOpen = { source ->
                        clearCatalogueRecovery()
                        overlays = overlays.openSource(source)
                        page = BrowsePage()
                        cataloguePageNumber = 1
                        loadCatalogue(
                            source,
                            "",
                            SourceCatalogueMode.Popular,
                            filters = null,
                            requestedPage = 1,
                            append = false,
                        )
                    },
                )

                BrowseSection.Extensions -> ExtensionsPane(
                    operation = extensionOperations.active,
                    repositories = snapshot.repositories,
                    selectedRepositoryId = snapshot.selectedRepositoryId,
                    extensions = snapshot.extensions.filter {
                        (showNsfw || !it.isNsfw) &&
                            (enabledLanguages.isEmpty() || it.language in enabledLanguages) &&
                            it.name.contains(query, ignoreCase = true)
                    },
                    onAddRepository = { url ->
                        launchOperation {
                            // ADR-0002 §8.2: a signed third-party repository shows its publisher key first.
                            val prompt = callbacks.inspectRepositoryTrust(url)
                            when {
                                prompt == null -> callbacks.addRepository(url) // unsigned: the URL the user typed
                                prompt.alreadyTrusted -> callbacks.trustAndAddRepository(prompt, null)
                                else -> pendingRepositoryTrust = prompt
                            }
                        }
                    },
                    onOpenPackageSecurity = { launchOperation { packageSecurity = callbacks.packageSecurity() } },
                    onUnblock = { extension ->
                        extension.blockEntryKey?.let { key -> launchOperation { callbacks.unblockPackageEntry(key) } }
                    },
                    onBlock = { extension ->
                        launchOperation { callbacks.setExtensionBlocked(extension.id, blocked = true) }
                    },
                    repositorySuggestions = snapshot.repositorySuggestions,
                    canRemoveFromAccount = sync?.state?.collectAsState()?.value?.signedIn == true,
                    onRemoveRepository = { id, fromAccount ->
                        launchOperation { callbacks.removeRepository(id, fromAccount) }
                    },
                    onDismissRepositorySuggestion = { url -> launchOperation { callbacks.dismissRepositorySuggestion(url) } },
                    onSelectRepository = { id -> launchOperation { callbacks.selectRepository(id) } },
                    onInstall = { extension ->
                        if (extension.installed && extension.updateAvailable) launchExtensionOperation(
                            extension,
                            "Updating…",
                            onSuccess = { review ->
                                pendingPluginEventGrant = review?.let { PendingPluginEventGrant(extension, it) }
                                if (review == null) {
                                    scope.launch {
                                        operationSnackbar.showSnackbar(
                                            strings.text("{0} was updated and kept the permissions you granted before.", extension.name),
                                        )
                                    }
                                }
                            },
                        ) {
                            // Asks for something new: review it first; the installed version keeps running.
                            val preview = callbacks.updatePermissionPreview(extension.id)
                            if (preview != null) {
                                pendingUpdateReview = extension to preview
                                return@launchExtensionOperation null
                            }
                            callbacks.installExtension(extension.id)
                            callbacks.pendingPluginEventGrantReview(extension.id)
                        }
                        else launchExtensionOperation(
                            extension,
                            if (extension.installed) "Updating…" else "Installing…",
                            onSuccess = { review ->
                                pendingPluginEventGrant = review?.let { PendingPluginEventGrant(extension, it) }
                                // An update that asked for nothing new kept the previous grant (D4): say so.
                                if (review == null && extension.installed) {
                                    scope.launch {
                                        operationSnackbar.showSnackbar(
                                            strings.text("{0} was updated and kept the permissions you granted before.", extension.name),
                                        )
                                    }
                                }
                            },
                        ) {
                            callbacks.installExtension(extension.id)
                            callbacks.pendingPluginEventGrantReview(extension.id)
                        }
                    },
                    onUninstall = { extension ->
                        launchExtensionOperation(extension, "Removing…") {
                            callbacks.uninstallExtension(extension.id)
                        }
                    },
                    onPermissions = { extension ->
                        launchExtensionOperation(
                            extension,
                            "Loading permissions…",
                            onSuccess = { permissions: dev.shinsou.kmp.contract.InstalledPluginPermissions? ->
                                editingPluginPermissions = permissions?.let { extension to it }
                            },
                        ) { callbacks.installedPluginPermissions(extension.id) }
                    },
                    onTrust = { extension, trusted ->
                        launchExtensionOperation(
                            extension,
                            "Updating trust…",
                            onSuccess = { review ->
                                pendingPluginEventGrant = review?.let { PendingPluginEventGrant(extension, it) }
                            },
                        ) {
                            callbacks.setExtensionTrusted(extension.id, trusted)
                            if (trusted) callbacks.pendingPluginEventGrantReview(extension.id) else null
                        }
                    },
                )

                BrowseSection.Migration -> MigrationPane(
                    migrations = snapshot.migrations.filter { it.title.contains(query, ignoreCase = true) },
                    sources = snapshot.sources.filter {
                        it.enabled &&
                            (showNsfw || !it.isNsfw) &&
                            (enabledLanguages.isEmpty() || it.language in enabledLanguages)
                    },
                    callbacks = callbacks,
                    onMigrate = { candidate, target ->
                        launchOperation { callbacks.migrateManga(candidate.mangaId, target) }
                    },
                )
            }
        }

        if (overlays.globalSearchVisible) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = size.width * backGestureProgress.coerceIn(0f, 1f)
                    },
            ) {
                GlobalSearchScreen(
                    callbacks = callbacks,
                    sources = snapshot.sources.filter {
                        it.enabled &&
                            (showNsfw || !it.isNsfw) &&
                            (enabledLanguages.isEmpty() || it.language in enabledLanguages)
                    },
                    onBack = ::closeGlobalSearch,
                    onOpenManga = ::openManga,
                )
            }
        }

        overlays.activeSource?.let { source ->
            // The dialog reads the live source row: cookies and the saved account change while it is open.
            if (catalogueLoginSourceIdentity == source.identityKey) {
                SourceSettingsDialog(
                    source = snapshot.sources.firstOrNull { it.identityKey == source.identityKey } ?: source,
                    callbacks = callbacks,
                    onImportDocument = onImportDocument,
                    onDismiss = { catalogueLoginSourceIdentity = null },
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = size.width * backGestureProgress.coerceIn(0f, 1f)
                    },
            ) {
                SourceCatalogueScreen(
                    source = source,
                    refreshGeneration = source.sourceKey?.let(sourceRefreshInvalidations::get) ?: 0L,
                    page = page,
                    loading = catalogueLoading,
                    loadingMore = catalogueLoadingMore,
                    error = catalogueError,
                    challengeAvailable = catalogueChallengeAvailable,
                    challengePreparing = catalogueChallengePreparing,
                    onBack = ::closeActiveSource,
                    onWebChallenge = ::openCatalogueWebChallenge,
                    signedIn = (snapshot.sources.firstOrNull { it.identityKey == source.identityKey } ?: source).signedIn,
                    onLogin = if (source.canSignIn) {
                        { catalogueLoginSourceIdentity = source.identityKey }
                    } else {
                        null
                    },
                    onBrowse = { search, mode, filters ->
                        overlays.activeSource?.let {
                            loadCatalogue(it, search, mode, filters, requestedPage = 1, append = false)
                        }
                    },
                    onLoadMore = { search, mode, filters ->
                        overlays.activeSource?.let {
                            loadCatalogue(
                                it,
                                search,
                                mode,
                                filters,
                                requestedPage = cataloguePageNumber + 1,
                                append = true,
                            )
                        }
                    },
                    onOpenManga = ::openManga,
                )
            }
        }
        overlays.activeV2Publication?.let { publication ->
            val source = snapshot.sources.firstOrNull { source ->
                source.sourceKey == publication.sourceKey
            }
            // Sources without an explicit account-favorite capability use the app-owned library.
            // The two mutation boundaries stay disjoint so a local bookmark cannot leak into a
            // website account.
            val favoriteDestination = extensionFavoriteDestination(source?.supportsFavorites)
            val localLibrary = favoriteDestination == ExtensionFavoriteDestination.LOCAL_LIBRARY
            val supportsFavorite = true
            val localFavorite = localLibrary && isLocalLibraryFavorite(publication)
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = size.width * backGestureProgress.coerceIn(0f, 1f)
                    },
            ) {
                // Keep the catalogue mounted on desktop, exactly like the legacy manga detail
                // pane. Mobile still receives a full-screen destination so the chapter list can
                // scroll naturally without squeezing the source grid.
                val detailModifier = if (maxWidth >= 900.dp && !overlays.activeV2Reader) {
                    Modifier.fillMaxHeight().fillMaxWidth(0.68f).align(Alignment.CenterEnd)
                } else {
                    Modifier.fillMaxSize()
                }
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = detailModifier,
                ) {
                    ExtensionV2PublicationPane(
                        callbacks = callbacks,
                        sync = sync,
                        item = publication,
                        supportsFavorite = supportsFavorite,
                        favoriteDestination = favoriteDestination,
                        localLibrary = localLibrary,
                        localLibraryFavorite = localFavorite,
                        onToggleLocalLibrary = onToggleLocalLibrary,
                        refreshGeneration = publication.sourceKey?.let(sourceRefreshInvalidations::get) ?: 0L,
                        contentFeatures = contentFeatures,
                        copyText = copyText,
                        readerSettings = readerSettings,
                        onReaderSettingsChange = onReaderSettingsChange,
                        onOpenExternalUrl = openExternalUrl,
                        onShareText = shareText,
                        onReaderVisibilityChanged = { visible ->
                            overlays = overlays.setReaderVisible(visible)
                            currentReaderVisibilityCallback.value(visible)
                        },
                        onReaderProgress = onReaderProgress,
                        onReaderProgressFlushed = onReaderProgressFlushed,
                        volumeKeyRouter = volumeKeyRouter,
                        readerBackRequest = v2ReaderBackRequest,
                        onBack = {
                            overlays = overlays.closePublication()
                            currentReaderVisibilityCallback.value(false)
                        },
                    )
                }
            }
        }
        if (!overlays.activeV2Reader) {
            SnackbarHost(
                hostState = operationSnackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp),
            )
        }

        catalogueChallenge?.let { challenge ->
            SourceWebChallengeDialog(
                request = challenge.browserRequest,
                onImport = { importedSession ->
                    // Remove the dialog immediately. The opaque authority is one-shot, so neither
                    // a double click nor a late platform callback can start a duplicate import.
                    if (catalogueChallenge !== challenge) return@SourceWebChallengeDialog
                    catalogueChallenge = null
                    catalogueChallengePreparing = true
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        var imported = false
                        try {
                            withContext(Dispatchers.Default) {
                                callbacks.importSourceWebChallengeSession(
                                    sourceId = challenge.catalogueRequest.source.id,
                                    capability = importedSession.capability,
                                    cookies = importedSession.cookies,
                                    userAgent = importedSession.userAgent,
                                    localStorage = importedSession.localStorage,
                                )
                            }
                            imported = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            if (pendingCatalogueRecovery === challenge.catalogueRequest) {
                                catalogueError = error.localizedDiagnosticMessage(strings)
                                catalogueChallengeAvailable = false
                            }
                        } finally {
                            catalogueChallengePreparing = false
                            // Import consumes valid grants. Cancellation or an early validation
                            // failure must consume them too, and cancellation itself cannot skip it.
                            withContext(NonCancellable) {
                                callbacks.cancelSourceWebChallenge(challenge.browserRequest.capability)
                            }
                        }
                        if (
                            imported &&
                            pendingCatalogueRecovery === challenge.catalogueRequest &&
                            overlays.activeSource?.identityKey == challenge.catalogueRequest.source.identityKey
                        ) {
                            val retry = challenge.catalogueRequest
                            // Consume this recovery before dispatch; a repeated typed challenge
                            // creates a fresh explicit CTA, never an automatic retry loop.
                            pendingCatalogueRecovery = null
                            catalogueChallengeAvailable = false
                            loadCatalogue(
                                source = retry.source,
                                search = retry.search,
                                mode = retry.mode,
                                filters = retry.filters,
                                requestedPage = retry.requestedPage,
                                append = retry.append,
                            )
                        }
                    }
                },
                onDismiss = {
                    if (catalogueChallenge !== challenge) return@SourceWebChallengeDialog
                    catalogueChallenge = null
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        withContext(NonCancellable) {
                            callbacks.cancelSourceWebChallenge(challenge.browserRequest.capability)
                        }
                    }
                },
            )
        }
    }


    pendingRepositoryTrust?.let { prompt ->
        RepositoryTrustDialog(
            prompt = prompt,
            onTrust = { fingerprint ->
                pendingRepositoryTrust = null
                launchOperation { callbacks.trustAndAddRepository(prompt, fingerprint) }
            },
            onDismiss = { pendingRepositoryTrust = null },
        )
    }

    packageSecurity?.let { current ->
        fun change(action: suspend () -> Unit) = launchOperation {
            action()
            packageSecurity = callbacks.packageSecurity()
        }
        PackageSecurityDialog(
            snapshot = current,
            enabled = extensionOperations.active == null,
            onStrictMode = { strict -> change { callbacks.setStrictPublisherTrust(strict) } },
            onPublisherBlocked = { id, blocked -> change { callbacks.setPublisherBlocked(id, blocked) } },
            onConfirmFingerprint = { id, fingerprint -> change { callbacks.confirmPublisherFingerprint(id, fingerprint) } },
            onUnblock = { key -> change { callbacks.unblockPackageEntry(key) } },
            onDeveloperMode = { on -> change { callbacks.setPluginDeveloperMode(on) } },
            onDeveloperHosts = { hosts -> change { callbacks.setPluginDeveloperHosts(hosts) } },
            onDismiss = { packageSecurity = null },
        )
    }

    pendingPluginEventGrant?.let { pending ->
        var details by remember(pending) { mutableStateOf<dev.shinsou.kmp.contract.PluginPermissionDetails?>(null) }
        LaunchedEffect(pending) {
            details = runCatchingCancellable { callbacks.pluginPermissionDetails(pending.extension.id, pending.review) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }
        PluginPermissionReviewDialog(
            review = pending.review,
            details = details,
            onConfirm = { selection ->
                if (launchExtensionOperation(pending.extension, "Applying permissions…") {
                    callbacks.approvePluginEventGrantReview(
                        pending.extension.id,
                        pending.review,
                        selection.hostPermissions,
                        selection,
                    )
                }) pendingPluginEventGrant = null
            },
            onDismiss = { pendingPluginEventGrant = null },
        )
    }

    pendingUpdateReview?.let { (extension, preview) ->
        PluginPermissionReviewDialog(
            review = preview.review,
            details = preview.details,
            onConfirm = { selection ->
                pendingUpdateReview = null
                launchExtensionOperation(
                    extension,
                    "Updating…",
                    onSuccess = { remaining: dev.shinsou.kmp.plugin.events.PluginEventGrantReview? ->
                        // The artifact asked for something else than shown: review that instead.
                        pendingPluginEventGrant = remaining?.let { PendingPluginEventGrant(extension, it) }
                    },
                ) { callbacks.installReviewedUpdate(extension.id, preview, selection) }
            },
            onDismiss = { pendingUpdateReview = null },
        )
    }

    editingPluginPermissions?.let { (extension, permissions) ->
        PluginPermissionReviewDialog(
            review = permissions.review,
            details = permissions.details,
            current = permissions.granted,
            onConfirm = { selection ->
                if (selection == permissions.granted) {
                    editingPluginPermissions = null
                } else if (launchExtensionOperation(extension, "Applying permissions…") {
                    callbacks.narrowPluginPermissions(extension.id, selection)
                }) editingPluginPermissions = null
            },
            onDismiss = { editingPluginPermissions = null },
        )
    }
}

private fun Throwable.diagnosticMessage(): String {
    val messages = generateSequence(this) { it.cause }
        .mapNotNull { error -> error.message?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .toList()
    return messages.joinToString(" ← ").ifBlank {
        this::class.simpleName ?: "Unknown error"
    }
}

internal fun Throwable.localizedDiagnosticMessage(strings: ShinsouStrings): String {
    localizedSourceFailure(strings)?.let { return it }
    // A repository that switched to signed publishing: its card already offers "Confirm publisher".
    if (generateSequence(this) { it.cause }.any { it is dev.shinsou.kmp.plugin.RepositoryTrustException.UntrustedRepository }) {
        return strings.text("Now signed by its publisher: confirm the publisher to keep updating.")
    }
    if (generateSequence(this) { it.cause }.any { it is ExtensionArtifactNotReviewedException }) {
        return strings.text("This plugin can't run: it isn't from a publisher you trust, or this device has no plugin sandbox.")
    }
    if (generateSequence(this) { it.cause }.any {
            it.message in setOf(
                "Installed plugin artifact or requested permissions changed after review",
                "The exact displayed plugin review is no longer pending",
                "Reviewed plugin identity does not match the requested extension",
            )
        }
    ) return strings.text("This extension changed. Review its permissions again.")
    val diagnostic = diagnosticMessage()
    val translated = strings.text(diagnostic)
    if (translated != diagnostic) return translated
    // Repository/extension failures carry a deliberately bounded, non-secret message (status,
    // URL policy, metadata field, etc.). Hiding it behind one generic sentence made `index.json`/legacy
    // `repo.json` mismatches impossible to diagnose from the UI.
    return diagnostic
        .filterNot(Char::isISOControl)
        .take(MAX_DIAGNOSTIC_MESSAGE_CHARS)
        .ifBlank { strings.text("The operation could not be completed.") }
}

private const val MAX_DIAGNOSTIC_MESSAGE_CHARS: Int = 512

@Composable
private fun GlobalSearchScreen(
    callbacks: BrowseCallbacks,
    sources: List<BrowseSource>,
    onBack: () -> Unit,
    onOpenManga: (BrowseManga) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SourceSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var requestVersion by remember { mutableStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun search() {
        val submittedQuery = query.trim()
        if (submittedQuery.isEmpty()) return
        val requestedSources = sources.distinctBy(BrowseSource::identityKey)
        val version = requestVersion + 1
        requestVersion = version
        searchJob?.cancel()
        searching = true
        hasSearched = true
        results = emptyList()
        searchJob = scope.launch {
            withFrameNanos { }
            val orderedResults = arrayOfNulls<SourceSearchResult>(requestedSources.size)
            try {
                searchAcrossSources(callbacks, requestedSources, submittedQuery, strings).collect { update ->
                    if (requestVersion != version) return@collect
                    orderedResults[update.sourceIndex] = update.result
                    results = orderedResults.filterNotNull()
                }
            } finally {
                if (requestVersion == version) {
                    searching = false
                    searchJob = null
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = strings.text("Global Search"),
            subtitle = strings.text("{0} enabled sources", sources.size),
            leading = {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text("Back")) }
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = strings.text("Search all sources"),
                modifier = Modifier.weight(1f),
                onSubmit = ::search,
            )
            Button(onClick = ::search, enabled = query.isNotBlank()) {
                if (searching) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.search)
                }
            }
        }

        when {
            sources.isEmpty() -> EmptyState(
                title = strings.text("No enabled sources"),
                message = strings.text("Enable at least one source before searching."),
                icon = { Icon(Icons.Outlined.TravelExplore, null, Modifier.size(30.dp)) },
            )
            searching && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ShinsouLoadingIndicator()
            }
            !hasSearched -> EmptyState(
                title = strings.text("Search across sources"),
                message = strings.text("Results are grouped by source so you can compare editions before opening one."),
                icon = { Icon(Icons.Outlined.Search, null, Modifier.size(30.dp)) },
            )
            results.isEmpty() -> EmptyState(
                title = strings.noMatches,
                message = strings.text("Try a different title or enable more source languages."),
                icon = { Icon(Icons.Outlined.Search, null, Modifier.size(30.dp)) },
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(results, key = { "global:${it.source.identityKey}" }) { result ->
                    SourceSearchResultSection(result, onOpenManga)
                }
            }
        }
    }
}

@Composable
private fun SourceSearchResultSection(
    result: SourceSearchResult,
    onOpenManga: (BrowseManga) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceIcon(result.source)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(result.source.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    strings.text("{0} · {1} results", result.source.language.uppercase(), result.totalCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            result.items.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(result.items, key = BrowseManga::identityKey) { item ->
                    BrowseResultCard(item = item, onClick = { onOpenManga(item) })
                }
            }
            result.errorMessage != null -> Text(
                result.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Text(
                strings.text("No matches from this source."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun BrowseResultCard(item: BrowseManga, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.width(112.dp),
    ) {
        Column(Modifier.padding(6.dp)) {
            CoverImage(
                title = item.title,
                url = item.thumbnailUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                headers = item.thumbnailHeaders,
                sourceId = item.sourceId,
                sourceKey = item.sourceKey,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.author?.takeIf(String::isNotBlank)?.let { author ->
                Text(
                    author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
