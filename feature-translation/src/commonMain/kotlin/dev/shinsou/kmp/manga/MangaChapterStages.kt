package dev.shinsou.kmp.manga

import dev.shinsou.kmp.translation.ChapterTranslationRequest
import kotlinx.coroutines.CoroutineDispatcher

/*
 * The parts of one chapter's run (0007 C5): what it asks of its pipeline, what it is keyed by,
 * what its stages hand each other and what they fill in per page.
 */

/** A page kept as it is in a chapter's list of drawn pages: it has no text to translate. */
internal const val ORIGINAL_PAGE = "-"

/** What a chapter run asks of the pipeline it holds: [MangaPipeline] in the app ([PipelineStages]). */
internal interface ChapterStages {
    val tuning: MangaTuning

    /** Finds the regions of [page] with the [detector]th detector; each is used by one worker at a time. */
    fun detect(page: RgbImage, detector: Int): List<Detection>

    /** Reads the regions [detections] found, in the chapter's source language, on [worker]'s readers. */
    fun read(page: RgbImage, detections: List<Detection>, worker: Int): PageAnalysis

    /** Draws [translations] over [page], in the chapter's orientation and target language. */
    fun render(page: RgbImage, analysis: PageAnalysis, translations: Map<Int, String>, worker: Int): RgbImage
}

/** [ChapterStages] of [pipeline] for [request]'s languages and orientation. */
internal class PipelineStages(private val pipeline: MangaPipeline, private val request: MangaChapterRequest) : ChapterStages {
    override val tuning: MangaTuning get() = pipeline.tuning

    override fun detect(page: RgbImage, detector: Int): List<Detection> = pipeline.detect(page, detector)

    override fun read(page: RgbImage, detections: List<Detection>, worker: Int): PageAnalysis =
        pipeline.read(page, detections, worker, request.from)

    override fun render(page: RgbImage, analysis: PageAnalysis, translations: Map<Int, String>, worker: Int): RgbImage =
        pipeline.render(page, analysis, translations, request.orientation, worker, request.to)
}

/** Which of the pipeline's detectors reads each page of a chapter ([assignPages]), and what they are named. */
internal class DetectorAssignment(private val ids: List<String>, private val assignment: IntArray) {
    val count: Int get() = ids.size

    /** The detector reading page [page]. */
    fun of(page: Int): Int = assignment[page]

    /** What page [page] is cached by for its detector: nothing for the default one (its keys stay as they were). */
    fun cacheId(page: Int): String? = ids[assignment[page]].takeIf { it != MangaDetector.ID }

    /** How many pages each detector read, for the diagnostics. */
    fun stats(): String = ids.indices.joinToString(" ") { k -> "${ids[k]}:${assignment.count { it == k }}" }
}

/**
 * What a chapter's drawn pages are keyed by besides the page itself: the target language, the
 * provider and the layout, and the recogniser and font when they are not the default ones.
 */
internal class ChapterRenderKeys(
    private val renders: MangaRenderCache,
    private val request: MangaChapterRequest,
    private val provider: String,
    private val inpaints: Boolean,
    private val reader: String?,
    private val font: String?,
) {
    /** A page's key: its image, its detector, and the text drawn on it. */
    fun page(hash: String, detector: String?, translations: Map<Int, String>): String {
        val drawnText = translations.entries.sortedBy { it.key }.joinToString("\u0001") { "${it.key}\u0002${it.value}" }
        return renders.key(hash, request.to, provider, request.orientation, inpaint = inpaints, detector = detector, translation = drawnText, reader = reader, font = font)
    }

    /**
     * The whole chapter's drawing by [detectorId]. One recogniser reads both scripts of Chinese,
     * which are translated by different models: the chapter is drawn apart for each (manga-ocr
     * reads Japanese alone, its keys stay as they were).
     */
    fun chapter(detectorId: String): String = renders.key(
        "chapter", request.to, provider, request.orientation, inpaint = inpaints,
        detector = detectorId.takeIf { it != MangaDetector.ID }, reader = reader?.let { "$it@${request.from}" }, font = font,
    )
}

/** What a chapter run was decided to be before it starts. */
internal class ChapterPlan(
    val request: MangaChapterRequest,
    val detectors: DetectorAssignment,
    /** The recogniser reading the pages when not manga-ocr: part of what their readings are cached by. */
    private val reader: String?,
    val renderKeys: ChapterRenderKeys,
    /** Pages drawn before stand for the translation only while it is still kept. */
    val reuseDrawn: Boolean,
    /** What the chapter's list of drawn pages is kept by ([ChapterRenderKeys.chapter]). */
    val variant: String,
) {
    val pageCount: Int get() = request.pageCount

    /** What page [page]'s reading is kept by in the page store. */
    fun readVersion(page: Int): String = listOfNotNull(MangaPageStore.VERSION, detectors.cacheId(page), reader).joinToString("+")

    /** The request translating [script], a part of the chapter or all of it. */
    fun translation(script: MangaScript, partial: Boolean): ChapterTranslationRequest = ChapterTranslationRequest(
        workKey = request.workKey, chapterKey = request.chapterKey, text = script.text,
        from = request.from, to = request.to, keep = script::keep, exportable = request.exportable, labels = request.labels,
        partial = partial,
    )
}

/** Where a chapter run reads, keeps and translates. */
internal class ChapterEnvironment(
    val codec: PageCodec,
    /** Where the chapter's work runs: one efficiency thread for [MangaCpuMode.SAVER]. */
    val work: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val pages: MangaPageStore,
    val drawn: DrawnChapters,
    val translator: MangaTextTranslator,
)

/** A page on its way to its detector. */
internal class PageToDetect(val index: Int, val image: RgbImage)

/** A page on its way to the readers: [detections] empty when its reading was cached, null when it could not be loaded. */
internal class DetectedPage(val index: Int, val image: RgbImage, val detections: List<Detection>?)

/** A page on its way to a drawer, with its regions' [translations]; [complete] when its part was translated completely. */
internal class PageToDraw(val index: Int, val translations: Map<Int, String>, val complete: Boolean)

/** What the stages of a chapter fill in per page: each slot is written by one stage and read by those after it. */
internal class ChapterPages(count: Int) {
    /** Encoded pages (about a megabyte each) rather than decoded ones (about ten): decoded again to draw. */
    val encoded: Array<ByteArray?> = arrayOfNulls(count)
    val hashes: Array<String?> = arrayOfNulls(count)
    val analyses: Array<PageAnalysis?> = arrayOfNulls(count)
    val drawnKeys: Array<String?> = arrayOfNulls(count)

    fun hash(page: Int): String = hashes.filled(page, "hash")

    fun analysis(page: Int): PageAnalysis = analyses.filled(page, "analysis")

    fun image(page: Int): ByteArray = encoded.filled(page, "image")

    /** The pages of [range] with text to translate (none found, or only page numbers and punctuation, is none). */
    fun readable(range: IntRange): List<Int> = range.filter { analyses[it]?.let(MangaScript::hasText) == true }

    /** Page [page], read and without text: shown as it is, neither translated nor drawn. */
    fun keepOriginal(page: Int) {
        drawnKeys[page] = ORIGINAL_PAGE
        encoded[page] = null
    }

    /** Whether there are pages and every one was drawn (or kept as it is). */
    fun allDrawn(): Boolean = drawnKeys.isNotEmpty() && drawnKeys.all { it != null }
}

/** The value an earlier stage of the chapter put in page [page]'s slot; empty means the stages ran out of order. */
private fun <T : Any> Array<T?>.filled(page: Int, what: String): T = checkNotNull(this[page]) { "The $what of page $page is not ready" }
