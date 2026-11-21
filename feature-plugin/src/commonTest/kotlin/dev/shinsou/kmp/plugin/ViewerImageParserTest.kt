package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewerImageParserTest {
    @Test
    fun findsTargetImageRegardlessOfAttributeOrderOrQuoteStyle() {
        val html = """
            <img id="thumbnail" src="ignored.jpg">
            <IMG class="full" src='../images/page.jpg?token=a&amp;next=b&gt;c' data-note='x>y' ID = 'img'>
        """.trimIndent()

        assertEquals(
            "../images/page.jpg?token=a&next=b>c",
            ViewerImageParser.extractImageSource(html),
        )
    }

    @Test
    fun decodesNumericEntitiesAndResolvesRelativeViewerUrl() {
        val html = "<img src=\"..&#x2f;..&#47;full/page.jpg?x=1&amp;y=2\" id=\"img\">"
        val source = ViewerImageParser.extractImageSource(html)

        assertEquals("../../full/page.jpg?x=1&y=2", source)
        assertEquals(
            "https://reader.example/full/page.jpg?x=1&y=2",
            ViewerImageParser.resolveUrl(
                "https://reader.example/gallery/viewer/page.html",
                requireNotNull(source),
            ),
        )
    }

    @Test
    fun supportsProtocolRelativeAndRejectsUnrelatedImages() {
        assertEquals(
            "https://cdn.example/page.jpg",
            ViewerImageParser.resolveUrl("https://reader.example/s/1", "//cdn.example/page.jpg"),
        )
        assertNull(ViewerImageParser.extractImageSource("<img id='other' src='wrong.jpg'>"))
    }
}
