package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.domain.model.Chapter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal sealed interface ChapterPresentationItem {
    data class ChapterRow(val chapter: Chapter) : ChapterPresentationItem

    data class MissingRange(
        val lowerExistingChapter: Double,
        val upperExistingChapter: Double,
        val position: Int,
    ) : ChapterPresentationItem
}

internal fun chapterPresentationItems(chapters: List<Chapter>): List<ChapterPresentationItem> = buildList {
    chapters.forEachIndexed { index, chapter ->
        add(ChapterPresentationItem.ChapterRow(chapter))
        val next = chapters.getOrNull(index + 1) ?: return@forEachIndexed
        if (
            chapter.chapterNumber >= 0.0 &&
            next.chapterNumber >= 0.0 &&
            abs(chapter.chapterNumber - next.chapterNumber) > 1.0 + 1e-9
        ) {
            add(
                ChapterPresentationItem.MissingRange(
                    lowerExistingChapter = min(chapter.chapterNumber, next.chapterNumber),
                    upperExistingChapter = max(chapter.chapterNumber, next.chapterNumber),
                    position = index,
                ),
            )
        }
    }
}

internal fun duplicateChapterIds(chapters: List<Chapter>): Set<Long> = chapters
    .filter { it.chapterNumber >= 0.0 }
    .groupBy(Chapter::chapterNumber)
    .values
    .filter { it.size > 1 }
    .flatten()
    .mapTo(linkedSetOf(), Chapter::id)

/** Keeps the earliest source entry and returns the redundant copies, matching the original action. */
internal fun redundantDuplicateChapterIds(chapters: List<Chapter>): Set<Long> = chapters
    .filter { it.chapterNumber >= 0.0 }
    .groupBy(Chapter::chapterNumber)
    .values
    .asSequence()
    .filter { it.size > 1 }
    .flatMap { group -> group.sortedWith(compareBy(Chapter::sourceOrder).thenBy(Chapter::id)).drop(1) }
    .mapTo(linkedSetOf(), Chapter::id)
