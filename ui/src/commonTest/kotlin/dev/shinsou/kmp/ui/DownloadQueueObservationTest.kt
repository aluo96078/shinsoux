package dev.shinsou.kmp.ui

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.Manga
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 0008 §7-5: a download's progress reaches the views showing the queue, not the shell. */
class DownloadQueueObservationTest {
    private val downloading = DownloadQueueItem(id = "1_10", mangaId = 1, chapterId = 10, state = DownloadState.DOWNLOADING)
    private val base = AppSnapshot(
        revision = 3,
        mangas = listOf(Manga(id = 1, source = 1, url = "/m", title = "Title")),
        chapters = listOf(Chapter(id = 10, mangaId = 1, url = "/c", name = "One")),
        downloadQueue = listOf(downloading),
    )

    @Test
    fun aQueueOnlyChangeIsNotTheShells() {
        val ticked = base.copy(revision = 4, downloadQueue = listOf(downloading.copy(progress = 0.5, downloadedPages = 3)))
        assertTrue(base.differsOnlyInDownloadQueue(ticked))
        assertTrue(base.differsOnlyInDownloadQueue(base))
    }

    @Test
    fun anyOtherChangeIsTheShells() {
        assertFalse(base.differsOnlyInDownloadQueue(base.copy(revision = 4, chapters = base.chapters.map { it.copy(read = true) })))
        assertFalse(base.differsOnlyInDownloadQueue(base.copy(revision = 4, mangas = emptyList(), downloadQueue = emptyList())))
        assertFalse(base.differsOnlyInDownloadQueue(base.copy(settings = base.settings.copy(library = base.settings.library.copy(downloadOnly = true)))))
    }

    @Test
    fun theShellSeesTheSnapshotsThatChangeMoreThanTheQueue() = runTest(UnconfinedTestDispatcher()) {
        val snapshots = MutableStateFlow(base)
        val seen = mutableListOf<AppSnapshot>()
        val collecting = launch { snapshots.shellSnapshots().toList(seen) }
        snapshots.value = base.copy(revision = 4, downloadQueue = listOf(downloading.copy(progress = 0.5)))
        snapshots.value = base.copy(revision = 5, downloadQueue = listOf(downloading.copy(progress = 0.9)))
        val marked = snapshots.value.copy(revision = 6, chapters = base.chapters.map { it.copy(bookmark = true) })
        snapshots.value = marked
        collecting.cancel()
        assertEquals(2, seen.size)
        assertSame(base, seen[0])
        // The one the shell gets carries the queue as it is then.
        assertSame(marked, seen[1])
        assertEquals(0.9, seen[1].downloadQueue.single().progress)
    }

    @Test
    fun downloadMarksFollowTheRowsDownloadedInFull() {
        val queue = listOf(
            downloading,
            DownloadQueueItem(id = "1_11", mangaId = 1, chapterId = 11, state = DownloadState.DOWNLOADED),
            DownloadQueueItem(id = "1_12", mangaId = 1, chapterId = 12, state = DownloadState.DOWNLOADED, visibleInQueue = false),
        )
        assertEquals(setOf(11L, 12L), downloadedChapterIds(queue))
        assertEquals(setOf("1_11", "1_12"), downloadedItemIds(queue))
        // Progress on an unfinished row changes neither.
        val ticked = queue.map { if (it.id == "1_10") it.copy(progress = 0.7) else it }
        assertEquals(downloadedChapterIds(queue), downloadedChapterIds(ticked))
        assertEquals(downloadedItemIds(queue), downloadedItemIds(ticked))
    }
}
