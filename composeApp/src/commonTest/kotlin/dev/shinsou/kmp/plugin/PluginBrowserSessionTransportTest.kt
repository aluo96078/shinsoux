package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PluginBrowserSessionTransportTest {
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
    }
}
