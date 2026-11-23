package dev.shinsou.kmp.ui

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.contract.ContentCallbacks
import dev.shinsou.kmp.contract.ReaderChapter
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.domain.content.UnifiedReaderNavigation
import dev.shinsou.kmp.ui.i18n.StringKey
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.ui.reader.ContentFeatures
import dev.shinsou.kmp.ui.reader.ReaderProgressObservationClock
import dev.shinsou.kmp.ui.reader.readerProgressSessionIsActive

/** One opening of the reader on a chapter; a new one is made for every chapter change. */
internal data class ReaderSession(
    val mangaId: Long,
    val chapterId: Long,
    val progressSessionId: String,
)

/** Why the open chapter shows no content. */
internal sealed interface ReaderLoadError {
    data class Failed(val cause: Throwable) : ReaderLoadError

    /** The content's rights were withdrawn while it was open. */
    data object NoLongerAvailable : ReaderLoadError
}

internal data class ReaderState(
    val session: ReaderSession? = null,
    /** The last loaded chapter; it belongs to [chapterSession], which may be an older session. */
    val chapter: ReaderChapter = ReaderChapter(),
    val chapterSession: ReaderSession? = null,
    val loading: Boolean = false,
    val error: ReaderLoadError? = null,
    /** Bumped by Retry: loads the open session again. */
    val reloadKey: Int = 0,
    val serial: Long = 0L,
    /** The open session is being closed or replaced (its progress is being flushed). */
    val transitionInFlight: Boolean = false,
    /** The session whose position the reader may report (set once it has been restored). */
    val positionInitializedFor: ReaderSession? = null,
    /** One-shot: bumped when Back should close the reader. */
    val backRequest: Long = 0L,
    val categoryPickerMangaId: Long? = null,
    /**
     * The page a session restored after process death reopens at, until the reader reports its
     * first position; null for a session opened anew (it starts from the chapter's stored page).
     */
    val restoredPageIndex: Int? = null,
) {
    /** The loaded chapter of the open session, empty while it is still loading. */
    val activeChapter: ReaderChapter
        get() = chapter.takeIf { session != null && chapterSession == session } ?: ReaderChapter()

    val showsLoading: Boolean get() = transitionInFlight || loading || chapterSession != session
}

/** A position the typed reader reported. */
internal data class ReaderLocatorPosition(
    val locator: ReadingLocator,
    val pageIndex: Int,
    val pageCount: Int?,
)

/** Loads reader chapters and checks their rights, off the UI thread. */
internal interface ReaderChapterSource {
    /** The chapter of [session], typed content restored to its local position. */
    suspend fun load(session: ReaderSession): ReaderChapter

    /** Fails when [typed]'s rights no longer allow showing it. */
    suspend fun recheckRights(typed: TypedReaderContentSession)

    fun stopSpeech()
}

