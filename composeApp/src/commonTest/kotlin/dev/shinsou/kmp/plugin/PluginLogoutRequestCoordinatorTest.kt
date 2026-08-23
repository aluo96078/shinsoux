package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.events.ExactPluginSourceTarget
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginLogoutRequestCoordinatorTest {
    @Test
    fun hostEventIdsKeepSamePluginMessageFromDifferentSourcesIndependent() = runTest {
        val coordinator = PluginLogoutRequestCoordinator(this, timeoutMillis = 1_000)
        val first = confirmation("op-1", "a")
        val second = confirmation("op-2", "b")
        coordinator.request(first)
        coordinator.request(second)
        assertEquals(first, coordinator.take("op-1"))
        assertEquals(listOf(second), coordinator.requests.value)
        assertNull(coordinator.take("plugin-local-message-id"))
    }

    @Test
    fun timeoutRemovesRequestAndPreventsLaterConfirmation() = runTest {
        val coordinator = PluginLogoutRequestCoordinator(this, timeoutMillis = 100)
        coordinator.request(confirmation("op-1", "a"))
        advanceTimeBy(101)
        testScheduler.runCurrent()
        assertNull(coordinator.take("op-1"))
    }

    @Test
    fun exactTargetHasAtMostOnePendingWhileSameSourceIdInAnotherPackageIsIndependent() = runTest {
        val coordinator = PluginLogoutRequestCoordinator(this)
        val first = confirmation("op-1", "a")
        val duplicate = first.copy(eventId = "op-2")
        val otherPackage = first.copy(
            eventId = "op-3",
            target = ExactPluginSourceTarget(
                PluginArtifactIdentity("pkg.other", "1.0.0", 1, "b".repeat(64)),
                SourceKey(packageId = "pkg.other", sourceId = "a"),
            ),
        )
        coordinator.request(first)
        coordinator.request(duplicate)
        coordinator.request(otherPackage)
        assertEquals(listOf("op-1", "op-3"), coordinator.requests.value.map { it.eventId })
    }

    @Test
    fun exactSessionOwnerMustMatchBeforeCleanup() {
        val target = confirmation("op-1", "a").target
        val replacement = ExactPluginSourceTarget(
            PluginArtifactIdentity("pkg.test", "2.0.0", 2, "b".repeat(64)),
            target.sourceKey,
        )
        val owner = ExactPluginSessionOwnership.targetKey(target)
        assertTrue(ExactPluginSessionOwnership.authorizesCleanup(owner, target))
        assertFalse(ExactPluginSessionOwnership.authorizesCleanup(owner, replacement))
        assertFalse(ExactPluginSessionOwnership.authorizesCleanup(null, target))
    }

    private fun confirmation(eventId: String, sourceId: String) = PluginLogoutConfirmation(
        eventId = eventId,
        target = ExactPluginSourceTarget(
            PluginArtifactIdentity("pkg.test", "1.0.0", 1, "a".repeat(64)),
            SourceKey(packageId = "pkg.test", sourceId = sourceId),
        ),
        sourceName = sourceId,
        message = null,
    )
}
