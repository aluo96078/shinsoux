package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
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

/** Offline end-to-end coverage for the exact reviewed MyComic Rhino artifact. */
class MyComicWebChallengeTest {
    @Test
    fun exactArtifactImportsClearanceThenPreservesCatalogueDetailChapterAndPages() = runTest {
        val repository = locateRepository()
        val http = HttpClient(MockEngine { request ->
            val relative = request.url.encodedPath.substringAfter("refs/heads/master/")
            respond(Files.readAllBytes(repository.resolve(relative)), HttpStatusCode.OK)
        })
        val keys = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keys)
        val sourceRequests = mutableListOf<PluginHttpRequest>()
        var responseStatus = 200
        var responseHtml = NO_RESULTS_HTML
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
                            error("Expected DNS-pinned mock request")

                        override suspend fun executeResolved(
                            request: PluginHttpRequest,
                            resolution: PluginHostResolution,
                        ): PluginHttpResponse {
                            sourceRequests += request
                            return PluginHttpResponse(responseStatus, responseHtml.encodeToByteArray())
                        }
                    },
                    storage,
                    hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                ),
                storage,
            ),
            eventGrantAdmission = KeyValuePluginEventGrantAdmission(
                keys,
                MutablePluginSystemEventAuthorizer(),
            ),
        )
        val browse = PluginBrowseAdapter(
            manager, client, KeyValueExtensionRepositoryStore(keys), storage, keys,
            KeyValuePluginTrustStore(keys),
        )
        try {
            val entry = when (val index = client.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)) {
                is RepositoryIndex.Combined -> index.plugins.single { it.id == MYCOMIC_PACKAGE_ID }
                is RepositoryIndex.Plugins -> index.entries.single { it.id == MYCOMIC_PACKAGE_ID }
                is RepositoryIndex.Legacy -> error("Expected v2 repository")
            }
            assertEquals("1.0.2", entry.version)
            assertEquals(3, entry.versionCode)
            assertEquals(
                setOf(
                    PluginRuntimePermission.EXECUTE_SCRIPT,
                    PluginRuntimePermission.NETWORK,
                    PluginRuntimePermission.COOKIE_STORAGE,
                    PluginRuntimePermission.BROWSER_CHALLENGE,
                ),
                entry.runtimePermissions,
            )
            assertTrue("LOGIN" !in entry.capabilities)
            assertTrue(entry.requestedHostPermissions.isEmpty())

            val expectedSourceKey = SourceKey(
                packageId = MYCOMIC_PACKAGE_ID,
                sourceId = MYCOMIC_SOURCE_ID.toString(),
                legacyLongId = MYCOMIC_SOURCE_ID,
            )
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), entry)
            assertFalse(
                manager.authorizeWebChallenge(expectedSourceKey),
                "Installing a permission-bearing artifact must not approve browser authority",
            )
            manager.approveCurrentEventGrantReview(entry.id, emptySet())
            browse.setPluginUiAvailable(true)
            browse.refresh()
            val source = browse.state.value.sources.singleOrNull { it.id == MYCOMIC_SOURCE_ID }
                ?: error("MyComic UI row missing: ${browse.state.value.sources.map { it.id to it.name }}")
            assertNull(source.sourceKey, "Legacy UI rows must not expose exact authority publicly")
            val sourceKey = assertNotNull(manager.exactSourceKeyForLegacyId(source.id))
            assertEquals(expectedSourceKey, sourceKey)
            assertEquals(MYCOMIC_PACKAGE_ID, sourceKey.packageId)
            assertEquals(source.id, sourceKey.legacyLongId)
            assertNull(manager.exactSourceKeyForLegacyId(9_999_999L))
            assertFalse(browse.isSourceWebChallengeAvailable(9_999_999L))
            assertTrue(browse.isSourceWebChallengeAvailable(source.id))
            assertTrue(sourceRequests.isEmpty(), "Availability must remain a non-network predicate")

            responseStatus = 403
            responseHtml = CHALLENGE_HTML
            val challengeError = assertFailsWith<Throwable> {
                browse.browseSource(source.id, RETRY_QUERY, 1)
            }
            assertEquals("SHINSOU_SOURCE_HTTP_CHALLENGE", challengeError.sourceFailureMarker())
            assertEquals(1, sourceRequests.size, "A challenge response must not trigger an automatic retry")
            val challengedHttp = sourceRequests.single()
            assertEquals("https://mycomic.com/comics?q=no%20matching%20titles", challengedHttp.url)
            assertTrue(
                challengedHttp.headers.keys.none { it.equals("Cookie", true) },
                "The initial request must not invent a clearance cookie",
            )

            val request = assertNotNull(browse.sourceWebChallenge(source.id))
            assertEquals("https://mycomic.com/comics", request.url)
            assertEquals(setOf("https://mycomic.com"), request.allowedNavigationOrigins)
            assertEquals(
                setOf("https://mycomic.com", "https://challenges.cloudflare.com"),
                request.allowedSubresourceOrigins,
            )
            assertEquals("cf_clearance", request.requiredCookieName)
            assertNull(request.username)
            assertNull(request.password)
            assertTrue(request.localStorageKeys.isEmpty())
            assertTrue(request.requiredLocalStorageKeys.isEmpty())

            assertFailsWith<IllegalArgumentException> {
                browse.importSourceWebChallengeSession(
                    source.id,
                    request.capability,
                    listOf(SourceCookie("other", "value", "mycomic.com")),
                    "MyComic-test-agent",
                )
            }
            assertTrue(storage.getCookies(source.id).isEmpty())

            val wrongOrigin = assertNotNull(browse.sourceWebChallenge(source.id))
            assertFailsWith<IllegalArgumentException> {
                browse.importSourceWebChallengeSession(
                    source.id,
                    wrongOrigin.capability,
                    listOf(SourceCookie("cf_clearance", "value", "challenges.cloudflare.com")),
                    "MyComic-test-agent",
                )
            }
            assertTrue(storage.getCookies(source.id).isEmpty())

            for (invalidClearance in listOf(
                SourceCookie("cf_clearance", "expired", "mycomic.com", expiresAtEpochMillis = 1L),
                SourceCookie("cf_clearance", "wrong-path", "mycomic.com", path = "/unrelated"),
            )) {
                val invalidSession = assertNotNull(browse.sourceWebChallenge(source.id))
                assertFailsWith<IllegalArgumentException> {
                    browse.importSourceWebChallengeSession(
                        source.id,
                        invalidSession.capability,
                        listOf(invalidClearance),
                        "MyComic-test-agent",
                    )
                }
                val boundStorage = assertNotNull(manager.storageForSource(source.id))
                assertTrue(boundStorage.getCookies(source.id).isEmpty())
                assertNull(boundStorage.getWebChallengeUserAgent(source.id))
            }

            assertFailsWith<IllegalArgumentException> {
                browse.importSourceWebChallengeSession(
                    source.id,
                    SourceWebChallengeCapability(),
                    listOf(SourceCookie("cf_clearance", "value", "mycomic.com")),
                    "MyComic-test-agent",
                )
            }

            val accepted = assertNotNull(browse.sourceWebChallenge(source.id))
            repeat(2) { assertTrue(browse.isSourceWebChallengeAvailable(source.id)) }
            browse.importSourceWebChallengeSession(
                source.id,
                accepted.capability,
                listOf(SourceCookie("cf_clearance", "reviewed", "mycomic.com")),
                "MyComic-test-agent",
            )
            val bound = assertNotNull(manager.storageForSource(source.id))
            assertEquals("reviewed", bound.getCookies(source.id).single().value)
            assertEquals("MyComic-test-agent", bound.getWebChallengeUserAgent(source.id))

            responseStatus = 200
            responseHtml = NO_RESULTS_HTML
            browse.browseSource(source.id, RETRY_QUERY, 1)
            assertEquals(2, sourceRequests.size, "Only the explicit post-import retry should follow the challenge")
            val resumedHttp = sourceRequests.last()
            assertEquals(challengedHttp.url, resumedHttp.url, "Retry must preserve the challenged operation")
            assertEquals(
                "MyComic-test-agent",
                resumedHttp.headers.entries.single { it.key.equals("User-Agent", true) }.value,
            )
            assertEquals(
                "cf_clearance=reviewed",
                resumedHttp.headers.entries.single { it.key.equals("Cookie", true) }.value,
            )

            responseHtml = CATALOGUE_HTML
            val catalogue = browse.browseSource(source.id, "", 1)
            assertEquals("Synthetic MyComic", catalogue.items.single().title)
            assertEquals("/comics/synthetic-mycomic", catalogue.items.single().url)
            assertEquals("https://mycomic.com/covers/synthetic.webp", catalogue.items.single().thumbnailUrl)
            assertTrue(catalogue.hasNextPage)

            val runtime = assertNotNull(manager.source(source.id))
            val manga = SManga(url = "/comics/synthetic-mycomic", title = "Fallback")
            responseHtml = DETAIL_AND_CHAPTER_HTML
            val detail = runtime.getMangaDetails(manga)
            assertEquals("Synthetic MyComic", detail.title)
            assertEquals("Fixture Author", detail.author)
            assertEquals(listOf("冒險", "青年"), detail.genre)
            assertEquals(MangaStatus.ONGOING, detail.status)
            assertEquals("Synthetic description", detail.description)
            assertTrue(detail.initialized)
            val chapter = runtime.getChapterList(manga).single()
            assertEquals("/chapters/chapter-10", chapter.url)
            assertEquals("第 10 話", chapter.name)
            assertEquals("Fixture Group", chapter.scanlator)
            assertEquals(10.0, chapter.chapterNumber)

            responseHtml = PAGE_HTML
            val pages = runtime.getPageList(chapter)
            assertEquals(
                listOf(
                    "https://mycomic.com/images/page-1.webp",
                    "https://mycomic.com/images/page-2.webp",
                ),
                pages.map(Page::imageUrl),
            )

            val resumed = assertNotNull(browse.sourceWebChallenge(source.id))
            assertEquals("reviewed", resumed.cookies.single { it.name == "cf_clearance" }.value)
            assertEquals("MyComic-test-agent", resumed.userAgent)
            assertFalse("https://challenges.cloudflare.com" in resumed.allowedNavigationOrigins)
            manager.setEventSourceEnabled(sourceKey, false)
            assertFalse(manager.authorizeWebChallenge(sourceKey))
            assertFalse(browse.isSourceWebChallengeAvailable(source.id))
            assertFailsWith<IllegalArgumentException> { manager.issueWebChallenge(sourceKey) }
            assertFailsWith<IllegalArgumentException> {
                browse.importSourceWebChallengeSession(
                    source.id,
                    resumed.capability,
                    listOf(SourceCookie("cf_clearance", "stale", "mycomic.com")),
                    "MyComic-test-agent",
                )
            }
        } finally {
            manager.close()
            http.close()
        }
    }

    @Test
    fun structuredFailuresStayExplicitAndChallengeNeverRetriesSilently() = runTest {
        val repository = locateRepository()
        val script = Files.readString(repository.resolve("plugins/zh.mycomic.js"))
        val requests = mutableListOf<PluginHttpRequest>()
        var responseStatus = 200
        var responseHtml = NO_RESULTS_HTML
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            script,
            PluginManifest(
                id = MYCOMIC_PACKAGE_ID,
                name = "MyComic",
                version = "1.0.2",
                versionCode = 3,
                lang = "zh",
                nsfw = true,
                script = "zh.mycomic.js",
                signature = "",
                sources = listOf(SourceIndexEntry("MyComic", "zh", MYCOMIC_SOURCE_ID, "https://mycomic.com")),
            ),
            ScriptPluginEnvironment(
                PluginNetworkClient(
                    transport = object : PluginHttpTransport {
                        override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                            error("Expected DNS-pinned mock request")

                        override suspend fun executeResolved(
                            request: PluginHttpRequest,
                            resolution: PluginHostResolution,
                        ): PluginHttpResponse {
                            requests += request
                            return PluginHttpResponse(responseStatus, responseHtml.encodeToByteArray())
                        }
                    },
                    storage = storage,
                    policy = PluginNetworkPolicy(
                        requestOrigins = setOf("https://mycomic.com"),
                        credentialOrigins = setOf("https://mycomic.com"),
                    ),
                    hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                ),
                storage,
                runtimePermissions = setOf(
                    PluginRuntimePermission.EXECUTE_SCRIPT,
                    PluginRuntimePermission.NETWORK,
                ),
            ),
        )
        try {
            for (status in listOf(200, 403, 503)) {
                responseStatus = status
                responseHtml = CHALLENGE_HTML
                val before = requests.size
                val error = assertFailsWith<Throwable> { runtime.getPopularManga(0) }
                assertEquals("SHINSOU_SOURCE_HTTP_CHALLENGE", error.sourceFailureMarker(), "HTTP $status")
                assertEquals(before + 1, requests.size, "Challenge classification must not retry")
            }

            responseStatus = 200
            responseHtml = BLOCKED_HTML
            assertEquals(
                "SHINSOU_SOURCE_HTTP_BLOCKED",
                assertFailsWith<Throwable> { runtime.getPopularManga(0) }.sourceFailureMarker(),
            )

            responseStatus = 403
            responseHtml = "<html><body>Forbidden without a challenge marker</body></html>"
            assertEquals(
                "SHINSOU_SOURCE_HTTP_FORBIDDEN",
                assertFailsWith<Throwable> { runtime.getPopularManga(0) }.sourceFailureMarker(),
            )

            responseStatus = 503
            responseHtml = "<html><body>Temporary outage</body></html>"
            assertEquals(
                "SHINSOU_SOURCE_HTTP_UNAVAILABLE",
                assertFailsWith<Throwable> { runtime.getPopularManga(0) }.sourceFailureMarker(),
            )

            responseStatus = 200
            responseHtml = " \n\t"
            assertEquals(
                "SHINSOU_SOURCE_HTTP_UNAVAILABLE",
                assertFailsWith<Throwable> { runtime.getPopularManga(0) }.sourceFailureMarker(),
            )

            responseHtml = NORMAL_CLOUDFLARE_RESOURCE_HTML
            val normal = runtime.getPopularManga(0)
            assertTrue(normal.mangas.isEmpty())
            assertFalse(normal.hasNextPage)
        } finally {
            runtime.close()
        }
    }

    private fun locateRepository(): Path = requireNotNull(
        listOfNotNull(
            Path.of("../shinsou_plugin"),
            Path.of("../../shinsou_plugin"),
            System.getProperty("shinsou.pluginRepo")?.let(Path::of),
        ).map(Path::toAbsolutePath).firstOrNull { Files.isRegularFile(it.resolve("index.json")) },
    ) { "Sibling shinsou_plugin fixture repository was not found" }

    private companion object {
        const val MYCOMIC_PACKAGE_ID: String = "zh.mycomic"
        const val MYCOMIC_SOURCE_ID: Long = 9_119_537_447_562_549_661L
        const val RETRY_QUERY: String = "no matching titles"
        const val NO_RESULTS_HTML: String = "<html><body>No matching MyComic titles</body></html>"
        const val CHALLENGE_HTML: String =
            "<html><head><title>Just a moment...</title></head><body><script>window._cf_chl_opt={};</script></body></html>"
        const val BLOCKED_HTML: String =
            "<html><head><title>Attention Required! | Cloudflare</title></head><body><div id='cf-error-details'>Sorry, you have been blocked</div><script>window._cf_chl_opt={};</script></body></html>"
        const val NORMAL_CLOUDFLARE_RESOURCE_HTML: String =
            "<html><head><title>MyComic catalogue</title><script src='/cdn-cgi/challenge-platform/scripts/jsd/main.js'></script></head><body><div class='cf-turnstile'></div></body></html>"

        val CATALOGUE_HTML: String = """
            <html><head><title>MyComic catalogue</title>
            <script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script></head><body>
            <div class="grid"><div class="group">
              <a href="/comics/synthetic-mycomic"><img alt="Synthetic MyComic" data-src="/covers/synthetic.webp"></a>
            </div></div>
            <nav role="navigation"><a rel="next" href="/comics?page=2">Next</a></nav>
            </body></html>
        """.trimIndent()

        val DETAIL_AND_CHAPTER_HTML: String = """
            <html><head><meta name="description" content="Synthetic description"></head><body>
            <div data-flux-card>
              <div data-flux-heading>Synthetic MyComic</div>
              <img class="object-cover" src="/covers/detail.webp">
              <span data-flux-badge>連載中</span>
              <a href="/comics?filter%5Bauthor%5D=fixture">Fixture Author</a>
              <a href="/comics?filter%5Btag%5D=adventure">冒險</a>
              <a href="/comics?filter%5Baudience%5D=young">青年</a>
            </div>
            <div>
              <div x-data="{chapters: [{&quot;id&quot;:&quot;chapter-10&quot;,&quot;title&quot;:&quot;第 10 話&quot;}]}">
                <div><div>Fixture Group</div></div>
              </div>
            </div>
            <time datetime="2026-09-08"></time>
            </body></html>
        """.trimIndent()

        val PAGE_HTML: String = """
            <html><body>
            <img x-ref="page" data-src="/images/page-1.webp">
            <img class="page" src="https://mycomic.com/images/page-2.webp">
            <img class="page" src="data:image/gif;base64,ignored">
            <img class="page" src="https://mycomic.com/images/page-2.webp">
            </body></html>
        """.trimIndent()
    }
}
