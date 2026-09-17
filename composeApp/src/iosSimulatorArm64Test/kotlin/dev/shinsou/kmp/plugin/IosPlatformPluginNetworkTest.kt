package dev.shinsou.kmp.plugin

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosPlatformPluginNetworkTest {
    @Test
    fun networkFrameworkPinnedCapabilityIsAvailable() {
        val capability = inspectPlatformPluginNetwork()

        assertTrue(capability.hostResolverAvailable)
        assertTrue(capability.pinnedTransportAvailable)
        assertTrue(capability.canServePinnedRequests)
        assertTrue(capability.detail.contains("literal addresses"))
        assertTrue(capability.detail.contains("TLS SNI"))
        assertNotNull(createPlatformPinnedPluginHttpTransport())
    }

    @Test
    fun requestUsesOriginalHostAndOverridesCompressionAndConnectionFraming() {
        val encoded = encodeIosPinnedHttpRequest(
            PluginHttpRequest(
                method = "GET",
                url = "https://images.example:8443/a%20b.jpg?q=1#fragment",
                headers = mapOf("Accept-Encoding" to "gzip", "Accept" to "image/*"),
            ),
            Url("https://images.example:8443/a%20b.jpg?q=1#fragment"),
        ).decodeToString()

        assertTrue(encoded.startsWith("GET /a%20b.jpg?q=1 HTTP/1.1\r\n"))
        assertTrue("Host: images.example:8443\r\n" in encoded)
        assertEquals(1, Regex("Accept-Encoding:", RegexOption.IGNORE_CASE).findAll(encoded).count())
        assertTrue("Accept-Encoding: identity\r\n" in encoded)
        assertTrue("Connection: close\r\n" in encoded)
    }

    @Test
    fun unicodeAlbumPathIsEncodedWithoutRewritingSignedQuery() {
        val encoded = encodeIosPinnedHttpRequest(PluginHttpRequest(
            "GET", "https://example.test/album/42/作品 名稱?verify=a%2Fb%2Bc+z&x=1&x=2#fragment",
        )).decodeToString()
        assertEquals(
            "GET /album/42/%E4%BD%9C%E5%93%81%20%E5%90%8D%E7%A8%B1?verify=a%2Fb%2Bc+z&x=1&x=2 HTTP/1.1",
            encoded.substringBefore("\r\n"),
        )
        val preEncoded = encodeIosPinnedHttpRequest(PluginHttpRequest(
            "GET", "https://example.test/%E4%BD%9C%E5%93%81.jpg?verify=a%2Fb%2Bc+z",
        )).decodeToString()
        assertTrue(preEncoded.startsWith("GET /%E4%BD%9C%E5%93%81.jpg?verify=a%2Fb%2Bc+z HTTP/1.1\r\n"))
    }

    @Test
    fun requestRejectsTransportHeadersAndHeaderInjection() {
        assertFailsWith<IllegalArgumentException> {
            encodeIosPinnedHttpRequest(
                PluginHttpRequest("GET", "https://example.test/", headers = mapOf("Host" to "evil.test")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            encodeIosPinnedHttpRequest(
                PluginHttpRequest("GET", "https://example.test/", headers = mapOf("X-Test" to "ok\r\nevil")),
            )
        }
    }

    @Test
    fun fixedAndChunkedBodiesAreParsedWithinBound() {
        val fixed = parseIosPinnedHttpResponse(
            "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nX-Test: yes\r\n\r\nabc".encodeToByteArray(),
            3,
        )
        assertEquals(200, fixed.status)
        assertContentEquals("abc".encodeToByteArray(), fixed.body)
        assertEquals(listOf("yes"), fixed.headers["X-Test"])

        val chunked = parseIosPinnedHttpResponse(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n"
                .encodeToByteArray(),
            5,
        )
        assertContentEquals("abcde".encodeToByteArray(), chunked.body)
    }

    @Test
    fun responseParserRejectsSmugglingCompressionAndOversize() {
        assertFailsWith<IllegalArgumentException> {
            parseIosPinnedHttpResponse(
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nok".encodeToByteArray(),
                8,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseIosPinnedHttpResponse(
                "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 2\r\n\r\nok".encodeToByteArray(),
                8,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseIosPinnedHttpResponse(
                ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 5\r\n\r\n" +
                    "0\r\n\r\n").encodeToByteArray(),
                8,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseIosPinnedHttpResponse(
                "HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\n123456789".encodeToByteArray(),
                8,
            )
        }
    }
}
