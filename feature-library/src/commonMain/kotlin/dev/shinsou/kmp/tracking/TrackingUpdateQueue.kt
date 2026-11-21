package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TrackingQueueKey(val mangaId: Long, val trackerId: Int)

data class QueuedTrackingUpdate(
    val key: TrackingQueueKey,
    val track: Track,
    val update: TrackUpdate,
)

/** Trailing-edge debounce queue; updates for the same manga/tracker are coalesced for 3 seconds. */
class TrackingUpdateQueue(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val dispatch: suspend (QueuedTrackingUpdate) -> Unit,
) {
    private val mutex = Mutex()
    private val pending = mutableMapOf<TrackingQueueKey, Pending>()

    init {
        require(debounceMillis >= 0) { "Debounce duration cannot be negative" }
    }

    suspend fun enqueue(track: Track, update: TrackUpdate) {
        if (update.isEmpty) return
        val key = TrackingQueueKey(track.mangaId, track.trackerId)
        mutex.withLock {
            val previous = pending[key]
            previous?.job?.cancel()
            val merged = previous?.update?.merge(update) ?: update
            val job = scope.launch {
                delay(debounceMillis)
                deliver(key)
            }
            pending[key] = Pending(track, merged, job)
        }
    }

    suspend fun flush(key: TrackingQueueKey): Boolean {
        val value = mutex.withLock { pending.remove(key) } ?: return false
        value.job.cancel()
        dispatch(QueuedTrackingUpdate(key, value.track, value.update))
        return true
    }

    suspend fun flushAll() {
        val values = mutex.withLock {
            val copy = pending.map { (key, value) -> key to value }
            pending.clear()
            copy
        }
        values.forEach { (key, value) ->
            value.job.cancel()
            dispatch(QueuedTrackingUpdate(key, value.track, value.update))
        }
    }

    suspend fun cancel(key: TrackingQueueKey): Boolean {
        val value = mutex.withLock { pending.remove(key) } ?: return false
        value.job.cancel()
        return true
    }

    suspend fun cancelAll() {
        val jobs = mutex.withLock {
            val values = pending.values.map { it.job }
            pending.clear()
            values
        }
        jobs.forEach(Job::cancel)
    }

    suspend fun pendingCount(): Int = mutex.withLock { pending.size }

    private suspend fun deliver(key: TrackingQueueKey) {
        val value = mutex.withLock { pending.remove(key) } ?: return
        dispatch(QueuedTrackingUpdate(key, value.track, value.update))
    }

    private data class Pending(
        val track: Track,
        val update: TrackUpdate,
        val job: Job,
    )

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS: Long = 3_000
    }
}
