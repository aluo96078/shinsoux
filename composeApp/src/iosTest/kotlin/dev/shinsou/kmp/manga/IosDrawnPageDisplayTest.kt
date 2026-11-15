package dev.shinsou.kmp.manga

import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drawn pages reach the reader through Skia, which on iOS has no JPEG decoder (ADR-0005): what
 * [PageCodec.forDisplay] hands over must decode there, at the page's own size.
 */
class IosDrawnPageDisplayTest {
    @Test
    fun aDrawnPageDecodesInTheReadersImageLoader() {
        val codec = IosMangaPlatform().codec
        val page = RgbImage(120, 80, ByteArray(120 * 80 * 3) { (it % 251).toByte() })
        val jpeg = codec.encodeJpeg(page, 90)
        assertTrue(runCatching { Image.makeFromEncoded(jpeg) }.isFailure, "if iOS's Skia reads JPEG now, forDisplay can pass it through")
        val shown = Image.makeFromEncoded(codec.forDisplay(jpeg))
        assertEquals(120, shown.width); assertEquals(80, shown.height)
    }
}
