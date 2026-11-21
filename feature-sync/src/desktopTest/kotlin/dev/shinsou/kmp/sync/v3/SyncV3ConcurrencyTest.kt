package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.domain.model.ChapterPatch
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R4-7 (docs/architecture/0006): the cycle lock, a lost push answer, and a half-finished apply. */
class SyncV3ConcurrencyTest {
    private fun TestDevice.view(): Map<String, String> {
        val snapshot = repository.currentSnapshot
        val result = sortedMapOf<String, String>()
        result["titles"] = snapshot.mangas.size.toString()
        result["chapters"] = snapshot.chapters.size.toString()
        snapshot.mangas.forEach { m -> result["fav:${m.title}"] = m.favorite.toString() }
        snapshot.chapters.forEach { c ->
            val title = snapshot.mangas.first { it.id == c.mangaId }.title
            result["ch:$title:${c.name}"] = "${c.read}/${c.bookmark}/${c.lastPageRead}"
        }
        return result
    }

    /** The title as browsing it here would create it: chapters come from the source, not from sync. */
    private suspend fun TestDevice.knowTitle(units: List<String>) {
        val manga = repository.upsertManga(fixtures.manga("t", "Title", favorite = false))
        repository.upsertChapters(fixtures.chapters(manga.id, "t", units))
    }

