package dev.shinsou.kmp.reader

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.transformations
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JmTransformedImageRequestTest {
    private val width = 2
    private val height = 130 // 130 % 18 = 4, so cutting the decoded height directly would drift.
    private val segments = 18

    private fun encodedRows(): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        val paint = Paint()
        repeat(height) { row ->
            paint.color = Color.makeRGB(row, 0, 0)
            surface.canvas.drawRect(Rect.makeXYWH(0f, row.toFloat(), width.toFloat(), 1f), paint)
        }
        val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        paint.close()
        surface.close()
        return bytes
    }

    private suspend fun decode(bytes: ByteArray, configure: ImageRequest.Builder.() -> ImageRequest.Builder): Bitmap {
        val context = PlatformContext.INSTANCE
        val request = ImageRequest.Builder(context).data(bytes).configure().build()
        val result = ImageLoader(context).execute(request)
        assertIs<SuccessResult>(result, result.toString())
        return result.image.toBitmap()
    }

    @Test
    fun headerHeightIsReadWithoutDecoding() {
        assertEquals(height, encodedImageHeight(encodedRows()))
        assertEquals(null, encodedImageHeight("not an image".encodeToByteArray()))
    }

    @Test
    fun transformedRequestKeepsTheViewSize() = runTest {
        val viewSize = Size(width, height / 2)
        val built = ImageRequest.Builder(PlatformContext.INSTANCE)
            .size(viewSize)
            .readerImageTransform(ReaderImageTransform.ReverseVerticalSegments(segments), naturalHeight = height)
            .build()
        // The full-size decode is gone: the request still decodes at the view's size.
        assertEquals(viewSize, built.sizeResolver.size())
        assertEquals(
            "shinsou-jm-reverse-vertical:$segments:$height:v3",
            built.transformations.single().cacheKey,
        )
    }

    @Test
    fun downsampledPageIsRestoredOnScaledNaturalCuts() = runTest {
        val bytes = encodedRows()
        val transform = ReaderImageTransform.ReverseVerticalSegments(segments)
        val output = decode(bytes) {
            size(width, height / 2).readerImageTransform(transform, encodedImageHeight(bytes))
        }
        assertTrue(output.height < height, "expected a downsampled decode, got ${output.height}")

        // Full-resolution descramble: which source row lands on each output row, and its slice.
        val sourceRow = IntArray(height)
        val sliceOf = IntArray(height)
        var slice = 0
        drawReversedSegments(width, height, segments) { sourceTop, _, destinationTop, sliceHeight ->
            repeat(sliceHeight) {
                sourceRow[destinationTop + it] = sourceTop + it
                sliceOf[destinationTop + it] = slice
            }
            slice++
        }
        val ratio = height.toDouble() / output.height
        var checked = 0
        repeat(output.height) { row ->
            val low = (row * ratio).toInt().coerceIn(0, height - 1)
            val high = ((row + 1) * ratio).toInt().coerceIn(0, height - 1)
            // Rows that straddle a cut legitimately mix two slices after filtering.
            if (sliceOf[low] != sliceOf[high]) return@repeat
            val expected = (sourceRow[low] + sourceRow[high]) / 2.0
            val actual = Color.getR(output.getColor(0, row))
            assertTrue(kotlin.math.abs(actual - expected) <= 3.0, "row $row: expected ~$expected, got $actual")
            checked++
        }
        assertTrue(checked > output.height / 2, "too few rows were checked: $checked")
    }
}
