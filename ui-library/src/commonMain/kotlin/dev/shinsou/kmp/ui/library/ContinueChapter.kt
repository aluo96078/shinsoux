package dev.shinsou.kmp.ui.library

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.reader.readerStoryOrderComparator

// 0008 §7-5: from ReaderProgressCoordinators (the shell); the library and the detail page continue from it.
internal fun latestHistoryChapter(
    snapshot: AppSnapshot,
    allowedMangaIds: Set<Long>? = null,
): Chapter? {
    val chapterById = snapshot.chapters.associateBy(Chapter::id)
    return snapshot.histories
        .asSequence()
        .mapNotNull { history -> chapterById[history.chapterId]?.let { chapter -> history to chapter } }
        .filter { (_, chapter) -> allowedMangaIds == null || chapter.mangaId in allowedMangaIds }
        .maxWithOrNull(compareBy<Pair<dev.shinsou.kmp.domain.model.History, Chapter>> { it.first.lastRead }
            .thenBy { it.first.id })
        ?.second
}

public fun continueChapter(snapshot: AppSnapshot, mangaId: Long): Chapter? {
    latestHistoryChapter(snapshot, setOf(mangaId))?.let { return it }
    val chapters = snapshot.chapters.filter { it.mangaId == mangaId }
        .sortedWith(readerStoryOrderComparator)
    return chapters.firstOrNull { !it.read } ?: chapters.lastOrNull()
}
