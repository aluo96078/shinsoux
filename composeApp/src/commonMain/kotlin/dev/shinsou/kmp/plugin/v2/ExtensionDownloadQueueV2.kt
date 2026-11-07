package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.plugin.sourceFailureMarker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** One extension (novel) chapter waiting for, or done with, an offline download. */
public data class ExtensionDownloadEntryV2(
    val id: String,
    val sourceKey: dev.shinsou.kmp.domain.model.SourceKey,
    val remotePublicationId: String,
    val remoteUnitId: String,
    val publicationTitle: String,
    val unitTitle: String,
    val state: DownloadState = DownloadState.QUEUED,
    val errorMessage: String? = null,
)

/** What survives a restart for one queued unit; the selection is reissued by the host on restore. */
@Serializable
public data class ExtensionDownloadRecordV2(
    val sourceKey: SourceKey,
    val remotePublicationId: String,
    val publicationTitle: String,
    val unit: RemoteUnitV2,
    val state: DownloadState,
    val errorMessage: String? = null,
    /** Stopped by the queue itself after repeated failures, not by the user; retried on restart. */
    val autoPaused: Boolean = false,
)

/** Durable storage of the queue, so closing or killing the app does not lose pending downloads. */
public interface ExtensionDownloadQueueStoreV2 {
    public suspend fun load(): List<ExtensionDownloadRecordV2>
    public suspend fun save(records: List<ExtensionDownloadRecordV2>)
}

/**
 * App-scoped download queue for extension v2 units. Downloads previously ran in the detail
 * screen's coroutine scope, so leaving the title cancelled them and nothing reached the
 * Downloads screen. Units run one at a time to stay gentle on the source.
 */
