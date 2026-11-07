package dev.shinsou.kmp.download

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.plugin.v2.ExtensionDownloadEntryV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Progress of the manga and novel queues together, as a platform keep-alive shows it. */
public data class BackgroundDownloadStatus(val done: Int, val total: Int, val current: String?)

/** Null when no chapter is queued or downloading, so nothing needs the process kept alive. */
public fun backgroundDownloadStatus(
    snapshot: AppSnapshot,
    novels: List<ExtensionDownloadEntryV2>,
): BackgroundDownloadStatus? {
    val manga = snapshot.downloadQueue.filter { it.visibleInQueue }
    val pending = manga.count { it.state.pending() } + novels.count { it.state.pending() }
    if (pending == 0) return null
    val done = manga.count { it.state == DownloadState.DOWNLOADED } +
        novels.count { it.state == DownloadState.DOWNLOADED }
    val current = manga.firstOrNull { it.state == DownloadState.DOWNLOADING }?.let { item ->
        val title = snapshot.mangas.firstOrNull { it.id == item.mangaId }?.title
        val chapter = snapshot.chapters.firstOrNull { it.id == item.chapterId }?.name
        listOfNotNull(title, chapter).joinToString(" · ").ifEmpty { null }
    } ?: novels.firstOrNull { it.state == DownloadState.DOWNLOADING }
        ?.let { "${it.publicationTitle} · ${it.unitTitle}" }
    return BackgroundDownloadStatus(done = done, total = done + pending, current = current)
}

private fun DownloadState.pending() = this == DownloadState.QUEUED || this == DownloadState.DOWNLOADING

/**
 * Both download queues as one piece of background work, shared by every platform. A platform
 * keeps the process alive while [status] is non-null and calls [suspendForSystem] when the OS is
 * about to stop it. The entries that were waiting or running are paused and saved by id, so
 * [resumeAfterSystem] (on the next foreground or launch, even after the process was killed)
 * restarts exactly those and leaves entries the user paused alone.
 *
 * The pause callbacks stop exactly the given entries, which are on disk in the mark first; the
 * resume callbacks queue the given entries again, or every paused entry (null) after a damaged mark.
 */
