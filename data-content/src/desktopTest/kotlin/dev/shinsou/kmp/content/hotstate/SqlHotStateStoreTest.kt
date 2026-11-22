package dev.shinsou.kmp.content.hotstate

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.domain.model.ReadingScope
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.domain.persistence.ChapterProgress
import dev.shinsou.kmp.domain.persistence.HotDelta
import dev.shinsou.kmp.domain.persistence.HotRows
import dev.shinsou.kmp.domain.persistence.HotState
import dev.shinsou.kmp.domain.persistence.HotStatePairing
import dev.shinsou.kmp.domain.persistence.Ordered
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C3-2 (docs/architecture/0007 §6.3.7): the `legacy_hot_state` tables. */
class SqlHotStateStoreTest {
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val store = SqlHotStateStore(driver)

    @AfterTest
    fun close() = driver.close()

    @Test
    fun everyValueComesBackAsItWasWrittenInItsOrder() {
        val state = sample()
        store.apply(fullDelta(HotState.EMPTY, state))
        assertEquals(state, store.load())
        // Read by a store opened afresh on the same database.
        assertEquals(state, SqlHotStateStore(driver).load())
    }

    @Test
    fun randomDeltasLeaveTheTablesAsTheStateTheyLeadTo() {
        repeat(SEQUENCES) { sequence ->
            val random = Random(sequence)
            store.clear()
            var state = HotState.EMPTY
            repeat(STEPS) { step ->
                val next = mutate(random, state)
                val delta = orderedDelta(state, next)
                assertEquals(next, state.applying(delta), "sequence $sequence step $step: in memory")
                store.apply(delta)
                assertEquals(next, store.load(), "sequence $sequence step $step")
                state = next
            }
        }
    }

    @Test
    fun writingADeltaTwiceChangesNothing() {
        val first = sample()
        store.apply(fullDelta(HotState.EMPTY, first))
        val second = mutate(Random(7), mutate(Random(8), first))
        val delta = orderedDelta(first, second)
        store.apply(delta)
        store.apply(delta)
        assertEquals(second, store.load())
        store.apply(HotDelta.EMPTY)
        assertEquals(second, store.load())
    }

    @Test
    fun aRolledBackTransactionLeavesTheRowsAndThePairingAsTheyWere() {
        val before = sample()
        store.apply(fullDelta(HotState.EMPTY, before))
        store.setPairing(HotStatePairing(jsonRevision = 4, token = "before"))
        val host = object : TransacterImpl(driver) {}

        assertFailsWith<IllegalStateException> {
            host.transaction {
                store.apply(fullDelta(before, HotState.EMPTY))
                store.setPairing(HotStatePairing(jsonRevision = 5, token = "after"))
                assertEquals(HotState.EMPTY, store.load())
                error("the host's own write failed")
            }
        }

        assertEquals(before, store.load())
        assertEquals(HotStatePairing(4, "before"), store.pairing())

        host.transaction {
            store.apply(fullDelta(before, HotState.EMPTY))
            store.setPairing(HotStatePairing(jsonRevision = 5, token = "after"))
        }
        assertEquals(HotState.EMPTY, store.load())
        assertEquals(HotStatePairing(5, "after"), store.pairing())
    }

    @Test
    fun clearDeletesTheRowsAndThePairing() {
        assertNull(store.pairing())
        store.apply(fullDelta(HotState.EMPTY, sample()))
        store.setPairing(HotStatePairing(jsonRevision = Long.MAX_VALUE - 2, token = "t"))
        assertEquals(HotStatePairing(Long.MAX_VALUE - 2, "t"), store.pairing())
        store.clear()
        assertEquals(HotState.EMPTY, store.load())
        assertNull(store.pairing())
        store.setPairing(HotStatePairing(1, "u"))
        store.setPairing(null)
        assertNull(store.pairing())
    }

