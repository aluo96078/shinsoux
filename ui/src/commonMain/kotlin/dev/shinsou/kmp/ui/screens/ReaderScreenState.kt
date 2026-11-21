package dev.shinsou.kmp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.domain.content.ContentRepresentation
import dev.shinsou.kmp.domain.content.UnifiedReaderContent
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.reader.ReaderPosition
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.reader.coerceReaderPosition
import dev.shinsou.kmp.reader.isContinuousReaderMode
import dev.shinsou.kmp.reader.pagedReaderPosition
import dev.shinsou.kmp.ui.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.readerMayCrossChapterBoundary

internal enum class ReaderChapterBoundary {
    PREVIOUS,
    NEXT,
}

/** How a typed chapter is laid out, as far as the page reader's navigation needs to know. */
internal enum class ReaderContentKind {
    IMAGES,
    TEXT,
    EPUB,
}

/** The typed content of the open chapter, reduced to what navigation needs. */
internal data class ReaderContentShape(
    val kind: ReaderContentKind,
    val representationId: String,
    /** Semantic items (image pages, text blocks, EPUB documents). */
    val itemCount: Int,
    /** The item the content's own initial locator points at. */
    val initialItemIndex: Int?,
    /** The stored visual page and page count ([dev.shinsou.kmp.contract.TypedReaderContentSession]). */
    val initialPageIndex: Int? = null,
    val initialPageCount: Int? = null,
) {
    val prose: Boolean get() = kind != ReaderContentKind.IMAGES
}

internal fun UnifiedReaderContent.shape(initialPageIndex: Int?, initialPageCount: Int?): ReaderContentShape =
    ReaderContentShape(
        kind = when (representation) {
            is ContentRepresentation.ImageSequence -> ReaderContentKind.IMAGES
            is ContentRepresentation.PlainText -> ReaderContentKind.TEXT
            is ContentRepresentation.EpubSpine -> ReaderContentKind.EPUB
        },
        representationId = representation.representationId,
        itemCount = navigation.itemCount,
        initialItemIndex = navigation.indexOf(initialLocator),
        initialPageIndex = initialPageIndex,
        initialPageCount = initialPageCount,
    )

/** What [ReaderScreenState] reads of the screen's parameters on each composition. */
internal data class ReaderScreenInputs(
    val chapterId: Long,
    val sessionId: String,
    val settings: ReaderSettings,
    /** Pages of the page reader; ignored when [unified] content is shown. */
    val pageCount: Int,
    /** The chapter's stored page, where the page reader starts without [initialPosition]. */
    val storedPageIndex: Int,
    val initialPosition: ReaderPosition?,
    val loading: Boolean,
    val hasPreviousChapter: Boolean,
    val hasNextChapter: Boolean,
    val positionReportingEnabled: Boolean,
    /** Typed content drawn by the unified renderer; null for the page reader. */
    val unified: ReaderContentShape?,
)

/** What [ReaderScreenState] asks of its host. */
internal class ReaderNavigationCallbacks(
    val onClose: () -> Unit,
    val onPositionChanged: (ReaderPosition) -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onChapterSelected: (Long) -> Unit,
)

/**
 * R4-3 (docs/architecture/0006): the reader screen's state — chrome, sheets, the chapter-boundary
 * card, the reading position and the unified renderer's page commands — and its navigation rules,
 * outside composition. [bind] is called on every composition with the screen's parameters; state
 * that belonged to an older chapter, session, content or layout starts afresh exactly where the
 * screen's `remember` keys used to restart it.
 */
internal class ReaderScreenState {
    /** Bumped when the toolbar's translation button is pressed; outlives chapters, as the renderer watches it. */
    var translationRequest by mutableLongStateOf(0L)
        private set

    /** Bumped when the toolbar's translation button is tapped: the translation turns on or off. */
    var translationToggle by mutableLongStateOf(0L)
        private set

    var settingsVisible by mutableStateOf(false)
    var chapterListVisible by mutableStateOf(false)
    var unifiedTranslationActive by mutableStateOf(false)
    var controlsVisible by mutableStateOf(true)
    var boundaryTransition by mutableStateOf<ReaderChapterBoundary?>(null)
    var navigationAction by mutableStateOf<ReaderTapAction?>(null)
        private set
    var navigationRequestKey by mutableLongStateOf(0L)
        private set
    var currentPosition by mutableStateOf(ReaderPosition(ReadingMode.PAGER_LTR, 0))
    var viewportRequestSerial by mutableLongStateOf(0L)
        private set
    var unifiedPageCount by mutableIntStateOf(0)
        private set
    var unifiedPageIndex by mutableIntStateOf(0)
        private set
    var pageRequest by mutableStateOf(UnifiedReaderPageRequest(0))
        private set

