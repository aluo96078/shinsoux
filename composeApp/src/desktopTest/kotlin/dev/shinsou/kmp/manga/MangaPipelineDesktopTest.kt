package dev.shinsou.kmp.manga

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.translation.ChapterTranslation
import dev.shinsou.kmp.translation.ChapterTranslationRequest
import dev.shinsou.kmp.translation.TranslatedSegment
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole page pipeline with the real models (ADR-0005). Runs only when the model packs and a
 * test page are on this machine:
 *   SHINSOU_MANGA_PACKS=/path/to/packs (built by tools/manga-models/build_packs.py)
 *   SHINSOU_MANGA_PAGE=/path/to/page.jpg, with SHINSOU_MANGA_EXPECTED=/path/to/expected.txt (one OCR line per text region)
 */
class MangaPipelineDesktopTest {
    private val packs = System.getenv("SHINSOU_MANGA_PACKS")?.let(::File)?.takeIf { it.resolve("vision-v1").isDirectory }
    private val page = System.getenv("SHINSOU_MANGA_PAGE")?.let(::File)?.takeIf { it.isFile }

    @Test
    fun readsAndRedrawsAPage() {
        if (packs == null || page == null) return
        val platform = DesktopMangaPlatform.create()
        val v = packs.resolve("vision-v1")
        val paths = MangaModelPaths(
            detector = v.resolve("detector.onnx").path, encoder = v.resolve("encoder.onnx").path,
            decoder = v.resolve("decoder.onnx").path, vocab = v.resolve("vocab.txt").path, font = v.resolve("NotoSansTC-VF.ttf").path,
        )
        MangaPipeline(platform, paths) { File(it).readLines() }.use { pipeline ->
            val image = platform.codec.decode(page.readBytes()) ?: error("cannot decode $page")
            var t = System.nanoTime()
            val analysis = pipeline.analyze(image)
            val analyseMs = (System.nanoTime() - t) / 1_000_000
            println("MANGA analyse=${analyseMs}ms regions=${analysis.regions.size} bubbles=${analysis.bubbles.size}")
            analysis.regions.forEach { println("MANGA ${it.kind} ${it.text}") }
            System.getenv("SHINSOU_MANGA_EXPECTED")?.let(::File)?.takeIf { it.isFile }?.let { expected ->
                val lines = expected.readLines().filter { it.isNotBlank() }
                // platforms decode JPEG slightly differently and int8 OCR is sensitive to that:
                // the same regions, each line at least 85% the same characters
                assertEquals(lines.size, analysis.regions.size)
                lines.zip(analysis.regions.map { it.text }).forEach { (want, got) -> assertTrue(similarity(want, got) >= 0.85, "$want ≠ $got") }
            }
            val translations = analysis.regions.indices.associateWith { i -> SAMPLE.substring((i * 7) % 20).take(maxOf(4, analysis.regions[i].text.length * 4 / 5)) }
            t = System.nanoTime()
            val rendered = pipeline.render(image, analysis, translations, MangaOrientation.VERTICAL)
            println("MANGA render=${(System.nanoTime() - t) / 1_000_000}ms")
            val out = File("build/manga-test").apply { mkdirs() }.resolve(page.nameWithoutExtension + "-rendered.jpg")
            out.writeBytes(platform.codec.encodeJpeg(rendered, 90))
            assertTrue(out.length() > 0)
            println("MANGA wrote $out")
        }
    }

