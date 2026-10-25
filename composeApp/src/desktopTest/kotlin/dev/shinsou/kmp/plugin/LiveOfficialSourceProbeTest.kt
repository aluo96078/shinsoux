package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.ui.i18n.localizedSourceFailure
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/** Explicit opt-in diagnostic. No personal app data, credentials or rendered content are used. */
class LiveOfficialSourceProbeTest {
    /**
     * Narrow opt-in diagnostic for separating the JM content plane from source catalogue access.
     * Repository reads use the local 1.0.9 fixture; only the known public CDN image is live.
     */
    @Test
    fun probeKnownJinmantiantangCoverThroughExactContentScope() {
        if (System.getenv("SHINSOU_LIVE_JM_COVER_PROBE") != "1") return
        runBlocking {
            val localDirectory = System.getenv("SHINSOU_LIVE_PLUGIN_LOCAL_DIR")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: locateRepository()
            if (localDirectory == null || !Files.isDirectory(localDirectory)) return@runBlocking
            val http = HttpClient(MockEngine { request ->
                val relative = request.url.encodedPath.substringAfter("refs/heads/master/", "")
                require(relative.isNotBlank() && !relative.contains("..")) {
                    "Unexpected local repository path"
                }
                val file = localDirectory.resolve(relative).normalize()
                require(file.startsWith(localDirectory.normalize())) { "Local repository path escaped root" }
                respond(Files.readAllBytes(file), HttpStatusCode.OK)
            })
            val client = ExtensionRepositoryClient(
                http,
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            )
            val keys = InMemoryPluginKeyValueStore()
            val storage = KeyValuePluginStorage(keys)
            val resolver = createPlatformPluginHostResolver()
            val realTransport = requireNotNull(createPlatformPinnedPluginHttpTransport())
            val transport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    error("Use pinned transport")

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse = realTransport.executeResolved(request, resolution)
            }
            val manager = PluginManager(
                client,
                InMemoryPluginPackageStore(),
                PluginVerifier(KeyValuePluginTrustStore(keys)),
                RhinoScriptPluginRuntimeFactory(),
                ScriptPluginEnvironment(
                    PluginNetworkClient(transport, storage, hostResolver = resolver),
                    storage,
                    hostResolver = resolver,
                ),
                eventGrantAdmission = KeyValuePluginEventGrantAdmission(
                    keys,
                    MutablePluginSystemEventAuthorizer(),
                ),
            )
            var stage = "index"
            try {
                val index = client.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)
                val entry = when (index) {
                    is RepositoryIndex.Combined -> index.plugins.single { it.id == "zh.jinmantiantang" }
                    is RepositoryIndex.Plugins -> index.entries.single { it.id == "zh.jinmantiantang" }
                    is RepositoryIndex.Legacy -> error("Expected v2 repository")
                }
                stage = "install"
                manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), entry)
                stage = "approve"
                val review = requireNotNull(manager.pendingEventGrantReview(entry.id))
                manager.approveEventGrantReview(entry.id, review, review.requestedPermissions)
                stage = "content"
                val knownUrl = "https://cdn-msp3.18comic.vip/media/albums/356488_3x4.jpg"
                val response = withTimeout(30_000) {
                    requireNotNull(manager.contentNetworkForSource(JM_SOURCE_ID)).get(
                        JM_SOURCE_ID,
                        knownUrl,
                        mapOf("Referer" to "https://18comic.vip/"),
                    )
                }
                println(
                    "PROBE JM COVER host=${Url(knownUrl).host} status=${response.status} " +
                        "bytes=${response.body.size} type=${response.normalizedPluginMediaType()}",
                )
            } catch (error: Throwable) {
                println("PROBE JM COVER FAIL stage=$stage error=${error::class.simpleName}")
            } finally {
                manager.close()
                http.close()
            }
        }
    }

    @Test
    fun inspectPublicCatalogueCoverAndFirstChapter() {
        if (System.getenv("SHINSOU_LIVE_PLUGIN_PROBE") != "1") return
        runBlocking {
            val localDirectory = System.getenv("SHINSOU_LIVE_PLUGIN_LOCAL_DIR")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
            localDirectory?.let { directory ->
                require(Files.isDirectory(directory)) { "Local plugin probe directory does not exist" }
            }
            // Local artifact mode replaces repository I/O only. URLs retain the official origin,
            // and the production parser, sidecar parity, digest, reviewed catalogue admission,
            // runtime, content scope, DNS resolver, and pinned network transport remain active.
            val http = if (localDirectory == null) HttpClient(CIO) else HttpClient(MockEngine { request ->
                val relative = request.url.encodedPath.substringAfter("refs/heads/master/", "")
                require(relative.isNotBlank() && !relative.contains("..")) { "Unexpected local repository path" }
                val file = localDirectory.resolve(relative).normalize()
                require(file.startsWith(localDirectory.normalize())) { "Local repository path escaped root" }
                respond(Files.readAllBytes(file), HttpStatusCode.OK)
            })
            val client = ExtensionRepositoryClient(http,
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY)
            val keys = InMemoryPluginKeyValueStore()
            val storage = KeyValuePluginStorage(keys)
            val realTransport = requireNotNull(createPlatformPinnedPluginHttpTransport())
            var currentPackage = "index"
            val transport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("Use pinned transport")
                override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse {
                    val response = realTransport.executeResolved(request, resolution)
                    println("PROBE HTTP $currentPackage ${request.method} ${Url(request.url).host} status=${response.status} bytes=${response.body.size} type=${response.normalizedPluginMediaType()}")
                    return response
                }
            }
            val manager = PluginManager(
                client, InMemoryPluginPackageStore(), PluginVerifier(KeyValuePluginTrustStore(keys)),
                RhinoScriptPluginRuntimeFactory(),
                ScriptPluginEnvironment(
                    PluginNetworkClient(transport, storage, hostResolver = createPlatformPluginHostResolver()), storage,
                    hostResolver = createPlatformPluginHostResolver(),
                ),
                eventGrantAdmission = KeyValuePluginEventGrantAdmission(keys, MutablePluginSystemEventAuthorizer()),
                reviewedImageTransport = createPlatformReviewedImageTransport(),
            )
            suspend fun <T> probe(stage: String, block: suspend () -> T): T? = try {
                withTimeout(30_000) { block() }.also { println("PROBE OK $currentPackage $stage") }
            } catch (error: Throwable) {
                // URLs may carry short-lived image tickets in their query/fragment. Keep probe
                // output actionable without echoing exception messages or stack traces.
                println("PROBE FAIL $currentPackage $stage ${error::class.simpleName}")
                error.localizedSourceFailure(shinsouStringsFor("zh-TW"))?.let { label ->
                    println("PROBE SOURCE FAILURE $currentPackage $label")
                }
                null
            }
            try {
                val index = client.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)
                val entries = when (index) {
                    is RepositoryIndex.Plugins -> index.entries
                    is RepositoryIndex.Combined -> index.plugins
                    is RepositoryIndex.Legacy -> error("Expected official v2 repository")
                }
                val targets = System.getenv("SHINSOU_LIVE_PLUGIN_IDS")?.split(',')?.toSet()
                    ?: if (localDirectory != null) setOf("zh.dm5", "zh.manhuagui") else
                        setOf("eh.ehentai", "zh.jinmantiantang", "zh.baozimh", "zh.wnacg", "zh.manhuagui", "zh.dm5", "zh.manhuaren", "zh.mangacopy", "zh.mycomic", "zh.bilimanga.manga")
                for (entry in entries.filter { it.id in targets }) {
                    currentPackage = entry.id
                    if (probe("install") {
                            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), entry)
                            val review = requireNotNull(manager.pendingEventGrantReview(entry.id))
                            manager.approveEventGrantReview(entry.id, review, review.requestedPermissions)
                        } == null) continue
                    val source = requireNotNull(manager.source(requireNotNull(entry.sources).single().id)) as CatalogueSource
                    val page = probe("catalogue") { source.getPopularManga(1) } ?: continue
                    println("PROBE ITEMS $currentPackage ${page.mangas.size}")
                    val manga = page.mangas.firstOrNull() ?: continue
                    manga.thumbnailUrl?.let { url -> probe("cover host=${safeProbeHost(url)}") {
                        val content = requireNotNull(manager.contentNetworkForSource(source.id))
                        val request = PageRequestMetadata.parse(url)
                        val response = content.get(source.id, request.cleanUrl,
                            request.headers + mapOf("Referer" to source.baseUrl + "/"))
                        check(response.imageBodyForDecoderOrNull() != null) { "Cover status=${response.status} type=${response.normalizedPluginMediaType()}" }
                    } }
                    probe("details") { source.getMangaDetails(manga) }
                    val chapters = probe("chapters") { source.getChapterList(manga) } ?: continue
                    println("PROBE CHAPTERS $currentPackage ${chapters.size}")
                    val chapter = chapters.firstOrNull() ?: continue
                    val pages = probe("pages") { source.getPageList(chapter) } ?: continue
                    println("PROBE PAGES $currentPackage ${pages.size}")
                    val firstPage = pages.firstOrNull()
                    val image = firstPage?.imageUrl?.takeIf { it.isNotBlank() }
                        ?: firstPage?.url?.takeIf { it.isNotBlank() }?.let { pageUrl ->
                            probe("resolve-image") { source.resolveImageUrl(pageUrl) }
                        }
                    if (image != null) probe("page-image host=${safeProbeHost(image)}") {
                        val request = PageRequestMetadata.parse(image)
                        val response = requireNotNull(manager.contentNetworkForSource(source.id)).get(
                            source.id, request.cleanUrl,
                            mapOf("Referer" to source.baseUrl + "/") + request.headers,
                        )
                        check(response.imageBodyForDecoderOrNull() != null) { "Image status=${response.status} type=${response.normalizedPluginMediaType()}" }
                    }
                    if (currentPackage == "zh.bilimanga.manga") {
                        suspend fun pageImageUrl(page: Page): String? =
                            page.imageUrl?.takeIf { it.isNotBlank() }
                                ?: page.url?.takeIf { it.isNotBlank() }?.let { source.resolveImageUrl(it) }
                        suspend fun fetchBiliImage(chapterNumber: Int, pageNumber: Int, page: Page): Boolean {
                            val imageUrl = pageImageUrl(page) ?: return false
                            return probe("bili-image chapter=$chapterNumber index=$pageNumber host=${safeProbeHost(imageUrl)}") {
                                val request = PageRequestMetadata.parse(imageUrl)
                                val response = requireNotNull(manager.contentNetworkForSource(source.id)).get(
                                    source.id, request.cleanUrl,
                                    mapOf("Referer" to source.baseUrl + "/") + request.headers,
                                )
                                check(response.imageBodyForDecoderOrNull() != null)
                                println("PROBE BILI IMAGE chapter=$chapterNumber index=$pageNumber status=${response.status} bytes=${response.body.size} host=${safeProbeHost(imageUrl)}")
                            } != null
                        }
                        val firstChapterCount = pages.take(3).count { page ->
                            fetchBiliImage(1, pages.indexOf(page) + 1, page)
                        }
                        println("PROBE BILI IMAGES chapter=1 count=$firstChapterCount")
                        chapters.getOrNull(1)?.let { secondChapter ->
                            probe("bili-second-pages") { source.getPageList(secondChapter) }?.firstOrNull()?.let { secondPage ->
                                val secondOk = fetchBiliImage(2, 1, secondPage)
                                println("PROBE BILI IMAGES chapter=2 count=${if (secondOk) 1 else 0}")
                            }
                        }
                    }
                }
            } finally {
                manager.close()
                http.close()
            }
        }
    }
}

private fun safeProbeHost(value: String): String =
    runCatching { Url(PageRequestMetadata.parse(value).cleanUrl).host }.getOrDefault("invalid")

private fun locateRepository(): Path? = listOfNotNull(
    Path.of("../shinsou_plugin"), Path.of("../../shinsou_plugin"),
    System.getProperty("shinsou.pluginRepo")?.let(Path::of),
).map(Path::toAbsolutePath).firstOrNull { Files.isRegularFile(it.resolve("index.json")) }

private const val JM_SOURCE_ID: Long = 1_817_081L
