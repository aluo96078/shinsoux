package dev.shinsou.kmp.local

import dev.shinsou.kmp.acquisition.EpubArchiveExtractor
import dev.shinsou.kmp.acquisition.LocalImagePageSource
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.LocalPackageKind
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.ui.ContentCallbacks
import dev.shinsou.kmp.ui.ImportedDocument
import dev.shinsou.kmp.ui.ImportedDocumentLimits
import dev.shinsou.kmp.ui.ReaderChapter
import dev.shinsou.kmp.ui.ReaderPage
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip

public data class LocalImportResult(
    val mangaId: Long,
    val chapterId: Long,
    val title: String,
    val pageCount: Int,
)

public data class LocalTypedProjectionRepairResult(
    val mangaRowsCreated: Int,
    val chapterRowsCreated: Int,
)

public class LocalContentImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

public class LocalContentUnavailableException(message: String) : Exception(message)

/**
 * Source 0 implementation and remote-content decorator.
 *
 * Imported pages are copied into app-private storage. Persisted Manga/Chapter records therefore
 * remain readable after a snapshot reload as long as the corresponding page directory still exists.
 */
public class LocalContentManager(
    private val repository: ShinsouRepository,
    private val fileSystem: AppFileSystem,
    private val remote: ContentCallbacks = ContentCallbacks.None,
    contentFoundation: ContentFoundationRuntime? = null,
    epubArchiveExtractor: EpubArchiveExtractor? = null,
    private val onTypedContentCommitted: () -> Unit = {},
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ContentCallbacks {
    private val importMutex = Mutex()
    private val typedContent = contentFoundation?.let { foundation ->
        LocalTypedContentController(foundation, epubArchiveExtractor, now)
    }

    override suspend fun importLocalDocuments(
        documents: List<ImportedDocument>,
        syncContentBodies: Boolean,
    ): List<LocalImportResult> =
        importMutex.withLock {
            val selected = documents.filterNot { it.name.isBlank() }
            if (selected.isEmpty()) return@withLock emptyList()
            val unsupported = selected.filter {
                fileExtension(it.name) !in LOCAL_IMAGE_EXTENSIONS &&
                    fileExtension(it.name) !in LOCAL_ARCHIVE_EXTENSIONS &&
                    fileExtension(it.name) !in LOCAL_TYPED_CONTENT_EXTENSIONS
            }
            if (unsupported.isNotEmpty()) {
                throw LocalContentImportException(
                    "Unsupported local file type: ${unsupported.joinToString { it.name }}",
                )
            }

            val typedDocuments = selected.filter { fileExtension(it.name) in LOCAL_TYPED_CONTENT_EXTENSIONS }
            if (typedDocuments.isNotEmpty() && typedContent == null) {
                throw LocalContentImportException(
                    "TXT/EPUB import requires the shared content database on this platform.",
                )
            }
            val works = mutableListOf<LocalWork>()
            val directImages = selected.filter { isSupportedLocalImage(it.name) }
            if (directImages.isNotEmpty()) {
                val pages = withContext(Dispatchers.Default) {
                    val importContext = currentCoroutineContext()
                    directImages
                        .sortedWith { left, right -> naturalPathComparator.compare(left.name, right.name) }
                        .map { document ->
                            document.toPagePayload { importContext.ensureActive() }
                        }
                }
                works += LocalWork(
                    title = deriveLocalTitle(directImages.map(ImportedDocument::name)),
                    packageKind = LocalPackageKind.IMAGES,
                    pages = pages,
                )
            }
            selected.filter { fileExtension(it.name) in LOCAL_ARCHIVE_EXTENSIONS }
                .sortedWith { left, right -> naturalPathComparator.compare(left.name, right.name) }
                .forEach { archive ->
                    works += LocalWork(
                        title = archive.name.substringBeforeLast('.', archive.name).trim()
                            .ifBlank { "Local Manga" }
                            .take(200),
                        packageKind = if (fileExtension(archive.name) == "cbz") {
                            LocalPackageKind.CBZ
                        } else {
                            LocalPackageKind.ZIP
                        },
                        pages = extractArchivePages(archive),
                    )
                }
            if (works.isEmpty() && typedDocuments.isEmpty()) {
                throw LocalContentImportException("No supported local content was selected.")
            }

            val imported = mutableListOf<LocalImportResult>()
            try {
                typedDocuments
                    .sortedWith { left, right -> naturalPathComparator.compare(left.name, right.name) }
                    .forEach { document ->
                        val typed = try {
                            withContext(Dispatchers.Default) {
                                val importContext = currentCoroutineContext()
                                requireNotNull(typedContent).import(
                                    document = document,
                                    syncContentBodies = syncContentBodies,
                                    cancellationCheckpoint = { importContext.ensureActive() },
                                )
                            }
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            if (error is LocalContentImportException) throw error
                            throw LocalContentImportException(
                                "Unable to import typed document “${document.name}”.",
                                error,
                            )
                        }
                        imported += persistTyped(typed)
                        // Publication/blob/outbox state is already durable. Notify only after the
                        // compatibility Manga/Chapter projection is also visible, and keep the
                        // callback non-blocking so derived indexing never enters the import path.
                        onTypedContentCommitted()
                    }
                works.forEach { work ->
                    val typed = typedContent
                    if (typed == null) {
                        imported += persist(work)
                    } else {
                        val content = try {
                            withContext(Dispatchers.Default) {
                                val importContext = currentCoroutineContext()
                                typed.importImageSequence(
                                    title = work.title,
                                    pages = work.pages.mapIndexed { index, page ->
                                        page.toTypedSource(index) { importContext.ensureActive() }
                                    },
                                    packageKind = work.packageKind,
                                    syncContentBodies = syncContentBodies,
                                    cancellationCheckpoint = { importContext.ensureActive() },
                                )
                            }
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            if (error is LocalContentImportException) throw error
                            throw LocalContentImportException(
                                "Unable to import image publication “${work.title}”.",
                                error,
                            )
                        }
                        imported += persistTyped(content)
                        onTypedContentCommitted()
                    }
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    imported.forEach { result ->
                        fileSystem.deleteTree(localDirectory(result.mangaId, result.chapterId))
                        runCatching { repository.deleteManga(result.mangaId) }
                    }
                }
                throw error
            }
            imported
        }

    /**
     * Repairs the only non-atomic compatibility seam in a typed local import. The authoritative
     * Publication/blob transaction commits first; a process death before Manga/Chapter persistence
     * is repaired here using the typed URL as the deterministic repository identity. Existing rows
     * are never overwritten, so favorite/progress/category changes remain intact.
     */
    public suspend fun repairTypedContentLegacyProjection(): LocalTypedProjectionRepairResult =
        importMutex.withLock {
            val controller = typedContent
                ?: return@withLock LocalTypedProjectionRepairResult(0, 0)
            var mangasCreated = 0
            var chaptersCreated = 0
            controller.legacyProjectionCandidates().forEach { content ->
                val mangaUrl = content.chapterUrl.substringBeforeLast('/')
                val existingManga = repository.currentSnapshot.mangas.singleOrNull {
                    it.source == LOCAL_SOURCE_ID && it.url == mangaUrl
                }
                val manga = existingManga ?: repository.upsertManga(
                    typedCompatibilityManga(content, mangaUrl),
                ).also { mangasCreated++ }
                val existingChapter = repository.currentSnapshot.chapters.singleOrNull {
                    it.mangaId == manga.id && it.url == content.chapterUrl
                }
                if (existingChapter == null) {
                    repository.upsertChapter(typedCompatibilityChapter(content, manga.id))
                    chaptersCreated++
                }
            }
            LocalTypedProjectionRepairResult(mangasCreated, chaptersCreated)
        }

    override suspend fun refreshLibrary(mangaIds: Set<Long>) {
        val remoteIds = mangaIds.filterTo(linkedSetOf()) { repository.manga(it)?.source != LOCAL_SOURCE_ID }
        if (remoteIds.isNotEmpty()) remote.refreshLibrary(remoteIds)
    }

    override suspend fun refreshManga(mangaId: Long) {
        if (!isLocalManga(mangaId)) remote.refreshManga(mangaId)
    }

    override suspend fun resolveMangaOriginalUrl(mangaId: Long): String? =
        if (isLocalManga(mangaId)) null else remote.resolveMangaOriginalUrl(mangaId)

    override suspend fun resolveChapterOriginalUrl(mangaId: Long, chapterId: Long): String? =
        if (isLocalManga(mangaId)) null else remote.resolveChapterOriginalUrl(mangaId, chapterId)

    override suspend fun loadReaderChapter(mangaId: Long, chapterId: Long): ReaderChapter {
        if (!isLocalManga(mangaId)) return remote.loadReaderChapter(mangaId, chapterId)
        val manga = repository.manga(mangaId)
            ?: throw LocalContentUnavailableException("The local manga record no longer exists.")
        val chapter = repository.chapter(chapterId)
            ?.takeIf { it.mangaId == mangaId }
            ?: throw LocalContentUnavailableException("The selected local chapter no longer exists.")
        typedContent?.load(chapter.url)?.let { session ->
            return ReaderChapter(typedSession = session)
        }
        val directory = localDirectory(mangaId, chapter.id)
        val manifest = LocalContentManifests.readValid(fileSystem, directory)
        if (manifest == null) {
            throw LocalContentUnavailableException(
                "Local pages for “${manga.title}” are missing or incomplete. " +
                    "Re-import the original images or CBZ/ZIP file.",
            )
        }
        return ReaderChapter(
            pages = manifest.pages.mapIndexed { index, fileName ->
                ReaderPage(index = index, imageUrl = fileSystem.uri("$directory/$fileName"), local = true)
            },
        )
    }

    override suspend fun enqueueDownload(mangaId: Long, chapterId: Long) {
        if (!isLocalManga(mangaId)) remote.enqueueDownload(mangaId, chapterId)
    }

    override suspend fun retryDownload(itemId: String) = remote.retryDownload(itemId)

    override suspend fun removeDownload(itemId: String) = remote.removeDownload(itemId)

    override suspend fun reorderDownloads(orderedIds: List<String>) = remote.reorderDownloads(orderedIds)

    override suspend fun clearCompletedDownloads() = remote.clearCompletedDownloads()

    override suspend fun pauseDownloads(paused: Boolean) = remote.pauseDownloads(paused)

    private suspend fun persist(work: LocalWork): LocalImportResult {
        require(work.pages.isNotEmpty()) { "A local manga needs at least one page." }
        require(work.pages.size <= MAX_ARCHIVE_ENTRIES) { "Local manga contains too many pages." }
        validatePageSizes(work.pages)
        val timestamp = now()
        val seed = repository.upsertManga(
            Manga(
                source = LOCAL_SOURCE_ID,
                favorite = true,
                dateAdded = timestamp,
                url = "local://import/$timestamp-${repository.currentSnapshot.revision}-${work.title.hashCode().toUInt()}",
                title = work.title,
                description = "Imported local manga",
                genre = listOf("Local"),
                updateStrategy = 1,
                initialized = true,
                lastModifiedAt = timestamp,
                favoriteModifiedAt = timestamp,
                version = 1,
            ),
        )
        val chapter = repository.upsertChapter(
            Chapter(
                mangaId = seed.id,
                url = "local://manga/${seed.id}/chapter/1",
                name = "Chapter 1",
                chapterNumber = 1.0,
                sourceOrder = 0,
                dateFetch = timestamp,
                dateUpload = timestamp,
                lastModifiedAt = timestamp,
            ),
        )
        val directory = localDirectory(seed.id, chapter.id)
        try {
            val pageNames = work.pages.mapIndexed { index, page ->
                val extension = page.extension.let { if (it == "jpeg") "jpg" else it }
                val fileName = "page-${(index + 1).toString().padStart(6, '0')}.$extension"
                fileSystem.write("$directory/$fileName", page.bytes)
                fileName
            }
            val manifest = LocalContentManifests.publish(fileSystem, directory, pageNames)
            val firstPage = "$directory/${manifest.pages.first()}"
            repository.upsertManga(
                seed.copy(
                    url = "local://manga/${seed.id}",
                    thumbnailUrl = fileSystem.uri(firstPage),
                    lastModifiedAt = timestamp,
                    version = seed.version + 1,
                ),
            )
            return LocalImportResult(seed.id, chapter.id, work.title, work.pages.size)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                fileSystem.deleteTree(directory)
                runCatching { repository.deleteManga(seed.id) }
            }
            if (error is CancellationException) throw error
            if (error is LocalContentImportException) throw error
            throw LocalContentImportException("Unable to save local manga “${work.title}”.", error)
        }
    }

    private suspend fun persistTyped(content: LocalTypedImport): LocalImportResult {
        val timestamp = content.acquiredAtEpochMillis ?: now()
        val mangaUrl = content.chapterUrl.substringBeforeLast('/')
        val seed = repository.upsertManga(
            typedCompatibilityManga(content, mangaUrl, timestamp),
        )
        val chapter = repository.upsertChapter(
            typedCompatibilityChapter(content, seed.id, timestamp),
        )
        return LocalImportResult(
            mangaId = seed.id,
            chapterId = chapter.id,
            title = content.title,
            pageCount = content.itemCount,
        )
    }

    private fun typedCompatibilityManga(
        content: LocalTypedImport,
        mangaUrl: String,
        timestamp: Long = content.acquiredAtEpochMillis ?: now(),
    ): Manga = Manga(
        source = LOCAL_SOURCE_ID,
        favorite = true,
        dateAdded = timestamp,
        url = mangaUrl,
        title = content.title,
        description = "Imported local publication",
        genre = listOf("Local"),
        updateStrategy = 1,
        initialized = true,
        lastModifiedAt = timestamp,
        favoriteModifiedAt = timestamp,
        version = 1,
    )

    private fun typedCompatibilityChapter(
        content: LocalTypedImport,
        mangaId: Long,
        timestamp: Long = content.acquiredAtEpochMillis ?: now(),
    ): Chapter = Chapter(
        mangaId = mangaId,
        url = content.chapterUrl,
        name = content.title,
        chapterNumber = 1.0,
        sourceOrder = 0,
        dateFetch = timestamp,
        dateUpload = timestamp,
        lastModifiedAt = timestamp,
    )

    private suspend fun extractArchivePages(document: ImportedDocument): List<LocalPagePayload> {
        try {
            validateZipEntryNames(document.contents)
        } catch (error: IllegalArgumentException) {
            throw LocalContentImportException(error.message ?: "The archive is invalid.", error)
        }
        val temporary = "local/.imports/archive-${now()}-${document.name.hashCode().toUInt()}.zip"
        fileSystem.write(temporary, document.contents)
        try {
            val absolutePath = fileSystem.absolutePath(temporary)
                ?: throw LocalContentImportException("ZIP/CBZ import is unavailable on this platform.")
            return withContext(Dispatchers.Default) {
                val zip = FileSystem.SYSTEM.openZip(absolutePath.toPath())
                try {
                    val entries = zip.listRecursively("/".toPath())
                        .filter { path ->
                            val relative = path.toString().trimStart('/')
                            val metadata = zip.metadata(path)
                            metadata.isRegularFile &&
                                metadata.symlinkTarget == null &&
                                isSafeArchiveEntryName(relative) &&
                                relative.split('/').none { it.startsWith('.') || it == "__MACOSX" } &&
                                isSupportedLocalImage(relative)
                        }
                        .map { it.toString() }
                        .toList()
                        .let(::naturalSortedPaths)
                    if (entries.isEmpty()) {
                        throw LocalContentImportException("${document.name} does not contain supported image pages.")
                    }
                    if (entries.size > MAX_ARCHIVE_ENTRIES) {
                        throw LocalContentImportException("${document.name} contains too many image pages.")
                    }
                    var totalSize = 0L
                    entries.map { entryName ->
                        val path = entryName.toPath()
                        val declaredSize = zip.metadata(path).size
                            ?: throw LocalContentImportException("An archive page has no declared size.")
                        if (declaredSize <= 0 || declaredSize > LOCAL_PAGE_MAX_BYTES) {
                            throw LocalContentImportException("Archive page is empty or exceeds the 64 MB limit: $entryName")
                        }
                        totalSize += declaredSize
                        if (totalSize > LOCAL_IMPORT_MAX_BYTES) {
                            throw LocalContentImportException("Archive images exceed the 512 MB import limit.")
                        }
                        val source = zip.source(path).buffer()
                        val bytes = try {
                            val payload = runCatching { source.readByteArray(declaredSize) }
                                .getOrElse {
                                    throw LocalContentImportException(
                                        "Archive page is shorter than its declared size: $entryName",
                                        it,
                                    )
                                }
                            if (!source.exhausted()) {
                                throw LocalContentImportException(
                                    "Archive page exceeds its declared size: $entryName",
                                )
                            }
                            payload
                        } finally {
                            source.close()
                        }
                        LocalPagePayload(entryName, fileExtension(entryName), bytes)
                    }
                } finally {
                    zip.close()
                }
            }
        } catch (error: LocalContentImportException) {
            throw error
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            throw LocalContentImportException("Unable to read ${document.name} as ZIP/CBZ.", error)
        } finally {
            withContext(NonCancellable) { fileSystem.delete(temporary) }
        }
    }

    private fun ImportedDocument.toPagePayload(
        cancellationCheckpoint: () -> Unit,
    ): LocalPagePayload {
        if (byteSize <= 0) throw LocalContentImportException("Image is empty: $name")
        if (byteSize > LOCAL_PAGE_MAX_BYTES) {
            throw LocalContentImportException("Image exceeds the 64 MB limit: $name")
        }
        val bytes = ByteArray(byteSize.toInt())
        var offset = 0
        while (offset < bytes.size) {
            cancellationCheckpoint()
            val count = minOf(LOCAL_IMAGE_READ_CHUNK_BYTES, bytes.size - offset)
            val chunk = source.read(offset.toLong(), count)
            require(chunk.size == count) { "Local image changed while it was being read: $name" }
            chunk.copyInto(bytes, offset)
            offset += count
        }
        cancellationCheckpoint()
        return LocalPagePayload(name, fileExtension(name), bytes)
    }

    private fun validatePageSizes(pages: List<LocalPagePayload>) {
        var total = 0L
        pages.forEach { page ->
            if (page.bytes.isEmpty() || page.bytes.size.toLong() > LOCAL_PAGE_MAX_BYTES) {
                throw LocalContentImportException("Image is empty or exceeds the 64 MB limit: ${page.sourceName}")
            }
            total += page.bytes.size
            if (total > LOCAL_IMPORT_MAX_BYTES) throw LocalContentImportException("Images exceed the 512 MB import limit.")
        }
    }

    private fun LocalPagePayload.toTypedSource(
        index: Int,
        cancellationCheckpoint: () -> Unit,
    ): LocalImagePageSource = LocalImagePageSource(
        logicalName = "page-${(index + 1).toString().padStart(6, '0')}-${sourceName.take(4_000)}",
        mediaType = localImageMediaType(extension),
        bytes = bytes,
        cancellationCheckpoint = cancellationCheckpoint,
    )

    private fun isLocalManga(mangaId: Long): Boolean = repository.manga(mangaId)?.source == LOCAL_SOURCE_ID

    private fun localDirectory(mangaId: Long, chapterId: Long): String = "local/$mangaId/$chapterId"
}

