package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.LibraryUpdate
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.persistence.ChapterProgress
import dev.shinsou.kmp.domain.persistence.HotDelta
import dev.shinsou.kmp.domain.persistence.HotState
import dev.shinsou.kmp.domain.persistence.SnapshotStorage
import dev.shinsou.kmp.domain.persistence.WholeJsonSnapshotStorage
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** C3-1 (docs/architecture/0007 §6.3.7): the hot delta and the cold-change check. */
class SnapshotHotStateTest {
    @Test
    fun applyingTheDeltaOfRandomMutationsGivesTheNextHotState() {
        repeat(SEQUENCES) { sequence ->
            val random = Random(sequence)
            var previous = library(random)
            var written = previous.hotState()
            repeat(STEPS) { step ->
                val (name, next) = mutation(random, previous)
                val delta = hotDelta(previous, next)
                val context = "sequence $sequence, step $step ($name)"
                assertEquals(previous.hotState().applying(delta), next.hotState(), context)
                // The rows written so far, delta after delta, are next's: what the table holds.
                written = written.applying(delta)
                assertEquals(next.hotState(), written, context)
                delta.histories.forEach { assertSame(next.histories[it.ordinal], it.value, context) }
                delta.downloads.forEach { assertSame(next.downloadQueue[it.ordinal], it.value, context) }
                assertEquals(coldOracle(previous) != coldOracle(next), coldChanged(previous, next), context)
                previous = next
            }
        }
    }

    @Test
    fun aMutationOfOneHotRowWritesOnlyThatRow() {
        val base = library(Random(1))
        val chapter = base.chapters[3]
        val read = base.copy(chapters = base.chapters.replacing(3, chapter.copy(lastPageRead = chapter.lastPageRead + 1)))
        assertEquals(HotDelta(progress = mapOf(chapter.id to read.chapters[3].progress())), hotDelta(base, read))

        val history = base.histories[1]
        val touched = base.copy(histories = base.histories.replacing(1, history.copy(timeRead = history.timeRead + 5)))
        val delta = hotDelta(base, touched)
        assertEquals(listOf(1), delta.histories.map { it.ordinal })
        assertTrue(delta.removedHistories.isEmpty())

        assertTrue(hotDelta(base, base).isEmpty)
        assertTrue(hotDelta(base, base.copy(revision = base.revision + 1)).isEmpty)
    }

    @Test
    fun progressBackToTheDefaultDeletesTheRow() {
        val base = library(Random(2))
        val index = base.chapters.indexOfFirst { it.read || it.bookmark || it.lastPageRead != 0 }
        val chapter = base.chapters[index]
        val unread = base.copy(chapters = base.chapters.replacing(index, chapter.copy(read = false, bookmark = false, lastPageRead = 0)))
        assertEquals(HotDelta(removedProgress = setOf(chapter.id)), hotDelta(base, unread))
    }

    @Test
    fun aRemovedHistoryShiftsTheOnesAfterIt() {
        val base = library(Random(3))
        val removed = base.histories[0]
        val next = base.copy(histories = base.histories.drop(1))
        val delta = hotDelta(base, next)
        assertEquals(setOf(removed.id), delta.removedHistories)
        assertEquals(next.histories.indices.toList(), delta.histories.map { it.ordinal })
    }

    @Test
    fun hotOnlyChangesAreNotColdChanges() {
        val base = library(Random(4))
        val chapter = base.chapters[0]
        listOf(
            base.copy(revision = base.revision + 7),
            base.copy(chapters = base.chapters.replacing(0, chapter.copy(read = !chapter.read, bookmark = !chapter.bookmark, lastPageRead = 9))),
            base.copy(histories = base.histories.reversed()),
            base.copy(histories = emptyList(), downloadQueue = emptyList()),
            base.copy(downloadQueue = base.downloadQueue.map { it.copy(state = DownloadState.DOWNLOADED, progress = 1.0) }),
            // Equal but not the same lists.
            base.copy(chapters = base.chapters.toList(), mangas = base.mangas.toList(), settings = base.settings.copy()),
        ).forEach { next -> assertFalse(coldChanged(base, next), "$next") }
        assertFalse(coldChanged(base, base))
    }

