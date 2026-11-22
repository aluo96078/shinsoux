package dev.shinsou.kmp.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageViewerTest {
    @Test
    fun fileNameKeepsTheUrlNameAndAUsualExtension() {
        assertEquals("shinsou-157328.jpg", imageFileNameFor("https://pic.777743.xyz/2/2883/116186/157328.jpg"))
        assertEquals("shinsou-a.png", imageFileNameFor("https://x.test/p/a.png?w=1#top"))
        assertEquals("shinsou-page.jpg", imageFileNameFor("https://x.test/page.php"))
    }

    @Test
    fun fileNameNeverCarriesPathOrOddCharacters() {
        assertEquals("shinsou-illustration.jpg", imageFileNameFor("https://x.test/", fallbackStem = "illustration"))
        val name = imageFileNameFor("https://x.test/a%2F..%5Cb.jpg")
        assertEquals(true, name.none { it == '/' || it == '\\' || it == '%' }, name)
        assertEquals(true, name.endsWith(".jpg"), name)
    }
}