public class ExtensionDownloadQueueV2(
    private val materialize: suspend (ExtensionUnitSelectionV2) -> Unit,
    /** True when the unit's content is already stored; such units complete without a fetch. */
    private val isStored: (ExtensionUnitSelectionV2) -> Boolean = { false },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val store: ExtensionDownloadQueueStoreV2? = null,
    /** False holds the worker until [open], e.g. while the app is still loading its sources. */
    startOpen: Boolean = true,
) {
    private val gate = CompletableDeferred<Unit>().apply { if (startOpen) complete(Unit) }

    /** Lets the worker start; queued work restored or enqueued before this waits for it. */
    public fun open() {
        gate.complete(Unit)
    }

    private val _entries = MutableStateFlow<List<ExtensionDownloadEntryV2>>(emptyList())
    public val entries: StateFlow<List<ExtensionDownloadEntryV2>> = _entries.asStateFlow()

    private val lock = Mutex()
    private val saving = Mutex()
    private val selections = mutableMapOf<String, ExtensionUnitSelectionV2>()
    private var worker: Job? = null
    private var activeId: String? = null
    private var activeJob: Job? = null
    private var paused = false
    private var persisting = false
    private var consecutiveFailures = 0
    private val failureStreak = mutableListOf<String>()
    private var pausedAfterFailures = false
    private val unissued = mutableListOf<ExtensionDownloadRecordV2>()
    private val saveRequests = MutableStateFlow(0)

    /**
     * Brings back the queue saved by an earlier run and resumes it. [issue] turns a saved record
     * into a live selection (or null when its source is gone). Saving starts only after this, so
     * an empty queue at startup never overwrites the saved one.
     */
    public suspend fun restore(issue: (ExtensionDownloadRecordV2) -> ExtensionUnitSelectionV2?) {
        val loaded = runCatching { store?.load().orEmpty() }
        val records = loaded.getOrDefault(emptyList())
        lock.withLock {
            records.forEach { record ->
                val selection = runCatching { issue(record) }.getOrNull()
                if (selection == null) {
                    // Its source is not available right now; keep the record for a later run.
                    if (unissued.none { it.key() == record.key() }) unissued += record
                    return@forEach
                }
                unissued.removeAll { it.key() == record.key() }
                val id = entryId(selection)
                if (_entries.value.any { it.id == id }) return@forEach
                selections[id] = selection
                _entries.update {
                    it + ExtensionDownloadEntryV2(
                        id = id,
                        sourceKey = record.sourceKey,
                        remotePublicationId = record.remotePublicationId,
                        remoteUnitId = record.unit.remoteId,
                        publicationTitle = record.publicationTitle,
                        unitTitle = record.unit.title,
                        // Interrupted downloads fetch again; finished ones are re-checked against
                        // storage by the worker (no fetch when the content is still there).
                        // Opening the app again is the same signal as returning to it: units
                        // the queue stopped on its own (network failures) are retried.
                        state = when {
                            record.autoPaused -> DownloadState.QUEUED
                            record.state == DownloadState.DOWNLOADING || record.state == DownloadState.DOWNLOADED ->
                                DownloadState.QUEUED
                            else -> record.state
                        },
                        errorMessage = record.errorMessage.takeUnless { record.autoPaused },
                    )
                }
            }
            startWorkerLocked()
            // A failed read must not be followed by a save, or the saved queue would be replaced
            // by this run's (empty) one. Downloads still work; they just are not saved this run.
            if (store != null && !persisting && loaded.isSuccess) {
                persisting = true
                scope.launch { persist(store) }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun persist(store: ExtensionDownloadQueueStoreV2) {
        // Debounced: a chapter finishing every second must not rewrite the file every second.
        combine(_entries, saveRequests) { list, _ -> list }.debounce(PERSIST_DEBOUNCE_MS).collect {
            runCatching { saveNow(store) }
        }
    }

    /**
     * Saves are serialized and each one captures the queue inside that order, so a slow older
     * save can never land after (and overwrite) a newer one.
     */
    private suspend fun saveNow(store: ExtensionDownloadQueueStoreV2) = saving.withLock {
        store.save(lock.withLock { recordsLocked(_entries.value) })
    }

    /**
     * Saves the queue now instead of after the debounce; throws if it could not be saved. Used
     * where a caller must know the current state is on disk (a pause the system made).
     */
    public suspend fun flush() {
        val target = store ?: return
        // Nothing was restored (or the read failed): saving now would replace the saved queue,
        // and reporting success would tell the caller a state is saved that is not.
        check(lock.withLock { persisting }) { "The download queue was not restored" }
        saveNow(target)
    }

    private fun recordsLocked(list: List<ExtensionDownloadEntryV2>): List<ExtensionDownloadRecordV2> =
        list.mapNotNull { entry ->
            val selection = selections[entry.id] ?: return@mapNotNull null
            ExtensionDownloadRecordV2(
                sourceKey = selection.sourceKey,
                remotePublicationId = selection.remotePublicationId,
                publicationTitle = selection.publicationTitle,
                unit = selection.unit,
                state = entry.state,
                errorMessage = entry.errorMessage,
                autoPaused = pausedAfterFailures &&
                    (entry.id in failureStreak || entry.state == DownloadState.PAUSED),
            )
        }.let { live ->
            // Saved records whose unit is live again are represented by the live entry.
            val liveKeys = live.mapTo(mutableSetOf()) { it.key() }
            live + unissued.filterNot { it.key() in liveKeys }.distinctBy { it.key() }
        }

    public suspend fun enqueue(units: List<ExtensionUnitSelectionV2>) {
        lock.withLock {
            units.forEach { selection ->
                val id = entryId(selection)
                selections[id] = selection
                val existing = _entries.value.firstOrNull { it.id == id }
                when {
                    existing == null -> _entries.update {
                        it + ExtensionDownloadEntryV2(
                            id = id,
                            sourceKey = selection.sourceKey,
                            remotePublicationId = selection.remotePublicationId,
                            remoteUnitId = selection.unit.remoteId,
                            publicationTitle = selection.publicationTitle,
                            unitTitle = selection.unit.title,
                        )
                    }
                    // DOWNLOADED rows are re-checked against storage when the worker reaches them.
                    existing.state == DownloadState.ERROR || existing.state == DownloadState.DOWNLOADED ->
                        setState(id, DownloadState.QUEUED)
                }
            }
            startWorkerLocked()
        }
    }

    public suspend fun retry(id: String) {
        lock.withLock {
            if (id !in selections) return
            setState(id, DownloadState.QUEUED)
            startWorkerLocked()
        }
    }

    public suspend fun remove(id: String) {
        lock.withLock {
            if (activeId == id) activeJob?.cancel()
            selections.remove(id)
            _entries.update { list -> list.filterNot { it.id == id } }
        }
    }

    /** Drops every queued or finished row of one title, stopping its active download. */
    public suspend fun removePublication(sourceKey: SourceKey, remotePublicationId: String) {
        lock.withLock {
            val ids = _entries.value
                .filter { it.sourceKey.canonicalId == sourceKey.canonicalId && it.remotePublicationId == remotePublicationId }
                .mapTo(mutableSetOf()) { it.id }
            if (activeId in ids) activeJob?.cancel()
            ids.forEach(selections::remove)
            failureStreak.removeAll { it in ids }
            val droppedSaved = unissued.removeAll {
                it.sourceKey.canonicalId == sourceKey.canonicalId && it.remotePublicationId == remotePublicationId
            }
            // Saved-only records are not in [entries]; ask for a save explicitly.
            if (droppedSaved) saveRequests.update { it + 1 }
            _entries.update { list -> list.filterNot { it.id in ids } }
        }
    }

    public suspend fun clearCompleted() {
        lock.withLock {
            val done = _entries.value.filter { it.state == DownloadState.DOWNLOADED }.map { it.id }.toSet()
            done.forEach(selections::remove)
            _entries.update { list -> list.filterNot { it.id in done } }
        }
    }

    /**
     * Picks the queue up again after it paused itself on repeated failures (usually no network),
     * retrying the units that failed in that streak. Called when the app returns to the screen.
     */
    public suspend fun resumeAfterFailures() {
        lock.withLock {
            if (!pausedAfterFailures) return
            pausedAfterFailures = false
            failureStreak.forEach { id ->
                if (_entries.value.any { it.id == id && it.state == DownloadState.ERROR }) setState(id, DownloadState.QUEUED)
            }
            failureStreak.clear()
            consecutiveFailures = 0
            paused = false
            _entries.update { list ->
                list.map { if (it.state == DownloadState.PAUSED) it.copy(state = DownloadState.QUEUED) else it }
            }
            startWorkerLocked()
        }
    }

    public suspend fun setPaused(value: Boolean) {
        lock.withLock {
            // The user's choice replaces an automatic pause.
            pausedAfterFailures = false
            failureStreak.clear()
            consecutiveFailures = 0
            paused = value
            if (value) {
                activeJob?.cancel()
                _entries.update { list ->
                    list.map {
                        if (it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING) {
                            it.copy(state = DownloadState.PAUSED)
                        } else it
                    }
                }
            } else {
                _entries.update { list ->
                    list.map { if (it.state == DownloadState.PAUSED) it.copy(state = DownloadState.QUEUED) else it }
                }
                startWorkerLocked()
            }
        }
    }

    /**
     * A pause the system asked for: exactly [ids] (saved by the caller beforehand) stop. The
     * queue-wide pause flag is left alone, so a pause the user chose is never lifted by recovery.
     */
    public suspend fun pauseForSystem(ids: Set<String>): Unit = lock.withLock {
        _entries.update { list ->
            list.map {
                if (it.id in ids && (it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING)) {
                    it.copy(state = DownloadState.PAUSED)
                } else it
            }
        }
        if (activeId in ids) activeJob?.cancel()
    }

    /** Undoes [pauseForSystem] for [ids] (null: every paused entry); entries the user paused stay. */
    public suspend fun resumeAfterSystem(ids: Set<String>?): Unit = lock.withLock {
        _entries.update { list ->
            list.map {
                if (it.state == DownloadState.PAUSED && (ids == null || it.id in ids)) it.copy(state = DownloadState.QUEUED) else it
            }
        }
        startWorkerLocked()
    }

    private fun startWorkerLocked() {
        if (paused || worker?.isActive == true) return
        worker = scope.launch { drain() }
    }

    private suspend fun drain() {
        gate.await()
        while (true) {
            val next = lock.withLock {
                // Retire under the lock so a concurrent enqueue/resume starts a fresh worker
                // instead of seeing this one still active as it exits.
                if (paused) {
                    worker = null
                    return
                }
                val entry = _entries.value.firstOrNull { it.state == DownloadState.QUEUED } ?: run {
                    worker = null
                    return
                }
                val selection = selections[entry.id]
                when {
                    selection == null -> {
                        setState(entry.id, DownloadState.ERROR, "Download request expired")
                        null
                    }
                    // Re-downloading a title must not fetch chapters that are already offline.
                    runCatching { isStored(selection) }.getOrDefault(false) -> {
                        setState(entry.id, DownloadState.DOWNLOADED)
                        null
                    }
                    else -> {
                        setState(entry.id, DownloadState.DOWNLOADING)
                        val task = scope.async { materialize(selection) }
                        activeId = entry.id
                        activeJob = task
                        entry.id to task
                    }
                }
            } ?: continue
            val (id, task) = next
            val failure = try {
                task.await()
                null
            } catch (cancelled: CancellationException) {
                // Only a cancelled download task is a pause/remove; anything else stops the worker.
                if (!task.isCancelled) throw cancelled
                cancelled
            } catch (error: Throwable) {
                error
            }
            lock.withLock {
                activeId = null
                activeJob = null
                if (_entries.value.none { it.id == id }) return@withLock
                when (failure) {
                    null -> {
                        setState(id, DownloadState.DOWNLOADED)
                        consecutiveFailures = 0
                        failureStreak.clear()
                    }
                    // The commit may have landed just before the cancel arrived; don't fetch it again.
                    is CancellationException -> setState(
                        id,
                        when {
                            selections[id]?.let { runCatching { isStored(it) }.getOrDefault(false) } == true ->
                                DownloadState.DOWNLOADED
                            // Paused for the whole queue, or this entry alone (a system pause).
                            paused || _entries.value.firstOrNull { it.id == id }?.state == DownloadState.PAUSED ->
                                DownloadState.PAUSED
                            else -> DownloadState.QUEUED
                        },
                    )
                    else -> {
                        setState(id, DownloadState.ERROR, failure.downloadFailureMessage())
                        consecutiveFailures++
                        failureStreak += id
                        // Offline every unit fails in turn; marking hundreds of rows failed churns
                        // the UI and wastes the source. Stop and wait for the app to come back.
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            pausedAfterFailures = true
                            paused = true
                            _entries.update { list ->
                                list.map { if (it.state == DownloadState.QUEUED) it.copy(state = DownloadState.PAUSED) else it }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setState(id: String, state: DownloadState, error: String? = null) {
        _entries.update { list ->
            list.map { if (it.id == id) it.copy(state = state, errorMessage = error) else it }
        }
    }

    public companion object {
        private const val PERSIST_DEBOUNCE_MS = 500L
        private const val MAX_CONSECUTIVE_FAILURES = 3

        public fun entryId(selection: ExtensionUnitSelectionV2): String =
            "${selection.sourceKey.canonicalId}|${selection.remotePublicationId}|${selection.unit.remoteId}"
    }
}

private fun ExtensionDownloadRecordV2.key() = Triple(sourceKey.canonicalId, remotePublicationId, unit.remoteId)

private fun Throwable.downloadFailureMessage(): String =
    sourceFailureMarker() ?: message ?: "Download failed"

/** Keeps the novel download queue in one JSON file; a damaged file is set aside, not merged. */
public class ExtensionDownloadQueueFileStoreV2(
    private val fileSystem: dev.shinsou.kmp.files.AppFileSystem,
) : ExtensionDownloadQueueStoreV2 {
    override suspend fun load(): List<ExtensionDownloadRecordV2> {
        val bytes = fileSystem.read(PATH) ?: return emptyList()
        return try {
            json.decodeFromString<List<ExtensionDownloadRecordV2>>(bytes.decodeToString())
        } catch (_: kotlinx.serialization.SerializationException) {
            // Writes are atomic, so this is a real corruption, not a half-written file. Keep a copy
            // for inspection and start over instead of blocking the queue from saving for good.
            fileSystem.writeAtomically("$PATH.corrupt", bytes)
            emptyList()
        } catch (_: IllegalArgumentException) {
            fileSystem.writeAtomically("$PATH.corrupt", bytes)
            emptyList()
        }
    }

    override suspend fun save(records: List<ExtensionDownloadRecordV2>) {
        fileSystem.writeAtomically(
            PATH,
            json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ExtensionDownloadRecordV2.serializer()), records)
                .encodeToByteArray(),
        )
    }

    private companion object {
        const val PATH = "extension-v2/download-queue.json"
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