    @Test
    fun everyOtherChangeIsACold() {
        val base = library(Random(5))
        val chapter = base.chapters[0]
        listOf(
            base.copy(chapters = base.chapters.replacing(0, chapter.copy(name = chapter.name + "!"))),
            base.copy(chapters = base.chapters.replacing(0, chapter.copy(version = chapter.version + 1))),
            base.copy(chapters = base.chapters.drop(1)),
            base.copy(chapters = base.chapters.reversed()),
            base.copy(mangas = base.mangas.replacing(0, base.mangas[0].copy(title = "Renamed"))),
            base.copy(settings = AppSettings().let { it.copy(library = it.library.copy(portraitColumns = 7)) }),
            base.copy(updates = listOf(LibraryUpdate(mangaId = chapter.mangaId, chapterId = chapter.id, discoveredAt = 1))),
            base.copy(contentAuthorityProjectionMarkers = setOf("hot-state-sql:v1:token")),
            base.copy(schemaVersion = 2),
        ).forEach { next -> assertTrue(coldChanged(base, next), "$next") }
    }

    @Test
    fun theWholeJsonStorageKeepsNothingApart() {
        val written = mutableListOf<String>()
        val storage = WholeJsonSnapshotStorage(written::add)
        storage.writeHot(HotDelta(removedProgress = setOf(1)))
        storage.writeCold("{}", revision = 1)
        assertEquals(listOf("{}"), written)
        assertNull(storage.loadHot())
        assertNull(storage.pairedRevision)
    }

    /** The repository still writes the whole JSON each time, and its deltas add up to the hot state. */
    @Test
    fun theRepositoryWritesTheHotRowsThenTheWholeJson() = runTest {
        val storage = RecordingStorage()
        val repository = ShinsouRepository(storage = storage)
        val manga = repository.upsertManga(Manga(source = 1, url = "/m", title = "M", favorite = true))
        val chapters = (1..3).map { repository.upsertChapter(Chapter(mangaId = manga.id, url = "/c$it", name = "C$it")) }
        repository.flushPersistence()
        repository.markChapterProgress(chapters[1].id, lastPageRead = 4, read = false, readAt = 10)
        repository.upsertDownload(DownloadQueueItem(DownloadQueueItem.id(manga.id, chapters[2].id), manga.id, chapters[2].id))
        repository.flushPersistence()
        repository.renameManga(manga.id)
        repository.closePersistence()

        // Every write ends with the whole JSON; the hot rows, when any changed, come just before it.
        val kinds = storage.calls.map { it.first }
        assertTrue(kinds.contains("hot"))
        kinds.forEachIndexed { index, kind -> if (kind == "hot") assertEquals("cold", kinds.getOrNull(index + 1), "$kinds") }
        assertEquals(repository.currentSnapshot.hotState(), storage.hot)
        assertEquals(repository.currentSnapshot, ShinsouRepository.decodeSnapshot(storage.json))
        assertEquals(repository.currentSnapshot.revision, storage.calls.last().second)
    }

    private suspend fun ShinsouRepository.renameManga(id: Long) {
        upsertManga(currentSnapshot.mangas.single { it.id == id }.copy(title = "Renamed"))
    }

    private class RecordingStorage : SnapshotStorage {
        val calls = mutableListOf<Pair<String, Long>>()
        var hot = HotState.EMPTY
        var json = ""

        override fun writeCold(json: String, revision: Long) {
            calls += "cold" to revision
            this.json = json
        }

        override fun writeHot(delta: HotDelta) {
            calls += "hot" to -1L
            hot = hot.applying(delta)
        }

        override fun loadHot(): HotState = hot

        override val pairedRevision: Long? get() = null
    }

    private fun Chapter.progress(): ChapterProgress = ChapterProgress(read, bookmark, lastPageRead)

    /** What the JSON holds apart from the hot rows, compared whole: the reference for [coldChanged]. */
    private fun coldOracle(snapshot: AppSnapshot): AppSnapshot = snapshot.copy(
        revision = 0,
        chapters = snapshot.chapters.map { it.copy(read = false, bookmark = false, lastPageRead = 0) },
        histories = emptyList(),
        downloadQueue = emptyList(),
    )

    private fun library(random: Random): AppSnapshot {
        val mangas = (1L..4L).map { Manga(id = it, source = 1, url = "/m$it", title = "M$it", favorite = true) }
        val chapters = (1L..30L).map { id -> randomChapter(random, id, mangas.random(random).id) }
        val histories = chapters.shuffled(random).take(8).mapIndexed { index, chapter ->
            History(id = index + 1L, chapterId = chapter.id, lastRead = random.nextLong(1, 1_000), timeRead = random.nextLong(0, 100))
        }
        val downloads = chapters.shuffled(random).take(6).mapIndexed { index, chapter -> download(random, chapter, index) }
        return AppSnapshot(revision = 1, mangas = mangas, chapters = chapters, histories = histories, downloadQueue = downloads)
    }

