package dev.shinsou.kmp.manga

import dev.shinsou.kmp.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The stage-one workers of a chapter: [readers] end once every page is read, each sent to [pageReady] when it is. */
internal class ReadingPages(val readers: List<Deferred<Unit>>, val pageReady: Channel<Int>)

/**
 * Stage one of a chapter (0007 C5): every page read. Pages load several at a time (from the
 * network, for a source) but reach detection in order; detection runs on one worker per detector,
 * OCR on as many as the device has room for, each on whole pages, so every page reads the same
 * whatever the device. A page read before is only loaded, its reading taken from the page store.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ChapterReading(
    private val plan: ChapterPlan,
    private val stages: ChapterStages,
    private val env: ChapterEnvironment,
    private val slots: ChapterPages,
    /** Pages that failed to load or decode: the chapter is then only partly translated. */
    private val unreadPages: AtomicInt,
    private val onProgress: (MangaProgress) -> Unit,
) {
    private val n = plan.pageCount
    private val tuning = stages.tuning

    /** The page store is written from several readers. */
    private val store = Mutex()
    private val reading = AtomicInt(0)

    /** Starts loading, detecting and reading in [scope]. */
    fun start(scope: CoroutineScope): ReadingPages {
        val detected = Channel<DetectedPage>(capacity = tuning.ocrWorkers + 1)
        // one worker per detector, each with the pages assigned to it
        val toDetect = List(plan.detectors.count) { Channel<PageToDetect>(capacity = 2) }
        scope.launch(env.work) { load(detected, toDetect) }
        // each page once it is read, or known unreadable (for the parts of a progressive chapter)
        val pageReady = Channel<Int>(Channel.UNLIMITED)
        val readers = List(tuning.ocrWorkers) { worker ->
            scope.async(env.work) {
                for (page in detected) read(worker, page, pageReady)
            }
        }
        return ReadingPages(readers, pageReady)
    }

    /** Loads the pages in order, a few ahead, and sends each to its detector or, read before, on to the readers. */
    private suspend fun CoroutineScope.load(detected: SendChannel<DetectedPage>, toDetect: List<Channel<PageToDetect>>) {
        // loading, decoding and hashing run ahead, several pages at a time
        val loads = arrayOfNulls<Deferred<LoadedPage?>>(n)
        fun startLoad(i: Int) {
            if (i < n && loads[i] == null) loads[i] = async(env.work) { loadPage(i) }
        }
        val detecting = toDetect.indices.map { k ->
            async(env.work) { for (page in toDetect[k]) detected.send(DetectedPage(page.index, page.image, stages.detect(page.image, k))) }
        }
        for (i in 0 until n) {
            for (ahead in i until i + tuning.pageLoadsAhead) startLoad(ahead)
            val loaded = checkNotNull(loads[i]) { "The load of page $i is not ready" }.await()
            loads[i] = null
            route(i, loaded, detected, toDetect)
        }
        toDetect.forEach { it.close() }
        detecting.awaitAll()
        detected.close()
    }

    private suspend fun route(i: Int, loaded: LoadedPage?, detected: SendChannel<DetectedPage>, toDetect: List<Channel<PageToDetect>>) {
        if (loaded == null) {
            detected.send(DetectedPage(i, RgbImage(1, 1, ByteArray(3)), null))
            return
        }
        slots.hashes[i] = loaded.hash
        slots.encoded[i] = loaded.bytes
        val cached = store.withLock { withContext(env.io) { env.pages.get(loaded.hash, plan.readVersion(i)) } }
        if (cached != null) {
            slots.analyses[i] = cached
            detected.send(DetectedPage(i, loaded.image, emptyList()))
        } else {
            toDetect[plan.detectors.of(i)].send(PageToDetect(i, loaded.image))
        }
    }

    /**
     * Page [i], decoded and hashed. Null for a page left as it is on purpose (scrambled, not an
     * image), and for one that should have been read and was not (it threw, or is undecodable),
     * which is counted in [unreadPages].
     */
    private suspend fun loadPage(i: Int): LoadedPage? {
        @Suppress("TooGenericExceptionCaught") // any failure leaves this page unread
        val bytes = try {
            plan.request.loadPage(i)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Exception) {
            Log.warn("MangaTranslation", logged) { "page $i could not be loaded" }
            unreadPages.addAndFetch(1)
            null
        } ?: return null
        val image = env.codec.decode(bytes)
        if (image == null) unreadPages.addAndFetch(1)
        return image?.let { LoadedPage(bytes, it, pageHash(bytes)) }
    }

    /** Reads [page] on [worker]'s readers, unless it was read before or could not be loaded. */
    private suspend fun read(worker: Int, page: DetectedPage, pageReady: SendChannel<Int>) {
        val i = page.index
        val count = reading.addAndFetch(1)
        val detections = page.detections
        if (detections == null || slots.analyses[i] != null) {
            onProgress(MangaProgress.Loading(count, n))
            pageReady.trySend(i)
            return
        }
        onProgress(MangaProgress.Reading(count, n))
        val analysis = stages.read(page.image, detections, worker)
        slots.analyses[i] = analysis
        store.withLock { withContext(env.io) { env.pages.put(slots.hash(i), analysis, plan.readVersion(i)) } }
        pageReady.trySend(i)
    }

    private class LoadedPage(val bytes: ByteArray, val image: RgbImage, val hash: String)
}
