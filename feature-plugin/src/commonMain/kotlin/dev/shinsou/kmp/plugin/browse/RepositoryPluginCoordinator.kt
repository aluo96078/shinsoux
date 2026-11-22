package dev.shinsou.kmp.plugin.browse

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.contract.diagnosticUrl
import dev.shinsou.kmp.domain.model.decodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.domain.model.LOCAL_SOURCE_ID
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.LibraryUpdate
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.ReaderImageTransform
import dev.shinsou.kmp.contract.ChapterDownloads
import dev.shinsou.kmp.contract.ChapterPageProvider
import dev.shinsou.kmp.contract.DownloadPage
import dev.shinsou.kmp.contract.DownloadPageFetcher
import dev.shinsou.kmp.contract.DownloadedPage
import dev.shinsou.kmp.contract.ContentCallbacks
import dev.shinsou.kmp.contract.ReaderChapter
import dev.shinsou.kmp.contract.ReaderPage
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import dev.shinsou.kmp.plugin.contracts.BrowseManga
import dev.shinsou.kmp.plugin.contracts.CatalogueSource
import dev.shinsou.kmp.plugin.contracts.MangaStatus
import dev.shinsou.kmp.plugin.contracts.MigrationCandidate
import dev.shinsou.kmp.plugin.contracts.Page
import dev.shinsou.kmp.plugin.contracts.PageRequestMetadata
import dev.shinsou.kmp.plugin.contracts.SChapter
import dev.shinsou.kmp.plugin.contracts.SManga
import dev.shinsou.kmp.plugin.contracts.Source
import dev.shinsou.kmp.plugin.contracts.UpdateStrategy
import dev.shinsou.kmp.plugin.manager.PluginManager
import dev.shinsou.kmp.plugin.network.PluginContentNetworkClient
import dev.shinsou.kmp.plugin.network.PluginHttpRequest
import dev.shinsou.kmp.plugin.network.ViewerImageParser
import dev.shinsou.kmp.plugin.network.imageBodyForDecoderOrNull
import dev.shinsou.kmp.plugin.network.normalizedPluginMediaType
import dev.shinsou.kmp.plugin.network.sanitizePluginContentHeaders

/**
 * End-to-end content bridge between executable sources and the portable repository.
 *
 * It deliberately owns the model mapping used by browse, refresh, reader, migration and
 * downloads so those paths cannot silently disagree about source URLs or request headers.
 */
