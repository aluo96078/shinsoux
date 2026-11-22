package dev.shinsou.kmp.ui.browse

import dev.shinsou.kmp.ui.core.LocalAppUnlocked
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.logging.Log
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
import dev.shinsou.kmp.sync.v3.SyncReviews
import dev.shinsou.kmp.sync.v3.SyncSharing
import dev.shinsou.kmp.sync.v3.SyncWorks
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
import dev.shinsou.kmp.plugin.contracts.ContentAcquisition
import dev.shinsou.kmp.plugin.contracts.ExtensionLibraryBinding
import dev.shinsou.kmp.plugin.contracts.ExtensionManagement
import dev.shinsou.kmp.plugin.contracts.ExtensionTrust
import dev.shinsou.kmp.plugin.contracts.SourceAuth
import dev.shinsou.kmp.plugin.contracts.SourceSettings
import dev.shinsou.kmp.plugin.contracts.SourceWebSession
import dev.shinsou.kmp.plugin.contracts.BrowseExtension
import dev.shinsou.kmp.plugin.contracts.BrowseFilter
import dev.shinsou.kmp.plugin.contracts.BrowseManga
import dev.shinsou.kmp.plugin.contracts.BrowsePage
import dev.shinsou.kmp.plugin.contracts.BrowseSource
import dev.shinsou.kmp.plugin.runtime.ExtensionArtifactNotReviewedException
import dev.shinsou.kmp.domain.model.sourceFailureMarker
import dev.shinsou.kmp.plugin.contracts.toBrowseFilterV2
import dev.shinsou.kmp.plugin.contracts.ExtensionPublicationCatalog
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.plugin.contracts.SourceCatalog
import dev.shinsou.kmp.plugin.contracts.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.browse.ExtensionFavoriteDestination
import dev.shinsou.kmp.ui.browse.extensionFavoriteDestination
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.ui.challenge.SourceWebChallengeDialog
import dev.shinsou.kmp.ui.components.CoverImage
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.components.SearchField
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.authoredMessage
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.localizedSourceFailure
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public enum class BrowseSection {
    Sources,
    Extensions,
    Migration,
}

