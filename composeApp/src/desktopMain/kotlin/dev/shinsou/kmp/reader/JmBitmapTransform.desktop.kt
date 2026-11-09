package dev.shinsou.kmp.reader

import coil3.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

internal actual fun reverseVerticalSegments(input: Bitmap, segmentCount: Int, naturalHeight: Int): Bitmap {
    val width = input.width
    val height = input.height
    if (width <= 0 || height <= 0 || naturalHeight <= segmentCount) return input

    val output = Bitmap()
    if (!output.allocPixels(input.imageInfo)) return input
    val image = Image.makeFromBitmap(input)
    val canvas = Canvas(output)
    try {
        drawReversedSegments(width, height, segmentCount, naturalHeight) { sourceTop, sourceHeight, destinationTop, destinationHeight ->
            canvas.drawImageRect(
                image,
                Rect.makeXYWH(0f, sourceTop.toFloat(), width.toFloat(), sourceHeight.toFloat()),
                Rect.makeXYWH(0f, destinationTop.toFloat(), width.toFloat(), destinationHeight.toFloat()),
            )
        }
    } finally {
        canvas.close()
        image.close()
    }
    return output
}

/** Reads only the header: a Skia codec reports dimensions without decoding any pixels. */
internal actual fun encodedImageHeight(bytes: ByteArray): Int? {
    val data = Data.makeFromBytes(bytes)
    return try {
        val codec = runCatching { Codec.makeFromData(data) }.getOrNull() ?: return null
        try {
            codec.height.takeIf { it > 0 }
        } finally {
            codec.close()
        }
    } finally {
        data.close()
    }
}
