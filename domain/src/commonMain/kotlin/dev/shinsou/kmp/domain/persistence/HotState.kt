package dev.shinsou.kmp.domain.persistence

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.History

/**
 * C3 (docs/architecture/0007 §6.3): the reading progress of one chapter, the three fields of
 * [Chapter] that change on every page turn. Only chapters whose progress differs from [DEFAULT]
 * are kept in a [HotState].
 */
public data class ChapterProgress(
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val lastPageRead: Int = 0,
) {
    public val isDefault: Boolean get() = this == DEFAULT

    public companion object {
        public val DEFAULT: ChapterProgress = ChapterProgress()
    }
}

public val Chapter.progress: ChapterProgress
    get() = if (!read && !bookmark && lastPageRead == 0) ChapterProgress.DEFAULT else ChapterProgress(read, bookmark, lastPageRead)

/** [this] chapter with [progress] in place of its own. */
public fun Chapter.withProgress(progress: ChapterProgress): Chapter =
    if (read == progress.read && bookmark == progress.bookmark && lastPageRead == progress.lastPageRead) {
        this
    } else {
        copy(read = progress.read, bookmark = progress.bookmark, lastPageRead = progress.lastPageRead)
    }

/**
 * The hot part of the app snapshot (§6.3.1): the progress of every chapter that has any, the
 * histories and the download queue, the last two in their list order. Everything else is cold.
 */
public data class HotState(
    /** Chapter id to its progress; no entry for a chapter with [ChapterProgress.DEFAULT]. */
    val chapterProgress: Map<Long, ChapterProgress> = emptyMap(),
    val histories: List<History> = emptyList(),
    val downloads: List<DownloadQueueItem> = emptyList(),
) {
    /** The state after [delta] (computed against this state) was written. */
    public fun applying(delta: HotDelta): HotState {
        if (delta.isEmpty) return this
        val progress = chapterProgress.toMutableMap()
        delta.removedProgress.forEach(progress::remove)
        delta.progress.forEach { (chapterId, value) ->
            if (value.isDefault) progress.remove(chapterId) else progress[chapterId] = value
        }
        return HotState(
            chapterProgress = progress,
            histories = histories.applying(delta.histories, delta.removedHistories) { it.id },
            downloads = downloads.applying(delta.downloads, delta.removedDownloads) { it.id },
        )
    }

    public companion object {
        public val EMPTY: HotState = HotState()
    }
}

/** A row of an ordered hot list: its value and its index in the list ([ordinal]). */
public data class Ordered<out T>(val ordinal: Int, val value: T)

/**
 * What changed in the hot state between two snapshots, row by row: the rows to write (put in place
 * of any row with the same key) and the keys of the rows to delete. A list row is written with its
 * index; writing a delta computed from `previous` to `next` onto `previous`'s rows leaves exactly
 * `next`'s rows, with the indices `0 until next.size`.
 */
public data class HotDelta(
    val progress: Map<Long, ChapterProgress> = emptyMap(),
    val removedProgress: Set<Long> = emptySet(),
    val histories: List<Ordered<History>> = emptyList(),
    val removedHistories: Set<Long> = emptySet(),
    val downloads: List<Ordered<DownloadQueueItem>> = emptyList(),
    val removedDownloads: Set<String> = emptySet(),
) {
    public val isEmpty: Boolean
        get() = progress.isEmpty() && removedProgress.isEmpty() && histories.isEmpty() &&
            removedHistories.isEmpty() && downloads.isEmpty() && removedDownloads.isEmpty()

    public companion object {
        public val EMPTY: HotDelta = HotDelta()
    }
}

/**
 * The rows the app snapshot keeps hot, outside its JSON. The SQL implementation is in
 * `:data-content`; the repository only sees this port.
 */
public interface HotStateStore {
    /** Every row, the lists in their order. */
    public fun load(): HotState

    /** Writes [delta] in one transaction (joining the caller's, when there is one). Idempotent. */
    public fun apply(delta: HotDelta)

    /** Deletes every row and the [pairing]. */
    public fun clear()

    /** Which JSON the rows belong to (§6.3.5–6.3.6); null before the first one was paired. */
    public fun pairing(): HotStatePairing?

    public fun setPairing(pairing: HotStatePairing?)
}

/** The snapshot JSON the hot rows go with: the last revision written as JSON, and a random token. */
public data class HotStatePairing(val jsonRevision: Long, val token: String)

private fun <T, K> List<T>.applying(written: List<Ordered<T>>, removed: Set<K>, key: (T) -> K): List<T> {
    if (written.isEmpty() && removed.isEmpty()) return this
    // As the table: one row per key, read back in ordinal order (a delta leaves no two rows on one).
    val rows = LinkedHashMap<K, Ordered<T>>()
    forEachIndexed { index, value -> rows[key(value)] = Ordered(index, value) }
    removed.forEach(rows::remove)
    written.forEach { rows[key(it.value)] = it }
    return rows.values.sortedBy { it.ordinal }.map { it.value }
}
