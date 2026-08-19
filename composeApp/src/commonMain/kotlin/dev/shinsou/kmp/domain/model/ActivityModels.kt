package dev.shinsou.kmp.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class History(
    val id: Long = -1,
    val chapterId: Long,
    val lastRead: Long = 0,
    val timeRead: Long = 0,
)

@Serializable
data class HistoryItem(
    val manga: Manga,
    val chapter: Chapter,
    val lastRead: Long,
    val timeRead: Long = 0,
) {
    val id: Long get() = chapter.id
}

@Serializable
data class LibraryUpdate(
    val mangaId: Long,
    val chapterId: Long,
    val discoveredAt: Long = 0,
) {
    val id: Long get() = chapterId
}

@Serializable
data class UpdateItem(
    val manga: Manga,
    val chapter: Chapter,
    val discoveredAt: Long = 0,
) {
    val id: Long get() = chapter.id
}

@Serializable
data class Track(
    val id: Long = -1,
    val mangaId: Long = -1,
    val trackerId: Int = 0,
    val remoteId: Long = 0,
    val title: String = "",
    val lastChapterRead: Double = 0.0,
    val totalChapters: Int = 0,
    val status: Int = 0,
    val score: Double = 0.0,
    val remoteUrl: String = "",
    val startDate: Long = 0,
    val finishDate: Long = 0,
)

@Serializable
data class TrackSearch(
    val id: Long,
    val title: String,
    val totalChapters: Int = 0,
    val coverUrl: String = "",
    val summary: String = "",
    val publishingStatus: String = "",
    val publishingType: String = "",
    val startDate: String = "",
)

@Serializable
enum class TrackStatus(val rawValue: Int) {
    READING(1),
    COMPLETED(2),
    ON_HOLD(3),
    DROPPED(4),
    PLAN_TO_READ(5),
    REREADING(6),
}

@Serializable
data class ExtensionRepo(
    val baseUrl: String,
    val name: String,
    val shortName: String? = null,
    val website: String = "",
    val signingKeyFingerprint: String = "",
) {
    val id: String get() = baseUrl
}
