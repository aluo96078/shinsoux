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
    /** The app's sync once its stores are open; null before, or where sync is unavailable. */
    private val sync: () -> SyncV3Service?,
    private val scope: CoroutineScope,
    /** Cold launches by the system have not started the app graph yet. */
    private val startApp: suspend () -> Unit,
    /** The stores can never open (a newer library): nothing is scheduled any more. */
    private val cannotOpen: () -> Boolean = { false },
) {
    private val scheduler: BGTaskScheduler get() = BGTaskScheduler.sharedScheduler
    private var registered = false

    /** Registered at launch, before the stores are open: a run without sync completes at once. */
    fun register(): Boolean {
        if (registered) return true
        registered = scheduler.registerForTaskWithIdentifier(
            identifier = IOS_SYNC_TASK_IDENTIFIER,
            usingQueue = null,
        ) { task -> task?.let(::runTask) }
        return registered
    }

    /** A request that came before the stores were open; submitted again once they are. Main thread. */
    private var requestedBeforeOpen = false

    /**
     * Submits the next refresh request; safe to call repeatedly. Before the stores are open it is
     * submitted all the same, since a run waits for them ([startApp]): the scene may go to the
     * background and iOS suspend the app before they open, and nothing would submit it then.
     * [submitRequestFromBeforeOpen] renews it once they are open.
     */
    fun schedule(): Boolean {
        if (cannotOpen()) return false
        if (sync() == null) requestedBeforeOpen = true
        return submitRequest()
    }

    /** Submits the next refresh request to the system, whether or not the stores are open yet. */
    private fun submitRequest(): Boolean {
        val request = BGAppRefreshTaskRequest(IOS_SYNC_TASK_IDENTIFIER).apply {
            earliestBeginDate = NSDate().dateByAddingTimeInterval(SYNC_INTERVAL_SECONDS)
        }
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            scheduler.submitTaskRequest(request, error.ptr)
        }
    }

    /** The stores are open: submits the request the scene asked for while they were opening. */
    fun submitRequestFromBeforeOpen() {
        if (!requestedBeforeOpen) return
        requestedBeforeOpen = false
        schedule()
    }

    private fun runTask(task: BGTask) {
        var operation: Job? = null
        task.expirationHandler = { operation?.cancel() }
        operation = scope.launch {
            var succeeded = false
            try {
                startApp()
                succeeded = (sync()?.runBackgroundSync() ?: SyncBackgroundResult.Completed) == SyncBackgroundResult.Completed
            } finally {
                task.expirationHandler = null
                try {
                    // This run came from a request submitted while sync was available. The next one
                    // is submitted even when startup did not finish (the run expired first), and
                    // before this run reports completion: iOS may suspend the app as soon as it
                    // does, and nothing would submit it then. A run that could never do anything
                    // (a newer library) is not asked for again.
                    if (!cannotOpen()) submitRequest()
                } finally {
                    task.setTaskCompletedWithSuccess(succeeded)
                }
            }
        }
    }
}

internal const val IOS_SYNC_TASK_IDENTIFIER: String = "dev.aluo.shinsoux.sync"
private const val SYNC_INTERVAL_SECONDS = 30.0 * 60.0