    /** C3-3: the rows and the pairing in one transaction; every row at once after a failed write. */
    @Test
    fun aCommitWritesTheRowsAndThePairingTogether() {
        val first = sample()
        val pairing = HotStatePairing(jsonRevision = 4, token = "t", pendingJsonRevision = 6, hotRevision = 5)
        store.commit(HotRows.All(first), pairing)
        assertEquals(first, store.load())
        assertEquals(pairing, store.pairing())

        val second = mutate(Random(3), first)
        val host = object : TransacterImpl(driver) {}
        assertFailsWith<IllegalStateException> {
            host.transaction {
                store.commit(HotRows.Changed(orderedDelta(first, second)), pairing.copy(pendingJsonRevision = null, hotRevision = 9))
                error("the host's own write failed")
            }
        }
        assertEquals(first, store.load())
        assertEquals(pairing, store.pairing())

        store.commit(HotRows.All(second), pairing.copy(pendingJsonRevision = null))
        assertEquals(second, store.load())
        assertEquals(HotStatePairing(jsonRevision = 4, token = "t", hotRevision = 5), store.pairing())
        store.commit(HotRows.Unchanged, pairing.copy(hotRevision = 10))
        assertEquals(second, store.load())
        assertEquals(10, store.pairing()?.hotRevision)
    }

    @Test
    fun aDefaultProgressIsNotKept() {
        store.apply(HotDelta(progress = mapOf(1L to ChapterProgress(read = true), 2L to ChapterProgress.DEFAULT)))
        assertEquals(mapOf(1L to ChapterProgress(read = true)), store.load().chapterProgress)
        store.apply(HotDelta(progress = mapOf(1L to ChapterProgress.DEFAULT)))
        assertTrue(store.load().chapterProgress.isEmpty())
    }

    @Test
    fun aDownloadStateThisVersionDoesNotKnowReadsAsAnError() {
        store.apply(HotDelta(downloads = listOf(Ordered(0, DownloadQueueItem(id = "1_2", mangaId = 1, chapterId = 2)))))
        driver.execute(null, "UPDATE legacy_download_queue SET state = 'VERIFYING'", 0).value
        assertEquals(DownloadState.ERROR, store.load().downloads.single().state)
    }

    @Test
    fun theUiThreadIsRefused() {
        val result = AtomicReference<Result<HotState>>()
        SwingUtilities.invokeAndWait { result.set(runCatching { store.load() }) }
        assertTrue(result.get().exceptionOrNull() is IllegalStateException)
    }

    private fun sample(): HotState {
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        val locator = ReadingLocator.Text(
            schemaVersion = 1,
            scope = ReadingScope(
                schemaVersion = 1,
                publicationId = publication,
                acquisitionId = "22222222-2222-4222-8222-222222222222",
                unitId = UnitKey(publication, "33333333-3333-4333-8333-333333333333"),
                contentRevision = 3,
            ),
            resourceId = "body",
            blockId = "paragraph-1",
            offset = 12,
        )
        return HotState(
            chapterProgress = mapOf(
                -5L to ChapterProgress(read = true),
                7L to ChapterProgress(bookmark = true, lastPageRead = Int.MAX_VALUE),
                Long.MAX_VALUE to ChapterProgress(read = true, bookmark = true, lastPageRead = 1),
            ),
            // Not in id order: the list order is what comes back.
            histories = listOf(
                History(id = 9, chapterId = 7, lastRead = Long.MAX_VALUE, timeRead = 0, lastLocator = locator, lastPageCount = 40),
                History(id = -1, chapterId = -5, lastRead = Long.MIN_VALUE, timeRead = 123_456_789_012),
                History(id = 3, chapterId = 8),
            ),
            downloads = listOf(
                DownloadQueueItem(
                    id = "2_8",
                    mangaId = 2,
                    chapterId = 8,
                    state = DownloadState.DOWNLOADING,
                    progress = 0.1 + 0.2,
                    downloadedPages = 3,
                    totalPages = 10,
                    position = 5,
                    queuedAt = 1_700_000_000_000,
                    updatedAt = 1_700_000_000_123,
                    destinationPath = "/下載/漫画 1/ch'8\"",
                    errorMessage = null,
                ),
                DownloadQueueItem(
                    id = "1_7",
                    mangaId = 1,
                    chapterId = 7,
                    state = DownloadState.ERROR,
                    progress = Double.MIN_VALUE,
                    position = 0,
                    errorMessage = "HTTP 503\nretry",
                    visibleInQueue = false,
                ),
            ),
        )
    }

