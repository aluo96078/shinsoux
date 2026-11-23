package dev.shinsou.kmp.ui.browse

import dev.shinsou.kmp.ui.browse.extensionUnitIdentity
import dev.shinsou.kmp.ui.core.LocalAppUnlocked
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.concurrent.runCatchingCancellable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import dev.shinsou.kmp.sync.v3.SyncReviews
import dev.shinsou.kmp.sync.v3.SyncSharing
import dev.shinsou.kmp.sync.v3.SyncWorks
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.logging.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.plugin.contracts.ContentAcquisition
import dev.shinsou.kmp.plugin.contracts.ExtensionLibraryBinding
import dev.shinsou.kmp.plugin.contracts.ExtensionPublicationCatalog
import dev.shinsou.kmp.plugin.contracts.SourceCatalog
import dev.shinsou.kmp.plugin.contracts.BrowseManga
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.ui.browse.ExtensionFavoriteDestination
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.browse.mutateExtensionFavorite
import dev.shinsou.kmp.ui.reader.ContentFeatures
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.extensionPublicationKey
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerException
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationPageV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitPageProgressV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.overKnownDetails
import dev.shinsou.kmp.plugin.v2.withoutScriptObjectText
import dev.shinsou.kmp.ui.components.CoverImage
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.core.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.ui.translation.LocalMangaTranslation
import dev.shinsou.kmp.ui.reader.ExtensionNovelReaderShell
import dev.shinsou.kmp.ui.reader.PrefetchProgress
import dev.shinsou.kmp.ui.reader.PrefetchSetupDialog
import dev.shinsou.kmp.ui.reader.extensionContinueReadingDetail
import dev.shinsou.kmp.ui.sync.ReviewsEntry
import dev.shinsou.kmp.ui.sync.ShareWithUserDialog

// R4-4 (docs/architecture/0006): split out of BrowseScreen.kt without changing it.

/**
 * Exact-keyed native extension path; host-issued selections remain the only content authority.
 * [sync] is what the page uses of sync (the work hashes, reviews and sharing; 0008 §7-5).
 */
@Composable
public fun <S> ExtensionV2PublicationPane(
    services: ExtensionPublicationServices,
    item: BrowseManga,
    favorites: PublicationFavorites,
    reader: ExtensionReaderHost,
    navigation: PublicationPaneNavigation,
    sync: S?,
) where S : SyncWorks, S : SyncReviews, S : SyncSharing {
    val scope = rememberCoroutineScope()
    val strings = rememberUpdatedState(LocalShinsouStrings.current)
    val state = remember(item.identityKey) {
        ExtensionPublicationState(
            item = item,
            services = services,
            initialFavorites = favorites,
            reader = reader,
            work = ScreenWork(scope, reader.workDispatcher, awaitFrame = { withFrameNanos { } }, strings = { strings.value }),
        )
    }
    state.syncFavorites(favorites)
    state.onReaderVisibilityChanged = navigation.reader.onVisibilityChanged
    state.onReaderUnitChanged = navigation.reader.onUnitChanged
    state.isShown = navigation.reader.isShown
    state.onReaderOpening = navigation.reader.onOpening
    DisposableEffect(state) {
        // Another title replaced this one (a wide layout keeps the library clickable): a chapter
        // it was still opening no longer opens the reader over the new title.
        onDispose {
            state.onReaderVisibilityChanged = {}
            state.onReaderUnitChanged = {}
            state.isShown = { false }
            state.onReaderOpening = {}
        }
    }
    // Visibility is opened explicitly when materialization returns and closed explicitly by the
    // reader/back handlers. Do not emit a transient `false` from an effect keyed only by a
    // nullable session: during the first materialization recomposition that stale callback can
    // race the `true` notification and put desktop back into the split catalogue layout.
    val openedReader = state.readerSession
    LaunchedEffect(openedReader) {
        if (openedReader != null) navigation.reader.onVisibilityChanged(true)
    }
    // Before the reader branch: a chapter opened while the list loads (a restored one, as soon as
    // it is listed) must not cancel the rest of the list, which the reader's next chapter needs.
    PublicationLoadAndReopenEffects(state, navigation, scope)
    val features = reader.contentFeatures
    if (openedReader != null && features != null) {
        PublicationReader(state, services, reader, features, openedReader, navigation.readerBackRequest)
        return
    }
    PublicationPageUnderCover(state, services, reader, navigation, sync)
}

