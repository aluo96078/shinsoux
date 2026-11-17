package dev.shinsou.kmp.ui.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import dev.shinsou.kmp.sync.v3.SyncV3Service
import dev.shinsou.kmp.domain.model.DownloadState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.contract.BrowseCallbacks
import dev.shinsou.kmp.contract.BrowseManga
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.ui.ExtensionFavoriteDestination
import dev.shinsou.kmp.ui.mutateExtensionFavorite
import dev.shinsou.kmp.features.ContentFeatureRuntime
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.plugin.v2.extensionPublicationKey
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.ui.ReaderVolumeKeyRouter
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerException
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationPageV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitPageProgressV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.overKnownDetails
import dev.shinsou.kmp.plugin.v2.withoutScriptObjectText
import dev.shinsou.kmp.ui.components.CoverImage
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// R4-4 (docs/architecture/0006): split out of BrowseScreen.kt without changing it.

/** Exact-keyed native extension path; host-issued selections remain the only content authority. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionV2PublicationPane(
    callbacks: BrowseCallbacks,
    sync: SyncV3Service? = null,
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
    /** True only when [refreshedPublication] came from the source, not from the saved copy. */
    var detailsFresh by remember(item.identityKey) { mutableStateOf(false) }
    var units by remember(item.identityKey) {
        mutableStateOf<List<ExtensionUnitSelectionV2>>(emptyList())
    }
    var nextPage by remember(item.identityKey) { mutableStateOf(0) }
    var hasNextPage by remember(item.identityKey) { mutableStateOf(false) }
    var unitDirectoryComplete by remember(item.identityKey) { mutableStateOf(false) }
    // A later page that came back empty may be a failed fetch; such a list must not replace the cache.
    var unitDirectoryPartial by remember(item.identityKey) { mutableStateOf(false) }
    // [units] holds a complete list of a library title (the saved one, or the last full refresh),
    // kept on screen until the next refresh has every page.
    var savedDirectoryShown by remember(item.identityKey) { mutableStateOf(false) }
    var loading by remember(item.identityKey) { mutableStateOf(true) }
    var loadingMore by remember(item.identityKey) { mutableStateOf(false) }
    var loadError by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var busyUnitId by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var openingReaderUnitId by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var operationMessage by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var operationFailed by remember(item.identityKey) { mutableStateOf(false) }
    var readerSession by remember(item.identityKey) {
        mutableStateOf<dev.shinsou.kmp.contract.TypedReaderContentSession?>(null)
    }
    var readerUnitId by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var pendingRepresentation by remember(item.identityKey) {
        mutableStateOf<PendingExtensionRepresentationSelection?>(null)
    }
    var favorite by remember(item.identityKey, localLibraryFavorite) {
        mutableStateOf(localLibrary && localLibraryFavorite)
    }
    var notes by remember(item.identityKey) { mutableStateOf("") }
    // Reviews and sharing need a signed-in account on a server that offers them. The work hash is
    // the same one the library record syncs under, so both work before and after adding the title.
    val syncState = sync?.state?.collectAsState()?.value
    val workHash = remember(sync, sourceKey, remotePublicationId) { sync?.sourceWorkHash(sourceKey, remotePublicationId) }
    val inLibrary = favorite && (localLibrary || favoriteDestination == ExtensionFavoriteDestination.LOCAL_LIBRARY)
    val reviewsWorkHash = workHash?.takeIf { syncState?.signedIn == true && syncState.reviewsEnabled }
    val shareWorkHash = workHash?.takeIf { syncState?.signedIn == true && syncState.sharingEnabled && inLibrary }
    var shareWithUserVisible by remember(item.identityKey) { mutableStateOf(false) }
    var selectedUnitIds by remember(item.identityKey) { mutableStateOf<Set<String>>(emptySet()) }
    var readUnitIds by remember(item.identityKey) { mutableStateOf<Set<String>>(emptySet()) }
    // Read/unread marks the user set by hand on this screen. Reading state loads in the
    // background, so a late result must not undo a mark made while it was loading.
    var manualReadMarks by remember(item.identityKey) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var resumeUnitId by remember(item.identityKey) { mutableStateOf<String?>(null) }
    var inProgressUnits by remember(item.identityKey) {
        mutableStateOf<Map<String, ExtensionUnitPageProgressV2>>(emptyMap())
    }
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
        ).withoutScriptObjectText()
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
                // Reading a chapter stores its body offline as well.
                downloadedUnitIds = downloadedUnitIds + selection.unit.remoteId
                if (opened != null) {
                    readerSession = opened
                    readerUnitId = selection.unit.remoteId
                    // Keep the parent layout in sync with the session creation frame. The
                    // lifecycle effect below remains a fallback, but relying on it alone lets
                    // desktop briefly (and sometimes permanently) retain the split catalogue
                    // pane while the novel reader is already visible.
                    onReaderVisibilityChanged(true)
                } else {
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
        val openedReaderSource = callbacks.state.collectAsState().value.sources
            .firstOrNull { it.sourceKey == sourceKey }
        ExtensionNovelReaderShell(
            title = openedReaderTitle,
            currentUnitTitle = openedReaderUnitTitle,
            sourceName = openedReaderSource?.name,
            // "all", "multi" and sources that say nothing leave it to the pages
            workLanguage = openedReaderSource?.language
                ?.takeIf { dev.shinsou.kmp.translation.OfflineModelCatalog.language(it) != null }.orEmpty(),
            units = units,
            activeUnitIndex = activeUnitIndex,
            busy = busyUnitId != null,
            onBack = {
                readerSession = null
                readerUnitId = null
                onReaderVisibilityChanged(false)
                // The shell calls back only after local progress is durable. Merge it so the
                // chapter list shows the page just reached; unsaved manual read marks survive.
                scope.launch {
                    runCatchingCancellable {
                        withContext(Dispatchers.Default) {
                            callbacks.extensionLocalReadingStateV2(
                                sourceKey,
                                remotePublicationId,
                                units.map { it.unit.remoteId },
                            )
                        }
                    }.getOrNull()?.let { localReading ->
                        readUnitIds = (readUnitIds + localReading.completedRemoteUnitIds).withReadMarks(manualReadMarks)
                        resumeUnitId = localReading.lastReadRemoteUnitId ?: resumeUnitId
                        inProgressUnits = localReading.inProgressRemoteUnits
                    }
                }
            },
            onOpenUnit = { selection -> materialize(selection, openReader = true) },
            session = openedReader,
            features = contentFeatures,
            copyText = copyText,
            readerSettings = readerSettings,
            onReaderSettingsChange = onReaderSettingsChange,
            onCompleted = {
                if (readerSession === openedReader) {
                    readerUnitId?.let { unitId ->
                        // Reading it again supersedes an earlier hand-set "unread".
                        manualReadMarks = manualReadMarks - unitId
                        readUnitIds = readUnitIds + unitId
                    }
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

    suspend fun fetchUnitPage(requestedPage: Int): ExtensionPublicationPageV2 {
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
        return loaded
    }

    suspend fun loadUnitPage(requestedPage: Int, append: Boolean) {
        val loaded = fetchUnitPage(requestedPage)
        if (append && loaded.units.isEmpty()) unitDirectoryPartial = true
        publicationPage = if (append) publicationPage ?: loaded else loaded
        units = if (append) {
            (units + loaded.units).distinctBy { it.unit.remoteId }
        } else {
            loaded.units
        }
        nextPage = requestedPage + 1
        // An empty later page ends pagination; following its hasNextPage could loop forever.
        hasNextPage = loaded.hasNextPage && !(append && loaded.units.isEmpty())
    }

    suspend fun loadInitialUnitDirectory() {
        if (localLibrary && savedDirectoryShown) {
            // The saved list is complete; replacing it with page 1 while later pages load would
            // move "Start reading" and the reader's adjacent chapters until the end. Fetch every
            // page first and swap the whole list in at once.
            val first = fetchUnitPage(0)
            if (first.units.isEmpty()) {
                unitDirectoryPartial = true
                return
            }
            var fetched = first.units
            var page = 1
            var more = first.hasNextPage
            while (more) {
                currentCoroutineContext().ensureActive()
                val next = fetchUnitPage(page)
                page++
                if (next.units.isEmpty()) {
                    // Possibly a failed fetch: keep the complete list on screen, not a truncated
                    // one (and, being partial, it is not written to the saved copy either).
                    unitDirectoryPartial = true
                    return
                }
                fetched = (fetched + next.units).distinctBy { it.unit.remoteId }
                more = next.hasNextPage
            }
            publicationPage = first
            units = fetched
            nextPage = page
            hasNextPage = false
            return
        }
        loadUnitPage(requestedPage = 0, append = false)
        // A local-library title must be self-contained when opened directly. Sources
        // that paginate long catalogues (100 units per page); leaving later pages behind a
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

    // Downloads run in the app-scoped queue so leaving this title does not cancel them.
    val extensionDownloads by callbacks.extensionDownloadsV2.collectAsState()
    val publicationDownloads = remember(extensionDownloads, sourceKey, remotePublicationId) {
        extensionDownloads.filter { it.sourceKey == sourceKey && it.remotePublicationId == remotePublicationId }
    }
    val queuedUnitIds = remember(publicationDownloads) {
        publicationDownloads
            .filter { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING }
            .mapTo(hashSetOf()) { it.remoteUnitId }
    }
    LaunchedEffect(publicationDownloads) {
        val finished = publicationDownloads
            .filter { it.state == DownloadState.DOWNLOADED }
            .mapTo(hashSetOf()) { it.remoteUnitId }
        if (!downloadedUnitIds.containsAll(finished)) downloadedUnitIds = downloadedUnitIds + finished
    }

    // Translating ahead of reading: the same app-wide queue idea as downloads.
    val translation = contentFeatures?.translation
    val mangaTranslation = LocalMangaTranslation.current
    val prefetchAppLanguage = dev.shinsou.kmp.ui.i18n.LocalAppLanguage.current
    val prefetchGroup = remember(sourceKey, remotePublicationId) { sourceKey.canonicalId + "|" + remotePublicationId }
    val prefetchEntries by (translation?.prefetch?.entries ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    val publicationPrefetch = remember(prefetchEntries, prefetchGroup) { prefetchEntries.filter { it.group == prefetchGroup } }
    val prefetchByUnit = remember(publicationPrefetch) { publicationPrefetch.associateBy { it.unit } }
    // Translating ahead fetches a chapter the way a download does: it is on the device from then on.
    val prefetchFetched = remember(publicationPrefetch) { publicationPrefetch.filter { it.fetched }.mapTo(hashSetOf()) { it.unit } }
    LaunchedEffect(prefetchFetched) {
        if (!downloadedUnitIds.containsAll(prefetchFetched)) downloadedUnitIds = downloadedUnitIds + prefetchFetched
    }
    var storedTranslatedUnitIds by remember(item.identityKey) { mutableStateOf<Set<String>>(emptySet()) }
    val finishedPrefetch = remember(publicationPrefetch) {
        publicationPrefetch.count { it.state == dev.shinsou.kmp.translation.PrefetchState.DONE }
    }
    LaunchedEffect(translation, units.size, finishedPrefetch) {
        if (translation == null || units.isEmpty()) return@LaunchedEffect
        val workKey = dev.shinsou.kmp.plugin.v2.extensionPublicationKey(sourceKey, remotePublicationId).value
        val keys = runCatchingCancellable { translation.translatedChapterKeys(workKey) }.getOrDefault(emptySet())
        storedTranslatedUnitIds = if (keys.isEmpty()) {
            emptySet()
        } else {
            withContext(Dispatchers.Default) {
                units.mapNotNullTo(hashSetOf()) { selection ->
                    selection.unit.remoteId.takeIf {
                        dev.shinsou.kmp.plugin.v2.extensionUnitKey(sourceKey, remotePublicationId, it).value in keys
                    }
                }
            }
        }
    }

    // Nothing is translated before the user has seen what into, from what, and through which service.
    var prefetchSetup by remember(item.identityKey) { mutableStateOf<List<ExtensionUnitSelectionV2>?>(null) }

    fun translateUnits(selections: List<ExtensionUnitSelectionV2>, onlyDownloaded: Boolean = false) {
        if (translation == null) return
        // Which of them are already translated depends on the language chosen next, not on
        // whether anything at all is stored for them.
        val candidates = selections.distinctBy { it.unit.remoteId }
        operationFailed = false
        if (candidates.isEmpty()) {
            operationMessage = if (onlyDownloaded) strings.text("No chapter of this title is downloaded yet.") else null
            return
        }
        operationMessage = null
        prefetchSetup = candidates
    }

    fun startPrefetch(selections: List<ExtensionUnitSelectionV2>, from: String?, to: String) {
        val features = contentFeatures ?: return
        val queue = translation?.prefetch ?: return
        val source = callbacks.state.value.sources.firstOrNull { it.sourceKey == sourceKey }
        val sourceName = source?.name
        val workLanguage = source?.language?.takeIf { dev.shinsou.kmp.translation.OfflineModelCatalog.language(it) != null }.orEmpty()
        val workTitle = publicationPage?.publication?.title ?: item.title
        val languages = dev.shinsou.kmp.translation.PrefetchLanguages(to = to, from = from)
        val jobs = selections
            .map { selection ->
                dev.shinsou.kmp.translation.TranslationPrefetchJob(
                    id = prefetchGroup + "|" + selection.unit.remoteId,
                    group = prefetchGroup,
                    title = selection.unit.title,
                    languages = languages,
                    unit = selection.unit.remoteId,
                    load = {
                        // Fetching the chapter stores it, exactly as a download does.
                        val materialization = try {
                            callbacks.materializeExtensionContentV2(selection, null)
                        } catch (choice: ExtensionContentConsumerException.RepresentationSelectionRequired) {
                            callbacks.materializeExtensionContentV2(selection, choice.availableIds.firstOrNull() ?: throw choice)
                        }
                        prefetchChapter(
                            callbacks.openMaterializedExtensionContentV2(materialization),
                            features,
                            dev.shinsou.kmp.translation.TranslationLabels(workTitle, selection.unit.title, sourceName),
                            manga = mangaTranslation,
                            workLanguage = workLanguage, appLanguage = prefetchAppLanguage,
                        )
                    },
                )
            }
        scope.launch {
            // Chapters already whole in this language, from the service in use, need nothing.
            val workKey = dev.shinsou.kmp.plugin.v2.extensionPublicationKey(sourceKey, remotePublicationId).value
            val done = runCatchingCancellable { translation?.translatedChapterKeys(workKey, to, from) }.getOrNull().orEmpty()
            val pending = if (done.isEmpty()) jobs else withContext(Dispatchers.Default) {
                jobs.filterNot { dev.shinsou.kmp.plugin.v2.extensionUnitKey(sourceKey, remotePublicationId, it.unit).value in done }
            }
            if (pending.isEmpty()) {
                operationMessage = strings.text("The selected chapters are already translated.")
                return@launch
            }
            val added = queue.enqueue(pending)
            operationMessage = if (added > 0) {
                strings.text("{0} chapters added to the translation queue.", added)
            } else {
                strings.text("The selected chapters are already translated or queued.")
            }
        }
    }

    val pendingPrefetch = prefetchSetup
    if (pendingPrefetch != null && translation != null) {
        PrefetchSetupDialog(
            translation = translation,
            workKey = remember(sourceKey, remotePublicationId) {
                dev.shinsou.kmp.plugin.v2.extensionPublicationKey(sourceKey, remotePublicationId).value
            },
            chapterCount = pendingPrefetch.size,
            onConfirm = { from, to ->
                prefetchSetup = null
                startPrefetch(pendingPrefetch, from, to)
            },
            onDismiss = { prefetchSetup = null },
        )
    }

    fun downloadUnits(selections: List<ExtensionUnitSelectionV2>) {
        // Chapters already offline or already queued are skipped; the queue re-checks storage too.
        val pending = selections
            .distinctBy { it.unit.remoteId }
            .filter { it.unit.remoteId !in downloadedUnitIds && it.unit.remoteId !in queuedUnitIds }
        operationMessage = null
        operationFailed = false
        if (pending.isEmpty()) {
            if (selections.isNotEmpty()) {
                operationMessage = strings.text("The selected chapters are already downloaded or queued.")
            }
            return
        }
        scope.launch {
            try {
                callbacks.enqueueExtensionDownloadsV2(pending)
                operationMessage = strings.text("{0} chapters added to the download queue.", pending.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                operationFailed = true
                operationMessage = error.localizedDiagnosticMessage(strings)
            }
        }
    }

    // Library titles keep their details and full chapter list on disk, refreshed on every
    // successful visit, so they open instantly and still work offline.
    suspend fun cacheLibraryCopy() {
        val page = publicationPage ?: return
        if (!localLibrary || !favorite) return
        // Details and the unit list are saved independently: a source whose chapter list failed
        // (or came from the offline copy) must still be able to update the saved introduction.
        val freshDetails = refreshedPublication?.takeIf { detailsFresh }
        val unitsFresh = unitDirectoryComplete && !unitDirectoryPartial && !page.offline
        if (freshDetails == null && !unitsFresh) return
        try {
            val cached = callbacks.cachedExtensionPublicationPageV2(sourceKey, remotePublicationId)
            val savedUnits = if (unitsFresh) units.map { it.unit } else cached?.units?.map { it.unit } ?: return
            // Without fresh details keep the cached ones; the unit page only carries the list hint.
            val details = freshDetails ?: cached?.publication ?: page.publication
            val stored = details.withFallbackThumbnail(cataloguePublication.thumbnailUrl).withoutScriptObjectText()
            callbacks.cacheExtensionPublicationV2(
                sourceKey = sourceKey,
                publication = stored,
                units = savedUnits,
            )
            // The library row keeps whatever title it was added with (sometimes a placeholder like
            // "Source #id"); bring it up to date so the library reads right offline.
            if (freshDetails != null && (
                    stored.title != item.title ||
                        (stored.thumbnailUrl != null && stored.thumbnailUrl != item.thumbnailUrl) ||
                        (stored.author != null && stored.author != item.author) ||
                        stored.description != item.description
                    )
            ) {
                onToggleLocalLibrary(item, stored, true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The cache is a convenience; the next successful visit writes it again.
        }
    }

    LaunchedEffect(item.identityKey, refreshGeneration) {
        loading = true
        unitDirectoryComplete = false
        unitDirectoryPartial = false
        loadError = null
        refreshedPublication = null
        detailsFresh = false
        if (localLibrary && favorite) {
            val cached = try {
                callbacks.cachedExtensionPublicationPageV2(sourceKey, remotePublicationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (cached != null && publicationPage == null) {
                publicationPage = cached
                units = cached.units
                // Only complete lists are saved (see cacheLibraryCopy).
                savedDirectoryShown = cached.units.isNotEmpty()
                refreshedPublication = cached.publication
                nextPage = 1
                hasNextPage = false
                loading = false
            }
        }
        // Keep the units request on the critical path. Wenku8 fetches the long introduction in
        // the full details call, so refresh metadata only after the first chapter page is ready.
        val detailsRefresh = launch(start = CoroutineStart.LAZY) {
            try {
                val fresh = withContext(Dispatchers.Default) {
                    callbacks.extensionDetailsV2(sourceKey, remotePublicationId)
                }
                // A source may answer offline with a placeholder title and no introduction.
                refreshedPublication = fresh.overKnownDetails(
                    refreshedPublication ?: publicationPage?.publication ?: cataloguePublication,
                )
                detailsFresh = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Catalogue (or cached) metadata remains if the background refresh fails.
            }
        }
        try {
            // Reading marks decode the whole stored publication (downloaded chapter text included):
            // off the UI thread, beside the chapter request, merged when ready.
            launch {
                val localReading = try {
                    withContext(Dispatchers.Default) {
                        callbacks.extensionLocalReadingStateV2(
                            sourceKey,
                            remotePublicationId,
                            units.map { it.unit.remoteId },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return@launch
                }
                readUnitIds = (readUnitIds + localReading.completedRemoteUnitIds).withReadMarks(manualReadMarks)
                resumeUnitId = localReading.lastReadRemoteUnitId ?: resumeUnitId
                inProgressUnits = localReading.inProgressRemoteUnits
            }
            // Offline bodies are durable; only the markers were screen-local before. Recover them
            // beside the chapter request and merge, so downloads finished meanwhile are kept.
            launch {
                val stored = try {
                    withContext(Dispatchers.Default) {
                        callbacks.extensionDownloadedUnitIdsV2(
                            extensionPublicationKey(sourceKey, remotePublicationId),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    emptySet()
                }
                downloadedUnitIds = downloadedUnitIds + stored
            }
            loadInitialUnitDirectory()
            unitDirectoryComplete = true
            if (!unitDirectoryPartial) savedDirectoryShown = true
            // Progress of units this device never opened (carried over by a library migration)
            // resolves only against the listed unit ids.
            launch {
                val listed = units.map { it.unit.remoteId }
                if (listed.isEmpty()) return@launch
                val localReading = try {
                    withContext(Dispatchers.Default) {
                        callbacks.extensionLocalReadingStateV2(sourceKey, remotePublicationId, listed)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return@launch
                }
                readUnitIds = (readUnitIds + localReading.completedRemoteUnitIds).withReadMarks(manualReadMarks)
                resumeUnitId = localReading.lastReadRemoteUnitId ?: resumeUnitId
                inProgressUnits = inProgressUnits + localReading.inProgressRemoteUnits
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            loadError = error.localizedDiagnosticMessage(strings)
        } finally {
            loading = false
            detailsRefresh.start()
        }
        detailsRefresh.join()
        cacheLibraryCopy()
    }

    // A details endpoint is allowed to omit the cover even when the catalogue supplied one.
    // Do not let a successful background refresh replace a usable catalogue cover with a
    // placeholder in the detail pane (novel APIs commonly split metadata across endpoints).
    val publication = refreshedPublication ?: publicationPage?.publication
    val displayPublication = publication
        ?.withFallbackThumbnail(cataloguePublication.thumbnailUrl)
        ?.withoutScriptObjectText()
        ?: cataloguePublication
    val targetUrl = (displayPublication.url ?: item.url).takeIf {
        it.startsWith("http://") || it.startsWith("https://")
    }

    fun retryInitialPage() {
        scope.launch {
            loading = true
            loadError = null
            publicationPage = null
            refreshedPublication = null
            detailsFresh = false
            units = emptyList()
            savedDirectoryShown = false
            nextPage = 0
            hasNextPage = false
            unitDirectoryComplete = false
            unitDirectoryPartial = false
            try {
                loadInitialUnitDirectory()
                unitDirectoryComplete = true
                if (!unitDirectoryPartial) savedDirectoryShown = true
                cacheLibraryCopy()
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

    val continueUnit = remember(localLibrary, unitDirectoryComplete, savedDirectoryShown, units, readUnitIds, resumeUnitId) {
        // A library title resumes from its complete list: the saved one until the refresh is done.
        if (localLibrary && !unitDirectoryComplete && !savedDirectoryShown) return@remember null
        extensionContinueUnitId(
            unitIds = units.map { it.unit.remoteId },
            completedUnitIds = readUnitIds,
            resumeUnitId = resumeUnitId,
        )?.let { target -> units.firstOrNull { it.unit.remoteId == target } }
    }
    val continueDetail = continueUnit
        ?.takeIf { it.unit.remoteId == resumeUnitId || it.unit.remoteId in inProgressUnits }
        ?.let { unit ->
            extensionContinueReadingDetail(
                strings = strings,
                unitTitle = unit.unit.title,
                progress = inProgressUnits[unit.unit.remoteId]?.takeIf { unit.unit.remoteId !in readUnitIds },
            )
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
                if (localLibrary) {
                    if (next) {
                        cacheLibraryCopy()
                    } else {
                        runCatchingCancellable { callbacks.forgetCachedExtensionPublicationV2(sourceKey, remotePublicationId) }
                    }
                }
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

    val chapterPane = ExtensionV2ChapterPaneModel(
        units = units,
        selectedUnitIds = selectedUnitIds,
        readUnitIds = readUnitIds,
        inProgressUnits = inProgressUnits,
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
        queuedUnitIds = queuedUnitIds,
        openingReaderUnitId = openingReaderUnitId,
        onRetry = ::retryInitialPage,
        onLoadMore = ::loadMore,
        onReadUnit = { materialize(it, openReader = true) },
        onDownloadUnits = { downloadUnits(it) },
        onTranslateUnits = if (translation != null) ({ translateUnits(it) }) else null,
        translatedUnitIds = storedTranslatedUnitIds,
        prefetchByUnit = prefetchByUnit,
        onSelectionChange = { selectedUnitIds = it },
        onReadStateChange = { ids, read ->
            manualReadMarks = manualReadMarks + ids.associateWith { read }
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
    )
    val visibleChapterUnits = rememberVisibleChapterUnits(chapterPane)

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wideLayout = maxWidth >= 900.dp
            if (wideLayout) {
                Row(Modifier.fillMaxSize()) {
                    ExtensionV2PublicationInfoPane(
                        publication = displayPublication,
                        sourceKey = sourceKey,
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
                        continueDetail = continueDetail,
                        onToggleFavorite = ::toggleFavorite,
                        onRefresh = ::refreshPublication,
                        onShare = {
                            onShareText(displayPublication.title, targetUrl ?: displayPublication.title)
                        },
                        onShareWithUser = if (shareWorkHash != null) ({ shareWithUserVisible = true }) else null,
                        reviewsEntry = if (sync != null && reviewsWorkHash != null) ({
                            ReviewsEntry(sync, reviewsWorkHash, displayPublication.title, inLibrary)
                        }) else null,
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
                        onTranslateAll = if (translation != null) ({ translateUnits(units) }) else null,
                        onTranslateDownloaded = if (translation != null) {
                            { translateUnits(units.filter { it.unit.remoteId in downloadedUnitIds }, onlyDownloaded = true) }
                        } else {
                            null
                        },
                        prefetch = publicationPrefetch,
                        onCancelPrefetch = { scope.launch { translation?.prefetch?.cancel(prefetchGroup) } },
                        onClearPrefetch = { scope.launch { translation?.prefetch?.clearFinished(prefetchGroup) } },
                        onContinueReading = continueUnit?.let { { materialize(it, openReader = true) } },
                        onBack = onBack,
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
                            publication = displayPublication,
                            sourceKey = sourceKey,
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
                        continueDetail = continueDetail,
                            onToggleFavorite = ::toggleFavorite,
                            onRefresh = ::refreshPublication,
                            onShare = {
                                onShareText(displayPublication.title, targetUrl ?: displayPublication.title)
                            },
                            onShareWithUser = if (shareWorkHash != null) ({ shareWithUserVisible = true }) else null,
                            reviewsEntry = if (sync != null && reviewsWorkHash != null) ({
                                ReviewsEntry(sync, reviewsWorkHash, displayPublication.title, inLibrary)
                            }) else null,
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
                            onTranslateAll = if (translation != null) ({ translateUnits(units) }) else null,
                        onTranslateDownloaded = if (translation != null) {
                            { translateUnits(units.filter { it.unit.remoteId in downloadedUnitIds }, onlyDownloaded = true) }
                        } else {
                            null
                        },
                            prefetch = publicationPrefetch,
                            onCancelPrefetch = { scope.launch { translation?.prefetch?.cancel(prefetchGroup) } },
                            onClearPrefetch = { scope.launch { translation?.prefetch?.clearFinished(prefetchGroup) } },
                            onContinueReading = continueUnit?.let { { materialize(it, openReader = true) } },
                            onBack = onBack,
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

    if (shareWithUserVisible && sync != null && shareWorkHash != null) {
        ShareWithUserDialog(
            sync = sync,
            workHash = shareWorkHash,
            title = displayPublication.title,
            onDismiss = { shareWithUserVisible = false },
        )
    }
}

/** Native publication metadata pane deliberately mirrors MangaInfoPane's layout and actions. */
@Composable
private fun ExtensionV2PublicationInfoPane(
    publication: RemotePublicationV2,
    sourceKey: dev.shinsou.kmp.domain.model.SourceKey,
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
    continueDetail: String?,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onShareWithUser: (() -> Unit)? = null,
    reviewsEntry: (@Composable () -> Unit)? = null,
    onOpenWeb: () -> Unit,
    onOpenTracking: () -> Unit,
    onUpdateNotes: (String) -> Unit,
    onDownloadAll: () -> Unit,
    /** Queues every chapter for translation ahead of reading (fetching those not stored yet); null where there is no translation. */
    onTranslateAll: (() -> Unit)? = null,
    /** Queues only the chapters that are already downloaded: nothing new is fetched from the source. */
    onTranslateDownloaded: (() -> Unit)? = null,
    prefetch: List<dev.shinsou.kmp.translation.PrefetchEntry> = emptyList(),
    onCancelPrefetch: () -> Unit = {},
    onClearPrefetch: () -> Unit = {},
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
            if (onShareWithUser != null) {
                IconButton(onClick = onShareWithUser) { Icon(Icons.Outlined.Send, strings.text("Share with a user")) }
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
                        Text(strings.text("Translate chapters ahead"))
                    }
                    DropdownMenu(expanded = translateMenuVisible, onDismissRequest = { translateMenuVisible = false }) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(strings.text("Translate all chapters"))
                                    Text(
                                        strings.text("Chapters that are not downloaded yet are downloaded as well."),
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
                                        Text(strings.text("Translate downloaded chapters"))
                                        Text(
                                            strings.text("Only what is already on this device; nothing new is downloaded."),
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
                Text(if (continueReadingLoading) strings.text("Loading") else continueLabel)
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
                ?: strings.text("No description available."),
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