internal enum class SourceCatalogueMode {
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
internal const val GLOBAL_SEARCH_VISIBLE_RESULTS: Int = 10

/** How far a back-swipe has dragged the topmost Browse overlay, and the shell's Back requests. */
public class BrowseBackNavigation(
    /** One-shot: a change asks Browse to go back once. */
    internal val systemBackRequest: Long = 0L,
    internal val backGestureProgress: Float = 0f,
)

/** The holders of one Browse screen. */
private class BrowseHolders(
    val screen: BrowseScreenState,
    val catalogue: SourceCatalogueState,
    val extensions: ExtensionManagementState,
)

@Composable
private fun <B> rememberBrowseHolders(
    callbacks: B,
    workDispatcher: CoroutineDispatcher,
    initialSection: BrowseSection,
    systemBackRequest: Long,
    snackbar: SnackbarHostState,
): BrowseHolders where
    B : SourceCatalog,
    B : ExtensionPublicationCatalog,
    B : SourceWebSession,
    B : ExtensionManagement,
    B : ExtensionTrust {
    val scope = rememberCoroutineScope()
    val strings = rememberUpdatedState(LocalShinsouStrings.current)
    return remember(scope) {
        val work = ScreenWork(
            scope = scope,
            workDispatcher = workDispatcher,
            awaitFrame = { withFrameNanos { } },
            strings = { strings.value },
        )
        val screen = BrowseScreenState(initialSection, systemBackRequest)
        val catalogue = SourceCatalogueState(
            backend = BridgeCatalogueBackend(callbacks, callbacks, callbacks),
            work = work,
            activeSourceIdentity = { screen.overlays.activeSource?.identityKey },
        )
        screen.onCatalogueClosed = {
            catalogue.cancelLoad()
            catalogue.clearRecovery()
        }
        BrowseHolders(screen, catalogue, ExtensionManagementState(callbacks, callbacks, work) { snackbar.showSnackbar(it) })
    }
}

/**
 * The Browse section. [callbacks] is the extension host, by the narrow interfaces the pages use, and
 * [sync] what the publication page uses of sync (0008 §7-5: no umbrella crosses into this module).
 */
@Composable
public fun <B, S> BrowseScreen(
    callbacks: B,
    filters: BrowseSourceFilters,
    host: BrowseHost,
    reader: ExtensionReaderHost,
    sync: S?,
    modifier: Modifier = Modifier,
    navigation: BrowseBackNavigation = BrowseBackNavigation(),
    initialSection: BrowseSection = BrowseSection.Sources,
) where
    B : SourceCatalog,
    B : SourceSettings,
    B : ExtensionPublicationCatalog,
    B : ExtensionLibraryBinding,
    B : ContentAcquisition,
    B : SourceAuth,
    B : SourceWebSession,
    B : ExtensionManagement,
    B : ExtensionTrust,
    S : SyncWorks,
    S : SyncReviews,
    S : SyncSharing {
    val snapshot by callbacks.state.collectAsState()
    val operationSnackbar = remember { SnackbarHostState() }
    val holders = rememberBrowseHolders(callbacks, reader.workDispatcher, initialSection, navigation.systemBackRequest, operationSnackbar)
    val state = holders.screen
    val extensions = holders.extensions
    state.host = host
    val currentHost = rememberUpdatedState(host)

    DisposableEffect(Unit) {
        onDispose { currentHost.value.onReaderVisibilityChanged(false) }
    }
    LaunchedEffect(snapshot.extensions.map { Triple(it.id, it.version, it.installed) }, extensions.operations.active) {
        extensions.findPendingGrant(snapshot.extensions)
    }
    LaunchedEffect(state.overlays.hasOverlay) { currentHost.value.onBackAvailabilityChanged(state.overlays.hasOverlay) }
    DisposableEffect(Unit) {
        onDispose {
            holders.catalogue.dispose()
            currentHost.value.onBackAvailabilityChanged(false)
        }
    }
    LaunchedEffect(navigation.systemBackRequest) { state.onSystemBack(navigation.systemBackRequest) }

    // ShinsouComposition.start owns the initial extension refresh on every platform. Re-entering
    // this composition must not repeat that network/package scan; the toolbar remains the retry path.
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BrowseHeader(state, holders, snapshot, callbacks)
            when (state.section) {
                BrowseSection.Sources -> BrowseSourcesPage(holders, snapshot, filters, callbacks, host)
                BrowseSection.Extensions -> BrowseExtensionsPage(state, extensions, snapshot, filters, sync)
                BrowseSection.Migration -> MigrationPane(
                    migrations = snapshot.migrations.filter { it.title.contains(state.query, ignoreCase = true) },
                    sources = snapshot.sources.filter { it.enabled && filters.admits(it.isNsfw, it.language) },
                    catalog = callbacks,
                    publications = callbacks,
                    onMigrate = { candidate, target ->
                        extensions.launch { callbacks.migrateManga(candidate.mangaId, target) }
                    },
                )
            }
        }
        BrowseOverlays(holders, snapshot, filters, callbacks, host, reader, sync, navigation.backGestureProgress)
        if (!state.overlays.activeV2Reader) {
            SnackbarHost(hostState = operationSnackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp))
        }
        holders.catalogue.challenge?.let { challenge ->
            SourceWebChallengeDialog(
                request = challenge.browserRequest,
                onImport = { capture -> holders.catalogue.importWebChallenge(challenge, capture) },
                onDismiss = { holders.catalogue.dismissWebChallenge(challenge) },
            )
        }
    }
    ExtensionDialogs(extensions)
}