    /** The unified renderer reported its real pagination for the current layout. */
    var pageCountMeasured by mutableStateOf(false)

    private var inputs: ReaderScreenInputs? = null
    private var callbacks: ReaderNavigationCallbacks? = null
    private val sessionRestart = Restart()
    private val chapterRestart = Restart()
    private val controlsRestart = Restart()
    private val countRestart = Restart()
    private val indexRestart = Restart()
    private val layoutRestart = Restart()

    private val current: ReaderScreenInputs get() = checkNotNull(inputs) { "ReaderScreenState used before bind" }

    val unifiedEnabled: Boolean get() = inputs?.unified != null
    val proseReader: Boolean get() = inputs?.unified?.prose == true
    val textReader: Boolean get() = inputs?.unified?.kind == ReaderContentKind.TEXT
    val epubReader: Boolean get() = inputs?.unified?.kind == ReaderContentKind.EPUB
    private val imageContent: Boolean get() = inputs?.unified?.kind == ReaderContentKind.IMAGES

    /** Pages the reader can move between: measured visual pages of typed content, or the page list. */
    val pageCount: Int get() = if (unifiedEnabled) unifiedPageCount else current.pageCount

    val currentPage: Int get() = currentPosition.pageIndex

    val sessionId: String get() = current.sessionId

    val positionReportingEnabled: Boolean get() = current.positionReportingEnabled

    /** Takes this composition's [inputs] and restarts the state of a new chapter, session, content or layout. */
    fun bind(inputs: ReaderScreenInputs, callbacks: ReaderNavigationCallbacks) {
        this.inputs = inputs
        this.callbacks = callbacks
        val unified = inputs.unified
        val chapter = inputs.chapterId to inputs.sessionId
        val settings = inputs.settings
        controlsRestart.on(listOf(chapter, unified?.prose == true)) { controlsVisible = unified?.prose != true }
        sessionRestart.on(inputs.sessionId) {
            settingsVisible = false
            chapterListVisible = false
            unifiedTranslationActive = false
        }
        countRestart.on(listOf(chapter, unified?.representationId, unified?.initialPageIndex, unified?.initialPageCount)) {
            unifiedPageCount = initialPageCount(unified)
        }
        layoutRestart.on(
            listOf(
                chapter,
                unified?.representationId,
                settings.readingMode,
                settings.novelFontSizeSp,
                settings.novelLineHeightMultiplier,
                settings.novelMaxWidthDp,
            ),
        ) { pageCountMeasured = unified?.kind == ReaderContentKind.IMAGES }
        indexRestart.on(listOf(chapter, unified?.representationId, unified?.initialPageIndex)) {
            unifiedPageIndex = (unified?.initialPageIndex ?: unified?.initialItemIndex)
                ?.coerceIn(0, (unifiedPageCount - 1).coerceAtLeast(0))
                ?: 0
            pageRequest = UnifiedReaderPageRequest(unifiedPageIndex)
        }
        chapterRestart.on(chapter) { restartChapter() }
    }

    private fun restartChapter() {
        val input = current
        boundaryTransition = null
        navigationAction = null
        navigationRequestKey = 0L
        currentPosition = coerceReaderPosition(
            position = if (unifiedEnabled) {
                ReaderPosition(readingMode = input.settings.readingMode, pageIndex = unifiedPageIndex)
            } else {
                input.initialPosition ?: ReaderPosition(
                    readingMode = input.settings.readingMode,
                    pageIndex = input.storedPageIndex.coerceAtLeast(0),
                )
            },
            readingMode = input.settings.readingMode,
            pageCount = pageCount,
        )
        viewportRequestSerial = 0L
    }

    private fun initialPageCount(unified: ReaderContentShape?): Int = when (unified?.kind) {
        ReaderContentKind.IMAGES -> unified.itemCount
        ReaderContentKind.TEXT, ReaderContentKind.EPUB ->
            unified.initialPageCount ?: unified.initialPageIndex?.plus(1) ?: 1
        null -> 0
    }.coerceAtLeast(0)

    private fun continuous(): Boolean {
        val mode = current.settings.readingMode
        return isContinuousReaderMode(mode) || (textReader && isNovelContinuousMode(mode))
    }

    private fun mayCrossChapter(): Boolean = readerMayCrossChapterBoundary(
        proseReader = proseReader,
        pageCountMeasured = pageCountMeasured,
        interactionBlocked = current.loading,
    )

