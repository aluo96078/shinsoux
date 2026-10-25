package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginNetworkSecurityTest {
    @Test
    fun reviewedInProcessScopeStillUsesResolvedTransport() = runTest {
        var ordinaryCalls = 0
        var resolvedCalls = 0
        var received: PluginHostResolution? = null
        val transport = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse {
                ordinaryCalls++
                return PluginHttpResponse(200, ByteArray(0))
            }

            override suspend fun executeResolved(
                request: PluginHttpRequest,
                resolution: PluginHostResolution,
            ): PluginHttpResponse {
                resolvedCalls++
                received = resolution
                return PluginHttpResponse(200, ByteArray(0))
            }
        }
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        PluginNetworkClient(
            transport = transport,
            storage = storage,
            policy = PluginNetworkPolicy(requestOrigins = setOf("https://api.example")),
            hostResolver = PluginHostResolver { listOf("93.184.216.34") },
        ).forReviewedInProcessArtifact().execute(
            1,
            PluginHttpRequest("GET", "https://api.example/item"),
        )

        assertEquals(0, ordinaryCalls)
        assertEquals(1, resolvedCalls)
        assertEquals("api.example", received?.host)
        assertEquals(listOf("93.184.216.34"), received?.addresses)
    }

    @Test
    fun pluginClientCloneCanDisableKtOrAutomaticRedirects() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            if (request.url.encodedPath == "/start") {
                respond(
                    content = "redirect",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://redirect.example/final"),
                )
            } else {
                respond("final", HttpStatusCode.OK)
            }
        }
        val shared = HttpClient(engine) { followRedirects = true }
        val noRedirects = shared.config { followRedirects = false }
        try {
            val response = KtorPluginHttpTransport(noRedirects).execute(
                PluginHttpRequest("GET", "https://redirect.example/start"),
            )

            assertEquals(302, response.status)
            assertEquals(1, requests)
        } finally {
            noRedirects.close()
            shared.close()
        }
    }

    @Test
    fun crossOriginRedirectRebuildsCookiesAndDropsSensitiveHeaders() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        storage.setCookie(7, PluginCookie("sid", "origin-only", "origin.example"))
        val requests = mutableListOf<PluginHttpRequest>()
        val client = client(storage) { request ->
            requests += request
            if (request.url.startsWith("https://origin.example")) {
                PluginHttpResponse(302, ByteArray(0), mapOf("Location" to listOf("https://evil.example/final")))
            } else {
                PluginHttpResponse(200, "ok".encodeToByteArray())
            }
        }

        val response = client.execute(
            sourceId = 7,
            request = PluginHttpRequest("GET", "https://origin.example/start"),
            sourceHeaders = mapOf(
                "Authorization" to "Bearer source-secret",
                "X-Api-Key" to "source-secret",
                "Accept" to "image/*",
            ),
        )

        assertEquals("ok", response.bodyText())
        assertEquals("sid=origin-only", requests[0].headers["Cookie"])
        assertEquals("Bearer source-secret", requests[0].headers["Authorization"])
        assertFalse(requests[1].headers.keys.any { it.equals("Cookie", ignoreCase = true) })
        assertFalse(requests[1].headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertFalse(requests[1].headers.keys.any { it.equals("X-Api-Key", ignoreCase = true) })
        // Cross-origin redirects intentionally drop all caller headers, including Referer and
        // otherwise harmless metadata, to avoid leaking source context to an untrusted origin.
        assertFalse(requests[1].headers.containsKey("Accept"))
    }

    @Test
    fun intermediateSetCookieIsAvailableOnTheNextSameOriginHop() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val requests = mutableListOf<PluginHttpRequest>()
        val client = client(storage) { request ->
            requests += request
            if (request.url.endsWith("/start")) {
                PluginHttpResponse(
                    302,
                    ByteArray(0),
                    mapOf(
                        "Location" to listOf("/final"),
                        "Set-Cookie" to listOf("session=redirected; Path=/; HttpOnly"),
                    ),
                )
            } else {
                PluginHttpResponse(200, ByteArray(0))
            }
        }

        client.execute(3, PluginHttpRequest("GET", "https://same.example/start"))

        assertEquals(2, requests.size)
        assertEquals("session=redirected", requests[1].headers["Cookie"])
        assertEquals("redirected", storage.getCookies(3).single().value)
    }

    @Test
    fun requestOnlySubdomainCannotPlantCookieForCredentialParent() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val client = PluginNetworkClient(
            transport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    error("resolved transport expected")

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse = PluginHttpResponse(
                    200,
                    ByteArray(0),
                    mapOf("Set-Cookie" to listOf("sid=attacker; Domain=api.example; Path=/; Secure")),
                )
            },
            storage = storage,
            policy = PluginNetworkPolicy(
                requestOrigins = setOf("https://evil.api.example", "https://api.example"),
                credentialOrigins = setOf("https://api.example"),
            ),
            hostResolver = PluginHostResolver { listOf("93.184.216.34") },
        )

        client.execute(7, PluginHttpRequest("GET", "https://evil.api.example/plant"))

        assertTrue(storage.getCookies(7).isEmpty())
    }

    @Test
    fun httpsRedirectCannotDowngradeToPlainHttp() = runTest {
        val client = client(KeyValuePluginStorage(InMemoryPluginKeyValueStore())) {
            PluginHttpResponse(302, ByteArray(0), mapOf("Location" to listOf("http://site.example/plain")))
        }

        val error = assertFailsWith<IllegalArgumentException> {
            client.execute(1, PluginHttpRequest("GET", "https://site.example/secure"))
        }

        assertTrue(error.message.orEmpty().contains("HTTPS"))
    }

    @Test
    fun expiresDeletionTargetsOnlyTheMatchingCookiePath() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        storage.setCookie(9, PluginCookie("sid", "root", "accounts.example", path = "/"))
        storage.setCookie(9, PluginCookie("sid", "account", "accounts.example", path = "/account"))
        val client = client(storage, now = 1_700_000_000_000L) {
            PluginHttpResponse(
                200,
                ByteArray(0),
                mapOf(
                    "Set-Cookie" to listOf(
                        "sid=; Path=/account; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
                    ),
                ),
            )
        }

        client.execute(9, PluginHttpRequest("GET", "https://accounts.example/account/logout"))

        val remaining = storage.getCookies(9).single()
        assertEquals("root", remaining.value)
        assertEquals("/", remaining.path)
    }

    @Test
    fun maxAgeOverridesExpiresAndSaturatesWithoutOverflow() = runTest {
        val now = 1_700_000_000_000L
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        storage.setCookie(4, PluginCookie("logout", "old", "cookie.example"))
        var responseHeaders = mapOf(
            "Set-Cookie" to listOf(
                "logout=gone; Expires=Wed, 01 Jan 2099 00:00:00 GMT; Max-Age=0",
                "long_lived=yes; Max-Age=999999999999999999999999999999999999",
            ),
        )
        val client = client(storage, now) { PluginHttpResponse(200, ByteArray(0), responseHeaders) }

        client.execute(4, PluginHttpRequest("GET", "https://cookie.example/path"))

        assertFalse(storage.getCookies(4).any { it.name == "logout" })
        assertEquals(Long.MAX_VALUE, assertNotNull(storage.getCookies(4).single { it.name == "long_lived" }.expiresAtEpochMillis))
    }

    @Test
    fun invalidOrPublicSuffixDomainsAreRejectedInsteadOfBecomingHostOnly() = runTest {
        suspend fun cookiesAfter(host: String, domain: String): List<PluginCookie> {
            val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
            val client = client(storage) {
                PluginHttpResponse(200, ByteArray(0), mapOf("Set-Cookie" to listOf("sid=x; Domain=$domain")))
            }
            client.execute(1, PluginHttpRequest("GET", "https://$host/path"))
            return storage.getCookies(1)
        }

        assertTrue(cookiesAfter("shop.example.com", "com").isEmpty())
        assertTrue(cookiesAfter("shop.co.uk", "co.uk").isEmpty())
        assertTrue(cookiesAfter("shop.example.com", "other.example").isEmpty())
        assertTrue(cookiesAfter("shop.example.com", "example.com.").isEmpty())
    }

    @Test
    fun longerCookiePathsAreSentBeforeShorterPaths() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        storage.setCookie(2, PluginCookie("sid", "root", "paths.example", path = "/"))
        storage.setCookie(2, PluginCookie("sid", "account", "paths.example", path = "/account"))
        var captured: PluginHttpRequest? = null
        val client = client(storage) { request ->
            captured = request
            PluginHttpResponse(200, ByteArray(0))
        }

        client.execute(2, PluginHttpRequest("GET", "https://paths.example/account/profile"))

        assertEquals("sid=account; sid=root", assertNotNull(captured).headers["Cookie"])
    }

    @Test
    fun explicitCookieNameOverridesThePersistedJarWithoutDuplication() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        storage.setCookie(12, PluginCookie("night", "stored", "cookie.example"))
        storage.setCookie(12, PluginCookie("cf_clearance", "clearance", "cookie.example"))

        val built = PluginRequestBuilder(storage).build(
            sourceId = 12,
            request = PluginHttpRequest(
                method = "GET",
                url = "https://cookie.example/reader",
                headers = mapOf("Cookie" to "night=1"),
            ),
        )

        assertEquals("night=1; cf_clearance=clearance", built.transportRequest.headers["Cookie"])
    }

    @Test
    fun requestBuilderNeverForwardsUriFragmentsToTransportOrProxy() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        var routedTarget: String? = null
        val builder = PluginRequestBuilder(
            storage = storage,
            proxyResolver = PluginProxyResolver { _, targetUrl ->
                routedTarget = targetUrl
                PluginProxyRoute("https://proxy.example/fetch")
            },
        )

        val built = builder.build(
            sourceId = 5,
            request = PluginHttpRequest(
                "GET",
                "https://images.example/page.jpg?token=one#Referer=https%3A%2F%2Fsite.example%2F",
            ),
        )

        assertEquals("https://images.example/page.jpg?token=one", built.originalUrl.toString())
        assertEquals("https://images.example/page.jpg?token=one", routedTarget)
        assertEquals("https://proxy.example/fetch", built.transportRequest.url)
    }

    @Test
    fun oversizedResponseHeadersAreRejectedBeforePluginProcessing() = runTest {
        val client = client(KeyValuePluginStorage(InMemoryPluginKeyValueStore())) {
            PluginHttpResponse(
                200,
                ByteArray(0),
                mapOf("X-Attacker" to listOf("x".repeat(PLUGIN_NETWORK_MAX_RESPONSE_HEADER_VALUE_BYTES + 1))),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            client.execute(1, PluginHttpRequest("GET", "https://site.example/resource"))
        }
    }

    @Test
    fun responseHeaderAndCookieCountsAreBounded() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        var headers = (0..PLUGIN_NETWORK_MAX_RESPONSE_HEADER_FIELDS).associate { index ->
            "X-Test-$index" to listOf("value")
        }
        val client = client(storage) { PluginHttpResponse(200, ByteArray(0), headers) }

        assertFailsWith<IllegalArgumentException> {
            client.execute(1, PluginHttpRequest("GET", "https://site.example/resource"))
        }

        headers = mapOf(
            "Set-Cookie" to (0..PLUGIN_NETWORK_MAX_SET_COOKIE_HEADERS).map { index ->
                "cookie$index=value; Path=/"
            },
        )
        assertFailsWith<IllegalArgumentException> {
            client.execute(1, PluginHttpRequest("GET", "https://site.example/resource"))
        }
        assertTrue(storage.getCookies(1).isEmpty())
    }

    @Test
    fun contentLengthMustBeUniqueAndCanonical() {
        assertEquals(
            17L,
            mapOf("content-length" to listOf("17")).pluginDeclaredContentLength(),
        )
        listOf(
            listOf("17", "18"),
            listOf(""),
            listOf("+17"),
            listOf("-1"),
            listOf("17, 17"),
            listOf("18446744073709551616"),
        ).forEach { values ->
            assertFailsWith<IllegalArgumentException>(values.toString()) {
                mapOf("Content-Length" to values).pluginDeclaredContentLength()
            }
        }
    }

    @Test
    fun contentEncodingMustBeUniqueAndSupported() {
        assertEquals("gzip", mapOf("Content-Encoding" to listOf(" GZip ")).pluginContentEncoding())
        listOf(
            listOf("gzip", "identity"),
            listOf("gzip, br"),
            listOf("attacker-defined"),
        ).forEach { values ->
            assertFailsWith<IllegalArgumentException>(values.toString()) {
                mapOf("Content-Encoding" to values).pluginContentEncoding()
            }
        }
    }

    @Test
    fun ambiguousOrOversizedRedirectLocationIsRejected() = runTest {
        var headers = mapOf("Location" to listOf("/one", "/two"))
        val client = client(KeyValuePluginStorage(InMemoryPluginKeyValueStore())) {
            PluginHttpResponse(302, ByteArray(0), headers)
        }

        assertFailsWith<IllegalArgumentException> {
            client.execute(1, PluginHttpRequest("GET", "https://site.example/start"))
        }

        headers = mapOf(
            "Location" to listOf("/" + "a".repeat(PLUGIN_NETWORK_MAX_REDIRECT_LOCATION_BYTES)),
        )
        assertFailsWith<IllegalArgumentException> {
            client.execute(1, PluginHttpRequest("GET", "https://site.example/start"))
        }
    }

    @Test
    fun setCookieAttributeFanoutIsRejectedBeforeSplitting() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val excessiveAttributes = buildString {
            append("sid=value")
            repeat(33) { append("; ignored").append(it) }
        }
        val client = client(storage) {
            PluginHttpResponse(200, ByteArray(0), mapOf("Set-Cookie" to listOf(excessiveAttributes)))
        }

        client.execute(1, PluginHttpRequest("GET", "https://site.example/resource"))

        assertTrue(storage.getCookies(1).isEmpty())
    }

    @Test
    fun policyResponseBudgetReachesEveryRedirectHop() = runTest {
        val seenLimits = mutableListOf<Int>()
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val transport = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                error("resolved transport expected")

            override suspend fun executeResolved(
                request: PluginHttpRequest,
                resolution: PluginHostResolution,
            ): PluginHttpResponse {
                seenLimits += request.maxResponseBytes
                return if (seenLimits.size == 1) {
                    PluginHttpResponse(302, ByteArray(0), mapOf("Location" to listOf("/final")))
                } else {
                    PluginHttpResponse(200, "ok".encodeToByteArray())
                }
            }
        }
        val client = PluginNetworkClient(
            transport = transport,
            storage = storage,
            policy = PluginNetworkPolicy(
                requestOrigins = setOf("https://site.example"),
                maxResponseBytes = 1_024,
            ),
            hostResolver = PluginHostResolver { listOf("93.184.216.34") },
        )

        assertEquals(
            "ok",
            client.execute(
                1,
                PluginHttpRequest(
                    "GET",
                    "https://site.example/start",
                    maxResponseBytes = 127,
                ),
            ).bodyText(),
        )
        assertEquals(listOf(127, 127), seenLimits)
    }

    @Test
    fun policyNarrowsRequestBudgetBeforeCustomTransport() = runTest {
        var seenLimit = 0
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val client = PluginNetworkClient(
            transport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    error("resolved transport expected")

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse {
                    seenLimit = request.maxResponseBytes
                    return PluginHttpResponse(200, ByteArray(request.maxResponseBytes + 1))
                }
            },
            storage = storage,
            policy = PluginNetworkPolicy(
                requestOrigins = setOf("https://site.example"),
                maxResponseBytes = 64,
            ),
            hostResolver = PluginHostResolver { listOf("93.184.216.34") },
        )

        assertFailsWith<IllegalArgumentException> {
            client.execute(1, PluginHttpRequest("GET", "https://site.example/resource"))
        }
        assertEquals(64, seenLimit)
    }

    private fun client(
        storage: PluginStorage,
        now: Long = 1_000L,
        transport: suspend (PluginHttpRequest) -> PluginHttpResponse,
    ): PluginNetworkClient = PluginNetworkClient(
        transport = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = transport(request)

            override suspend fun executeResolved(
                request: PluginHttpRequest,
                resolution: PluginHostResolution,
            ): PluginHttpResponse = transport(request)
        },
        storage = storage,
        requestBuilder = PluginRequestBuilder(storage, nowEpochMillis = { now }),
        requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        nowEpochMillis = { now },
        policy = PluginNetworkPolicy(
            requestOrigins = setOf(
                "https://origin.example",
                "https://evil.example",
                "https://same.example",
                "https://site.example",
                "https://accounts.example",
                "https://cookie.example",
                "https://paths.example",
                "https://shop.example.com",
                "https://shop.co.uk",
            ),
            credentialOrigins = setOf(
                "https://origin.example",
                "https://same.example",
                "https://site.example",
                "https://accounts.example",
                "https://cookie.example",
                "https://paths.example",
            ),
        ),
        hostResolver = PluginHostResolver { listOf("93.184.216.34") },
    )
}
