package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.MangaPatch
import dev.shinsou.kmp.download.DownloadManager
import dev.shinsou.kmp.files.AppFileSystem
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryPluginCoordinatorTest {
    @Test
    fun browseRefreshDownloadAndOfflineReaderUseOneSourceContract() = runTest {
        val source = FakeRuntime()
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        storage.setPreference(source.id, ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE, "on")
        val requestBuilder = PluginRequestBuilder(
            storage = storage,
            userAgents = PluginUserAgentProvider { "test-agent" },
            proxyResolver = ConfiguredPluginProxyResolver(storage) {
                PluginNetworkConfiguration(
                    proxyWorkerUrl = "https://proxy.example",
                    proxyApiKey = "proxy-key",
                )
            },
        )
        val transportRequests = mutableListOf<PluginHttpRequest>()
        val pluginNetwork = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                transportRequests += request
                PluginHttpResponse(
                    status = 200,
                    body = byteArrayOf(1, 2, 3),
                    headers = mapOf("Content-Type" to listOf("image/jpeg")),
                )
            },
            storage = storage,
            requestBuilder = requestBuilder,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val repositoryClient = ExtensionRepositoryClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { respond("fixture", headers = headersOf(HttpHeaders.ContentType, "text/javascript")) }
                }
            },
        )
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = ScriptPluginRuntimeFactory { _, _, _ -> source },
            environment = ScriptPluginEnvironment(pluginNetwork, storage),
        )
        manager.install(
            ExtensionRepository("https://plugins.example", "Fixtures"),
            PluginIndexEntry(
                id = "en.fixture",
                name = "Fixture",
                version = "1.0.0",
                versionCode = 1,
                lang = "en",
                scriptUrl = "fixture.js",
                sources = listOf(SourceIndexEntry("Fixture", "en", source.id, source.baseUrl)),
            ),
        )

        val repository = ShinsouRepository()
        val files = CoordinatorMemoryFileSystem()
        val coordinator = RepositoryPluginCoordinator(
            repository = repository,
            manager = manager,
            network = pluginNetwork,
            requestBuilder = requestBuilder,
            fileSystem = files,
            now = { 100 },
        )
        val downloads = DownloadManager(
            repository,
            files,
            coordinator,
            coordinator,
            now = { 100 },
        ).also(coordinator::attachDownloadManager)

        val mangaId = assertNotNull(
            coordinator.resolve(BrowseManga(source.id, "/title", "Seed")),
        )
        assertEquals("Seed", repository.currentSnapshot.mangas.single().title)
        assertEquals(false, repository.currentSnapshot.mangas.single().initialized)
        assertTrue(repository.currentSnapshot.chapters.isEmpty())

        coordinator.refreshManga(mangaId)
        assertEquals("Detailed", repository.currentSnapshot.mangas.single().title)
        assertEquals(2, repository.currentSnapshot.chapters.size)
        assertEquals("https://reader.example/title", coordinator.resolveMangaOriginalUrl(mangaId))

        repository.patchManga(mangaId, MangaPatch(favorite = true))
        source.includeNewChapter = true
        coordinator.refreshManga(mangaId)
        assertEquals(3, repository.currentSnapshot.chapters.size)
        assertEquals(1, repository.currentSnapshot.updates.size)
        assertTrue(repository.currentSnapshot.mangas.single().favorite)

        val chapterId = repository.currentSnapshot.chapters.first().id
        assertEquals(
            "https://reader.example/chapter/1",
            coordinator.resolveChapterOriginalUrl(mangaId, chapterId),
        )
        assertNull(coordinator.resolveChapterOriginalUrl(mangaId, Long.MAX_VALUE))
        val online = coordinator.loadReaderChapter(mangaId, chapterId)
        assertEquals(
            "https://images.example/0.jpg",
            Url(online.pages.first().imageUrl).parameters["url"],
        )
        assertEquals("test-agent", online.pages.first().headers["User-Agent"])
        assertEquals("proxy-key", online.pages.first().headers["X-Proxy-Key"])
        assertEquals("https://reader.example/chapter", online.pages.first().headers["Referer"])

        coordinator.enqueueDownload(mangaId, chapterId)
        downloads.awaitIdle()
        assertEquals(3, files.list("downloads/$mangaId/$chapterId").size)
        assertEquals(2, transportRequests.size)
        assertEquals(
            setOf("https://images.example/0.jpg", "https://images.example/1.jpg"),
            transportRequests.mapNotNull { Url(it.url).parameters["url"] }.toSet(),
        )
        assertEquals("test-agent", transportRequests.first().headers["User-Agent"])
        assertEquals("proxy-key", transportRequests.first().headers["X-Proxy-Key"])
        assertEquals("https://reader.example/chapter", transportRequests.first().headers["Referer"])

        val offline = coordinator.loadReaderChapter(mangaId, chapterId)
        assertEquals(2, offline.pages.size)
        assertTrue(offline.pages.all { it.local && it.imageUrl.startsWith("memory://") })

        downloads.close()
        manager.close()
    }

    @Test
    fun externalWebUrlResolutionHandlesReferencesAndRejectsUnsafeSchemes() {
        assertEquals(
            "https://reader.example/catalog/chapter/1?token=a#page-2",
            resolveSourceHttpUrl(
                baseUrl = "https://reader.example/catalog",
                value = "chapter/1?token=a#page-2",
            ),
        )
        assertEquals(
            "https://reader.example/chapter/2",
            resolveSourceHttpUrl("https://reader.example/catalog", "/chapter/2"),
        )
        assertEquals(
            "https://cdn.example/chapter/3",
            resolveSourceHttpUrl("https://reader.example", "//cdn.example/chapter/3"),
        )
        assertEquals(
            "http://absolute.example/chapter/4",
            resolveSourceHttpUrl(null, "http://absolute.example/chapter/4"),
        )

        assertNull(resolveSourceHttpUrl("https://reader.example", "javascript:alert(1)"))
        assertNull(resolveSourceHttpUrl("https://reader.example", "file:///private/chapter"))
        assertNull(resolveSourceHttpUrl("https://reader.example", "local://chapter/1"))
        assertNull(resolveSourceHttpUrl("ftp://reader.example", "/chapter/1"))
        assertNull(resolveSourceHttpUrl("https://reader.example", "chapter\\redirect"))
        assertNull(resolveSourceHttpUrl("https://reader.example", "chapter/1\nhttps://evil.example"))
        assertNull(resolveSourceHttpUrl("https://reader.example", "   "))
    }
}

