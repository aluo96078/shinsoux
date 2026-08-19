package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackSearch
import dev.shinsou.kmp.domain.model.TrackStatus
import dev.shinsou.kmp.domain.model.TrackerAccountState
import kotlinx.coroutines.CoroutineScope

class TrackerManager(
    adapters: List<TrackerAdapter>,
    private val repository: ShinsouRepository,
    scope: CoroutineScope,
    debounceMillis: Long = TrackingUpdateQueue.DEFAULT_DEBOUNCE_MILLIS,
) {
    private val adaptersById = adapters.associateBy { it.descriptor.id }
    private val updateQueue = TrackingUpdateQueue(scope, debounceMillis, ::dispatchQueuedUpdate)

    val descriptors: List<TrackerDescriptor> = adapters.map { it.descriptor }

    init {
        require(adaptersById.size == adapters.size) { "Tracker IDs must be unique" }
    }

    fun adapter(trackerId: Int): TrackerAdapter = adaptersById[trackerId] ?: throw TrackerNotFoundException(trackerId)

    suspend fun refreshAuthenticationState(trackerId: Int): TrackerAccountState {
        val tracker = adapter(trackerId)
        val state = TrackerAccountState(trackerId, loggedIn = tracker.isAuthenticated())
        return repository.upsertTrackerAccount(state)
    }

    suspend fun search(trackerId: Int, query: String, limit: Int = 20): List<TrackSearch> {
        if (query.isBlank()) return emptyList()
        return adapter(trackerId).search(query.trim(), limit)
    }

    suspend fun bind(mangaId: Long, trackerId: Int, remote: TrackSearch): Track {
        val bound = adapter(trackerId).bind(mangaId, remote)
        return repository.upsertTrack(bound)
    }

    suspend fun refresh(mangaId: Long, trackerId: Int): Track {
        val current = requireTrack(mangaId, trackerId)
        val refreshed = adapter(trackerId).refresh(current)
        return repository.upsertTrack(refreshed)
    }

    suspend fun updateNow(mangaId: Long, trackerId: Int, update: TrackUpdate): Track {
        val current = requireTrack(mangaId, trackerId)
        val updated = adapter(trackerId).update(current, update)
        return repository.upsertTrack(updated)
    }

    suspend fun queueUpdate(mangaId: Long, trackerId: Int, update: TrackUpdate) {
        updateQueue.enqueue(requireTrack(mangaId, trackerId), update)
    }

    suspend fun updateProgress(mangaId: Long, trackerId: Int, chapter: Double) {
        queueUpdate(mangaId, trackerId, TrackUpdate(progress = chapter.coerceAtLeast(0.0)))
    }

    suspend fun updateStatus(mangaId: Long, trackerId: Int, status: TrackStatus) {
        queueUpdate(mangaId, trackerId, TrackUpdate(status = status))
    }

    suspend fun updateScore(mangaId: Long, trackerId: Int, score: Double) {
        queueUpdate(mangaId, trackerId, TrackUpdate(score = score.coerceAtLeast(0.0)))
    }

    suspend fun updateDates(mangaId: Long, trackerId: Int, startDate: Long? = null, finishDate: Long? = null) {
        queueUpdate(mangaId, trackerId, TrackUpdate(startDate = startDate, finishDate = finishDate))
    }

    suspend fun flush(mangaId: Long, trackerId: Int): Boolean = updateQueue.flush(TrackingQueueKey(mangaId, trackerId))

    suspend fun flushAll() = updateQueue.flushAll()

    suspend fun cancelAll() = updateQueue.cancelAll()

    suspend fun pendingUpdateCount(): Int = updateQueue.pendingCount()

    private suspend fun dispatchQueuedUpdate(queued: QueuedTrackingUpdate) {
        val tracker = adapter(queued.key.trackerId)
        val latest = repository.tracksForManga(queued.key.mangaId)
            .firstOrNull { it.trackerId == queued.key.trackerId }
            ?: queued.track
        repository.upsertTrack(tracker.update(latest, queued.update))
    }

    private fun requireTrack(mangaId: Long, trackerId: Int): Track = repository.tracksForManga(mangaId)
        .firstOrNull { it.trackerId == trackerId }
        ?: throw IllegalArgumentException("Manga $mangaId is not bound to tracker $trackerId")
}
