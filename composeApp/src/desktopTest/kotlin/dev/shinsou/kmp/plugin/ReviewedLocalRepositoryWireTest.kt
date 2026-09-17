package dev.shinsou.kmp.plugin

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Opt-in wire tests use only the expressly scoped port; stop the development server first. */
class ReviewedLocalRepositoryWireTest {
    @Test
    fun pythonHttp10ResponsesWithoutConnectionHeaderSupportConsecutiveFiles() = runBlocking {
        withServer(3, { _, _ -> "HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\n{}" }) { transport, requests ->
            repeat(3) { assertEquals("{}", transport.execute(request()).bodyText()) }
            assertEquals(3, requests().size)
            requests().forEach { assertTrue(it.contains("Connection: close\r\n", ignoreCase = true)) }
        }
    }

    @Test
    fun realTransportDoesNotRetainCookiesOrSendAuthorization() = runBlocking {
        withServer(2, { _, index ->
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n" +
                (if (index == 0) "Set-Cookie: local_test=synthetic; Path=/\r\n" else "") + "\r\n{}"
        }) { transport, requests ->
            repeat(2) {
                val response = transport.execute(request())
                assertEquals("{}", response.bodyText())
                assertTrue(response.headers.keys.none { it.equals("Set-Cookie", true) })
            }
            val observed = requests()
            assertEquals(2, observed.size)
            observed.forEach { headers ->
                assertTrue(headers.startsWith("GET /repo.json HTTP/1.1\r\n"))
                assertFalse(Regex("(?im)^(Cookie|Authorization|Proxy-Authorization):").containsMatchIn(headers))
            }
        }
    }

    @Test
    fun sameOriginRedirectIsRejectedWithoutSecondRequest() = runBlocking {
        withServer(1, { _, _ ->
            "HTTP/1.1 302 Found\r\nLocation: /index.json\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }) { transport, requests ->
            assertFailsWith<IllegalArgumentException> { transport.execute(request()) }
            assertEquals(1, requests().size)
        }
    }

    @Test
    fun declaredAndStreamedOversizedBodiesAreRejected() = runBlocking {
        withServer(2, { _, index ->
            "HTTP/1.1 200 OK\r\nConnection: close\r\n" +
                (if (index == 0) "Content-Length: 5\r\n" else "") + "\r\n12345"
        }) { transport, requests ->
            repeat(2) {
                assertFailsWith<IllegalArgumentException> { transport.execute(request().copy(maxResponseBytes = 4)) }
            }
            assertEquals(2, requests().size)
        }
    }

    @Test
    fun duplicateRedirectHeadersAreRejected() = runBlocking {
        withServer(1, { _, _ ->
            "HTTP/1.1 200 OK\r\nLocation: /index.json\r\nLocation: /repo.json\r\n" +
                "Content-Length: 2\r\nConnection: close\r\n\r\n{}"
        }) { transport, requests ->
            assertFailsWith<IllegalArgumentException> { transport.execute(request()) }
            assertEquals(1, requests().size)
        }
    }

    private fun request() = PluginHttpRequest("GET", "$REVIEWED_LOCAL_SHINSOU_REPOSITORY_BASE_URL/repo.json")

    private suspend fun withServer(
        count: Int,
        respond: (String, Int) -> String,
        block: suspend (DesktopReviewedLocalRepositoryTransport, () -> List<String>) -> Unit,
    ) {
        assumeTrue("Requires exclusive scoped 127.0.0.1:18081", System.getenv("SHINSOU_LOCAL_REPOSITORY_WIRE_TEST") == "true")
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(java.net.InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 18081))
                server.soTimeout = 5_000
                val served = executor.submit {
                    repeat(count) { index ->
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val headers = readHeaders(socket)
                            requests += headers
                            socket.getOutputStream().write(respond(headers, index).toByteArray(Charsets.US_ASCII))
                            socket.getOutputStream().flush()
                        }
                    }
                }
                DesktopReviewedLocalRepositoryTransport().use { transport ->
                    block(transport) { synchronized(requests) { requests.toList() } }
                }
                served.get(10, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun readHeaders(socket: Socket): String {
        val input = socket.getInputStream()
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 8_192) {
            val next = input.read()
            check(next >= 0) { "Incomplete request" }
            bytes.write(next)
            val value = bytes.toString(Charsets.US_ASCII)
            if (value.endsWith("\r\n\r\n")) return value
        }
        error("Oversized request headers")
    }
}
