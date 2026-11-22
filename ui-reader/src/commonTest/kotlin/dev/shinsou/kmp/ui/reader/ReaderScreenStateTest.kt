package dev.shinsou.kmp.ui.reader

import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.reader.ReaderPosition
import dev.shinsou.kmp.reader.ReaderTapAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderScreenStateTest {
    private class Host {
        val events = mutableListOf<String>()
        val positions = mutableListOf<Int>()
        val callbacks = ReaderNavigationCallbacks(
            onClose = { events += "close" },
            onPositionChanged = { positions += it.pageIndex },
            onPreviousChapter = { events += "previous" },
            onNextChapter = { events += "next" },
            onChapterSelected = { events += "chapter:$it" },
        )
    }

    private fun inputs(
        chapterId: Long = 1L,
        sessionId: String = "s1",
        pageCount: Int = 5,
        storedPage: Int = 0,
        loading: Boolean = false,
        unified: ReaderContentShape? = null,
        mode: ReadingMode = ReadingMode.PAGER_LTR,
        initialPosition: ReaderPosition? = null,
    ) = ReaderScreenInputs(
        chapterId = chapterId,
        sessionId = sessionId,
        settings = ReaderSettings(readingMode = mode),
        pageCount = pageCount,
        storedPageIndex = storedPage,
        initialPosition = initialPosition,
        loading = loading,
        hasPreviousChapter = true,
        hasNextChapter = true,
        positionReportingEnabled = true,
        unified = unified,
    )

    @Test
    fun startsAtTheStoredPageOrTheRestoredPosition() {
        val host = Host()
        val state = ReaderScreenState()
        state.bind(inputs(storedPage = 3), host.callbacks)
        assertEquals(3, state.currentPage)
        val restored = ReaderScreenState()
        restored.bind(inputs(storedPage = 3, initialPosition = ReaderPosition(ReadingMode.PAGER_LTR, 1)), host.callbacks)
        assertEquals(1, restored.currentPage)
    }

    @Test
    fun pagingPastTheEndShowsTheBoundaryCardThenCrosses() {
        val host = Host()
        val state = ReaderScreenState()
        state.bind(inputs(storedPage = 4), host.callbacks)
        state.handlePageAction(ReaderTapAction.NEXT_PAGE)
        assertEquals(ReaderChapterBoundary.NEXT, state.boundaryTransition)
        assertEquals(emptyList(), host.events)
        state.handlePageAction(ReaderTapAction.NEXT_PAGE)
        assertNull(state.boundaryTransition)
        assertEquals(listOf("next"), host.events)
        // The position was reported before the chapter changed.
        assertEquals(listOf(4), host.positions)
    }

    @Test
    fun backClosesSheetsAndTheCardBeforeTheReader() {
        val host = Host()
        val state = ReaderScreenState()
        state.bind(inputs(), host.callbacks)
        state.chapterListVisible = true
        state.settingsVisible = true
        state.boundaryTransition = ReaderChapterBoundary.PREVIOUS
        assertTrue(state.handleReaderBack())
        assertFalse(state.chapterListVisible)
        state.handleReaderBack()
        assertFalse(state.settingsVisible)
        state.handleReaderBack()
        assertNull(state.boundaryTransition)
        assertEquals(emptyList(), host.events)
        state.handleReaderBack()
        assertEquals(listOf("close"), host.events)
    }

    @Test
    fun volumeKeysCrossChaptersAtOnce() {
        val host = Host()
        val state = ReaderScreenState()
        state.bind(inputs(storedPage = 3), host.callbacks)
        assertTrue(state.handleVolumeKey(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertEquals(4, state.currentPage)
        assertTrue(state.handleVolumeKey(ReaderVolumeKeyEvent.VOLUME_DOWN))
        assertEquals(listOf("next"), host.events)
        // While the page reader loads, the keys stay with the system.
        val loading = ReaderScreenState()
        loading.bind(inputs(loading = true), host.callbacks)
        assertFalse(loading.handleVolumeKey(ReaderVolumeKeyEvent.VOLUME_DOWN))
    }

    @Test
    fun typedImagesCountTheirItemsAndReportObservedPages() {
        val host = Host()
        val state = ReaderScreenState()
        val images = ReaderContentShape(ReaderContentKind.IMAGES, "r1", itemCount = 8, initialItemIndex = 2)
        state.bind(inputs(pageCount = 0, unified = images), host.callbacks)
        assertEquals(8, state.pageCount)
        assertEquals(2, state.unifiedPageIndex)
        assertTrue(state.pageCountMeasured)
        state.handlePageAction(ReaderTapAction.NEXT_PAGE)
        assertEquals(3, state.pageRequest.targetIndex)
        state.onUnifiedPageObserved(pageIndex = 3, pageCount = 8)
        assertEquals(3, state.currentPage)
    }

    @Test
    fun proseCrossesChaptersOnlyOnceItsPagesAreMeasured() {
        val host = Host()
        val state = ReaderScreenState()
        val text = ReaderContentShape(ReaderContentKind.TEXT, "t1", itemCount = 40, initialItemIndex = 0)
        state.bind(inputs(pageCount = 0, unified = text), host.callbacks)
        assertEquals(1, state.pageCount)
        assertFalse(state.controlsVisible)
        state.requestPage(1)
        assertEquals(emptyList(), host.events)
        state.onUnifiedPageObserved(pageIndex = 0, pageCount = 1)
        state.requestPage(1)
        assertEquals(listOf("next"), host.events)
    }

    @Test
    fun aNewChapterOrSessionStartsAfreshButKeepsTranslationRequests() {
        val host = Host()
        val state = ReaderScreenState()
        state.bind(inputs(), host.callbacks)
        state.settingsVisible = true
        state.requestPage(2)
        state.requestTranslation()
        state.bind(inputs(storedPage = 2), host.callbacks)
        // Same chapter and session: nothing restarts.
        assertTrue(state.settingsVisible)
        assertEquals(2, state.currentPage)
        state.bind(inputs(chapterId = 2L, sessionId = "s2"), host.callbacks)
        assertFalse(state.settingsVisible)
        assertEquals(0, state.currentPage)
        assertEquals(0L, state.viewportRequestSerial)
        assertEquals(1L, state.translationRequest)
    }

    @Test
    fun aLayoutChangeAsksForPaginationAgain() {
        val host = Host()
        val state = ReaderScreenState()
        val text = ReaderContentShape(ReaderContentKind.TEXT, "t1", itemCount = 40, initialItemIndex = 0)
        state.bind(inputs(pageCount = 0, unified = text), host.callbacks)
        state.onUnifiedPageObserved(pageIndex = 0, pageCount = 6)
        assertTrue(state.pageCountMeasured)
        state.bind(inputs(pageCount = 0, unified = text, mode = ReadingMode.PAGER_RTL), host.callbacks)
        assertFalse(state.pageCountMeasured)
        // The measured count itself stays until the renderer reports again.
        assertEquals(6, state.pageCount)
    }

    @Test
    fun choosingAnotherChapterReportsThePositionFirst() {
        val host = Host()
        val state = ReaderScreenState()
        state.bind(inputs(storedPage = 2), host.callbacks)
        state.chapterListVisible = true
        state.selectChapter(1L)
        assertFalse(state.chapterListVisible)
        assertEquals(emptyList(), host.events)
        state.selectChapter(9L)
        assertEquals(listOf("chapter:9"), host.events)
        assertEquals(listOf(2), host.positions)
    }
}
