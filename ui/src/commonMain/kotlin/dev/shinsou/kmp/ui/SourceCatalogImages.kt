package dev.shinsou.kmp.ui

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.BrowseSnapshot
import dev.shinsou.kmp.plugin.SourceCatalog
import dev.shinsou.kmp.ui.core.PluginImageSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The browse catalogue as the plugin image loader of :ui-core sees it (0008): the authority is
 * what in the browse snapshot decides whether a source may still serve images, and the images
 * come through the catalogue's host content plane.
 */
internal class SourceCatalogImages(private val catalog: SourceCatalog) : PluginImageSource {
    // Last snapshot -> token. A racing recomputation is harmless: the token is a pure function.
    @kotlin.concurrent.Volatile
    private var authorityMemo: Pair<BrowseSnapshot, Any>? = null

    override val authority: Flow<Any?> = catalog.state.map(::authorityOf).distinctUntilChanged()

    override fun currentAuthority(): Any? = authorityOf(catalog.state.value)

    override suspend fun loadThumbnail(sourceId: Long, url: String, headers: Map<String, String>): ByteArray? =
        catalog.loadPluginThumbnail(sourceId, url, headers)

    override suspend fun loadThumbnail(sourceKey: SourceKey, url: String, headers: Map<String, String>): ByteArray? =
        catalog.loadPluginThumbnail(sourceKey, url, headers)

    private fun authorityOf(snapshot: BrowseSnapshot): Any {
        authorityMemo?.let { (memoSnapshot, token) -> if (memoSnapshot === snapshot) return token }
        return pluginImageAuthority(snapshot).also { authorityMemo = snapshot to it }
    }
}

/** Everything in the browse snapshot that decides whether a source may still serve images. */
private fun pluginImageAuthority(snapshot: BrowseSnapshot): Any = listOf(
    // Disabling a source withdraws its covers as well, even though the host could still fetch them.
    snapshot.sources.mapTo(HashSet()) { listOf(it.identityKey, it.id, it.enabled) },
    snapshot.extensions.mapTo(HashSet()) {
        listOf(it.id, it.version, it.installed, it.trusted, it.sourceIds)
    },
)