/** The publication page, with the reader's loading screen over it while a chapter opens for it. */
@Composable
private fun <S> PublicationPageUnderCover(
    state: ExtensionPublicationState,
    services: ExtensionPublicationServices,
    reader: ExtensionReaderHost,
    navigation: PublicationPaneNavigation,
    sync: S?,
) where S : SyncWorks, S : SyncReviews, S : SyncSharing {
    Box(Modifier.fillMaxSize()) {
        PublicationPage(state, services, reader, navigation, sync)
        // A chapter to choose a representation for is asked on the page itself.
        val cover = state.readerCover?.takeIf { state.pendingRepresentation == null } ?: return@Box
        ReaderOpeningCover(
            cover = cover,
            title = state.publicationPage?.publication?.title ?: state.item.title,
            unitTitle = state.units.firstOrNull { extensionUnitIdentity(it.unit.remoteId) == cover.unit }?.unit?.title,
            systemBackRequest = navigation.readerBackRequest,
            onLeave = state::leaveReaderCover,
            onRetry = state::retryReaderCover,
        )
    }
}

/** The reader of a chapter opened from the publication page. */
@Composable
private fun PublicationReader(
    state: ExtensionPublicationState,
    services: ExtensionPublicationServices,
    reader: ExtensionReaderHost,
    features: ContentFeatures,
    session: TypedReaderContentSession,
    readerBackRequest: Long,
) {
    val item = state.item
    val units = state.units
    val sessionScope = session.content.navigation.scope
    val title = state.publicationPage?.publication?.title ?: item.title
    val unitTitle = units.firstOrNull { it.unit.remoteId == state.readerUnitId }?.unit?.title ?: item.title
    val source = services.catalog.state.collectAsState().value.sources.firstOrNull { it.sourceKey == state.sourceKey }
    ExtensionNovelReaderShell(
        title = title,
        currentUnitTitle = unitTitle,
        sourceName = source?.name,
        // "all", "multi" and sources that say nothing leave it to the pages
        workLanguage = source?.language
            ?.takeIf { dev.shinsou.kmp.translation.OfflineModelCatalog.language(it) != null }.orEmpty(),
        units = units,
        activeUnitIndex = state.readerUnitId?.let { id -> units.indexOfFirst { it.unit.remoteId == id } } ?: -1,
        busy = state.busyUnitId != null,
        onBack = state::closeReader,
        onOpenUnit = { selection -> state.materialize(selection, openReader = true) },
        session = session,
        features = features,
        copyText = reader.copyText,
        readerSettings = reader.readerSettings,
        onReaderSettingsChange = reader.onReaderSettingsChange,
        onCompleted = { state.onReaderCompleted(session) },
        onReaderProgress = { position ->
            if (state.readerSession === session && position.locator.scope == sessionScope) {
                reader.onReaderProgress.record(title, unitTitle, position.locator, position.pageIndex, position.pageCount)
            }
        },
        onReaderProgressFlushed = reader.onReaderProgressFlushed,
        volumeKeyRouter = reader.volumeKeyRouter,
        systemBackRequest = readerBackRequest,
    )
}

