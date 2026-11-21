package dev.shinsou.kmp.manga

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The drawn pages kept for reuse (0007 C5): each page in [renders] by its [ChapterRenderKeys.page]
 * key, and for a chapter drawn whole the list of its pages' keys in [pages], so it is shown again
 * without loading its pages.
 */
internal class DrawnChapters(
    private val pages: MangaPageStore,
    val renders: MangaRenderCache,
    private val io: CoroutineDispatcher,
) {
    /**
     * The drawn pages of [request]'s chapter in [variant] (null for a page shown as it is), when
     * every one is still kept; null when the chapter was not drawn whole or some page is gone.
     */
    suspend fun find(request: MangaChapterRequest, variant: String): List<ByteArray?>? {
        val n = request.pageCount
        val manifest = withContext(io) { pages.chapterRenders(request.workKey, request.chapterKey, variant) }?.takeIf { it.size == n }
            ?: return null
        val drawn = withContext(io) { manifest.map { key -> if (key == ORIGINAL_PAGE) null else renders.get(key) } }
        // a page with no text was not drawn: the reader shows it as it is
        return drawn.takeIf { manifest.indices.all { i -> manifest[i] == ORIGINAL_PAGE || drawn[i] != null } }
    }

    /** Keeps [request]'s chapter as drawn in [variant], by its pages' keys. */
    suspend fun record(request: MangaChapterRequest, variant: String, pageKeys: Array<String?>) {
        withContext(io) { pages.putChapterRenders(request.workKey, request.chapterKey, variant, pageKeys.filterNotNull()) }
    }

    /** The page drawn by [key], when kept. */
    suspend fun page(key: String): ByteArray? = withContext(io) { renders.get(key) }
}

/**
 * Draws a chapter's pages in their translations, or takes them from [DrawnChapters], and hands
 * them to [onPage] one at a time (several drawers finish pages at once).
 */
internal class ChapterDrawing(
    private val plan: ChapterPlan,
    private val stages: ChapterStages,
    private val env: ChapterEnvironment,
    private val slots: ChapterPages,
    private val onPage: suspend (index: Int, jpeg: ByteArray?) -> Unit,
    private val started: TimeSource.Monotonic.ValueTimeMark,
) {
    private val handOver = Mutex()

    /** From the start until the first drawn page was handed over. */
    var firstPage: Duration? = null
        private set

    /**
     * Draws [page] on [worker]'s renderer. A page drawn from an incomplete translation is shown,
     * never kept: kept, it would stand in for the page once the rest is translated. Its encoded
     * image is let go when [forgetImage] (a page drawn without some of its lines may be drawn again).
     */
    suspend fun draw(worker: Int, page: PageToDraw, forgetImage: Boolean) {
        val i = page.index
        val key = plan.renderKeys.page(slots.hash(i), plan.detectors.cacheId(i), page.translations)
        val cached = if (plan.reuseDrawn) env.drawn.page(key) else null
        val jpeg = cached ?: render(worker, page)?.also {
            if (page.complete) env.drawn.renders.put(key, it)
        }
        if (jpeg != null) slots.drawnKeys[i] = key
        if (forgetImage) slots.encoded[i] = null
        val shown = jpeg?.let { env.codec.forDisplay(it) }
        handOver.withLock {
            if (firstPage == null) firstPage = started.elapsedNow()
            onPage(i, shown)
        }
    }

    /** The page drawn anew, as JPEG; null when its image cannot be decoded. */
    private fun render(worker: Int, page: PageToDraw): ByteArray? {
        val image = env.codec.decode(slots.image(page.index)) ?: return null
        return env.codec.encodeJpeg(stages.render(image, slots.analysis(page.index), page.translations, worker), 90)
    }

    /** Hands over page [page] as it is (no text, or not read), in turn with the drawn ones. */
    suspend fun handOverOriginal(page: Int) {
        handOver.withLock { onPage(page, null) }
    }
}
