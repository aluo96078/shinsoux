package dev.shinsou.kmp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.plugin.v2.ExtensionDownloadEntryV2
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Progress of the manga and novel download queues, as shown in the ongoing notification. */
internal data class AndroidDownloadProgress(val done: Int, val total: Int, val current: String?)

private fun DownloadState.pending() = this == DownloadState.QUEUED || this == DownloadState.DOWNLOADING

internal fun downloadProgress(
    snapshot: AppSnapshot,
    novels: List<ExtensionDownloadEntryV2>,
): AndroidDownloadProgress? {
    val manga = snapshot.downloadQueue.filter { it.visibleInQueue }
    val pending = manga.count { it.state.pending() } + novels.count { it.state.pending() }
    if (pending == 0) return null
    val done = manga.count { it.state == DownloadState.DOWNLOADED } +
        novels.count { it.state == DownloadState.DOWNLOADED }
    val current = manga.firstOrNull { it.state == DownloadState.DOWNLOADING }?.let { item ->
        val title = snapshot.mangas.firstOrNull { it.id == item.mangaId }?.title
        val chapter = snapshot.chapters.firstOrNull { it.id == item.chapterId }?.name
        listOfNotNull(title, chapter).joinToString(" · ").ifEmpty { null }
    } ?: novels.firstOrNull { it.state == DownloadState.DOWNLOADING }
        ?.let { "${it.publicationTitle} · ${it.unitTitle}" }
    return AndroidDownloadProgress(done = done, total = done + pending, current = current)
}

/**
 * Keeps the process running while manga or novel chapters download. Without a foreground service Android
 * freezes the app shortly after it leaves the screen, which stalled the queue and the plugin
 * sandbox it talks to (bound with BIND_AUTO_CREATE, so it shares this priority).
 */
internal class AndroidDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ensureChannel(this)
        startInForeground(progress.value ?: AndroidDownloadProgress(0, 0, null))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shinsou:downloads")
            .apply { setReferenceCounted(false) }
        scope.launch {
            progress.collect { value ->
                if (value == null) {
                    stopSelf()
                } else {
                    // Renewed on every chapter; a stalled queue lets the screen-off CPU sleep again.
                    wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                    notificationManager().notify(NOTIFICATION_ID, notification(value))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (progress.value == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 caps dataSync services; give up the foreground state instead of crashing.
        // ensureRunning() starts it again the next time the app is opened.
        running = false
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        running = false
        super.onDestroy()
    }

    private fun startInForeground(value: AndroidDownloadProgress) {
        startForeground(NOTIFICATION_ID, notification(value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notification(value: AndroidDownloadProgress): Notification {
        val strings = notificationStrings
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(strings.text("Downloading {0} / {1}", value.done, value.total))
            .setContentText(value.current)
            .setProgress(value.total, value.done, value.total == 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2101
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private val progress = MutableStateFlow<AndroidDownloadProgress?>(null)

        /** Language of the activity that last published progress. */
        @Volatile
        private var notificationStrings: ShinsouStrings = ShinsouStrings()

        @Volatile
        private var running = false

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    notificationStrings.downloads,
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        /** Restarts the service on app open if an earlier start was refused or the service timed out. */
        fun ensureRunning(context: Context) {
            if (progress.value != null && !running) start(context)
        }

        /** Starts the service when work appears; the service stops itself once the queue drains. */
        fun publish(context: Context, value: AndroidDownloadProgress?, strings: ShinsouStrings) {
            notificationStrings = strings
            progress.value = value
            // Also retried on later updates if an earlier start was refused in the background.
            if (value == null || running) return
            start(context)
        }

        private fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, AndroidDownloadService::class.java))
            }.onFailure {
                // Only allowed while the app is visible; downloads still run while it stays open.
                Log.w("ShinsouDownloads", "Could not start the download service", it)
            }
        }
    }
}
