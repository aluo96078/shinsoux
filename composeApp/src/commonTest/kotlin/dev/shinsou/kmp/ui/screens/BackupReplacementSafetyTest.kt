package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupReplacementSafetyTest {
    @Test
    fun notConfiguredUsesLegacyDeviceLocalReplacementOnly() {
        val safety = backupReplacementSafety(
            status = SyncSessionStatus.NOT_CONFIGURED,
            safeHandlerAvailable = false,
        )

        assertTrue(safety.directReplacementAllowed)
        assertFalse(safety.allDevicesEnabled)
        assertFalse(safety.thisDeviceEnabled)
    }

    @Test
    fun readyWorkspaceOffersBothExplicitTargets() {
        val safety = backupReplacementSafety(
            status = SyncSessionStatus.READY,
            safeHandlerAvailable = true,
        )

        assertFalse(safety.directReplacementAllowed)
        assertTrue(safety.allDevicesEnabled)
        assertTrue(safety.thisDeviceEnabled)
    }

    @Test
    fun nonReadyWorkspaceCanOnlyLeaveThenReplaceLocally() {
        listOf(
            SyncSessionStatus.DEPLOYING,
            SyncSessionStatus.LINKING,
            SyncSessionStatus.REVOKED,
            SyncSessionStatus.ERROR,
        ).forEach { status ->
            val safety = backupReplacementSafety(status, safeHandlerAvailable = true)

            assertFalse(safety.directReplacementAllowed, status.name)
            assertFalse(safety.allDevicesEnabled, status.name)
            assertTrue(safety.thisDeviceEnabled, status.name)
        }
    }

    @Test
    fun configuredWorkspaceWithoutSafeHandlerFailsClosed() {
        val safety = backupReplacementSafety(
            status = SyncSessionStatus.READY,
            safeHandlerAvailable = false,
        )

        assertFalse(safety.directReplacementAllowed)
        assertFalse(safety.allDevicesEnabled)
        assertFalse(safety.thisDeviceEnabled)
    }
}
