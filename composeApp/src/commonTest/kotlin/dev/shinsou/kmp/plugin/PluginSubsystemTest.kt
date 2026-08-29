package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.BrowseOptionsSchemaV2
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.ExtensionCapability
import dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi
import dev.shinsou.kmp.plugin.v2.ExtensionPackageV2
import dev.shinsou.kmp.plugin.v2.ExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.ImmutableExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.LoginCredentialsV2
import dev.shinsou.kmp.plugin.v2.LoginResultV2
import dev.shinsou.kmp.plugin.v2.PagedResultV2
import dev.shinsou.kmp.plugin.v2.PreferenceV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import dev.shinsou.kmp.plugin.v2.SourceDescriptorV2
import dev.shinsou.kmp.plugin.v2.TextChunkStreamV2
import dev.shinsou.kmp.plugin.v2.UnitContentResultV2
import dev.shinsou.kmp.plugin.v2.WebChallengeUserAgentSourceV2
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import dev.shinsou.kmp.ui.BrowseManga
import dev.shinsou.kmp.ui.BrowseFilter
import dev.shinsou.kmp.ui.SourceLoginRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExtensionImplementationApi::class)
class PluginSubsystemTest {
    @Test
    fun repositoryFormatsDecodeStringAndNumericIds() {
        val native = PluginJson.decodeFromString<List<PluginIndexEntry>>(
            """[{"id":"all.example","name":"Example","version":"1.0.0","versionCode":1,
                "lang":"all","nsfw":0,"scriptUrl":"plugins/all.example.js","sources":[
                {"name":"One","lang":"all","id":6912170,"baseUrl":"https://one.example"},
                {"name":"Two","lang":"all","id":"9119537447562549661"}]}]""",
        )
        assertEquals(6_912_170L, native.single().sources?.first()?.id)
        assertEquals(9_119_537_447_562_549_661L, native.single().sources?.last()?.id)

        val legacy = PluginJson.decodeFromString<List<LegacyExtensionIndexEntry>>(
            """[{"name":"Tachiyomi: Test","pkg":"eu.test","apk":"test.apk","lang":"all",
                "code":12,"version":"1.4.12","nsfw":1,"sources":[{"name":"Test","lang":"en",
                "id":"2499283573021220255","baseUrl":"https://test.example"}]}]""",
        )
        assertEquals(2_499_283_573_021_220_255L, legacy.single().sources?.single()?.id)
    }

