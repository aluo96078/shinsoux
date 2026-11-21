package dev.shinsou.kmp.network

import android.graphics.BitmapFactory

public actual fun encodedImageHeight(bytes: ByteArray): Int? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // A bounds-only decode never allocates pixels; any platform failure means "unknown height".
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrElse { return null }
    return options.outHeight.takeIf { it > 0 }
}
