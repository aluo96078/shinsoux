package dev.shinsou.kmp.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JmImageDescramblerTest {
    @Test
    fun segmentationMatchesOriginalThresholdAndMd5Rules() {
        assertEquals(0, JmImageDescrambler.segmentationCount(300_001, 300_000, "abc"))
        assertEquals(10, JmImageDescrambler.segmentationCount(220_980, 268_849, "abc"))
        assertEquals(4, JmImageDescrambler.segmentationCount(220_980, 300_000, "abc"))
        assertEquals(18, JmImageDescrambler.segmentationCount(220_980, 421_925, "sample"))
        assertEquals(10, JmImageDescrambler.segmentationCount(220_980, 421_926, "sample"))
        assertEquals(12, JmImageDescrambler.segmentationCount(220_980, 500_000, "001"))
    }

    @Test
    fun requiresJinmanSourceAndAllMetadata() {
        val metadata = mapOf(
            JmImageDescrambler.SCRAMBLE_ID_KEY to "220980",
            JmImageDescrambler.PHOTO_ID_KEY to "300000",
            JmImageDescrambler.FILENAME_KEY to "abc",
        )
        assertNull(JmImageDescrambler.transform(77, metadata))
        assertEquals(
            ReaderImageTransform.ReverseVerticalSegments(4),
            JmImageDescrambler.transform(JmImageDescrambler.SOURCE_ID, metadata),
        )
        assertNull(
            JmImageDescrambler.transform(
                JmImageDescrambler.SOURCE_ID,
                metadata - JmImageDescrambler.FILENAME_KEY,
            ),
        )
    }

    @Test
    fun sidecarRoundTripsTransform() {
        val transform = ReaderImageTransform.ReverseVerticalSegments(18)
        assertEquals(transform, ReaderImageTransform.decodeSidecar(transform.encodeSidecar()))
        assertNull(ReaderImageTransform.decodeSidecar("unknown:18".encodeToByteArray()))
    }
}
