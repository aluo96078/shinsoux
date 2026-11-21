package dev.shinsou.kmp.translation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A chapter translated in parts (manga's page-by-page flow), then recorded as a whole. */
class TranslationSegmentTest {
    private class Provider : TranslationProvider {
        override val id = "fake"
        override val paid = false
        val calls = mutableListOf<List<String>>()
        val contexts = mutableListOf<List<Pair<String, String>>>()
        override fun route(from: String, to: String) = listOf(TranslationHop(from, to, "fake"))
        override suspend fun translate(hop: TranslationHop, segments: List<String>): List<String> {
            calls += segments
            contexts += currentCoroutineContext()[TranslationContext]?.earlier.orEmpty()
            return segments.map { "譯:$it" }
        }
    }

    @Test
    fun partsKeepTheirLinesAndOnlyTheWholeChapterIsRecorded() = runBlocking<Unit> {
        val memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val service = TranslationService(memory, now = { 1L })
        val provider = Provider()
        fun request(text: String, segment: Boolean, context: List<Pair<String, String>> = emptyList()) =
            ChapterTranslationRequest("work", "c1", text, "ja", "zh-Hant", segment = segment, context = context)

        service.translate(request("一\n二", segment = true), provider)
        assertTrue(memory.completeChapterKeys("work", "zh-Hant", "fake").isEmpty(), "a part does not record the chapter as translated")
        service.translate(request("三", segment = true, context = listOf("一" to "譯:一", "二" to "譯:二")), provider)
        assertEquals(listOf("一" to "譯:一", "二" to "譯:二"), provider.contexts.last(), "the earlier lines reach the provider")
        assertEquals(emptyList(), provider.contexts.first(), "no context, none sent")

        val whole = service.translate(request("一\n二\n三", segment = false), provider)
        assertEquals(2, provider.calls.size, "the whole chapter finds every line in the memory")
        assertTrue(whole.complete)
        assertEquals(setOf("c1"), memory.completeChapterKeys("work", "zh-Hant", "fake"))

        // a later part does not undo the whole chapter's record
        service.translate(request("三", segment = true), provider)
        assertEquals(setOf("c1"), memory.completeChapterKeys("work", "zh-Hant", "fake"))
    }

    @Test
    fun aChapterLeftAfterSomePartsIsListedAndCanBeDeleted() = runBlocking<Unit> {
        val memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val service = TranslationService(memory, now = { 1L })
        val provider = Provider()
        fun part(text: String) = ChapterTranslationRequest("work", "c1", text, "ja", "zh-Hant", segment = true)

        service.translate(part("一\n二"), provider)
        service.translate(part("三"), provider)
        val listed = memory.storedChapters(10).single()
        assertEquals("c1", listed.chapterKey)
        assertTrue(!listed.complete, "left before its end: listed, but not as translated")

        memory.deleteWorkTranslations("work")
        assertTrue(memory.storedChapters(10).isEmpty())
        provider.calls.clear()
        service.translate(part("一\n二\n三"), provider)
        assertEquals(listOf(listOf("一", "二", "三")), provider.calls, "every part's lines went with the chapter")
    }
}
