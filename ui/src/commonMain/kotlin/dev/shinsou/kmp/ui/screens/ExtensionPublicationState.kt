package dev.shinsou.kmp.ui.screens

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.async
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.extensionPublicationKey
import dev.shinsou.kmp.domain.model.extensionUnitKey
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.plugin.ContentAcquisition
import dev.shinsou.kmp.plugin.ExtensionLibraryBinding
import dev.shinsou.kmp.plugin.ExtensionPublicationCatalog
import dev.shinsou.kmp.plugin.SourceCatalog
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerException
import dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationPageV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitPageProgressV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.overKnownDetails
import dev.shinsou.kmp.plugin.v2.withoutScriptObjectText
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.ui.ExtensionFavoriteDestination
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.mutateExtensionFavorite
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The parts of the extension bridge a publication page uses. */
internal class ExtensionPublicationServices(
    val catalog: SourceCatalog,
    val publications: ExtensionPublicationCatalog,
    val library: ExtensionLibraryBinding,
    val acquisition: ContentAcquisition,
)

/** Where a publication's favorite lives, and whether it is one. */
internal data class PublicationFavorites(
    val supportsFavorite: Boolean,
    val destination: ExtensionFavoriteDestination,
    /** The page shows a title of the app's own library (offline copy, complete chapter list). */
    val localLibrary: Boolean,
    val localLibraryFavorite: Boolean,
)

/** How the publication page fits into its parent. */
internal class PublicationPaneNavigation(
    val refreshGeneration: Long,
    /** One-shot: a change asks the open reader to go back once. */
    val readerBackRequest: Long = 0L,
    val onBack: () -> Unit,
    val reader: PublicationReaderEvents,
    /** After a restart: the chapter to reopen once the chapter list is loaded. */
    val reopen: PublicationReaderReopen? = null,
)

/** What the parent is told about, and asked about, the reader a publication page opens. */
internal class PublicationReaderEvents(
    val onVisibilityChanged: (Boolean) -> Unit,
    /** Told the unit the reader shows (its source id), or null when the reader closes. */
    val onUnitChanged: (String?) -> Unit = {},
    /**
     * Whether this title is still the page the shell shows. Read when a chapter finishes loading:
     * a page the user already left (still drawn while it animates away) opens nothing.
     */
    val isShown: () -> Boolean = { true },
)

/** The reader's loading screen over a publication page ([ExtensionPublicationState.readerCover]). */
internal class ReaderCover(
    /** The chapter opening ([dev.shinsou.kmp.ui.extensionUnitIdentity]). */
    val unit: Long,
    val onGiveUp: () -> Unit = {},
    /** Why it did not open; null while it is opening. */
    val failure: String? = null,
)

/** A chapter ([dev.shinsou.kmp.ui.extensionUnitIdentity]) to reopen; [onHandled] once it was tried. */
internal class PublicationReaderReopen(
    val unit: Long,
    /** The user asked for it ("continue reading"), rather than a restart restoring it. */
    val userRequested: Boolean,
    /** False once the user opened something else (the same title again included): it is given up. */
    val stillWanted: () -> Boolean,
    val onHandled: () -> Unit,
)

/**
 * R4-3 (docs/architecture/0006): one extension publication's page — its details and chapter list
 * (the saved copy of a library title first, then every page from the source), reading and download
 * marks, favorites, downloads, translation ahead of reading and the reader session it opens.
 * Created for one publication ([BrowseManga.identityKey]); the composable only draws it.
 */