    private fun randomChapter(random: Random, id: Long, mangaId: Long) = Chapter(
        id = id,
        mangaId = mangaId,
        url = "/c$id",
        name = "C$id",
        read = random.nextInt(3) == 0,
        bookmark = random.nextInt(5) == 0,
        lastPageRead = if (random.nextBoolean()) 0 else random.nextInt(1, 40),
    )

    private fun download(random: Random, chapter: Chapter, position: Int) = DownloadQueueItem(
        id = DownloadQueueItem.id(chapter.mangaId, chapter.id),
        mangaId = chapter.mangaId,
        chapterId = chapter.id,
        state = DownloadState.entries.random(random),
        progress = random.nextDouble(),
        position = position,
    )

    /** One random change of [state], hot or cold, as the repository or a replacement could make it. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun mutation(random: Random, state: AppSnapshot): Pair<String, AppSnapshot> {
        val next = state.copy(revision = state.revision + 1)
        val chapters = state.chapters
        return when (random.nextInt(16)) {
            0 -> "progress" to next.copy(chapters = chapters.updatingRandom(random) {
                it.copy(read = random.nextBoolean(), lastPageRead = random.nextInt(0, 3))
            })
            1 -> "bookmark" to next.copy(chapters = chapters.updatingRandom(random) { it.copy(bookmark = !it.bookmark) })
            2 -> "rename chapter" to next.copy(chapters = chapters.updatingRandom(random) { it.copy(name = it.name + "'") })
            3 -> "add chapter" to next.copy(
                chapters = chapters + randomChapter(random, (chapters.maxOfOrNull { it.id } ?: 0) + 1, state.mangas.random(random).id),
            )
            4 -> "remove chapter" to if (chapters.isEmpty()) next else chapters.random(random).let { gone ->
                next.copy(
                    chapters = chapters - gone,
                    histories = state.histories.filterNot { it.chapterId == gone.id },
                    downloadQueue = state.downloadQueue.filterNot { it.chapterId == gone.id },
                )
            }
            5 -> "shuffle chapters" to next.copy(chapters = chapters.shuffled(random))
            6 -> "record history" to if (chapters.isEmpty()) next else chapters.random(random).let { chapter ->
                val index = state.histories.indexOfFirst { it.chapterId == chapter.id }
                if (index >= 0) {
                    next.copy(histories = state.histories.replacing(index, state.histories[index].copy(lastRead = random.nextLong(1, 9_999))))
                } else {
                    val id = (state.histories.maxOfOrNull { it.id } ?: 0) + 1
                    next.copy(histories = state.histories + History(id = id, chapterId = chapter.id, lastRead = 1))
                }
            }
            7 -> "remove history" to next.copy(histories = state.histories.shuffled(random).drop(1).let { kept ->
                state.histories.filter { it in kept }
            })
            8 -> "move history" to next.copy(histories = state.histories.shuffled(random))
            9 -> "clear histories" to next.copy(histories = emptyList())
            10 -> "download progress" to next.copy(downloadQueue = state.downloadQueue.updatingRandom(random) {
                it.copy(progress = random.nextDouble(), downloadedPages = it.downloadedPages + 1)
            })
            11 -> "queue download" to (chapters.filter { chapter -> state.downloadQueue.none { it.chapterId == chapter.id } }
                .randomOrNull(random)?.let { next.copy(downloadQueue = state.downloadQueue + download(random, it, state.downloadQueue.size)) }
                ?: next)
            12 -> "remove download" to next.copy(downloadQueue = state.downloadQueue.drop(1))
            13 -> "reorder downloads" to next.copy(downloadQueue = state.downloadQueue.shuffled(random).mapIndexed { index, item ->
                item.copy(position = index)
            })
            14 -> "edit manga" to next.copy(mangas = state.mangas.updatingRandom(random) { it.copy(title = it.title + "'") })
            else -> "copies only" to next.copy(chapters = chapters.toList(), histories = state.histories.toList())
        }
    }

    private fun <T> List<T>.updatingRandom(random: Random, change: (T) -> T): List<T> =
        if (isEmpty()) this else random.nextInt(size).let { replacing(it, change(this[it])) }

    private fun <T> List<T>.replacing(index: Int, value: T): List<T> = toMutableList().apply { this[index] = value }

    private companion object {
        const val SEQUENCES = 60
        const val STEPS = 80
    }
}
