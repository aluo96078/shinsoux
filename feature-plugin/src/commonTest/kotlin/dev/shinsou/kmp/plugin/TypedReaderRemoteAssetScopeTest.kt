package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.contract.ReaderRemoteImageFetcher
import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reader's inline-image fetch keeps the content policy and the decoder response checks. */
class TypedReaderRemoteAssetScopeTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)

    @Test
    fun imageFromAContentOriginIsReturned() = runTest {
        val requests = mutableListOf<PluginHttpRequest>()
        val fetcher = scope { request ->
            requests += request
            PluginHttpResponse(200, png, mapOf("Content-Type" to listOf("image/png")))
        }

        assertContentEquals(png, fetcher.fetchImage("https://images.example/a.png"))
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://images.example/a.png", request.url)
        assertTrue(request.headers.keys.none { it.equals("Cookie", ignoreCase = true) })
    }

    @Test
    fun documentAndFailedResponsesAreNotHandedToTheDecoder() = runTest {
        val html = scope { PluginHttpResponse(200, png, mapOf("Content-Type" to listOf("text/html"))) }
        assertNull(html.fetchImage("https://images.example/a.png"))
        val missing = scope { PluginHttpResponse(404, png) }
        assertNull(missing.fetchImage("https://images.example/a.png"))
    }

    @Test
    fun refusedOrFailingRequestsYieldNull() = runTest {
        val requests = mutableListOf<PluginHttpRequest>()
        val fetcher = scope { request ->
            requests += request
            PluginHttpResponse(200, png)
        }
        // Outside the declared content origins: the content policy refuses before any transport.
        assertNull(fetcher.fetchImage("https://elsewhere.example/a.png"))
        assertTrue(requests.isEmpty())
        val failing = scope { error("transport down") }
        assertNull(failing.fetchImage("https://images.example/a.png"))
    }

    @Test
    fun cancellationPropagates() = runTest {
        val fetcher = scope { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { fetcher.fetchImage("https://images.example/a.png") }
    }

    private fun scope(block: suspend (PluginHttpRequest) -> PluginHttpResponse): ReaderRemoteImageFetcher {
        val transport = object : PluginHttpTransport {
            override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = block(request)

            override suspend fun executeResolved(
                request: PluginHttpRequest,
                resolution: PluginHostResolution,
            ): PluginHttpResponse = block(request)
        }
        val policy = PluginNetworkPolicy(
            requestOrigins = setOf("https://api.example"),
            contentOrigins = setOf("https://images.example"),
        )
        val network = PluginNetworkClient(
            transport = transport,
            storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore()),
            policy = policy,
            hostResolver = PluginHostResolver { listOf("93.184.216.34") },
        ).scopedToContentPolicy(policy)
        return TypedReaderRemoteAssetScope(
            sourceKey = SourceKey(packageId = "pkg.test", sourceId = "novels"),
            sourceId = 1,
            network = network,
        )
    }
}
