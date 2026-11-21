package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.withContext
/** [ExtensionLibraryBinding] of [PluginBrowseAdapter]. */
internal class PluginExtensionLibraryBinding(
    deps: PluginBrowseDependencies,
    private val core: PluginBrowseCore,
) : ExtensionLibraryBinding {
    private val manager = deps.manager
    private val extensionContentConsumerV2 = deps.extensionContentConsumerV2
    private val defaultDispatcher = deps.defaultDispatcher

    override fun extensionLibraryBindingV2(
        publicationKey: dev.shinsou.kmp.domain.model.PublicationKey,
    ): dev.shinsou.kmp.domain.model.ExtensionLibraryBindingV2? =
        extensionContentConsumerV2?.extensionLibraryBinding(publicationKey)

    override fun extensionLocalReadingStateV2(
        publicationKey: dev.shinsou.kmp.domain.model.PublicationKey,
    ): dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2 =
        extensionContentConsumerV2?.localReadingState(publicationKey)
            ?: dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2()

    override fun extensionLocalReadingStateV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitIds: Collection<String>,
    ): dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2 =
        extensionContentConsumerV2?.localReadingState(sourceKey, remotePublicationId, remoteUnitIds)
            ?: dev.shinsou.kmp.plugin.v2.ExtensionLocalReadingStateV2()

    override fun extensionDownloadedUnitIdsV2(
        publicationKey: dev.shinsou.kmp.domain.model.PublicationKey,
    ): Set<String> = extensionContentConsumerV2?.downloadedRemoteUnitIds(publicationKey).orEmpty()

    override suspend fun favoriteExtensionPublicationV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        favorite: Boolean,
    ): Unit = withContext(defaultDispatcher) {
        manager.withUserInteractionContext(sourceKey) {
            // A favorite mutation is still a visible publication invocation.  Keeping the
            // publication identity bound here is important for plugins that emit an
            // ACTIVE_CONTEXT refresh (and also keeps the event context consistent with details,
            // units, and content requests).
            manager.withVisibleEventContext(sourceKey, remotePublicationId) {
                core.extensionGatewayV2.favorite(sourceKey, remotePublicationId, favorite)
            }
        }
    }
}
