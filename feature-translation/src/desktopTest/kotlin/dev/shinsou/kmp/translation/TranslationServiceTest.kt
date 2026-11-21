package dev.shinsou.kmp.translation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationServiceTest {
    /** Two hops through English, like the offline engine: `<to>(text)`. */
    private class PivotProvider(override val paid: Boolean = false, override val id: String = "fake") : TranslationProvider {
        val calls = ArrayList<Pair<TranslationHop, List<String>>>()
        var failOn: String? = null

        override fun route(from: String, to: String): List<TranslationHop>? = when {
            from == to || from == "xx" -> null
            from == "en" || to == "en" -> listOf(TranslationHop(from, to, "fake:$from-$to:1"))
            else -> listOf(TranslationHop(from, "en", "fake:$from-en:1"), TranslationHop("en", to, "fake:en-$to:1"))
        }

        override suspend fun translate(hop: TranslationHop, segments: List<String>): List<String> {
            calls += hop to segments
            failOn?.let { marker -> if (segments.any { marker in it }) throw TranslationException(TranslationFailure.ENGINE_FAILED, "boom") }
            return segments.map { "${hop.to}($it)" }
        }

        fun sent(): List<String> = calls.flatMap { it.second }
    }

    private fun fixture(): Pair<TranslationMemoryStore, TranslationService> {
        val store = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        var clock = 1_000L
        return store to TranslationService(store, now = { clock++ }, batchSize = 2)
    }

    private fun request(text: String, to: String = "zh-Hant", chapter: String = "c1") =
        ChapterTranslationRequest("work", chapter, text, "ja", to)

    @Test
    fun aChapterIsTranslatedLineForLineAndNotTranslatedAgain() = runBlocking<Unit> {
        val (store, service) = fixture()
        val provider = PivotProvider()
        val progress = ArrayList<Pair<Int, Int>>()

        val first = service.translate(request("一\n\n二\n三"), provider) { done, total -> progress += done to total }

        assertEquals(listOf("zh-Hant(en(一))", null, "zh-Hant(en(二))", "zh-Hant(en(三))"), first.segments.map { it.translation })
        assertTrue(first.complete)
        assertEquals(3, first.translatedSegments)
        assertEquals(0 to 3, progress.first())
        assertEquals(3 to 3, progress.last())
        assertEquals(progress.map { it.first }.sorted(), progress.map { it.first }, "progress never goes back")
        assertTrue(progress.any { it.first in 1..2 }, "progress is reported while the chapter is being translated")
        // One hop at a time: every line through the first model before the second is loaded.
        assertEquals(listOf("ja", "ja", "en", "en"), provider.calls.map { it.first.from })
        assertEquals("zh-Hant(en(一))\n\nzh-Hant(en(二))\nzh-Hant(en(三))", first.text())

        provider.calls.clear()
        val again = service.translate(request("一\n\n二\n三"), provider)
        assertTrue(provider.calls.isEmpty(), "everything comes from the memory")
        assertEquals(3, again.reusedSegments)
        assertEquals(first.segments, again.segments)
        assertEquals(first.segments, service.stored(request("一\n\n二\n三"), provider)?.segments)
        assertTrue(store.chapters("work", "zh-Hant").single().complete)
    }

    @Test
    fun onlyChangedLinesAreSentWhenAChapterIsRevisedOrReadFromAnotherSource() = runBlocking<Unit> {
        val (_, service) = fixture()
        val provider = PivotProvider()
        service.translate(request("一\n二\n三"), provider)
        provider.calls.clear()

        // Another chapter id (a different source of the same work) with one revised line.
        val revised = service.translate(request("一\n二改\n三", chapter = "other-source-c1"), provider)

        assertEquals(listOf("二改", "en(二改)"), provider.sent())
        assertEquals(2, revised.reusedSegments)
        assertEquals(1, revised.translatedSegments)
    }

    @Test
    fun theEnglishHalfIsReusedForAnotherTargetLanguage() = runBlocking<Unit> {
        val (_, service) = fixture()
        val provider = PivotProvider()
        service.translate(request("一\n二"), provider)
        provider.calls.clear()

        service.translate(request("一\n二", to = "zh-Hans"), provider)

        assertEquals(listOf("en" to "zh-Hans"), provider.calls.map { it.first.from to it.first.to }.distinct())
    }

    @Test
    fun glossaryTermsKeepOneRenderingAndARenamedTermNeedsNoNewTranslation() = runBlocking<Unit> {
        val (store, service) = fixture()
        val provider = PivotProvider()
        store.putGlossaryTerm("work", "zh-Hant", "葵", "小葵")
        store.putGlossaryTerm("work", "zh-Hant", "健太", "健太郎")

        val first = service.translate(request("葵は健太を見た"), provider)

        assertEquals(listOf("N1XはN2Xを見た"), provider.calls.first().second, "the engine sees codes, not names")
        assertEquals("zh-Hant(en(小葵は健太郎を見た))", first.segments.single().translation)

        provider.calls.clear()
        store.putGlossaryTerm("work", "zh-Hant", "葵", "阿葵")
        val renamed = service.translate(request("葵は健太を見た"), provider)
        assertTrue(provider.calls.isEmpty())
        assertEquals("zh-Hant(en(阿葵は健太郎を見た))", renamed.segments.single().translation)

        // A new term only costs the lines it appears in.
        service.translate(request("葵は健太を見た\n蓮は寝た"), provider)
        provider.calls.clear()
        store.putGlossaryTerm("work", "zh-Hant", "蓮", "小蓮")
        service.translate(request("葵は健太を見た\n蓮は寝た"), provider)
        assertEquals(listOf("N3Xは寝た", "en(N3Xは寝た)"), provider.sent())
    }

    @Test
    fun aFailureKeepsWhatWasTranslatedAndTheNextRunContinues() = runBlocking<Unit> {
        val (store, service) = fixture()
        val provider = PivotProvider().apply { failOn = "三" }

        assertFailsWith<TranslationException> { service.translate(request("一\n二\n三\n四"), provider) }
        assertFalse(store.chapters("work", "zh-Hant").single().complete)
        // The first hop of the lines before the failure is kept, so they are not sent to it again.

        provider.failOn = null
        provider.calls.clear()
        val finished = service.translate(request("一\n二\n三\n四"), provider)
        assertTrue(finished.complete)
        assertFalse(provider.sent().any { it == "一" || it == "二" })
        assertTrue(store.chapters("work", "zh-Hant").single().complete)
    }

    @Test
    fun anUnsupportedPairIsReportedAndNothingIsStored() = runBlocking<Unit> {
        val (store, service) = fixture()
        val error = assertFailsWith<TranslationException> {
            service.translate(ChapterTranslationRequest("work", "c1", "text", "xx", "zh-Hant"), PivotProvider())
        }
        assertEquals(TranslationFailure.UNSUPPORTED_PAIR, error.failure)
        assertTrue(store.chapters("work", "zh-Hant").isEmpty())
        assertNull(service.stored(ChapterTranslationRequest("work", "c1", "text", "xx", "zh-Hant"), PivotProvider()))
    }

    @Test
    fun freeTranslationsAreEvictedOldestFirstAndPaidOnesAreKept() = runBlocking<Unit> {
        val (store, service) = fixture()
        service.translate(request("舊的一行"), PivotProvider())
        service.translate(request("付費的一行", chapter = "c2"), PivotProvider(paid = true, id = "paid"))
        service.translate(request("新的一行", chapter = "c3"), PivotProvider())
        val paidBefore = store.paidBytes()
        assertTrue(store.freeBytes() > 0 && paidBefore > 0)

        val newest = store.freeBytes() / 2
        assertTrue(store.evictFree(newest) > 0)

        assertTrue(store.freeBytes() <= newest)
        assertEquals(paidBefore, store.paidBytes())
        val chapters = store.chapters("work", "zh-Hant").associateBy { it.chapterKey }
        assertFalse(chapters.getValue("c1").complete, "a free chapter is checked again after an eviction")
        assertTrue(chapters.getValue("c2").complete, "a paid chapter lost nothing")
        val provider = PivotProvider()
        service.translate(request("舊的一行"), provider)
        assertTrue("舊的一行" in provider.sent(), "the oldest free line was evicted")
    }

    @Test
    fun aRepeatedLineIsSentOnceAndAnEmptyAnswerIsNotATranslation() = runBlocking<Unit> {
        val (store, service) = fixture()
        val provider = object : TranslationProvider {
            override val id = "blank"
            override val paid = true
            val sent = ArrayList<String>()
            var blank = true
            override fun route(from: String, to: String) = listOf(TranslationHop(from, to, "blank:1"))
            override suspend fun translate(hop: TranslationHop, segments: List<String>): List<String> {
                sent += segments
                return segments.map { if (blank && it == "空") "  " else "T($it)" }
            }
        }

        val first = service.translate(request("重複\n重複\n空\n重複"), provider)

        assertEquals(listOf("重複", "空"), provider.sent, "a repeated line is paid for once")
        assertEquals(listOf("T(重複)", "T(重複)", null, "T(重複)"), first.segments.map { it.translation })
        assertFalse(first.complete)
        assertFalse(store.chapters("work", "zh-Hant").single().complete)

        provider.blank = false
        provider.sent.clear()
        val second = service.translate(request("重複\n重複\n空\n重複"), provider)
        assertEquals(listOf("空"), provider.sent)
        assertTrue(second.complete)
    }

    @Test
    fun aLineHoldingSomethingShapedLikeACodeKeepsIt() = runBlocking<Unit> {
        val (store, service) = fixture()
        store.putGlossaryTerm("work", "zh-Hant", "葵", "小葵")
        val result = service.translate(request("Model N1X と葵"), PivotProvider())
        assertEquals("zh-Hant(en(Model N1X と葵))", result.segments.single().translation)

        // A line the glossary turned into "N1X..." and source text that reads the same do not
        // share a memory entry, nor a "codes lost" mark.
        val lossy = object : TranslationProvider {
            override val id = "lossy"
            override val paid = false
            override fun route(from: String, to: String) = listOf(TranslationHop(from, to, "lossy:2"))
            override suspend fun translate(hop: TranslationHop, segments: List<String>) = segments.map { "T(" + it.replace("N1X", "") + ")" }
        }
        assertEquals("T(葵は寝た)", service.translate(request("葵は寝た", chapter = "c8"), lossy).segments.single().translation)
        assertEquals("T(は寝た)", service.translate(request("N1Xは寝た", chapter = "c9"), lossy).segments.single().translation)
    }

    @Test
    fun aLineWhoseCodesTheEngineLosesIsTranslatedWithoutThemAndNotRetriedWithThem() = runBlocking<Unit> {
        val (store, service) = fixture()
        store.putGlossaryTerm("work", "zh-Hant", "葵", "小葵")
        val provider = object : TranslationProvider {
            override val id = "lossy"
            override val paid = false
            val sent = ArrayList<String>()
            override fun route(from: String, to: String) = listOf(TranslationHop(from, to, "lossy:1"))
            override suspend fun translate(hop: TranslationHop, segments: List<String>): List<String> {
                sent += segments
                // Loses the code in one line, keeps it in the other.
                return segments.map { if ("壊" in it) "T(" + it.replace("N1X", "") + ")" else "T($it)" }
            }
        }

        val first = service.translate(request("葵は壊した\n葵は寝た"), provider)

        assertEquals(listOf("N1Xは壊した", "N1Xは寝た", "葵は壊した"), provider.sent)
        assertEquals(listOf("T(葵は壊した)", "T(小葵は寝た)"), first.segments.map { it.translation })
        assertTrue(first.complete)

        provider.sent.clear()
        val again = service.translate(request("葵は壊した\n葵は寝た"), provider)
        assertTrue(provider.sent.isEmpty(), "neither form is sent again: ${provider.sent}")
        assertEquals(first.segments, again.segments)
        assertEquals(first.segments, service.stored(request("葵は壊した\n葵は寝た"), provider)?.segments)
    }

    @Test
    fun aPaidTranslationIsNotReplacedByAFreeOne() = runBlocking<Unit> {
        val (store, _) = fixture()
        val key = MemoryKey(TranslationText.hash("一"), "ja", "zh-Hant", "same-engine")
        store.store(mapOf(key to "付費譯文"), paid = true, now = 1)
        store.store(mapOf(key to "免費譯文"), paid = false, now = 2)
        assertEquals("付費譯文", store.lookup(listOf(key.sourceHash), "ja", "zh-Hant", "same-engine", 3).values.single())
        assertEquals(0, store.freeBytes())
        store.evictFree(0)
        assertEquals(1, store.lookup(listOf(key.sourceHash), "ja", "zh-Hant", "same-engine", 4).size)
        // A free entry may become a paid one.
        val other = MemoryKey(TranslationText.hash("二"), "ja", "zh-Hant", "same-engine")
        store.store(mapOf(other to "免費"), paid = false, now = 5)
        store.store(mapOf(other to "付費"), paid = true, now = 6)
        assertEquals(0, store.freeBytes())
    }

    @Test
    fun cancellationStopsBetweenBatches() = runBlocking<Unit> {
        val (_, service) = fixture()
        val provider = PivotProvider()
        assertFailsWith<CancellationException> {
            service.translate(request("一\n二\n三\n四"), provider) { done, _ -> if (done == 3) throw CancellationException("stop") }
        }
        assertEquals(listOf("zh-Hant(en(一))", "zh-Hant(en(二))", null, null), service.stored(request("一\n二\n三\n四"), provider)?.segments?.map { it.translation })
    }

    @Test
    fun storedChaptersAreListedWithTheNamesTheReaderGaveThem() = kotlinx.coroutines.runBlocking<Unit> {
        val store = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        var clock = 1_000L
        val service = TranslationService(store, now = { clock++ })
        val provider = object : TranslationProvider {
            override val id = "deepl"
            override val paid = true
            override fun route(from: String, to: String) = listOf(TranslationHop(from, to, "deepl:v2"))
            override suspend fun translate(hop: TranslationHop, segments: List<String>) = segments.map { "T($it)" }
        }
        fun request(work: String, chapter: String, labels: TranslationLabels?) =
            ChapterTranslationRequest(work, chapter, "一行\n二行", "ko", "zh-Hant", labels = labels)

        service.translate(request("w1", "c1", TranslationLabels("作品甲", "第一話", "Novelpia")), provider)
        service.translate(request("w1", "c2", TranslationLabels("作品甲", "第二話", "Novelpia")), provider)
        service.translate(request("w2", "c9", null), provider) // translated before names were recorded

        val listed = store.storedChapters(limit = 10)
        assertEquals(listOf("c9", "c2", "c1"), listed.map { it.chapterKey }, "most recently translated first")
        val second = listed[1]
        assertEquals("作品甲", second.workTitle)
        assertEquals("第二話", second.chapterTitle)
        assertEquals("Novelpia", second.sourceName)
        assertEquals("deepl", second.provider)
        assertEquals(2, second.lineCount)
        assertTrue(second.complete && second.paid)
        assertEquals(null, listed[0].workTitle)
        assertEquals(2, store.storedChapters(limit = 2).size)

        // Opening the unnamed chapter again names it; a renamed chapter shows its new name.
        store.saveLabels("w2", "c9", TranslationLabels("作品乙", "第九話", null))
        store.saveLabels("w1", "c1", TranslationLabels("作品甲", "第一話（修訂）", "Novelpia"))
        val renamed = store.storedChapters(limit = 10).associateBy { it.chapterKey }
        assertEquals("作品乙", renamed.getValue("c9").workTitle)
        assertEquals(null, renamed.getValue("c9").sourceName)
        assertEquals("第一話（修訂）", renamed.getValue("c1").chapterTitle)

        store.forgetWork("w1")
        assertEquals(listOf("c9"), store.storedChapters(limit = 10).map { it.chapterKey })
    }

    @Test
    fun oneChaptersTranslationIsRemovedWithoutTakingLinesAnotherChapterUses() = kotlinx.coroutines.runBlocking<Unit> {
        val store = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        var clock = 1_000L
        val service = TranslationService(store, now = { clock++ })
        val sent = ArrayList<String>()
        val provider = object : TranslationProvider {
            override val id = "deepl"
            override val paid = true
            override fun route(from: String, to: String) = listOf(TranslationHop(from, "en", "a"), TranslationHop("en", to, "b"))
            override suspend fun translate(hop: TranslationHop, segments: List<String>): List<String> {
                sent += segments
                return segments.map { "${hop.engine}($it)" }
            }
        }
        fun request(work: String, chapter: String, text: String) =
            ChapterTranslationRequest(work, chapter, text, "ko", "zh-Hant", labels = TranslationLabels("作品 $work", "章 $chapter"))

        service.translate(request("w1", "c1", "共用的一行\n只在第一章"), provider)
        service.translate(request("w1", "c2", "共用的一行\n只在第二章"), provider)
        service.translate(request("w2", "c1", "另一部作品"), provider)
        val before = store.paidBytes()

        val freed = store.deleteChapterTranslation("w1", "c1", "zh-Hant", "deepl")
        assertTrue(freed > 0 && store.paidBytes() == before - freed)
        assertEquals(listOf("c1" to "w2", "c2" to "w1"), store.storedChapters(10).map { it.chapterKey to it.workKey })

        // The second chapter still reads back whole: the shared line was kept, at both hops.
        sent.clear()
        assertTrue(service.stored(request("w1", "c2", "共用的一行\n只在第二章"), provider)!!.complete)
        service.translate(request("w1", "c2", "共用的一行\n只在第二章"), provider)
        assertTrue(sent.isEmpty(), sent.toString())
        // The removed chapter is translated again, but only the line that was its own.
        service.translate(request("w1", "c1", "共用的一行\n只在第一章"), provider)
        assertEquals(listOf("只在第一章", "a(只在第一章)"), sent)

        // A whole work: its chapters and names go, another work's stay, the glossary stays.
        store.putGlossaryTerm("w1", "zh-Hant", "葵", "小葵")
        store.deleteWorkTranslations("w1")
        assertEquals(listOf("w2"), store.storedChapters(10).map { it.workKey })
        assertEquals(1, store.glossary("w1", "zh-Hant").size)
        sent.clear()
        service.translate(request("w2", "c1", "另一部作品"), provider)
        assertTrue(sent.isEmpty())
        store.deleteWorkTranslations("w2")
        assertEquals(0L, store.paidBytes())
        assertTrue(store.storedChapters(10).isEmpty())
    }

    @Test
    fun aChapterTranslatedBeforeLinesWereTrackedBecomesRemovableOnceItIsReadAgain() = kotlinx.coroutines.runBlocking<Unit> {
        val store = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val service = TranslationService(store, now = { 5L })
        val provider = object : TranslationProvider {
            override val id = "offline"
            override val paid = false
            override fun route(from: String, to: String) = listOf(TranslationHop(from, to, "m"))
            override suspend fun translate(hop: TranslationHop, segments: List<String>) = segments.map { "T($it)" }
        }
        val request = ChapterTranslationRequest("w", "c", "舊的一行", "ja", "zh-Hant")
        service.translate(request, provider)
        // As an older build left it: the chapter record and its line, with nothing tying them together.
        store.saveChapterSegments("w", "c", "zh-Hant", "offline", emptyList())
        assertEquals(0L, store.deleteChapterTranslation("w", "c", "zh-Hant", "offline").also { store.saveChapter(ChapterRecord("w", "c", "zh-Hant", "offline", "h", 1, true, false, 5)) })

        assertTrue(service.stored(request, provider)!!.complete)
        assertTrue(store.deleteChapterTranslation("w", "c", "zh-Hant", "offline") > 0)
        assertEquals(0L, store.freeBytes())
    }
}
