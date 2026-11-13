package dev.shinsou.kmp.download

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadSettings
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.files.AppFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadManagerTest {
    @Test
    fun quotaFailurePersistsClassificationWithoutFetchingPagesOrRetrying() = runTest {
        val repository = ShinsouRepository(
            AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 2, title = "A")),
                chapters = listOf(Chapter(id = 3, mangaId = 1, name = "One")),
            ),
        )
        var requests = 0
        val manager = DownloadManager(
            repository,
            MemoryFileSystem(),
            ChapterPageProvider { _, _ ->
                requests++
                throw IllegalStateException("wrapper", IllegalArgumentException("Error: SHINSOU_SOURCE_QUOTA_EXCEEDED (plugin.js#280)"))
            },
            DownloadPageFetcher { error("No pages should be fetched after a quota refusal") },
        )
        try {
            manager.enqueue(1, 3)
            manager.awaitIdle()
            val entry = repository.currentSnapshot.downloadQueue.single()
            assertEquals(DownloadState.ERROR, entry.state)
            assertEquals("SHINSOU_SOURCE_QUOTA_EXCEEDED", entry.errorMessage)
            assertEquals(1, requests)
        } finally {
            manager.close()
        }
    }

    @Test
    fun closeWaitsForCancelledDownloadCleanup() = runTest {
        val repository = ShinsouRepository(
            AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 2, title = "A")),
                chapters = listOf(Chapter(id = 3, mangaId = 1, name = "One")),
            ),
        )
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchFinished = CompletableDeferred<Unit>()
        val manager = DownloadManager(
            repository,
            MemoryFileSystem(),
            ChapterPageProvider { _, _ -> listOf(DownloadPage(0, "pending")) },
            DownloadPageFetcher {
                fetchStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    fetchFinished.complete(Unit)
                }
            },
        )
        manager.enqueue(1, 3)
        fetchStarted.await()

        manager.close()

        assertTrue(fetchFinished.isCompleted)
    }

    @Test
    fun failedPageLeavesPersistentErrorInsteadOfFalseSuccess() = runTest {
        val repository = ShinsouRepository(
            AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 2, title = "A")),
                chapters = listOf(Chapter(id = 3, mangaId = 1, name = "One")),
            ),
        )
        val manager = DownloadManager(
            repository,
            MemoryFileSystem(),
            ChapterPageProvider { _, _ -> listOf(DownloadPage(0, "ok"), DownloadPage(1, "bad")) },
            DownloadPageFetcher { page -> if (page.url == "bad") error("boom") else DownloadedPage(byteArrayOf(1)) },
            now = { 10 },
        )
        manager.enqueue(1, 3)
        manager.awaitIdle()
        assertEquals(DownloadState.ERROR, repository.currentSnapshot.downloadQueue.single().state)
        assertTrue(repository.currentSnapshot.downloadQueue.single().errorMessage?.contains("boom") == true)
        manager.close()
    }

    @Test
    fun bulkRemovalKeepsAChapterThatFinishedFirst() = runTest {
        val repository = ShinsouRepository(
            AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 2, title = "A")),
                chapters = listOf(Chapter(id = 3, mangaId = 1, name = "One"), Chapter(id = 4, mangaId = 1, name = "Two")),
            ),
        )
        val files = MemoryFileSystem()
        val manager = DownloadManager(
            repository,
            files,
            ChapterPageProvider { _, _ -> listOf(DownloadPage(0, "page")) },
            DownloadPageFetcher { DownloadedPage(byteArrayOf(1, 2, 3), "image/jpeg") },
            now = { 20 },
        )
        val finished = manager.enqueue(1, 3)
        manager.awaitIdle()
        // The bulk "remove waiting" was confirmed while this chapter was still downloading.
        manager.removeUnfinished(finished.id)
        assertEquals(DownloadState.DOWNLOADED, repository.currentSnapshot.downloadQueue.single().state)
        assertEquals(1, manager.downloadedPages(1, 3).size)

        manager.pauseAll(true)
        val waiting = manager.enqueue(1, 4)
        manager.removeUnfinished(waiting.id)
        assertEquals(listOf(finished.id), repository.currentSnapshot.downloadQueue.map { it.id })
        manager.close()
    }

    @Test
    fun aChapterBeingRemovedIsNotStartedByAnotherCompletion() = runTest {
        val repository = ShinsouRepository(
            AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 2, title = "A")),
                chapters = listOf(Chapter(id = 3, mangaId = 1, name = "One"), Chapter(id = 4, mangaId = 1, name = "Two")),
            ),
        )
        val deleting = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val started = mutableListOf<Long>()
        val files = object : AppFileSystem by MemoryFileSystem() {
            override suspend fun deleteTree(relativeDirectory: String): Boolean {
                if (relativeDirectory == DownloadCompletionManifests.directory(1, 4)) {
                    deleting.complete(Unit)
                    releaseDelete.await()
                }
                return true
            }
        }
        val manager = DownloadManager(
            repository,
            files,
            ChapterPageProvider { _, chapterId -> started += chapterId; listOf(DownloadPage(0, "page-$chapterId")) },
            DownloadPageFetcher { page ->
                if (page.url == "page-3") releaseFirst.await()
                DownloadedPage(byteArrayOf(1), "image/jpeg")
            },
            now = { 20 },
        )
        repository.updateSettings { it.copy(downloads = it.downloads.copy(parallelDownloads = 1)) }
        manager.enqueue(1, 3) // occupies the only slot
        val second = manager.enqueue(1, 4)
        val removal = backgroundScope.launch { manager.removeUnfinished(second.id) }
        deleting.await()
        releaseFirst.complete(Unit) // the first finishes and asks the scheduler for more work
        manager.awaitIdle()
        releaseDelete.complete(Unit)
        removal.join()

        assertEquals(listOf(3L), started)
        assertEquals(1, repository.currentSnapshot.downloadQueue.size)
        manager.close()
    }

    @Test
    fun aSecondRemovalOfTheSameEntryWaitsForTheFirst() = runTest {
        val repository = ShinsouRepository(
            AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 2, title = "A")),
                chapters = listOf(Chapter(id = 3, mangaId = 1, name = "One")),
            ),
        )
        val deleting = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        var deletes = 0
        val files = object : AppFileSystem by MemoryFileSystem() {
            override suspend fun deleteTree(relativeDirectory: String): Boolean {
                deletes++
                deleting.complete(Unit)
                releaseDelete.await()
                return true
            }
        }
        val manager = DownloadManager(
            repository,
            files,
            ChapterPageProvider { _, _ -> listOf(DownloadPage(0, "page")) },
            DownloadPageFetcher { DownloadedPage(byteArrayOf(1), "image/jpeg") },
            now = { 20 },
        )
        manager.pauseAll(true)
        val item = manager.enqueue(1, 3)
        val first = backgroundScope.launch { manager.remove(item.id) }
        deleting.await()
        val second = backgroundScope.launch { manager.remove(item.id) }
        testScheduler.advanceUntilIdle()
        // The second call neither deletes on its own nor finishes before the first.
        assertTrue(second.isActive)
        releaseDelete.complete(Unit)
        first.join()
        second.join()

        assertEquals(1, deletes)
        assertTrue(repository.currentSnapshot.downloadQueue.isEmpty())
        manager.close()
    }

    @Test
    fun aCancelledOrFailedRemovalReleasesTheEntryAndReportsTheFailure() = runTest {
        val repository = ShinsouRepository(
            AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 2, title = "A")),
                chapters = listOf(Chapter(id = 3, mangaId = 1, name = "One")),
            ),
        )
        val deleting = CompletableDeferred<Unit>()
        var mode = "block"
        val files = object : AppFileSystem by MemoryFileSystem() {
            override suspend fun deleteTree(relativeDirectory: String): Boolean = when (mode) {
                "block" -> { deleting.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                "fail" -> error("disk unavailable")
                else -> true
            }
        }
        val manager = DownloadManager(
            repository,
            files,
            ChapterPageProvider { _, _ -> listOf(DownloadPage(0, "page")) },
            DownloadPageFetcher { DownloadedPage(byteArrayOf(1), "image/jpeg") },
            now = { 20 },
        )
        manager.pauseAll(true)
        val item = manager.enqueue(1, 3)

        val cancelled = backgroundScope.launch { manager.remove(item.id) }
        deleting.await()
        cancelled.cancel()
        cancelled.join()

        // Not left reserved: a failing removal runs for real and its failure reaches the caller.
        mode = "fail"
        assertTrue(runCatching { manager.remove(item.id) }.isFailure)
        mode = "ok"
        manager.remove(item.id)
        assertTrue(repository.currentSnapshot.downloadQueue.isEmpty())
        manager.close()
    }

    @Test
    fun aRemovalDuringPauseWaitsForTheWorkerItRemoves() = runTest {
        val repository = ShinsouRepository(
            AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 2, title = "A")),
                chapters = listOf(Chapter(id = 3, mangaId = 1, name = "One"), Chapter(id = 4, mangaId = 1, name = "Two")),
            ),
        )
        val fetching = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val manager = DownloadManager(
            repository,
            MemoryFileSystem(),
            ChapterPageProvider { _, chapterId -> listOf(DownloadPage(0, "page-$chapterId")) },
            DownloadPageFetcher { page ->
                if (page.url == "page-4") {
                    fetching.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
                DownloadedPage(byteArrayOf(1), "image/jpeg")
            },
            now = { 20 },
        )
        repository.updateSettings { it.copy(downloads = it.downloads.copy(parallelDownloads = 2)) }
        manager.pauseAll(true)
        val second = manager.enqueue(1, 4)
        manager.pauseAll(false)
        fetching.await()
        // Pause and removal overlap; the removal must still find the worker and wait for it.
        val pausing = backgroundScope.launch { manager.pauseAll(true) }
        manager.removeUnfinished(second.id)
        pausing.join()

        assertTrue(stopped.isCompleted)
        assertTrue(repository.currentSnapshot.downloadQueue.none { it.id == second.id })
        manager.close()
    }

    @Test
    fun pausingRightAfterEnqueueNeverStallsTheQueue() = runTest {
        val repository = ShinsouRepository(
            AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 2, title = "A")),
                chapters = (1L..20L).map { Chapter(id = it, mangaId = 1, name = "C$it") },
            ),
        )
        val manager = DownloadManager(
            repository,
            MemoryFileSystem(),
            ChapterPageProvider { _, chapterId -> listOf(DownloadPage(0, "page-$chapterId")) },
            DownloadPageFetcher { DownloadedPage(byteArrayOf(1), "image/jpeg") },
            now = { 20 },
        )
        repository.updateSettings { it.copy(downloads = it.downloads.copy(parallelDownloads = 1)) }
        // Cancel workers as close to their registration as possible, before most are dispatched.
        for (chapter in 1L..20L) {
            manager.enqueue(1, chapter)
            manager.pauseAll(true)
            manager.pauseAll(false)
        }
        manager.awaitIdle()

        assertTrue(repository.currentSnapshot.downloadQueue.all { it.state == DownloadState.DOWNLOADED })
        manager.close()
    }

    @Test
    fun systemRecoveryNeverLiftsTheUsersPause() = runTest {
        val repository = ShinsouRepository(
            AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 2, title = "A")),
                chapters = listOf(Chapter(id = 3, mangaId = 1, name = "One")),
            ),
        )
        val manager = DownloadManager(
            repository,
            MemoryFileSystem(),
            ChapterPageProvider { _, _ -> listOf(DownloadPage(0, "page")) },
            DownloadPageFetcher { DownloadedPage(byteArrayOf(1), "image/jpeg") },
            now = { 20 },
        )
        manager.pauseAll(true)
        val item = manager.enqueue(1, 3)
        manager.pauseForSystem(setOf(item.id))
        assertEquals(DownloadState.PAUSED, repository.currentSnapshot.downloadQueue.single().state)
        manager.resumeAfterSystem(setOf(item.id))
        manager.awaitIdle()
        // Queued again, but the user's pause still holds it.
        assertEquals(DownloadState.QUEUED, repository.currentSnapshot.downloadQueue.single().state)

        manager.pauseAll(false)
        manager.awaitIdle()
        assertEquals(DownloadState.DOWNLOADED, repository.currentSnapshot.downloadQueue.single().state)
        manager.close()
    }

    @Test
    fun restoreRedownloadsDownloadedQueueEntryWhenOfflineBytesAreMissing() = runTest {
        val repository = ShinsouRepository(
            AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 2, title = "A")),
                chapters = listOf(Chapter(id = 3, mangaId = 1, name = "One")),
            ),
        )
        val files = MemoryFileSystem()
        var fetches = 0
        val manager = DownloadManager(
            repository,
            files,
            ChapterPageProvider { _, _ -> listOf(DownloadPage(0, "page")) },
            DownloadPageFetcher {
                fetches++
                DownloadedPage(byteArrayOf(1, 2, 3), "image/jpeg")
            },
            now = { 20 },
        )
        val queued = repository.enqueueDownload(1, 3, queuedAt = 10)
        repository.setDownloadState(
            queued.id,
            DownloadState.DOWNLOADED,
            progress = 1.0,
            downloadedPages = 1,
            totalPages = 1,
            updatedAt = 10,
        )

        manager.restoreQueue()
        manager.awaitIdle()

        val restored = repository.currentSnapshot.downloadQueue.single()
        assertEquals(1, fetches)
        assertEquals(DownloadState.DOWNLOADED, restored.state)
        assertEquals(1, restored.downloadedPages)
        assertEquals(1, manager.downloadedPages(1, 3).size)
        manager.close()
    }

    @Test
    fun restoreChecksCompletedChaptersWithoutReadingTheirPagesAndSurvivesAnUnreadableOne() = runTest {
        val repository = ShinsouRepository(
            AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 2, title = "A")),
                chapters = listOf(
                    Chapter(id = 3, mangaId = 1, name = "One"),
                    Chapter(id = 4, mangaId = 1, name = "Two"),
                    Chapter(id = 5, mangaId = 1, name = "Three"),
                ),
            ),
        )
        val files = MemoryFileSystem()
        var fetches = 0
        val manager = DownloadManager(
            repository,
            files,
            ChapterPageProvider { _, _ -> listOf(DownloadPage(0, "page")) },
            DownloadPageFetcher {
                fetches++
                DownloadedPage(byteArrayOf(1, 2, 3), "image/jpeg")
            },
            now = { 20 },
        )
        // One at a time: the in-memory file system is not thread-safe.
        manager.enqueue(1, 3)
        manager.awaitIdle()
        manager.enqueue(1, 4)
        manager.awaitIdle()
        assertEquals(2, fetches)

        // Chapter 5 is queued and its folder cannot be listed; chapters 3 and 4 are complete.
        repository.enqueueDownload(1, 5, queuedAt = 30)
        files.failing = "downloads/1/5"
        files.reads.clear()
        manager.restoreQueue()
        manager.awaitIdle()

        assertTrue(files.reads.none { it.substringAfterLast('/').startsWith("page-") && "/1/3/" in it }, files.reads.toString())
        assertTrue(files.reads.none { it.substringAfterLast('/').startsWith("page-") && "/1/4/" in it }, files.reads.toString())
        val states = repository.currentSnapshot.downloadQueue.associate { it.chapterId to it.state }
        assertEquals(DownloadState.DOWNLOADED, states[3])
        assertEquals(DownloadState.DOWNLOADED, states[4])
        // Only the queued chapter was started: the unreadable folder did not stop the scheduler,
        // and the complete chapters were not downloaded again.
        assertEquals(3, fetches)
        // Opening a chapter still verifies its bytes.
        assertEquals(1, manager.downloadedPages(1, 3).size)
        assertTrue(files.reads.any { it.substringAfterLast('/').startsWith("page-") && "/1/3/" in it })

        // A page corrupted in place passes the start-up check, fails when opened, and is then
        // downloaded again instead of staying marked as available offline.
        files.write("downloads/1/4/page-0.jpg", byteArrayOf(9))
        manager.restoreQueue()
        manager.awaitIdle()
        assertEquals(DownloadState.DOWNLOADED, repository.currentSnapshot.downloadQueue.single { it.chapterId == 4L }.state)
        assertTrue(manager.downloadedPages(1, 4).isEmpty())
        val before = fetches
        // "Clear completed" hid the entry; the replacement download must show up in the queue.
        repository.clearCompletedDownloads()
        assertTrue(repository.currentSnapshot.downloadQueue.none { it.chapterId == 4L && it.visibleInQueue })
        manager.requeueBrokenDownload(1, 4)
        manager.awaitIdle()
        assertEquals(before + 1, fetches)
        assertTrue(repository.currentSnapshot.downloadQueue.single { it.chapterId == 4L }.visibleInQueue)
        assertEquals(DownloadState.DOWNLOADED, repository.currentSnapshot.downloadQueue.single { it.chapterId == 4L }.state)
        assertEquals(1, manager.downloadedPages(1, 4).size)
        manager.close()
    }
}

private class MemoryFileSystem : AppFileSystem {
    private val data = mutableMapOf<String, ByteArray>()
    val reads = mutableListOf<String>()
    /** Paths under this prefix fail like a file system that cannot be read right now. */
    var failing: String? = null
    private fun check(path: String) {
        failing?.let { if (path.startsWith(it)) throw IllegalStateException("unreadable: $path") }
    }
    override suspend fun write(relativePath: String, bytes: ByteArray) { data[relativePath] = bytes }
    override suspend fun read(relativePath: String): ByteArray? {
        check(relativePath)
        reads += relativePath
        return data[relativePath]
    }
    override suspend fun exists(relativePath: String): Boolean = relativePath in data
    override suspend fun delete(relativePath: String): Boolean = data.remove(relativePath) != null
    override suspend fun deleteTree(relativeDirectory: String): Boolean {
        val keys = data.keys.filter { it.startsWith(relativeDirectory) }
        keys.forEach(data::remove)
        return keys.isNotEmpty()
    }
    override suspend fun list(relativeDirectory: String): List<String> {
        check(relativeDirectory)
        return data.keys.filter { it.startsWith(relativeDirectory) }
    }
    override fun uri(relativePath: String): String = "memory://$relativePath"
}
