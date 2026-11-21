package dev.shinsou.kmp.statistics

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotStatisticsTest {
    @Test
    fun aggregatesLibraryReadingDownloadTrackingGenreAndSourceMetrics() {
        val mangas = listOf(
            Manga(id = 1, source = 10, favorite = true, url = "/one", title = "One", genre = listOf("Action", "Drama"), status = 2),
            Manga(id = 2, source = 10, favorite = true, url = "/two", title = "Two", genre = listOf("Action")),
            Manga(id = 3, source = 20, favorite = false, url = "/three", title = "Three"),
        )
        val chapters = listOf(
            Chapter(id = 11, mangaId = 1, url = "/1", name = "1", read = true, bookmark = true),
            Chapter(id = 12, mangaId = 1, url = "/2", name = "2", read = true),
            Chapter(id = 21, mangaId = 2, url = "/3", name = "3", read = false),
        )
        val snapshot = AppSnapshot(
            mangas = mangas,
            chapters = chapters,
            categories = listOf(Category.Default, Category(id = 1, name = "Reading")),
            mangaCategories = listOf(MangaCategory(1, 1), MangaCategory(2, Category.Default.id)),
            histories = listOf(
                History(id = 1, chapterId = 11, lastRead = 86_400_001, timeRead = 1_000),
                History(id = 2, chapterId = 12, lastRead = 172_800_001, timeRead = 2_000),
            ),
            downloadQueue = listOf(
                DownloadQueueItem("1_11", 1, 11, DownloadState.DOWNLOADED, 1.0, 10, 10),
                DownloadQueueItem("2_21", 2, 21, DownloadState.QUEUED),
            ),
            tracks = listOf(
                Track(id = 1, mangaId = 1, trackerId = 1, score = 8.0),
                Track(id = 2, mangaId = 2, trackerId = 2, score = 6.0),
            ),
        )

        val stats = SnapshotStatisticsAggregator.aggregate(snapshot)

        assertEquals(3, stats.totalMangaCount)
        assertEquals(2, stats.libraryMangaCount)
        assertEquals(1, stats.completedMangaCount)
        assertEquals(1, stats.categoryCount)
        assertEquals(2, stats.sourceCount)
        assertEquals(3, stats.totalChapterCount)
        assertEquals(2, stats.readChapterCount)
        assertEquals(1, stats.unreadChapterCount)
        assertEquals(1, stats.bookmarkedChapterCount)
        assertEquals(1, stats.downloadedChapterCount)
        assertEquals(1, stats.queuedDownloadCount)
        assertEquals(2, stats.trackedMangaCount)
        assertEquals(3_000, stats.totalReadingTimeMillis)
        assertEquals(2, stats.readingDayCount)
        assertEquals(7.0, stats.averageTrackerScore)
        assertEquals(listOf("Action" to 2, "Drama" to 1), stats.genres.map { it.genre to it.mangaCount })
        assertEquals(3_000, stats.mangaActivity.first().readingTimeMillis)
        assertEquals(10, stats.sources.first().sourceId)
        assertEquals(2, stats.sources.first().libraryMangaCount)
    }
}
