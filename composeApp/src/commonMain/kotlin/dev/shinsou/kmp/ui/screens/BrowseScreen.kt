package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.ui.BrowseCallbacks
import dev.shinsou.kmp.ui.BrowseExtension
import dev.shinsou.kmp.ui.BrowseFilter
import dev.shinsou.kmp.ui.BrowseManga
import dev.shinsou.kmp.ui.BrowsePage
import dev.shinsou.kmp.ui.BrowseRepository
import dev.shinsou.kmp.ui.BrowseSource
import dev.shinsou.kmp.plugin.PluginContentType
import dev.shinsou.kmp.plugin.toBrowseFilterV2
import dev.shinsou.kmp.ui.BrowseSortSelection
import dev.shinsou.kmp.ui.BrowseTriState
import dev.shinsou.kmp.ui.ImportedDocument
import dev.shinsou.kmp.ui.MigrationCandidate
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceCredential
import dev.shinsou.kmp.ui.SourcePreferenceKind
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.TypedReaderContentSession
import dev.shinsou.kmp.ui.ExtensionFavoriteDestination
import dev.shinsou.kmp.ui.extensionFavoriteDestination
import dev.shinsou.kmp.ui.mutateExtensionFavorite
import dev.shinsou.kmp.app.ContentFeatureRuntime
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.plugin.v2.extensionPublicationKey
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.ui.ReaderProgressPosition
import dev.shinsou.kmp.ui.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.ReaderVolumeKeyHandlerSlot
import dev.shinsou.kmp.ui.ReaderVolumeKeyRouter
import dev.shinsou.kmp.ui.effectiveReaderVolumeKeysEnabled
import dev.shinsou.kmp.ui.readerVolumeKeyAction
import dev.shinsou.kmp.ui.readerMayCrossChapterBoundary
import dev.shinsou.kmp.ui.readerStatusBarsPadding
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerException
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationPageV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueQuarantineReviewV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewStatusV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueExecutionPermissionV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedInstallApprovalV2
import dev.shinsou.kmp.ui.challenge.SourceWebChallengeDialog
import dev.shinsou.kmp.ui.components.CoverImage
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.components.SearchField
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class BrowseSection {
    Sources,
    Extensions,
    Migration,
}

private enum class SourceCatalogueMode {
    Popular,
    Latest,
    Favorites,
}

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
private const val GLOBAL_SEARCH_VISIBLE_RESULTS: Int = 10

private data class PendingReviewedShuYueInstall(
    val extension: BrowseExtension,
    val review: ShuYueQuarantineReviewV2,
)

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
    // A reader is owned by ExtensionV2PublicationPane. Keep a monotonically increasing
    // request so a system back gesture can ask that child to dispose its materialized session
    // before the parent changes the layout back to the publication detail pane.
    var v2ReaderBackRequest by remember { mutableStateOf(0L) }
    var page by remember { mutableStateOf(BrowsePage()) }
    var catalogueLoading by remember { mutableStateOf(false) }
    var catalogueLoadingMore by remember { mutableStateOf(false) }
    var catalogueError by remember { mutableStateOf<String?>(null) }
    var cataloguePageNumber by remember { mutableStateOf(1) }
    var catalogueRequestToken by remember { mutableStateOf(0) }
    val catalogueJobs = remember { CatalogueJobController() }
    var pendingReviewedInstall by remember { mutableStateOf<PendingReviewedShuYueInstall?>(null) }
    var pendingPluginEventGrant by remember { mutableStateOf<PendingPluginEventGrant?>(null) }
    var reviewedInstallBusyId by remember { mutableStateOf<String?>(null) }
    var handledSystemBackRequest by remember { mutableStateOf(systemBackRequest) }
    val operationSnackbar = remember { SnackbarHostState() }
    val hasBrowseOverlay = overlays.hasOverlay
    val currentBackAvailabilityCallback = rememberUpdatedState(onBackAvailabilityChanged)
    val currentReaderVisibilityCallback = rememberUpdatedState(onReaderVisibilityChanged)

    DisposableEffect(Unit) {
        onDispose { currentReaderVisibilityCallback.value(false) }
    }

    LaunchedEffect(snapshot.extensions.map { Triple(it.id, it.version, it.installed) }) {
        if (pendingPluginEventGrant == null) {
            snapshot.extensions.firstOrNull { it.installed && !it.reviewedShuYueV2 }?.let { extension ->
                callbacks.pendingPluginEventGrantReview(extension.id)?.let { review ->
                    pendingPluginEventGrant = PendingPluginEventGrant(extension, review)
                }
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

    fun closeActiveSource() {
        val hadSource = overlays.activeSource != null
        cancelCatalogueLoad()
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

    fun stageReviewedShuYue(extension: BrowseExtension) {
        if (reviewedInstallBusyId != null) return
        reviewedInstallBusyId = extension.id
        scope.launch {
            withFrameNanos { }
            try {
                val review = withContext(Dispatchers.Default) {
                    callbacks.stageReviewedShuYuePackageV2(extension.id)
                }
                check(review.reviewStatus == ShuYueReviewStatusV2.REVIEWED) {
                    "Downloaded ShuYue artifact is not an exact reviewed version"
                }
                pendingReviewedInstall = PendingReviewedShuYueInstall(extension, review)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                operationSnackbar.showSnackbar(error.localizedDiagnosticMessage(strings))
            } finally {
                reviewedInstallBusyId = null
            }
        }
    }

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
                    catalogueError = error.localizedDiagnosticMessage(strings)
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
                        enabled = !snapshot.isRefreshing,
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
                    repositories = snapshot.repositories,
                    selectedRepositoryId = snapshot.selectedRepositoryId,
                    extensions = snapshot.extensions.filter {
                        (showNsfw || !it.isNsfw) &&
                            (enabledLanguages.isEmpty() || it.language in enabledLanguages) &&
                            it.name.contains(query, ignoreCase = true)
                    },
                    onAddRepository = { url -> launchOperation { callbacks.addRepository(url) } },
                    onRemoveRepository = { id -> launchOperation { callbacks.removeRepository(id) } },
                    onSelectRepository = { id -> launchOperation { callbacks.selectRepository(id) } },
                    onInstall = { extension ->
                        if (extension.reviewedShuYueV2) stageReviewedShuYue(extension)
                        else launchOperation {
                            callbacks.installExtension(extension.id)
                            callbacks.pendingPluginEventGrantReview(extension.id)?.let { review ->
                                pendingPluginEventGrant = PendingPluginEventGrant(extension, review)
                            }
                        }
                    },
                    onUninstall = { extension ->
                        launchOperation {
                            if (extension.reviewedShuYueV2) {
                                callbacks.uninstallReviewedShuYueV2(extension.id)
                            } else {
                                callbacks.uninstallExtension(extension.id)
                            }
                        }
                    },
                    onTrust = { extension, trusted ->
                        launchOperation { callbacks.setExtensionTrusted(extension.id, trusted) }
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
                    onBack = ::closeActiveSource,
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
    }


    pendingReviewedInstall?.let { pending ->
        val review = pending.review
        AlertDialog(
            onDismissRequest = { pendingReviewedInstall = null },
            title = { Text(strings.text("Approve reviewed extension")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pending.extension.name, style = MaterialTheme.typography.titleMedium)
                    pending.extension.description?.let { Text(it) }
                    Text(
                        strings.text("SHA-256: {0}", review.identity.sha256),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(strings.text("Required permissions"), fontWeight = FontWeight.SemiBold)
                    review.requiredPermissions.sortedBy { it.name }.forEach { permission ->
                        Text("• ${permission.localizedLabel(strings)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        strings.text("The script remains blocked until you approve this exact version and digest."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingReviewedInstall = null
                        launchOperation {
                            callbacks.approveAndInstallReviewedShuYueV2(
                                ShuYueReviewedInstallApprovalV2(
                                    quarantineId = review.quarantineId,
                                    identity = review.identity,
                                    grantedPermissions = review.requiredPermissions,
                                    userConfirmed = true,
                                    replaceInstalledVersion = pending.extension.installed,
                                ),
                            )
                        }
                    },
                ) { Text(strings.install) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReviewedInstall = null }) { Text(strings.cancel) }
            },
        )
    }

    pendingPluginEventGrant?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingPluginEventGrant = null },
            title = { Text(strings.text("Review host permissions")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pending.extension.name, style = MaterialTheme.typography.titleMedium)
                    Text("SHA-256: ${pending.review.artifact.sha256}", style = MaterialTheme.typography.labelSmall)
                    pending.review.requestedPermissions.sortedBy { it.name }.forEach { permission ->
                        Text("• ${permission.name}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        strings.text("Host event permissions remain blocked until you approve this exact version and digest."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingPluginEventGrant = null
                    launchOperation {
                        callbacks.approvePluginEventGrantReview(
                            pending.extension.id,
                            pending.review.requestedPermissions,
                        )
                    }
                }) { Text(strings.text("Approve")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPluginEventGrant = null }) { Text(strings.cancel) }
            },
        )
    }
}

private fun ShuYueExecutionPermissionV2.localizedLabel(strings: ShinsouStrings): String = strings.text(
    when (this) {
        ShuYueExecutionPermissionV2.EXECUTE_SCRIPT -> "Execute reviewed script"
        ShuYueExecutionPermissionV2.NETWORK -> "Network access"
        ShuYueExecutionPermissionV2.COOKIE_STORAGE -> "Cookie storage"
        ShuYueExecutionPermissionV2.CREDENTIAL_ACCESS -> "Credential access"
        ShuYueExecutionPermissionV2.LOGIN_PROMPT -> "Show login prompt"
        ShuYueExecutionPermissionV2.FAVORITE_MUTATION -> "Modify favorites"
        ShuYueExecutionPermissionV2.BROWSER_CHALLENGE -> "Open browser challenge"
    },
)

