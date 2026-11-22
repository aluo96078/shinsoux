package dev.shinsou.kmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * 0008 §7-5 (docs/architecture/0007 §6.3.8): the download queue has its own observable path. A
 * download's progress changes only [AppSnapshot.downloadQueue] (and the revision), several times a
 * second while chapters download. The shell composes [shellSnapshots], which leaves those changes
 * out, and the views that show the queue read it themselves ([rememberDownloadQueue], or a value of
 * it through [rememberDownloadQueueValue]), so a progress tick recomposes only them. The snapshot
 * the shell holds therefore has a stale queue: code outside those views reads
 * `repository.currentSnapshot.downloadQueue` when it needs the queue. Persistence is not involved:
 * the repository's snapshot, its hot-state path (C3) and the stored formats are unchanged.
 */

/** The repository's snapshots without those that differ from the previous one only in the download queue. */
internal fun StateFlow<AppSnapshot>.shellSnapshots(): Flow<AppSnapshot> =
    distinctUntilChanged { old, new -> old.differsOnlyInDownloadQueue(new) }

/**
 * Whether [next] is this snapshot with another download queue (and revision). Cheap when it is:
 * every other field is the same reference, and equality checks references first.
 */
internal fun AppSnapshot.differsOnlyInDownloadQueue(next: AppSnapshot): Boolean =
    this === next || copy(revision = next.revision, downloadQueue = next.downloadQueue) == next

/** The shell's snapshot: [shellSnapshots] of the repository, as state. */
@Composable
internal fun rememberShellSnapshot(repository: ShinsouRepository): State<AppSnapshot> {
    val snapshots = remember(repository) { repository.snapshot.shellSnapshots() }
    return snapshots.collectAsState(repository.currentSnapshot)
}

/** The download queue; reading it recomposes the reader on every change of the queue. */
@Composable
internal fun rememberDownloadQueue(repository: ShinsouRepository): State<List<DownloadQueueItem>> {
    val queue = remember(repository) { repository.snapshot.map { it.downloadQueue }.distinctUntilChanged { old, new -> old === new } }
    return queue.collectAsState(repository.currentSnapshot.downloadQueue)
}

/** [of] the download queue; its reader recomposes only when that value changes, not on every tick. */
@Composable
internal fun <T> rememberDownloadQueueValue(repository: ShinsouRepository, of: (List<DownloadQueueItem>) -> T): T {
    val queue = rememberDownloadQueue(repository)
    val current = rememberUpdatedState(of)
    return remember(queue) { derivedStateOf { current.value(queue.value) } }.value
}

/** The chapters downloaded in full: what the library's and the reader's download marks follow. */
internal fun downloadedChapterIds(queue: List<DownloadQueueItem>): Set<Long> =
    queue.asSequence().filter { it.state == DownloadState.DOWNLOADED }.mapTo(mutableSetOf()) { it.chapterId }

/** The queue rows downloaded in full: what the library's download counts follow. */
internal fun downloadedItemIds(queue: List<DownloadQueueItem>): Set<String> =
    queue.asSequence().filter { it.state == DownloadState.DOWNLOADED }.mapTo(mutableSetOf()) { it.id }
