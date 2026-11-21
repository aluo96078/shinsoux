package dev.shinsou.kmp.manga

import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.translation.ChapterTranslation
import dev.shinsou.kmp.translation.ChapterTranslationRequest
import dev.shinsou.kmp.translation.ChineseScript
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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

    /** [providerId] for the pair: some pairs go through a provider of their own whatever is chosen. */
    public suspend fun providerId(from: String, to: String): String = providerId()

    public suspend fun translate(request: ChapterTranslationRequest, onProgress: (done: Int, total: Int) -> Unit): ChapterTranslation

    /**
     * Whether the chapter's translation from [from] into [to] is still kept, complete, by the
     * provider the pair goes through. A chapter whose translation was deleted (or made by another
     * engine) is drawn again rather than shown from the pages drawn for it before.
     */
    public suspend fun hasChapter(workKey: String, chapterKey: String, from: String, to: String): Boolean = true
}

public fun ReaderTranslation.asMangaTranslator(): MangaTextTranslator = object : MangaTextTranslator {
    override suspend fun providerId(): String = engineIdentity()
    override suspend fun providerId(from: String, to: String): String = engineIdentity(from, to)
    override suspend fun translate(request: ChapterTranslationRequest, onProgress: (Int, Int) -> Unit): ChapterTranslation =
        this@asMangaTranslator.translate(request, onProgress)
    override suspend fun hasChapter(workKey: String, chapterKey: String, from: String, to: String): Boolean =
        chapterKey in translatedChapterKeys(workKey, to, from)
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
    /**
     * The chapter is being read now: it may be translated in parts to show its first pages sooner
     * ([MangaTranslationFlow.PROGRESSIVE]). A chapter translated ahead is always done as a whole.
     */
    public val reading: Boolean = true,
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
    /** From the start until the first translated page was handed over. */
    val firstPageMs: Long = 0,
    val flow: MangaTranslationFlow = MangaTranslationFlow.CHAPTER,
)

/** A page kept as it is in a chapter's list of drawn pages: it has no text to translate. */
private const val ORIGINAL_PAGE = "-"

/** The largest part of a progressive chapter: a few requests a chapter, each still soon. */
private const val MAX_PART_PAGES = 8

/** Translated lines carried to the next part of a progressive chapter as context. */
private const val MAX_CONTEXT_LINES = 30

/** How a chapter goes from reading to drawing, chosen by the user (docs/research/manga-page-pipeline.md). */
public enum class MangaTranslationFlow {
    /** Every page read, the chapter translated as one script, then the pages drawn. */
    CHAPTER,

    /**
     * The pages translated and drawn in parts as they are read, in page order: the first part is
     * one page, so it shows soonest; each next part doubles. Each part carries the lines before it.
     */
    PROGRESSIVE,
}

/** Where a chapter is in its translation. */
public sealed interface MangaProgress {
    public data class Reading(val page: Int, val pages: Int) : MangaProgress

    /** The page was read before: it is only loaded, to find its reading and drawing in the caches. */
    public data class Loading(val page: Int, val pages: Int) : MangaProgress
    public data class Translating(val done: Int, val total: Int) : MangaProgress
    public data class Drawing(val page: Int, val pages: Int) : MangaProgress
}

/** What a downloaded resource of manga translation is for. */
public enum class MangaResourceKind { RECOGNITION, INPAINT, LANGUAGE, FONT, ACCELERATOR, LEFTOVER }

/**
 * A resource manga translation downloads, as the settings list it: [id] is its pack (packs joined
 * by "+" for an accelerator; a path in the store for a leftover), [language] the source language a
 * recogniser reads or the target a font draws, and [diskBytes] what it takes now.
 */