/** The title, the page chips and the search field. */
@Composable
private fun BrowseHeader(
    state: BrowseScreenState,
    holders: BrowseHolders,
    snapshot: dev.shinsou.kmp.plugin.contracts.BrowseSnapshot,
    callbacks: SourceCatalog,
) {
    val strings = LocalShinsouStrings.current
    fun BrowseSection.label(): String = when (this) {
        BrowseSection.Sources -> strings.sources
        BrowseSection.Extensions -> strings.extensions
        BrowseSection.Migration -> strings.migration
    }
    ScreenHeader(
        title = strings.browse,
        subtitle = state.section.label(),
        actions = {
            if (state.section == BrowseSection.Sources) {
                IconButton(
                    onClick = {
                        holders.catalogue.cancelLoad()
                        state.openGlobalSearch()
                    },
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = strings.text(StringKey.SEARCH_ALL_SOURCES))
                }
            }
            IconButton(
                onClick = { holders.extensions.launch { callbacks.refresh() } },
                enabled = !snapshot.isRefreshing && holders.extensions.operations.active == null,
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
                selected = state.section == option,
                onClick = { state.selectSection(option) },
                label = { Text(option.label()) },
            )
        }
    }
    SearchField(
        value = state.query,
        onValueChange = { state.query = it },
        placeholder = strings.search,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
    )
}

@Composable
private fun <B> BrowseSourcesPage(
    holders: BrowseHolders,
    snapshot: dev.shinsou.kmp.plugin.contracts.BrowseSnapshot,
    filters: BrowseSourceFilters,
    callbacks: B,
    host: BrowseHost,
) where
    B : SourceSettings,
    B : SourceAuth,
    B : SourceWebSession {
    val state = holders.screen
    val catalogue = holders.catalogue
    SourcesPane(
        repositories = snapshot.repositories,
        sources = snapshot.sources.filter { filters.admits(it.isNsfw, it.language) }
            .filter { it.name.contains(state.query, ignoreCase = true) },
        onToggle = { source, enabled ->
            holders.extensions.launch {
                source.sourceKey?.let { callbacks.setSourceEnabledV2(it, enabled) }
                    ?: callbacks.setSourceEnabled(source.id, enabled)
            }
        },
        pinnedSourceIds = filters.pinnedSourceIds,
        pinnedSourceKeys = filters.pinnedSourceKeys,
        onSourcePinnedChange = host.onSourcePinnedChange,
        onSourcePinnedKeyChange = host.onSourcePinnedKeyChange,
        settings = callbacks,
        auth = callbacks,
        webSession = callbacks,
        onImportDocument = host.onImportDocument,
        onOpen = { source ->
            catalogue.clearRecovery()
            state.openSource(source)
            catalogue.reset()
            catalogue.browse(source, "", SourceCatalogueMode.Popular, filters = null)
        },
    )
}

@Composable
private fun BrowseExtensionsPage(
    state: BrowseScreenState,
    extensions: ExtensionManagementState,
    snapshot: dev.shinsou.kmp.plugin.contracts.BrowseSnapshot,
    filters: BrowseSourceFilters,
    sync: dev.shinsou.kmp.sync.v3.SyncStatus?,
) {
    ExtensionsPane(
        operation = extensions.operations.active,
        repositories = snapshot.repositories,
        selectedRepositoryId = snapshot.selectedRepositoryId,
        extensions = snapshot.extensions.filter {
            filters.admits(it.isNsfw, it.language) && it.name.contains(state.query, ignoreCase = true)
        },
        onAddRepository = extensions::addRepository,
        onOpenPackageSecurity = extensions::openPackageSecurity,
        onUnblock = extensions::unblock,
        onBlock = extensions::block,
        repositorySuggestions = snapshot.repositorySuggestions,
        canRemoveFromAccount = sync?.state?.collectAsState()?.value?.signedIn == true,
        onRemoveRepository = extensions::removeRepository,
        onDismissRepositorySuggestion = extensions::dismissRepositorySuggestion,
        onSelectRepository = extensions::selectRepository,
        onInstall = extensions::install,
        onUninstall = extensions::uninstall,
        onPermissions = extensions::editPermissions,
        onTrust = extensions::setTrusted,
        repositoryBusy = extensions.repositoryWork > 0,
    )
}

