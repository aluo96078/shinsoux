package dev.shinsou.kmp.ui

import dev.shinsou.kmp.ui.core.SettingsEdit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.domain.model.shouldAskForCategoriesOnFavorite
import dev.shinsou.kmp.navigation.AppNavigator
import dev.shinsou.kmp.reader.readerTrackingProgress
import dev.shinsou.kmp.tracking.TrackUpdate
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.screens.MoreDestination
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.ui.reader.ReaderProgressObservationClock
import dev.shinsou.kmp.ui.reader.ReaderVolumeKeyPressTracker
import dev.shinsou.kmp.ui.reader.ReaderVolumeKeyRouter

/** How the shell starts: its first section and what the platform says at launch. */
internal class ShellStart(
    val section: MainSection,
    val appLocked: Boolean,
    val downloadsPaused: Boolean,
    /** Ends a text input on touch devices (focus and the software keyboard). */
    val dismissMobileInput: () -> Unit,
)

/**
 * R4-3 (docs/architecture/0006): the app shell's state holders and the events that span more
 * than one of them. Plain classes, so the rules are tested without Compose; the composables draw
 * their state flows and report events here.
 */
internal class ShinsouAppState(
    val repository: ShinsouRepository,
    val appServices: ShinsouAppServices,
    val autoBackupService: AutoBackupService?,
    val environment: HolderEnvironment,
    start: ShellStart,
) {
    private val messages get() = environment.messages
    private val dismissInput = start.dismissMobileInput

    val selection = LibrarySelectionState()
    val navigator = AppNavigator(start.section, selection)
    val localLibrary = ExtensionLocalLibrary(repository, appServices.browse, environment.workDispatcher)
    val reviews = PluginReviewCoordinator(
        browse = appServices.browse,
        scope = environment.scope,
        messages = messages,
        acknowledgeLink = appServices::deepLinkHandled,
        onSourcesChanged = { appServices.sync?.sourcesChanged() },
        workDispatcher = environment.workDispatcher,
    )
    val titles = TitleNavigationController(navigator, repository, appServices.browse, reviews, localLibrary, environment)

    /** One clock for both readers, so their observations stay ordered. */
    private val progressClock = ReaderProgressObservationClock()
    val localProgress = ReaderLocalProgressCoordinator(
        repository = repository,
        onFailure = { failure -> messages.showFailure(failure, StringKey.THE_LATEST_READING_POSITION_COULD_NOT_BE) },
    )
    val v2Progress = V2ReaderProgressCoordinator(repository = repository)
    private val v2ProgressSessions = mutableMapOf<String, String>()
    val reader = ReaderSessionController(
        source = HostReaderChapterSource(
            repository,
            appServices.content,
            appServices.contentFeatures,
            environment.workDispatcher,
        ),
        progress = localProgress,
        scope = environment.scope,
        messages = messages,
        observationClock = progressClock,
    )
    val lock = AppLockController(
        lockedAtStart = start.appLocked,
        authenticationAvailable = appServices.securityCapabilities.appLock.available,
        authenticate = { appServices.authenticate(messages.currentStrings.text(StringKey.UNLOCK_SHINSOU_X)) },
    )
    val volumeKeyRouter = ReaderVolumeKeyRouter()
    val volumeKeyPressTracker = ReaderVolumeKeyPressTracker()
    val downloadsPaused = MutableStateFlow(start.downloadsPaused)

    /** Ends a text input on touch devices; the app keeps the keyboard focus for its shortcuts. */
    fun dismissMobileInput() = dismissInput()

    /** How the panes run user actions: store work off the UI thread, in order. */
    val actions: UserActions = ShellUserActions(environment)

    /** Runs a user action on the UI thread, reporting its failure in the snackbar. */
    fun mutate(block: suspend () -> Unit) = actions.onUi(block)

    fun selectSection(next: MainSection) {
        dismissMobileInput()
        // The user chose a tab (the current one again included, which changes no page): a title
        // still opening, and a chapter a restoration still waits to reopen, are given up.
        titles.cancelOpen()
        pendingLocalExtensionUnit = null
        navigator.selectSection(next)
    }

    fun openManga(mangaId: Long) {
        dismissMobileInput()
        // The user opened a title: a chapter a restoration still waits to reopen is given up.
        pendingLocalExtensionUnit = null
        titles.openManga(mangaId, ::onTitleOpened)
    }

    /**
     * "Continue reading" of a library extension title: its page opens, and then the chapter
     * [remoteUnitId] in the reader once the page lists it, as a restored chapter reopens.
     */
    fun continueExtensionUnit(mangaId: Long, remoteUnitId: String) {
        dismissMobileInput()
        pendingLocalExtensionUnit = null
        titles.openManga(mangaId) { outcome ->
            onTitleOpened(outcome)
            // Asked for once the page shows, not before: opening some titles takes a lookup off the
            // UI thread, and a title not opened in the end opens no chapter either.
            if (outcome == TitleOpenOutcome.ELSEWHERE) {
                pendingLocalExtensionUnit = PendingExtensionReopen(mangaId, extensionUnitIdentity(remoteUnitId), userRequested = true)
            }
        }
    }

    /** A title's detail page replaced the page: the incognito reader's title is no longer what shows. */
    private fun onTitleOpened(outcome: TitleOpenOutcome) {
        if (outcome == TitleOpenOutcome.DETAIL) titleChosenByReader = null
    }

    fun openBrowseManga(item: BrowseManga) {
        if (navigator.openBrowseManga(item)) dismissMobileInput()
    }

    fun openMore(destination: MoreDestination) {
        dismissMobileInput()
        navigator.openMore(destination)
    }

    fun openReader(mangaId: Long, chapterId: Long) {
        if (!reader.open(mangaId, chapterId, repository.currentSnapshot)) return
        // A title still opening (its binding being read) would replace the page behind the reader,
        // and a restored extension chapter still opening would open a second reader under it.
        titles.cancelOpen()
        pendingLocalExtensionUnit = null
        dismissMobileInput()
        onReaderOpened(mangaId)
    }

    /**
     * The title the reader selected when it opened over another page (a chapter opened from
     * Updates or History): it is not saved as an open detail page.
     */
    private var titleChosenByReader: Long? = null

    private fun onReaderOpened(mangaId: Long) {
        if (navigator.state.value.selectedMangaId != mangaId) titleChosenByReader = mangaId
        navigator.onReaderOpened(mangaId)
    }

    /** A link the user followed decides where the app is: a restoration still under way stops. */
    private var linkFollowed = false

    fun closeRemovedLibraryTitles(mangaIds: Set<Long>) = titles.closeRemovedLibraryTitles(mangaIds)

    /**
     * The chapter open in the reader of a local-library extension title: the title's
     * [BrowseManga.identityKey] and the chapter's [extensionUnitIdentity]. UI thread.
     */
    private var localExtensionReaderUnit: Pair<String, Long>? = null

    /**
     * A restored local-library extension title's chapter to reopen once its page has loaded: only
     * on that title's page, and dropped once that page goes away or another title is opened.
     */
    var pendingLocalExtensionUnit by mutableStateOf<PendingExtensionReopen?>(null)
        private set

    /** The reopen pending for the local-library extension page of [item], if any. */
    fun pendingReopenFor(item: BrowseManga): Long? {
        val pending = pendingLocalExtensionUnit ?: return null
        val row = repository.currentSnapshot.mangas.firstOrNull { it.id == pending.mangaId }
        return pending.unit.takeIf { row != null && localLibrary.isRowOf(row, item) }
    }

    /** The local-library extension reader of [item] shows [unitId] (null: it closed). */
    fun onLocalExtensionReaderUnit(item: BrowseManga, unitId: String?) {
        localExtensionReaderUnit = unitId?.let { item.identityKey to extensionUnitIdentity(it) }
    }

    /** The reopen [handled] was tried: it is no longer pending, unless a newer one replaced it. */
    fun localExtensionUnitReopened(handled: PendingExtensionReopen) {
        if (pendingLocalExtensionUnit === handled) pendingLocalExtensionUnit = null
    }

    /** The library page shown is now [item] (null: none): a reopen waiting for another is given up. */
    fun forgetReopenUnlessFor(item: BrowseManga?) {
        if (pendingLocalExtensionUnit != null && (item == null || pendingReopenFor(item) == null)) {
            pendingLocalExtensionUnit = null
        }
    }

    /** What process death and configuration changes keep of the shell ([ShellSavedState]). */
    fun savedState(): ShellSavedState {
        val snapshot = repository.currentSnapshot
        val navigation = navigator.state.value
        val open = navigation.localLibraryPublication
        val localExtension = open?.let { publication -> snapshot.mangas.firstOrNull { localLibrary.isRowOf(it, publication) }?.id }
        val openUnit = localExtensionReaderUnit?.takeIf { (identity, _) -> open?.identityKey == identity }?.second
        // A restored chapter not reopened yet is still the one to save.
        val unit = openUnit.takeIf { navigation.browseReaderOpen }
            ?: pendingLocalExtensionUnit?.takeIf { it.mangaId == localExtension }?.unit
        val incognito = snapshot.settings.security.incognitoMode
        val saved = captureShellState(
            navigation = navigation,
            selection = selection.state.value,
            reader = reader.savedSession(),
            localExtension = localExtension?.let { SavedLocalExtension(it, unit) },
            incognito = incognito,
        )
        // A title only the reader selected (a chapter resumed from History or Updates) is not an
        // open detail page while that reader shows: reopening it as one would route it like a tap
        // (an extension title moves to Library, or stops at a permission review) instead of
        // reopening the chapter where it was. In incognito mode it would reveal what was read.
        val readerOnly = saved.detailMangaId != null && saved.detailMangaId == titleChosenByReader &&
            (incognito || reader.state.value.session != null)
        val kept = if (readerOnly) saved.copy(detailMangaId = null, chapterIds = emptySet()) else saved
        return kept.withIdentitiesFrom(snapshot)
    }

    /**
     * Reopens [saved] through the same entry points as a fresh open; titles and chapters that
     * left the library meanwhile are not reopened.
     */
    fun restore(saved: ShellSavedState, onFinished: () -> Unit = {}) {
        if (linkFollowed) {
            onFinished()
            return
        }
        // Incognito now (turned on after the state was saved): no chapter is reopened.
        val restored = withoutReadingInIncognito(saved.validatedAgainst(repository.currentSnapshot))
        var finished = false
        selectionAtRestore = null
        restoreShellState(restored, restoreTarget.until { linkFollowed }) {
            finished = true
            restoreOrigin = null
            onFinished()
        }
        // Still opening a title (asynchronously): the user moving on gives it up at once.
        if (!finished) restoreOrigin = navigator.pageChanges
    }

    /** [saved] without its chapters while incognito mode is on: neither reopened nor saved again. */
    fun withoutReadingInIncognito(saved: ShellSavedState): ShellSavedState =
        if (repository.currentSnapshot.settings.security.incognitoMode) {
            saved.copy(reader = null, localExtensionUnit = null)
        } else {
            saved
        }

    /** The page count when a restoration still under way had done what it could at once. */
    private var restoreOrigin: Long? = null

    /** The library selection and category when a restoration began opening its title (asynchronously). */
    private var selectionAtRestore: Pair<Set<Long>, Long>? = null

    /**
     * Whether a restoration still under way was given up: the user moved to another page before
     * its title opened, so the state to save is what is shown, not what was being reopened.
     */
    val restoreAbandoned: Boolean
        get() = restoreOrigin?.let { it != navigator.pageChanges } == true

    private val restoreTarget = object : ShellRestoreTarget {
        override fun selectSection(section: MainSection) = navigator.selectSection(section)

        override fun openMore(destination: MoreDestination) = navigator.openMore(destination)

        override fun openManga(mangaId: Long, onSettled: (TitleOpenOutcome) -> Unit) {
            selectionAtRestore = selection.state.value.let { it.libraryIds to it.categoryId }
            titles.openManga(mangaId, onSettled)
        }

        override fun openReader(saved: ReaderSavedSession) {
            if (reader.restore(saved, repository.currentSnapshot)) onReaderOpened(saved.mangaId)
        }

        override fun reopenExtensionUnit(mangaId: Long, identity: Long) {
            pendingLocalExtensionUnit = PendingExtensionReopen(mangaId, identity, userRequested = false)
        }

        override fun restoreSelection(libraryIds: Set<Long>, categoryId: Long) {
            // The library stays usable while a title reopens: a category or selection the user
            // chose meanwhile is theirs, not replaced by the saved one.
            val current = selection.state.value
            if (selectionAtRestore.let { it == null || it == current.libraryIds to current.categoryId }) {
                selection.selectCategory(categoryId)
                if (libraryIds.isNotEmpty()) selection.selectLibraryTitles(libraryIds)
            }
            selectionAtRestore = null
        }

        override fun restoreChapterSelection(mangaId: Long, chapterIds: Set<Long>) {
            // The user may have moved on before an asynchronous open finished.
            if (navigator.state.value.selectedMangaId == mangaId) selection.selectChapters(chapterIds)
        }
    }

    /** The reader changed a setting ([SettingsEdit]). */
    fun updateReaderSettings(edit: SettingsEdit<ReaderSettings>) =
        actions.editSettings(repository) { it.copy(reader = edit(it.reader)) }

    /** An extension reader (Browse or the local library) reached [locator]. */
    fun recordV2ReaderProgress(
        title: String,
        unitTitle: String,
        locator: ReadingLocator,
        pageIndex: Int,
        pageCount: Int?,
    ) {
        if (repository.currentSnapshot.settings.security.incognitoMode) return
        val sessionKey = "${locator.scope.publicationId.value}:${locator.scope.unitId.value}"
        val progressSessionId = v2ProgressSessions.getOrPut(sessionKey) { "v2-reader-${sessionKey.hashCode()}" }
        v2Progress.enqueue(
            V2ReaderProgressEvent(
                title = title,
                unitTitle = unitTitle,
                locator = locator,
                pageIndex = pageIndex.coerceAtLeast(0),
                pageCount = pageCount,
                progressSessionId = progressSessionId,
                readAt = progressClock.next(Clock.System.now().toEpochMilliseconds()),
            ),
        )
    }

    /**
     * Handles one Back press (system button, gesture, Escape or the mouse's back button); false
     * when nothing was open, so the platform may leave the app.
     */
    fun systemBack(): Boolean {
        val login = appServices.browse.loginRequests.value.firstOrNull()
        return when {
            lock.state.value.unlocked && login != null -> {
                login.eventId?.let(appServices.browse::dismissSourceLoginEvent)
                    ?: appServices.browse.dismissSourceLoginRequest(login.sourceId)
                true
            }
            reader.dismissCategoryPicker() -> true
            reader.state.value.session != null -> {
                reader.requestBack()
                true
            }
            else -> navigator.back()
        }
    }

    /** Whether a back-swipe drags the visible page (not the reader or a login prompt). */
    fun backGestureDragsPage(): Boolean =
        reader.state.value.session == null && appServices.browse.loginRequests.value.isEmpty()

    /**
     * Follows a deep link. Links stay with the platform until acknowledged: a failure leaves the
     * link for a later retry, and repository links wait for the user's answer.
     */
    suspend fun handleDeepLink(link: ShinsouDeepLink) {
        try {
            if (follow(link)) appServices.deepLinkHandled(link)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Throwable) {
            messages.showFailure(logged, StringKey.UNABLE_TO_OPEN_THIS_LINK)
        }
    }

    /** Follows [link]; false when it stays unacknowledged (a repository link waits for the user). */
    private suspend fun follow(link: ShinsouDeepLink): Boolean {
        // The queue emits its head again when another link arrives: a repository link already
        // being handled changes nothing (the reader the user opened since stays open).
        if (reviews.handlesRepositoryLink(link)) return false
        linkFollowed = true
        // A title open still under way (a restoration's, or a Browse title still resolving) would
        // otherwise land over the link's page.
        titles.cancelOpen()
        navigator.cancelPendingBrowse()
        // What the user asked for is shown, not hidden under a reader still open (a restored
        // chapter); a chapter link replaces the reader's chapter instead.
        if (link !is ShinsouDeepLink.OpenChapter) reader.close()
        when (link) {
            // Acknowledged once the title's page opened or the open ended: a title routed off the
            // UI thread (or behind a permission review) is not lost if the shell goes meanwhile.
            is ShinsouDeepLink.OpenManga -> openMangaAndWait(link.mangaId)
            is ShinsouDeepLink.OpenChapter -> openChapterLink(link)
            is ShinsouDeepLink.OpenSection -> selectSection(link.section.toMainSection())
            ShinsouDeepLink.OpenSettings -> navigator.openSettings()
            is ShinsouDeepLink.AddRepository -> reviews.onRepositoryLink(link) { selectSection(MainSection.BROWSE) }
            is ShinsouDeepLink.AddDevRepository -> reviews.onDevRepositoryLink(link) { selectSection(MainSection.BROWSE) }
        }
        return link !is ShinsouDeepLink.AddRepository && link !is ShinsouDeepLink.AddDevRepository
    }

    private suspend fun openMangaAndWait(mangaId: Long) {
        suspendCancellableCoroutine { continuation ->
            dismissMobileInput()
            pendingLocalExtensionUnit = null
            val request = titles.openManga(mangaId) { outcome ->
                onTitleOpened(outcome)
                if (continuation.isActive) continuation.resume(Unit)
            }
            // The shell stopped handling links (it went to the background): the open stops with it,
            // and the link, not acknowledged, is followed by whichever shell takes over.
            continuation.invokeOnCancellation { titles.cancelOpen(request) }
        }
    }

    private fun openChapterLink(link: ShinsouDeepLink.OpenChapter) {
        val mangaId = link.mangaId.takeIf { it >= 0 }
            ?: repository.currentSnapshot.chapters.firstOrNull { it.id == link.chapterId }?.mangaId
        if (mangaId != null) openReader(mangaId, link.chapterId)
    }

    /** The reader's favorite button: leaving the library closes the title and its reader. */
    suspend fun toggleFavoriteInReader(manga: Manga) {
        val updated = actions.stores { repository.toggleMangaFavorite(manga.id) }
        if (!updated.favorite) {
            // The writes below are for [manga]; the pages close only if they still show it.
            if (reader.state.value.categoryPickerMangaId == manga.id) reader.dismissCategoryPicker()
            navigator.closeDetailOf(manga.id)
            withContext(environment.workDispatcher) {
                removeDownloadsForMangas(
                    content = appServices.content,
                    queue = repository.currentSnapshot.downloadQueue,
                    mangaIds = setOf(manga.id),
                )
            }
            if (reader.state.value.session?.mangaId == manga.id) reader.transition()
        } else {
            val current = repository.currentSnapshot
            if (
                shouldAskForCategoriesOnFavorite(
                    configuredCategoryId = current.settings.library.defaultCategoryId,
                    availableCategoryIds = current.categories.map { it.id },
                )
            ) {
                reader.showCategoryPicker(manga.id)
            }
        }
    }

    /**
     * A chapter was read to its end for the first time: its download goes when the settings say
     * so, and trackers learn the progress. A tracker failure is reported after all were tried.
     */
    suspend fun afterChapterRead(manga: Manga, chapter: Chapter, storyOrder: List<Chapter>) {
        val snapshot = repository.currentSnapshot
        withContext(environment.workDispatcher) {
            if (snapshot.settings.downloads.deleteAfterReading) {
                snapshot.downloadQueue
                    .filter { it.chapterId == chapter.id && it.state == DownloadState.DOWNLOADED }
                    .forEach { appServices.content.removeDownload(it.id) }
            }
        }
        val trackingSettings = snapshot.settings.tracking
        val tracking = appServices.tracking
        if (!trackingSettings.autoSyncAfterRead || !trackingSettings.updateProgressAfterRead || tracking == null) return
        val progress = readerTrackingProgress(chapter, storyOrder)
        val failure = withContext(environment.workDispatcher) {
            snapshot.tracks
                .filter { it.mangaId == manga.id }
                .mapNotNull { track ->
                    runCatchingCancellable {
                        tracking.update(
                            mangaId = manga.id,
                            trackerId = track.trackerId,
                            update = TrackUpdate(progress = maxOf(track.lastChapterRead, progress)),
                        )
                    }.exceptionOrNull()
                }
                .firstOrNull()
        } ?: return
        val strings = messages.currentStrings
        throw IllegalStateException(
            strings.text(
                StringKey.CHAPTER_COMPLETED_BUT_ONE_OR_MORE_TRACKERS,
                failure.message ?: strings.text(StringKey.UNKNOWN_TRACKER_ERROR),
            ),
            failure,
        )
    }

    /** Opens the original web page of [chapter] of [manga] in the browser. */
    fun openChapterOnWeb(manga: Manga, chapter: Chapter) = mutate {
        withContext(environment.workDispatcher) {
            appServices.content.resolveChapterOriginalUrl(manga.id, chapter.id)
        }?.let(appServices::openExternalUrl)
    }
}

/** Remembers that a Back key press was handled, so its repeats and release are swallowed too. */
internal class HardwareBackKeyLatch {
    var held: Boolean = false
}

internal fun DeepLinkSection.toMainSection(): MainSection = when (this) {
    DeepLinkSection.Library -> MainSection.LIBRARY
    DeepLinkSection.Updates -> MainSection.UPDATES
    DeepLinkSection.History -> MainSection.HISTORY
    DeepLinkSection.Browse -> MainSection.BROWSE
    DeepLinkSection.More -> MainSection.MORE
}

/**
 * A chapter ([extensionUnitIdentity]) of the local-library extension title [mangaId] to reopen.
 * Each request is its own instance (no value equality): asking for the same chapter again is a new
 * request the page handles, and a finished one cannot clear a newer one.
 */
internal class PendingExtensionReopen(
    val mangaId: Long,
    val unit: Long,
    /** "Continue reading" asked for it, rather than a restart restoring the reader. */
    val userRequested: Boolean,
)
