package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.plugin.PluginContentType
import dev.shinsou.kmp.ui.BrowseExtension
import dev.shinsou.kmp.ui.BrowseManga
import dev.shinsou.kmp.ui.BrowseRepository
import dev.shinsou.kmp.ui.MigrationCandidate
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
): List<BrowseSource> = eligibleMigrationSources(
    sources,
    MigrationCandidate(mangaId = 0, title = "", currentSourceName = currentSourceName),
)

/**
 * Sources a library title can move to: enabled, of the same kind (manga or novel), and on the same
 * identity path. A legacy row moves through the Long-keyed import bridge, an extension-backed
 * title only between exact v2 sources (its chapters are typed units of a source publication).
 */
internal fun eligibleMigrationSources(
    sources: List<BrowseSource>,
    candidate: MigrationCandidate,
): List<BrowseSource> = sources
    .asSequence()
    .filter(BrowseSource::enabled)
    .filter { (it.sourceKey != null) == (candidate.sourceKey != null) }
    .filter { migrationContentTypesMatch(it.contentType, candidate.contentType) }
    .filterNot { source ->
        candidate.sourceKey?.let { source.sourceKey == it }
            ?: (source.id == candidate.sourceId || source.name.equals(candidate.currentSourceName, ignoreCase = true))
    }
    .distinctBy(BrowseSource::identityKey)
    .sortedWith(compareBy<BrowseSource> { it.name.lowercase() }.thenBy(BrowseSource::identityKey))
    .toList()

/** An unknown ([PluginContentType.BOTH]) side never rules a source out. */
internal fun migrationContentTypesMatch(source: PluginContentType, title: PluginContentType): Boolean =
    source == PluginContentType.BOTH || title == PluginContentType.BOTH || source == title

internal fun rankBrowseResults(query: String, items: List<BrowseManga>): List<BrowseManga> {
    val normalizedQuery = normalizeSearchTitle(query).lowercase()
    val distinct = items.distinctBy(BrowseManga::identityKey)
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

/** One repository's items; [repository] is null for items from no configured repository. */
internal data class RepositoryGroup<T>(
    val repository: BrowseRepository?,
    val items: List<T>,
)

internal typealias ExtensionRepositoryGroup = RepositoryGroup<BrowseExtension>

internal val RepositoryGroup<BrowseExtension>.extensions: List<BrowseExtension> get() = items

/**
 * A selected repository shows only its own items (installed ones included); no selection shows
 * every repository, each under its own heading in list order, and items from a repository that is
 * no longer configured (or none) last.
 */
internal fun <T> repositoryGroups(
    items: List<T>,
    repositories: List<BrowseRepository>,
    selectedRepositoryId: String?,
    repositoryOf: (T) -> String?,
): List<RepositoryGroup<T>> {
    fun key(id: String?): String? = id?.trim()?.trimEnd('/')?.lowercase()
    val selected = repositories.firstOrNull { it.id == selectedRepositoryId }
    if (selected != null) {
        return listOf(RepositoryGroup(selected, items.filter { key(repositoryOf(it)) == key(selected.id) }))
    }
    val known = repositories.mapTo(mutableSetOf()) { key(it.id) }
    val groups = repositories.map { repository ->
        RepositoryGroup(repository, items.filter { key(repositoryOf(it)) == key(repository.id) })
    }.filter { it.items.isNotEmpty() }
    val unlisted = items.filter { repositoryOf(it) == null || key(repositoryOf(it)) !in known }
    return if (unlisted.isEmpty()) groups else groups + RepositoryGroup(null, unlisted)
}

internal fun extensionRepositoryGroups(
    extensions: List<BrowseExtension>,
    repositories: List<BrowseRepository>,
    selectedRepositoryId: String?,
): List<ExtensionRepositoryGroup> = repositoryGroups(extensions, repositories, selectedRepositoryId) { it.repositoryId }

/** Repositories whose name or URL contains [query] (case-insensitive), in list order. */
internal fun repositoryPickerMatches(repositories: List<BrowseRepository>, query: String): List<BrowseRepository> {
    val needle = query.trim()
    if (needle.isEmpty()) return repositories
    return repositories.filter { it.name.contains(needle, ignoreCase = true) || it.url.contains(needle, ignoreCase = true) }
}
