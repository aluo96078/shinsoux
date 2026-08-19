package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.domain.model.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterPresentationTest {
    @Test
    fun insertsMissingRangeInEitherDisplayDirection() {
        val descending = listOf(chapter(3, 8.0), chapter(2, 7.5), chapter(1, 4.0))
        val rows = chapterPresentationItems(descending)
        val gap = rows.filterIsInstance<ChapterPresentationItem.MissingRange>().single()

        assertEquals(4.0, gap.lowerExistingChapter)
        assertEquals(7.5, gap.upperExistingChapter)
    }

    @Test
    fun identifiesDuplicateGroupsAndOnlyRedundantCopies() {
        val chapters = listOf(
            chapter(1, 5.0, sourceOrder = 3),
            chapter(2, 5.0, sourceOrder = 1),
            chapter(3, 5.0, sourceOrder = 2),
            chapter(4, 6.0, sourceOrder = 0),
        )

        assertEquals(setOf(1L, 2L, 3L), duplicateChapterIds(chapters))
        assertEquals(setOf(3L, 1L), redundantDuplicateChapterIds(chapters))
        assertTrue(4L !in duplicateChapterIds(chapters))
    }

    private fun chapter(id: Long, number: Double, sourceOrder: Int = id.toInt()) = Chapter(
        id = id,
        mangaId = 1,
        name = "Chapter $number",
        chapterNumber = number,
        sourceOrder = sourceOrder,
    )
}
