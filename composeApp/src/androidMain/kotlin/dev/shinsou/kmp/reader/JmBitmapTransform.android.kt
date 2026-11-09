package dev.shinsou.kmp.reader

import android.graphics.Bitmap.Config
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import coil3.Bitmap

internal actual fun reverseVerticalSegments(input: Bitmap, segmentCount: Int, naturalHeight: Int): Bitmap {
    val width = input.width
    val height = input.height
    if (width <= 0 || height <= 0 || naturalHeight <= segmentCount) return input

    val config = input.config?.takeUnless { it == Config.HARDWARE } ?: Config.ARGB_8888
    val output = Bitmap.createBitmap(width, height, config).apply {
        density = input.density
        setHasAlpha(input.hasAlpha())
    }
    val canvas = Canvas(output)
    val paint = Paint().apply { isFilterBitmap = false }
    drawReversedSegments(width, height, segmentCount, naturalHeight) { sourceTop, sourceHeight, destinationTop, destinationHeight ->
        canvas.drawBitmap(
            input,
            Rect(0, sourceTop, width, sourceTop + sourceHeight),
            Rect(0, destinationTop, width, destinationTop + destinationHeight),
            paint,
        )
    }
    return output
}

internal actual fun encodedImageHeight(bytes: ByteArray): Int? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // A bounds-only decode never allocates pixels; any platform failure means "unknown height".
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrElse { return null }
    return options.outHeight.takeIf { it > 0 }
}
