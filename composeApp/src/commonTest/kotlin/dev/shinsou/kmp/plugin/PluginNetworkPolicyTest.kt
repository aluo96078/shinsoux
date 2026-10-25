package dev.shinsou.kmp.plugin

import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginNetworkPolicyTest {
    @Test
    fun imageBudgetDoesNotRaiseScriptBudget() = runTest {
        val requests = mutableListOf<PluginHttpRequest>()
        val policy = PluginNetworkPolicy(
            requestOrigins = setOf("https://api.example"),
            contentOrigins = setOf("https://images.example"),
            maxResponseBytes = 4 * 1024 * 1024,
            maxContentResponseBytes = 16 * 1024 * 1024,
        )
        val network = PluginNetworkClient(
            resolvedTransport { request ->
                requests += request
                PluginHttpResponse(200, ByteArray(5 * 1024 * 1024))
            }, KeyValuePluginStorage(InMemoryPluginKeyValueStore()),
            policy = policy, hostResolver = publicResolver,
        )
        assertEquals(5 * 1024 * 1024, network.scopedToContentPolicy(policy).get(1, "https://images.example/a").body.size)
        assertEquals(16 * 1024 * 1024, requests.last().maxResponseBytes)
        assertFailsWith<IllegalArgumentException> { network.get(1, "https://api.example/a", emptyMap()) }
        assertEquals(4 * 1024 * 1024, requests.last().maxResponseBytes)
        assertFailsWith<IllegalArgumentException> { policy.copy(maxContentResponseBytes = 16 * 1024 * 1024 + 1) }
    }

    @Test
    fun manhuarenReaderCdnIsContentOnly() = runTest {
        val requests = mutableListOf<PluginHttpRequest>()
        val content = PluginNetworkClient(
            transport = resolvedTransport { request ->
                requests += request
                PluginHttpResponse(200, byteArrayOf(1))
            },
            storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore()),
            hostResolver = publicResolver,
        ).scopedToContentPolicy(
            PluginNetworkPolicy(
                requestOrigins = setOf("https://www.manhuaren.com"),
                credentialOrigins = setOf("https://www.manhuaren.com"),
                contentOrigins = setOf("https://mhfm1tw.cdndm5.com"),
            ),
        )

        content.get(1, "https://mhfm1tw.cdndm5.com/page.jpg")
        assertEquals("https://mhfm1tw.cdndm5.com/page.jpg", requests.single().url)
        assertFailsWith<IllegalArgumentException> {
            content.get(1, "https://www.manhuaren.com/private")
        }
    }

    @Test
    fun manhuarenCdnPolicyDoesNotGrantCredentialOrigin() {
        val policy = PluginNetworkPolicy(
            requestOrigins = setOf("https://www.manhuaren.com"),
            credentialOrigins = setOf("https://www.manhuaren.com"),
            contentOrigins = setOf("https://mhfm1tw.cdndm5.com"),
        )
        assertTrue(policy.maySendCredentials(Url("https://www.manhuaren.com/private")))
        assertFalse(policy.maySendCredentials(Url("https://mhfm1tw.cdndm5.com/page.jpg")))
    }

    @Test
    fun contentPlaneUsesOnlyContentOriginsAndNeverCarriesCredentials() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore()).also {
            it.setCookie(7, PluginCookie("session", "secret", "images.example", secure = true))
            it.setWebChallengeUserAgent(7, "secret-browser-agent")
            it.setPreference(7, ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE, "on")
        }
        val requests = mutableListOf<PluginHttpRequest>()
        val bridge = PluginNetworkClient(
            transport = resolvedTransport { request ->
                requests += request
                PluginHttpResponse(200, byteArrayOf(1), mapOf("Set-Cookie" to listOf("stolen=1")))
            },
            storage = storage,
            requestBuilder = PluginRequestBuilder(
                storage = storage,
                userAgents = PluginUserAgentProvider { "ordinary-agent" },
                proxyResolver = ConfiguredPluginProxyResolver(storage) {
                    PluginNetworkConfiguration(
                        proxyWorkerUrl = "https://proxy.example",
                        proxyApiKey = "proxy-secret",
                    )
                },
            ),
            hostResolver = publicResolver,
        )
        val policy = PluginNetworkPolicy(
            requestOrigins = setOf("https://api.example"),
            credentialOrigins = setOf("https://api.example"),
            contentOrigins = setOf("https://images.example"),
        )
        val content = bridge.scopedToContentPolicy(policy)

        content.get(
            7,
            "https://images.example/page.jpg",
            headers = mapOf(
                "Referer" to "https://api.example/chapter",
                "X-Image-Ticket" to "komiic-ticket",
                "X-Source" to "drop-me",
            ),
        )

        val sent = requests.single()
        assertEquals("https://images.example/page.jpg", sent.url)
        assertEquals("https://api.example/chapter", sent.headers["Referer"])
        assertEquals("komiic-ticket", sent.headers["X-Image-Ticket"])
        assertEquals("ordinary-agent", sent.headers["User-Agent"])
        assertFalse(sent.headers.keys.any { it.equals("Cookie", true) })
        assertFalse(sent.headers.keys.any { it.equals("X-Proxy-Key", true) })
        assertFalse(sent.headers.keys.any { it.equals("X-Source", true) })
        assertTrue(storage.getCookies(7).none { it.name == "stolen" })
        assertFailsWith<IllegalArgumentException> {
            content.get(7, "https://api.example/private")
        }
        assertFailsWith<IllegalArgumentException> {
            content.execute(7, PluginHttpRequest("POST", "https://images.example/upload"))
        }
        assertFailsWith<IllegalArgumentException> {
            content.get(7, "https://images.example/page.jpg", mapOf("Authorization" to "Bearer secret"))
        }
    }

    @Test
    fun contentPlaneRevalidatesRedirectsAndDnsAnswers() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        var requests = 0
        val content = PluginNetworkClient(
            transport = resolvedTransport {
                requests++
                PluginHttpResponse(302, ByteArray(0), mapOf("Location" to listOf("https://cdn.example/page.jpg")))
            },
            storage = storage,
        ).scopedToContentPolicy(
            PluginNetworkPolicy(
                requestOrigins = setOf("https://api.example"),
                contentOrigins = setOf("https://images.example"),
                resolver = PluginHostResolver { host ->
                    if (host == "images.example") listOf("93.184.216.34") else listOf("169.254.169.254")
                },
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            content.get(9, "https://images.example/page.jpg")
        }
        assertEquals(1, requests, "the redirected private/undeclared destination must never be fetched")

        val private = PluginNetworkClient(
            transport = resolvedTransport { error("private destination must never reach transport") },
            storage = storage,
        ).scopedToContentPolicy(
            PluginNetworkPolicy(
                contentOrigins = setOf("https://metadata.example"),
                resolver = PluginHostResolver { listOf("169.254.169.254") },
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            private.get(9, "https://metadata.example/latest/meta-data")
        }
    }

    @Test
    fun contentPlaneDoesNotForwardImageTicketAcrossOriginRedirect() = runTest {
        val requests = mutableListOf<PluginHttpRequest>()
        val content = PluginNetworkClient(
            transport = resolvedTransport { request ->
                requests += request
                if (request.url.startsWith("https://images.example")) {
                    PluginHttpResponse(302, ByteArray(0), mapOf("Location" to listOf("https://cdn.example/page.jpg")))
                } else {
                    PluginHttpResponse(200, byteArrayOf(1))
                }
            },
            storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore()),
            hostResolver = publicResolver,
        ).scopedToContentPolicy(
            PluginNetworkPolicy(
                contentOrigins = setOf("https://images.example", "https://cdn.example"),
                resolver = publicResolver,
            ),
        )

        content.get(1, "https://images.example/page.jpg", mapOf("X-Image-Ticket" to "komiic-ticket"))

        assertEquals("komiic-ticket", requests.first().headers["X-Image-Ticket"])
        assertFalse(requests[1].headers.keys.any { it.equals("X-Image-Ticket", ignoreCase = true) })
    }

    @Test
    fun reviewedDynamicContentSuffixRequiresContentScopeAndResolvedTransport() = runTest {
        val policy = PluginNetworkPolicy(
            requestOrigins = setOf("https://ehgt.org"),
            contentOrigins = setOf("https://ehgt.org"),
            contentHostSuffixes = setOf(REVIEWED_DYNAMIC_CONTENT_SUFFIX),
            resolver = publicResolver,
        )
        policy.validate(Url("https://ehgt.org/cover.jpg"))
        assertFailsWith<IllegalArgumentException> {
            policy.validate(Url("https://abc.hath.network/hash/page.jpg"))
        }

        val requests = mutableListOf<PluginHttpRequest>()
        val content = PluginNetworkClient(
            transport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    error("content scope must use resolved transport")

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse {
                    requests += request
                    return PluginHttpResponse(200, byteArrayOf(1))
                }
            },
            storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore()),
        ).scopedToContentPolicy(policy)

        content.get(7, "https://abc.hath.network/hash/page.jpg")
        assertEquals(
            listOf("https://abc.hath.network/hash/page.jpg"),
            requests.map(PluginHttpRequest::url),
        )

        listOf(
            "https://hath.network/page.jpg",
            "https://evilhath.network/page.jpg",
            "https://abc.hath.network.evil/page.jpg",
            "http://abc.hath.network/page.jpg",
            "https://user:pass@abc.hath.network/page.jpg",
            "https://abc.hath.network:80/page.jpg",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>("Expected content URL to be rejected: $url") {
                content.get(7, url)
            }
        }
        listOf(
            "https://abc.hath.network:5891/page.jpg",
            "https://abc.hath.network:8443/page.jpg",
            "https://abc.hath.network:54200/page.jpg",
        ).forEach { url ->
            content.get(7, url)
        }
        assertEquals(4, requests.size, "Rejected content URLs must not reach transport")
    }

    @Test
    fun reviewedMangaDexAtHomeSuffixIsContentOnlyAndStandardHttpsOnly() = runTest {
        val requests = mutableListOf<PluginHttpRequest>()
        val policy = PluginNetworkPolicy(
            requestOrigins = setOf("https://mangadex.org", "https://api.mangadex.org"),
            credentialOrigins = setOf("https://api.mangadex.org"),
            contentOrigins = setOf("https://uploads.mangadex.org"),
            contentHostSuffixes = setOf(REVIEWED_MANGADEX_CONTENT_SUFFIX),
            resolver = PluginHostResolver { listOf("93.184.216.34") },
        )
        val content = PluginNetworkClient(
            transport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    error("MangaDex dynamic content must use resolved transport")

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse {
                    requests += request
                    return PluginHttpResponse(200, byteArrayOf(1))
                }
            },
            storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore()),
        ).scopedToContentPolicy(policy)

        val atHomeUrl = "https://cmdxd98sb0x3yprd.mangadex.network/data/hash/1.png"
        content.get(1, atHomeUrl)
        assertEquals(listOf(atHomeUrl), requests.map(PluginHttpRequest::url))
        assertFalse(policy.maySendCredentials(Url(atHomeUrl)))

        listOf(
            "https://mangadex.network/data/hash/1.png",
            "https://cmdxd98sb0x3yprd.mangadex.network.evil/data/hash/1.png",
            "https://cmdxd98sb0x3yprd.mangadex.network:8443/data/hash/1.png",
            "http://cmdxd98sb0x3yprd.mangadex.network/data/hash/1.png",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>("Expected MangaDex content URL to be rejected: $url") {
                content.get(1, url)
            }
        }
    }

    @Test
    fun policyRequiresExactHttpsRequestOrigin() = runTest {
        val policy = PluginNetworkPolicy(
            requestOrigins = setOf("https://api.example"),
            resolver = publicResolver,
        )
        policy.validate(Url("https://api.example/v1"))
        assertFailsWith<IllegalArgumentException> { policy.validate(Url("https://api.example.evil/v1")) }
        assertFailsWith<IllegalArgumentException> { policy.validate(Url("http://api.example/v1")) }
        assertFailsWith<IllegalArgumentException> { policy.validate(Url("https://other.example/v1")) }
    }

    @Test
    fun addressCorpusRejectsLocalSpecialAndAlternateForms() {
        val blocked = listOf(
            "localhost", "127.0.0.1", "127.1", "2130706433", "0x7f000001", "0177.0.0.1",
            "10.2.3.4", "172.20.1.1", "192.168.1.2", "169.254.169.254", "224.0.0.1",
            "0.0.0.0", "::1", "::ffff:127.0.0.1", "::ffff:192.168.1.1", "fc00::1",
            "fe80::1", "ff02::1", "2001:db8::1",
        )
        blocked.forEach { assertTrue(isBlockedPluginAddress(it), "Expected blocked: $it") }
        assertFalse(isBlockedPluginAddress("8.8.8.8"))
        assertFalse(isBlockedPluginAddress("2001:4860:4860::8888"))
    }

    @Test
    fun resolverAnswersRequireCanonicalIpLiteralsAndBlockReservedRanges() {
        listOf("0x08080808", "134744072", "010.0.0.1", "8.8.8", "8.8.8.08").forEach {
            assertFalse(isPluginIpLiteral(it), "Non-canonical answer must be rejected: $it")
        }
        listOf("100.64.0.1", "100.127.255.254", "198.19.0.1", "240.0.0.1").forEach {
            assertTrue(isBlockedPluginAddress(it), "Expected reserved address to be blocked: $it")
        }
        listOf("::192.168.1.1", "64:ff9b::c0a8:0101", "64:ff9b:1::c0a8:0101").forEach {
            assertTrue(isBlockedPluginAddress(it), "Expected translated private address to be blocked: $it")
        }
    }

    @Test
    fun everyDnsAnswerMustBePublic() = runTest {
        val policy = PluginNetworkPolicy(
            requestOrigins = setOf("https://rebinding.example"),
            resolver = PluginHostResolver { listOf("93.184.216.34", "127.0.0.1") },
        )
        assertFailsWith<IllegalArgumentException> {
            policy.validate(Url("https://rebinding.example/resource"))
        }
    }

    @Test
    fun credentialOriginsAreSeparateAndNeverExpandRequestOrigins() = runTest {
        val policy = PluginNetworkPolicy(
            requestOrigins = setOf("https://api.example"),
            credentialOrigins = setOf("https://api.example"),
            contentOrigins = setOf("https://images.example"),
        )
        assertTrue(policy.maySendCredentials(Url("https://api.example/a"), Url("https://api.example/a")))
        assertFalse(policy.maySendCredentials(Url("https://images.example/a"), Url("https://api.example/a")))
        assertFailsWith<IllegalArgumentException> { policy.validate(Url("https://images.example/a")) }
    }

    @Test
    fun legacySourceUsesOnlyItsHttpsBaseOriginForCompatibility() {
        val source = SourceIndexEntry(
            name = "Legacy",
            lang = "en",
            id = 1L,
            baseUrl = "https://source.example/catalog",
        )

        assertEquals(setOf("https://source.example"), source.declaredRequestOrigins())
        assertEquals(setOf("https://source.example"), source.declaredContentOrigins())
        assertEquals(setOf("https://source.example"), source.networkPolicy().credentialOrigins)
    }

    @Test
    fun v2EmptyOriginDeclarationsDenyInsteadOfFallingBackToBaseUrl() = runTest {
        val source = SourceIndexEntry(
            name = "V2 deny",
            lang = "en",
            id = 2L,
            baseUrl = "https://source.example",
            requestOrigins = emptySet(),
            credentialOrigins = emptySet(),
            contentOrigins = emptySet(),
            originPolicyVersion = 2,
        )
        val policy = source.networkPolicy().copy(resolver = publicResolver)

        assertTrue(source.declaredRequestOrigins().isEmpty())
        assertTrue(source.declaredContentOrigins().isEmpty())
        assertTrue(policy.credentialOrigins.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            policy.validate(Url("https://source.example/catalog"))
        }
        assertFalse(policy.maySendCredentials(Url("https://source.example/catalog")))
        assertFailsWith<IllegalArgumentException> {
            PluginNetworkPolicy(requestOrigins = policy.contentOrigins).validate(
                Url("https://source.example/image.jpg"),
            )
        }
    }

    @Test
    fun emptyCredentialOriginsNeverInheritRequestOrigins() {
        val policy = PluginNetworkPolicy(
            requestOrigins = setOf("https://api.example"),
            credentialOrigins = emptySet(),
        )

        assertFalse(
            policy.maySendCredentials(
                Url("https://api.example/private"),
                initialUrl = Url("https://api.example/private"),
            ),
        )
    }

    @Test
    fun undeclaredActiveRuntimeOriginIsDeniedEvenWhenContentOriginIsAllowed() = runTest {
        val source = SourceIndexEntry(
            name = "Content only",
            lang = "en",
            id = 3L,
            baseUrl = "https://source.example",
            requestOrigins = setOf("https://api.example"),
            credentialOrigins = emptySet(),
            contentOrigins = setOf("https://images.example"),
            originPolicyVersion = 2,
        )

        val policy = source.networkPolicy().copy(resolver = publicResolver)
        policy.validate(Url("https://api.example/catalog"))
        assertFailsWith<IllegalArgumentException> {
            policy.validate(Url("https://images.example/page.jpg"))
        }
        assertFalse(policy.maySendCredentials(Url("https://api.example/catalog")))
    }

    @Test
    fun networkWithoutCookieStorageDoesNotPersistSetCookie() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val filtered = PermissionFilteredPluginStorage(
            storage,
            setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
        )
        val transport = resolvedTransport {
            PluginHttpResponse(200, "ok".encodeToByteArray(), mapOf("Set-Cookie" to listOf("sid=secret; Path=/")))
        }
        val network = PluginNetworkClient(transport, filtered).scopedToPolicy(
            PluginNetworkPolicy(
                requestOrigins = setOf("https://example.test"),
                resolver = publicResolver,
            ),
        )
        assertTrue(network.get(7L, "https://example.test/", emptyMap()).bodyText() == "ok")
        assertTrue(filtered.getCookies(7L).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            filtered.setCookie(7L, PluginCookie("sid", "secret", "example.test"))
        }
    }

    private val publicResolver: PluginHostResolver = PluginHostResolver { listOf("93.184.216.34") }

    private fun resolvedTransport(
        block: suspend (PluginHttpRequest) -> PluginHttpResponse,
    ): PluginHttpTransport = object : PluginHttpTransport {
        override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = block(request)

        override suspend fun executeResolved(
            request: PluginHttpRequest,
            resolution: PluginHostResolution,
        ): PluginHttpResponse = block(request)
    }
}
