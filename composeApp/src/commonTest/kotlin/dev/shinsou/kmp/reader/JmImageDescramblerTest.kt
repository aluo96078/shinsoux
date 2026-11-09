package dev.shinsou.kmp.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertNull(JmImageDescrambler.transform("77", metadata))
        assertEquals(
            ReaderImageTransform.ReverseVerticalSegments(4),
            JmImageDescrambler.transform(JmImageDescrambler.SOURCE_ID.toString(), metadata),
        )
        assertNull(
            JmImageDescrambler.transform(
                JmImageDescrambler.SOURCE_ID.toString(),
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

class JmSegmentBoundaryTest {
    private data class Slice(val sourceTop: Int, val sourceHeight: Int, val destinationTop: Int, val destinationHeight: Int)

    private fun slices(height: Int, segments: Int, naturalHeight: Int = height): List<Slice> {
        val out = mutableListOf<Slice>()
        drawReversedSegments(1, height, segments, naturalHeight) { s, sh, d, dh -> out += Slice(s, sh, d, dh) }
        return out
    }

    @Test
    fun naturalHeightCutsMatchOfficialFormula() {
        val height = 1300
        val segments = 18
        val base = height / segments
        val overflow = height % segments
        val expected = List(segments) { index ->
            val top = height - base * (index + 1) - overflow
            val slice = base + if (index == 0) overflow else 0
            Slice(top, slice, base * index + if (index == 0) 0 else overflow, slice)
        }
        assertEquals(expected, slices(height, segments))
    }

    @Test
    fun scaledCutsStayWithinOnePixelOfTheNaturalCutsAndTileWithoutGaps() {
        // Non-integer scales with h % n != 0, as on a phone decoding at the view's size.
        listOf(Triple(1300, 18, 0.85), Triple(3001, 20, 0.39), Triple(2731, 14, 0.613), Triple(1300, 6, 0.5)).forEach { (h, n, scale) ->
            assertTrue(h % n != 0)
            val decoded = (h * scale).toInt()
            val ratio = decoded.toDouble() / h
            val natural = slices(h, n)
            val scaled = slices(decoded, n, naturalHeight = h)
            assertEquals(natural.size, scaled.size)
            natural.zip(scaled).forEach { (a, b) ->
                assertTrue(kotlin.math.abs(a.sourceTop * ratio - b.sourceTop) <= 1.0, "source cut $a -> $b at $h/$n/$scale")
                assertTrue(kotlin.math.abs(a.destinationTop * ratio - b.destinationTop) <= 1.0, "destination cut $a -> $b")
                assertTrue(kotlin.math.abs((a.sourceTop + a.sourceHeight) * ratio - (b.sourceTop + b.sourceHeight)) <= 1.0)
            }
            // Both sides cover every decoded row exactly once.
            assertEquals((0 until decoded).toList(), scaled.flatMap { it.destinationTop until it.destinationTop + it.destinationHeight }.sorted())
            assertEquals((0 until decoded).toList(), scaled.flatMap { it.sourceTop until it.sourceTop + it.sourceHeight }.sorted())
        }
    }

    @Test
    fun cuttingTheDecodedHeightDirectlyDriftsFromTheNaturalCuts() {
        // The premise: computing floor(h'/n) and h' % n on a downsampled bitmap misplaces cuts.
        val h = 3000
        val n = 20
        val decoded = (h * 0.39).toInt()
        val ratio = decoded.toDouble() / h
        val naive = slices(decoded, n)
        val natural = slices(h, n)
        assertTrue(natural.zip(naive).any { (a, b) -> kotlin.math.abs(a.sourceTop * ratio - b.sourceTop) > 1.0 })
    }
}
