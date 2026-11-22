package dev.shinsou.kmp.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.content.hotstate.SqlHotStateStore
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.AppSnapshotJson
import dev.shinsou.kmp.data.BEFORE_HOT_STATE_SUFFIX
import dev.shinsou.kmp.data.HotStateOpening
import dev.shinsou.kmp.data.OpenedSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.data.SnapshotFile
import dev.shinsou.kmp.data.hotState
import dev.shinsou.kmp.data.openSnapshotPersistence
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.persistence.HotDelta
import dev.shinsou.kmp.domain.persistence.HotRows
import dev.shinsou.kmp.domain.persistence.HotState
import dev.shinsou.kmp.domain.persistence.HotStatePairing
import dev.shinsou.kmp.domain.persistence.HotStateStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * C3-3/C3-4 (docs/architecture/0007 §6.3.5-6.3.6) on the desktop's own pieces: the state file and
 * its `.before-hot-state` copy on disk, the hot rows in a SQLite file opened afresh at each start.
 */
class HotStateDesktopStartupTest {
    @Test
    fun theMigrationIsSafeToRerunAfterTheProcessDiesAtAnyStep() {
        val legacy = AppSnapshotJson.encode(library())
        var step = 0
        do {
            val directory = Files.createTempDirectory("hot-state-migration")
            val state = directory.resolve("shinsou-state.json").apply { writeText(legacy) }
            // What an earlier copy cut short left behind.
            directory.resolve("shinsou-state.json$BEFORE_HOT_STATE_SUFFIX.partial").writeText("{ cut short")
            val database = directory.resolve("content.db")
            val died = withStore(database) { store ->
                try {
                    start(state, CrashingStore(store, step))
                    false
                } catch (_: Died) {
                    // The process died here: the next start sees only what was on disk.
                    true
                }
            }
            val context = "died at step $step"
            withStore(database) { store ->
                val restarted = start(state, store)
                assertTrue(restarted.opening.keepsHotApart, context)
                assertEquals(library(), restarted.snapshot, context)
                assertEquals(library().hotState(), store.load(), context)
            }
            withStore(database) { store -> assertEquals(HotStateOpening.SQL_CURRENT, start(state, store).opening, context) }
            assertEquals(legacy, directory.resolve("shinsou-state.json$BEFORE_HOT_STATE_SUFFIX").readText(), context)
            assertFalse(Files.exists(directory.resolve("shinsou-state.json$BEFORE_HOT_STATE_SUFFIX.partial")), context)
            directory.toFile().deleteRecursively()
            step++
        } while (died)
    }

    @Test
    fun readingAfterARestartKeepsItsProgressWithoutRewritingTheJson() = runTest {
        val directory = Files.createTempDirectory("hot-state-reading")
        val state = directory.resolve("shinsou-state.json").apply { writeText(AppSnapshotJson.encode(library())) }
        val database = directory.resolve("content.db")
        val read = withStore(database) { store ->
            val opened = start(state, store)
            val written = state.readText()
            val repository = ShinsouRepository(opened.snapshot, opened.storage)
            repeat(10) { page -> repository.markChapterProgress(2, lastPageRead = page, read = false, readAt = 100L + page, timeRead = 3) }
            repository.flushPersistence()
            assertEquals(written, state.readText(), "a page turn rewrote the JSON")
            // The process is killed: no close, no lifecycle flush.
            repository.currentSnapshot
        }

        withStore(database) { store ->
            val restarted = start(state, store)
            assertEquals(HotStateOpening.SQL_CURRENT, restarted.opening)
            assertEquals(read, restarted.snapshot)
            // A version without hot rows reads the JSON: the library, as of its last whole write.
            assertEquals(library().chapters.map { it.id }, AppSnapshotJson.decode(state.readText()).chapters.map { it.id })
        }

        // The database is lost: the JSON wins, the progress since its last whole write is not there.
        Files.delete(database)
        withStore(database) { store ->
            val restarted = start(state, store)
            assertEquals(HotStateOpening.REBUILT_FROM_JSON, restarted.opening)
            assertEquals(library(), restarted.snapshot)
            assertEquals(library().hotState(), store.load())
        }
        directory.toFile().deleteRecursively()
    }

