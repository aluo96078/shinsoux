package dev.shinsou.kmp

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.shinsou.kmp.sync.v3.SyncBackgroundResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Periodic background sync (sync v3). It reuses the process-wide composition instead of building a
 * second stack, so it shares the repository, the sync database and the single dirty-tracking
 * observer with the foreground UI.
 */
internal object AndroidSyncScheduler {
    /** Idempotent: keeps the existing schedule. The worker is a no-op while signed out. */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<AndroidSyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private const val UNIQUE_WORK_NAME = "shinsou-sync-v3"
    private const val WORK_TAG = "sync-v3"
    private const val SYNC_INTERVAL_MINUTES = 30L
}

internal class AndroidSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        return try {
            val runtime = AndroidAppRuntime.get(applicationContext)
            // Installs the dirty tracker and restores the session when the process was started
            // by the system rather than by an Activity.
            runtime.start()
            runtime.composition.awaitStartup()
            val sync = runtime.composition.sync ?: return Result.success()
            when (sync.runBackgroundSync()) {
                SyncBackgroundResult.Completed -> Result.success()
                SyncBackgroundResult.RetryLater -> retryOrGiveUp()
                // An expired session or a rejected request does not fix itself: retrying with
                // backoff would only drain the battery. Periodic work runs again next period.
                SyncBackgroundResult.Failed -> Result.failure()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Startup failed for an unknown reason; allow a few attempts, never an endless loop.
            retryOrGiveUp()
        }
    }

    private fun retryOrGiveUp(): Result =
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()

    private companion object {
        const val MAX_RETRIES = 3
    }
}
