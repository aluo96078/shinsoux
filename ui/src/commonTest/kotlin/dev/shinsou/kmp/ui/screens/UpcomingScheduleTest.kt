package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpcomingScheduleTest {
    @Test
    fun predictsFromRecentUploadIntervalsAndIgnoresNonFavorites() {
        val favorite = Manga(id = 1, title = "Weekly", favorite = true)
        val ignored = Manga(id = 2, title = "Ignored", favorite = false)
        val start = 1_700_000_000_000L
        val result = predictUpcomingManga(
            mangas = listOf(favorite, ignored),
            chapters = listOf(
                Chapter(id = 1, mangaId = 1, dateUpload = start),
                Chapter(id = 2, mangaId = 1, dateUpload = start + 7L * UPCOMING_MILLIS_PER_DAY),
                Chapter(id = 3, mangaId = 1, dateUpload = start + 14L * UPCOMING_MILLIS_PER_DAY),
                Chapter(id = 4, mangaId = 2, dateUpload = start),
            ),
        )

        assertEquals(1, result.size)
        assertEquals(start + 21L * UPCOMING_MILLIS_PER_DAY, result.single().expectedAt)
        assertEquals(7.0, result.single().averageIntervalDays)
    }

    @Test
    fun clampsImplausibleIntervalsLikeOriginal() {
        val start = 1_700_000_000_000L
        val manga = Manga(id = 1, title = "Slow", favorite = true)
        val result = predictUpcomingManga(
            listOf(manga),
            listOf(
                Chapter(id = 1, mangaId = 1, dateUpload = start),
                Chapter(id = 2, mangaId = 1, dateUpload = start + 365L * UPCOMING_MILLIS_PER_DAY),
            ),
        ).single()

        assertEquals(start + 545L * UPCOMING_MILLIS_PER_DAY, result.expectedAt)
        assertEquals(365.0, result.averageIntervalDays)
    }

    @Test
    fun fallsBackToTwoWeeksAfterLastUpdateWhenHistoryIsInsufficient() {
        val lastUpdate = 1_700_000_000_000L
        val result = predictUpcomingManga(
            listOf(Manga(id = 1, title = "New", favorite = true, lastUpdate = lastUpdate)),
            emptyList(),
        ).single()

        assertEquals(lastUpdate + 14L * UPCOMING_MILLIS_PER_DAY, result.expectedAt)
        assertNull(result.averageIntervalDays)
    }
}
