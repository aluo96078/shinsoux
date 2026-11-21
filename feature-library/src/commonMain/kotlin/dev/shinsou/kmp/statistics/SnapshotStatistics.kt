package dev.shinsou.kmp.statistics

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.DownloadState
import kotlinx.serialization.Serializable

@Serializable
data class SnapshotStatistics(
    val totalMangaCount: Int,
    val libraryMangaCount: Int,
    val completedMangaCount: Int,
    val categoryCount: Int,
    val sourceCount: Int,
    val totalChapterCount: Int,
    val readChapterCount: Int,
    val unreadChapterCount: Int,
    val bookmarkedChapterCount: Int,
    val downloadedChapterCount: Int,
    val queuedDownloadCount: Int,
    val trackedMangaCount: Int,
    val totalReadingTimeMillis: Long,
    val readingDayCount: Int,
    val averageTrackerScore: Double,
    val genres: List<GenreStatistic>,
    val sources: List<SourceStatistic>,
    val mangaActivity: List<MangaActivityStatistic>,
)

@Serializable
data class GenreStatistic(
    val genre: String,
    val mangaCount: Int,
)

@Serializable
data class SourceStatistic(
    val sourceId: Long,
    val mangaCount: Int,
    val libraryMangaCount: Int,
    val chapterCount: Int,
)

@Serializable
data class MangaActivityStatistic(
    val mangaId: Long,
    val title: String,
    val readingTimeMillis: Long,
    val lastReadAt: Long,
    val chaptersRead: Int,
)

object SnapshotStatisticsAggregator {
    private const val MILLIS_PER_DAY = 86_400_000L

    fun aggregate(snapshot: AppSnapshot, completedStatus: Long = 2): SnapshotStatistics {
        snapshot.validate()
        val chapterById = snapshot.chapters.associateBy { it.id }
        val mangaById = snapshot.mangas.associateBy { it.id }

        val historyByManga = snapshot.histories.groupBy { history ->
            chapterById[history.chapterId]?.mangaId
        }.filterKeys { it != null }

        val activity = historyByManga.mapNotNull { (nullableMangaId, histories) ->
            val mangaId = nullableMangaId ?: return@mapNotNull null
            val manga = mangaById[mangaId] ?: return@mapNotNull null
            MangaActivityStatistic(
                mangaId = mangaId,
                title = manga.title,
                readingTimeMillis = histories.sumOf { it.timeRead },
                lastReadAt = histories.maxOfOrNull { it.lastRead } ?: 0,
                chaptersRead = histories.map { it.chapterId }.distinct().size,
            )
        }.sortedWith(compareByDescending<MangaActivityStatistic> { it.readingTimeMillis }.thenByDescending { it.lastReadAt })

        val sourceStats = snapshot.mangas.groupBy { it.source }.map { (sourceId, mangas) ->
            val mangaIds = mangas.mapTo(mutableSetOf()) { it.id }
            SourceStatistic(
                sourceId = sourceId,
                mangaCount = mangas.size,
                libraryMangaCount = mangas.count { it.favorite },
                chapterCount = snapshot.chapters.count { it.mangaId in mangaIds },
            )
        }.sortedWith(compareByDescending<SourceStatistic> { it.libraryMangaCount }.thenBy { it.sourceId })

        val genres = snapshot.mangas.asSequence()
            .filter { it.favorite }
            .flatMap { it.genre.orEmpty().asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .map { GenreStatistic(it.key, it.value) }
            .sortedWith(compareByDescending<GenreStatistic> { it.mangaCount }.thenBy { it.genre.lowercase() })

        val scoredTracks = snapshot.tracks.map { it.score }.filter { it > 0.0 }
        val readCount = snapshot.chapters.count { it.read }
        return SnapshotStatistics(
            totalMangaCount = snapshot.mangas.size,
            libraryMangaCount = snapshot.mangas.count { it.favorite },
            completedMangaCount = snapshot.mangas.count { it.favorite && it.status == completedStatus },
            categoryCount = snapshot.categories.count { !it.isSystemCategory },
            sourceCount = snapshot.mangas.map { it.source }.distinct().size,
            totalChapterCount = snapshot.chapters.size,
            readChapterCount = readCount,
            unreadChapterCount = snapshot.chapters.size - readCount,
            bookmarkedChapterCount = snapshot.chapters.count { it.bookmark },
            downloadedChapterCount = snapshot.downloadQueue.count { it.state == DownloadState.DOWNLOADED },
            queuedDownloadCount = snapshot.downloadQueue.count { it.state != DownloadState.DOWNLOADED },
            trackedMangaCount = snapshot.tracks.map { it.mangaId }.distinct().size,
            totalReadingTimeMillis = snapshot.histories.sumOf { it.timeRead },
            readingDayCount = snapshot.histories.asSequence().map { it.lastRead / MILLIS_PER_DAY }.distinct().count(),
            averageTrackerScore = if (scoredTracks.isEmpty()) 0.0 else scoredTracks.average(),
            genres = genres,
            sources = sourceStats,
            mangaActivity = activity,
        )
    }
}
