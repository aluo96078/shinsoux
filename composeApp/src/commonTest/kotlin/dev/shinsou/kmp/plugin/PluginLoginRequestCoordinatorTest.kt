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

    private fun target(packageId: String) = ExactPluginSourceTarget(
        PluginArtifactIdentity(packageId, "1.0.0", 1, "a".repeat(64)),
        SourceKey(packageId = packageId, sourceId = "7", legacyLongId = 7),
    )
}
