package dev.shinsou.kmp.ui

import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Whether the app is unlocked, and whether the platform's authentication is showing. */
internal data class AppLockState(
    val unlocked: Boolean,
    val unlocking: Boolean = false,
)

/**
 * R4-3 (docs/architecture/0006): the app lock. The shell reports the platform's lifecycle and the
 * lock settings; this decides when the app locks and asks [authenticate] to unlock it.
 */
internal class AppLockController(
    lockedAtStart: Boolean,
    authenticationAvailable: Boolean,
    private val authenticate: suspend () -> Boolean,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow(AppLockState(unlocked = !lockedAtStart))
    private val lifecycleTracker = AppLockLifecycleTracker()
    private var authenticationWasAvailable = authenticationAvailable

    /** The lifecycle [onLifecycleChanged] last saw (and locked for, when it was time to). */
    private val checkedLifecycle = MutableStateFlow<AppLifecycleState?>(null)

    val state: StateFlow<AppLockState> = mutableState.asStateFlow()

    /** Device authentication was added or removed: a configured lock applies once it is back. */
    fun onAuthenticationAvailabilityChanged(appLockConfigured: Boolean, available: Boolean) {
        val shouldLock = shouldLockOnAuthenticationAvailabilityChange(
            appLockConfigured = appLockConfigured,
            wasAvailable = authenticationWasAvailable,
            isAvailable = available,
        )
        authenticationWasAvailable = available
        if (shouldLock) lock()
    }

    /** The app moved to [lifecycle]; returning after [lockAfterSeconds] in the background locks it. */
    fun onLifecycleChanged(lifecycle: AppLifecycleState, appLockEnabled: Boolean, lockAfterSeconds: Int) {
        val shouldLock = lifecycleTracker.onLifecycleChanged(
            state = lifecycle,
            nowEpochMillis = now(),
            appLockEnabled = appLockEnabled,
            lockAfterSeconds = lockAfterSeconds,
        )
        if (shouldLock) lock()
        checkedLifecycle.value = lifecycle
    }

    /**
     * Whether the app is unlocked once [onLifecycleChanged] has seen [lifecycle]: returning after the
     * lock timeout locks the app there, so nothing may act on an earlier "unlocked" before that.
     */
    suspend fun unlockedOnceChecked(lifecycle: AppLifecycleState): Boolean {
        checkedLifecycle.first { it == lifecycle }
        return mutableState.value.unlocked
    }

    /**
     * Asks for authentication when the app is locked and on screen; a lock that was switched off
     * unlocks the app.
     */
    suspend fun enforce(appLockEnabled: Boolean, lifecycle: AppLifecycleState) {
        val current = mutableState.value
        if (appLockEnabled && !current.unlocked && lifecycle == AppLifecycleState.FOREGROUND) {
            unlock()
        } else if (!appLockEnabled) {
            mutableState.update { it.copy(unlocked = true) }
        }
    }

    /** Shows the platform's authentication; the app unlocks when it succeeds. */
    suspend fun unlock() {
        mutableState.update { it.copy(unlocking = true) }
        val unlocked = authenticate()
        mutableState.update { it.copy(unlocked = unlocked, unlocking = false) }
    }

    private fun lock() = mutableState.update { it.copy(unlocked = false) }
}