    /** Reports the position the reader is at, when it may report one. */
    fun commitCurrentPosition() {
        val input = current
        if (input.positionReportingEnabled && !input.loading && pageCount > 0) {
            callbacks?.onPositionChanged?.invoke(currentPosition)
        }
    }

    fun openPreviousChapter() {
        boundaryTransition = null
        if (current.hasPreviousChapter) {
            commitCurrentPosition()
            callbacks?.onPreviousChapter?.invoke()
        }
    }

    fun openNextChapter() {
        boundaryTransition = null
        if (current.hasNextChapter) {
            commitCurrentPosition()
            callbacks?.onNextChapter?.invoke()
        }
    }

    /** The toolbar's chapter buttons: prose crosses at once, pages show the boundary card first. */
    fun previousChapterControl() {
        if (proseReader) openPreviousChapter() else boundaryTransition = ReaderChapterBoundary.PREVIOUS
    }

    fun nextChapterControl() {
        if (proseReader) openNextChapter() else boundaryTransition = ReaderChapterBoundary.NEXT
    }

    fun toggleControls() {
        controlsVisible = !controlsVisible
    }

    fun requestTranslation() {
        translationRequest++
    }

    fun toggleTranslation() {
        translationToggle++
    }

    /** Moves to page [index]; past either end it crosses (prose) or shows the boundary card (pages). */
    fun requestPage(index: Int) {
        val count = pageCount
        when {
            index !in 0 until count && !mayCrossChapter() -> Unit
            index < 0 && proseReader -> openPreviousChapter()
            index < 0 -> boundaryTransition = ReaderChapterBoundary.PREVIOUS
            index >= count && proseReader -> openNextChapter()
            index >= count -> boundaryTransition = ReaderChapterBoundary.NEXT
            count > 0 -> moveTo(index.coerceIn(0, (count - 1).coerceAtLeast(0)), index, count)
        }
    }

    private fun moveTo(target: Int, logicalIndex: Int, count: Int) {
        boundaryTransition = null
        if (unifiedEnabled) pageRequest = pageRequest.request(target)
        val mode = current.settings.readingMode
        currentPosition = if (continuous()) {
            ReaderPosition(
                readingMode = mode,
                pageIndex = target,
                normalizedOffsetFraction = 0.0,
                resetEpoch = currentPosition.resetEpoch,
            )
        } else {
            pagedReaderPosition(
                readingMode = mode,
                logicalPageIndex = logicalIndex,
                pageCount = count,
                resetEpoch = currentPosition.resetEpoch,
            )
        }
        viewportRequestSerial++
    }

    private fun requestUnifiedNavigation(action: ReaderTapAction) {
        navigationAction = action
        navigationRequestKey++
    }

