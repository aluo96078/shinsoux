package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ChapterPatch
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.persistence.HotDelta
import dev.shinsou.kmp.domain.persistence.Ordered
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * C3 (docs/architecture/0007 §6.3.5-6.3.7) after its adversarial review: the paths on which the
 * JSON and the hot rows could part and lose data, each pinned.
 */
class HotStateRecoveryTest {
    /** Fix 1: the restore's rows commit with the JSON of their cold part, not paired with the old one. */
    @Test
    fun aRestoreCutShortBeforeItsJsonIsWrittenIsReplayed() = runTest {
        listOf("overlapping ids" to overlappingRestore(), "disjoint ids" to disjointRestore()).forEach { (case, restored) ->
            val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
            val store = FakeHotStateStore()
            val opened = open(file, store)
            // The writer never gets to run: the process dies right after the host transaction.
            val repository = ShinsouRepository(opened.snapshot, opened.storage, StandardTestDispatcher(testScheduler))
            val before = file.json

            val committed = repository.commitSnapshotMutationAtomically(restored, SnapshotReplacementOrigin.DIRECT) { _, _, commitSyncJournal ->
                store.hostTransaction { commitSyncJournal() }
            }.snapshot
            assertEquals(before, file.json, case)

            val reopened = open(file, store)

            assertEquals(HotStateOpening.RESTORE_REPLAYED, reopened.opening, case)
            assertEquals(AppSnapshotJson.encode(committed), AppSnapshotJson.encode(reopened.snapshot), case)
            assertEquals(committed.hotState(), store.rows, case)
            assertEquals(committed, file.load().withHotStateMarker(null), case)
            assertNull(store.journal, case)
            assertNull(store.meta?.journaledJsonRevision, case)
            val again = open(file, store)
            assertEquals(HotStateOpening.SQL_CURRENT, again.opening, case)
            assertEquals(committed, again.snapshot, case)
        }
    }

    @Test
    fun aRestoreWhoseJsonWasWrittenLeavesNoJournal() = runTest {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val store = FakeHotStateStore()
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage, StandardTestDispatcher(testScheduler))
        val failed = runCatching {
            repository.commitSnapshotMutationAtomically(disjointRestore(), SnapshotReplacementOrigin.DIRECT) { _, _, commitSyncJournal ->
                store.hostTransaction {
                    commitSyncJournal()
                    error("the content transaction failed half-way")
                }
            }
        }
        assertTrue(failed.isFailure)
        assertNull(store.journal, "rolled back with the host")
        assertEquals(HotStateOpening.SQL_CURRENT, open(file, store).opening)

        val committed = repository.commitSnapshotMutationAtomically(disjointRestore(), SnapshotReplacementOrigin.DIRECT) { _, _, commitSyncJournal ->
            store.hostTransaction { commitSyncJournal() }
        }.snapshot
        repository.markChapterProgress(201, lastPageRead = 2, read = false, readAt = 9_000L)
        repository.flushPersistence()

