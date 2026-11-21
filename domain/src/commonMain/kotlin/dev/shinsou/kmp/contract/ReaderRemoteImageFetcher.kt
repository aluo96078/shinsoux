package dev.shinsou.kmp.contract

import dev.shinsou.kmp.domain.model.SourceKey

/**
 * Host-issued authority to load the remote images inline in one source's reader content.
 *
 * Only the host creates implementations; each is bound to the exact [sourceKey] it was issued for
 * and fetches through that source's credential-free content network policy.
 */
public interface ReaderRemoteImageFetcher {
    /** The source this authority was issued for. */
    public val sourceKey: SourceKey

    /**
     * The body of [url] when it is safe to hand to a raster image decoder, or null when the
     * request was refused or failed or the response is not a decodable image. Cancellation of the
     * calling coroutine propagates.
     */
    public suspend fun fetchImage(url: String): ByteArray?
}
