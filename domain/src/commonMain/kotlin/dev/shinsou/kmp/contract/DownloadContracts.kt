package dev.shinsou.kmp.contract

import dev.shinsou.kmp.domain.model.ReaderImageTransform

// The download feature and the source bridge, each through what it needs from the other.

/** One page of a chapter to download, as the source resolved it. */
data class DownloadPage(
    val index: Int,
    val url: String,
    val sourceId: Long = -1,
    val headers: Map<String, String> = emptyMap(),
    val imageTransform: ReaderImageTransform? = null,
)

data class DownloadedPage(
    val bytes: ByteArray,
    val contentType: String? = null,
)

/** The pages of a chapter to download; the source bridge supplies them. */
fun interface ChapterPageProvider {
    suspend fun pages(mangaId: Long, chapterId: Long): List<DownloadPage>
}

/** Fetches one page's bytes through the source's content network. */
fun interface DownloadPageFetcher {
    suspend fun fetch(page: DownloadPage): DownloadedPage
}

/** What the source bridge needs from the download feature: the queue and finished chapters. */
interface ChapterDownloads {
    suspend fun retry(itemId: String)

    suspend fun enqueue(mangaId: Long, chapterId: Long)

    suspend fun remove(itemId: String)

    suspend fun removeUnfinished(itemId: String)

    suspend fun pauseAll(paused: Boolean)

    /**
     * The pages of the chapter's finished download, read from the device; no pages when it has
     * none or its bytes do not verify (such a download is queued again).
     */
    suspend fun downloadedChapter(mangaId: Long, chapterId: Long): ReaderChapter
}
