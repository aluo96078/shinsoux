package dev.shinsou.kmp.ui

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.ui.components.ShinsouLoadingIndicator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.backup.AutoBackupEntry
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.backup.BackupV2ArchiveSource
import dev.shinsou.kmp.backup.DEFAULT_MAX_ARCHIVE_BYTES
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.backup.SnapshotBackupService
import dev.shinsou.kmp.backup.createBackupEnvelope
import dev.shinsou.kmp.backup.restoreBackup
import dev.shinsou.kmp.backup.withTranslations
import dev.shinsou.kmp.domain.model.BackupStatus
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.ChapterPatch
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaPatch
import dev.shinsou.kmp.download.downloadIdsToRemoveAfterMarkedRead
import dev.shinsou.kmp.domain.model.shouldAskForCategoriesOnFavorite
import dev.shinsou.kmp.local.LOCAL_CONTENT_EXTENSIONS
import dev.shinsou.kmp.local.LOCAL_IMPORTED_DOCUMENT_LIMITS
import dev.shinsou.kmp.domain.model.decodeTypedLocalPublicationUrl
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.backup.shuyue.ShuYueBackupV1Limits
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.domain.model.decodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.components.CoverImage
import dev.shinsou.kmp.ui.components.LocalPluginImageLoader
import dev.shinsou.kmp.ui.components.ScreenHeader
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.screens.AboutScreen
import dev.shinsou.kmp.ui.screens.BackupScreen
import dev.shinsou.kmp.ui.screens.BrowseBackNavigation
import dev.shinsou.kmp.ui.screens.BrowseHost
import dev.shinsou.kmp.ui.screens.BrowseScreen
import dev.shinsou.kmp.ui.screens.BrowseSourceFilters
import dev.shinsou.kmp.ui.screens.ExtensionLibraryHost
import dev.shinsou.kmp.ui.screens.ExtensionReaderHost
import dev.shinsou.kmp.ui.screens.ExtensionReaderProgressSink
import dev.shinsou.kmp.ui.screens.MAX_COOKIE_FILE_BYTES
import dev.shinsou.kmp.ui.sync.ReviewsEntry
import dev.shinsou.kmp.ui.sync.ShareWithUserDialog
import dev.shinsou.kmp.ui.screens.CategoryPickerDialog
import dev.shinsou.kmp.ui.screens.ContentBackupV2Screen
import dev.shinsou.kmp.ui.screens.DownloadsScreen
import dev.shinsou.kmp.ui.screens.HistoryScreen
import dev.shinsou.kmp.ui.screens.LibraryScreen
import dev.shinsou.kmp.ui.screens.MangaDetailScreen
import dev.shinsou.kmp.ui.screens.MoreDestination
import dev.shinsou.kmp.ui.screens.SyncScreen
import dev.shinsou.kmp.ui.screens.syncSummary
import dev.shinsou.kmp.ui.screens.MoreScreen
import dev.shinsou.kmp.ui.screens.SettingsScreen
import dev.shinsou.kmp.ui.screens.ShuYueMigrationScreen
import dev.shinsou.kmp.ui.screens.SourceLoginDialog
import dev.shinsou.kmp.ui.screens.StatisticsScreen
import dev.shinsou.kmp.ui.translation.TranslationScreen
import dev.shinsou.kmp.ui.screens.TrackingSheet
import dev.shinsou.kmp.ui.screens.UpdatesScreen
import dev.aluo.shinsoux.generated.resources.Res
import dev.aluo.shinsoux.generated.resources.shinsou_icon
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.plugin.ContentAcquisition
import dev.shinsou.kmp.contract.ContentCallbacks
import dev.shinsou.kmp.plugin.ExtensionLibraryBinding
import dev.shinsou.kmp.plugin.ExtensionPublicationCatalog
import dev.shinsou.kmp.contract.ImportedDocumentLimits
import dev.shinsou.kmp.plugin.SourceAuth
import dev.shinsou.kmp.plugin.SourceCatalog
import dev.shinsou.kmp.contract.SourceLoginRequest
import dev.shinsou.kmp.plugin.SourceWebSession
import dev.shinsou.kmp.ui.reader.ReaderVolumeKeyRouter

// R4-4 (docs/architecture/0006): moved out of ShinsouApp.kt without changing it.

/**
 * Browse state changes during every extension refresh/install. Subscribe only while the login
 * prompt is visible so those updates do not invalidate the entire application composition.
 */
@Composable
internal fun PendingSourceLoginDialog(
    request: SourceLoginRequest,
    catalog: SourceCatalog,
    auth: SourceAuth,
    webSession: SourceWebSession,
) {
    val browseSnapshot by catalog.state.collectAsState()
    val source = request.exactTarget?.sourceKey?.let { exactKey ->
        browseSnapshot.sources.firstOrNull { it.sourceKey == exactKey }
            ?: exactKey.legacyLongId?.let { legacyId ->
                browseSnapshot.sources.firstOrNull { it.sourceKey == null && it.id == legacyId }
            }
    } ?: browseSnapshot.sources.firstOrNull { it.id == request.sourceId }
    if (source?.supportsLogin == true) {
        SourceLoginDialog(
            request = request,
            source = source,
            auth = auth,
            webSession = webSession,
        )
    } else {
        // A queued request can outlive an uninstall, capability change, or source refresh. Never
        // turn such a stale request into a credential dialog.
        LaunchedEffect(request) {
            request.eventId?.let(auth::dismissSourceLoginEvent)
                ?: auth.dismissSourceLoginRequest(request.sourceId)
        }
    }
}

/** What the Browse section reports to the shell, and its extension reader's progress. */
internal data class BrowseSectionHooks(
    val navigation: BrowseBackNavigation,
    val onBackAvailabilityChanged: (Boolean) -> Unit,
    val onParentDismissed: () -> Unit,
    val onReaderVisibilityChanged: (Boolean) -> Unit,
    val onReaderProgress: ExtensionReaderProgressSink,
    val onReaderProgressFlushed: suspend () -> Unit,
    val volumeKeyRouter: ReaderVolumeKeyRouter,
    val library: ExtensionLibraryHost,
)

