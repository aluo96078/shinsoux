package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 18comic list cards place the author block (`div.title-truncate.hidden-xs > a[href*=/search/]`)
 * before `span.video-title` in document order. A grouped selector therefore resolves to the author
 * under real Jsoup; the catalogue title must come from the title node only.
 */
class JinmantiantangListParsingTest {
    @Test
    fun catalogueTitleIsNotTheAuthorLink() = runTest {
        val repository = locateRepository() ?: return@runTest
        val entry = localEntry(repository, "zh.jinmantiantang")
        val bytes = Files.readAllBytes(repository.resolve(entry.scriptUrl.substringBefore('?')))
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    error("List parsing test requires resolved transport")

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse = PluginHttpResponse(200, LIST_FIXTURE.encodeToByteArray(), emptyMap())
            },
            storage = storage,
            policy = PluginNetworkPolicy(requestOrigins = setOf("https://18comic.vip")),
            hostResolver = PluginHostResolver { listOf("93.184.216.34") },
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val environment = ScriptPluginEnvironment(
            network,
            storage,
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
        )
        val manifest = PluginManifest(
            id = entry.id,
            name = entry.name,
            version = entry.version,
            versionCode = entry.versionCode,
            lang = entry.lang,
            nsfw = entry.nsfw == 1,
            script = "${entry.id}.js",
            signature = Sha256.hex(bytes),
            sources = entry.sources,
        )
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests()
            .create(bytes.decodeToString(), manifest, environment)
        try {
            val page = runtime.getPopularManga(1)
            assertEquals(
                listOf("網格標題" to "/album/111/grid", "首頁標題" to "/album/222/home", "圖片標題" to "/album/333/alt"),
                page.mangas.map { it.title to it.url },
            )
        } finally {
            runtime.close()
        }
    }

    private suspend fun localEntry(repository: Path, packageId: String): PluginIndexEntry {
        val http = HttpClient(MockEngine { request ->
            val relative = request.url.encodedPath.substringAfter("refs/heads/master/")
            respond(Files.readAllBytes(repository.resolve(relative)), HttpStatusCode.OK)
        })
        return try {
            val client = ExtensionRepositoryClient(
                http,
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            )
            when (val index = client.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)) {
                is RepositoryIndex.Combined -> index.plugins
                is RepositoryIndex.Plugins -> index.entries
                is RepositoryIndex.Legacy -> error("Expected V2 repository")
            }.single { it.id == packageId }
        } finally {
            http.close()
        }
    }

    private fun locateRepository(): Path? = listOfNotNull(
        Path.of("../shinsou_plugin"),
        Path.of("../../shinsou_plugin"),
        System.getProperty("shinsou.pluginRepo")?.let(Path::of),
    ).map(Path::toAbsolutePath).firstOrNull { Files.isRegularFile(it.resolve("index.json")) }
}

private val LIST_FIXTURE = """
<html><head><title>18comic</title></head><body>
<div class="row">
  <div class="col-xs-6 col-sm-6 col-md-4 col-lg-3 list-col">
    <div class="thumb-overlay-albums">
      <a href="/album/111/grid"><img class="lazy_img" data-original="https://cdn-msp.18comic.vip/media/albums/111_3x4.jpg" title="網格標題" alt="網格標題"></a>
    </div>
    <div class="title-truncate hidden-xs"><a href="/search/photos?main_tag=2&amp;search_query=作者甲">作者甲</a></div>
    <a href="/album/111/grid"><span class="video-title title-truncate m-t-5">網格標題</span></a>
  </div>
  <div class="well well-sm">
    <a href="/album/222/home"><img src="https://cdn-msp.18comic.vip/media/albums/222_3x4.jpg"></a>
    <div class="title-truncate"><a href="/search/photos?main_tag=2&amp;search_query=作者乙">作者乙</a></div>
    <a href="/album/222/home"><span class="video-title title-truncate">首頁標題</span></a>
  </div>
  <div class="col-xs-6 col-sm-6 col-md-4 col-lg-3 list-col">
    <a href="/album/333/alt"><img src="https://cdn-msp.18comic.vip/media/albums/333_3x4.jpg" alt="圖片標題"></a>
    <div class="title-truncate hidden-xs"><a href="/search/photos?main_tag=2&amp;search_query=作者丙">作者丙</a></div>
  </div>
</div>
</body></html>
""".trimIndent()
