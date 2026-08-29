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
        assertEquals("image/*", requests[1].headers["Accept"])
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
    fun httpsRedirectCannotDowngradeToPlainHttp() = runTest {
        val client = client(KeyValuePluginStorage(InMemoryPluginKeyValueStore())) {
            PluginHttpResponse(302, ByteArray(0), mapOf("Location" to listOf("http://site.example/plain")))
        }

        val error = assertFailsWith<IllegalStateException> {
            client.execute(1, PluginHttpRequest("GET", "https://site.example/secure"))
        }

        assertTrue(error.message.orEmpty().contains("HTTPS to HTTP"))
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

    private fun client(
        storage: PluginStorage,
        now: Long = 1_000L,
        transport: suspend (PluginHttpRequest) -> PluginHttpResponse,
    ): PluginNetworkClient = PluginNetworkClient(
        transport = PluginHttpTransport(transport),
        storage = storage,
        requestBuilder = PluginRequestBuilder(storage, nowEpochMillis = { now }),
        requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        nowEpochMillis = { now },
    )
}
