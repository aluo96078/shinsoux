package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import kotlin.math.round

internal data class UpcomingPrediction(
    val manga: Manga,
    val expectedAt: Long,
    val averageIntervalDays: Double?,
)

/** Mirrors the original app's recent-upload prediction without depending on a platform calendar. */
internal fun predictUpcomingManga(
    mangas: List<Manga>,
    chapters: List<Chapter>,
): List<UpcomingPrediction> {
    val chaptersByManga = chapters.groupBy(Chapter::mangaId)
    return mangas.asSequence()
        .filter(Manga::favorite)
        .mapNotNull { manga ->
            val uploadDates = chaptersByManga[manga.id]
                .orEmpty()
                .asSequence()
                .map(Chapter::dateUpload)
                .filter { it > 0L }
                .sorted()
                .toList()

            if (uploadDates.size < 2) {
                manga.lastUpdate.takeIf { it > 0L }?.let { lastUpdate ->
                    UpcomingPrediction(
                        manga = manga,
                        expectedAt = lastUpdate + FALLBACK_INTERVAL_MILLIS,
                        averageIntervalDays = null,
                    )
                }
            } else {
                val recent = uploadDates.takeLast(MAX_RECENT_UPLOADS)
                val intervals = recent.zipWithNext { previous, next -> next - previous }
                val averageMillis = intervals.average()
                val expectedInterval = averageMillis
                    .coerceIn(UPCOMING_MILLIS_PER_DAY.toDouble(), MAX_INTERVAL_MILLIS.toDouble())
                    .toLong()
                UpcomingPrediction(
                    manga = manga,
                    expectedAt = recent.last() + expectedInterval,
                    averageIntervalDays = round(averageMillis / UPCOMING_MILLIS_PER_DAY * 10.0) / 10.0,
                )
            }
        }
        .sortedWith(compareBy(UpcomingPrediction::expectedAt).thenBy { it.manga.title.lowercase() })
        .toList()
}

internal const val UPCOMING_MILLIS_PER_DAY: Long = 86_400_000L
private const val MAX_RECENT_UPLOADS = 10
private const val FALLBACK_INTERVAL_MILLIS = 14L * UPCOMING_MILLIS_PER_DAY
private const val MAX_INTERVAL_MILLIS = 180L * UPCOMING_MILLIS_PER_DAY
