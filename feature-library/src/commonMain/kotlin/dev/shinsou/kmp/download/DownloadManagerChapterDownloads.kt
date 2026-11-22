package dev.shinsou.kmp.download

import dev.shinsou.kmp.contract.ChapterDownloads
import dev.shinsou.kmp.contract.ReaderChapter
import dev.shinsou.kmp.contract.ReaderPage
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.ReaderImageTransform
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.network.encodedImageHeight

/** [DownloadManager] and the chapters it finished, as the source bridge uses them. */
public class DownloadManagerChapterDownloads(
    private val manager: DownloadManager,
    private val repository: ShinsouRepository,
    private val fileSystem: AppFileSystem,
) : ChapterDownloads {
    override suspend fun retry(itemId: String) {
        manager.retry(itemId)
    }

    override suspend fun enqueue(mangaId: Long, chapterId: Long) {
        manager.enqueue(mangaId, chapterId)
    }

    override suspend fun remove(itemId: String) {
        manager.remove(itemId)
    }

    override suspend fun removeUnfinished(itemId: String) {
        manager.removeUnfinished(itemId)
    }

    override suspend fun pauseAll(paused: Boolean) {
        manager.pauseAll(paused)
    }

    override suspend fun downloadedChapter(mangaId: Long, chapterId: Long): ReaderChapter {
        val queueCompletion = repository.currentSnapshot.downloadQueue.firstOrNull {
            it.mangaId == mangaId &&
                it.chapterId == chapterId &&
                it.state == DownloadState.DOWNLOADED &&
                it.totalPages > 0 &&
                it.downloadedPages == it.totalPages
        }
        val completed = DownloadCompletionManifests.readValid(fileSystem, mangaId, chapterId)
            ?: queueCompletion?.let {
                DownloadCompletionManifests.readValidOrMigrateLegacy(
                    fileSystem,
                    mangaId,
                    chapterId,
                    it.totalPages,
                )
            }
        if (completed == null) {
            // Marked complete but its bytes do not verify: read online now, download it again.
            manager.requeueBrokenDownload(mangaId, chapterId)
            return ReaderChapter()
        }
        return ReaderChapter(pages = readerPages("downloads/$mangaId/$chapterId", completed).orEmpty())
    }

    /** The pages of [completed] in [directory]; null when a page's image transform cannot be read. */
    private suspend fun readerPages(directory: String, completed: DownloadCompletionManifest): List<ReaderPage>? {
        val pages = mutableListOf<ReaderPage>()
        for (completedPage in completed.pages.sortedBy { it.index }) {
            val path = "$directory/${completedPage.fileName}"
            val sidecar = completedPage.transformFileName?.let { fileSystem.read("$directory/$it") }
            val imageTransform = sidecar?.let { ReaderImageTransform.decodeSidecar(it) }
            if (completedPage.transformFileName != null && imageTransform == null) return null
            pages += ReaderPage(
                index = completedPage.index,
                imageUrl = fileSystem.uri(path),
                local = true,
                imageTransform = imageTransform,
                // Header only: the bytes are dropped right away and never decoded to pixels.
                imageNaturalHeight = imageTransform?.let { fileSystem.read(path)?.let(::encodedImageHeight) },
            )
        }
        return pages
    }
}
