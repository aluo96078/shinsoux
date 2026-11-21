package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_SERVER_URL
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_SIGNED_IN
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.STREAM_BACKOFF_MAX_MILLIS
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.STREAM_BACKOFF_MIN_MILLIS
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.STREAM_STABLE_MILLIS
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** When automatic cycles may run; written by the cycle, read by the triggers. */
internal class SyncCycleTiming {
    @Volatile var consecutiveFailures = 0
    @Volatile var lastCycleStartedAt = 0L
    @Volatile var nextAutomaticAttemptAt = 0L
}

/** How [SyncTriggers] runs automatic cycles. */
internal class SyncTriggerSettings(
    val pushDebounceMillis: Long,
    val automaticIntervalMillis: Long,
    val timing: SyncCycleTiming,
    val runAutomaticCycle: suspend () -> Unit,
)

/**
 * The one owner of the background jobs: the debounced local-edit trigger, the server-notification
 * trigger and the realtime event stream, with their signals and the foreground flag.
 */
@OptIn(FlowPreview::class)
internal class SyncTriggers(
    private val context: SyncV3Context,
    private val scope: CoroutineScope,
    private val tokens: SyncSessionTokens,
    private val publisher: SyncStatePublisher,
    private val settings: SyncTriggerSettings,
) {
    private val store = context.store
    private val api = context.api
    private val nowMillis = context.nowMillis
    private val timing = settings.timing

    // The entry points run on a multi-threaded dispatcher: the jobs and the foreground flag change
    // only under [lock], so two starts cannot both open a stream, and a background flush cannot
    // close the stream a return to the foreground just kept.
    private val lock = SynchronousLock()

    @Volatile var foreground = false
        private set
    private var streamJob: Job? = null
    private var debounceJob: Job? = null
    private val dirtySignal = Channel<Unit>(Channel.CONFLATED)
    private var remoteJob: Job? = null
    private val remoteSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var remoteSeq = 0L

    /** Asks for an automatic cycle (debounced). */
    fun signalDirty() {
        dirtySignal.trySend(Unit)
    }

    fun stopBackgroundWork(): Unit = lock.withLock {
        streamJob?.cancel()
        streamJob = null
        debounceJob?.cancel()
        debounceJob = null
        remoteJob?.cancel()
        remoteJob = null
    }

    /** Whether the live stream is open (or reconnecting). */
    val streamOpen: Boolean get() = lock.withLock { streamJob?.isActive == true }

    /** The app came to the foreground (true) or left the screen (false). */
    fun setForeground(value: Boolean): Unit = lock.withLock { foreground = value }

    /** Closes the event stream (the app left the screen). */
    fun closeStream(): Unit = lock.withLock { closeStreamLocked() }

    /** [closeStream], unless the app came back to the foreground meanwhile. */
    fun closeStreamIfInBackground(): Unit = lock.withLock {
        if (!foreground) closeStreamLocked()
    }

    private fun closeStreamLocked() {
        streamJob?.cancel()
        streamJob = null
        publisher.update { it.copy(streamConnected = false) }
    }

    /** Starts the two automatic triggers: debounced local edits and server notifications. */
    fun startDebounce(): Unit = lock.withLock {
        if (debounceJob?.isActive != true) {
            debounceJob = scope.launch {
                dirtySignal.receiveAsFlow().debounce(settings.pushDebounceMillis).collect {
                    // Reading turns pages every few seconds: never run automatic cycles back to back.
                    val wait = timing.lastCycleStartedAt + settings.automaticIntervalMillis - nowMillis()
                    if (wait > 0) delay(wait)
                    if (nowMillis() >= timing.nextAutomaticAttemptAt) settings.runAutomaticCycle()
                }
            }
        }
        if (remoteJob?.isActive != true) {
            remoteJob = scope.launch {
                // One consumer of a conflated channel: at most one cycle running and one waiting,
                // however many notifications arrive meanwhile.
                remoteSignal.receiveAsFlow().collect {
                    if (remoteSeq > context.cursor) settings.runAutomaticCycle()
                }
            }
        }
    }

    /** The server announced that the account reached [seq]. */
    fun onRemoteSeq(seq: Long) {
        if (seq > remoteSeq) remoteSeq = seq
        remoteSignal.trySend(Unit)
    }

    /** After a token refresh: the stream authenticates once when it opens, so it reconnects. */
    fun restartStreamIfActive(): Unit = lock.withLock {
        if (streamJob?.isActive == true) startStreamLocked(restart = true)
    }

    fun startStream(restart: Boolean = false): Unit = lock.withLock { startStreamLocked(restart) }

    /** [startStream], only while the app is in the foreground. */
    fun startStreamIfInForeground(): Unit = lock.withLock {
        if (foreground) startStreamLocked(restart = false)
    }

    private fun startStreamLocked(restart: Boolean) {
        if (!restart && streamJob?.isActive == true) return
        streamJob?.cancel()
        streamJob = scope.launch {
            var backoff = STREAM_BACKOFF_MIN_MILLIS
            while (true) {
                val connectedAt = nowMillis()
                try {
                    val url = store.meta(META_SERVER_URL) ?: return@launch
                    val token = tokens.accessTokenOrRefresh()
                    publisher.update { it.copy(streamConnected = true) }
                    api.stream(url, token) { seq -> onRemoteSeq(seq) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: SyncApiException) {
                    if (error.isUnauthorized) {
                        runCatchingCancellable { tokens.forceRefresh(tokens.cachedAccessToken) }
                            .onFailure { Log.warn("Sync", it) { "Refreshing the sync session for the event stream failed" } }
                    }
                } catch (logged: Throwable) {
                    // Reconnect below.
                    Log.warn("Sync", logged) { "The sync event stream disconnected; reconnecting" }
                }
                publisher.update { it.copy(streamConnected = false) }
                if (store.meta(META_SIGNED_IN) != "1") return@launch
                if (nowMillis() - connectedAt > STREAM_STABLE_MILLIS) backoff = STREAM_BACKOFF_MIN_MILLIS
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(STREAM_BACKOFF_MAX_MILLIS)
            }
        }
    }
}
