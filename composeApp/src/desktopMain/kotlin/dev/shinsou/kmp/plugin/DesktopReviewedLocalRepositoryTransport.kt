package dev.shinsou.kmp.plugin

internal class DesktopReviewedLocalRepositoryTransport : PluginHttpTransport, AutoCloseable {
    private val delegate = JvmReviewedLocalRepositoryTransport(ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081)
    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = delegate.execute(request)
    override fun close() = delegate.close()
    internal companion object {
        internal fun isAllowedRequestUrl(url: String): Boolean =
            ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081.admitsRequestUrl(url)
    }
}

internal fun isReviewedLocalRepositoryEnabled(value: String?): Boolean = value == "true"
