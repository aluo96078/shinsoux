package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.typedLocalChapterUnitKey

/**
 * The reading state the repository snapshot holds for the units of [publicationKey] stored on this
 * device: which are read, which is in progress (and how far), and which was read last.
 */
internal fun ShinsouRepository.extensionLocalUnitProgress(publicationKey: PublicationKey): ExtensionLocalUnitProgressV2 {
    val snapshot = currentSnapshot
    val chapters = snapshot.chapters.filter { chapter ->
        typedLocalChapterUnitKey(chapter.url)?.publicationKey == publicationKey
    }
    val chapterById = chapters.associateBy { it.id }
    val completed = chapters.asSequence()
        .filter { it.read }
        .mapNotNull { typedLocalChapterUnitKey(it.url) }
        .toSet()
    val histories = snapshot.histories.filter { it.chapterId in chapterById }
    val inProgress = histories.mapNotNull { history ->
        val chapter = chapterById[history.chapterId] ?: return@mapNotNull null
        if (chapter.read) return@mapNotNull null
        val unitKey = typedLocalChapterUnitKey(chapter.url) ?: return@mapNotNull null
        unitKey to extensionUnitPageProgress(chapter.lastPageRead, history.lastPageCount)
    }.toMap()
    val latestHistory = histories
        .asSequence()
        .maxWithOrNull(
            compareBy<History> { it.lastRead }
                .thenBy { it.id },
        )
    val latest = latestHistory
        ?.chapterId
        ?.let(chapterById::get)
        ?.url
        ?.let(::typedLocalChapterUnitKey)
    return ExtensionLocalUnitProgressV2(
        completedUnitKeys = completed,
        lastReadUnitKey = latest,
        inProgressUnits = inProgress,
        lastReadAtMillis = latestHistory?.lastRead?.takeIf { latest != null },
    )
}
