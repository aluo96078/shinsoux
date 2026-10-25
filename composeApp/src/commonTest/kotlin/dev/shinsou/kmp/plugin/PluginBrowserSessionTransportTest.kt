package dev.shinsou.kmp.plugin

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginBrowserSessionTransportTest {
    @Test
    fun productionBrowserSessionRequiresResolvedTransportAndPassesVettedAddresses() = runTest {
        var ordinaryCalls = 0
        var resolvedCalls = 0
        val transport = object : PluginBrowserSessionTransport {
            override suspend fun execute(
                sourceId: Long,
                sourceOrigin: String,
                allowedOrigins: Set<String>,
                request: PluginHttpRequest,
            ): PluginHttpResponse {
                ordinaryCalls++
                return PluginHttpResponse(200, ByteArray(0))
            }

            override suspend fun executeResolved(
                sourceId: Long,
                sourceOrigin: String,
                allowedOrigins: Set<String>,
                request: PluginHttpRequest,
                resolution: PluginHostResolution,
            ): PluginHttpResponse {
                resolvedCalls++
                assertEquals("api.example", resolution.host)
                assertEquals(listOf("93.184.216.34"), resolution.addresses)
                return PluginHttpResponse(200, ByteArray(0))
            }
        }
        transport.executeWithNetworkPolicy(
            sourceId = 1,
            sourceOrigin = "https://source.example",
            allowedOrigins = setOf("https://api.example"),
            request = PluginHttpRequest("GET", "https://api.example/item"),
            resolver = PluginHostResolver { listOf("93.184.216.34") },
        )
        assertEquals(0, ordinaryCalls)
        assertEquals(1, resolvedCalls)
    }

    @Test
    fun defaultBrowserTransportAndPrivateDnsFailBeforeAnyRequest() = runTest {
        var calls = 0
        val transport = object : PluginBrowserSessionTransport {
            override suspend fun execute(
                sourceId: Long,
                sourceOrigin: String,
                allowedOrigins: Set<String>,
                request: PluginHttpRequest,
            ): PluginHttpResponse {
                calls++
                return PluginHttpResponse(200, ByteArray(0))
            }
        }
        assertFailsWith<IllegalStateException> {
            transport.executeWithNetworkPolicy(
                1,
                "https://source.example",
                setOf("https://api.example"),
                PluginHttpRequest("GET", "https://api.example/item"),
                PluginHostResolver { listOf("93.184.216.34") },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            transport.executeWithNetworkPolicy(
                1,
                "https://source.example",
                setOf("https://api.example"),
                PluginHttpRequest("GET", "https://api.example/item"),
                PluginHostResolver { listOf("10.0.0.1") },
            )
        }
        assertEquals(0, calls)
    }

    @Test
    fun exactDeclaredHttpsOriginIsRequired() {
        val prepared = preparePluginBrowserSessionRequest(
            sourceOrigin = "https://source.example/path",
            allowedOrigins = setOf("https://api.example"),
            request = PluginHttpRequest("GET", "https://api.example/comics?page=1"),
        )

        assertEquals("https://source.example", prepared.sourceOrigin)
        assertEquals("https://api.example", prepared.targetOrigin)
        assertFailsWith<IllegalArgumentException> {
            preparePluginBrowserSessionRequest(
                "https://source.example",
                setOf("https://api.example"),
                PluginHttpRequest("GET", "https://api.example.evil.invalid/comics"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            preparePluginBrowserSessionRequest(
                "https://source.example",
                setOf("https://api.example"),
                PluginHttpRequest("GET", "http://api.example/comics"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            preparePluginBrowserSessionRequest(
                "https://source.example",
                emptySet(),
                PluginHttpRequest("GET", "https://api.example/comics"),
            )
        }
    }

    @Test
    fun browserManagedIdentityHeadersAreRemovedButApiHeadersRemain() {
        val prepared = preparePluginBrowserSessionRequest(
            sourceOrigin = "https://source.example",
            allowedOrigins = setOf("https://api.example"),
            request = PluginHttpRequest(
                method = "POST",
                url = "https://api.example/search",
                body = "{}".encodeToByteArray(),
                headers = mapOf(
                    "Authorization" to "signed-token",
                    "X-Signature" to "signed-value",
                    "Content-Type" to "application/json",
                    "Cookie" to "forged=1",
                    "Origin" to "https://evil.invalid",
                    "Referer" to "https://evil.invalid/",
                    "User-Agent" to "forged-agent",
                    "Sec-Fetch-Site" to "none",
                ),
            ),
        )

        assertEquals("signed-token", prepared.request.headers["Authorization"])
        assertEquals("signed-value", prepared.request.headers["X-Signature"])
        assertEquals("application/json", prepared.request.headers["Content-Type"])
        assertFalse(prepared.request.headers.keys.any { it.equals("Cookie", true) })
        assertFalse(prepared.request.headers.keys.any { it.equals("Origin", true) })
        assertFalse(prepared.request.headers.keys.any { it.equals("Referer", true) })
        assertFalse(prepared.request.headers.keys.any { it.equals("User-Agent", true) })
        assertFalse(prepared.request.headers.keys.any { it.startsWith("Sec-", true) })
    }

    @Test
    fun browserSessionRejectsTransportAuthorityForwardingProxyAndFramingHeaders() {
        val forbidden = listOf(
            "Host", ":authority", "Forwarded", "X-Forwarded-For", "X-Forwarded-Host",
            "X-Original-URL", "X-Rewrite-URL", "X-Real-IP", "Via", "Proxy",
            "Proxy-Authorization", "Proxy-Authenticate", "Proxy-Connection", "X-Proxy-Key",
            "Connection", "Keep-Alive", "Transfer-Encoding", "TE", "Trailer",
        )
        forbidden.forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) {
                preparePluginBrowserSessionRequest(
                    "https://source.example",
                    setOf("https://api.example"),
                    PluginHttpRequest("GET", "https://api.example/item", headers = mapOf(name to "forged")),
                )
            }
        }
    }

    @Test
    fun methodBodyAndResponseBoundsFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            preparePluginBrowserSessionRequest(
                "https://source.example",
                setOf("https://api.example"),
                PluginHttpRequest("DELETE", "https://api.example/item"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            preparePluginBrowserSessionRequest(
                "https://source.example",
                setOf("https://api.example"),
                PluginHttpRequest("GET", "https://api.example/item", "body".encodeToByteArray()),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            preparePluginBrowserSessionRequest(
                "https://source.example",
                setOf("https://api.example"),
                PluginHttpRequest(
                    "POST",
                    "https://api.example/item",
                    ByteArray(PLUGIN_BROWSER_SESSION_MAX_REQUEST_BODY_BYTES + 1),
                ),
            )
        }
        assertNull(decodePluginBrowserSessionFetchResult(""))
        val decoded = decodePluginBrowserSessionFetchResult(
            "\"{\\\"status\\\":429,\\\"body\\\":\\\"limited\\\"}\"",
        )
        assertEquals(429, decoded?.status)
        assertEquals("limited", decoded?.body)
        assertFailsWith<IllegalArgumentException> {
            decodePluginBrowserSessionFetchResult(
                "x".repeat(PLUGIN_BROWSER_SESSION_MAX_RESULT_WIRE_BYTES + 1),
            )
        }
    }

    @Test
    fun browserFetchScriptsBoundStreamingAndCleanCancelledSlots() {
        val prepared = preparePluginBrowserSessionRequest(
            "https://source.example",
            setOf("https://api.example"),
            PluginHttpRequest(
                "GET",
                "https://api.example/item",
                maxResponseBytes = 257,
            ),
        )
        val start = pluginBrowserSessionFetchStartScript("request-1", prepared)
        val cleanup = pluginBrowserSessionFetchCleanupScript("request-1")

        assertTrue(start.contains("new AbortController()"))
        assertTrue(start.contains("response.body.getReader"))
        assertTrue(start.contains("const limit = 257;"))
        assertFalse(start.contains("response.text()"))
        assertTrue(cleanup.contains("slot.controller?.abort()"))
        assertTrue(cleanup.contains("delete slots[requestId]").not())
        assertTrue(cleanup.contains("delete slots[\"request-1\"]"))
    }
}
