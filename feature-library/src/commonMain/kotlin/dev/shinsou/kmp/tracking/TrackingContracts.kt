package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.domain.model.Track
import dev.shinsou.kmp.domain.model.TrackSearch
import dev.shinsou.kmp.domain.model.TrackStatus
import kotlinx.serialization.Serializable

@Serializable
enum class TrackerScoreFormat {
    POINT_10,
    POINT_100,
    POINT_5,
    POINT_10_DECIMAL,
    POINT_3,
}

@Serializable
data class TrackerDescriptor(
    val id: Int,
    val name: String,
    val scoreFormat: TrackerScoreFormat = TrackerScoreFormat.POINT_10,
    val supportsReadingDates: Boolean = true,
    val supportsPrivateTracking: Boolean = false,
)

/** Latest values win when multiple updates are coalesced by [TrackingUpdateQueue]. */
@Serializable
data class TrackUpdate(
    val progress: Double? = null,
    val status: TrackStatus? = null,
    val score: Double? = null,
    val startDate: Long? = null,
    val finishDate: Long? = null,
) {
    val isEmpty: Boolean
        get() = progress == null && status == null && score == null && startDate == null && finishDate == null

    fun merge(newer: TrackUpdate): TrackUpdate = TrackUpdate(
        progress = newer.progress ?: progress,
        status = newer.status ?: status,
        score = newer.score ?: score,
        startDate = newer.startDate ?: startDate,
        finishDate = newer.finishDate ?: finishDate,
    )

    fun applyTo(track: Track): Track = track.copy(
        lastChapterRead = progress ?: track.lastChapterRead,
        status = status?.rawValue ?: track.status,
        score = score ?: track.score,
        startDate = startDate ?: track.startDate,
        finishDate = finishDate ?: track.finishDate,
    )
}

interface TrackerAdapter {
    val descriptor: TrackerDescriptor

    suspend fun isAuthenticated(): Boolean

    suspend fun search(query: String, limit: Int = 20): List<TrackSearch>

    suspend fun bind(mangaId: Long, remote: TrackSearch): Track

    suspend fun refresh(track: Track): Track

    /** Pushes only the supplied fields and returns the server's canonical representation. */
    suspend fun update(track: Track, update: TrackUpdate): Track
}

class TrackerNotFoundException(trackerId: Int) : IllegalArgumentException("Tracker $trackerId is not registered")

class TrackerAuthenticationException(message: String) : IllegalStateException(message)

class TrackerHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException("Tracker request failed with HTTP $statusCode: $responseBody")
