package dev.shinsou.kmp.reader

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ChapterFilterState
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode

enum class ReaderTapAction {
    PREVIOUS_PAGE,
    TOGGLE_CHROME,
    NEXT_PAGE,
}

/** Maps the reader's left/centre/right thirds to a logical action. */
fun readerTapAction(
    horizontalPosition: Float,
    viewportWidth: Float,
    readingMode: ReadingMode,
): ReaderTapAction {
    if (viewportWidth <= 0f || !horizontalPosition.isFinite()) return ReaderTapAction.TOGGLE_CHROME
    val position = horizontalPosition.coerceIn(0f, viewportWidth)
    val zone = when {
        position < viewportWidth / 3f -> -1
        position > viewportWidth * 2f / 3f -> 1
        else -> 0
    }
    if (zone == 0) return ReaderTapAction.TOGGLE_CHROME

    val rightToLeft = readingMode == ReadingMode.PAGER_RTL
    return when {
        zone < 0 && rightToLeft -> ReaderTapAction.NEXT_PAGE
        zone > 0 && rightToLeft -> ReaderTapAction.PREVIOUS_PAGE
        zone < 0 -> ReaderTapAction.PREVIOUS_PAGE
        else -> ReaderTapAction.NEXT_PAGE
    }
}

data class ReaderChapterNavigation(
    val storyOrder: List<Chapter>,
    val currentIndex: Int,
    val previous: Chapter?,
    val next: Chapter?,
) {
    val current: Chapter? get() = storyOrder.getOrNull(currentIndex)
}

/**
 * Builds deterministic reader navigation in story order (earliest to latest).
 *
 * Source chapter lists are newest-first (`sourceOrder == 0` is newest), therefore source order is
 * descending here. Enabled skip rules are applied to candidates, never to the currently open
 * chapter, so changing settings cannot eject the reader from its current session.
 */
fun buildReaderChapterNavigation(
    chapters: List<Chapter>,
    currentChapterId: Long,
    manga: Manga,
    settings: ReaderSettings,
    downloadedChapterIds: Set<Long> = emptySet(),
): ReaderChapterNavigation {
    val storyOrder = chapters
        .distinctBy(Chapter::id)
        .sortedWith(readerStoryOrderComparator)
    val currentIndex = storyOrder.indexOfFirst { it.id == currentChapterId }
    if (currentIndex < 0) return ReaderChapterNavigation(storyOrder, -1, null, null)

    val duplicateIdsToSkip = if (settings.skipDuplicateChapters) {
        duplicateChapterIdsToSkip(storyOrder)
    } else {
        emptySet()
    }
    fun Chapter.isCandidate(): Boolean {
        if (settings.skipReadChapters && read) return false
        if (settings.skipFilteredChapters && !matchesChapterFilters(manga, downloadedChapterIds)) return false
        if (id in duplicateIdsToSkip) return false
        return true
    }

    val previous = (currentIndex - 1 downTo 0)
        .asSequence()
        .map(storyOrder::get)
        .firstOrNull { it.isCandidate() }
    val next = (currentIndex + 1 until storyOrder.size)
        .asSequence()
        .map(storyOrder::get)
        .firstOrNull { it.isCandidate() }
    return ReaderChapterNavigation(storyOrder, currentIndex, previous, next)
}

/** Logical page indices worth warming in Coil; nearer forward pages are prioritised. */
fun readerPrefetchIndices(
    currentPage: Int,
    pageCount: Int,
    ahead: Int = 3,
    behind: Int = 1,
): List<Int> {
    if (pageCount <= 1 || currentPage !in 0 until pageCount) return emptyList()
    val result = ArrayList<Int>(ahead.coerceAtLeast(0) + behind.coerceAtLeast(0))
    repeat(ahead.coerceAtLeast(0)) { offset ->
        val index = currentPage + offset + 1
        if (index < pageCount) result += index
    }
    repeat(behind.coerceAtLeast(0)) { offset ->
        val index = currentPage - offset - 1
        if (index >= 0) result += index
    }
    return result.distinct()
}

/** Progress value sent to trackers after a chapter is completed. */
fun readerTrackingProgress(chapter: Chapter, storyOrder: List<Chapter>): Double {
    if (chapter.chapterNumber.isFinite() && chapter.chapterNumber >= 0.0) return chapter.chapterNumber
    val storyIndex = storyOrder.indexOfFirst { it.id == chapter.id }
    return if (storyIndex >= 0) (storyIndex + 1).toDouble() else 0.0
}

val readerStoryOrderComparator: Comparator<Chapter> =
    compareByDescending<Chapter> { it.sourceOrder }
        .thenBy { it.chapterNumber }
        .thenBy { it.id }

private fun duplicateChapterIdsToSkip(chapters: List<Chapter>): Set<Long> = chapters
    .filter { it.chapterNumber >= 0.0 && it.chapterNumber.isFinite() }
    .groupBy(Chapter::chapterNumber)
    .values
    .asSequence()
    .filter { it.size > 1 }
    .flatMap { duplicates ->
        val canonical = duplicates.minWithOrNull(compareBy<Chapter> { it.sourceOrder }.thenBy { it.id })
        duplicates.asSequence().filterNot { it.id == canonical?.id }.map(Chapter::id)
    }
    .toSet()

private fun Chapter.matchesChapterFilters(
    manga: Manga,
    downloadedChapterIds: Set<Long>,
): Boolean =
    scanlator?.takeIf(String::isNotBlank) !in manga.excludedScanlators &&
        manga.unreadFilter.matches(read, positiveMatch = false) &&
        manga.bookmarkedFilter.matches(bookmark) &&
        manga.downloadedFilter.matches(id in downloadedChapterIds)

private fun ChapterFilterState.matches(actual: Boolean, positiveMatch: Boolean = true): Boolean = when (this) {
    ChapterFilterState.DISABLED -> true
    ChapterFilterState.INCLUDE -> actual == positiveMatch
    ChapterFilterState.EXCLUDE -> actual != positiveMatch
}