private data class LocalWork(
    val title: String,
    val packageKind: LocalPackageKind,
    val pages: List<LocalPagePayload>,
)

private data class LocalPagePayload(val sourceName: String, val extension: String, val bytes: ByteArray)

private fun localImageMediaType(extension: String): String = when (extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "avif" -> "image/avif"
    "heic" -> "image/heic"
    "bmp" -> "image/bmp"
    else -> throw LocalContentImportException("Unsupported local image type: $extension")
}

public const val LOCAL_SOURCE_ID: Long = 0L
public val LOCAL_CONTENT_EXTENSIONS: Set<String> =
    LOCAL_IMAGE_EXTENSIONS + LOCAL_ARCHIVE_EXTENSIONS + LOCAL_TYPED_CONTENT_EXTENSIONS
public const val LOCAL_PAGE_MAX_BYTES: Long = 64L * 1024L * 1024L
public const val LOCAL_IMPORT_MAX_BYTES: Long = 512L * 1024L * 1024L
private const val LOCAL_IMAGE_READ_CHUNK_BYTES: Int = 64 * 1024
public val LOCAL_IMPORTED_DOCUMENT_LIMITS: ImportedDocumentLimits = ImportedDocumentLimits(
    maxBytesPerFile = LOCAL_IMPORT_MAX_BYTES,
    maxTotalBytes = LOCAL_IMPORT_MAX_BYTES,
    maxBytesByExtension = LOCAL_IMAGE_EXTENSIONS.associateWith { LOCAL_PAGE_MAX_BYTES } + mapOf(
        LOCAL_TEXT_EXTENSION to LOCAL_PAGE_MAX_BYTES,
        LOCAL_EPUB_EXTENSION to LOCAL_IMPORT_MAX_BYTES,
    ),
    randomAccessExtensions = setOf(LOCAL_EPUB_EXTENSION),
)
