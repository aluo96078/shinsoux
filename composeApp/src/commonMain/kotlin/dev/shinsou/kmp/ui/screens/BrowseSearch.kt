package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.ui.BrowseManga
import dev.shinsou.kmp.ui.BrowseSource

private val titleParentheticalPattern = Regex("\\([^)]*\\)")
private val titleBracketPattern = Regex("\\[[^]]*]")
private val titleWhitespacePattern = Regex("\\s+")
private val titleSuffixPatterns = listOf(
    "manga adaptation",
    "official comic",
    "the comic",
    "manhwa",
    "manhua",
    "manga",
    "comic",
    "web",
).map { suffix ->
    Regex("[\\s:–—-]+${Regex.escape(suffix)}$", RegexOption.IGNORE_CASE)
}

private data class ScoredBrowseResult(
    val item: BrowseManga,
    val score: Double,
    val titleSortKey: String,
)

/** Pure search helpers kept outside Compose so ranking and migration filtering stay deterministic. */
internal fun eligibleMigrationSources(
    sources: List<BrowseSource>,
    currentSourceName: String,
): List<BrowseSource> = sources
    .asSequence()
    .filter(BrowseSource::enabled)
    .filterNot { it.name.equals(currentSourceName, ignoreCase = true) }
    .distinctBy(BrowseSource::id)
    .sortedWith(compareBy<BrowseSource> { it.name.lowercase() }.thenBy(BrowseSource::id))
    .toList()

internal fun rankBrowseResults(query: String, items: List<BrowseManga>): List<BrowseManga> {
    val normalizedQuery = normalizeSearchTitle(query).lowercase()
    val distinct = items.distinctBy { it.sourceId to it.url }
    if (normalizedQuery.isBlank()) return distinct
    return distinct
        .map { item ->
            val normalizedTitle = normalizeSearchTitle(item.title).lowercase()
            ScoredBrowseResult(
                item = item,
                score = normalizedTitleSimilarity(normalizedQuery, normalizedTitle),
                titleSortKey = item.title.lowercase(),
            )
        }
        .sortedWith(
            compareByDescending<ScoredBrowseResult>(ScoredBrowseResult::score)
                .thenBy(ScoredBrowseResult::titleSortKey)
                .thenBy { it.item.url },
        )
        .map(ScoredBrowseResult::item)
}

internal fun normalizeSearchTitle(title: String): String {
    var cleaned = title
        .replace(titleParentheticalPattern, " ")
        .replace(titleBracketPattern, " ")
    titleSuffixPatterns.forEach { pattern -> cleaned = cleaned.replace(pattern, "") }
    return cleaned.trim().replace(titleWhitespacePattern, " ")
}

internal fun titleSimilarity(first: String, second: String): Double {
    val left = normalizeSearchTitle(first).lowercase()
    val right = normalizeSearchTitle(second).lowercase()
    return normalizedTitleSimilarity(left, right)
}

private fun normalizedTitleSimilarity(left: String, right: String): Double {
    if (left.isEmpty() || right.isEmpty()) return if (left == right) 1.0 else 0.0
    if (left == right) return 1.0
    if (left.contains(right) || right.contains(left)) {
        return minOf(left.length, right.length).toDouble() / maxOf(left.length, right.length)
    }
    return 1.0 - levenshteinDistance(left, right).toDouble() / maxOf(left.length, right.length)
}

private fun levenshteinDistance(first: String, second: String): Int {
    if (first.isEmpty()) return second.length
    if (second.isEmpty()) return first.length
    var previous = IntArray(second.length + 1) { it }
    var current = IntArray(second.length + 1)
    first.forEachIndexed { firstIndex, firstCharacter ->
        current[0] = firstIndex + 1
        second.forEachIndexed { secondIndex, secondCharacter ->
            current[secondIndex + 1] = if (firstCharacter == secondCharacter) {
                previous[secondIndex]
            } else {
                1 + minOf(
                    previous[secondIndex + 1],
                    current[secondIndex],
                    previous[secondIndex],
                )
            }
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[second.length]
}
