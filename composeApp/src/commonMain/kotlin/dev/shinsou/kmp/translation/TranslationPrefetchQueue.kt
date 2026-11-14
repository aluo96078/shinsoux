package dev.shinsou.kmp.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A chapter as the translation needs it; what a [TranslationPrefetchJob] loads. */
public class PrefetchChapter(
    public val workKey: String,
    public val chapterKey: String,
    public val text: String,
    /** Whether the content's grant allows export; see [ChapterTranslationRequest.exportable]. */
    public val exportable: Boolean,
    public val labels: TranslationLabels?,
    public val keep: (String) -> Boolean = { false },
)

/** The languages the user chose when queueing. */
public data class PrefetchLanguages(
    val to: String,
    /** The original's language; null detects it from each chapter. */
    val from: String? = null,
)

/** One chapter to translate ahead of reading. */
public class TranslationPrefetchJob(
    /** Stable for the chapter: asking twice does not queue it twice. */
    public val id: String,
    /** The work it belongs to, for the work's own progress and for cancelling it. */
    public val group: String,
    public val title: String,
    /** What the user chose when queueing; null follows the work's settings, or the reader's first guess. */
    public val languages: PrefetchLanguages? = null,
    /** The chapter's own identity within [group], as the screen that queued it knows it. */
    public val unit: String = id,
    /**
     * Fetches the chapter (downloading it when it is not stored yet). Throws
     * [PrefetchNotTranslatable] for a chapter with nothing to translate.
     */
    public val load: suspend () -> PrefetchChapter,
)

/** The chapter is not text, or its grant does not allow its translation to be kept. */
public class PrefetchNotTranslatable(message: String) : Exception(message)

public enum class PrefetchState { QUEUED, RUNNING, DONE, FAILED }

public data class PrefetchEntry(
    val id: String,
    val group: String,
    val title: String,
    val state: PrefetchState,
    val unit: String = id,
    /** The chapter was fetched (and so is stored on the device), whatever became of its translation. */
    val fetched: Boolean = false,
    /** Lines done and in all, while [state] is RUNNING. */
    val done: Int = 0,
    val total: Int = 0,
    /** Why it failed; null with FAILED means the chapter could not be fetched or is not text. */
    val failure: TranslationFailure? = null,
)

/**
 * Translates chapters ahead of reading, the way chapters are downloaded ahead of reading: an
 * app-wide queue that goes on after the screen that filled it is left. One chapter at a time,
 * through the same service, memory and daily limit as the reader, so a chapter translated here
 * opens translated and nothing is paid for twice.
 *
 * A failure the next chapter would only repeat stops the chapters it would repeat for: no key
 * or the day's limit stops everything, a missing model or an unsupported pair stops that work.
 * The queue lives for the app's run.
 */
