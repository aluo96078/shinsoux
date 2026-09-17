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
    fun catalogueCookieDoesNotBlockDownloadContent() = runTest {
        val source = ReaderParityRuntime(
            sourceId = 9007, pages = listOf(Page(0, imageUrl = "https://images.example/page.jpg")),
            headers = mapOf("Cookie" to "isAdult=1", "Authorization" to "Bearer synthetic", "Referer" to "https://reader.example/"),
        )
        val fixture = createReaderParityFixture(source) { request ->
            assertTrue(request.headers.keys.none { it.equals("Cookie",true) || it.equals("Authorization",true) })
            PluginHttpResponse(200, byteArrayOf(1,2,3), mapOf("Content-Type" to listOf("image/jpeg")))
        }
        try {
            val page = fixture.coordinator.pages(fixture.mangaId,fixture.chapterId).single()
            assertTrue(page.headers.keys.none { it.equals("Cookie",true) || it.equals("Authorization",true) })
            assertEquals("https://reader.example/",page.headers["Referer"])
            fixture.coordinator.enqueueDownload(fixture.mangaId,fixture.chapterId)
            fixture.downloads.awaitIdle()
            assertTrue(fixture.coordinator.loadReaderChapter(fixture.mangaId,fixture.chapterId).pages.single().local)
        } finally { fixture.close() }
    }

    @Test
    fun sourceViewerResolverUsesCredentialsBeforeCredentialFreeAllowlistedImageFetch() = runTest {
        var resolvedImageUrl = "https://images.example/page.jpg"
        var requestPlaneNetwork: PluginNetworkClient? = null
        val source = ReaderParityRuntime(
            sourceId = 9_005,
            pages = listOf(Page(0, url = "/gallery/view/page.html?nw=1", imageUrl = null)),
            resolveImageUrl = { pageUrl ->
                val network = requireNotNull(requestPlaneNetwork)
                val response = network.execute(
                    sourceId = 9_005,
                    request = PluginHttpRequest("GET", "https://reader.example$pageUrl"),
                    sourceHeaders = mapOf("X-Source" to "fixture-source"),
                    referer = "https://reader.example/chapter",
                )
                check(response.status in 200..299)
                resolvedImageUrl
            },
        )
        val fixture = createReaderParityFixture(source) { request ->
            when {
                request.url == "https://reader.example/gallery/view/page.html?nw=1" ->
                    PluginHttpResponse(status = 200, body = "resolved".encodeToByteArray())
                request.url == "https://images.example/page.jpg" ->
                    PluginHttpResponse(
                        status = 200,
                        body = byteArrayOf(9, 8, 7),
                        headers = mapOf("Content-Type" to listOf("image/jpeg")),
                    )
                else -> error("unexpected request: ${request.url}")
            }
        }
        try {
            requestPlaneNetwork = fixture.network.scopedToPolicy(
                PluginNetworkPolicy(
                    requestOrigins = setOf("https://reader.example"),
                    credentialOrigins = setOf("https://reader.example"),
                ),
            )
            fixture.storage.setCookie(
                source.id,
                PluginCookie("session", "reader-cookie", ".example", secure = true),
            )

            val chapter = fixture.coordinator.loadReaderChapter(fixture.mangaId, fixture.chapterId)
            val pendingPage = chapter.pages.single()
            val page = assertNotNull(pendingPage.imageResolver).invoke()

            assertNotNull(page.imageBytes)
            assertEquals(2, fixture.transportRequests.size)
            val resolverRequest = fixture.transportRequests[0]
            assertEquals("https://reader.example/gallery/view/page.html?nw=1", resolverRequest.url)
            assertEquals("session=reader-cookie", resolverRequest.headers["Cookie"])
            assertEquals("fixture-source", resolverRequest.headers["X-Source"])
            val imageRequest = fixture.transportRequests[1]
            assertEquals("https://images.example/page.jpg", imageRequest.url)
            assertEquals("https://reader.example/chapter", imageRequest.headers["Referer"])
            assertTrue(imageRequest.headers.keys.none {
                it.equals("Cookie", true) || it.equals("X-Proxy-Key", true) || it.equals("X-Source", true)
            })

            // A resolver result outside the manifest's content origins is rejected before the
            // transport is called, preserving the content-plane allowlist.
            resolvedImageUrl = "https://unrelated.example/image.jpg"
            val resolver = assertNotNull(pendingPage.imageResolver)
            val error = assertFailsWith<IllegalArgumentException> { resolver.invoke() }
            assertTrue(error.message.orEmpty().contains("origin was not declared"))
            assertEquals(3, fixture.transportRequests.size)
            assertTrue(fixture.transportRequests.none { it.url == "https://unrelated.example/image.jpg" })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun resolvesViewerHtmlOnlyWhenTheReaderDisplaysThatPage() = runTest {
        val source = ReaderParityRuntime(
            sourceId = 9_001,
            pages = listOf(Page(0, url = "/gallery/view/page.html", imageUrl = null)),
        )
        val fixture = createReaderParityFixture(source, proxyEnabled = true) {
            if (it.url.contains("/gallery/view/page.html")) {
                PluginHttpResponse(
                    status = 200,
                    body = """
                        <html><body>
                        <img class="full" src='../../images/page.jpg?token=a&amp;next=b' data-x='1>0' ID = "img">
                        </body></html>
                    """.trimIndent().encodeToByteArray(),
                    headers = mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
                )
            } else {
                PluginHttpResponse(
                    status = 200,
                    body = byteArrayOf(9, 8, 7),
                    headers = mapOf("Content-Type" to listOf("image/jpeg")),
                )
            }
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

            val viewerRequest = fixture.transportRequests.first()
            assertEquals("https://reader.example/gallery/view/page.html", viewerRequest.url)
            assertEquals("reader-agent", viewerRequest.headers["User-Agent"])
            assertEquals("https://reader.example/chapter", viewerRequest.headers["Referer"])
            assertTrue(viewerRequest.headers.getValue("Accept").startsWith("text/html"))
            assertTrue(viewerRequest.headers.keys.none {
                it.equals("Cookie", true) || it.equals("X-Proxy-Key", true) || it.equals("X-Source", true)
            })

            assertNotNull(readerPage.imageBytes)
            assertTrue(readerPage.imageUrl.isBlank())
            assertEquals("https://reader.example/images/page.jpg?token=a&next=b", fixture.transportRequests.last().url)
            assertEquals("https://reader.example/gallery/view/page.html", fixture.transportRequests.last().headers["Referer"])
            assertTrue(fixture.transportRequests.last().headers.keys.none {
                it.equals("Cookie", true) || it.equals("X-Proxy-Key", true) || it.equals("X-Source", true)
            })
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
            val onlinePage = assertNotNull(online.pages.single().imageResolver).invoke()
            assertNotNull(onlinePage.imageBytes)
            assertEquals(expected, onlinePage.imageTransform)
            assertTrue(onlinePage.headers.keys.none { it.startsWith("Shinsou-JM-") })

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
            val onlinePage = assertNotNull(chapter.pages.single().imageResolver).invoke()
            assertNotNull(onlinePage.imageBytes)
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

    @Test
    fun versionOneCompletionMarkerIsUpgradedWithPageIntegrity() = runTest {
        val source = ReaderParityRuntime(
            sourceId = 9_008,
            pages = listOf(Page(0, imageUrl = "https://images.example/online.jpg")),
        )
        val fixture = createReaderParityFixture(source) {
            error("valid v1 offline content must not use the network")
        }
        try {
            val directory = "downloads/${fixture.mangaId}/${fixture.chapterId}"
            fixture.files.write("$directory/page-0.jpg", byteArrayOf(9, 8, 7))
            fixture.files.write(
                "$directory/completion-v1.json",
                """{"version":1,"pageCount":1,"pages":[{"index":0,"fileName":"page-0.jpg","transformFileName":null}]}"""
                    .encodeToByteArray(),
            )

            val chapter = fixture.coordinator.loadReaderChapter(fixture.mangaId, fixture.chapterId)

            assertTrue(chapter.pages.single().local)
            val upgraded = assertNotNull(fixture.files.read("$directory/completion-v1.json")).decodeToString()
            assertTrue(Regex("\\\"version\\\"\\s*:\\s*2").containsMatchIn(upgraded))
            assertTrue(Regex("\\\"byteSize\\\"\\s*:\\s*3").containsMatchIn(upgraded))
            assertTrue(upgraded.contains("\"plaintextDigest\""))
            assertTrue(fixture.transportRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun corruptedCompletedPageDoesNotShadowTheLiveChapterAfterReopen() = runTest {
        val source = ReaderParityRuntime(
            sourceId = 9_006,
            pages = listOf(Page(0, imageUrl = "https://images.example/online.jpg")),
        )
        var fetchedBody = byteArrayOf(1, 2, 3)
        val fixture = createReaderParityFixture(source) {
            PluginHttpResponse(
                status = 200,
                body = fetchedBody,
                headers = mapOf("Content-Type" to listOf("image/jpeg")),
            )
        }
        try {
            fixture.coordinator.enqueueDownload(fixture.mangaId, fixture.chapterId)
            fixture.downloads.awaitIdle()
            val directory = "downloads/${fixture.mangaId}/${fixture.chapterId}"
            fixture.files.write("$directory/page-0.jpg", byteArrayOf(9))
            fetchedBody = byteArrayOf(4, 5, 6)

            val chapter = fixture.coordinator.loadReaderChapter(fixture.mangaId, fixture.chapterId)

            assertFalse(chapter.pages.single().local)
            val online = assertNotNull(chapter.pages.single().imageResolver).invoke()
            assertEquals(listOf<Byte>(4, 5, 6), assertNotNull(online.imageBytes).toList())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun downloadRejectsHtmlBodyBeforePublishingOfflineCompletion() = runTest {
        val source = ReaderParityRuntime(
            sourceId = 9_007,
            pages = listOf(Page(0, imageUrl = "https://images.example/error.jpg")),
        )
        val fixture = createReaderParityFixture(source) {
            PluginHttpResponse(
                status = 200,
                body = "<html>upstream error</html>".encodeToByteArray(),
                headers = mapOf("Content-Type" to listOf("text/html")),
            )
        }
        try {
            fixture.coordinator.enqueueDownload(fixture.mangaId, fixture.chapterId)
            fixture.downloads.awaitIdle()

            val queued = fixture.repository.currentSnapshot.downloadQueue.single()
            assertEquals(DownloadState.ERROR, queued.state)
            assertTrue(queued.errorMessage.orEmpty().contains("non-image content"))
            assertTrue(
                fixture.downloads.downloadedPages(fixture.mangaId, fixture.chapterId).isEmpty(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun readerRejectsDuplicatePageIndexesBeforeRendering() = runTest {
        listOf(
            listOf(
                Page(index = 4, imageUrl = "https://images.example/one.jpg"),
                Page(index = 4, imageUrl = "https://images.example/two.jpg"),
            ) to "duplicate page indexes",
        ).forEach { (malformedPages, expectedMessage) ->
            val source = ReaderParityRuntime(sourceId = 9_009, pages = malformedPages)
            val fixture = createReaderParityFixture(source) {
                PluginHttpResponse(status = 200, body = byteArrayOf(1), headers = emptyMap())
            }
            try {
                val error = assertFailsWith<IllegalArgumentException> {
                    fixture.coordinator.loadReaderChapter(fixture.mangaId, fixture.chapterId)
                }
                assertTrue(error.message.orEmpty().contains(expectedMessage))
                assertTrue(fixture.transportRequests.isEmpty())
            } finally {
                fixture.close()
            }
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
    val network: PluginNetworkClient,
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
        transport = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = respond(request)

            override suspend fun executeResolved(
                request: PluginHttpRequest,
                resolution: PluginHostResolution,
            ): PluginHttpResponse = respond(request)

            private suspend fun respond(request: PluginHttpRequest): PluginHttpResponse {
                transportRequests += request
                return transportResponse(request)
            }
        },
        storage = storage,
        requestBuilder = requestBuilder,
        requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        hostResolver = PluginHostResolver { listOf("93.184.216.34") },
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
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        ),
        packageStore = InMemoryPluginPackageStore(),
        verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
        runtimeFactory = ScriptPluginRuntimeFactory { _, _, _ -> source },
        environment = ScriptPluginEnvironment(network, storage),
        executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
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
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
            sources = listOf(
                SourceIndexEntry(
                    source.name,
                    source.lang,
                    source.id,
                    source.baseUrl,
                    requestOrigins = setOf("https://reader.example"),
                    credentialOrigins = setOf("https://reader.example"),
                    contentOrigins = setOf("https://reader.example", "https://images.example"),
                    originPolicyVersion = 1,
                ),
            ),
        ),
    )
    val repository = ShinsouRepository()
    val files = ReaderParityMemoryFileSystem()
    val coordinator = RepositoryPluginCoordinator(
        repository = repository,
        manager = manager,
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
        network,
        transportRequests,
        mangaId,
        chapterId,
    )
}

private class ReaderParityRuntime(
    private val sourceId: Long,
    private val pages: List<Page>,
    private val resolveImageUrl: (suspend (String) -> String?)? = null,
    override val headers: Map<String, String> = mapOf("X-Source" to "fixture-source"),
) : ScriptPluginRuntime {
    override val pluginId: String = "reader.fixture.$sourceId"
    override val id: Long = sourceId
    override val name: String = "Reader fixture $sourceId"
    override val lang: String = "en"
    override val baseUrl: String = "https://reader.example"
    override val supportsLatest: Boolean = false
    override val supportsLogin: Boolean = false
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
    override suspend fun resolveImageUrl(pageUrl: String): String? = resolveImageUrl?.invoke(pageUrl)
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
