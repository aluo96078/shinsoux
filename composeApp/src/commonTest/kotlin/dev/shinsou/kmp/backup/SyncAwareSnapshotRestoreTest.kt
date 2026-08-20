package dev.shinsou.kmp.backup

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import dev.shinsou.kmp.sync.v2.CloudflareSnapshotReplacementGuard
import dev.shinsou.kmp.sync.v2.DirectSnapshotReplacementBlockedException
import dev.shinsou.kmp.sync.v2.EntityPresenceSet
import dev.shinsou.kmp.sync.v2.InMemoryLocalSyncStore
import dev.shinsou.kmp.sync.v2.InMemorySyncSessionStore
import dev.shinsou.kmp.sync.v2.RepositorySyncBridge
import dev.shinsou.kmp.sync.v2.SyncPortableIdGenerator
import dev.shinsou.kmp.sync.v2.SyncProvider
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.SyncWorkspaceDeparture
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncAwareSnapshotRestoreTest {
    @Test
    fun activeCloudflareRejectsDirectReplacementButExplicitRestoreCreatesBatchedDrafts() = runTest {
        val repository = ShinsouRepository()
        val sessionStore = InMemorySyncSessionStore(readySession())
        val localStore = InMemoryLocalSyncStore()
        var nextId = 0
        val bridge = RepositorySyncBridge(
            localStore = localStore,
            sessionStore = sessionStore,
            idGenerator = SyncPortableIdGenerator { "id-${nextId++}" },
            nowMillis = { 1_000 },
            eventCodec = DeterministicSyncEventCodec(),
            maxMutationsPerEvent = 1,
        )
        repository.configureSyncMutationBoundary(
            observer = bridge,
            guard = CloudflareSnapshotReplacementGuard(sessionStore),
        )
        val target = populatedSnapshot("Restored")

        assertFailsWith<DirectSnapshotReplacementBlockedException> {
            repository.replaceSnapshot(target)
        }
        assertTrue(repository.currentSnapshot.mangas.isEmpty())

        val restore = SyncAwareSnapshotRestore(
            repository = repository,
            sessionStore = sessionStore,
            workspaceDeparture = SyncWorkspaceDeparture { error("must not leave") },
        )
        val envelope = SnapshotBackupService.create(target, createdAt = 2_000)
        restore.restoreBackup(envelope, target = SnapshotRestoreTarget.ALL_SYNCED_DEVICES)

        assertEquals("Restored", repository.currentSnapshot.mangas.single().title)
        val drafts = localStore.readState().drafts.values
        assertTrue(drafts.size > 1)
        assertTrue(drafts.all { it.event.mutations.size == 1 })
    }

    @Test
    fun localOnlyRestoreLeavesWorkspaceBeforeReplacingSnapshot() = runTest {
        val repository = ShinsouRepository()
        val sessionStore = InMemorySyncSessionStore(readySession())
        var departed = false
        repository.setSnapshotReplacementGuard(CloudflareSnapshotReplacementGuard(sessionStore))
        val restore = SyncAwareSnapshotRestore(
            repository = repository,
            sessionStore = sessionStore,
            workspaceDeparture = SyncWorkspaceDeparture {
                departed = true
                sessionStore.clear()
            },
        )

        val result = restore.restoreBackup(
            envelope = SnapshotBackupService.create(populatedSnapshot("Local only"), 3_000),
            target = SnapshotRestoreTarget.THIS_DEVICE_ONLY,
        )

        assertTrue(departed)
        assertEquals(null, sessionStore.load())
        assertEquals("Local only", result.snapshot.mangas.single().title)
        assertEquals("Local only", repository.currentSnapshot.mangas.single().title)
    }

    @Test
    fun synchronizedResetCreatesDurableTombstonesInsteadOfDirectReplacement() = runTest {
        val repository = ShinsouRepository(populatedSnapshot("Before reset"))
        val sessionStore = InMemorySyncSessionStore(readySession())
        val localStore = InMemoryLocalSyncStore()
        var nextId = 0
        repository.configureSyncMutationBoundary(
            observer = RepositorySyncBridge(
                localStore = localStore,
                sessionStore = sessionStore,
                idGenerator = SyncPortableIdGenerator { "reset-${nextId++}" },
                nowMillis = { 4_000 },
            ),
            guard = CloudflareSnapshotReplacementGuard(sessionStore),
        )
        val restore = SyncAwareSnapshotRestore(
            repository = repository,
            sessionStore = sessionStore,
            workspaceDeparture = SyncWorkspaceDeparture { error("must not leave") },
        )

        restore.reset(SnapshotRestoreTarget.ALL_SYNCED_DEVICES)

        assertTrue(repository.currentSnapshot.mangas.isEmpty())
        assertTrue(repository.currentSnapshot.chapters.isEmpty())
        val mutations = localStore.readState().drafts.values.flatMap { it.event.mutations }
        assertTrue(mutations.any { it is EntityPresenceSet && !it.present })
    }

    @Test
    fun localOnlyImportValidatesBeforeLeavingWorkspace() = runTest {
        val repository = ShinsouRepository(populatedSnapshot("Unchanged"))
        val sessionStore = InMemorySyncSessionStore(readySession())
        var departed = false
        repository.setSnapshotReplacementGuard(CloudflareSnapshotReplacementGuard(sessionStore))
        val restore = SyncAwareSnapshotRestore(
            repository = repository,
            sessionStore = sessionStore,
            workspaceDeparture = SyncWorkspaceDeparture {
                departed = true
                sessionStore.clear()
            },
        )

        assertFailsWith<Throwable> {
            restore.importSnapshot("not valid snapshot json", SnapshotRestoreTarget.THIS_DEVICE_ONLY)
        }

        assertFalse(departed)
        assertEquals("Unchanged", repository.currentSnapshot.mangas.single().title)
        assertEquals(SyncProvider.CLOUDFLARE_V2, sessionStore.load()?.provider)
    }

    private fun populatedSnapshot(title: String): AppSnapshot {
        val manga = Manga(id = 1, source = 7, favorite = true, url = "/m", title = title)
        val chapter = Chapter(id = 10, mangaId = 1, url = "/c", name = "Chapter")
        return AppSnapshot(
            mangas = listOf(manga),
            chapters = listOf(chapter),
            categories = listOf(Category.Default, Category(id = 2, name = "Restored")),
            mangaCategories = listOf(MangaCategory(mangaId = 1, categoryId = 2)),
        )
    }

    private fun readySession() = SyncSession(
        endpoint = "https://sync.example",
        instanceId = "instance",
        userId = "user",
        workspaceId = "workspace",
        deviceId = "device",
        deviceDisplayName = "Phone",
        platform = "ios",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = 1,
    )
}
