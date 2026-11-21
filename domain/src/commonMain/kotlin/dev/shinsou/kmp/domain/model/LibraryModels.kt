package dev.shinsou.kmp.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val id: Long = 0,
    val name: String = "",
    val sort: Int = 0,
    val flags: Long = 0,
) {
    val isSystemCategory: Boolean get() = id <= 0
    val displayModeOverride: LibraryDisplayMode
        get() = LibraryDisplayMode.fromRaw((flags and DISPLAY_MODE_MASK).toInt())
    val sortTypeOverride: LibrarySortType
        get() = LibrarySortType.fromRaw(((flags and SORT_TYPE_MASK) shr SORT_TYPE_SHIFT).toInt())
    val sortDirectionOverride: SortDirection
        get() = if (flags and SORT_DIRECTION_MASK == 0L) SortDirection.ASCENDING else SortDirection.DESCENDING
    val effectiveSort: LibrarySort get() = LibrarySort(sortTypeOverride, sortDirectionOverride)

    fun withDisplayMode(mode: LibraryDisplayMode): Category = copy(
        flags = (flags and DISPLAY_MODE_MASK.inv()) or mode.rawValue.toLong(),
    )

    fun withSortType(type: LibrarySortType): Category = copy(
        flags = (flags and SORT_TYPE_MASK.inv()) or (type.rawValue.toLong() shl SORT_TYPE_SHIFT),
    )

    fun withSortDirection(direction: SortDirection): Category = copy(
        flags = (flags and SORT_DIRECTION_MASK.inv()) or
            (if (direction == SortDirection.DESCENDING) 1L shl SORT_DIRECTION_SHIFT else 0L),
    )

    fun withSort(sort: LibrarySort): Category = withSortType(sort.type).withSortDirection(sort.direction)

    companion object {
        val Default = Category(id = 0, name = "Default")

        private const val DISPLAY_MODE_MASK: Long = 0x03
        private const val SORT_TYPE_MASK: Long = 0x3C
        private const val SORT_DIRECTION_MASK: Long = 0x40
        private const val SORT_TYPE_SHIFT = 2
        private const val SORT_DIRECTION_SHIFT = 6
    }
}

@Serializable
enum class LibraryDisplayMode(val rawValue: Int) {
    COMPACT_GRID(0),
    COMFORTABLE_GRID(1),
    LIST(2),
    COVER_ONLY_GRID(3);

    companion object {
        fun fromRaw(rawValue: Int): LibraryDisplayMode = entries.firstOrNull { it.rawValue == rawValue } ?: COMPACT_GRID
    }
}

@Serializable
data class LibrarySort(
    val type: LibrarySortType = LibrarySortType.ALPHABETICAL,
    val direction: SortDirection = SortDirection.ASCENDING,
    val randomSeed: Long = 0,
)

@Serializable
enum class LibrarySortType(val rawValue: Int) {
    ALPHABETICAL(0),
    LAST_READ(1),
    LAST_UPDATE(2),
    UNREAD_COUNT(3),
    TOTAL_CHAPTERS(4),
    LATEST_CHAPTER(5),
    CHAPTER_FETCH_DATE(6),
    DATE_ADDED(7),
    TRACKER_MEAN(8),
    RANDOM(9);

    companion object {
        fun fromRaw(rawValue: Int): LibrarySortType = entries.firstOrNull { it.rawValue == rawValue } ?: ALPHABETICAL
    }
}

@Serializable
enum class SortDirection(val rawValue: Int) {
    ASCENDING(0),
    DESCENDING(1);

    fun toggled(): SortDirection = if (this == ASCENDING) DESCENDING else ASCENDING
}

@Serializable
data class LibraryFilter(
    val downloaded: LibraryFilterState = LibraryFilterState.DISABLED,
    val unread: LibraryFilterState = LibraryFilterState.DISABLED,
    val started: LibraryFilterState = LibraryFilterState.DISABLED,
    val bookmarked: LibraryFilterState = LibraryFilterState.DISABLED,
    val completed: LibraryFilterState = LibraryFilterState.DISABLED,
    val trackerFilters: Map<Int, LibraryFilterState> = emptyMap(),
) {
    val hasActiveFilters: Boolean
        get() = downloaded != LibraryFilterState.DISABLED || unread != LibraryFilterState.DISABLED ||
            started != LibraryFilterState.DISABLED || bookmarked != LibraryFilterState.DISABLED ||
            completed != LibraryFilterState.DISABLED || trackerFilters.values.any { it != LibraryFilterState.DISABLED }

    fun trackerFilter(trackerId: Int): LibraryFilterState = trackerFilters[trackerId] ?: LibraryFilterState.DISABLED

    fun withTrackerFilter(trackerId: Int, state: LibraryFilterState): LibraryFilter {
        val updated = trackerFilters.toMutableMap()
        if (state == LibraryFilterState.DISABLED) updated.remove(trackerId) else updated[trackerId] = state
        return copy(trackerFilters = updated)
    }
}

@Serializable
enum class LibraryFilterState {
    DISABLED,
    INCLUDE,
    EXCLUDE;

    fun next(): LibraryFilterState = when (this) {
        DISABLED -> INCLUDE
        INCLUDE -> EXCLUDE
        EXCLUDE -> DISABLED
    }
}

@Serializable
data class MangaCategory(
    val mangaId: Long,
    val categoryId: Long,
)

@Serializable
data class LibraryManga(
    val manga: Manga,
    val totalChapters: Int = 0,
    val readCount: Int = 0,
    val bookmarkCount: Int = 0,
    val latestUpload: Long = 0,
    val chapterFetchedAt: Long = 0,
    val lastRead: Long = 0,
    val category: Long = 0,
) {
    val id: Long get() = manga.id
    val unreadCount: Int get() = (totalChapters - readCount).coerceAtLeast(0)
    val hasStarted: Boolean get() = readCount > 0
    val hasBookmarks: Boolean get() = bookmarkCount > 0
}

@Serializable
data class LibraryItem(
    val libraryManga: LibraryManga,
    val downloadCount: Long = 0,
    val isLocal: Boolean = false,
    val sourceLanguage: String = "",
    val trackerIds: Set<Int> = emptySet(),
) {
    val id: Long get() = libraryManga.id
    val unreadCount: Long get() = libraryManga.unreadCount.toLong()

    fun matches(query: String): Boolean {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return true
        if (normalized.startsWith("id:")) return libraryManga.manga.id.toString() == normalized.removePrefix("id:")
        val manga = libraryManga.manga
        return manga.title.lowercase().contains(normalized) ||
            manga.author?.lowercase()?.contains(normalized) == true ||
            manga.artist?.lowercase()?.contains(normalized) == true ||
            manga.description?.lowercase()?.contains(normalized) == true ||
            manga.genre?.any { it.lowercase().contains(normalized) } == true
    }
}

object TrackerIds {
    const val MY_ANIME_LIST = 1
    const val KITSU = 3
    const val SHIKIMORI = 4
    const val BANGUMI = 5
    const val MANGA_UPDATES = 6
    const val KOMGA = 7
    const val KAVITA = 8
    const val SUWAYOMI = 9
}
