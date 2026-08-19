package dev.shinsou.kmp.ui

/**
 * Tracks one background interval and decides whether returning to the foreground
 * must invalidate an existing app-lock authentication.
 */
internal class AppLockLifecycleTracker {
    private var backgroundedAtEpochMillis: Long? = null

    fun onLifecycleChanged(
        state: AppLifecycleState,
        nowEpochMillis: Long,
        appLockEnabled: Boolean,
        lockAfterSeconds: Int,
    ): Boolean = when (state) {
        AppLifecycleState.BACKGROUND -> {
            if (backgroundedAtEpochMillis == null) {
                backgroundedAtEpochMillis = nowEpochMillis
            }
            false
        }

        AppLifecycleState.FOREGROUND -> {
            val backgroundedAt = backgroundedAtEpochMillis
            backgroundedAtEpochMillis = null
            if (!appLockEnabled || backgroundedAt == null) {
                false
            } else {
                val elapsed = (nowEpochMillis - backgroundedAt).coerceAtLeast(0L)
                val timeout = lockAfterSeconds.coerceAtLeast(0).toLong() * MILLIS_PER_SECOND
                elapsed >= timeout
            }
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