    @Test
    fun sha256AndTrustVerificationMatchKnownVectors() = runTest {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".encodeToByteArray()),
        )
        val kv = InMemoryPluginKeyValueStore()
        val verifier = PluginVerifier(KeyValuePluginTrustStore(kv))
        val bytes = "var source={};".encodeToByteArray()
        val hash = Sha256.hex(bytes)
        val manifest = PluginManifest("all.test", "Test", "1.2.3", 7, "all", script = "all.test.js", signature = hash)
        assertEquals(hash, verifier.verify(bytes, manifest).sha256)
        assertEquals(hash, verifier.verify(bytes, manifest.copy(signature = "")).sha256)
        assertFailsWith<PluginVerificationException.HashMismatch> {
            verifier.verify(bytes, manifest.copy(signature = "00".repeat(32)))
        }
        assertFailsWith<PluginVerificationException.UnsafeIdentifier> {
            verifier.verify(bytes, manifest.copy(id = "../escape"))
        }
    }

    @Test
    fun pluginIdentifiersAreSafeOnWindowsFilesystems() {
        listOf(
            "CON",
            "nul.js",
            "COM1.source",
            "bad:name",
            "bad*name",
            "trailing.",
            "trailing ",
            "control\u001fcharacter",
        ).forEach { value ->
            assertFailsWith<PluginVerificationException.UnsafeIdentifier>(value) {
                PluginVerifier.validateSafeFileComponent(value)
            }
        }

        PluginVerifier.validateSafeFileComponent("zh.bika")
        PluginVerifier.validateSafeFileName("all.example.js")
    }

    @Test
    fun keyValueStoresSurviveReconstructionAndKeepSourcesIsolated() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val first = KeyValuePluginStorage(kv)
        first.setPreference(1, "token", "one")
        first.setPreference(2, "token", "two")
        first.setCredential(1, PluginCredential("user", "pass"))
        first.setCookie(1, PluginCookie("sid", "abc", ".example.com"))

        val second = KeyValuePluginStorage(kv)
        assertEquals("one", second.getPreference(1, "token"))
        assertEquals("two", second.getPreference(2, "token"))
        assertEquals(PluginCredential("user", "pass"), second.getCredential(1))
        assertEquals("abc", second.getCookies(1).single().value)
        assertTrue(second.getCookies(2).isEmpty())
    }

    @Test
    fun sourceCookieJarIsLoadedOnlyOnceForRepeatedImageRequests() = runTest {
        val persisted = InMemoryPluginKeyValueStore()
        KeyValuePluginStorage(persisted).setCookie(
            42,
            PluginCookie("session", "value", "images.example"),
        )
        val counting = CountingPluginKeyValueStore(persisted)
        val reconstructed = KeyValuePluginStorage(counting)

        repeat(20) {
            assertEquals("value", reconstructed.getCookies(42).single().value)
        }

        assertEquals(1, counting.reads["source.42.cookies"])
    }

    @Test
    fun repositoryClientPreservesExistingQueryWhenCacheBusting() = runTest {
        var requestedUrl = ""
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(
                content = "plugin body",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/javascript"),
            )
        }
        val client = ExtensionRepositoryClient(HttpClient(engine), cacheToken = { 42L })
        assertEquals(
            "plugin body",
            client.downloadPluginScript("https://repo.example", "plugins/test.js?v=1").decodeToString(),
        )
        assertTrue("v=1" in requestedUrl)
        assertTrue("_t=42" in requestedUrl)
        assertFalse("v=1?_t" in requestedUrl)
    }

    @Test
    fun managerInstallsAndReloadsFromPersistentPackageStore() = runTest {
        val script = "var source={baseUrl:'https://source.example'};"
        val engine = MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/plugins/all.test.js"))
            respond(script, HttpStatusCode.OK)
        }
        val kv = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(kv)
        val packageStore = KeyValuePluginPackageStore(kv)
        val verifier = PluginVerifier(KeyValuePluginTrustStore(kv))
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val manager = PluginManager(
            ExtensionRepositoryClient(HttpClient(engine), cacheToken = { 1L }),
            packageStore,
            verifier,
            NoopScriptPluginRuntimeFactory,
            ScriptPluginEnvironment(network, storage),
        )
        val repository = ExtensionRepository("https://repo.example", "Repo")
        val entry = PluginIndexEntry(
            "all.test", "Test", "1.0.0", 1, "all", 0, "plugins/all.test.js",
            sources = listOf(SourceIndexEntry("Test", "all", 123L, "https://source.example")),
        )
        manager.install(repository, entry)
        assertEquals(1, KeyValuePluginPackageStore(kv).list().size)

        val reloadedManager = PluginManager(
            ExtensionRepositoryClient(HttpClient(engine)),
            KeyValuePluginPackageStore(kv),
            verifier,
            NoopScriptPluginRuntimeFactory,
            ScriptPluginEnvironment(network, KeyValuePluginStorage(kv)),
        )
        assertEquals(123L, reloadedManager.loadInstalled().single().id)
        reloadedManager.uninstall("all.test")
        assertTrue(KeyValuePluginPackageStore(kv).list().isEmpty())
    }

    @Test
    fun revokedSignedPackageRemainsInstalledButCannotReloadAfterRestart() = runTest {
        val scriptBytes = "var source={};".encodeToByteArray()
        val hash = Sha256.hex(scriptBytes)
        val manifest = PluginManifest(
            id = "all.signed",
            name = "Signed",
            version = "1.0.0",
            versionCode = 1,
            lang = "all",
            script = "all.signed.js",
            signature = hash,
            sources = listOf(SourceIndexEntry("Signed Source", "all", 101L, "https://signed.example")),
        )
        val keyValues = InMemoryPluginKeyValueStore()
        val trustStore = KeyValuePluginTrustStore(keyValues)
        val packageStore = InMemoryPluginPackageStore()
        packageStore.put(
            StoredPlugin(
                InstalledPluginMetadata(manifest, "https://repo.example", hash),
                scriptBytes,
            ),
        )
        trustStore.trust(manifest.id, manifest.versionCode!!, hash)
        val firstFactory = RecordingRuntimeFactory()
        val firstManager = testPluginManager(packageStore, trustStore, keyValues, firstFactory)

        assertEquals(101L, firstManager.loadInstalled().single().id)
        val activeRuntime = assertNotNull(firstFactory.runtime)
        firstManager.setPluginTrusted(manifest.id, false)

        assertTrue(activeRuntime.closed)
        assertNull(firstManager.source(101L))
        assertTrue(firstManager.catalogueSources().isEmpty())
        assertEquals(manifest.id, firstManager.installedPlugins().single().manifest.id)

        val restartFactory = RecordingRuntimeFactory()
        val restartedManager = testPluginManager(packageStore, trustStore, keyValues, restartFactory)
        assertTrue(restartedManager.loadInstalled().isEmpty())
        assertNull(restartFactory.runtime)
        assertEquals(manifest.id, restartedManager.installedPlugins().single().manifest.id)
    }

    @Test
    fun corruptInstalledPackageDoesNotPreventTrustedPackageLoading() = runTest {
        val keyValues = InMemoryPluginKeyValueStore()
        val trustStore = KeyValuePluginTrustStore(keyValues)
        val packageStore = InMemoryPluginPackageStore()
        // A truncated executable must not be mistaken for a metadata-only legacy package.
        val badBytes = ByteArray(0)
        val badHash = Sha256.hex(badBytes)
        val badManifest = PluginManifest(
            id = "all.bad",
            name = "Bad",
            version = "1.0.0",
            versionCode = 1,
            lang = "all",
            script = "all.bad.js",
            signature = "00".repeat(32),
            sources = listOf(SourceIndexEntry("Bad Source", "all", 201L, "https://bad.example")),
        )
        val goodBytes = "var source={};".encodeToByteArray()
        val goodHash = Sha256.hex(goodBytes)
        val goodManifest = PluginManifest(
            id = "all.good",
            name = "Good",
            version = "1.0.0",
            versionCode = 1,
            lang = "all",
            script = "all.good.js",
            signature = goodHash,
            sources = listOf(SourceIndexEntry("Good Source", "all", 202L, "https://good.example")),
        )
        packageStore.put(
            StoredPlugin(InstalledPluginMetadata(badManifest, null, badHash), badBytes),
        )
        packageStore.put(
            StoredPlugin(InstalledPluginMetadata(goodManifest, null, goodHash), goodBytes),
        )
        trustStore.trust(badManifest.id, badManifest.versionCode!!, badHash)
        trustStore.trust(goodManifest.id, goodManifest.versionCode!!, goodHash)
        val runtimeFactory = RecordingRuntimeFactory()
        val manager = testPluginManager(packageStore, trustStore, keyValues, runtimeFactory)

        assertEquals(listOf(202L), manager.loadInstalled().map { it.id })
        assertEquals(listOf("all.good"), runtimeFactory.createdPluginIds)
        assertEquals(setOf("all.bad", "all.good"), manager.installedPlugins().map { it.manifest.id }.toSet())
    }

    @Test
    fun requestBuilderSharesCookiesUaRefererAndProxySemantics() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        storage.setCookie(7, PluginCookie("session", "abc", ".example.com"))
        var captured: PluginHttpRequest? = null
        val requestBuilder = PluginRequestBuilder(
            storage,
            userAgents = PluginUserAgentProvider { "sticky-agent" },
            proxyResolver = PluginProxyResolver { _, target ->
                PluginProxyRoute("https://proxy.example/?url=$target", mapOf("X-Proxy-Key" to "key"))
            },
            nowEpochMillis = { 1_000L },
        )
        val client = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                captured = request
                PluginHttpResponse(
                    200,
                    "ok".encodeToByteArray(),
                    mapOf("Set-Cookie" to listOf("next=xyz; Domain=.example.com; Path=/; Max-Age=60")),
                )
            },
            storage = storage,
            requestBuilder = requestBuilder,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
            nowEpochMillis = { 1_000L },
        )
        assertEquals("ok", client.get(7, "https://api.example.com/path", emptyMap(), referer = "https://example.com").bodyText())
        val request = assertNotNull(captured)
        assertEquals("session=abc", request.headers["Cookie"])
        assertEquals("sticky-agent", request.headers["User-Agent"])
        assertEquals("https://example.com", request.headers["Referer"])
        assertEquals("key", request.headers["X-Proxy-Key"])
        assertEquals("xyz", storage.getCookies(7).first { it.name == "next" }.value)
    }

    @Test
    fun browserBoundUserAgentOverridesPluginAndRequestHintsUntilCookiesAreCleared() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        storage.setCookie(7, PluginCookie("cf_clearance", "verified", ".example.com"))
        storage.setWebChallengeUserAgent(7, "  WKWebView Native/1.0  ")
        val builder = PluginRequestBuilder(
            storage,
            userAgents = PluginUserAgentProvider { "fallback-agent" },
        )

        val verified = builder.build(
            sourceId = 7,
            request = PluginHttpRequest(
                "GET",
                "https://www.example.com/login",
                headers = mapOf("user-agent" to "request-agent"),
            ),
            sourceHeaders = mapOf("User-Agent" to "plugin-agent"),
        )
        assertEquals(
            "WKWebView Native/1.0",
            verified.transportRequest.headers.entries.single { it.key.equals("User-Agent", true) }.value,
        )

        storage.clearCookies(7)
        assertNull(storage.getWebChallengeUserAgent(7))
        val cleared = builder.build(
            sourceId = 7,
            request = PluginHttpRequest("GET", "https://www.example.com/login"),
            sourceHeaders = mapOf("User-Agent" to "plugin-agent"),
        )
        assertEquals("plugin-agent", cleared.transportRequest.headers["User-Agent"])
    }

    @Test
    fun pageFragmentSeparatesHeadersFromDescrambleMetadata() {
        val parsed = PageRequestMetadata.parse(
            "https://img.example/a.jpg#Referer=https%3A%2F%2Fsite.example%2F&" +
                "Shinsou-JM-Photo-Id=123",
        )
        assertEquals("https://img.example/a.jpg", parsed.cleanUrl)
        assertEquals("https://site.example/", parsed.headers["Referer"])
        assertEquals("123", parsed.metadata["Shinsou-JM-Photo-Id"])
    }

    @Test
    fun installingAnotherExtensionDoesNotReevaluateUnchangedSourceSettings() = runTest {
        val index = """[
            {"id":"all.first","name":"First","version":"1.0.0","versionCode":1,
             "lang":"all","scriptUrl":"plugins/all.first.js","sources":[
             {"name":"First Source","lang":"all","id":101,"baseUrl":"https://first.example"}]},
            {"id":"all.second","name":"Second","version":"1.0.0","versionCode":1,
             "lang":"all","scriptUrl":"plugins/all.second.js","sources":[
             {"name":"Second Source","lang":"all","id":202,"baseUrl":"https://second.example"}]}
        ]"""
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/repo.json") -> respond(
                    """{"meta":{"name":"Test Repository"}}""",
                    HttpStatusCode.OK,
                )
                request.url.encodedPath.endsWith("/index.json") -> respond(index, HttpStatusCode.OK)
                request.url.encodedPath.endsWith(".js") -> respond("var source={};", HttpStatusCode.OK)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val persisted = InMemoryPluginKeyValueStore()
        KeyValuePluginStorage(persisted).apply {
            setCredential(101L, PluginCredential("saved-user", "saved-password"))
            setCookie(101L, PluginCookie("saved-session", "saved-cookie", ".first.example"))
        }
        val kv = CountingPluginKeyValueStore(persisted)
        val storage = KeyValuePluginStorage(kv)
        val trust = KeyValuePluginTrustStore(kv)
        val runtimeFactory = RecordingRuntimeFactory()
        val repositoryClient = ExtensionRepositoryClient(HttpClient(engine), cacheToken = { 1L })
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = KeyValuePluginPackageStore(kv),
            verifier = PluginVerifier(trust),
            runtimeFactory = runtimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                    storage,
                    requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
                ),
                storage = storage,
            ),
        )
        val browse = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(kv),
            pluginStorage = storage,
            keyValueStore = kv,
            trustStore = trust,
            defaultRepositoryUrl = "https://repo.example",
        )

        browse.refresh()
        browse.installExtension("all.first")
        val first = assertNotNull(runtimeFactory.runtimes["all.first"])
        assertEquals(1, first.filterListRequests)
        assertEquals(1, first.preferenceDefinitionRequests)

        browse.installExtension("all.second")

        assertEquals(1, first.filterListRequests, "an unchanged JS runtime must reuse its UI projection")
        assertEquals(1, first.preferenceDefinitionRequests)
        val second = assertNotNull(runtimeFactory.runtimes["all.second"])
        assertEquals(1, second.filterListRequests, "the new runtime must still build complete settings")
        assertEquals(1, second.preferenceDefinitionRequests)
        assertEquals(setOf(101L, 202L), browse.state.value.sources.map { it.id }.toSet())
        assertEquals(0, kv.reads["source.101.credential.username"] ?: 0)
        assertEquals(0, kv.reads["source.101.credential.password"] ?: 0)
        assertEquals(0, kv.reads["source.101.cookies"] ?: 0)
    }

    @Test
    fun nonLoginSourceDoesNotExposeOrAcceptCredentials() = runTest {
        val index = """[{"id":"all.no-login","name":"No Login","version":"1.0.0","versionCode":1,
            "lang":"all","scriptUrl":"plugins/all.no-login.js","sources":[
            {"name":"No Login Source","lang":"all","id":404,"baseUrl":"https://source.example"}]}]"""
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/repo.json") -> respond(
                    """{"meta":{"name":"Test Repository"}}""",
                    HttpStatusCode.OK,
                )
                request.url.encodedPath.endsWith("/index.json") -> respond(index, HttpStatusCode.OK)
                request.url.encodedPath.endsWith("/plugins/all.no-login.js") ->
                    respond("var source={};", HttpStatusCode.OK)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val kv = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(kv)
        val staleCredential = PluginCredential("stale-user", "stale-password")
        storage.setCredential(404L, staleCredential)
        storage.setCookie(404L, PluginCookie("stale-session", "stale-cookie", ".source.example"))
        val trust = KeyValuePluginTrustStore(kv)
        val repositoryClient = ExtensionRepositoryClient(HttpClient(engine), cacheToken = { 1L })
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = KeyValuePluginPackageStore(kv),
            verifier = PluginVerifier(trust),
            runtimeFactory = RecordingRuntimeFactory(supportsLogin = false),
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                    storage,
                    requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
                ),
                storage = storage,
            ),
        )
        val browse = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(kv),
            pluginStorage = storage,
            keyValueStore = kv,
            trustStore = trust,
            defaultRepositoryUrl = "https://repo.example",
        )

        browse.refresh()
        browse.installExtension("all.no-login")

        val source = browse.state.value.sources.single()
        assertFalse(source.supportsLogin)
        assertNull(source.credential, "stale secrets must not enter the UI snapshot")
        val rejected = browse.saveSourceCredentialsResult(source.id, "new-user", "new-password")
        assertFalse(rejected.succeeded)
        assertEquals(
            dev.shinsou.kmp.ui.SourceLoginFailureStage.PREPARE_SOURCE,
            rejected.failureStage,
        )
        assertEquals(staleCredential, storage.getCredential(404L), "unsupported writes must be rejected")
    }

    @Test
    fun uiAdapterPreservesV2LoginFailureMessageAndRestoresCredentialState() = runTest {
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val trust = KeyValuePluginTrustStore(keyValues)
        val http = HttpClient(MockEngine { error("Repository access is not expected") })
        val repositoryClient = ExtensionRepositoryClient(http)
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(trust),
            runtimeFactory = NoopScriptPluginRuntimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                    storage,
                ),
                storage = storage,
            ),
        )
        val sourceKey = SourceKey(2, "zh.bilimanga", "zh.bilimanga.novel")
        val descriptor = SourceDescriptorV2(
            sourceKey = sourceKey,
            displayName = "嗶哩輕小說（Linovelib）",
            languageTag = "zh",
            supportedContentKinds = setOf(ContentKind.PLAIN_TEXT),
            capabilities = setOf(ExtensionCapability.CONTENT, ExtensionCapability.LOGIN),
            baseUrl = "https://tw.linovelib.com",
        )
        manager.installExtensionRuntimeV2(
            ImmutableExtensionPackageRuntimeV2(
                ExtensionPackageV2(
                    contractVersion = 2,
                    packageId = sourceKey.packageId,
                    version = "1.5.0",
                    displayName = "嗶哩輕小說／漫畫",
                    sources = listOf(descriptor),
                ),
                listOf(
                    FailingLoginExtensionSource(
                        descriptor = descriptor,
                        webChallengeUserAgent = "reviewed-source-agent",
                        webChallengeUrl = "https://tw.linovelib.com/login.php",
                    ),
                ),
            ),
        )
        val browse = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = trust,
            requestBuilder = PluginRequestBuilder(
                storage,
                userAgents = PluginUserAgentProvider { "challenge-fallback-agent" },
            ),
        )

        try {
            browse.setPluginUiAvailable(true)
            browse.setSourceEnabledV2(sourceKey, true)
            val source = browse.state.value.sources.single { it.sourceKey == sourceKey }
            storage.setCredential(
                -9_110_000_000_000_004L,
                PluginCredential("stored-user", "stored-password"),
            )
            val challenge = assertNotNull(browse.sourceWebChallenge(source.id))
            assertEquals("reviewed-source-agent", challenge.userAgent)
            assertEquals("https://tw.linovelib.com/login.php", challenge.url)
            assertEquals("stored-user", challenge.username)
            assertEquals("stored-password", challenge.password)
            val editedChallenge = assertNotNull(
                browse.sourceWebChallenge(source.id, "edited-user", "edited-password"),
            )
            assertEquals("edited-user", editedChallenge.username)
            assertEquals("edited-password", editedChallenge.password)
            val blankChallenge = assertNotNull(browse.sourceWebChallenge(source.id, "", ""))
            assertNull(blankChallenge.username)
            assertNull(blankChallenge.password)
            storage.clearCredential(-9_110_000_000_000_004L)
            val result = browse.saveSourceCredentialsResult(source.id, "alice", "wrong")

            assertFalse(result.succeeded)
            assertEquals("帳號或密碼錯誤", result.errorMessage)
            assertNull(storage.getCredential(-9_110_000_000_000_004L))
        } finally {
            manager.close()
            http.close()
        }
    }

    @Test
    fun uiAdapterRunsV2LoginWithoutGrantingModalAuthorityWhenUiIsUnavailable() = runTest {
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val trust = KeyValuePluginTrustStore(keyValues)
        val http = HttpClient(MockEngine { error("Repository access is not expected") })
        val repositoryClient = ExtensionRepositoryClient(http)
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(trust),
            runtimeFactory = NoopScriptPluginRuntimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                    storage,
                ),
                storage = storage,
            ),
        )
        val sourceKey = SourceKey(2, "zh.bilimanga", "zh.bilimanga.novel")
        val descriptor = SourceDescriptorV2(
            sourceKey = sourceKey,
            displayName = "UI state fixture",
            languageTag = "en",
            supportedContentKinds = setOf(ContentKind.PLAIN_TEXT),
            capabilities = setOf(ExtensionCapability.CONTENT, ExtensionCapability.LOGIN),
            baseUrl = "https://fixture.example",
        )
        manager.installExtensionRuntimeV2(
            ImmutableExtensionPackageRuntimeV2(
                ExtensionPackageV2(
                    contractVersion = 2,
                    packageId = sourceKey.packageId,
                    version = "1.0.0",
                    displayName = "UI state fixture",
                    sources = listOf(descriptor),
                ),
                listOf(FailingLoginExtensionSource(descriptor, null)),
            ),
        )
        val browse = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = trust,
        )

        try {
            browse.setSourceEnabledV2(sourceKey, true)
            val source = browse.state.value.sources.single { it.sourceKey == sourceKey }
            val result = browse.saveSourceCredentialsResult(source.id, "alice", "wrong")

            assertFalse(result.succeeded)
            assertEquals("帳號或密碼錯誤", result.errorMessage)
            assertNull(result.failureStage)
            assertNull(storage.getCredential(-9_110_000_000_000_004L))
        } finally {
            manager.close()
            http.close()
        }
    }

    @Test
    fun uiAdapterRequiresCloudflareCookieByStableBiliMangaSourceIdentity() = runTest {
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val trust = KeyValuePluginTrustStore(keyValues)
        val http = HttpClient(MockEngine { error("Repository access is not expected") })
        val repositoryClient = ExtensionRepositoryClient(http)
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(trust),
            runtimeFactory = NoopScriptPluginRuntimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                    storage,
                ),
                storage = storage,
            ),
        )
        val sourceKey = SourceKey(2, "zh.bilimanga", "zh.bilimanga.manga")
        val descriptor = SourceDescriptorV2(
            sourceKey = sourceKey,
            displayName = "嗶哩漫畫（本地化名稱）",
            languageTag = "zh",
            supportedContentKinds = setOf(ContentKind.IMAGE_SEQUENCE),
            capabilities = setOf(ExtensionCapability.CONTENT, ExtensionCapability.LOGIN),
            baseUrl = "https://www.bilimanga.net",
        )
        manager.installExtensionRuntimeV2(
            ImmutableExtensionPackageRuntimeV2(
                ExtensionPackageV2(
                    contractVersion = 2,
                    packageId = sourceKey.packageId,
                    version = "1.5.3",
                    displayName = "嗶哩輕小說／漫畫",
                    sources = listOf(descriptor),
                ),
                listOf(
                    FailingLoginExtensionSource(
                        descriptor = descriptor,
                        webChallengeUserAgent = "reviewed-source-agent",
                        webChallengeUrl = "https://www.bilimanga.net/login.php",
                    ),
                ),
            ),
        )
        val browse = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = trust,
        )

        try {
            browse.setPluginUiAvailable(true)
            browse.setSourceEnabledV2(sourceKey, true)
            val source = browse.state.value.sources.single { it.sourceKey == sourceKey }
            val challenge = assertNotNull(browse.sourceWebChallenge(source.id))

            assertEquals("cf_clearance", challenge.requiredCookieName)
            assertEquals("https://www.bilimanga.net/login.php", challenge.url)
        } finally {
            manager.close()
            http.close()
        }
    }

    @Test
    fun uiAdapterRejectsCrossOriginV2WebChallengeUrl() = runTest {
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val trust = KeyValuePluginTrustStore(keyValues)
        val http = HttpClient(MockEngine { error("Repository access is not expected") })
        val repositoryClient = ExtensionRepositoryClient(http)
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(trust),
            runtimeFactory = NoopScriptPluginRuntimeFactory,
            environment = ScriptPluginEnvironment(
                network = PluginNetworkClient(
                    PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                    storage,
                ),
                storage = storage,
            ),
        )
        val sourceKey = SourceKey(2, "example.challenge", "example.challenge")
        val descriptor = SourceDescriptorV2(
            sourceKey = sourceKey,
            displayName = "Challenge fixture",
            languageTag = "en",
            supportedContentKinds = setOf(ContentKind.PLAIN_TEXT),
            capabilities = setOf(ExtensionCapability.CONTENT, ExtensionCapability.LOGIN),
            baseUrl = "https://source.example",
        )
        manager.installExtensionRuntimeV2(
            ImmutableExtensionPackageRuntimeV2(
                ExtensionPackageV2(
                    contractVersion = 2,
                    packageId = sourceKey.packageId,
                    version = "1.0.0",
                    displayName = "Challenge fixture",
                    sources = listOf(descriptor),
                ),
                listOf(
                    FailingLoginExtensionSource(
                        descriptor = descriptor,
                        webChallengeUserAgent = "fixture-agent",
                        webChallengeUrl = "https://evil.example/login.php",
                    ),
                ),
            ),
        )
        val browse = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = trust,
        )

        try {
            browse.setPluginUiAvailable(true)
            browse.setSourceEnabledV2(sourceKey, true)
            val source = browse.state.value.sources.single { it.sourceKey == sourceKey }
            assertFailsWith<IllegalArgumentException> { browse.sourceWebChallenge(source.id) }
        } finally {
            manager.close()
            http.close()
        }
    }

    @Test
    fun uiAdaptersInstallBrowseAndBuildReaderRequestsWithSharedSemantics() = runTest {
        val index = """[{"id":"all.test","name":"Test","version":"1.0.0","versionCode":1,
            "lang":"all","nsfw":0,"scriptUrl":"plugins/all.test.js","sources":[
            {"name":"Test Source","lang":"all","id":123,"baseUrl":"https://source.example"}]}]"""
        var indexRequestCount = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/repo.json") -> respond(
                    """{"meta":{"name":"Test Repository","website":"https://repo.example"}}""",
                    HttpStatusCode.OK,
                )
                request.url.encodedPath.endsWith("/index.json") -> {
                    indexRequestCount += 1
                    respond(index, HttpStatusCode.OK)
                }
                request.url.encodedPath.endsWith("/plugins/all.test.js") -> respond("var source={};", HttpStatusCode.OK)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val kv = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(kv)
        val trust = KeyValuePluginTrustStore(kv)
        val runtimeFactory = RecordingRuntimeFactory(
            sourceHeaders = mapOf("User-Agent" to "source-specific-agent"),
        )
        val loginRequests = PluginLoginRequestCoordinator()
        val repositoryClient = ExtensionRepositoryClient(HttpClient(engine), cacheToken = { 1L })
        val manager = PluginManager(
            repositoryClient,
            KeyValuePluginPackageStore(kv),
            PluginVerifier(trust),
            runtimeFactory,
            ScriptPluginEnvironment(
                PluginNetworkClient(
                    PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                    storage,
                    requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
                ),
                storage,
                loginRequester = loginRequests,
            ),
        )
        val browse = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(kv),
            pluginStorage = storage,
            keyValueStore = kv,
            trustStore = trust,
            requestBuilder = PluginRequestBuilder(
                storage,
                userAgents = PluginUserAgentProvider { "challenge-agent" },
            ),
            loginRequestCoordinator = loginRequests,
            defaultRepositoryUrl = "https://repo.example/index.json",
        )

        browse.refresh()
        assertEquals(1, indexRequestCount)
        assertEquals("Test Repository", browse.state.value.repositories.single().name)
        assertFalse(browse.state.value.extensions.single().installed)
        browse.installExtension("all.test")
        manager.setPluginUiAvailable(true)
        assertEquals(2, indexRequestCount, "install must reuse its fetched index when rebuilding UI state")
        assertTrue(browse.state.value.extensions.single().installed)
        assertTrue(browse.state.value.extensions.single().trusted)
        val sourceSettings = browse.state.value.sources.single()
        assertEquals("https://source.example", sourceSettings.baseUrl)
        assertTrue(sourceSettings.supportsLogin)
        assertEquals("zh-TW", sourceSettings.preferences.first { it.key == "language" }.value)
        assertEquals(
            listOf("zh-TW", "en"),
            sourceSettings.preferences.first { it.key == "language" }.choiceValues,
        )
        assertEquals(
            listOf(BrowseFilter.Select("Genre", listOf("All", "Action"), 0)),
            sourceSettings.filters,
        )
        val emptyChallenge = assertNotNull(browse.sourceWebChallenge(123))
        assertEquals("https://source.example", emptyChallenge.url.trimEnd('/'))
        assertEquals("source-specific-agent", emptyChallenge.userAgent)
        assertTrue(emptyChallenge.cookies.isEmpty())

        browse.saveSourcePreferences(123, mapOf("language" to "en", "show_nsfw" to "true"))
        assertEquals("en", storage.getPreference(123, "language"))
        assertEquals("en", browse.state.value.sources.single().preferences.first { it.key == "language" }.value)

        assertTrue(loginRequests.request(123, "Test Source", "Account required"))
        assertEquals(
            listOf(SourceLoginRequest(123, "Test Source", "Account required")),
            browse.loginRequests.value,
        )
        val failedLogin = browse.saveSourceCredentialsResult(123, "alice", "wrong")
        assertFalse(failedLogin.succeeded)
        assertEquals("帳號或密碼錯誤", failedLogin.errorMessage)
        assertEquals(null, storage.getCredential(123))
        assertEquals(1, browse.loginRequests.value.size)
        assertTrue(browse.saveSourceCredentials(123, "alice", "secret"))
        assertEquals(PluginCredential("alice", "secret"), storage.getCredential(123))
        assertNull(browse.state.value.sources.single().credential)
        assertEquals("alice", browse.loadSourceSecrets(123).secrets.credential?.username)
        assertTrue(browse.loginRequests.value.isEmpty())
        assertTrue(loginRequests.request(123, "Test Source", null))
        browse.dismissSourceLoginRequest(123)
        assertTrue(browse.loginRequests.value.isEmpty())

        val result = browse.browseSource(123, page = 1)
        assertEquals(0, runtimeFactory.runtime?.popularPage)
        assertEquals("Fixture Manga", result.items.single().title)
        assertEquals("https://source.example/covers/one.jpg", result.items.single().thumbnailUrl)

        val latest = browse.browseSourceLatest(123, page = 2)
        assertEquals(1, runtimeFactory.runtime?.popularPage)
        assertEquals("Fixture Manga", latest.items.single().title)

        val appliedFilters = listOf(BrowseFilter.Select("Genre", listOf("All", "Action"), 1))
        browse.browseSource(123, query = "needle", page = 3, filters = appliedFilters)
        assertEquals(2, runtimeFactory.runtime?.searchPage)
        assertEquals("needle", runtimeFactory.runtime?.searchQuery)
        assertEquals(
            listOf(Filter.Select("Genre", listOf("All", "Action"), 1)),
            runtimeFactory.runtime?.searchFilters,
        )
        browse.browseSource(123, query = "", page = 1, filters = appliedFilters)
        assertEquals("", runtimeFactory.runtime?.searchQuery)
        assertEquals(0, runtimeFactory.runtime?.searchPage)

        browse.setSourceCookie(
            123,
            dev.shinsou.kmp.ui.SourceCookie("session", "abc", ".images.example"),
        )
        assertTrue(browse.state.value.sources.single().cookies.isEmpty())
        assertEquals("session", browse.loadSourceSecrets(123).secrets.cookies.single().name)
        val content = PluginContentAdapter(
            manager = manager,
            chapterResolver = PluginReaderChapterResolver { _, _ ->
                PluginReaderChapterReference(123, SChapter("https://source.example/chapter/1", "Chapter 1"))
            },
            requestBuilder = PluginRequestBuilder(storage, PluginUserAgentProvider { "fixture-agent" }),
        )
        val reader = content.loadReaderChapter(10, 20)
        val page = reader.pages.single()
        assertEquals("https://images.example/one.jpg", page.imageUrl)
        assertEquals("https://source.example/chapter/1", reader.referer)
        assertEquals("https://source.example/", page.headers["Referer"])
        assertEquals("session=abc", page.headers["Cookie"])
        assertEquals("source-specific-agent", page.headers["User-Agent"])

        browse.deleteSourceCookie(123, "session", ".images.example")
        assertTrue(browse.loadSourceSecrets(123).secrets.cookies.isEmpty())
        browse.setSourceCookie(123, dev.shinsou.kmp.ui.SourceCookie("one", "1", ".source.example"))
        browse.setSourceCookie(123, dev.shinsou.kmp.ui.SourceCookie("two", "2", ".source.example"))
        val populatedChallenge = assertNotNull(browse.sourceWebChallenge(123))
        assertEquals(listOf("one", "two"), populatedChallenge.cookies.map { it.name })
        assertTrue(populatedChallenge.cookies.all { !it.hostOnly })
        browse.clearSourceCookies(123)
        assertTrue(browse.loadSourceSecrets(123).secrets.cookies.isEmpty())

        browse.logoutSource(123)
        assertTrue(runtimeFactory.runtime?.loggedOut == true)
        assertEquals(null, storage.getCredential(123))
        assertEquals(null, browse.loadSourceSecrets(123).secrets.credential)

        browse.setSourceEnabled(123, false)
        assertFalse(browse.state.value.sources.single().enabled)
        val activeRuntime = assertNotNull(runtimeFactory.runtime)
        browse.setExtensionTrusted("all.test", false)
        assertFalse(browse.state.value.extensions.single().trusted)
        assertTrue(browse.state.value.extensions.single().installed)
        assertTrue(browse.state.value.sources.isEmpty())
        assertTrue(activeRuntime.closed)
        assertNull(manager.source(123L))
        assertFailsWith<IllegalArgumentException> { browse.browseSource(123L, page = 1) }

        browse.setExtensionTrusted("all.test", true)
        assertTrue(browse.state.value.extensions.single().trusted)
        assertEquals(123L, browse.state.value.sources.single().id)
        assertTrue(runtimeFactory.runtime !== activeRuntime)

        browse.uninstallExtension("all.test")
        assertTrue(browse.state.value.sources.isEmpty())
    }
}

