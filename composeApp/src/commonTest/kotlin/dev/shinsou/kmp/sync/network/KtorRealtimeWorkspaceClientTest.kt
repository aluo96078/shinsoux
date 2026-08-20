package dev.shinsou.kmp.sync.network

import kotlin.test.Test
import kotlin.test.assertEquals

class KtorRealtimeWorkspaceClientTest {
    @Test
    fun secureRestEndpointBecomesSecureWebSocketEndpoint() {
        assertEquals(
            "wss://sync.example.test/base",
            websocketEndpointForSync("https://sync.example.test/base/"),
        )
    }

    @Test
    fun loopbackHttpEndpointRemainsPlainWebSocketForLocalTests() {
        assertEquals(
            "ws://127.0.0.1:8787",
            websocketEndpointForSync("http://127.0.0.1:8787/"),
        )
    }
}
