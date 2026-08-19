package dev.shinsou.kmp.download

import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadSettings
import dev.shinsou.kmp.domain.model.DownloadState

/** Download ids removed when chapters are marked read outside the reader. */
internal fun downloadIdsToRemoveAfterMarkedRead(
    settings: DownloadSettings,
    queue: List<DownloadQueueItem>,
    chapterIds: Set<Long>,
    markedRead: Boolean,
): List<String> {
    if (!markedRead || !settings.deleteAfterReading || !settings.removeAfterMarkedRead) return emptyList()
    return queue.asSequence()
        .filter { it.chapterId in chapterIds && it.state == DownloadState.DOWNLOADED }
        .map(DownloadQueueItem::id)
        .distinct()
        .toList()
}
