package dev.shinsou.kmp.manga

import androidx.compose.ui.graphics.ImageBitmap
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.translation.ChapterTranslation
import dev.shinsou.kmp.translation.ChapterTranslationRequest
import dev.shinsou.kmp.translation.TranslatedSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A chapter's run (0007 C5) with stand-in stages, codec and translator: which pages are handed
 * over and how, what is kept, and what the run reports, in both flows. The real models are in
 * MangaPipelineDesktopTest.
 */
class MangaChapterRunTest {
    /** Page i decodes to one pixel holding i; drawn, its second byte is the number of lines drawn on it. */
    private object Codec : PageCodec {
        override fun decode(bytes: ByteArray): RgbImage? = if (bytes.isEmpty()) null else RgbImage(1, 1, byteArrayOf(bytes[0], 0, 0))
        override fun encodeJpeg(image: RgbImage, quality: Int): ByteArray = byteArrayOf(image.pixels[0], image.pixels[1])
        override fun toImageBitmap(image: RgbImage): ImageBitmap = error("not drawn on screen")
        override fun fromImageBitmap(bitmap: ImageBitmap): RgbImage = error("not drawn on screen")
    }

    /** Pages in [blank] have no text; every other page one line, named after it. */
    private class Stages(private val blank: Set<Int>, workers: Int = 2) : ChapterStages {
        val detected = AtomicInteger(0)
        val read = AtomicInteger(0)
        override val tuning = MangaTuning(detectorThreads = 1, ocrWorkers = workers, ocrThreads = 1, inpaintThreads = 1, pageLoadsAhead = 2)

        override fun detect(page: RgbImage, detector: Int): List<Detection> {
            detected.incrementAndGet()
            return listOf(Detection(RegionKind.BUBBLE_TEXT, 1f, Box(0f, 0f, 1f, 1f)))
        }

        override fun read(page: RgbImage, detections: List<Detection>, worker: Int): PageAnalysis {
            read.incrementAndGet()
            val i = page.pixels[0].toInt()
            val regions = if (i in blank) emptyList() else listOf(TextRegion(RegionKind.BUBBLE_TEXT, 0, 0, 1, 1, "ページ$i"))
            return PageAnalysis(1, 1, regions, emptyList())
        }

        override fun render(page: RgbImage, analysis: PageAnalysis, translations: Map<Int, String>, worker: Int): RgbImage =
            page.copy().also { it.pixels[1] = translations.size.toByte() }
    }

    /** Translates every line; a part ([ChapterTranslationRequest.segment]) is left untranslated while [partsFail]. */
    private class Translator(var partsFail: Boolean = false) : MangaTextTranslator {
        val requests: MutableList<ChapterTranslationRequest> = Collections.synchronizedList(mutableListOf())
        override suspend fun providerId(): String = "fake"
        override suspend fun translate(request: ChapterTranslationRequest, onProgress: (Int, Int) -> Unit): ChapterTranslation {
            requests += request
            val lines = request.text.split("\n")
            return ChapterTranslation(
                lines.map { line ->
                    when {
                        request.keep(line) || line.isBlank() -> TranslatedSegment(line, null, kept = true)
                        request.segment && partsFail -> TranslatedSegment(line, null)
                        else -> TranslatedSegment(line, "訳$line")
                    }
                },
                0, lines.size,
            )
        }
    }

