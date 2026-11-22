package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.persistence.ChapterProgress
import dev.shinsou.kmp.persistence.HotDelta
import dev.shinsou.kmp.persistence.HotRows
import dev.shinsou.kmp.persistence.HotState
import dev.shinsou.kmp.persistence.HotStatePairing
import dev.shinsou.kmp.persistence.Ordered
import dev.shinsou.kmp.persistence.progress
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/** C3-3 to C3-5 (docs/architecture/0007 §6.3.5-6.3.7): the JSON and the hot rows, together. */
class HotStatePersistenceTest {
    @Test
    fun theMigrationIsSafeToRerunAfterACrashAtAnyStep() {
        val original = hotLibrary()
        val legacy = AppSnapshotJson.encode(original)
        var step = 0
        do {
            val crashes = Crashes(at = step)
            val file = FakeSnapshotFile(legacy, crashes)
            val store = FakeHotStateStore(crashes)
            val crashed = try {
                open(file, store)
                false
            } catch (_: Crash) {
                // The process died at this step; it starts again on what was durable.
                true
            }
            crashes.at = null
            val context = "crash at step $step"
            val restarted = open(file, store)
            assertTrue(restarted.opening.keepsHotApart, context)
            assertEquals(AppSnapshotJson.encode(original), AppSnapshotJson.encode(restarted.snapshot), context)
            assertEquals(original.hotState(), store.rows, context)
            assertEquals(legacy, file.copy, context)
            val again = open(file, store)
            assertEquals(HotStateOpening.SQL_CURRENT, again.opening, context)
            assertEquals(original, again.snapshot, context)
            step++
        } while (crashed)
        // the pairing read, the copy, the rows, the pending revision, the file, the final pairing
        assertEquals(7, step)
    }

    @Test
    fun theFirstStartCopiesMarksAndPairsTheJson() {
        val original = hotLibrary()
        val file = FakeSnapshotFile(AppSnapshotJson.encode(original))
        val store = FakeHotStateStore()

        val opened = open(file, store)

        assertEquals(HotStateOpening.MIGRATED, opened.opening)
        assertEquals(original, opened.snapshot)
        val written = AppSnapshotJson.decode(checkNotNull(file.json))
        val token = checkNotNull(store.meta).token
        assertEquals(setOf("$HOT_STATE_MARKER_PREFIX" + "v1:$token"), written.contentAuthorityProjectionMarkers)
        assertEquals(HotStatePairing(jsonRevision = 7, token = token, hotRevision = 7), store.meta)
        // The marker is the storage's: never in the snapshot the app works with (or backs up).
        assertTrue(opened.snapshot.contentAuthorityProjectionMarkers.isEmpty())
    }

