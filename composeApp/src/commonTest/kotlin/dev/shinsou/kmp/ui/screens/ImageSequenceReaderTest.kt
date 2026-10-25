package dev.shinsou.kmp.ui.screens

import androidx.compose.ui.input.key.Key
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.reader.ReaderTapAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageSequenceReaderTest {
    @Test
    fun readingModesSelectTheirActualImageContainer() {
        assertEquals(
            ImageSequenceReaderLayout.HORIZONTAL_PAGER,
            imageSequenceReaderLayout(ReadingMode.PAGER_LTR),
        )
        assertEquals(
            ImageSequenceReaderLayout.HORIZONTAL_PAGER,
            imageSequenceReaderLayout(ReadingMode.PAGER_RTL),
        )
        assertEquals(
            ImageSequenceReaderLayout.VERTICAL_PAGER,
            imageSequenceReaderLayout(ReadingMode.PAGER_VERTICAL),
        )
        assertEquals(
            ImageSequenceReaderLayout.CONTINUOUS,
            imageSequenceReaderLayout(ReadingMode.WEBTOON),
        )
        assertEquals(
            ImageSequenceReaderLayout.CONTINUOUS,
            imageSequenceReaderLayout(ReadingMode.CONTINUOUS_VERTICAL),
        )
    }

    @Test
    fun decodedPagesKeepTheirIntrinsicAspectRatio() {
        assertEquals(0.5f, imageSequencePageAspectRatio(width = 1080, height = 2160))
        assertEquals(2f, imageSequencePageAspectRatio(width = 2160, height = 1080))
    }

    @Test
    fun invalidDecodedDimensionsDoNotCreateAConstraint() {
        assertNull(imageSequencePageAspectRatio(width = 0, height = 2160))
        assertNull(imageSequencePageAspectRatio(width = 1080, height = 0))
        assertNull(imageSequencePageAspectRatio(width = -1, height = 2160))
    }

    @Test
    fun externalPageRequestsAreAppliedAndClampedAfterTheInitialLayout() {
        assertNull(requestedImageSequencePageIndex(0L, requestedPageIndex = 2, pageCount = 5))
        assertEquals(2, requestedImageSequencePageIndex(1L, requestedPageIndex = 2, pageCount = 5))
        assertEquals(4, requestedImageSequencePageIndex(2L, requestedPageIndex = 99, pageCount = 5))
        assertNull(requestedImageSequencePageIndex(2L, requestedPageIndex = 0, pageCount = 0))
    }

    @Test
    fun imageSequenceVolumeNavigationMovesOnePageAndStopsAtTheBoundary() {
        assertEquals(1, imageSequenceNavigationTarget(0, 3, ReaderTapAction.NEXT_PAGE))
        assertEquals(2, imageSequenceNavigationTarget(1, 3, ReaderTapAction.NEXT_PAGE))
        assertNull(imageSequenceNavigationTarget(2, 3, ReaderTapAction.NEXT_PAGE))
        assertEquals(0, imageSequenceNavigationTarget(1, 3, ReaderTapAction.PREVIOUS_PAGE))
        assertNull(imageSequenceNavigationTarget(0, 3, ReaderTapAction.PREVIOUS_PAGE))
        assertNull(imageSequenceNavigationTarget(1, 3, ReaderTapAction.TOGGLE_CHROME))
    }

    @Test
    fun delayedProgressCannotOverwriteAnOppositePageRequest() {
        var request = UnifiedReaderPageRequest(targetIndex = 2)

        request = request.request(3)
        request = request.observe(3)
        request = request.request(2)
        request = request.observe(3)

        assertEquals(2, request.targetIndex)
        assertEquals(2, request.navigationIndex(observedIndex = 3))
        assertEquals(true, request.pending)

        request = request.observe(2)
        assertEquals(false, request.pending)
        assertEquals(2, request.navigationIndex(observedIndex = 2))
    }

    @Test
    fun unifiedImageReadersKeepHardwareVolumeKeysOnTheHost() {
        assertEquals(true, readerPreviewKeyIsHostOwned(Key.VolumeDown, unifiedImageOrEpubReader = true))
        assertEquals(true, readerPreviewKeyIsHostOwned(Key.VolumeUp, unifiedImageOrEpubReader = true))
        assertEquals(true, readerPreviewKeyIsHostOwned(Key.Escape, unifiedImageOrEpubReader = true))
        assertEquals(false, readerPreviewKeyIsHostOwned(Key.DirectionRight, unifiedImageOrEpubReader = true))
        assertEquals(true, readerPreviewKeyIsHostOwned(Key.DirectionRight, unifiedImageOrEpubReader = false))
    }
}
