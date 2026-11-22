package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.MangaPatch
import dev.shinsou.kmp.download.DownloadManager
import dev.shinsou.kmp.download.DownloadManagerChapterDownloads
import dev.shinsou.kmp.files.AppFileSystem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.shinsou.kmp.plugin.browse.RepositoryPluginCoordinator
import dev.shinsou.kmp.plugin.contracts.BrowseManga
import dev.shinsou.kmp.plugin.contracts.FilterList
import dev.shinsou.kmp.plugin.contracts.MangasPage
import dev.shinsou.kmp.plugin.contracts.Page
import dev.shinsou.kmp.plugin.contracts.SChapter
import dev.shinsou.kmp.plugin.contracts.SManga
import dev.shinsou.kmp.plugin.manager.PluginExecutionAdmissionMode
import dev.shinsou.kmp.plugin.manager.PluginManager
import dev.shinsou.kmp.plugin.model.ExtensionRepository
import dev.shinsou.kmp.plugin.model.PluginIndexEntry
import dev.shinsou.kmp.plugin.model.PluginRuntimePermission
import dev.shinsou.kmp.plugin.model.SourceIndexEntry
import dev.shinsou.kmp.plugin.network.ConfiguredPluginProxyResolver
import dev.shinsou.kmp.plugin.network.PerHostRequestGate
import dev.shinsou.kmp.plugin.network.PluginHostResolution
import dev.shinsou.kmp.plugin.network.PluginHostResolver
import dev.shinsou.kmp.plugin.network.PluginHttpRequest
import dev.shinsou.kmp.plugin.network.PluginHttpResponse
import dev.shinsou.kmp.plugin.network.PluginHttpTransport
import dev.shinsou.kmp.plugin.network.PluginNetworkClient
import dev.shinsou.kmp.plugin.network.PluginNetworkConfiguration
import dev.shinsou.kmp.plugin.network.PluginRateLimit
import dev.shinsou.kmp.plugin.network.PluginRateLimitProvider
import dev.shinsou.kmp.plugin.network.PluginRequestBuilder
import dev.shinsou.kmp.plugin.network.PluginUserAgentProvider
import dev.shinsou.kmp.plugin.repository.ExtensionRepositoryClient
import dev.shinsou.kmp.plugin.runtime.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.runtime.ScriptPluginRuntime
import dev.shinsou.kmp.plugin.runtime.ScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.storage.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.storage.InMemoryPluginPackageStore
import dev.shinsou.kmp.plugin.storage.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.storage.KeyValuePluginTrustStore
import dev.shinsou.kmp.plugin.trust.PluginVerifier
import dev.shinsou.kmp.plugin.trust.RepositoryTrustPolicies

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
        val transportRequestsMutex = Mutex()
        val pluginNetwork = PluginNetworkClient(
            transport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = respond(request)

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse = respond(request)

                private suspend fun respond(request: PluginHttpRequest): PluginHttpResponse {
                    transportRequestsMutex.withLock { transportRequests += request }
                    return PluginHttpResponse(
                        status = 200,
                        body = byteArrayOf(1, 2, 3),
                        headers = mapOf("Content-Type" to listOf("image/jpeg")),
                    )
                }
            },
            storage = storage,
            requestBuilder = requestBuilder,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
            hostResolver = PluginHostResolver { listOf("93.184.216.34") },
        )
        val repositoryClient = ExtensionRepositoryClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { respond("fixture", headers = headersOf(HttpHeaders.ContentType, "text/javascript")) }
                }
            },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val packageStore = InMemoryPluginPackageStore()
        fun pluginManager() = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = packageStore,
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = ScriptPluginRuntimeFactory { _, _, _ -> source },
            environment = ScriptPluginEnvironment(pluginNetwork, storage),
            executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
            executionTierGate = null,
        )
        val manager = pluginManager()
        manager.install(
            ExtensionRepository("https://plugins.example", "Fixtures"),
            PluginIndexEntry(
                id = "en.fixture",
                name = "Fixture",
                version = "1.0.0",
                versionCode = 1,
                lang = "en",
                scriptUrl = "fixture.js",
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
                sources = listOf(
                    SourceIndexEntry(
                        "Fixture",
                        "en",
                        source.id,
                        source.baseUrl,
                        contentOrigins = setOf("https://images.example"),
                    ),
                ),
            ),
        )

        val repository = ShinsouRepository()
        val files = CoordinatorMemoryFileSystem()
        val coordinator = RepositoryPluginCoordinator(
            repository = repository,
            manager = manager,
            now = { 100 },
        )
        val downloads = DownloadManager(
            repository,
            files,
            coordinator,
            coordinator,
            now = { 100 },
        ).also { coordinator.attachDownloads(DownloadManagerChapterDownloads(it, repository, files)) }

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
        // The source lists chapters in story order; the stored order counts from the newest one,
        // so library titles list the latest chapter first and "next" moves forward in the story.
        val stored = repository.currentSnapshot.chapters.associateBy { it.url }
        assertEquals(0, stored.getValue("/chapter/3").sourceOrder)
        assertEquals(2, stored.getValue("/chapter/1").sourceOrder)
        val navigation = dev.shinsou.kmp.reader.buildReaderChapterNavigation(
            chapters = repository.currentSnapshot.chapters,
            currentChapterId = stored.getValue("/chapter/1").id,
            manga = repository.currentSnapshot.mangas.single(),
            settings = dev.shinsou.kmp.domain.model.ReaderSettings(),
        )
        assertEquals("/chapter/2", navigation.next?.url)
        assertNull(navigation.previous)
        assertEquals(1, repository.currentSnapshot.updates.size)
        assertTrue(repository.currentSnapshot.mangas.single().favorite)

        val chapterId = repository.currentSnapshot.chapters.first().id
        assertEquals(
            "https://reader.example/chapter/1",
            coordinator.resolveChapterOriginalUrl(mangaId, chapterId),
        )
        assertNull(coordinator.resolveChapterOriginalUrl(mangaId, Long.MAX_VALUE))
        val online = coordinator.loadReaderChapter(mangaId, chapterId)
        val firstOnline = assertNotNull(online.pages.first().imageResolver).invoke()
        assertEquals(byteArrayOf(1, 2, 3).toList(), assertNotNull(firstOnline.imageBytes).toList())
        assertTrue(firstOnline.imageUrl.isBlank())

        // A launch that restores this chapter before startup has loaded the installed sources.
        val relaunched = pluginManager()
        assertFailsWith<IllegalStateException> {
            RepositoryPluginCoordinator(repository, relaunched, now = { 100 }).loadReaderChapter(mangaId, chapterId)
        }
        val restored = RepositoryPluginCoordinator(
            repository = repository,
            manager = relaunched,
            now = { 100 },
            awaitInstalledSources = { relaunched.loadInstalled() },
        ).loadReaderChapter(mangaId, chapterId)
        assertEquals(online.pages.size, restored.pages.size)
        // Its detail page, refreshed from the source at that point, waits for them too.
        val relaunchedAgain = pluginManager()
        RepositoryPluginCoordinator(
            repository = repository,
            manager = relaunchedAgain,
            now = { 100 },
            awaitInstalledSources = { relaunchedAgain.loadInstalled() },
        ).refreshManga(mangaId)
        relaunchedAgain.close()

        coordinator.enqueueDownload(mangaId, chapterId)
        downloads.awaitIdle()
        val completedTransportRequests = transportRequestsMutex.withLock { transportRequests.toList() }
        assertEquals(3, files.list("downloads/$mangaId/$chapterId").size)
        assertEquals(3, completedTransportRequests.size)
        assertEquals(setOf("https://images.example/0.jpg", "https://images.example/1.jpg"),
            completedTransportRequests.map(PluginHttpRequest::url).toSet())
        assertEquals("test-agent", completedTransportRequests.first().headers["User-Agent"])
        assertEquals("https://reader.example/chapter", completedTransportRequests.first().headers["Referer"])
        assertTrue(completedTransportRequests.all { "X-Proxy-Key" !in it.headers && "Cookie" !in it.headers })

        val offline = coordinator.loadReaderChapter(mangaId, chapterId)
        assertEquals(2, offline.pages.size)
        assertTrue(offline.pages.all { it.local && it.imageUrl.startsWith("memory://") })

        downloads.close()
        manager.close()
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
    private val mutex = Mutex()

    override suspend fun write(relativePath: String, bytes: ByteArray) = mutex.withLock {
        values[relativePath] = bytes
    }

    override suspend fun read(relativePath: String): ByteArray? = mutex.withLock {
        values[relativePath]
    }

    override suspend fun exists(relativePath: String): Boolean = mutex.withLock {
        relativePath in values
    }

    override suspend fun delete(relativePath: String): Boolean = mutex.withLock {
        values.remove(relativePath) != null
    }

    override suspend fun deleteTree(relativeDirectory: String): Boolean = mutex.withLock {
        val prefix = relativeDirectory.trimEnd('/') + "/"
        val matching = values.keys.filter { it.startsWith(prefix) }
        matching.forEach(values::remove)
        matching.isNotEmpty()
    }

    override suspend fun list(relativeDirectory: String): List<String> = mutex.withLock {
        val prefix = relativeDirectory.trimEnd('/') + "/"
        values.keys.filter { it.startsWith(prefix) }
    }

    override fun uri(relativePath: String): String = "memory://$relativePath"
}
