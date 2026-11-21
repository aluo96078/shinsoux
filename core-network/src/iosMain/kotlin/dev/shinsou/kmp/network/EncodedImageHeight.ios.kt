package dev.shinsou.kmp.network

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

/** Reads only the header: a Skia codec reports dimensions without decoding any pixels. */
public actual fun encodedImageHeight(bytes: ByteArray): Int? {
    val data = Data.makeFromBytes(bytes)
    return try {
        // A decode probe: bytes Skia cannot read mean "unknown height".
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