public class TranslationPrefetchQueue(
    private val translation: ReaderTranslation,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** The language to translate into for a work the reader has not been set up for yet. */
    private val defaultTargetLanguage: () -> String = { "zh-Hant" },
) {
    private val _entries = MutableStateFlow<List<PrefetchEntry>>(emptyList())
    public val entries: StateFlow<List<PrefetchEntry>> = _entries.asStateFlow()

    private val lock = Mutex()
    private val jobs = LinkedHashMap<String, TranslationPrefetchJob>()
    private var worker: Job? = null
    private var active: Pair<String, Job>? = null

    /** Adds [added] to the queue; chapters already queued, running or done are left as they are. */
    public suspend fun enqueue(added: List<TranslationPrefetchJob>): Int = lock.withLock {
        val current = _entries.value.associateBy { it.id }
        val fresh = added.distinctBy { it.id }.filter { job ->
            val state = current[job.id]?.state
            state == null || state == PrefetchState.FAILED
        }
        if (fresh.isEmpty()) return@withLock 0
        fresh.forEach { jobs[it.id] = it }
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        _entries.update { list ->
            list.filterNot { it.id in freshIds } +
                fresh.map { PrefetchEntry(it.id, it.group, it.title, PrefetchState.QUEUED, unit = it.unit) }
        }
        if (worker?.isActive != true) worker = scope.launch { run() }
        fresh.size
    }

    /** Stops and forgets the waiting and running chapters of [group]; what is already translated stays. */
    public suspend fun cancel(group: String): Unit = lock.withLock {
        val gone = _entries.value.filter { it.group == group && it.state != PrefetchState.DONE }.mapTo(HashSet()) { it.id }
        gone.forEach { jobs.remove(it) }
        active?.takeIf { it.first in gone }?.second?.cancel()
        _entries.update { list -> list.filterNot { it.id in gone } }
    }

    /** Forgets finished and failed chapters of [group], leaving what is still to do. */
    public suspend fun clearFinished(group: String): Unit = lock.withLock {
        _entries.update { list ->
            list.filterNot { it.group == group && (it.state == PrefetchState.DONE || it.state == PrefetchState.FAILED) }
        }
    }

    private class Outcome {
        var failed = false
        var failure: TranslationFailure? = null
    }

    private suspend fun run() {
        while (true) {
            val outcome = Outcome()
            // Chosen, started and registered under one lock: a cancel that arrives in between
            // either finds nothing running yet (and removes the entry) or finds this very job.
            val (job, work) = lock.withLock {
                val next = _entries.value.firstOrNull { it.state == PrefetchState.QUEUED }
                if (next == null) {
                    // Cleared under the lock, so an enqueue that follows starts a new worker.
                    worker = null
                    return
                }
                val job = jobs[next.id]
                if (job == null) {
                    _entries.update { list -> list.filterNot { it.id == next.id } }
                    return@withLock null
                }
                set(job.id) { it.copy(state = PrefetchState.RUNNING, done = 0, total = 0, failure = null) }
                val work = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        translate(job)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: TranslationException) {
                        outcome.failed = true
                        outcome.failure = error.failure
                    } catch (_: Exception) {
                        outcome.failed = true
                    }
                }
                active = job.id to work
                job to work
            } ?: continue
            work.start()
            work.join()
            lock.withLock {
                active = null
                jobs.remove(job.id)
                // Still listed means nobody cancelled it: a cancellation from inside (a source
                // that shut down mid-fetch) is a failure the user can retry, not a chapter left
                // "running" for ever.
                val failed = outcome.failed || work.isCancelled
                set(job.id) {
                    if (failed) it.copy(state = PrefetchState.FAILED, failure = outcome.failure) else it.copy(state = PrefetchState.DONE)
                }
                if (outcome.failed) failRestLocked(job.group, outcome.failure)
            }
        }
    }

    private suspend fun translate(job: TranslationPrefetchJob) {
        val chapter = job.load()
        set(job.id) { it.copy(fetched = true) }
        val settings = translation.settings(chapter.workKey)
        val chosen = job.languages
        val request = ChapterTranslationRequest(
            workKey = chapter.workKey,
            chapterKey = chapter.chapterKey,
            text = chapter.text,
            // What the user chose when queueing ("detect" meaning this chapter's own text); with no
            // choice, the work's settings, else as the reader would guess on first opening it.
            from = if (chosen != null) chosen.from ?: ReaderTranslation.detectLanguage(chapter.text)
            else settings?.from ?: ReaderTranslation.detectLanguage(chapter.text),
            to = chosen?.to ?: settings?.to ?: ReaderTranslation.targetLanguage(defaultTargetLanguage()),
            keep = chapter.keep,
            exportable = chapter.exportable,
            labels = chapter.labels,
        )
        // A work translated ahead opens translated: its settings are those it was queued with.
        if (settings == null || settings.from != request.from || settings.to != request.to || !settings.enabled) {
            translation.saveSettings(
                chapter.workKey,
                (settings ?: WorkTranslationSettings(enabled = true, from = request.from, to = request.to))
                    .copy(enabled = true, from = request.from, to = request.to),
            )
        }
        if (!translation.supports(request.from, request.to)) {
            throw TranslationException(TranslationFailure.UNSUPPORTED_PAIR, "No translation from ${request.from} to ${request.to}")
        }
        if (translation.stored(request)?.complete == true) return
        if (translation.activeProvider() == TranslationProviderKind.OFFLINE) {
            if (!translation.deviceSupported) {
                throw TranslationException(TranslationFailure.DEVICE_UNSUPPORTED, "This device cannot run the offline engine")
            }
            if (translation.missingModels(request.from, request.to).isNotEmpty()) {
                // Models are downloaded from the reader, where their size is shown first.
                throw TranslationException(TranslationFailure.MODEL_MISSING, "The models for ${request.from} to ${request.to} are not downloaded")
            }
        }
        val result = translation.translate(request) { done, total ->
            set(job.id) { it.copy(done = done, total = total) }
        }
        if (!result.complete) throw TranslationException(TranslationFailure.ENGINE_FAILED, "Some lines were left untranslated")
    }

    /**
     * The reason that stopped one chapter would stop others: they are told so without being
     * tried. A key, a limit or the device stops every work; a language pair or its models only
     * the work that asked for them.
     */
    private fun failRestLocked(group: String, failure: TranslationFailure?) {
        val everyWork = failure in STOPS_EVERY_WORK
        if (!everyWork && failure !in STOPS_ITS_WORK) return
        _entries.update { list ->
            list.map {
                if (it.state == PrefetchState.QUEUED && (everyWork || it.group == group)) {
                    it.copy(state = PrefetchState.FAILED, failure = failure)
                } else {
                    it
                }
            }
        }
        _entries.value.filter { it.state == PrefetchState.FAILED }.forEach { jobs.remove(it.id) }
    }

    private fun set(id: String, change: (PrefetchEntry) -> PrefetchEntry) {
        _entries.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private companion object {
        val STOPS_EVERY_WORK = setOf(
            TranslationFailure.INVALID_KEY,
            TranslationFailure.NOT_CONFIGURED,
            TranslationFailure.QUOTA_EXCEEDED,
            TranslationFailure.DAILY_LIMIT,
            TranslationFailure.DEVICE_UNSUPPORTED,
        )
        val STOPS_ITS_WORK = setOf(TranslationFailure.MODEL_MISSING, TranslationFailure.UNSUPPORTED_PAIR)
    }
}