    private class Fixture(val stages: Stages, val translator: Translator = Translator()) {
        val root = Files.createTempDirectory("manga-run").toFile()
        val pages = MangaPageStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), { 0L })
        val drawn = DrawnChapters(pages, MangaRenderCache(root.resolve("pages").path), Dispatchers.IO)
        var lastRun: MangaRunStats? = null

        fun plan(request: MangaChapterRequest): ChapterPlan {
            val keys = ChapterRenderKeys(drawn.renders, request, "fake", inpaints = false, reader = null, font = null)
            return ChapterPlan(request, DetectorAssignment(listOf(MangaDetector.ID), IntArray(request.pageCount)), null, keys, true, keys.chapter(MangaDetector.ID))
        }

        /** Runs [request]: the pages handed over, in the order they were, and whether the chapter is done. */
        fun run(request: MangaChapterRequest, flow: MangaTranslationFlow): Pair<Boolean, List<Pair<Int, ByteArray?>>> = runBlocking {
            val shown = Collections.synchronizedList(mutableListOf<Pair<Int, ByteArray?>>())
            val env = ChapterEnvironment(Codec, Dispatchers.Default, Dispatchers.IO, pages, drawn, translator)
            val done = MangaChapterRun(plan(request), stages, env, {}, { i, jpeg -> shown += i to jpeg }) { lastRun = it }.run(flow)
            done to shown.toList()
        }
    }

    private fun request(pageCount: Int, chapter: String = "c", load: (Int) -> ByteArray? = { byteArrayOf(it.toByte()) }) =
        MangaChapterRequest("w", chapter, pageCount, { load(it) }, "ja", "zh-Hant")

    @Test
    fun aWholeChapterHandsOverEveryPageOnceAndIsKeptAsDrawn() {
        val fixture = Fixture(Stages(blank = setOf(1)))
        val request = request(4)
        val (done, shown) = fixture.run(request, MangaTranslationFlow.CHAPTER)
        assertTrue(done)
        assertEquals(listOf(0, 1, 2, 3), shown.map { it.first }.sorted())
        // the page without text is shown as it is, before any page is drawn
        assertEquals(1 to null, shown.first().let { it.first to it.second })
        assertTrue(shown.filter { it.first != 1 }.all { (i, jpeg) -> jpeg!!.contentEquals(byteArrayOf(i.toByte(), 1)) })
        assertEquals(1, fixture.translator.requests.size)
        val kept = assertNotNull(runBlocking { fixture.drawn.find(request, fixture.plan(request).variant) })
        assertNull(kept[1])
        assertEquals(MangaTranslationFlow.CHAPTER, fixture.lastRun?.flow)
        assertEquals("${MangaDetector.ID}:4", fixture.lastRun?.detector)
    }

    @Test
    fun aPageThatFailsToLoadLeavesTheChapterToDoAndItIsNotKept() {
        val fixture = Fixture(Stages(blank = emptySet()))
        val request = request(3) { i -> if (i == 2) error("offline") else byteArrayOf(i.toByte()) }
        val (done, shown) = fixture.run(request, MangaTranslationFlow.CHAPTER)
        assertEquals(false, done)
        assertEquals(listOf(0, 1, 2), shown.map { it.first }.sorted())
        // the page that did not load is handed over last, as it is
        assertEquals(2 to null, shown.last().let { it.first to it.second })
        assertTrue(fixture.translator.requests.single().partial)
        assertNull(runBlocking { fixture.drawn.find(request, fixture.plan(request).variant) })
    }

    @Test
    fun pagesReadBeforeAreOnlyLoadedAndPagesDrawnBeforeAreNotDrawnAgain() {
        val stages = Stages(blank = emptySet())
        val fixture = Fixture(stages)
        fixture.run(request(3, chapter = "a"), MangaTranslationFlow.CHAPTER)
        assertEquals(3, stages.read.get())
        // another chapter of the same pages: read and drawn from the caches
        val (done, shown) = fixture.run(request(3, chapter = "b"), MangaTranslationFlow.CHAPTER)
        assertTrue(done)
        assertEquals(3, stages.read.get())
        assertEquals(3, stages.detected.get())
        assertEquals(3, shown.size)
    }

    @Test
    fun aProgressiveChapterTranslatesInGrowingPartsAndThenAsAWhole() {
        val fixture = Fixture(Stages(blank = setOf(2), workers = 1))
        val request = request(7)
        val (done, shown) = fixture.run(request, MangaTranslationFlow.PROGRESSIVE)
        assertTrue(done)
        assertEquals((0 until 7).toList(), shown.map { it.first }.sorted())
        val requests = fixture.translator.requests
        // parts of one, two and four pages (the third page has no text), then the whole chapter
        assertEquals(listOf(1, 1, 4, 6), requests.map { r -> r.text.split("\n").count { it.contains("ページ") } })
        assertTrue(requests.dropLast(1).all { it.segment && it.partial })
        assertEquals(listOf("ページ0" to "訳ページ0"), requests[1].context)
        assertEquals(false, requests.last().segment)
        assertNotNull(runBlocking { fixture.drawn.find(request, fixture.plan(request).variant) })
        assertEquals(MangaTranslationFlow.PROGRESSIVE, fixture.lastRun?.flow)
    }

    @Test
    fun aProgressivePartLeftIncompleteIsDrawnAgainOnceTheWholeChapterIsTranslated() {
        val fixture = Fixture(Stages(blank = emptySet(), workers = 1), Translator(partsFail = true))
        val request = request(3)
        val (done, shown) = fixture.run(request, MangaTranslationFlow.PROGRESSIVE)
        assertTrue(done)
        // each page shown first without its line, then with it
        for (i in 0 until 3) {
            assertEquals(listOf(0, 1), shown.filter { it.first == i }.map { it.second!![1].toInt() })
        }
        assertNotNull(runBlocking { fixture.drawn.find(request, fixture.plan(request).variant) })
    }
}
