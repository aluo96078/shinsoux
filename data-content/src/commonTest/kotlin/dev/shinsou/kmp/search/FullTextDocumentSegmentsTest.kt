package dev.shinsou.kmp.search

import dev.shinsou.kmp.domain.content.TextBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An oversized semantic block splits into bounded, surrogate-safe search documents (from SqlDriverLocalFullTextIndexRestartTest). */
class FullTextDocumentSegmentsTest {
    @Test
    fun oversizedSemanticBlockUsesSurrogateSafeBoundedSearchTransactions() {
        val text = "a".repeat(MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH - 1) +
            "\uD83D\uDE00" +
            "b".repeat(MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH)
        val block = TextBlock("semantic-block", 0, text.length)

        val segments = fullTextDocumentSegments(FIRST_REPRESENTATION_ID, block, text)

        assertTrue(segments.size >= 2)
        assertEquals(MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH - 1, segments.first().endUtf16)
        assertTrue(segments.all { it.blockId == block.blockId })
        assertTrue(segments.all {
            it.endUtf16 - it.startUtf16 <= MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH
        })
        assertEquals(segments.size, segments.map { it.documentId }.distinct().size)
        assertTrue(segments.zipWithNext().all { (left, right) -> left.endUtf16 == right.startUtf16 })
        assertTrue(segments.all { segment ->
            val boundary = segment.endUtf16
            boundary == text.length ||
                !(text[boundary - 1].isHighSurrogate() && text[boundary].isLowSurrogate())
        })
        assertEquals(
            text,
            segments.joinToString("") { segment ->
                text.substring(segment.startUtf16, segment.endUtf16)
            },
        )
    }

    private companion object {
        const val FIRST_REPRESENTATION_ID = "77777777-7777-4777-8777-777777777777"
    }
}