    /** One random change of the rows, as the repository's mutations make them. */
    private fun mutate(random: Random, state: HotState): HotState = when (random.nextInt(9)) {
        0 -> state.copy(chapterProgress = state.chapterProgress + (random.nextLong(1, 40) to randomProgress(random)))
        1 -> state.copy(chapterProgress = state.chapterProgress - (state.chapterProgress.keys.randomOrNull(random) ?: 0L))
        2 -> state.copy(histories = state.histories + History(id = (state.histories.maxOfOrNull { it.id } ?: 0) + 1, chapterId = random.nextLong(40)))
        3 -> state.copy(histories = state.histories.updatingRandom(random) { it.copy(lastRead = random.nextLong(), timeRead = random.nextLong(0, 99)) })
        4 -> state.copy(histories = state.histories.shuffled(random).drop(random.nextInt(2)))
        5 -> state.copy(downloads = state.downloads + random.nextLong(1, 500).let { chapter ->
            DownloadQueueItem(id = "1_$chapter", mangaId = 1, chapterId = chapter, position = state.downloads.size)
        }.takeIf { new -> state.downloads.none { it.id == new.id } }.let(::listOfNotNull))
        6 -> state.copy(downloads = state.downloads.updatingRandom(random) {
            it.copy(state = DownloadState.entries.random(random), progress = random.nextDouble(), downloadedPages = it.downloadedPages + 1)
        })
        7 -> state.copy(downloads = state.downloads.shuffled(random).drop(random.nextInt(2)))
        else -> state
    }

    private fun randomProgress(random: Random) =
        ChapterProgress(random.nextBoolean(), random.nextBoolean(), random.nextInt(0, 3)).takeUnless { it.isDefault }
            ?: ChapterProgress(read = true)

    private fun <T> List<T>.updatingRandom(random: Random, change: (T) -> T): List<T> =
        if (isEmpty()) this else random.nextInt(size).let { index -> toMutableList().apply { this[index] = change(this[index]) } }

    /** Every row of [next] and the removal of every other row of [previous]. */
    private fun fullDelta(previous: HotState, next: HotState) = HotDelta(
        progress = next.chapterProgress,
        removedProgress = previous.chapterProgress.keys - next.chapterProgress.keys,
        histories = next.histories.mapIndexed(::Ordered),
        removedHistories = previous.histories.map { it.id }.toSet() - next.histories.map { it.id }.toSet(),
        downloads = next.downloads.mapIndexed(::Ordered),
        removedDownloads = previous.downloads.map { it.id }.toSet() - next.downloads.map { it.id }.toSet(),
    )

    /** Only the rows that changed or moved, as the repository computes them. */
    private fun orderedDelta(previous: HotState, next: HotState) = HotDelta(
        progress = next.chapterProgress.filter { (id, value) -> previous.chapterProgress[id] != value },
        removedProgress = previous.chapterProgress.keys - next.chapterProgress.keys,
        histories = next.histories.mapIndexedNotNull { index, value -> Ordered(index, value).takeIf { previous.histories.getOrNull(index) != value } },
        removedHistories = previous.histories.map { it.id }.toSet() - next.histories.map { it.id }.toSet(),
        downloads = next.downloads.mapIndexedNotNull { index, value -> Ordered(index, value).takeIf { previous.downloads.getOrNull(index) != value } },
        removedDownloads = previous.downloads.map { it.id }.toSet() - next.downloads.map { it.id }.toSet(),
    )

    private companion object {
        const val SEQUENCES = 20
        const val STEPS = 60
    }
}
