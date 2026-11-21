package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.CachedExtensionPublicationV2
import dev.shinsou.kmp.plugin.v2.withoutScriptObjectText
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationPageV2
import dev.shinsou.kmp.plugin.v2.HostExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.PagedResultV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import kotlin.time.Clock
import dev.shinsou.kmp.plugin.v2.UnitContentResultV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
/** [ExtensionPublicationCatalog] of [PluginBrowseAdapter]: the exact v2 browse/detail/unit/content routes. */
internal class PluginExtensionPublicationCatalog(
    deps: PluginBrowseDependencies,
    private val core: PluginBrowseCore,
) : ExtensionPublicationCatalog {
    private val manager = deps.manager
    private val extensionContentConsumerV2 = deps.extensionContentConsumerV2
    private val defaultDispatcher = deps.defaultDispatcher

    override suspend fun extensionSourceV2(sourceKey: SourceKey): HostExtensionSourceV2? =
        core.extensionGatewayV2.source(sourceKey)

    override suspend fun browseSourceV2(
        sourceKey: SourceKey,
        options: BrowseOptionsV2,
        page: Int,
    ): PagedResultV2<RemotePublicationV2> = withContext(defaultDispatcher) {
        manager.withUserInteractionContext(sourceKey) { core.extensionGatewayV2.browse(sourceKey, options, page) }
    }

    override suspend fun searchSourceV2(
        sourceKey: SourceKey,
        query: String,
        page: Int,
        options: BrowseOptionsV2,
    ): PagedResultV2<RemotePublicationV2> = withContext(defaultDispatcher) {
        manager.withUserInteractionContext(sourceKey) {
            core.extensionGatewayV2.search(sourceKey, query, page, options)
        }
    }

    override suspend fun latestSourceV2(
        sourceKey: SourceKey,
        page: Int,
    ): PagedResultV2<RemotePublicationV2> = withContext(defaultDispatcher) {
        manager.withUserInteractionContext(sourceKey) { core.extensionGatewayV2.latest(sourceKey, page) }
    }

    override suspend fun extensionDetailsV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
    ): RemotePublicationV2 = withContext(defaultDispatcher) {
        // Always the source's answer: the detail screen shows (and keeps) the cached copy itself,
        // and must be able to tell fresh details from cached ones before saving them.
        manager.withUserInteractionContext(sourceKey) {
            manager.withVisibleEventContext(sourceKey, remotePublicationId) {
                core.extensionGatewayV2.details(sourceKey, remotePublicationId)
            }
        }.withoutScriptObjectText()
    }

    override suspend fun storedExtensionPublicationPageV2(
        sourceKey: SourceKey,
        publication: RemotePublicationV2,
    ): ExtensionPublicationPageV2? = withContext(defaultDispatcher) {
        extensionContentConsumerV2?.storedPublicationPage(sourceKey, publication)
    }

    override suspend fun cachedExtensionPublicationPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
    ): ExtensionPublicationPageV2? = withContext(defaultDispatcher) {
        val consumer = extensionContentConsumerV2 ?: return@withContext null
        core.publicationCacheV2?.read(sourceKey, remotePublicationId)?.let { consumer.cachedPublicationPage(sourceKey, it) }
    }

    override suspend fun cacheExtensionPublicationV2(
        sourceKey: SourceKey,
        publication: RemotePublicationV2,
        units: List<RemoteUnitV2>,
    ) {
        val cache = core.publicationCacheV2 ?: return
        withContext(defaultDispatcher) {
            cache.write(
                sourceKey,
                CachedExtensionPublicationV2(
                    sourceCanonicalId = sourceKey.canonicalId,
                    publication = publication,
                    units = units,
                    updatedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                ),
            )
            // Keep the cover too: offline the library would otherwise show a blank placeholder.
            val cover = publication.thumbnailUrl?.takeIf(String::isNotBlank) ?: return@withContext
            if (!cache.hasCover(sourceKey, cover)) {
                runCatchingCancellable { core.fetchExtensionThumbnailV2(sourceKey, cover, emptyMap()) }
                    .getOrNull()
                    ?.let { cache.writeCover(sourceKey, cover, it) }
            }
        }
    }

    override suspend fun forgetCachedExtensionPublicationV2(sourceKey: SourceKey, remotePublicationId: String) {
        val cache = core.publicationCacheV2 ?: return
        withContext(defaultDispatcher) { cache.delete(sourceKey, remotePublicationId) }
    }

    override suspend fun extensionUnitsV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int,
    ): PagedResultV2<RemoteUnitV2> = withContext(defaultDispatcher) {
        manager.withUserInteractionContext(sourceKey) {
            manager.withVisibleEventContext(sourceKey, remotePublicationId) {
                core.extensionGatewayV2.units(sourceKey, remotePublicationId, page)
            }
        }
    }

    override suspend fun extensionContentV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitId: String,
    ): UnitContentResultV2 = withContext(defaultDispatcher) {
        manager.withUserInteractionContext(sourceKey) {
            manager.withVisibleEventContext(sourceKey, remotePublicationId, remoteUnitId) {
                core.extensionGatewayV2.content(sourceKey, remotePublicationId, remoteUnitId)
            }
        }
    }

    override suspend fun extensionPublicationPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int,
    ): ExtensionPublicationPageV2 = withContext(defaultDispatcher) {
        core.ensureInstalledLoaded()
        requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.let { consumer ->
            manager.withUserInteractionContext(sourceKey) {
                consumer.publicationPage(sourceKey, remotePublicationId, page)
            }
        }
    }

    override suspend fun extensionPublicationUnitsPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        publication: RemotePublicationV2,
        page: Int,
    ): ExtensionPublicationPageV2 = withContext(defaultDispatcher) {
        core.ensureInstalledLoaded()
        requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.let { consumer ->
            // Offline (or a source that comes back empty), the first page falls back to the
            // library cache together with the stored chapters it may not list yet, so downloaded
            // content stays reachable.
            val stored: suspend () -> ExtensionPublicationPageV2? = {
                if (page != 0) {
                    null
                } else {
                    consumer.offlinePublicationPage(sourceKey, core.publicationCacheV2?.read(sourceKey, remotePublicationId), publication)
                }
            }
            val remote = try {
                manager.withUserInteractionContext(sourceKey) {
                    consumer.publicationUnitsPage(sourceKey, remotePublicationId, publication, page)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return@let stored() ?: throw error
            }
            if (remote.units.isEmpty()) stored() ?: remote else remote
        }
    }
}
