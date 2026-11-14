package dev.shinsou.kmp.translation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Clock

/**
 * Translates chapters through a [TranslationProvider], reusing the translation memory and
 * applying the work's glossary. The same code serves the offline engine and cloud providers.
 */
public class TranslationService(
    private val memory: TranslationMemoryStore,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Segments sent to the provider per call: bounds memory and gives progress and cancellation points. */
    private val batchSize: Int = 48,
) {
    /** What the memory already holds for a chapter, without translating anything; null when the pair is unsupported. */
    public fun stored(request: ChapterTranslationRequest, provider: TranslationProvider): ChapterTranslation? {
        val route = provider.route(request.from, request.to) ?: return null
        val plan = Plan(request, route, provider)
        plan.fillFromMemory()
        // A chapter read back whole is tied to its lines here too: one translated before lines
        // were tracked becomes removable once it is opened again.
        // It is also given its chapter record: a chapter made up of lines translated elsewhere
        // would otherwise never be listed, and its lines could not be removed with the work.
        if (plan.translatable > 0 && plan.finished == plan.translatable) plan.record()
        return plan.result(translated = 0)
    }

    /**
     * Translates the lines of [request] that the memory does not hold yet. [onProgress] reports
     * finished and total translatable lines. Cancellation and failures keep what was translated.
     * @throws TranslationException when the provider cannot translate the pair or fails.
     */
    public suspend fun translate(
        request: ChapterTranslationRequest,
        provider: TranslationProvider,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ChapterTranslation {
        val route = provider.route(request.from, request.to)
            ?: throw TranslationException(TranslationFailure.UNSUPPORTED_PAIR, "${provider.id} cannot translate ${request.from} to ${request.to}")
        val plan = Plan(request, route, provider)
        plan.fillFromMemory()
        val total = plan.translatable
        var translated = 0
        onProgress(plan.finished, total)
        try {
            // A group goes through one hop completely before the next: the offline engine holds
            // one model at a time, and switching for every batch costs more than the batches.
            for (group in plan.pending().chunked(batchSize * GROUP_BATCHES)) {
                val before = plan.finished
                var lineHops = 0
                translated += plan.run(group) { lines ->
                    lineHops += lines
                    // Whole lines only: a line counts once every hop of it is done.
                    onProgress(minOf(before + lineHops / route.size, total), total)
                }
                onProgress(plan.finished, total)
            }
        } finally {
            plan.record()
        }
        return plan.result(translated)
    }

    private inner class Plan(
        private val request: ChapterTranslationRequest,
        private val route: List<TranslationHop>,
        private val provider: TranslationProvider,
    ) {
        val sources: List<String> = TranslationText.segments(request.text)
        private val glossary = memory.glossary(request.workKey, request.to).let { if (it.isEmpty()) TranslationGlossary.EMPTY else TranslationGlossary(it) }

        /**
         * What is sent to the first hop: the line with glossary terms replaced by their codes,
         * or the line itself once the engine is known to lose the codes in it.
         */
        val inputs: MutableList<String> = sources.mapTo(ArrayList()) {
            if (it.isBlank() || request.keep(it)) "" else glossary.protect(it.trim())
        }
        val outputs: Array<String?> = arrayOfNulls(sources.size)
        val translatable: Int = inputs.count { it.isNotEmpty() }
        val finished: Int get() = outputs.count { it != null }
        private var reused = 0

        /** Every stored line this chapter's translation reads, at every hop. */
        private val usedLines = HashSet<MemoryKey>()

        fun recordLines() {
            memory.saveChapterSegments(request.workKey, request.chapterKey, request.to, provider.id, usedLines)
        }

        fun pending(): List<Int> = inputs.indices.filter { inputs[it].isNotEmpty() && outputs[it] == null }

        private fun protected(index: Int): Boolean = inputs[index] != sources[index].trim()

        /**
         * Memory identity of a line at any hop. Lines carrying glossary codes have their own
         * namespace: source text that happens to read "N1X..." must not share an entry (or a
         * "codes lost" mark) with a line where the glossary wrote that code.
         */
        private fun key(text: String, withCodes: Boolean): String =
            if (withCodes) TranslationText.hash(GLOSSARY_NAMESPACE + glossarySalt + "\n" + text.trim()) else TranslationText.hash(text)

        /** Ties cached lines to this glossary: one restored from a backup numbers its terms differently. */
        private val glossarySalt: String by lazy { memory.glossarySalt(request.workKey, request.to) }

        /** Lines whose every hop is in memory need no provider at all. */
        fun fillFromMemory() {
            reused = runBlockingMemoryOnly(pending())
        }

        private fun runBlockingMemoryOnly(indices: List<Int>): Int {
            var filled = 0
            var round = indices
            // Second round: lines whose codes the engine lost are looked up as plain text.
            repeat(2) {
                val results = lookupRoute(round)
                val retry = ArrayList<Int>()
                round.forEachIndexed { position, index ->
                    when (val result = results[position]) {
                        null -> { inputs[index] = sources[index].trim(); retry += index }
                        "" -> Unit
                        else -> { outputs[index] = result; filled++ }
                    }
                }
                round = retry
            }
            return filled
        }

        /** Memory only: the final text, "" when a hop is missing, null when the line must go unprotected. */
        private fun lookupRoute(indices: List<Int>): List<String?> {
            val checked = indices.map(::protected)
            var texts: List<String?> = indices.map { inputs[it] }
            for (hop in route) {
                val hashes = texts.mapIndexed { position, text -> text?.takeIf(String::isNotEmpty)?.let { key(it, checked[position]) } }
                if (hashes.all { it == null }) break
                val known = memory.lookup(hashes.filterNotNull(), hop.from, hop.to, hop.engine, now(), request.exportable)
                texts = texts.mapIndexed { position, text ->
                    if (text.isNullOrEmpty()) text else when (val hit = known[hashes[position]]) {
                        null -> ""
                        else -> {
                            usedLines += MemoryKey(hashes[position]!!, hop.from, hop.to, hop.engine)
                            if (hit == CODES_LOST) null else hit
                        }
                    }
                }
            }
            return texts
        }

        /** Translates [batch] through the provider; returns how many lines got a translation. */
        suspend fun run(batch: List<Int>, onLineHops: (Int) -> Unit): Int {
            var done = 0
            var round = batch
            repeat(2) {
                if (round.isEmpty()) return done
                val results = translateRoute(round, onLineHops)
                val retry = ArrayList<Int>()
                round.forEachIndexed { position, index ->
                    when (val result = results[position]) {
                        null -> { inputs[index] = sources[index].trim(); retry += index }
                        "" -> Unit
                        else -> { outputs[index] = result; done++ }
                    }
                }
                round = retry
            }
            return done
        }

        /**
         * The final text per line; "" when the engine gave nothing; null when it lost a glossary
         * code, which is remembered so the line is not tried with codes again.
         */
        private suspend fun translateRoute(indices: List<Int>, onLineHops: (Int) -> Unit): List<String?> {
            val checked = indices.map(::protected)
            var texts: List<String?> = indices.map { inputs[it] }
            for (hop in route) {
                val hashes = texts.mapIndexed { position, text -> text?.takeIf(String::isNotEmpty)?.let { key(it, checked[position]) } }
                val known = memory.lookup(hashes.filterNotNull(), hop.from, hop.to, hop.engine, now(), request.exportable)
                // Each distinct line once: a repeated line must not be paid for twice.
                val missing = LinkedHashMap<String, String>()
                texts.forEachIndexed { position, text -> hashes[position]?.let { hash -> if (hash !in known) missing.getOrPut(hash) { text!! } } }
                val produced = HashMap<String, String>()
                val mustKeepCodes = HashSet<String>()
                hashes.forEachIndexed { position, hash -> if (hash != null && checked[position]) mustKeepCodes += hash }
                val occurrences = hashes.filterNotNull().groupingBy { it }.eachCount()
                onLineHops(hashes.count { it != null && it in known })
                for (chunk in missing.entries.chunked(provider.linesPerCall ?: batchSize)) {
                    currentCoroutineContext().ensureActive()
                    fun keep(answers: List<String>) {
                        val fresh = HashMap<MemoryKey, String>()
                        chunk.take(answers.size).forEachIndexed { position, (hash, input) ->
                            // An empty answer is not a translation: the line stays untranslated and is tried again later.
                            val answer = answers[position].trim().takeIf(String::isNotEmpty) ?: return@forEachIndexed
                            val stored = if (hash in mustKeepCodes && !TranslationGlossary.codesPreserved(input, answer)) CODES_LOST else answer
                            produced[hash] = stored
                            fresh[MemoryKey(hash, hop.from, hop.to, hop.engine)] = stored
                        }
                        memory.store(fresh, provider.paid, now(), request.exportable)
                    }
                    val answers = try {
                        provider.translate(hop, chunk.map { it.value })
                    } catch (error: TranslationException) {
                        // The provider got part of the way: what it returned is stored before the failure goes on.
                        if (error.completed.isNotEmpty() && error.completed.size <= chunk.size) keep(error.completed)
                        throw error
                    }
                    if (answers.size != chunk.size) {
                        throw TranslationException(TranslationFailure.ENGINE_FAILED, "${hop.engine} returned ${answers.size} lines for ${chunk.size}")
                    }
                    keep(answers)
                    onLineHops(chunk.sumOf { occurrences[it.key] ?: 0 })
                }
                texts = texts.mapIndexed { position, _ ->
                    val hash = hashes[position] ?: return@mapIndexed texts[position]
                    when (val hit = known[hash] ?: produced[hash]) {
                        null -> ""
                        else -> {
                            usedLines += MemoryKey(hash, hop.from, hop.to, hop.engine)
                            if (hit == CODES_LOST) null else hit
                        }
                    }
                }
            }
            return texts
        }

        fun record() {
            request.labels?.let { memory.saveLabels(request.workKey, request.chapterKey, it) }
            recordLines()
            memory.saveChapter(
                ChapterRecord(
                    workKey = request.workKey,
                    chapterKey = request.chapterKey,
                    to = request.to,
                    provider = provider.id,
                    sourceHash = TranslationText.hash(request.text),
                    segmentCount = translatable,
                    complete = finished == translatable,
                    paid = provider.paid,
                    updatedAt = now(),
                ),
            )
        }

        fun result(translated: Int): ChapterTranslation = ChapterTranslation(
            segments = sources.mapIndexed { index, source ->
                TranslatedSegment(
                    source = source,
                    kept = source.isNotBlank() && inputs[index].isEmpty(),
                    // Codes are only swapped back in lines where the glossary put them.
                    translation = outputs[index]?.let { output ->
                        TranslationText.localizePunctuation(if (protected(index)) glossary.restore(output) else output, request.to)
                    },
                )
            },
            reusedSegments = reused,
            translatedSegments = translated,
        )
    }

    private companion object {
        /** Batches translated through one hop before moving to the next hop. */
        const val GROUP_BATCHES = 8

        const val GLOSSARY_NAMESPACE = "\u0001glossary\n"

        /**
         * Stored in place of a translation when the engine dropped or repeated a glossary code:
         * the line is then translated without codes, and not tried with them again.
         */
        const val CODES_LOST = "\u0001codes-lost"
    }
}
