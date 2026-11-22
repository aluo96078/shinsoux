package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.persistence.ChapterProgress
import dev.shinsou.kmp.domain.persistence.HotDelta
import dev.shinsou.kmp.domain.persistence.HotState
import dev.shinsou.kmp.domain.persistence.Ordered
import dev.shinsou.kmp.domain.persistence.progress

/*
 * C3 (docs/architecture/0007 §6.3): the snapshot split into its hot part (chapter progress,
 * histories, download queue) and the cold rest. Both comparisons below start from reference
 * equality, as SyncDiff does: a mutation copies only the lists it changes, so an unchanged list
 * costs nothing and a changed one is scanned once, aligned by index while the ids line up.
 */

/** The hot part of this snapshot. */
fun AppSnapshot.hotState(): HotState = HotState(
    chapterProgress = buildMap {
        chapters.forEach { chapter -> chapter.progress.takeUnless { it.isDefault }?.let { put(chapter.id, it) } }
    },
    histories = histories,
    downloads = downloadQueue,
)

/** The hot rows to write so the rows of [previous] become those of [next]. */
fun hotDelta(previous: AppSnapshot, next: AppSnapshot): HotDelta {
    if (previous === next) return HotDelta.EMPTY
    val (progress, removedProgress) = progressDelta(previous.chapters, next.chapters)
    val (histories, removedHistories) = orderedDelta(previous.histories, next.histories) { it.id }
    val (downloads, removedDownloads) = orderedDelta(previous.downloadQueue, next.downloadQueue) { it.id }
    return HotDelta(progress, removedProgress, histories, removedHistories, downloads, removedDownloads)
}

/**
 * Whether anything but the hot part (and the revision, which every mutation moves) differs:
 * what decides whether the snapshot JSON has to be written again.
 */
fun coldChanged(previous: AppSnapshot, next: AppSnapshot): Boolean =
    // Data class equality: each unchanged list is the same reference and compares at once.
    previous !== next &&
        (previous.withoutHotLists() != next.withoutHotLists() || chaptersColdChanged(previous.chapters, next.chapters))

private fun AppSnapshot.withoutHotLists(): AppSnapshot =
    copy(revision = 0, chapters = emptyList(), histories = emptyList(), downloadQueue = emptyList())

/** The chapters' order and every field but the progress are cold. */
private fun chaptersColdChanged(previous: List<Chapter>, next: List<Chapter>): Boolean =
    previous !== next && (previous.size != next.size || previous.indices.any { chapterColdChanged(previous[it], next[it]) })

private fun chapterColdChanged(before: Chapter, after: Chapter): Boolean =
    before !== after && (before.id != after.id || before.withoutProgress() != after.withoutProgress())

private fun Chapter.withoutProgress(): Chapter =
    if (read || bookmark || lastPageRead != 0) copy(read = false, bookmark = false, lastPageRead = 0) else this

private typealias ProgressDelta = Pair<Map<Long, ChapterProgress>, Set<Long>>

private fun progressDelta(previous: List<Chapter>, next: List<Chapter>): ProgressDelta = when {
    previous === next -> NO_PROGRESS_CHANGE
    aligned(previous, next) -> alignedProgressDelta(previous, next)
    else -> keyedProgressDelta(previous, next)
}

/** Whether [next] has [previous]'s chapters in the same order (a page turn or an edit). */
private fun aligned(previous: List<Chapter>, next: List<Chapter>): Boolean =
    previous.size == next.size && previous.indices.all { previous[it] === next[it] || previous[it].id == next[it].id }

private fun alignedProgressDelta(previous: List<Chapter>, next: List<Chapter>): ProgressDelta {
    val written = HashMap<Long, ChapterProgress>()
    val removed = HashSet<Long>()
    for (index in previous.indices) {
        val before = previous[index]
        val after = next[index]
        val progress = after.progress
        when {
            before === after || progress == before.progress -> Unit
            progress.isDefault -> removed += after.id
            else -> written[after.id] = progress
        }
    }
    return written to removed
}

private fun keyedProgressDelta(previous: List<Chapter>, next: List<Chapter>): ProgressDelta {
    val before = previous.progressById()
    val after = next.progressById()
    val written = after.filter { (id, progress) -> before[id] != progress }
    val removed = before.keys.filterTo(HashSet()) { it !in after }
    return written to removed
}

private fun List<Chapter>.progressById(): Map<Long, ChapterProgress> {
    val byId = HashMap<Long, ChapterProgress>()
    forEach { chapter -> chapter.progress.takeUnless { it.isDefault }?.let { byId[chapter.id] = it } }
    return byId
}

/**
 * Rows of [next] whose index or value differs from [previous]'s, with their index, and the keys of
 * [previous] that [next] no longer has. A row that moved is written again at its new index.
 */
private fun <T, K> orderedDelta(previous: List<T>, next: List<T>, key: (T) -> K): Pair<List<Ordered<T>>, Set<K>> {
    if (previous === next) return Pair(emptyList(), emptySet())
    val written = next.indices
        .filter { index -> index >= previous.size || !(previous[index] === next[index] || previous[index] == next[index]) }
        .map { index -> Ordered(index, next[index]) }
    // When every key of previous is still at its index, none can have been removed.
    val keysInPlace = next.size >= previous.size &&
        written.all { it.ordinal >= previous.size || key(previous[it.ordinal]) == key(it.value) }
    val removed = if (keysInPlace) {
        emptySet()
    } else {
        val kept = next.mapTo(HashSet(), key)
        previous.asSequence().map(key).filterTo(HashSet()) { it !in kept }
    }
    return written to removed
}

private val NO_PROGRESS_CHANGE: ProgressDelta = Pair(emptyMap(), emptySet())

/**
 * This history without its reader page count when that no longer holds a chapter at
 * [lastPageRead] (validate() requires it to): a rendition hint the reader computes again.
 */
internal fun History.fittingPage(lastPageRead: Int): History {
    val count = lastPageCount ?: return this
    return if (count > 0 && lastPageRead < count) this else copy(lastPageCount = null)
}

/**
 * [this] histories with every history of a chapter in [lastPageReadByChapter] fitted to its new
 * page ([fittingPage]); the same list when none had to change. A chapter may have several
 * histories, and a hot write is not validated whole: each of them has to stay valid.
 */
internal fun List<History>.fittingPages(lastPageReadByChapter: Map<Long, Int>): List<History> {
    if (lastPageReadByChapter.isEmpty() || none { it.lastPageCount != null && it.chapterId in lastPageReadByChapter }) return this
    var changed = false
    val fitted = map { history ->
        val lastPageRead = lastPageReadByChapter[history.chapterId] ?: return@map history
        history.fittingPage(lastPageRead).also { if (it !== history) changed = true }
    }
    return if (changed) fitted else this
}
