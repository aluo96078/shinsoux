package dev.shinsou.kmp.reader

import coil3.size.Size
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.IRect
import kotlin.test.Test
import kotlin.test.assertEquals

class JmVerticalSegmentTransformationTest {
    @Test
    fun reversesSlicesAndKeepsOverflowWithFirstOutputSlice() = runTest {
        val input = Bitmap().apply { check(allocN32Pixels(2, 10, false)) }
        repeat(input.height) { row ->
            input.erase(Color.makeRGB(row, 0, 0), IRect.makeXYWH(0, row, input.width, 1))
        }

        val transformation = JmVerticalSegmentTransformation(segmentCount = 4)
        val output = transformation.transform(input, Size.ORIGINAL)
        try {
            assertEquals("shinsou-jm-reverse-vertical:4:v1", transformation.cacheKey)
            assertEquals(
                listOf(6, 7, 8, 9, 4, 5, 2, 3, 0, 1),
                List(output.height) { row -> Color.getR(output.getColor(0, row)) },
            )
        } finally {
            if (output !== input) output.close()
            input.close()
        }
    }
}
