@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.ExtensionRepositoryClient
import dev.shinsou.kmp.plugin.InMemoryPluginPackageStore
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.KeyValueExtensionRepositoryStore
import dev.shinsou.kmp.plugin.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.KeyValuePluginTrustStore
import dev.shinsou.kmp.plugin.PluginHttpRequest
import dev.shinsou.kmp.plugin.PluginHttpResponse
import dev.shinsou.kmp.plugin.PluginHttpTransport
import dev.shinsou.kmp.plugin.PluginManager
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.PluginBrowseAdapter
import dev.shinsou.kmp.plugin.PluginCredential
import dev.shinsou.kmp.plugin.PluginVerifier
import dev.shinsou.kmp.plugin.RhinoScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.HostExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.LegacyLoginCredentialsResolverV2
import dev.shinsou.kmp.plugin.v2.LegacyLoginCredentialsV2
import dev.shinsou.kmp.plugin.v2.LoginCredentialsV2
import dev.shinsou.kmp.plugin.v2.TextPayloadSourceV2
import dev.shinsou.kmp.plugin.v2.UnitContentPayload
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionShuYueReviewedRuntimeV2Test {
    @Test
    fun productionRepositoryCallbacksExposeExplicitReviewedInstallAndUninstallFlow() = runTest {
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val network = PluginNetworkClient(
            transport = PluginHttpTransport(::fixtureResponse),
            storage = storage,
        )
        val repositoryClient = ExtensionRepositoryClient(
            HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
        )
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            environment = ScriptPluginEnvironment(network, storage),
        )
        val callbacks = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = KeyValuePluginTrustStore(keyValues),
            reviewedShuYueRepositoryLoaderV2 = ShuYueRepositoryIndexLoader(
                transport = ShuYueRepositoryTransport(::fixtureRepositoryResponse),
                limits = ShuYueRepositoryLimits(
                    allowedArtifactOrigins = setOf(REVIEWED_ARTIFACT_ORIGIN),
                ),
            ),
            reviewedShuYueRepositoryLocationV2 = ShuYueRepositoryLocation.IndexUrl(REVIEWED_INDEX_URL),
        )

        callbacks.refresh()
        val reviewed = callbacks.state.value.extensions.filter { it.reviewedShuYueV2 }
        assertEquals(
            listOf("zh.biquge.tw", "zh.wenku8", "zh.wenku8.api"),
            reviewed.map { it.id }.sorted(),
        )
        assertTrue(reviewed.none { it.installed })

        val review = callbacks.stageReviewedShuYuePackageV2("zh.biquge.tw")
        val sourceKey = SourceKey(2, "zh.biquge.tw", "zh.biquge.tw")
        assertEquals(ShuYueReviewStatusV2.REVIEWED, review.reviewStatus)
        assertNull(callbacks.extensionSourceV2(sourceKey))
        callbacks.approveAndInstallReviewedShuYueV2(
            ShuYueReviewedInstallApprovalV2(
                quarantineId = review.quarantineId,
                identity = review.identity,
                grantedPermissions = review.requiredPermissions,
                userConfirmed = true,
                replaceInstalledVersion = false,
            ),
        )
        assertNotNull(callbacks.extensionSourceV2(sourceKey))
        assertTrue(callbacks.state.value.extensions.single { it.id == "zh.biquge.tw" }.trusted)
        assertTrue(callbacks.state.value.sources.any { it.sourceKey == sourceKey })

        callbacks.uninstallReviewedShuYueV2("zh.biquge.tw")
        assertNull(callbacks.extensionSourceV2(sourceKey))
        assertFalse(callbacks.state.value.extensions.single { it.id == "zh.biquge.tw" }.installed)
        manager.close()
    }

    @Test
    fun reviewedBiqugeExecutesThroughProductionBrowseDetailContentCallbacks() = runTest {
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val network = PluginNetworkClient(
            transport = PluginHttpTransport(::fixtureResponse),
            storage = storage,
        )
        val repositoryClient = ExtensionRepositoryClient(
            HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
        )
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            environment = ScriptPluginEnvironment(network, storage),
        )
        val approvals = InMemoryShuYueExecutionApprovalsV2()
        val admission = productionShuYueReviewedAdmissionV2(
            quarantineStore = InMemoryShuYueScriptQuarantineStoreV2(),
            trustStore = approvals,
            permissionStore = approvals,
            runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            environment = ScriptPluginEnvironment(network, storage),
        )

        val biquge = prepare("zh.biquge.tw", "biquge-tw.js", admission, approvals)
        val wenkuApi = prepare("zh.wenku8.api", "wenku8-api.js", admission, approvals)
        manager.installReviewedShuYueRuntimeV2(admission, biquge)
        manager.installReviewedShuYueRuntimeV2(admission, wenkuApi)

        val callbacks = PluginBrowseAdapter(
            manager = manager,
            repositoryClient = repositoryClient,
            repositoryStore = KeyValueExtensionRepositoryStore(keyValues),
            pluginStorage = storage,
            keyValueStore = keyValues,
            trustStore = KeyValuePluginTrustStore(keyValues),
        )
        val sourceKey = SourceKey(2, "zh.biquge.tw", "zh.biquge.tw")
        val otherKey = SourceKey(2, "zh.wenku8.api", "zh.wenku8.api")

        assertNotNull(callbacks.extensionSourceV2(sourceKey))
        assertNotNull(callbacks.extensionSourceV2(otherKey))
        assertNull(callbacks.extensionSourceV2(SourceKey(2, "zh.biquge.tw", "wrong")))

        val browse = callbacks.browseSourceV2(
            sourceKey,
            BrowseOptionsV2(mapOf("option" to "top:lastupdate")),
        )
        val publication = browse.items.single()
        assertEquals("https://www.biquge.tw/book/123.html", publication.remoteId)

        val details = callbacks.extensionDetailsV2(sourceKey, publication.remoteId)
        assertEquals(publication.remoteId, details.remoteId)
        assertEquals("Fixture Book", details.title)

        val unit = callbacks.extensionUnitsV2(sourceKey, publication.remoteId).items.single()
        assertEquals("Fixture Chapter", unit.title)
        val content = callbacks.extensionContentV2(sourceKey, publication.remoteId, unit.remoteId)
        assertEquals(sourceKey, content.sourceKey)
        assertEquals(listOf("shuyue-inline-text"), content.representations.map { it.representationId })
        val text = assertIs<UnitContentPayload.InlineTextPayload>(content.representations.single()).source.text
        assertTrue("Hello" in text && "world" in text)

        val biqugeProfile = requireNotNull(ShuYueReviewedPluginCatalogV2.profiles.singleOrNull {
            it.identity.packageId == "zh.biquge.tw"
        })
        val executionScope = BuiltInShuYueExecutionScopesV2.resolve(biqugeProfile.identity, sourceKey)
        assertTrue(storage.getCookies(executionScope).isEmpty())
        approvals.revokeTrust(biqugeProfile.identity)
        assertFailsWith<ShuYueAdmissionException.NotTrusted> {
            callbacks.extensionContentV2(sourceKey, publication.remoteId, unit.remoteId)
        }
        manager.close()
    }

    @Test
    fun reviewedLargeTextUsesOneUseBoundedCursorStreamAndSupportsCancellation() = runTest {
        val largeText = "界".repeat((4 * 1024 * 1024 / 3) + 17)
        val fixture = installedReviewedSource(
            packageId = "zh.biquge.tw",
            resourceName = "biquge-tw.js",
            transport = PluginHttpTransport { request ->
                require(request.method == "GET")
                require(request.url == "https://www.biquge.tw/book/123/456.html")
                PluginHttpResponse(
                    status = 200,
                    body = "<div id=\"chaptercontent\">$largeText</div>".encodeToByteArray(),
                )
            },
        )

        val first = fixture.source.content(
            "https://www.biquge.tw/book/123.html",
            "https://www.biquge.tw/book/123/456.html",
        ).representations.single()
        val firstPayload = assertIs<UnitContentPayload.ChunkedTextPayload>(first)
        val firstPlan = assertIs<TextPayloadSourceV2.ChunkedTextPayload>(firstPayload.source)
        assertEquals(64 * 1024, firstPlan.maxChunkBytes)
        assertTrue(firstPlan.maxTotalBytes > 4L * 1024L * 1024L)

        val stream = fixture.source.openTextStream(firstPlan)
        val reconstructed = StringBuilder(largeText.length)
        var cursor = firstPlan.firstCursor
        var chunks = 0
        do {
            val result = stream.next(cursor)
            assertTrue(result.utf8Text.encodeToByteArray().size <= firstPlan.maxChunkBytes)
            reconstructed.append(result.utf8Text)
            chunks++
            cursor = result.nextCursor
        } while (!result.done)
        assertTrue(chunks > 1)
        assertEquals(largeText, reconstructed.toString())
        assertFailsWith<IllegalArgumentException> { fixture.source.openTextStream(firstPlan) }

        val cancellable = assertIs<UnitContentPayload.ChunkedTextPayload>(
            fixture.source.content(
                "https://www.biquge.tw/book/123.html",
                "https://www.biquge.tw/book/123/456.html",
            ).representations.single(),
        )
        val cancellablePlan = assertIs<TextPayloadSourceV2.ChunkedTextPayload>(cancellable.source)
        val cancelledStream = fixture.source.openTextStream(cancellablePlan)
        cancelledStream.cancel()
        cancelledStream.cancel()
        assertFailsWith<IllegalStateException> { cancelledStream.next(cancellablePlan.firstCursor) }

        val activeStreams = List(8) {
            val payload = assertIs<UnitContentPayload.ChunkedTextPayload>(
                fixture.source.content(
                    "https://www.biquge.tw/book/123.html",
                    "https://www.biquge.tw/book/123/456.html",
                ).representations.single(),
            )
            val plan = assertIs<TextPayloadSourceV2.ChunkedTextPayload>(payload.source)
            fixture.source.openTextStream(plan)
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.source.content(
                "https://www.biquge.tw/book/123.html",
                "https://www.biquge.tw/book/123/456.html",
            )
        }
        activeStreams.first().cancel()
        val afterRelease = assertIs<UnitContentPayload.ChunkedTextPayload>(
            fixture.source.content(
                "https://www.biquge.tw/book/123.html",
                "https://www.biquge.tw/book/123/456.html",
            ).representations.single(),
        )
        fixture.source.openTextStream(
            assertIs<TextPayloadSourceV2.ChunkedTextPayload>(afterRelease.source),
        ).cancel()
        activeStreams.drop(1).forEach { it.cancel() }

        val unopened = assertIs<UnitContentPayload.ChunkedTextPayload>(
            fixture.source.content(
                "https://www.biquge.tw/book/123.html",
                "https://www.biquge.tw/book/123/456.html",
            ).representations.single(),
        )
        val unopenedPlan = assertIs<TextPayloadSourceV2.ChunkedTextPayload>(unopened.source)
        fixture.manager.close()
        assertFailsWith<IllegalStateException> { fixture.source.openTextStream(unopenedPlan) }
    }

    @Test
    fun reviewedWenku8HtmlExecutesBrowseDetailUnitsContentLoginAndFavorite() = runTest {
        val requests = mutableListOf<PluginHttpRequest>()
        val fixture = installedReviewedSource(
            packageId = "zh.wenku8",
            resourceName = "wenku8.js",
            transport = PluginHttpTransport { request ->
                requests += request
                wenkuHtmlResponse(request)
            },
            credentialsResolver = fixtureCredentialsResolver(),
        )

        val publication = fixture.source.browse(
            BrowseOptionsV2(mapOf("option" to "rank:lastupdate")),
            page = 0,
        ).items.single()
        assertEquals("https://www.wenku8.net/book/123.htm", publication.remoteId)
        assertEquals("Fixture Book", fixture.source.details(publication.remoteId).title)

        val unit = fixture.source.units(publication.remoteId, page = 0).items.single()
        assertEquals("Fixture Chapter", unit.title)
        assertEquals("https://www.wenku8.net/novel/0/123/456.htm", unit.remoteId)
        val content = fixture.source.content(publication.remoteId, unit.remoteId)
        val text = assertIs<UnitContentPayload.InlineTextPayload>(content.representations.single()).source.text
        assertTrue("Hello" in text && "world" in text)

        assertTrue(
            fixture.source.login(LoginCredentialsV2("username-ref", "password-ref")).loggedIn,
        )
        assertStoredLoginAndCookie(fixture, "www.wenku8.net")
        fixture.source.favorite(publication.remoteId, favorite = true)
        assertEquals(
            "wenku-session=abc",
            requests.single { it.url.contains("addbookcase.php") }.headers["Cookie"],
        )
        fixture.manager.close()
    }

    @Test
    fun reviewedWenku8ApiExecutesBrowseDetailUnitsContentLoginAndFavorite() = runTest {
        val innerRequests = mutableListOf<String>()
        val favoriteRequests = mutableListOf<PluginHttpRequest>()
        val fixture = installedReviewedSource(
            packageId = "zh.wenku8.api",
            resourceName = "wenku8-api.js",
            transport = PluginHttpTransport { request ->
                val inner = decodeRelayRequest(request)
                innerRequests += inner
                if (inner == "action=bookcase&do=add&aid=123") favoriteRequests += request
                wenkuApiResponse(inner)
            },
            credentialsResolver = fixtureCredentialsResolver(),
        )

        val publication = fixture.source.browse(
            BrowseOptionsV2(mapOf("option" to "rank:lastupdate")),
            page = 0,
        ).items.single()
        assertEquals("https://wenku8-relay.mewx.org/book/123", publication.remoteId)
        val details = fixture.source.details(publication.remoteId)
        assertEquals("Fixture Book", details.title)

        val unit = fixture.source.units(publication.remoteId, page = 0).items.single()
        assertEquals("Volume 1 / Fixture Chapter", unit.title)
        assertEquals("https://wenku8-relay.mewx.org/book/123/chapter/456", unit.remoteId)
        val content = fixture.source.content(publication.remoteId, unit.remoteId)
        val text = assertIs<UnitContentPayload.InlineTextPayload>(content.representations.single()).source.text
        assertTrue("Hello" in text && "world" in text)

        assertTrue(
            fixture.source.login(LoginCredentialsV2("username-ref", "password-ref")).loggedIn,
        )
        assertStoredLoginAndCookie(fixture, "wenku8-relay.mewx.org")
        fixture.source.favorite(publication.remoteId, favorite = true)
        assertEquals("wenku-session=abc", favoriteRequests.single().headers["Cookie"])
        assertEquals(
            listOf(
                "action=articlelist&sort=lastupdate&page=1",
                "action=book&do=bookinfo&aid=123&t=1",
                "action=book&do=intro&aid=123&t=1",
                "action=book&do=list&aid=123&t=1",
                "action=book&do=text&aid=123&cid=456&t=1",
                "action=login&username=alice&password=secret",
                "action=bookcase&do=add&aid=123",
            ),
            innerRequests,
        )
        fixture.manager.close()
    }

    private suspend fun prepare(
        packageId: String,
        resourceName: String,
        admission: ShuYueReviewedPluginAdmissionV2,
        approvals: InMemoryShuYueExecutionApprovalsV2,
    ): String {
        val profile = requireNotNull(ShuYueReviewedPluginCatalogV2.profiles.singleOrNull {
            it.identity.packageId == packageId
        })
        val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream("shuyue-plugin/$resourceName"))
            .use { it.readBytes() }
        val staged = admission.quarantine(
            ShuYueScriptCandidateV2(
                packageId = profile.identity.packageId,
                version = profile.identity.version,
                versionCode = profile.identity.versionCode,
                sourceIds = listOf(profile.sourceId),
                bytes = bytes,
                provenance = ShuYueScriptProvenanceV2.REVIEWED_REPOSITORY,
                reportedSha256 = profile.identity.sha256,
            ),
        )
        assertEquals(ShuYueReviewStatusV2.REVIEWED, staged.reviewStatus)
        approvals.trust(profile.identity)
        approvals.grant(profile.identity, profile.requiredPermissions)
        return staged.quarantineId
    }

    private suspend fun installedReviewedSource(
        packageId: String,
        resourceName: String,
        transport: PluginHttpTransport,
        credentialsResolver: LegacyLoginCredentialsResolverV2? = null,
    ): InstalledReviewedSourceFixture {
        val keyValues = InMemoryPluginKeyValueStore()
        val storage = KeyValuePluginStorage(keyValues)
        val network = PluginNetworkClient(transport = transport, storage = storage)
        val repositoryClient = ExtensionRepositoryClient(
            HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
        )
        val manager = PluginManager(
            repositoryClient = repositoryClient,
            packageStore = InMemoryPluginPackageStore(),
            verifier = PluginVerifier(KeyValuePluginTrustStore(keyValues)),
            runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            environment = ScriptPluginEnvironment(network, storage),
        )
        val approvals = InMemoryShuYueExecutionApprovalsV2()
        val admission = productionShuYueReviewedAdmissionV2(
            quarantineStore = InMemoryShuYueScriptQuarantineStoreV2(),
            trustStore = approvals,
            permissionStore = approvals,
            runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            environment = ScriptPluginEnvironment(network, storage),
            credentialsResolver = credentialsResolver,
        )
        val quarantineId = prepare(packageId, resourceName, admission, approvals)
        manager.installReviewedShuYueRuntimeV2(admission, quarantineId)
        val profile = requireNotNull(ShuYueReviewedPluginCatalogV2.profiles.singleOrNull {
            it.identity.packageId == packageId
        })
        val sourceKey = SourceKey(2, packageId, profile.sourceId)
        return InstalledReviewedSourceFixture(
            manager = manager,
            source = requireNotNull(manager.extensionSourceV2(sourceKey)),
            sourceKey = sourceKey,
            storage = storage,
            profile = profile,
        )
    }

    private fun fixtureCredentialsResolver(): LegacyLoginCredentialsResolverV2 =
        LegacyLoginCredentialsResolverV2 { references ->
            assertEquals("username-ref", references.usernameReference)
            assertEquals("password-ref", references.passwordReference)
            LegacyLoginCredentialsV2("alice", "secret")
        }

    private suspend fun assertStoredLoginAndCookie(
        fixture: InstalledReviewedSourceFixture,
        expectedDomain: String,
    ) {
        val executionScope = BuiltInShuYueExecutionScopesV2.resolve(fixture.profile.identity, fixture.sourceKey)
        assertEquals(PluginCredential("alice", "secret"), fixture.storage.getCredential(executionScope))
        val cookie = fixture.storage.getCookies(executionScope).single { it.name == "wenku-session" }
        assertEquals("abc", cookie.value)
        assertEquals(expectedDomain, cookie.domain)
        assertEquals("/", cookie.path)
        assertTrue(cookie.secure)
        assertTrue(cookie.httpOnly)
        assertTrue(cookie.hostOnly)
    }

    private fun wenkuHtmlResponse(request: PluginHttpRequest): PluginHttpResponse {
        val response = when (request.url) {
            "https://www.wenku8.net/modules/article/toplist.php?sort=lastupdate&page=1&charset=gbk" -> {
                require(request.method == "GET")
                "<a href=\"/book/123.htm\">Fixture Book</a>" to emptyMap()
            }
            "https://www.wenku8.net/modules/article/articleinfo.php?id=123&charset=gbk" -> {
                require(request.method == "GET")
                "<title>Fixture Book</title>" to emptyMap()
            }
            "https://www.wenku8.net/modules/article/reader.php?aid=123&charset=gbk" -> {
                require(request.method == "GET")
                "<td class=\"ccss\"><a href=\"/modules/article/reader.php?aid=123&cid=456\">" +
                    "Fixture Chapter</a></td>" to emptyMap()
            }
            "https://www.wenku8.net/novel/0/123/456.htm" -> {
                require(request.method == "GET")
                "<div id=\"content\">Hello<br>world</div><div id=\"footlink\"></div>" to emptyMap()
            }
            "https://www.wenku8.net/", "https://www.wenku8.net/login.php" -> {
                require(request.method == "GET")
                "" to emptyMap()
            }
            "https://www.wenku8.net/login.php?do=submit" -> {
                require(request.method == "POST")
                assertEquals(
                    "username=alice&password=secret&checkcode=&usecookie=315360000&action=login",
                    request.body.decodeToString(),
                )
                "登录成功" to mapOf(
                    "Set-Cookie" to listOf("wenku-session=abc; Path=/; Secure; HttpOnly"),
                )
            }
            "https://www.wenku8.net/modules/article/addbookcase.php?bid=123&charset=gbk" -> {
                require(request.method == "GET")
                "处理成功" to emptyMap()
            }
            else -> error("Unexpected Wenku8 HTML request: ${request.method} ${request.url}")
        }
        return PluginHttpResponse(status = 200, body = response.first.encodeToByteArray(), headers = response.second)
    }

    private fun decodeRelayRequest(request: PluginHttpRequest): String {
        require(request.method == "POST")
        require(request.url == "https://wenku8-relay.mewx.org/")
        val outer = request.body.decodeToString()
        require(
            Regex("""^&appver=1\.29-digital-bento-[0-9a-f]{8}&request=([^&]+)&timetoken=\d+$""")
                .matches(outer),
        )
        val encoded = outer.substringAfter("&request=").substringBefore("&timetoken=")
        return java.util.Base64.getDecoder().decode(encoded).decodeToString()
    }

    private fun wenkuApiResponse(inner: String): PluginHttpResponse {
        val body = when (inner) {
            "action=articlelist&sort=lastupdate&page=1" ->
                "<item aid=\"123\"><data name=\"Title\" value=\"Fixture Book\"/>" +
                    "<data name=\"Author\" value=\"Fixture Author\"/></item>"
            "action=book&do=bookinfo&aid=123&t=1" ->
                "<data name=\"Title\" value=\"Fixture Book\"/>" +
                    "<data name=\"Author\" value=\"Fixture Author\"/>"
            "action=book&do=intro&aid=123&t=1" -> "Fixture description"
            "action=book&do=list&aid=123&t=1" ->
                "<volume name=\"Volume 1\"><chapter cid=\"456\">Fixture Chapter</chapter></volume>"
            "action=book&do=text&aid=123&cid=456&t=1" -> "Hello\nworld"
            "action=login&username=alice&password=secret" -> "1"
            "action=bookcase&do=add&aid=123" -> "1"
            else -> error("Unexpected Wenku8 API request: $inner")
        }
        val headers = if (inner == "action=login&username=alice&password=secret") {
            mapOf("Set-Cookie" to listOf("wenku-session=abc; Path=/; Secure; HttpOnly"))
        } else {
            emptyMap()
        }
        return PluginHttpResponse(status = 200, body = body.encodeToByteArray(), headers = headers)
    }

    private fun fixtureResponse(request: PluginHttpRequest): PluginHttpResponse {
        val body = when {
            request.url.contains("/top/lastupdate") -> """
                <html><body><a href="/book/123.html">Fixture Book</a></body></html>
            """.trimIndent()
            request.url.endsWith("/book/123.html") -> """
                <html><head><title>Fixture Book</title></head><body><h1>Fixture Book</h1></body></html>
            """.trimIndent()
            request.url.endsWith("/book/123/") -> """
                <html><body><a href="/book/123/456.html">Fixture Chapter</a></body></html>
            """.trimIndent()
            request.url.endsWith("/book/123/456.html") -> """
                <html><body><div id="chaptercontent">Hello<br/>world</div></body></html>
            """.trimIndent()
            else -> error("Unexpected reviewed-script request: ${request.url}")
        }
        return PluginHttpResponse(
            status = 200,
            body = body.encodeToByteArray(),
            headers = mapOf("Set-Cookie" to listOf("reviewed-session=must-not-persist; Path=/")),
        )
    }

    private fun fixtureRepositoryResponse(
        request: ShuYueRepositoryRequest,
    ): ShuYueRepositoryResponse {
        val resourceName = if (request.url == REVIEWED_INDEX_URL) {
            "index.json"
        } else {
            request.url.substringAfterLast('/').substringBefore('?')
        }
        val body = requireNotNull(javaClass.classLoader.getResourceAsStream("shuyue-plugin/$resourceName")) {
            "Missing reviewed repository fixture $resourceName"
        }.use { it.readBytes() }
        require(body.size.toLong() <= request.maxBytes)
        return ShuYueRepositoryResponse(
            status = 200,
            body = body,
            finalUrl = request.url,
        )
    }

    private companion object {
        const val REVIEWED_ARTIFACT_ORIGIN: String = "https://raw.githubusercontent.com"
        const val REVIEWED_INDEX_URL: String =
            "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/index.json"
    }
}

private data class InstalledReviewedSourceFixture(
    val manager: PluginManager,
    val source: HostExtensionSourceV2,
    val sourceKey: SourceKey,
    val storage: KeyValuePluginStorage,
    val profile: ShuYueReviewedPluginProfileV2,
)
