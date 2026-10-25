package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeCapability
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JinmantiantangWebChallengeTest {
    @Test
    fun exactReviewedArtifactPinsCloudflareSubresourceAndRequiresClearance() = runTest {
        val repository = locateRepository() ?: return@runTest
        val http = HttpClient(MockEngine { request ->
            val relative = request.url.encodedPath.substringAfter("refs/heads/master/")
            respond(Files.readAllBytes(repository.resolve(relative)), HttpStatusCode.OK)
        })
        val keys = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keys)
        val sourceRequests = mutableListOf<PluginHttpRequest>()
        var responseStatus = 200
        var responseHtml = "<html><body>No matching test titles</body></html>"
        val client = ExtensionRepositoryClient(
            http,
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val manager = PluginManager(
            client, InMemoryPluginPackageStore(), PluginVerifier(KeyValuePluginTrustStore(keys)),
            RhinoScriptPluginRuntimeFactory(),
            ScriptPluginEnvironment(
                PluginNetworkClient(
                    object : PluginHttpTransport {
                        override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                            error("Expected pinned mock request")

                        override suspend fun executeResolved(
                            request: PluginHttpRequest,
                            resolution: PluginHostResolution,
                        ): PluginHttpResponse {
                            sourceRequests += request
                            return PluginHttpResponse(responseStatus, responseHtml.encodeToByteArray())
                        }
                    }, storage,
                    hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                ),
                storage,
            ),
            eventGrantAdmission = KeyValuePluginEventGrantAdmission(keys, MutablePluginSystemEventAuthorizer()),
        )
        val browse = PluginBrowseAdapter(
            manager, client, KeyValueExtensionRepositoryStore(keys), storage, keys,
            KeyValuePluginTrustStore(keys),
        )
        try {
            val entry = when (val index = client.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)) {
                is RepositoryIndex.Combined -> index.plugins.single { it.id == "zh.jinmantiantang" }
                is RepositoryIndex.Plugins -> index.entries.single { it.id == "zh.jinmantiantang" }
                is RepositoryIndex.Legacy -> error("Expected v2 repository")
            }
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), entry)
            manager.approveCurrentEventGrantReview(entry.id, emptySet())
            browse.setPluginUiAvailable(true)
            browse.refresh()
            val loaded = manager.catalogueSources()
            assertEquals(
                listOf(JM_SOURCE_ID),
                loaded.filter { it.id == JM_SOURCE_ID }.map { it.id },
                "Manager sources=${loaded.map { "${it.id}:${it.name}" }}",
            )
            val source = browse.state.value.sources.singleOrNull { it.id == JM_SOURCE_ID }
                ?: error("JM UI row missing; rows=${browse.state.value.sources.map { "${it.id}:${it.name}:${it.sourceKey}" }}")
            assertNull(source.sourceKey, "Legacy UI rows must not expose exact authority publicly")
            val managerOwnedKey = assertNotNull(manager.exactSourceKeyForLegacyId(source.id))
            assertEquals(entry.id, managerOwnedKey.packageId)
            assertEquals(source.id, managerOwnedKey.legacyLongId)
            assertNull(manager.exactSourceKeyForLegacyId(9_999_999L))
            assertFalse(browse.isSourceWebChallengeAvailable(9_999_999L))
            assertTrue(browse.isSourceWebChallengeAvailable(source.id))
            assertTrue(sourceRequests.isEmpty(), "Availability must not perform network requests")
            val request = assertNotNull(browse.sourceWebChallenge(source.id))
            assertEquals("https://18comic.vip/", request.url)
            assertEquals(setOf("https://18comic.vip"), request.allowedNavigationOrigins)
            assertEquals(
                setOf("https://18comic.vip", "https://challenges.cloudflare.com"),
                request.allowedSubresourceOrigins,
            )
            assertEquals("cf_clearance", request.requiredCookieName)
            assertNull(request.username)
            assertNull(request.password)

            assertFailsWith<IllegalArgumentException> {
                browse.importSourceWebChallengeSession(
                    source.id, request.capability,
                    listOf(SourceCookie("other", "value", "18comic.vip")), "JM-test-agent",
                )
            }
            assertTrue(storage.getCookies(source.id).isEmpty())

            val wrongOrigin = assertNotNull(browse.sourceWebChallenge(source.id))
            assertFailsWith<IllegalArgumentException> {
                browse.importSourceWebChallengeSession(
                    source.id, wrongOrigin.capability,
                    listOf(SourceCookie("cf_clearance", "value", "mirror.18comic.vip")), "JM-test-agent",
                )
            }
            assertTrue(storage.getCookies(source.id).isEmpty())

            for (invalidClearance in listOf(
                SourceCookie("cf_clearance", "expired", "18comic.vip", expiresAtEpochMillis = 1L),
                SourceCookie("cf_clearance", "wrong-path", "18comic.vip", path = "/unrelated"),
            )) {
                val invalidSession = assertNotNull(browse.sourceWebChallenge(source.id))
                assertFailsWith<IllegalArgumentException> {
                    browse.importSourceWebChallengeSession(
                        source.id, invalidSession.capability, listOf(invalidClearance), "JM-test-agent",
                    )
                }
                val boundStorage = assertNotNull(manager.storageForSource(source.id))
                assertTrue(boundStorage.getCookies(source.id).isEmpty())
                assertNull(boundStorage.getWebChallengeUserAgent(source.id))
            }

            val forged = SourceWebChallengeCapability()
            assertFailsWith<IllegalArgumentException> {
                browse.importSourceWebChallengeSession(
                    source.id, forged,
                    listOf(SourceCookie("cf_clearance", "value", "18comic.vip")), "JM-test-agent",
                )
            }

            val accepted = assertNotNull(browse.sourceWebChallenge(source.id))
            // Availability must not issue a new capability and revoke the open dialog.
            repeat(2) { assertTrue(browse.isSourceWebChallengeAvailable(source.id)) }
            browse.importSourceWebChallengeSession(
                source.id, accepted.capability,
                listOf(SourceCookie("cf_clearance", "reviewed", "18comic.vip")), "JM-test-agent",
            )
            val bound = assertNotNull(manager.storageForSource(source.id))
            assertEquals("reviewed", bound.getCookies(source.id).single().value)
            assertEquals("JM-test-agent", bound.getWebChallengeUserAgent(source.id))
            browse.browseSource(source.id, "no matching test titles", 1)
            val resumedHttp = sourceRequests.single()
            assertEquals("JM-test-agent", resumedHttp.headers.entries.single { it.key.equals("User-Agent", true) }.value)
            assertEquals("cf_clearance=reviewed", resumedHttp.headers.entries.single { it.key.equals("Cookie", true) }.value)
            assertTrue(resumedHttp.url.contains("page=1"), "UI page one must map to the website's first page")

            responseHtml = """
                <html><head><title>Test catalogue</title>
                <script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script></head><body>
                <div class="well well-sm"><a href="/album/300000" title="Test title">
                <img data-original="//cdn-msp.18comic.vip/media/albums/300000.jpg"></a>
                <span class="video-title">Test title</span></div>
                <a class="prevnext" href="/albums?o=mv&amp;page=2">下一頁</a></body></html>
            """.trimIndent()
            val catalogue = browse.browseSource(source.id, "", 1)
            assertEquals("Test title", catalogue.items.single().title)
            assertEquals("https://cdn-msp.18comic.vip/media/albums/300000_3x4.jpg", catalogue.items.single().thumbnailUrl)
            assertTrue(catalogue.hasNextPage)

            for (status in listOf(200, 403, 503)) {
                responseStatus = status
                responseHtml = "<html><script>window._cf_chl_opt={};</script></html>"
                val before = sourceRequests.size
                val error = assertFailsWith<Throwable> { browse.browseSource(source.id, "", 1) }
                assertEquals("SHINSOU_SOURCE_HTTP_CHALLENGE", error.sourceFailureMarker(), "HTTP $status")
                assertEquals(before + 1, sourceRequests.size, "Challenge must not trigger a duplicate request")
            }
            responseStatus = 200
            responseHtml = "<html><body>sorry, you have been blocked<script>window._cf_chl_opt={};</script></body></html>"
            assertEquals("SHINSOU_SOURCE_HTTP_BLOCKED", assertFailsWith<Throwable> {
                browse.browseSource(source.id, "", 1)
            }.sourceFailureMarker())
            responseHtml = " \n\t"
            assertEquals("SHINSOU_SOURCE_HTTP_UNAVAILABLE", assertFailsWith<Throwable> {
                browse.browseSource(source.id, "", 1)
            }.sourceFailureMarker())
            responseHtml = "<html><body>No matching test titles</body></html>"
            assertTrue(browse.browseSource(source.id, "no matching test titles", 1).items.isEmpty())

            val runtime = assertNotNull(manager.source(source.id))
            responseHtml = """
                <html><body><h1 itemprop="name">Test title</h1>
                <ul class="btn-toolbar"><li><a href="/photo/300000">Test chapter</a></li></ul></body></html>
            """.trimIndent()
            val manga = SManga(url = "/album/300000", title = "Test title")
            assertEquals("Test title", runtime.getMangaDetails(manga).title)
            val chapter = runtime.getChapterList(manga).single()
            assertEquals("/photo/300000", chapter.url)
            responseHtml = """
                <html><body><script>var scramble_id = 220980;</script>
                <div class="scramble-page"><img id="album_photo_0"
                data-original="//cdn-msp.18comic.vip/media/photos/300000/abc.jpg"></div></body></html>
            """.trimIndent()
            val readerPage = runtime.getPageList(chapter).single()
            assertEquals(
                "https://cdn-msp.18comic.vip/media/photos/300000/abc.jpg#Shinsou-JM-Scramble-Id=220980&Shinsou-JM-Photo-Id=300000&Shinsou-JM-Filename=abc",
                readerPage.imageUrl,
            )
            val resumed = assertNotNull(browse.sourceWebChallenge(source.id))
            assertEquals("reviewed", resumed.cookies.single { it.name == "cf_clearance" }.value)
            assertEquals("JM-test-agent", resumed.userAgent)
            assertFalse(resumed.allowedNavigationOrigins.contains("https://challenges.cloudflare.com"))
            manager.setEventSourceEnabled(managerOwnedKey, false)
            assertFalse(manager.authorizeWebChallenge(managerOwnedKey))
            assertFalse(browse.isSourceWebChallengeAvailable(source.id))
            assertFailsWith<IllegalArgumentException> { manager.issueWebChallenge(managerOwnedKey) }
            assertFailsWith<IllegalArgumentException> {
                browse.importSourceWebChallengeSession(
                    source.id, resumed.capability,
                    listOf(SourceCookie("cf_clearance", "stale", "18comic.vip")), "JM-test-agent",
                )
            }
            manager.setEventSourceEnabled(managerOwnedKey, true)
            assertTrue(manager.authorizeWebChallenge(managerOwnedKey))
            assertTrue(browse.isSourceWebChallengeAvailable(source.id))
        } finally {
            manager.close()
            http.close()
        }
    }

    private fun locateRepository(): Path? = listOfNotNull(
        Path.of("../shinsou_plugin"), Path.of("../../shinsou_plugin"),
        System.getProperty("shinsou.pluginRepo")?.let(Path::of),
    ).map(Path::toAbsolutePath).firstOrNull { Files.isRegularFile(it.resolve("index.json")) }

    private companion object {
        const val JM_SOURCE_ID: Long = 1_817_081L
    }
}
