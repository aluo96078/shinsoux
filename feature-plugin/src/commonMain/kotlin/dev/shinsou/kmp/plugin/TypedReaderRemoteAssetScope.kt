package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.contract.ReaderRemoteImageFetcher
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.logging.Log
import kotlin.coroutines.cancellation.CancellationException

/**
 * Exact host-issued authority retained only while rendering remote extension content.
 *
 * The constructor is host-internal and this is deliberately not a data class: UI and extension
 * code may consume a scope, but cannot manufacture one through a generated `copy` function.
 */
public class TypedReaderRemoteAssetScope internal constructor(
    override val sourceKey: SourceKey,
    public val sourceId: Long,
    public val network: PluginContentNetworkClient,
) : ReaderRemoteImageFetcher {
    init {
        sourceKey.validate()
    }

    /**
     * GETs [url] through this source's content network policy and keeps the body only when
     * [imageBodyForDecoderOrNull] accepts it. A refused or failed request is logged and yields
     * null; cancellation propagates.
     */
    override suspend fun fetchImage(url: String): ByteArray? =
        try {
            network.execute(
                sourceId = sourceId,
                request = PluginHttpRequest("GET", url),
            ).imageBodyForDecoderOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Throwable) {
            Log.warn("Reader", logged) { "Loading a novel image failed" }
            null
        }
}
