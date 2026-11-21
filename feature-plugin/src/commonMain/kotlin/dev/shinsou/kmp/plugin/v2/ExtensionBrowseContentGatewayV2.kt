package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.SourceKey

/** Exact source lookup boundary used by the production browse callbacks and focused tests. */
public fun interface ExtensionSourceResolverV2 {
    public suspend fun resolve(sourceKey: SourceKey): HostExtensionSourceV2?
}

/**
 * Production browse/detail/content route for extension v2.
 *
 * This gateway never receives an implementation SPI and never projects an opaque source id to a
 * Long. Every operation resolves a [HostExtensionSourceV2], so capability, input and output gates
 * run on the real application path. Content results are returned intact, including sibling
 * representations of the same unit.
 */
public class ExtensionBrowseContentGatewayV2(
    private val sources: ExtensionSourceResolverV2,
) {
    public suspend fun source(sourceKey: SourceKey): HostExtensionSourceV2? = sources.resolve(sourceKey)

    public suspend fun search(
        sourceKey: SourceKey,
        query: String,
        page: Int = 0,
        options: BrowseOptionsV2 = BrowseOptionsV2(),
    ): PagedResultV2<RemotePublicationV2> = requireSource(sourceKey).search(query, page, options)

    public suspend fun latest(
        sourceKey: SourceKey,
        page: Int = 0,
    ): PagedResultV2<RemotePublicationV2> = requireSource(sourceKey).latest(page)

    public suspend fun browse(
        sourceKey: SourceKey,
        options: BrowseOptionsV2 = BrowseOptionsV2(),
        page: Int = 0,
    ): PagedResultV2<RemotePublicationV2> = requireSource(sourceKey).browse(options, page)

    public suspend fun details(
        sourceKey: SourceKey,
        remotePublicationId: String,
    ): RemotePublicationV2 = requireSource(sourceKey).details(remotePublicationId)

    public suspend fun favorite(
        sourceKey: SourceKey,
        remotePublicationId: String,
        favorite: Boolean,
    ): Unit = requireSource(sourceKey).favorite(remotePublicationId, favorite)

    public suspend fun units(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int = 0,
    ): PagedResultV2<RemoteUnitV2> = requireSource(sourceKey).units(remotePublicationId, page)

    public suspend fun content(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitId: String,
    ): UnitContentResultV2 = requireSource(sourceKey).content(remotePublicationId, remoteUnitId)

    private suspend fun requireSource(sourceKey: SourceKey): HostExtensionSourceV2 =
        requireNotNull(sources.resolve(sourceKey)) {
            "Unknown extension v2 source: ${sourceKey.canonicalId}"
        }
}
