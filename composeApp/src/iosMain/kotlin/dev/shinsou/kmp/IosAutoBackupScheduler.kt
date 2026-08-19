package dev.shinsou.kmp

import dev.shinsou.kmp.backup.AutoBackupRunResult
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.backup.nextAutoBackupAt
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.BackupState
import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSError
import platform.Foundation.NSDate
import platform.Foundation.dateByAddingTimeInterval

/**
 * Registers and submits the Info.plist-permitted iOS BGProcessing task.
 *
 * `earliestBeginDate` is only a hint. iOS decides whether and when the task actually runs.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAutoBackupScheduler(
    private val repository: ShinsouRepository,
    private val service: AutoBackupService,
    private val scope: CoroutineScope,
) {
    private val scheduler: BGTaskScheduler get() = BGTaskScheduler.sharedScheduler
    private var registered = false

    fun register(): Boolean {
        if (registered) return true
        val accepted = scheduler.registerForTaskWithIdentifier(
            identifier = IOS_AUTO_BACKUP_TASK_IDENTIFIER,
            usingQueue = null,
        ) { task ->
            task?.let(::runTask)
        }
        registered = accepted
        return accepted
    }

    fun apply(state: BackupState): Boolean {
        scheduler.cancelTaskRequestWithIdentifier(IOS_AUTO_BACKUP_TASK_IDENTIFIER)
        if (!state.automaticEnabled) return true

        val now = Clock.System.now().toEpochMilliseconds()
        val next = nextAutoBackupAt(state)
        val delayMillis = if (next == null || next <= now) MINIMUM_IOS_SCHEDULE_DELAY_MILLIS else next - now
        val request = BGProcessingTaskRequest(IOS_AUTO_BACKUP_TASK_IDENTIFIER).apply {
            requiresExternalPower = false
            requiresNetworkConnectivity = false
            earliestBeginDate = NSDate().dateByAddingTimeInterval(
                delayMillis.coerceAtLeast(MINIMUM_IOS_SCHEDULE_DELAY_MILLIS).toDouble() / 1_000.0,
            )
        }
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            scheduler.submitTaskRequest(request, error.ptr)
        }
    }

    private fun runTask(task: BGTask) {
        var operation: Job? = null
        task.expirationHandler = { operation?.cancel() }
        operation = scope.launch {
            var succeeded = false
            try {
                succeeded = service.runIfDue() !is AutoBackupRunResult.Failed
            } finally {
                task.expirationHandler = null
                task.setTaskCompletedWithSuccess(succeeded)
                apply(repository.currentSnapshot.backupState)
            }
        }
    }
}

internal const val IOS_AUTO_BACKUP_TASK_IDENTIFIER: String = "dev.aluo.shinsoux.backup"
private const val MINIMUM_IOS_SCHEDULE_DELAY_MILLIS = 60_000L
