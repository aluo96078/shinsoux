package dev.shinsou.kmp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.shinsou.kmp.backup.AutoBackupRunResult
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.backup.nextAutoBackupAt
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.BackupState
import dev.shinsou.kmp.files.AndroidAppFileSystem
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Process-wide state shared by the Activity and WorkManager worker.
 *
 * Sharing one repository avoids a background worker persisting a stale full snapshot over changes
 * made by a foreground Activity in the same application process.
 */
internal class AndroidSharedState private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val persistence = AndroidSnapshotPersistence(applicationContext)

    val repository: ShinsouRepository = ShinsouRepository(persistence.load(), persistence::save)
    val fileSystem: AndroidAppFileSystem = AndroidAppFileSystem(applicationContext)
    val autoBackups: AutoBackupService = AutoBackupService(
        repository = repository,
        fileSystem = fileSystem,
        appVersion = ANDROID_APP_VERSION,
    )

    companion object {
        @Volatile
        private var instance: AndroidSharedState? = null

        fun get(context: Context): AndroidSharedState = instance ?: synchronized(this) {
            instance ?: AndroidSharedState(context).also { instance = it }
        }
    }
}

/** WorkManager owns exact execution timing; local snapshots do not require power or network. */
internal object AndroidAutoBackupScheduler {
    fun apply(context: Context, state: BackupState) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!state.automaticEnabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val now = System.currentTimeMillis()
        val next = nextAutoBackupAt(state)
        val initialDelay = if (next == null || next <= now) 0L else next - now
        val request = PeriodicWorkRequestBuilder<AndroidAutoBackupWorker>(
            state.intervalHours.coerceAtLeast(1).toLong(),
            TimeUnit.HOURS,
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private const val UNIQUE_WORK_NAME = "shinsou-automatic-backup"
    private const val WORK_TAG = "automatic-backup"
}

/** Default WorkManager construction is sufficient; no Activity or UI object is retained. */
internal class AndroidAutoBackupWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        return try {
            when (AndroidSharedState.get(applicationContext).autoBackups.runIfDue()) {
                is AutoBackupRunResult.Failed -> Result.retry()
                AutoBackupRunResult.Disabled,
                is AutoBackupRunResult.NotDue,
                is AutoBackupRunResult.Created,
                -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}

private const val ANDROID_APP_VERSION = "1.0.1-beta.2"
