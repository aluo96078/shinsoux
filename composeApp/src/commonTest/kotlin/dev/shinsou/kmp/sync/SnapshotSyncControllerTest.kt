package dev.shinsou.kmp.sync

import dev.shinsou.kmp.backup.SnapshotBackupService
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotSyncControllerTest {
    @Test
    fun firstSyncUploadsVersionedBackupWithoutMutatingLocalState() = runTest {
        val repository = ShinsouRepository()
        repository.upsertManga(Manga(source = 7, url = "/one", title = "One"))
        val before = repository.currentSnapshot
        val transport = MemorySnapshotTransport()
        val controller = SnapshotSyncController(
            repository = repository,
            transport = transport,
            deviceId = "device-a",
            appVersion = "test",
            nowEpochMillis = { 1_000L },
        )

        val result = controller.sync()

        assertEquals(SnapshotSyncOutcome.UPLOADED_LOCAL, result.outcome)
        assertEquals(before, repository.currentSnapshot)
        assertEquals(1, transport.writeCount)
        val uploaded = SnapshotBackupService.decode(transport.payload!!)
        assertEquals("device-a", uploaded.deviceId)
        assertEquals(1_000L, uploaded.createdAt)
        assertEquals(before, uploaded.snapshot)
    }

    @Test
    fun pullMergePersistPushIsDeterministicAndSecondRunDoesNotLoop() = runTest {
        suspend fun scenario(): ScenarioResult {
            val repository = ShinsouRepository()
            val manga = repository.upsertManga(Manga(source = 7, url = "/one", title = "One"))
            val chapter = repository.upsertChapter(
                Chapter(mangaId = manga.id, url = "/chapter", name = "Chapter 1", chapterNumber = 1.0),
            )
            repository.markChapterProgress(chapter.id, lastPageRead = 2, read = false, readAt = 10)
            val local = repository.currentSnapshot
            val remote = local.copy(
                revision = local.revision + 4,
                chapters = local.chapters.map {
                    if (it.id == chapter.id) it.copy(read = true, lastPageRead = 8, version = it.version + 1) else it
                },
            )
            val transport = MemorySnapshotTransport(
                SnapshotBackupService.encode(SnapshotBackupService.create(remote, 90, "test", "device-z")),
            )
            val controller = SnapshotSyncController(
                repository = repository,
                transport = transport,
                deviceId = "device-a",
                appVersion = "test",
                nowEpochMillis = { 100L },
            )

            val first = controller.sync()
            val revisionAfterFirst = repository.currentSnapshot.revision
            val pushed = transport.payload!!
            val second = controller.sync()

            assertEquals(SnapshotSyncOutcome.MERGED_AND_UPLOADED, first.outcome)
            assertTrue(first.conflictCount > 0)
            assertTrue(repository.chapter(chapter.id)!!.read)
            assertEquals(8, repository.chapter(chapter.id)!!.lastPageRead)
            assertEquals(SnapshotSyncOutcome.NO_CHANGES, second.outcome)
            assertEquals(revisionAfterFirst, repository.currentSnapshot.revision)
            assertEquals(1, transport.writeCount)
            return ScenarioResult(first, pushed)
        }

        val firstRun = scenario()
        val secondRun = scenario()
        assertEquals(firstRun.result, secondRun.result)
        assertEquals(firstRun.encodedPush, secondRun.encodedPush)
    }

    @Test
    fun malformedRemoteBecomesVisibleErrorAndLeavesRepositoryUntouched() = runTest {
        val repository = ShinsouRepository()
        repository.upsertManga(Manga(source = 3, url = "/m", title = "M"))
        val before = repository.currentSnapshot
        val controller = SnapshotSyncController(
            repository = repository,
            transport = MemorySnapshotTransport("not-json"),
            deviceId = "device-a",
            nowEpochMillis = { 200L },
        )

        val result = controller.sync()

        assertEquals(SnapshotSyncOutcome.ERROR, result.outcome)
        assertFalse(result.succeeded)
        assertEquals(before, repository.currentSnapshot)
        assertEquals(SnapshotSyncPhase.ERROR, controller.state.value.phase)
    }

    @Test
    fun unavailableTransportReturnsResultInsteadOfThrowing() = runTest {
        val controller = SnapshotSyncController(
            repository = ShinsouRepository(),
            transport = UnavailableSnapshotSyncTransport("iCloud Drive is available only on iOS."),
            deviceId = "desktop",
            nowEpochMillis = { 300L },
        )

        val capability = controller.refreshCapability()
        val result = controller.sync()

        assertEquals(SnapshotSyncAvailability.UNAVAILABLE, capability.availability)
        assertEquals(SnapshotSyncOutcome.UNAVAILABLE, result.outcome)
        assertEquals("iCloud Drive is available only on iOS.", result.message)
        assertEquals(SnapshotSyncPhase.UNAVAILABLE, controller.state.value.phase)
    }

    private data class ScenarioResult(
        val result: SnapshotSyncResult,
        val encodedPush: String,
    )

    private class MemorySnapshotTransport(
        var payload: String? = null,
    ) : SnapshotSyncTransport {
        override val initialCapability = SnapshotSyncCapability(
            availability = SnapshotSyncAvailability.AVAILABLE,
            detail = "Test remote is available.",
        )
        var writeCount: Int = 0

        override suspend fun capability(): SnapshotSyncCapability = initialCapability

        override suspend fun readSnapshot(): String? = payload

        override suspend fun writeSnapshot(encodedEnvelope: String) {
            payload = encodedEnvelope
            writeCount++
        }
    }
}
