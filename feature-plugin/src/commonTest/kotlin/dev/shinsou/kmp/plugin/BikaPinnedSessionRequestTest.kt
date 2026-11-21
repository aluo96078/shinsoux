package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals

class BikaPinnedSessionRequestTest {
    private val origin = "https://manhuabika.com"
    private val api = "https://picaapi.go2778.com"
    private val resolution = PluginHostResolution("picaapi.go2778.com", listOf("93.184.216.34"))

    @Test
    fun preservesSignedTokenRequestAndSuppliesHostOwnedBrowserHeaders() {
        val body = "{\"sort\":\"latest\"}".encodeToByteArray()
        val request = PluginHttpRequest("POST", "$api/comics/search", body = body, headers = mapOf(
            "authorization" to "fixture-token", "nonce" to "fixture-nonce", "signature" to "fixture-signature",
            "Origin" to "https://wrong.example", "Cookie" to "must-not-forward=1",
        ))
        val result = bikaPinnedSessionRequest(origin, setOf(api), request, resolution, "fixture-agent")
        assertContentEquals(body, result.body)
        assertEquals("POST", result.method)
        assertEquals("fixture-token", result.headers["authorization"])
        assertEquals("fixture-nonce", result.headers["nonce"])
        assertEquals("fixture-signature", result.headers["signature"])
        assertEquals(origin, result.headers["Origin"])
        assertEquals("$origin/", result.headers["Referer"])
        assertEquals("fixture-agent", result.headers["User-Agent"])
        assertFalse(result.headers.keys.any { it.equals("Cookie", true) })
    }

    @Test
    fun rejectsOtherOriginsAndMismatchedDnsBinding() {
        val request = PluginHttpRequest("GET", "$api/categories")
        assertFailsWith<IllegalArgumentException> {
            bikaPinnedSessionRequest("https://other.example", setOf(api), request, resolution, "agent")
        }
        assertFailsWith<IllegalArgumentException> {
            bikaPinnedSessionRequest(origin, setOf(api), request,
                PluginHostResolution("other.example", listOf("93.184.216.34")), "agent")
        }
        assertFailsWith<IllegalArgumentException> {
            bikaPinnedSessionRequest(origin, setOf(api), request.copy(url = "https://127.0.0.1/"), resolution, "agent")
        }
    }
}