/** [ReaderChapterSource] of the app's content and protected-content runtime. */
internal class HostReaderChapterSource(
    private val repository: ShinsouRepository,
    private val content: ContentCallbacks,
    private val features: ContentFeatures?,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ReaderChapterSource {
    override suspend fun load(session: ReaderSession): ReaderChapter = withContext(workDispatcher) {
        val loaded = content.loadReaderChapter(session.mangaId, session.chapterId)
        val restored = loaded.typedSession?.let { typed -> restore(session, typed) }
        if (restored == null) loaded else loaded.copy(typedSession = restored)
    }

    private suspend fun restore(session: ReaderSession, typed: TypedReaderContentSession): TypedReaderContentSession {
        val features = requireNotNull(features) {
            "Protected reader content is unavailable without the host content runtime"
        }
        checkRights(features, typed)
        val navigation = typed.content.navigation
        val localVisualPageIndex = repository.chapter(session.chapterId)?.lastPageRead?.coerceAtLeast(0)
        val localHistory = repository.currentSnapshot.histories.firstOrNull { it.chapterId == session.chapterId }
        val localLocator = localHistory?.lastLocator?.let(navigation::resolve)
        return TypedReaderContentSession(
            content = typed.content.copy(
                initialLocator = localLocator ?: typed.content.initialLocator,
                initialLocatorSaved = localLocator != null,
            ),
            canonicalText = typed.canonicalText,
            access = typed.access,
            initialVisualPageIndex = localVisualPageIndex,
            initialVisualPageCount = localHistory?.lastPageCount,
            remoteAssetScope = typed.remoteAssetScope,
        )
    }

    override suspend fun recheckRights(typed: TypedReaderContentSession) {
        val features = features ?: return
        withContext(workDispatcher) { checkRights(features, typed) }
    }

    private suspend fun checkRights(features: ContentFeatures, typed: TypedReaderContentSession) {
        val textLength = typed.canonicalText?.length?.toLong()
        val cleanupAccess = textLength?.let { length ->
            typed.access.copy(context = typed.access.context.copy(textCharacters = length))
        } ?: typed.access
        features.cleanupRevokedDerivedData(typed.content.navigation.scope, cleanupAccess)
        check(features.operations.display(request = typed.access, textCharacters = textLength) { true })
    }

    override fun stopSpeech() {
        features?.textToSpeech?.stop()
    }
}

/**
 * R4-3 (docs/architecture/0006): the page reader's session — opening a chapter, loading it,
 * moving between chapters and closing — and the local reading progress it reports. Closing or
 * changing chapters first waits for the progress already reported to be written.
 */
internal class ReaderSessionController(
    private val source: ReaderChapterSource,
    private val progress: ReaderLocalProgressCoordinator,
    private val scope: CoroutineScope,
    private val messages: AppMessages,
    private val observationClock: ReaderProgressObservationClock = ReaderProgressObservationClock(),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow(ReaderState())
    private val completedChapterIds = mutableSetOf<Long>()

    /** The last page the page reader reported, with the session it belongs to. */
    private var lastReportedPage: Pair<ReaderSession, Int>? = null

    val state: StateFlow<ReaderState> = mutableState.asStateFlow()

    /**
     * Opens [chapterId] of [mangaId] when both are in [snapshot]; false otherwise. Back requests
     * are one-shot: the counter restarts, so a Back already handled cannot close the new session
     * during its first composition.
     */
    fun open(mangaId: Long, chapterId: Long, snapshot: AppSnapshot): Boolean {
        if (snapshot.mangas.none { it.id == mangaId } || snapshot.chapters.none { it.id == chapterId }) return false
        mutableState.update {
            val serial = it.serial + 1
            it.copy(
                backRequest = 0L,
                positionInitializedFor = null,
                serial = serial,
                session = ReaderSession(mangaId, chapterId, progressSessionId = "local-reader-$serial"),
                restoredPageIndex = null,
            )
        }
        return true
    }

    /**
     * Reopens [saved] after process death, through the same checks as [open]: false, and nothing
     * opens, when its title or chapter is gone. Its page applies until the reader reports one.
     */
    fun restore(saved: ReaderSavedSession, snapshot: AppSnapshot): Boolean {
        val ofTitle = snapshot.chapters.any { it.id == saved.chapterId && it.mangaId == saved.mangaId }
        val opened = ofTitle && open(saved.mangaId, saved.chapterId, snapshot)
        if (opened) mutableState.update { it.copy(restoredPageIndex = saved.pageIndex?.takeIf { page -> page >= 0 }) }
        return opened
    }

    /** What process-death restoration needs of the open session; null when none is open. */
    fun savedSession(): ReaderSavedSession? {
        val session = mutableState.value.session ?: return null
        val page = lastReportedPage?.takeIf { it.first == session }?.second
            ?: mutableState.value.restoredPageIndex
        return ReaderSavedSession(session.mangaId, session.chapterId, page)
    }

    /**
     * Closes the reader, or moves it to [targetChapterId] of [targetMangaId], once the progress of
     * the open chapter is written. Ignored while another transition is under way.
     */
    fun transition(targetMangaId: Long? = null, targetChapterId: Long? = null) {
        val current = mutableState.value
        val closing = current.session ?: return
        if (current.transitionInFlight) return
        var target: ReaderSession? = null
        mutableState.update {
            target = if (targetMangaId != null && targetChapterId != null) {
                ReaderSession(targetMangaId, targetChapterId, progressSessionId = "local-reader-${it.serial + 1}")
            } else {
                null
            }
            it.copy(serial = if (target != null) it.serial + 1 else it.serial, transitionInFlight = true)
        }
        transitionTarget = target
        scope.launch {
            runCatchingCancellable { progress.flushLocal() }
                .onFailure { failure -> messages.showFailure(failure, StringKey.THE_LATEST_READING_POSITION_COULD_NOT_BE) }
            // [close] may have replaced the chapter to open with none meanwhile.
            val next = transitionTarget
            mutableState.update {
                if (it.session == closing) {
                    it.copy(
                        positionInitializedFor = null,
                        session = next,
                        transitionInFlight = false,
                        restoredPageIndex = null,
                    )
                } else {
                    it.copy(transitionInFlight = false)
                }
            }
        }
    }

    /** Where the transition under way goes once progress is written: a chapter, or none (closing). UI thread. */
    private var transitionTarget: ReaderSession? = null

    /**
     * Closes the reader, after its progress is written. A move to another chapter still writing
     * progress closes instead (a link the user followed takes precedence over it).
     */
    fun close() {
        if (mutableState.value.transitionInFlight) transitionTarget = null else transition()
    }

    fun retry() = mutableState.update { it.copy(reloadKey = it.reloadKey + 1) }

    /**
     * Loads [session]'s chapter. [awaitFrame] lets the loading state reach the screen before the
     * chapter or plugin work begins.
     */
    suspend fun load(session: ReaderSession, awaitFrame: suspend () -> Unit = {}) {
        mutableState.update { it.copy(loading = true, error = null, chapterSession = null) }
        awaitFrame()
        try {
            val loaded = source.load(session)
            updateIfOpen(session) { it.copy(chapter = loaded, chapterSession = session) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Throwable) {
            Log.warn("Reader", logged) { "Loading a reader chapter failed" }
            updateIfOpen(session) {
                it.copy(chapter = ReaderChapter(), chapterSession = session, error = ReaderLoadError.Failed(logged))
            }
        } finally {
            updateIfOpen(session) { it.copy(loading = false) }
        }
    }

    /** Checks the open typed chapter's rights again; withdrawn rights close its content. */
    suspend fun recheckRights(typed: TypedReaderContentSession) {
        try {
            source.recheckRights(typed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Throwable) {
            Log.warn("App", logged) { "The open reader chapter failed its rights check; closing it" }
            source.stopSpeech()
            mutableState.update { it.copy(chapter = ReaderChapter(), error = ReaderLoadError.NoLongerAvailable) }
        }
    }

    /** Reading progress is local only: the reader restores from the chapter itself. */
    fun markPositionInitialized(session: ReaderSession) = mutableState.update {
        if (it.positionInitializedFor != session) it.copy(positionInitializedFor = session) else it
    }

    /** The title or chapter of [session] disappeared from the library: the reader closes. */
    fun dropSession(session: ReaderSession) = updateIfOpen(session) { it.copy(session = null) }

    fun requestBack() = mutableState.update { it.copy(backRequest = it.backRequest + 1) }

    fun showCategoryPicker(mangaId: Long) = mutableState.update { it.copy(categoryPickerMangaId = mangaId) }

    /** Closes the category picker; true when it was open (it consumed a Back press). */
    fun dismissCategoryPicker(): Boolean {
        if (mutableState.value.categoryPickerMangaId == null) return false
        mutableState.update { it.copy(categoryPickerMangaId = null) }
        return true
    }

    /**
     * The page reader of [session] reached [pageIndex] of [pageCount] in [chapter]. Returns true
     * when this completed the chapter for the first time (its after-reading work is due).
     */
    fun recordPagePosition(
        session: ReaderSession,
        chapter: Chapter,
        pageIndex: Int,
        pageCount: Int,
        incognito: Boolean,
    ): Boolean {
        if (incognito || !acceptsProgressFrom(session)) return false
        lastReportedPage = session to pageIndex
        if (mutableState.value.restoredPageIndex != null) mutableState.update { it.copy(restoredPageIndex = null) }
        val readAt = observationClock.next(now())
        val completedNow = pageCount > 0 &&
            pageIndex == pageCount - 1 &&
            !chapter.read &&
            completedChapterIds.add(chapter.id)
        progress.enqueue(
            ReaderLocalProgressEvent(
                chapterId = chapter.id,
                pageIndex = pageIndex,
                read = chapter.read || completedNow,
                readAt = readAt,
                pageCount = pageCount.takeIf { it > 0 },
            ),
        )
        return completedNow
    }

    /** The typed reader of [session] reported [position] in [chapter], laid out by [navigation]. */
    fun recordLocator(
        session: ReaderSession,
        chapter: Chapter,
        navigation: UnifiedReaderNavigation,
        position: ReaderLocatorPosition,
        incognito: Boolean,
    ) {
        if (incognito || !acceptsProgressFrom(session)) return
        val semanticIndex = navigation.indexOf(position.locator) ?: return
        val readAt = observationClock.next(now())
        val completed = semanticIndex == navigation.itemCount - 1 &&
            (position.locator.progression ?: 0.0) >= COMPLETED_PROGRESSION
        progress.enqueue(
            ReaderLocalProgressEvent(
                chapterId = chapter.id,
                pageIndex = position.pageIndex.coerceAtLeast(0),
                read = chapter.read || completed,
                readAt = readAt,
                locator = position.locator,
                pageCount = position.pageCount,
            ),
        )
    }

    private fun acceptsProgressFrom(session: ReaderSession): Boolean {
        val current = mutableState.value
        return readerProgressSessionIsActive(
            activeSession = current.session,
            callbackSession = session,
            transitionInFlight = current.transitionInFlight,
        )
    }

    private fun updateIfOpen(session: ReaderSession, transform: (ReaderState) -> ReaderState) =
        mutableState.update { if (it.session == session) transform(it) else it }

    private companion object {
        const val COMPLETED_PROGRESSION = 0.995
    }
}
