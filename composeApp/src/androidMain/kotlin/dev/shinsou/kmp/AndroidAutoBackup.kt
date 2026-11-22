package dev.shinsou.kmp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.shinsou.kmp.app.checkBeforeOpening
import dev.shinsou.kmp.app.openShinsouRepository
import dev.shinsou.kmp.backup.AutoBackupRunResult
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.backup.nextAutoBackupAt
import dev.shinsou.kmp.content.schema.SchemaTooNewException
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.domain.model.BackupState
import dev.shinsou.kmp.files.AndroidAppFileSystem
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Process-wide state shared by the Activity and WorkManager worker.
 *
 * Sharing one repository avoids a background worker persisting a stale full snapshot over changes
 * made by a foreground Activity in the same application process.
 *
 * Made off the main thread (every caller is on an I/O or worker dispatcher): it checks the content
 * database and reads the snapshot with its hot rows from it (C3, docs/architecture/0007 §6.3).
 */
internal class AndroidSharedState private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val persistence = AndroidSnapshotPersistence(applicationContext)

    /** One content database for the Activity and the backup worker alike. */
    val contentInfrastructure: AndroidContentInfrastructure = AndroidContentInfrastructure(applicationContext)

    /**
     * Set when a newer Shinsou X wrote the content database (R3-1): the Activity asks for the
     * update and no worker opens the stores, so the newer data is left as it was. The repository
     * then keeps to the JSON alone.
     */
    val schemaTooNew: SchemaTooNewException? = contentInfrastructure.checkBeforeOpening()

    val repository: ShinsouRepository = openShinsouRepository(
        loaded = persistence.load(),
        file = persistence,
        contentInfrastructure = contentInfrastructure.takeIf { schemaTooNew == null },
        hotStateEnabled = !persistence.wholeJsonRequested(),
    )
    val fileSystem: AndroidAppFileSystem = AndroidAppFileSystem(applicationContext)
    val autoBackups: AutoBackupService = AutoBackupService(
        repository = repository,
        fileSystem = fileSystem,
        appVersion = ANDROID_APP_VERSION,
    )

    /**
     * A worker started in a fresh process has no app composition to bind translations to the
     * backup: without this its backups would silently leave glossaries and translations out.
     */
    /**
     * Writes the whole snapshot JSON once a piece of background work is over (C3): Android's cloud
     * backup and device transfer carry the JSON without the content database, so hot rows the
     * work wrote must not wait for the next foreground session to reach it. Never throws.
     */
    suspend fun checkpointSnapshot() {
        try {
            withContext(NonCancellable) { repository.flushPersistence(wholeSnapshot = true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Exception) {
            Log.warn("Storage", logged) { "Writing the snapshot after background work failed" }
        }
    }

    fun bindTranslationBackups() {
        if (autoBackups.translations != null) return
        autoBackups.translations = dev.shinsou.kmp.translation.TranslationMemoryBackups(
            dev.shinsou.kmp.translation.TranslationMemoryStore(contentInfrastructure.contentDriver()),
        )
    }

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
            val state = AndroidSharedState.get(applicationContext)
            if (state.schemaTooNew != null) return Result.failure()
            state.bindTranslationBackups()
            val result = when (state.autoBackups.runIfDue()) {
                is AutoBackupRunResult.Failed -> Result.retry()
                AutoBackupRunResult.Disabled,
                is AutoBackupRunResult.NotDue,
                is AutoBackupRunResult.Created,
                -> Result.success()
            }
            state.checkpointSnapshot()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Throwable) {
            Log.warn("Backup", logged) { "Automatic backup worker failed; scheduling a retry" }
            Result.retry()
        }
    }
}

internal const val ANDROID_APP_VERSION = dev.shinsou.kmp.buildinfo.SHINSOU_APP_VERSION
