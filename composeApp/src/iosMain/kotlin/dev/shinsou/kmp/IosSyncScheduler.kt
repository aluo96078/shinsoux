package dev.shinsou.kmp

import dev.shinsou.kmp.sync.v3.SyncBackgroundResult
import dev.shinsou.kmp.sync.v3.SyncV3Service
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateByAddingTimeInterval

/**
 * Periodic sync through an iOS app-refresh task. `earliestBeginDate` is only a hint; the system
 * decides when (and whether) the task runs, so foreground sync stays the primary path.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosSyncScheduler(
    private val sync: SyncV3Service?,
    private val scope: CoroutineScope,
    /** Cold launches by the system have not started the app graph yet. */
    private val startApp: suspend () -> Unit,
) {
    private val scheduler: BGTaskScheduler get() = BGTaskScheduler.sharedScheduler
    private var registered = false

    fun register(): Boolean {
        if (sync == null) return false
        if (registered) return true
        registered = scheduler.registerForTaskWithIdentifier(
            identifier = IOS_SYNC_TASK_IDENTIFIER,
            usingQueue = null,
        ) { task -> task?.let(::runTask) }
        return registered
    }

    /** Submits the next refresh request; safe to call repeatedly. */
    fun schedule(): Boolean {
        if (sync == null) return false
        val request = BGAppRefreshTaskRequest(IOS_SYNC_TASK_IDENTIFIER).apply {
            earliestBeginDate = NSDate().dateByAddingTimeInterval(SYNC_INTERVAL_SECONDS)
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
                startApp()
                succeeded = (sync?.runBackgroundSync() ?: SyncBackgroundResult.Completed) == SyncBackgroundResult.Completed
            } finally {
                task.expirationHandler = null
                task.setTaskCompletedWithSuccess(succeeded)
                schedule()
            }
        }
    }
}

internal const val IOS_SYNC_TASK_IDENTIFIER: String = "dev.aluo.shinsoux.sync"
private const val SYNC_INTERVAL_SECONDS = 30.0 * 60.0
