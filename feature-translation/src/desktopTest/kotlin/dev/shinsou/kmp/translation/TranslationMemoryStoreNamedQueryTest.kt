package dev.shinsou.kmp.translation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The translation store reads every row by the names of the very SELECT it runs
 * ([dev.shinsou.kmp.content.sql.ContentRow.selectColumns]), so these round trips cover each projection:
 * aliases, aggregates, joins, DISTINCT, and NULL columns. (Split from the app's NamedQueryStoreTest.)
 */
class TranslationMemoryStoreNamedQueryTest {
    @Test
    fun translationRowsRoundTripThroughTheirProjections() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
        val store = TranslationMemoryStore(driver)
        val key = MemoryKey("hash-1", "ja", "en", "engine")
        store.store(mapOf(key to "hello"), paid = false, now = 10, exportable = true)
        store.store(mapOf(MemoryKey("hash-2", "ja", "en", "engine") to "paid line"), paid = true, now = 11)
        assertEquals(5L, store.freeBytes())
        assertEquals(9L, store.paidBytes())
        assertEquals(mapOf("hash-1" to "hello"), store.lookup(listOf("hash-1", "absent"), "ja", "en", "engine", now = 12, exportable = true))

        val chapter = ChapterRecord("work", "chapter", "en", "engine", "source", 1, complete = true, paid = false, updatedAt = 20)
        store.saveChapter(chapter)
        assertEquals(listOf(chapter), store.chapters("work", "en"))
        assertEquals(setOf("chapter"), store.completeChapterKeys("work", to = null, provider = null))

        // Without labels the joined title columns are NULL.
        val unlabelled = store.storedChapters(10).single()
        assertNull(unlabelled.workTitle)
        assertNull(unlabelled.chapterTitle)
        assertNull(unlabelled.sourceName)
        assertEquals(1, unlabelled.lineCount)
        store.saveLabels("work", "chapter", TranslationLabels("Work", "Chapter", sourceName = null))
        val labelled = store.storedChapters(10).single()
        assertEquals("Work", labelled.workTitle)
        assertEquals("Chapter", labelled.chapterTitle)
        assertNull(labelled.sourceName)

        assertEquals(1, store.putGlossaryTerm("work", "en", "名前", "Name").id)
        assertEquals(listOf("名前" to "Name"), store.glossary("work", "en").map { it.source to it.target })
        val backup = store.exportBackup(includePaid = true, includeFree = true, segmentByteBudget = Long.MAX_VALUE)
        assertEquals(listOf("hello"), backup.segments.map { it.text })
        assertEquals(listOf("chapter"), backup.chapters.map { it.chapterKey })
    }
}
