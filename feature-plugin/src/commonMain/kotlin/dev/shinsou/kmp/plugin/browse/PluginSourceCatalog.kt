package dev.shinsou.kmp.plugin.browse

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import dev.shinsou.kmp.domain.model.decodeExtensionLibraryPublicationUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.plugin.contracts.BrowseFilter
import dev.shinsou.kmp.plugin.contracts.BrowseManga
import dev.shinsou.kmp.plugin.contracts.BrowsePage
import dev.shinsou.kmp.plugin.contracts.BrowseSnapshot
import dev.shinsou.kmp.plugin.contracts.CatalogueSource
import dev.shinsou.kmp.plugin.contracts.PageRequestMetadata
import dev.shinsou.kmp.plugin.contracts.SManga
import dev.shinsou.kmp.plugin.contracts.Source
import dev.shinsou.kmp.plugin.contracts.SourceCatalog
import dev.shinsou.kmp.plugin.contracts.toPluginFilter
/** Upper bound on target chapter pages read while migrating one title. */
private const val MAX_MIGRATION_UNIT_PAGES: Int = 200

/** [SourceCatalog] of [PluginBrowseAdapter]: legacy-source browsing, thumbnails and title migration. */
internal class PluginSourceCatalog(
    private val shared: PluginBrowseState,
    deps: PluginBrowseDependencies,
    private val core: PluginBrowseCore,
    private val publications: PluginExtensionPublicationCatalog,
) : SourceCatalog {
    private val manager = deps.manager
    private val keyValueStore = deps.keyValueStore
    private val mangaResolver = deps.mangaResolver
    private val migrationHandler = deps.migrationHandler
    private val portableRepository = deps.portableRepository
    private val exactSourceRefreshInvalidations = deps.exactSourceRefreshInvalidations
    private val defaultDispatcher = deps.defaultDispatcher

    override val sourceRefreshInvalidations: StateFlow<Map<SourceKey, Long>> =
        exactSourceRefreshInvalidations.generations

    override val state: StateFlow<BrowseSnapshot> = shared.mutableState

    override suspend fun refresh(): Unit = shared.operationMutex.withLock {
        core.refreshLocked()
    }

    override suspend fun browseSource(
        sourceId: Long,
        query: String,
        page: Int,
        filters: List<BrowseFilter>?,
    ): BrowsePage = withContext(defaultDispatcher) {
        val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
        val enabled = keyValueStore.getString(core.sourceEnabledKey(sourceId))?.toBooleanStrictOrNull() ?: true
        check(enabled) { "Source '$sourceId' is disabled" }
        val pluginPage = (page.coerceAtLeast(1) - 1)
        val result = manager.withUserInteractionContext(sourceId) {
            if (query.isBlank() && filters == null) {
                source.getPopularManga(pluginPage)
            } else {
                source.getSearchManga(
                    pluginPage,
                    query,
                    filters?.map { it.toPluginFilter() } ?: source.getFilterList(),
                )
            }
        }
        BrowsePage(
            items = mapBrowseManga(source, result.mangas),
            hasNextPage = result.hasNextPage,
        )
    }

    override suspend fun browseSourceLatest(sourceId: Long, page: Int): BrowsePage =
        withContext(defaultDispatcher) {
        val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
        val enabled = keyValueStore.getString(core.sourceEnabledKey(sourceId))?.toBooleanStrictOrNull() ?: true
        check(enabled) { "Source '$sourceId' is disabled" }
        check(source.supportsLatest) { "Source '$sourceId' does not support latest updates" }
        val result = manager.withUserInteractionContext(sourceId) {
            source.getLatestUpdates(page.coerceAtLeast(1) - 1)
        }
        BrowsePage(
            items = mapBrowseManga(source, result.mangas),
            hasNextPage = result.hasNextPage,
        )
    }

    override suspend fun browseSourceFavorites(sourceId: Long, page: Int): BrowsePage =
        withContext(defaultDispatcher) {
            val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
            val enabled = keyValueStore.getString(core.sourceEnabledKey(sourceId))?.toBooleanStrictOrNull() ?: true
            check(enabled) { "Source '$sourceId' is disabled" }
            check(source.supportsFavorites) { "Source '$sourceId' does not support favorites" }
            val result = manager.withUserInteractionContext(sourceId) {
                source.getFavoriteManga(page.coerceAtLeast(1) - 1)
            }
            BrowsePage(
                items = mapBrowseManga(source, result.mangas),
                hasNextPage = result.hasNextPage,
            )
        }

    override fun isPluginSource(sourceId: Long): Boolean =
        sourceId in shared.sourceProjections || shared.v2SourceKeyFor(sourceId) != null

    override fun isPluginSource(sourceKey: SourceKey): Boolean =
        shared.mutableState.value.sources.any { it.sourceKey == sourceKey }

    override suspend fun loadPluginThumbnail(
        sourceId: Long,
        url: String,
        headers: Map<String, String>,
    ): ByteArray? {
        val sourceKey = shared.v2SourceKeyFor(sourceId)
        if (sourceKey != null) return loadPluginThumbnail(sourceKey, url, headers)
        val source = manager.source(sourceId) ?: return null
        val metadata = PageRequestMetadata.parse(url)
        val resolved = resolveSourceHttpUrl(source.baseUrl, metadata.cleanUrl) ?: return null
        val content = manager.contentNetworkForSource(sourceId) ?: return null
        return core.fetchPluginThumbnail(
            content = content,
            sourceId = sourceId,
            url = resolved,
            headers = metadata.headers + headers,
            referer = source.headers.header("Referer") ?: source.baseUrl,
        )
    }

    override suspend fun loadPluginThumbnail(
        sourceKey: SourceKey,
        url: String,
        headers: Map<String, String>,
    ): ByteArray? {
        val live = try {
            core.fetchExtensionThumbnailV2(sourceKey, url, headers)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            core.publicationCacheV2?.readCover(sourceKey, url)?.let { return it }
            throw error
        }
        if (live == null) return core.publicationCacheV2?.readCover(sourceKey, url)
        // A library cover kept for offline use follows the source when its artwork changes.
        core.publicationCacheV2?.let { cache ->
            if (cache.hasCover(sourceKey, url)) runCatchingCancellable { cache.writeCover(sourceKey, url, live) }
        }
        return live
    }

    override suspend fun resolveManga(item: BrowseManga): Long? {
        require(item.sourceKey == null) {
            "Extension v2 publications must use the exact details/unit/content workflow"
        }
        return mangaResolver.resolve(item)
    }

    override suspend fun migrateManga(mangaId: Long, target: BrowseManga) {
        if (target.sourceKey == null) {
            migrationHandler.migrate(mangaId, target)
        } else {
            migrateExtensionPublicationV2(mangaId, target)
        }
        shared.operationMutex.withLock { core.rebuildSnapshot(errorMessage = null) }
    }

    /**
     * Moves an extension-backed library title to another exact source publication: the same row
     * (categories, tracking, notes) is re-bound, and every chapter that has a counterpart keeps
     * its read mark, page and history under the target's unit identity.
     */
    private suspend fun migrateExtensionPublicationV2(mangaId: Long, target: BrowseManga) {
        val repository = checkNotNull(portableRepository) { "Library storage is unavailable" }
        val targetKey = requireNotNull(target.sourceKey)
        val targetRemoteId = requireNotNull(target.remotePublicationId) { "The target has no publication id" }
        val before = repository.currentSnapshot
        val manga = before.mangas.firstOrNull { it.id == mangaId }
            ?: throw IllegalArgumentException("Unknown manga: $mangaId")
        val current = decodeExtensionLibraryPublicationUrl(manga.url)
        require(manga.source == dev.shinsou.kmp.domain.model.LOCAL_SOURCE_ID && current != null) {
            "Only an extension-backed title can migrate to an extension source"
        }
        require(current.sourceKey != targetKey || current.remotePublicationId != targetRemoteId) {
            "The target is the title's current source publication"
        }
        val units = mutableListOf<dev.shinsou.kmp.plugin.v2.RemoteUnitV2>()
        var page = 0
        while (true) {
            val result = publications.extensionUnitsV2(targetKey, targetRemoteId, page)
            units += result.items
            if (!result.hasNextPage || result.items.isEmpty()) break
            // An incomplete chapter list would read as "no counterpart" and drop read chapters.
            check(++page < MAX_MIGRATION_UNIT_PAGES) {
                "The target source lists more chapter pages than migration reads; nothing was changed"
            }
        }
        check(units.isNotEmpty()) { "The target source returned no chapters" }
        val details = try {
            publications.extensionDetailsV2(targetKey, targetRemoteId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Throwable) {
            // the catalogue row the user picked still names and pictures the title
            Log.warn("Plugin", logged) {
                "Fetching extension details failed; falling back to the catalogue row"
            }
            null
        }
        val latest = repository.currentSnapshot
        val plan = dev.shinsou.kmp.plugin.v2.planExtensionLibraryMigrationV2(
            oldChapters = latest.chapters.filter { it.mangaId == mangaId },
            targetSourceKey = targetKey,
            targetRemotePublicationId = targetRemoteId,
            targetUnits = units,
        )
        val targetPublication = dev.shinsou.kmp.domain.model.extensionPublicationKey(targetKey, targetRemoteId)
        check(
            latest.mangas.none { row ->
                row.id != mangaId && row.source == manga.source && (
                    row.url == plan.libraryUrl ||
                        decodeExtensionLibraryPublicationUrl(row.url)?.publicationKey == targetPublication ||
                        dev.shinsou.kmp.domain.model.decodeTypedLocalPublicationUrl(row.url) == targetPublication
                    )
            },
        ) { "The target title is already in the library" }
        repository.rebindLibraryTitle(
            mangaId = mangaId,
            expectedUrl = manga.url,
            rewrittenChapters = plan.moved,
            removedChapterIds = plan.droppedChapterIds,
            transform = { row ->
            row.copy(
                url = plan.libraryUrl,
                title = details?.title?.takeIf(String::isNotBlank) ?: target.title,
                thumbnailUrl = details?.thumbnailUrl ?: target.thumbnailUrl ?: row.thumbnailUrl,
                author = details?.author ?: target.author,
                artist = details?.artist ?: target.artist,
                description = details?.description ?: target.description,
                genre = details?.genre ?: target.genre,
            )
            },
        )
    }

    private fun mapBrowseManga(source: CatalogueSource, mangas: List<SManga>): List<BrowseManga> {
        val mapped = ArrayList<BrowseManga>(mangas.size)
        for (manga in mangas) mapped += manga.toBrowseManga(source)
        return mapped
    }

    private fun SManga.toBrowseManga(source: CatalogueSource): BrowseManga {
        val resolvedThumbnail = thumbnailUrl?.let { resolveSourceAsset(source.baseUrl, it) }
        return BrowseManga(
            sourceId = source.id,
            url = url,
            title = title,
            // Keep the source URL inert. The UI resolves it only through loadPluginThumbnail,
            // whose exact-source content policy strips cookies, authorization and proxy secrets.
            thumbnailUrl = resolvedThumbnail,
            thumbnailHeaders = emptyMap(),
            author = author ?: artist,
        )
    }

    private fun resolveSourceAsset(baseUrl: String, value: String): String = when {
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("//") -> baseUrl.substringBefore(':') + ":" + value
        value.startsWith('/') -> {
            val scheme = baseUrl.substringBefore("://", "https")
            val authority = baseUrl.substringAfter("://", baseUrl).substringBefore('/')
            "$scheme://$authority$value"
        }
        else -> baseUrl.trimEnd('/') + "/" + value.trimStart('/')
    }
}