/** The publication's details and chapter list, its dialogs, and the work that follows them. */
@Composable
private fun <S> PublicationPage(
    state: ExtensionPublicationState,
    services: ExtensionPublicationServices,
    reader: ExtensionReaderHost,
    navigation: PublicationPaneNavigation,
    sync: S?,
) where S : SyncWorks, S : SyncReviews, S : SyncSharing {
    val scope = rememberCoroutineScope()
    // Downloads run in the app-scoped queue so leaving this title does not cancel them.
    val extensionDownloads by services.acquisition.extensionDownloadsV2.collectAsState()
    val publicationDownloads = remember(extensionDownloads, state.sourceKey, state.remotePublicationId) {
        extensionDownloads.filter { it.sourceKey == state.sourceKey && it.remotePublicationId == state.remotePublicationId }
    }
    val queuedUnitIds = remember(publicationDownloads) {
        publicationDownloads
            .filter { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING }
            .mapTo(hashSetOf()) { it.remoteUnitId }
    }
    LaunchedEffect(publicationDownloads) {
        state.markDownloaded(publicationDownloads.filter { it.state == DownloadState.DOWNLOADED }.mapTo(hashSetOf()) { it.remoteUnitId })
    }

    // Translating ahead of reading: the same app-wide queue idea as downloads.
    val translation = reader.contentFeatures?.translation
    val prefetchContext = PrefetchContext(LocalMangaTranslation.current, dev.shinsou.kmp.ui.i18n.LocalAppLanguage.current)
    val prefetchEntries by (translation?.prefetch?.entries ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    val publicationPrefetch = remember(prefetchEntries, state.prefetchGroup) { prefetchEntries.filter { it.group == state.prefetchGroup } }
    val prefetchByUnit = remember(publicationPrefetch) { publicationPrefetch.associateBy { it.unit } }
    // Translating ahead fetches a chapter the way a download does: it is on the device from then on.
    val prefetchFetched = remember(publicationPrefetch) { publicationPrefetch.filter { it.fetched }.mapTo(hashSetOf()) { it.unit } }
    LaunchedEffect(prefetchFetched) { state.markDownloaded(prefetchFetched) }
    val finishedPrefetch = remember(publicationPrefetch) {
        publicationPrefetch.count { it.state == dev.shinsou.kmp.translation.PrefetchState.DONE }
    }
    LaunchedEffect(translation, state.units.size, finishedPrefetch) { translation?.let { state.loadTranslatedUnits(it) } }

    // Nothing is translated before the user has seen what into, from what, and through which service.
    val pendingPrefetch = state.prefetchSetup
    if (pendingPrefetch != null && translation != null) {
        PrefetchSetupDialog(
            translation = translation,
            workKey = state.workKey,
            chapterCount = pendingPrefetch.size,
            onConfirm = { from, to ->
                state.prefetchSetup = null
                state.startPrefetch(pendingPrefetch, dev.shinsou.kmp.translation.PrefetchLanguages(to = to, from = from), prefetchContext)
            },
            onDismiss = { state.prefetchSetup = null },
        )
    }

    val chapterPane = publicationChapterPaneModel(state, queuedUnitIds, prefetchByUnit, translation != null)
    val visibleChapterUnits = rememberVisibleChapterUnits(chapterPane)
    val info = rememberPublicationInfo(state, sync, reader, navigation, translation, queuedUnitIds, publicationPrefetch, scope)

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 900.dp) {
                Row(Modifier.fillMaxSize()) {
                    ExtensionV2PublicationInfoPane(
                        info = info,
                        showClose = true,
                        scrollable = true,
                        modifier = Modifier.width(330.dp).fillMaxHeight(),
                    )
                    VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                    ExtensionV2ChapterPane(
                        model = chapterPane,
                        visibleUnits = visibleChapterUnits,
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
                            info = info,
                            showClose = false,
                            scrollable = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // Each chapter is an item of its own: a work's hundreds of chapters are laid
                    // out as they scroll into view, not all at once when the list first shows.
                    extensionV2ChapterItems(chapterPane, visibleChapterUnits, horizontalPadding = 14.dp)
                }
            }
        }
    }

    PendingRepresentationDialog(state)

    val shareWorkHash = info.shareWorkHash
    if (state.shareWithUserVisible && sync != null && shareWorkHash != null) {
        ShareWithUserDialog(
            sync = sync,
            workHash = shareWorkHash,
            title = state.displayPublication.title,
            onDismiss = { state.shareWithUserVisible = false },
        )
    }
}