/** The global search, a source's catalogue and an extension publication, over the page. */
@Composable
private fun <B, S> BrowseOverlays(
    holders: BrowseHolders,
    snapshot: dev.shinsou.kmp.plugin.contracts.BrowseSnapshot,
    filters: BrowseSourceFilters,
    callbacks: B,
    host: BrowseHost,
    reader: ExtensionReaderHost,
    sync: S?,
    backGestureProgress: Float,
) where
    B : SourceCatalog,
    B : ExtensionPublicationCatalog,
    B : ExtensionLibraryBinding,
    B : ContentAcquisition,
    B : SourceSettings,
    B : SourceAuth,
    B : SourceWebSession,
    S : SyncWorks,
    S : SyncReviews,
    S : SyncSharing {
    val state = holders.screen
    val overlays = state.overlays
    val sourceRefreshInvalidations by callbacks.sourceRefreshInvalidations.collectAsState()
    val dragged = Modifier.fillMaxSize().graphicsLayer {
        translationX = size.width * backGestureProgress.coerceIn(0f, 1f)
    }
    if (overlays.globalSearchVisible) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = dragged) {
            GlobalSearchScreen(
                catalog = callbacks,
                publications = callbacks,
                sources = snapshot.sources.filter { it.enabled && filters.admits(it.isNsfw, it.language) },
                onBack = state::closeGlobalSearch,
                onOpenManga = state::openManga,
            )
        }
    }
    overlays.activeSource?.let { source ->
        // The dialog reads the live source row: cookies and the saved account change while it is open.
        val live = snapshot.sources.firstOrNull { it.identityKey == source.identityKey } ?: source
        if (state.loginSourceIdentity == source.identityKey) {
            SourceSettingsDialog(
                source = live,
                settings = callbacks,
                auth = callbacks,
                webSession = callbacks,
                onImportDocument = host.onImportDocument,
                onDismiss = { state.loginSourceIdentity = null },
            )
        }
        Surface(color = MaterialTheme.colorScheme.background, modifier = dragged) {
            SourceCatalogueOverlay(state, holders.catalogue, source, live.signedIn, source.sourceKey?.let(sourceRefreshInvalidations::get) ?: 0L)
        }
    }
    overlays.activeV2Publication?.let { publication ->
        BrowsePublicationOverlay(
            state = state,
            publication = publication,
            source = snapshot.sources.firstOrNull { it.sourceKey == publication.sourceKey },
            callbacks = callbacks,
            reader = reader,
            sync = sync,
            refreshGeneration = publication.sourceKey?.let(sourceRefreshInvalidations::get) ?: 0L,
            modifier = dragged,
        )
    }
}

@Composable
private fun SourceCatalogueOverlay(
    state: BrowseScreenState,
    catalogue: SourceCatalogueState,
    source: BrowseSource,
    signedIn: Boolean,
    refreshGeneration: Long,
) {
    SourceCatalogueScreen(
        source = source,
        refreshGeneration = refreshGeneration,
        page = catalogue.page,
        loading = catalogue.loading,
        loadingMore = catalogue.loadingMore,
        error = catalogue.error,
        challengeAvailable = catalogue.challengeAvailable,
        challengePreparing = catalogue.challengePreparing,
        onBack = state::closeActiveSource,
        onWebChallenge = catalogue::openWebChallenge,
        signedIn = signedIn,
        onLogin = if (source.canSignIn) {
            { state.loginSourceIdentity = source.identityKey }
        } else {
            null
        },
        onBrowse = { search, mode, filters ->
            state.overlays.activeSource?.let { catalogue.browse(it, search, mode, filters) }
        },
        onLoadMore = { search, mode, filters ->
            state.overlays.activeSource?.let { catalogue.loadMore(it, search, mode, filters) }
        },
        onOpenManga = state::openManga,
    )
}