@Composable
internal fun SectionPane(
    section: MainSection,
    snapshot: AppSnapshot,
    repository: ShinsouRepository,
    appServices: ShinsouAppServices,
    compactChrome: Boolean,
    selectedCategoryId: Long,
    onCategorySelected: (Long) -> Unit,
    selectedLibraryIds: Set<Long>,
    onLibrarySelectionChange: (Set<Long>) -> Unit,
    onOpenManga: (Long) -> Unit,
    onOpenBrowseManga: (BrowseManga) -> Unit,
    onReadChapter: (Long, Long) -> Unit,
    /** Opens a library extension title's page and its chapter (by the source's id) in the reader. */
    onContinueExtensionUnit: (Long, String) -> Unit,
    onOpenMore: (MoreDestination) -> Unit,
    onLibraryTitlesRemoved: (Set<Long>) -> Unit,
    browse: BrowseSectionHooks,
    actions: UserActions,
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val pluginImages = LocalPluginImageLoader.current
    // Exact extension bindings of typed local rows live in the content store: read off the UI
    // thread, and used by the covers below once known.
    val typedBindings = rememberTypedLocalBindings(
        snapshot,
        library = appServices.browse,
        acquisition = appServices.browse,
        backup = appServices.portableContentBackupV2,
        workDispatcher = workDispatcher,
        active = section != MainSection.BROWSE && section != MainSection.MORE,
    )
    val loadCover: (Manga) -> (suspend () -> ByteArray?)? = { manga -> pluginImages.libraryCover(manga, typedBindings) }
    AnimatedContent(
        targetState = section,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            androidx.compose.animation.fadeIn(tween(140)) togetherWith
                androidx.compose.animation.fadeOut(tween(100))
        },
        label = "main-section",
    ) { current ->
        when (current) {
            MainSection.LIBRARY -> {
                val browseSnapshot by appServices.browse.state.collectAsState()
                val extensionSourceTypes = remember(browseSnapshot.sources) {
                    browseSnapshot.sources.mapNotNull { source ->
                        source.sourceKey?.let { it to source.contentType }
                    }.toMap()
                }
                val legacySourceTypes = remember(browseSnapshot.sources) {
                    browseSnapshot.sources.asSequence()
                        .filter { it.sourceKey == null }
                        .associate { it.id to it.contentType }
                }
                val libraryItems = remember(snapshot.revision) {
                    repository.libraryItems().let { items ->
                        if (snapshot.settings.library.downloadOnly) items.filter { it.downloadCount > 0 } else items
                    }
                }
                // The title read last on any device, where its reading goes on.
                val continueReading = rememberLibraryContinueReading(
                    snapshot = snapshot,
                    libraryItems = libraryItems,
                    typedBindings = typedBindings,
                    sync = appServices.sync,
                    browse = appServices.browse,
                    workDispatcher = workDispatcher,
                )
                val continueScope = rememberCoroutineScope()
                val strings = LocalShinsouStrings.current
                val typedPublicationKinds = rememberTypedPublicationKinds(snapshot, appServices.contentFeatures, workDispatcher)
                val legacyExtensionBindings = typedBindings
                val waitingSources = appServices.sync?.state?.collectAsState()?.value?.missingSources.orEmpty()
                LibraryScreen(
                    items = libraryItems,
                    contentTypes = remember(
                        snapshot.revision,
                        libraryItems,
                        extensionSourceTypes,
                        legacySourceTypes,
                        typedPublicationKinds,
                        legacyExtensionBindings,
                    ) {
                        libraryItems.associate { item ->
                            item.id to libraryContentType(
                                manga = item.libraryManga.manga,
                                extensionSourceTypes = extensionSourceTypes,
                                legacySourceTypes = legacySourceTypes,
                                legacyExtensionBinding = legacyExtensionBindings::get,
                                typedPublicationKinds = typedPublicationKinds::get,
                            )
                        }
                    },
                    categories = snapshot.categories.sortedBy { it.sort },
                    settings = snapshot.settings.library,
                    selectedCategoryId = selectedCategoryId,
                    selectedMangaIds = selectedLibraryIds,
                    compactChrome = compactChrome,
                    onCategorySelected = onCategorySelected,
                    onCreateCategory = { name ->
                        actions.inStores { repository.createCategory(name) }
                    },
                    onRenameCategory = { category, name ->
                        actions.inStores { repository.renameCategory(category.id, name) }
                    },
                    onDeleteCategory = { categoryId -> actions.inStores { repository.deleteCategory(categoryId) } },
                    onReorderCategories = { ids ->
                        actions.inStores { repository.reorderCategories(listOf(Category.Default.id) + ids) }
                    },
                    onSelectionChange = onLibrarySelectionChange,
                    onSettingsChange = { edit ->
                        actions.editSettings(repository) { it.copy(library = edit(it.library)) }
                    },
                    waitingSources = waitingSources,
                    onOpenWaitingSources = { onOpenMore(MoreDestination.Sync) },
                    onOpenManga = onOpenManga,
                    onContinueReading = { mangaId ->
                        val item = libraryItems.firstOrNull { it.id == mangaId }
                        if (item == null || extensionBindingOf(item.libraryManga.manga, typedBindings) == null) {
                            continueChapter(snapshot, mangaId)?.let { onReadChapter(mangaId, it.id) }
                        } else {
                            // An extension title continues through its page, as its own button
                            // does: the chapter may be one only another device has read.
                            continueScope.launch {
                                val unitId = continueReading?.takeIf { it.item.id == mangaId }?.extensionUnitId
                                    ?: runCatchingCancellable {
                                        withContext(workDispatcher) {
                                            continueReadingOf(item, snapshot, typedBindings, appServices.browse, strings)
                                        }
                                    }.onFailure { Log.warn("Library", it) { "Reading the chapter to continue failed" } }
                                        .getOrNull()?.extensionUnitId
                                if (unitId != null) onContinueExtensionUnit(mangaId, unitId) else onOpenManga(mangaId)
                            }
                        }
                    },
                    continueReading = continueReading,
                    onRefresh = {
                        val ids = snapshot.mangas.filter { it.favorite }.mapTo(linkedSetOf()) { it.id }
                        actions.inStores { appServices.content.refreshLibrary(ids) }
                    },
                    onMarkSelectedRead = { ids ->
                        actions.inStores {
                            markChaptersRead(
                                repository = repository,
                                content = appServices.content,
                                snapshot = snapshot,
                                chapterIds = snapshot.chapters
                                    .filterTo(linkedSetOf()) { it.mangaId in ids }
                                    .mapTo(linkedSetOf()) { it.id },
                                read = true,
                            )
                        }
                    },
                    onMoveSelected = { ids, categoryIds ->
                        actions.inStores { ids.forEach { repository.setMangaCategories(it, categoryIds) } }
                    },
                    onDeleteSelected = { ids ->
                        onLibraryTitlesRemoved(ids)
                        actions.inStores {
                            removeDownloadsForMangas(
                                content = appServices.content,
                                queue = snapshot.downloadQueue,
                                mangaIds = ids,
                            )
                            withContext(workDispatcher) {
                                forgetExtensionTitles(appServices.browse, appServices.browse, appServices.browse, snapshot.mangas.filter { it.id in ids })
                            }
                            ids.forEach { repository.patchManga(it, MangaPatch(favorite = false)) }
                        }
                    },
                    loadCoverBytes = loadCover,
                )
            }
            MainSection.UPDATES -> UpdatesScreen(
                updates = remember(snapshot.revision) { repository.recentUpdates() },
                allManga = snapshot.mangas,
                allChapters = snapshot.chapters,
                onRefresh = {
                    actions.inStores {
                        appServices.content.refreshLibrary(snapshot.mangas.filter { it.favorite }.mapTo(linkedSetOf()) { it.id })
                    }
                },
                onOpenManga = onOpenManga,
                onReadChapter = onReadChapter,
                onDownloadChapter = { mangaId, chapterId ->
                    actions.inStores { appServices.content.enqueueDownload(mangaId, chapterId) }
                },
                onMarkChaptersRead = { chapterIds, read ->
                    actions.inStores {
                        markChaptersRead(repository, appServices.content, snapshot, chapterIds, read)
                    }
                },
                onDownloadChapters = { chapterIds ->
                    actions.inStores {
                        snapshot.chapters.filter { it.id in chapterIds }.forEach { chapter ->
                            appServices.content.enqueueDownload(chapter.mangaId, chapter.id)
                        }
                    }
                },
                onDeleteChapterDownloads = { chapterIds ->
                    actions.inStores {
                        snapshot.downloadQueue.filter { it.chapterId in chapterIds }.forEach { item ->
                            appServices.content.removeDownload(item.id)
                        }
                    }
                },
                onToggleChapterBookmarks = { chapterIds ->
                    actions.inStores {
                        snapshot.chapters.filter { it.id in chapterIds }.forEach { chapter ->
                            repository.patchChapter(chapter.id, ChapterPatch(bookmark = !chapter.bookmark))
                        }
                    }
                },
                loadCoverBytes = loadCover,
            )
            MainSection.HISTORY -> HistoryScreen(
                history = remember(snapshot.revision) { repository.history() },
                onOpenManga = onOpenManga,
                onResumeChapter = onReadChapter,
                onDeleteChapterHistory = { chapterId -> actions.inStores { repository.deleteHistory(chapterId) } },
                onClearHistory = { actions.inStores { repository.clearHistory() } },
                loadCoverBytes = loadCover,
            )
            MainSection.BROWSE -> BrowseScreen(
                callbacks = appServices.browse,
                sync = appServices.sync,
                filters = BrowseSourceFilters(
                    showNsfw = snapshot.settings.browse.showNsfwSources,
                    enabledLanguages = snapshot.settings.browse.enabledLanguages,
                    pinnedSourceIds = snapshot.settings.browse.pinnedSourceIds,
                    pinnedSourceKeys = snapshot.settings.browse.pinnedSourceKeys,
                ),
                host = BrowseHost(
                    onOpenManga = onOpenBrowseManga,
                    onSourcePinnedChange = { sourceId, pinned ->
                        actions.inStores {
                            repository.updateSettings { settings ->
                                val pinnedIds = if (pinned) {
                                    settings.browse.pinnedSourceIds + sourceId
                                } else {
                                    settings.browse.pinnedSourceIds - sourceId
                                }
                                settings.copy(browse = settings.browse.copy(pinnedSourceIds = pinnedIds))
                            }
                        }
                    },
                    onSourcePinnedKeyChange = { sourceKey, pinned ->
                        actions.inStores {
                            repository.updateSettings { settings ->
                                val pinnedKeys = if (pinned) {
                                    settings.browse.pinnedSourceKeys + sourceKey
                                } else {
                                    settings.browse.pinnedSourceKeys - sourceKey
                                }
                                settings.copy(browse = settings.browse.copy(pinnedSourceKeys = pinnedKeys))
                            }
                        }
                    },
                    onImportDocument = { acceptedExtensions ->
                        appServices.importDocument(
                            acceptedExtensions = acceptedExtensions,
                            limits = ImportedDocumentLimits(MAX_COOKIE_FILE_BYTES.toLong()),
                        )
                    },
                    onBackAvailabilityChanged = browse.onBackAvailabilityChanged,
                    onParentDismissed = browse.onParentDismissed,
                    onReaderVisibilityChanged = browse.onReaderVisibilityChanged,
                ),
                reader = ExtensionReaderHost(
                    contentFeatures = appServices.contentFeatures,
                    readerSettings = snapshot.settings.reader,
                    onReaderSettingsChange = { edit ->
                        actions.editSettings(repository) { it.copy(reader = edit(it.reader)) }
                    },
                    copyText = appServices::copyText,
                    openExternalUrl = appServices::openExternalUrl,
                    shareText = appServices::shareText,
                    onReaderProgress = browse.onReaderProgress,
                    onReaderProgressFlushed = browse.onReaderProgressFlushed,
                    volumeKeyRouter = browse.volumeKeyRouter,
                    library = browse.library,
                    workDispatcher = workDispatcher,
                ),
                navigation = browse.navigation,
            )
            MainSection.MORE -> MoreScreen(
                downloadCount = pendingDownloadCount(snapshot.downloadQueue, appServices.browse),
                lastBackupLabel = snapshot.backupState.lastFileName,
                syncLabel = syncSummary(appServices.sync),
                incognitoMode = snapshot.settings.security.incognitoMode,
                downloadOnlyMode = snapshot.settings.library.downloadOnly,
                onOpen = onOpenMore,
                onImportLocal = { syncContentBodies ->
                    actions.onUi {
                        val documents = appServices.pickLocalFiles(
                            acceptedExtensions = LOCAL_CONTENT_EXTENSIONS,
                            limits = LOCAL_IMPORTED_DOCUMENT_LIMITS,
                        )
                        val imported = actions.stores {
                            appServices.content.importLocalDocuments(
                                documents = documents,
                                syncContentBodies = syncContentBodies,
                            )
                        }
                        imported.firstOrNull()?.let { onOpenManga(it.mangaId) }
                    }
                },
                onIncognitoModeChange = { enabled ->
                    actions.inStores {
                        repository.updateSettings { settings ->
                            settings.copy(security = settings.security.copy(incognitoMode = enabled))
                        }
                    }
                },
                onDownloadOnlyModeChange = { enabled ->
                    actions.inStores {
                        repository.updateSettings { settings ->
                            settings.copy(library = settings.library.copy(downloadOnly = enabled))
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun PendingMangaDetailPane(
    item: BrowseManga,
    onBack: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = item.title,
            subtitle = strings.text(StringKey.LOADING_TITLE_DETAILS),
            leading = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, strings.text(StringKey.BACK))
                }
            },
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CoverImage(
                    title = item.title,
                    url = item.thumbnailUrl,
                    headers = item.thumbnailHeaders,
                    modifier = Modifier.width(160.dp).aspectRatio(2f / 3f),
                    sourceId = item.sourceId,
                    sourceKey = item.sourceKey,
                )
                ShinsouLoadingIndicator(indicatorModifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                Text(
                    strings.text(StringKey.LOADING_TITLE_DETAILS),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun DetailPane(
    mangaId: Long,
    snapshot: AppSnapshot,
    repository: ShinsouRepository,
    appServices: ShinsouAppServices,
    wideLayout: Boolean,
    systemBackRequest: Long = 0L,
    onNestedBackAvailabilityChanged: (Boolean) -> Unit = {},
    selectedChapterIds: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onBack: () -> Unit,
    onRead: (Long, Long) -> Unit,
    actions: UserActions,
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Closes [mangaId]'s page once its unfavorite is stored; the user may have opened another
     * title meanwhile, which stays open.
     */
    onUnfavorited: () -> Unit = onBack,
) {
    val pluginImages = LocalPluginImageLoader.current
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var trackingSheetVisible by remember(mangaId) { mutableStateOf(false) }
    var categoryPickerVisible by remember(mangaId) { mutableStateOf(false) }
    var shareWithUserVisible by remember(mangaId) { mutableStateOf(false) }
    var handledSystemBackRequest by remember(mangaId) { mutableStateOf(systemBackRequest) }
    val nestedBackAvailable = trackingSheetVisible || categoryPickerVisible
    val currentBackCallback = rememberUpdatedState(onBack)
    val currentNestedBackCallback = rememberUpdatedState(onNestedBackAvailabilityChanged)
    LaunchedEffect(nestedBackAvailable) {
        currentNestedBackCallback.value(nestedBackAvailable)
    }
    DisposableEffect(mangaId) {
        onDispose { currentNestedBackCallback.value(false) }
    }
    LaunchedEffect(systemBackRequest) {
        if (systemBackRequest == handledSystemBackRequest) return@LaunchedEffect
        handledSystemBackRequest = systemBackRequest
        when {
            trackingSheetVisible -> trackingSheetVisible = false
            categoryPickerVisible -> categoryPickerVisible = false
            else -> currentBackCallback.value()
        }
    }
    val manga = snapshot.mangas.firstOrNull { it.id == mangaId }
    if (manga == null) {
        EmptyState(
            strings.text(StringKey.TITLE_NOT_FOUND),
            strings.text(StringKey.THIS_TITLE_MAY_HAVE_BEEN_REMOVED),
            icon = { Icon(Icons.Outlined.Book, null) },
        )
        return
    }
    val chapters = snapshot.chapters.filter { it.mangaId == mangaId }
    val downloads = snapshot.downloadQueue.filter { it.mangaId == mangaId }
    val coverBinding = rememberExtensionBinding(manga.url, appServices.browse, workDispatcher)
    var refreshingFromSource by remember(mangaId) {
        mutableStateOf(!manga.initialized || chapters.isEmpty())
    }
    fun refreshFromSource() {
        if (refreshingFromSource) return
        refreshingFromSource = true
        actions.onUi {
            try {
                actions.stores { appServices.content.refreshManga(manga.id) }
            } finally {
                refreshingFromSource = false
            }
        }
    }
    LaunchedEffect(mangaId) {
        if (!manga.initialized || chapters.isEmpty()) {
            // The seed detail surface must be committed before extension startup, HTTP and HTML
            // parsing begin. Remote execution itself is dispatched away from Main by the source
            // runtime/coordinator, so the shell and its progress feedback remain responsive.
            withFrameNanos { }
            // This starts true so the very first detail frame already contains progress UI.
            // Clear and immediately re-enter through the guarded action to launch the request.
            refreshingFromSource = false
            refreshFromSource()
        } else if (manga.favorite) {
            // Library titles show their stored details and chapters at once, then pick up source
            // changes quietly on every visit; offline the stored copy simply stays.
            try {
                withContext(workDispatcher) { appServices.content.refreshManga(manga.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Throwable) {
                Log.warn("Library", logged) { "Refreshing the stored title details failed; keeping the stored copy" }
            }
        }
    }
    fun withOriginalUrl(action: (String) -> Unit) {
        scope.launch {
            val resolved = runCatchingCancellable {
                withContext(workDispatcher) { appServices.content.resolveMangaOriginalUrl(manga.id) }
            }
                .onFailure { Log.warn("Library", it) { "Resolving the original web address failed; using the stored one" } }
                .getOrNull()
                ?: manga.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            resolved?.let(action)
        }
    }
    // Sharing needs a signed-in account on a server that offers it, and a title the server can know.
    val sync = appServices.sync
    // Reviews need a server that offers them and a title with a portable identity. The row itself
    // loads lazily and hides when the server does not know the work (unless it is in the library).
    val workHashes = rememberSyncWorkHashes(manga, sync, workDispatcher)
    val shareWorkHash = workHashes.share
    val reviewsWorkHash = workHashes.reviews
    // ADR-0005: page-reader chapters translated ahead of reading, through the app-wide queue.
    val mangaTranslation = appServices.mangaTranslation
    val textTranslation = appServices.contentFeatures?.translation
    val prefetchGroup = remember(manga.source, manga.url) { dev.shinsou.kmp.ui.reader.mangaPrefetchGroup(manga) }
    // a work with no settings is translated ahead as it would start by itself in the reader
    val prefetchAppLanguage = dev.shinsou.kmp.ui.i18n.LocalAppLanguage.current
    val prefetchWorkLanguage = rememberSourceLanguage(appServices.browse, manga.source)
    val prefetchEntries by (textTranslation?.prefetch?.entries ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    val mangaPrefetch = remember(prefetchEntries, prefetchGroup) { prefetchEntries.filter { it.group == prefetchGroup } }
    val translationStatus = if (mangaPrefetch.isEmpty()) null else {
        val done = mangaPrefetch.count { it.state == dev.shinsou.kmp.translation.PrefetchState.DONE }
        val failed = mangaPrefetch.count { it.state == dev.shinsou.kmp.translation.PrefetchState.FAILED }
        val running = mangaPrefetch.firstOrNull { it.state == dev.shinsou.kmp.translation.PrefetchState.RUNNING }
        buildString {
            append(strings.text(StringKey.TRANSLATING_AHEAD_X_OF_X_CHAPTERS_DONE, done, mangaPrefetch.size))
            if (running != null) append(" · ").append(running.title)
            if (failed > 0) append(" · ").append(strings.text(StringKey.X_FAILED, failed))
        }
    }
    // Chapters with a stored translation (read translated, or translated ahead), as the list marks them.
    val finishedMangaPrefetch = mangaPrefetch.count { it.state == dev.shinsou.kmp.translation.PrefetchState.DONE }
    var translatedChapterIds by remember(manga.id) { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(textTranslation, mangaTranslation, chapters, finishedMangaPrefetch) {
        if (textTranslation == null || mangaTranslation == null || chapters.isEmpty()) return@LaunchedEffect
        val keys = runCatchingCancellable {
            withContext(workDispatcher) { textTranslation.translatedChapterKeys(dev.shinsou.kmp.ui.reader.mangaWorkKey(manga)) }
        }
            .onFailure { Log.warn("Translation", it) { "Reading the translated chapter list failed" } }
            .getOrDefault(emptySet())
        translatedChapterIds = if (keys.isEmpty()) emptySet() else chapters.filter { dev.shinsou.kmp.ui.reader.mangaChapterKey(it) in keys }.mapTo(hashSetOf()) { it.id }
    }
    MangaDetailScreen(
        manga = manga,
        chapters = chapters,
        downloads = downloads,
        translationStatus = translationStatus,
        translatedChapterIds = translatedChapterIds,
        onTranslateChapters = if (mangaTranslation != null && textTranslation != null) {
            { ids ->
                val sourceName: String? = null
                val jobs = chapters.filter { it.id in ids }.map { chapter ->
                    dev.shinsou.kmp.ui.reader.mangaPrefetchJob(
                        manga = mangaTranslation,
                        translation = textTranslation,
                        work = manga,
                        chapter = chapter,
                        labels = dev.shinsou.kmp.translation.TranslationLabels(manga.title, chapter.name, sourceName),
                        loadPages = { appServices.content.loadReaderChapter(manga.id, chapter.id).pages },
                        workLanguage = prefetchWorkLanguage, appLanguage = prefetchAppLanguage,
                    )
                }
                onSelectionChange(emptySet())
                scope.launch { textTranslation.prefetch.enqueue(jobs) }
            }
        } else {
            null
        },
        refreshingFromSource = refreshingFromSource,
        wideLayout = wideLayout,
        selectedChapterIds = selectedChapterIds,
        onSelectionChange = onSelectionChange,
        onBack = onBack,
        onToggleFavorite = {
            actions.onUi {
                val updated = actions.stores { repository.toggleMangaFavorite(manga.id) }
                if (!updated.favorite) {
                    categoryPickerVisible = false
                    onUnfavorited()
                    actions.stores {
                        removeDownloadsForMangas(
                            content = appServices.content,
                            queue = snapshot.downloadQueue,
                            mangaIds = setOf(manga.id),
                        )
                    }
                } else {
                    val current = repository.currentSnapshot
                    if (shouldAskForCategoriesOnFavorite(
                            configuredCategoryId = current.settings.library.defaultCategoryId,
                            availableCategoryIds = current.categories.map { it.id },
                        )
                    ) {
                        categoryPickerVisible = true
                    }
                }
            }
        },
        onRefresh = ::refreshFromSource,
        onShare = { withOriginalUrl { appServices.shareText(manga.title, it) } },
        onShareWithUser = if (shareWorkHash != null) ({ shareWithUserVisible = true }) else null,
        reviewsEntry = if (sync != null && reviewsWorkHash != null) ({
            ReviewsEntry(sync, reviewsWorkHash, manga.title, manga.favorite)
        }) else null,
        onOpenWeb = { withOriginalUrl(appServices::openExternalUrl) },
        onOpenTracking = {
            if (appServices.tracking != null) {
                trackingSheetVisible = true
            } else {
                snapshot.tracks.firstOrNull { it.mangaId == manga.id }?.remoteUrl?.takeIf { it.isNotBlank() }
                    ?.let(appServices::openExternalUrl)
            }
        },
        onUpdateNotes = { notes -> actions.inStores { repository.patchManga(manga.id, MangaPatch(notes = notes)) } },
        onChapterFlagsChange = { edit ->
            // Applied inside the write to the flags stored then: two quick changes both stay.
            actions.inStores { repository.updateManga(manga.id) { stored -> stored.copy(chapterFlags = edit(stored.chapterFlags)) } }
        },
        onExcludedScanlatorsChange = { excluded ->
            actions.inStores { repository.patchManga(manga.id, MangaPatch(excludedScanlators = excluded)) }
        },
        onReadChapter = { onRead(manga.id, it) },
        onMarkChaptersRead = { ids, read ->
            actions.inStores { markChaptersRead(repository, appServices.content, snapshot, ids, read) }
        },
        onBookmarkChapters = { ids, bookmark ->
            actions.inStores { ids.forEach { repository.patchChapter(it, ChapterPatch(bookmark = bookmark)) } }
        },
        onDownloadChapters = { ids ->
            actions.inStores { ids.forEach { appServices.content.enqueueDownload(manga.id, it) } }
        },
        onDeleteChapters = { ids ->
            actions.inStores {
                snapshot.downloadQueue.filter { it.chapterId in ids }.forEach {
                    appServices.content.removeDownload(it.id)
                }
            }
        },
        loadCoverBytes = coverBinding?.let { pluginImages.load(it.sourceKey, manga.thumbnailUrl) }
            ?: pluginImages.load(manga.source, manga.thumbnailUrl),
    )

    if (categoryPickerVisible) {
        CategoryPickerDialog(
            categories = snapshot.categories,
            selectedCategoryIds = repository.categoriesForManga(manga.id).mapTo(linkedSetOf()) { it.id },
            title = strings.text(StringKey.SET_CATEGORIES),
            onDismiss = {
                // The title was already added. Cancelling keeps it safely in Default, matching the
                // original mobile flow instead of leaving favorite/category state inconsistent.
                categoryPickerVisible = false
            },
            onConfirm = { categoryIds ->
                actions.onUi {
                    actions.stores { repository.setMangaCategories(manga.id, categoryIds) }
                    categoryPickerVisible = false
                }
            },
        )
    }

    if (shareWithUserVisible && sync != null && shareWorkHash != null) {
        ShareWithUserDialog(
            sync = sync,
            workHash = shareWorkHash,
            title = manga.title,
            onDismiss = { shareWithUserVisible = false },
        )
    }

    val tracking = appServices.tracking
    if (trackingSheetVisible && tracking != null) {
        TrackingSheet(
            manga = manga,
            tracks = snapshot.tracks.filter { it.mangaId == manga.id },
            coordinator = tracking,
            onOpenExternalUrl = appServices::openExternalUrl,
            onDismiss = { trackingSheetVisible = false },
        )
    }
}

private suspend fun markChaptersRead(
    repository: ShinsouRepository,
    content: ContentCallbacks,
    snapshot: AppSnapshot,
    chapterIds: Set<Long>,
    read: Boolean,
) {
    chapterIds.forEach { chapterId -> repository.patchChapter(chapterId, ChapterPatch(read = read)) }
    downloadIdsToRemoveAfterMarkedRead(
        settings = snapshot.settings.downloads,
        queue = snapshot.downloadQueue,
        chapterIds = chapterIds,
        markedRead = read,
    ).forEach { downloadId -> content.removeDownload(downloadId) }
}

/** Novel titles leaving the library also leave the download queue and the offline copy. */
private suspend fun forgetExtensionTitles(
    library: ExtensionLibraryBinding,
    acquisition: ContentAcquisition,
    publications: ExtensionPublicationCatalog,
    mangas: List<Manga>,
) {
    mangas.forEach { manga ->
        val binding = decodeExtensionLibraryPublicationUrl(manga.url)
            ?: decodeTypedLocalPublicationUrl(manga.url)?.let(library::extensionLibraryBindingV2)
            ?: return@forEach
        runCatchingCancellable {
            acquisition.removeExtensionPublicationDownloadsV2(binding.sourceKey, binding.remotePublicationId)
        }.onFailure { Log.warn("Library", it) { "Removing downloads of a title leaving the library failed" } }
        runCatchingCancellable {
            acquisition.purgeExtensionPublicationContentV2(binding.sourceKey, binding.remotePublicationId)
        }.onFailure { Log.warn("Library", it) { "Purging the offline copy of a title leaving the library failed" } }
        runCatchingCancellable {
            publications.forgetCachedExtensionPublicationV2(binding.sourceKey, binding.remotePublicationId)
        }.onFailure { Log.warn("Library", it) { "Forgetting the cached details of a title leaving the library failed" } }
    }
}

/** Screens that keep their own inner page and take back presses for it first. */
private val NESTED_MORE_DESTINATIONS = setOf(MoreDestination.Settings, MoreDestination.Translation)

@Composable
internal fun MoreDestinationPane(
    destination: MoreDestination,
    snapshot: AppSnapshot,
    repository: ShinsouRepository,
    appServices: ShinsouAppServices,
    securityCapabilities: PlatformSecurityCapabilities,
    autoBackupService: AutoBackupService?,
    wideLayout: Boolean,
    downloadsPaused: Boolean,
    onDownloadsPausedChange: (Boolean) -> Unit,
    onOpenDestination: (MoreDestination) -> Unit,
    systemBackRequest: Long = 0L,
    backGestureProgress: Float = 0f,
    onNestedBackAvailabilityChanged: (Boolean) -> Unit = {},
    onBack: () -> Unit,
    actions: UserActions,
) {
    val strings = LocalShinsouStrings.current
    var automaticBackups by remember(autoBackupService) {
        mutableStateOf<List<AutoBackupEntry>>(emptyList())
    }
    var settingsNestedBackAvailable by remember(destination) { mutableStateOf(false) }
    var handledSystemBackRequest by remember(destination) { mutableStateOf(systemBackRequest) }
    var openedDownloadWork by remember(destination) { mutableStateOf<String?>(null) }
    val nestedBackAvailable = (destination in NESTED_MORE_DESTINATIONS && settingsNestedBackAvailable) ||
        (destination == MoreDestination.Downloads && openedDownloadWork != null)
    val currentNestedBackCallback = rememberUpdatedState(onNestedBackAvailabilityChanged)
    LaunchedEffect(nestedBackAvailable) {
        currentNestedBackCallback.value(nestedBackAvailable)
    }
    DisposableEffect(destination) {
        onDispose { currentNestedBackCallback.value(false) }
    }
    LaunchedEffect(destination, autoBackupService, snapshot.backupState.lastBackupAt) {
        if (destination == MoreDestination.Backup && autoBackupService != null) {
            automaticBackups = autoBackupService.listBackups()
        }
    }
    LaunchedEffect(systemBackRequest) {
        if (systemBackRequest == handledSystemBackRequest) return@LaunchedEffect
        handledSystemBackRequest = systemBackRequest
        // SettingsScreen owns its detail route and consumes the same request when a
        // section is open.  Do not let this parent observe the transient state change
        // and pop the whole More destination in the same frame; that briefly reveals
        // the parent screen during a return gesture.
        when {
            destination in NESTED_MORE_DESTINATIONS -> Unit
            // Back from one title's chapters returns to the title list first.
            destination == MoreDestination.Downloads && openedDownloadWork != null -> openedDownloadWork = null
            else -> onBack()
        }
    }

    val extensionDownloads by appServices.browse.extensionDownloadsV2.collectAsState()
    var backgroundDownloadsRestricted by remember(destination) { mutableStateOf(false) }
    if (destination == MoreDestination.Downloads) {
        // Re-checked while the screen is open, so returning from the system setting updates it.
        LaunchedEffect(appServices) {
            while (true) {
                backgroundDownloadsRestricted = appServices.backgroundDownloadsRestricted()
                kotlinx.coroutines.delay(2_000)
            }
        }
    }
    when (destination) {
        MoreDestination.Downloads -> DownloadsScreen(
            queue = snapshot.downloadQueue.filter { it.visibleInQueue },
            extensionQueue = extensionDownloads,
            openedWorkKey = openedDownloadWork,
            onOpenWork = { openedDownloadWork = it },
            backgroundRestricted = backgroundDownloadsRestricted,
            onLiftBackgroundRestriction = appServices::openBackgroundDownloadSettings,
            onRetryExtension = { id -> actions.inStores { appServices.browse.retryExtensionDownloadV2(id) } },
            onRemoveExtension = { id -> actions.inStores { appServices.browse.removeExtensionDownloadV2(id) } },
            mangaById = snapshot.mangas.associateBy { it.id },
            chapterById = snapshot.chapters.associateBy { it.id },
            paused = downloadsPaused,
            onBack = onBack,
            onPauseChange = { paused ->
                onDownloadsPausedChange(paused)
                actions.inStores {
                    appServices.content.pauseDownloads(paused)
                    appServices.browse.pauseExtensionDownloadsV2(paused)
                }
            },
            onRetry = { id ->
                actions.inStores { appServices.content.retryDownload(id) }
            },
            onRemove = { actions.inStores { appServices.content.removeDownload(it) } },
            onRemoveUnfinished = { actions.inStores { appServices.content.removeUnfinishedDownload(it) } },
            onMove = { id, delta ->
                val ordered = snapshot.downloadQueue
                    .filter { it.visibleInQueue }
                    .sortedBy { it.position }
                    .map { it.id }
                    .toMutableList()
                val current = ordered.indexOf(id)
                val target = (current + delta).coerceIn(0, ordered.lastIndex)
                if (current >= 0 && target != current) {
                    ordered.removeAt(current)
                    ordered.add(target, id)
                    actions.inStores { appServices.content.reorderDownloads(ordered) }
                }
            },
            onClearCompleted = {
                actions.inStores {
                    appServices.content.clearCompletedDownloads()
                    appServices.browse.clearCompletedExtensionDownloadsV2()
                }
            },
        )
        MoreDestination.Statistics -> StatisticsScreen(
            library = remember(snapshot.revision) { repository.libraryItems() },
            chapters = snapshot.chapters,
            tracks = snapshot.tracks,
            categories = snapshot.categories,
            onBack = onBack,
        )
        MoreDestination.Translation -> TranslationScreen(
            translation = appServices.contentFeatures?.translation,
            wideLayout = wideLayout,
            systemBackRequest = systemBackRequest,
            backGestureProgress = if (settingsNestedBackAvailable) backGestureProgress else 0f,
            onBackAvailabilityChanged = { settingsNestedBackAvailable = it },
            onBack = onBack,
        )
        MoreDestination.Settings -> SettingsScreen(
            settings = snapshot.settings,
            categories = snapshot.categories.sortedBy { it.sort },
            securityCapabilities = securityCapabilities,
            wideLayout = wideLayout,
            systemBackRequest = systemBackRequest,
            backGestureProgress = if (settingsNestedBackAvailable) backGestureProgress else 0f,
            onBackAvailabilityChanged = { settingsNestedBackAvailable = it },
            onBack = onBack,
            onChange = { edit -> actions.editSettings(repository, edit) },
            onCreateCategory = { name ->
                actions.inStores { repository.createCategory(name) }
            },
            onRenameCategory = { category, name ->
                actions.inStores { repository.renameCategory(category.id, name) }
            },
            onDeleteCategory = { categoryId -> actions.inStores { repository.deleteCategory(categoryId) } },
            onReorderCategories = { ids ->
                actions.inStores { repository.reorderCategories(listOf(Category.Default.id) + ids) }
            },
            authenticate = appServices::authenticate,
        )
        MoreDestination.Backup -> {
            val backupTranslations = remember(appServices) {
                appServices.contentFeatures?.translation?.let { dev.shinsou.kmp.translation.ReaderTranslationBackups(it) }
            }
            BackupScreen(
            state = snapshot.backupState,
            automaticBackups = automaticBackups,
            onBack = onBack,
            onCreate = {
                actions.onUi {
                    val createdAt = Clock.System.now().toEpochMilliseconds()
                    val name = "shinsou_$createdAt.shinsoubackup"
                    // Only the status fields are written, to the state current then: an option the
                    // user just changed (paid translations) is neither undone nor exported against.
                    val payload = actions.stores {
                        repository.updateBackupState { it.copy(status = BackupStatus.CREATING, errorMessage = null) }
                        SnapshotBackupService.encode(
                            repository.createBackupEnvelope(createdAt, appVersion = dev.shinsou.kmp.buildinfo.SHINSOU_APP_VERSION)
                                .withTranslations(backupTranslations, repository.currentSnapshot.backupState),
                        )
                    }
                    // The platform's save dialog belongs to the UI thread.
                    val saved = appServices.exportDocument(name, payload)
                    actions.stores {
                        repository.updateBackupState { current ->
                            current.copy(
                                status = if (saved) BackupStatus.COMPLETED else BackupStatus.FAILED,
                                lastBackupAt = if (saved) Clock.System.now().toEpochMilliseconds() else current.lastBackupAt,
                                lastFileName = if (saved) name else current.lastFileName,
                                errorMessage = if (saved) null else strings.text(StringKey.BACKUP_EXPORT_WAS_CANCELLED),
                            )
                        }
                    }
                }
            },
            onRestore = {
                actions.onUi {
                    val document = appServices.importDocument(setOf("json", "shinsoubackup")) ?: return@onUi
                    actions.stores {
                    repository.updateBackupState { it.copy(status = BackupStatus.RESTORING, errorMessage = null) }
                    val encoded = document.contents.decodeToString()
                    // A format probe: a file that is not a backup envelope is imported as a plain snapshot.
                    val envelope = runCatchingCancellable { SnapshotBackupService.decode(encoded) }.getOrNull()
                    if (envelope != null) {
                        repository.restoreBackup(
                            envelope = envelope,
                            restoredAt = Clock.System.now().toEpochMilliseconds(),
                        )
                        envelope.translations?.let { backup -> backupTranslations?.restore(backup) }
                    } else {
                        repository.importSnapshot(encoded)
                    }
                    repository.updateBackupState {
                        it.copy(
                            status = BackupStatus.COMPLETED,
                            lastRestoreAt = Clock.System.now().toEpochMilliseconds(),
                            lastFileName = document.name,
                        )
                    }
                    }
                }
            },
            onAutomaticChange = { enabled ->
                actions.inStores { repository.updateBackupState { it.copy(automaticEnabled = enabled) } }
            },
            translationOptionsVisible = backupTranslations != null,
            onIncludePaidTranslationsChange = { include ->
                actions.inStores { repository.updateBackupState { it.copy(includePaidTranslations = include) } }
            },
            onIncludeFreeTranslationsChange = { include ->
                actions.inStores { repository.updateBackupState { it.copy(includeFreeTranslations = include) } }
            },
            onIntervalChange = { intervalHours ->
                actions.inStores { repository.updateBackupState { it.copy(intervalHours = intervalHours.coerceAtLeast(1)) } }
            },
            onRetentionChange = { retainedCount ->
                actions.onUi {
                    val retained = actions.stores {
                        repository.updateBackupState { it.copy(retainedBackupCount = retainedCount.coerceAtLeast(1)) }
                        autoBackupService?.enforceRetention()
                    }
                    if (retained != null) automaticBackups = retained
                }
            },
            onCreateAutomatic = {
                actions.onUi {
                    if (autoBackupService != null) {
                        automaticBackups = actions.stores {
                            autoBackupService.createNow()
                            autoBackupService.listBackups()
                        }
                    }
                }
            },
            onRefreshAutomatic = {
                actions.onUi {
                    if (autoBackupService != null) {
                        automaticBackups = actions.stores { autoBackupService.listBackups() }
                    }
                }
            },
            onRestoreAutomatic = { backup ->
                actions.onUi {
                    if (autoBackupService != null) {
                        automaticBackups = actions.stores {
                            autoBackupService.restore(backup.fileName)
                            autoBackupService.listBackups()
                        }
                    }
                }
            },
            onReset = { actions.inStores { repository.reset() } },
            onDeleteAutomatic = { backup ->
                actions.onUi {
                    if (autoBackupService != null) {
                        automaticBackups = actions.stores {
                            autoBackupService.delete(backup.fileName)
                            autoBackupService.listBackups()
                        }
                    }
                }
            },
        )
        }
        MoreDestination.ContentBackupV2 -> {
            val controller = appServices.portableContentBackupV2
            if (controller == null) {
                PortabilityUnavailablePane(
                    title = strings.text(StringKey.CONTENT_BACKUP_V2),
                    message = strings.text(
                        StringKey.CONTENT_BACKUP_IS_UNAVAILABLE_UNTIL_THE_SHARED,
                    ),
                    onBack = onBack,
                )
            } else {
                ContentBackupV2Screen(
                    controller = controller,
                    onChooseRestoreArchive = {
                        actions.onUi {
                            val document = appServices.importDocument(
                                acceptedExtensions = setOf("shinsou2"),
                                limits = ImportedDocumentLimits(
                                    maxBytesPerFile = DEFAULT_MAX_ARCHIVE_BYTES,
                                    randomAccessExtensions = setOf("shinsou2"),
                                ),
                            ) ?: return@onUi
                            actions.stores {
                            controller.inspectForRestore(
                                object : BackupV2ArchiveSource {
                                    override val byteSize: Long get() = document.byteSize
                                    override fun read(offset: Long, byteCount: Int): ByteArray =
                                        document.source.read(offset, byteCount)
                                },
                            )
                            }
                        }
                    },
                    onExportReady = { artifact ->
                        // The platform's save dialog belongs to the UI thread.
                        actions.onUi {
                            appServices.exportBinaryDocument(
                                artifact.suggestedFileName,
                                artifact,
                            )
                        }
                    },
                    onOpenShuYueMigration = {
                        onOpenDestination(MoreDestination.ShuYueMigration)
                    },
                    onBack = onBack,
                )
            }
        }
        MoreDestination.ShuYueMigration -> {
            val controller = appServices.shuYueMigration
            if (controller == null) {
                PortabilityUnavailablePane(
                    title = strings.text(StringKey.IMPORT_FROM_SHUYUE),
                    message = strings.text(
                        StringKey.SHUYUE_MIGRATION_IS_UNAVAILABLE_UNTIL_THE_SHARED,
                    ),
                    onBack = onBack,
                )
            } else {
                ShuYueMigrationScreen(
                    controller = controller,
                    onChooseBackup = {
                        actions.onUi {
                            val document = appServices.importDocument(
                                acceptedExtensions = setOf("json"),
                                limits = ImportedDocumentLimits(
                                    ShuYueBackupV1Limits.Default.maxRawBytes.toLong(),
                                ),
                            ) ?: return@onUi
                            actions.stores { controller.inspect(document.contents) }
                        }
                    },
                    onBack = onBack,
                )
            }
        }
        MoreDestination.Sync -> SyncScreen(
            appServices.sync,
            appServices::copyText,
            onBack,
            kinds = snapshot.settings.sync.kinds,
            onKindsChange = { edit ->
                actions.editSettings(repository) { it.copy(sync = it.sync.copy(kinds = edit(it.sync.kinds))) }
            },
        )
        MoreDestination.About -> AboutScreen(onBack, appServices::openExternalUrl)
    }
}

@Composable
private fun PortabilityUnavailablePane(
    title: String,
    message: String,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = title,
            leading = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, LocalShinsouStrings.current.text(StringKey.BACK))
                }
            },
        )
        EmptyState(
            title = LocalShinsouStrings.current.text(StringKey.UNAVAILABLE),
            message = message,
            icon = { Icon(Icons.Outlined.Lock, null) },
        )
    }
}

@Composable
internal fun DesktopSidebar(
    selected: MainSection,
    onSelect: (MainSection) -> Unit,
    downloadCount: Int,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 14.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(Res.drawable.shinsou_icon),
                    contentDescription = "Shinsou X",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Shinsou X", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(strings.library, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(12.dp))
            navigationItems(strings).forEach { item ->
                val active = selected == item.section
                Surface(
                    onClick = { onSelect(item.section) },
                    shape = RoundedCornerShape(9.dp),
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            if (active) item.selectedIcon else item.icon,
                            null,
                            Modifier.size(19.dp),
                            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            item.label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.section == MainSection.MORE && downloadCount > 0) {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary) {
                                Text(
                                    downloadCount.coerceAtMost(99).toString(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            Text(
                "Shinsou X ${dev.shinsou.kmp.buildinfo.SHINSOU_APP_VERSION}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(11.dp),
            )
        }
    }
}
