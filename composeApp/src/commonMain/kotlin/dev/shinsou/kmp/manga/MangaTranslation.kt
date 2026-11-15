package dev.shinsou.kmp.manga

import dev.shinsou.kmp.translation.ChapterTranslation
import dev.shinsou.kmp.translation.ChapterTranslationRequest
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.TranslationLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The text translation a manga chapter goes through: [ReaderTranslation] in the app. */
public interface MangaTextTranslator {
    /**
     * A short, stable name of the active provider and the engine behind it (part of what rendered
     * pages are keyed by): another LLM model, or the system's other models, is another translation.
     */
    public suspend fun providerId(): String

    public suspend fun translate(request: ChapterTranslationRequest, onProgress: (done: Int, total: Int) -> Unit): ChapterTranslation

    /**
     * Whether the chapter's translation into [to] is still kept, complete, by the active provider.
     * A chapter whose translation was deleted (or made by another engine) is drawn again rather
     * than shown from the pages drawn for it before.
     */
    public suspend fun hasChapter(workKey: String, chapterKey: String, to: String): Boolean = true
}

public fun ReaderTranslation.asMangaTranslator(): MangaTextTranslator = object : MangaTextTranslator {
    override suspend fun providerId(): String = engineIdentity()
    override suspend fun translate(request: ChapterTranslationRequest, onProgress: (Int, Int) -> Unit): ChapterTranslation =
        this@asMangaTranslator.translate(request, onProgress)
    override suspend fun hasChapter(workKey: String, chapterKey: String, to: String): Boolean =
        chapterKey in translatedChapterKeys(workKey, to)
}

/** A chapter to translate: its identity, its pages (loaded on demand) and the language pair. */
public class MangaChapterRequest(
    public val workKey: String,
    public val chapterKey: String,
    /** Loads page n's encoded image; null when it cannot be loaded (the page stays as it is). */
    public val pageCount: Int,
    public val loadPage: suspend (Int) -> ByteArray?,
    public val from: String,
    public val to: String,
    public val orientation: MangaOrientation = MangaOrientation.VERTICAL,
    public val direction: ReadingDirection = ReadingDirection.RIGHT_TO_LEFT,
    public val exportable: Boolean = false,
    public val labels: TranslationLabels? = null,
)

/** How the last chapter went, for the developer diagnostics. */
public data class MangaRunStats(
    val tuning: MangaTuning,
    val pages: Int,
    /** Shown from the drawn pages without loading anything. */
    val fromCache: Boolean,
    val readMs: Long = 0,
    val translateMs: Long = 0,
    val drawMs: Long = 0,
    /** What found the regions ([MangaDetector.ID] on the CPU, or the GPU's). */
    val detector: String = MangaDetector.ID,
)

/** Where a chapter is in its translation. */
public sealed interface MangaProgress {
    public data class Reading(val page: Int, val pages: Int) : MangaProgress

    /** The page was read before: it is only loaded, to find its reading and drawing in the caches. */
    public data class Loading(val page: Int, val pages: Int) : MangaProgress
    public data class Translating(val done: Int, val total: Int) : MangaProgress
    public data class Drawing(val page: Int, val pages: Int) : MangaProgress
}

/**
 * Manga translation for the reader (ADR-0005): detects and reads every page (detection of the
 * next page runs while the current one is read), translates the chapter as one script through the
 * user's provider, and redraws each page. OCR results and finished pages are cached, so a chapter
 * read again costs nothing.
 */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
