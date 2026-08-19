package dev.shinsou.kmp.download

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.reader.ReaderImageTransform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

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

fun interface ChapterPageProvider {
    suspend fun pages(mangaId: Long, chapterId: Long): List<DownloadPage>
}

fun interface DownloadPageFetcher {
    suspend fun fetch(page: DownloadPage): DownloadedPage
}

/** Persistent, resumable chapter queue shared by all targets. */
class DownloadManager(
    private val repository: ShinsouRepository,
    private val fileSystem: AppFileSystem,
    private val pageProvider: ChapterPageProvider,
    private val pageFetcher: DownloadPageFetcher,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Default)
    private val schedulerMutex = Mutex()
    private val progressMutex = Mutex()
    private val active = linkedMapOf<String, Job>()
    private var globallyPaused = false

    suspend fun restoreQueue() {
        repository.currentSnapshot.downloadQueue.forEach { item ->
            val completed = if (
                item.state == DownloadState.DOWNLOADED &&
                item.totalPages > 0 &&
                item.downloadedPages == item.totalPages
            ) {
                DownloadCompletionManifests.readValidOrMigrateLegacy(
                    fileSystem,
                    item.mangaId,
                    item.chapterId,
                    item.totalPages,
                )
            } else {
                DownloadCompletionManifests.readValid(fileSystem, item.mangaId, item.chapterId)
            }
            when {
                completed != null && item.state != DownloadState.DOWNLOADED -> {
                    repository.setDownloadState(
                        item.id,
                        DownloadState.DOWNLOADED,
                        progress = 1.0,
                        downloadedPages = completed.pageCount,
                        totalPages = completed.pageCount,
                        updatedAt = now(),
                    )
                }
                item.state == DownloadState.DOWNLOADING -> {
                    repository.setDownloadState(item.id, DownloadState.QUEUED, updatedAt = now())
                }
            }
        }
        schedule()
    }

    suspend fun enqueue(mangaId: Long, chapterId: Long): DownloadQueueItem {
        val item = repository.enqueueDownload(mangaId, chapterId, queuedAt = now())
        if (item.state == DownloadState.ERROR || item.state == DownloadState.PAUSED) {
            repository.setDownloadState(item.id, DownloadState.QUEUED, updatedAt = now())
        }
        schedule()
        return repository.currentSnapshot.downloadQueue.first { it.id == item.id }
    }

    suspend fun retry(itemId: String) {
        repository.setDownloadState(
            itemId,
            DownloadState.QUEUED,
            progress = 0.0,
            downloadedPages = 0,
            totalPages = 0,
            updatedAt = now(),
        )
        schedule()
    }

    suspend fun pause(itemId: String) {
        schedulerMutex.withLock { active.remove(itemId) }?.cancelAndJoin()
        repository.setDownloadState(itemId, DownloadState.PAUSED, updatedAt = now())
        schedule()
    }

    suspend fun resume(itemId: String) {
        repository.setDownloadState(itemId, DownloadState.QUEUED, updatedAt = now())
        schedule()
    }

    suspend fun pauseAll(paused: Boolean) {
        val running = schedulerMutex.withLock {
            globallyPaused = paused
            if (paused) active.values.toList().also { active.clear() } else emptyList()
        }
        if (paused) {
            running.forEach { it.cancelAndJoin() }
            repository.currentSnapshot.downloadQueue
                .filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED }
                .forEach { item ->
                    repository.setDownloadState(item.id, DownloadState.PAUSED, updatedAt = now())
            }
        } else {
            repository.currentSnapshot.downloadQueue
                .filter { it.state == DownloadState.PAUSED }
                .forEach { repository.setDownloadState(it.id, DownloadState.QUEUED, updatedAt = now()) }
            schedule()
        }
    }

    suspend fun remove(itemId: String) {
        schedulerMutex.withLock { active.remove(itemId) }?.cancelAndJoin()
        val item = repository.currentSnapshot.downloadQueue.firstOrNull { it.id == itemId } ?: return
        fileSystem.deleteTree(directory(item.mangaId, item.chapterId))
        repository.removeDownload(itemId)
        schedule()
    }

    suspend fun downloadedPages(mangaId: Long, chapterId: Long): List<String> =
        DownloadCompletionManifests.readValid(fileSystem, mangaId, chapterId)
            ?.pages
            ?.sortedBy { it.index }
            ?.map { fileSystem.uri("${directory(mangaId, chapterId)}/${it.fileName}") }
            .orEmpty()

    /**
     * Suspends until every currently queued download has reached a terminal or paused state.
     * This is also useful for platform shutdown and deterministic background-work tests.
     */
    suspend fun awaitIdle() {
        while (true) {
            val jobs = schedulerMutex.withLock { active.values.toList() }
            if (jobs.isNotEmpty()) {
                jobs.joinAll()
                continue
            }

            val hasQueuedWork = repository.currentSnapshot.downloadQueue.any {
                it.state == DownloadState.QUEUED
            }
            if (globallyPaused || !hasQueuedWork) return
            schedule()
        }
    }

    suspend fun close() = scopeJob.cancelAndJoin()

    private suspend fun schedule(): Unit {
        schedulerMutex.withLock {
            if (globallyPaused) return
            val limit = repository.currentSnapshot.settings.downloads.parallelDownloads.coerceIn(1, 10)
            val available = limit - active.size
            if (available <= 0) return
            val candidates = repository.currentSnapshot.downloadQueue
                .asSequence()
                .filter { it.state == DownloadState.QUEUED && it.id !in active }
                .sortedBy { it.position }
                .take(available)
                .toList()
            candidates.forEach { item ->
                active[item.id] = scope.launch {
                    try {
                        download(item)
                    } catch (_: CancellationException) {
                        throw CancellationException()
                    } catch (error: Throwable) {
                        repository.setDownloadState(
                            item.id,
                            DownloadState.ERROR,
                            updatedAt = now(),
                            errorMessage = error.message ?: "Download failed",
                        )
                    } finally {
                        schedulerMutex.withLock { active.remove(item.id) }
                        schedule()
                    }
                }
            }
        }
    }

    private suspend fun download(item: DownloadQueueItem) {
        repository.setDownloadState(item.id, DownloadState.DOWNLOADING, updatedAt = now())
        val pages = pageProvider.pages(item.mangaId, item.chapterId).sortedBy { it.index }
        require(pages.isNotEmpty()) { "Chapter returned no pages" }
        require(pages.map { it.index }.toSet().size == pages.size) { "Chapter returned duplicate page indexes" }
        require(pages.all { it.index >= 0 }) { "Chapter returned a negative page index" }
        // Downloads restart from a clean directory. Otherwise a failed older attempt can leave
        // enough page files for the reader to mistake a partial or changed chapter for complete.
        fileSystem.deleteTree(directory(item.mangaId, item.chapterId))
        repository.setDownloadState(
            item.id,
            DownloadState.DOWNLOADING,
            totalPages = pages.size,
            downloadedPages = 0,
            progress = 0.0,
            updatedAt = now(),
        )

        var completed = 0
        val completedPages = mutableListOf<DownloadCompletionPage>()
        val semaphore = Semaphore(
            repository.currentSnapshot.settings.downloads.parallelPages.coerceIn(1, 15),
        )
        coroutineScope {
            pages.map { page ->
                async {
                semaphore.withPermit {
                    val downloaded = pageFetcher.fetch(page)
                    require(downloaded.bytes.isNotEmpty()) { "Page ${page.index + 1} returned no data" }
                    val pageStem = "${directory(item.mangaId, item.chapterId)}/page-${page.index}"
                    val path = "$pageStem.${extension(downloaded.contentType, page.url)}"
                    val sidecarPath = "$pageStem.transform"
                    // Image decoding/encoding is platform-specific. Persist the deterministic
                    // operation beside raw bytes so offline Coil applies exactly the online path.
                    page.imageTransform?.let { fileSystem.write(sidecarPath, it.encodeSidecar()) }
                        ?: fileSystem.delete(sidecarPath)
                    fileSystem.write(path, downloaded.bytes)
                    progressMutex.withLock {
                        completedPages += DownloadCompletionPage(
                            index = page.index,
                            fileName = path.substringAfterLast('/'),
                            transformFileName = page.imageTransform?.let { sidecarPath.substringAfterLast('/') },
                        )
                        completed += 1
                        repository.setDownloadState(
                            item.id,
                            DownloadState.DOWNLOADING,
                            progress = completed.toDouble() / pages.size,
                            downloadedPages = completed,
                            totalPages = pages.size,
                            updatedAt = now(),
                        )
                    }
                }
                }
            }.awaitAll()
        }

        DownloadCompletionManifests.publish(
            fileSystem = fileSystem,
            mangaId = item.mangaId,
            chapterId = item.chapterId,
            pages = completedPages,
        )
        repository.setDownloadState(
            item.id,
            DownloadState.DOWNLOADED,
            progress = 1.0,
            downloadedPages = pages.size,
            totalPages = pages.size,
            updatedAt = now(),
        )
    }

    private fun directory(mangaId: Long, chapterId: Long): String =
        DownloadCompletionManifests.directory(mangaId, chapterId)

    private fun extension(contentType: String?, url: String): String {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        return when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/avif" -> "avif"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> url.substringBefore('?').substringAfterLast('.', "jpg")
                .lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif", "avif") } ?: "jpg"
        }
    }
}
