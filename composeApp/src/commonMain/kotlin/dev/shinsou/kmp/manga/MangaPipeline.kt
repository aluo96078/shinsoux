package dev.shinsou.kmp.manga

import kotlin.math.max
import kotlin.math.min

/** Where the files of the `vision-v1` model pack are on this device. */
public data class MangaModelPaths(
    val detector: String,
    val encoder: String,
    val decoder: String,
    val vocab: String,
    val font: String,
    /** LaMa from the optional `inpaint-v1` pack: text over artwork is translated too. */
    val inpaint: String? = null,
    /** The Core ML detector package (`vision-coreml-v1`), used where the platform runs Core ML. */
    val coreMlDetector: String? = null,
    /** The detector for Qualcomm's NPU (`vision-qnn-v1`). */
    val qnnDetector: String? = null,
    /** The directory of the QNN runtime's libraries, once downloaded. */
    val qnnRuntime: String? = null,
)

/**
 * The per-page steps of manga translation (ADR-0005 §4): [detect] and [read] analyse a page (they
 * may run side by side on different pages), [render] erases the source text and draws the
 * translation. Sessions load on first use; one pipeline can serve every chapter.
 */
public class MangaPipeline(
    private val platform: MangaPlatform,
    private val paths: MangaModelPaths,
    /** How the models share the device; by default from the platform's profile. */
    tuningOverride: MangaTuning? = null,
    /** Where detection runs (the user's choice, installed); empty for the CPU alone. */
    private val accelerators: List<AcceleratorSupport> = emptyList(),
    private val readVocab: (String) -> List<String>,
) : AutoCloseable {
    private val onnx get() = platform.onnx
    private val ops get() = platform.ops
    /** How the models share this device, fixed for the pipeline's life. */
    public val tuning: MangaTuning = tuningOverride ?: MangaTuning.of(platform.profile())

    init {
        platform.ops.singleThreaded(tuning.efficiencyThread)
    }
    // workers open their sessions at the same time: kept in a list replaced atomically
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    private val sessions = kotlin.concurrent.atomics.AtomicReference(emptyList<OnnxSession>())

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    private fun open(path: String, threads: Int, variableShapes: Boolean = false): OnnxSession =
        onnx.open(path, threads, variableShapes).also { session ->
            while (true) { val current = sessions.load(); if (sessions.compareAndSet(current, current + session)) break }
        }

    // the chosen detectors, with their weights; one that cannot start here (a GPU that cannot run
    // the model) is left out, and the CPU's reads when none is left
    private val detectorSet: Lazy<List<Pair<PageDetector, Int>>> = lazy {
        val cpu = lazy { MangaDetector(onnx, ops, open(paths.detector, tuning.detectorThreads)) }
        accelerators.mapNotNull { support ->
            when (support.accelerator) {
                MangaAccelerator.CPU -> cpu.value to support.weight
                else -> runCatching { platform.acceleratedDetector(paths, support.accelerator) }.getOrNull()?.let { it to support.weight }
            }
        }.ifEmpty { listOf(cpu.value to 1) }
    }

    /** The detectors that share a chapter's pages ([assignPages] by [detectorWeights]). */
    public val detectors: List<PageDetector> get() = detectorSet.value.map { it.first }
    public val detectorWeights: List<Int> get() = detectorSet.value.map { it.second }

    /** Held by the chapter using the workers' shared state (see [MangaTranslation.translateChapter]). */
    internal val chapters: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex()

    /** What finds the regions, all together (a chapter read by another set is cached apart). */
    public val detectorId: String get() = detectors.joinToString("+") { it.id }
    private val vocab by lazy { readVocab(paths.vocab) }
    // one reader per worker, each with its own sessions: workers read different pages at once
    private val readers = List(tuning.ocrWorkers) {
        lazy { MangaOcr(onnx, ops, open(paths.encoder, tuning.ocrThreads), open(paths.decoder, tuning.ocrThreads), vocab) }
    }
    private val eraser by lazy { MangaEraser(ops, paths.inpaint?.let { MangaInpainter(onnx, open(it, tuning.inpaintThreads, variableShapes = true)) }) }

    /** Whether text over artwork is redrawn (pages drawn with and without it look different). */
    public val inpaints: Boolean get() = paths.inpaint != null
    // one renderer and layout per worker: text measuring keeps a cache that is not shared
    private val drawers = List(tuning.ocrWorkers) {
        lazy { MangaRenderer(platform, paths.font).let { renderer -> renderer to MangaLayout(renderer.measure, ops::distanceTransform) } }
    }

    /** Detects with the [which]th detector; each is used by one worker at a time. */
    public fun detect(page: RgbImage, which: Int = 0): List<Detection> = detectorSet.value[which].first.detect(page)

    /**
     * Reads the text regions of [detections] and returns the page analysis. Regions are read in
     * fixed groups of [MangaOcr.BATCH] in detection order, so a page always reads the same.
     */
    public fun read(page: RgbImage, detections: List<Detection>, worker: Int = 0): PageAnalysis {
        val texts = detections.filter { it.kind != RegionKind.BUBBLE }
        val ocr = readers[worker % readers.size].value
        val read = texts.chunked(MangaOcr.BATCH).flatMap { group ->
            ocr.read(group.map { page.crop(it.box.x0.toInt(), it.box.y0.toInt(), it.box.x1.toInt(), it.box.y1.toInt()) })
        }
        return PageAnalysis(
            width = page.width, height = page.height,
            regions = texts.mapIndexed { i, d ->
                TextRegion(d.kind, d.box.x0.toInt(), d.box.y0.toInt(), d.box.x1.toInt(), d.box.y1.toInt(), read[i])
            },
            bubbles = detections.filter { it.kind == RegionKind.BUBBLE }.map {
                intArrayOf(it.box.x0.toInt(), it.box.y0.toInt(), it.box.x1.toInt(), it.box.y1.toInt())
            },
        )
    }

    public fun analyze(page: RgbImage): PageAnalysis = read(page, detect(page))

    /**
     * Erases the regions that have a translation and draws it in their place. Regions that cannot
     * be erased cleanly (text over artwork, without the LaMa pack) keep their source text.
     */
    public fun render(page: RgbImage, analysis: PageAnalysis, translations: Map<Int, String>, orientation: MangaOrientation, worker: Int = 0): RgbImage {
        val (renderer, layout) = drawers[worker % drawers.size].value
        val img = page.copy()
        val bubbles = analysis.bubbles.map { Box(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }
        val placed = mutableListOf<PlacedText>()
        // Erasing a region in a bubble clears the whole bubble, and its translation is laid out
        // over the whole interior: the regions of one bubble are drawn together, as one text.
        val inBubble = analysis.regions.indices.groupBy { i ->
            val box = analysis.regions[i].box
            bubbles.indices.maxByOrNull { bubbles[it].overlap(box) }?.takeIf { bubbles[it].overlap(box) >= 0.6f * box.area }
        }
        for ((bubbleIndex, members) in inBubble) {
            val bubble = bubbleIndex?.let(bubbles::get)
            val groups = if (bubble == null) members.map { listOf(it) } else listOf(members)
            for (group in groups) {
                val regions = group.map { analysis.regions[it] }
                // a region of punctuation only ("!?") reads the same translated; any other without
                // a translation keeps the bubble as it is, rather than erasing its source text
                val texts = group.map { i ->
                    val region = analysis.regions[i]
                    tidyTranslation(translations[i], region.text) ?: region.text.takeUnless { MangaScript.needsTranslation(it) }
                }
                if (texts.any { it == null } || texts.all { !MangaScript.needsTranslation(it!!) }) continue
                val (vertical, order) = bubbleReadingOrder(regions)
                val text = order.map { texts[it]!! }.reduce(::joinTranslations)
                val box = regions.map { it.box }.reduce { a, b -> Box(min(a.x0, b.x0), min(a.y0, b.y0), max(a.x1, b.x1), max(a.y1, b.y1)) }
                val erased = eraser.erase(
                    img, box, bubble, overArt = regions.all { it.kind == RegionKind.FREE_TEXT },
                    horizontal = if (regions.size > 1) !vertical else null,
                ) ?: continue
                val laid = layout.layout(text, erased.area, box, erased.sourceSize, erased.inBubble, erased.horizontalSource, orientation)
                if (laid != null) {
                    placed += PlacedText(laid, erased)
                } else {
                    // it does not fit where it has to go: the source text stays, not an empty bubble
                    restore(img, page, erased.area, box)
                }
            }
        }
        return renderer.draw(img, placed)
    }

    /** Two translations of one bubble as one text: a space between words, none between CJK. */
    private fun joinTranslations(a: String, b: String): String {
        fun latin(c: Char) = c.code < 0x2E80 && c.isLetterOrDigit()
        return if (a.isNotEmpty() && b.isNotEmpty() && (latin(a.last()) || a.last() in ",.!?") && latin(b.first())) "$a $b" else a + b
    }

    private fun restore(img: RgbImage, page: RgbImage, area: Region, box: Box) {
        val pad = 8
        val x0 = max(0, min(area.x0, box.x0.toInt() - pad)); val y0 = max(0, min(area.y0, box.y0.toInt() - pad))
        val x1 = min(page.width, max(area.x0 + area.w, box.x1.toInt() + pad)); val y1 = min(page.height, max(area.y0 + area.h, box.y1.toInt() + pad))
        for (y in y0 until y1) page.pixels.copyInto(img.pixels, (y * page.width + x0) * 3, (y * page.width + x0) * 3, (y * page.width + x1) * 3)
    }

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    override fun close() {
        sessions.exchange(emptyList()).forEach { runCatching { it.close() } }
        // An accelerated detector (GPU, NPU) owns a session the platform opened, not one of
        // [sessions]: replaced pipelines would otherwise keep its model, buffers and threads.
        if (detectorSet.isInitialized()) {
            detectorSet.value.forEach { (detector, _) -> (detector as? AutoCloseable)?.let { runCatching { it.close() } } }
        }
    }
}

private val REPEATED = Regex("(.{1,4}?)\\1{3,}")

/**
 * A translation ready to draw, or null when the source should stay: runs a translation engine
 * repeats without end ("喔喔喔喔喔…") are cut to three, and what is still far longer than the
 * source is not a translation of it.
 */
internal fun tidyTranslation(translation: String?, source: String): String? {
    val text = translation?.trim()?.replace(REPEATED) { it.groupValues[1].repeat(3) } ?: return null
    if (text.isEmpty()) return null
    if (text.length > max(source.length * 3, source.length + 16)) return null
    return text
}

/**
 * How the regions of one bubble run (vertical or not), decided by those with words in them ("!?"
 * is often wider than tall), and the order they are read in: columns right to left, or lines top
 * to bottom, with an exclamation or question mark on its own last.
 */
internal fun bubbleReadingOrder(regions: List<TextRegion>): Pair<Boolean, List<Int>> {
    val worded = regions.filter { MangaScript.needsTranslation(it.text) }.ifEmpty { regions }
    // the text's own shape, all of it together: a short line is as wide as tall, a long one decides
    val vertical = worded.sumOf { it.box.height.toDouble() } >= worded.sumOf { it.box.width.toDouble() }
    val byPlace = if (vertical) compareByDescending<Int> { regions[it].box.x1 }.thenBy { regions[it].box.y0 }
    else compareBy<Int> { regions[it].box.y0 }.thenBy { regions[it].box.x0 }
    // an exclamation or question mark on its own closes what is said, wherever it sits in the
    // bubble; brackets and ellipses keep their place (「 opens, 」 closes)
    val order = regions.indices.sortedWith(compareBy<Int> { regions[it].text.isClosingMark() }.then(byPlace))
    return vertical to order
}

private fun String.isClosingMark(): Boolean = isNotBlank() && all { it in "!?！？‼⁉⁈ " }
