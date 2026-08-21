package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.shinsou.kmp.ui.BrowseSortSelection
import dev.shinsou.kmp.ui.BrowseTriState
import dev.shinsou.kmp.ui.ImportedDocument
import dev.shinsou.kmp.ui.MigrationCandidate
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourcePreferenceKind
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import dev.shinsou.kmp.app.ContentFeatureRuntime
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerException
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
import dev.shinsou.kmp.ui.components.LoadingScrim
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
    modifier: Modifier = Modifier,
    systemBackRequest: Long = 0L,
    backGestureProgress: Float = 0f,
    onBackAvailabilityChanged: (Boolean) -> Unit = {},
) {
    val strings = LocalShinsouStrings.current
    val snapshot by callbacks.state.collectAsState()
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(initialSection) }
    var query by remember { mutableStateOf("") }
    var activeSource by remember { mutableStateOf<BrowseSource?>(null) }
    var globalSearchVisible by remember { mutableStateOf(false) }
    var activeV2Publication by remember { mutableStateOf<BrowseManga?>(null) }
    var page by remember { mutableStateOf(BrowsePage()) }
    var catalogueLoading by remember { mutableStateOf(false) }
    var catalogueLoadingMore by remember { mutableStateOf(false) }
    var catalogueError by remember { mutableStateOf<String?>(null) }
    var cataloguePageNumber by remember { mutableStateOf(1) }
    var catalogueRequestToken by remember { mutableStateOf(0) }
    val catalogueJobs = remember { CatalogueJobController() }
    var pendingReviewedInstall by remember { mutableStateOf<PendingReviewedShuYueInstall?>(null) }
    var reviewedInstallBusyId by remember { mutableStateOf<String?>(null) }
    var handledSystemBackRequest by remember { mutableStateOf(systemBackRequest) }
    val operationSnackbar = remember { SnackbarHostState() }
    val hasBrowseOverlay = activeSource != null || globalSearchVisible || activeV2Publication != null
    val currentBackAvailabilityCallback = rememberUpdatedState(onBackAvailabilityChanged)

    fun cancelCatalogueLoad() {
        // Invalidate completion handlers before cancellation so an old request cannot mutate the
        // loading state after the catalogue has closed or another source has been selected.
        catalogueRequestToken += 1
        catalogueJobs.cancel()
        catalogueLoading = false
        catalogueLoadingMore = false
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
                activeV2Publication != null -> activeV2Publication = null
                activeSource != null -> {
                    cancelCatalogueLoad()
                    activeSource = null
                }
                globalSearchVisible -> globalSearchVisible = false
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
            activeV2Publication = item
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
                        val v2Page = when {
                            mode == SourceCatalogueMode.Latest && search.isBlank() ->
                                callbacks.latestSourceV2(source.sourceKey, requestedPage - 1)
                            search.isNotBlank() ->
                                callbacks.searchSourceV2(source.sourceKey, search, requestedPage - 1)
                            else -> callbacks.browseSourceV2(source.sourceKey, page = requestedPage - 1)
                        }
                        BrowsePage(
                            items = v2Page.items.map { publication -> publication.toBrowseManga(source) },
                            hasNextPage = v2Page.hasNextPage,
                        )
                    } else if (mode == SourceCatalogueMode.Latest && search.isBlank()) {
                        callbacks.browseSourceLatest(source.id, requestedPage)
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
                        IconButton(onClick = { globalSearchVisible = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = strings.text("Search all sources"))
                        }
                    }
                    IconButton(onClick = { launchOperation { callbacks.refresh() } }) {
                        Icon(Icons.Outlined.Refresh, strings.refresh)
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
                    onSourcePinnedChange = onSourcePinnedChange,
                    callbacks = callbacks,
                    onImportDocument = onImportDocument,
                    onOpen = { source ->
                        activeSource = source
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
                        else launchOperation { callbacks.installExtension(extension.id) }
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

        if (globalSearchVisible) {
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
                    onBack = { globalSearchVisible = false },
                    onOpenManga = ::openManga,
                )
            }
        }

        activeSource?.let { source ->
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
                    page = page,
                    loading = catalogueLoading,
                    loadingMore = catalogueLoadingMore,
                    error = catalogueError,
                    onBack = {
                        cancelCatalogueLoad()
                        activeSource = null
                    },
                    onBrowse = { search, mode, filters ->
                        activeSource?.let {
                            loadCatalogue(it, search, mode, filters, requestedPage = 1, append = false)
                        }
                    },
                    onLoadMore = { search, mode, filters ->
                        activeSource?.let {
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
        activeV2Publication?.let { publication ->
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = size.width * backGestureProgress.coerceIn(0f, 1f)
                    },
            ) {
                ExtensionV2PublicationScreen(
                    callbacks = callbacks,
                    item = publication,
                    contentFeatures = contentFeatures,
                    copyText = copyText,
                    onBack = { activeV2Publication = null },
                )
            }
        }
        LoadingScrim(
            visible = snapshot.isRefreshing,
            label = strings.refresh,
        )
        SnackbarHost(
            hostState = operationSnackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp),
        )
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
    return if (translated != diagnostic) translated else strings.text("The operation could not be completed.")
}

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
)

