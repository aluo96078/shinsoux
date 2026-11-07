package dev.shinsou.kmp

import dev.shinsou.kmp.download.BackgroundDownloadCoordinator
import dev.shinsou.kmp.download.BackgroundDownloadStatus
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.BackgroundTasks.BGContinuedProcessingTask
import platform.BackgroundTasks.BGContinuedProcessingTaskRequest
import platform.BackgroundTasks.BGContinuedProcessingTaskRequestSubmissionStrategy
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSBundle
import platform.Foundation.NSClassFromString
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateByAddingTimeInterval
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * Keeps manga and novel downloads going after the app leaves the screen, with whatever iOS allows:
 *
 * - iOS 26+: a continued-processing task, submitted while the app is on screen as soon as there
 *   is work. The system shows its progress and lets it run in the background until the queues
 *   drain or the system (or the user, from that progress UI) stops it.
 * - Every version, and whenever that task is not running: the background time UIKit grants an
 *   app that leaves the screen (usually about 30 seconds).
 * - When either runs out, the shared coordinator pauses the queues cleanly and a processing task
 *   is requested, so iOS can finish them later (typically overnight, idle and online) even if the
 *   app is not opened again. They also resume as soon as the app returns.
 *
 * Under LiveContainer the scheduler checks the host app's Info.plist and bundle id, not ours, so
 * both task kinds fail to register and only the UIKit background time is used.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosDownloadBackgroundKeeper(
    private val downloads: BackgroundDownloadCoordinator,
    private val scope: CoroutineScope,
    /** Starts the app core (idempotent) and waits until both queues are restored. */
    private val startApp: suspend () -> Unit,
    private val strings: () -> ShinsouStrings,
) {
    private val scheduler: BGTaskScheduler get() = BGTaskScheduler.sharedScheduler
    private val foreground = MutableStateFlow(true)
    /** False until the scene reports; a launch straight into a background task has no scene yet. */
    private var foregroundReported = false

    /** Continued-processing and catch-up runs now keeping the app alive; UIKit time is not needed then. */
    private val systemRuns = MutableStateFlow(0)

    // Main thread only.
    private var taskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    private var continuedPending = false
    private var continuedRunning = false

    private var continuedRegistered = false
    private var catchUpRegistered = false

    /** Must run before the app finishes launching; each identifier is registered at most once. */
    fun registerTasks() {
        val permitted = permittedIdentifiers()
        if (!catchUpRegistered && CATCH_UP_TASK_IDENTIFIER in permitted) {
            catchUpRegistered = scheduler.registerForTaskWithIdentifier(CATCH_UP_TASK_IDENTIFIER, usingQueue = null) { task ->
                task?.let(::runCatchUp)
            }
        }
        if (!continuedRegistered && continuedProcessingAvailable() && CONTINUED_TASK_IDENTIFIER in permitted) {
            continuedRegistered = scheduler.registerForTaskWithIdentifier(CONTINUED_TASK_IDENTIFIER, usingQueue = null) { task ->
                (task as? BGContinuedProcessingTask)?.let(::runContinued)
            }
        }
    }

    fun start() {
        scope.launch {
            combine(foreground, downloads.status, systemRuns) { visible, status, runs ->
                !visible && status != null && runs == 0
            }
                .distinctUntilChanged()
                .collect { needsTime -> withContext(Dispatchers.Main) { if (needsTime) begin() else end() } }
        }
        // A continued-processing task may only be submitted while the app is on screen.
        scope.launch {
            combine(foreground, downloads.status) { visible, status -> visible && status != null }
                .distinctUntilChanged()
                .collect { submit -> if (submit) withContext(Dispatchers.Main) { submitContinued() } }
        }
    }

    /** Resuming on return is done by ShinsouComposition.onForeground. */
    fun setForeground(visible: Boolean) {
        foregroundReported = true
        foreground.value = visible
        if (visible) {
            // Coming back resumes the paused work right away; no later run is needed for it.
            if (catchUpRegistered) scheduler.cancelTaskRequestWithIdentifier(CATCH_UP_TASK_IDENTIFIER)
        } else {
            // Work a stop in an earlier process paused is still waiting.
            scope.launch { requestCatchUpIfNeeded() }
        }
    }

    // UIKit background time ----------------------------------------------------------------------

    private fun begin() {
        if (taskId != UIBackgroundTaskInvalid) return
        taskId = UIApplication.sharedApplication.beginBackgroundTaskWithName("shinsou-downloads") {
            // Called on the main thread just before suspension; skipped if the app came back first.
            scope.launch {
                downloads.suspendForSystem(stillApplies = { !foreground.value })
                requestCatchUpIfNeeded()
                withContext(Dispatchers.Main) { end() }
            }
        }
    }

    private fun end() {
        if (taskId == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(taskId)
        taskId = UIBackgroundTaskInvalid
    }

    // Continued processing (iOS 26+) --------------------------------------------------------------

    private fun submitContinued() {
        if (!continuedRegistered || continuedPending || continuedRunning) return
        val status = downloads.currentStatus() ?: return
        val text = strings()
        val request = BGContinuedProcessingTaskRequest(
            identifier = CONTINUED_TASK_IDENTIFIER,
            title = text.text("Downloading {0} / {1}", status.done, status.total),
            subtitle = subtitle(status, text),
        ).apply {
            // Run now or not at all: queued for later it would start when the work may be over,
            // and the UIKit time already covers a refusal.
            strategy = BGContinuedProcessingTaskRequestSubmissionStrategy.BGContinuedProcessingTaskRequestSubmissionStrategyFail
        }
        continuedPending = submitRequest(request)
    }

    /** Called by the scheduler on a background queue. */
    private fun runContinued(task: BGContinuedProcessingTask) {
        val run = SystemRun(task)
        // Started only once finish() can cancel it: the queue may already be empty.
        val work = scope.launch(start = CoroutineStart.LAZY) {
            // Set here, so finish() (later in this coroutine, or after the expiration handler
            // has cancelled and joined it) always comes after.
            withContext(Dispatchers.Main) {
                continuedPending = false
                continuedRunning = true
            }
            // The queues themselves: a buffered copy could still hold an earlier "nothing to do".
            downloads.liveStatus.collect { status ->
                if (status == null) {
                    run.finish(success = true)
                } else {
                    report(task, status)
                }
            }
        }
        run.onFinish = { work.cancel() }
        task.expirationHandler = {
            scope.launch {
                work.cancelAndJoin()
                // Stopped from its progress UI while the app is on screen: the downloads go on.
                downloads.suspendForSystem(stillApplies = { !foreground.value })
                requestCatchUpIfNeeded()
                run.finish(success = false)
            }
        }
        work.start()
    }

    private suspend fun report(task: BGContinuedProcessingTask, status: BackgroundDownloadStatus) {
        val text = strings()
        withContext(Dispatchers.Main) {
            task.progress.totalUnitCount = status.total.toLong()
            task.progress.completedUnitCount = status.done.toLong()
            task.updateTitle(
                text.text("Downloading {0} / {1}", status.done, status.total),
                subtitle = subtitle(status, text),
            )
        }
    }

    /** The system UI has one line: the title being downloaded, or how many titles are. */
    private fun subtitle(status: BackgroundDownloadStatus, text: ShinsouStrings): String {
        val works = status.works
        return when {
            works.size == 1 -> works.single().let { work ->
                listOfNotNull("${work.title}  ${work.done} / ${work.total}", work.chapter).joinToString(" · ")
            }
            works.size > 1 -> text.text("Downloading {0} titles", works.size)
            else -> status.current ?: text.downloads
        }
    }

    // Catch-up processing task ---------------------------------------------------------------------

    private suspend fun requestCatchUpIfNeeded() {
        if (!catchUpRegistered || foreground.value) return
        // Work still waiting unpaused (its pause mark could not be saved) restarts on any launch,
        // a catch-up launch included, so it asks for one too.
        if (downloads.currentStatus() == null && !downloads.hasSystemPausedWork()) return
        val request = BGProcessingTaskRequest(CATCH_UP_TASK_IDENTIFIER).apply {
            requiresNetworkConnectivity = true
            requiresExternalPower = false
            earliestBeginDate = NSDate().dateByAddingTimeInterval(CATCH_UP_DELAY_SECONDS)
        }
        // Replaces any earlier request with this identifier.
        submitRequest(request)
    }

    /** Called by the scheduler on a background queue, possibly in a process launched just for it. */
    private fun runCatchUp(task: BGTask) {
        val run = SystemRun(task)
        val work = scope.launch(start = CoroutineStart.LAZY) {
            withContext(Dispatchers.Main) {
                if (!foregroundReported) {
                    foreground.value = UIApplication.sharedApplication.applicationState ==
                        UIApplicationState.UIApplicationStateActive
                }
            }
            val started = try {
                startApp()
                downloads.resumeAfterSystem()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            if (!started) {
                // Nothing could be resumed in this process; the marks stay for the next run.
                requestCatchUpIfNeeded()
                run.finish(success = false)
                return@launch
            }
            // Bounded by the task's own expiration, which pauses whatever is left.
            downloads.liveStatus.first { it == null }
            run.finish(success = true)
        }
        run.onFinish = { work.cancel() }
        task.expirationHandler = {
            scope.launch {
                work.cancelAndJoin()
                downloads.suspendForSystem(stillApplies = { !foreground.value })
                // Asks for the next run with whatever is still left.
                requestCatchUpIfNeeded()
                run.finish(success = false)
            }
        }
        work.start()
    }

    // Shared ---------------------------------------------------------------------------------------

    /** One scheduler task: counted while it runs, completed exactly once. */
    private inner class SystemRun(private val task: BGTask) {
        private var finished = false
        var onFinish: () -> Unit = {}

        init {
            systemRuns.update { it + 1 }
        }

        suspend fun finish(success: Boolean) = withContext(Dispatchers.Main) {
            if (finished) return@withContext
            finished = true
            task.expirationHandler = null
            onFinish()
            task.setTaskCompletedWithSuccess(success)
            // Both, even if the work never reached the point that set them (an early expiration).
            if (task is BGContinuedProcessingTask) {
                continuedPending = false
                continuedRunning = false
            }
            systemRuns.update { it - 1 }
        }
    }

    private fun submitRequest(request: platform.BackgroundTasks.BGTaskRequest): Boolean = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        scheduler.submitTaskRequest(request, error.ptr)
    }

    private fun permittedIdentifiers(): Set<String> =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("BGTaskSchedulerPermittedIdentifiers") as? List<*>)
            ?.filterIsInstance<String>()
            ?.toSet()
            .orEmpty()

    /** The class exists from iOS 26; looked up by name so earlier versions never touch it. */
    private fun continuedProcessingAvailable(): Boolean =
        NSClassFromString("BGContinuedProcessingTaskRequest") != null

    private companion object {
        const val CONTINUED_TASK_IDENTIFIER = "dev.aluo.shinsoux.downloads"
        const val CATCH_UP_TASK_IDENTIFIER = "dev.aluo.shinsoux.downloads.catchup"
        const val CATCH_UP_DELAY_SECONDS = 5.0 * 60.0
    }
}