    /** C3 fix 2: a state file that no longer decodes is quarantined; the rows outlive it. */
    @Test
    fun aQuarantinedStateFileLeavesTheRowsForItsCopyToComeBack() = runTest {
        val directory = Files.createTempDirectory("hot-state-quarantine")
        val state = directory.resolve("shinsou-state.json").apply { writeText(AppSnapshotJson.encode(library())) }
        val database = directory.resolve("content.db")
        val (read, paired) = withStore(database) { store ->
            val opened = start(state, store)
            val repository = ShinsouRepository(opened.snapshot, opened.storage)
            repository.markChapterProgress(2, lastPageRead = 7, read = false, readAt = 500L)
            repository.flushPersistence()
            repository.currentSnapshot to state.readText()
        }
        state.writeText("{ torn")

        val rows = withStore(database) { store ->
            val restarted = start(state, store)
            assertEquals(HotStateOpening.ROWS_KEPT, restarted.opening)
            assertEquals(AppSnapshot(), restarted.snapshot)
            store.load()
        }
        assertEquals(read.hotState(), rows)
        val quarantined = Files.list(directory).use { files -> files.filter { it.fileName.toString().startsWith("shinsou-state.corrupt-") }.toList() }
        assertEquals(1, quarantined.size)

        // The good copy put back: the progress since its last whole write is there again.
        state.writeText(paired)
        withStore(database) { store ->
            val restarted = start(state, store)
            assertEquals(HotStateOpening.SQL_CURRENT, restarted.opening)
            assertEquals(read, restarted.snapshot)
        }
        directory.toFile().deleteRecursively()
    }

    private fun start(state: Path, store: HotStateStore): OpenedSnapshot =
        openSnapshotPersistence(DesktopPersistence.readSnapshot(state), PathSnapshotFile(state), store)

    private inline fun <T> withStore(database: Path, block: (SqlHotStateStore) -> T): T =
        JdbcSqliteDriver("jdbc:sqlite:$database").use { driver -> block(SqlHotStateStore(driver)) }

    private fun library(): AppSnapshot {
        val manga = Manga(id = 1, source = 1, url = "/m", title = "M", favorite = true)
        val chapters = (1L..4L).map { Chapter(id = it, mangaId = 1, url = "/c$it", name = "C$it", read = it == 1L, lastPageRead = it.toInt() - 1) }
        return AppSnapshot(
            revision = 3,
            mangas = listOf(manga),
            chapters = chapters,
            histories = listOf(History(id = 1, chapterId = 3, lastRead = 30), History(id = 2, chapterId = 1, lastRead = 10)),
            downloadQueue = listOf(DownloadQueueItem(id = DownloadQueueItem.id(1, 4), mangaId = 1, chapterId = 4)),
        ).validate()
    }

    private class Died : Error("simulated process death")

    /** The state file as the desktop writes it, its copy through [DesktopPersistence]. */
    private class PathSnapshotFile(private val path: Path) : SnapshotFile {
        override fun save(json: String) {
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            temporary.writeText(json)
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }

        override fun keepCopyBeforeHotState(): Boolean = DesktopPersistence.keepCopyBeforeHotState(path)
    }

    /** [delegate], with the process dying at its call number [at] (each call is one transaction). */
    private class CrashingStore(private val delegate: HotStateStore, private val at: Int) : HotStateStore {
        private var calls = 0

        private fun <T> call(block: () -> T): T {
            if (calls++ == at) throw Died()
            return block()
        }

        override fun load(): HotState = call(delegate::load)

        override fun apply(delta: HotDelta) = call { delegate.apply(delta) }

        override fun clear() = call(delegate::clear)

        override fun pairing(): HotStatePairing? = call(delegate::pairing)

        override fun setPairing(pairing: HotStatePairing?) = call { delegate.setPairing(pairing) }

        override fun journaledJson(): String? = call(delegate::journaledJson)

        override fun setJournaledJson(json: String?) = call { delegate.setJournaledJson(json) }

        override fun commit(rows: HotRows, pairing: HotStatePairing) = call { delegate.commit(rows, pairing) }
    }
}
