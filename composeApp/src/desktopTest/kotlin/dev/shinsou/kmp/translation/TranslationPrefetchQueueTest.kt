package dev.shinsou.kmp.translation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslationPrefetchQueueTest {
    private object NoEngine : OfflineTranslationEngine {
        override suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String> = error("the offline engine must not run")
        override suspend fun unload() = Unit
    }

    /** A device set up for DeepL, answering every line with "D(line)". */
    private class Device(dailyLimit: Long = 0) {
        var requests = 0
        val memory = TranslationMemoryStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        private val client = HttpClient(
            MockEngine { request ->
                requests++
                val json = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val answer = buildJsonObject {
                    put("translations", JsonArray(json.getValue("text").jsonArray.map { buildJsonObject { put("text", "D(${it.jsonPrimitive.content})") } }))
                }
                respond(answer.toString(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        val translation = ReaderTranslation(memory, OfflineTranslationPlatform(NoEngine, "/nonexistent"), client, InMemoryPluginKeyValueStore(), now = { 1_000L })

        init {
            runBlocking {
                translation.setProvider(TranslationProviderKind.DEEPL)
                translation.setKey(TranslationProviderKind.DEEPL, "k")
                translation.setDailyCharacterLimit(dailyLimit)
            }
        }

        val loaded = ArrayList<String>()

        fun job(chapter: String, text: String = "$chapter 한 줄\n$chapter 두 줄", group: String = "work", gate: CompletableDeferred<Unit>? = null) =
            TranslationPrefetchJob(id = "$group|$chapter", group = group, title = "Chapter $chapter", unit = chapter) {
                loaded += chapter
                gate?.await()
                PrefetchChapter(group, chapter, text, exportable = false, labels = TranslationLabels("Work", "Chapter $chapter", "Source"))
            }

        suspend fun settled(): List<PrefetchEntry> = withTimeout(10_000) {
            translation.prefetch.entries.first { list -> list.none { it.state == PrefetchState.QUEUED || it.state == PrefetchState.RUNNING } }
        }
    }

    @Test
    fun queuedChaptersAreTranslatedInOrderAndOpenTranslatedWithoutBeingSentAgain() = runBlocking<Unit> {
        val device = Device()
        assertEquals(3, device.translation.prefetch.enqueue(listOf(device.job("c1"), device.job("c2"), device.job("c3"))))
        val entries = device.settled()
        assertEquals(listOf(PrefetchState.DONE, PrefetchState.DONE, PrefetchState.DONE), entries.map { it.state })
        assertEquals(listOf("c1", "c2", "c3"), device.loaded)
        assertEquals(setOf("c1", "c2", "c3"), device.translation.translatedChapterKeys("work"))
        // Listed by name in the settings page, like a chapter translated while reading.
        assertEquals(setOf("Chapter c1", "Chapter c2", "Chapter c3"), device.translation.storedChapters().map { it.chapterTitle }.toSet())

        // The reader finds the chapter whole: nothing more is sent.
        val sent = device.requests
        val request = ChapterTranslationRequest("work", "c2", "c2 한 줄\nc2 두 줄", "ko", "zh-Hant")
        assertTrue(device.translation.stored(request)!!.complete)
        // Asked for again, nothing is queued; a new chapter still is.
        assertEquals(0, device.translation.prefetch.enqueue(listOf(device.job("c1"))))
        assertEquals(1, device.translation.prefetch.enqueue(listOf(device.job("c1"), device.job("c4"))))
        device.settled()
        assertEquals(sent + 1, device.requests)
    }

    @Test
    fun theLanguagesChosenWhenQueueingAreUsedAndBecomeTheWorksSettings() = runBlocking<Unit> {
        val device = Device()
        // Never set up: the reader would have guessed Korean into the system's language.
        val chosen = TranslationPrefetchJob("work|c1", "work", "Chapter c1", languages = PrefetchLanguages(to = "en", from = "ja")) {
            PrefetchChapter("work", "c1", "一行目\n二行目", exportable = false, labels = null)
        }
        device.translation.prefetch.enqueue(listOf(chosen))
        assertEquals(PrefetchState.DONE, device.settled().single().state)
        // Opening the work now shows the translation, in the languages it was queued with.
        assertEquals(WorkTranslationSettings(enabled = true, from = "ja", to = "en"), device.translation.settings("work")!!.copy(mode = WorkTranslationSettings(true, "ja", "en").mode))
        assertTrue(device.translation.stored(ChapterTranslationRequest("work", "c1", "一行目\n二行目", "ja", "en"))!!.complete)

        // Without a choice the work's own settings decide; a new choice replaces them.
        device.translation.prefetch.enqueue(listOf(device.job("c2", text = "三行目")))
        device.settled()
        assertTrue(device.translation.stored(ChapterTranslationRequest("work", "c2", "三行目", "ja", "en"))!!.complete)
        val other = TranslationPrefetchJob("work|c3", "work", "Chapter c3", languages = PrefetchLanguages(to = "zh-Hant", from = "ja")) {
            PrefetchChapter("work", "c3", "四行目", exportable = false, labels = null)
        }
        device.translation.prefetch.enqueue(listOf(other))
        device.settled()
        assertEquals("zh-Hant", device.translation.settings("work")!!.to)
        assertEquals("ja", device.translation.settings("work")!!.from)
    }

    @Test
    fun aReasonThatWouldStopEveryChapterStopsTheQueueInsteadOfFailingEachInTurn() = runBlocking<Unit> {
        // Room for one chapter of twelve characters, not for two.
        val device = Device(dailyLimit = 15)
        device.translation.prefetch.enqueue(listOf(device.job("c1"), device.job("c2"), device.job("c3"), device.job("c4")))
        val entries = device.settled()
        assertEquals(PrefetchState.DONE, entries[0].state)
        assertEquals(listOf(TranslationFailure.DAILY_LIMIT, TranslationFailure.DAILY_LIMIT, TranslationFailure.DAILY_LIMIT), entries.drop(1).map { it.failure })
        assertTrue(entries.drop(1).all { it.state == PrefetchState.FAILED })
        // The chapters after the one that hit the limit were not even fetched.
        assertEquals(listOf("c1", "c2"), device.loaded)

        // With the limit raised they can be queued again.
        device.translation.setDailyCharacterLimit(0)
        assertEquals(3, device.translation.prefetch.enqueue(listOf(device.job("c2"), device.job("c3"), device.job("c4"))))
        assertTrue(device.settled().all { it.state == PrefetchState.DONE })
    }

    @Test
    fun detectingTheOriginalLooksAtEachChapterWhateverTheWorkWasSetTo() = runBlocking<Unit> {
        val device = Device()
        device.translation.saveSettings("work", WorkTranslationSettings(enabled = false, from = "en", to = "zh-Hant"))
        val detect = TranslationPrefetchJob("work|c1", "work", "Chapter c1", languages = PrefetchLanguages(to = "zh-Hant", from = null)) {
            PrefetchChapter("work", "c1", "一行目です\n二行目です", exportable = false, labels = null)
        }
        device.translation.prefetch.enqueue(listOf(detect))
        assertEquals(PrefetchState.DONE, device.settled().single().state)
        // The saved "English" was wrong: choosing "detect" corrects it from the text.
        assertEquals("ja", device.translation.settings("work")!!.from)
        assertTrue(device.settled().single().fetched)
        assertEquals("a|b", TranslationPrefetchJob("g|a|b", "g", "t", unit = "a|b") { error("unused") }.unit)
    }

    @Test
    fun aLanguageProblemStopsItsOwnWorkAndNotTheOthers() = runBlocking<Unit> {
        val device = Device()
        val same = PrefetchLanguages(to = "ko", from = "ko") // no such pair
        fun unsupported(chapter: String) = TranslationPrefetchJob("bad|$chapter", "bad", chapter, languages = same) {
            PrefetchChapter("bad", chapter, "한 줄", exportable = false, labels = null)
        }
        device.translation.prefetch.enqueue(listOf(unsupported("x1"), unsupported("x2"), device.job("c1")))
        val entries = device.settled().associateBy { it.id }
        assertEquals(TranslationFailure.UNSUPPORTED_PAIR, entries.getValue("bad|x1").failure)
        assertEquals(TranslationFailure.UNSUPPORTED_PAIR, entries.getValue("bad|x2").failure)
        assertEquals(PrefetchState.DONE, entries.getValue("work|c1").state)
    }

    @Test
    fun theReaderAndTheQueueNeverSendTheSameChapterTwice() = runBlocking<Unit> {
        val device = Device()
        val request = ChapterTranslationRequest("work", "c1", "c1 한 줄\nc1 두 줄", "ko", "zh-Hant")
        device.translation.saveSettings("work", WorkTranslationSettings(enabled = true, from = "ko", to = "zh-Hant"))
        val gate = CompletableDeferred<Unit>()
        device.translation.prefetch.enqueue(listOf(device.job("c1", gate = gate)))
        // The reader opens the chapter while the queue is still fetching it.
        val reader = async(Dispatchers.Default) { device.translation.translate(request) { _, _ -> } }
        gate.complete(Unit)
        assertTrue(reader.await().complete)
        device.settled()
        assertEquals(1, device.requests, "one of the two found the other's work in the memory")
        assertEquals(setOf("c1"), device.translation.translatedChapterKeys("work", "zh-Hant"))
        assertTrue(device.translation.translatedChapterKeys("work", "en").isEmpty())
    }

    @Test
    fun aChapterThatCannotBeFetchedOrIsNotTextFailsAloneAndTheRestGoOn() = runBlocking<Unit> {
        val device = Device()
        val notText = TranslationPrefetchJob("work|pictures", "work", "Pictures") { throw PrefetchNotTranslatable("images") }
        device.translation.prefetch.enqueue(listOf(device.job("c1"), notText, device.job("c2")))
        val entries = device.settled()
        assertEquals(listOf(PrefetchState.DONE, PrefetchState.FAILED, PrefetchState.DONE), entries.map { it.state })
        assertEquals(null, entries[1].failure)
    }

    @Test
    fun cancellingAWorkStopsWhatIsRunningAndWaitingForItAndLeavesOtherWorksAlone() = runBlocking<Unit> {
        val device = Device()
        val gate = CompletableDeferred<Unit>()
        device.translation.prefetch.enqueue(
            listOf(device.job("a1", gate = gate), device.job("a2"), device.job("b1", group = "other")),
        )
        withTimeout(10_000) { device.translation.prefetch.entries.first { list -> list.any { it.state == PrefetchState.RUNNING } } }
        device.translation.prefetch.cancel("work")
        val entries = device.settled()
        assertEquals(listOf("other|b1"), entries.map { it.id })
        assertEquals(PrefetchState.DONE, entries.single().state)
        assertEquals(setOf("b1"), device.translation.translatedChapterKeys("other"))
        assertTrue(device.translation.translatedChapterKeys("work").isEmpty())

        device.translation.prefetch.clearFinished("other")
        assertTrue(device.translation.prefetch.entries.value.isEmpty())
    }
}