internal class ExtensionPublicationState(
    val item: BrowseManga,
    internal val services: ExtensionPublicationServices,
    initialFavorites: PublicationFavorites,
    internal val reader: ExtensionReaderHost,
    internal val work: ScreenWork,
) {
    /** Follows the live source list ([syncFavorites]): a removed source favorites locally. */
    private var favorites by mutableStateOf(initialFavorites)

    val sourceKey: SourceKey = requireNotNull(item.sourceKey)
    val remotePublicationId: String = requireNotNull(item.remotePublicationId)
    val localLibrary: Boolean get() = favorites.localLibrary
    val supportsFavorite: Boolean get() = favorites.supportsFavorite

    /** Groups this title's translation-ahead jobs in the app-wide queue. */
    val prefetchGroup: String = sourceKey.canonicalId + "|" + remotePublicationId
    val workKey: String = extensionPublicationKey(sourceKey, remotePublicationId).value

    /**
     * Catalogue metadata is already available when the user taps a result: the immediate detail
     * hint, so chapters do not wait for a second metadata roundtrip.
     */
    val cataloguePublication: RemotePublicationV2 = RemotePublicationV2(
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

    var publicationPage by mutableStateOf<ExtensionPublicationPageV2?>(null)
        private set
    var refreshedPublication by mutableStateOf<RemotePublicationV2?>(null)
        private set

    /** True only when [refreshedPublication] came from the source, not from the saved copy. */
    private var detailsFresh by mutableStateOf(false)
    var units by mutableStateOf<List<ExtensionUnitSelectionV2>>(emptyList())
        private set
    private var nextPage by mutableIntStateOf(0)
    var hasNextPage by mutableStateOf(false)
        private set
    var unitDirectoryComplete by mutableStateOf(false)
        private set

    /** A later page came back empty and may be a failed fetch; such a list must not replace the cache. */
    private var unitDirectoryPartial by mutableStateOf(false)

    /**
     * [units] holds a complete list of a library title (the saved one, or the last full refresh),
     * kept on screen until the next refresh has every page.
     */
    var savedDirectoryShown by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var busyUnitId by mutableStateOf<String?>(null)
        private set

    /** The user opened or stored a chapter here: a restored chapter no longer replaces it. */
    internal var unitChosenByUser = false
        private set
    var openingReaderUnitId by mutableStateOf<String?>(null)
        private set

    /**
     * The reader was closed while [openingReaderUnitId] was loading (Back while moving to another
     * chapter): that chapter is still stored offline but no longer opens over the page.
     */
    internal var readerClosedWhileOpening = false
    var operationMessage by mutableStateOf<String?>(null)
    var operationFailed by mutableStateOf(false)
        private set
    var readerSession by mutableStateOf<TypedReaderContentSession?>(null)
        private set
    var readerUnitId by mutableStateOf<String?>(null)
        private set
    var pendingRepresentation by mutableStateOf<PendingExtensionRepresentationSelection?>(null)

    /**
     * The reader's loading screen over this page while "continue reading" (or a reader restored
     * after a restart) opens its chapter: the page underneath cannot be tapped meanwhile, and Back
     * gives the open up. Null when nothing covers the page.
     */
    var readerCover by mutableStateOf<ReaderCover?>(null)
        internal set
    var favorite by mutableStateOf(favorites.localLibrary && favorites.localLibraryFavorite)
        private set
    private var favoriteKey = favorites.localLibraryFavorite
    var notes by mutableStateOf("")
    var shareWithUserVisible by mutableStateOf(false)
    var selectedUnitIds by mutableStateOf<Set<String>>(emptySet())
    var readUnitIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Read/unread marks the user set by hand on this screen. Reading state loads in the
     * background, so a late result must not undo a mark made while it was loading.
     */
    private var manualReadMarks by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var resumeUnitId by mutableStateOf<String?>(null)
        private set
    var inProgressUnits by mutableStateOf<Map<String, ExtensionUnitPageProgressV2>>(emptyMap())
        private set
    var bookmarkedUnitIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var downloadedUnitIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var chapterFilter by mutableStateOf(ExtensionChapterFilter.ALL)
    var reverseWebsiteOrder by mutableStateOf(false)
    var storedTranslatedUnitIds by mutableStateOf<Set<String>>(emptySet())
        internal set

    /** Chapters waiting for the user to choose what to translate into (nothing starts before that). */
    var prefetchSetup by mutableStateOf<List<ExtensionUnitSelectionV2>?>(null)

    /** Told when the reader opens or closes; replaced on every composition. */
    var onReaderVisibilityChanged: (Boolean) -> Unit = {}

    /** Told which unit the reader shows, null once it closes; replaced on every composition. */
    var onReaderUnitChanged: (String?) -> Unit = {}

    /** Whether the shell still shows this page ([PublicationReaderEvents.isShown]); replaced on every composition. */
    var isShown: () -> Boolean = { true }

    /**
     * The page's parent recomputed [next] (where a favorite goes, whether the library has it):
     * the favorite button follows it from now on.
     */
    fun syncFavorites(next: PublicationFavorites) {
        favorites = next
        syncLocalFavorite(next.localLibraryFavorite)
    }

    /** The library said whether this title is a favorite: a change replaces the page's own state. */
    fun syncLocalFavorite(localLibraryFavorite: Boolean) {
        if (favoriteKey == localLibraryFavorite) return
        favoriteKey = localLibraryFavorite
        favorite = favorites.localLibrary && localLibraryFavorite
    }

    /** In the app's library (a local favorite), where sharing is offered. */
    val inLibrary: Boolean
        get() = favorite && (favorites.localLibrary || favorites.destination == ExtensionFavoriteDestination.LOCAL_LIBRARY)

    /** The details shown: refreshed, else the unit page's, else the catalogue's (keeping its cover). */
    val displayPublication: RemotePublicationV2
        get() = (refreshedPublication ?: publicationPage?.publication)
            // A details endpoint may omit the cover even when the catalogue supplied one (novel
            // APIs commonly split metadata across endpoints): keep the usable catalogue cover.
            ?.withFallbackThumbnail(cataloguePublication.thumbnailUrl)
            ?.withoutScriptObjectText()
            ?: cataloguePublication

    /** The title's web page, when it has one. */
    val targetUrl: String?
        get() = (displayPublication.url ?: item.url).takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /** The chapter "continue reading" opens; a library title waits for its complete list. */
    val continueUnit: ExtensionUnitSelectionV2?
        get() {
            if (favorites.localLibrary && !unitDirectoryComplete && !savedDirectoryShown) return null
            val target = extensionContinueUnitId(
                unitIds = units.map { it.unit.remoteId },
                completedUnitIds = readUnitIds,
                resumeUnitId = resumeUnitId,
            )
            return target?.let { id -> units.firstOrNull { it.unit.remoteId == id } }
        }

    private fun mergeReadingState(localReading: ExtensionLocalReadingStateV2, keepInProgress: Boolean) {
        readUnitIds = (readUnitIds + localReading.completedRemoteUnitIds).withReadMarks(manualReadMarks)
        resumeUnitId = localReading.lastReadRemoteUnitId ?: resumeUnitId
        inProgressUnits = if (keepInProgress) inProgressUnits + localReading.inProgressRemoteUnits else localReading.inProgressRemoteUnits
    }

    private suspend fun readingState(unitIds: List<String>): ExtensionLocalReadingStateV2 =
        withContext(work.workDispatcher) {
            services.library.extensionLocalReadingStateV2(sourceKey, remotePublicationId, unitIds)
        }

    /**
     * Opens [selection] in the reader (or only stores it offline, [openReader] false); false when
     * another chapter is opening. [onSettled] runs once the open ended, opened or not.
     */
    fun materialize(
        selection: ExtensionUnitSelectionV2,
        representationId: String? = null,
        openReader: Boolean = true,
        onSettled: () -> Unit = {},
        /**
         * A restored chapter reopening ([reopenUnit]) rather than the user's choice: whether it is
         * still wanted, asked before it opens and again before it shows.
         */
        restoring: (() -> Boolean)? = null,
    ): Boolean {
        if (busyUnitId != null || restoring?.invoke() == false) return false
        if (restoring == null) unitChosenByUser = true
        busyUnitId = selection.unit.remoteId
        if (openReader) openingReaderUnitId = selection.unit.remoteId
        readerClosedWhileOpening = false
        operationMessage = null
        operationFailed = false
        work.scope.launch {
            work.awaitFrame()
            try {
                runCatchingCancellable { withContext(work.workDispatcher) { open(selection, representationId, openReader) } }
                    // The user opened something else meanwhile: the restored chapter neither takes
                    // over nor reports how its open went.
                    .takeIf { restoring?.invoke() != false }
                    ?.onSuccess { opened -> afterMaterialized(selection, opened) }
                    ?.onFailure { failure -> onMaterializeFailure(selection, openReader, failure) }
            } finally {
                busyUnitId = null
                if (openReader) openingReaderUnitId = null
                onSettled()
            }
        }
        return true
    }

    private suspend fun open(
        selection: ExtensionUnitSelectionV2,
        representationId: String?,
        openReader: Boolean,
    ): TypedReaderContentSession? {
        val materialization = services.acquisition.materializeExtensionContentV2(selection, representationId)
        return if (openReader && reader.contentFeatures != null) {
            services.acquisition.openMaterializedExtensionContentV2(materialization)
        } else {
            null
        }
    }

    private fun afterMaterialized(selection: ExtensionUnitSelectionV2, opened: TypedReaderContentSession?) {
        // Reading a chapter stores its body offline as well.
        downloadedUnitIds = downloadedUnitIds + selection.unit.remoteId
        if (opened != null && (readerClosedWhileOpening || !isShown())) {
            // The user left the reader, or the whole page, while this chapter loaded: it stays
            // stored, unopened.
            readerClosedWhileOpening = false
        } else if (opened != null) {
            readerSession = opened
            // The reader shows: no loading screen (nor an earlier failed one) waits beneath it.
            readerCover = null
            readerUnitId = selection.unit.remoteId
            onReaderUnitChanged(selection.unit.remoteId)
            // Keep the parent layout in sync with the session creation frame. The lifecycle
            // effect remains a fallback, but relying on it alone lets desktop briefly (and
            // sometimes permanently) retain the split catalogue pane while the reader is visible.
            onReaderVisibilityChanged(true)
        } else {
            operationMessage = work.strings().text(StringKey.CONTENT_SAVED_FOR_OFFLINE_READING)
        }
    }

    private fun onMaterializeFailure(selection: ExtensionUnitSelectionV2, openReader: Boolean, failure: Throwable) {
        if (failure is ExtensionContentConsumerException.RepresentationSelectionRequired) {
            pendingRepresentation = PendingExtensionRepresentationSelection(
                selection = selection,
                representationIds = failure.availableIds,
                openReader = openReader,
            )
        } else {
            operationFailed = true
            operationMessage = work.describe(failure)
        }
    }

    /** One of the offered representations was chosen. */
    fun chooseRepresentation(pending: PendingExtensionRepresentationSelection, representationId: String) {
        pendingRepresentation = null
        materialize(pending.selection, representationId, pending.openReader)
    }

    /**
     * The reader closed. Its shell calls back only after local progress is durable: merge it so
     * the chapter list shows the page just reached; unsaved manual read marks survive.
     */
    fun closeReader() {
        if (openingReaderUnitId != null) readerClosedWhileOpening = true
        readerSession = null
        readerUnitId = null
        onReaderUnitChanged(null)
        onReaderVisibilityChanged(false)
        work.scope.launch {
            runCatchingCancellable { readingState(units.map { it.unit.remoteId }) }
                .onFailure { Log.warn("Extension", it) { "Reloading extension reading state failed" } }
                .getOrNull()?.let { mergeReadingState(it, keepInProgress = false) }
        }
    }

    /** The reader of [session] finished its chapter: reading it again supersedes a hand-set "unread". */
    fun onReaderCompleted(session: TypedReaderContentSession) {
        if (readerSession !== session) return
        readerUnitId?.let { unitId ->
            manualReadMarks = manualReadMarks - unitId
            readUnitIds = readUnitIds + unitId
        }
    }

    /** Offline bodies finished elsewhere (the download queue, translation ahead of reading). */
    fun markDownloaded(unitIds: Set<String>) {
        if (!downloadedUnitIds.containsAll(unitIds)) downloadedUnitIds = downloadedUnitIds + unitIds
    }

    fun setReadState(ids: Set<String>, read: Boolean) {
        manualReadMarks = manualReadMarks + ids.associateWith { read }
        readUnitIds = if (read) readUnitIds + ids else readUnitIds - ids
    }

    fun setBookmarked(ids: Set<String>, bookmarked: Boolean) {
        bookmarkedUnitIds = if (bookmarked) bookmarkedUnitIds + ids else bookmarkedUnitIds - ids
    }

    fun clearDownloadMarks(ids: Set<String>) {
        downloadedUnitIds = downloadedUnitIds - ids
        operationMessage = work.strings().text(StringKey.OFFLINE_MARKERS_CLEARED_FOR_X_CHAPTERS, ids.size)
    }

    /** The page has no web address: says so instead of opening one. */
    fun reportMissingUrl(key: StringKey) {
        operationMessage = work.strings().text(key)
    }

    private suspend fun fetchUnitPage(requestedPage: Int): ExtensionPublicationPageV2 {
        val loaded = withContext(work.workDispatcher) {
            services.publications.extensionPublicationUnitsPageV2(
                sourceKey = sourceKey,
                remotePublicationId = remotePublicationId,
                publication = publicationPage?.publication ?: cataloguePublication,
                page = requestedPage,
            )
        }
        check(loaded.sourceKey == sourceKey) { "Extension publication source identity changed" }
        check(loaded.publication.remoteId == remotePublicationId) { "Extension publication identity changed" }
        check(loaded.page == requestedPage) { "Extension unit page identity changed" }
        return loaded
    }

    private suspend fun loadUnitPage(requestedPage: Int, append: Boolean) {
        val loaded = fetchUnitPage(requestedPage)
        if (append && loaded.units.isEmpty()) unitDirectoryPartial = true
        publicationPage = if (append) publicationPage ?: loaded else loaded
        units = if (append) (units + loaded.units).distinctBy { it.unit.remoteId } else loaded.units
        nextPage = requestedPage + 1
        // An empty later page ends pagination; following its hasNextPage could loop forever.
        hasNextPage = loaded.hasNextPage && !(append && loaded.units.isEmpty())
    }

    private suspend fun loadInitialUnitDirectory() {
        if (favorites.localLibrary && savedDirectoryShown) {
            replaceSavedDirectory()
            return
        }
        loadUnitPage(requestedPage = 0, append = false)
        // A local-library title must be self-contained when opened directly. Sources paginate
        // long catalogues (100 units per page); leaving later pages behind a manual button makes
        // the saved resume unit and adjacent chapter navigation disappear.
        if (favorites.localLibrary) {
            while (hasNextPage) {
                currentCoroutineContext().ensureActive()
                val page = nextPage
                check(page > 0) { "Extension unit pagination did not advance" }
                loadUnitPage(requestedPage = page, append = true)
            }
        }
    }

    /**
     * The saved list is complete; replacing it with page 1 while later pages load would move
     * "Start reading" and the reader's adjacent chapters until the end. Every page is fetched
     * first and the whole list swapped in at once. An empty page may be a failed fetch: the
     * complete list stays on screen (and, being partial, is not written to the saved copy).
     */
    private suspend fun replaceSavedDirectory() {
        val first = fetchUnitPage(0)
        var fetched = first.units
        var page = 1
        var more = first.hasNextPage
        var partial = first.units.isEmpty()
        while (more && !partial) {
            currentCoroutineContext().ensureActive()
            val next = fetchUnitPage(page)
            page++
            partial = next.units.isEmpty()
            fetched = (fetched + next.units).distinctBy { it.unit.remoteId }
            more = next.hasNextPage
        }
        if (partial) {
            unitDirectoryPartial = true
        } else {
            publicationPage = first
            units = fetched
            nextPage = page
            hasNextPage = false
        }
    }

    /**
     * Library titles keep their details and full chapter list on disk, refreshed on every
     * successful visit, so they open instantly and still work offline. Details and the unit list
     * are saved independently: a source whose chapter list failed (or came from the offline copy)
     * must still be able to update the saved introduction.
     */
    private suspend fun cacheLibraryCopy() {
        val page = publicationPage?.takeIf { favorites.localLibrary && favorite } ?: return
        val freshDetails = refreshedPublication?.takeIf { detailsFresh }
        val unitsFresh = unitDirectoryComplete && !unitDirectoryPartial && !page.offline
        if (freshDetails == null && !unitsFresh) return
        runCatchingCancellable { writeLibraryCopy(page, freshDetails, unitsFresh) }
            // The cache is a convenience; the next successful visit writes it again.
            .onFailure { Log.warn("Extension", it) { "Caching the extension publication failed" } }
    }

    private suspend fun writeLibraryCopy(page: ExtensionPublicationPageV2, freshDetails: RemotePublicationV2?, unitsFresh: Boolean) {
        val cached = services.publications.cachedExtensionPublicationPageV2(sourceKey, remotePublicationId)
        val savedUnits = if (unitsFresh) units.map { it.unit } else cached?.units?.map { it.unit }
        // Without fresh details keep the cached ones; the unit page only carries the list hint.
        val details = freshDetails ?: cached?.publication ?: page.publication
        val stored = details.withFallbackThumbnail(cataloguePublication.thumbnailUrl).withoutScriptObjectText()
        if (savedUnits == null) return
        services.publications.cacheExtensionPublicationV2(sourceKey = sourceKey, publication = stored, units = savedUnits)
        // The library row keeps whatever title it was added with (sometimes a placeholder like
        // "Source #id"); bring it up to date so the library reads right offline.
        val changed = stored.title != item.title ||
            (stored.thumbnailUrl != null && stored.thumbnailUrl != item.thumbnailUrl) ||
            (stored.author != null && stored.author != item.author) ||
            stored.description != item.description
        if (freshDetails != null && changed) reader.library.setFavorite(item, stored, true)
    }

    private suspend fun showSavedCopy() {
        val cached = runCatchingCancellable {
            services.publications.cachedExtensionPublicationPageV2(sourceKey, remotePublicationId)
        }.onFailure { Log.warn("Extension", it) { "Reading the cached extension publication failed" } }.getOrNull()
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

    private suspend fun refreshDetails() {
        runCatchingCancellable {
            withContext(work.workDispatcher) { services.publications.extensionDetailsV2(sourceKey, remotePublicationId) }
        }.onSuccess { fresh ->
            // A source may answer offline with a placeholder title and no introduction.
            refreshedPublication = fresh.overKnownDetails(refreshedPublication ?: publicationPage?.publication ?: cataloguePublication)
            detailsFresh = true
        }.onFailure {
            // Catalogue (or cached) metadata remains if the background refresh fails.
            Log.warn("Extension", it) { "Refreshing extension publication details failed; keeping cached metadata" }
        }
    }

    /** Loads the page: the saved copy of a library title first, then the source; runs until done. */
    suspend fun load() = coroutineScope {
        loading = true
        unitDirectoryComplete = false
        unitDirectoryPartial = false
        loadError = null
        refreshedPublication = null
        detailsFresh = false
        if (favorites.localLibrary && favorite) showSavedCopy()
        // Keep the units request on the critical path. Wenku8 fetches the long introduction in
        // the full details call, so refresh metadata only after the first chapter page is ready.
        val detailsRefresh = launch(start = CoroutineStart.LAZY) { refreshDetails() }
        try {
            // Reading marks decode the whole stored publication (downloaded chapter text included):
            // off the UI thread, beside the chapter request, merged when ready.
            launch { loadReadingMarks(units.map { it.unit.remoteId }, "Loading extension reading state failed; leaving progress unchanged") }
            // Offline bodies are durable; only the markers were screen-local before. Recover them
            // beside the chapter request and merge, so downloads finished meanwhile are kept.
            launch { loadDownloadMarks() }
            runCatchingCancellable {
                loadInitialUnitDirectory()
                unitDirectoryComplete = true
                if (!unitDirectoryPartial) savedDirectoryShown = true
                // Progress of units this device never opened (carried over by a library migration)
                // resolves only against the listed unit ids.
                launch { loadCarriedOverProgress() }
            }.onFailure { loadError = work.describe(it) }
        } finally {
            loading = false
            detailsRefresh.start()
        }
        detailsRefresh.join()
        cacheLibraryCopy()
    }

    private suspend fun loadReadingMarks(unitIds: List<String>, failureMessage: String) {
        runCatchingCancellable { readingState(unitIds) }
            .onSuccess { mergeReadingState(it, keepInProgress = false) }
            .onFailure { Log.warn("Extension", it) { failureMessage } }
    }

    private suspend fun loadDownloadMarks() {
        val stored = runCatchingCancellable {
            withContext(work.workDispatcher) {
                services.library.extensionDownloadedUnitIdsV2(extensionPublicationKey(sourceKey, remotePublicationId))
            }
        }.onFailure { Log.warn("Extension", it) { "Loading the downloaded extension units failed" } }.getOrDefault(emptySet())
        downloadedUnitIds = downloadedUnitIds + stored
    }

    private suspend fun loadCarriedOverProgress() {
        val listed = units.map { it.unit.remoteId }
        if (listed.isEmpty()) return
        runCatchingCancellable { readingState(listed) }
            .onSuccess { mergeReadingState(it, keepInProgress = true) }
            .onFailure { Log.warn("Extension", it) { "Loading carried-over reading progress failed" } }
    }

    /** Loads the chapter list again from its first page. */
    fun retry() {
        work.scope.launch {
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
                runCatchingCancellable {
                    loadInitialUnitDirectory()
                    unitDirectoryComplete = true
                    if (!unitDirectoryPartial) savedDirectoryShown = true
                    cacheLibraryCopy()
                }.onFailure { loadError = work.describe(it) }
            } finally {
                loading = false
            }
        }
    }

    /** The toolbar's refresh; ignored while something is loading or a chapter is being opened. */
    fun refresh() {
        if (loading || loadingMore || busyUnitId != null) return
        retry()
    }

    fun loadMore() {
        if (loadingMore) return
        work.scope.launch {
            loadingMore = true
            loadError = null
            try {
                runCatchingCancellable { loadUnitPage(requestedPage = nextPage, append = true) }
                    .onFailure { loadError = work.describe(it) }
            } finally {
                loadingMore = false
            }
        }
    }

    /** Queues [selections] for download; chapters already offline or queued ([queuedUnitIds]) are skipped. */
    fun downloadUnits(selections: List<ExtensionUnitSelectionV2>, queuedUnitIds: Set<String>) {
        // The queue re-checks storage too.
        val pending = selections
            .distinctBy { it.unit.remoteId }
            .filter { it.unit.remoteId !in downloadedUnitIds && it.unit.remoteId !in queuedUnitIds }
        operationMessage = null
        operationFailed = false
        if (pending.isEmpty()) {
            if (selections.isNotEmpty()) {
                operationMessage = work.strings().text(StringKey.THE_SELECTED_CHAPTERS_ARE_ALREADY_DOWNLOADED_OR)
            }
            return
        }
        work.scope.launch {
            runCatchingCancellable { services.acquisition.enqueueExtensionDownloadsV2(pending) }
                .onSuccess {
                    operationMessage = work.strings().text(StringKey.X_CHAPTERS_ADDED_TO_THE_DOWNLOAD_QUEUE, pending.size)
                }.onFailure {
                    operationFailed = true
                    operationMessage = work.describe(it)
                }
        }
    }

    /**
     * Asks what to translate [selections] into. Which of them are already translated depends on
     * the language chosen next, not on whether anything at all is stored for them.
     */
    fun translateUnits(selections: List<ExtensionUnitSelectionV2>, onlyDownloaded: Boolean = false) {
        if (reader.contentFeatures?.translation == null) return
        val candidates = selections.distinctBy { it.unit.remoteId }
        operationFailed = false
        if (candidates.isEmpty()) {
            operationMessage = if (onlyDownloaded) work.strings().text(StringKey.NO_CHAPTER_OF_THIS_TITLE_IS_DOWNLOADED) else null
            return
        }
        operationMessage = null
        prefetchSetup = candidates
    }

    /** Adds the title to its favorites (the app's library or the source account) or removes it. */
    fun toggleFavorite() {
        val next = !favorite
        operationMessage = null
        operationFailed = false
        work.scope.launch {
            work.awaitFrame()
            runCatchingCancellable { setFavorite(next) }
                .onSuccess { operationMessage = favoriteMessage(next) }
                .onFailure {
                    operationFailed = true
                    operationMessage = work.describe(it)
                }
        }
    }

    private suspend fun setFavorite(next: Boolean) {
        withContext(work.workDispatcher) {
            mutateExtensionFavorite(
                destination = favorites.destination,
                localMutation = {
                    reader.library.setFavorite(item, refreshedPublication ?: publicationPage?.publication ?: cataloguePublication, next)
                },
                sourceMutation = { services.library.favoriteExtensionPublicationV2(sourceKey, remotePublicationId, next) },
            )
        }
        favorite = next
        if (favorites.localLibrary && next) {
            cacheLibraryCopy()
        } else if (favorites.localLibrary) {
            runCatchingCancellable { services.publications.forgetCachedExtensionPublicationV2(sourceKey, remotePublicationId) }
                .onFailure { Log.warn("Extension", it) { "Forgetting the cached extension publication failed" } }
        }
    }

    private fun favoriteMessage(added: Boolean): String {
        val key = when {
            added && favorites.localLibrary -> StringKey.ADDED_TO_APP_LIBRARY
            added -> StringKey.ADDED_TO_SOURCE_FAVORITES
            favorites.localLibrary -> StringKey.REMOVED_FROM_APP_LIBRARY
            else -> StringKey.REMOVED_FROM_SOURCE_FAVORITES
        }
        return work.strings().text(key)
    }
}

/** The stored translations of the listed chapters, as the chapter list marks them. */
internal suspend fun ExtensionPublicationState.loadTranslatedUnits(translation: ReaderTranslation) {
    if (units.isEmpty()) return
    val keys = runCatchingCancellable { translation.translatedChapterKeys(workKey) }
        .onFailure { Log.warn("Translation", it) { "Reading the translated chapter list failed" } }
        .getOrDefault(emptySet())
    storedTranslatedUnitIds = if (keys.isEmpty()) {
        emptySet()
    } else {
        withContext(work.workDispatcher) {
            units.mapNotNullTo(hashSetOf()) { selection ->
                selection.unit.remoteId.takeIf { extensionUnitKey(sourceKey, remotePublicationId, it).value in keys }
            }
        }
    }
}

/** What translating ahead needs from the screen: the manga translator and the app's language. */
internal class PrefetchContext(
    val manga: dev.shinsou.kmp.manga.MangaTranslation?,
    val appLanguage: String?,
)

/**
 * After a restart: opens the listed unit whose [dev.shinsou.kmp.ui.extensionUnitIdentity] is
 * [identity] in the reader, through the ordinary open (rights included). [onSettled] runs once that
 * open ended, or at once when the unit is not listed (false) or another one is opening. A chapter the
 * user chose on this page holds a restored one back, unless the user asked for this one
 * ([userRequested]).
 */
internal fun ExtensionPublicationState.reopenUnit(
    identity: Long,
    stillWanted: () -> Boolean = { true },
    userRequested: Boolean = false,
    onSettled: () -> Unit = {},
): Boolean {
    val selection = units.firstOrNull { dev.shinsou.kmp.ui.extensionUnitIdentity(it.unit.remoteId) == identity }
        ?.takeUnless { yieldsToTheUser(userRequested) }
    val started = selection != null && materialize(selection, onSettled = onSettled, restoring = stillWanted)
    if (!started) onSettled()
    return started
}

/** The user chose a chapter here (listed before the saved one): theirs stays open. */
private fun ExtensionPublicationState.yieldsToTheUser(userRequested: Boolean) = unitChosenByUser && !userRequested

/** Until no chapter is opening: one asked for meanwhile opens after it, rather than failing. */
private suspend fun ExtensionPublicationState.awaitNoOpen() {
    snapshotFlow { busyUnitId == null }.first { it }
}

/**
 * [ExtensionPublicationState.load] that also reopens a restored chapter ([reopenUnit]) as soon as
 * it is listed: the saved copy of a library title lists its chapters before the source answers,
 * so a chapter already on the device opens without waiting for (or when cut off from) the source.
 */
internal suspend fun ExtensionPublicationState.loadAndReopen(
    identity: Long,
    stillWanted: () -> Boolean,
    userRequested: Boolean = false,
    onSettled: () -> Unit,
) = coroutineScope {
    val loading = launch { load() }
    val listed = async {
        snapshotFlow { units.any { dev.shinsou.kmp.ui.extensionUnitIdentity(it.unit.remoteId) == identity } }.first { it }
    }
    select {
        listed.onAwait { }
        loading.onJoin { }
    }
    listed.cancel()
    // Neither list has it (a chapter stored after the saved list, and the source no longer lists
    // it): it still opens from the device.
    val stored = if (units.any { dev.shinsou.kmp.ui.extensionUnitIdentity(it.unit.remoteId) == identity } || yieldsToTheUser(userRequested)) {
        null
    } else {
        storedUnit(identity)
    }
    awaitNoOpen()
    when {
        stored == null -> reopenUnit(identity, stillWanted, userRequested, onSettled)
        yieldsToTheUser(userRequested) || !materialize(stored, onSettled = onSettled, restoring = stillWanted) -> onSettled()
    }
}

/**
 * "Continue reading" asked for [identity] while this page already shows (the wide layout keeps the
 * library beside it): it opens once listed, or from the device once the list is complete, after a
 * chapter still opening. Unlike [reopenUnit] it is the user's request, so a chapter chosen here
 * earlier does not hold it back.
 */
internal suspend fun ExtensionPublicationState.continueUnit(
    identity: Long,
    stillWanted: () -> Boolean,
    onSettled: () -> Unit,
) {
    fun listed() = units.firstOrNull { dev.shinsou.kmp.ui.extensionUnitIdentity(it.unit.remoteId) == identity }
    snapshotFlow { !loading || listed() != null }.first { it }
    val selection = listed() ?: storedUnit(identity)
    awaitNoOpen()
    if (selection == null || !materialize(selection, onSettled = onSettled, restoring = stillWanted)) onSettled()
}

/** The stored unit whose identity is [identity], when this device has one. */
private suspend fun ExtensionPublicationState.storedUnit(identity: Long): ExtensionUnitSelectionV2? =
    runCatchingCancellable {
        withContext(work.workDispatcher) { services.publications.storedExtensionPublicationPageV2(sourceKey, cataloguePublication) }
    }.onFailure { Log.warn("Extension", it) { "Reading the stored chapters failed" } }
        .getOrNull()
        ?.units
        ?.firstOrNull { dev.shinsou.kmp.ui.extensionUnitIdentity(it.unit.remoteId) == identity }
