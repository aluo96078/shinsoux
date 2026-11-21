package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.domain.model.ReadingLocator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SyncPendingReadingTest {
    @Test
    fun readingOfChaptersNotStoredHereIsShownUntilTheyAre() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            // A finished u1, then stopped on page 4 of u2.
            val manga = a.repository.upsertManga(a.fixtures.manga("book-1", "One"))
            val (u1, u2) = a.repository.upsertChapters(a.fixtures.chapters(manga.id, "book-1", listOf("u1", "u2", "u3")))
            val locator = ReadingLocator.Text(
                schemaVersion = 1, scope = a.identity.scopeFor(u2)!!, resourceId = "body", blockId = "b-1", offset = 7, progression = 0.4,
            )
            a.repository.markChapterProgress(u1.id, lastPageRead = 9, read = true, readAt = 1_700_000_100_000)
            a.repository.markChapterProgress(u2.id, lastPageRead = 3, read = false, readAt = 1_700_000_200_000, lastLocator = locator)
            a.signIn(register = true)
            a.sync()

            // B has the title but none of its chapters: the reading waits, and is shown meanwhile.
            b.signIn()
            b.sync()
            val pending = assertNotNull(b.service.pendingReading(TestSourceKey, "book-1"))
            assertEquals("u2", pending.lastReadUnitId)
            assertEquals(1_700_000_200_000, pending.lastReadAtMillis)
            assertEquals(setOf("u1"), pending.readUnitIds)
            assertEquals(mapOf("u2" to 3), pending.lastPageIndexByUnit)
            val created = b.repository.currentSnapshot.mangas.single()
            val (_, chapterB) = b.fixtures.chapters(created.id, "book-1", listOf("u1", "u2"))
            val scopeB = b.identity.scopeFor(chapterB)!!
            val placed = assertIs<ReadingLocator.Text>(pending.locator("u2", scopeB))
            assertEquals(7, placed.offset)
            assertEquals(scopeB, placed.scope, "placed in this device's own scope")
            assertNull(pending.locator("u1", scopeB), "only the chapter the position is about")
            assertNull(b.service.pendingReading(TestSourceKey, "book-2"))
            assertEquals(
                mapOf(created.id to 1_700_000_200_000),
                b.service.pendingLastReadTimes(b.repository.currentSnapshot.mangas),
                "when the title was last read elsewhere, for the library's continue banner",
            )

            // Once B stores the chapters, the reading applies to them and nothing waits anymore.
            b.repository.upsertChapters(b.fixtures.chapters(created.id, "book-1", listOf("u1", "u2", "u3")))
            b.service.retryPendingRemote()
            assertNull(b.service.pendingReading(TestSourceKey, "book-1"))
            assertEquals(emptyMap(), b.service.pendingLastReadTimes(b.repository.currentSnapshot.mangas))
        } finally {
            a.close(); b.close()
        }
    }
}
