package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.WebChallengeEmbeddedPolicy
import dev.shinsou.kmp.ui.challenge.allowsWebChallengeCapture
import dev.shinsou.kmp.ui.challenge.allowsWebChallengeNavigation
import dev.shinsou.kmp.ui.challenge.allowsWebChallengeSubresource
import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficialPluginCompatibilityTest {
    @Test
    fun bikaContentFollowsExactCdnRedirectWithoutCredentials() = runTest {
        val entry = localIndexEntry(requireNotNull(locateRepository()), "zh.bika")
        val policy = assertNotNull(OfficialShinsouReviewedCatalog.matchReviewedRepositoryEntry(
            entry, ReviewedLocalRepositoryPolicy.EXACT_LOOPBACK_18081,
            REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL,
        )).networkPolicy
        val requests = mutableListOf<PluginHttpRequest>()
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        storage.setCookie(8_123_456L, PluginCookie("sid", "synthetic", "picacomic.com"))
        val network = PluginNetworkClient(
            object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("Pin required")
                override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse {
                    requests += request
                    return if (io.ktor.http.Url(request.url).host != "img.picacomic.com") {
                        PluginHttpResponse(302, ByteArray(0), mapOf("Location" to listOf("https://img.picacomic.com/static/test.jpg")))
                    } else PluginHttpResponse(200, byteArrayOf(1, 2, 3))
                }
            }, storage, hostResolver = PluginHostResolver { listOf("93.184.216.34") },
        ).scopedToContentPolicy(policy)
        for (host in listOf("storage1.picacomic.com", "storage-b.picacomic.com")) {
            assertEquals(200, network.get(8_123_456L, "https://$host/static/test.jpg").status)
        }
        assertEquals(4, requests.size)
        assertTrue(requests.all { request -> request.headers.keys.none {
            it.equals("Cookie", true) || it.equals("Authorization", true)
        } })
        for (url in listOf("https://unreviewed.picacomic.com/a", "https://img.picacomic.com.evil.example/a")) {
            assertFailsWith<IllegalArgumentException> { network.get(8_123_456L, url) }
        }
        val resolver = PluginHostResolver { listOf("93.184.216.34") }
        assertFailsWith<IllegalArgumentException> {
            policy.validate(io.ktor.http.Url("https://img.picacomic.com/a"), resolver)
        }
    }

    @Test
    fun exactReviewedBikaIssuesImportsAndRevokesBrowserChallengeCapability() = runTest {
        val repositoryFiles = requireNotNull(locateRepository()) { "Bika repository fixture is required" }
        val http = HttpClient(MockEngine { request ->
            val relative = request.url.encodedPath.substringAfter("refs/heads/master/")
            respond(Files.readAllBytes(repositoryFiles.resolve(relative)), HttpStatusCode.OK)
        })
        val client = ExtensionRepositoryClient(
            http,
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val keys = InMemoryPluginKeyValueStore()
        val packages = InMemoryPluginPackageStore()
        val storage = KeyValuePluginStorage(keys)
        val manager = PluginManager(
            repositoryClient = client,
            packageStore = packages,
            verifier = PluginVerifier(KeyValuePluginTrustStore(keys)),
            runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                    storage,
                    hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                ),
                storage = storage,
            ),
            eventGrantAdmission = KeyValuePluginEventGrantAdmission(
                keys,
                MutablePluginSystemEventAuthorizer(),
            ),
        )
        val sourceKey = SourceKey(2, "zh.bika", "8123456", 8_123_456L)
        try {
            val entry = when (val index = client.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)) {
                is RepositoryIndex.Combined -> index.plugins.single { it.id == "zh.bika" }
                is RepositoryIndex.Plugins -> index.entries.single { it.id == "zh.bika" }
                is RepositoryIndex.Legacy -> error("Expected official v2 repository")
            }
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), entry)
            manager.approveCurrentEventGrantReview(
                entry.id,
                setOf(PluginHostPermission.REQUEST_LOGIN_UI),
            )

            assertTrue(manager.authorizeWebChallenge(sourceKey))
            val authorization = assertNotNull(manager.issueWebChallenge(sourceKey))
            assertEquals("https://manhuabika.com", authorization.canonicalOrigin)
            assertEquals(
                setOf("https://manhuabika.com", "https://picaapi.go2778.com"),
                authorization.allowedSubresourceOrigins,
            )
            val browserRequest = SourceWebChallengeRequest(
                capability = authorization.capability,
                sourceId = 8_123_456L,
                sourceName = "哔咔漫画",
                url = authorization.canonicalOrigin,
                userAgent = "fixture-agent",
                embeddedPolicy = WebChallengeEmbeddedPolicy.ALLOW_REVIEWED_ORIGINS,
                allowedNavigationOrigins = setOf(authorization.canonicalOrigin),
                allowedSubresourceOrigins = authorization.allowedSubresourceOrigins,
            )
            assertTrue(browserRequest.allowsWebChallengeSubresource("https://picaapi.go2778.com/"))
            assertFalse(browserRequest.allowsWebChallengeNavigation("https://picaapi.go2778.com/"))
            assertFalse(browserRequest.allowsWebChallengeCapture("https://picaapi.go2778.com/"))
            assertFalse(browserRequest.allowsWebChallengeSubresource("https://picaapi.go2778.com.evil.test/"))
            assertFalse(browserRequest.allowsWebChallengeSubresource("http://picaapi.go2778.com/"))
            assertFalse(browserRequest.allowsWebChallengeSubresource("https://127.0.0.1/"))
            assertEquals(setOf("token", "nonce"), authorization.localStorageKeys)
            assertEquals(setOf("token"), authorization.requiredLocalStorageKeys)
            manager.importWebChallengeSession(
                sourceKey = sourceKey,
                capability = authorization.capability,
                cookies = listOf(
                    dev.shinsou.kmp.ui.SourceCookie(
                        name = "session",
                        value = "reviewed-cookie",
                        domain = "manhuabika.com",
                    ),
                ),
                userAgent = "reviewed-browser-agent",
                localStorage = mapOf("token" to "reviewed-token", "nonce" to "reviewed-nonce"),
            )
            val sourceStorage = assertNotNull(manager.storageForSource(8_123_456L))
            assertEquals("reviewed-token", sourceStorage.getPreference(8_123_456L, "token"))
            assertEquals("reviewed-nonce", sourceStorage.getPreference(8_123_456L, "nonce"))
            assertEquals("reviewed-browser-agent", sourceStorage.getWebChallengeUserAgent(8_123_456L))
            assertEquals("reviewed-cookie", sourceStorage.getCookies(8_123_456L).single().value)

            val tokenOnly = assertNotNull(manager.issueWebChallenge(sourceKey))
            manager.importWebChallengeSession(
                sourceKey, tokenOnly.capability, emptyList(), "reviewed-browser-agent",
                mapOf("token" to "token-without-nonce"),
            )
            assertEquals("token-without-nonce", sourceStorage.getPreference(8_123_456L, "token"))
            val missingToken = assertNotNull(manager.issueWebChallenge(sourceKey))
            assertFailsWith<IllegalArgumentException> {
                manager.importWebChallengeSession(
                    sourceKey, missingToken.capability, emptyList(), "reviewed-browser-agent",
                    mapOf("nonce" to "nonce-without-token"),
                )
            }
            assertEquals("token-without-nonce", sourceStorage.getPreference(8_123_456L, "token"))

            val forbiddenStorage = assertNotNull(manager.issueWebChallenge(sourceKey))
            assertFailsWith<IllegalArgumentException> {
                manager.importWebChallengeSession(
                    sourceKey,
                    forbiddenStorage.capability,
                    emptyList(),
                    "reviewed-browser-agent",
                    mapOf("token" to "token", "nonce" to "nonce", "unexpected" to "secret"),
                )
            }
            assertNull(sourceStorage.getPreference(8_123_456L, "unexpected"))

            val crossOriginCookie = assertNotNull(manager.issueWebChallenge(sourceKey))
            assertFailsWith<IllegalArgumentException> {
                manager.importWebChallengeSession(
                    sourceKey,
                    crossOriginCookie.capability,
                    listOf(
                        dev.shinsou.kmp.ui.SourceCookie(
                            name = "session",
                            value = "foreign-cookie",
                            domain = "picaapi.go2778.com",
                        ),
                    ),
                    "reviewed-browser-agent",
                    mapOf("token" to "token", "nonce" to "nonce"),
                )
            }
            assertTrue(storage.getCookies(8_123_456L).none { it.value == "foreign-cookie" })

            val stale = assertNotNull(manager.issueWebChallenge(sourceKey))
            manager.loadInstalled() // Runtime replacement advances the capability epoch.
            assertFailsWith<IllegalArgumentException> {
                manager.importWebChallengeSession(
                    sourceKey,
                    stale.capability,
                    emptyList(),
                    "reviewed-browser-agent",
                    mapOf("token" to "stale", "nonce" to "stale"),
                )
            }

            val revoked = assertNotNull(manager.issueWebChallenge(sourceKey))
            manager.setPluginTrusted("zh.bika", false)
            assertFailsWith<IllegalArgumentException> {
                manager.importWebChallengeSession(
                    sourceKey,
                    revoked.capability,
                    emptyList(),
                    "reviewed-browser-agent",
                    mapOf("token" to "revoked", "nonce" to "revoked"),
                )
            }
        } finally {
            manager.close()
            http.close()
        }
    }

    @Test
    fun reviewedOfficialContentPolicySurvivesLocalRepositoryMirror() = runTest {
        val repositoryFiles = locateRepository() ?: return@runTest
        val entry = localIndexEntry(repositoryFiles, "zh.komiic")
        val script = Files.readAllBytes(repositoryFiles.resolve(entry.scriptUrl))
        val source = requireNotNull(entry.sources).single()
        val stored = StoredPlugin(
            metadata = InstalledPluginMetadata(
                manifest = PluginManifest(
                    id = entry.id, name = entry.name, version = entry.version, versionCode = entry.versionCode,
                    lang = entry.lang, script = "${entry.id}.js", signature = Sha256.hex(script),
                    sources = listOf(source), contentKinds = entry.contentKinds,
                    contract = entry.contract, runtime = entry.runtime, nsfw = entry.nsfw == 1,
                    requestedHostPermissions = entry.requestedHostPermissions,
                    runtimePermissions = entry.runtimePermissions,
                    systemEvents = entry.systemEvents, sidecarUrl = entry.sidecarUrl,
                ),
                repositoryBaseUrl = "http://127.0.0.1:18082",
                installedSha256 = Sha256.hex(script),
            ),
            scriptBytes = script,
        )
        val policy = requireNotNull(OfficialShinsouReviewedCatalog.matchContent(stored, source)).networkPolicy
        assertTrue("https://public.komiic.com" in policy.contentOrigins)
        assertTrue("https://img.komiic.com" in policy.contentOrigins)
        assertTrue(policy.credentialOrigins.none { it == "https://public.komiic.com" })
    }

    @Test
    fun reviewedOfficialPackageLoadsOnRestartWithoutOpeningGenericTrustStore() = runTest {
        val repositoryFiles = locateRepository() ?: return@runTest
        val http = HttpClient(MockEngine { request ->
            val relative = request.url.encodedPath.substringAfter("refs/heads/master/")
            respond(Files.readAllBytes(repositoryFiles.resolve(relative)), HttpStatusCode.OK)
        })
        val keyValues = InMemoryPluginKeyValueStore()
        val packageStore = InMemoryPluginPackageStore()
        val storage = KeyValuePluginStorage(keyValues)
        val indexClient = ExtensionRepositoryClient(
            client = http,
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val entry = when (val index = indexClient.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)) {
            is RepositoryIndex.Combined -> index.plugins.single { it.id == "eh.ehentai" }
            is RepositoryIndex.Plugins -> index.entries.single { it.id == "eh.ehentai" }
            is RepositoryIndex.Legacy -> error("Expected V2 official package")
        }
        fun manager(verifier: PluginVerifier): PluginManager = PluginManager(
            repositoryClient = indexClient,
            packageStore = packageStore,
            verifier = verifier,
            runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                    storage = storage,
                    hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                ),
                storage = storage,
            ),
            eventGrantAdmission = KeyValuePluginEventGrantAdmission(
                keyValues,
                MutablePluginSystemEventAuthorizer(),
            ),
        )
        val first = manager(PluginVerifier(KeyValuePluginTrustStore(keyValues)))
        try {
            first.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), entry)
            first.approveCurrentEventGrantReview(entry.id, emptySet())
        } finally {
            first.close()
        }

        // The exact built-in review plus the event approval is sufficient. A cold restart must
        // not be made unavailable just because the unrelated generic trust-token store is locked.
        val restarted = manager(PluginVerifier(TrustStoreUnavailableForTest()))
        try {
            assertEquals(1, restarted.loadInstalled().size)
            assertEquals("eh.ehentai", (restarted.source(6_912_170L) as ScriptPluginRuntime).pluginId)
        } finally {
            restarted.close()
            http.close()
        }
    }

    @Test
    fun reviewedOfficialEhentaiInstallsInertThenStartsInProductionRhinoAfterExactApproval() = runTest {
        val repositoryFiles = locateRepository() ?: return@runTest
        val http = HttpClient(MockEngine { request ->
                val relative = request.url.encodedPath.substringAfter("refs/heads/master/")
                val file = repositoryFiles.resolve(relative)
                check(Files.isRegularFile(file)) { "Unexpected official fixture request: $relative" }
                respond(Files.readAllBytes(file), HttpStatusCode.OK)
            })
        val client = ExtensionRepositoryClient(
            client = http,
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val packageStore = InMemoryPluginPackageStore()
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val contentRequests = mutableListOf<PluginHttpRequest>()
        val manager = PluginManager(
            repositoryClient = client,
            packageStore = packageStore,
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = object : PluginHttpTransport {
                        override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                            error("Official compatibility test requires resolved transport")

                        override suspend fun executeResolved(
                            request: PluginHttpRequest,
                            resolution: PluginHostResolution,
                        ): PluginHttpResponse {
                            contentRequests += request
                            return PluginHttpResponse(200, byteArrayOf(1))
                        }
                    },
                    storage = storage,
                    hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                ),
                storage = storage,
            ),
            eventGrantAdmission = KeyValuePluginEventGrantAdmission(
                keyValues,
                MutablePluginSystemEventAuthorizer(),
            ),
        )
        try {
            val index = client.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)
            val entry = when (index) {
                is RepositoryIndex.Combined -> index.plugins
                is RepositoryIndex.Plugins -> index.entries
                is RepositoryIndex.Legacy -> emptyList()
            }.single { it.id == "eh.ehentai" }
            val repository = ExtensionRepository(
                "  $OFFICIAL_SHINSOU_REPOSITORY_BASE_URL///  ",
                "Official Shinsou",
            )

            manager.install(repository, entry)
            assertEquals(null, manager.source(6_912_170L), "Unapproved artifact must remain inert")

            manager.approveCurrentEventGrantReview(entry.id, emptySet())

            val source = requireNotNull(manager.source(6_912_170L))
            assertEquals("eh.ehentai", (source as ScriptPluginRuntime).pluginId)
            assertEquals("E-Hentai", source.name)

            val content = requireNotNull(manager.contentNetworkForSource(6_912_170L))
            listOf(
                "https://e-hentai.org/gallery/1",
                "https://api.e-hentai.org/api.php",
                "https://ehgt.org/cover.jpg",
                "https://abc.hath.network/image.jpg",
            ).forEach { content.get(6_912_170L, it) }
            assertFailsWith<IllegalArgumentException> {
                content.get(6_912_170L, "https://unrelated.example/image.jpg")
            }
            assertEquals(
                listOf(
                    "https://e-hentai.org/gallery/1",
                    "https://api.e-hentai.org/api.php",
                    "https://ehgt.org/cover.jpg",
                    "https://abc.hath.network/image.jpg",
                ),
                contentRequests.map(PluginHttpRequest::url),
            )
        } finally {
            manager.close()
            http.close()
        }
    }

    @Test
    fun reviewedOfficialImageCdnsAreAvailableOnlyThroughContentPlane() = runTest {
        val repositoryFiles = locateRepository() ?: return@runTest
        val http = HttpClient(MockEngine { request ->
            val relative = request.url.encodedPath.substringAfter("refs/heads/master/")
            val file = repositoryFiles.resolve(relative)
            check(Files.isRegularFile(file)) { "Unexpected official fixture request: $relative" }
            respond(Files.readAllBytes(file), HttpStatusCode.OK)
        })
        val client = ExtensionRepositoryClient(
            client = http,
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val packageStore = InMemoryPluginPackageStore()
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val contentRequests = mutableListOf<PluginHttpRequest>()
        val manager = PluginManager(
            repositoryClient = client,
            packageStore = packageStore,
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = object : PluginHttpTransport {
                        override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                            error("Reviewed CDN test requires resolved transport")

                        override suspend fun executeResolved(
                            request: PluginHttpRequest,
                            resolution: PluginHostResolution,
                        ): PluginHttpResponse {
                            contentRequests += request
                            return PluginHttpResponse(200, byteArrayOf(1))
                        }
                    },
                    storage = storage,
                    hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                ),
                storage = storage,
            ),
            eventGrantAdmission = KeyValuePluginEventGrantAdmission(
                keyValues,
                MutablePluginSystemEventAuthorizer(),
            ),
        )
        try {
            val index = client.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)
            val entries = when (index) {
                is RepositoryIndex.Combined -> index.plugins
                is RepositoryIndex.Plugins -> index.entries
                is RepositoryIndex.Legacy -> emptyList()
            }
            val nhentaiEntry = entries.single { it.id == "all.nhentai" }
            val baoziEntry = entries.single { it.id == "zh.baozimh" }
            val komiicEntry = entries.single { it.id == "zh.komiic" }
            val wnacgEntry = entries.single { it.id == "zh.wnacg" }
            val dm5Entry = entries.single { it.id == "zh.dm5" }
            val mangaCopyEntry = entries.single { it.id == "zh.mangacopy" }
            val jmEntry = entries.single { it.id == "zh.jinmantiantang" }
            val manhuaguiEntry = entries.single { it.id == "zh.manhuagui" }
            val manhuarenEntry = entries.single { it.id == "zh.manhuaren" }

            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), nhentaiEntry)
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), baoziEntry)
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), komiicEntry)
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), wnacgEntry)
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), dm5Entry)
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), mangaCopyEntry)
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), jmEntry)
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), manhuaguiEntry)
            manager.install(ExtensionRepository(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL, "Official"), manhuarenEntry)
            manager.approveCurrentEventGrantReview(nhentaiEntry.id, emptySet())
            manager.approveCurrentEventGrantReview(baoziEntry.id, emptySet())
            manager.approveCurrentEventGrantReview(komiicEntry.id, emptySet())
            manager.approveCurrentEventGrantReview(wnacgEntry.id, emptySet())
            manager.approveCurrentEventGrantReview(dm5Entry.id, setOf(PluginHostPermission.REQUEST_LOGIN_UI))
            manager.approveCurrentEventGrantReview(mangaCopyEntry.id, emptySet())
            manager.approveCurrentEventGrantReview(jmEntry.id, emptySet())
            manager.approveCurrentEventGrantReview(manhuaguiEntry.id, emptySet())
            manager.approveCurrentEventGrantReview(manhuarenEntry.id, emptySet())

            val nhentaiContent = requireNotNull(manager.contentNetworkForSource(7_309_872L))
            nhentaiContent.get(7_309_872L, "https://t1.nhentai.net/galleries/1/cover.jpg")
            nhentaiContent.get(7_309_872L, "https://i4.nhentai.net/galleries/1/1.jpg")
            assertFailsWith<IllegalArgumentException> {
                nhentaiContent.get(7_309_872L, "https://t5.nhentai.net/galleries/1/cover.jpg")
            }

            val baoziContent = requireNotNull(manager.contentNetworkForSource(4_502_917L))
            baoziContent.get(4_502_917L, "https://s1.bzcdn.net/scomic/book/1.jpg")
            baoziContent.get(4_502_917L, "https://s1.baozicdn.com/w640/scomic/book/1.jpg")
            assertFailsWith<IllegalArgumentException> {
                baoziContent.get(4_502_917L, "https://evil.example/scomic/book/1.jpg")
            }
            baoziContent.get(4_502_917L, "https://static-tw.baozimh.com/cover/book.jpg")

            val dm5Content = requireNotNull(manager.contentNetworkForSource(3_947_628L))
            dm5Content.get(3_947_628L, "https://mhfm3tw.cdndm5.com/74/73441/cover.jpg")
            dm5Content.get(3_947_628L, "https://manhua1033zjcdn79.cdndm5.com/reader/page.jpg")
            assertFailsWith<IllegalArgumentException> {
                dm5Content.get(3_947_628L, "https://css79tw.cdndm5.com/cover.jpg")
            }
            assertFailsWith<IllegalArgumentException> {
                dm5Content.get(3_947_628L, "https://manhua9999zjcdn99.cdndm5.com/reader/page.jpg")
            }

            val mangaCopyContent = requireNotNull(manager.contentNetworkForSource(6_696_312_508_930_833_206L))
            mangaCopyContent.get(
                6_696_312_508_930_833_206L,
                "https://sg.mangafunb.fun/g/book/cover/1.jpg",
            )
            assertFailsWith<IllegalArgumentException> {
                mangaCopyContent.get(
                    6_696_312_508_930_833_206L,
                    "https://evil.mangafunb.fun/g/book/cover/1.jpg",
                )
            }

            val komiicContent = requireNotNull(manager.contentNetworkForSource(8_104_923L))
            komiicContent.get(8_104_923L, "https://public.komiic.com/comics/book/cover.jpg")
            komiicContent.get(8_104_923L, "https://img.komiic.com/comics/book/chapter/01/000.jpg")
            assertFailsWith<IllegalArgumentException> {
                komiicContent.get(8_104_923L, "https://cdn.komiic.com/comics/book/cover.jpg")
            }

            val jmContent = requireNotNull(manager.contentNetworkForSource(1_817_081L))
            jmContent.get(1_817_081L, "https://cdn-msp2.18comic.vip/media/albums/cover.jpg")
            jmContent.get(1_817_081L, "https://cdn-msp3.18comic.vip/media/albums/cover.jpg")
            assertFailsWith<IllegalArgumentException> {
                jmContent.get(1_817_081L, "https://cdn-msp4.18comic.vip/media/albums/cover.jpg")
            }
            val manhuaguiContent = requireNotNull(manager.contentNetworkForSource(6_301_748L))
            manhuaguiContent.get(6_301_748L, "https://cf.mhgui.com/cpic/b/12912_60.jpg")
            val manhuarenContent = requireNotNull(manager.contentNetworkForSource(3_616_827_811_449_702_173L))
            manhuarenContent.get(
                3_616_827_811_449_702_173L,
                "https://manhua1041zjcdn63.cdndm5.com/96/95798/1828834/1.jpg",
            )

            val wnacgContent = requireNotNull(manager.contentNetworkForSource(5_209_831L))
            wnacgContent.get(5_209_831L, "https://img5.qy0.ru/gallery/1.jpg")
            wnacgContent.get(5_209_831L, "https://t4.qy0.ru/data/t/3825/64/cover.webp")
            assertFailsWith<IllegalArgumentException> {
                wnacgContent.get(5_209_831L, "https://evil.example/gallery/1.jpg")
            }
            assertEquals(
                listOf(
                    "https://t1.nhentai.net/galleries/1/cover.jpg",
                    "https://i4.nhentai.net/galleries/1/1.jpg",
                    "https://s1.bzcdn.net/scomic/book/1.jpg",
                    "https://s1.baozicdn.com/w640/scomic/book/1.jpg",
                    "https://static-tw.baozimh.com/cover/book.jpg",
                    "https://mhfm3tw.cdndm5.com/74/73441/cover.jpg",
                    "https://manhua1033zjcdn79.cdndm5.com/reader/page.jpg",
                    "https://sg.mangafunb.fun/g/book/cover/1.jpg",
                    "https://public.komiic.com/comics/book/cover.jpg",
                    "https://img.komiic.com/comics/book/chapter/01/000.jpg",
                    "https://cdn-msp2.18comic.vip/media/albums/cover.jpg",
                    "https://cdn-msp3.18comic.vip/media/albums/cover.jpg",
                    "https://cf.mhgui.com/cpic/b/12912_60.jpg",
                    "https://manhua1041zjcdn63.cdndm5.com/96/95798/1828834/1.jpg",
                    "https://img5.qy0.ru/gallery/1.jpg",
                    "https://t4.qy0.ru/data/t/3825/64/cover.webp",
                ),
                contentRequests.map(PluginHttpRequest::url),
            )
        } finally {
            manager.close()
            http.close()
        }
    }

    @Test
    fun localMirrorKeepsExactArtifactContentCdnsWithoutReceivingExecutionTrust() = runTest {
        val repositoryFiles = locateRepository() ?: return@runTest
        val packageStore = InMemoryPluginPackageStore()
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val requests = mutableListOf<PluginHttpRequest>()
        val client = ExtensionRepositoryClient(
            HttpClient(MockEngine { error("repository I/O is not expected") }),
        )
        val manager = PluginManager(
            repositoryClient = client,
            packageStore = packageStore,
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = CapturingLocalMirrorRuntimeFactory(),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    transport = object : PluginHttpTransport {
                        override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                            error("Local-mirror content test requires resolved transport")

                        override suspend fun executeResolved(
                            request: PluginHttpRequest,
                            resolution: PluginHostResolution,
                        ): PluginHttpResponse {
                            requests += request
                            return PluginHttpResponse(200, byteArrayOf(1))
                        }
                    },
                    storage = storage,
                    hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                ),
                storage = storage,
            ),
            executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
        )
        try {
            listOf("zh.komiic", "eh.ehentai").forEach { packageId ->
                val entry = localIndexEntry(repositoryFiles, packageId)
                val scriptBytes = Files.readAllBytes(repositoryFiles.resolve(entry.scriptUrl))
                val manifest = PluginManifest(
                    id = entry.id,
                    name = entry.name,
                    version = entry.version,
                    versionCode = entry.versionCode,
                    lang = entry.lang,
                    nsfw = entry.nsfw == 1,
                    script = "${entry.id}.js",
                    signature = requireNotNull(entry.sha256),
                    sources = entry.sources,
                    systemEvents = entry.systemEvents,
                    requestedHostPermissions = entry.requestedHostPermissions,
                    runtimePermissions = entry.runtimePermissions,
                    contentKinds = entry.contentKinds,
                    contract = entry.contract,
                    runtime = entry.runtime,
                    sidecarUrl = entry.sidecarUrl,
                )
                packageStore.put(
                    StoredPlugin(
                        InstalledPluginMetadata(
                            manifest = manifest,
                            repositoryBaseUrl = "http://127.0.0.1:18082",
                            installedSha256 = requireNotNull(entry.sha256),
                            // Represents a separately admitted/isolatable generic runtime. The
                            // first manager below still proves the local URL receives no host
                            // in-process review provenance.
                            legacyTrustOnInstall = true,
                        ),
                        scriptBytes,
                    ),
                )
            }

            // Loading stays inert: a mirror URL must not mint in-process script provenance.
            assertTrue(manager.loadInstalled().isEmpty())
            assertNull(manager.source(8_104_923L))
            assertNull(manager.source(6_912_170L))

            // Publish metadata ownership as it exists once a separately admitted runtime is live;
            // the content matcher still requires the complete exact manifest and script digest.
            val liveFactory = NoopScriptPluginRuntimeFactory
            val liveManager = PluginManager(
                repositoryClient = client,
                packageStore = packageStore,
                verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
                runtimeFactory = liveFactory,
                environment = managerEnvironment(storage, requests),
                executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
            )
            try {
                assertEquals(2, liveManager.loadInstalled().size)
                val komiic = requireNotNull(liveManager.contentNetworkForSource(8_104_923L))
                komiic.get(8_104_923L, "https://public.komiic.com/comics/book/cover.jpg")
                komiic.get(8_104_923L, "https://img.komiic.com/comics/book/page.webp")
                val exactKomiicScope = requireNotNull(
                    liveManager.contentNetworkScopeForSource(
                        SourceKey.fromLegacy("zh.komiic", 8_104_923L),
                    ),
                )
                exactKomiicScope.network.get(
                    exactKomiicScope.sourceId,
                    "https://public.komiic.com/comics/book/detail-cover.jpg",
                )
                assertNull(
                    liveManager.contentNetworkScopeForSource(
                        SourceKey(
                            packageId = "zh.komiic",
                            sourceId = "forged-source",
                            legacyLongId = 8_104_923L,
                        ),
                    ),
                )
                assertFailsWith<IllegalArgumentException> {
                    komiic.get(8_104_923L, "https://evil.example/page.webp")
                }
                val ehentai = requireNotNull(liveManager.contentNetworkForSource(6_912_170L))
                ehentai.get(6_912_170L, "https://ehgt.org/cover.jpg")
                assertFailsWith<IllegalArgumentException> {
                    ehentai.get(6_912_170L, "https://evil.example/cover.jpg")
                }
                assertEquals(
                    listOf(
                        "https://public.komiic.com/comics/book/cover.jpg",
                        "https://img.komiic.com/comics/book/page.webp",
                        "https://public.komiic.com/comics/book/detail-cover.jpg",
                        "https://ehgt.org/cover.jpg",
                    ),
                    requests.map(PluginHttpRequest::url),
                )
            } finally {
                liveManager.close()
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun localMirrorContentReviewFailsClosedOnAnyManifestOrByteMismatch() {
        val repositoryFiles = locateRepository() ?: return
        val entry = localIndexEntry(repositoryFiles, "zh.komiic")
        val scriptBytes = Files.readAllBytes(repositoryFiles.resolve(entry.scriptUrl))
        val digest = requireNotNull(entry.sha256)
        val manifest = PluginManifest(
            id = entry.id,
            name = entry.name,
            version = entry.version,
            versionCode = entry.versionCode,
            lang = entry.lang,
            nsfw = entry.nsfw == 1,
            script = "${entry.id}.js",
            signature = digest,
            sources = entry.sources,
            systemEvents = entry.systemEvents,
            requestedHostPermissions = entry.requestedHostPermissions,
            runtimePermissions = entry.runtimePermissions,
            contentKinds = entry.contentKinds,
            contract = entry.contract,
            runtime = entry.runtime,
            sidecarUrl = entry.sidecarUrl,
        )
        fun stored(candidateManifest: PluginManifest, bytes: ByteArray = scriptBytes) = StoredPlugin(
            InstalledPluginMetadata(
                manifest = candidateManifest,
                repositoryBaseUrl = "http://127.0.0.1:18082",
                installedSha256 = digest,
            ),
            bytes,
        )
        val source = requireNotNull(manifest.sources).single()

        assertTrue(OfficialShinsouReviewedCatalog.matchContent(stored(manifest), source) != null)
        assertNull(
            OfficialShinsouReviewedCatalog.matchContent(
                stored(manifest.copy(version = manifest.version + ".unreviewed")),
                source,
            ),
        )
        assertNull(
            OfficialShinsouReviewedCatalog.matchContent(
                stored(manifest, scriptBytes + byteArrayOf(0)),
                source,
            ),
        )
        assertNull(
            OfficialShinsouReviewedCatalog.matchContent(
                stored(manifest),
                source.copy(baseUrl = "https://evil.example"),
            ),
        )
        assertNull(
            OfficialShinsouReviewedCatalog.match(stored(manifest), source),
            "A local mirror must never receive executable review provenance",
        )
    }

    @Test
    fun everyOfficialPluginCanInitializeInRhino() = runTest {
        val repository = locateRepository() ?: return@runTest
        val entries = localIndexEntries(repository)
        assertTrue(entries.isNotEmpty())

        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                PluginHttpResponse(200, "<html></html>".encodeToByteArray(), emptyMap())
            },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val environment = ScriptPluginEnvironment(
            network,
            storage,
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
        )

        entries.filter { it.installable && !it.referenceOnly }.forEachIndexed { offset, entry ->
            val packageId = entry.id
            val localScript = entry.scriptUrl
                .substringBefore('?').substringBefore('#')
            val bytes = Files.readAllBytes(repository.resolve(localScript))
            val manifest = PluginManifest(
                id = packageId,
                name = entry.name,
                version = entry.version,
                versionCode = entry.versionCode,
                lang = entry.lang,
                nsfw = entry.nsfw == 1,
                script = "$packageId.js",
                signature = Sha256.hex(bytes),
                sources = entry.sources,
            )
            val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(bytes.decodeToString(), manifest, environment)
            try {
                assertEquals(packageId, runtime.pluginId, packageId)
                assertTrue(runtime.name.isNotBlank(), packageId)
            } finally {
                runtime.close()
            }
        }
    }

private fun locateRepository(): Path? {
        val candidates = listOf(
            Path.of("../shinsou_plugin"),
            Path.of("../../shinsou_plugin"),
            System.getProperty("shinsou.pluginRepo")?.let(Path::of),
        ).filterNotNull()
        return candidates.map(Path::toAbsolutePath).firstOrNull { Files.isRegularFile(it.resolve("index.json")) }
    }

    private fun managerEnvironment(
        storage: KeyValuePluginStorage,
        requests: MutableList<PluginHttpRequest>,
    ): ScriptPluginEnvironment = ScriptPluginEnvironment(
        network = PluginNetworkClient(
            transport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    error("Local-mirror content test requires resolved transport")

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse {
                    requests += request
                    return PluginHttpResponse(200, byteArrayOf(1))
                }
            },
            storage = storage,
            hostResolver = PluginHostResolver { listOf("93.184.216.34") },
        ),
        storage = storage,
    )
}