public class MangaTranslation(
    private val platform: MangaPlatform?,
    public val models: MangaModelStore,
    private val pages: MangaPageStore,
    private val renders: MangaRenderCache,
    private val translator: MangaTextTranslator,
    private val readVocab: (String) -> List<String>,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    /** Overrides the device's own [MangaTuning] (for measuring one against another). */
    private val tuning: MangaTuning? = null,
) {
    /** How this device shares the models: the override, else the device's own; null where unsupported. */
    public val deviceTuning: MangaTuning? get() = tuning ?: platform?.let { MangaTuning.of(it.profile(), models.cpuMode()) }

    /** How hard the CPU works on pages (all of recognition, and detection and redrawing on the CPU). */
    public fun cpuMode(): MangaCpuMode = models.cpuMode()

    /** Chooses how hard the CPU works; chapters started from then on use it. */
    public suspend fun setCpuMode(mode: MangaCpuMode) {
        models.setCpuMode(mode)
        release()
    }

    /** The last chapter's timings (developer diagnostics). */
    public var lastRun: MangaRunStats? = null
        private set
    private var pipeline: MangaPipeline? = null
    private val pipelineLock = Mutex()
    /** Chapters using each pipeline: a replaced one closes when its last chapter ends. */
    private val holders = mutableMapOf<MangaPipeline, Int>()
    private var stale = false

    /** False on devices the inference and image libraries are not shipped for. */
    public val deviceSupported: Boolean get() = platform != null

    public fun modelsInstalled(): Boolean = models.paths() != null

    public fun missingModelBytes(): Long = models.missingBytes(MangaModelCatalog.VISION)

    public suspend fun downloadModels(onProgress: (received: Long, total: Long) -> Unit) {
        withContext(Dispatchers.IO) { models.download(MangaModelCatalog.VISION, onProgress) }
        release()
    }

    /** One way to run detection, as the settings list it. */
    public data class AcceleratorState(
        val support: AcceleratorSupport,
        val selected: Boolean,
        /** Its models (and runtime) are on the device. */
        val installed: Boolean,
        /** What its download would bring (its runtime as kept on the device). */
        val missingBytes: Long,
    )

    /** The ways this device can run detection, which are chosen, and what each still needs. */
    public fun acceleratorStates(): List<AcceleratorState> {
        val p = platform ?: return emptyList()
        val selected = models.selectedAccelerators()
        return p.accelerators().map { support ->
            val runtimeMissing = support.runtime?.takeIf { models.runtimeInstalled(it) == null }?.installedBytes ?: 0
            val missing = support.packs.sumOf { models.missingBytes(it) } + runtimeMissing
            AcceleratorState(support, support.accelerator in selected, installed = support.supported && missing == 0L, missingBytes = missing)
        }
    }

    /**
     * Downloads what [accelerator] needs (its models, and for Qualcomm's NPU the QNN runtime from
     * Maven Central), then readies it ([MangaPlatform.prepare]); [onPreparing] is told when the
     * downloads are done and the (longer) preparation starts.
     */
    public suspend fun downloadAccelerator(
        accelerator: MangaAccelerator,
        onProgress: (received: Long, total: Long) -> Unit,
        onPreparing: () -> Unit = {},
    ) {
        val p = platform ?: throw MangaModelException("Manga translation is not available on this device")
        val support = p.accelerators().first { it.accelerator == accelerator }
        if (!support.supported) throw MangaModelException(support.unsupportedReason ?: "Not available")
        withContext(Dispatchers.IO) {
            val total = support.packs.sumOf { models.missingBytes(it) } + (support.runtime?.takeIf { models.runtimeInstalled(it) == null }?.size ?: 0)
            var done = 0L
            for (pack in support.packs) {
                val missing = models.missingBytes(pack)
                models.download(pack) { received, _ -> onProgress(done + received, total) }
                done += missing
            }
            support.runtime?.let { runtime ->
                val before = done
                models.downloadRuntime(runtime, p::extractRuntime) { received, _ -> onProgress(before + received, total) }
            }
        }
        // without the recognition models yet, it is readied by the first chapter instead
        pathsWithRuntimes(p)?.let { paths ->
            onPreparing()
            withContext(work) { p.prepare(accelerator, paths) }
        }
        release()
    }

    /**
     * Removes what [accelerator] downloaded and stops using it, and every other choice that needs
     * the same files (on an iPhone the GPU and the Neural Engine share one model).
     */
    public suspend fun removeAccelerator(accelerator: MangaAccelerator) {
        val p = platform ?: return
        release()
        val supports = p.accelerators()
        val support = supports.first { it.accelerator == accelerator }
        withContext(Dispatchers.IO) {
            support.packs.forEach(models::remove)
            support.runtime?.let(models::removeRuntime)
            p.forget(accelerator)
        }
        val sharing = supports.filter { other -> other.packs.any { it in support.packs } || (other.runtime != null && other.runtime == support.runtime) }
            .map { it.accelerator }.toSet() + accelerator
        val left = models.selectedAccelerators() - sharing
        models.selectAccelerators(left.ifEmpty { setOf(MangaAccelerator.CPU) })
    }

    /** Chooses where detection runs (at least one); chapters started from then on use the choice. */
    public suspend fun selectAccelerators(accelerators: Set<MangaAccelerator>) {
        models.selectAccelerators(accelerators)
        release()
    }

    /** The models' paths with the downloaded runtimes' directories filled in. */
    private fun pathsWithRuntimes(p: MangaPlatform): MangaModelPaths? {
        val paths = models.paths() ?: return null
        val qnn = p.accelerators().firstNotNullOfOrNull { it.runtime }?.let { models.runtimeInstalled(it) }
        return paths.copy(qnnRuntime = qnn)
    }

    /** Whether the optional LaMa pack is here: text drawn over the artwork is then translated too. */
    public fun inpaintInstalled(): Boolean = models.installed(MangaModelCatalog.INPAINT) != null

    public fun missingInpaintBytes(): Long = models.missingBytes(MangaModelCatalog.INPAINT)

    /** Downloads the LaMa pack; chapters started from then on use it. */
    public suspend fun downloadInpaint(onProgress: (received: Long, total: Long) -> Unit) {
        withContext(Dispatchers.IO) { models.download(MangaModelCatalog.INPAINT, onProgress) }
        release()
    }

    /** Removes the LaMa pack; text over artwork stays as drawn again. */
    public suspend fun removeInpaint() {
        release()
        withContext(Dispatchers.IO) { models.remove(MangaModelCatalog.INPAINT) }
    }

    /**
     * The pipeline for one chapter, held until [done]: a chapter keeps the one it started with, so
     * the models never close under it. One marked stale is replaced once no chapter holds it.
     */
    private suspend fun acquire(): MangaPipeline = pipelineLock.withLock {
        if (stale) {
            stale = false
            pipeline?.let { old -> if (old !in holders) old.close() }
            pipeline = null
        }
        val current = pipeline ?: run {
            val p = platform ?: throw MangaModelException("Manga translation is not available on this device")
            val paths = pathsWithRuntimes(p) ?: throw MangaModelException("The manga models are not installed")
            // the chosen detectors this device has everything for; the CPU's when none is left
            val chosen = models.selectedAccelerators()
            val supports = acceleratorStates().filter { it.support.accelerator in chosen && it.installed }.map { it.support }
            MangaPipeline(p, paths, tuning ?: MangaTuning.of(p.profile(), models.cpuMode()), supports, readVocab).also { pipeline = it }
        }
        holders[current] = (holders[current] ?: 0) + 1
        current
    }

    private suspend fun done(used: MangaPipeline): Unit = pipelineLock.withLock {
        val left = (holders[used] ?: 1) - 1
        if (left > 0) { holders[used] = left; return@withLock }
        holders.remove(used)
        if (used !== pipeline) used.close()
    }

    /**
     * Translates [request]'s pages and hands each finished page to [onPage] as JPEG bytes (null
     * for a page that could not be read). Pages already drawn for this language, provider and
     * layout come straight from the cache. Returns whether every line was translated and every
     * page read: a chapter that was not is neither kept as drawn nor recorded as translated, and
     * is to be tried again. [MangaChapterRequest.loadPage] returns null for a page left as it is
     * on purpose (a scrambled one the reader descrambles itself), and throws for one that failed.
     */
    public suspend fun translateChapter(
        request: MangaChapterRequest,
        onProgress: (MangaProgress) -> Unit = {},
        onPage: suspend (index: Int, jpeg: ByteArray?) -> Unit,
    ): Boolean {
        val codec = platform?.codec ?: throw MangaModelException("Manga translation is not available on this device")
        // Callers start chapters from the reader's UI coroutine. Building the pipeline reads the
        // model files, and its detectors are created on first use - for Core ML that compiles the
        // model, seconds of work that froze the reader when translation was switched on. Both
        // happen here, off the UI thread; the chapter's own work then stays off it as well.
        val pipeline = withContext(work) { acquire() }
        return try {
            withContext(work) { translateChapter(request, codec, pipeline, onProgress, onPage) }
        } finally {
            withContext(NonCancellable) { done(pipeline) }
        }
    }

    private suspend fun translateChapter(
        request: MangaChapterRequest,
        codec: PageCodec,
        pipeline: MangaPipeline,
        onProgress: (MangaProgress) -> Unit,
        onPage: suspend (index: Int, jpeg: ByteArray?) -> Unit,
    ): Boolean = coroutineScope {
        val provider = translator.providerId()
        // Pages drawn before stand for the translation only while it is still kept: deleted (or
        // made by another engine), the chapter is translated and drawn again.
        val reuseDrawn = translator.hasChapter(request.workKey, request.chapterKey, request.to)
        val n = request.pageCount
        // the saver keeps every step on one thread the platform puts on an efficiency core
        val work = if (pipeline.tuning.efficiencyThread) platform?.efficiencyDispatcher() ?: work else work
        // A chapter drawn before in this language, by this provider and layout, is shown as drawn:
        // its pages are not loaded again (from the network, for a source) just to find them.
        // pages read by another detector are cached apart (the default keeps its older keys)
        // pages read by another detector are cached apart (the default keeps its older keys); with
        // several detectors, each page always goes to the same one
        val detectors = pipeline.detectors
        val assignment = assignPages(n, pipeline.detectorWeights)
        val variant = renders.key("chapter", request.to, provider, request.orientation, inpaint = pipeline.inpaints, detector = pipeline.detectorId.takeIf { it != MangaDetector.ID })
        val manifest = if (!reuseDrawn) null
        else withContext(Dispatchers.IO) { pages.chapterRenders(request.workKey, request.chapterKey, variant) }?.takeIf { it.size == n }
        if (manifest != null) {
            val drawn = withContext(Dispatchers.IO) { manifest.map { renders.get(it) } }
            if (drawn.all { it != null }) {
                for (i in 0 until n) onPage(i, withContext(work) { codec.forDisplay(drawn[i]!!) })
                lastRun = MangaRunStats(pipeline.tuning, n, fromCache = true, detector = pipeline.detectorId)
                return@coroutineScope true
            }
        }
        // The pipeline's readers, detectors and text measurers are shared by its workers, which
        // are numbered from zero in every chapter: two chapters at once (the reader's and the
        // queue's) would overwrite each other's pages. Its chapters run one after the other.
        pipeline.chapters.withLock { drawChapter(request, codec, pipeline, work, provider, reuseDrawn, variant, detectors, assignment, onProgress, onPage) }
    }

    private suspend fun drawChapter(
        request: MangaChapterRequest,
        codec: PageCodec,
        pipeline: MangaPipeline,
        work: CoroutineDispatcher,
        provider: String,
        reuseDrawn: Boolean,
        variant: String,
        detectors: List<PageDetector>,
        assignment: IntArray,
        onProgress: (MangaProgress) -> Unit,
        onPage: suspend (index: Int, jpeg: ByteArray?) -> Unit,
    ): Boolean = coroutineScope {
        val n = request.pageCount
        fun detectorOf(i: Int): String? = detectors[assignment[i]].id.takeIf { it != MangaDetector.ID }
        fun readVersion(i: Int): String = detectorOf(i)?.let { "${MangaPageStore.VERSION}+$it" } ?: MangaPageStore.VERSION
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        // pages that failed to load or decode: the chapter is then only partly translated
        val unreadPages = kotlin.concurrent.atomics.AtomicInt(0)
        // encoded pages (about a megabyte each) rather than decoded ones (about ten): decoded again to draw
        val encoded = arrayOfNulls<ByteArray>(n)
        val hashes = arrayOfNulls<String>(n)
        val analyses = arrayOfNulls<PageAnalysis>(n)

        // 1. Read every page. Pages load several at a time (from the network, for a source) but
        // reach detection in order; detection runs on one worker, OCR on as many as the device
        // has room for, each on whole pages, so every page reads the same whatever the device.
        val tuning = pipeline.tuning
        val store = Mutex() // the page store is written from several readers
        val detected = Channel<Triple<Int, RgbImage, List<Detection>?>>(capacity = tuning.ocrWorkers + 1)
        // one worker per detector, each with the pages assigned to it
        val toDetect = detectors.map { Channel<Pair<Int, RgbImage>>(capacity = 2) }
        launch(work) {
            // loading, decoding and hashing run ahead, several pages at a time
            class Loaded(val bytes: ByteArray, val image: RgbImage, val hash: String)
            val loads = arrayOfNulls<kotlinx.coroutines.Deferred<Loaded?>>(n)
            fun startLoad(i: Int) {
                if (i < n && loads[i] == null) loads[i] = async(work) {
                    // null: a page left as it is on purpose (scrambled, not an image); thrown, or
                    // undecodable: a page that should have been read and was not
                    val bytes = try {
                        request.loadPage(i)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        unreadPages.addAndFetch(1); null
                    } ?: return@async null
                    val image = codec.decode(bytes) ?: run { unreadPages.addAndFetch(1); return@async null }
                    Loaded(bytes, image, pageHash(bytes))
                }
            }
            val detecting = detectors.indices.map { k ->
                async(work) { for ((i, image) in toDetect[k]) detected.send(Triple(i, image, pipeline.detect(image, k))) }
            }
            for (i in 0 until n) {
                for (ahead in i until i + tuning.pageLoadsAhead) startLoad(ahead)
                val loaded = loads[i]!!.await(); loads[i] = null
                if (loaded == null) { detected.send(Triple(i, RgbImage(1, 1, ByteArray(3)), null)); continue }
                val image = loaded.image; val hash = loaded.hash
                hashes[i] = hash; encoded[i] = loaded.bytes
                val cached = store.withLock { withContext(Dispatchers.IO) { pages.get(hash, readVersion(i)) } }
                if (cached != null) { analyses[i] = cached; detected.send(Triple(i, image, emptyList())); continue }
                toDetect[assignment[i]].send(i to image)
            }
            toDetect.forEach { it.close() }
            detecting.awaitAll()
            detected.close()
        }
        val reading = kotlin.concurrent.atomics.AtomicInt(0)
        val readers = List(tuning.ocrWorkers) { worker ->
            async(work) {
                for ((i, image, detections) in detected) {
                    val count = reading.addAndFetch(1)
                    if (detections == null || analyses[i] != null) {
                        onProgress(MangaProgress.Loading(count, n))
                        continue
                    }
                    onProgress(MangaProgress.Reading(count, n))
                    val analysis = pipeline.read(image, detections, worker)
                    analyses[i] = analysis
                    store.withLock { withContext(Dispatchers.IO) { pages.put(hashes[i]!!, analysis, readVersion(i)) } }
                }
            }
        }
        readers.awaitAll()
        val read = started.elapsedNow()

        // 2. Translate the chapter as one script, one line per region.
        val readable = (0 until n).filter { analyses[it] != null }
        val script = MangaScript.build(readable.map { analyses[it]!! }, request.direction)
        val translated = translator.translate(
            ChapterTranslationRequest(
                workKey = request.workKey, chapterKey = request.chapterKey, text = script.text,
                from = request.from, to = request.to, keep = script::keep, exportable = request.exportable, labels = request.labels,
                partial = unreadPages.load() > 0,
            ),
        ) { done, total -> onProgress(MangaProgress.Translating(done, total)) }
        val byPage = script.translations(translated.segments.map { it.translation })
        val translatedAt = started.elapsedNow()

        // 3. Draw each page in its translation (or take it from the cache), several at a time,
        // in page order as far as the workers allow.
        val drawnKeys = arrayOfNulls<String>(n)
        val nextPage = kotlin.concurrent.atomics.AtomicInt(0)
        val drawing = kotlin.concurrent.atomics.AtomicInt(0)
        val handOver = Mutex() // pages reach the caller one at a time
        List(tuning.ocrWorkers) { worker ->
            async(work) {
                while (true) {
                    val position = nextPage.fetchAndAdd(1).takeIf { it < readable.size } ?: break
                    val i = readable[position]
                    onProgress(MangaProgress.Drawing(drawing.addAndFetch(1), readable.size))
                    val drawnText = byPage[position].orEmpty().entries.sortedBy { it.key }.joinToString("\u0001") { "${it.key}\u0002${it.value}" }
                    val key = renders.key(hashes[i]!!, request.to, provider, request.orientation, inpaint = pipeline.inpaints, detector = detectorOf(i), translation = drawnText)
                    val cached = if (reuseDrawn) withContext(Dispatchers.IO) { renders.get(key) } else null
                    val jpeg = cached ?: run {
                        val image = codec.decode(encoded[i]!!) ?: return@run null
                        codec.encodeJpeg(pipeline.render(image, analyses[i]!!, byPage[position].orEmpty(), request.orientation, worker), 90)
                    }?.also {
                        // A page drawn from an incomplete translation is shown, never kept: kept,
                        // it would stand in for the page once the rest is translated.
                        if (it !== cached && translated.complete) renders.put(key, it)
                    }
                    if (jpeg != null) drawnKeys[i] = key
                    encoded[i] = null
                    val shown = jpeg?.let { codec.forDisplay(it) }
                    handOver.withLock { onPage(i, shown) }
                }
            }
        }.awaitAll()
        for (i in 0 until n) if (analyses[i] == null) onPage(i, null)
        val total = started.elapsedNow()
        lastRun = MangaRunStats(
            tuning, n, fromCache = false, readMs = read.inWholeMilliseconds,
            translateMs = (translatedAt - read).inWholeMilliseconds, drawMs = (total - translatedAt).inWholeMilliseconds,
            detector = detectors.indices.joinToString(" ") { k -> "${detectors[k].id}:${assignment.count { it == k }}" },
        )
        // Only a chapter whose every page was drawn: a page that failed to load is tried again.
        if (n > 0 && drawnKeys.all { it != null } && translated.complete) {
            withContext(Dispatchers.IO) { pages.putChapterRenders(request.workKey, request.chapterKey, variant, drawnKeys.map { it!! }) }
        }
        translated.complete && unreadPages.load() == 0
    }

    /** Frees the models' memory once no chapter is using them; they load again on the next chapter. */
    public suspend fun release(): Unit = pipelineLock.withLock {
        val current = pipeline ?: return@withLock
        if (current in holders) { stale = true } else { current.close(); pipeline = null; stale = false }
    }
}
