package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.reader.ReaderTapAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NovelReaderContentTest {
    @Test
    fun usesShuYueEdgeZonesAndReversesThemForRtl() {
        assertEquals(
            ReaderTapAction.PREVIOUS_PAGE,
            novelTapAction(20f, 100f, ReadingMode.PAGER_LTR),
        )
        assertEquals(
            ReaderTapAction.TOGGLE_CHROME,
            novelTapAction(50f, 100f, ReadingMode.PAGER_LTR),
        )
        assertEquals(
            ReaderTapAction.NEXT_PAGE,
            novelTapAction(80f, 100f, ReadingMode.PAGER_LTR),
        )
        assertEquals(
            ReaderTapAction.NEXT_PAGE,
            novelTapAction(20f, 100f, ReadingMode.PAGER_RTL),
        )
        assertEquals(
            ReaderTapAction.PREVIOUS_PAGE,
            novelTapAction(80f, 100f, ReadingMode.PAGER_RTL),
        )
    }

    @Test
    fun sanitizesPublisherHtmlWithoutLosingSourceOffsets() {
        val display = sanitizeNovelDisplayText("<p>第一段&amp;文字</p><br><b>第二段</b>")

        assertEquals("第一段&文字\n第二段", display.value)
        assertEquals(display.value.length + 1, display.sourceOffsets.size)
        assertEquals(3, display.sourceOffsets.first())
        assertEquals("<p>第一段&amp;文字</p><br><b>第二段</b>".length, display.sourceOffsets.last())
    }

    @Test
    fun preservesLiteralAngleBracketsAndDecodesNumericEntities() {
        val source = "1 < 2 > 0 &#20013; &#x6587; &#x1F600;"
        val display = sanitizeNovelDisplayText(source)

        assertEquals("1 < 2 > 0 中 文 😀", display.value)
        assertEquals(display.value.length + 1, display.sourceOffsets.size)
        assertEquals(source.length, display.sourceOffsets.last())
    }

    @Test
    fun preferredBreakUsesPunctuationAndNeverSplitsASurrogatePair() {
        assertEquals(3, preferredNovelPageBreak("一二。三四", start = 0, measuredEnd = 4))

        val text = "甲😀乙丙"
        val boundary = preferredNovelPageBreak(text, start = 0, measuredEnd = 2)
        assertTrue(
            boundary == 0 || boundary == text.length ||
                !(text[boundary - 1].isHighSurrogate() && text[boundary].isLowSurrogate()),
        )
    }

    @Test
    fun pagedReadingReportsTheDocumentEndOnTheLastVisualPage() {
        val pages = listOf(
            NovelVisualPage.Text("第一頁", sourceStartUtf16 = 0, sourceEndUtf16 = 3),
            NovelVisualPage.Text("第二頁", sourceStartUtf16 = 3, sourceEndUtf16 = 6),
        )

        assertEquals(0, novelPagedSourceOffset(pages, 0))
        assertEquals(6, novelPagedSourceOffset(pages, 1))
    }

    @Test
    fun continuousReadingKeepsAnInPageOffsetAndReportsTheDocumentEnd() {
        val source = "甲乙😀丙丁戊"
        val pages = listOf(
            NovelVisualPage.Text("甲乙😀", sourceStartUtf16 = 0, sourceEndUtf16 = 4),
            NovelVisualPage.Text("丙丁戊", sourceStartUtf16 = 4, sourceEndUtf16 = source.length),
        )

        val middle = novelViewportSourceOffset(
            source = source,
            pages = pages,
            viewport = NovelViewportPosition(
                pageIndex = 0,
                pageOffsetFraction = 0.75,
                atDocumentEnd = false,
            ),
        )
        assertTrue(
            middle == 0 || middle == source.length ||
                !(source[middle - 1].isHighSurrogate() && source[middle].isLowSurrogate()),
        )
        assertEquals(
            source.length,
            novelViewportSourceOffset(
                source = source,
                pages = pages,
                viewport = NovelViewportPosition(
                    pageIndex = pages.lastIndex,
                    pageOffsetFraction = 0.0,
                    atDocumentEnd = true,
                ),
            ),
        )
    }

    @Test
    fun parsesWenku8MarkdownImagesWithoutDroppingSurroundingText() {
        val segments = parseNovelReaderSegments(
            "開頭\n\n![插圖](https://img.wenku8.com/image/1/2/2.jpg)\n\n結尾",
        )

        assertEquals(3, segments.size)
        assertEquals("開頭\n\n", assertIs<NovelReaderSegment.Text>(segments[0]).value)
        val image = assertIs<NovelReaderSegment.Image>(segments[1])
        assertEquals("https://img.wenku8.com/image/1/2/2.jpg", image.url)
        assertEquals("插圖", image.alt)
        assertEquals("\n\n結尾", assertIs<NovelReaderSegment.Text>(segments[2]).value)
    }

    @Test
    fun parsesHtmlImageAndLeavesUnsafeSchemesAsText() {
        val segments = parseNovelReaderSegments(
            "<p>一</p><img data-src=\"https://example.com/a.png?x=1&amp;y=2\" alt=\"a\"><br>![x](javascript:alert(1))",
        )

        val image = segments.filterIsInstance<NovelReaderSegment.Image>().single()
        assertEquals("https://example.com/a.png?x=1&y=2", image.url)
        assertEquals("a", image.alt)
        assertTrue(
            segments.filterIsInstance<NovelReaderSegment.Text>().last().value
                .contains("![x](javascript:alert(1))"),
        )
    }
}
