package dev.shinsou.kmp.contract

import dev.shinsou.kmp.domain.model.ReaderImageTransform

// The download feature and the source bridge, each through what it needs from the other.

/** One page of a chapter to download, as the source resolved it. */
public data class DownloadPage(
    val index: Int,
    val url: String,
    val sourceId: Long = -1,
    val headers: Map<String, String> = emptyMap(),
    val imageTransform: ReaderImageTransform? = null,
)

public data class DownloadedPage(
    val bytes: ByteArray,
    val contentType: String? = null,
)

/** The pages of a chapter to download; the source bridge supplies them. */
public fun interface ChapterPageProvider {
    public suspend fun pages(mangaId: Long, chapterId: Long): List<DownloadPage>
}

/** Fetches one page's bytes through the source's content network. */
public fun interface DownloadPageFetcher {
    public suspend fun fetch(page: DownloadPage): DownloadedPage
}

/** What the source bridge needs from the download feature: the queue and finished chapters. */
public interface ChapterDownloads {
    public suspend fun retry(itemId: String)

    public suspend fun enqueue(mangaId: Long, chapterId: Long)

    public suspend fun remove(itemId: String)

    public suspend fun removeUnfinished(itemId: String)

    public suspend fun pauseAll(paused: Boolean)

    /**
     * The pages of the chapter's finished download, read from the device; no pages when it has
     * none or its bytes do not verify (such a download is queued again).
     */
    public suspend fun downloadedChapter(mangaId: Long, chapterId: Long): ReaderChapter
}