@Composable
private fun <B, S> BrowsePublicationOverlay(
    state: BrowseScreenState,
    publication: BrowseManga,
    source: BrowseSource?,
    callbacks: B,
    reader: ExtensionReaderHost,
    sync: S?,
    refreshGeneration: Long,
    modifier: Modifier,
) where
    B : SourceCatalog,
    B : ExtensionPublicationCatalog,
    B : ExtensionLibraryBinding,
    B : ContentAcquisition,
    S : SyncWorks,
    S : SyncReviews,
    S : SyncSharing {
    // Sources without an explicit account-favorite capability use the app-owned library. The two
    // mutation boundaries stay disjoint so a local bookmark cannot leak into a website account.
    val favoriteDestination = extensionFavoriteDestination(source?.supportsFavorites)
    val localLibrary = favoriteDestination == ExtensionFavoriteDestination.LOCAL_LIBRARY
    BoxWithConstraints(modifier) {
        // Keep the catalogue mounted on desktop, exactly like the legacy manga detail pane. Mobile
        // still receives a full-screen destination so the chapter list can scroll naturally
        // without squeezing the source grid.
        val detailModifier = if (maxWidth >= 900.dp && !state.overlays.activeV2Reader) {
            Modifier.fillMaxHeight().fillMaxWidth(0.68f).align(Alignment.CenterEnd)
        } else {
            Modifier.fillMaxSize()
        }
        Surface(color = MaterialTheme.colorScheme.background, modifier = detailModifier) {
            ExtensionV2PublicationPane(
                services = ExtensionPublicationServices(callbacks, callbacks, callbacks, callbacks),
                item = publication,
                favorites = PublicationFavorites(
                    supportsFavorite = true,
                    destination = favoriteDestination,
                    localLibrary = localLibrary,
                    localLibraryFavorite = localLibrary && reader.library.isFavorite(publication),
                ),
                reader = reader,
                navigation = PublicationPaneNavigation(
                    refreshGeneration = refreshGeneration,
                    readerBackRequest = state.readerBackRequest,
                    onBack = state::closePublication,
                    reader = PublicationReaderEvents(
                        onVisibilityChanged = state::setReaderVisible,
                        isShown = { state.overlays.activeV2Publication?.identityKey == publication.identityKey },
                    ),
                ),
                sync = sync,
            )
        }
    }
}

