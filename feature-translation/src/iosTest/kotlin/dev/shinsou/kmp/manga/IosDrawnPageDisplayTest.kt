package dev.shinsou.kmp.manga

import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Drawn pages reach the reader through Skia (ADR-0005): what [PageCodec.forDisplay] hands over
 * must decode there, at the page's own size. The Skia of Compose 1.11 reads JPEG on iOS, and a
 * page it reads is handed over as it is; one it cannot read would be turned into PNG.
 */
class IosDrawnPageDisplayTest {
    @Test
    fun aDrawnPageDecodesInTheReadersImageLoader() {
        val codec = IosMangaPlatform().codec
        val page = RgbImage(120, 80, ByteArray(120 * 80 * 3) { (it % 251).toByte() })
        val jpeg = codec.encodeJpeg(page, 90)

        val display = codec.forDisplay(jpeg)
        val shown = Image.makeFromEncoded(display)
        assertEquals(120, shown.width)
        assertEquals(80, shown.height)
        // The JPEG decodes in the reader's Skia, so it is not converted (and drawn pages cost no
        // PNG encoding); without a JPEG decoder this fails, as the conversion is needed again.
        assertEquals(120, Image.makeFromEncoded(jpeg).width, "Skia reads the drawn JPEG")
        assertContentEquals(jpeg, display)
    }
}