private class RecordingRuntimeFactory(
    private val supportsLogin: Boolean = true,
    private val sourceHeaders: Map<String, String> = emptyMap(),
) : ScriptPluginRuntimeFactory {
    var runtime: RecordingRuntime? = null
    val createdPluginIds = mutableListOf<String>()
    val runtimes = mutableMapOf<String, RecordingRuntime>()

    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime = RecordingRuntime(manifest, supportsLogin, sourceHeaders).also {
        runtime = it
        createdPluginIds += manifest.id
        runtimes[manifest.id] = it
    }
}

private class RecordingRuntime(
    manifest: PluginManifest,
    override val supportsLogin: Boolean,
    override val headers: Map<String, String>,
) : ScriptPluginRuntime {
    private val source = requireNotNull(manifest.sources?.firstOrNull())
    var popularPage: Int? = null
    var searchPage: Int? = null
    var searchQuery: String? = null
    var searchFilters: FilterList? = null
    var loggedOut: Boolean = false
    var closed: Boolean = false
    var filterListRequests: Int = 0
    var preferenceDefinitionRequests: Int = 0

    override val pluginId: String = manifest.id
    override val id: Long = source.id
    override val name: String = source.name
    override val lang: String = source.lang
    override val baseUrl: String = source.baseUrl.orEmpty()
    override val supportsLatest: Boolean = true
    override val recentLogs: List<String> = emptyList()

    override suspend fun getPopularManga(page: Int): MangasPage {
        popularPage = page
        return MangasPage(
            listOf(SManga("/manga/one", "Fixture Manga", thumbnailUrl = "/covers/one.jpg")),
            hasNextPage = true,
        )
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        searchPage = page
        searchQuery = query
        searchFilters = filters
        return getPopularManga(page)
    }
    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)
    override suspend fun getFilterList(): FilterList {
        filterListRequests += 1
        return listOf(Filter.Select("Genre", listOf("All", "Action"), 0))
    }
    override suspend fun getPreferenceDefinitions(): List<SourcePreference> {
        preferenceDefinitionRequests += 1
        return listOf(
            SourcePreference.Select(
                key = "language",
                title = "Language",
                entries = listOf("繁中", "English"),
                entryValues = listOf("zh-TW", "en"),
                defaultValue = "zh-TW",
            ),
            SourcePreference.Toggle(
                key = "show_nsfw",
                title = "Show NSFW",
                summary = "Display adult results",
                defaultValue = false,
            ),
        )
    }
    override suspend fun getMangaDetails(manga: SManga): SManga = manga
    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(
        Page(
            0,
            imageUrl = "https://images.example/one.jpg#Referer=https%3A%2F%2Fsource.example%2F&" +
                "Shinsou-JM-Photo-Id=123",
        ),
    )
    override suspend fun login(username: String, password: String): Boolean =
        username == "alice" && password == "secret"
    override suspend fun loginResult(username: String, password: String): LoginAttemptResult =
        LoginAttemptResult(
            loggedIn = login(username, password),
            errorMessage = if (password == "secret") null else "帳號或密碼錯誤",
        )
    override suspend fun logout() {
        loggedOut = true
    }
    override suspend fun close() {
        closed = true
    }
}

