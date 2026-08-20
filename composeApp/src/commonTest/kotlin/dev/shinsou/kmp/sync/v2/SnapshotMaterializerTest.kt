package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.AdvancedSettings
import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.AppearanceSettings
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadSettings
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.GeneralSettings
import dev.shinsou.kmp.domain.model.LibraryDisplayMode
import dev.shinsou.kmp.domain.model.LibrarySettings
import dev.shinsou.kmp.domain.model.LibraryUpdate
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.model.ReaderOrientation
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.domain.model.SecuritySettings
import dev.shinsou.kmp.domain.model.SyncSettings
import dev.shinsou.kmp.domain.model.ThemeMode
import dev.shinsou.kmp.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SnapshotMaterializerTest {
    private val mangaKey = SyncEntityKey.manga("9", "https://source.example/manga/one")
    private val chapterKey = SyncEntityKey.chapter("9", "https://source.example/chapter/one")
    private val categoryKey = SyncEntityKey.category("reading-uuid")
    private val repositoryKey = SyncEntityKey.extensionRepository("https://repo.example/index.json")

    @Test
    fun projectionUsesReplicaForSyncedFieldsAndKeepsDeviceOwnedState() {
        val localManga = Manga(id = 1, source = 9, favorite = true, url = "/old", title = "Old")
        val localChapter = Chapter(id = 10, mangaId = 1, url = "/old-c", name = "Old chapter")
        val download = DownloadQueueItem(
            id = DownloadQueueItem.id(1, 10),
            mangaId = 1,
            chapterId = 10,
            state = DownloadState.DOWNLOADED,
            downloadedPages = 3,
            totalPages = 3,
        )
        val current = AppSnapshot(
            revision = 4,
            mangas = listOf(localManga),
            chapters = listOf(localChapter),
            mangaCategories = listOf(MangaCategory(1, 0)),
            downloadQueue = listOf(download),
            updates = listOf(LibraryUpdate(1, 10, 50)),
            tracks = listOf(Track(id = 7, mangaId = 1, trackerId = 2)),
            extensionRepositories = listOf(
                ExtensionRepo("https://repo.example/index.json", "Repo", signingKeyFingerprint = "old-fingerprint"),
            ),
            settings = AppSettings(
                general = GeneralSettings(confirmBeforeClosing = false),
                appearance = AppearanceSettings(theme = ThemeMode.LIGHT),
                library = LibrarySettings(displayMode = LibraryDisplayMode.LIST, portraitColumns = 7),
                reader = ReaderSettings(orientation = ReaderOrientation.LANDSCAPE),
                downloads = DownloadSettings(location = "/device-only"),
                sync = SyncSettings(enabled = true),
                security = SecuritySettings(incognitoMode = true),
                advanced = AdvancedSettings(proxyApiKey = "must-stay-local"),
            ),
        ).validate()

        val remoteManga = Manga(
            source = 9,
            favorite = true,
            url = "https://source.example/manga/one",
            title = "Remote title",
        )
        val remoteChapter = Chapter(
            url = "https://source.example/chapter/one",
            name = "Remote chapter",
            bookmark = true,
            chapterNumber = 1.0,
        )
        var state = SyncState()
        listOf(
            operation("manga", 1, SyncMutationFactory.libraryEntry(mangaKey, remoteManga)),
            operation("chapter", 2, SyncMutationFactory.chapter(chapterKey, mangaKey, remoteChapter)),
            operation("category", 3, SyncMutationFactory.category(categoryKey, Category(name = "Reading", sort = 2))),
            operation("membership", 4, CategoryMembershipSet(mangaKey, categoryKey, true)),
            operation(
                "progress",
                5,
                ReadingProgressSet(
                    chapterKey,
                    mangaKey,
                    ReaderPosition(ReadingMode.WEBTOON, 3, 0.4),
                    readState = true,
                    historyTouchedAt = 900,
                    sessionId = "reader",
                ),
            ),
            operation(
                "settings",
                6,
                PortableSettingPatch(
                    mapOf(
                        "appearance.theme" to SyncValue.StringValue(ThemeMode.DARK.name),
                        "reader.readingMode" to SyncValue.StringValue(ReadingMode.PAGER_RTL.name),
                    ),
                ),
            ),
            operation(
                "repo",
                7,
                SyncMutationFactory.extensionRepository(
                    repositoryKey,
                    ExtensionRepo(repositoryKey.canonicalValue, "Repo", signingKeyFingerprint = "new-fingerprint"),
                ),
            ),
        ).forEach { state = SyncReducer.reduce(state, it) }
        val identity = SyncIdentityMap()
            .bind(SyncEntityKey.defaultCategory(), 0)
            .bind(mangaKey, 1)
            .bind(chapterKey, 10)
            .bind(categoryKey, 5)

        val result = SnapshotMaterializer.materialize(state, current, identity)
        val snapshot = result.snapshot

        assertEquals("Remote title", snapshot.mangas.single().title)
        assertEquals("Remote chapter", snapshot.chapters.single().name)
        assertTrue(snapshot.chapters.single().bookmark)
        assertTrue(snapshot.chapters.single().read)
        assertEquals(3, snapshot.chapters.single().lastPageRead)
        assertEquals(900, snapshot.histories.single().lastRead)
        assertEquals(listOf(MangaCategory(1, 5)), snapshot.mangaCategories)
        assertEquals(ThemeMode.DARK, snapshot.settings.appearance.theme)
        assertEquals(ReadingMode.PAGER_RTL, snapshot.settings.reader.readingMode)

        assertEquals(false, snapshot.settings.general.confirmBeforeClosing)
        assertEquals(LibraryDisplayMode.LIST, snapshot.settings.library.displayMode)
        assertEquals(7, snapshot.settings.library.portraitColumns)
        assertEquals(ReaderOrientation.LANDSCAPE, snapshot.settings.reader.orientation)
        assertEquals("/device-only", snapshot.settings.downloads.location)
        assertTrue(snapshot.settings.sync.enabled)
        assertTrue(snapshot.settings.security.incognitoMode)
        assertEquals("must-stay-local", snapshot.settings.advanced.proxyApiKey)
        assertEquals(listOf(download), snapshot.downloadQueue)
        assertEquals(current.updates, snapshot.updates)
        assertEquals(current.tracks, snapshot.tracks)

        assertEquals("old-fingerprint", snapshot.extensionRepositories.single().signingKeyFingerprint)
        assertEquals(setOf(repositoryKey.canonicalValue), result.repositoriesRequiringTrustConfirmation)
        assertEquals("old-fingerprint", result.repositoryTrustConfirmations.single().trustedFingerprint)
        assertEquals("new-fingerprint", result.repositoryTrustConfirmations.single().proposedFingerprint)
        snapshot.validate()
    }

    @Test
    fun remoteRepositoryIsNotExposedUntilExactFingerprintTransitionIsAcceptedLocally() {
        val state = SyncReducer.reduce(
            SyncState(),
            operation(
                "repository",
                1,
                SyncMutationFactory.extensionRepository(
                    repositoryKey,
                    ExtensionRepo(
                        baseUrl = repositoryKey.canonicalValue,
                        name = "Remote repository",
                        signingKeyFingerprint = "fingerprint-v1",
                    ),
                ),
            ),
        )

        val blocked = SnapshotMaterializer.materialize(state, AppSnapshot(), SyncIdentityMap())

        assertTrue(blocked.snapshot.extensionRepositories.isEmpty())
        val request = blocked.repositoryTrustConfirmations.single()
        assertEquals(repositoryKey.canonicalValue, request.baseUrl)
        assertEquals("", request.trustedFingerprint)
        assertEquals("fingerprint-v1", request.proposedFingerprint)

        val accepted = SnapshotMaterializer.materialize(
            state,
            AppSnapshot(),
            SyncIdentityMap(),
            acceptedRepositoryTrustChanges = listOf(
                request.copy(status = RepositoryTrustConfirmationStatus.ACCEPTED),
            ),
        )

        assertEquals("fingerprint-v1", accepted.snapshot.extensionRepositories.single().signingKeyFingerprint)
        assertTrue(accepted.repositoryTrustConfirmations.isEmpty())
    }

    @Test
    fun newlyDiscoveredRepositoryWithBlankFingerprintIsInvalidAndNeverProjected() {
        val state = SyncReducer.reduce(
            SyncState(),
            operation(
                "unsigned-repository",
                1,
                SyncMutationFactory.extensionRepository(
                    repositoryKey,
                    ExtensionRepo(
                        baseUrl = repositoryKey.canonicalValue,
                        name = "Unsigned remote repository",
                        signingKeyFingerprint = "",
                    ),
                ),
            ),
        )

        val result = SnapshotMaterializer.materialize(state, AppSnapshot(), SyncIdentityMap())

        assertTrue(result.snapshot.extensionRepositories.isEmpty())
        assertTrue(result.repositoryTrustConfirmations.isEmpty())
        assertTrue(result.issues.any {
            it.kind == MaterializationIssueKind.INVALID_FIELD && it.key == repositoryKey
        })
    }

    @Test
    fun staleRepositoryApprovalCannotAuthorizeANewerRemoteFingerprint() {
        val approvedV1 = RepositoryTrustConfirmation(
            repositoryKey = repositoryKey,
            baseUrl = repositoryKey.canonicalValue,
            trustedFingerprint = "",
            proposedFingerprint = "fingerprint-v1",
            status = RepositoryTrustConfirmationStatus.ACCEPTED,
        )
        val state = SyncReducer.reduce(
            SyncState(),
            operation(
                "repository-v2",
                1,
                SyncMutationFactory.extensionRepository(
                    repositoryKey,
                    ExtensionRepo(
                        baseUrl = repositoryKey.canonicalValue,
                        name = "Remote repository",
                        signingKeyFingerprint = "fingerprint-v2",
                    ),
                ),
            ),
        )

        val result = SnapshotMaterializer.materialize(
            state,
            AppSnapshot(),
            SyncIdentityMap(),
            acceptedRepositoryTrustChanges = listOf(approvedV1),
        )

        assertTrue(result.snapshot.extensionRepositories.isEmpty())
        assertEquals("fingerprint-v2", result.repositoryTrustConfirmations.single().proposedFingerprint)
    }

    @Test
    fun remoteEntityKeyRemapPreservesTheExistingLocalIdBeforeAllocation() {
        val mangaV2 = SyncEntityKey.manga("9", "https://source.example/manga/canonical", version = 2)
        val local = Manga(id = 41, source = 9, favorite = true, url = mangaKey.canonicalValue, title = "Local")
        var state = SyncState()
        state = SyncReducer.reduce(state, operation("manga", 1, SyncMutationFactory.libraryEntry(mangaKey, local)))
        state = SyncReducer.reduce(state, operation("remap", 2, EntityKeyRemap(mangaKey, mangaV2)))

        val splitBatch = SnapshotMaterializer.materialize(
            state,
            AppSnapshot(mangas = listOf(local)),
            SyncIdentityMap().bind(mangaKey, local.id),
        )

        assertTrue(splitBatch.snapshot.mangas.isEmpty())
        assertTrue(splitBatch.issues.any {
            it.kind == MaterializationIssueKind.INVALID_FIELD && it.key == mangaV2
        })
        assertEquals(local.id, splitBatch.identityMap.localId(mangaV2))

        val migrated = local.copy(url = mangaV2.canonicalValue, title = "Canonical")
        state = SyncReducer.reduce(state, operation("patch-v2", 3, SyncMutationFactory.libraryEntry(mangaV2, migrated)))
        val result = SnapshotMaterializer.materialize(state, AppSnapshot(mangas = listOf(local)), splitBatch.identityMap)

        assertEquals(local.id, result.identityMap.localId(mangaV2))
        assertEquals(null, result.identityMap.localId(mangaKey))
        assertEquals(local.id, result.snapshot.mangas.single().id)
        assertEquals(mangaV2.canonicalValue, result.snapshot.mangas.single().url)
        assertTrue(result.issues.none { it.kind == MaterializationIssueKind.IDENTITY_COLLISION })
        assertTrue(result.issues.none { it.kind == MaterializationIssueKind.INVALID_FIELD })
    }

    @Test
    fun mangaAndChapterIdentityFieldMismatchesAreInvalidAndNotProjected() {
        val wrongMangaFields = Manga(
            source = 10,
            favorite = true,
            url = "https://source.example/manga/different",
            title = "Wrong identity",
        )
        val validManga = Manga(
            source = 9,
            favorite = true,
            url = mangaKey.canonicalValue,
            title = "Valid parent",
        )
        val wrongChapterFields = Chapter(
            url = "https://source.example/chapter/different",
            name = "Wrong child identity",
        )
        var state = SyncState()
        state = SyncReducer.reduce(
            state,
            operation("invalid-manga", 1, SyncMutationFactory.libraryEntry(mangaKey, wrongMangaFields)),
        )
        state = SyncReducer.reduce(
            state,
            operation("invalid-chapter", 2, SyncMutationFactory.chapter(chapterKey, mangaKey, wrongChapterFields)),
        )

        val invalidManga = SnapshotMaterializer.materialize(state, AppSnapshot(), SyncIdentityMap())

        assertTrue(invalidManga.snapshot.mangas.isEmpty())
        assertTrue(invalidManga.snapshot.chapters.isEmpty())
        assertTrue(invalidManga.issues.any {
            it.kind == MaterializationIssueKind.INVALID_FIELD && it.key == mangaKey
        })

        state = SyncReducer.reduce(
            state,
            operation("valid-manga", 3, SyncMutationFactory.libraryEntry(mangaKey, validManga)),
        )
        val invalidChapter = SnapshotMaterializer.materialize(state, AppSnapshot(), SyncIdentityMap())

        assertEquals(mangaKey.canonicalValue, invalidChapter.snapshot.mangas.single().url)
        assertTrue(invalidChapter.snapshot.chapters.isEmpty())
        assertTrue(invalidChapter.issues.any {
            it.kind == MaterializationIssueKind.INVALID_FIELD && it.key == chapterKey
        })
    }

    @Test
    fun sameVersionConcurrentRemapForkRelocatesIdentityToDeterministicWinnerAcrossIncrementalAndFreshReplay() {
        val a1 = SyncEntityKey.manga("9", "/a", version = 1)
        val b2 = SyncEntityKey.manga("9", "/b", version = 2)
        val d2 = SyncEntityKey.manga("9", "/d", version = 2)
        val first = operation("a-to-b", 1, EntityKeyRemap(a1, b2))
        val fork = operation("a-to-d", 1, EntityKeyRemap(a1, d2))

        val afterFirstReplica = SyncReducer.reduce(SyncState(), first)
        val afterFirstProjection = SnapshotMaterializer.materialize(
            afterFirstReplica,
            AppSnapshot(),
            SyncIdentityMap().bind(a1, 41),
        )
        assertEquals(41, afterFirstProjection.identityMap.localId(b2))

        val incrementalReplica = SyncReducer.reduce(afterFirstReplica, fork)
        val incrementalProjection = SnapshotMaterializer.materialize(
            incrementalReplica,
            AppSnapshot(),
            afterFirstProjection.identityMap,
        )
        assertEquals(d2, incrementalReplica.resolveKey(a1))
        assertEquals(41, incrementalProjection.identityMap.localId(d2))
        assertEquals(null, incrementalProjection.identityMap.localId(b2))

        val freshReplica = listOf(fork, first).fold(SyncState(), SyncReducer::reduce)
        val freshProjection = SnapshotMaterializer.materialize(
            freshReplica,
            AppSnapshot(),
            SyncIdentityMap().bind(a1, 41),
        )
        assertEquals(incrementalReplica, freshReplica)
        assertEquals(incrementalProjection.identityMap, freshProjection.identityMap)
    }

    @Test
    fun remoteEntityKeyRemapTargetCollisionBlocksProjectionAndReportsRepair() {
        val mangaV2 = SyncEntityKey.manga("9", "https://source.example/manga/canonical", version = 2)
        val oldLocal = Manga(id = 41, source = 9, favorite = true, url = mangaKey.canonicalValue, title = "Old")
        val targetLocal = Manga(id = 42, source = 9, favorite = true, url = mangaV2.canonicalValue, title = "Target")
        var state = SyncState()
        state = SyncReducer.reduce(state, operation("manga", 1, SyncMutationFactory.libraryEntry(mangaKey, oldLocal)))
        state = SyncReducer.reduce(state, operation("remap", 2, EntityKeyRemap(mangaKey, mangaV2)))
        val identity = SyncIdentityMap().bind(mangaKey, oldLocal.id).bind(mangaV2, targetLocal.id)

        val result = SnapshotMaterializer.materialize(
            state,
            AppSnapshot(mangas = listOf(oldLocal, targetLocal)),
            identity,
        )

        assertTrue(result.snapshot.mangas.isEmpty())
        assertTrue(mangaKey in result.identityMap.blockedKeys)
        assertTrue(mangaV2 in result.identityMap.blockedKeys)
        val issue = assertNotNull(result.issues.singleOrNull {
            it.kind == MaterializationIssueKind.IDENTITY_COLLISION && it.key == mangaV2
        })
        assertTrue(issue.message.isNotBlank())
    }

    @Test
    fun orphanIsRetainedInReplicaButNotProjected() {
        val missingParent = SyncEntityKey.manga("9", "/missing")
        val state = SyncReducer.reduce(
            SyncState(),
            operation(
                "child-first",
                1,
                SyncMutationFactory.chapter(
                    chapterKey,
                    missingParent,
                    Chapter(url = "/c", name = "orphan"),
                ),
            ),
        )

        val result = SnapshotMaterializer.materialize(state, AppSnapshot(), SyncIdentityMap())

        assertTrue(chapterKey in state.entities)
        assertTrue(result.snapshot.chapters.isEmpty())
        assertTrue(result.issues.any { it.kind == MaterializationIssueKind.ORPHAN && it.key == chapterKey })
        result.snapshot.validate()
    }

    @Test
    fun tombstonedParentsBecomeNonVisibleLocalShellsOnlyWhileDeviceDataNeedsThem() {
        val manga = Manga(id = 1, source = 9, favorite = true, url = "/m", title = "Local")
        val chapter = Chapter(id = 10, mangaId = 1, url = "/c", name = "Local chapter", read = true)
        val download = DownloadQueueItem(
            id = DownloadQueueItem.id(1, 10),
            mangaId = 1,
            chapterId = 10,
            state = DownloadState.DOWNLOADED,
            downloadedPages = 1,
            totalPages = 1,
        )
        val current = AppSnapshot(mangas = listOf(manga), chapters = listOf(chapter), downloadQueue = listOf(download))
        var state = SyncState()
        state = SyncReducer.reduce(state, operation("m", 1, SyncMutationFactory.libraryEntry(mangaKey, manga)))
        state = SyncReducer.reduce(state, operation("c", 2, SyncMutationFactory.chapter(chapterKey, mangaKey, chapter)))
        state = SyncReducer.reduce(
            state,
            SyncEvent("delete", HlcTimestamp(3, 0, "device"), CascadeMutationPlanner.deleteManga(state, mangaKey)),
        )
        val identity = SyncIdentityMap().bind(mangaKey, 1).bind(chapterKey, 10)

        val withShell = SnapshotMaterializer.materialize(state, current, identity)

        assertEquals(setOf(1L), withShell.localMangaShellIds)
        assertEquals(setOf(10L), withShell.localChapterShellIds)
        assertFalse(withShell.snapshot.mangas.single().favorite)
        assertEquals(listOf(download), withShell.snapshot.downloadQueue)
        assertTrue(withShell.snapshot.histories.isEmpty())

        val withoutDeviceDependency = SnapshotMaterializer.materialize(
            state,
            current.copy(downloadQueue = emptyList()),
            identity,
        )
        assertTrue(withoutDeviceDependency.snapshot.mangas.isEmpty())
        assertTrue(withoutDeviceDependency.snapshot.chapters.isEmpty())
    }

    @Test
    fun cascadePlannerEmitsChildProgressAndMembershipTombstones() {
        var state = SyncState()
        listOf(
            operation("m", 1, LibraryEntryPatch(mangaKey, emptyMap())),
            operation("c", 2, ChapterStatePatch(chapterKey, mangaKey, emptyMap())),
            operation("member", 3, CategoryMembershipSet(mangaKey, categoryKey, true)),
        ).forEach { state = SyncReducer.reduce(state, it) }

        val mutations = CascadeMutationPlanner.deleteManga(state, mangaKey)

        assertTrue(mutations.contains(EntityPresenceSet(mangaKey, false)))
        assertTrue(mutations.contains(EntityPresenceSet(chapterKey, false)))
        assertTrue(mutations.contains(ReadingProgressPresenceSet(chapterKey, mangaKey, false)))
        assertTrue(mutations.contains(CategoryMembershipSet(mangaKey, categoryKey, false)))
    }

    @Test
    fun portableSettingsEncoderExactlyMatchesDocumentedAllowlist() {
        val encoded = PortableSettingProjector.encode(AppSettings())

        assertEquals(PortableSettingsV1.allowedFields, encoded.keys)
        assertFalse(encoded.keys.any { it.startsWith("downloads.") || it.startsWith("security.") || it.startsWith("sync.") })
    }

    @Test
    fun olderRemoteRepositoryCannotClearLocallyTrustedFingerprint() {
        val current = AppSnapshot(
            extensionRepositories = listOf(
                ExtensionRepo(
                    baseUrl = repositoryKey.canonicalValue,
                    name = "Repo",
                    signingKeyFingerprint = "locally-trusted",
                ),
            ),
        )
        val state = SyncReducer.reduce(
            SyncState(),
            operation(
                "older-repository",
                1,
                SyncMutationFactory.extensionRepository(
                    repositoryKey,
                    ExtensionRepo(repositoryKey.canonicalValue, "Repo", signingKeyFingerprint = ""),
                ),
            ),
        )

        val result = SnapshotMaterializer.materialize(state, current, SyncIdentityMap())

        assertEquals("locally-trusted", result.snapshot.extensionRepositories.single().signingKeyFingerprint)
        assertTrue(result.repositoriesRequiringTrustConfirmation.isEmpty())
    }

    private fun operation(id: String, millis: Long, mutation: SyncMutation): SyncEvent =
        SyncEvent(id, HlcTimestamp(millis, 0, "device"), listOf(mutation))
}