public class BackgroundDownloadCoordinator(
    private val snapshot: StateFlow<AppSnapshot>,
    private val novels: StateFlow<List<ExtensionDownloadEntryV2>>,
    private val pauseManga: suspend (Set<String>) -> Unit,
    private val resumeManga: suspend (Set<String>?) -> Unit,
    private val pauseNovels: suspend (Set<String>) -> Unit,
    private val resumeNovels: suspend (Set<String>?) -> Unit,
    private val fileSystem: AppFileSystem?,
    scope: CoroutineScope,
) {
    private val transitions = Mutex()
    private var pausedBySystem: SystemPausedQueues? = null
    /** Counts system stops in this process; a resume prepared before one yields to it. */
    private var stops = 0L

    /** The novel queue loads asynchronously at startup; a stop before that is applied once it has. */
    private var novelsRestored = false
    private var stopBeforeNovelsRestored = false

    /** For [resumeAfterSystem]: a resume prepared at this point yields to any later stop. */
    public suspend fun stopCount(): Long = transitions.withLock { stops }

    /**
     * Computed straight from the queues, with no buffered value in between: right after
     * [resumeAfterSystem] its first value already reflects the resumed work.
     */
    public val liveStatus: Flow<BackgroundDownloadStatus?> =
        combine(snapshot, novels, ::backgroundDownloadStatus).distinctUntilChanged()

    /** Read straight from the queues now; for decisions that must not see a stale [status]. */
    public fun currentStatus(): BackgroundDownloadStatus? = backgroundDownloadStatus(snapshot.value, novels.value)

    public val status: StateFlow<BackgroundDownloadStatus?> =
        combine(snapshot, novels, ::backgroundDownloadStatus)
            .stateIn(scope, SharingStarted.Eagerly, backgroundDownloadStatus(snapshot.value, novels.value))

    /**
     * Pauses the queues that still have work because the system is about to stop the app.
     * [stillApplies] is checked once the transition lock is held, so a return to the foreground
     * that won the race is not undone.
     */
    public suspend fun suspendForSystem(stillApplies: () -> Boolean = { true }): Unit = transitions.withLock {
        if (!stillApplies()) return@withLock
        // Counted even with nothing pending right now (startup may not have restored the queues
        // yet): a startup or foreground resume prepared before this stop must still yield to it.
        stops++
        if (!novelsRestored) stopBeforeNovelsRestored = true
        pauseRunningLocked()
    }

    /**
     * Called once the novel queue has loaded and before its downloads may start. A system stop
     * that came while it was still loading could not pause it then, so it is applied now.
     */
    public suspend fun novelQueueRestored(): Unit = transitions.withLock {
        novelsRestored = true
        if (!stopBeforeNovelsRestored) return@withLock
        stopBeforeNovelsRestored = false
        pauseRunningLocked()
    }

    private suspend fun pauseRunningLocked() {
        val current = loadPausedBySystem() ?: return
        val manga = snapshot.value.downloadQueue.filter { it.visibleInQueue && it.state.pending() }.mapTo(mutableSetOf()) { it.id }
        val novel = novels.value.filter { it.state.pending() }.mapTo(mutableSetOf()) { it.id }
        if (manga.isEmpty() && novel.isEmpty()) return
        val marked = current.copy(manga = current.manga + manga, novels = current.novels + novel)
        // Saved before pausing, and only these entries are paused: whatever is paused is always
        // in the mark, even if the process dies right after. If it cannot be saved, nothing is
        // paused; a frozen download simply restarts on launch.
        if (!savePausedBySystem(marked)) return
        if (manga.isNotEmpty()) runCatching { pauseManga(manga) }.rethrowCancellation()
        if (novel.isNotEmpty()) runCatching { pauseNovels(novel) }.rethrowCancellation()
    }

    /**
     * Resumes only the queues [suspendForSystem] paused; safe to call on every foreground and
     * launch. Each mark is cleared only after its queue resumed, so a failure is retried next time.
     */
    public suspend fun resumeAfterSystem(unlessStoppedSince: Long? = null): Unit = transitions.withLock {
        // The system stopped the work again after the caller began (startup still hydrating
        // when the job was stopped): leave it to the next foreground or job run.
        if (unlessStoppedSince != null && stops != unlessStoppedSince) return@withLock
        // A newer run wants the work back, including a stop still waiting for the novel queue.
        stopBeforeNovelsRestored = false
        var paused = loadPausedBySystem() ?: return@withLock
        if (paused.everything) {
            val manga = runCatching { resumeManga(null) }.rethrowCancellation().isSuccess
            val novel = runCatching { resumeNovels(null) }.rethrowCancellation().isSuccess
            if (manga && novel) savePausedBySystem(SystemPausedQueues())
            return@withLock
        }
        if (paused.manga.isNotEmpty() && runCatching { resumeManga(paused.manga) }.rethrowCancellation().isSuccess) {
            paused = paused.copy(manga = emptySet())
            savePausedBySystem(paused)
        }
        if (paused.novels.isNotEmpty() && runCatching { resumeNovels(paused.novels) }.rethrowCancellation().isSuccess) {
            paused = paused.copy(novels = emptySet())
            savePausedBySystem(paused)
        }
    }

    /**
     * True when a system stop left entries paused that [resumeAfterSystem] would restart, so a
     * platform knows to ask for a later background run. An unreadable mark counts as work.
     */
    public suspend fun hasSystemPausedWork(): Boolean = transitions.withLock {
        val paused = loadPausedBySystem() ?: return@withLock true
        paused.everything || paused.manga.isNotEmpty() || paused.novels.isNotEmpty()
    }

    /** Null when the saved state cannot be read right now; it is read again next time. */
    private suspend fun loadPausedBySystem(): SystemPausedQueues? {
        pausedBySystem?.let { return it }
        val files = fileSystem ?: return SystemPausedQueues().also { pausedBySystem = it }
        val bytes = runCatching { files.read(PATH) }.rethrowCancellation().getOrElse { return null }
        // Some platforms (iOS NSData) answer an unreadable file the same as a missing one.
        if (bytes == null && runCatching { files.exists(PATH) }.rethrowCancellation().getOrDefault(true)) return null
        val stored = when (bytes) {
            null -> SystemPausedQueues()
            // A damaged mark must not strand a queue paused for good; resuming both is the safe side.
            else -> runCatching { json.decodeFromString(SystemPausedQueues.serializer(), bytes.decodeToString()) }
                .getOrElse { SystemPausedQueues(everything = true) }
        }
        return stored.also { pausedBySystem = it }
    }

    /** The in-memory copy follows only a successful write, so memory never claims more than disk. */
    private suspend fun savePausedBySystem(value: SystemPausedQueues): Boolean {
        val files = fileSystem
        if (files != null) {
            val written = runCatching {
                files.write(PATH, json.encodeToString(SystemPausedQueues.serializer(), value).encodeToByteArray())
            }.rethrowCancellation()
            if (written.isFailure) return false
        }
        pausedBySystem = value
        return true
    }

    private companion object {
        const val PATH = "downloads/system-paused.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Queue entry ids a system pause stopped, per queue. */
@Serializable
private data class SystemPausedQueues(
    val manga: Set<String> = emptySet(),
    val novels: Set<String> = emptySet(),
    /** Read from a damaged mark: every paused entry is resumed rather than any left stranded. */
    val everything: Boolean = false,
)

private fun <T> Result<T>.rethrowCancellation(): Result<T> =
    onFailure { if (it is CancellationException) throw it }
