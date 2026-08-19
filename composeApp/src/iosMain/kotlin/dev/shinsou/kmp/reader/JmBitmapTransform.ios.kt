package dev.shinsou.kmp.reader

import coil3.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

internal actual fun reverseVerticalSegments(input: Bitmap, segmentCount: Int): Bitmap {
    val width = input.width
    val height = input.height
    if (width <= 0 || height <= segmentCount) return input

    val output = Bitmap()
    if (!output.allocPixels(input.imageInfo)) return input
    val image = Image.makeFromBitmap(input)
    val canvas = Canvas(output)
    try {
        drawReversedSegments(width, height, segmentCount) { sourceTop, destinationTop, sliceHeight ->
            canvas.drawImageRect(
                image,
                Rect.makeXYWH(0f, sourceTop.toFloat(), width.toFloat(), sliceHeight.toFloat()),
                Rect.makeXYWH(0f, destinationTop.toFloat(), width.toFloat(), sliceHeight.toFloat()),
            )
        }
    } finally {
        canvas.close()
        image.close()
    }
    return output
}
