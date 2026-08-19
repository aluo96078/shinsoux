package dev.shinsou.kmp.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Best-effort scheduler for hosts that remain alive, such as the macOS desktop application.
 *
 * [start] is idempotent and [AutoBackupService] performs the authoritative due check, so lifecycle
 * re-entry cannot create duplicate backups. OS-managed mobile schedulers remain authoritative when
 * the app is suspended or terminated.
 */
public class ForegroundAutoBackupScheduler(
    private val service: AutoBackupService,
    private val scope: CoroutineScope,
    private val checkIntervalMillis: Long = DEFAULT_FOREGROUND_CHECK_INTERVAL_MILLIS,
) {
    private var job: Job? = null

    public fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                service.runIfDue()
                delay(checkIntervalMillis.coerceAtLeast(MINIMUM_FOREGROUND_CHECK_INTERVAL_MILLIS))
            }
        }
    }

    public fun stop() {
        job?.cancel()
        job = null
    }
}

private const val MINIMUM_FOREGROUND_CHECK_INTERVAL_MILLIS = 1_000L
private const val DEFAULT_FOREGROUND_CHECK_INTERVAL_MILLIS = 15L * 60L * 1_000L
