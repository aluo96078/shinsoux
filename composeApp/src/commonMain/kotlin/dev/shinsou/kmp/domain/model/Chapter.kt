package dev.shinsou.kmp.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val id: Long = -1,
    val mangaId: Long = -1,
    val url: String = "",
    val name: String = "",
    val scanlator: String? = null,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val lastPageRead: Int = 0,
    val chapterNumber: Double = -1.0,
    val sourceOrder: Int = 0,
    val dateFetch: Long = 0,
    val dateUpload: Long = 0,
    val lastModifiedAt: Long = 0,
    val version: Long = 1,
)

data class ChapterPatch(
    val read: Boolean? = null,
    val bookmark: Boolean? = null,
    val lastPageRead: Int? = null,
    val name: String? = null,
    val scanlator: String? = null,
    val dateUpload: Long? = null,
)

fun Chapter.applying(patch: ChapterPatch): Chapter = copy(
    read = patch.read ?: read,
    bookmark = patch.bookmark ?: bookmark,
    lastPageRead = patch.lastPageRead ?: lastPageRead,
    name = patch.name ?: name,
    scanlator = patch.scanlator ?: scanlator,
    dateUpload = patch.dateUpload ?: dateUpload,
)
