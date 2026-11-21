package dev.shinsou.kmp.manga

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.TimeSource

/** The largest part of a progressive chapter: a few requests a chapter, each still soon. */
private const val MAX_PART_PAGES = 8

/** Translated lines carried to the next part of a progressive chapter as context. */
private const val MAX_CONTEXT_LINES = 30

/**
 * One chapter translated and drawn (0007 C5), while its caller holds the pipeline's chapters:
 * [ChapterReading] reads its pages, then it is translated and its pages drawn by [ChapterDrawing],
 * as one script ([MangaTranslationFlow.CHAPTER]) or in parts as its pages are read
 * ([MangaTranslationFlow.PROGRESSIVE]). How it went is given to [report]; a chapter whose every
 * page was drawn from a complete translation is kept as drawn ([DrawnChapters.record]).
 */
@OptIn(ExperimentalAtomicApi::class)
internal class MangaChapterRun(
    private val plan: ChapterPlan,
    private val stages: ChapterStages,
    private val env: ChapterEnvironment,
    private val onProgress: (MangaProgress) -> Unit,
    private val onPage: suspend (index: Int, jpeg: ByteArray?) -> Unit,
    private val report: (MangaRunStats) -> Unit,
) {
    private val request = plan.request
    private val n = plan.pageCount
    private val tuning = stages.tuning
    private val started = TimeSource.Monotonic.markNow()
    private val unreadPages = AtomicInt(0)
    private val slots = ChapterPages(n)

    /**
     * Whether every line was translated and every page read: a chapter that was not is neither
     * kept as drawn nor recorded as translated, and is to be tried again.
     */
    suspend fun run(flow: MangaTranslationFlow): Boolean = coroutineScope {
        val reading = ChapterReading(plan, stages, env, slots, unreadPages, onProgress).start(this)
        if (flow == MangaTranslationFlow.PROGRESSIVE) {
            launch {
                reading.readers.awaitAll()
                reading.pageReady.close()
            }
            drawProgressively(reading.pageReady)
        } else {
            reading.pageReady.close()
            reading.readers.awaitAll()
            drawWhole()
        }
    }

    /** The chapter translated as one script, one line per region, then its pages drawn. */
    private suspend fun drawWhole(): Boolean = coroutineScope {
        val read = started.elapsedNow()
        val readable = slots.readable(0 until n)
        val script = MangaScript.build(readable.map(slots::analysis), request.direction)
        val translated = env.translator.translate(plan.translation(script, partial = unreadPages.load() > 0)) { done, total ->
            onProgress(MangaProgress.Translating(done, total))
        }
        val byPage = script.translations(translated.segments.map { it.translation })
        val translatedAt = started.elapsedNow()

        // Each page drawn in its translation (or taken from the cache), several at a time, in
        // page order as far as the workers allow.
        val drawing = ChapterDrawing(plan, stages, env, slots, onPage, started)
        val nextPage = AtomicInt(0)
        val drawn = AtomicInt(0)
        for (i in 0 until n) {
            if (slots.analyses[i] != null && i !in readable) {
                slots.keepOriginal(i)
                onPage(i, null)
            }
        }
        List(tuning.ocrWorkers) { worker ->
            async(env.work) {
                while (true) {
                    val position = nextPage.fetchAndAdd(1).takeIf { it < readable.size } ?: break
                    onProgress(MangaProgress.Drawing(drawn.addAndFetch(1), readable.size))
                    drawing.draw(worker, PageToDraw(readable[position], byPage[position].orEmpty(), translated.complete), forgetImage = true)
                }
            }
        }.awaitAll()
        for (i in 0 until n) if (slots.analyses[i] == null) onPage(i, null)
        val total = started.elapsedNow()
        report(
            MangaRunStats(
                tuning, n, fromCache = false, readMs = read.inWholeMilliseconds,
                translateMs = (translatedAt - read).inWholeMilliseconds, drawMs = (total - translatedAt).inWholeMilliseconds,
                detector = plan.detectors.stats(), firstPageMs = drawing.firstPage?.inWholeMilliseconds ?: 0,
            ),
        )
        // Only a chapter whose every page was drawn: a page that failed to load is tried again.
        if (slots.allDrawn() && translated.complete) env.drawn.record(request, plan.variant, slots.drawnKeys)
        translated.complete && unreadPages.load() == 0
    }

    /**
     * A [MangaTranslationFlow.PROGRESSIVE] chapter, its pages being read ([pageReady]): each part,
     * once all its pages are read, is translated (with the translated lines before it as context)
     * and its pages drawn while the next part is read and translated. The chapter is then
     * recorded by one request for the whole of it, which finds every line in the memory.
     */
    private suspend fun drawProgressively(pageReady: ReceiveChannel<Int>): Boolean = coroutineScope {
        val drawing = ChapterDrawing(plan, stages, env, slots, onPage, started)
        val drawn = AtomicInt(0)
        suspend fun draw(worker: Int, page: PageToDraw) {
            onProgress(MangaProgress.Drawing(drawn.addAndFetch(1).coerceAtMost(n), n))
            // a page drawn without some of its lines may be drawn again with them
            drawing.draw(worker, page, forgetImage = page.complete)
        }
        val toDraw = Channel<PageToDraw>(Channel.UNLIMITED)
        val drawers = List(tuning.ocrWorkers) { worker ->
            async(env.work) { for (page in toDraw) draw(worker, page) }
        }
        val parts = ChapterParts(drawing, toDraw)
        for (i in pageReady) parts.pageRead(i)
        parts.rest()
        val read = started.elapsedNow()

        // The chapter as a whole: every line is in the memory now, so this mostly records it; the
        // lines a part was left without are asked for again, and pages drawn without them redrawn.
        val readable = slots.readable(0 until n)
        val script = MangaScript.build(readable.map(slots::analysis), request.direction)
        val began = TimeSource.Monotonic.markNow()
        val whole = env.translator.translate(plan.translation(script, partial = unreadPages.load() > 0)) { _, _ -> }
        val translating = parts.translating + began.elapsedNow()
        toDraw.close()
        drawers.awaitAll()
        var partsComplete = parts.complete
        if (whole.complete && parts.drawnIncomplete.isNotEmpty()) {
            val byPage = script.translations(whole.segments.map { it.translation })
            val again = readable.withIndex().filter { (_, i) -> i in parts.drawnIncomplete && slots.encoded[i] != null }
            again.chunked(maxOf(1, (again.size + tuning.ocrWorkers - 1) / tuning.ocrWorkers)).mapIndexed { worker, pages ->
                async(env.work) { for ((position, i) in pages) draw(worker, PageToDraw(i, byPage[position].orEmpty(), complete = true)) }
            }.awaitAll()
            partsComplete = parts.drawnIncomplete.all { slots.encoded[it] == null }
        }
        val total = started.elapsedNow()
        report(
            MangaRunStats(
                tuning, n, fromCache = false, readMs = read.inWholeMilliseconds,
                translateMs = translating.inWholeMilliseconds, drawMs = (total - read).inWholeMilliseconds,
                detector = plan.detectors.stats(), firstPageMs = drawing.firstPage?.inWholeMilliseconds ?: 0,
                flow = MangaTranslationFlow.PROGRESSIVE,
            ),
        )
        val complete = whole.complete && partsComplete
        if (slots.allDrawn() && complete) env.drawn.record(request, plan.variant, slots.drawnKeys)
        // a retry draws them from the memory, where the whole chapter's request left its lines
        complete && unreadPages.load() == 0
    }

    /**
     * The parts of a progressive chapter, translated in page order: the first is one page, so it
     * shows soonest; each next part doubles, up to [MAX_PART_PAGES]. Each part carries the lines
     * before it, and sends its pages to [toDraw].
     */
    private inner class ChapterParts(private val drawing: ChapterDrawing, private val toDraw: SendChannel<PageToDraw>) {
        private val ready = BooleanArray(n)
        private var start = 0
        private var size = 1
        private val context = ArrayList<Pair<String, String>>()

        /** Time spent translating the parts. */
        var translating: Duration = Duration.ZERO
            private set

        /**
         * Whether every part was translated completely. A part translated in part was drawn with
         * lines left as they are: the request for the whole chapter may find them, but the pages
         * shown do not have them until they are drawn again.
         */
        var complete: Boolean = true
            private set
        val drawnIncomplete: MutableSet<Int> = mutableSetOf()

        /** Page [i] is read: translates every part from the next one on whose pages are all read. */
        suspend fun pageRead(i: Int) {
            ready[i] = true
            while (start < n) {
                val end = minOf(n, start + size)
                if ((start until end).all { ready[it] }) translate(end) else break
            }
        }

        /** Every page is read: translates the parts left. */
        suspend fun rest() {
            while (start < n) translate(minOf(n, start + size))
        }

        private suspend fun translate(end: Int) {
            val part = slots.readable(start until end)
            for (i in start until end) {
                if (slots.analyses[i] != null && i !in part) {
                    // no text: shown as it is, neither translated nor drawn
                    slots.keepOriginal(i)
                    drawing.handOverOriginal(i)
                }
            }
            if (part.isNotEmpty()) translateLines(part)
            for (i in start until end) if (slots.analyses[i] == null) drawing.handOverOriginal(i)
            start = end
            size = minOf(size * 2, MAX_PART_PAGES)
        }

        private suspend fun translateLines(part: List<Int>) {
            val script = MangaScript.build(part.map(slots::analysis), request.direction)
            val began = TimeSource.Monotonic.markNow()
            val translated = env.translator.translate(
                plan.translation(script, partial = true).copy(segment = true, context = context.toList()),
            ) { done, total -> onProgress(MangaProgress.Translating(done, total)) }
            translating += began.elapsedNow()
            if (!translated.complete) {
                complete = false
                drawnIncomplete += part
            }
            translated.segments.forEach { line ->
                val t = line.translation
                if (t != null && !line.kept && line.source.isNotBlank()) context += line.source to t
            }
            if (context.size > MAX_CONTEXT_LINES) context.subList(0, context.size - MAX_CONTEXT_LINES).clear()
            val byPage = script.translations(translated.segments.map { it.translation })
            part.forEachIndexed { position, i -> toDraw.send(PageToDraw(i, byPage[position].orEmpty(), translated.complete)) }
        }
    }
}
