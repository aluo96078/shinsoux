package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.AppearanceSettings
import dev.shinsou.kmp.domain.model.ALWAYS_ASK_CATEGORY_ID
import dev.shinsou.kmp.domain.model.AdvancedSettings
import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.BackupState
import dev.shinsou.kmp.domain.model.BackupStatus
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.LibraryUpdate
import dev.shinsou.kmp.domain.model.LibrarySettings
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaPatch
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.SecuritySettings
import dev.shinsou.kmp.domain.model.ThemeMode
import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackerAccountState
import dev.shinsou.kmp.domain.model.TrackerIds
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ShinsouRepositoryTest {
    @Test
    fun proxySecretStaysInMemoryButNeverEntersPersistedOrExportedSnapshots() = runTest {
        val persisted = mutableListOf<String>()
        val repository = ShinsouRepository(
            initial = AppSnapshot(
                settings = AppSettings(advanced = AdvancedSettings(proxyApiKey = "super-secret")),
            ),
            persist = persisted::add,
        )

        repository.updateSettings { it.copy(appearance = AppearanceSettings(theme = ThemeMode.DARK)) }
        repository.flushPersistence()

        assertEquals("super-secret", repository.currentSnapshot.settings.advanced.proxyApiKey)
        assertFalse(persisted.single().contains("super-secret"))
        assertEquals("", ShinsouRepository.decodeSnapshot(persisted.single()).settings.advanced.proxyApiKey)
        assertFalse(repository.exportSnapshot().contains("super-secret"))
        repository.closePersistence()
    }

    @Test
    fun mangaChapterCategoryAndHistoryCrudPublishesAndPersists() = runTest {
        val persisted = mutableListOf<String>()
        val repository = ShinsouRepository(persist = persisted::add)

        val manga = repository.upsertManga(
            Manga(source = 10, favorite = true, url = "/manga", title = "Shinsou"),
        )
        val chapter = repository.upsertChapter(
            Chapter(mangaId = manga.id, url = "/chapter/1", name = "Chapter 1", chapterNumber = 1.0),
        )
        val category = repository.upsertCategory(Category(name = "Reading"))
        repository.setMangaCategories(manga.id, listOf(category.id))
        repository.markChapterProgress(
            chapterId = chapter.id,
            lastPageRead = 7,
            read = false,
            readAt = 1_000,
            timeRead = 25,
        )
        repository.recordHistory(chapter.id, lastRead = 2_000, timeRead = 15)
        repository.flushPersistence()

        assertEquals(category.id, repository.categoriesForManga(manga.id).single().id)
        assertEquals(7, repository.chapter(chapter.id)?.lastPageRead)
        assertEquals(7, repository.history().single().chapter.lastPageRead)
        assertEquals(40, repository.history().single().timeRead)
        assertEquals(1, repository.library().single().totalChapters)
        assertTrue(repository.snapshot.value.revision >= 6)
        assertEquals(repository.exportSnapshot(), persisted.last())
        repository.closePersistence()
    }

    @Test
    fun readerProgressDoesNotRevertACompletedChapterToUnread() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 10, url = "/manga", title = "Shinsou"))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/chapter/1", name = "Chapter 1"))

        repository.markChapterProgress(chapter.id, lastPageRead = 9, read = true, readAt = 1_000)
        repository.markChapterProgress(chapter.id, lastPageRead = 8, read = false, readAt = 1_001)

        assertTrue(repository.chapter(chapter.id)?.read == true)
        assertEquals(8, repository.chapter(chapter.id)?.lastPageRead)
    }

    @Test
    fun delayedOlderReaderProgressCannotOverwriteTheFinalPage() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 10, url = "/manga", title = "Shinsou"))
        val chapter = repository.upsertChapter(
            Chapter(mangaId = manga.id, url = "/chapter/1", name = "Chapter 1"),
        )

        repository.markChapterProgress(chapter.id, lastPageRead = 7, read = false, readAt = 2_000)
        repository.markChapterProgress(chapter.id, lastPageRead = 3, read = false, readAt = 1_000)

        assertEquals(7, repository.chapter(chapter.id)?.lastPageRead)
        assertEquals(2_000, repository.history().single().lastRead)
    }

    @Test
    fun exactLocatorPersistsWithVisualPageAndStaleWritesCannotReplaceIt() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 10, url = "/manga", title = "Shinsou"))
        val chapter = repository.upsertChapter(
            Chapter(mangaId = manga.id, url = "/chapter/1", name = "Chapter 1"),
        )
        val newer = testTextLocator(offset = 700)
        val older = testTextLocator(offset = 300)

        repository.markChapterProgress(
            chapter.id,
            lastPageRead = 7,
            read = false,
            readAt = 2_000,
            lastLocator = newer,
        )
        repository.markChapterProgress(
            chapter.id,
            lastPageRead = 3,
            read = false,
            readAt = 1_000,
            lastLocator = older,
        )

        assertEquals(7, repository.chapter(chapter.id)?.lastPageRead)
        assertEquals(newer, repository.currentSnapshot.histories.single().lastLocator)
        assertEquals(newer, ShinsouRepository.decodeSnapshot(repository.exportSnapshot()).histories.single().lastLocator)
    }

    @Test
    fun newerPageClearsAnIncompatibleOldRenditionPageCount() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 10, url = "/manga", title = "Shinsou"))
        val chapter = repository.upsertChapter(
            Chapter(mangaId = manga.id, url = "/chapter/1", name = "Chapter 1"),
        )
        val locator = testTextLocator(offset = 300)
        repository.markChapterProgress(
            chapter.id,
            lastPageRead = 3,
            read = false,
            readAt = 1_000,
            lastLocator = locator,
            lastPageCount = 5,
        )

        repository.markChapterProgress(
            chapter.id,
            lastPageRead = 7,
            read = false,
            readAt = 2_000,
        )

        assertEquals(7, repository.chapter(chapter.id)?.lastPageRead)
        assertEquals(locator, repository.currentSnapshot.histories.single().lastLocator)
        assertNull(repository.currentSnapshot.histories.single().lastPageCount)
        repository.currentSnapshot.validate()
    }

    @Test
    fun updatesDownloadsTrackingSettingsAndBackupAreSharedState() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 5, favorite = true, url = "/m", title = "M"))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "C"))

        repository.upsertUpdate(LibraryUpdate(manga.id, chapter.id, discoveredAt = 800))
        val queued = repository.enqueueDownload(manga.id, chapter.id, queuedAt = 100)
        repository.setDownloadState(
            id = queued.id,
            state = DownloadState.DOWNLOADED,
            progress = 1.0,
            downloadedPages = 12,
            totalPages = 12,
            updatedAt = 900,
        )
        val track = repository.upsertTrack(
            Track(mangaId = manga.id, trackerId = TrackerIds.MY_ANIME_LIST, remoteId = 22, title = manga.title),
        )
        repository.upsertTrackerAccount(
            TrackerAccountState(TrackerIds.MY_ANIME_LIST, loggedIn = true, username = "reader", lastSyncAt = 1_000),
        )
        repository.updateSettings {
            it.copy(
                appearance = AppearanceSettings(theme = ThemeMode.DARK, amoledDark = true),
                security = SecuritySettings(incognitoMode = true),
            )
        }
        repository.setBackupState(
            BackupState(status = BackupStatus.COMPLETED, lastBackupAt = 1_100, lastFileName = "backup.shinsoubackup"),
        )

        assertEquals(chapter.id, repository.recentUpdates().single().chapter.id)
        assertEquals(1, repository.libraryItems().single().downloadCount)
        assertEquals(track, repository.tracksForManga(manga.id).single())
        assertTrue(repository.snapshot.value.trackerAccounts.single().loggedIn)
        assertTrue(repository.snapshot.value.settings.appearance.amoledDark)
        assertTrue(repository.snapshot.value.settings.security.incognitoMode)
        assertEquals(BackupStatus.COMPLETED, repository.snapshot.value.backupState.status)

        repository.clearCompletedDownloads()
        assertTrue(repository.snapshot.value.downloadQueue.none { it.visibleInQueue })
        assertEquals(1, repository.libraryItems().single().downloadCount)

        val redisplayed = repository.enqueueDownload(manga.id, chapter.id, queuedAt = 1_200)
        assertTrue(redisplayed.visibleInQueue)
        assertEquals(DownloadState.DOWNLOADED, redisplayed.state)
    }

    @Test
    fun exportedSnapshotCanSeedASecondRepositoryAndContinueIds() = runTest {
        var persisted = ""
        val first = ShinsouRepository(persist = { persisted = it })
        val original = first.upsertManga(Manga(source = 1, favorite = true, url = "/one", title = "One"))
        val chapter = first.upsertChapter(Chapter(mangaId = original.id, url = "/one/1", name = "One"))
        first.recordHistory(chapter.id, lastRead = 99)
        first.flushPersistence()

        val second = ShinsouRepository(initial = ShinsouRepository.decodeSnapshot(persisted))
        val next = second.upsertManga(Manga(source = 1, url = "/two", title = "Two"))

        assertEquals(original.id + 1, next.id)
        assertEquals("One", second.history().single().manga.title)
        assertEquals(first.snapshot.value, ShinsouRepository.decodeSnapshot(first.exportSnapshot()))
        first.closePersistence()
    }

    @Test
    fun deletingMangaCascadesAllDependentSnapshotRecords() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1, favorite = true, url = "/m", title = "M"))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "C"))
        repository.recordHistory(chapter.id, 10)
        repository.upsertUpdate(LibraryUpdate(manga.id, chapter.id, 10))
        repository.enqueueDownload(manga.id, chapter.id)
        repository.upsertTrack(Track(mangaId = manga.id, trackerId = TrackerIds.MY_ANIME_LIST))

        repository.deleteManga(manga.id)

        val snapshot = repository.snapshot.value
        assertTrue(snapshot.mangas.isEmpty())
        assertTrue(snapshot.chapters.isEmpty())
        assertTrue(snapshot.mangaCategories.isEmpty())
        assertTrue(snapshot.histories.isEmpty())
        assertTrue(snapshot.updates.isEmpty())
        assertTrue(snapshot.downloadQueue.isEmpty())
        assertTrue(snapshot.tracks.isEmpty())
    }

    @Test
    fun favoritesAndCategoryDeletionMaintainDefaultCategoryInvariant() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1, favorite = true, url = "/m", title = "M"))
        val category = repository.upsertCategory(Category(name = "Later"))
        repository.setMangaCategories(manga.id, listOf(category.id))

        repository.deleteCategory(category.id)
        assertEquals(Category.Default.id, repository.categoriesForManga(manga.id).single().id)

        repository.patchManga(manga.id, MangaPatch(favorite = false))
        assertTrue(repository.library().isEmpty())
        assertTrue(repository.snapshot.value.mangaCategories.none { it.mangaId == manga.id })

        val restored = repository.patchManga(manga.id, MangaPatch(favorite = true))
        assertTrue(restored.favorite)
        assertEquals(Category.Default.id, repository.categoriesForManga(manga.id).single().id)
    }

    @Test
    fun favoriteToggleIsAtomicAndUpdatesConflictMetadata() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(
            Manga(
                source = 1,
                url = "/atomic-favorite",
                title = "Before refresh",
                lastModifiedAt = 10,
                version = 2,
            ),
        )

        val favorited = repository.toggleMangaFavorite(manga.id, modifiedAt = 100)
        assertTrue(favorited.favorite)
        assertEquals(100L, favorited.favoriteModifiedAt)
        assertEquals(100L, favorited.lastModifiedAt)
        assertEquals(3L, favorited.version)
        assertEquals(Category.Default.id, repository.categoriesForManga(manga.id).single().id)

        val refreshed = repository.updateManga(manga.id) { latest ->
            latest.copy(title = "After refresh")
        }
        assertTrue(refreshed.favorite)
        assertEquals("After refresh", refreshed.title)

        val removed = repository.toggleMangaFavorite(manga.id, modifiedAt = 200)
        assertFalse(removed.favorite)
        assertEquals(200L, removed.favoriteModifiedAt)
        assertEquals(200L, removed.lastModifiedAt)
        assertEquals(4L, removed.version)
        assertTrue(repository.currentSnapshot.mangaCategories.none { it.mangaId == manga.id })
    }

    @Test
    fun newlyFavoritedMangaUsesTheConfiguredDefaultCategory() = runTest {
        val repository = ShinsouRepository()
        val category = repository.upsertCategory(Category(name = "Reading", sort = 1))
        repository.updateSettings {
            it.copy(library = LibrarySettings(defaultCategoryId = category.id))
        }

        val importedFavorite = repository.upsertManga(
            Manga(source = 1, favorite = true, url = "/one", title = "One"),
        )
        val unfavorited = repository.upsertManga(Manga(source = 1, url = "/two", title = "Two"))
        repository.patchManga(unfavorited.id, MangaPatch(favorite = true))

        assertEquals(category.id, repository.categoriesForManga(importedFavorite.id).single().id)
        assertEquals(category.id, repository.categoriesForManga(unfavorited.id).single().id)
    }

    @Test
    fun mangaCanBelongToMultipleRealCategoriesWithoutDefault() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1, favorite = true, url = "/m", title = "M"))
        val later = repository.upsertCategory(Category(name = "Later", sort = 20))
        val reading = repository.upsertCategory(Category(name = "Reading", sort = 10))

        repository.setMangaCategories(manga.id, setOf(Category.Default.id, later.id, reading.id))

        assertEquals(listOf(reading.id, later.id), repository.categoriesForManga(manga.id).map { it.id })
        assertEquals(
            listOf(reading.id, later.id),
            repository.currentSnapshot.mangaCategories.filter { it.mangaId == manga.id }.map { it.categoryId },
        )
        assertEquals(setOf(reading.id, later.id), repository.libraryItems().mapTo(linkedSetOf()) { it.libraryManga.category })
    }

    @Test
    fun alwaysAskSentinelFallsBackToDefaultUntilPickerSaves() = runTest {
        val repository = ShinsouRepository()
        repository.upsertCategory(Category(name = "Reading"))
        repository.updateSettings {
            it.copy(library = LibrarySettings(defaultCategoryId = ALWAYS_ASK_CATEGORY_ID))
        }
        val manga = repository.upsertManga(Manga(source = 1, url = "/m", title = "M"))

        repository.patchManga(manga.id, MangaPatch(favorite = true))

        assertTrue(repository.manga(manga.id)?.favorite == true)
        assertEquals(Category.Default.id, repository.categoriesForManga(manga.id).single().id)
    }

    @Test
    fun categoryNamesAreNormalizedAndUnique() = runTest {
        val repository = ShinsouRepository()

        val saved = repository.upsertCategory(Category(name = "  Reading  "))
        assertEquals("Reading", saved.name)
        assertFailsWith<RepositoryConstraintException> {
            repository.upsertCategory(Category(name = "reading"))
        }
        assertFailsWith<RepositoryConstraintException> {
            repository.upsertCategory(Category(name = "   "))
        }
    }

    @Test
    fun createCategoryAssignsOrderAndRejectsReservedOrDuplicateNames() = runTest {
        val repository = ShinsouRepository()

        val reading = repository.createCategory("  Reading  ")
        val later = repository.createCategory("Later")

        assertTrue(reading.id > Category.Default.id)
        assertEquals("Reading", reading.name)
        assertEquals(Category.Default.sort + 1, reading.sort)
        assertEquals(reading.sort + 1, later.sort)

        val snapshotBeforeFailures = repository.currentSnapshot
        assertFailsWith<RepositoryConstraintException> { repository.createCategory("default") }
        assertFailsWith<RepositoryConstraintException> { repository.createCategory("READING") }
        assertFailsWith<RepositoryConstraintException> { repository.createCategory("   ") }
        assertEquals(snapshotBeforeFailures, repository.currentSnapshot)
    }

    @Test
    fun categoriesCanBeRenamedAndReorderedWithoutChangingTheirIdentity() = runTest {
        val repository = ShinsouRepository()
        val reading = repository.createCategory("Reading")
        val later = repository.createCategory("Later")

        val renamed = repository.renameCategory(reading.id, "  Currently reading  ")
        repository.reorderCategories(listOf(Category.Default.id, later.id, reading.id))

        assertEquals(reading.id, renamed.id)
        assertEquals("Currently reading", renamed.name)
        assertEquals(
            listOf(Category.Default.id, later.id, reading.id),
            repository.currentSnapshot.categories.sortedBy { it.sort }.map { it.id },
        )

        val snapshotBeforeFailures = repository.currentSnapshot
        assertFailsWith<RepositoryConstraintException> { repository.renameCategory(reading.id, "LATER") }
        assertFailsWith<RepositoryConstraintException> { repository.renameCategory(reading.id, "   ") }
        assertFailsWith<RepositoryConstraintException> { repository.renameCategory(Category.Default.id, "Renamed") }
        assertEquals(snapshotBeforeFailures, repository.currentSnapshot)
    }

    @Test
    fun libraryItemsExposeBoundTrackersForFiltering() = runTest {
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 1, favorite = true, url = "/m", title = "M"))
        repository.upsertTrack(Track(mangaId = manga.id, trackerId = TrackerIds.MY_ANIME_LIST))

        assertEquals(setOf(TrackerIds.MY_ANIME_LIST), repository.libraryItems().single().trackerIds)
    }

    @Test
    fun constraintsRejectDanglingReferencesAndInvalidSnapshots() = runTest {
        val repository = ShinsouRepository()

        assertFailsWith<RepositoryConstraintException> {
            repository.upsertChapter(Chapter(mangaId = 404, name = "Missing"))
        }
        assertFailsWith<SnapshotValidationException> {
            ShinsouRepository.decodeSnapshot(
                ShinsouRepository.encodeSnapshot(AppSnapshot()).replace(
                    "\"schemaVersion\":1",
                    "\"schemaVersion\":999",
                ),
            )
        }
        assertNotNull(repository.snapshot.value.categories.singleOrNull { it.id == Category.Default.id })
        assertNull(repository.manga(404))
        assertFalse(repository.snapshot.value.settings.security.incognitoMode)
    }

    @Test
    fun persistenceFailureIsReportedByFlushWithoutBlockingMemoryState() = runTest {
        val repository = ShinsouRepository(persist = { error("disk full") })

        repository.upsertManga(Manga(source = 1, url = "/m", title = "M"))

        assertFailsWith<IllegalStateException> {
            repository.flushPersistence()
        }

        assertEquals("M", repository.snapshot.value.mangas.single().title)
        assertEquals(1, repository.snapshot.value.revision)
        assertTrue(repository.persistenceFailure.value is IllegalStateException)
        runCatching { repository.closePersistence() }
    }

    @Test
    fun mutationsPublishBeforeDebouncedPersistenceAndFlushWritesOnlyLatestState() = runTest {
        val persisted = mutableListOf<String>()
        val repository = ShinsouRepository(persist = persisted::add)

        repository.upsertManga(Manga(source = 1, url = "/one", title = "One"))
        repository.upsertManga(Manga(source = 1, url = "/two", title = "Two"))

        assertEquals(2, repository.currentSnapshot.mangas.size)
        assertTrue(persisted.isEmpty())

        repository.flushPersistence()

        assertEquals(1, persisted.size)
        assertEquals(repository.currentSnapshot, ShinsouRepository.decodeSnapshot(persisted.single()))
        repository.closePersistence()
    }

    @Test
    fun mutationAfterCloseFailsBeforePublishingMemoryState() = runTest {
        val repository = ShinsouRepository(persist = {})
        repository.closePersistence()

        assertFailsWith<IllegalStateException> {
            repository.upsertManga(Manga(source = 1, url = "/late", title = "Late"))
        }

        assertTrue(repository.currentSnapshot.mangas.isEmpty())
        assertEquals(0L, repository.currentSnapshot.revision)
    }

    @Test
    fun invalidLocalMutationIsRejectedBeforeItCanPoisonPersistence() = runTest {
        val persisted = mutableListOf<String>()
        val repository = ShinsouRepository(persist = persisted::add)

        assertFailsWith<RepositoryConstraintException> {
            repository.updateSettings {
                it.copy(library = it.library.copy(portraitColumns = 0))
            }
        }
        assertEquals(0L, repository.currentSnapshot.revision)

        val manga = repository.upsertManga(Manga(source = 1, url = "/m", title = "M"))
        val chapter = repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c", name = "C"))
        assertFailsWith<RepositoryConstraintException> {
            repository.upsertDownload(
                dev.shinsou.kmp.domain.model.DownloadQueueItem(
                    id = dev.shinsou.kmp.domain.model.DownloadQueueItem.id(manga.id, chapter.id),
                    mangaId = manga.id,
                    chapterId = chapter.id,
                    progress = 1.5,
                ),
            )
        }

        repository.flushPersistence()
        assertTrue(repository.currentSnapshot.downloadQueue.isEmpty())
        assertEquals(repository.currentSnapshot, ShinsouRepository.decodeSnapshot(persisted.single()))
        repository.closePersistence()
    }

    @Test
    fun closeSerializesWithMutationAndCompletesFromACancelledCaller() = runTest {
        val persistEntered = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        val repository = ShinsouRepository(
            persist = {
                runBlocking {
                    persistEntered.complete(Unit)
                    releasePersist.await()
                }
            },
        )
        repository.upsertManga(Manga(source = 1, url = "/before", title = "Before"))

        val firstClose = async { repository.closePersistence() }
        persistEntered.await()
        val lateMutation = async {
            runCatching { repository.upsertManga(Manga(source = 1, url = "/late", title = "Late")) }
        }
        val cancellationArmed = CompletableDeferred<Unit>()
        var cancelledCloseReturned = false
        val cancelledClose = launch {
            try {
                cancellationArmed.complete(Unit)
                awaitCancellation()
            } finally {
                repository.closePersistence()
                cancelledCloseReturned = true
            }
        }
        cancellationArmed.await()
        cancelledClose.cancel()
        runCurrent()
        assertFalse(lateMutation.isCompleted)

        releasePersist.complete(Unit)
        firstClose.await()
        cancelledClose.join()

        assertTrue(cancelledCloseReturned)
        assertTrue(lateMutation.await().exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("Before"), repository.currentSnapshot.mangas.map { it.title })
    }

    @Test
    fun debounceWaitsForQuietWindowAndPublishesLatestRevision() = runTest {
        val updates = Channel<AppSnapshot>(Channel.CONFLATED)
        val result = async {
            awaitDebouncedSnapshot(
                first = AppSnapshot(revision = 1),
                updates = updates,
                quietMillis = 350,
                maximumLatencyMillis = 2_000,
            )
        }
        runCurrent()

        advanceTimeBy(300)
        updates.send(AppSnapshot(revision = 2))
        runCurrent()
        advanceTimeBy(349)
        runCurrent()
        assertFalse(result.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2L, result.await().revision)
        updates.close()
    }

    @Test
    fun debouncePersistsAtMaximumLatencyDuringContinuousUpdates() = runTest {
        val updates = Channel<AppSnapshot>(Channel.CONFLATED)
        val result = async {
            awaitDebouncedSnapshot(
                first = AppSnapshot(revision = 1),
                updates = updates,
                quietMillis = 350,
                maximumLatencyMillis = 2_000,
            )
        }
        runCurrent()

        for (revision in 2L..7L) {
            advanceTimeBy(300)
            updates.send(AppSnapshot(revision = revision))
            runCurrent()
        }
        assertFalse(result.isCompleted)

        advanceTimeBy(200)
        runCurrent()
        assertEquals(7L, result.await().revision)
        updates.close()
    }

    @Test
    fun revisionExhaustionIsRejectedBeforePublishing() = runTest {
        val repository = ShinsouRepository(initial = AppSnapshot(revision = Long.MAX_VALUE - 2L))

        assertFailsWith<RepositoryConstraintException> {
            repository.upsertManga(Manga(source = 1, url = "/overflow", title = "Overflow"))
        }

        assertEquals(Long.MAX_VALUE - 2L, repository.currentSnapshot.revision)
        assertTrue(repository.currentSnapshot.mangas.isEmpty())
        assertFailsWith<SnapshotValidationException> {
            AppSnapshot(revision = Long.MAX_VALUE).validate()
        }
    }

    private fun testTextLocator(offset: Int): ReadingLocator.Text {
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        return ReadingLocator.Text(
            schemaVersion = 1,
            scope = ReadingScope(
                schemaVersion = 1,
                publicationId = publication,
                acquisitionId = "22222222-2222-4222-8222-222222222222",
                unitId = UnitKey(publication, "33333333-3333-4333-8333-333333333333"),
                contentRevision = 1,
            ),
            resourceId = "body",
            blockId = "paragraph-1",
            offset = offset,
        )
    }
}
