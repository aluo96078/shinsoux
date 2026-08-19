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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadManagerTest {
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
}

private class MemoryFileSystem : AppFileSystem {
    private val data = mutableMapOf<String, ByteArray>()
    override suspend fun write(relativePath: String, bytes: ByteArray) { data[relativePath] = bytes }
    override suspend fun read(relativePath: String): ByteArray? = data[relativePath]
    override suspend fun exists(relativePath: String): Boolean = relativePath in data
    override suspend fun delete(relativePath: String): Boolean = data.remove(relativePath) != null
    override suspend fun deleteTree(relativeDirectory: String): Boolean {
        val keys = data.keys.filter { it.startsWith(relativeDirectory) }
        keys.forEach(data::remove)
        return keys.isNotEmpty()
    }
    override suspend fun list(relativeDirectory: String): List<String> = data.keys.filter { it.startsWith(relativeDirectory) }
    override fun uri(relativePath: String): String = "memory://$relativePath"
}