@OptIn(ExtensionImplementationApi::class)
private class FailingLoginExtensionSource(
    override val descriptor: SourceDescriptorV2,
    override val webChallengeUserAgent: String?,
    override val webChallengeUrl: String? = null,
) : ExtensionSourceV2, WebChallengeUserAgentSourceV2 {
    override suspend fun browseOptions(): BrowseOptionsSchemaV2 = BrowseOptionsSchemaV2()
    override suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2> =
        PagedResultV2(emptyList(), false)
    override suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2> = PagedResultV2(emptyList(), false)
    override suspend fun browse(options: BrowseOptionsV2, page: Int): PagedResultV2<RemotePublicationV2> =
        PagedResultV2(emptyList(), false)
    override suspend fun details(remotePublicationId: String): RemotePublicationV2 =
        RemotePublicationV2(remotePublicationId, "Fixture")
    override suspend fun units(remotePublicationId: String, page: Int): PagedResultV2<RemoteUnitV2> =
        PagedResultV2(emptyList(), false)
    override suspend fun content(remotePublicationId: String, remoteUnitId: String): UnitContentResultV2 =
        error("not used")
    override suspend fun openTextStream(streamId: String): TextChunkStreamV2 = error("not used")
    override suspend fun login(credentials: LoginCredentialsV2): LoginResultV2 =
        LoginResultV2(false, "帳號或密碼錯誤")
    override suspend fun logout(): Unit = Unit
    override suspend fun preferences(): List<PreferenceV2> = emptyList()
    override suspend fun favorite(remotePublicationId: String, favorite: Boolean): Unit = Unit
}

