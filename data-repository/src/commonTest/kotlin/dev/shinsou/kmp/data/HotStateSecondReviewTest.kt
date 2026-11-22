package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.persistence.HotDelta
import dev.shinsou.kmp.persistence.Ordered
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C3 (docs/architecture/0007 §6.3.5-6.3.7) after its second review: a whole write cut short at
 * each of its steps, the first whole write after a restore, and rows kept apart across restarts.
 * A crash is the process ending at a step of the fakes; a reopen is the next start.
 */
class HotStateSecondReviewTest {
    /** Review 1: the pending revision, the file, then the rows and the pairing; and no crash. */
    @Test
    fun aWholeWriteCutShortAtAnyStepLosesNothing() = runTest {
        (0..WHOLE_WRITE_STEPS).forEach { step ->
            val context = "crash at step $step"
            val crashes = Crashes()
            val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()), crashes)
            val store = FakeHotStateStore(crashes)
            val opened = open(file, store)
            val repository = ShinsouRepository(opened.snapshot, opened.storage, StandardTestDispatcher(testScheduler))
            repository.markChapterProgress(4, lastPageRead = 3, read = false, readAt = 5_000L)
            repository.flushPersistence()
            val durable = repository.currentSnapshot
            val before = checkNotNull(store.meta)
            // A hot change and a cold one: written whole.
            repository.markChapterProgress(6, lastPageRead = 2, read = true, readAt = 6_000L)
            rename(repository, 0)
            val latest = repository.currentSnapshot

            crashes.at = crashes.steps + step
            val failure = runCatching { repository.flushPersistence() }.exceptionOrNull()
            crashes.at = null

