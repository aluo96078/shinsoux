package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.local.encodeTypedLocalChapterUrl

/**
 * How one extension-backed library title moves to another exact source publication.
 *
 * [moved] keeps every matched chapter row (its id, read mark, page and history) and only rewrites
 * its typed compatibility URL to the target unit, so the reader and the detail screen resolve the
 * carried-over progress against the new source. [droppedChapterIds] had no counterpart.
 */
internal data class ExtensionLibraryMigrationPlanV2(
    val libraryUrl: String,
    val moved: List<Chapter>,
    val droppedChapterIds: List<Long>,
)

/**
 * Matches the title's local chapter rows to [targetUnits] (in source order): first by the same
 * normalized chapter title, then by the chapter number written in the title. Pure and offline.
 */
internal fun planExtensionLibraryMigrationV2(
    oldChapters: List<Chapter>,
    targetSourceKey: SourceKey,
    targetRemotePublicationId: String,
    targetUnits: List<RemoteUnitV2>,
): ExtensionLibraryMigrationPlanV2 {
    val publicationKey = extensionPublicationKey(targetSourceKey, targetRemotePublicationId)
    val acquisitionId = extensionAcquisitionId(targetSourceKey, targetRemotePublicationId)
    val units = targetUnits.distinctBy(RemoteUnitV2::remoteId)
    val claimedUnits = mutableSetOf<String>()
    val byTitle = units.withIndex().groupBy { normalizedUnitTitle(it.value.title) }
    // A number decides only when it names exactly one chapter on each side: specials, volume
    // labels and alternate versions often repeat a number, and a guess would move read marks.
    val byNumber = units.withIndex()
        .mapNotNull { indexed -> unitNumber(indexed.value.title)?.let { it to indexed } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size == 1 }
    val oldNumberCounts = oldChapters.mapNotNull { unitNumber(it.name) }.groupingBy { it }.eachCount()

    fun claim(candidates: List<IndexedValue<RemoteUnitV2>>?): IndexedValue<RemoteUnitV2>? =
        candidates?.firstOrNull { it.value.remoteId !in claimedUnits }?.also { claimedUnits += it.value.remoteId }

    // Title matches first for every chapter, so a number match never steals an exact title.
    val titleMatches = oldChapters.associate { chapter ->
        chapter.id to claim(byTitle[normalizedUnitTitle(chapter.name)].takeIf { normalizedUnitTitle(chapter.name).isNotEmpty() })
    }
    val moved = mutableListOf<Chapter>()
    val dropped = mutableListOf<Long>()
    oldChapters.forEach { chapter ->
        val match = titleMatches[chapter.id]
            ?: unitNumber(chapter.name)
                ?.takeIf { oldNumberCounts[it] == 1 }
                ?.let { number -> claim(byNumber[number]) }
        if (match == null) {
            dropped += chapter.id
            return@forEach
        }
        val unitKey = extensionUnitKey(targetSourceKey, targetRemotePublicationId, match.value.remoteId)
        moved += chapter.copy(
            url = encodeTypedLocalChapterUrl(publicationKey, acquisitionId, unitKey),
            name = match.value.title,
            sourceOrder = match.index,
        )
    }
    return ExtensionLibraryMigrationPlanV2(
        libraryUrl = encodeExtensionLibraryPublicationUrl(targetSourceKey, targetRemotePublicationId),
        moved = moved,
        droppedChapterIds = dropped,
    )
}

internal fun normalizedUnitTitle(value: String): String =
    value.lowercase().filter(Char::isLetterOrDigit)

private val UNIT_NUMBER = Regex("""第\s*([0-9０-９零〇一二兩两三四五六七八九十百千]+)\s*[章話话回節节卷集篇]|(?:chapter|ch\.?|ep\.?)\s*(\d+)""", RegexOption.IGNORE_CASE)
private val LEADING_NUMBER = Regex("""^\s*(\d+)""")

/** The chapter number a title states, if it states one plainly. */
internal fun unitNumber(title: String): Int? {
    UNIT_NUMBER.find(title)?.let { match ->
        val raw = match.groupValues[1].ifEmpty { match.groupValues[2] }
        return parseUnitNumber(raw)
    }
    return LEADING_NUMBER.find(title)?.groupValues?.get(1)?.toIntOrNull()
}

private fun parseUnitNumber(raw: String): Int? {
    val ascii = raw.map { c -> if (c in '０'..'９') '0' + (c - '０') else c }.joinToString("")
    ascii.toIntOrNull()?.let { return it }
    val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    val units = mapOf('十' to 10, '百' to 100, '千' to 1000)
    var total = 0
    var current = 0
    for (c in ascii) {
        when (c) {
            in digits -> current = digits.getValue(c)
            in units -> {
                total += (if (current == 0) 1 else current) * units.getValue(c)
                current = 0
            }
            else -> return null
        }
    }
    return (total + current).takeIf { it > 0 }
}