/** The Extensions page's dialogs: repository trust, package security and permission reviews. */
@Composable
private fun ExtensionDialogs(extensions: ExtensionManagementState) {
    // Shown once the app is unlocked (LocalAppUnlocked): each of them grants or changes something.
    if (!LocalAppUnlocked.current) return
    extensions.repositoryTrust?.let { prompt ->
        RepositoryTrustDialog(
            prompt = prompt,
            onTrust = { fingerprint -> extensions.trustRepository(prompt, fingerprint) },
            onDismiss = extensions::dismissRepositoryTrust,
        )
    }
    extensions.packageSecurity?.let { current ->
        PackageSecurityDialog(
            snapshot = current,
            enabled = extensions.operations.active == null,
            onStrictMode = { strict -> extensions.changePackageSecurity { setStrictPublisherTrust(strict) } },
            onPublisherBlocked = { id, blocked -> extensions.changePackageSecurity { setPublisherBlocked(id, blocked) } },
            onConfirmFingerprint = { id, fingerprint ->
                extensions.changePackageSecurity { confirmPublisherFingerprint(id, fingerprint) }
            },
            onUnblock = { key -> extensions.changePackageSecurity { unblockPackageEntry(key) } },
            onDeveloperMode = { on -> extensions.changePackageSecurity { setPluginDeveloperMode(on) } },
            onDeveloperHosts = { hosts -> extensions.changePackageSecurity { setPluginDeveloperHosts(hosts) } },
            onDismiss = extensions::dismissPackageSecurity,
        )
    }
    extensions.pendingGrant?.let { pending ->
        var details by remember(pending) { mutableStateOf<dev.shinsou.kmp.plugin.contracts.PluginPermissionDetails?>(null) }
        LaunchedEffect(pending) { details = extensions.permissionDetails(pending) }
        PluginPermissionReviewDialog(
            review = pending.review,
            details = details,
            onConfirm = { selection -> extensions.approveGrant(pending, selection) },
            onDismiss = extensions::dismissGrant,
        )
    }
    extensions.pendingUpdate?.let { pending ->
        PluginPermissionReviewDialog(
            review = pending.preview.review,
            details = pending.preview.details,
            onConfirm = { selection -> extensions.approveUpdate(pending, selection) },
            onDismiss = extensions::dismissUpdate,
        )
    }
    extensions.editedPermissions?.let { edited ->
        PluginPermissionReviewDialog(
            review = edited.permissions.review,
            details = edited.permissions.details,
            current = edited.permissions.granted,
            onConfirm = { selection -> extensions.narrowPermissions(edited, selection) },
            onDismiss = extensions::dismissPermissions,
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
    if (generateSequence(this) { it.cause }.any { it is dev.shinsou.kmp.plugin.trust.RepositoryTrustException.UntrustedRepository }) {
        return strings.text(StringKey.NOW_SIGNED_BY_ITS_PUBLISHER_CONFIRM_THE)
    }
    if (generateSequence(this) { it.cause }.any { it is ExtensionArtifactNotReviewedException }) {
        return strings.text(StringKey.THIS_PLUGIN_CAN_T_RUN_IT_ISN)
    }
    if (generateSequence(this) { it.cause }.any {
            it.message in setOf(
                "Installed plugin artifact or requested permissions changed after review",
                "The exact displayed plugin review is no longer pending",
                "Reviewed plugin identity does not match the requested extension",
            )
        }
    ) return strings.text(StringKey.THIS_EXTENSION_CHANGED_REVIEW_ITS_PERMISSIONS_AGAIN)
    val diagnostic = diagnosticMessage()
    strings.authoredMessage(diagnostic)?.let { return it }
    // Repository/extension failures carry a deliberately bounded, non-secret message (status,
    // URL policy, metadata field, etc.). Hiding it behind one generic sentence made `index.json`/legacy
    // `repo.json` mismatches impossible to diagnose from the UI.
    return diagnostic
        .filterNot(Char::isISOControl)
        .take(MAX_DIAGNOSTIC_MESSAGE_CHARS)
        .ifBlank { strings.text(StringKey.THE_OPERATION_COULD_NOT_BE_COMPLETED) }
}

private const val MAX_DIAGNOSTIC_MESSAGE_CHARS: Int = 512

@Composable
private fun GlobalSearchScreen(
    catalog: SourceCatalog,
    publications: ExtensionPublicationCatalog,
    sources: List<BrowseSource>,
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
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
                searchAcrossSources(catalog, publications, requestedSources, submittedQuery, strings).flowOn(workDispatcher).collect { update ->
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
            title = strings.text(StringKey.GLOBAL_SEARCH),
            subtitle = strings.text(StringKey.X_ENABLED_SOURCES, sources.size),
            leading = {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.BACK)) }
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
                placeholder = strings.text(StringKey.SEARCH_ALL_SOURCES),
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
                title = strings.text(StringKey.NO_ENABLED_SOURCES),
                message = strings.text(StringKey.ENABLE_AT_LEAST_ONE_SOURCE_BEFORE_SEARCHING),
                icon = { Icon(Icons.Outlined.TravelExplore, null, Modifier.size(30.dp)) },
            )
            searching && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ShinsouLoadingIndicator()
            }
            !hasSearched -> EmptyState(
                title = strings.text(StringKey.SEARCH_ACROSS_SOURCES),
                message = strings.text(StringKey.RESULTS_ARE_GROUPED_BY_SOURCE_SO_YOU),
                icon = { Icon(Icons.Outlined.Search, null, Modifier.size(30.dp)) },
            )
            results.isEmpty() -> EmptyState(
                title = strings.noMatches,
                message = strings.text(StringKey.TRY_A_DIFFERENT_TITLE_OR_ENABLE_MORE),
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
                    strings.text(StringKey.X_X_RESULTS, result.source.language.uppercase(), result.totalCount),
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
                strings.text(StringKey.NO_MATCHES_FROM_THIS_SOURCE),
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
