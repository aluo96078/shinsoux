package dev.shinsou.kmp

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process running while manga or novel chapters download on Android 10–13 (and as a
 * fallback when the user-initiated job cannot be scheduled). Without it Android freezes the app
 * shortly after it leaves the screen, which stalled the queue and the plugin sandbox it talks to
 * (bound with BIND_AUTO_CREATE, so it shares this priority).
 */
internal class AndroidDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(
            AndroidDownloadNotification.ID,
            AndroidDownloadNotification.build(this, AndroidBackgroundDownloads.progress.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shinsou:downloads")
            .apply { setReferenceCounted(false) }
        scope.launch {
            AndroidBackgroundDownloads.progress.collect { value ->
                if (value == null) {
                    stopSelf()
                } else {
                    // Renewed on every chapter; a stalled queue lets the screen-off CPU sleep again.
                    wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                    AndroidDownloadNotification.update(this@AndroidDownloadService, value)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AndroidBackgroundDownloads.progress.value == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 caps dataSync services. Pause the queues cleanly (they resume when the app
        // is next opened) and give up the foreground state instead of crashing.
        running = false
        AndroidBackgroundDownloads.systemStop?.invoke()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        running = false
        super.onDestroy()
    }

    companion object {
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        @Volatile
        private var running = false

        fun start(context: Context) {
            if (running) return
            runCatching {
                context.startForegroundService(Intent(context, AndroidDownloadService::class.java))
            }.onFailure {
                // Only allowed while the app is visible; downloads still run while it stays open.
                Log.w("ShinsouDownloads", "Could not start the download service", it)
            }
        }
    }
}
