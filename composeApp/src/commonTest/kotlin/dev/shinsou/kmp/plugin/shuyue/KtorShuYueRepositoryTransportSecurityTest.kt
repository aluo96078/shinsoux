package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.plugin.PluginHostResolution
import dev.shinsou.kmp.plugin.PluginHostResolver
import dev.shinsou.kmp.plugin.PluginHttpRequest
import dev.shinsou.kmp.plugin.PluginHttpResponse
import dev.shinsou.kmp.plugin.PluginHttpTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class KtorShuYueRepositoryTransportSecurityTest {
    @Test
    fun reviewedLanUsesDedicatedTransportAndRejectsOtherRoutesAndRedirects() = runTest {
        val base = "http://192.168.50.193:18081"
        var calls = 0
        var reply = PluginHttpResponse(200, "[]".encodeToByteArray())
        val http = HttpClient(MockEngine { error("Ordinary HTTP must not be used") })
        val transport = KtorShuYueRepositoryTransport(
            client = http,
            reviewedLocalRepositoryPolicy = dev.shinsou.kmp.plugin.ReviewedLocalRepositoryPolicy
                .EXACT_IOS_LAN_192_168_50_193_18081,
            reviewedLocalRepositoryTransport = PluginHttpTransport { request ->
                calls++
                assertEquals("GET", request.method)
                assertEquals(emptyMap(), request.headers)
                assertEquals(1024, request.maxResponseBytes)
                reply
            },
        )
        suspend fun fetch(url: String) = transport.execute(ShuYueRepositoryRequest(
            url, 1024, setOf(ShuYueOrigin.parse(base)),
        ))
        try {
            for (route in listOf("index.json", "plugins/zh.wenku8.api.js", "sidecars/zh.wenku8.api.json")) {
                assertEquals(200, fetch("$base/$route").status)
            }
            assertFailsWith<IllegalArgumentException> { fetch("$base/private.json") }
            assertFailsWith<ShuYueRepositoryException.OriginNotAllowed> {
                fetch("http://192.168.50.194:18081/index.json")
            }
            assertEquals(3, calls)
            reply = PluginHttpResponse(302, ByteArray(0), mapOf("Location" to listOf("/index.json")))
            assertFailsWith<IllegalArgumentException> { fetch("$base/index.json") }
            assertEquals(4, calls)
            reply = PluginHttpResponse(200, ByteArray(1025))
            assertFailsWith<ShuYueRepositoryException.BodyTooLarge> { fetch("$base/index.json") }
        } finally {
            http.close()
        }
    }

    @Test
    fun productionUsesPinnedResolutionAndRevalidatesRedirectDns() = runTest {
        val resolutions = ArrayDeque(listOf(listOf("93.184.216.34"), listOf("127.0.0.1")))
        var ordinaryCalls = 0
        var resolvedCalls = 0
        val transport = KtorShuYueRepositoryTransport(
            client = HttpClient(MockEngine {
                ordinaryCalls++
                respond("unexpected", HttpStatusCode.OK)
            }),
            hostResolver = PluginHostResolver { resolutions.removeFirst() },
            pinnedTransport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    error("production must not use ordinary transport")

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse {
                    resolvedCalls++
                    assertEquals(listOf("93.184.216.34"), resolution.addresses)
                    return PluginHttpResponse(
                        302,
                        ByteArray(0),
                        mapOf("Location" to listOf("/next.json")),
                    )
                }
            },
        )

        assertFailsWith<IllegalArgumentException> {
            transport.execute(
                ShuYueRepositoryRequest(
                    "https://repo.example/index.json",
                    1024,
                    setOf(ShuYueOrigin.parse("https://repo.example")),
                ),
            )
        }
        assertEquals(0, ordinaryCalls)
        assertEquals(1, resolvedCalls)
        assertEquals(0, resolutions.size)
    }

    @Test
    fun missingPinnedTransportFailsClosedBeforeHttp() = runTest {
        var requests = 0
        val transport = KtorShuYueRepositoryTransport(
            client = HttpClient(MockEngine {
                requests++
                respond("unexpected", HttpStatusCode.OK)
            }),
            hostResolver = PluginHostResolver { listOf("93.184.216.34") },
        )

        assertFailsWith<ShuYueRepositoryException.PinnedTransportUnavailable> {
            transport.execute(
                ShuYueRepositoryRequest(
                    "https://repo.example/index.json",
                    1024,
                    setOf(ShuYueOrigin.parse("https://repo.example")),
                ),
            )
        }
        assertEquals(0, requests)
    }

    @Test
    fun developerHttpEscapeHatchAcceptsOnlyLocalOrigins() = runTest {
        var requests = 0
        val transport = KtorShuYueRepositoryTransport(
            client = HttpClient(MockEngine {
                requests++
                respond("[]", HttpStatusCode.OK)
            }),
            allowLocalDeveloperTransport = true,
        )

        val local = ShuYueOrigin.parse("http://127.0.0.1:8080")
        assertEquals(
            200,
            transport.execute(
                ShuYueRepositoryRequest("http://127.0.0.1:8080/index.json", 1024, setOf(local)),
            ).status,
        )
        val public = ShuYueOrigin.parse("http://public.example")
        assertFailsWith<ShuYueRepositoryException.InvalidUrl> {
            transport.execute(
                ShuYueRepositoryRequest("http://public.example/index.json", 1024, setOf(public)),
            )
        }
        assertEquals(1, requests)
    }

    @Test
    fun artifactLimitIsPropagatedToPinnedTransport() = runTest {
        var receivedLimit = 0
        val transport = KtorShuYueRepositoryTransport(
            client = HttpClient(MockEngine { error("ordinary transport must not be called") }),
            hostResolver = PluginHostResolver { listOf("93.184.216.34") },
            pinnedTransport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    error("production must not use ordinary transport")

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse {
                    receivedLimit = request.maxResponseBytes
                    return PluginHttpResponse(200, "ok".encodeToByteArray())
                }
            },
        )

        val response = transport.execute(
            ShuYueRepositoryRequest(
                "https://repo.example/index.json",
                257,
                setOf(ShuYueOrigin.parse("https://repo.example")),
            ),
        )

        assertEquals(200, response.status)
        assertEquals(257, receivedLimit)
    }

    @Test
    fun localDeveloperStreamingResponseStopsAtRequestLimit() = runTest {
        val transport = KtorShuYueRepositoryTransport(
            client = HttpClient(MockEngine {
                respond(ByteArray(4_096), HttpStatusCode.OK)
            }),
            allowLocalDeveloperTransport = true,
        )

        val failure = assertFailsWith<ShuYueRepositoryException> {
            transport.execute(
                ShuYueRepositoryRequest(
                    "http://127.0.0.1:8080/index.json",
                    31,
                    setOf(ShuYueOrigin.parse("http://127.0.0.1:8080")),
                ),
            )
        }

        assertIs<ShuYueRepositoryException.BodyTooLarge>(failure)
    }
}
