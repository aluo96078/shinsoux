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
import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedPluginCatalogV2
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
    fun browserAvailabilityRequiresLiveEnabledReviewedRuntimeWithoutIssuingAuthority() = runTest {
        val fixture = reviewedBiliMangaWebChallengeRollbackFixture()
        try {
            assertTrue(fixture.manager.authorizeWebChallenge(fixture.sourceKey))
            val request = assertNotNull(fixture.manager.issueWebChallenge(fixture.sourceKey))
            assertTrue(fixture.manager.authorizeWebChallenge(fixture.sourceKey))
            fixture.manager.cancelWebChallenge(request.capability)
            fixture.manager.setEventSourceEnabled(fixture.sourceKey, false)
            assertFalse(fixture.manager.authorizeWebChallenge(fixture.sourceKey))

            val noBrowserProfile = ShuYueReviewedPluginCatalogV2.profiles.first {
                it.identity.packageId == "zh.wenku8.api"
            }
            val descriptor = noBrowserProfile.descriptor.sources.single()
            fixture.manager.installReviewedRuntimeForTest(
                ImmutableExtensionPackageRuntimeV2(
                    noBrowserProfile.descriptor,
                    listOf(FailingLoginExtensionSource(descriptor, webChallengeUserAgent = null)),
                ),
                PluginArtifactIdentity(
                    noBrowserProfile.identity.packageId, noBrowserProfile.identity.version,
                    noBrowserProfile.identity.versionCode, noBrowserProfile.identity.sha256,
                ),
            )
            assertFalse(fixture.manager.authorizeWebChallenge(descriptor.sourceKey))
        } finally {
            fixture.manager.close()
            fixture.http.close()
        }
    }

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
        val client = ExtensionRepositoryClient(
            HttpClient(engine),
            cacheToken = { 42L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
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
            ExtensionRepositoryClient(
                HttpClient(engine),
                cacheToken = { 1L },
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            ),
            packageStore,
            verifier,
            NoopScriptPluginRuntimeFactory,
            ScriptPluginEnvironment(network, storage),
        )
        val repository = ExtensionRepository("https://repo.example", "Repo")
        val entry = PluginIndexEntry(
            "all.test", "Test", "1.0.0", 1, "all", 0, "plugins/all.test.js",
            sources = listOf(SourceIndexEntry("Test", "all", 123L, "https://source.example")),
            runtimePermissions = PluginRuntimePermission.LEGACY_COMPATIBILITY,
        )
        manager.install(repository, entry)
        assertEquals(1, KeyValuePluginPackageStore(kv).list().size)

        val reloadedManager = PluginManager(
            ExtensionRepositoryClient(
                HttpClient(engine),
                repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
            ),
            KeyValuePluginPackageStore(kv),
            verifier,
            NoopScriptPluginRuntimeFactory,
            ScriptPluginEnvironment(network, KeyValuePluginStorage(kv)),
            executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
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
            runtimePermissions = PluginRuntimePermission.LEGACY_COMPATIBILITY,
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
            runtimePermissions = PluginRuntimePermission.LEGACY_COMPATIBILITY,
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
            policy = PluginNetworkPolicy(
                requestOrigins = setOf("https://api.example.com", "https://proxy.example"),
                credentialOrigins = setOf("https://api.example.com"),
                resolver = PluginHostResolver { listOf("93.184.216.34") },
                allowDeveloperUnpinnedTransport = true,
            ),
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
             "lang":"all","runtimePermissions":["EXECUTE_SCRIPT","NETWORK","COOKIE_STORAGE","CREDENTIAL_ACCESS","LOGIN_PROMPT","FAVORITE_MUTATION","BROWSER_CHALLENGE"],"scriptUrl":"plugins/all.first.js","sources":[
             {"name":"First Source","lang":"all","id":101,"baseUrl":"https://first.example"}]},
            {"id":"all.second","name":"Second","version":"1.0.0","versionCode":1,
             "lang":"all","runtimePermissions":["EXECUTE_SCRIPT","NETWORK","COOKIE_STORAGE","CREDENTIAL_ACCESS","LOGIN_PROMPT","FAVORITE_MUTATION","BROWSER_CHALLENGE"],"scriptUrl":"plugins/all.second.js","sources":[
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
        val repositoryClient = ExtensionRepositoryClient(
            HttpClient(engine),
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
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
            executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
        )
        val browse = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(kv).also {
                it.put(ExtensionRepository("https://repo.example", "Test Repository"))
            },
            pluginStorage = storage,
            keyValueStore = kv,
            trustStore = trust,
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
            "lang":"all","runtimePermissions":["EXECUTE_SCRIPT","NETWORK","COOKIE_STORAGE","CREDENTIAL_ACCESS","LOGIN_PROMPT","FAVORITE_MUTATION","BROWSER_CHALLENGE"],"scriptUrl":"plugins/all.no-login.js","sources":[
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
        val repositoryClient = ExtensionRepositoryClient(
            HttpClient(engine),
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
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
            executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
        )
        val browse = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(kv).also {
                it.put(ExtensionRepository("https://repo.example", "Test Repository"))
            },
            pluginStorage = storage,
            keyValueStore = kv,
            trustStore = trust,
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
    fun uninstallUsesDisplayedIndexWithoutNetworkAndReusesUnchangedSourceProjection() = runTest {
        val index = """[
            {"id":"all.first","name":"First","version":"1.0.0","versionCode":1,
             "lang":"all","runtimePermissions":["EXECUTE_SCRIPT"],"scriptUrl":"plugins/all.first.js","sources":[
             {"name":"First Source","lang":"all","id":101,"baseUrl":"https://first.example"}]},
            {"id":"all.second","name":"Second","version":"1.0.0","versionCode":1,
             "lang":"all","runtimePermissions":["EXECUTE_SCRIPT"],"scriptUrl":"plugins/all.second.js","sources":[
             {"name":"Second Source","lang":"all","id":202,"baseUrl":"https://second.example"}]}
        ]"""
        var networkAllowed = true
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            check(networkAllowed) { "Uninstall must not access the repository" }
            when {
                request.url.encodedPath.endsWith("/repo.json") -> respond("""{"meta":{"name":"Test"}}""")
                request.url.encodedPath.endsWith("/index.json") -> respond(index)
                request.url.encodedPath.endsWith(".js") -> respond("var source={};")
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val trust = KeyValuePluginTrustStore(keyValues)
        val client = ExtensionRepositoryClient(
            HttpClient(engine),
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val factory = RecordingRuntimeFactory()
        val manager = PluginManager(
            client,
            InMemoryPluginPackageStore(),
            PluginVerifier(trust),
            factory,
            ScriptPluginEnvironment(
                PluginNetworkClient(PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) }, storage),
                storage,
            ),
            executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
        )
        val browse = PluginBrowseAdapter(
            manager, client, KeyValueExtensionRepositoryStore(keyValues).also {
                it.put(ExtensionRepository("https://repo.example", "Test"))
            }, storage, keyValues, trust,
        )

        browse.refresh()
        browse.installExtension("all.first")
        browse.installExtension("all.second")
        val second = assertNotNull(factory.runtimes["all.second"])
        val filtersBefore = second.filterListRequests
        val preferencesBefore = second.preferenceDefinitionRequests
        val requestsBefore = requests
        networkAllowed = false

        browse.uninstallExtension("all.first")

        assertEquals(requestsBefore, requests)
        assertFalse(browse.state.value.extensions.single { it.id == "all.first" }.installed)
        assertTrue(browse.state.value.extensions.single { it.id == "all.second" }.installed)
        assertEquals(setOf(202L), browse.state.value.sources.map { it.id }.toSet())
        assertEquals(filtersBefore, second.filterListRequests)
        assertEquals(preferencesBefore, second.preferenceDefinitionRequests)
    }

    @Test
    fun uiAdapterPreservesV2LoginFailureMessageAndRestoresCredentialState() = runTest {
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val trust = KeyValuePluginTrustStore(keyValues)
        val http = HttpClient(MockEngine { error("Repository access is not expected") })
        val repositoryClient = ExtensionRepositoryClient(
            http,
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
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
        val reviewedProfile = ShuYueReviewedPluginCatalogV2.profiles.single {
            it.identity.packageId == sourceKey.packageId && it.identity.version == "1.5.0"
        }
        manager.installReviewedRuntimeForTest(
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
            PluginArtifactIdentity(
                reviewedProfile.identity.packageId, reviewedProfile.identity.version,
                reviewedProfile.identity.versionCode, reviewedProfile.identity.sha256,
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
            // This fixture now has exact reviewed storage/login authority. Browser denial for
            // same-key generic impostors is covered separately; legitimate review retains it.
            val challenge = assertNotNull(browse.sourceWebChallenge(source.id))
            assertEquals("stored-user", challenge.username)
            manager.cancelWebChallenge(challenge.capability)
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
        val repositoryClient = ExtensionRepositoryClient(
            http,
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
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
        val reviewedProfile = ShuYueReviewedPluginCatalogV2.profiles.single {
            it.identity.packageId == sourceKey.packageId && it.identity.version == "1.5.0"
        }
        manager.installReviewedRuntimeForTest(
            ImmutableExtensionPackageRuntimeV2(
                ExtensionPackageV2(
                    contractVersion = 2,
                    packageId = sourceKey.packageId,
                    version = reviewedProfile.identity.version,
                    displayName = "UI state fixture",
                    sources = listOf(descriptor),
                ),
                listOf(FailingLoginExtensionSource(descriptor, null)),
            ),
            PluginArtifactIdentity(
                reviewedProfile.identity.packageId, reviewedProfile.identity.version,
                reviewedProfile.identity.versionCode, reviewedProfile.identity.sha256,
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
        val repositoryClient = ExtensionRepositoryClient(
            http,
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
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
        val reviewedProfile = ShuYueReviewedPluginCatalogV2.profiles.single {
            it.identity.packageId == "zh.bilimanga" && it.identity.version == "1.5.3"
        }
        val descriptor = reviewedProfile.descriptor.sources.single {
            it.sourceKey.sourceId == "zh.bilimanga.manga"
        }
        val sourceKey = descriptor.sourceKey
        manager.installReviewedRuntimeForTest(
            ImmutableExtensionPackageRuntimeV2(
                reviewedProfile.descriptor.copy(
                    sources = listOf(descriptor),
                    supportedContentKinds = descriptor.supportedContentKinds,
                ),
                listOf(
                    FailingLoginExtensionSource(
                        descriptor = descriptor,
                        webChallengeUserAgent = "reviewed-source-agent",
                        webChallengeUrl = "https://www.bilimanga.net/login.php",
                    ),
                ),
            ),
            PluginArtifactIdentity(
                packageId = reviewedProfile.identity.packageId,
                version = reviewedProfile.identity.version,
                versionCode = reviewedProfile.identity.versionCode,
                sha256 = reviewedProfile.identity.sha256,
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
    fun reviewedStorageScopeRejectsGenericSameKeyReplacement() = runTest {
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val http = HttpClient(MockEngine { error("Repository access is not expected") })
        val repositoryClient = ExtensionRepositoryClient(http)
        val manager = PluginManager(
            repositoryClient, InMemoryPluginPackageStore(), PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            NoopScriptPluginRuntimeFactory,
            ScriptPluginEnvironment(PluginNetworkClient(PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) }, storage), storage),
        )
        val profile = ShuYueReviewedPluginCatalogV2.profiles.single {
            it.identity.packageId == "zh.bilimanga" && it.identity.version == "1.5.3"
        }
        val descriptor = profile.descriptor.sources.single { it.sourceKey.sourceId == "zh.bilimanga.manga" }
        val reviewedRuntime = ImmutableExtensionPackageRuntimeV2(
            profile.descriptor.copy(sources = listOf(descriptor), supportedContentKinds = descriptor.supportedContentKinds),
            listOf(FailingLoginExtensionSource(descriptor, "reviewed-agent", descriptor.baseUrl)),
        )
        try {
            manager.installReviewedRuntimeForTest(
                reviewedRuntime,
                PluginArtifactIdentity(profile.identity.packageId, profile.identity.version, profile.identity.versionCode, profile.identity.sha256),
            )
            assertNotNull(manager.reviewedShuYueStorageScope(descriptor.sourceKey))

            val impostor = ImmutableExtensionPackageRuntimeV2(
                reviewedRuntime.descriptor,
                listOf(FailingLoginExtensionSource(descriptor, "impostor-agent", descriptor.baseUrl)),
            )
            manager.installExtensionRuntimeV2(impostor, replace = true)
            assertNull(manager.reviewedShuYueStorageScope(descriptor.sourceKey))
        } finally {
            manager.close()
            http.close()
        }
    }

    @Test
    fun uiAdapterRequiresCloudflareCookieForStandaloneBiliMangaSource() = runTest {
        val keyValues = SessionAccessTrackingKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val trust = KeyValuePluginTrustStore(keyValues)
        val http = HttpClient(MockEngine { error("Repository access is not expected") })
        val repositoryClient = ExtensionRepositoryClient(
            http,
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
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
        val sourceKey = SourceKey.fromLegacy("zh.bilimanga.manga", BILIMANGA_MANGA_SOURCE_ID)
        val descriptor = SourceDescriptorV2(
            sourceKey = sourceKey,
            displayName = "嗶哩漫畫",
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
                    version = "1.0.0",
                    displayName = "嗶哩漫畫",
                    sources = listOf(descriptor),
                ),
                listOf(
                    FailingLoginExtensionSource(
                        descriptor = descriptor,
                        webChallengeUserAgent = null,
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
            val rejected = assertFailsWith<IllegalArgumentException> {
                browse.setSourceEnabledV2(sourceKey, true)
            }
            assertTrue(rejected.message!!.contains("No host-owned storage"))
            assertTrue(keyValues.accessedSessionKeys.isEmpty())
        } finally {
            manager.close()
            http.close()
        }
    }

    @Test
    fun uiAdapterImportsAllowlistedV2BrowserStorageWithoutCookies() = runTest {
        val keyValues = SessionAccessTrackingKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val trust = KeyValuePluginTrustStore(keyValues)
        val http = HttpClient(MockEngine { error("Repository access is not expected") })
        val repositoryClient = ExtensionRepositoryClient(
            http,
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
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
        val sourceKey = SourceKey(2, "zh.bika", "8123456", 8_123_456L)
        val descriptor = SourceDescriptorV2(
            sourceKey = sourceKey,
            displayName = "哔咔漫画",
            languageTag = "zh",
            supportedContentKinds = setOf(ContentKind.IMAGE_SEQUENCE),
            capabilities = setOf(ExtensionCapability.CONTENT, ExtensionCapability.LOGIN),
            baseUrl = "https://manhuabika.com",
        )
        manager.installExtensionRuntimeV2(
            ImmutableExtensionPackageRuntimeV2(
                ExtensionPackageV2(
                    contractVersion = 2,
                    packageId = sourceKey.packageId,
                    version = "1.0.10",
                    displayName = "哔咔漫画",
                    sources = listOf(descriptor),
                ),
                listOf(
                    FailingLoginExtensionSource(
                        descriptor = descriptor,
                        webChallengeUserAgent = null,
                        webChallengeUrl = "https://manhuabika.com/",
                        webChallengeLocalStorageKeys = setOf("token", "nonce"),
                        requiredWebChallengeLocalStorageKeys = setOf("token", "nonce"),
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
            requestBuilder = PluginRequestBuilder(storage, PluginUserAgentProvider { "android-webview-agent" }),
        )

        try {
            browse.setPluginUiAvailable(true)
            val rejected = assertFailsWith<IllegalArgumentException> {
                browse.setSourceEnabledV2(sourceKey, true)
            }
            assertTrue(rejected.message!!.contains("No host-owned storage"))
            assertTrue(keyValues.accessedSessionKeys.isEmpty())
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
        val repositoryClient = ExtensionRepositoryClient(
            http,
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
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
    fun genericBrowserChallengeRequiresPermissionBeforeReadingOrWritingSession() = runTest {
        val fixture = genericBrowserChallengeFixture(
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
            browserSessionOrigins = setOf("https://source.example"),
        )
        try {
            fixture.manager.install(GENERIC_BROWSER_REPOSITORY, fixture.entry())
            fixture.manager.approveCurrentEventGrantReview(GENERIC_BROWSER_PLUGIN_ID, emptySet())
            fixture.browse.setPluginUiAvailable(true)

            assertFailsWith<IllegalArgumentException> {
                fixture.browse.sourceWebChallenge(GENERIC_BROWSER_SOURCE_ID)
            }
            assertFailsWith<IllegalArgumentException> {
                fixture.browse.importSourceWebChallengeSession(
                    sourceId = GENERIC_BROWSER_SOURCE_ID,
                    capability = dev.shinsou.kmp.ui.SourceWebChallengeCapability(),
                    cookies = listOf(
                        dev.shinsou.kmp.ui.SourceCookie(
                            name = "session",
                            value = "blocked",
                            domain = "source.example",
                        ),
                    ),
                    userAgent = "fixture-agent",
                )
            }
            assertTrue(fixture.keyValues.accessedSessionKeys.isEmpty())
        } finally {
            fixture.manager.close()
            fixture.http.close()
        }
    }

    @Test
    fun genericBrowserChallengeRequiresDeclaredExactOrigin() = runTest {
        val emptyOrigins = genericBrowserChallengeFixture(
            runtimePermissions = setOf(
                PluginRuntimePermission.EXECUTE_SCRIPT,
                PluginRuntimePermission.BROWSER_CHALLENGE,
            ),
            browserSessionOrigins = emptySet(),
        )
        val crossOrigin = genericBrowserChallengeFixture(
            runtimePermissions = setOf(
                PluginRuntimePermission.EXECUTE_SCRIPT,
                PluginRuntimePermission.BROWSER_CHALLENGE,
            ),
            browserSessionOrigins = setOf("https://source.example"),
            challengeUrl = "https://evil.example/login",
        )
        try {
            emptyOrigins.manager.install(GENERIC_BROWSER_REPOSITORY, emptyOrigins.entry())
            emptyOrigins.manager.approveCurrentEventGrantReview(GENERIC_BROWSER_PLUGIN_ID, emptySet())
            emptyOrigins.browse.setPluginUiAvailable(true)
            assertFailsWith<IllegalArgumentException> {
                emptyOrigins.browse.sourceWebChallenge(GENERIC_BROWSER_SOURCE_ID)
            }

            crossOrigin.manager.install(GENERIC_BROWSER_REPOSITORY, crossOrigin.entry())
            crossOrigin.manager.approveCurrentEventGrantReview(GENERIC_BROWSER_PLUGIN_ID, emptySet())
            crossOrigin.browse.setPluginUiAvailable(true)
            assertFailsWith<IllegalArgumentException> {
                crossOrigin.browse.sourceWebChallenge(GENERIC_BROWSER_SOURCE_ID)
            }
        } finally {
            emptyOrigins.manager.close()
            emptyOrigins.http.close()
            crossOrigin.manager.close()
            crossOrigin.http.close()
        }
    }

    @Test
    fun webChallengeImportRollbackRestoresAbsentOptionalStorageAfterMidCommitFailure() = runTest {
        val fixture = reviewedBiliMangaWebChallengeRollbackFixture()
        try {
            val authorization = assertNotNull(fixture.manager.issueWebChallenge(fixture.sourceKey))
            val failure = assertFailsWith<IllegalStateException> {
                fixture.manager.importWebChallengeSession(
                    sourceKey = fixture.sourceKey,
                    capability = authorization.capability,
                    cookies = listOf(
                        dev.shinsou.kmp.ui.SourceCookie(
                            name = "cf_clearance",
                            value = "new-cookie",
                            domain = "www.bilimanga.net",
                        ),
                    ),
                    userAgent = "new-browser-agent",
                    localStorage = mapOf(
                        "token" to "new-token",
                        "nonce" to "new-nonce",
                    ),
                )
            }
            assertEquals("Injected preference write failure for nonce", failure.message)

            assertNull(fixture.storage.getPreference(authorization.storageId, "token"))
            assertNull(fixture.storage.getPreference(authorization.storageId, "nonce"))
            assertTrue(fixture.storage.getCookies(authorization.storageId).isEmpty())
            assertNull(fixture.storage.getWebChallengeUserAgent(authorization.storageId))
        } finally {
            fixture.manager.close()
            fixture.http.close()
        }
    }

    @Test
    fun webChallengeImportRollbackRestoresExistingStorageCookiesAndUserAgentAfterMidCommitFailure() = runTest {
        val fixture = reviewedBiliMangaWebChallengeRollbackFixture()
        try {
            val authorization = assertNotNull(fixture.manager.issueWebChallenge(fixture.sourceKey))
            fixture.seedExistingSession(authorization.storageId)

            val failure = assertFailsWith<IllegalStateException> {
                fixture.manager.importWebChallengeSession(
                    sourceKey = fixture.sourceKey,
                    capability = authorization.capability,
                    cookies = listOf(
                        dev.shinsou.kmp.ui.SourceCookie(
                            name = "cf_clearance",
                            value = "new-cookie",
                            domain = "www.bilimanga.net",
                        ),
                    ),
                    userAgent = "new-browser-agent",
                    localStorage = mapOf(
                        "token" to "new-token",
                        "nonce" to "new-nonce",
                    ),
                )
            }
            assertEquals("Injected preference write failure for nonce", failure.message)

            assertEquals("old-token", fixture.storage.getPreference(authorization.storageId, "token"))
            assertEquals("old-nonce", fixture.storage.getPreference(authorization.storageId, "nonce"))
            assertEquals(
                listOf(PluginCookie("cf_clearance", "old-cookie", "www.bilimanga.net")),
                fixture.storage.getCookies(authorization.storageId),
            )
            assertEquals("old-browser-agent", fixture.storage.getWebChallengeUserAgent(authorization.storageId))
        } finally {
            fixture.manager.close()
            fixture.http.close()
        }
    }

    @Test
    fun browserChallengeCompatibilityIsLimitedToPreContractStoredMetadata() = runTest {
        val newInstall = genericBrowserChallengeFixture(
            runtimePermissions = null,
            browserSessionOrigins = setOf("https://source.example"),
        )
        assertFailsWith<IllegalArgumentException> {
            newInstall.manager.install(GENERIC_BROWSER_REPOSITORY, newInstall.entry())
        }
        assertTrue(newInstall.packageStore.list().isEmpty())

        val legacyAllowed = genericBrowserChallengeFixture(
            runtimePermissions = null,
            browserSessionOrigins = emptySet(),
            originPolicyVersion = null,
            legacyStorageMigrationAllowed = true,
        )
        val legacyDenied = genericBrowserChallengeFixture(
            runtimePermissions = null,
            browserSessionOrigins = emptySet(),
            originPolicyVersion = null,
            legacyStorageMigrationAllowed = false,
        )
        try {
            listOf(legacyAllowed, legacyDenied).forEach { fixture ->
                val bytes = GENERIC_BROWSER_SCRIPT.encodeToByteArray()
                val hash = Sha256.hex(bytes)
                fixture.packageStore.put(
                    StoredPlugin(
                        InstalledPluginMetadata(
                            manifest = fixture.manifest(),
                            repositoryBaseUrl = GENERIC_BROWSER_REPOSITORY.baseUrl,
                            installedSha256 = hash,
                            legacyTrustOnInstall = true,
                            legacyStorageMigrationAllowed = fixture.legacyStorageMigrationAllowed,
                        ),
                        bytes,
                    ),
                )
                fixture.manager.loadInstalled()
                assertFalse(fixture.manager.hasExecutionApproval(GENERIC_BROWSER_PLUGIN_ID))
                assertFailsWith<IllegalArgumentException> {
                    fixture.manager.approveCurrentEventGrantReview(GENERIC_BROWSER_PLUGIN_ID, emptySet())
                }
                assertNull(fixture.manager.source(GENERIC_BROWSER_SOURCE_ID))
            }
            assertFalse(legacyAllowed.manager.authorizeWebChallenge(GENERIC_BROWSER_SOURCE_ID))
            assertFalse(legacyDenied.manager.authorizeWebChallenge(GENERIC_BROWSER_SOURCE_ID))
        } finally {
            newInstall.manager.close()
            newInstall.http.close()
            legacyAllowed.manager.close()
            legacyAllowed.http.close()
            legacyDenied.manager.close()
            legacyDenied.http.close()
        }
    }

    @Test
    fun uiAdaptersInstallBrowseAndBuildReaderRequestsWithSharedSemantics() = runTest {
        val index = """[{"id":"all.test","name":"Test","version":"1.0.0","versionCode":1,
            "lang":"all","nsfw":0,"runtimePermissions":["EXECUTE_SCRIPT","NETWORK","COOKIE_STORAGE","CREDENTIAL_ACCESS","LOGIN_PROMPT","FAVORITE_MUTATION","BROWSER_CHALLENGE"],"scriptUrl":"plugins/all.test.js","sources":[
            {"name":"Test Source","lang":"all","id":123,"baseUrl":"https://source.example",
             "contentOrigins":["https://source.example","https://images.example"],"originPolicyVersion":1}]}]"""
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
        val repositoryClient = ExtensionRepositoryClient(
            HttpClient(engine),
            cacheToken = { 1L },
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
        )
        val manager = PluginManager(
            repositoryClient,
            KeyValuePluginPackageStore(kv),
            PluginVerifier(trust),
            runtimeFactory,
            ScriptPluginEnvironment(
                PluginNetworkClient(
                    object : PluginHttpTransport {
                        override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                            if (request.url.endsWith("/html-cover")) {
                                PluginHttpResponse(
                                    200,
                                    "<html>upstream error</html>".encodeToByteArray(),
                                    mapOf("Content-Type" to listOf("text/html")),
                                )
                            } else {
                                PluginHttpResponse(200, "fixture-image".encodeToByteArray(), emptyMap())
                            }

                        override suspend fun executeResolved(
                            request: PluginHttpRequest,
                            resolution: PluginHostResolution,
                        ): PluginHttpResponse = execute(request)
                    },
                    storage,
                    requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
                    hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                ),
                storage,
                loginRequester = loginRequests,
            ),
            executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
        )
        val browse = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(kv).also {
                it.put(ExtensionRepository("https://repo.example", "Test Repository"))
            },
            pluginStorage = storage,
            keyValueStore = kv,
            trustStore = trust,
            requestBuilder = PluginRequestBuilder(
                storage,
                userAgents = PluginUserAgentProvider { "challenge-agent" },
            ),
            loginRequestCoordinator = loginRequests,
        )

        browse.refresh()
        assertEquals(1, indexRequestCount)
        assertEquals("Test Repository", browse.state.value.repositories.single().name)
        assertFalse(browse.state.value.extensions.single().installed)
        browse.installExtension("all.test")
        manager.setPluginUiAvailable(true)
        assertEquals(1, indexRequestCount, "install must reuse its fetched index when rebuilding UI state")
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
        val proxyPreference = sourceSettings.preferences.first {
            it.key == ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE
        }
        assertEquals("global", proxyPreference.value)
        assertEquals(
            listOf("Follow global", "Force enable", "Force disable"),
            proxyPreference.choices,
        )
        assertEquals(listOf("global", "on", "off"), proxyPreference.choiceValues)
        assertEquals(
            listOf(BrowseFilter.Select("Genre", listOf("All", "Action"), 0)),
            sourceSettings.filters,
        )
        // Legacy UI rows intentionally do not expose a SourceKey. Resolve the backing view
        // through the manager so the assertion still reads the host-derived exact namespace.
        val sourceStorage = assertNotNull(manager.storageForSource(123))
        assertFailsWith<IllegalArgumentException> {
            browse.sourceWebChallenge(123)
        }

        browse.saveSourcePreferences(123, mapOf("language" to "en", "show_nsfw" to "true"))
        assertEquals("en", sourceStorage.getPreference(123, "language"))
        assertEquals("en", browse.state.value.sources.single().preferences.first { it.key == "language" }.value)

        browse.saveSourcePreferences(
            123,
            mapOf(ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE to " ON "),
        )
        assertEquals(
            "on",
            sourceStorage.getPreference(123, ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE),
        )
        assertEquals(
            "on",
            browse.state.value.sources.single().preferences.first {
                it.key == ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE
            }.value,
        )

        assertTrue(loginRequests.request(123, "Test Source", "Account required"))
        assertEquals(
            listOf(SourceLoginRequest(123, "Test Source", "Account required")),
            browse.loginRequests.value,
        )
        val failedLogin = browse.saveSourceCredentialsResult(123, "alice", "wrong")
        assertFalse(failedLogin.succeeded)
        assertEquals("帳號或密碼錯誤", failedLogin.errorMessage)
        assertEquals(null, sourceStorage.getCredential(123))
        assertEquals(1, browse.loginRequests.value.size)
        assertTrue(browse.saveSourceCredentials(123, "alice", "secret"))
        assertEquals(PluginCredential("alice", "secret"), sourceStorage.getCredential(123))
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
        assertEquals(
            "fixture-image",
            assertNotNull(
                browse.loadPluginThumbnail(
                    sourceId = result.items.single().sourceId,
                    url = requireNotNull(result.items.single().thumbnailUrl),
                    headers = result.items.single().thumbnailHeaders,
                ),
            ).decodeToString(),
            "browse covers must pass through the source-scoped, credential-free content plane",
        )
        assertNull(
            browse.loadPluginThumbnail(
                sourceId = result.items.single().sourceId,
                url = "https://source.example/html-cover",
            ),
            "a successful HTML error page must not be handed to the cover image decoder",
        )

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
        val pendingPage = reader.pages.single()
        // Remote plugin URLs stay inert until the host-owned, policy-checked lazy resolver runs.
        val page = assertNotNull(pendingPage.imageResolver).invoke()
        assertTrue(page.imageUrl.isEmpty())
        assertEquals("fixture-image", assertNotNull(page.imageBytes).decodeToString())
        assertEquals("https://source.example/chapter/1", reader.referer)
        assertTrue(page.headers.isEmpty())

        runtimeFactory.runtime?.viewerPage = true
        val viewerReader = content.loadReaderChapter(10, 20)
        assertEquals(
            null,
            runtimeFactory.runtime?.lastResolvedImagePage,
            "viewer URL resolution must remain lazy while the chapter opens",
        )
        val viewerPage = assertNotNull(viewerReader.pages.single().imageResolver).invoke()
        assertEquals("fixture-image", assertNotNull(viewerPage.imageBytes).decodeToString())
        assertEquals("/viewer/one", runtimeFactory.runtime?.lastResolvedImagePage)

        browse.deleteSourceCookie(123, "session", ".images.example")
        assertTrue(browse.loadSourceSecrets(123).secrets.cookies.isEmpty())
        browse.setSourceCookie(123, dev.shinsou.kmp.ui.SourceCookie("one", "1", ".source.example"))
        browse.setSourceCookie(123, dev.shinsou.kmp.ui.SourceCookie("two", "2", ".source.example"))
        assertEquals(
            listOf("one", "two"),
            browse.loadSourceSecrets(123).secrets.cookies.map { it.name },
        )
        assertFailsWith<IllegalArgumentException> {
            browse.sourceWebChallenge(123)
        }
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

private const val GENERIC_BROWSER_PLUGIN_ID = "all.browser-gate"
private const val GENERIC_BROWSER_SOURCE_ID = 8_701L
private const val GENERIC_BROWSER_SCRIPT = "var source = { browser: true };"
private val GENERIC_BROWSER_REPOSITORY = ExtensionRepository("https://repo.example", "Generic browser gate")

private data class ReviewedBiliMangaWebChallengeRollbackFixture(
    val manager: PluginManager,
    val storage: FaultInjectingWebChallengeStorage,
    val sourceKey: SourceKey,
    val http: HttpClient,
) {
    suspend fun seedExistingSession(storageId: Long) {
        storage.setCookie(storageId, PluginCookie("cf_clearance", "old-cookie", "www.bilimanga.net"))
        storage.setPreference(storageId, "token", "old-token")
        storage.setPreference(storageId, "nonce", "old-nonce")
        storage.setWebChallengeUserAgent(storageId, "old-browser-agent")
        storage.failNextNonceWrite = true
    }
}

@OptIn(ExtensionImplementationApi::class)
private suspend fun reviewedBiliMangaWebChallengeRollbackFixture(): ReviewedBiliMangaWebChallengeRollbackFixture {
    val keyValues = InMemoryPluginKeyValueStore()
    val backingStorage = KeyValuePluginStorage(keyValues)
    val storage = FaultInjectingWebChallengeStorage(backingStorage)
    val trust = KeyValuePluginTrustStore(keyValues)
    val http = HttpClient(MockEngine { error("Repository access is not expected") })
    val repositoryClient = ExtensionRepositoryClient(
        http,
        repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
    )
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
    val profile = ShuYueReviewedPluginCatalogV2.profiles.single {
        it.identity.packageId == "zh.bilimanga" && it.identity.version == "1.5.3"
    }
    val descriptor = profile.descriptor.sources.single { it.sourceKey.sourceId == "zh.bilimanga.manga" }
    val sourceKey = descriptor.sourceKey
    manager.installReviewedRuntimeForTest(
        ImmutableExtensionPackageRuntimeV2(
            profile.descriptor.copy(
                sources = listOf(descriptor),
                supportedContentKinds = descriptor.supportedContentKinds,
            ),
            listOf(
                FailingLoginExtensionSource(
                    descriptor = descriptor,
                    webChallengeUserAgent = "reviewed-source-agent",
                    webChallengeUrl = "https://www.bilimanga.net/login.php",
                    webChallengeLocalStorageKeys = setOf("token", "nonce"),
                    requiredWebChallengeLocalStorageKeys = setOf("token"),
                ),
            ),
        ),
        PluginArtifactIdentity(
            packageId = profile.identity.packageId,
            version = profile.identity.version,
            versionCode = profile.identity.versionCode,
            sha256 = profile.identity.sha256,
        ),
    )
    return ReviewedBiliMangaWebChallengeRollbackFixture(manager, storage, sourceKey, http)
}

private class FaultInjectingWebChallengeStorage(
    private val delegate: PluginStorage,
) : PluginStorage {
    var failNextNonceWrite: Boolean = true

    override suspend fun getPreference(sourceId: Long, key: String): String? =
        delegate.getPreference(sourceId, key)

    override suspend fun setPreference(sourceId: Long, key: String, value: String) {
        if (key == "nonce" && failNextNonceWrite && value == "new-nonce") {
            failNextNonceWrite = false
            error("Injected preference write failure for nonce")
        }
        delegate.setPreference(sourceId, key, value)
    }

    override suspend fun removePreference(sourceId: Long, key: String) =
        delegate.removePreference(sourceId, key)

    override suspend fun getCredential(sourceId: Long): PluginCredential? = delegate.getCredential(sourceId)
    override suspend fun setCredential(sourceId: Long, credential: PluginCredential) =
        delegate.setCredential(sourceId, credential)
    override suspend fun clearCredential(sourceId: Long) = delegate.clearCredential(sourceId)

    override suspend fun getCookies(sourceId: Long): List<PluginCookie> = delegate.getCookies(sourceId)
    override suspend fun setCookie(sourceId: Long, cookie: PluginCookie) = delegate.setCookie(sourceId, cookie)
    override suspend fun deleteCookie(sourceId: Long, name: String, domain: String) =
        delegate.deleteCookie(sourceId, name, domain)
    override suspend fun deleteCookieExact(sourceId: Long, name: String, domain: String, path: String) =
        delegate.deleteCookieExact(sourceId, name, domain, path)
    override suspend fun clearCookies(sourceId: Long) = delegate.clearCookies(sourceId)

    override suspend fun getWebChallengeUserAgent(sourceId: Long): String? =
        delegate.getWebChallengeUserAgent(sourceId)
    override suspend fun setWebChallengeUserAgent(sourceId: Long, userAgent: String) =
        delegate.setWebChallengeUserAgent(sourceId, userAgent)
    override suspend fun clearWebChallengeUserAgent(sourceId: Long) =
        delegate.clearWebChallengeUserAgent(sourceId)
}

private data class GenericBrowserChallengeFixture(
    val manager: PluginManager,
    val browse: PluginBrowseAdapter,
    val packageStore: InMemoryPluginPackageStore,
    val keyValues: SessionAccessTrackingKeyValueStore,
    val http: HttpClient,
    val runtimePermissions: Set<PluginRuntimePermission>?,
    val browserSessionOrigins: Set<String>,
    val challengeUrl: String,
    val originPolicyVersion: Int?,
    val legacyStorageMigrationAllowed: Boolean,
) {
    fun entry(): PluginIndexEntry = PluginIndexEntry(
        id = GENERIC_BROWSER_PLUGIN_ID,
        name = "Generic browser gate fixture",
        version = "1.0.0",
        versionCode = 1,
        lang = "all",
        scriptUrl = "plugins/$GENERIC_BROWSER_PLUGIN_ID.js",
        sources = listOf(
            SourceIndexEntry(
                name = "Generic browser gate source",
                lang = "all",
                id = GENERIC_BROWSER_SOURCE_ID,
                baseUrl = "https://source.example",
                browserSessionOrigins = browserSessionOrigins,
                originPolicyVersion = originPolicyVersion,
            ),
        ),
        runtimePermissions = runtimePermissions,
    )

    fun manifest(): PluginManifest = PluginManifest(
        id = GENERIC_BROWSER_PLUGIN_ID,
        name = "Generic browser gate fixture",
        version = "1.0.0",
        versionCode = 1,
        lang = "all",
        script = "$GENERIC_BROWSER_PLUGIN_ID.js",
        signature = Sha256.hex(GENERIC_BROWSER_SCRIPT.encodeToByteArray()),
        sources = entry().sources,
        runtimePermissions = runtimePermissions,
    )
}

private suspend fun genericBrowserChallengeFixture(
    runtimePermissions: Set<PluginRuntimePermission>?,
    browserSessionOrigins: Set<String>,
    challengeUrl: String = "https://source.example/login",
    originPolicyVersion: Int? = 1,
    legacyStorageMigrationAllowed: Boolean = false,
): GenericBrowserChallengeFixture {
    val keyValues = SessionAccessTrackingKeyValueStore()
    val storage = KeyValuePluginStorage(keyValues)
    val packageStore = InMemoryPluginPackageStore()
    val trustStore = KeyValuePluginTrustStore(keyValues)
    val http = HttpClient(MockEngine { respond(GENERIC_BROWSER_SCRIPT) })
    val repositoryClient = ExtensionRepositoryClient(
        http,
        repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
    )
    val manager = PluginManager(
        repositoryClient = repositoryClient,
        packageStore = packageStore,
        verifier = PluginVerifier(trustStore),
        runtimeFactory = RecordingRuntimeFactory(
            webChallengeUrl = challengeUrl,
            browserSessionOrigins = browserSessionOrigins,
        ),
        environment = ScriptPluginEnvironment(
            network = PluginNetworkClient(
                PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) },
                storage,
                requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
            ),
            storage = storage,
        ),
        eventGrantAdmission = KeyValuePluginEventGrantAdmission(
            keyValues,
            dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer(),
        ),
    )
    val browse = PluginBrowseAdapter(
        manager = manager,
        repositoryClient = repositoryClient,
        repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
        pluginStorage = storage,
        keyValueStore = keyValues,
        trustStore = trustStore,
        requestBuilder = PluginRequestBuilder(
            storage,
            userAgents = PluginUserAgentProvider { "fixture-agent" },
        ),
    )
    return GenericBrowserChallengeFixture(
        manager,
        browse,
        packageStore,
        keyValues,
        http,
        runtimePermissions,
        browserSessionOrigins,
        challengeUrl,
        originPolicyVersion,
        legacyStorageMigrationAllowed,
    )
}

private class RecordingRuntimeFactory(
    private val supportsLogin: Boolean = true,
    private val sourceHeaders: Map<String, String> = emptyMap(),
    private val webChallengeUrl: String? = null,
    private val browserSessionOrigins: Set<String> = emptySet(),
) : ScriptPluginRuntimeFactory {
    var runtime: RecordingRuntime? = null
    val createdPluginIds = mutableListOf<String>()
    val runtimes = mutableMapOf<String, RecordingRuntime>()

    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime = RecordingRuntime(
        manifest,
        supportsLogin,
        sourceHeaders,
        webChallengeUrl,
        browserSessionOrigins,
    ).also {
        runtime = it
        createdPluginIds += manifest.id
        runtimes[manifest.id] = it
    }
}

private class RecordingRuntime(
    manifest: PluginManifest,
    override val supportsLogin: Boolean,
    override val headers: Map<String, String>,
    override val webChallengeUrl: String? = null,
    override val browserSessionOrigins: Set<String> = emptySet(),
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
    var viewerPage: Boolean = false
    var lastResolvedImagePage: String? = null

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
    override suspend fun getPageList(chapter: SChapter): List<Page> = if (viewerPage) {
        listOf(Page(0, url = "/viewer/one", imageUrl = null))
    } else {
        listOf(
            Page(
                0,
                imageUrl = "https://images.example/one.jpg#Referer=https%3A%2F%2Fsource.example%2F&" +
                    "Shinsou-JM-Photo-Id=123",
            ),
        )
    }
    override suspend fun resolveImageUrl(pageUrl: String): String {
        lastResolvedImagePage = pageUrl
        return "https://images.example/one.jpg#Referer=" +
            "https%3A%2F%2Fsource.example%2Fviewer%2Fone"
    }
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
    override val webChallengeLocalStorageKeys: Set<String> = emptySet(),
    override val requiredWebChallengeLocalStorageKeys: Set<String> = emptySet(),
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
            repositoryTrustPolicy = RepositoryTrustPolicies.UNSIGNED_DEVELOPER_COMPATIBILITY,
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
        executionAdmissionMode = PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY,
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

private class SessionAccessTrackingKeyValueStore : PluginKeyValueStore {
    private val values = mutableMapOf<String, String>()
    val accessedSessionKeys = mutableSetOf<String>()

    override suspend fun getString(key: String): String? {
        record(key)
        return values[key]
    }

    override suspend fun putString(key: String, value: String) {
        record(key)
        values[key] = value
    }

    override suspend fun remove(key: String) {
        record(key)
        values.remove(key)
    }

    private fun record(key: String) {
        if ((key.startsWith("source.") || key.startsWith("source.v2.")) &&
            (".credential." in key || key.endsWith(".cookies") || ".webChallenge." in key)
        ) {
            accessedSessionKeys += key
        }
    }
}