private class FakeRuntime : ScriptPluginRuntime {
    var includeNewChapter: Boolean = false
    override val pluginId: String = "en.fixture"
    override val id: Long = 77
    override val name: String = "Fixture"
    override val lang: String = "en"
    override val baseUrl: String = "https://reader.example"
    override val supportsLatest: Boolean = true
    override val supportsLogin: Boolean = false
    override val headers: Map<String, String> = mapOf("X-Source" to "fixture")
    override val recentLogs: List<String> = emptyList()

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getFilterList(): FilterList = emptyList()
    override suspend fun getMangaDetails(manga: SManga): SManga = manga.copy(
        title = "Detailed",
        author = "Author",
        initialized = true,
    )
    override suspend fun getChapterList(manga: SManga): List<SChapter> = buildList {
        add(SChapter("/chapter/1", "Chapter 1", dateUpload = 1, chapterNumber = 1.0))
        add(SChapter("/chapter/2", "Chapter 2", dateUpload = 2, chapterNumber = 2.0))
        if (includeNewChapter) add(SChapter("/chapter/3", "Chapter 3", dateUpload = 3, chapterNumber = 3.0))
    }
    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(
        Page(0, imageUrl = "https://images.example/0.jpg#Referer=https%3A%2F%2Freader.example%2Fchapter"),
        Page(1, imageUrl = "https://images.example/1.jpg#Referer=https%3A%2F%2Freader.example%2Fchapter"),
    )
    override suspend fun login(username: String, password: String): Boolean = false
    override suspend fun logout() = Unit
    override suspend fun close() = Unit
}

private class CoordinatorMemoryFileSystem : AppFileSystem {
    private val values = linkedMapOf<String, ByteArray>()
    override suspend fun write(relativePath: String, bytes: ByteArray) { values[relativePath] = bytes }
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
