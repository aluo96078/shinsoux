package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.ExactPluginSourceTarget
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginLoginRequestCoordinatorTest {
    @Test
    fun sameLegacyLongAcrossPackagesRemainsIndependentButExactTargetDeduplicates() {
        val coordinator = PluginLoginRequestCoordinator()
        val first = target("pkg.one")
        val second = target("pkg.two")
        assertTrue(coordinator.requestEvent("op-1", first, 7, "One", null))
        assertTrue(coordinator.requestEvent("op-2", second, 7, "Two", null))
        assertTrue(coordinator.requestEvent("op-3", first, 7, "One", null))
        assertEquals(listOf("op-1", "op-2"), coordinator.loginRequests.value.map { it.eventId })
        coordinator.clearTarget(first)
        assertEquals(listOf("op-2"), coordinator.loginRequests.value.map { it.eventId })
        assertFalse(coordinator.hasTarget(first))
    }

    @Test
    fun backgroundTransitionRetainsOnlyThePresentedEventRequest() {
        val coordinator = PluginLoginRequestCoordinator()
        assertTrue(coordinator.requestEvent("op-1", target("pkg.one"), 7, "One", null))
        assertTrue(coordinator.requestEvent("op-2", target("pkg.two"), 8, "Two", null))

        coordinator.retainPresentedRequest()

        assertEquals(listOf("op-1"), coordinator.loginRequests.value.map { it.eventId })
    }

    @Test
    fun backgroundTransitionDropsEventRequestsWhenLegacyPromptIsPresented() {
        val coordinator = PluginLoginRequestCoordinator()
        assertTrue(coordinator.request(9, "Legacy", null))
        assertTrue(coordinator.requestEvent("op-1", target("pkg.one"), 7, "One", null))

        coordinator.retainPresentedRequest()

        assertEquals(listOf<Long>(9), coordinator.loginRequests.value.map { it.sourceId })
        assertEquals(listOf<String?>(null), coordinator.loginRequests.value.map { it.eventId })
    }

    @Test
    fun backgroundTransitionKeepsLegacyRequestsQueuedBehindPresentedEvent() {
        val coordinator = PluginLoginRequestCoordinator()
        assertTrue(coordinator.requestEvent("op-1", target("pkg.one"), 7, "One", null))
        assertTrue(coordinator.request(9, "Legacy", null))
        assertTrue(coordinator.requestEvent("op-2", target("pkg.two"), 8, "Two", null))

        coordinator.retainPresentedRequest()

        assertEquals(listOf("op-1", null), coordinator.loginRequests.value.map { it.eventId })
        assertEquals(listOf(7L, 9L), coordinator.loginRequests.value.map { it.sourceId })
    }

    private fun target(packageId: String) = ExactPluginSourceTarget(
        PluginArtifactIdentity(packageId, "1.0.0", 1, "a".repeat(64)),
        SourceKey(packageId = packageId, sourceId = "7", legacyLongId = 7),
    )
}