private fun testPluginManager(
    packageStore: PluginPackageStore,
    trustStore: PluginTrustStore,
    keyValues: PluginKeyValueStore,
    runtimeFactory: ScriptPluginRuntimeFactory,
): PluginManager {
    val storage = KeyValuePluginStorage(keyValues)
    return PluginManager(
        repositoryClient = ExtensionRepositoryClient(
            HttpClient(MockEngine { error("Repository access is not expected in this test") }),
        ),
        packageStore = packageStore,
        verifier = PluginVerifier(trustStore),
        runtimeFactory = runtimeFactory,
        environment = ScriptPluginEnvironment(
            network = PluginNetworkClient(
                transport = PluginHttpTransport { PluginHttpResponse(200, ByteArray(0), emptyMap()) },
                storage = storage,
                requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
            ),
            storage = storage,
        ),
    )
}

private class CountingPluginKeyValueStore(
    private val delegate: PluginKeyValueStore,
) : PluginKeyValueStore {
    val reads = mutableMapOf<String, Int>()

    override suspend fun getString(key: String): String? {
        reads[key] = (reads[key] ?: 0) + 1
        return delegate.getString(key)
    }

    override suspend fun putString(key: String, value: String) {
        delegate.putString(key, value)
    }

    override suspend fun remove(key: String) {
        delegate.remove(key)
    }
}
