package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Manga
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FailClosedSyncMutationObserverTest {
    @Test
    fun configuredButUnavailableSyncBoundaryRejectsInsteadOfLosingMutation() = runTest {
        val repository = ShinsouRepository()
        repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, guard = null)

        assertFailsWith<SyncMutationBoundaryUnavailableException> {
            repository.upsertManga(Manga(source = 7, url = "/blocked", title = "Blocked"))
        }

        assertTrue(repository.currentSnapshot.mangas.isEmpty())
        assertEquals(0L, repository.currentSnapshot.revision)
    }

    @Test
    fun deviceLocalMutationRemainsAvailableWhileSynchronizedWriterIsBlocked() = runTest {
        val repository = ShinsouRepository()
        repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, guard = null)

        val updated = repository.currentSnapshot.backupState.copy(automaticEnabled = true)
        repository.setBackupState(updated)

        assertEquals(updated, repository.currentSnapshot.backupState)
        assertEquals(1L, repository.currentSnapshot.revision)
    }
}
