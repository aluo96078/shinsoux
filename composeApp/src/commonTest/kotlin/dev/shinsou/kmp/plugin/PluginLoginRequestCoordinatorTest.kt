package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.ui.SourceLoginRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginLoginRequestCoordinatorTest {
    @Test
    fun requestsAreDeduplicatedBySourceAndKeepFifoOrder() {
        val coordinator = PluginLoginRequestCoordinator()

        assertTrue(coordinator.request(20, "Second", "  Account required  "))
        assertTrue(coordinator.request(10, "First", null))
        assertTrue(coordinator.request(20, "Renamed", "New reason"))

        assertEquals(
            listOf(
                SourceLoginRequest(20, "Second", "Account required"),
                SourceLoginRequest(10, "First"),
            ),
            coordinator.loginRequests.value,
        )

        coordinator.dismiss(20)
        assertEquals(listOf(SourceLoginRequest(10, "First")), coordinator.loginRequests.value)
        coordinator.dismiss(999)
        assertEquals(listOf(SourceLoginRequest(10, "First")), coordinator.loginRequests.value)
    }

    @Test
    fun defaultRequesterIsANonThrowingNoOp() {
        assertFalse(PluginLoginRequester.None.request(1, "Source", "Sign in"))
    }
}
