package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BaoziCompatibilityTest {
    @Test
    fun admittedReaderFollowsExactRedirectAndFetchesCredentialFreePages() = runTest {
        val repo = requireNotNull(
            listOfNotNull(
                Path.of("../shinsou_plugin"),
                Path.of("../../shinsou_plugin"),
                System.getProperty("shinsou.pluginRepo")?.let(Path::of),
            ).map(Path::toAbsolutePath).firstOrNull { Files.isRegularFile(it.resolve("index.json")) },
        ) { "Sibling shinsou_plugin fixture repository was not found" }
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath.substringAfter("refs/heads/master/")
            respond(Files.readAllBytes(repo.resolve(path)), HttpStatusCode.OK)
        })
        val keys = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keys)
        val requests = mutableListOf<PluginHttpRequest>()
        var redirect = "https://www.twmanga.com/comic/chapter/test/0_1.html"
        var challenge = false
        val client = ExtensionRepositoryClient(http,
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY)
        val manager = PluginManager(
            client, InMemoryPluginPackageStore(), PluginVerifier(KeyValuePluginTrustStore(keys)),
            RhinoScriptPluginRuntimeFactory(), ScriptPluginEnvironment(
                PluginNetworkClient(object : PluginHttpTransport {
                    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("Must pin DNS")
                    override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse {
                        requests += request
                        return when {
                            challenge -> PluginHttpResponse(403, "<script>window._cf_chl_opt={}</script>".encodeToByteArray())
                            request.url.endsWith("/comic/long-series") -> PluginHttpResponse(200,
                                ("<div id=\"chapter-items\">" + (1..4000).joinToString("") { "<a href=\"/chapter/$it\">Chapter $it</a>" } + "</div>").encodeToByteArray())
                            request.url.contains("/user/page_direct") -> PluginHttpResponse(
                                302, ByteArray(0), mapOf("Location" to listOf(redirect)),
                            )
                            request.url.startsWith("https://www.twmanga.com/") -> PluginHttpResponse(200,
                                """<html><body><amp-img class="comic-contain__item" data-src="https://s1.bzcdn.net/scomic/internal/1.jpg"></amp-img></body></html>""".encodeToByteArray())
                            request.url == "https://s1.bzcdn.net/scomic/internal/1.jpg" -> PluginHttpResponse(200,
                                byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), mapOf("Content-Type" to listOf("image/jpeg")))
                            else -> error("Unexpected mock URL: ${request.url}")
                        }
                    }
                }, storage, hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                    requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) })),
                storage,
            ), eventGrantAdmission = KeyValuePluginEventGrantAdmission(keys, MutablePluginSystemEventAuthorizer()),
        )
        try {
            val entry = when (val index = client.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)) {
                is RepositoryIndex.Combined -> index.plugins.single { it.id == "zh.baozimh" }
                is RepositoryIndex.Plugins -> index.entries.single { it.id == "zh.baozimh" }
                else -> error("Expected package index")
            }
            assertEquals("1.0.9", entry.version)
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), entry)
            manager.approveCurrentEventGrantReview(entry.id, emptySet())
            val source = assertNotNull(manager.source(4_502_917L))
            val key = assertNotNull(manager.exactSourceKeyForLegacyId(source.id))
            assertFalse(manager.authorizeWebChallenge(key))
            val bound = assertNotNull(manager.storageForSource(source.id))
            // Even pre-existing synthetic jar state must be suppressed without COOKIE_STORAGE.
            bound.setCookie(source.id, PluginCookie("test", "source-only", "www.baozimh.com"))
            val chapter = SChapter(url = "/user/page_direct?comic_id=test&section_slot=0&chapter_slot=1")
            val pages = source.getPageList(chapter)
            assertEquals(listOf("https://s1.bzcdn.net/scomic/internal/1.jpg"), pages.map(Page::imageUrl))
            assertEquals(2, requests.size, "Public reader should work without app mirror fallback")
            assertTrue(requests.first().headers.keys.none { it.equals("Cookie", true) })
            assertTrue(requests.last().url.startsWith("https://www.twmanga.com/"))
            assertTrue(requests.last().headers.keys.none {
                it.equals("Cookie", true) || it.equals("Authorization", true) || it.equals("Origin", true)
            })
            val image = assertNotNull(manager.contentNetworkForSource(source.id))
            assertEquals(200, image.get(source.id, pages.single().imageUrl!!).status)
            assertTrue(requests.last().headers.keys.none { it.equals("Cookie", true) || it.equals("Authorization", true) })

            val longChapters = source.getChapterList(SManga(url = "https://www.baozimh.com/comic/long-series"))
            assertEquals(4000, longChapters.size)
            assertEquals("/chapter/1", longChapters.first().url)
            assertEquals("/chapter/4000", longChapters.last().url)
            requests.clear()
            challenge = true
            assertEquals("SHINSOU_SOURCE_HTTP_CHALLENGE", assertFailsWith<Throwable> {
                source.getPageList(chapter)
            }.sourceFailureMarker())
            assertEquals(1, requests.size, "Challenge must stop subsequent mirror requests")

            requests.clear()
            challenge = false
            redirect = "https://unreviewed.example/comic/chapter/test/0_1.html"
            // The source may use its already-reviewed explicit fallback, but the foreign redirect
            // must never reach the transport.
            source.getPageList(chapter)
            assertTrue(requests.none { it.url.startsWith("https://unreviewed.example/") })
        } finally {
            manager.close()
            http.close()
        }
    }
}