/** Exact-keyed native extension path; host-issued selections remain the only content authority. */
@Composable
private fun ExtensionV2PublicationScreen(
    callbacks: BrowseCallbacks,
    item: BrowseManga,
    contentFeatures: ContentFeatureRuntime?,
    copyText: (label: String, text: String) -> Boolean,
    onBack: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    val sourceKey = requireNotNull(item.sourceKey)
    val remotePublicationId = requireNotNull(item.remotePublicationId)
    var publicationPage by remember(item.identityKey) {
        mutableStateOf<ExtensionPublicationPageV2?>(null)
    }
    var units by remember(item.identityKey) {
        mutableStateOf<List<ExtensionUnitSelectionV2>>(emptyList())
    }
    var nextPage by remember(item.identityKey) { mutableStateOf(0) }
    var hasNextPage by remember(item.identityKey) { mutableStateOf(false) }
    var loading by remember(item.identityKey) { mutableStateOf(true) }
    var loadingMore by remember(item.identityKey) { mutableStateOf(false) }
    var loadError by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var busyUnitId by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var operationMessage by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var operationFailed by remember(item.identityKey) { mutableStateOf(false) }
    var readerSession by remember(item.identityKey) {
        mutableStateOf<dev.shinsou.kmp.ui.TypedReaderContentSession?>(null)
    }
    var pendingRepresentation by remember(item.identityKey) {
        mutableStateOf<PendingExtensionRepresentationSelection?>(null)
    }

    val openedReader = readerSession
    if (openedReader != null && contentFeatures != null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = publicationPage?.publication?.title ?: item.title,
                subtitle = strings.text("Extension content"),
                leading = {
                    IconButton(onClick = { readerSession = null }) {
                        Icon(Icons.Outlined.ArrowBack, strings.text("Back"))
                    }
                },
            )
            UnifiedContentReader(
                session = openedReader,
                features = contentFeatures,
                copyText = copyText,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    suspend fun loadUnitPage(requestedPage: Int, append: Boolean) {
        val loaded = withContext(Dispatchers.Default) {
            callbacks.extensionPublicationPageV2(sourceKey, remotePublicationId, requestedPage)
        }
        check(loaded.sourceKey == sourceKey) { "Extension publication source identity changed" }
        check(loaded.publication.remoteId == remotePublicationId) {
            "Extension publication identity changed"
        }
        check(loaded.page == requestedPage) { "Extension unit page identity changed" }
        publicationPage = publicationPage ?: loaded
        units = if (append) {
            (units + loaded.units).distinctBy { it.unit.remoteId }
        } else {
            loaded.units
        }
        nextPage = requestedPage + 1
        hasNextPage = loaded.hasNextPage
    }

    fun materialize(selection: ExtensionUnitSelectionV2, representationId: String? = null) {
        if (busyUnitId != null) return
        busyUnitId = selection.unit.remoteId
        operationMessage = null
        operationFailed = false
        scope.launch {
            withFrameNanos { }
            try {
                val opened = withContext(Dispatchers.Default) {
                    val materialization = callbacks.materializeExtensionContentV2(selection, representationId)
                    if (contentFeatures != null) {
                        callbacks.openMaterializedExtensionContentV2(materialization)
                    } else {
                        null
                    }
                }
                if (opened != null) {
                    readerSession = opened
                } else {
                    operationMessage = strings.text("Content saved for offline reading.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (required: ExtensionContentConsumerException.RepresentationSelectionRequired) {
                pendingRepresentation = PendingExtensionRepresentationSelection(
                    selection = selection,
                    representationIds = required.availableIds,
                )
            } catch (error: Throwable) {
                operationFailed = true
                operationMessage = error.localizedDiagnosticMessage(strings)
            } finally {
                busyUnitId = null
            }
        }
    }

    LaunchedEffect(item.identityKey) {
        loading = true
        loadError = null
        try {
            loadUnitPage(requestedPage = 0, append = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            loadError = error.localizedDiagnosticMessage(strings)
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = publicationPage?.publication?.title ?: item.title,
            subtitle = strings.text("Extension content"),
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, strings.text("Back"))
                }
            },
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            loadError != null && units.isEmpty() -> EmptyState(
                title = strings.text("Unable to load chapters"),
                message = requireNotNull(loadError),
                icon = { Icon(Icons.Outlined.Extension, null, Modifier.size(30.dp)) },
                action = {
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true
                                loadError = null
                                try {
                                    loadUnitPage(requestedPage = 0, append = false)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Throwable) {
                                    loadError = error.localizedDiagnosticMessage(strings)
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    ) { Text(strings.retry) }
                },
            )
            units.isEmpty() -> EmptyState(
                title = strings.text("No chapters"),
                message = strings.text("This extension did not return any readable units."),
                icon = { Icon(Icons.Outlined.Extension, null, Modifier.size(30.dp)) },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                operationMessage?.let { message ->
                    item("extension-content-message") {
                        Text(
                            message,
                            color = if (operationFailed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                }
                items(units, key = { it.unit.remoteId }) { selection ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(selection.unit.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    strings.text("Choose a format when this chapter provides more than one."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = { materialize(selection) },
                                enabled = busyUnitId == null,
                            ) {
                                if (busyUnitId == selection.unit.remoteId) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(strings.text("Save offline"))
                                }
                            }
                        }
                    }
                }
                if (hasNextPage || loadingMore || loadError != null) {
                    item("extension-content-more") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            loadError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                            if (loadingMore) {
                                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                            } else if (hasNextPage) {
                                OutlinedButton(
                                    onClick = {
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
                                    },
                                ) { Text(strings.text("Load more")) }
                            }
                        }
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
                                materialize(pending.selection, representationId)
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
    sourceKey = requireNotNull(source.sourceKey),
    remotePublicationId = remoteId,
)

@Composable
private fun SourcesPane(
    sources: List<BrowseSource>,
    onToggle: (BrowseSource, Boolean) -> Unit,
    pinnedSourceIds: Set<Long>,
    onSourcePinnedChange: (sourceId: Long, pinned: Boolean) -> Unit,
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
    val sections = browseSourceSections(sources, pinnedSourceIds)
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
                onSettings = { settingsSourceIdentity = source.identityKey },
            )
        }
    }
    sources.firstOrNull {
        it.sourceKey == null && it.identityKey == settingsSourceIdentity
    }?.let { source ->
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
        pinned = unique.filter { it.sourceKey == null && it.id in pinnedSourceIds }.sortedWith(pinnedOrder),
        regular = unique.filterNot { it.sourceKey == null && it.id in pinnedSourceIds }.sortedWith(regularOrder),
    )
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

@Composable
private fun SourceListRow(
    source: BrowseSource,
    pinned: Boolean,
    onOpen: (BrowseSource) -> Unit,
    onToggle: (BrowseSource, Boolean) -> Unit,
    onPinnedChange: (sourceId: Long, pinned: Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val legacyActions = source.sourceKey == null
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
                        if (legacyActions) {
                            IconButton(onClick = { onPinnedChange(source.id, !pinned) }) {
                                Icon(
                                    Icons.Outlined.PushPin,
                                    if (pinned) strings.text("Unpin {0}", source.name) else strings.text("Pin {0}", source.name),
                                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = source.enabled,
                            onCheckedChange = { onToggle(source, it) },
                        )
                        if (legacyActions) {
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
                                if (source.isNsfw) append(" · NSFW")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (legacyActions) {
                        IconButton(onClick = { onPinnedChange(source.id, !pinned) }) {
                            Icon(
                                Icons.Outlined.PushPin,
                                if (pinned) strings.text("Unpin {0}", source.name) else strings.text("Pin {0}", source.name),
                                tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = source.enabled,
                        onCheckedChange = { onToggle(source, it) },
                    )
                    if (legacyActions) {
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
    require(source.sourceKey == null) { "Native extension v2 settings require an exact SourceKey settings surface" }
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var values by remember(source.id, source.preferences) {
        mutableStateOf(source.preferences.associate { it.key to it.value })
    }
    var username by remember(source.id) { mutableStateOf(source.credential?.username.orEmpty()) }
    var password by remember(source.id) { mutableStateOf(source.credential?.password.orEmpty()) }
    var loginBusy by remember(source.id) { mutableStateOf(false) }
    var loginError by remember(source.id) { mutableStateOf<String?>(null) }
    var cookieName by remember(source.id) { mutableStateOf("") }
    var cookieValue by remember(source.id) { mutableStateOf("") }
    var cookieDomain by remember(source.id) { mutableStateOf(defaultCookieDomain(source.baseUrl)) }
    var challengeRequest by remember(source.id) { mutableStateOf<SourceWebChallengeRequest?>(null) }
    var challengeBusy by remember(source.id) { mutableStateOf(false) }
    var challengeMessage by remember(source.id) { mutableStateOf<String?>(null) }
    var cookieImportBusy by remember(source.id) { mutableStateOf(false) }
    var cookieImportMessage by remember(source.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
        onDismissRequest = onDismiss,
        title = { Text("${source.name} ${strings.settings}") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(strings.text("Credentials"), style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(strings.text("Username")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(strings.text("Password")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    loginBusy = true
                                    loginError = null
                                    scope.launch {
                                        withFrameNanos { }
                                        runCatching {
                                            callbacks.saveSourceCredentials(source.id, username, password)
                                        }.onSuccess { success ->
                                            if (!success) loginError = strings.text("Login failed. Check your username and password.")
                                        }.onFailure { error ->
                                            loginError = error.message ?: strings.text("Unable to save credentials")
                                        }
                                        loginBusy = false
                                    }
                                },
                                enabled = username.isNotBlank() && password.isNotEmpty() && !loginBusy,
                            ) {
                                Text(if (source.supportsLogin) strings.text("Login") else strings.text("Save credentials"))
                            }
                            if (source.credential != null) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            withFrameNanos { }
                                            runCatching { callbacks.logoutSource(source.id) }
                                                .onFailure { loginError = it.message }
                                            username = ""
                                            password = ""
                                        }
                                    },
                                ) { Text(strings.text("Logout")) }
                            }
                        }
                        loginError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (source.preferences.isNotEmpty()) {
                    item { Text(strings.text("Preferences"), style = MaterialTheme.typography.titleMedium) }
                    items(source.preferences, key = { "preference:${it.key}" }) { preference ->
                        Column {
                            Text(preference.title, style = MaterialTheme.typography.titleSmall)
                            preference.summary?.let {
                                Text(
                                    it,
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
                                            label = { Text(label, maxLines = 1) },
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
                                                label = { Text(label, maxLines = 1) },
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
                        Text(strings.text("Cookies"), style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(
                            onClick = {
                                challengeBusy = true
                                challengeMessage = null
                                scope.launch {
                                    withFrameNanos { }
                                    runCatching { callbacks.sourceWebChallenge(source.id) }
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
                            },
                            enabled = !challengeBusy,
                        ) {
                            Icon(Icons.Outlined.Security, null, Modifier.size(18.dp))
                            Text(if (challengeBusy) strings.text("Preparing…") else strings.text("Web challenge / Cloudflare"))
                        }
                        Text(
                            strings.text("Uses the source's exact User-Agent and imports only cookies valid for its domain and path."),
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
                                        val imported = CookieFileParser.parse(content, source.baseUrl)
                                        require(imported.isNotEmpty()) {
                                            strings.text("No valid cookies for {0} were found.", defaultCookieDomain(source.baseUrl).trimStart('.'))
                                        }
                                        imported.forEach { cookie -> callbacks.setSourceCookie(source.id, cookie) }
                                        imported.size
                                    }.onSuccess { importedCount ->
                                        if (importedCount != null) {
                                            cookieImportMessage = strings.text("Imported {0} cookie(s).", importedCount)
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
                        if (source.cookies.isEmpty()) {
                            Text(strings.text("No cookies saved"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(
                    source.cookies,
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
                                    callbacks.deleteSourceCookie(source.id, cookie.name, cookie.domain)
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
                                        callbacks.setSourceCookie(
                                            source.id,
                                            SourceCookie(cookieName, cookieValue, cookieDomain),
                                        )
                                        cookieName = ""
                                        cookieValue = ""
                                    }
                                },
                                enabled = cookieName.isNotBlank() && cookieValue.isNotEmpty() && cookieDomain.isNotBlank(),
                            ) { Text(strings.text("Add cookie")) }
                            if (source.cookies.isNotEmpty()) {
                                TextButton(
                                    onClick = { scope.launch { callbacks.clearSourceCookies(source.id) } },
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
            onImport = { cookies ->
                scope.launch {
                    challengeBusy = true
                    runCatching {
                        cookies.forEach { cookie -> callbacks.setSourceCookie(source.id, cookie) }
                    }.onSuccess {
                        challengeMessage = strings.text("Imported {0} cookie(s).", cookies.size)
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
                challengeMessage = strings.text("Web challenge cancelled. No browser cookies were imported.")
            },
        )
    }
}

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
                        } else {
                            IconButton(onClick = { onRemoveRepository(repository.id) }) {
                                Icon(Icons.Outlined.Delete, strings.delete)
                            }
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
            Button(onClick = { requestBrowse() }) { Text(strings.search) }
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