    /** The unified renderer reached its first or last page and was asked to go further. */
    fun handleUnifiedNavigationBoundary(action: ReaderTapAction) {
        if (current.loading) return
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> if (current.hasPreviousChapter) openPreviousChapter()
            ReaderTapAction.NEXT_PAGE -> if (current.hasNextChapter) openNextChapter()
            ReaderTapAction.TOGGLE_CHROME -> toggleControls()
        }
    }

    /** A position from elsewhere (a restored or remote one) replaces the current one. */
    fun applyExternalPosition(position: ReaderPosition) {
        currentPosition = coerceReaderPosition(position, current.settings.readingMode, pageCount)
        if (unifiedEnabled && pageCount > 0) pageRequest = pageRequest.request(currentPosition.pageIndex)
        boundaryTransition = null
        viewportRequestSerial++
    }

    /** The reading mode or page count changed: the position is kept inside them. */
    fun coercePosition() {
        currentPosition = coerceReaderPosition(currentPosition, current.settings.readingMode, pageCount)
        viewportRequestSerial++
    }

    /** A tap zone, a key or the toolbar asked for [action]. */
    fun handlePageAction(action: ReaderTapAction) {
        val boundary = boundaryTransition
        if (boundary != null) {
            when {
                action == ReaderTapAction.TOGGLE_CHROME -> toggleControls()
                boundary == ReaderChapterBoundary.PREVIOUS && action == ReaderTapAction.PREVIOUS_PAGE -> openPreviousChapter()
                boundary == ReaderChapterBoundary.NEXT && action == ReaderTapAction.NEXT_PAGE -> openNextChapter()
                else -> boundaryTransition = null
            }
            return
        }
        val page = if (imageContent) pageRequest.navigationIndex(unifiedPageIndex) else currentPage
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> requestPage(page - 1)
            ReaderTapAction.TOGGLE_CHROME -> toggleControls()
            ReaderTapAction.NEXT_PAGE -> requestPage(page + 1)
        }
    }

    /** Back inside the reader closes a sheet or the boundary card first; true, as it is always handled. */
    fun handleReaderBack(): Boolean {
        when {
            chapterListVisible -> chapterListVisible = false
            settingsVisible -> settingsVisible = false
            boundaryTransition != null -> boundaryTransition = null
            else -> {
                commitCurrentPosition()
                callbacks?.onClose?.invoke()
            }
        }
        return true
    }

    /** A chapter was picked in the chapter list. */
    fun selectChapter(chapterId: Long) {
        chapterListVisible = false
        if (chapterId != current.chapterId) {
            commitCurrentPosition()
            callbacks?.onChapterSelected?.invoke(chapterId)
        }
    }

    /**
     * Hardware volume buttons follow the original Shinsou reader semantics: move exactly one
     * page, and cross a chapter boundary immediately. Tap zones and pager swipes intentionally
     * keep the in-reader transition card so users can preview the destination chapter.
     */
    fun handleVolumeKey(event: ReaderVolumeKeyEvent): Boolean {
        // Unified image/EPUB surfaces already have a page graph. A parent loading flag must
        // not drop hardware paging, or manga volume keys stay silent after the first frame.
        if (pageCount <= 0 || (current.loading && !unifiedEnabled)) return false
        val forward = event == ReaderVolumeKeyEvent.VOLUME_DOWN
        val action = if (forward) ReaderTapAction.NEXT_PAGE else ReaderTapAction.PREVIOUS_PAGE
        return when {
            imageContent -> volumeKeyOnImages(forward, action)
            unifiedEnabled -> {
                requestUnifiedNavigation(action)
                true
            }
            else -> volumeKeyOnPages(forward, action)
        }
    }

    private fun volumeKeyOnImages(forward: Boolean, action: ReaderTapAction): Boolean {
        val target = imageSequenceNavigationTarget(
            currentIndex = pageRequest.navigationIndex(unifiedPageIndex),
            pageCount = pageCount,
            action = action,
        )
        // Image pages already expose a durable page-index request channel. Use it directly so a
        // volume press cannot be mistaken for the initial value when the pager is mounted or
        // recomposed after chapter loading.
        if (target != null) {
            requestPage(target)
            return true
        }
        return crossChapter(forward)
    }

    private fun volumeKeyOnPages(forward: Boolean, action: ReaderTapAction): Boolean {
        val atBoundary = if (forward) currentPage >= (pageCount - 1).coerceAtLeast(0) else currentPage <= 0
        if (!atBoundary) {
            handlePageAction(action)
            return true
        }
        // Plain text and EPUB start with only a provisional visual-page count. Until the renderer
        // reports its actual pagination, that provisional last page is not proof of a chapter
        // boundary and must never trigger an adjacent chapter.
        return mayCrossChapter() && crossChapter(forward)
    }

    private fun crossChapter(forward: Boolean): Boolean {
        val available = if (forward) current.hasNextChapter else current.hasPreviousChapter
        if (available) {
            if (forward) openNextChapter() else openPreviousChapter()
        }
        return available
    }

    /** The unified renderer shows [pageIndex] of [pageCount] measured pages. */
    fun onUnifiedPageObserved(pageIndex: Int, pageCount: Int) {
        val safePageCount = pageCount.coerceAtLeast(1)
        pageCountMeasured = true
        unifiedPageCount = safePageCount
        if (pageIndex !in 0 until safePageCount) return
        unifiedPageIndex = pageIndex
        pageRequest = pageRequest.observe(pageIndex)
        val mode = current.settings.readingMode
        currentPosition = if (continuous()) {
            ReaderPosition(
                readingMode = mode,
                pageIndex = pageIndex,
                normalizedOffsetFraction = 0.0,
                resetEpoch = currentPosition.resetEpoch,
            )
        } else {
            pagedReaderPosition(
                readingMode = mode,
                logicalPageIndex = pageIndex,
                pageCount = safePageCount,
                resetEpoch = currentPosition.resetEpoch,
            )
        }
    }

    /** A pager of the page reader settled on [logicalPage]. */
    fun onPagerPageChanged(logicalPage: Int) {
        currentPosition = pagedReaderPosition(
            readingMode = current.settings.readingMode,
            logicalPageIndex = logicalPage,
            pageCount = current.pageCount,
            resetEpoch = currentPosition.resetEpoch,
        )
    }
}

/** Runs a restart once for each new key, as a `remember` keyed on it would start afresh. */
private class Restart {
    private var key: Any? = Unset

    fun on(key: Any, restart: () -> Unit) {
        if (this.key == key) return
        this.key = key
        restart()
    }

    private object Unset
}
