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

    @Test
    fun typedContentAuthorityProjectionUsesItsOwnGuardOriginAndBypassesOutboxObserver() = runTest {
        val repository = ShinsouRepository()
        var observed = 0
        var guardedOrigin: SnapshotReplacementOrigin? = null
        repository.configureSyncMutationBoundary(
            observer = SnapshotMutationObserver { _, _ -> observed++ },
            guard = SnapshotReplacementGuard { origin, _, _ -> guardedOrigin = origin },
        )
        val requested = AppSnapshot(
            mangas = listOf(Manga(id = 1, source = 0, url = "local://typed/v1/publication", title = "Typed")),
        )

        val committed = repository.materializeContentAuthorityProjectionIfRevision(
            expectedRevision = 0,
            requested = requested,
        )

        assertEquals(SnapshotReplacementOrigin.CONTENT_AUTHORITY_MATERIALIZER, guardedOrigin)
        assertEquals(0, observed)
        assertEquals("Typed", committed?.mangas?.single()?.title)
        assertEquals(null, repository.materializeContentAuthorityProjectionIfRevision(0, requested))
    }

    @Test
    fun atomicPortableMutationDoesNotPublishAfterAnyParticipantFailureAndCanRetry() = runTest {
        AtomicRestoreFailure.entries.forEach { failurePoint ->
            val repository = ShinsouRepository()
            val durableJournalKeys = linkedSetOf<String>()
            var failOutbox = failurePoint == AtomicRestoreFailure.OUTBOX
            repository.setMutationObserver { _, next ->
                if (failOutbox) {
                    failOutbox = false
                    error("outbox unavailable")
                }
                durableJournalKeys += "revision:${next.revision}"
            }
            val requested = AppSnapshot(
                mangas = listOf(Manga(id = 1, source = 7, url = "/portable", title = "Portable")),
            )
            var failParticipant = true

            suspend fun attempt(): AtomicSnapshotMutationResult<String> =
                repository.commitSnapshotMutationAtomically(
                    requested = requested,
                    origin = SnapshotReplacementOrigin.DIRECT,
                ) { _, _, commitSyncJournal ->
                    if (failurePoint == AtomicRestoreFailure.ANNOTATION && failParticipant) {
                        failParticipant = false
                        error("annotation unavailable")
                    }
                    commitSyncJournal()
                    if (failurePoint == AtomicRestoreFailure.CONTENT && failParticipant) {
                        failParticipant = false
                        error("content transaction unavailable")
                    }
                    "committed"
                }

            assertFailsWith<IllegalStateException> { attempt() }
            assertTrue(repository.currentSnapshot.mangas.isEmpty(), failurePoint.name)
            assertEquals(0L, repository.currentSnapshot.revision, failurePoint.name)

            val retried = attempt()
            assertEquals("committed", retried.result)
            assertEquals("Portable", retried.snapshot.mangas.single().title)
            assertEquals(retried.snapshot, repository.currentSnapshot)
            assertEquals(setOf("revision:1"), durableJournalKeys)
        }
    }

    private enum class AtomicRestoreFailure { ANNOTATION, OUTBOX, CONTENT }
}
