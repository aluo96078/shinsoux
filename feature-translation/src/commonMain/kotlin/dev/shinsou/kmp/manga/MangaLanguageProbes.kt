package dev.shinsou.kmp.manga

import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.translation.ChineseScript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val ENOUGH_LETTERS = 40

/** Characters written only one way that settle a work's script; fewer still decide it after the last page read. */
private const val ENOUGH_SCRIPT_CHARACTERS = 12
private const val MIN_SCRIPT_CHARACTERS = 3

/** The first pages a language probe reads, until enough text is read. */
private const val PROBE_PAGES = 3

/**
 * The language and script of a work its source does not say, guessed from the text on its first
 * pages (0007 C5) and remembered per work while the app runs. The pages are read as the chapter
 * would read them and kept in the page store, so the chapter that starts after does not read them again.
 */
internal class MangaLanguageProbes(
    private val platform: MangaPlatform?,
    private val models: MangaModelStore,
    private val pipelines: MangaPipelines,
    private val pages: MangaPageStore,
    private val work: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * The guesses, per work. The panel (in the UI's scope) and the prefetch (on the default
     * dispatcher) ask at the same time, so the maps are only touched under [guessLock]; the probe
     * itself runs outside it, and two that race just both write the same guess.
     */
    private val guessLock = Mutex()
    private val japaneseGuesses = mutableMapOf<String, Boolean>()
    private val scriptGuesses = mutableMapOf<String, String>()

    /** See [MangaChapterTranslator.readsJapanese]. */
    suspend fun readsJapanese(workKey: String, pageCount: Int, loadPage: suspend (Int) -> ByteArray?): Boolean? =
        guessLock.withLock { japaneseGuesses[workKey] } ?: probeJapanese(workKey, pageCount, loadPage)

    private suspend fun probeJapanese(workKey: String, pageCount: Int, loadPage: suspend (Int) -> ByteArray?): Boolean? {
        val codec = probeCodec(pageCount) ?: return null
        val texts = pipelines.holding(work) { pipeline ->
            Probe(pipeline, codec, pageCount, loadPage, "ja").texts { texts -> letters(texts) >= ENOUGH_LETTERS }
        }
        currentCoroutineContext().ensureActive()
        return guessedJapanese(workKey, texts)
    }

    /** [looksJapanese] for [texts], remembered for the work when it is the work's answer. */
    private suspend fun guessedJapanese(workKey: String, texts: List<String>): Boolean? {
        val guess = looksJapanese(texts) ?: return null
        // "Not Japanese" from a little text (a cover, a colophon) is this chapter's answer, not the work's
        val settled = guess || letters(texts) >= ENOUGH_LETTERS
        if (settled) guessLock.withLock { japaneseGuesses[workKey] = guess }
        return guess
    }

    /** See [MangaChapterTranslator.readsChineseScript]. */
    suspend fun readsChineseScript(workKey: String, pageCount: Int, loadPage: suspend (Int) -> ByteArray?): String? =
        guessLock.withLock { scriptGuesses[workKey] } ?: probeChineseScript(workKey, pageCount, loadPage)

    private suspend fun probeChineseScript(workKey: String, pageCount: Int, loadPage: suspend (Int) -> ByteArray?): String? {
        val codec = probeCodec(pageCount) ?: return null
        val texts = pipelines.holding(work) { pipeline ->
            if (!pipeline.reads(ChineseScript.SIMPLIFIED)) {
                null
            } else {
                Probe(pipeline, codec, pageCount, loadPage, ChineseScript.SIMPLIFIED).texts { texts ->
                    ChineseScript.detect(texts.joinToString("\n"), minimum = ENOUGH_SCRIPT_CHARACTERS) != null
                }
            }
        }
        return texts?.let { guessedScript(workKey, it) }
    }

    private suspend fun guessedScript(workKey: String, texts: List<String>): String? {
        currentCoroutineContext().ensureActive()
        return ChineseScript.detect(texts.joinToString("\n"), minimum = MIN_SCRIPT_CHARACTERS)
            ?.also { guess -> guessLock.withLock { scriptGuesses[workKey] = guess } }
    }

    /** The codec pages are decoded with, when a chapter of [pageCount] pages can be probed at all. */
    private fun probeCodec(pageCount: Int): PageCodec? = platform?.codec?.takeIf { models.paths() != null && pageCount != 0 }

    private fun letters(texts: List<String>): Int = texts.sumOf { t -> t.count { it.isLetter() } }

    /** A probe of one chapter of [pageCount] pages in [language], with the [pipeline] it holds. */
    private inner class Probe(
        private val pipeline: MangaPipeline,
        private val codec: PageCodec,
        private val pageCount: Int,
        private val loadPage: suspend (Int) -> ByteArray?,
        private val language: String,
    ) {
        /** The text of the first pages, read until [enough]. */
        suspend fun texts(enough: (List<String>) -> Boolean): List<String> {
            val texts = mutableListOf<String>()
            for (i in 0 until minOf(pageCount, PROBE_PAGES)) {
                val bytes = page(i)
                if (bytes != null) texts += read(bytes, i)?.regions?.map { it.text }.orEmpty()
                // a page that did not load adds nothing, so it cannot make the text enough
                if (bytes != null && enough(texts)) break
            }
            return texts
        }

        /** A page for the probe; null when it cannot be loaded. A probe cancelled stops, not guesses from fewer pages. */
        private suspend fun page(i: Int): ByteArray? =
            try {
                loadPage(i)
            } catch (e: CancellationException) {
                throw e
            } catch (logged: Exception) {
                Log.warn("Manga", logged) { "Loading a page for a language probe failed" }
                null
            }

        /**
         * Reads page [i] as the chapter itself would (the detector it is assigned, the reader for
         * [language]) and keeps the result in the page store. Null when undecodable.
         */
        private suspend fun read(bytes: ByteArray, i: Int): PageAnalysis? = withContext(work) {
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
    }
}

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
