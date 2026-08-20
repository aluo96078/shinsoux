package dev.shinsou.kmp.sync.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShinsouSyncRuntimeRecoveryTest {
    @Test
    fun claimedEpochRotatesExactlyOnce() {
        assertEquals(
            RecoveryRotationAction.ROTATE_ONCE,
            recoveryRotationAction(activeKeyEpoch = 7, claimedKeyEpoch = 7),
        )
    }

    @Test
    fun persistedNextEpochOnlyVerifiesExistingRotation() {
        assertEquals(
            RecoveryRotationAction.VERIFY_EXISTING_ROTATION,
            recoveryRotationAction(activeKeyEpoch = 8, claimedKeyEpoch = 7),
        )
    }

    @Test
    fun anyEpochOutsideBoundRecoveryTransitionFailsClosed() {
        assertFailsWith<SyncInvariantViolation> {
            recoveryRotationAction(activeKeyEpoch = 9, claimedKeyEpoch = 7)
        }
        assertFailsWith<SyncInvariantViolation> {
            recoveryRotationAction(activeKeyEpoch = 6, claimedKeyEpoch = 7)
        }
    }
}