    @Test
    fun translatesAChapterAndServesItFromTheCacheTheSecondTime() = runBlocking {
        if (packs == null || page == null) return@runBlocking
        val platform = DesktopMangaPlatform.create()
        val root = Files.createTempDirectory("manga-chapter").toFile()
        packs.resolve("vision-v1").copyRecursively(root.resolve("models/vision-v1"))
        var calls = 0
        val translator = object : MangaTextTranslator {
            override suspend fun providerId() = "fake"
            override suspend fun translate(request: ChapterTranslationRequest, onProgress: (Int, Int) -> Unit): ChapterTranslation {
                calls++
                val lines = request.text.split("\n")
                return ChapterTranslation(lines.map { if (request.keep(it) || it.isBlank()) TranslatedSegment(it, null, kept = true) else TranslatedSegment(it, SAMPLE.take(maxOf(4, it.length * 4 / 5))) }, 0, lines.size)
            }
        }
        val manga = MangaTranslation(
            platform, MangaModelStore(root.resolve("models").path, HttpClient(MockEngine { respond("") })),
            MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), { System.currentTimeMillis() }),
            MangaRenderCache(root.resolve("pages").path), translator, { File(it).readLines() },
        )
        val bytes = page.readBytes()
        val loads = java.util.concurrent.atomic.AtomicInteger(0); var failSecondPage = true
        val request = MangaChapterRequest("w", "c", pageCount = 2, loadPage = { i -> loads.incrementAndGet(); if (i == 1 && failSecondPage) null else bytes }, from = "ja", to = "zh-Hant")
        suspend fun run(): Pair<Long, List<MangaProgress>> {
            val t = System.nanoTime(); val got = HashMap<Int, Int>(); val progress = mutableListOf<MangaProgress>()
            manga.translateChapter(request, onProgress = { progress += it }) { i, jpeg -> got[i] = jpeg?.size ?: -1 }
            assertEquals(setOf(0, 1), got.keys)
            return (System.nanoTime() - t) / 1_000_000 to progress
        }
        // a page that failed to load: the chapter is not remembered as drawn, so it is tried again
        run()
        assertEquals(2, loads.get())
        failSecondPage = false
        val (first, firstProgress) = run()
        assertEquals(4, loads.get(), "the failed page is loaded again")
        // both pages are the image read before: loaded to find it in the caches, not read again
        assertTrue(firstProgress.none { it is MangaProgress.Reading })
        assertTrue(MangaProgress.Loading(1, 2) in firstProgress && MangaProgress.Loading(2, 2) in firstProgress)
        val callsBefore = calls
        // drawn whole: opened again, nothing is loaded, read or translated
        val (second, progress) = run()
        println("MANGA chapter first=${first}ms second=${second}ms translatorCalls=$calls")
        assertEquals(4, loads.get(), "a drawn chapter does not load its pages again")
        assertEquals(callsBefore, calls)
        assertEquals(emptyList(), progress)
        assertTrue(second < first)
        // another language is another drawing
        manga.translateChapter(MangaChapterRequest("w", "c", 2, { bytes }, "ja", "zh-Hans")) { _, _ -> }
        assertTrue(loads.get() >= 4)
        manga.release()
    }

    /** A chapter set up with [translator] over the real models; null where they are not here. */
    private fun chapterWith(translator: MangaTextTranslator): Pair<MangaTranslation, ByteArray>? {
        if (packs == null || page == null) return null
        val root = Files.createTempDirectory("manga-chapter").toFile()
        packs.resolve("vision-v1").copyRecursively(root.resolve("models/vision-v1"))
        val manga = MangaTranslation(
            DesktopMangaPlatform.create(), MangaModelStore(root.resolve("models").path, HttpClient(MockEngine { respond("") })),
            MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), { System.currentTimeMillis() }),
            MangaRenderCache(root.resolve("pages").path), translator, { File(it).readLines() },
        )
        return manga to page.readBytes()
    }

    /** Translates every line, or (with [complete] off) leaves every other one untranslated. */
    private class Translator : MangaTextTranslator {
        var complete = true
        var stored = true
        private val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val running = java.util.concurrent.atomic.AtomicInteger(0)
        val mostAtOnce = java.util.concurrent.atomic.AtomicInteger(0)
        val calls: Int get() = callCount.get()
        override suspend fun providerId() = "fake"
        override suspend fun hasChapter(workKey: String, chapterKey: String, to: String) = stored
        override suspend fun translate(request: ChapterTranslationRequest, onProgress: (Int, Int) -> Unit): ChapterTranslation {
            callCount.incrementAndGet()
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            try {
                kotlinx.coroutines.delay(200) // long enough for another chapter to arrive
                val lines = request.text.split("\n")
                var translatable = 0
                return ChapterTranslation(
                    lines.map {
                        when {
                            request.keep(it) || it.isBlank() -> TranslatedSegment(it, null, kept = true)
                            !complete && translatable++ % 2 == 1 -> TranslatedSegment(it, null)
                            else -> TranslatedSegment(it, SAMPLE.take(maxOf(4, it.length * 4 / 5)))
                        }
                    },
                    0, lines.size,
                )
            } finally {
                running.decrementAndGet()
            }
        }
    }

    @Test
    fun aChapterWithUntranslatedLinesIsShownButNeitherKeptNorCountedDone() = runBlocking {
        val translator = Translator().apply { complete = false }
        val (manga, bytes) = chapterWith(translator) ?: return@runBlocking
        val request = MangaChapterRequest("w", "c", pageCount = 1, loadPage = { bytes }, from = "ja", to = "zh-Hant")
        var partial: ByteArray? = null
        assertEquals(false, manga.translateChapter(request) { _, jpeg -> partial = jpeg }, "an incomplete chapter is reported")
        assertTrue(partial != null, "what was translated is still shown")
        // translated in full now: the page is drawn again, not taken from the partial drawing
        translator.complete = true
        var full: ByteArray? = null
        assertEquals(true, manga.translateChapter(request) { _, jpeg -> full = jpeg })
        assertEquals(2, translator.calls)
        assertTrue(!full.contentEquals(partial), "the page is drawn from the whole translation")
        // and that one is kept
        manga.translateChapter(request) { _, _ -> }
        assertEquals(2, translator.calls)
        manga.release()
    }

    @Test
    fun aChapterWhoseTranslationWasDeletedIsTranslatedAndDrawnAgain() = runBlocking {
        val translator = Translator()
        val (manga, bytes) = chapterWith(translator) ?: return@runBlocking
        val request = MangaChapterRequest("w", "c", pageCount = 1, loadPage = { bytes }, from = "ja", to = "zh-Hant")
        manga.translateChapter(request) { _, _ -> }
        manga.translateChapter(request) { _, _ -> }
        assertEquals(1, translator.calls, "kept while its translation is")
        translator.stored = false
        manga.translateChapter(request) { _, _ -> }
        assertEquals(2, translator.calls, "deleted: not shown from the pages drawn before")
        manga.release()
    }

    @Test
    fun twoChaptersAtOnceTakeTurnsOnTheModels() = runBlocking {
        val translator = Translator()
        val (manga, bytes) = chapterWith(translator) ?: return@runBlocking
        val alone = HashMap<Int, ByteArray?>()
        manga.translateChapter(MangaChapterRequest("w", "alone", 2, { bytes }, "ja", "zh-Hant")) { i, jpeg -> alone[i] = jpeg }
        val a = HashMap<Int, ByteArray?>(); val b = HashMap<Int, ByteArray?>()
        translator.mostAtOnce.set(0)
        kotlinx.coroutines.coroutineScope {
            launch(kotlinx.coroutines.Dispatchers.Default) { manga.translateChapter(MangaChapterRequest("w", "a", 2, { bytes }, "ja", "zh-Hans")) { i, jpeg -> a[i] = jpeg } }
            launch(kotlinx.coroutines.Dispatchers.Default) { manga.translateChapter(MangaChapterRequest("w", "b", 2, { bytes }, "ja", "zh-Hans")) { i, jpeg -> b[i] = jpeg } }
        }
        assertEquals(1, translator.mostAtOnce.get(), "one chapter at a time on the shared readers")
        // both drew the same page the same way
        assertTrue(a.keys == setOf(0, 1) && b.keys == setOf(0, 1))
        assertTrue((0..1).all { a[it].contentEquals(b[it]) })
        manga.release()
    }

    private companion object {
        const val SAMPLE = "等一下，你到底在說什麼啊？我完全聽不懂！今天的天氣真好呢。快點過來這邊，大家都在等你了。"
    }
}
