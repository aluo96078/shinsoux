package dev.shinsou.kmp.reader

import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import coil3.Bitmap

internal actual fun reverseVerticalSegments(input: Bitmap, segmentCount: Int): Bitmap {
    val width = input.width
    val height = input.height
    if (width <= 0 || height <= segmentCount) return input

    val config = input.config?.takeUnless { it == Config.HARDWARE } ?: Config.ARGB_8888
    val output = Bitmap.createBitmap(width, height, config).apply {
        density = input.density
        setHasAlpha(input.hasAlpha())
    }
    val canvas = Canvas(output)
    val paint = Paint().apply { isFilterBitmap = false }
    drawReversedSegments(width, height, segmentCount) { sourceTop, destinationTop, sliceHeight ->
        canvas.drawBitmap(
            input,
            Rect(0, sourceTop, width, sourceTop + sliceHeight),
            Rect(0, destinationTop, width, destinationTop + sliceHeight),
            paint,
        )
    }
    return output
}