private fun Throwable.diagnosticMessage(): String {
    val messages = generateSequence(this) { it.cause }
        .mapNotNull { error -> error.message?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .toList()
    return messages.joinToString(" ← ").ifBlank {
        this::class.simpleName ?: "Unknown error"
    }
}

private fun Throwable.localizedDiagnosticMessage(strings: ShinsouStrings): String {
    val diagnostic = diagnosticMessage()
    val translated = strings.text(diagnostic)
    if (translated != diagnostic) return translated
    // Repository/extension failures carry a deliberately bounded, non-secret message (status,
    // URL policy, metadata field, etc.). Hiding it behind one generic sentence made ShuYue
    // `index.json`/legacy `repo.json` mismatches impossible to diagnose from the UI.
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
                CircularProgressIndicator()
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
private fun BrowseResultCard(item: BrowseManga, onClick: () -> Unit) {
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

private data class PendingExtensionRepresentationSelection(
    val selection: ExtensionUnitSelectionV2,
    val representationIds: List<String>,
    val openReader: Boolean,
)

private class LatestExtensionReaderProgress {
    var position: ReaderProgressPosition? = null
}

private enum class ExtensionChapterFilter {
    ALL,
    UNREAD,
    READ,
    BOOKMARKED,
    DOWNLOADED,
}

internal fun extensionContinueUnitId(
    unitIds: List<String>,
    completedUnitIds: Set<String>,
    resumeUnitId: String?,
): String? = resumeUnitId
    ?.takeIf(unitIds::contains)
    ?: unitIds.firstOrNull { it !in completedUnitIds }
    ?: unitIds.lastOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtensionNovelReaderShell(
    title: String,
    currentUnitTitle: String,
    units: List<ExtensionUnitSelectionV2>,
    activeUnitIndex: Int,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenUnit: (ExtensionUnitSelectionV2) -> Unit,
    session: TypedReaderContentSession,
    features: ContentFeatureRuntime,
    copyText: (label: String, text: String) -> Boolean,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onCompleted: () -> Unit,
    onReaderProgress: (ReaderProgressPosition) -> Unit,
    onReaderProgressFlushed: suspend () -> Unit,
    volumeKeyRouter: ReaderVolumeKeyRouter?,
    systemBackRequest: Long,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    val hasCurrent = activeUnitIndex in units.indices
    val textContent = session.content.representation is ContentRepresentation.PlainText ||
        session.content.representation is ContentRepresentation.EpubSpine
    val epubReader = session.content.representation is ContentRepresentation.EpubSpine
    val imageReader = session.content.representation is ContentRepresentation.ImageSequence
    val initialPage = session.initialVisualPageIndex
        ?: session.content.navigation.indexOf(session.content.initialLocator)
        ?: 0
    var controlsVisible by remember(session) { mutableStateOf(false) }
    var settingsVisible by remember(session) { mutableStateOf(false) }
    var chapterListVisible by remember(session) { mutableStateOf(false) }
    var progressTransitionInFlight by remember(session) { mutableStateOf(false) }
    var handledSystemBackRequest by remember(session) { mutableStateOf(systemBackRequest) }
    var currentPage by remember(session) { mutableStateOf(initialPage) }
    var pageCount by remember(session) {
        mutableStateOf(
            when {
                imageReader -> session.content.navigation.itemCount.coerceAtLeast(1)
                else -> session.initialVisualPageCount ?: initialPage.plus(1).coerceAtLeast(1)
            },
        )
    }
    var pageCountMeasured by remember(
        session,
        readerSettings.readingMode,
        readerSettings.novelFontSizeSp,
        readerSettings.novelLineHeightMultiplier,
        readerSettings.novelMaxWidthDp,
    ) {
        mutableStateOf(imageReader)
    }
    var requestedPage by remember(session) { mutableStateOf(initialPage) }
    var pageRequestSerial by remember(session) { mutableStateOf(0L) }
    var readerNavigationAction by remember(session) { mutableStateOf<ReaderTapAction?>(null) }
    var readerNavigationRequestKey by remember(session) { mutableStateOf(0L) }
    val focusRequester = remember { FocusRequester() }
    val heldNavigationKeys = remember(session) { mutableSetOf<Key>() }
    val latestProgress = remember(session) {
        LatestExtensionReaderProgress().apply {
            position = ReaderProgressPosition(
                session.content.initialLocator,
                initialPage.coerceAtLeast(0),
                session.initialVisualPageCount,
            )
        }
    }
    val currentProgressCallback by rememberUpdatedState(onReaderProgress)

    fun commitLatestProgress() {
        latestProgress.position?.let(currentProgressCallback)
    }

    fun closeReader() {
        if (progressTransitionInFlight) return
        progressTransitionInFlight = true
        commitLatestProgress()
        // Keep the surface mounted until the app-owned local progress lane confirms that the
        // final locator/page pair is durable. This also covers fast Escape/back close sequences.
        scope.launch {
            try {
                onReaderProgressFlushed()
                onBack()
            } finally {
                progressTransitionInFlight = false
            }
        }
    }

    fun openUnitAfterProgressFlush(selection: ExtensionUnitSelectionV2) {
        if (progressTransitionInFlight || busy) return
        progressTransitionInFlight = true
        commitLatestProgress()
        scope.launch {
            try {
                onReaderProgressFlushed()
                onOpenUnit(selection)
            } finally {
                progressTransitionInFlight = false
            }
        }
    }

    fun handleReaderBack() {
        when {
            chapterListVisible -> chapterListVisible = false
            settingsVisible -> settingsVisible = false
            else -> closeReader()
        }
    }

    fun requestPage(index: Int) {
        when {
            index !in 0 until pageCount &&
                !readerMayCrossChapterBoundary(
                    proseReader = textContent,
                    pageCountMeasured = pageCountMeasured,
                    interactionBlocked = busy || progressTransitionInFlight,
                ) -> Unit
            index < 0 && hasCurrent && activeUnitIndex > 0 && !busy -> {
                openUnitAfterProgressFlush(units[activeUnitIndex - 1])
            }
            index >= pageCount && hasCurrent && activeUnitIndex + 1 < units.size && !busy -> {
                openUnitAfterProgressFlush(units[activeUnitIndex + 1])
            }
            index in 0 until pageCount -> {
                currentPage = index
                requestedPage = index
                pageRequestSerial++
            }
        }
    }

    fun handleTap(action: ReaderTapAction) {
        if (progressTransitionInFlight || busy) return
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> requestPage(currentPage - 1)
            ReaderTapAction.TOGGLE_CHROME -> controlsVisible = !controlsVisible
            ReaderTapAction.NEXT_PAGE -> requestPage(currentPage + 1)
        }
    }

    fun handleNavigationBoundary(action: ReaderTapAction) {
        if (progressTransitionInFlight || busy) return
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> {
                if (hasCurrent && activeUnitIndex > 0) {
                    openUnitAfterProgressFlush(units[activeUnitIndex - 1])
                }
            }
            ReaderTapAction.NEXT_PAGE -> {
                if (hasCurrent && activeUnitIndex + 1 < units.size) {
                    openUnitAfterProgressFlush(units[activeUnitIndex + 1])
                }
            }
            ReaderTapAction.TOGGLE_CHROME -> controlsVisible = !controlsVisible
        }
    }

    fun handleVolumeKey(event: ReaderVolumeKeyEvent): Boolean {
        val action = readerVolumeKeyAction(
            event = event,
            readerOpen = true,
            volumeKeysEnabled = effectiveReaderVolumeKeysEnabled(readerSettings.volumeKeys),
        ) ?: return false
        if (progressTransitionInFlight || busy) return false
        if (textContent) {
            readerNavigationAction = action
            readerNavigationRequestKey++
        } else {
            val beforePage = currentPage
            val beforeTransition = progressTransitionInFlight
            handleTap(action)
            if (beforePage == currentPage && beforeTransition == progressTransitionInFlight) {
                val hasAdjacent = if (action == ReaderTapAction.NEXT_PAGE) {
                    hasCurrent && activeUnitIndex + 1 < units.size
                } else {
                    hasCurrent && activeUnitIndex > 0
                }
                if (!hasAdjacent) return false
            }
        }
        return true
    }

    val volumeKeyHandlerSlot = remember(session) { ReaderVolumeKeyHandlerSlot() }
    volumeKeyHandlerSlot.update(::handleVolumeKey)
    DisposableEffect(volumeKeyRouter, volumeKeyHandlerSlot) {
        val registration = volumeKeyRouter?.register(volumeKeyHandlerSlot::dispatch)
        onDispose { registration?.unregister() }
    }

    LaunchedEffect(session, settingsVisible) {
        heldNavigationKeys.clear()
        if (!settingsVisible && !epubReader) focusRequester.requestFocus()
    }

    LaunchedEffect(systemBackRequest) {
        if (systemBackRequest == 0L || systemBackRequest == handledSystemBackRequest) {
            return@LaunchedEffect
        }
        handledSystemBackRequest = systemBackRequest
        handleReaderBack()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF211D1B))
            // Android is edge-to-edge; SwiftUI already supplies the iOS safe-area frame.
            .readerStatusBarsPadding()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) {
                    heldNavigationKeys.remove(event.key)
                    return@onPreviewKeyEvent false
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (epubReader && event.key != Key.Escape && event.key != Key.Back &&
                    event.key != Key.NavigatePrevious
                ) {
                    return@onPreviewKeyEvent false
                }
                val handledNavigationKey = when (event.key) {
                    Key.DirectionDown, Key.NumPadDirectionDown,
                    Key.PageDown, Key.NumPadPageDown,
                    Key.Spacebar,
                    Key.DirectionUp, Key.NumPadDirectionUp,
                    Key.PageUp, Key.NumPadPageUp,
                    -> true
                    Key.DirectionRight, Key.NumPadDirectionRight,
                    Key.DirectionLeft, Key.NumPadDirectionLeft,
                    -> !isNovelContinuousMode(readerSettings.readingMode)
                    Key.Escape, Key.Back, Key.NavigatePrevious,
                    -> true
                    else -> false
                }
                if (handledNavigationKey && !heldNavigationKeys.add(event.key)) {
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.DirectionDown, Key.NumPadDirectionDown,
                    Key.PageDown, Key.NumPadPageDown,
                    Key.Spacebar,
                    -> {
                        handleTap(ReaderTapAction.NEXT_PAGE)
                        true
                    }
                    Key.DirectionUp, Key.NumPadDirectionUp,
                    Key.PageUp, Key.NumPadPageUp,
                    -> {
                        handleTap(ReaderTapAction.PREVIOUS_PAGE)
                        true
                    }
                    Key.DirectionRight, Key.NumPadDirectionRight -> {
                        if (isNovelContinuousMode(readerSettings.readingMode)) return@onPreviewKeyEvent false
                        handleTap(
                            if (readerSettings.readingMode == dev.shinsou.kmp.domain.model.ReadingMode.PAGER_RTL) {
                                ReaderTapAction.PREVIOUS_PAGE
                            } else {
                                ReaderTapAction.NEXT_PAGE
                            },
                        )
                        true
                    }
                    Key.DirectionLeft, Key.NumPadDirectionLeft -> {
                        if (isNovelContinuousMode(readerSettings.readingMode)) return@onPreviewKeyEvent false
                        handleTap(
                            if (readerSettings.readingMode == dev.shinsou.kmp.domain.model.ReadingMode.PAGER_RTL) {
                                ReaderTapAction.NEXT_PAGE
                            } else {
                                ReaderTapAction.PREVIOUS_PAGE
                            },
                        )
                        true
                    }
                    Key.Escape, Key.Back, Key.NavigatePrevious -> {
                        handleReaderBack()
                        true
                    }
                    else -> false
                }
            },
    ) {
        UnifiedContentReader(
            session = session,
            features = features,
            copyText = copyText,
            settings = readerSettings,
            requestedPageIndex = requestedPage,
            pageRequestSerial = pageRequestSerial,
            navigationAction = readerNavigationAction,
            navigationRequestKey = readerNavigationRequestKey,
            readerControlsVisible = controlsVisible,
            onPageIndexChanged = { index, count ->
                pageCountMeasured = true
                currentPage = index
                requestedPage = index
                pageCount = count.coerceAtLeast(1)
                latestProgress.position = latestProgress.position?.copy(
                    pageIndex = index.coerceAtLeast(0),
                    pageCount = count.coerceAtLeast(1),
                )
            },
            onPageCountInvalidated = { pageCountMeasured = false },
            onNavigationBoundary = ::handleNavigationBoundary,
            onLocatorChanged = { locator, pageIndex, documentPageCount ->
                val position = ReaderProgressPosition(
                    locator,
                    pageIndex.coerceAtLeast(0),
                    documentPageCount,
                )
                latestProgress.position = position
                onReaderProgress(position)
                val navigation = session.content.navigation
                if (navigation.indexOf(locator) == navigation.itemCount - 1 &&
                    (locator.progression ?: 0.0) >= 0.995
                ) {
                    onCompleted()
                }
            },
            onReaderTap = ::handleTap,
            modifier = Modifier.fillMaxSize(),
        )

        if (controlsVisible) {
            Surface(
                color = Color.Black.copy(alpha = 0.88f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::closeReader) {
                        Icon(Icons.Outlined.ArrowBack, strings.text("Close reader"))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            currentUnitTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { chapterListVisible = true }, enabled = units.isNotEmpty()) {
                        Icon(Icons.Outlined.MenuBook, strings.chapters)
                    }
                    IconButton(onClick = { settingsVisible = true }) {
                        Icon(Icons.Outlined.Settings, strings.settings)
                    }
                }
            }
            // Keep plain-text progress controls as an overlay. EPUB retains its own semantic
            // search/speech/note overlay inside UnifiedContentReader.
            if (!epubReader) {
                NovelReaderProgressControls(
                    page = currentPage,
                    pageCount = pageCount,
                    hasPreviousChapter = hasCurrent && activeUnitIndex > 0,
                    hasNextChapter = hasCurrent && activeUnitIndex + 1 < units.size,
                    onPageChange = ::requestPage,
                    onPreviousChapter = {
                        units.getOrNull(activeUnitIndex - 1)?.let(::openUnitAfterProgressFlush)
                    },
                    onNextChapter = {
                        units.getOrNull(activeUnitIndex + 1)?.let(::openUnitAfterProgressFlush)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    interactionEnabled = !busy && !progressTransitionInFlight,
                )
            }
        }

        if (progressTransitionInFlight || busy) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.46f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 8.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Text(strings.text("Loading"), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    if (settingsVisible) {
        ReaderSettingsSheet(
            settings = readerSettings,
            textContent = textContent,
            onChange = onReaderSettingsChange,
            onDismiss = { settingsVisible = false },
        )
    }
    if (chapterListVisible) {
        ModalBottomSheet(onDismissRequest = { chapterListVisible = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    strings.chapters,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(units, key = { it.unit.remoteId }) { selection ->
                        val selected = selection.unit.remoteId ==
                            units.getOrNull(activeUnitIndex)?.unit?.remoteId
                        TextButton(
                            onClick = {
                                chapterListVisible = false
                                if (!selected) openUnitAfterProgressFlush(selection)
                            },
                            enabled = !progressTransitionInFlight && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                selection.unit.title,
                                modifier = Modifier.fillMaxWidth(),
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Exact-keyed native extension path; host-issued selections remain the only content authority. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionV2PublicationPane(
    callbacks: BrowseCallbacks,
    item: BrowseManga,
    supportsFavorite: Boolean,
    favoriteDestination: ExtensionFavoriteDestination,
    localLibrary: Boolean,
    localLibraryFavorite: Boolean,
    onToggleLocalLibrary: suspend (BrowseManga, RemotePublicationV2, Boolean) -> Unit,
    refreshGeneration: Long,
    contentFeatures: ContentFeatureRuntime?,
    copyText: (label: String, text: String) -> Boolean,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onShareText: (String, String) -> Unit,
    onReaderVisibilityChanged: (Boolean) -> Unit,
    onReaderProgress: (
        title: String,
        unitTitle: String,
        locator: ReadingLocator,
        pageIndex: Int,
        pageCount: Int?,
    ) -> Unit,
    onReaderProgressFlushed: suspend () -> Unit,
    volumeKeyRouter: ReaderVolumeKeyRouter? = null,
    readerBackRequest: Long = 0L,
    onBack: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    val sourceKey = requireNotNull(item.sourceKey)
    val remotePublicationId = requireNotNull(item.remotePublicationId)
    var publicationPage by remember(item.identityKey) {
        mutableStateOf<ExtensionPublicationPageV2?>(null)
    }
    var refreshedPublication by remember(item.identityKey) {
        mutableStateOf<RemotePublicationV2?>(null)
    }
    var units by remember(item.identityKey) {
        mutableStateOf<List<ExtensionUnitSelectionV2>>(emptyList())
    }
    var nextPage by remember(item.identityKey) { mutableStateOf(0) }
    var hasNextPage by remember(item.identityKey) { mutableStateOf(false) }
    var unitDirectoryComplete by remember(item.identityKey) { mutableStateOf(false) }
    var loading by remember(item.identityKey) { mutableStateOf(true) }
    var loadingMore by remember(item.identityKey) { mutableStateOf(false) }
    var loadError by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var busyUnitId by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var openingReaderUnitId by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var operationMessage by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var operationFailed by remember(item.identityKey) { mutableStateOf(false) }
    var readerSession by remember(item.identityKey) {
        mutableStateOf<dev.shinsou.kmp.ui.TypedReaderContentSession?>(null)
    }
    var readerUnitId by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var pendingRepresentation by remember(item.identityKey) {
        mutableStateOf<PendingExtensionRepresentationSelection?>(null)
    }
    var favorite by remember(item.identityKey, localLibraryFavorite) {
        mutableStateOf(localLibrary && localLibraryFavorite)
    }
    var notes by remember(item.identityKey) { mutableStateOf("") }
    var selectedUnitIds by remember(item.identityKey) { mutableStateOf<Set<String>>(emptySet()) }
    var readUnitIds by remember(item.identityKey) { mutableStateOf<Set<String>>(emptySet()) }
    var resumeUnitId by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var bookmarkedUnitIds by remember(item.identityKey) { mutableStateOf<Set<String>>(emptySet()) }
    var downloadedUnitIds by remember(item.identityKey) { mutableStateOf<Set<String>>(emptySet()) }
    var chapterFilter by remember(item.identityKey) { mutableStateOf(ExtensionChapterFilter.ALL) }
    var reverseWebsiteOrder by remember(item.identityKey) { mutableStateOf(false) }

    // Catalogue metadata is already available when the user taps a result. Use it as the
    // immediate detail hint so chapters do not wait for a second metadata roundtrip.
    val cataloguePublication = remember(item.identityKey) {
        RemotePublicationV2(
            remoteId = remotePublicationId,
            title = item.title,
            url = item.url.takeIf { it.startsWith("http://") || it.startsWith("https://") },
            thumbnailUrl = item.thumbnailUrl,
            author = item.author,
            artist = item.artist,
            description = item.description,
            genre = item.genre,
            status = item.status,
        )
    }

    fun materialize(
        selection: ExtensionUnitSelectionV2,
        representationId: String? = null,
        openReader: Boolean = true,
    ) {
        if (busyUnitId != null) return
        busyUnitId = selection.unit.remoteId
        if (openReader) openingReaderUnitId = selection.unit.remoteId
        operationMessage = null
        operationFailed = false
        scope.launch {
            withFrameNanos { }
            try {
                val opened = withContext(Dispatchers.Default) {
                    val materialization = callbacks.materializeExtensionContentV2(selection, representationId)
                    if (openReader && contentFeatures != null) {
                        callbacks.openMaterializedExtensionContentV2(materialization)
                    } else {
                        null
                    }
                }
                if (opened != null) {
                    readerSession = opened
                    readerUnitId = selection.unit.remoteId
                    // Keep the parent layout in sync with the session creation frame. The
                    // lifecycle effect below remains a fallback, but relying on it alone lets
                    // desktop briefly (and sometimes permanently) retain the split catalogue
                    // pane while the novel reader is already visible.
                    onReaderVisibilityChanged(true)
                } else {
                    downloadedUnitIds = downloadedUnitIds + selection.unit.remoteId
                    operationMessage = strings.text("Content saved for offline reading.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (required: ExtensionContentConsumerException.RepresentationSelectionRequired) {
                pendingRepresentation = PendingExtensionRepresentationSelection(
                    selection = selection,
                    representationIds = required.availableIds,
                    openReader = openReader,
                )
            } catch (error: Throwable) {
                operationFailed = true
                operationMessage = error.localizedDiagnosticMessage(strings)
            } finally {
                busyUnitId = null
                if (openReader) openingReaderUnitId = null
            }
        }
    }

    val openedReader = readerSession
    val activeUnitIndex = readerUnitId?.let { id -> units.indexOfFirst { it.unit.remoteId == id } } ?: -1
    // Visibility is opened explicitly when materialization returns and closed explicitly by
    // the reader/back handlers below. Do not emit a transient `false` from an effect keyed only
    // by a nullable session: during the first materialization recomposition that stale callback
    // can race the `true` notification and put desktop back into the split catalogue layout.
    LaunchedEffect(openedReader) {
        if (openedReader != null) onReaderVisibilityChanged(true)
    }
    if (openedReader != null && contentFeatures != null) {
        val openedReaderScope = openedReader.content.navigation.scope
        val openedReaderTitle = publicationPage?.publication?.title ?: item.title
        val openedReaderUnitTitle = units
            .firstOrNull { it.unit.remoteId == readerUnitId }
            ?.unit
            ?.title
            ?: item.title
        ExtensionNovelReaderShell(
            title = openedReaderTitle,
            currentUnitTitle = openedReaderUnitTitle,
            units = units,
            activeUnitIndex = activeUnitIndex,
            busy = busyUnitId != null,
            onBack = {
                readerSession = null
                readerUnitId = null
                onReaderVisibilityChanged(false)
            },
            onOpenUnit = { selection -> materialize(selection, openReader = true) },
            session = openedReader,
            features = contentFeatures,
            copyText = copyText,
            readerSettings = readerSettings,
            onReaderSettingsChange = onReaderSettingsChange,
            onCompleted = {
                if (readerSession === openedReader) {
                    readerUnitId?.let { unitId -> readUnitIds = readUnitIds + unitId }
                }
            },
            onReaderProgress = { position ->
                if (
                    readerSession === openedReader &&
                    position.locator.scope == openedReaderScope
                ) {
                    onReaderProgress(
                        openedReaderTitle,
                        openedReaderUnitTitle,
                        position.locator,
                        position.pageIndex,
                        position.pageCount,
                    )
                }
            },
            onReaderProgressFlushed = onReaderProgressFlushed,
            volumeKeyRouter = volumeKeyRouter,
            systemBackRequest = readerBackRequest,
        )
        return
    }

    suspend fun loadUnitPage(requestedPage: Int, append: Boolean) {
        val loaded = withContext(Dispatchers.Default) {
            callbacks.extensionPublicationUnitsPageV2(
                sourceKey = sourceKey,
                remotePublicationId = remotePublicationId,
                publication = publicationPage?.publication ?: cataloguePublication,
                page = requestedPage,
            )
        }
        check(loaded.sourceKey == sourceKey) { "Extension publication source identity changed" }
        check(loaded.publication.remoteId == remotePublicationId) {
            "Extension publication identity changed"
        }
        check(loaded.page == requestedPage) { "Extension unit page identity changed" }
        publicationPage = if (append) publicationPage ?: loaded else loaded
        units = if (append) {
            (units + loaded.units).distinctBy { it.unit.remoteId }
        } else {
            loaded.units
        }
        nextPage = requestedPage + 1
        hasNextPage = loaded.hasNextPage
    }

    suspend fun loadInitialUnitDirectory() {
        loadUnitPage(requestedPage = 0, append = false)
        // A local-library title must be self-contained when opened directly. Reviewed ShuYue
        // sources paginate long catalogues (100 units per page); leaving later pages behind a
        // manual button makes the saved resume unit and adjacent chapter navigation disappear.
        if (localLibrary) {
            while (hasNextPage) {
                currentCoroutineContext().ensureActive()
                val page = nextPage
                check(page > 0) { "Extension unit pagination did not advance" }
                loadUnitPage(requestedPage = page, append = true)
            }
        }
    }

    fun downloadUnits(selections: List<ExtensionUnitSelectionV2>) {
        val pending = selections.distinctBy { it.unit.remoteId }
        if (pending.isEmpty() || busyUnitId != null) return
        operationMessage = null
        operationFailed = false
        scope.launch {
            withFrameNanos { }
            try {
                pending.forEach { selection ->
                    busyUnitId = selection.unit.remoteId
                    withContext(Dispatchers.Default) {
                        callbacks.materializeExtensionContentV2(selection)
                    }
                    downloadedUnitIds = downloadedUnitIds + selection.unit.remoteId
                }
                operationMessage = strings.text("{0} chapters saved for offline reading.", pending.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (required: ExtensionContentConsumerException.RepresentationSelectionRequired) {
                pendingRepresentation = PendingExtensionRepresentationSelection(
                    selection = pending.firstOrNull { it.unit.remoteId == busyUnitId } ?: pending.first(),
                    representationIds = required.availableIds,
                    openReader = false,
                )
            } catch (error: Throwable) {
                operationFailed = true
                operationMessage = error.localizedDiagnosticMessage(strings)
            } finally {
                busyUnitId = null
            }
        }
    }

    LaunchedEffect(item.identityKey, refreshGeneration) {
        loading = true
        unitDirectoryComplete = false
        loadError = null
        refreshedPublication = null
        // Keep the units request on the critical path. Wenku8 fetches the long introduction in
        // the full details call, so refresh metadata only after the first chapter page is ready.
        val detailsRefresh = launch(start = CoroutineStart.LAZY) {
            try {
                refreshedPublication = withContext(Dispatchers.Default) {
                    callbacks.extensionDetailsV2(sourceKey, remotePublicationId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Catalogue metadata remains a valid fallback if the background refresh fails.
            }
        }
        try {
            val localReading = callbacks.extensionLocalReadingStateV2(
                extensionPublicationKey(sourceKey, remotePublicationId),
            )
            readUnitIds = localReading.completedRemoteUnitIds
            resumeUnitId = localReading.lastReadRemoteUnitId
            loadInitialUnitDirectory()
            unitDirectoryComplete = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            loadError = error.localizedDiagnosticMessage(strings)
        } finally {
            loading = false
            detailsRefresh.start()
        }
        detailsRefresh.join()
    }

    val publication = refreshedPublication ?: publicationPage?.publication
    val displayPublication = publication ?: cataloguePublication
    val targetUrl = (displayPublication.url ?: item.url).takeIf {
        it.startsWith("http://") || it.startsWith("https://")
    }

    fun retryInitialPage() {
        scope.launch {
            loading = true
            loadError = null
            publicationPage = null
            refreshedPublication = null
            units = emptyList()
            nextPage = 0
            hasNextPage = false
            unitDirectoryComplete = false
            try {
                loadInitialUnitDirectory()
                unitDirectoryComplete = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                loadError = error.localizedDiagnosticMessage(strings)
            } finally {
                loading = false
            }
        }
    }

    fun refreshPublication() {
        if (loading || loadingMore || busyUnitId != null) return
        retryInitialPage()
    }

    fun loadMore() {
        if (loadingMore) return
        scope.launch {
            loadingMore = true
            loadError = null
            try {
                loadUnitPage(requestedPage = nextPage, append = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                loadError = error.localizedDiagnosticMessage(strings)
            } finally {
                loadingMore = false
            }
        }
    }

    val continueUnit = remember(localLibrary, unitDirectoryComplete, units, readUnitIds, resumeUnitId) {
        if (localLibrary && !unitDirectoryComplete) return@remember null
        extensionContinueUnitId(
            unitIds = units.map { it.unit.remoteId },
            completedUnitIds = readUnitIds,
            resumeUnitId = resumeUnitId,
        )?.let { target -> units.firstOrNull { it.unit.remoteId == target } }
    }
    val continueLabel = when {
        continueUnit == null -> null
        resumeUnitId != null && continueUnit.unit.remoteId == resumeUnitId -> strings.continueReading
        continueUnit.unit.remoteId in readUnitIds -> strings.text("Read again")
        readUnitIds.isNotEmpty() -> strings.continueReading
        else -> strings.text("Start reading")
    }

    fun toggleFavorite() {
        val next = !favorite
        operationMessage = null
        operationFailed = false
        scope.launch {
            withFrameNanos { }
            try {
                withContext(Dispatchers.Default) {
                    mutateExtensionFavorite(
                        destination = favoriteDestination,
                        localMutation = {
                            onToggleLocalLibrary(
                                item,
                                refreshedPublication ?: publicationPage?.publication ?: cataloguePublication,
                                next,
                            )
                        },
                        sourceMutation = {
                            callbacks.favoriteExtensionPublicationV2(sourceKey, remotePublicationId, next)
                        },
                    )
                }
                favorite = next
                operationMessage = if (next) {
                    if (localLibrary) strings.text("Added to app library.")
                    else strings.text("Added to source favorites.")
                } else {
                    if (localLibrary) strings.text("Removed from app library.")
                    else strings.text("Removed from source favorites.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                operationFailed = true
                operationMessage = error.localizedDiagnosticMessage(strings)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wideLayout = maxWidth >= 900.dp
            if (wideLayout) {
                Row(Modifier.fillMaxSize()) {
                    ExtensionV2PublicationInfoPane(
                        publication = displayPublication,
                        supportsFavorite = supportsFavorite,
                        favorite = favorite,
                        allowUnfavorite = localLibrary,
                        notes = notes,
                        operationMessage = operationMessage,
                        operationFailed = operationFailed,
                        refreshingFromSource = loading,
                        continueReadingLoading = loading ||
                            openingReaderUnitId == continueUnit?.unit?.remoteId,
                        showClose = true,
                        hasChapters = units.isNotEmpty() || hasNextPage,
                        continueLabel = continueLabel,
                        onToggleFavorite = ::toggleFavorite,
                        onRefresh = ::refreshPublication,
                        onShare = {
                            onShareText(displayPublication.title, targetUrl ?: displayPublication.title)
                        },
                        onOpenWeb = {
                            targetUrl?.let(onOpenExternalUrl)
                                ?: run { operationMessage = strings.text("This title has no original URL.") }
                        },
                        onOpenTracking = {
                            targetUrl?.let(onOpenExternalUrl)
                                ?: run { operationMessage = strings.text("This title has no tracking URL.") }
                        },
                        onUpdateNotes = { notes = it },
                        onDownloadAll = { downloadUnits(units) },
                        onContinueReading = continueUnit?.let { { materialize(it, openReader = true) } },
                        onBack = onBack,
                        scrollable = true,
                        modifier = Modifier.width(330.dp).fillMaxHeight(),
                    )
                    VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                    ExtensionV2ChapterPane(
                        units = units,
                        selectedUnitIds = selectedUnitIds,
                        readUnitIds = readUnitIds,
                        bookmarkedUnitIds = bookmarkedUnitIds,
                        downloadedUnitIds = downloadedUnitIds,
                        chapterFilter = chapterFilter,
                        reverseWebsiteOrder = reverseWebsiteOrder,
                        loading = loading,
                        loadingMore = loadingMore,
                        loadError = loadError,
                        hasNextPage = hasNextPage,
                        operationMessage = operationMessage,
                        operationFailed = operationFailed,
                        busyUnitId = busyUnitId,
                        openingReaderUnitId = openingReaderUnitId,
                        onRetry = ::retryInitialPage,
                        onLoadMore = ::loadMore,
                        onReadUnit = { materialize(it, openReader = true) },
                        onDownloadUnits = { downloadUnits(it) },
                        onSelectionChange = { selectedUnitIds = it },
                        onReadStateChange = { ids, read ->
                            readUnitIds = if (read) readUnitIds + ids else readUnitIds - ids
                        },
                        onBookmarkStateChange = { ids, bookmarked ->
                            bookmarkedUnitIds = if (bookmarked) bookmarkedUnitIds + ids else bookmarkedUnitIds - ids
                        },
                        onDeleteDownloads = { ids ->
                            downloadedUnitIds = downloadedUnitIds - ids
                            operationMessage = strings.text("Offline markers cleared for {0} chapters.", ids.size)
                        },
                        onFilterChange = { chapterFilter = it },
                        onToggleWebsiteOrder = { reverseWebsiteOrder = !reverseWebsiteOrder },
                        scrollable = true,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    item("extension-info") {
                        ExtensionV2PublicationInfoPane(
                            publication = displayPublication,
                            supportsFavorite = supportsFavorite,
                            favorite = favorite,
                            allowUnfavorite = localLibrary,
                            notes = notes,
                            operationMessage = operationMessage,
                            operationFailed = operationFailed,
                            refreshingFromSource = loading,
                            continueReadingLoading = loading ||
                                openingReaderUnitId == continueUnit?.unit?.remoteId,
                            showClose = false,
                            hasChapters = units.isNotEmpty() || hasNextPage,
                            continueLabel = continueLabel,
                            onToggleFavorite = ::toggleFavorite,
                            onRefresh = ::refreshPublication,
                            onShare = {
                                onShareText(displayPublication.title, targetUrl ?: displayPublication.title)
                            },
                            onOpenWeb = {
                                targetUrl?.let(onOpenExternalUrl)
                                    ?: run { operationMessage = strings.text("This title has no original URL.") }
                            },
                            onOpenTracking = {
                                targetUrl?.let(onOpenExternalUrl)
                                    ?: run { operationMessage = strings.text("This title has no tracking URL.") }
                            },
                            onUpdateNotes = { notes = it },
                            onDownloadAll = { downloadUnits(units) },
                            onContinueReading = continueUnit?.let { { materialize(it, openReader = true) } },
                            onBack = onBack,
                            scrollable = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item("extension-chapters") {
                        ExtensionV2ChapterPane(
                            units = units,
                            selectedUnitIds = selectedUnitIds,
                            readUnitIds = readUnitIds,
                            bookmarkedUnitIds = bookmarkedUnitIds,
                            downloadedUnitIds = downloadedUnitIds,
                            chapterFilter = chapterFilter,
                            reverseWebsiteOrder = reverseWebsiteOrder,
                            loading = loading,
                            loadingMore = loadingMore,
                            loadError = loadError,
                            hasNextPage = hasNextPage,
                            operationMessage = operationMessage,
                            operationFailed = operationFailed,
                            busyUnitId = busyUnitId,
                            openingReaderUnitId = openingReaderUnitId,
                            onRetry = ::retryInitialPage,
                            onLoadMore = ::loadMore,
                            onReadUnit = { materialize(it, openReader = true) },
                            onDownloadUnits = { downloadUnits(it) },
                            onSelectionChange = { selectedUnitIds = it },
                            onReadStateChange = { ids, read ->
                                readUnitIds = if (read) readUnitIds + ids else readUnitIds - ids
                            },
                            onBookmarkStateChange = { ids, bookmarked ->
                                bookmarkedUnitIds = if (bookmarked) bookmarkedUnitIds + ids else bookmarkedUnitIds - ids
                            },
                            onDeleteDownloads = { ids ->
                                downloadedUnitIds = downloadedUnitIds - ids
                                operationMessage = strings.text("Offline markers cleared for {0} chapters.", ids.size)
                            },
                            onFilterChange = { chapterFilter = it },
                            onToggleWebsiteOrder = { reverseWebsiteOrder = !reverseWebsiteOrder },
                            scrollable = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    pendingRepresentation?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingRepresentation = null },
            title = { Text(strings.text("Choose content format")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pending.representationIds.forEach { representationId ->
                        OutlinedButton(
                            onClick = {
                                pendingRepresentation = null
                                materialize(pending.selection, representationId, pending.openReader)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(strings.text("Format: {0}", representationId))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingRepresentation = null }) {
                    Text(strings.cancel)
                }
            },
        )
    }
}

/** Native publication metadata pane deliberately mirrors MangaInfoPane's layout and actions. */
@Composable
private fun ExtensionV2PublicationInfoPane(
    publication: RemotePublicationV2,
    supportsFavorite: Boolean,
    favorite: Boolean,
    allowUnfavorite: Boolean,
    notes: String,
    operationMessage: String?,
    operationFailed: Boolean,
    refreshingFromSource: Boolean,
    continueReadingLoading: Boolean,
    showClose: Boolean,
    hasChapters: Boolean,
    continueLabel: String?,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onOpenWeb: () -> Unit,
    onOpenTracking: () -> Unit,
    onUpdateNotes: (String) -> Unit,
    onDownloadAll: () -> Unit,
    onContinueReading: (() -> Unit)?,
    scrollable: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    var editingNotes by remember(publication.remoteId) { mutableStateOf(false) }
    var notesDraft by remember(publication.remoteId, notes) { mutableStateOf(notes) }
    val contentModifier = modifier
        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp)
        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
    Column(contentModifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    if (showClose) Icons.Outlined.Close else Icons.Outlined.ArrowBack,
                    if (showClose) strings.close else strings.text("Back"),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !refreshingFromSource) {
                if (refreshingFromSource) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, strings.refresh)
                }
            }
            IconButton(onClick = onOpenWeb) {
                Icon(Icons.Outlined.OpenInBrowser, strings.text("Open original page"))
            }
            IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, strings.share) }
        }
        CoverImage(
            title = publication.title,
            url = publication.thumbnailUrl,
            modifier = Modifier.width(186.dp).aspectRatio(2f / 3f),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            publication.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            publication.author?.takeIf(String::isNotBlank)
                ?: publication.artist?.takeIf(String::isNotBlank)
                ?: strings.text("Unknown author"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        publication.artist?.takeIf { it.isNotBlank() && it != publication.author }?.let { artist ->
            Text(
                strings.text("Artist: {0}", artist),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (supportsFavorite) {
                Button(
                    onClick = onToggleFavorite,
                    enabled = !refreshingFromSource && (!favorite || allowUnfavorite),
                ) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(if (favorite) strings.unfavorite else strings.favorite)
                }
            }
            OutlinedButton(onClick = onOpenTracking) {
                Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(strings.text("Track"))
            }
        }
        operationMessage?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (operationFailed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (hasChapters) {
            Spacer(Modifier.height(9.dp))
            OutlinedButton(onClick = onDownloadAll, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(strings.text("Download all chapters"))
            }
        }
        if (continueLabel != null && onContinueReading != null) {
            Spacer(Modifier.height(9.dp))
            OutlinedButton(
                onClick = onContinueReading,
                enabled = !continueReadingLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (continueReadingLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(7.dp))
                Text(if (continueReadingLoading) strings.text("Loading") else continueLabel)
            }
        }
        Spacer(Modifier.height(18.dp))
        publication.status?.takeIf(String::isNotBlank)?.let { status ->
            AssistChip(onClick = {}, label = { Text(status) })
            Spacer(Modifier.height(10.dp))
        }
        publication.genre?.takeIf { it.isNotEmpty() }?.let { genres ->
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(genres) { genre -> AssistChip(onClick = {}, label = { Text(genre) }) }
            }
            Spacer(Modifier.height(14.dp))
        }
        Text(
            publication.description?.takeIf(String::isNotBlank)
                ?: strings.text("No description available."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.text("Notes"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            notesDraft = notes
                            editingNotes = true
                        },
                    ) { Icon(Icons.Outlined.Edit, strings.text("Edit notes")) }
                }
                Text(
                    notes.ifBlank { strings.text("Add a private note for this title.") },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (notes.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

        }
    }
    if (editingNotes) {
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { editingNotes = false },
            title = { Text(strings.text("Novel notes")) },
            text = {
                OutlinedTextField(
                    value = notesDraft,
                    onValueChange = { notesDraft = it },
                    label = { Text(strings.text("Notes")) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateNotes(notesDraft.trim())
                        editingNotes = false
                    },
                ) { Text(strings.save) }
            },
            dismissButton = { TextButton(onClick = { editingNotes = false }) { Text(strings.cancel) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExtensionV2ChapterPane(
    units: List<ExtensionUnitSelectionV2>,
    selectedUnitIds: Set<String>,
    readUnitIds: Set<String>,
    bookmarkedUnitIds: Set<String>,
    downloadedUnitIds: Set<String>,
    chapterFilter: ExtensionChapterFilter,
    reverseWebsiteOrder: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    loadError: String?,
    hasNextPage: Boolean,
    operationMessage: String?,
    operationFailed: Boolean,
    busyUnitId: String?,
    openingReaderUnitId: String?,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onReadUnit: (ExtensionUnitSelectionV2) -> Unit,
    onDownloadUnits: (List<ExtensionUnitSelectionV2>) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onReadStateChange: (Set<String>, Boolean) -> Unit,
    onBookmarkStateChange: (Set<String>, Boolean) -> Unit,
    onDeleteDownloads: (Set<String>) -> Unit,
    onFilterChange: (ExtensionChapterFilter) -> Unit,
    onToggleWebsiteOrder: () -> Unit,
    scrollable: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    var sortMenuVisible by remember { mutableStateOf(false) }
    val visibleUnits = remember(
        units,
        readUnitIds,
        bookmarkedUnitIds,
        downloadedUnitIds,
        chapterFilter,
        reverseWebsiteOrder,
    ) {
        val filtered = units.asSequence()
            .filter { selection ->
                when (chapterFilter) {
                    ExtensionChapterFilter.ALL -> true
                    ExtensionChapterFilter.UNREAD -> selection.unit.remoteId !in readUnitIds
                    ExtensionChapterFilter.READ -> selection.unit.remoteId in readUnitIds
                    ExtensionChapterFilter.BOOKMARKED -> selection.unit.remoteId in bookmarkedUnitIds
                    ExtensionChapterFilter.DOWNLOADED -> selection.unit.remoteId in downloadedUnitIds
                }
            }
            .toList()
        websiteOrderedItems(filtered, reverseWebsiteOrder)
    }
    val selectedVisible = visibleUnits.filter { it.unit.remoteId in selectedUnitIds }
    val markSelectedRead = selectedVisible.isEmpty() || selectedVisible.any { it.unit.remoteId !in readUnitIds }
    val bookmarkSelected = selectedVisible.isEmpty() || selectedVisible.any { it.unit.remoteId !in bookmarkedUnitIds }
    val contentModifier = modifier
        .padding(horizontal = 14.dp, vertical = 12.dp)
        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
    Column(contentModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (selectedUnitIds.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onSelectionChange(emptySet()) }) {
                    Icon(Icons.Filled.Check, strings.done)
                }
                Text(
                    "${selectedUnitIds.size} ${strings.selected}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onSelectionChange(visibleUnits.mapTo(linkedSetOf()) { it.unit.remoteId }) }) {
                    Icon(Icons.Outlined.SelectAll, strings.text("Select all visible chapters"))
                }
                IconButton(onClick = { onReadStateChange(selectedUnitIds, markSelectedRead) }) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        if (markSelectedRead) strings.markRead else strings.markUnread,
                    )
                }
                IconButton(onClick = { onBookmarkStateChange(selectedUnitIds, bookmarkSelected) }) {
                    Icon(
                        if (bookmarkSelected) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        if (bookmarkSelected) strings.text("Bookmark") else strings.text("Remove bookmarks"),
                    )
                }
                IconButton(onClick = { onDownloadUnits(selectedVisible) }) {
                    Icon(Icons.Outlined.Download, strings.download)
                }
                IconButton(onClick = { onDeleteDownloads(selectedUnitIds) }) {
                    Icon(Icons.Outlined.Delete, strings.delete, tint = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(strings.chapters, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (hasNextPage) strings.text("{0} loaded", units.size) else strings.text("{0} total", units.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { sortMenuVisible = true }) {
                        Icon(Icons.Outlined.Sort, strings.text("Sort chapters"))
                    }
                    DropdownMenu(sortMenuVisible, onDismissRequest = { sortMenuVisible = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (reverseWebsiteOrder) strings.text("Reverse website order")
                                    else strings.text("Website order"),
                                )
                            },
                            onClick = {
                                onToggleWebsiteOrder()
                                sortMenuVisible = false
                            },
                        )
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item {
                    FilterChip(
                        selected = chapterFilter == ExtensionChapterFilter.UNREAD,
                        onClick = {
                            onFilterChange(
                                if (chapterFilter == ExtensionChapterFilter.UNREAD) ExtensionChapterFilter.ALL
                                else ExtensionChapterFilter.UNREAD,
                            )
                        },
                        label = { Text(strings.text("Unread")) },
                    )
                }
                item {
                    FilterChip(
                        selected = chapterFilter == ExtensionChapterFilter.BOOKMARKED,
                        onClick = {
                            onFilterChange(
                                if (chapterFilter == ExtensionChapterFilter.BOOKMARKED) ExtensionChapterFilter.ALL
                                else ExtensionChapterFilter.BOOKMARKED,
                            )
                        },
                        label = { Text(strings.text("Bookmarked")) },
                    )
                }
                item {
                    FilterChip(
                        selected = chapterFilter == ExtensionChapterFilter.DOWNLOADED,
                        onClick = {
                            onFilterChange(
                                if (chapterFilter == ExtensionChapterFilter.DOWNLOADED) ExtensionChapterFilter.ALL
                                else ExtensionChapterFilter.DOWNLOADED,
                            )
                        },
                        label = { Text(strings.text("Downloaded")) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        operationMessage?.let { message ->
            Text(
                message,
                color = if (operationFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        when {
            loading && units.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(220.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            loadError != null && units.isEmpty() -> EmptyState(
                title = strings.text("Unable to load chapters"),
                message = loadError,
                icon = { Icon(Icons.Outlined.Extension, null, Modifier.size(30.dp)) },
                action = { Button(onClick = onRetry) { Text(strings.retry) } },
            )
            units.isEmpty() -> EmptyState(
                title = strings.text("No chapters"),
                message = strings.text("This extension did not return any readable units."),
                icon = { Icon(Icons.Outlined.Extension, null, Modifier.size(30.dp)) },
            )
            visibleUnits.isEmpty() -> EmptyState(
                title = strings.text("No matching chapters"),
                message = strings.text("Change the chapter filter to see more."),
                icon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(30.dp)) },
            )
            else -> visibleUnits.forEach { selection ->
                val unitId = selection.unit.remoteId
                val selected = unitId in selectedUnitIds
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onLongClick = { onSelectionChange(selectedUnitIds.toggleExtensionUnit(unitId)) },
                        onClick = {
                            if (selectedUnitIds.isNotEmpty()) {
                                onSelectionChange(selectedUnitIds.toggleExtensionUnit(unitId))
                            } else {
                                onReadUnit(selection)
                            }
                        },
                    ),
                ) {
                    Row(
                        Modifier.padding(start = 13.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    selection.unit.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (unitId in readUnitIds) FontWeight.Normal else FontWeight.SemiBold,
                                    color = if (unitId in readUnitIds) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (unitId in bookmarkedUnitIds) {
                                    Spacer(Modifier.width(5.dp))
                                    Icon(Icons.Filled.Favorite, strings.text("Bookmarked"), Modifier.size(15.dp))
                                }
                            }
                        }
                        if (unitId in downloadedUnitIds) {
                            Icon(Icons.Outlined.Download, strings.text("Downloaded"), Modifier.size(17.dp))
                        }
                        IconButton(
                            onClick = { onDownloadUnits(listOf(selection)) },
                            enabled = busyUnitId == null,
                        ) {
                            if (busyUnitId == unitId && openingReaderUnitId == null) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Download, strings.download)
                            }
                        }
                        IconButton(onClick = { onReadUnit(selection) }, enabled = busyUnitId == null) {
                            if (openingReaderUnitId == unitId) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.PlayArrow, strings.text("Start reading"))
                            }
                        }
                    }
                }
            }
        }
        if (hasNextPage || loadingMore || loadError != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (loadingMore) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                } else if (hasNextPage) {
                    OutlinedButton(onClick = onLoadMore) { Text(strings.text("Load more")) }
                }
            }
        }
    }
}

private fun Set<String>.toggleExtensionUnit(id: String): Set<String> =
    if (id in this) this - id else this + id

internal fun searchAcrossSources(
    callbacks: BrowseCallbacks,
    sources: List<BrowseSource>,
    query: String,
    strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings,
): Flow<SourceSearchUpdate> = channelFlow {
    val semaphore = Semaphore(GLOBAL_SEARCH_MAX_CONCURRENCY)
    sources.distinctBy(BrowseSource::identityKey).forEachIndexed { sourceIndex, source ->
        launch(Dispatchers.Default) {
            val result = semaphore.withPermit {
                try {
                    val page = browseGlobalSearchSource(callbacks, source, query)
                    currentCoroutineContext().ensureActive()
                    val ranked = rankBrowseResults(query, page.items)
                    SourceSearchResult(
                        source = source,
                        items = ranked.take(GLOBAL_SEARCH_VISIBLE_RESULTS),
                        totalCount = ranked.size,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    SourceSearchResult(
                        source = source,
                        errorMessage = error.localizedDiagnosticMessage(strings),
                    )
                }
            }
            currentCoroutineContext().ensureActive()
            send(SourceSearchUpdate(sourceIndex, result))
        }
    }
}

internal suspend fun browseGlobalSearchSource(
    callbacks: BrowseCallbacks,
    source: BrowseSource,
    query: String,
): BrowsePage {
    val sourceKey = source.sourceKey
    if (sourceKey != null) {
        val result = callbacks.searchSourceV2(sourceKey, query, page = 0)
        return BrowsePage(
            items = result.items.map { publication -> publication.toBrowseManga(source) },
            hasNextPage = result.hasNextPage,
        )
    }
    return callbacks.browseSource(
        sourceId = source.id,
        query = query,
        page = 1,
        // Global search intentionally ignores source-specific filters, matching original Shinsou.
        // The explicit empty list also avoids requiring optional hooks from sources such as MangaCopy.
        filters = emptyList(),
    )
}

/** Compatibility helper for legacy-only focused tests/callers. */
internal suspend fun browseGlobalSearchSource(
    callbacks: BrowseCallbacks,
    sourceId: Long,
    query: String,
): BrowsePage = callbacks.browseSource(
    sourceId = sourceId,
    query = query,
    page = 1,
    filters = emptyList(),
)

private fun RemotePublicationV2.toBrowseManga(source: BrowseSource): BrowseManga = BrowseManga(
    sourceId = source.id,
    url = url ?: remoteId,
    title = title,
    thumbnailUrl = thumbnailUrl,
    author = author,
    artist = artist,
    description = description,
    genre = genre,
    status = status,
    sourceKey = requireNotNull(source.sourceKey),
    remotePublicationId = remoteId,
)

@Composable
private fun SourcesPane(
    sources: List<BrowseSource>,
    onToggle: (BrowseSource, Boolean) -> Unit,
    pinnedSourceIds: Set<Long>,
    pinnedSourceKeys: Set<String>,
    onSourcePinnedChange: (sourceId: Long, pinned: Boolean) -> Unit,
    onSourcePinnedKeyChange: (sourceKey: String, pinned: Boolean) -> Unit,
    callbacks: BrowseCallbacks,
    onImportDocument: suspend (acceptedExtensions: Set<String>) -> ImportedDocument?,
    onOpen: (BrowseSource) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    if (sources.isEmpty()) {
        EmptyState(
            title = strings.text("No sources"),
            message = strings.text("Install an extension or enable another language to browse manga."),
            icon = { Icon(Icons.Outlined.TravelExplore, null, Modifier.size(30.dp)) },
        )
        return
    }
    var settingsSourceIdentity by remember { mutableStateOf<String?>(null) }
    val sections = browseSourceSections(sources, pinnedSourceIds, pinnedSourceKeys)
    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (sections.pinned.isNotEmpty()) {
            item("pinned-header") {
                SourceSectionHeader(strings.text("Pinned"), sections.pinned.size, pinned = true)
            }
            items(sections.pinned, key = { "pinned:${it.identityKey}" }) { source ->
                SourceListRow(
                    source = source,
                    pinned = true,
                    onOpen = onOpen,
                    onToggle = onToggle,
                    onPinnedChange = onSourcePinnedChange,
                    onPinnedKeyChange = onSourcePinnedKeyChange,
                    onSettings = { settingsSourceIdentity = source.identityKey },
                )
            }
            if (sections.regular.isNotEmpty()) {
                item("regular-header") {
                    SourceSectionHeader(strings.text("All sources"), sections.regular.size, pinned = false)
                }
            }
        }
        items(sections.regular, key = { "source:${it.identityKey}" }) { source ->
            SourceListRow(
                source = source,
                pinned = false,
                onOpen = onOpen,
                onToggle = onToggle,
                onPinnedChange = onSourcePinnedChange,
                onPinnedKeyChange = onSourcePinnedKeyChange,
                onSettings = { settingsSourceIdentity = source.identityKey },
            )
        }
    }
    sources.firstOrNull { it.identityKey == settingsSourceIdentity }?.let { source ->
        SourceSettingsDialog(
            source = source,
            callbacks = callbacks,
            onImportDocument = onImportDocument,
            onDismiss = { settingsSourceIdentity = null },
        )
    }
}

internal data class BrowseSourceSections(
    val pinned: List<BrowseSource>,
    val regular: List<BrowseSource>,
)

/** Partitions unique sources and applies a platform-independent order for stable list keys/UI. */
internal fun browseSourceSections(
    sources: List<BrowseSource>,
    pinnedSourceIds: Set<Long>,
    pinnedSourceKeys: Set<String> = emptySet(),
): BrowseSourceSections {
    val unique = sources.distinctBy(BrowseSource::identityKey)
    val pinnedOrder = compareBy<BrowseSource>(
        { it.name.trim().lowercase() },
        { it.language.trim().lowercase() },
        BrowseSource::identityKey,
    )
    val regularOrder = compareBy<BrowseSource>(
        { it.language.trim().lowercase() },
        { it.name.trim().lowercase() },
        BrowseSource::identityKey,
    )
    return BrowseSourceSections(
        pinned = unique.filter { source ->
            source.isPinned(pinnedSourceIds, pinnedSourceKeys)
        }.sortedWith(pinnedOrder),
        regular = unique.filterNot { source ->
            source.isPinned(pinnedSourceIds, pinnedSourceKeys)
        }.sortedWith(regularOrder),
    )
}

private fun BrowseSource.isPinned(
    pinnedSourceIds: Set<Long>,
    pinnedSourceKeys: Set<String>,
): Boolean = if (sourceKey == null) {
    id in pinnedSourceIds
} else {
    identityKey in pinnedSourceKeys
}

@Composable
private fun SourceSectionHeader(title: String, count: Int, pinned: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 9.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (pinned) Icon(Icons.Outlined.PushPin, null, Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal data class SourceSettingsSections(
    val credentials: Boolean,
    val preferences: Boolean,
    val browserSessionLogin: Boolean,
)

/**
 * Login UI is capability-driven. A stale credential from an older plugin version must not make
 * username/password controls reappear after that source stops implementing the login contract.
 */
internal fun sourceSettingsSections(source: BrowseSource): SourceSettingsSections =
    SourceSettingsSections(
        credentials = source.supportsLogin,
        preferences = source.preferences.isNotEmpty(),
        browserSessionLogin = source.supportsLogin && source.requiresBrowserSessionLogin,
    )

@Composable
private fun SourceListRow(
    source: BrowseSource,
    pinned: Boolean,
    onOpen: (BrowseSource) -> Unit,
    onToggle: (BrowseSource, Boolean) -> Unit,
    onPinnedChange: (sourceId: Long, pinned: Boolean) -> Unit,
    onPinnedKeyChange: (sourceKey: String, pinned: Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val legacyActions = source.sourceKey == null
    val settingsSections = sourceSettingsSections(source)
    val settingsAvailable = legacyActions || settingsSections.credentials ||
        settingsSections.preferences || source.baseUrl.isNotBlank()
    Surface(
        onClick = { if (source.enabled) onOpen(source) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 360.dp
            if (compact) {
                // A 320dp Android phone cannot fit the icon, three actions and a readable
                // source title on one row. Keep the summary and actions on separate rows so
                // the weighted text column receives a real width instead of one glyph.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        SourceIcon(source)
                        Column(Modifier.weight(1f)) {
                            Text(source.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    append(source.language.uppercase())
                                    append(" · ")
                                    append(source.contentType.label(strings))
                                    if (source.isNsfw) append(" · NSFW")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(
                            onClick = {
                                if (source.sourceKey == null) {
                                    onPinnedChange(source.id, !pinned)
                                } else {
                                    onPinnedKeyChange(source.identityKey, !pinned)
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.PushPin,
                                if (pinned) strings.text("Unpin {0}", source.name) else strings.text("Pin {0}", source.name),
                                tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = source.enabled,
                            onCheckedChange = { onToggle(source, it) },
                        )
                        if (settingsAvailable) {
                            IconButton(onClick = onSettings) {
                                Icon(Icons.Outlined.Settings, strings.text("Source settings"))
                            }
                        }
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    SourceIcon(source)
                    Column(Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append(source.language.uppercase())
                                append(" · ")
                                append(source.contentType.label(strings))
                                if (source.isNsfw) append(" · NSFW")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            if (source.sourceKey == null) {
                                onPinnedChange(source.id, !pinned)
                            } else {
                                onPinnedKeyChange(source.identityKey, !pinned)
                            }
                        },
                    ) {
                        Icon(
                            Icons.Outlined.PushPin,
                            if (pinned) strings.text("Unpin {0}", source.name) else strings.text("Pin {0}", source.name),
                            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = source.enabled,
                        onCheckedChange = { onToggle(source, it) },
                    )
                    if (settingsAvailable) {
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Outlined.Settings, strings.text("Source settings"))
                        }
                    }
                    Icon(Icons.Outlined.OpenInNew, strings.browse, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SourceSettingsDialog(
    source: BrowseSource,
    callbacks: BrowseCallbacks,
    onImportDocument: suspend (acceptedExtensions: Set<String>) -> ImportedDocument?,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    val settingsSections = sourceSettingsSections(source)
    var values by remember(source.id, source.preferences) {
        mutableStateOf(source.preferences.associate { it.key to it.value })
    }
    var credential by remember(source.id) { mutableStateOf<SourceCredential?>(null) }
    var sourceCookies by remember(source.id) { mutableStateOf(emptyList<SourceCookie>()) }
    var secretsLoading by remember(source.id) { mutableStateOf(true) }
    var secretsError by remember(source.id) { mutableStateOf<String?>(null) }
    var username by remember(source.id) { mutableStateOf("") }
    var password by remember(source.id) { mutableStateOf("") }
    var loginBusy by remember(source.id) { mutableStateOf(false) }
    var loginMessage by remember(source.id) { mutableStateOf<String?>(null) }
    var loginError by remember(source.id) { mutableStateOf<String?>(null) }
    var cookieName by remember(source.id) { mutableStateOf("") }
    var cookieValue by remember(source.id) { mutableStateOf("") }
    var cookieDomain by remember(source.id) { mutableStateOf(defaultCookieDomain(source.baseUrl)) }
    var challengeRequest by remember(source.id) { mutableStateOf<SourceWebChallengeRequest?>(null) }
    var challengeBusy by remember(source.id) { mutableStateOf(false) }
    var challengeMessage by remember(source.id) { mutableStateOf<String?>(null) }
    var cookieImportBusy by remember(source.id) { mutableStateOf(false) }
    var cookieImportMessage by remember(source.id) { mutableStateOf<String?>(null) }

    fun openWebChallenge() {
        challengeBusy = true
        challengeMessage = null
        scope.launch {
            withFrameNanos { }
            runCatching {
                callbacks.sourceWebChallenge(
                    sourceId = source.id,
                    username = username,
                    password = password,
                )
            }
                .onSuccess { request ->
                    if (request == null) {
                        challengeMessage = strings.text("This source does not provide a valid HTTP(S) URL.")
                    } else {
                        challengeRequest = request
                    }
                }
                .onFailure { error ->
                    challengeMessage = error.message ?: strings.text("Unable to start the web challenge.")
                }
            challengeBusy = false
        }
    }

    LaunchedEffect(source.id) {
        secretsLoading = true
        secretsError = null
        runCatching { callbacks.loadSourceSecrets(source.id) }
            .onSuccess { result ->
                credential = result.secrets.credential
                sourceCookies = result.secrets.cookies
                username = result.secrets.credential?.username.orEmpty()
                password = result.secrets.credential?.password.orEmpty()
                secretsError = result.failureStage?.let { stage ->
                    strings.text(sourceLoginFailureMessageKey(stage))
                }
            }
            .onFailure {
                secretsError = strings.text("Unable to read credentials from secure storage.")
            }
        secretsLoading = false
    }

    AlertDialog(
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
        onDismissRequest = onDismiss,
        title = { Text("${source.name} ${strings.settings}") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (settingsSections.credentials) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(strings.text("Credentials"), style = MaterialTheme.typography.titleMedium)
                            if (secretsLoading) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(
                                        strings.text("Preparing…"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            secretsError?.let { message ->
                                Text(
                                    message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text(strings.text("Username")) },
                                singleLine = true,
                                enabled = !secretsLoading && !loginBusy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(strings.text("Password")) },
                                singleLine = true,
                                enabled = !secretsLoading && !loginBusy,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (settingsSections.browserSessionLogin) {
                                            loginMessage = null
                                            loginError = null
                                            openWebChallenge()
                                            return@Button
                                        }
                                        loginBusy = true
                                        loginMessage = null
                                        loginError = null
                                        scope.launch {
                                            withFrameNanos { }
                                            runCatching {
                                                callbacks.saveSourceCredentialsResult(source.id, username, password)
                                            }.onSuccess { result ->
                                                val feedback = sourceLoginFeedback(
                                                    result = result,
                                                    successMessage = strings.text("Login successful."),
                                                    fallbackErrorMessage = strings.text(
                                                        "Login failed. Check your username and password.",
                                                    ),
                                                    failureMessage = { stage ->
                                                        strings.text(sourceLoginFailureMessageKey(stage))
                                                    },
                                                )
                                                loginMessage = feedback.successMessage
                                                loginError = feedback.errorMessage
                                                if (result.succeeded) {
                                                    credential = SourceCredential(username, password)
                                                }
                                            }.onFailure {
                                                // Never expose raw runtime/transport errors because they may
                                                // contain headers, cookies, stack text, or credentials.
                                                loginError = strings.text("The login operation failed unexpectedly.")
                                            }
                                            loginBusy = false
                                        }
                                    },
                                    enabled = !secretsLoading && !loginBusy && !challengeBusy &&
                                        (settingsSections.browserSessionLogin ||
                                            (username.isNotBlank() && password.isNotEmpty())),
                                ) {
                                    Text(
                                        strings.text(
                                            if (settingsSections.browserSessionLogin) "Sign in in browser" else "Login",
                                        ),
                                    )
                                }
                                if (credential != null) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                withFrameNanos { }
                                                runCatching { callbacks.logoutSource(source.id) }
                                                    .onSuccess {
                                                        credential = null
                                                        loginMessage = null
                                                        username = ""
                                                        password = ""
                                                    }
                                                    .onFailure {
                                                        loginError = strings.text(
                                                            "The login operation failed unexpectedly.",
                                                        )
                                                    }
                                            }
                                        },
                                    ) { Text(strings.text("Logout")) }
                                }
                            }
                            loginMessage?.let {
                                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            }
                            loginError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (settingsSections.browserSessionLogin) {
                                Text(
                                    strings.text(
                                        "This source must sign in through its website. The app will import only the source-declared browser session data and will not call its direct password login API.",
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                if (settingsSections.preferences) {
                    item { Text(strings.text("Preferences"), style = MaterialTheme.typography.titleMedium) }
                    items(source.preferences, key = { "preference:${it.key}" }) { preference ->
                        Column {
                            Text(strings.text(preference.title), style = MaterialTheme.typography.titleSmall)
                            preference.summary?.let {
                                Text(
                                    strings.text(it),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when (preference.kind) {
                                SourcePreferenceKind.Text -> OutlinedTextField(
                                    value = values[preference.key].orEmpty(),
                                    onValueChange = { values = values + (preference.key to it) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SourcePreferenceKind.Toggle -> Switch(
                                    checked = values[preference.key].toBoolean(),
                                    onCheckedChange = { values = values + (preference.key to it.toString()) },
                                )
                                SourcePreferenceKind.Choice -> Column {
                                    preference.choices.forEachIndexed { index, label ->
                                        val choiceValue = preference.choiceValues.getOrElse(index) { label }
                                        FilterChip(
                                            selected = values[preference.key] == choiceValue,
                                            onClick = { values = values + (preference.key to choiceValue) },
                                            label = { Text(strings.text(label), maxLines = 1) },
                                        )
                                    }
                                }
                                SourcePreferenceKind.MultiChoice -> {
                                    val selected = values[preference.key].orEmpty()
                                        .split(',')
                                        .filter { it.isNotBlank() }
                                        .toSet()
                                    Column {
                                        preference.choices.forEachIndexed { index, label ->
                                            val choiceValue = preference.choiceValues.getOrElse(index) { label }
                                            FilterChip(
                                                selected = choiceValue in selected,
                                                onClick = {
                                                    val updated = if (choiceValue in selected) {
                                                        selected - choiceValue
                                                    } else {
                                                        selected + choiceValue
                                                    }
                                                    values = values + (
                                                        preference.key to updated.sorted().joinToString(",")
                                                    )
                                                },
                                                label = { Text(strings.text(label), maxLines = 1) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                strings.text(
                                    if (settingsSections.browserSessionLogin) "Browser session" else "Cookies",
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            OutlinedButton(
                                onClick = { openWebChallenge() },
                                enabled = !challengeBusy && !secretsLoading,
                            ) {
                                Icon(Icons.Outlined.Security, null, Modifier.size(18.dp))
                                Text(if (challengeBusy) strings.text("Preparing…") else strings.text("Web challenge / Cloudflare"))
                            }
                            Text(
                                strings.text(
                                    "Uses a browser-compatible User-Agent. If credentials are filled above, the isolated same-origin browser fills and submits the website login form automatically; only cookies valid for the source domain and path are imported.",
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                onClick = {
                                    cookieImportBusy = true
                                    cookieImportMessage = null
                                    scope.launch {
                                        withFrameNanos { }
                                        runCatching {
                                            val document = onImportDocument(setOf("json", "txt"))
                                                ?: return@runCatching null
                                            require(document.contents.size <= MAX_COOKIE_FILE_BYTES) {
                                                strings.text("Cookie file is larger than 1 MiB.")
                                            }
                                            val content = document.contents.decodeToString(throwOnInvalidSequence = true)
                                            val importedCookies = CookieFileParser.parse(content, source.baseUrl)
                                            require(importedCookies.isNotEmpty()) {
                                                strings.text("No valid cookies for {0} were found.", defaultCookieDomain(source.baseUrl).trimStart('.'))
                                            }
                                            importedCookies.forEach { cookie -> callbacks.setSourceCookie(source.id, cookie) }
                                            importedCookies
                                        }.onSuccess { importedCookies ->
                                            if (importedCookies != null) {
                                                sourceCookies = importedCookies.fold(sourceCookies) { saved, cookie ->
                                                    saved.upsert(cookie)
                                                }
                                                cookieImportMessage = strings.text(
                                                    "Imported {0} cookie(s).",
                                                    importedCookies.size,
                                                )
                                            }
                                        }.onFailure { error ->
                                            cookieImportMessage = strings.text("Error: {0}", error.message ?: strings.text("unable to import cookies"))
                                        }
                                        cookieImportBusy = false
                                    }
                                },
                                enabled = !cookieImportBusy,
                            ) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                                Text(if (cookieImportBusy) strings.text("Importing…") else strings.text("Import cookies.txt / JSON"))
                            }
                            cookieImportMessage?.let { message ->
                                Text(
                                    message,
                                    color = if (message.startsWith("Error", ignoreCase = true)) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            challengeMessage?.let { message ->
                                Text(
                                    message,
                                    color = if (message.startsWith("Error", ignoreCase = true) ||
                                        message.startsWith("Unable", ignoreCase = true)
                                    ) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!secretsLoading && sourceCookies.isEmpty()) {
                                Text(strings.text("No cookies saved"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(
                        sourceCookies,
                        key = { "cookie:${it.domain}:${it.path}:${it.name}" },
                    ) { cookie ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cookie.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${cookie.value} · ${cookie.domain}${cookie.path}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            callbacks.deleteSourceCookie(source.id, cookie.name, cookie.domain)
                                        }.onSuccess {
                                            sourceCookies = sourceCookies.filterNot { saved ->
                                                saved.name == cookie.name &&
                                                    saved.domain.equals(cookie.domain, ignoreCase = true)
                                            }
                                        }.onFailure {
                                            cookieImportMessage = strings.text("Error: {0}", strings.text("unable to save browser cookies"))
                                        }
                                    }
                                },
                            ) { Icon(Icons.Outlined.Delete, strings.delete) }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedTextField(
                                value = cookieName,
                                onValueChange = { cookieName = it },
                                label = { Text(strings.text("Cookie name")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = cookieValue,
                                onValueChange = { cookieValue = it },
                                label = { Text(strings.text("Cookie value")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = cookieDomain,
                                onValueChange = { cookieDomain = it },
                                label = { Text(strings.text("Cookie domain")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val added = SourceCookie(cookieName, cookieValue, cookieDomain)
                                            runCatching { callbacks.setSourceCookie(source.id, added) }
                                                .onSuccess {
                                                    sourceCookies = sourceCookies.upsert(added)
                                                    cookieName = ""
                                                    cookieValue = ""
                                                }
                                                .onFailure {
                                                    cookieImportMessage = strings.text(
                                                        "Error: {0}",
                                                        strings.text("unable to save browser cookies"),
                                                    )
                                                }
                                        }
                                    },
                                    enabled = cookieName.isNotBlank() && cookieValue.isNotEmpty() && cookieDomain.isNotBlank(),
                                ) { Text(strings.text("Add cookie")) }
                                if (sourceCookies.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                runCatching { callbacks.clearSourceCookies(source.id) }
                                                    .onSuccess { sourceCookies = emptyList() }
                                                    .onFailure {
                                                        cookieImportMessage = strings.text(
                                                            "Error: {0}",
                                                            strings.text("unable to save browser cookies"),
                                                        )
                                                    }
                                            }
                                        },
                                    ) { Text(strings.text("Clear cookies")) }
                                }
                            }
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        callbacks.saveSourcePreferences(source.id, values)
                        onDismiss()
                    }
                },
            ) { Text(strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
    )

    challengeRequest?.let { request ->
        SourceWebChallengeDialog(
            request = request,
            onImport = { importedSession ->
                scope.launch {
                    challengeBusy = true
                    runCatching {
                        callbacks.importSourceWebChallengeSession(
                            sourceId = source.id,
                            cookies = importedSession.cookies,
                            userAgent = importedSession.userAgent,
                            localStorage = importedSession.localStorage,
                        )
                    }.onSuccess {
                        sourceCookies = importedSession.cookies.fold(sourceCookies) { saved, cookie -> saved.upsert(cookie) }
                        challengeMessage = if (importedSession.localStorage.isNotEmpty()) {
                            strings.text("Browser session imported successfully.")
                        } else {
                            strings.text("Imported {0} cookie(s).", importedSession.cookies.size)
                        }
                        challengeRequest = null
                    }.onFailure { error ->
                        challengeMessage = strings.text(
                            "Error: {0}",
                            error.message ?: strings.text("unable to save browser cookies"),
                        )
                    }
                    challengeBusy = false
                }
            },
            onDismiss = {
                challengeRequest = null
                challengeMessage = strings.text("Web challenge cancelled. No browser session was imported.")
            },
        )
    }
}

private fun List<SourceCookie>.upsert(cookie: SourceCookie): List<SourceCookie> =
    filterNot { saved ->
        saved.name == cookie.name &&
            saved.domain.equals(cookie.domain, ignoreCase = true) &&
            saved.path == cookie.path
    } + cookie

private fun defaultCookieDomain(baseUrl: String): String {
    val host = baseUrl.substringAfter("://", "").substringBefore('/').substringBefore(':')
    return host.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
}

@Composable
private fun SourceIcon(source: BrowseSource) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(source.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ExtensionsPane(
    repositories: List<BrowseRepository>,
    selectedRepositoryId: String?,
    extensions: List<BrowseExtension>,
    onAddRepository: (String) -> Unit,
    onRemoveRepository: (String) -> Unit,
    onSelectRepository: (String?) -> Unit,
    onInstall: (BrowseExtension) -> Unit,
    onUninstall: (BrowseExtension) -> Unit,
    onTrust: (BrowseExtension, Boolean) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var repositoryDialog by remember { mutableStateOf(false) }
    var repositoryUrl by remember { mutableStateOf("") }
    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item("repositories-title") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(strings.text("Repositories"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { repositoryDialog = true }) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.text("Add"))
                }
            }
        }
        if (repositories.isEmpty()) {
            item("empty-repositories") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        strings.text("Add an extension repository before installing sources."),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        } else {
            items(repositories, key = { "repo-${it.id}" }) { repository ->
                Surface(
                    onClick = { onSelectRepository(repository.id) },
                    shape = RoundedCornerShape(11.dp),
                    color = if (repository.id == selectedRepositoryId) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Language, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(repository.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                repository.url,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (repository.official) {
                            Icon(Icons.Filled.CheckCircle, strings.text("Official"), tint = MaterialTheme.colorScheme.primary)
                        }
                        // Official repositories are recoverable defaults, not immutable rows. The
                        // adapter hides an official ShuYue row until the user adds it again.
                        IconButton(onClick = { onRemoveRepository(repository.id) }) {
                            Icon(Icons.Outlined.Delete, strings.delete)
                        }
                    }
                }
            }
        }
        item("extensions-title") {
            Text(
                strings.extensions,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
            )
        }
        if (extensions.isEmpty()) {
            item("empty-extensions") {
                EmptyState(
                    title = strings.text("No extensions"),
                    message = strings.text("Refresh the selected repository or try a different language."),
                    icon = { Icon(Icons.Outlined.Extension, null, Modifier.size(30.dp)) },
                )
            }
        } else {
            items(extensions, key = { "extension-${it.id}" }) { extension ->
                ExtensionRow(extension, onInstall, onUninstall, onTrust)
            }
        }
    }

    if (repositoryDialog) {
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { repositoryDialog = false },
            title = { Text(strings.text("Add repository")) },
            text = {
                OutlinedTextField(
                    value = repositoryUrl,
                    onValueChange = { repositoryUrl = it },
                    label = { Text(strings.text("Repository URL")) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = repositoryUrl.startsWith("http"),
                    onClick = {
                        onAddRepository(repositoryUrl.trim())
                        repositoryUrl = ""
                        repositoryDialog = false
                    },
                ) { Text(strings.text("Add")) }
            },
            dismissButton = { TextButton(onClick = { repositoryDialog = false }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun ExtensionRow(
    extension: BrowseExtension,
    onInstall: (BrowseExtension) -> Unit,
    onUninstall: (BrowseExtension) -> Unit,
    onTrust: (BrowseExtension, Boolean) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(10.dp), modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Extension, null)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(extension.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${extension.language.uppercase()} · v${extension.version}" +
                        " · ${extension.contentType.label(strings)}" +
                        if (extension.updateAvailable) " · ${strings.text("Update available")}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (extension.installed) {
                    if (extension.reviewedShuYueV2) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Outlined.Security, null, Modifier.size(16.dp))
                            Text(
                                if (extension.trusted) {
                                    strings.text("Exact reviewed permissions granted")
                                } else {
                                    strings.text("Execution blocked")
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    } else {
                        AssistChip(
                            onClick = { onTrust(extension, !extension.trusted) },
                            label = {
                                Text(
                                    if (extension.trusted) strings.text("Execution allowed")
                                    else strings.text("Execution blocked"),
                                )
                            },
                            leadingIcon = { Icon(Icons.Outlined.Security, null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            when {
                extension.installed && extension.updateAvailable -> Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Button(onClick = { onInstall(extension) }) { Text(strings.text("Update")) }
                    TextButton(onClick = { onUninstall(extension) }) { Text(strings.uninstall) }
                }
                extension.installed -> OutlinedButton(onClick = { onUninstall(extension) }) {
                    Text(strings.uninstall)
                }
                else -> Button(onClick = { onInstall(extension) }) { Text(strings.install) }
            }
        }
    }
}

private fun PluginContentType.label(strings: ShinsouStrings): String = when (this) {
    PluginContentType.MANGA -> strings.text("Manga")
    PluginContentType.NOVEL -> strings.text("Novel")
    PluginContentType.BOTH -> strings.text("Manga + novel")
}

@Composable
private fun MigrationPane(
    migrations: List<MigrationCandidate>,
    sources: List<BrowseSource>,
    callbacks: BrowseCallbacks,
    onMigrate: (MigrationCandidate, BrowseManga) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    if (migrations.isEmpty()) {
        EmptyState(
            title = strings.text("Nothing to migrate"),
            message = strings.text("Migration suggestions appear after compatible sources are installed."),
            icon = { Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(30.dp)) },
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(migrations, key = { it.mangaId }) { candidate ->
            MigrationCandidateCard(candidate, sources, callbacks, onMigrate)
        }
    }
}

@Composable
private fun MigrationCandidateCard(
    candidate: MigrationCandidate,
    sources: List<BrowseSource>,
    callbacks: BrowseCallbacks,
    onMigrate: (MigrationCandidate, BrowseManga) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    val targetSources = remember(sources, candidate.currentSourceName) {
        eligibleMigrationSources(sources, candidate.currentSourceName)
    }
    var selectedSourceId by remember(candidate.mangaId, targetSources) {
        mutableStateOf(targetSources.firstOrNull()?.id)
    }
    val selectedSource = targetSources.firstOrNull { it.id == selectedSourceId }
    var sourceMenuVisible by remember(candidate.mangaId) { mutableStateOf(false) }
    var searchQuery by remember(candidate.mangaId) { mutableStateOf(candidate.title) }
    var searchResults by remember(candidate.mangaId, selectedSourceId, candidate.suggestions) {
        mutableStateOf(
            rankBrowseResults(
                candidate.title,
                candidate.suggestions.filter { it.sourceId == selectedSourceId },
            ),
        )
    }
    var searchLoading by remember(candidate.mangaId) { mutableStateOf(false) }
    var searchError by remember(candidate.mangaId) { mutableStateOf<String?>(null) }
    var pendingTarget by remember(candidate.mangaId) { mutableStateOf<BrowseManga?>(null) }

    fun searchSelectedSource() {
        val source = selectedSource ?: return
        val submittedQuery = searchQuery.trim()
        if (submittedQuery.isEmpty() || searchLoading) return
        searchLoading = true
        searchError = null
        scope.launch {
            withFrameNanos { }
            try {
                searchResults = withContext(Dispatchers.Default) {
                    val page = callbacks.browseSource(source.id, submittedQuery, page = 1)
                    rankBrowseResults(submittedQuery, page.items)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                searchResults = emptyList()
                searchError = error.message ?: strings.text("Unable to search {0}", source.name)
            }
            searchLoading = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(candidate.title, style = MaterialTheme.typography.titleLarge)
            Text(
                strings.text("Current source: {0}", candidate.currentSourceName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (targetSources.isEmpty()) {
                Text(
                    strings.text("Install or enable another compatible source to migrate this title."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        OutlinedButton(onClick = { sourceMenuVisible = true }) {
                            Icon(Icons.Outlined.Language, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(selectedSource?.name ?: strings.text("Choose source"), maxLines = 1)
                        }
                        DropdownMenu(
                            expanded = sourceMenuVisible,
                            onDismissRequest = { sourceMenuVisible = false },
                        ) {
                            targetSources.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text("${source.name} · ${source.language.uppercase()}") },
                                    onClick = {
                                        selectedSourceId = source.id
                                        sourceMenuVisible = false
                                        searchError = null
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(strings.text("Search title")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = ::searchSelectedSource,
                        enabled = searchQuery.isNotBlank() && selectedSource != null && !searchLoading,
                    ) {
                        if (searchLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Search, strings.search, Modifier.size(18.dp))
                        }
                    }
                }
                searchError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (!searchLoading && searchResults.isEmpty() && searchError == null) {
                    Text(
                        strings.text("Choose a target source, adjust the title if needed, then search."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (searchResults.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(searchResults.take(10), key = { "migration:${it.sourceId}:${it.url}" }) { target ->
                            BrowseResultCard(target, onClick = { pendingTarget = target })
                        }
                    }
                }
            }
        }
    }

    pendingTarget?.let { target ->
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { pendingTarget = null },
            title = { Text(strings.text("Migrate to this manga?")) },
            text = {
                Text(
                    strings.text("Replace “{0}” with “{1}”? Read progress, categories and tracking links stay attached to this library entry.", candidate.title, target.title),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingTarget = null
                        onMigrate(candidate, target)
                    },
                ) { Text(strings.migration, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTarget = null }) { Text(strings.cancel) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceCatalogueScreen(
    source: BrowseSource,
    refreshGeneration: Long,
    page: BrowsePage,
    loading: Boolean,
    loadingMore: Boolean,
    error: String?,
    onBack: () -> Unit,
    onBrowse: (String, SourceCatalogueMode, List<BrowseFilter>?) -> Unit,
    onLoadMore: (String, SourceCatalogueMode, List<BrowseFilter>?) -> Unit,
    onOpenManga: (BrowseManga) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var query by remember(source.identityKey) { mutableStateOf("") }
    var mode by remember(source.identityKey) { mutableStateOf(SourceCatalogueMode.Popular) }
    var appliedFilters by remember(source.identityKey) { mutableStateOf<List<BrowseFilter>?>(null) }
    var filterDialogVisible by remember(source.identityKey) { mutableStateOf(false) }
    // Keep the last submitted request separate from the editable search controls. Otherwise
    // typing a new query while the old result grid is at its end could auto-load another page
    // for a query that the user has not submitted yet.
    var requestedQuery by remember(source.identityKey) { mutableStateOf("") }
    var requestedMode by remember(source.identityKey) { mutableStateOf(SourceCatalogueMode.Popular) }
    var requestedFilters by remember(source.identityKey) { mutableStateOf<List<BrowseFilter>?>(null) }
    val gridState = rememberLazyGridState()

    fun requestBrowse(
        search: String = query,
        selectedMode: SourceCatalogueMode = mode,
        filters: List<BrowseFilter>? = appliedFilters,
    ) {
        requestedQuery = search
        requestedMode = selectedMode
        requestedFilters = filters
        onBrowse(search, selectedMode, filters)
    }

    fun requestLoadMore() {
        onLoadMore(requestedQuery, requestedMode, requestedFilters)
    }

    LaunchedEffect(source.identityKey, refreshGeneration) {
        if (refreshGeneration > 0L) {
            requestBrowse(requestedQuery, requestedMode, requestedFilters)
        }
    }

    val latestPage = rememberUpdatedState(page)
    val latestLoading = rememberUpdatedState(loading)
    val latestLoadingMore = rememberUpdatedState(loadingMore)
    val latestError = rememberUpdatedState(error)
    val latestLoadMore = rememberUpdatedState<() -> Unit> { requestLoadMore() }
    LaunchedEffect(
        source.identityKey,
        page.items.size,
        page.hasNextPage,
        loading,
        loadingMore,
    ) {
        snapshotFlow {
            val layoutInfo = gridState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val totalItems = layoutInfo.totalItemsCount
            totalItems > 0 && lastVisibleIndex >= totalItems - 4
        }.collect { nearEnd ->
            if (
                nearEnd &&
                latestPage.value.hasNextPage &&
                latestError.value == null &&
                !latestLoading.value &&
                !latestLoadingMore.value
            ) {
                latestLoadMore.value()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = source.name,
            subtitle = source.language.uppercase(),
            leading = {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text("Back")) }
            },
            actions = {
                if (source.filters.isNotEmpty()) {
                    IconButton(onClick = { filterDialogVisible = true }) {
                        Icon(
                            Icons.Outlined.FilterList,
                            contentDescription = strings.filter,
                            tint = if (appliedFilters != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { requestBrowse() }) {
                    Icon(Icons.Outlined.Refresh, strings.refresh)
                }
            },
        )
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = strings.search,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            onSubmit = { requestBrowse() },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = mode == SourceCatalogueMode.Popular,
                onClick = {
                    mode = SourceCatalogueMode.Popular
                    requestBrowse(query, SourceCatalogueMode.Popular, appliedFilters)
                },
                label = { Text(strings.text("Popular")) },
            )
            if (source.supportsFavorites) {
                FilterChip(
                    selected = mode == SourceCatalogueMode.Favorites,
                    onClick = {
                        mode = SourceCatalogueMode.Favorites
                        query = ""
                        appliedFilters = null
                        requestBrowse("", SourceCatalogueMode.Favorites, null)
                    },
                    label = { Text(strings.myLibrary) },
                )
            }
            if (source.supportsLatest) {
                FilterChip(
                    selected = mode == SourceCatalogueMode.Latest,
                    onClick = {
                        mode = SourceCatalogueMode.Latest
                        requestBrowse(query, SourceCatalogueMode.Latest, appliedFilters)
                    },
                    label = { Text(strings.text("Latest")) },
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (mode == SourceCatalogueMode.Favorites) {
                        requestBrowse("", SourceCatalogueMode.Favorites, null)
                    } else {
                        requestBrowse()
                    }
                },
            ) { Text(if (mode == SourceCatalogueMode.Favorites) strings.text("Refresh") else strings.search) }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && page.items.isEmpty() -> EmptyState(
                title = strings.text("Source error"),
                message = error,
                icon = { Icon(Icons.Outlined.Language, null, Modifier.size(30.dp)) },
                action = { Button(onClick = { requestBrowse() }) { Text(strings.retry) } },
            )
            page.items.isEmpty() -> EmptyState(
                title = strings.noMatches,
                message = strings.text("Try another query or refresh this source."),
                icon = { Icon(Icons.Outlined.TravelExplore, null, Modifier.size(30.dp)) },
            )
            else -> LazyVerticalGrid(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = gridState,
                columns = GridCells.Adaptive(128.dp),
                contentPadding = PaddingValues(20.dp, 10.dp, 20.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(page.items, key = BrowseManga::identityKey) { item ->
                    Column(
                        Modifier.combinedClickable(
                            onClick = { onOpenManga(item) },
                            onLongClick = { onOpenManga(item) },
                        ),
                    ) {
                        CoverImage(
                            title = item.title,
                            url = item.thumbnailUrl,
                            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                            headers = item.thumbnailHeaders,
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (page.hasNextPage || loadingMore || error != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (error != null) {
                                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (loadingMore) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            } else if (page.hasNextPage) {
                                OutlinedButton(onClick = ::requestLoadMore) {
                                    Text(strings.text("Load more"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (filterDialogVisible) {
        SourceFilterDialog(
            sourceName = source.name,
            defaults = source.filters,
            current = appliedFilters ?: source.filters,
            onDismiss = { filterDialogVisible = false },
            onApply = { filters ->
                appliedFilters = filters
                mode = SourceCatalogueMode.Popular
                filterDialogVisible = false
                requestBrowse(query, SourceCatalogueMode.Popular, filters)
            },
            onClear = {
                appliedFilters = null
                filterDialogVisible = false
                requestBrowse(query, mode, null)
            },
        )
    }
}

@Composable
private fun SourceFilterDialog(
    sourceName: String,
    defaults: List<BrowseFilter>,
    current: List<BrowseFilter>,
    onDismiss: () -> Unit,
    onApply: (List<BrowseFilter>) -> Unit,
    onClear: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var working by remember(sourceName, defaults, current) { mutableStateOf(current) }
    AlertDialog(
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
        onDismissRequest = onDismiss,
        title = { Text("$sourceName · ${strings.filter}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { working = defaults }) { Text(strings.text("Reset")) }
                    TextButton(onClick = onClear) { Text(strings.clearFilters) }
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(working.size, key = { index -> "filter:$index:${working[index].name}" }) { index ->
                        SourceFilterEditor(
                            filter = working[index],
                            onChange = { changed ->
                                working = working.toMutableList().apply { this[index] = changed }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(working) }) { Text(strings.text("Apply")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun SourceFilterEditor(
    filter: BrowseFilter,
    onChange: (BrowseFilter) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    when (filter) {
        is BrowseFilter.Header -> Text(
            filter.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
        BrowseFilter.Separator -> HorizontalDivider()
        is BrowseFilter.Select -> SourceSelectFilter(filter, onChange)
        is BrowseFilter.Text -> OutlinedTextField(
            value = filter.state,
            onValueChange = { onChange(filter.copy(state = it)) },
            label = { Text(filter.name) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is BrowseFilter.CheckBox -> Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(filter.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = filter.state,
                onCheckedChange = { onChange(filter.copy(state = it)) },
            )
        }
        is BrowseFilter.TriState -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(filter.name, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BrowseTriState.entries.forEach { state ->
                    FilterChip(
                        selected = filter.state == state,
                        onClick = { onChange(filter.copy(state = state)) },
                        label = {
                            Text(
                                when (state) {
                                    BrowseTriState.Ignore -> strings.text("Ignore")
                                    BrowseTriState.Include -> strings.text("Include")
                                    BrowseTriState.Exclude -> strings.text("Exclude")
                                },
                            )
                        },
                    )
                }
            }
        }
        is BrowseFilter.Group -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(filter.name, style = MaterialTheme.typography.titleSmall)
                filter.filters.forEachIndexed { index, child ->
                    SourceFilterEditor(
                        filter = child,
                        onChange = { changed ->
                            onChange(
                                filter.copy(
                                    filters = filter.filters.toMutableList().apply { this[index] = changed },
                                ),
                            )
                        },
                    )
                }
            }
        }
        is BrowseFilter.Sort -> SourceSortFilter(filter, onChange)
    }
}

@Composable
private fun SourceSelectFilter(
    filter: BrowseFilter.Select,
    onChange: (BrowseFilter) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var expanded by remember(filter.name, filter.values) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(filter.name, style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = filter.values.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(filter.values.getOrNull(filter.state) ?: strings.text("Not set"), maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                filter.values.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onChange(filter.copy(state = index))
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceSortFilter(
    filter: BrowseFilter.Sort,
    onChange: (BrowseFilter) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var expanded by remember(filter.name, filter.values) { mutableStateOf(false) }
    val selection = filter.selection
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(filter.name, style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selection?.let { filter.values.getOrNull(it.index) } ?: strings.text("None"), maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(strings.text("None")) },
                    onClick = {
                        onChange(filter.copy(selection = null))
                        expanded = false
                    },
                )
                filter.values.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onChange(
                                filter.copy(
                                    selection = BrowseSortSelection(
                                        index = index,
                                        ascending = selection?.ascending ?: false,
                                    ),
                                ),
                            )
                            expanded = false
                        },
                    )
                }
            }
        }
        selection?.let { selected ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = selected.ascending,
                    onClick = { onChange(filter.copy(selection = selected.copy(ascending = true))) },
                    label = { Text(strings.text("Ascending")) },
                )
                FilterChip(
                    selected = !selected.ascending,
                    onClick = { onChange(filter.copy(selection = selected.copy(ascending = false))) },
                    label = { Text(strings.text("Descending")) },
                )
            }
        }
    }
}
