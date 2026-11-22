package dev.shinsou.kmp.ui.browse

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import dev.shinsou.kmp.plugin.ExtensionPublicationCatalog
import dev.shinsou.kmp.plugin.SourceCatalog
import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.plugin.BrowsePage
import dev.shinsou.kmp.plugin.BrowseSource
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// R4-4 (docs/architecture/0006): split out of BrowseScreen.kt without changing it.

internal fun searchAcrossSources(
    catalog: SourceCatalog,
    publications: ExtensionPublicationCatalog,
    sources: List<BrowseSource>,
    query: String,
    strings: dev.shinsou.kmp.ui.i18n.ShinsouStrings,
): Flow<SourceSearchUpdate> = channelFlow {
    val semaphore = Semaphore(GLOBAL_SEARCH_MAX_CONCURRENCY)
    sources.distinctBy(BrowseSource::identityKey).forEachIndexed { sourceIndex, source ->
        launch {
            val result = semaphore.withPermit {
                try {
                    val page = browseGlobalSearchSource(catalog, publications, source, query)
                    currentCoroutineContext().ensureActive()
                    val ranked = rankBrowseResults(query, page.items)
                    SourceSearchResult(
                        source = source,
                        items = ranked.take(GLOBAL_SEARCH_VISIBLE_RESULTS),
                        totalCount = ranked.size,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    SourceSearchResult(
                        source = source,
                        errorMessage = error.localizedDiagnosticMessage(strings),
                    )
                }
            }
            currentCoroutineContext().ensureActive()
            send(SourceSearchUpdate(sourceIndex, result))
        }
    }
}

internal suspend fun browseGlobalSearchSource(
    catalog: SourceCatalog,
    publications: ExtensionPublicationCatalog,
    source: BrowseSource,
    query: String,
): BrowsePage {
    val sourceKey = source.sourceKey
    if (sourceKey != null) {
        val result = publications.searchSourceV2(sourceKey, query, page = 0)
        return BrowsePage(
            items = result.items.map { publication -> publication.toBrowseManga(source) },
            hasNextPage = result.hasNextPage,
        )
    }
    return catalog.browseSource(
        sourceId = source.id,
        query = query,
        page = 1,
        // Global search intentionally ignores source-specific filters, matching original Shinsou.
        // The explicit empty list also avoids requiring optional hooks from sources such as MangaCopy.
        filters = emptyList(),
    )
}

/** Compatibility helper for legacy-only focused tests/callers. */
internal suspend fun browseGlobalSearchSource(
    catalog: SourceCatalog,
    sourceId: Long,
    query: String,
): BrowsePage = catalog.browseSource(
    sourceId = sourceId,
    query = query,
    page = 1,
    filters = emptyList(),
)

/** Keeps a previously resolved catalogue cover when a detail response omits it. */
internal fun RemotePublicationV2.withFallbackThumbnail(fallback: String?): RemotePublicationV2 =
    if (!thumbnailUrl.isNullOrBlank() || fallback.isNullOrBlank()) this
    else copy(thumbnailUrl = fallback)

internal fun RemotePublicationV2.toBrowseManga(source: BrowseSource): BrowseManga = BrowseManga(
    sourceId = source.id,
    url = url ?: remoteId,
    title = title,
    thumbnailUrl = thumbnailUrl,
    author = author,
    artist = artist,
    description = description,
    genre = genre,
    status = status,
    sourceKey = requireNotNull(source.sourceKey),
    remotePublicationId = remoteId,
)