private fun localIndexEntry(repository: Path, packageId: String): PluginIndexEntry =
    localIndexEntries(repository).single { it.id == packageId }

private fun localIndexEntries(repository: Path): List<PluginIndexEntry> = runBlocking {
    val http = HttpClient(MockEngine { request ->
        val relative = request.url.encodedPath.substringAfter("refs/heads/master/")
        respond(Files.readAllBytes(repository.resolve(relative)), HttpStatusCode.OK)
    })
    try {
        val client = ExtensionRepositoryClient(http,
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY)
        when (val index = client.fetchIndex(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)) {
            is RepositoryIndex.Combined -> index.plugins
            is RepositoryIndex.Plugins -> index.entries
            is RepositoryIndex.Legacy -> error("Expected V2 repository")
        }
    } finally {
        http.close()
    }
}


private class CapturingLocalMirrorRuntimeFactory : ScriptPluginRuntimeFactory {
    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime {
        if (environment.inProcessScriptProvenance == null) {
            throw ScriptRuntimeUnavailableException("Artifact is not execution-reviewed")
        }
        return NoopScriptPluginRuntimeFactory.create(script, manifest, environment)
    }
}

private class TrustStoreUnavailableForTest : PluginTrustStore {
    override suspend fun isTrusted(pluginId: String, versionCode: Int, sha256: String): Boolean =
        error("generic trust store unavailable")

    override suspend fun trust(pluginId: String, versionCode: Int, sha256: String): Unit =
        error("generic trust store unavailable")

    override suspend fun revoke(pluginId: String, versionCode: Int, sha256: String): Unit =
        error("generic trust store unavailable")

    override suspend fun revokeAll(pluginId: String): Unit =
        error("generic trust store unavailable")
}
