package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.download.DownloadManager
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.reader.JmImageDescrambler
import dev.shinsou.kmp.reader.ReaderImageTransform
import dev.shinsou.kmp.ui.BrowseManga
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RepositoryPluginReaderParityTest {
    @Test
    fun resolvesViewerHtmlOnlyWhenTheReaderDisplaysThatPage() = runTest {
        val source = ReaderParityRuntime(
            sourceId = 9_001,
            pages = listOf(Page(0, url = "/gallery/view/page.html", imageUrl = null)),
        )
        val fixture = createReaderParityFixture(source, proxyEnabled = true) {
            PluginHttpResponse(
                status = 200,
                body = """
                    <html><body>
                    <img class="full" src='../../images/page.jpg?token=a&amp;next=b' data-x='1>0' ID = "img">
                    </body></html>
                """.trimIndent().encodeToByteArray(),
                headers = mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
            )
        }
        try {
            fixture.storage.setCookie(
                source.id,
                PluginCookie("session", "reader-cookie", ".example", secure = true),
            )
            val chapter = fixture.coordinator.loadReaderChapter(fixture.mangaId, fixture.chapterId)
            assertTrue(fixture.transportRequests.isEmpty(), "viewer HTML must stay lazy while opening a chapter")
            val pendingPage = chapter.pages.single()
            assertTrue(pendingPage.imageUrl.isBlank())
            val readerPage = assertNotNull(pendingPage.imageResolver).invoke()

            val viewerRequest = fixture.transportRequests.single()
            assertEquals(
                "https://reader.example/gallery/view/page.html",
                Url(viewerRequest.url).parameters["url"],
            )
            assertEquals("fixture-source", viewerRequest.headers["X-Source"])
            assertEquals("reader-agent", viewerRequest.headers["User-Agent"])
            assertEquals("session=reader-cookie", viewerRequest.headers["Cookie"])
            assertEquals("proxy-key", viewerRequest.headers["X-Proxy-Key"])
            assertEquals("https://reader.example/chapter", viewerRequest.headers["Referer"])
            assertTrue(viewerRequest.headers.getValue("Accept").startsWith("text/html"))

            assertEquals(
                "https://reader.example/images/page.jpg?token=a&next=b",
                Url(readerPage.imageUrl).parameters["url"],
            )
            assertEquals("fixture-source", readerPage.headers["X-Source"])
            assertEquals("reader-agent", readerPage.headers["User-Agent"])
            assertEquals("session=reader-cookie", readerPage.headers["Cookie"])
            assertEquals("proxy-key", readerPage.headers["X-Proxy-Key"])
            assertEquals(
                "https://reader.example/gallery/view/page.html",
                readerPage.headers["Referer"],
            )
            assertFalse(readerPage.imageUrl.contains("/gallery/view/page.html"))
            assertEquals(null, readerPage.imageResolver)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun viewerWithoutTargetImageFailsDiagnostically() = runTest {
        val source = ReaderParityRuntime(
            sourceId = 9_002,
            pages = listOf(Page(0, url = "/viewer/1", imageUrl = null)),
        )
        val fixture = createReaderParityFixture(source) {
            PluginHttpResponse(
                status = 200,
                body = "<html><img id='thumbnail' src='not-the-page.jpg'></html>".encodeToByteArray(),
                headers = mapOf("Content-Type" to listOf("text/html")),
            )
        }
        try {
            val error = assertFailsWith<IllegalStateException> {
                val chapter = fixture.coordinator.loadReaderChapter(fixture.mangaId, fixture.chapterId)
                assertNotNull(chapter.pages.single().imageResolver).invoke()
            }
            assertTrue(error.message.orEmpty().contains("<img id=\"img\""))
            assertTrue(error.message.orEmpty().contains("refusing to pass an HTML URL"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun jmTransformSurvivesReaderDownloadAndOfflineSidecar() = runTest {
        val encodedUrl = "https://images.example/page.jpg#" +
            "${JmImageDescrambler.SCRAMBLE_ID_KEY}=220980&" +
            "${JmImageDescrambler.PHOTO_ID_KEY}=300000&" +
            "${JmImageDescrambler.FILENAME_KEY}=abc"
        val source = ReaderParityRuntime(
            sourceId = JmImageDescrambler.SOURCE_ID,
            pages = listOf(Page(0, imageUrl = encodedUrl)),
        )
        val fixture = createReaderParityFixture(source) {
            PluginHttpResponse(
                status = 200,
                body = byteArrayOf(9, 8, 7),
                headers = mapOf("Content-Type" to listOf("image/jpeg")),
            )
        }
        val expected = ReaderImageTransform.ReverseVerticalSegments(4)
        try {
            val online = fixture.coordinator.loadReaderChapter(fixture.mangaId, fixture.chapterId)
            assertEquals(expected, online.pages.single().imageTransform)
            assertEquals("https://images.example/page.jpg", online.pages.single().imageUrl)
            assertTrue(online.pages.single().headers.keys.none { it.startsWith("Shinsou-JM-") })

            val downloadPage = fixture.coordinator.pages(fixture.mangaId, fixture.chapterId).single()
            assertEquals(expected, downloadPage.imageTransform)
            assertEquals("https://images.example/page.jpg", downloadPage.url)
            assertTrue(downloadPage.headers.keys.none { it.startsWith("Shinsou-JM-") })

            fixture.coordinator.enqueueDownload(fixture.mangaId, fixture.chapterId)
            fixture.downloads.awaitIdle()
            val directory = "downloads/${fixture.mangaId}/${fixture.chapterId}"
            assertEquals(3, fixture.files.list(directory).size)
            assertNotNull(fixture.files.read("$directory/page-0.transform"))
            assertEquals(1, fixture.downloads.downloadedPages(fixture.mangaId, fixture.chapterId).size)

            fixture.repository.clearCompletedDownloads()
            assertTrue(fixture.repository.currentSnapshot.downloadQueue.none { it.visibleInQueue })

            val offline = fixture.coordinator.loadReaderChapter(fixture.mangaId, fixture.chapterId)
            assertTrue(offline.pages.single().local)
            assertEquals(expected, offline.pages.single().imageTransform)
            assertTrue(offline.pages.single().imageUrl.startsWith("memory://"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun partialDownloadFilesNeverReplaceTheOnlineChapter() = runTest {
        val source = ReaderParityRuntime(
            sourceId = 9_003,
            pages = listOf(Page(0, imageUrl = "https://images.example/online.jpg")),
        )
        val fixture = createReaderParityFixture(source) {
            PluginHttpResponse(status = 200, body = byteArrayOf(1), headers = emptyMap())
        }
        try {
            val item = fixture.repository.enqueueDownload(fixture.mangaId, fixture.chapterId, queuedAt = 100)
            fixture.repository.setDownloadState(
                id = item.id,
                state = DownloadState.DOWNLOADING,
                downloadedPages = 1,
                totalPages = 2,
            )
            fixture.files.write(
                "downloads/${fixture.mangaId}/${fixture.chapterId}/page-0.jpg",
                byteArrayOf(9),
            )

            val chapter = fixture.coordinator.loadReaderChapter(fixture.mangaId, fixture.chapterId)

            assertFalse(chapter.pages.single().local)
            assertEquals("https://images.example/online.jpg", chapter.pages.single().imageUrl)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun completeLegacyQueueIsSafelyUpgradedToAnAtomicManifest() = runTest {
        val source = ReaderParityRuntime(
            sourceId = 9_004,
            pages = listOf(Page(0, imageUrl = "https://images.example/online.jpg")),
        )
        val fixture = createReaderParityFixture(source) {
            PluginHttpResponse(status = 200, body = byteArrayOf(1), headers = emptyMap())
        }
        try {
            val item = fixture.repository.enqueueDownload(fixture.mangaId, fixture.chapterId, queuedAt = 100)
            fixture.repository.setDownloadState(
                id = item.id,
                state = DownloadState.DOWNLOADED,
                progress = 1.0,
                downloadedPages = 1,
                totalPages = 1,
            )
            val directory = "downloads/${fixture.mangaId}/${fixture.chapterId}"
            fixture.files.write("$directory/page-0.jpg", byteArrayOf(9))

            val chapter = fixture.coordinator.loadReaderChapter(fixture.mangaId, fixture.chapterId)

            assertTrue(chapter.pages.single().local)
            assertEquals(2, fixture.files.list(directory).size)
            assertEquals(1, fixture.downloads.downloadedPages(fixture.mangaId, fixture.chapterId).size)
        } finally {
            fixture.close()
        }
    }
}

private data class ReaderParityFixture(
    val repository: ShinsouRepository,
    val storage: KeyValuePluginStorage,
    val manager: PluginManager,
    val coordinator: RepositoryPluginCoordinator,
    val downloads: DownloadManager,
    val files: ReaderParityMemoryFileSystem,
    val transportRequests: MutableList<PluginHttpRequest>,
    val mangaId: Long,
    val chapterId: Long,
) {
    suspend fun close() {
        downloads.close()
        manager.close()
    }
}

private suspend fun createReaderParityFixture(
    source: ReaderParityRuntime,
    proxyEnabled: Boolean = false,
    transportResponse: suspend (PluginHttpRequest) -> PluginHttpResponse,
): ReaderParityFixture {
    val keyValues = InMemoryPluginKeyValueStore()
    val storage = KeyValuePluginStorage(keyValues)
    if (proxyEnabled) {
        storage.setPreference(source.id, ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE, "on")
    }
    val requestBuilder = PluginRequestBuilder(
        storage = storage,
        userAgents = PluginUserAgentProvider { "reader-agent" },
        proxyResolver = ConfiguredPluginProxyResolver(storage) {
            PluginNetworkConfiguration(
                proxyWorkerUrl = if (proxyEnabled) "https://proxy.example" else "",
                proxyApiKey = if (proxyEnabled) "proxy-key" else "",
            )
        },
    )
    val transportRequests = mutableListOf<PluginHttpRequest>()
    val network = PluginNetworkClient(
        transport = PluginHttpTransport { request ->
            transportRequests += request
            transportResponse(request)
        },
        storage = storage,
        requestBuilder = requestBuilder,
        requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
    )
    val manager = PluginManager(
        repositoryClient = ExtensionRepositoryClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond("fixture", headers = headersOf(HttpHeaders.ContentType, "text/javascript"))
                    }
                }
            },
        ),
        packageStore = InMemoryPluginPackageStore(),
        verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
        runtimeFactory = ScriptPluginRuntimeFactory { _, _, _ -> source },
        environment = ScriptPluginEnvironment(network, storage),
    )
    manager.install(
        ExtensionRepository("https://plugins.example", "Reader fixtures"),
        PluginIndexEntry(
            id = source.pluginId,
            name = source.name,
            version = "1.0.0",
            versionCode = 1,
            lang = source.lang,
            scriptUrl = "fixture.js",
            sources = listOf(SourceIndexEntry(source.name, source.lang, source.id, source.baseUrl)),
        ),
    )
    val repository = ShinsouRepository()
    val files = ReaderParityMemoryFileSystem()
    val coordinator = RepositoryPluginCoordinator(
        repository = repository,
        manager = manager,
        network = network,
        requestBuilder = requestBuilder,
        fileSystem = files,
        now = { 100 },
    )
    val downloads = DownloadManager(
        repository = repository,
        fileSystem = files,
        pageProvider = coordinator,
        pageFetcher = coordinator,
        now = { 100 },
    ).also(coordinator::attachDownloadManager)
    val mangaId = assertNotNull(coordinator.resolve(BrowseManga(source.id, "/title", "Reader fixture")))
    coordinator.refreshManga(mangaId)
    val chapterId = repository.currentSnapshot.chapters.single().id
    return ReaderParityFixture(
        repository,
        storage,
        manager,
        coordinator,
        downloads,
        files,
        transportRequests,
        mangaId,
        chapterId,
    )
}

private class ReaderParityRuntime(
    private val sourceId: Long,
    private val pages: List<Page>,
) : ScriptPluginRuntime {
    override val pluginId: String = "reader.fixture.$sourceId"
    override val id: Long = sourceId
    override val name: String = "Reader fixture $sourceId"
    override val lang: String = "en"
    override val baseUrl: String = "https://reader.example"
    override val supportsLatest: Boolean = false
    override val supportsLogin: Boolean = false
    override val headers: Map<String, String> = mapOf("X-Source" to "fixture-source")
    override val recentLogs: List<String> = emptyList()

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getFilterList(): FilterList = emptyList()
    override suspend fun getMangaDetails(manga: SManga): SManga = manga.copy(initialized = true)
    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        listOf(SChapter("/chapter", "Chapter", chapterNumber = 1.0))
    override suspend fun getPageList(chapter: SChapter): List<Page> = pages
    override suspend fun login(username: String, password: String): Boolean = false
    override suspend fun logout() = Unit
    override suspend fun close() = Unit
}

private class ReaderParityMemoryFileSystem : AppFileSystem {
    private val values = linkedMapOf<String, ByteArray>()
    override suspend fun write(relativePath: String, bytes: ByteArray) {
        values[relativePath] = bytes
    }
    override suspend fun read(relativePath: String): ByteArray? = values[relativePath]
    override suspend fun exists(relativePath: String): Boolean = relativePath in values
    override suspend fun delete(relativePath: String): Boolean = values.remove(relativePath) != null
    override suspend fun deleteTree(relativeDirectory: String): Boolean {
        val matching = values.keys.filter { it.startsWith(relativeDirectory.trimEnd('/') + "/") }
        matching.forEach(values::remove)
        return matching.isNotEmpty()
    }
    override suspend fun list(relativeDirectory: String): List<String> =
        values.keys.filter { it.startsWith(relativeDirectory.trimEnd('/') + "/") }
    override fun uri(relativePath: String): String = "memory://$relativePath"
}