public class RepositoryPluginCoordinator(
    private val repository: ShinsouRepository,
    private val manager: PluginManager,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Loads the installed sources (or waits for startup to); a source not found asks this first. */
    private val awaitInstalledSources: suspend () -> Unit = {},
) : ContentCallbacks,
    PluginMangaResolver,
    PluginReaderChapterResolver,
    PluginMigrationHandler,
    PluginMigrationProvider,
    ChapterPageProvider,
    DownloadPageFetcher {

    private val mutationMutex = Mutex()
    private var downloads: ChapterDownloads? = null

    public fun attachDownloads(value: ChapterDownloads) {
        check(downloads == null || downloads === value) { "A download manager is already attached" }
        downloads = value
    }

    override suspend fun resolve(manga: BrowseManga): Long? = withContext(defaultDispatcher) {
        mutationMutex.withLock {
            repository.currentSnapshot.mangas.firstOrNull {
                it.source == manga.sourceId && it.url == manga.url
            }?.let { return@withLock it.id }

            // Match the original Shinsou browse flow: a catalogue tap only persists enough data
            // to construct the detail screen. Fetching details and chapters before returning made
            // JavaScript execution, HTTP and full-snapshot persistence block navigation.
            manager.source(manga.sourceId)
                ?: throw IllegalStateException("Source '${manga.sourceId}' is not loaded")
            val timestamp = now()
            repository.upsertManga(
                Manga(
                    source = manga.sourceId,
                    url = manga.url,
                    title = manga.title,
                    author = manga.author,
                    thumbnailUrl = manga.thumbnailUrl,
                    initialized = false,
                    dateAdded = timestamp,
                    lastModifiedAt = timestamp,
                ),
            ).id
        }
    }

    override suspend fun refreshLibrary(mangaIds: Set<Long>) {
        val failures = mutableListOf<Throwable>()
        mangaIds.forEach { mangaId ->
            runCatchingCancellable { refreshManga(mangaId) }.exceptionOrNull()?.let(failures::add)
        }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    override suspend fun refreshManga(mangaId: Long): Unit = withContext(defaultDispatcher) {
        mutationMutex.withLock {
            val manga = repository.currentSnapshot.mangas.firstOrNull { it.id == mangaId }
                ?: throw IllegalArgumentException("Unknown manga: $mangaId")
            val source = loadedSource(manga.source)
            val details = source.getMangaDetails(manga.toSource())
            // Finish both remote calls before mutating the portable snapshot. Besides avoiding an
            // unnecessary half-loaded UI, this prevents cancellation between details and chapters
            // from leaving an initialized title with an empty chapter list.
            val remoteChapters = source.getChapterList(details)
            refreshMangaLocked(mangaId, source, details, remoteChapters)
        }
    }

    /**
     * The source [sourceId] once it is loaded: a title or chapter restored at launch can be asked
     * for before startup has loaded the installed sources, so their loading is waited for first.
     */
    private suspend fun loadedSource(sourceId: Long): CatalogueSource =
        manager.source(sourceId)
            ?: run {
                awaitInstalledSources()
                manager.source(sourceId)
            }
            ?: error("Source '$sourceId' is not loaded")

    override suspend fun resolveMangaOriginalUrl(mangaId: Long): String? {
        val manga = repository.currentSnapshot.mangas.firstOrNull { it.id == mangaId } ?: return null
        val storedUrl = manga.url.trim().takeIf { it.isNotEmpty() } ?: return null
        return resolveSourceHttpUrl(manager.source(manga.source)?.baseUrl, storedUrl)
    }

    override suspend fun resolveChapterOriginalUrl(mangaId: Long, chapterId: Long): String? {
        val snapshot = repository.currentSnapshot
        val manga = snapshot.mangas.firstOrNull { it.id == mangaId } ?: return null
        val chapter = snapshot.chapters.firstOrNull { it.id == chapterId && it.mangaId == mangaId } ?: return null
        return resolveSourceHttpUrl(manager.source(manga.source)?.baseUrl, chapter.url)
    }

    override suspend fun loadReaderChapter(mangaId: Long, chapterId: Long): ReaderChapter {
        val offline = downloads?.downloadedChapter(mangaId, chapterId) ?: ReaderChapter()
        if (offline.pages.isNotEmpty()) return offline

        val reference = resolve(mangaId, chapterId)
            ?: throw IllegalArgumentException("Unknown chapter: $chapterId")
        val source = loadedSource(reference.sourceId)
        val contentNetwork = requireContentNetwork(source.id)
        val referer = source.headers.header("Referer")
            ?: absoluteSourceUrl(source.baseUrl, reference.chapter.url)
        val pages = source.getPageList(reference.chapter).mapIndexed { fallbackIndex, page ->
            // The host fetches every plugin-controlled remote body lazily. Giving the raw URL to
            // Coil would create a second, policy-free network path (including its own redirects).
            ReaderPage(
                index = page.index.takeIf { it >= 0 } ?: fallbackIndex,
                diagnosticUrl = diagnosticUrl(page.imageUrl?.takeIf(String::isNotBlank) ?: page.url),
                imageResolver = {
                    buildReaderPage(
                        source = source,
                        contentNetwork = contentNetwork,
                        resolved = resolvePage(source, contentNetwork, page, fallbackIndex, referer),
                    )
                },
            )
        }.sortedBy(ReaderPage::index)
        validateReaderPageIndexes(pages)
        return ReaderChapter(pages, referer, source.headers)
    }

    override suspend fun retryDownload(itemId: String) {
        requireDownloads().retry(itemId)
    }

    override suspend fun enqueueDownload(mangaId: Long, chapterId: Long) {
        requireDownloads().enqueue(mangaId, chapterId)
    }

    override suspend fun removeDownload(itemId: String) {
        requireDownloads().remove(itemId)
    }

    override suspend fun removeUnfinishedDownload(itemId: String) {
        requireDownloads().removeUnfinished(itemId)
    }

    override suspend fun reorderDownloads(orderedIds: List<String>) {
        repository.reorderDownloads(orderedIds)
    }

    override suspend fun clearCompletedDownloads() {
        repository.clearCompletedDownloads()
    }

    override suspend fun pauseDownloads(paused: Boolean) {
        requireDownloads().pauseAll(paused)
    }

    override suspend fun pages(mangaId: Long, chapterId: Long): List<DownloadPage> {
        val reference = resolve(mangaId, chapterId)
            ?: throw IllegalArgumentException("Unknown chapter: $chapterId")
        val source = manager.source(reference.sourceId)
            ?: throw IllegalStateException("Source '${reference.sourceId}' is not loaded")
        val referer = source.headers.header("Referer")
            ?: absoluteSourceUrl(source.baseUrl, reference.chapter.url)
        return source.getPageList(reference.chapter).mapIndexed { fallbackIndex, page ->
            val resolved = resolvePage(
                source,
                requireContentNetwork(source.id),
                page,
                fallbackIndex,
                referer,
            )
            val headers = linkedMapOf<String, String>().apply {
                // Download bytes use the same credential-free content plane as online pages.
                // Catalogue cookies (for example DM5's age preference) must not make every
                // download fail the content client's credential-header guard.
                putAll(sanitizePluginContentHeaders(source.headers))
                sanitizePluginContentHeaders(resolved.headers).forEach { (name, value) -> putHeader(name, value) }
                if (keys.none { it.equals("Referer", ignoreCase = true) }) put("Referer", resolved.referer)
            }
            DownloadPage(
                index = resolved.index,
                url = resolved.url,
                sourceId = source.id,
                headers = headers,
                imageTransform = resolved.imageTransform,
            )
        }
    }

    override suspend fun fetch(page: DownloadPage): DownloadedPage {
        val response = requireContentNetwork(page.sourceId).execute(
            page.sourceId,
            PluginHttpRequest("GET", page.url, headers = page.headers),
        )
        check(response.status in 200..299) { "HTTP ${response.status} while downloading page ${page.index + 1}" }
        require(response.body.isNotEmpty()) { "Downloaded page ${page.index + 1} returned no data" }
        val contentType = response.normalizedPluginMediaType()
        val imageBytes = requireNotNull(response.imageBodyForDecoderOrNull()) {
            "Downloaded page ${page.index + 1} returned non-image content '$contentType'"
        }
        return DownloadedPage(
            bytes = imageBytes,
            contentType = contentType,
        )
    }

    override suspend fun resolve(mangaId: Long, chapterId: Long): PluginReaderChapterReference? {
        val snapshot = repository.currentSnapshot
        val manga = snapshot.mangas.firstOrNull { it.id == mangaId } ?: return null
        val chapter = snapshot.chapters.firstOrNull { it.id == chapterId && it.mangaId == mangaId } ?: return null
        return PluginReaderChapterReference(manga.source, chapter.toSource())
    }

    override suspend fun migrate(mangaId: Long, target: BrowseManga): Unit = mutationMutex.withLock {
        val snapshot = repository.currentSnapshot
        val manga = snapshot.mangas.firstOrNull { it.id == mangaId }
            ?: throw IllegalArgumentException("Unknown manga: $mangaId")
        require(manga.source != LOCAL_SOURCE_ID && target.sourceKey == null) {
            "Extension-backed titles migrate only between extension sources"
        }
        val targetSource = manager.source(target.sourceId)
            ?: throw IllegalStateException("Source '${target.sourceId}' is not loaded")
        val details = targetSource.getMangaDetails(
            SManga(target.url, target.title, author = target.author, thumbnailUrl = target.thumbnailUrl),
        )
        val remoteChapters = targetSource.getChapterList(details)
        check(remoteChapters.isNotEmpty()) { "The target source returned no chapters" }

        val oldChapters = snapshot.chapters.filter { it.mangaId == mangaId }
        val claimedIds = mutableSetOf<Long>()
        val migrated = remoteChapters.mapIndexed { order, remote ->
            val match = oldChapters.firstOrNull { old ->
                old.id !in claimedIds && when {
                    remote.chapterNumber >= 0 && old.chapterNumber >= 0 ->
                        remote.chapterNumber == old.chapterNumber && remote.scanlator == old.scanlator
                    else -> remote.name.equals(old.name, ignoreCase = true)
                }
            }
            match?.id?.let(claimedIds::add)
            remote.toDomain(mangaId, newestFirstSourceOrder(order, remoteChapters.size), now(), match)
        }

        repository.updateManga(mangaId) { latest ->
            details.merging(latest, targetSource.id, now())
        }
        repository.upsertChapters(migrated)
        oldChapters.filterNot { it.id in claimedIds }.forEach { repository.deleteChapter(it.id) }
    }

    override suspend fun candidates(): List<MigrationCandidate> {
        val snapshot = repository.currentSnapshot
        val sourceNames = manager.catalogueSources().associate { it.id to it.name }
        return snapshot.mangas.filter(Manga::favorite).mapNotNull { manga ->
            // An app-local row migrates only when it is bound to an exact extension source;
            // imported files have no source to move away from.
            val binding = if (manga.source == LOCAL_SOURCE_ID) {
                decodeExtensionLibraryPublicationUrl(manga.url) ?: return@mapNotNull null
            } else null
            MigrationCandidate(
                mangaId = manga.id,
                title = manga.title,
                thumbnailUrl = manga.thumbnailUrl,
                currentSourceName = binding?.sourceKey?.packageId
                    ?: sourceNames[manga.source]
                    ?: manga.source.toString(),
                sourceId = manga.source,
                sourceKey = binding?.sourceKey,
                remotePublicationId = binding?.remotePublicationId,
            )
        }
    }

    private suspend fun refreshMangaLocked(
        mangaId: Long,
        source: CatalogueSource,
        details: SManga,
        remoteChapters: List<SChapter>,
    ) {
        val before = repository.currentSnapshot
        if (before.mangas.none { it.id == mangaId }) {
            throw IllegalArgumentException("Unknown manga: $mangaId")
        }
        val knownUrls = before.chapters.filter { it.mangaId == mangaId }.associateBy(Chapter::url)
        val fetchedAt = now()
        val refreshedManga = repository.updateManga(mangaId) { latest ->
            details.merging(latest, source.id, fetchedAt).copy(
                dateAdded = latest.dateAdded.takeIf { it > 0 } ?: fetchedAt,
            )
        }
        val chapters = remoteChapters.mapIndexed { order, remote ->
            remote.toDomain(mangaId, newestFirstSourceOrder(order, remoteChapters.size), fetchedAt, knownUrls[remote.url])
        }
        val saved = repository.upsertChapters(chapters)
        if (refreshedManga.favorite) {
            saved.filter { it.url !in knownUrls }.forEach { chapter ->
                repository.upsertUpdate(LibraryUpdate(mangaId, chapter.id, fetchedAt))
            }
        }
    }

    private data class ResolvedPage(
        val index: Int,
        val url: String,
        val headers: Map<String, String>,
        val referer: String,
        val imageTransform: ReaderImageTransform?,
    )

    private suspend fun buildReaderPage(
        source: CatalogueSource,
        contentNetwork: PluginContentNetworkClient,
        resolved: ResolvedPage,
    ): ReaderPage {
        val response = contentNetwork.execute(
            sourceId = source.id,
            request = PluginHttpRequest(
                method = "GET",
                url = resolved.url,
                headers = linkedMapOf<String, String>().apply {
                    putAll(resolved.headers)
                    if (keys.none { it.equals("Referer", ignoreCase = true) }) {
                        put("Referer", resolved.referer)
                    }
                },
            ),
        )
        check(response.status in 200..299) {
            "Unable to load reader page ${resolved.index + 1}: HTTP ${response.status}"
        }
        require(response.body.isNotEmpty()) { "Reader page ${resolved.index + 1} returned no data" }
        val contentType = response.normalizedPluginMediaType()
        val imageBytes = requireNotNull(response.imageBodyForDecoderOrNull()) {
            "Reader page ${resolved.index + 1} returned non-image content '$contentType'"
        }
        return ReaderPage(
            index = resolved.index,
            imageBytes = imageBytes,
            imageTransform = resolved.imageTransform,
        )
    }

    private suspend fun resolvePage(
        source: CatalogueSource,
        contentNetwork: PluginContentNetworkClient,
        page: Page,
        fallbackIndex: Int,
        chapterReferer: String,
    ): ResolvedPage {
        val pageIndex = page.index.takeIf { it >= 0 } ?: fallbackIndex
        page.imageUrl?.takeIf(String::isNotBlank)?.let { imageUrl ->
            val metadata = PageRequestMetadata.parse(imageUrl)
            return ResolvedPage(
                index = pageIndex,
                url = absoluteSourceUrl(source.baseUrl, metadata.cleanUrl),
                headers = metadata.headers,
                referer = chapterReferer,
                imageTransform = metadata.imageTransform(source),
            )
        }

        // Some sources expose an optional authenticated resolver for viewer pages. Invoke it in
        // the plugin request plane (where source cookies/state are available), then keep the
        // resulting image fetch in this host-owned, credential-free content plane. E-Hentai uses
        // this hook to resolve its #img URL while preserving the gallery referer.
        source.resolveImageUrl(page.url)?.takeIf(String::isNotBlank)?.let { imageUrl ->
            val metadata = PageRequestMetadata.parse(imageUrl)
            return ResolvedPage(
                index = pageIndex,
                url = absoluteSourceUrl(source.baseUrl, metadata.cleanUrl),
                headers = metadata.headers,
                referer = chapterReferer,
                imageTransform = metadata.imageTransform(source),
            )
        }

        val viewerMetadata = PageRequestMetadata.parse(page.url)
        val viewerUrl = absoluteSourceUrl(source.baseUrl, viewerMetadata.cleanUrl)
        val viewerHeaders = linkedMapOf<String, String>().apply {
            putAll(viewerMetadata.headers)
            if (keys.none { it.equals("Accept", ignoreCase = true) }) {
                put("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            }
            if (keys.none { it.equals("Referer", ignoreCase = true) }) {
                put("Referer", chapterReferer)
            }
        }
        val response = contentNetwork.execute(
            sourceId = source.id,
            request = PluginHttpRequest("GET", viewerUrl, headers = viewerHeaders),
        )
        check(response.status in 200..299) {
            "Unable to resolve reader page ${pageIndex + 1}: viewer '$viewerUrl' returned HTTP ${response.status}"
        }

        val contentType = response.headers.headerValues("Content-Type")
            .firstOrNull()
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (contentType?.startsWith("image/") == true) {
            return ResolvedPage(
                index = pageIndex,
                url = viewerUrl,
                headers = viewerMetadata.headers,
                referer = chapterReferer,
                imageTransform = viewerMetadata.imageTransform(source),
            )
        }

        val body = response.bodyText()
        val imageSource = ViewerImageParser.extractImageSource(body)
        if (imageSource == null) {
            val looksLikeHtml = contentType == "text/html" || contentType == "application/xhtml+xml" ||
                body.trimStart().startsWith('<')
            check(!looksLikeHtml) {
                "Unable to resolve reader page ${pageIndex + 1}: viewer '$viewerUrl' did not contain " +
                    "<img id=\"img\" src=\"...\">; refusing to pass an HTML URL to the image decoder"
            }
            return ResolvedPage(
                index = pageIndex,
                url = viewerUrl,
                headers = viewerMetadata.headers,
                referer = chapterReferer,
                imageTransform = viewerMetadata.imageTransform(source),
            )
        }

        val imageMetadata = PageRequestMetadata.parse(imageSource)
        val combinedMetadata = viewerMetadata.metadata + imageMetadata.metadata
        return ResolvedPage(
            index = pageIndex,
            url = ViewerImageParser.resolveUrl(viewerUrl, imageMetadata.cleanUrl),
            headers = imageMetadata.headers,
            referer = viewerUrl,
            imageTransform = PageRequestMetadata("", emptyMap(), combinedMetadata).imageTransform(source),
        )
    }

    private fun requireDownloads(): ChapterDownloads =
        downloads ?: error("Download manager is not attached")

    private suspend fun requireContentNetwork(sourceId: Long): PluginContentNetworkClient =
        manager.contentNetworkForSource(sourceId)
            ?: throw IllegalStateException("Source '$sourceId' has no admitted content network policy")

    private fun SManga.toDomain(sourceId: Long, timestamp: Long): Manga = Manga(
        source = sourceId,
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        genre = genre,
        status = status.value.toLong(),
        thumbnailUrl = thumbnailUrl,
        updateStrategy = updateStrategy.value,
        initialized = true,
        dateAdded = timestamp,
        lastModifiedAt = timestamp,
    )

    private fun SManga.merging(existing: Manga, sourceId: Long, timestamp: Long): Manga = existing.copy(
        source = sourceId,
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        genre = genre,
        status = status.value.toLong(),
        thumbnailUrl = thumbnailUrl,
        updateStrategy = updateStrategy.value,
        initialized = true,
        lastUpdate = timestamp,
        lastModifiedAt = timestamp,
        version = existing.version + 1,
    )

    private fun Manga.toSource(): SManga = SManga(
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        genre = genre,
        status = MangaStatus.fromValue(status.toInt()),
        thumbnailUrl = thumbnailUrl,
        updateStrategy = UpdateStrategy.entries.firstOrNull { it.value == updateStrategy }
            ?: UpdateStrategy.ALWAYS_UPDATE,
        initialized = initialized,
    )

    private fun SChapter.toDomain(
        mangaId: Long,
        order: Int,
        timestamp: Long,
        existing: Chapter?,
    ): Chapter = Chapter(
        id = existing?.id ?: -1,
        mangaId = mangaId,
        url = url,
        name = name,
        scanlator = scanlator,
        read = existing?.read ?: false,
        bookmark = existing?.bookmark ?: false,
        lastPageRead = existing?.lastPageRead ?: 0,
        chapterNumber = chapterNumber,
        sourceOrder = order,
        dateFetch = timestamp,
        dateUpload = dateUpload,
        lastModifiedAt = timestamp,
        version = existing?.version ?: 1,
    )

    private fun Chapter.toSource(): SChapter = SChapter(
        url = url,
        name = name,
        scanlator = scanlator,
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
    )

    private fun absoluteSourceUrl(baseUrl: String, value: String): String = when {
        value.startsWith("https://") || value.startsWith("http://") || value.startsWith("file:") -> value
        value.startsWith("//") -> baseUrl.substringBefore(':') + ":" + value
        value.startsWith('/') -> {
            val scheme = baseUrl.substringBefore("://", "https")
            val authority = baseUrl.substringAfter("://", baseUrl).substringBefore('/')
            "$scheme://$authority$value"
        }
        else -> baseUrl.trimEnd('/') + "/" + value.trimStart('/')
    }

    private fun MutableMap<String, String>.putHeader(name: String, value: String) {
        keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(::remove)
        put(name, value)
    }

    private fun Map<String, String>.header(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun Map<String, List<String>>.headerValues(name: String): List<String> =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
}

/**
 * Reader composables use the source page index as their stable item key. A malformed source page
 * list with duplicate indexes would otherwise crash Compose (or make two pages share progress),
 * so reject it at the host boundary with a user-visible chapter load error.
 */
internal fun validateReaderPageIndexes(pages: List<ReaderPage>) {
    require(pages.all { it.index >= 0 }) { "Reader returned a negative page index" }
    require(pages.map(ReaderPage::index).distinct().size == pages.size) {
        "Reader returned duplicate page indexes"
    }
}

/** Resolves source-owned URL references while refusing non-web schemes and malformed input. */
internal fun resolveSourceHttpUrl(baseUrl: String?, value: String): String? {
    val reference = value.trim()
    if (reference.isEmpty() || reference.hasUnsafeUrlCharacter()) return null

    if (SOURCE_URL_SCHEME.containsMatchIn(reference)) {
        return canonicalHttpUrl(reference)
    }

    val canonicalBase = baseUrl?.trim()?.let(::canonicalHttpUrl) ?: return null
    val resolutionBase = canonicalBase
        .substringBefore('#')
        .substringBefore('?')
        .trimEnd('/') + "/"
    val resolved = runCatching { ViewerImageParser.resolveUrl(resolutionBase, reference) }.getOrNull()
        ?: return null
    return canonicalHttpUrl(resolved)
}

private fun canonicalHttpUrl(value: String): String? {
    if (value.isBlank() || value.hasUnsafeUrlCharacter()) return null
    val parsed = runCatching { Url(value) }.getOrNull() ?: return null
    if (parsed.protocol.name !in setOf("http", "https") || parsed.host.isBlank()) return null
    return parsed.toString()
}

private fun String.hasUnsafeUrlCharacter(): Boolean =
    any { character -> character == '\\' || character.code < 0x20 || character.code == 0x7f }

private val SOURCE_URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * Extensions list chapters in story order (first chapter first), the order the extension reader
 * pages through. [Chapter.sourceOrder] counts from the newest chapter instead (0 is the latest;
 * see `buildReaderChapterNavigation`), so a list position is turned around when it is stored.
 */
internal fun newestFirstSourceOrder(storyIndex: Int, size: Int): Int = size - 1 - storyIndex