            if (step < WHOLE_WRITE_STEPS) assertTrue(failure is Crash, context) else assertNull(failure, context)
            if (step == 2) {
                // The file went out under a token of its own; the rows still record the old one,
                // and the revision of that file as pending.
                assertNotEquals(setOf(HOT_STATE_MARKER_PREFIX + "v1:" + before.token), file.load().contentAuthorityProjectionMarkers, context)
                assertEquals(before.copy(pendingJsonRevision = latest.revision), store.meta, context)
            }
            val reopened = open(file, store)
            val expected = if (step < 2) durable else latest
            val opening = if (step == 2) HotStateOpening.REBUILT_FROM_JSON else HotStateOpening.SQL_CURRENT
            assertEquals(opening, reopened.opening, context)
            assertEquals(expected, reopened.snapshot, context)
            assertEquals(expected.hotState(), store.rows, context)
            assertEquals(HotStateOpening.SQL_CURRENT, open(file, store).opening, context)
            assertEquals(expected, open(file, store).snapshot, context)
        }
    }

    /** Review 2: the first whole write after a restore, cut short at each of its steps. */
    @Test
    fun theFirstWholeWriteAfterARestoreLosesItAtNoStep() = runTest {
        (0..WHOLE_WRITE_STEPS).forEach { step -> restoreThenWholeWrite(step, failedCommitFirst = false) }
    }

    /**
     * Review 2: a whole write whose commit failed left a file the rows do not pair with; a restore
     * then journaled its JSON, and the process died. That file is older than the journal: the
     * journal is replayed over it, never rebuilt from it (which cleared the rows and the journal).
     */
    @Test
    fun aRestoreAfterAFailedCommitIsReplayedOverTheFileThatCommitLeft() = runTest {
        (0..WHOLE_WRITE_STEPS).forEach { step -> restoreThenWholeWrite(step, failedCommitFirst = true) }
    }

    @Test
    fun aJournalIsReplayedWhenTheJsonIsMissing() = runTest {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val store = FakeHotStateStore()
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage, StandardTestDispatcher(testScheduler))
        val restored = restore(repository, store)

        // Quarantined, or deleted, before the restore reached it.
        file.json = null
        val reopened = open(file, store)

        assertEquals(HotStateOpening.RESTORE_REPLAYED, reopened.opening)
        assertEquals(AppSnapshotJson.encode(restored), AppSnapshotJson.encode(reopened.snapshot))
        assertEquals(restored, file.load().withHotStateMarker(null))
        assertNull(store.journal)
        assertEquals(HotStateOpening.SQL_CURRENT, open(file, store).opening)
    }

    /** Review 3: a quarantined JSON, rows kept over several runs, then the JSON put back. */
    @Test
    fun keptRowsWaitOverRestartsAndGoWithTheirJsonPutBack() = runTest {
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

        file.json = null
        val kept = open(file, store)
        assertEquals(HotStateOpening.ROWS_KEPT, kept.opening)
        val session = ShinsouRepository(kept.snapshot, kept.storage, StandardTestDispatcher(testScheduler))
        session.upsertManga(Manga(source = 1, url = "/new", title = "New"))
        session.closePersistence()
        val written = session.currentSnapshot

        repeat(RESTARTS) { restart ->
            val context = "restart $restart"
            val again = open(file, store)
            assertEquals(HotStateOpening.ROWS_KEPT, again.opening, context)
            assertEquals(written, again.snapshot, context)
            ShinsouRepository(again.snapshot, again.storage, StandardTestDispatcher(testScheduler)).closePersistence()
            assertEquals(rows, store.rows, context)
            assertEquals(meta, store.meta, context)
            assertEquals(written, file.load().withHotStateMarker(null), context)
        }
        // Nothing of the paired JSON is on disk to copy: no copy is taken from the kept one.
        assertNull(file.copies[HOT_STATE_KEPT_SUFFIX])

        file.json = paired
        val back = open(file, store)
        assertEquals(HotStateOpening.SQL_CURRENT, back.opening)
        assertEquals(latest, back.snapshot)
        assertEquals(latest.hotState(), store.rows)
    }

    /** Review 3: rows that do not fit their JSON keep it aside, however many runs write kept JSONs. */
    @Test
    fun rowsThatDoNotFitKeepTheirJsonAsideAndGoWithItOnceRepaired() = runTest {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val store = FakeHotStateStore()
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage, StandardTestDispatcher(testScheduler))
        repository.markChapterProgress(4, lastPageRead = 3, read = false, readAt = 5_000L)
        repository.flushPersistence()
        val latest = repository.currentSnapshot
        val paired = checkNotNull(file.json)
        // A row nothing repairs (a build with a bug wrote it).
        val broken = DownloadQueueItem(id = DownloadQueueItem.id(3, 12), mangaId = 3, chapterId = 12, progress = 2.0)
        store.apply(HotDelta(downloads = listOf(Ordered(store.rows.downloads.size, broken))))
        val rows = store.rows
        val meta = store.meta

        repeat(RESTARTS) { restart ->
            val context = "restart $restart"
            val kept = open(file, store)
            assertEquals(HotStateOpening.ROWS_KEPT, kept.opening, context)
            assertEquals(paired, file.copies[HOT_STATE_KEPT_SUFFIX], context)
            val session = ShinsouRepository(kept.snapshot, kept.storage, StandardTestDispatcher(testScheduler))
            session.upsertManga(Manga(source = 1, url = "/new$restart", title = "New $restart"))
            session.closePersistence()
            assertEquals(session.currentSnapshot, file.load().withHotStateMarker(null), context)
            assertEquals(rows, store.rows, context)
            assertEquals(meta, store.meta, context)
        }
        assertEquals(paired, file.copies[HOT_STATE_KEPT_SUFFIX])

        // A version that repairs the row, with the copy put back: the rows go with it again.
        store.apply(HotDelta(removedDownloads = setOf(broken.id)))
        file.json = file.copies[HOT_STATE_KEPT_SUFFIX]
        val back = open(file, store)
        assertEquals(HotStateOpening.SQL_CURRENT, back.opening)
        assertEquals(latest, back.snapshot)
    }

    /**
     * A restore committed (journaled), after a whole write whose commit failed when
     * [failedCommitFirst]; then the first whole write after it, crashing at [step] (none at
     * [WHOLE_WRITE_STEPS]), and the next start.
     */
    private suspend fun TestScope.restoreThenWholeWrite(step: Int, failedCommitFirst: Boolean) {
        val context = "crash at step $step, failed commit first: $failedCommitFirst"
        val crashes = Crashes()
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()), crashes)
        val store = FakeHotStateStore(crashes)
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage, StandardTestDispatcher(testScheduler))
        if (failedCommitFirst) {
            repository.markChapterProgress(4, lastPageRead = 3, read = false, readAt = 5_000L)
            rename(repository, 0)
            store.failNextCommit = IllegalStateException("database is locked")
            assertFailsWith<IllegalStateException>(context) { repository.flushPersistence() }
            assertEquals(repository.currentSnapshot, file.load().withHotStateMarker(null), context)
        }
        val restored = restore(repository, store)
        assertNotNull(store.journal, context)
        if (failedCommitFirst) {
            // The process could end here, before any whole write.
            val replayed = open(FakeSnapshotFile(file.json), store.copyOf())
            assertEquals(HotStateOpening.RESTORE_REPLAYED, replayed.opening, context)
            assertEquals(AppSnapshotJson.encode(restored), AppSnapshotJson.encode(replayed.snapshot), context)
        }
        repository.markChapterProgress(201, lastPageRead = 2, read = false, readAt = 9_000L)
        val latest = repository.currentSnapshot

        crashes.at = crashes.steps + step
        val failure = runCatching { repository.flushPersistence() }.exceptionOrNull()
        crashes.at = null
        if (step < WHOLE_WRITE_STEPS) assertTrue(failure is Crash, context) else assertNull(failure, context)

        val reopened = open(file, store)
        val expected = if (step < 2) restored else latest
        val opening = when {
            step < 2 -> HotStateOpening.RESTORE_REPLAYED
            step == 2 -> HotStateOpening.REBUILT_FROM_JSON
            else -> HotStateOpening.SQL_CURRENT
        }
        assertEquals(opening, reopened.opening, context)
        assertEquals(AppSnapshotJson.encode(expected), AppSnapshotJson.encode(reopened.snapshot), context)
        assertEquals(expected.hotState(), store.rows, context)
        assertNull(store.journal, context)
        assertEquals(expected, file.load().withHotStateMarker(null), context)
        val again = open(file, store)
        assertEquals(HotStateOpening.SQL_CURRENT, again.opening, context)
        assertEquals(expected, again.snapshot, context)
    }

    /** A content-backup v2 restore, committed with its host transaction. */
    private suspend fun restore(repository: ShinsouRepository, store: FakeHotStateStore): AppSnapshot =
        repository.commitSnapshotMutationAtomically(disjointRestore(), SnapshotReplacementOrigin.DIRECT) { _, _, commitSyncJournal ->
            store.hostTransaction { commitSyncJournal() }
        }.snapshot

    private fun open(file: FakeSnapshotFile, store: FakeHotStateStore): OpenedSnapshot =
        openSnapshotPersistence(file.read(), file, store, newToken = { "token-${tokens++}" })

    private var tokens = 0

    private suspend fun rename(repository: ShinsouRepository, index: Int) {
        val manga = repository.currentSnapshot.mangas[index]
        repository.upsertManga(manga.copy(title = manga.title + "'"))
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

    private companion object {
        /** A whole write's durable steps: the pending revision, the file, the rows with the pairing. */
        const val WHOLE_WRITE_STEPS = 3
        const val RESTARTS = 3
    }
}