/** The chapter list's state and callbacks, built from the publication [state] and the work around it. */
private fun publicationChapterPaneModel(
    state: ExtensionPublicationState,
    queuedUnitIds: Set<String>,
    prefetchByUnit: Map<String, dev.shinsou.kmp.translation.PrefetchEntry>,
    translationAvailable: Boolean,
): ExtensionV2ChapterPaneModel {
    return ExtensionV2ChapterPaneModel(
        units = state.units,
        selectedUnitIds = state.selectedUnitIds,
        readUnitIds = state.readUnitIds,
        inProgressUnits = state.inProgressUnits,
        bookmarkedUnitIds = state.bookmarkedUnitIds,
        downloadedUnitIds = state.downloadedUnitIds,
        chapterFilter = state.chapterFilter,
        reverseWebsiteOrder = state.reverseWebsiteOrder,
        loading = state.loading,
        loadingMore = state.loadingMore,
        loadError = state.loadError,
        hasNextPage = state.hasNextPage,
        operationMessage = state.operationMessage,
        operationFailed = state.operationFailed,
        busyUnitId = state.busyUnitId,
        queuedUnitIds = queuedUnitIds,
        openingReaderUnitId = state.openingReaderUnitId,
        onRetry = state::retry,
        onLoadMore = state::loadMore,
        onReadUnit = { state.materialize(it, openReader = true) },
        onDownloadUnits = { state.downloadUnits(it, queuedUnitIds) },
        onTranslateUnits = if (translationAvailable) ({ state.translateUnits(it) }) else null,
        translatedUnitIds = state.storedTranslatedUnitIds,
        prefetchByUnit = prefetchByUnit,
        onSelectionChange = { state.selectedUnitIds = it },
        onReadStateChange = state::setReadState,
        onBookmarkStateChange = state::setBookmarked,
        onDeleteDownloads = state::clearDownloadMarks,
        onFilterChange = { state.chapterFilter = it },
        onToggleWebsiteOrder = { state.reverseWebsiteOrder = !state.reverseWebsiteOrder },
    )
}

