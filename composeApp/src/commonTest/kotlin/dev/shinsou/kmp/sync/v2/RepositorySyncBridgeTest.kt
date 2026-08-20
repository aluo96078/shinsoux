package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositorySyncBridgeTest {
    private val readySession = SyncSession(
        endpoint = "https://sync.example",
        instanceId = "instance",
        userId = "user",
        workspaceId = "workspace",
        deviceId = "device",
        deviceDisplayName = "Desktop",
        platform = "desktop",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = 1,
    )

    @Test
    fun initialCheckpointSeedIsAtomicIdempotentAndHasNoEventJournal() = runTest {
        var nextId = 0
        val store = InMemoryLocalSyncStore()
        val bridge = RepositorySyncBridge(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(),
            idGenerator = SyncPortableIdGenerator { "seed-${++nextId}" },
            nowMillis = { 1_000 },
        )

        assertTrue(bridge.initializeReplicaForInitialCheckpoint(populatedSnapshot(), "device"))
        val seeded = store.readState()
        assertEquals("device", seeded.genesisCheckpointSeed?.deviceId)
        assertTrue(seeded.replica.entities.isNotEmpty())
        assertTrue(seeded.drafts.isEmpty())
        assertTrue(seeded.sealedOutbox.isEmpty())
        assertEquals(1, seeded.nextDeviceSeq)
        assertFalse(seeded.materializationPending)

        assertFalse(bridge.initializeReplicaForInitialCheckpoint(populatedSnapshot(), "device"))
        assertEquals(seeded, store.readState())
    }

    @Test
    fun fullProjectionCreatesPortableEntitiesProgressMembershipAndSettings() {
        val snapshot = populatedSnapshot()
        val plan = SnapshotMutationPlanner(
            previous = AppSnapshot(),
            next = snapshot,
            initialIdentityMap = SyncIdentityMap(),
            idGenerator = ids(),
            forceFull = true,
        ).build()

        assertTrue(plan.mutations.any { it is LibraryEntryPatch })
        assertTrue(plan.mutations.any { it is ChapterStatePatch })
        assertTrue(plan.mutations.any { it is CategoryPatch })
        assertTrue(plan.mutations.any { it is CategoryMembershipSet && it.present })
        val progress = plan.mutations.filterIsInstance<ReadingProgressSet>().single()
        assertEquals(12, progress.position?.pageIndex)
        assertEquals(900L, progress.historyTouchedAt)
        assertTrue(progress.readState == true)
        assertTrue(plan.mutations.any { it is PortableSettingPatch })
        assertEquals(1L, plan.identityMap.localId(requireNotNull(plan.identityMap.key(SyncEntityType.MANGA, 1))))
        assertEquals(10L, plan.identityMap.localId(requireNotNull(plan.identityMap.key(SyncEntityType.CHAPTER, 10))))
        assertNotNull(plan.identityMap.key(SyncEntityType.CATEGORY, 2))
    }

    @Test
    fun deletionEmitsExplicitCascadeTombstones() {
        val snapshot = populatedSnapshot()
        val initial = SnapshotMutationPlanner(
            AppSnapshot(),
            snapshot,
            SyncIdentityMap(),
            ids(),
            forceFull = true,
        ).build()
        val next = AppSnapshot(settings = snapshot.settings)
        val deletion = SnapshotMutationPlanner(
            snapshot,
            next,
            initial.identityMap,
            ids(),
            forceFull = false,
        ).build()

        val tombstones = deletion.mutations.filterIsInstance<EntityPresenceSet>()
        assertTrue(tombstones.any { it.key.entityType == SyncEntityType.MANGA && !it.present })
        assertTrue(tombstones.any { it.key.entityType == SyncEntityType.CHAPTER && !it.present })
        assertTrue(tombstones.any { it.key.entityType == SyncEntityType.CATEGORY && !it.present })
        assertTrue(deletion.mutations.any { it is ReadingProgressPresenceSet && !it.present })
        assertTrue(deletion.mutations.any { it is CategoryMembershipSet && !it.present })
    }

    @Test
    fun deviceOwnedSnapshotRegionsDoNotCreateMutations() {
        val previous = AppSnapshot()
        val next = previous.copy(
            backupState = previous.backupState.copy(
                automaticEnabled = true,
                destination = "/device-only/path",
            ),
        )
        val plan = SnapshotMutationPlanner(
            previous,
            next,
            SyncIdentityMap(),
            ids(),
            forceFull = false,
        ).build()
        assertTrue(plan.mutations.isEmpty())
    }

    @Test
    fun ordinaryUnreadChapterDoesNotManufactureReadingHistory() {
        val manga = Manga(id = 1, source = 7, url = "/manga/a", title = "A")
        val chapter = Chapter(id = 10, mangaId = 1, url = "/chapter/1", name = "1")
        val plan = SnapshotMutationPlanner(
            AppSnapshot(),
            AppSnapshot(mangas = listOf(manga), chapters = listOf(chapter)),
            SyncIdentityMap(),
            ids(),
            forceFull = true,
        ).build()
        assertFalse(plan.mutations.any { it is ReadingProgressSet })
    }

    @Test
    fun canonicalIdentityUpgradeAtomicallyPersistsRemapClockMapAndDraftAcrossRestart() = runTest {
        var now = 1_000L
        var nextId = 0
        val persistence = InMemorySyncStatePersistence()
        val store = PersistentLocalSyncStore.open(persistence)
        val bridge = RepositorySyncBridge(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(readySession),
            idGenerator = SyncPortableIdGenerator { "operation-${++nextId}" },
            nowMillis = { now },
        )
        val manga = Manga(id = 7, source = 42, url = "/legacy/title", title = "Title")
        bridge.initializeReplica(AppSnapshot(mangas = listOf(manga)), readySession.deviceId)
        val beforeUpgrade = store.readState()
        val oldKey = requireNotNull(beforeUpgrade.identityMap.key(SyncEntityType.MANGA, manga.id))

        now += 1
        val result = bridge.upgradeContentIdentity(
            oldKey = oldKey,
            sourceIdentity = " 42 ",
            urlOrCanonicalId = "HTTPS://SOURCE.EXAMPLE:443/a/../canonical/%7etitle#ignored",
            newVersion = 2,
        )

        val expectedKey = SyncEntityKey.manga(
            sourceIdentity = "42",
            urlOrCanonicalId = "https://source.example/canonical/~title",
            version = 2,
        )
        assertEquals(expectedKey, result.newKey)
        assertEquals(manga.id, result.localId)
        assertTrue(result.hlc > requireNotNull(beforeUpgrade.lastLocalHlc))

        val reopened = PersistentLocalSyncStore.open(persistence)
        val durable = reopened.readState()
        assertNull(durable.identityMap.localId(oldKey))
        assertEquals(manga.id, durable.identityMap.localId(expectedKey))
        assertEquals(expectedKey, durable.replica.resolveKey(oldKey))
        assertEquals(result.hlc, durable.lastLocalHlc)
        val remapDraft = requireNotNull(durable.drafts[result.draftId])
        assertEquals(EntityKeyRemap(oldKey, expectedKey), remapDraft.event.mutations.single())
        assertEquals(result.hlc, remapDraft.event.hlc)
        assertTrue(durable.sealedOutbox.isEmpty())
        assertEquals(1L, durable.nextDeviceSeq)
    }

    @Test
    fun remapPreflightFailuresLeaveClockMapJournalAndIdGeneratorUntouched() = runTest {
        val oldKey = SyncEntityKey.manga("source", "/old", version = 1)
        val collidingTarget = SyncEntityKey.manga("source", "/canonical", version = 2)
        val initial = LocalSyncStoreState(
            identityMap = SyncIdentityMap()
                .bind(oldKey, 7)
                .bind(collidingTarget, 8),
        )
        val store = InMemoryLocalSyncStore(initial)
        var generatedIds = 0
        val bridge = RepositorySyncBridge(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(readySession),
            idGenerator = SyncPortableIdGenerator {
                generatedIds += 1
                "unexpected-$generatedIds"
            },
            nowMillis = { 1_000 },
        )

        assertFailsWith<SyncIdentityCollisionException> {
            bridge.remapEntityKey(oldKey, collidingTarget)
        }
        assertFailsWith<SyncIdentityMappingNotFoundException> {
            bridge.remapEntityKey(SyncEntityKey.manga("source", "/missing"), collidingTarget)
        }
        assertFailsWith<IllegalArgumentException> {
            bridge.remapEntityKey(oldKey, SyncEntityKey.chapter("source", "/chapter", version = 2))
        }
        assertFailsWith<IllegalArgumentException> {
            bridge.remapEntityKey(oldKey, SyncEntityKey.manga("source", "/same-version", version = 1))
        }

        assertEquals(0, generatedIds)
        assertEquals(initial, store.readState())
    }

    @Test
    fun sourceMigrationRemapsMangaAndMatchedChaptersBeforePatchingTheirNewKeys() = runTest {
        var generatedIds = 0
        val persistence = InMemorySyncStatePersistence()
        val store = PersistentLocalSyncStore.open(persistence)
        val bridge = RepositorySyncBridge(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(readySession),
            idGenerator = SyncPortableIdGenerator { "migration-${++generatedIds}" },
            nowMillis = { 2_000 },
            maxMutationsPerEvent = 2,
        )
        val oldManga = Manga(id = 7, source = 10, favorite = true, url = "/title", title = "Old")
        val oldChapters = listOf(
            Chapter(id = 70, mangaId = 7, url = "/chapter/1", name = "One"),
            Chapter(id = 71, mangaId = 7, url = "/chapter/2", name = "Two"),
        )
        val previous = AppSnapshot(mangas = listOf(oldManga), chapters = oldChapters)
        bridge.initializeReplica(previous, readySession.deviceId)
        val before = store.readState()
        val oldMangaKey = requireNotNull(before.identityMap.key(SyncEntityType.MANGA, oldManga.id))
        val oldChapterKeys = oldChapters.associate { chapter ->
            chapter.id to requireNotNull(before.identityMap.key(SyncEntityType.CHAPTER, chapter.id))
        }

        val migratedManga = oldManga.copy(source = 20, url = "/canonical-title", title = "Migrated")
        val migratedChapters = oldChapters.map { it.copy(name = "${it.name} migrated") }
        val next = previous.copy(mangas = listOf(migratedManga), chapters = migratedChapters)
        bridge.beforeCommit(previous, next)

        val after = store.readState()
        val newMangaKey = requireNotNull(after.identityMap.key(SyncEntityType.MANGA, oldManga.id))
        val newChapterKeys = migratedChapters.associate { chapter ->
            chapter.id to requireNotNull(after.identityMap.key(SyncEntityType.CHAPTER, chapter.id))
        }
        assertEquals(oldMangaKey.version + 1, newMangaKey.version)
        assertEquals(syncMangaEntityKey(migratedManga).namespace, newMangaKey.namespace)
        assertEquals(syncMangaEntityKey(migratedManga).canonicalValue, newMangaKey.canonicalValue)
        oldChapterKeys.forEach { (localId, oldKey) ->
            val newKey = requireNotNull(newChapterKeys[localId])
            assertEquals(oldKey.version + 1, newKey.version)
            assertEquals("source:20", newKey.namespace)
            assertNull(after.identityMap.localId(oldKey))
            assertEquals(localId, after.identityMap.localId(newKey))
        }
        assertNull(after.identityMap.localId(oldMangaKey))
        assertEquals(oldManga.id, after.identityMap.localId(newMangaKey))

        val newDraftMutations = (after.drafts.keys - before.drafts.keys)
            .mapNotNull(after.drafts::get)
            .sortedBy { it.event.hlc }
            .flatMap { it.event.mutations }
        val mangaRemapIndex = newDraftMutations.indexOfFirst {
            it == EntityKeyRemap(oldMangaKey, newMangaKey)
        }
        val chapterRemapIndexes = oldChapterKeys.map { (localId, oldKey) ->
            newDraftMutations.indexOfFirst { it == EntityKeyRemap(oldKey, requireNotNull(newChapterKeys[localId])) }
        }
        val firstPatchIndex = newDraftMutations.indexOfFirst { it is LibraryEntryPatch || it is ChapterStatePatch }
        assertTrue(mangaRemapIndex >= 0)
        assertTrue(chapterRemapIndexes.all { it > mangaRemapIndex })
        assertTrue(firstPatchIndex > chapterRemapIndexes.max())
        assertTrue(newDraftMutations.filterIsInstance<LibraryEntryPatch>().all { it.key != oldMangaKey })
        assertTrue(newDraftMutations.filterIsInstance<ChapterStatePatch>().all { patch ->
            patch.key in newChapterKeys.values && patch.mangaKey == newMangaKey
        })

        val projected = SnapshotMaterializer.materialize(after.replica, previous, after.identityMap)
        assertEquals(oldManga.id, projected.snapshot.mangas.single().id)
        assertEquals(oldChapters.map { it.id }, projected.snapshot.chapters.map { it.id })
        assertEquals(20, projected.snapshot.mangas.single().source)
        assertTrue(projected.issues.isEmpty())

        // Simulate a process crash after the sync transaction committed but before the proposed
        // AppSnapshot became the repository's visible snapshot. Retrying after reopening may
        // republish idempotent field patches, but must never remap the already-upgraded lineage
        // again or address an obsolete key.
        val reopened = PersistentLocalSyncStore.open(persistence)
        val retryBridge = RepositorySyncBridge(
            localStore = reopened,
            sessionStore = InMemorySyncSessionStore(readySession),
            idGenerator = SyncPortableIdGenerator { "migration-${++generatedIds}" },
            nowMillis = { 2_001 },
            maxMutationsPerEvent = 2,
        )
        retryBridge.beforeCommit(previous, next)
        val retried = reopened.readState()
        assertEquals(after.identityMap, retried.identityMap)
        val retryMutations = (retried.drafts.keys - after.drafts.keys)
            .mapNotNull(retried.drafts::get)
            .sortedBy { it.event.hlc }
            .flatMap { it.event.mutations }
        assertTrue(retryMutations.none { it is EntityKeyRemap })
        assertTrue(retryMutations.filterIsInstance<LibraryEntryPatch>().all { it.key == newMangaKey })
        assertTrue(retryMutations.filterIsInstance<ChapterStatePatch>().all { patch ->
            patch.key in newChapterKeys.values && patch.mangaKey == newMangaKey
        })
    }

    @Test
    fun productionIdentityCollisionLeavesClockMapDraftsReplicaAndGeneratorUntouched() = runTest {
        val oldKey = SyncEntityKey.manga("10", "/old")
        val occupiedKey = SyncEntityKey.manga("20", "/occupied", version = 7)
        val oldManga = Manga(id = 1, source = 10, favorite = true, url = "/old", title = "Old")
        val occupiedManga = Manga(id = 2, source = 20, favorite = true, url = "/occupied", title = "Occupied")
        var replica = SyncState()
        replica = SyncReducer.reduce(
            replica,
            SyncEvent("old", HlcTimestamp(1, 0, "remote"), listOf(SyncMutationFactory.libraryEntry(oldKey, oldManga))),
        )
        replica = SyncReducer.reduce(
            replica,
            SyncEvent(
                "occupied",
                HlcTimestamp(2, 0, "remote"),
                listOf(SyncMutationFactory.libraryEntry(occupiedKey, occupiedManga)),
            ),
        )
        val initial = LocalSyncStoreState(
            replica = replica,
            identityMap = SyncIdentityMap().bind(oldKey, 1).bind(occupiedKey, 2),
            lastLocalHlc = HlcTimestamp(50, 3, readySession.deviceId),
        )
        val store = InMemoryLocalSyncStore(initial)
        var generatedIds = 0
        val bridge = RepositorySyncBridge(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(readySession),
            idGenerator = SyncPortableIdGenerator { "must-not-run-${++generatedIds}" },
            nowMillis = { 10_000 },
        )
        val previous = AppSnapshot(mangas = listOf(oldManga, occupiedManga))
        val requested = previous.copy(
            mangas = listOf(oldManga.copy(source = 20, url = "/occupied"), occupiedManga),
        )

        assertFailsWith<SyncIdentityCollisionException> {
            bridge.beforeCommit(previous, requested)
        }

        assertEquals(0, generatedIds)
        assertEquals(initial, store.readState())
    }

    @Test
    fun replicaLineageCollisionIsRejectedEvenWithoutALocalTargetMapping() = runTest {
        val oldKey = SyncEntityKey.manga("10", "/old")
        val occupiedReplicaKey = SyncEntityKey.manga("20", "/occupied", version = 4)
        val oldManga = Manga(id = 1, source = 10, favorite = true, url = "/old", title = "Old")
        val remoteManga = Manga(source = 20, favorite = true, url = "/occupied", title = "Remote")
        var replica = SyncState()
        replica = SyncReducer.reduce(
            replica,
            SyncEvent("old", HlcTimestamp(1, 0, "remote"), listOf(SyncMutationFactory.libraryEntry(oldKey, oldManga))),
        )
        replica = SyncReducer.reduce(
            replica,
            SyncEvent(
                "remote-lineage",
                HlcTimestamp(2, 0, "remote"),
                listOf(SyncMutationFactory.libraryEntry(occupiedReplicaKey, remoteManga)),
            ),
        )
        val initial = LocalSyncStoreState(
            replica = replica,
            identityMap = SyncIdentityMap().bind(oldKey, oldManga.id),
        )
        val store = InMemoryLocalSyncStore(initial)
        var generatedIds = 0
        val bridge = RepositorySyncBridge(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(readySession),
            idGenerator = SyncPortableIdGenerator { "must-not-run-${++generatedIds}" },
            nowMillis = { 10_000 },
        )
        val previous = AppSnapshot(mangas = listOf(oldManga))

        assertFailsWith<SyncIdentityCollisionException> {
            bridge.beforeCommit(previous, previous.copy(mangas = listOf(oldManga.copy(source = 20, url = "/occupied"))))
        }

        assertEquals(0, generatedIds)
        assertEquals(initial, store.readState())
    }

    private fun populatedSnapshot(): AppSnapshot {
        val manga = Manga(
            id = 1,
            source = 7,
            favorite = true,
            url = "/manga/a",
            title = "A",
        )
        val chapter = Chapter(
            id = 10,
            mangaId = 1,
            url = "/chapter/1",
            name = "Chapter 1",
            read = true,
            lastPageRead = 12,
            chapterNumber = 1.0,
        )
        return AppSnapshot(
            mangas = listOf(manga),
            chapters = listOf(chapter),
            categories = listOf(Category.Default, Category(id = 2, name = "Later")),
            mangaCategories = listOf(MangaCategory(1, 2)),
            histories = listOf(History(id = 10, chapterId = 10, lastRead = 900)),
        )
    }

    private fun ids(): SyncPortableIdGenerator {
        var next = 1
        return SyncPortableIdGenerator { "portable-${next++}" }
    }
}
