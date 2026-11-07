package dev.shinsou.kmp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.shinsou.kmp.download.BackgroundDownloadStatus
import dev.shinsou.kmp.download.BackgroundDownloadWork
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps manga and novel downloads running while the app is in the background. Android 14 and
 * later run them as a user-initiated data transfer job ([AndroidDownloadJobService]): no time
 * cap, and the system reschedules it (starting the app if needed) after it was stopped. Older
 * versions use the dataSync foreground service ([AndroidDownloadService]).
 */
internal object AndroidBackgroundDownloads {
    private val mutableProgress = MutableStateFlow<BackgroundDownloadStatus?>(null)
    val progress: StateFlow<BackgroundDownloadStatus?> = mutableProgress

    /** Language of the activity that last published progress. */
    @Volatile
    var strings: ShinsouStrings = ShinsouStrings()
        private set

    /** Set by the app core: pauses the queues because the system is ending the background work. */
    @Volatile
    var systemStop: (() -> Unit)? = null

    private val run = java.util.concurrent.atomic.AtomicLong()

    /**
     * Marks a new run (the app came to the foreground, or the job started). A system stop that
     * was requested in an earlier run but has not applied yet is then dropped, so it cannot
     * pause the queues this run resumed.
     */
    fun newRun(): Long = run.incrementAndGet()

    fun currentRun(): Long = run.get()

    /** Starts the background work when downloads appear; it ends itself once the queue drains. */
    fun publish(context: Context, value: BackgroundDownloadStatus?, strings: ShinsouStrings) {
        this.strings = strings
        mutableProgress.value = value
        // Also retried on later updates if an earlier start was refused in the background.
        if (value != null) start(context.applicationContext)
    }

    /** Restarts the background work on app open if an earlier start was refused or it was stopped. */
    fun ensureRunning(context: Context) {
        if (mutableProgress.value != null) start(context.applicationContext)
    }

    private fun start(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            AndroidDownloadJobService.schedule(context)
        ) {
            return
        }
        AndroidDownloadService.start(context)
    }
}

/** The one ongoing download notification shared by the job and the foreground service. */
internal object AndroidDownloadNotification {
    const val ID = 2101
    private const val CHANNEL_ID = "downloads"

    fun build(context: Context, value: BackgroundDownloadStatus?): Notification {
        val strings = AndroidBackgroundDownloads.strings
        ensureChannel(context, strings)
        val shown = value ?: BackgroundDownloadStatus(0, 0, null)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(strings.text("Downloading {0} / {1}", shown.done, shown.total))
            .setContentText(summary(shown, strings))
            .setProgress(shown.total, shown.done, shown.total == 0)
            .apply { titles(shown, strings)?.let(::setStyle) }
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

    /** Collapsed line: the one title (and its chapter), or how many titles are downloading. */
    private fun summary(status: BackgroundDownloadStatus, strings: ShinsouStrings): String? {
        val works = status.works
        return when {
            works.size == 1 -> works.single().line()
            works.size > 1 -> strings.text("Downloading {0} titles", works.size)
            else -> status.current
        }
    }

    /** Expanded: one line per title, so several series are told apart. */
    private fun titles(status: BackgroundDownloadStatus, strings: ShinsouStrings): Notification.Style? {
        val works = status.works
        if (works.size < 2) {
            return works.singleOrNull()?.let { Notification.BigTextStyle().bigText(it.line()) }
        }
        return Notification.InboxStyle().apply {
            works.take(MAX_LINES).forEach { addLine(it.line()) }
            if (works.size > MAX_LINES) setSummaryText(strings.text("{0} more titles", works.size - MAX_LINES))
        }
    }

    private fun BackgroundDownloadWork.line(): String =
        listOfNotNull("$title  $done / $total", chapter).joinToString(" · ")

    private const val MAX_LINES = 5

    fun update(context: Context, value: BackgroundDownloadStatus) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(ID, build(context, value))
        }.onFailure { Log.w("ShinsouDownloads", "Could not update the download notification", it) }
    }

    private fun ensureChannel(context: Context, strings: ShinsouStrings) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, strings.downloads, NotificationManager.IMPORTANCE_LOW),
        )
    }
}