    @Test
    fun twoSyncsAtOnceUploadAnEditOnce() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B", fixtures = a.fixtures)
        try {
            val manga = a.repository.upsertManga(a.fixtures.manga("t", "Title"))
            val chapters = a.repository.upsertChapters(a.fixtures.chapters(manga.id, "t", listOf("u1", "u2")))
            a.signIn(register = true)
            assertTrue(a.sync())
            b.knowTitle(listOf("u1", "u2"))
            b.signIn()
            assertTrue(b.sync())
            // A change from the other device waiting to be pulled, and a local edit waiting to be pushed.
            val remoteChapter = b.repository.currentSnapshot.chapters.first { it.name == chapters.last().name }
            b.repository.patchChapter(remoteChapter.id, ChapterPatch(bookmark = true))
            assertTrue(b.sync())
            a.repository.patchChapter(chapters.first().id, ChapterPatch(read = true))
            val pushesBefore = server.pushCount
            server.pulledRecords.clear()

            val first = async(Dispatchers.Default) { a.sync() }
            val second = async(Dispatchers.Default) { a.sync() }
            assertTrue(first.await())
            assertTrue(second.await())

            assertEquals(1, server.pushCount - pushesBefore, "the second cycle found nothing left to upload")
            assertEquals(0, a.store.dirtyRecordCount())
            val pulled = server.pulledRecords.toList()
            assertTrue(pulled.isNotEmpty(), "the other device's change was pulled")
            assertEquals(pulled.distinct(), pulled, "no change was pulled by both cycles")
            assertTrue(a.repository.currentSnapshot.chapters.first { it.id == chapters.last().id }.bookmark)
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun aSyncPressedOnTheUiThreadLooksPublicationsUpElsewhere() = runBlocking<Unit> {
        val lookupThreads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val publications = object : HashMap<PublicationKey, Publication>() {
            override fun get(key: PublicationKey): Publication? {
                lookupThreads += Thread.currentThread().name
                return super.get(key)
            }
        }
        val server = FakeSyncServer()
        val a = TestDevice(server, "A", fixtures = ExtensionFixtures(publications))
        val ui = Executors.newSingleThreadExecutor { Thread(it, "ui-thread") }.asCoroutineDispatcher()
        try {
            val manga = a.repository.upsertManga(a.fixtures.manga("t", "Title"))
            val chapters = a.repository.upsertChapters(a.fixtures.chapters(manga.id, "t", listOf("u1")))
            a.signIn(register = true)
            assertTrue(a.sync())
            a.repository.patchChapter(chapters.single().id, ChapterPatch(read = true))
            lookupThreads.clear()

            assertEquals(ManualSyncResult.Synced, withContext(ui) { a.service.syncFromUser() })

            assertTrue(lookupThreads.isNotEmpty(), "the edited chapter's publication was looked up")
            assertTrue(lookupThreads.none { it.startsWith("ui-thread") }, "no publication lookup ran on the caller's thread")
            assertEquals(0, a.store.dirtyRecordCount(), "the chapter edit was uploaded")
        } finally {
            ui.close()
            a.close()
        }
    }

    @Test
    fun sharingAndReviewingFromTheUiThreadUploadPendingChangesElsewhere() = runBlocking<Unit> {
        val lookupThreads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val publications = object : HashMap<PublicationKey, Publication>() {
            override fun get(key: PublicationKey): Publication? {
                lookupThreads += Thread.currentThread().name
                return super.get(key)
            }
        }
        val server = FakeSyncServer()
        val a = TestDevice(server, "A", fixtures = ExtensionFixtures(publications))
        val ui = Executors.newSingleThreadExecutor { Thread(it, "ui-thread") }.asCoroutineDispatcher()
        try {
            val manga = a.repository.upsertManga(a.fixtures.manga("t", "Title"))
            val chapters = a.repository.upsertChapters(a.fixtures.chapters(manga.id, "t", listOf("u1", "u2")))
            a.signIn(register = true)
            assertTrue(a.sync())
            val actions = listOf<suspend () -> Unit>(
                { a.service.shareWork("work", "someone", "") },
                { a.service.writeReview("work", rating = 5, body = "", spoiler = false) },
            )
            actions.forEachIndexed { index, action ->
                a.repository.patchChapter(chapters[index].id, ChapterPatch(read = true))
                lookupThreads.clear()
                // The fake server has no share or review endpoints; only the upload before them matters.
                withContext(ui) { action() }
                assertTrue(lookupThreads.isNotEmpty(), "the edited chapter's publication was looked up")
                assertTrue(lookupThreads.none { it.startsWith("ui-thread") }, "no publication lookup ran on the caller's thread")
                assertEquals(0, a.store.dirtyRecordCount(), "the chapter edit was uploaded")
            }
        } finally {
            ui.close()
            a.close()
        }
    }

    @Test
    fun aVersionRefusalDuringACycleSaysTheVersionsDoNotMatch() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            val manga = a.repository.upsertManga(a.fixtures.manga("t", "Title"))
            a.repository.upsertChapters(a.fixtures.chapters(manga.id, "t", listOf("u1")))
            a.signIn(register = true)
            // A 426 whose body carries some other error code still means "update the app or server".
            server.failNextPushWith = 426
            assertFalse(a.sync())
            assertEquals(
                "The sync server needs a different app version; update Shinsou X or the server",
                a.service.state.value.lastError,
            )
        } finally {
            a.close()
        }
    }

    @Test
    fun comingBackDuringTheBackgroundFlushKeepsTheStreamOpen() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            a.signIn(register = true)
            a.service.onForeground()
            assertTrue(a.service.streamOpen)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            a.onClockRead = {
                // Held inside the background flush: the app has left the screen by then.
                if (!a.service.inForeground) {
                    a.onClockRead = null
                    entered.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            val background = async(Dispatchers.Default) { a.service.onBackground() }
            assertTrue(entered.await(30, TimeUnit.SECONDS))

            a.service.onForeground()
            release.countDown()
            background.await()

            assertTrue(a.service.streamOpen, "the stream the app reopened was not closed by the older flush")
        } finally {
            a.close()
        }
    }

    @Test
    fun aPressDuringALongCycleReportsInProgress() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            a.signIn(register = true)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            a.onClockRead = {
                a.onClockRead = null
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            val background = async(Dispatchers.Default) { a.sync() }
            assertTrue(entered.await(30, TimeUnit.SECONDS))

            assertEquals(ManualSyncResult.InProgress, a.service.syncFromUser())

            release.countDown()
            assertTrue(background.await())
            assertEquals(ManualSyncResult.Synced, a.service.syncFromUser())
        } finally {
            a.close()
        }
    }

    @Test
    fun aPushWhoseAnswerWasLostIsResentWithoutChangingTheResult() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            val manga = a.repository.upsertManga(a.fixtures.manga("t", "Title"))
            val chapters = a.repository.upsertChapters(a.fixtures.chapters(manga.id, "t", listOf("u1", "u2")))
            a.signIn(register = true)
            assertTrue(a.sync())
            a.repository.patchChapter(chapters.first().id, ChapterPatch(read = true, lastPageRead = 7))

            server.loseNextPushResponse = true
            assertFalse(a.sync(), "the device never heard that the push arrived")
            val stored = server.accountRecords("alice")
            assertTrue(a.store.dirtyRecordCount() > 0, "the edit is still waiting to upload")

            assertTrue(a.sync())
            assertEquals(stored, server.accountRecords("alice"), "sending it again changed nothing on the server")
            assertEquals(0, a.store.dirtyRecordCount())

            val b = TestDevice(server, "B", fixtures = a.fixtures)
            try {
                b.knowTitle(listOf("u1", "u2"))
                b.signIn()
                assertTrue(b.sync())
                assertEquals(a.view(), b.view())
            } finally {
                b.close()
            }
        } finally {
            a.close()
        }
    }

    @Test
    fun applyingAgainAfterTheMarkWasLostGivesTheSameLibrary() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B", fixtures = a.fixtures)
        try {
            val manga = a.repository.upsertManga(a.fixtures.manga("t", "Title"))
            val chapters = a.repository.upsertChapters(a.fixtures.chapters(manga.id, "t", listOf("u1", "u2", "u3")))
            chapters.take(2).forEach { a.repository.patchChapter(it.id, ChapterPatch(read = true)) }
            a.signIn(register = true)
            assertTrue(a.sync())
            b.knowTitle(listOf("u1", "u2", "u3"))
            b.signIn()
            assertTrue(b.sync())
            val applied = b.view()
            assertEquals(a.view(), applied)

            // The library snapshot was written but the transaction marking the records applied
            // never committed (the process died between the two).
            b.driver.execute(null, "UPDATE sync3_record SET unapplied = 1, pending_reason = NULL", 0).value
            assertTrue(b.sync())

            assertEquals(applied, b.view())
            assertEquals(0, b.store.unappliedCount(emptySet()))
        } finally {
            a.close()
            b.close()
        }
    }
}
