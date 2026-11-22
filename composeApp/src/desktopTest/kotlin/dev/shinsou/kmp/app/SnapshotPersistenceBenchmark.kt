package dev.shinsou.kmp.app

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.content.hotstate.SqlHotStateStore
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.AppSnapshotJson
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.data.SnapshotFile
import dev.shinsou.kmp.data.openSnapshotPersistence
import dev.shinsou.kmp.data.coldChanged
import dev.shinsou.kmp.data.hotDelta
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.LibraryUpdate
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.persistence.HotDelta
import dev.shinsou.kmp.domain.persistence.Ordered
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C3-0 (docs/architecture/0007 §6.3.7): what one snapshot write costs on a large library, before
 * C3 (validate, encode and write the whole JSON on every page turn) and after (the hot delta, the
 * cold-change check and the SQL write of a page turn's rows). Not a CI gate: skipped unless
 * `SHINSOU_BENCHMARK=1`, e.g.
 *
 * `SHINSOU_BENCHMARK=1 ./gradlew :composeApp:desktopTest --tests '*SnapshotPersistenceBenchmark*' --rerun`
 *
 * The numbers are printed and written to `build/reports/c3-benchmark.txt`.
 */
class SnapshotPersistenceBenchmark {
    @Test
    fun aLargeLibrary() {
        assumeTrue("set SHINSOU_BENCHMARK=1 to run", System.getenv("SHINSOU_BENCHMARK") == "1")
        val library = syntheticLibrary(Random(42))
        val directory = Files.createTempDirectory("c3-benchmark")
        val report = StringBuilder()
        fun line(text: String) {
            println(text)
            report.appendLine(text)
        }
        line("library: ${library.mangas.size} mangas, ${library.chapters.size} chapters, ${library.histories.size} histories, " +
            "${library.downloadQueue.size} downloads, ${library.updates.size} updates")

        val json = AppSnapshotJson.encode(library)
        line("JSON: ${json.length} chars, ${json.toByteArray().size / 1024} KiB")
        line("validate(): ${measure { library.validate() }}")
        line("encode: ${measure { AppSnapshotJson.encode(library) }}")
        line("write (fsync + rename): ${measure { writeAtomically(directory, json) }}")
        line("whole JSON write (validate + encode + write): ${measure { writeAtomically(directory, AppSnapshotJson.encode(library.validate())) }}")
        line("decode (startup): ${measure(runs = 5) { AppSnapshotJson.decode(json) }}")

        // A page turn, as markChapterProgress makes it: one chapter and one history replaced.
        val pageTurn = pageTurn(library, chapterIndex = library.chapters.size / 2, historyIndex = library.histories.size / 3)
        val delta = hotDelta(library, pageTurn)
        assertEquals(2, delta.progress.size + delta.histories.size)
        assertTrue(!coldChanged(library, pageTurn))
        val repository = ShinsouRepository(library)
        var page = 0
        line("page turn, in memory (markChapterProgress): ${measure(runs = 21) { runBlocking { markChapterProgress(repository, ++page) } }}")
        line("page turn, hotDelta: ${measure(runs = 51) { hotDelta(library, pageTurn) }}")
        line("page turn, coldChanged: ${measure(runs = 51) { coldChanged(library, pageTurn) }}")
        // A download's progress: one queue row replaced.
        val downloadProgress = library.copy(
            downloadQueue = library.downloadQueue.toMutableList().apply { this[10] = this[10].copy(downloadedPages = 7, progress = 0.5) },
        )
        line("download page, hotDelta: ${measure(runs = 51) { hotDelta(library, downloadProgress) }}")

        JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("content.db")}").use { driver ->
            driver.executeQuery(null, "PRAGMA journal_mode = WAL", { QueryResult.Value(it.next().value) }, 0)
            driver.execute(null, "PRAGMA synchronous = FULL", 0).value
            val store = SqlHotStateStore(driver)
            val everything = HotDelta(
                progress = library.chapters.filter { it.read || it.bookmark || it.lastPageRead != 0 }
                    .associate { it.id to dev.shinsou.kmp.domain.persistence.ChapterProgress(it.read, it.bookmark, it.lastPageRead) },
                histories = library.histories.mapIndexed(::Ordered),
                downloads = library.downloadQueue.mapIndexed(::Ordered),
            )
            line("SQL, first write of every hot row (${everything.progress.size} progress rows): ${measure(runs = 3) { store.apply(everything) }}")
            line("SQL, page turn delta: ${measure(runs = 31) { store.apply(delta) }}")
            line("SQL, load every hot row: ${measure(runs = 5) { store.load() }}")
        }

        // C3-3: the repository as the app now runs it, the JSON and the hot rows on disk.
        JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("app.db")}").use { driver ->
            driver.executeQuery(null, "PRAGMA journal_mode = WAL", { QueryResult.Value(it.next().value) }, 0)
            driver.execute(null, "PRAGMA synchronous = FULL", 0).value
            val file = object : SnapshotFile {
                override fun save(json: String) = writeAtomically(directory, json)
                override fun keepCopyBeforeHotState(): Boolean = true
            }
            val start = System.nanoTime()
            val opened = openSnapshotPersistence(library, file, SqlHotStateStore(driver))
            line("C3-3 first start (rows written, JSON marked and written): %.0f ms".format((System.nanoTime() - start) / 1e6))
            val split = ShinsouRepository(opened.snapshot, opened.storage)
            var turn = 0
            line(
                "C3-3 page turn persisted (markChapterProgress + flush, hot rows only): " +
                    measure(runs = 31) { runBlocking { markChapterProgress(split, ++turn); split.flushPersistence() } },
            )
            line(
                "C3-3 lifecycle flush after a page turn (whole JSON): " +
                    measure(runs = 5) { runBlocking { markChapterProgress(split, ++turn); split.flushPersistence(wholeSnapshot = true) } },
            )
            val restart = System.nanoTime()
            openSnapshotPersistence(AppSnapshotJson.decode(Files.readString(directory.resolve("shinsou-state.json"))), file, SqlHotStateStore(driver))
            line("C3-3 later start (decode + rows applied): %.0f ms".format((System.nanoTime() - restart) / 1e6))
            runBlocking { split.closePersistence() }
        }
        val reports = Path.of("build/reports").also(Files::createDirectories)
        Files.writeString(reports.resolve("c3-benchmark.txt"), report)
        directory.toFile().deleteRecursively()
    }

    private suspend fun markChapterProgress(repository: ShinsouRepository, page: Int) {
        val chapter = repository.currentSnapshot.chapters.let { it[it.size / 2] }
        repository.markChapterProgress(chapter.id, lastPageRead = page, read = false, readAt = 2_000_000_000_000L + page)
    }

    private fun pageTurn(library: AppSnapshot, chapterIndex: Int, historyIndex: Int): AppSnapshot = library.copy(
        revision = library.revision + 1,
        chapters = library.chapters.toMutableList().apply { this[chapterIndex] = this[chapterIndex].copy(lastPageRead = 11) },
        histories = library.histories.toMutableList().apply {
            this[historyIndex] = this[historyIndex].copy(lastRead = 2_000_000_000_000, timeRead = this[historyIndex].timeRead + 30)
        },
    )

    /** Median and minimum of [runs] timed runs after two warm-up runs. */
    private fun measure(runs: Int = 7, block: () -> Unit): String {
        repeat(2) { block() }
        val times = (1..runs).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        return "median %.2f ms, min %.2f ms (%d runs)".format(times[times.size / 2], times.first(), runs)
    }

    private fun writeAtomically(directory: Path, payload: String) {
        val temporary = directory.resolve("shinsou-state.json.tmp")
        FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val bytes = ByteBuffer.wrap(payload.toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        Files.move(temporary, directory.resolve("shinsou-state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** ≈500 titles, 150k chapters, 20k histories, 5k downloads (§6.3.7), with realistic text. */
    private fun syntheticLibrary(random: Random): AppSnapshot {
        val categories = listOf(Category.Default) + (1L..8L).map { Category(id = it, name = "分類 $it", sort = it.toInt()) }
        val mangas = (1L..MANGAS).map { id ->
            Manga(
                id = id,
                source = 1_000L + id % 12,
                favorite = true,
                url = "/manga/$id-${"title".repeat(3)}",
                title = "作品タイトル $id: A Reasonably Long Series Name",
                author = "Author $id",
                artist = "Artist $id",
                description = "Synopsis ".repeat(40),
                genre = listOf("Action", "Drama", "Fantasy", "Slice of Life"),
                thumbnailUrl = "https://images.example.org/covers/$id.jpg",
                initialized = true,
                dateAdded = 1_600_000_000_000 + id,
                lastModifiedAt = 1_700_000_000_000 + id,
                version = 3,
            )
        }
        val chapters = mangas.flatMap { manga ->
            (1..CHAPTERS_PER_MANGA).map { number ->
                val id = (manga.id - 1) * CHAPTERS_PER_MANGA + number
                Chapter(
                    id = id,
                    mangaId = manga.id,
                    url = "/manga/${manga.id}/chapter/$number",
                    name = "Vol.${number / 10 + 1} Ch.$number - Chapter title $number",
                    scanlator = "Group ${manga.id % 7}",
                    read = random.nextInt(3) == 0,
                    bookmark = random.nextInt(50) == 0,
                    lastPageRead = if (random.nextInt(4) == 0) random.nextInt(1, 40) else 0,
                    chapterNumber = number.toDouble(),
                    sourceOrder = (CHAPTERS_PER_MANGA - number).toInt(),
                    dateFetch = 1_650_000_000_000 + id,
                    dateUpload = 1_640_000_000_000 + id,
                    lastModifiedAt = 1_700_000_000_000 + id,
                    version = 2,
                )
            }
        }
        val historyChapters = chapters.shuffled(random).take(HISTORIES)
        val histories = historyChapters.mapIndexed { index, chapter ->
            History(id = index + 1L, chapterId = chapter.id, lastRead = 1_700_000_000_000 + index, timeRead = random.nextLong(0, 3_600_000))
        }
        val downloads = chapters.shuffled(random).take(DOWNLOADS).mapIndexed { index, chapter ->
            DownloadQueueItem(
                id = DownloadQueueItem.id(chapter.mangaId, chapter.id),
                mangaId = chapter.mangaId,
                chapterId = chapter.id,
                state = if (index < DOWNLOADS - 50) DownloadState.DOWNLOADED else DownloadState.QUEUED,
                progress = if (index < DOWNLOADS - 50) 1.0 else 0.0,
                downloadedPages = 20,
                totalPages = 20,
                position = index,
                queuedAt = 1_700_000_000_000 + index,
                destinationPath = "/data/downloads/${chapter.mangaId}/${chapter.id}",
                visibleInQueue = index >= DOWNLOADS - 50,
            )
        }
        return AppSnapshot(
            revision = 1,
            mangas = mangas,
            chapters = chapters,
            categories = categories,
            mangaCategories = mangas.map { MangaCategory(it.id, categories[(it.id % categories.size).toInt()].id) },
            histories = histories,
            updates = chapters.takeLast(2_000).map { LibraryUpdate(it.mangaId, it.id, discoveredAt = it.dateFetch) },
            downloadQueue = downloads,
        ).validate()
    }

    private companion object {
        const val MANGAS = 500L
        const val CHAPTERS_PER_MANGA = 300L
        const val HISTORIES = 20_000
        const val DOWNLOADS = 5_000
    }
}
