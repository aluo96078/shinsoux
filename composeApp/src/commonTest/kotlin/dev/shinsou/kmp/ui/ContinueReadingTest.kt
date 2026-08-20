package dev.shinsou.kmp.ui

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import kotlin.test.Test
import kotlin.test.assertEquals

class ContinueReadingTest {
    @Test
    fun continueReadingUsesTheExactLatestHistoryChapter() {
        val snapshot = AppSnapshot(
            mangas = listOf(Manga(id = 1), Manga(id = 2)),
            chapters = listOf(
                Chapter(id = 10, mangaId = 1, sourceOrder = 3, read = true),
                Chapter(id = 11, mangaId = 1, sourceOrder = 2, read = false),
                Chapter(id = 12, mangaId = 1, sourceOrder = 1, read = false),
                Chapter(id = 20, mangaId = 2, sourceOrder = 1, read = false),
            ),
            histories = listOf(
                History(id = 1, chapterId = 11, lastRead = 100, timeRead = 0),
                History(id = 2, chapterId = 10, lastRead = 300, timeRead = 0),
                History(id = 3, chapterId = 20, lastRead = 200, timeRead = 0),
            ),
        )

        assertEquals(10L, continueChapter(snapshot, mangaId = 1)?.id)
        assertEquals(10L, latestHistoryChapter(snapshot, allowedMangaIds = setOf(1))?.id)
        assertEquals(20L, latestHistoryChapter(snapshot, allowedMangaIds = setOf(2))?.id)
        assertEquals(10L, latestHistoryChapter(snapshot)?.id)
    }

    @Test
    fun mangaWithoutHistoryFallsBackToStoryOrder() {
        val snapshot = AppSnapshot(
            mangas = listOf(Manga(id = 1)),
            chapters = listOf(
                Chapter(id = 2, mangaId = 1, sourceOrder = 0, read = false),
                Chapter(id = 1, mangaId = 1, sourceOrder = 1, read = true),
            ),
        )

        assertEquals(2L, continueChapter(snapshot, mangaId = 1)?.id)
    }
}