/** Asks which content format to open a chapter in when the source offers several. */
@Composable
private fun PendingRepresentationDialog(state: ExtensionPublicationState) {
    val strings = LocalShinsouStrings.current
    // Behind the app lock: a restored chapter can ask for its format before the user unlocks.
    state.pendingRepresentation?.takeIf { LocalAppUnlocked.current }?.let { pending ->
        AlertDialog(
            onDismissRequest = { state.pendingRepresentation = null },
            title = { Text(strings.text(StringKey.CHOOSE_CONTENT_FORMAT)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pending.representationIds.forEach { representationId ->
                        OutlinedButton(
                            onClick = { state.chooseRepresentation(pending, representationId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(strings.text(StringKey.FORMAT_X, representationId))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { state.pendingRepresentation = null }) {
                    Text(strings.cancel)
                }
            },
        )
    }
}

/** Everything the details pane shows and does. */
private data class PublicationInfo(
    val publication: RemotePublicationV2,
    val sourceKey: dev.shinsou.kmp.domain.model.SourceKey,
    val supportsFavorite: Boolean,
    val favorite: Boolean,
    val allowUnfavorite: Boolean,
    val notes: String,
    val operationMessage: String?,
    val operationFailed: Boolean,
    val refreshingFromSource: Boolean,
    val continueReadingLoading: Boolean,
    val hasChapters: Boolean,
    val continueLabel: String?,
    val continueDetail: String?,
    val shareWorkHash: String?,
    val actions: PublicationInfoActions,
    val prefetch: List<dev.shinsou.kmp.translation.PrefetchEntry>,
    val reviewsEntry: (@Composable () -> Unit)?,
)

/** What the details pane's buttons do. */
private data class PublicationInfoActions(
    val onBack: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onRefresh: () -> Unit,
    val onShare: () -> Unit,
    val onShareWithUser: (() -> Unit)?,
    val onOpenWeb: () -> Unit,
    val onOpenTracking: () -> Unit,
    val onUpdateNotes: (String) -> Unit,
    val onDownloadAll: () -> Unit,
    /** Queues every chapter for translation ahead of reading (fetching those not stored yet); null where there is no translation. */
    val onTranslateAll: (() -> Unit)?,
    /** Queues only the chapters that are already downloaded: nothing new is fetched from the source. */
    val onTranslateDownloaded: (() -> Unit)?,
    val onCancelPrefetch: () -> Unit,
    val onClearPrefetch: () -> Unit,
    val onContinueReading: (() -> Unit)?,
)

/** "Continue reading": the chapter it opens, its label and the progress line under it. */
private class ContinueReading(
    val unit: ExtensionUnitSelectionV2?,
    val label: String?,
    val detail: String?,
)

private fun continueReading(
    state: ExtensionPublicationState,
    unit: ExtensionUnitSelectionV2?,
    strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings,
): ContinueReading {
    val resumeUnitId = state.resumeUnitId
    val readUnitIds = state.readUnitIds
    val detail = unit
        ?.takeIf { it.unit.remoteId == resumeUnitId || it.unit.remoteId in state.inProgressUnits }
        ?.let { resumed ->
            extensionContinueReadingDetail(
                strings = strings,
                unitTitle = resumed.unit.title,
                progress = state.inProgressUnits[resumed.unit.remoteId]?.takeIf { resumed.unit.remoteId !in readUnitIds },
            )
        }
    val label = when {
        unit == null -> null
        resumeUnitId != null && unit.unit.remoteId == resumeUnitId -> strings.continueReading
        unit.unit.remoteId in readUnitIds -> strings.text(StringKey.READ_AGAIN)
        readUnitIds.isNotEmpty() -> strings.continueReading
        else -> strings.text(StringKey.START_READING)
    }
    return ContinueReading(unit, label, detail)
}

/** What the details pane's buttons act on besides the page's state. */
private data class PublicationActionInputs(
    val translation: dev.shinsou.kmp.translation.ReaderTranslation?,
    val queuedUnitIds: Set<String>,
    val shareWorkHash: String?,
    val continueUnit: ExtensionUnitSelectionV2?,
    val scope: kotlinx.coroutines.CoroutineScope,
)

/** The details pane's buttons. */
private fun publicationActions(
    state: ExtensionPublicationState,
    reader: ExtensionReaderHost,
    navigation: PublicationPaneNavigation,
    inputs: PublicationActionInputs,
): PublicationInfoActions {
    val translation = inputs.translation
    val queuedUnitIds = inputs.queuedUnitIds
    val scope = inputs.scope
    val display = state.displayPublication
    val targetUrl = state.targetUrl
    val units = state.units
    val prefetchGroup = state.prefetchGroup
    val translate = translation != null
    return PublicationInfoActions(
        onBack = navigation.onBack,
        onToggleFavorite = state::toggleFavorite,
        onRefresh = state::refresh,
        onShare = { reader.shareText(display.title, targetUrl ?: display.title) },
        onShareWithUser = inputs.shareWorkHash?.let { { state.shareWithUserVisible = true } },
        onOpenWeb = { targetUrl?.let(reader.openExternalUrl) ?: state.reportMissingUrl(StringKey.THIS_TITLE_HAS_NO_ORIGINAL_URL) },
        onOpenTracking = { targetUrl?.let(reader.openExternalUrl) ?: state.reportMissingUrl(StringKey.THIS_TITLE_HAS_NO_TRACKING_URL) },
        onUpdateNotes = { state.notes = it },
        onDownloadAll = { state.downloadUnits(units, queuedUnitIds) },
        onTranslateAll = { state.translateUnits(units) }.takeIf { translate },
        onTranslateDownloaded = {
            state.translateUnits(units.filter { it.unit.remoteId in state.downloadedUnitIds }, onlyDownloaded = true)
        }.takeIf { translate },
        onCancelPrefetch = { scope.launch { translation?.prefetch?.cancel(prefetchGroup) } },
        onClearPrefetch = { scope.launch { translation?.prefetch?.clearFinished(prefetchGroup) } },
        onContinueReading = inputs.continueUnit?.let { { state.materialize(it, openReader = true) } },
    )
}

@Composable
private fun <S> rememberPublicationInfo(
    state: ExtensionPublicationState,
    sync: S?,
    reader: ExtensionReaderHost,
    navigation: PublicationPaneNavigation,
    translation: dev.shinsou.kmp.translation.ReaderTranslation?,
    queuedUnitIds: Set<String>,
    publicationPrefetch: List<dev.shinsou.kmp.translation.PrefetchEntry>,
    scope: kotlinx.coroutines.CoroutineScope,
): PublicationInfo where S : SyncWorks, S : SyncReviews {
    val strings = LocalShinsouStrings.current
    // Reviews and sharing need a signed-in account on a server that offers them. The work hash is
    // the same one the library record syncs under, so both work before and after adding the title.
    val syncState = sync?.state?.collectAsState()?.value
    val workHash = remember(sync, state.sourceKey, state.remotePublicationId) {
        sync?.sourceWorkHash(state.sourceKey, state.remotePublicationId)
    }
    val inLibrary = state.inLibrary
    val reviewsWorkHash = workHash?.takeIf { syncState?.signedIn == true && syncState.reviewsEnabled }
    val shareWorkHash = workHash?.takeIf { syncState?.signedIn == true && syncState.sharingEnabled && inLibrary }
    val units = state.units
    val continueUnit = remember(state.localLibrary, state.unitDirectoryComplete, state.savedDirectoryShown, units, state.readUnitIds, state.resumeUnitId) {
        state.continueUnit
    }
    val continued = continueReading(state, continueUnit, strings)
    val display = state.displayPublication
    return PublicationInfo(
        publication = display,
        sourceKey = state.sourceKey,
        supportsFavorite = state.supportsFavorite,
        favorite = state.favorite,
        allowUnfavorite = state.localLibrary,
        notes = state.notes,
        operationMessage = state.operationMessage,
        operationFailed = state.operationFailed,
        refreshingFromSource = state.loading,
        continueReadingLoading = state.loading || state.openingReaderUnitId == continueUnit?.unit?.remoteId,
        hasChapters = units.isNotEmpty() || state.hasNextPage,
        continueLabel = continued.label,
        continueDetail = continued.detail,
        shareWorkHash = shareWorkHash,
        prefetch = publicationPrefetch,
        reviewsEntry = if (sync != null && reviewsWorkHash != null) ({
            ReviewsEntry(sync, reviewsWorkHash, display.title, inLibrary)
        }) else null,
        actions = publicationActions(
            state,
            reader,
            navigation,
            PublicationActionInputs(translation, queuedUnitIds, shareWorkHash, continueUnit, scope),
        ),
    )
}

/** Native publication metadata pane deliberately mirrors MangaInfoPane's layout and actions. */
@Composable
private fun ExtensionV2PublicationInfoPane(
    info: PublicationInfo,
    showClose: Boolean,
    scrollable: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    val publication = info.publication
    val sourceKey = info.sourceKey
    val supportsFavorite = info.supportsFavorite
    val favorite = info.favorite
    val allowUnfavorite = info.allowUnfavorite
    val notes = info.notes
    val operationMessage = info.operationMessage
    val operationFailed = info.operationFailed
    val refreshingFromSource = info.refreshingFromSource
    val continueReadingLoading = info.continueReadingLoading
    val hasChapters = info.hasChapters
    val continueLabel = info.continueLabel
    val continueDetail = info.continueDetail
    val reviewsEntry = info.reviewsEntry
    val prefetch = info.prefetch
    val onBack = info.actions.onBack
    val onToggleFavorite = info.actions.onToggleFavorite
    val onRefresh = info.actions.onRefresh
    val onShare = info.actions.onShare
    val onShareWithUser = info.actions.onShareWithUser
    val onOpenWeb = info.actions.onOpenWeb
    val onOpenTracking = info.actions.onOpenTracking
    val onUpdateNotes = info.actions.onUpdateNotes
    val onDownloadAll = info.actions.onDownloadAll
    val onTranslateAll = info.actions.onTranslateAll
    val onTranslateDownloaded = info.actions.onTranslateDownloaded
    val onCancelPrefetch = info.actions.onCancelPrefetch
    val onClearPrefetch = info.actions.onClearPrefetch
    val onContinueReading = info.actions.onContinueReading
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
                    if (showClose) strings.close else strings.text(StringKey.BACK),
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
                Icon(Icons.Outlined.OpenInBrowser, strings.text(StringKey.OPEN_ORIGINAL_PAGE))
            }
            IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, strings.share) }
            if (onShareWithUser != null) {
                IconButton(onClick = onShareWithUser) { Icon(Icons.Outlined.Send, strings.text(StringKey.SHARE_WITH_A_USER)) }
            }
        }
        CoverImage(
            title = publication.title,
            url = publication.thumbnailUrl,
            modifier = Modifier.width(186.dp).aspectRatio(2f / 3f),
            sourceKey = sourceKey,
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
                ?: strings.text(StringKey.UNKNOWN_AUTHOR),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        publication.artist?.takeIf { it.isNotBlank() && it != publication.author }?.let { artist ->
            Text(
                strings.text(StringKey.ARTIST_X, artist),
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
                Text(strings.text(StringKey.TRACK))
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
                Text(strings.text(StringKey.DOWNLOAD_ALL_CHAPTERS))
            }
            if (onTranslateAll != null) {
                Spacer(Modifier.height(9.dp))
                // Two ways to translate ahead: everything (which also downloads it), or only what is already here.
                var translateMenuVisible by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { translateMenuVisible = true },
                        modifier = Modifier.fillMaxWidth().testTag("translate-chapters"),
                    ) {
                        Icon(Icons.Outlined.Translate, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(strings.text(StringKey.TRANSLATE_CHAPTERS_AHEAD))
                    }
                    DropdownMenu(expanded = translateMenuVisible, onDismissRequest = { translateMenuVisible = false }) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(strings.text(StringKey.TRANSLATE_ALL_CHAPTERS))
                                    Text(
                                        strings.text(StringKey.CHAPTERS_THAT_ARE_NOT_DOWNLOADED_YET_ARE),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            leadingIcon = { Icon(Icons.Outlined.Download, null) },
                            onClick = {
                                translateMenuVisible = false
                                onTranslateAll()
                            },
                            modifier = Modifier.testTag("translate-all-chapters"),
                        )
                        if (onTranslateDownloaded != null) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(strings.text(StringKey.TRANSLATE_DOWNLOADED_CHAPTERS))
                                        Text(
                                            strings.text(StringKey.ONLY_WHAT_IS_ALREADY_ON_THIS_DEVICE),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Outlined.Translate, null) },
                                onClick = {
                                    translateMenuVisible = false
                                    onTranslateDownloaded()
                                },
                                modifier = Modifier.testTag("translate-downloaded-chapters"),
                            )
                        }
                    }
                }
            }
        }
        if (prefetch.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            PrefetchProgress(prefetch, onCancelPrefetch, onClearPrefetch)
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
                Text(if (continueReadingLoading) strings.text(StringKey.LOADING) else continueLabel)
            }
            continueDetail?.let { detail ->
                Spacer(Modifier.height(5.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                ?: strings.text(StringKey.NO_DESCRIPTION_AVAILABLE),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        if (reviewsEntry != null) {
            Spacer(Modifier.height(14.dp))
            reviewsEntry()
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.text(StringKey.NOTES), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            notesDraft = notes
                            editingNotes = true
                        },
                    ) { Icon(Icons.Outlined.Edit, strings.text(StringKey.EDIT_NOTES)) }
                }
                Text(
                    notes.ifBlank { strings.text(StringKey.ADD_A_PRIVATE_NOTE_FOR_THIS_TITLE) },
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
            title = { Text(strings.text(StringKey.NOVEL_NOTES)) },
            text = {
                OutlinedTextField(
                    value = notesDraft,
                    onValueChange = { notesDraft = it },
                    label = { Text(strings.text(StringKey.NOTES)) },
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
