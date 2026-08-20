package dev.shinsou.kmp.sync.v2

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncEndpointPolicyTest {
    @Test
    fun httpsAndExplicitLoopbackHttpAreAccepted() {
        assertTrue(isAllowedSyncEndpoint("https://sync.example.test"))
        assertTrue(isAllowedSyncEndpoint("http://localhost:8787"))
        assertTrue(isAllowedSyncEndpoint("http://127.0.0.1:8787"))
        session("http://localhost:8787")
    }

    @Test
    fun plainLanAndLookalikeHostsAreRejected() {
        listOf(
            "http://192.168.1.10:8787",
            "http://sync.example.test",
            "http://localhost.evil.test",
            "http://127.0.0.1.evil.test",
            "https://sync.example.test?token=forbidden",
        ).forEach { endpoint ->
            assertFailsWith<IllegalArgumentException>(endpoint) { session(endpoint) }
        }
    }

    private fun session(endpoint: String) = SyncSession(
        endpoint = endpoint,
        instanceId = "instance",
        userId = "user",
        workspaceId = "workspace",
        deviceId = "device",
        deviceDisplayName = "Test device",
        platform = "other",
        status = SyncSessionStatus.LINKING,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = 1,
    )
}