public data class MangaResource(
    val id: String,
    val kind: MangaResourceKind,
    val language: String?,
    val accelerators: List<MangaAccelerator>,
    val diskBytes: Long,
    val missingBytes: Long,
) {
    val installed: Boolean get() = missingBytes == 0L && diskBytes > 0
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** How this device shares the models: the override, else the device's own; null where unsupported. */
    public val deviceTuning: MangaTuning? get() = tuning ?: platform?.let { MangaTuning.of(it.profile(), models.cpuMode()) }

    /** How chapters are translated: as a whole, or in parts as they are read. */
    public fun translationFlow(): MangaTranslationFlow = models.translationFlow()

    public fun setTranslationFlow(flow: MangaTranslationFlow): Unit = models.setTranslationFlow(flow)

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
    /** Chapters holding any pipeline: files they read are deleted only once there are none. */
    private val held = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val removing = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** False on devices the inference and image libraries are not shipped for. */
    public val deviceSupported: Boolean get() = platform != null

    public fun modelsInstalled(): Boolean = models.paths() != null

    public fun missingModelBytes(): Long = models.missingBytes(MangaModelCatalog.VISION)

    public suspend fun downloadModels(onProgress: (received: Long, total: Long) -> Unit) {
        withContext(ioDispatcher) { models.download(MangaModelCatalog.VISION, onProgress) }
        release()
    }

    /**
     * What a chapter from [from] into [to] still needs to download (MangaLanguages.packs): the
     * recogniser for pages not in Japanese, the font for simplified Chinese. Zero when all is here.
     */
    public fun missingLanguageBytes(from: String, to: String): Long = MangaLanguages.packs(from, to).sumOf(models::missingBytes)

    /** Downloads what [missingLanguageBytes] counts; chapters started from then on use it. */
    public suspend fun downloadLanguage(from: String, to: String, onProgress: (received: Long, total: Long) -> Unit) {
        withContext(ioDispatcher) {
            val packs = MangaLanguages.packs(from, to)
            val total = packs.sumOf(models::missingBytes)
            var done = 0L
            for (pack in packs) {
                val missing = models.missingBytes(pack)
                if (missing == 0L) continue
                models.download(pack) { received, _ -> onProgress(done + received - (pack.downloadBytes - missing), total) }
                done += missing
            }
        }
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
        withContext(ioDispatcher) {
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
        val supports = p.accelerators()
        val support = supports.first { it.accelerator == accelerator }
        whileIdle {
            withContext(ioDispatcher) {
                support.packs.forEach(models::remove)
                support.runtime?.let(models::removeRuntime)
                p.forget(accelerator)
            }
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

    /**
     * Everything manga translation downloads on this device, for the settings to list and manage:
     * the recognition models, the optional packs, what each accelerator needs (one row for choices
     * that share their files), and what older builds left behind.
     */
    public fun resources(): List<MangaResource> {
        val p = platform ?: return emptyList()
        fun pack(kind: MangaResourceKind, pack: ModelPack, language: String? = null) =
            MangaResource(pack.id, kind, language, emptyList(), models.diskBytes(pack), models.missingBytes(pack))
        val list = mutableListOf(
            pack(MangaResourceKind.RECOGNITION, MangaModelCatalog.VISION),
            pack(MangaResourceKind.INPAINT, MangaModelCatalog.INPAINT),
            pack(MangaResourceKind.LANGUAGE, MangaModelCatalog.OCR_LINES),
            pack(MangaResourceKind.LANGUAGE, MangaModelCatalog.OCR_ZH, "zh"),
            pack(MangaResourceKind.LANGUAGE, MangaModelCatalog.OCR_EN, "en"),
            pack(MangaResourceKind.FONT, MangaModelCatalog.FONT_SC, "zh-Hans"),
        )
        val supports = p.accelerators().filter { it.supported && (it.packs.isNotEmpty() || it.runtime != null) }
        for ((_, group) in supports.groupBy { it.packs.map(ModelPack::id) to it.runtime?.id }) {
            val first = group.first()
            val runtime = first.runtime
            val disk = first.packs.sumOf(models::diskBytes) + (runtime?.let(models::runtimeBytes) ?: 0)
            val missing = first.packs.sumOf(models::missingBytes) + (runtime?.takeIf { models.runtimeInstalled(it) == null }?.installedBytes ?: 0)
            val id = (first.packs.map { it.id } + listOfNotNull(runtime?.id)).joinToString("+")
            list += MangaResource(id, MangaResourceKind.ACCELERATOR, null, group.map { it.accelerator }, disk, missing)
        }
        for ((name, bytes) in models.leftovers(inUse(p))) {
            list += MangaResource(name, MangaResourceKind.LEFTOVER, null, emptyList(), bytes, 0)
        }
        return list
    }

    /** Downloads what [resource] still misses; an accelerator's download also chooses it. */
    public suspend fun downloadResource(resource: MangaResource, onProgress: (received: Long, total: Long) -> Unit, onPreparing: () -> Unit = {}) {
        when (resource.kind) {
            MangaResourceKind.RECOGNITION -> downloadModels(onProgress)
            MangaResourceKind.INPAINT -> downloadInpaint(onProgress)
            MangaResourceKind.LANGUAGE, MangaResourceKind.FONT -> {
                val pack = MangaModelCatalog.PACKS.first { it.id == resource.id }
                withContext(ioDispatcher) { models.download(pack, onProgress) }
                release()
            }
            MangaResourceKind.ACCELERATOR -> {
                val accelerator = resource.accelerators.first()
                downloadAccelerator(accelerator, onProgress, onPreparing)
                selectAccelerators(models.selectedAccelerators() + accelerator)
            }
            MangaResourceKind.LEFTOVER -> Unit
        }
    }

    /** Deletes [resource] from this device; chapters that need it offer the download again. */
    public suspend fun removeResource(resource: MangaResource) {
        val p = platform ?: return
        when (resource.kind) {
            MangaResourceKind.RECOGNITION, MangaResourceKind.LANGUAGE, MangaResourceKind.FONT -> {
                val pack = MangaModelCatalog.PACKS.first { it.id == resource.id }
                whileIdle { withContext(ioDispatcher) { models.remove(pack) } }
            }
            MangaResourceKind.INPAINT -> removeInpaint()
            MangaResourceKind.ACCELERATOR -> removeAccelerator(resource.accelerators.first())
            MangaResourceKind.LEFTOVER -> {
                whileIdle { withContext(ioDispatcher) { models.removeLeftover(resource.id, inUse(p)) } }
            }
        }
    }

    /** The packs and runtimes this device's accelerators use, which are not left over even when outside the catalog. */
    private fun inUse(p: MangaPlatform): Set<String> =
        p.accelerators().flatMap { support -> support.packs.map { it.id } + listOfNotNull(support.runtime?.let { "runtime/${it.id}" }) }.toSet()

    /** Whether the optional LaMa pack is here: text drawn over the artwork is then translated too. */
    public fun inpaintInstalled(): Boolean = models.installed(MangaModelCatalog.INPAINT) != null

    public fun missingInpaintBytes(): Long = models.missingBytes(MangaModelCatalog.INPAINT)

    /** Downloads the LaMa pack; chapters started from then on use it. */
    public suspend fun downloadInpaint(onProgress: (received: Long, total: Long) -> Unit) {
        withContext(ioDispatcher) { models.download(MangaModelCatalog.INPAINT, onProgress) }
        release()
    }

    /** Removes the LaMa pack; text over artwork stays as drawn again. */
    public suspend fun removeInpaint() {
        whileIdle { withContext(ioDispatcher) { models.remove(MangaModelCatalog.INPAINT) } }
    }

    /**
     * The pipeline for one chapter, held until [done]: a chapter keeps the one it started with, so
     * the models never close under it. One marked stale is replaced once no chapter holds it.
     */
    private suspend fun acquire(): MangaPipeline {
        while (true) {
            // no chapter starts while models are being deleted
            removing.first { it == 0 }
            pipelineLock.withLock { if (removing.value == 0) return pipelineFor() }
        }
    }

    private suspend fun pipelineFor(): MangaPipeline {
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
        held.value++
        return current
    }

    private suspend fun done(used: MangaPipeline): Unit = pipelineLock.withLock {
        held.value--
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
        if (missingLanguageBytes(request.from, request.to) > 0) {
            throw MangaModelException("The text recognition models or font for ${request.from} → ${request.to} are not installed")
        }
        // Callers start chapters from the reader's UI coroutine. Building the pipeline reads the
        // model files, and its detectors are created on first use - for Core ML that compiles the
        // model, seconds of work that froze the reader when translation was switched on. Both
        // happen here, off the UI thread; the chapter's own work then stays off it as well.
        // held from inside the worker's context: cancelled on the way back, it is still let go
        var held: MangaPipeline? = null
        return try {
            val pipeline = withContext(work) { acquire().also { held = it } }
            withContext(work) { translateChapter(request, codec, pipeline, onProgress, onPage) }
        } finally {
            held?.let { withContext(NonCancellable) { done(it) } }
        }
    }

    private suspend fun translateChapter(
        request: MangaChapterRequest,
        codec: PageCodec,
        pipeline: MangaPipeline,
        onProgress: (MangaProgress) -> Unit,
        onPage: suspend (index: Int, jpeg: ByteArray?) -> Unit,
    ): Boolean = coroutineScope {
        val provider = translator.providerId(request.from, request.to)
        // Pages drawn before stand for the translation only while it is still kept: deleted (or
        // made by another engine), the chapter is translated and drawn again.
        val reuseDrawn = translator.hasChapter(request.workKey, request.chapterKey, request.from, request.to)
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
        // pages read by another recogniser, or drawn in another font, are kept apart as well
        val reader = pipeline.recognizerId(request.from)
        val font = pipeline.fontId(request.to)
        // one recogniser reads both scripts of Chinese, which are translated by different models:
        // the chapter is drawn apart for each (manga-ocr reads Japanese alone, its keys stay as they were)
        val variant = renders.key(
            "chapter", request.to, provider, request.orientation, inpaint = pipeline.inpaints,
            detector = pipeline.detectorId.takeIf { it != MangaDetector.ID }, reader = reader?.let { "$it@${request.from}" }, font = font,
        )
        val manifest = if (!reuseDrawn) null
        else withContext(ioDispatcher) { pages.chapterRenders(request.workKey, request.chapterKey, variant) }?.takeIf { it.size == n }
        if (manifest != null) {
            val drawn = withContext(ioDispatcher) { manifest.map { key -> if (key == ORIGINAL_PAGE) null else renders.get(key) } }
            if (manifest.indices.all { manifest[it] == ORIGINAL_PAGE || drawn[it] != null }) {
                // a page with no text was not drawn: the reader shows it as it is
                for (i in 0 until n) onPage(i, drawn[i]?.let { withContext(work) { codec.forDisplay(it) } })
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
        val flow = if (request.reading) models.translationFlow() else MangaTranslationFlow.CHAPTER
        fun detectorOf(i: Int): String? = detectors[assignment[i]].id.takeIf { it != MangaDetector.ID }
        val reader = pipeline.recognizerId(request.from)
        val font = pipeline.fontId(request.to)
        fun readVersion(i: Int): String = listOfNotNull(MangaPageStore.VERSION, detectorOf(i), reader).joinToString("+")
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
                    @Suppress("TooGenericExceptionCaught") // any failure leaves this page unread
                    val bytes = try {
                        request.loadPage(i)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (logged: Exception) {
                        Log.warn("MangaTranslation", logged) { "page $i could not be loaded" }
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
                val loaded = loads.filled(i, "load").await(); loads[i] = null
                if (loaded == null) { detected.send(Triple(i, RgbImage(1, 1, ByteArray(3)), null)); continue }
                val image = loaded.image; val hash = loaded.hash
                hashes[i] = hash; encoded[i] = loaded.bytes
                val cached = store.withLock { withContext(ioDispatcher) { pages.get(hash, readVersion(i)) } }
                if (cached != null) { analyses[i] = cached; detected.send(Triple(i, image, emptyList())); continue }
                toDetect[assignment[i]].send(i to image)
            }
            toDetect.forEach { it.close() }
            detecting.awaitAll()
            detected.close()
        }
        val reading = kotlin.concurrent.atomics.AtomicInt(0)
        // each page once it is read, or known unreadable (for the parts of a progressive chapter)
        val pageReady = Channel<Int>(Channel.UNLIMITED)
        val readers = List(tuning.ocrWorkers) { worker ->
            async(work) {
                for ((i, image, detections) in detected) {
                    val count = reading.addAndFetch(1)
                    if (detections == null || analyses[i] != null) {
                        onProgress(MangaProgress.Loading(count, n))
                        pageReady.trySend(i)
                        continue
                    }
                    onProgress(MangaProgress.Reading(count, n))
                    val analysis = pipeline.read(image, detections, worker, request.from)
                    analyses[i] = analysis
                    store.withLock { withContext(ioDispatcher) { pages.put(hashes.filled(i, "hash"), analysis, readVersion(i)) } }
                    pageReady.trySend(i)
                }
            }
        }
        if (flow == MangaTranslationFlow.PROGRESSIVE) {
            launch { readers.awaitAll(); pageReady.close() }
            return@coroutineScope drawProgressively(
                request, codec, pipeline, work, provider, reuseDrawn, variant, started, unreadPages,
                encoded, hashes, analyses, pageReady, { detectorOf(it) }, reader, font, onProgress, onPage,
                detectorStats = detectors.indices.joinToString(" ") { k -> "${detectors[k].id}:${assignment.count { it == k }}" },
            )
        }
        pageReady.close()
        readers.awaitAll()
        val read = started.elapsedNow()

        // 2. Translate the chapter as one script, one line per region.
        // a page with no text (none found, or only page numbers and punctuation) is neither
        // translated nor drawn: the reader shows it as it is
        val readable = (0 until n).filter { analyses[it]?.let(MangaScript::hasText) == true }
        val script = MangaScript.build(readable.map { analyses.filled(it, "analysis") }, request.direction)
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
        var firstPage: kotlin.time.Duration? = null
        val nextPage = kotlin.concurrent.atomics.AtomicInt(0)
        val drawing = kotlin.concurrent.atomics.AtomicInt(0)
        val handOver = Mutex() // pages reach the caller one at a time
        for (i in 0 until n) if (analyses[i] != null && i !in readable) { drawnKeys[i] = ORIGINAL_PAGE; encoded[i] = null; onPage(i, null) }
        List(tuning.ocrWorkers) { worker ->
            async(work) {
                while (true) {
                    val position = nextPage.fetchAndAdd(1).takeIf { it < readable.size } ?: break
                    val i = readable[position]
                    onProgress(MangaProgress.Drawing(drawing.addAndFetch(1), readable.size))
                    val drawnText = byPage[position].orEmpty().entries.sortedBy { it.key }.joinToString("\u0001") { "${it.key}\u0002${it.value}" }
                    val key = renders.key(hashes.filled(i, "hash"), request.to, provider, request.orientation, inpaint = pipeline.inpaints, detector = detectorOf(i), translation = drawnText, reader = reader, font = font)
                    val cached = if (reuseDrawn) withContext(ioDispatcher) { renders.get(key) } else null
                    val jpeg = cached ?: run {
                        val image = codec.decode(encoded.filled(i, "image")) ?: return@run null
                        codec.encodeJpeg(pipeline.render(image, analyses.filled(i, "analysis"), byPage[position].orEmpty(), request.orientation, worker, request.to), 90)
                    }?.also {
                        // A page drawn from an incomplete translation is shown, never kept: kept,
                        // it would stand in for the page once the rest is translated.
                        if (it !== cached && translated.complete) renders.put(key, it)
                    }
                    if (jpeg != null) drawnKeys[i] = key
                    encoded[i] = null
                    val shown = jpeg?.let { codec.forDisplay(it) }
                    handOver.withLock { if (firstPage == null) firstPage = started.elapsedNow(); onPage(i, shown) }
                }
            }
        }.awaitAll()
        for (i in 0 until n) if (analyses[i] == null) onPage(i, null)
        val total = started.elapsedNow()
        lastRun = MangaRunStats(
            tuning, n, fromCache = false, readMs = read.inWholeMilliseconds,
            translateMs = (translatedAt - read).inWholeMilliseconds, drawMs = (total - translatedAt).inWholeMilliseconds,
            detector = detectors.indices.joinToString(" ") { k -> "${detectors[k].id}:${assignment.count { it == k }}" },
            firstPageMs = firstPage?.inWholeMilliseconds ?: 0,
        )
        // Only a chapter whose every page was drawn: a page that failed to load is tried again.
        if (n > 0 && drawnKeys.all { it != null } && translated.complete) {
            withContext(ioDispatcher) { pages.putChapterRenders(request.workKey, request.chapterKey, variant, drawnKeys.filterNotNull()) }
        }
        translated.complete && unreadPages.load() == 0
    }

    /**
     * The rest of a [MangaTranslationFlow.PROGRESSIVE] chapter, its pages being read: each part,
     * once all its pages are read, is translated (with the translated lines before it as
     * context) and its pages drawn while the next part is read and translated. The chapter is
     * then recorded by one request for the whole of it, which finds every line in the memory.
     */
    private suspend fun drawProgressively(
        request: MangaChapterRequest,
        codec: PageCodec,
        pipeline: MangaPipeline,
        work: CoroutineDispatcher,
        provider: String,
        reuseDrawn: Boolean,
        variant: String,
        started: kotlin.time.TimeSource.Monotonic.ValueTimeMark,
        unreadPages: kotlin.concurrent.atomics.AtomicInt,
        encoded: Array<ByteArray?>,
        hashes: Array<String?>,
        analyses: Array<PageAnalysis?>,
        pageReady: Channel<Int>,
        detectorOf: (Int) -> String?,
        reader: String?,
        font: String?,
        onProgress: (MangaProgress) -> Unit,
        onPage: suspend (index: Int, jpeg: ByteArray?) -> Unit,
        detectorStats: String,
    ): Boolean = coroutineScope {
        val n = request.pageCount
        val tuning = pipeline.tuning
        val drawnKeys = arrayOfNulls<String>(n)
        val handOver = Mutex()
        val drawing = kotlin.concurrent.atomics.AtomicInt(0)
        var firstPage: kotlin.time.Duration? = null
        // page, its regions' translations, and whether its part was translated completely
        val toDraw = Channel<Triple<Int, Map<Int, String>, Boolean>>(Channel.UNLIMITED)
        suspend fun draw(worker: Int, i: Int, translations: Map<Int, String>, complete: Boolean) {
            onProgress(MangaProgress.Drawing(drawing.addAndFetch(1).coerceAtMost(n), n))
            val drawnText = translations.entries.sortedBy { it.key }.joinToString("\u0001") { "${it.key}\u0002${it.value}" }
            val key = renders.key(hashes.filled(i, "hash"), request.to, provider, request.orientation, inpaint = pipeline.inpaints, detector = detectorOf(i), translation = drawnText, reader = reader, font = font)
            val cached = if (reuseDrawn) withContext(ioDispatcher) { renders.get(key) } else null
            val jpeg = cached ?: run {
                val image = codec.decode(encoded.filled(i, "image")) ?: return@run null
                codec.encodeJpeg(pipeline.render(image, analyses.filled(i, "analysis"), translations, request.orientation, worker, request.to), 90)
            }?.also {
                // drawn from an incomplete translation: shown, never kept
                if (it !== cached && complete) renders.put(key, it)
            }
            if (jpeg != null) drawnKeys[i] = key
            // a page drawn without some of its lines may be drawn again with them
            if (complete) encoded[i] = null
            val shown = jpeg?.let { codec.forDisplay(it) }
            handOver.withLock { if (firstPage == null) firstPage = started.elapsedNow(); onPage(i, shown) }
        }
        val drawers = List(tuning.ocrWorkers) { worker ->
            async(work) { for ((i, translations, complete) in toDraw) draw(worker, i, translations, complete) }
        }
        val ready = BooleanArray(n)
        var start = 0
        var size = 1
        val context = ArrayList<Pair<String, String>>()
        var translating = kotlin.time.Duration.ZERO
        // a part translated in part was drawn with lines left as they are: the request for the whole
        // chapter may find them, but the pages shown do not have them until they are drawn again
        var partsComplete = true
        val drawnIncomplete = mutableSetOf<Int>()
        suspend fun translatePart(end: Int) {
            val part = (start until end).filter { analyses[it]?.let(MangaScript::hasText) == true }
            for (i in start until end) if (analyses[i] != null && i !in part) {
                // no text: shown as it is, neither translated nor drawn
                drawnKeys[i] = ORIGINAL_PAGE; encoded[i] = null
                handOver.withLock { onPage(i, null) }
            }
            if (part.isNotEmpty()) {
                val script = MangaScript.build(part.map { analyses.filled(it, "analysis") }, request.direction)
                val began = kotlin.time.TimeSource.Monotonic.markNow()
                val translated = translator.translate(
                    ChapterTranslationRequest(
                        workKey = request.workKey, chapterKey = request.chapterKey, text = script.text,
                        from = request.from, to = request.to, keep = script::keep, exportable = request.exportable, labels = request.labels,
                        partial = true, segment = true, context = context.toList(),
                    ),
                ) { done, total -> onProgress(MangaProgress.Translating(done, total)) }
                translating += began.elapsedNow()
                if (!translated.complete) { partsComplete = false; drawnIncomplete += part }
                translated.segments.forEach { line ->
                    val t = line.translation
                    if (t != null && !line.kept && line.source.isNotBlank()) context += line.source to t
                }
                if (context.size > MAX_CONTEXT_LINES) context.subList(0, context.size - MAX_CONTEXT_LINES).clear()
                val byPage = script.translations(translated.segments.map { it.translation })
                part.forEachIndexed { position, i -> toDraw.send(Triple(i, byPage[position].orEmpty(), translated.complete)) }
            }
            for (i in start until end) if (analyses[i] == null) handOver.withLock { onPage(i, null) }
            start = end
            size = minOf(size * 2, MAX_PART_PAGES)
        }
        for (i in pageReady) {
            ready[i] = true
            while (start < n) {
                val end = minOf(n, start + size)
                if ((start until end).all { ready[it] }) translatePart(end) else break
            }
        }
        while (start < n) translatePart(minOf(n, start + size))
        val read = started.elapsedNow()

        // The chapter as a whole: every line is in the memory now, so this mostly records it; the
        // lines a part was left without are asked for again, and pages drawn without them redrawn.
        val readable = (0 until n).filter { analyses[it]?.let(MangaScript::hasText) == true }
        val script = MangaScript.build(readable.map { analyses.filled(it, "analysis") }, request.direction)
        val began = kotlin.time.TimeSource.Monotonic.markNow()
        val whole = translator.translate(
            ChapterTranslationRequest(
                workKey = request.workKey, chapterKey = request.chapterKey, text = script.text,
                from = request.from, to = request.to, keep = script::keep, exportable = request.exportable, labels = request.labels,
                partial = unreadPages.load() > 0,
            ),
        ) { _, _ -> }
        translating += began.elapsedNow()
        toDraw.close()
        drawers.awaitAll()
        if (whole.complete && drawnIncomplete.isNotEmpty()) {
            val byPage = script.translations(whole.segments.map { it.translation })
            val again = readable.withIndex().filter { (_, i) -> i in drawnIncomplete && encoded[i] != null }
            again.chunked(maxOf(1, (again.size + tuning.ocrWorkers - 1) / tuning.ocrWorkers)).mapIndexed { worker, pages ->
                async(work) { for ((position, i) in pages) draw(worker, i, byPage[position].orEmpty(), complete = true) }
            }.awaitAll()
            partsComplete = drawnIncomplete.all { encoded[it] == null }
        }
        val total = started.elapsedNow()
        lastRun = MangaRunStats(
            tuning, n, fromCache = false, readMs = read.inWholeMilliseconds,
            translateMs = translating.inWholeMilliseconds, drawMs = (total - read).inWholeMilliseconds,
            detector = detectorStats, firstPageMs = firstPage?.inWholeMilliseconds ?: 0, flow = MangaTranslationFlow.PROGRESSIVE,
        )
        if (n > 0 && drawnKeys.all { it != null } && whole.complete && partsComplete) {
            withContext(ioDispatcher) { pages.putChapterRenders(request.workKey, request.chapterKey, variant, drawnKeys.filterNotNull()) }
        }
        // a retry draws them from the memory, where the whole chapter's request left its lines
        whole.complete && partsComplete && unreadPages.load() == 0
    }

    /**
     * The language and script guesses below, per work. The panel (in the UI's scope) and the
     * prefetch (on [Dispatchers.Default]) ask at the same time, so the maps are only touched under
     * [guessLock]; the probe itself runs outside it, and two that race just both write the same guess.
     */
    private val guessLock = Mutex()
    private val japaneseGuesses = mutableMapOf<String, Boolean>()

    /** A page for a language probe; null when it cannot be loaded. A probe cancelled stops, not guesses from fewer pages. */
    private suspend fun probePage(loadPage: suspend (Int) -> ByteArray?, i: Int): ByteArray? =
        try {
            loadPage(i)
        } catch (e: CancellationException) {
            throw e
        } catch (logged: Exception) {
            Log.warn("Manga", logged) { "Loading a page for a language probe failed" }
            null
        }

    /**
     * Reads page [i] of a chapter of [pageCount] pages for a language probe as the chapter itself
     * would (the detector it is assigned, the reader for [language]) and keeps the result in the
     * page store, so the chapter that starts after does not read it again. Null when undecodable.
     */
    private suspend fun probeRead(pipeline: MangaPipeline, codec: PageCodec, bytes: ByteArray, i: Int, pageCount: Int, language: String): PageAnalysis? =
        withContext(work) {
            val k = assignPages(pageCount, pipeline.detectorWeights)[i]
            val detector = pipeline.detectors[k].id.takeIf { it != MangaDetector.ID }
            val version = listOfNotNull(MangaPageStore.VERSION, detector, pipeline.recognizerId(language)).joinToString("+")
            val hash = pageHash(bytes)
            // a chapter writes the store only while it holds the pipeline's chapters: so does a probe
            pipeline.chapters.withLock {
                withContext(ioDispatcher) { pages.get(hash, version) } ?: run {
                    val image = codec.decode(bytes) ?: return@withLock null
                    pipeline.read(image, pipeline.detect(image, k), language = language)
                        .also { analysis -> withContext(ioDispatcher) { pages.put(hash, analysis, version) } }
                }
            }
        }

    /**
     * Whether a work whose language its source does not say reads as Japanese, from the text on its
     * first pages (the ones [loadPage] gives, up to three, until enough is read). Null when that
     * could not be told: no models, no pages, too little text. Remembered for [workKey] while the
     * app runs, so reopening the work does not read the pages again.
     */
    public suspend fun readsJapanese(workKey: String, pageCount: Int, loadPage: suspend (Int) -> ByteArray?): Boolean? {
        guessLock.withLock { japaneseGuesses[workKey] }?.let { return it }
        val codec = platform?.codec ?: return null
        if (!modelsInstalled() || pageCount == 0) return null
        var held: MangaPipeline? = null
        val texts = mutableListOf<String>()
        try {
            val pipeline = withContext(work) { acquire().also { held = it } }
            for (i in 0 until minOf(pageCount, 3)) {
                val bytes = probePage(loadPage, i) ?: continue
                texts += probeRead(pipeline, codec, bytes, i, pageCount, "ja")?.regions?.map { it.text }.orEmpty()
                if (texts.sumOf { t -> t.count { it.isLetter() } } >= ENOUGH_LETTERS) break
            }
        } finally {
            held?.let { withContext(NonCancellable) { done(it) } }
        }
        currentCoroutineContext().ensureActive()
        return guessedJapanese(workKey, texts)
    }

    /** [looksJapanese] for [texts], remembered for the work when it is the work's answer. */
    private suspend fun guessedJapanese(workKey: String, texts: List<String>): Boolean? {
        val guess = looksJapanese(texts) ?: return null
        // "Not Japanese" from a little text (a cover, a colophon) is this chapter's answer, not the work's
        val settled = guess || texts.sumOf { t -> t.count { it.isLetter() } } >= ENOUGH_LETTERS
        if (settled) guessLock.withLock { japaneseGuesses[workKey] = guess }
        return guess
    }

    private val scriptGuesses = mutableMapOf<String, String>()

    /**
     * The script of a Chinese work whose source does not say it, `zh-Hans` or `zh-Hant`, from the
     * text on its first pages, read by the Chinese recogniser (manga-ocr reads both scripts as
     * Japanese kanji). Null when that could not be told: the recogniser is not downloaded, no
     * pages, too little text. Remembered for [workKey] while the app runs.
     */
    public suspend fun readsChineseScript(workKey: String, pageCount: Int, loadPage: suspend (Int) -> ByteArray?): String? {
        guessLock.withLock { scriptGuesses[workKey] }?.let { return it }
        val codec = platform?.codec ?: return null
        if (!modelsInstalled() || pageCount == 0) return null
        var held: MangaPipeline? = null
        val texts = mutableListOf<String>()
        try {
            val pipeline = withContext(work) { acquire().also { held = it } }
            if (!pipeline.reads(ChineseScript.SIMPLIFIED)) return null
            for (i in 0 until minOf(pageCount, 3)) {
                val bytes = probePage(loadPage, i) ?: continue
                texts += probeRead(pipeline, codec, bytes, i, pageCount, ChineseScript.SIMPLIFIED)?.regions?.map { it.text }.orEmpty()
                if (ChineseScript.detect(texts.joinToString("\n"), minimum = ENOUGH_SCRIPT_CHARACTERS) != null) break
            }
        } finally {
            held?.let { withContext(NonCancellable) { done(it) } }
        }
        currentCoroutineContext().ensureActive()
        return ChineseScript.detect(texts.joinToString("\n"), minimum = MIN_SCRIPT_CHARACTERS)?.also { guess -> guessLock.withLock { scriptGuesses[workKey] = guess } }
    }

    /**
     * Runs [delete] once the chapters still reading (one translated ahead may be waiting on its
     * translation, its fonts not yet loaded) have ended, no new one starting until it is done:
     * what they read is deleted only after, and none opens it half deleted.
     */
    private suspend fun whileIdle(delete: suspend () -> Unit) {
        removing.update { it + 1 }
        try {
            release()
            held.first { it == 0 }
            // the last chapter has ended: its models close before their files go
            release()
            delete()
        } finally {
            removing.update { it - 1 }
        }
    }

    /** Frees the models' memory once no chapter is using them; they load again on the next chapter. */
    public suspend fun release(): Unit = pipelineLock.withLock {
        val current = pipeline ?: return@withLock
        if (current in holders) { stale = true } else { current.close(); pipeline = null; stale = false }
    }
}

private const val ENOUGH_LETTERS = 40

/** Characters written only one way that settle a work's script; fewer still decide it after the last page read. */
private const val ENOUGH_SCRIPT_CHARACTERS = 12
private const val MIN_SCRIPT_CHARACTERS = 3

/**
 * Whether text read off manga pages is Japanese: the reader is trained on Japanese, so it reads
 * Chinese as kanji and Korean or English as stray symbols, but only Japanese has kana in plenty.
 * Null with too few letters to tell.
 */
internal fun looksJapanese(texts: List<String>): Boolean? {
    val letters = texts.sumOf { t -> t.count { it.isLetter() } }
    if (letters < 12) return null
    val kana = texts.sumOf { t -> t.count { it in 'ぁ'..'ヺ' && it != '・' } }
    return kana >= 4 && kana * 100 >= letters * 15
}

/** The value an earlier stage of the chapter put in page [page]'s slot; empty means the stages ran out of order. */
private fun <T : Any> Array<T?>.filled(page: Int, what: String): T = checkNotNull(this[page]) { "The $what of page $page is not ready" }