        assertNull(store.journal)
        val reopened = open(file, store)
        assertEquals(HotStateOpening.SQL_CURRENT, reopened.opening)
        assertEquals(repository.currentSnapshot, reopened.snapshot)
        assertEquals(committed.mangas, reopened.snapshot.mangas)
    }

    /** Fix 2: a JSON missing, quarantined or restored without its database never clears the rows. */
    @Test
    fun aMissingOrQuarantinedJsonNeverClearsTheRows() = runTest {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val store = FakeHotStateStore()
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage, StandardTestDispatcher(testScheduler))
        repository.markChapterProgress(4, lastPageRead = 3, read = false, readAt = 5_000L)
        repository.flushPersistence()
        val latest = repository.currentSnapshot
        val paired = checkNotNull(file.json)
        val rows = store.rows
        val meta = store.meta

        // Deleted, or quarantined as undecodable: the platform reads no JSON either way.
        file.json = null
        val missing = open(file, store)

        assertEquals(HotStateOpening.ROWS_KEPT, missing.opening)
        assertEquals(AppSnapshot(), missing.snapshot)
        assertFalse(missing.storage.keepsHotApart)
        assertEquals(rows, store.rows)
        assertEquals(meta, store.meta)

        // The app goes on with a fresh library; its JSON says the rows are kept, and so the next
        // start (the off-switch set or not) keeps them too.
        val fresh = ShinsouRepository(missing.snapshot, missing.storage, StandardTestDispatcher(testScheduler))
        fresh.upsertManga(Manga(source = 1, url = "/new", title = "New"))
        fresh.closePersistence()
        assertEquals(
            setOf(HOT_STATE_MARKER_PREFIX + "kept:" + checkNotNull(meta).token),
            file.load().contentAuthorityProjectionMarkers,
        )
        assertEquals(HotStateOpening.ROWS_KEPT, open(file, store).opening)
        assertEquals(HotStateOpening.ROWS_KEPT, openSnapshotPersistence(file.read(), file, store, hotStateEnabled = false).opening)
        assertEquals(rows, store.rows)
        assertEquals(meta, store.meta)

        // The JSON put back (from the quarantine, or a backup with its token): its rows go with it.
        file.json = paired
        val back = open(file, store)
        assertEquals(HotStateOpening.SQL_CURRENT, back.opening)
        assertEquals(latest, back.snapshot)

        // A fresh install has neither, and is migrated as before.
        assertEquals(HotStateOpening.MIGRATED, open(FakeSnapshotFile(), FakeHotStateStore()).opening)
    }

    /** Fix 3: every history of a chapter stays valid on a hot write, so a restart takes the rows. */
    @Test
    fun twoHistoriesOfOneChapterStayValidAcrossARestart() = runTest {
        val library = hotLibrary().let { library ->
            // History 1 is chapter 4's first; this second one has a page count for page 4.
            library.copy(histories = library.histories + History(id = 10, chapterId = 4, lastRead = 10, lastPageCount = 6)).validate()
        }
        val file = FakeSnapshotFile(AppSnapshotJson.encode(library))
        val store = FakeHotStateStore()
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage, StandardTestDispatcher(testScheduler))

        repository.markChapterProgress(4, lastPageRead = 8, read = false, readAt = 5_000L)
        // Progress of other chapters, which a rebuild from the JSON would revert.
        repository.markChapterProgress(6, lastPageRead = 1, read = true, readAt = 5_001L)
        repository.patchChapter(5, ChapterPatch(bookmark = false))
        repository.flushPersistence()
        val latest = repository.currentSnapshot
        latest.validate()
        assertNull(latest.histories.single { it.id == 10L }.lastPageCount)

        val reopened = open(file, store)
        assertEquals(HotStateOpening.SQL_CURRENT, reopened.opening)
        assertEquals(latest, reopened.snapshot)

        // An explicit page edit, and a chapter written again, likewise.
        val again = ShinsouRepository(reopened.snapshot, reopened.storage, StandardTestDispatcher(testScheduler))
        again.markChapterProgress(4, lastPageRead = 1, read = false, readAt = 6_000L, lastPageCount = 3)
        again.recordHistory(4, lastRead = 6_001L)
        assertEquals(3, again.currentSnapshot.histories.first { it.chapterId == 4L }.lastPageCount)
        again.patchChapter(4, ChapterPatch(lastPageRead = 5))
        again.currentSnapshot.validate()
        again.markChapterProgress(4, lastPageRead = 1, read = false, readAt = 7_000L, lastPageCount = 3)
        again.upsertChapter(again.currentSnapshot.chapters.single { it.id == 4L }.copy(lastPageRead = 9))
        again.currentSnapshot.validate()
        again.markChapterProgress(4, lastPageRead = 1, read = false, readAt = 8_000L, lastPageCount = 3)
        again.upsertChapters(listOf(again.currentSnapshot.chapters.single { it.id == 4L }.copy(lastPageRead = 4)))
        again.currentSnapshot.validate()
        again.flushPersistence()
        assertEquals(again.currentSnapshot, open(file, store).snapshot)
    }

    /** Fix 3, on load: rows a build before the fix wrote are repaired, or kept, never rebuilt over. */
    @Test
    fun rowsThatDoNotFitAreRepairedOrKeptNeverRebuiltOver() {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val store = FakeHotStateStore()
        open(file, store)
        // A second history of chapter 4 (at page 4) whose page count no longer holds the page.
        store.apply(HotDelta(histories = listOf(Ordered(store.rows.histories.size, History(id = 10, chapterId = 4, lastRead = 10, lastPageCount = 2)))))
        val progress = store.rows.chapterProgress

        val repaired = open(file, store)

        assertEquals(HotStateOpening.SQL_CURRENT, repaired.opening)
        assertNull(repaired.snapshot.histories.single { it.id == 10L }.lastPageCount)
        assertEquals(repaired.snapshot.hotState(), store.rows)
        assertEquals(progress, store.rows.chapterProgress)

        // A row nothing repairs: kept as it is, and the JSON is used alone.
        val broken = DownloadQueueItem(id = DownloadQueueItem.id(3, 12), mangaId = 3, chapterId = 12, progress = 2.0)
        store.apply(HotDelta(downloads = listOf(Ordered(store.rows.downloads.size, broken))))
        val rows = store.rows
        val meta = store.meta

        val kept = open(file, store)

        assertEquals(HotStateOpening.ROWS_KEPT, kept.opening)
        assertEquals(file.load().withHotStateMarker(null), kept.snapshot)
        assertEquals(rows, store.rows)
        assertEquals(meta, store.meta)
    }

    /** Fix 4: a JSON whose commit failed is never paired with the older rows, even after a second failure. */
    @Test
    fun aJsonWhoseCommitFailedIsNeverPairedWithOlderRows() = runTest {
        val crashes = Crashes()
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()), crashes)
        val store = FakeHotStateStore(crashes)
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage, StandardTestDispatcher(testScheduler))
        repository.markChapterProgress(4, lastPageRead = 3, read = false, readAt = 5_000L)
        repository.flushPersistence()

        // The JSON is swapped in, the transaction after it fails; the process lives on.
        repository.markChapterProgress(6, lastPageRead = 2, read = true, readAt = 6_000L)
        rename(repository, 0)
        store.failNextCommit = IllegalStateException("database is locked")
        assertFailsWith<IllegalStateException> { repository.flushPersistence() }
        val onDisk = repository.currentSnapshot
        assertEquals(onDisk, file.load().withHotStateMarker(null))

        // The next whole write dies after recording its pending revision, before its file.
        repository.markChapterProgress(8, lastPageRead = 1, read = false, readAt = 7_000L)
        rename(repository, 1)
        crashes.at = crashes.steps + 1
        assertFailsWith<Crash> { repository.flushPersistence() }
        crashes.at = null

        val reopened = open(file, store)

        assertEquals(HotStateOpening.REBUILT_FROM_JSON, reopened.opening)
        assertEquals(onDisk, reopened.snapshot)
        assertEquals(onDisk.hotState(), store.rows)
    }

    @Test
    fun aWriteAfterFailedCommitsStartsAConsistentBaseline() = runTest {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val store = FakeHotStateStore()
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage, StandardTestDispatcher(testScheduler))
        repeat(2) { attempt ->
            repository.markChapterProgress(4, lastPageRead = attempt + 2, read = false, readAt = 5_000L + attempt)
            rename(repository, attempt)
            store.failNextCommit = IllegalStateException("database is locked")
            assertFailsWith<IllegalStateException> { repository.flushPersistence() }
        }
        val saves = file.saves

        // A change of hot rows only: written whole, every row in place, under a pairing of its own.
        repository.markChapterProgress(6, lastPageRead = 4, read = false, readAt = 6_000L)
        repository.flushPersistence()

        assertEquals(saves + 1, file.saves)
        assertEquals(repository.currentSnapshot.hotState(), store.rows)
        val reopened = open(file, store)
        assertEquals(HotStateOpening.SQL_CURRENT, reopened.opening)
        assertEquals(repository.currentSnapshot, reopened.snapshot)
    }

    /** Fix 5: hot rows reach the JSON (what Android backs up) without waiting for another change. */
    @Test
    fun hotRowsReachTheJsonSoonWithoutAnotherChange() = runTest {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val store = FakeHotStateStore()
        val opened = open(file, store)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = ShinsouRepository(opened.snapshot, opened.storage, dispatcher)
        val saves = file.saves

        repository.markChapterProgress(4, lastPageRead = 3, read = false, readAt = 5_000L)
        repository.flushPersistence()
        assertEquals(saves, file.saves)
        advanceTimeBy(HotStateSnapshotStorage.JSON_REFRESH_INTERVAL + 1.seconds)
        runCurrent()
        assertEquals(saves + 1, file.saves)
        assertEquals(repository.currentSnapshot, file.load().withHotStateMarker(null))

        // A later start finds rows the JSON lacks (the process died before the checkpoint).
        repository.markChapterProgress(4, lastPageRead = 5, read = false, readAt = 6_000L)
        repository.flushPersistence()
        val latest = repository.currentSnapshot
        val restarted = open(file, store)
        assertEquals(HotStateOpening.SQL_CURRENT, restarted.opening)
        val next = ShinsouRepository(restarted.snapshot, restarted.storage, dispatcher)
        val before = file.saves
        advanceTimeBy(HotStateSnapshotStorage.CHECKPOINT_AFTER_OPEN + 1.seconds)
        runCurrent()

        assertEquals(before + 1, file.saves)
        assertEquals(latest, file.load().withHotStateMarker(null))
        next.closePersistence()
    }

    /** Fix 6: a copy of the JSON rebuilt elsewhere, or brought back, never pairs with these rows. */
    @Test
    fun aCopyOfTheJsonNeverPairsWithTheRowsOfAnother() = runTest {
        val fileA = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val storeA = FakeHotStateStore()
        val a = open(fileA, storeA)
        val deviceA = ShinsouRepository(a.snapshot, a.storage, StandardTestDispatcher(testScheduler))
        val tokens = mutableListOf(checkNotNull(storeA.meta).token)
        repeat(2) { page ->
            deviceA.markChapterProgress(4, lastPageRead = page + 2, read = false, readAt = 5_000L + page)
            deviceA.flushPersistence(wholeSnapshot = true)
            tokens += checkNotNull(storeA.meta).token
        }
        assertEquals(tokens.size, tokens.toSet().size, "a new token for every whole write")

        // Device B: the JSON restored with a database of its own; both go on and write.
        val fileB = FakeSnapshotFile(fileA.json)
        val storeB = FakeHotStateStore()
        val b = open(fileB, storeB)
        assertEquals(HotStateOpening.REBUILT_FROM_JSON, b.opening)
        assertNotEquals(checkNotNull(storeA.meta).token, checkNotNull(storeB.meta).token)
        val deviceB = ShinsouRepository(b.snapshot, b.storage, StandardTestDispatcher(testScheduler))
        deviceB.markChapterProgress(6, lastPageRead = 9, read = true, readAt = 8_000L)
        deviceB.flushPersistence(wholeSnapshot = true)
        deviceA.markChapterProgress(8, lastPageRead = 7, read = true, readAt = 8_000L)
        deviceA.flushPersistence()

        // B's JSON brought to A, at the very revision A's rows are at: they do not go with it.
        assertEquals(deviceA.currentSnapshot.revision, deviceB.currentSnapshot.revision)
        fileA.json = fileB.json
        val mixed = open(fileA, storeA)

        assertEquals(HotStateOpening.REBUILT_FROM_JSON, mixed.opening)
        assertEquals(deviceB.currentSnapshot, mixed.snapshot)
    }

    private fun open(file: FakeSnapshotFile, store: FakeHotStateStore): OpenedSnapshot =
        openSnapshotPersistence(file.read(), file, store, newToken = { "token-${tokens++}" })

    private var tokens = 0

    private suspend fun rename(repository: ShinsouRepository, index: Int) {
        val manga = repository.currentSnapshot.mangas[index]
        repository.upsertManga(manga.copy(title = manga.title + "'"))
    }

    /** The library a backup brings back, with the chapter ids of the one there but other titles and progress. */
    private fun overlappingRestore(): AppSnapshot = hotLibrary().let { library ->
        library.copy(
            revision = 1,
            mangas = library.mangas.map { it.copy(url = it.url + "/restored", title = it.title + " (restored)") },
            chapters = library.chapters.map { it.copy(url = it.url + "/restored", read = it.id % 2 == 0L, bookmark = it.id == 3L, lastPageRead = (it.id % 5).toInt()) },
            histories = listOf(History(id = 1, chapterId = 2, lastRead = 50), History(id = 2, chapterId = 3, lastRead = 40, timeRead = 9)),
            downloadQueue = listOf(DownloadQueueItem(id = DownloadQueueItem.id(2, 5), mangaId = 2, chapterId = 5)),
        ).validate()
    }

    /** A restored library none of whose ids the one there has. */
    private fun disjointRestore(): AppSnapshot {
        val mangas = listOf(21L, 22L).map { Manga(id = it, source = 2, url = "/r$it", title = "R$it", favorite = true) }
        val chapters = (201L..206L).map { id ->
            Chapter(id = id, mangaId = if (id <= 203L) 21L else 22L, url = "/rc$id", name = "RC$id", read = id == 202L, lastPageRead = (id - 200).toInt())
        }
        return AppSnapshot(
            revision = 1,
            mangas = mangas,
            chapters = chapters,
            histories = listOf(History(id = 7, chapterId = 204, lastRead = 70, timeRead = 3)),
            downloadQueue = listOf(DownloadQueueItem(id = DownloadQueueItem.id(22, 205), mangaId = 22, chapterId = 205)),
        ).validate()
    }
}
