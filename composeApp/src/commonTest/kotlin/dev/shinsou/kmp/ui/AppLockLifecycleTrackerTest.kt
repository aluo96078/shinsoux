package dev.shinsou.kmp.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockLifecycleTrackerTest {
    @Test
    fun locksWhenBackgroundIntervalReachesConfiguredTimeout() {
        val tracker = AppLockLifecycleTracker()

        assertFalse(tracker.onLifecycleChanged(AppLifecycleState.BACKGROUND, 1_000, true, 30))
        assertFalse(tracker.onLifecycleChanged(AppLifecycleState.FOREGROUND, 30_999, true, 30))

        assertFalse(tracker.onLifecycleChanged(AppLifecycleState.BACKGROUND, 40_000, true, 30))
        assertTrue(tracker.onLifecycleChanged(AppLifecycleState.FOREGROUND, 70_000, true, 30))
    }

    @Test
    fun zeroTimeoutLocksOnNextForegroundAndDuplicateBackgroundDoesNotResetTimer() {
        val immediate = AppLockLifecycleTracker()
        assertFalse(immediate.onLifecycleChanged(AppLifecycleState.BACKGROUND, 5_000, true, 0))
        assertTrue(immediate.onLifecycleChanged(AppLifecycleState.FOREGROUND, 5_000, true, 0))

        val duplicate = AppLockLifecycleTracker()
        assertFalse(duplicate.onLifecycleChanged(AppLifecycleState.BACKGROUND, 10_000, true, 10))
        assertFalse(duplicate.onLifecycleChanged(AppLifecycleState.BACKGROUND, 19_000, true, 10))
        assertTrue(duplicate.onLifecycleChanged(AppLifecycleState.FOREGROUND, 20_000, true, 10))
    }

    @Test
    fun disabledLockAndForegroundWithoutBackgroundNeverRequestLock() {
        val tracker = AppLockLifecycleTracker()

        assertFalse(tracker.onLifecycleChanged(AppLifecycleState.FOREGROUND, 1_000, true, 0))
        assertFalse(tracker.onLifecycleChanged(AppLifecycleState.BACKGROUND, 2_000, false, 0))
        assertFalse(tracker.onLifecycleChanged(AppLifecycleState.FOREGROUND, 3_000, false, 0))
    }

    @Test
    fun backwardsWallClockDoesNotCreateAnElapsedTimeout() {
        val tracker = AppLockLifecycleTracker()

        assertFalse(tracker.onLifecycleChanged(AppLifecycleState.BACKGROUND, 5_000, true, 1))
        assertFalse(tracker.onLifecycleChanged(AppLifecycleState.FOREGROUND, 4_000, true, 1))
    }
}
