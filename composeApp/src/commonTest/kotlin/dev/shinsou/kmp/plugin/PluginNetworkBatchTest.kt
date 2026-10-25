package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
            policy = batchTestPolicy,
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
            policy = batchTestPolicy,
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

    @Test
    fun postBatchPropagatesBoundedDecodedBodyLimitToEveryTransportRequest() = runTest {
        val seenLimits = mutableListOf<Int>()
        val seenLimitsLock = Mutex()
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                seenLimitsLock.withLock { seenLimits += request.maxResponseBytes }
                PluginHttpResponse(200, byteArrayOf(1))
            },
            storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore()),
            requestGate = PerHostRequestGate(
                PluginRateLimitProvider { PluginRateLimit(PLUGIN_NETWORK_MAX_BATCH_REQUESTS, 0) },
            ),
            policy = batchTestPolicy,
        )
        val count = PLUGIN_NETWORK_MAX_BATCH_REQUESTS + 3

        network.postBatch(
            sourceId = 3,
            urls = (0 until count).map { "https://batch.example/$it" },
            bodies = List(count) { "body" },
        )

        assertEquals(List(count) { PLUGIN_NETWORK_MAX_BATCH_ITEM_RESPONSE_BYTES }, seenLimits)
        assertTrue(
            PLUGIN_NETWORK_MAX_BATCH_REQUESTS * PLUGIN_NETWORK_MAX_BATCH_ITEM_RESPONSE_BYTES <=
                PLUGIN_NETWORK_MAX_BATCH_RESPONSE_BYTES,
            "all concurrently decoded batch bodies must fit in the aggregate response budget",
        )
    }

    @Test
    fun postBatchStreamingTransportRejectsBeforeReturningAnOverCapResponse() = runTest {
        var transportReturnedResponse = false
        var seenLimit: Int? = null
        val ktorTransport = KtorPluginHttpTransport(
            HttpClient(
                MockEngine {
                    respond(
                        content = ByteArray(PLUGIN_NETWORK_MAX_BATCH_ITEM_RESPONSE_BYTES + 1),
                        status = HttpStatusCode.OK,
                    )
                },
            ),
        )
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                seenLimit = request.maxResponseBytes
                ktorTransport.execute(request).also { transportReturnedResponse = true }
            },
            storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore()),
            policy = batchTestPolicy,
        )

        assertFailsWith<IllegalArgumentException> {
            network.postBatch(
                sourceId = 4,
                urls = listOf("https://batch.example/oversized"),
                bodies = listOf("body"),
            )
        }

        assertEquals(PLUGIN_NETWORK_MAX_BATCH_ITEM_RESPONSE_BYTES, seenLimit)
        assertFalse(
            transportReturnedResponse,
            "streaming transport must reject before constructing the plugin response",
        )
    }

    @Test
    fun postBatchAtomicallyRejectsConcurrentAggregateOverflowAndCancelsRemainingRequests() = runTest {
        val stateLock = Mutex()
        val allRequestsStarted = CompletableDeferred<Unit>()
        val allPendingRequestsWaiting = CompletableDeferred<Unit>()
        val allReadyResponsesCompleted = CompletableDeferred<Unit>()
        var startedRequests = 0
        var waitingRequests = 0
        var readyResponses = 0
        var cancelledRequests = 0
        val firstChunkSize = PLUGIN_NETWORK_MAX_BATCH_REQUESTS
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                val index = request.url.substringAfterLast('/').toInt()
                if (index < firstChunkSize) {
                    return@PluginHttpTransport PluginHttpResponse(
                        200,
                        ByteArray(PLUGIN_NETWORK_MAX_BATCH_ITEM_RESPONSE_BYTES),
                    )
                }
                stateLock.withLock {
                    startedRequests++
                    if (startedRequests == 8) allRequestsStarted.complete(Unit)
                }
                if (index < firstChunkSize + 4) {
                    allPendingRequestsWaiting.await()
                    stateLock.withLock {
                        readyResponses++
                        if (readyResponses == 4) allReadyResponsesCompleted.complete(Unit)
                    }
                    allReadyResponsesCompleted.await()
                    PluginHttpResponse(200, byteArrayOf(1))
                } else {
                    try {
                        allRequestsStarted.await()
                        stateLock.withLock {
                            waitingRequests++
                            if (waitingRequests == 4) allPendingRequestsWaiting.complete(Unit)
                        }
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            stateLock.withLock { cancelledRequests++ }
                        }
                    }
                }
            },
            storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore()),
            requestGate = PerHostRequestGate(
                PluginRateLimitProvider { PluginRateLimit(PLUGIN_NETWORK_MAX_BATCH_REQUESTS, 0) },
            ),
            policy = batchTestPolicy,
        )
        val count = firstChunkSize + 8

        assertFailsWith<PluginResourceLimitException> {
            network.postBatch(
                sourceId = 5,
                urls = (0 until count).map { "https://batch.example/$it" },
                bodies = List(count) { "body" },
            )
        }

        assertEquals(4, readyResponses)
        assertEquals(4, cancelledRequests, "aggregate overflow must cancel unfinished requests")
    }

    @Test
    fun batchEncodingBoundsJsonEscapingAndOuterBridgeWrapper() {
        val response = PluginHttpResponse(200, byteArrayOf('\n'.code.toByte(), '"'.code.toByte()))

        assertEquals(
            "[\"\\n\\\"\"]",
            encodeBoundedPluginBatchResponseBodies(listOf(response), maximumBytes = 16),
        )
        assertFailsWith<PluginResourceLimitException> {
            encodeBoundedPluginBatchResponseBodies(
                listOf(response),
                maximumBytes = 12,
                jsonStringWrapped = true,
            )
        }
    }

    private companion object {
        val batchTestPolicy = PluginNetworkPolicy(
            requestOrigins = setOf("https://batch.example"),
            resolver = PluginHostResolver { listOf("93.184.216.34") },
            allowDeveloperUnpinnedTransport = true,
        )
    }
}
