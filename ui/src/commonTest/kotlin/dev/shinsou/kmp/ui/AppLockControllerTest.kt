package dev.shinsou.kmp.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockControllerTest {
    private var now = 0L
    private var authentications = 0
    private var accept = true

    private fun controller(locked: Boolean, available: Boolean = true) = AppLockController(
        lockedAtStart = locked,
        authenticationAvailable = available,
        authenticate = {
            authentications++
            accept
        },
        now = { now },
    )

    @Test
    fun aLockedAppAuthenticatesOnlyInTheForeground() = runTest {
        val lock = controller(locked = true)
        lock.enforce(appLockEnabled = true, lifecycle = AppLifecycleState.BACKGROUND)
        assertEquals(0, authentications)
        lock.enforce(appLockEnabled = true, lifecycle = AppLifecycleState.FOREGROUND)
        assertEquals(1, authentications)
        assertTrue(lock.state.value.unlocked)
        assertFalse(lock.state.value.unlocking)
    }

    @Test
    fun aRefusedAuthenticationStaysLocked() = runTest {
        accept = false
        val lock = controller(locked = true)
        lock.unlock()
        assertFalse(lock.state.value.unlocked)
    }

    @Test
    fun switchingTheLockOffUnlocks() = runTest {
        val lock = controller(locked = true)
        lock.enforce(appLockEnabled = false, lifecycle = AppLifecycleState.BACKGROUND)
        assertTrue(lock.state.value.unlocked)
        assertEquals(0, authentications)
    }

    @Test
    fun linksWaitForTheForegroundLockCheck() = runTest {
        val lock = controller(locked = false)
        lock.onLifecycleChanged(AppLifecycleState.BACKGROUND, appLockEnabled = true, lockAfterSeconds = 60)
        now = 100_000L
        // Back on screen: still "unlocked" until the lifecycle check has run.
        val answer = async { lock.unlockedOnceChecked(AppLifecycleState.FOREGROUND) }
        runCurrent()
        assertFalse(answer.isCompleted)
        assertTrue(lock.state.value.unlocked)
        lock.onLifecycleChanged(AppLifecycleState.FOREGROUND, appLockEnabled = true, lockAfterSeconds = 60)
        assertFalse(answer.await())
    }

    @Test
    fun returningAfterTheTimeoutLocks() {
        val lock = controller(locked = false)
        lock.onLifecycleChanged(AppLifecycleState.BACKGROUND, appLockEnabled = true, lockAfterSeconds = 60)
        now = 30_000L
        lock.onLifecycleChanged(AppLifecycleState.FOREGROUND, appLockEnabled = true, lockAfterSeconds = 60)
        assertTrue(lock.state.value.unlocked)
        lock.onLifecycleChanged(AppLifecycleState.BACKGROUND, appLockEnabled = true, lockAfterSeconds = 60)
        now = 100_000L
        lock.onLifecycleChanged(AppLifecycleState.FOREGROUND, appLockEnabled = true, lockAfterSeconds = 60)
        assertFalse(lock.state.value.unlocked)
    }

    @Test
    fun aConfiguredLockAppliesWhenAuthenticationComesBack() {
        val lock = controller(locked = false, available = false)
        lock.onAuthenticationAvailabilityChanged(appLockConfigured = true, available = false)
        assertTrue(lock.state.value.unlocked)
        lock.onAuthenticationAvailabilityChanged(appLockConfigured = true, available = true)
        assertFalse(lock.state.value.unlocked)
    }
}
