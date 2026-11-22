package dev.shinsou.kmp.manga

import dev.shinsou.kmp.translation.ChapterTranslation
import dev.shinsou.kmp.translation.ChapterTranslationRequest
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.TranslationLabels
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
    override suspend fun providerId(): String = chapters.engineIdentity()
    override suspend fun providerId(from: String, to: String): String = chapters.engineIdentity(from, to)
    override suspend fun translate(request: ChapterTranslationRequest, onProgress: (Int, Int) -> Unit): ChapterTranslation =
        this@asMangaTranslator.chapters.translate(request, onProgress)
    override suspend fun hasChapter(workKey: String, chapterKey: String, from: String, to: String): Boolean =
        chapterKey in storage.translatedChapterKeys(workKey, to, from)
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
 *
 * A facade (0007 C5): the downloads and accelerators are [MangaResourceManager]'s, the shared
 * pipeline [MangaPipelines]', a chapter's run [MangaChapterRun]'s, the language probes
 * [MangaLanguageProbes]'.
 */
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
) : MangaTranslationService {
    private val pipelines: MangaPipelines = MangaPipelines { resourceManager.newPipeline(tuning, readVocab) }
    private val resourceManager: MangaResourceManager = MangaResourceManager(platform, models, pipelines, work, ioDispatcher)
    private val probes = MangaLanguageProbes(platform, models, pipelines, pages, work, ioDispatcher)
    private val drawnChapters = DrawnChapters(pages, renders, ioDispatcher)

    /** How this device shares the models: the override, else the device's own; null where unsupported. */
    override val deviceTuning: MangaTuning? get() = tuning ?: platform?.let { MangaTuning.of(it.profile(), models.cpuMode()) }

    /** How chapters are translated: as a whole, or in parts as they are read. */
    override fun translationFlow(): MangaTranslationFlow = models.translationFlow()

    override fun setTranslationFlow(flow: MangaTranslationFlow): Unit = models.setTranslationFlow(flow)

    /** How hard the CPU works on pages (all of recognition, and detection and redrawing on the CPU). */
    override fun cpuMode(): MangaCpuMode = models.cpuMode()

    /** Chooses how hard the CPU works; chapters started from then on use it. */
    override suspend fun setCpuMode(mode: MangaCpuMode) {
        models.setCpuMode(mode)
        release()
    }

    /** The last chapter's timings (developer diagnostics). */
    override var lastRun: MangaRunStats? = null
        private set

    /** False on devices the inference and image libraries are not shipped for. */
    override val deviceSupported: Boolean get() = platform != null

    override fun modelsInstalled(): Boolean = models.paths() != null

    override fun missingModelBytes(): Long = models.missingBytes(MangaModelCatalog.VISION)

    override suspend fun downloadModels(onProgress: (received: Long, total: Long) -> Unit): Unit = resourceManager.downloadModels(onProgress)

    /**
     * What a chapter from [from] into [to] still needs to download (MangaLanguages.packs): the
     * recogniser for pages not in Japanese, the font for simplified Chinese. Zero when all is here.
     */
    override fun missingLanguageBytes(from: String, to: String): Long = resourceManager.missingLanguageBytes(from, to)

    /** Downloads what [missingLanguageBytes] counts; chapters started from then on use it. */
    override suspend fun downloadLanguage(from: String, to: String, onProgress: (received: Long, total: Long) -> Unit): Unit =
        resourceManager.downloadLanguage(from, to, onProgress)

    /** The ways this device can run detection, which are chosen, and what each still needs. */
    override fun acceleratorStates(): List<MangaAccelerators.AcceleratorState> = resourceManager.acceleratorStates()

    /**
     * Downloads what [accelerator] needs (its models, and for Qualcomm's NPU the QNN runtime from
     * Maven Central), then readies it ([MangaPlatform.prepare]); [onPreparing] is told when the
     * downloads are done and the (longer) preparation starts.
     */
    override suspend fun downloadAccelerator(
        accelerator: MangaAccelerator,
        onProgress: (received: Long, total: Long) -> Unit,
        onPreparing: () -> Unit,
    ): Unit = resourceManager.downloadAccelerator(accelerator, onProgress, onPreparing)

    /**
     * Removes what [accelerator] downloaded and stops using it, and every other choice that needs
     * the same files (on an iPhone the GPU and the Neural Engine share one model).
     */
    override suspend fun removeAccelerator(accelerator: MangaAccelerator): Unit = resourceManager.removeAccelerator(accelerator)

    /** Chooses where detection runs (at least one); chapters started from then on use the choice. */
    override suspend fun selectAccelerators(accelerators: Set<MangaAccelerator>): Unit = resourceManager.selectAccelerators(accelerators)

    /**
     * Everything manga translation downloads on this device, for the settings to list and manage:
     * the recognition models, the optional packs, what each accelerator needs (one row for choices
     * that share their files), and what older builds left behind.
     */
    override fun resources(): List<MangaResource> = resourceManager.resources()

    /** Downloads what [resource] still misses; an accelerator's download also chooses it. */
    override suspend fun downloadResource(resource: MangaResource, onProgress: (received: Long, total: Long) -> Unit, onPreparing: () -> Unit): Unit =
        resourceManager.downloadResource(resource, onProgress, onPreparing)

    /** Deletes [resource] from this device; chapters that need it offer the download again. */
    override suspend fun removeResource(resource: MangaResource): Unit = resourceManager.removeResource(resource)

    /** Whether the optional LaMa pack is here: text drawn over the artwork is then translated too. */
    override fun inpaintInstalled(): Boolean = models.installed(MangaModelCatalog.INPAINT) != null

    override fun missingInpaintBytes(): Long = models.missingBytes(MangaModelCatalog.INPAINT)

    /** Downloads the LaMa pack; chapters started from then on use it. */
    override suspend fun downloadInpaint(onProgress: (received: Long, total: Long) -> Unit): Unit = resourceManager.downloadInpaint(onProgress)

    /** Removes the LaMa pack; text over artwork stays as drawn again. */
    override suspend fun removeInpaint(): Unit = resourceManager.removeInpaint()

    /**
     * Translates [request]'s pages and hands each finished page to [onPage] as JPEG bytes (null
     * for a page that could not be read). Pages already drawn for this language, provider and
     * layout come straight from the cache. Returns whether every line was translated and every
     * page read: a chapter that was not is neither kept as drawn nor recorded as translated, and
     * is to be tried again. [MangaChapterRequest.loadPage] returns null for a page left as it is
     * on purpose (a scrambled one the reader descrambles itself), and throws for one that failed.
     */
    override suspend fun translateChapter(
        request: MangaChapterRequest,
        onProgress: (MangaProgress) -> Unit,
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
        return pipelines.holding(work) { pipeline ->
            withContext(work) { translateChapter(request, codec, pipeline, onProgress, onPage) }
        }
    }

    private suspend fun translateChapter(
        request: MangaChapterRequest,
        codec: PageCodec,
        pipeline: MangaPipeline,
        onProgress: (MangaProgress) -> Unit,
        onPage: suspend (index: Int, jpeg: ByteArray?) -> Unit,
    ): Boolean {
        val plan = plan(request, pipeline)
        // the saver keeps every step on one thread the platform puts on an efficiency core
        val work = if (pipeline.tuning.efficiencyThread) platform?.efficiencyDispatcher() ?: work else work
        // A chapter drawn before in this language, by this provider and layout, is shown as drawn:
        // its pages are not loaded again (from the network, for a source) just to find them.
        val drawn = if (plan.reuseDrawn) drawnChapters.find(request, plan.variant) else null
        if (drawn != null) {
            for (i in 0 until request.pageCount) onPage(i, drawn[i]?.let { withContext(work) { codec.forDisplay(it) } })
            lastRun = MangaRunStats(pipeline.tuning, request.pageCount, fromCache = true, detector = pipeline.detectorId)
            return true
        }
        val env = ChapterEnvironment(codec, work, ioDispatcher, pages, drawnChapters, translator)
        // The pipeline's readers, detectors and text measurers are shared by its workers, which
        // are numbered from zero in every chapter: two chapters at once (the reader's and the
        // queue's) would overwrite each other's pages. Its chapters run one after the other.
        return pipeline.chapters.withLock {
            val flow = if (request.reading) models.translationFlow() else MangaTranslationFlow.CHAPTER
            MangaChapterRun(plan, PipelineStages(pipeline, request), env, onProgress, onPage) { lastRun = it }.run(flow)
        }
    }

    /**
     * What [request]'s chapter is keyed by with [pipeline]: pages read by another detector or
     * recogniser, or drawn in another font, are cached apart (the defaults keep their older keys);
     * with several detectors, each page always goes to the same one.
     */
    private suspend fun plan(request: MangaChapterRequest, pipeline: MangaPipeline): ChapterPlan {
        val provider = translator.providerId(request.from, request.to)
        // Pages drawn before stand for the translation only while it is still kept: deleted (or
        // made by another engine), the chapter is translated and drawn again.
        val reuseDrawn = translator.hasChapter(request.workKey, request.chapterKey, request.from, request.to)
        val detectors = DetectorAssignment(pipeline.detectors.map { it.id }, assignPages(request.pageCount, pipeline.detectorWeights))
        val reader = pipeline.recognizerId(request.from)
        val keys = ChapterRenderKeys(renders, request, provider, pipeline.inpaints, reader, pipeline.fontId(request.to))
        return ChapterPlan(request, detectors, reader, keys, reuseDrawn, keys.chapter(pipeline.detectorId))
    }

    /**
     * Whether a work whose language its source does not say reads as Japanese, from the text on its
     * first pages (the ones [loadPage] gives, up to three, until enough is read). Null when that
     * could not be told: no models, no pages, too little text. Remembered for [workKey] while the
     * app runs, so reopening the work does not read the pages again.
     */
    override suspend fun readsJapanese(workKey: String, pageCount: Int, loadPage: suspend (Int) -> ByteArray?): Boolean? =
        probes.readsJapanese(workKey, pageCount, loadPage)

    /**
     * The script of a Chinese work whose source does not say it, `zh-Hans` or `zh-Hant`, from the
     * text on its first pages, read by the Chinese recogniser (manga-ocr reads both scripts as
     * Japanese kanji). Null when that could not be told: the recogniser is not downloaded, no
     * pages, too little text. Remembered for [workKey] while the app runs.
     */
    override suspend fun readsChineseScript(workKey: String, pageCount: Int, loadPage: suspend (Int) -> ByteArray?): String? =
        probes.readsChineseScript(workKey, pageCount, loadPage)

    /** Frees the models' memory once no chapter is using them; they load again on the next chapter. */
    public suspend fun release(): Unit = pipelines.release()
}
