package dev.shinsou.kmp.domain.model

import kotlinx.serialization.Serializable

/** Core manga record. Field names and flag values intentionally mirror Shinsou's Swift model. */
@Serializable
data class Manga(
    val id: Long = -1,
    val source: Long = -1,
    val favorite: Boolean = false,
    val lastUpdate: Long = 0,
    val nextUpdate: Long = 0,
    val fetchInterval: Int = 0,
    val dateAdded: Long = 0,
    val viewerFlags: Long = 0,
    val chapterFlags: Long = 0,
    val coverLastModified: Long = 0,
    val url: String = "",
    val title: String = "",
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Long = 0,
    val thumbnailUrl: String? = null,
    val updateStrategy: Int = 0,
    val initialized: Boolean = false,
    val lastModifiedAt: Long = 0,
    val favoriteModifiedAt: Long? = null,
    val version: Long = 0,
    val notes: String = "",
    /** Scanlators hidden in Manga Detail and honoured by Reader skip-filter navigation. */
    val excludedScanlators: Set<String> = emptySet(),
) {
    val sorting: Long get() = chapterFlags and CHAPTER_SORTING_MASK
    val displayMode: Long get() = chapterFlags and CHAPTER_DISPLAY_MASK
    val unreadFilterRaw: Long get() = chapterFlags and CHAPTER_UNREAD_MASK
    val downloadedFilterRaw: Long get() = chapterFlags and CHAPTER_DOWNLOADED_MASK
    val bookmarkedFilterRaw: Long get() = chapterFlags and CHAPTER_BOOKMARKED_MASK
    val sortDescending: Boolean get() = chapterFlags and CHAPTER_SORT_DIR_MASK == CHAPTER_SORT_DESC

    val unreadFilter: ChapterFilterState
        get() = when (unreadFilterRaw) {
            CHAPTER_SHOW_UNREAD -> ChapterFilterState.INCLUDE
            CHAPTER_SHOW_READ -> ChapterFilterState.EXCLUDE
            else -> ChapterFilterState.DISABLED
        }

    val downloadedFilter: ChapterFilterState
        get() = when (downloadedFilterRaw) {
            CHAPTER_SHOW_DOWNLOADED -> ChapterFilterState.INCLUDE
            CHAPTER_SHOW_NOT_DOWNLOADED -> ChapterFilterState.EXCLUDE
            else -> ChapterFilterState.DISABLED
        }

    val bookmarkedFilter: ChapterFilterState
        get() = when (bookmarkedFilterRaw) {
            CHAPTER_SHOW_BOOKMARKED -> ChapterFilterState.INCLUDE
            CHAPTER_SHOW_NOT_BOOKMARKED -> ChapterFilterState.EXCLUDE
            else -> ChapterFilterState.DISABLED
        }

    companion object {
        const val SHOW_ALL: Long = 0x00000000

        const val CHAPTER_SORT_DESC: Long = 0x00000000
        const val CHAPTER_SORT_ASC: Long = 0x00000001
        const val CHAPTER_SORT_DIR_MASK: Long = 0x00000001

        const val CHAPTER_SHOW_UNREAD: Long = 0x00000002
        const val CHAPTER_SHOW_READ: Long = 0x00000004
        const val CHAPTER_UNREAD_MASK: Long = 0x00000006

        const val CHAPTER_SHOW_DOWNLOADED: Long = 0x00000008
        const val CHAPTER_SHOW_NOT_DOWNLOADED: Long = 0x00000010
        const val CHAPTER_DOWNLOADED_MASK: Long = 0x00000018

        const val CHAPTER_SHOW_BOOKMARKED: Long = 0x00000020
        const val CHAPTER_SHOW_NOT_BOOKMARKED: Long = 0x00000040
        const val CHAPTER_BOOKMARKED_MASK: Long = 0x00000060

        const val CHAPTER_SORTING_SOURCE: Long = 0x00000000
        const val CHAPTER_SORTING_NUMBER: Long = 0x00000100
        const val CHAPTER_SORTING_UPLOAD_DATE: Long = 0x00000200
        const val CHAPTER_SORTING_ALPHABET: Long = 0x00000300
        const val CHAPTER_SORTING_MASK: Long = 0x00000300

        const val CHAPTER_DISPLAY_NAME: Long = 0x00000000
        const val CHAPTER_DISPLAY_NUMBER: Long = 0x00100000
        const val CHAPTER_DISPLAY_MASK: Long = 0x00100000
    }
}

@Serializable
enum class ChapterFilterState {
    DISABLED,
    INCLUDE,
    EXCLUDE,
}

/** Partial update shape used by repository callers without reconstructing a complete record. */
data class MangaPatch(
    val favorite: Boolean? = null,
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Long? = null,
    val chapterFlags: Long? = null,
    val viewerFlags: Long? = null,
    val notes: String? = null,
    val excludedScanlators: Set<String>? = null,
    val dateAdded: Long? = null,
    val thumbnailUrl: String? = null,
    val initialized: Boolean? = null,
    val lastUpdate: Long? = null,
    val nextUpdate: Long? = null,
)

fun Manga.applying(patch: MangaPatch): Manga = copy(
    favorite = patch.favorite ?: favorite,
    title = patch.title ?: title,
    author = patch.author ?: author,
    artist = patch.artist ?: artist,
    description = patch.description ?: description,
    genre = patch.genre ?: genre,
    status = patch.status ?: status,
    chapterFlags = patch.chapterFlags ?: chapterFlags,
    viewerFlags = patch.viewerFlags ?: viewerFlags,
    notes = patch.notes ?: notes,
    excludedScanlators = patch.excludedScanlators ?: excludedScanlators,
    dateAdded = patch.dateAdded ?: dateAdded,
    thumbnailUrl = patch.thumbnailUrl ?: thumbnailUrl,
    initialized = patch.initialized ?: initialized,
    lastUpdate = patch.lastUpdate ?: lastUpdate,
    nextUpdate = patch.nextUpdate ?: nextUpdate,
)
