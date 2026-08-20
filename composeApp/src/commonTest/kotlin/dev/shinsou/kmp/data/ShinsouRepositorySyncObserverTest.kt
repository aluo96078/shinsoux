package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.Manga
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShinsouRepositorySyncObserverTest {
    @Test
    fun observerCommitsBeforeProjectionIsPublished() = runTest {
        val repository = ShinsouRepository()
        var visibleRevisionDuringCommit = -1L
        repository.setMutationObserver { _, next ->
            visibleRevisionDuringCommit = repository.currentSnapshot.revision
            assertEquals(1L, next.revision)
        }

        repository.upsertManga(Manga(source = 7, url = "/title", title = "Title"))

        assertEquals(0L, visibleRevisionDuringCommit)
        assertEquals(1L, repository.currentSnapshot.revision)
    }

    @Test
    fun failedDurableCommitDoesNotPublishDomainMutation() = runTest {
        val repository = ShinsouRepository()
        repository.setMutationObserver { _, _ -> error("sync store unavailable") }

        assertFailsWith<IllegalStateException> {
            repository.upsertManga(Manga(source = 7, url = "/title", title = "Title"))
        }

        assertTrue(repository.currentSnapshot.mangas.isEmpty())
        assertEquals(0L, repository.currentSnapshot.revision)
    }

    @Test
    fun remoteProjectionBypassesOutboxObserver() = runTest {
        val repository = ShinsouRepository()
        var observed = 0
        repository.setMutationObserver { _, _ -> observed++ }

        repository.replaceSnapshotFromSync(
            AppSnapshot(mangas = listOf(Manga(id = 1, source = 7, url = "/remote", title = "Remote"))),
        )

        assertEquals(0, observed)
        assertEquals("Remote", repository.currentSnapshot.mangas.single().title)
    }
}
