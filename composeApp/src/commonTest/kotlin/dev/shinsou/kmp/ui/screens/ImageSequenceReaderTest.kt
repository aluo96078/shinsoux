package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.domain.model.ReadingMode
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
}
