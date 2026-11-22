package dev.shinsou.kmp.plugin.browse

import dev.shinsou.kmp.contract.ContentCallbacks
import dev.shinsou.kmp.contract.ReaderChapter
import dev.shinsou.kmp.contract.ReaderPage
import dev.shinsou.kmp.plugin.contracts.PageRequestMetadata
import dev.shinsou.kmp.plugin.contracts.SChapter
import dev.shinsou.kmp.plugin.manager.PluginManager
import dev.shinsou.kmp.plugin.network.PluginHttpRequest
import dev.shinsou.kmp.plugin.network.PluginRequestBuilder
import dev.shinsou.kmp.plugin.network.imageBodyForDecoderOrNull
import dev.shinsou.kmp.plugin.network.normalizedPluginMediaType

public data class PluginReaderChapterReference(
    val sourceId: Long,
    val chapter: SChapter,
)

internal fun interface PluginReaderChapterResolver {
    public suspend fun resolve(mangaId: Long, chapterId: Long): PluginReaderChapterReference?
}

/** Builds reader requests through the same request builder used by the JS bridge and downloader. */
internal class PluginContentAdapter(
    private val manager: PluginManager,
    private val chapterResolver: PluginReaderChapterResolver,
    private val requestBuilder: PluginRequestBuilder,
    private val delegate: ContentCallbacks = ContentCallbacks.None,
) : ContentCallbacks {
    override suspend fun refreshLibrary(mangaIds: Set<Long>) = delegate.refreshLibrary(mangaIds)
    override suspend fun refreshManga(mangaId: Long) = delegate.refreshManga(mangaId)
    override suspend fun resolveMangaOriginalUrl(mangaId: Long): String? =
        delegate.resolveMangaOriginalUrl(mangaId)
    override suspend fun resolveChapterOriginalUrl(mangaId: Long, chapterId: Long): String? =
        delegate.resolveChapterOriginalUrl(mangaId, chapterId)
    override suspend fun enqueueDownload(mangaId: Long, chapterId: Long) =
        delegate.enqueueDownload(mangaId, chapterId)
    override suspend fun retryDownload(itemId: String) = delegate.retryDownload(itemId)
    override suspend fun removeDownload(itemId: String) = delegate.removeDownload(itemId)
    override suspend fun removeUnfinishedDownload(itemId: String) = delegate.removeUnfinishedDownload(itemId)
    override suspend fun reorderDownloads(orderedIds: List<String>) = delegate.reorderDownloads(orderedIds)
    override suspend fun clearCompletedDownloads() = delegate.clearCompletedDownloads()
    override suspend fun pauseDownloads(paused: Boolean) = delegate.pauseDownloads(paused)

    override suspend fun loadReaderChapter(mangaId: Long, chapterId: Long): ReaderChapter {
        val reference = chapterResolver.resolve(mangaId, chapterId)
            ?: return delegate.loadReaderChapter(mangaId, chapterId)
        val source = manager.source(reference.sourceId)
            ?: throw IllegalStateException("Reader source '${reference.sourceId}' is not loaded")
        val referer = source.headers.header("Referer") ?: reference.chapter.url.takeIf { it.isNotBlank() }
        val contentNetwork = requireNotNull(manager.contentNetworkForSource(source.id)) {
            "No content network policy is registered for source ${source.id}"
        }
        val pages = source.getPageList(reference.chapter).mapIndexed { fallbackIndex, page ->
            ReaderPage(
                index = page.index.takeIf { it >= 0 } ?: fallbackIndex,
                // Compatibility readers remain lazy, but the resolver now returns bytes acquired
                // through the same credential-free, origin-pinned content plane as production.
                imageResolver = {
                    // Viewer-backed sources may perform an authenticated network request while
                    // resolving the final image URL. Keep that work inside the page resolver so
                    // opening a chapter does not eagerly fetch every viewer page (or fail the
                    // entire chapter because one later page is temporarily unavailable).
                    val remoteImage = page.imageUrl
                        ?.takeIf(String::isNotBlank)
                        ?: source.resolveImageUrl(page.url)?.takeIf(String::isNotBlank)
                        ?: page.url
                    val metadata = PageRequestMetadata.parse(remoteImage)
                    val resolvedUrl = requireNotNull(resolveSourceHttpUrl(source.baseUrl, metadata.cleanUrl)) {
                        "Reader page URL is not a safe HTTP(S) source URL"
                    }
                    val requestHeaders = metadata.headers.filterKeys(::isSafeCompatibilityContentHeader)
                    val response = contentNetwork.execute(
                        sourceId = source.id,
                        request = PluginHttpRequest(
                            method = "GET",
                            url = resolvedUrl,
                            headers = requestHeaders.withFallbackHeader("Referer", referer),
                        ),
                    )
                    check(response.status in 200..299) {
                        "Unable to load reader page: HTTP ${response.status}"
                    }
                    require(response.body.isNotEmpty()) { "Reader page returned no data" }
                    val contentType = response.normalizedPluginMediaType()
                    val imageBytes = requireNotNull(response.imageBodyForDecoderOrNull()) {
                        "Reader page returned non-image content '$contentType'"
                    }
                    ReaderPage(
                        index = page.index.takeIf { it >= 0 } ?: fallbackIndex,
                        imageBytes = imageBytes,
                        imageTransform = metadata.imageTransform(source),
                    )
                },
            )
        }
        validateReaderPageIndexes(pages)
        return ReaderChapter(pages = pages, referer = referer, sourceHeaders = source.headers)
    }
}

private fun isSafeCompatibilityContentHeader(name: String): Boolean = when (name.trim().lowercase()) {
    "accept", "accept-language", "cache-control", "if-modified-since", "if-none-match", "range",
    // Komiic issues a short-lived ticket in the page URL fragment. It is safe only as a
    // credential-free content-plane hint; PluginContentNetworkClient strips it from every
    // cross-origin redirect and never combines it with cookies or authorization.
    "referer", "user-agent", "x-image-ticket" -> true
    else -> false
}

private fun mapOfNotNullHeader(name: String, value: String?): Map<String, String> =
    value?.takeIf(String::isNotBlank)?.let { mapOf(name to it) }.orEmpty()

private fun Map<String, String>.withFallbackHeader(name: String, value: String?): Map<String, String> =
    if (keys.any { it.equals(name, ignoreCase = true) }) this else this + mapOfNotNullHeader(name, value)

internal fun Map<String, String>.header(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
