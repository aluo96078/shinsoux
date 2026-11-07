package dev.shinsou.kmp.download

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.plugin.v2.ExtensionDownloadEntryV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackgroundDownloadCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val files = MemoryFileSystem()
    private val snapshot = MutableStateFlow(AppSnapshot())
    private val novels = MutableStateFlow<List<ExtensionDownloadEntryV2>>(emptyList())
    private val calls = mutableListOf<String>()

    @AfterTest
    fun tearDown() = scope.cancel()

    private var failMangaResume = false

    private fun coordinator() = BackgroundDownloadCoordinator(
        snapshot = snapshot,
        novels = novels,
        pauseManga = { ids ->
            calls += "manga:true"
            snapshot.value = snapshot.value.copy(
                downloadQueue = snapshot.value.downloadQueue.map { if (it.id in ids) it.copy(state = DownloadState.PAUSED) else it },
            )
        },
        resumeManga = { ids ->
            calls += "manga:false"
            if (failMangaResume) error("source unavailable")
            snapshot.value = snapshot.value.copy(
                downloadQueue = snapshot.value.downloadQueue.map {
                    if (it.state == DownloadState.PAUSED && (ids == null || it.id in ids)) it.copy(state = DownloadState.QUEUED) else it
                },
            )
        },
        pauseNovels = { ids ->
            calls += "novels:true"
            novels.value = novels.value.map { if (it.id in ids) it.copy(state = DownloadState.PAUSED) else it }
        },
        resumeNovels = { ids ->
            calls += "novels:false"
            novels.value = novels.value.map {
                if (it.state == DownloadState.PAUSED && (ids == null || it.id in ids)) it.copy(state = DownloadState.QUEUED) else it
            }
        },
        fileSystem = files,
        scope = scope,
    )

    private fun DownloadState.waiting() = this == DownloadState.QUEUED || this == DownloadState.DOWNLOADING

    private fun setManga(state: DownloadState) {
        snapshot.value = snapshot.value.copy(downloadQueue = listOf(DownloadQueueItem("m1", 1, 1, state)))
    }

    private fun setNovel(state: DownloadState) {
        novels.value = listOf(
            ExtensionDownloadEntryV2("n1", SourceKey(2, "fixture", "source"), "book", "unit", "Book", "Chapter", state),
        )
    }

    @Test
    fun statusCountsBothQueuesAndIsNullWhenNothingWaits() {
        assertNull(backgroundDownloadStatus(AppSnapshot(), emptyList()))
        setManga(DownloadState.QUEUED)
        setNovel(DownloadState.DOWNLOADING)
        val status = backgroundDownloadStatus(snapshot.value, novels.value)
        assertEquals(2, status?.total)
        assertEquals("Book · Chapter", status?.current)
        setManga(DownloadState.PAUSED)
        setNovel(DownloadState.ERROR)
        assertNull(backgroundDownloadStatus(snapshot.value, novels.value))
    }

    @Test
    fun statusSplitsProgressPerTitleWithTheActiveOneFirst() {
        snapshot.value = snapshot.value.copy(
            downloadQueue = listOf(
                DownloadQueueItem("a1", 1, 11, DownloadState.DOWNLOADED),
                DownloadQueueItem("a2", 1, 12, DownloadState.QUEUED),
                // A title with nothing left waiting is not listed.
                DownloadQueueItem("b1", 2, 21, DownloadState.DOWNLOADED),
            ),
        )
        novels.value = listOf(
            ExtensionDownloadEntryV2("n1", SourceKey(2, "fixture", "source"), "book", "u1", "Book", "One", DownloadState.DOWNLOADED),
            ExtensionDownloadEntryV2("n2", SourceKey(2, "fixture", "source"), "book", "u2", "Book", "Two", DownloadState.DOWNLOADING),
            ExtensionDownloadEntryV2("n3", SourceKey(2, "fixture", "source"), "book", "u3", "Book", "Three", DownloadState.QUEUED),
        )

        val works = backgroundDownloadStatus(snapshot.value, novels.value)?.works

        assertEquals(
            listOf(
                BackgroundDownloadWork(title = "Book", done = 1, total = 3, chapter = "Two"),
                BackgroundDownloadWork(title = "1", done = 1, total = 2, chapter = null),
            ),
            works,
        )
    }

    @Test
    fun longSourceNamesAreShortenedForNotifications() {
        val long = "書".repeat(500)
        novels.value = listOf(
            ExtensionDownloadEntryV2("n1", SourceKey(2, "fixture", "source"), "book", "u1", long, "章\n節  $long", DownloadState.DOWNLOADING),
        )

        val status = backgroundDownloadStatus(snapshot.value, novels.value)!!
        val work = status.works.single()

        assertEquals(60, work.title.length)
        assertEquals(true, work.title.endsWith("…"))
        assertEquals(60, work.chapter!!.length)
        assertEquals(true, work.chapter!!.startsWith("章 節 "))
        assertEquals(true, status.current!!.length <= 120)
    }

    @Test
    fun resumesOnlyTheQueuesTheSystemPaused() = runBlocking {
        setManga(DownloadState.QUEUED)
        setNovel(DownloadState.PAUSED) // the user's pause
        val downloads = coordinator()

        downloads.suspendForSystem()
        downloads.resumeAfterSystem()
        downloads.resumeAfterSystem()

        assertEquals(listOf("manga:true", "manga:false"), calls)
        assertEquals(DownloadState.PAUSED, novels.value.single().state)
    }

    @Test
    fun aSystemPauseSurvivesAProcessRestart() = runBlocking {
        setManga(DownloadState.DOWNLOADING)
        setNovel(DownloadState.QUEUED)
        coordinator().suspendForSystem()
        calls.clear()

        // A new process reads what the old one saved.
        coordinator().resumeAfterSystem()

        assertEquals(listOf("manga:false", "novels:false"), calls)
    }

    @Test
    fun reportsSystemPausedWorkUntilItIsResumed() = runBlocking {
        setManga(DownloadState.QUEUED)
        val downloads = coordinator()
        assertEquals(false, downloads.hasSystemPausedWork())

        downloads.suspendForSystem()
        assertEquals(true, downloads.hasSystemPausedWork())
        // A new process (an iOS catch-up launch) sees the same mark.
        assertEquals(true, coordinator().hasSystemPausedWork())

        downloads.resumeAfterSystem()
        assertEquals(false, downloads.hasSystemPausedWork())
    }

    @Test
    fun anUnreadableMarkCountsAsSystemPausedWork() = runBlocking {
        setManga(DownloadState.QUEUED)
        coordinator().suspendForSystem()
        files.hideReads = true

        assertEquals(true, coordinator().hasSystemPausedWork())
    }

    @Test
    fun aReturnThatWonTheRaceIsNotUndone() = runBlocking {
        setManga(DownloadState.QUEUED)
        coordinator().suspendForSystem(stillApplies = { false })

        assertEquals(emptyList(), calls)
        assertEquals(DownloadState.QUEUED, snapshot.value.downloadQueue.single().state)
    }

    @Test
    fun aFailedResumeKeepsItsMarkAndIsRetried() = runBlocking {
        setManga(DownloadState.QUEUED)
        setNovel(DownloadState.QUEUED)
        val downloads = coordinator()
        downloads.suspendForSystem()
        failMangaResume = true
        downloads.resumeAfterSystem()
        failMangaResume = false
        calls.clear()

        // Even in a new process, only the queue that failed to resume is tried again.
        coordinator().resumeAfterSystem()

        assertEquals(listOf("manga:false"), calls)
    }

    @Test
    fun anUnreadableMarkIsReadAgainLater() = runBlocking {
        setManga(DownloadState.QUEUED)
        coordinator().suspendForSystem()
        calls.clear()
        files.hideReads = true
        val downloads = coordinator()
        downloads.resumeAfterSystem()
        assertEquals(emptyList(), calls)

        files.hideReads = false
        downloads.resumeAfterSystem()
        assertEquals(listOf("manga:false"), calls)
    }

    @Test
    fun aStartupResumeYieldsToAStopThatCameAfterIt() = runBlocking {
        setManga(DownloadState.QUEUED)
        val downloads = coordinator()
        val startedAt = downloads.stopCount()
        downloads.suspendForSystem() // the job was stopped while startup was still running
        calls.clear()

        downloads.resumeAfterSystem(unlessStoppedSince = startedAt)
        assertEquals(emptyList(), calls)
        // The next foreground (or job run) resumes it.
        downloads.resumeAfterSystem()
        assertEquals(listOf("manga:false"), calls)
    }

    @Test
    fun aStopBeforeTheQueuesAreRestoredStillBlocksTheStartupResume() = runBlocking {
        setManga(DownloadState.QUEUED)
        coordinator().suspendForSystem() // previous process
        snapshot.value = AppSnapshot() // new process: nothing restored yet
        val downloads = coordinator()
        val startedAt = downloads.stopCount()
        downloads.suspendForSystem() // the new job is stopped while startup runs
        calls.clear()

        downloads.resumeAfterSystem(unlessStoppedSince = startedAt)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun aStopWhileTheNovelQueueLoadsPausesItOnceLoaded() = runBlocking {
        val downloads = coordinator()
        downloads.suspendForSystem() // nothing loaded yet
        assertEquals(emptyList(), calls)
        setNovel(DownloadState.QUEUED) // the saved queue finishes loading, still held back

        downloads.novelQueueRestored()
        assertEquals(listOf("novels:true"), calls)
        // A later run gets it back.
        downloads.resumeAfterSystem()
        assertEquals(listOf("novels:true", "novels:false"), calls)
    }

    @Test
    fun aNewerRunCancelsAStopThatWaitedForTheNovelQueue() = runBlocking {
        val downloads = coordinator()
        downloads.suspendForSystem()
        downloads.resumeAfterSystem() // the user opened the app before the queue loaded
        setNovel(DownloadState.QUEUED)

        downloads.novelQueueRestored()
        assertEquals(emptyList(), calls)
    }

    @Test
    fun anEntryTheUserPausedStaysPausedAfterRecovery() = runBlocking {
        snapshot.value = AppSnapshot(
            downloadQueue = listOf(
                DownloadQueueItem("user-paused", 1, 1, DownloadState.PAUSED),
                DownloadQueueItem("running", 1, 2, DownloadState.DOWNLOADING),
            ),
        )
        coordinator().suspendForSystem()
        coordinator().resumeAfterSystem() // a new process

        assertEquals(
            mapOf("user-paused" to DownloadState.PAUSED, "running" to DownloadState.QUEUED),
            snapshot.value.downloadQueue.associate { it.id to it.state },
        )
    }

    @Test
    fun nothingIsPausedWhenTheMarkCannotBeSaved() = runBlocking {
        setManga(DownloadState.QUEUED)
        files.failWrites = true
        coordinator().suspendForSystem()

        assertEquals(emptyList(), calls)
    }

    private class MemoryFileSystem : AppFileSystem {
        private val values = linkedMapOf<String, ByteArray>()
        var failWrites = false
        override suspend fun write(relativePath: String, bytes: ByteArray) {
            if (failWrites) error("disk full")
            values[relativePath] = bytes
        }
        /** Like iOS NSData: an unreadable file comes back as null while it still exists. */
        var hideReads = false
        override suspend fun read(relativePath: String): ByteArray? = values[relativePath]?.takeUnless { hideReads }
        override suspend fun exists(relativePath: String): Boolean = relativePath in values
        override suspend fun delete(relativePath: String): Boolean = values.remove(relativePath) != null
        override suspend fun deleteTree(relativeDirectory: String): Boolean = false
        override suspend fun list(relativeDirectory: String): List<String> = emptyList()
        override fun uri(relativePath: String): String = "memory://$relativePath"
    }
}
