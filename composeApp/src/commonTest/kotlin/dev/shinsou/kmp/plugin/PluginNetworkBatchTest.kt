package dev.shinsou.kmp.plugin

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginNetworkBatchTest {
    @Test
    fun postBatchPreservesOrderAndOverlapsTransportWaits() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val stateLock = Mutex()
        var active = 0
        var maximumActive = 0
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                stateLock.withLock {
                    active++
                    maximumActive = maxOf(maximumActive, active)
                }
                try {
                    delay(40)
                    PluginHttpResponse(200, request.url.substringAfterLast('/').encodeToByteArray())
                } finally {
                    stateLock.withLock { active-- }
                }
            },
            storage = storage,
            requestGate = PerHostRequestGate(
                PluginRateLimitProvider { PluginRateLimit(32, 0) },
            ),
        )

        val responses = network.postBatch(
            sourceId = 1,
            urls = (0 until 8).map { "https://batch.example/$it" },
            bodies = (0 until 8).map { "body-$it" },
        )

        assertEquals((0 until 8).map(Int::toString), responses.map { it.bodyText() })
        assertTrue(maximumActive > 1, "batch requests should overlap transport waits")
    }

    @Test
    fun postBatchChunksLargeInputWithoutChangingResponseOrder() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                PluginHttpResponse(200, request.url.substringAfterLast('/').encodeToByteArray())
            },
            storage = storage,
            requestGate = PerHostRequestGate(
                PluginRateLimitProvider { PluginRateLimit(PLUGIN_NETWORK_MAX_BATCH_REQUESTS, 0) },
            ),
        )
        val count = PLUGIN_NETWORK_MAX_BATCH_REQUESTS * 2 + 3

        val responses = network.postBatch(
            sourceId = 2,
            urls = (0 until count).map { "https://batch.example/$it" },
            bodies = (0 until count).map { "body-$it" },
        )

        assertEquals(count, responses.size)
        assertEquals((0 until count).map(Int::toString), responses.map { it.bodyText() })
    }
}