    @Test
    fun noCopyNoMigration() {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary())).apply { copyFails = true }
        val store = FakeHotStateStore()

        val opened = open(file, store)

        assertEquals(HotStateOpening.MIGRATION_FAILED, opened.opening)
        assertFalse(opened.storage.keepsHotApart)
        assertNull(store.meta)
        assertEquals(0, file.saves)
    }

    @Test
    fun aPageTurnOrADownloadedPageWritesRowsNotTheJson() = runTest {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val store = FakeHotStateStore()
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage)
        val saves = file.saves
        val download = repository.currentSnapshot.downloadQueue[1]

        repeat(PAGES) { page ->
            repository.markChapterProgress(4, lastPageRead = page, read = false, readAt = 2_000L + page, timeRead = 1)
            repository.setDownloadState(download.id, DownloadState.DOWNLOADING, progress = page % 10 / 9.0, downloadedPages = page % 10, totalPages = 9)
            if (page % 3 == 0) repository.flushPersistence()
        }
        repository.flushPersistence()

        assertEquals(saves, file.saves, "reading and downloading wrote the JSON")
        assertEquals(repository.currentSnapshot.hotState(), store.rows)
        assertEquals(repository.currentSnapshot.revision, store.meta?.hotRevision)

        // A cold change writes the JSON, and so does a lifecycle flush.
        repository.upsertManga(repository.currentSnapshot.mangas[0].copy(title = "Renamed"))
        repository.flushPersistence()
        assertEquals(saves + 1, file.saves)
        repository.markChapterProgress(4, lastPageRead = 1, read = true, readAt = 9_000L)
        repository.flushPersistence(wholeSnapshot = true)
        assertEquals(saves + 2, file.saves)
        assertEquals(repository.currentSnapshot, file.load().withHotStateMarker(null))
        // Nothing left to write: a second whole flush does not write again.
        repository.flushPersistence(wholeSnapshot = true)
        assertEquals(saves + 2, file.saves)
        repository.closePersistence()
        assertEquals(saves + 2, file.saves)
    }

    @Test
    fun whatIsPersistedLoadsBackAsItWas() = runTest {
        repeat(SEQUENCES) { sequence ->
            val random = Random(sequence)
            val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
            val store = FakeHotStateStore()
            val opened = open(file, store)
            val repository = ShinsouRepository(opened.snapshot, opened.storage)
            repeat(STEPS) {
                mutate(random, repository)
                if (random.nextInt(4) == 0) repository.flushPersistence(wholeSnapshot = random.nextInt(4) == 0)
            }
            repository.flushPersistence()
            val persisted = repository.currentSnapshot

            val reopened = open(file, store)

            val context = "sequence $sequence"
            assertEquals(HotStateOpening.SQL_CURRENT, reopened.opening, context)
            assertEquals(AppSnapshotJson.encode(persisted), AppSnapshotJson.encode(reopened.snapshot), context)
            assertEquals(persisted.revision, reopened.snapshot.revision, context)
            repository.closePersistence()
            // The close wrote the JSON whole: what a version without hot rows would read.
            assertEquals(persisted, file.load().withHotStateMarker(null), context)
        }
    }

    @Test
    fun aJsonAnOlderVersionWroteAfterTheMigrationWins() = runTest {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val store = FakeHotStateStore()
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage)
        repository.markChapterProgress(4, lastPageRead = 3, read = false, readAt = 5_000L)
        repository.flushPersistence()
        val token = checkNotNull(store.meta).token

        // The older version reads the JSON (its hot fields as of its last whole write), keeps the
        // marker it does not know, reads on and writes its JSON.
        val older = AppSnapshotJson.decode(checkNotNull(file.json))
        assertEquals(4, older.chapters.single { it.id == 4L }.lastPageRead, "as of the last whole write")
        val olderWrote = older.copy(
            revision = older.revision + 1,
            chapters = older.chapters.map { if (it.id == 1L) it.copy(read = true, lastPageRead = 2) else it },
            histories = listOf(History(id = 9, chapterId = 1, lastRead = 7_000L)) + older.histories,
        ).validate()
        file.json = AppSnapshotJson.encode(olderWrote)

        val reopened = open(file, store)

        assertEquals(HotStateOpening.REBUILT_FROM_JSON, reopened.opening)
        assertEquals(olderWrote.withHotStateMarker(null), reopened.snapshot)
        assertEquals(olderWrote.hotState(), store.rows)
        // Paired again under a new token, the JSON written with it (C3 fix 6: a new pairing
        // generation on every rebuild): the next start takes the rows.
        val rebuilt = checkNotNull(store.meta)
        assertTrue(rebuilt.token != token)
        assertEquals(HotStatePairing(olderWrote.revision, rebuilt.token, hotRevision = olderWrote.revision), rebuilt)
        assertEquals(setOf(HOT_STATE_MARKER_PREFIX + "v1:" + rebuilt.token), file.load().contentAuthorityProjectionMarkers)
        assertEquals(HotStateOpening.SQL_CURRENT, open(file, store).opening)
        repository.closePersistence()
    }

    @Test
    fun aFixtureOfAnOlderVersionsJsonWins() {
        // Written by a version without hot rows after this one had marked the file at revision 7.
        val fixture = """
            {"schemaVersion":1,"revision":8,
             "mangas":[{"id":1,"source":1,"url":"/m1","title":"M1","favorite":true}],
             "chapters":[{"id":1,"mangaId":1,"url":"/c1","name":"C1","read":true,"lastPageRead":5}],
             "histories":[{"id":1,"chapterId":1,"lastRead":100,"timeRead":20}],
             "contentAuthorityProjectionMarkers":["hot-state-sql:v1:t0"]}
        """.trimIndent()
        val file = FakeSnapshotFile(fixture)
        val store = FakeHotStateStore().apply {
            rows = HotState(chapterProgress = mapOf(1L to ChapterProgress(lastPageRead = 9)))
            meta = HotStatePairing(jsonRevision = 7, token = "t0", hotRevision = 12)
        }

        val opened = open(file, store)

        assertEquals(HotStateOpening.REBUILT_FROM_JSON, opened.opening)
        assertEquals(ChapterProgress(read = true, lastPageRead = 5), opened.snapshot.chapters.single().progress)
        assertEquals(8, opened.snapshot.revision)
        assertEquals(opened.snapshot.hotState(), store.rows)
    }

    @Test
    fun aLostDatabaseLeavesTheJsonToWin() = runTest {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val opened = open(file, FakeHotStateStore())
        val repository = ShinsouRepository(opened.snapshot, opened.storage)
        repository.markChapterProgress(4, lastPageRead = 3, read = false, readAt = 5_000L)
        repository.closePersistence()
        val closed = repository.currentSnapshot

        // A new, empty database (the old one deleted, or a backup of the files restored elsewhere).
        val fresh = FakeHotStateStore()
        val reopened = open(file, fresh)
        assertEquals(HotStateOpening.REBUILT_FROM_JSON, reopened.opening)
        assertEquals(closed, reopened.snapshot)
        assertEquals(closed.hotState(), fresh.rows)

        // An unreadable one: the JSON, written whole from then on.
        val unreadable = FakeHotStateStore().apply { failure = IllegalStateException("disk I/O error") }
        val whole = open(file, unreadable)
        assertEquals(HotStateOpening.STORE_UNAVAILABLE, whole.opening)
        assertEquals(closed, whole.snapshot)
        assertFalse(whole.storage.keepsHotApart)
        assertEquals(HotStateOpening.STORE_UNAVAILABLE, openSnapshotPersistence(file.load(), file, null).opening)
    }

    @Test
    fun aJsonWrittenAfterItsRowsWereNotWins() = runTest {
        // The process dies between the JSON and the transaction with the rows of its revision.
        val crashes = Crashes()
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()), crashes)
        val store = FakeHotStateStore(crashes)
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage)
        repository.markChapterProgress(4, lastPageRead = 3, read = false, readAt = 5_000L)
        repository.upsertManga(repository.currentSnapshot.mangas[0].copy(title = "Renamed"))
        crashes.at = crashes.steps + 2 // the pending revision, the file, then the rows
        assertFailsWith<Crash> { repository.flushPersistence() }
        val lost = repository.currentSnapshot
        crashes.at = null

        val reopened = open(file, store)

        assertEquals(HotStateOpening.REBUILT_FROM_JSON, reopened.opening)
        assertEquals(lost, reopened.snapshot)
        assertEquals(lost.hotState(), store.rows)
    }

    @Test
    fun theRowsOfADeletedChapterArePruned() {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val store = FakeHotStateStore()
        open(file, store)
        // Rows of chapter 99, which the JSON does not have (deleted while its rows were written).
        val orphanDownload = DownloadQueueItem(id = DownloadQueueItem.id(1, 99), mangaId = 1, chapterId = 99)
        store.apply(
            HotDelta(
                progress = mapOf(99L to ChapterProgress(read = true)),
                histories = listOf(Ordered(0, History(id = 50, chapterId = 99, lastRead = 1))) +
                    store.rows.histories.mapIndexed { index, history -> Ordered(index + 1, history) },
                downloads = listOf(Ordered(store.rows.downloads.size, orphanDownload)),
            ),
        )

        val opened = open(file, store)

        assertEquals(HotStateOpening.SQL_CURRENT, opened.opening)
        assertEquals(hotLibrary(), opened.snapshot)
        // Pruned, and the lists numbered from 0 again: a row added at the end takes the next ordinal.
        assertEquals(hotLibrary().hotState(), store.rows)
        val added = opened.snapshot.copy(
            revision = opened.snapshot.revision + 1,
            histories = opened.snapshot.histories + History(id = 60, chapterId = 12, lastRead = 2),
        )
        opened.storage.write(added.revision, json = null, hot = HotRows.Changed(hotDelta(opened.snapshot, added)))
        assertEquals(added.hotState(), store.rows)
    }

    @Test
    fun theOffSwitchReadsTheRowsAndWritesTheWholeJson() = runTest {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val store = FakeHotStateStore()
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage)
        repository.markChapterProgress(4, lastPageRead = 3, read = false, readAt = 5_000L)
        repository.flushPersistence()
        // The rows are newer than the JSON when the switch is set (and the process restarted).
        val latest = repository.currentSnapshot
        val calls = store.calls

        val off = openSnapshotPersistence(file.load(), file, store, hotStateEnabled = false)

        assertEquals(HotStateOpening.OFF_SWITCH, off.opening)
        assertEquals(latest, off.snapshot)
        assertFalse(off.storage.keepsHotApart)
        val whole = ShinsouRepository(off.snapshot, off.storage)
        whole.markChapterProgress(4, lastPageRead = 5, read = false, readAt = 6_000L)
        whole.closePersistence()
        val closed = whole.currentSnapshot
        // Written whole, without the marker; the rows were only read (the pairing and the rows).
        assertEquals(closed, file.load())
        assertTrue(file.load().contentAuthorityProjectionMarkers.isEmpty())
        assertEquals(calls + 2, store.calls)

        // Switched back on: an unmarked JSON, so its rows are written again from it.
        val on = open(file, store)
        assertEquals(HotStateOpening.MIGRATED, on.opening)
        assertEquals(closed, on.snapshot)
        assertEquals(closed.hotState(), store.rows)
    }

    @Test
    fun hotChangesStillRefreshTheJsonNowAndThen() = runTest {
        val file = FakeSnapshotFile()
        val store = FakeHotStateStore()
        val time = TestTimeSource()
        val library = hotLibrary()
        store.commit(HotRows.All(library.hotState()), HotStatePairing(library.revision, "t"))
        val storage = HotStateSnapshotStorage(file, store, HotStatePairing(library.revision, "t"), refresh = HotStateSnapshotStorage.JsonRefresh(10.minutes, time))
        val repository = ShinsouRepository(library, storage)

        repository.markChapterProgress(4, lastPageRead = 3, read = false, readAt = 5_000L)
        repository.flushPersistence()
        assertEquals(0, file.saves)
        time += 11.minutes
        repository.markChapterProgress(4, lastPageRead = 4, read = false, readAt = 6_000L)
        repository.flushPersistence()
        assertEquals(1, file.saves)
        assertEquals(repository.currentSnapshot, file.load().withHotStateMarker(null))
        repository.markChapterProgress(4, lastPageRead = 5, read = false, readAt = 7_000L)
        repository.flushPersistence()
        assertEquals(1, file.saves)
        repository.closePersistence()
    }

    @Test
    fun anAtomicHostCommitWritesTheRowsInItsTransaction() = runTest {
        val file = FakeSnapshotFile(AppSnapshotJson.encode(hotLibrary()))
        val store = FakeHotStateStore()
        val opened = open(file, store)
        val repository = ShinsouRepository(opened.snapshot, opened.storage)
        val before = store.rows
        val restored = hotLibrary().let { library ->
            library.copy(
                mangas = library.mangas.map { it.copy(title = it.title + " (restored)") },
                chapters = library.chapters.map { it.copy(read = true, lastPageRead = 0) },
                histories = library.histories.drop(2),
                downloadQueue = emptyList(),
            )
        }

        val failed = runCatching {
            repository.commitSnapshotMutationAtomically(restored, SnapshotReplacementOrigin.DIRECT) { _, _, commitSyncJournal ->
                store.hostTransaction {
                    commitSyncJournal()
                    error("the content transaction failed half-way")
                }
            }
        }
        assertTrue(failed.isFailure)
        assertEquals(before, store.rows, "the rows rolled back with the host")
        assertEquals(hotLibrary(), repository.currentSnapshot)

        val committed = repository.commitSnapshotMutationAtomically(restored, SnapshotReplacementOrigin.DIRECT) { _, _, commitSyncJournal ->
            store.hostTransaction { commitSyncJournal() }
        }
        // In the host's transaction, before the writer got to it.
        assertEquals(committed.snapshot.hotState(), store.rows)
        repository.flushPersistence()
        assertEquals(committed.snapshot.hotState(), store.rows)
        assertEquals(committed.snapshot, file.load().withHotStateMarker(null))
        assertNotNull(open(file, store).takeIf { it.opening == HotStateOpening.SQL_CURRENT })
        repository.closePersistence()
    }

    private fun open(file: FakeSnapshotFile, store: FakeHotStateStore): OpenedSnapshot =
        openSnapshotPersistence(file.read(), file, store, newToken = { "token-${tokens++}" })

    private var tokens = 0

    /** One change, hot or cold, as the app makes them. */
    private suspend fun mutate(random: Random, repository: ShinsouRepository) {
        val state = repository.currentSnapshot
        val chapter = state.chapters.randomOrNull(random) ?: return
        when (random.nextInt(9)) {
            0, 1 -> repository.markChapterProgress(chapter.id, random.nextInt(0, 30), random.nextBoolean(), readAt = random.nextLong(1, 9_999))
            2 -> repository.recordHistory(chapter.id, lastRead = random.nextLong(1, 9_999), timeRead = 5)
            3 -> repository.enqueueDownload(chapter.mangaId, chapter.id)
            4 -> state.downloadQueue.randomOrNull(random)?.let { item ->
                repository.setDownloadState(item.id, DownloadState.DOWNLOADING, progress = 0.5, downloadedPages = 1, totalPages = 2)
            }
            5 -> repository.deleteHistory(chapter.id)
            6 -> repository.deleteChapter(chapter.id)
            7 -> repository.upsertChapter(Chapter(mangaId = chapter.mangaId, url = "/new${random.nextInt()}", name = "New", lastPageRead = 2))
            else -> repository.upsertManga(state.mangas.random(random).let { it.copy(title = it.title + "'") })
        }
    }

    private companion object {
        const val PAGES = 20
        const val SEQUENCES = 25
        const val STEPS = 40
    }
}
