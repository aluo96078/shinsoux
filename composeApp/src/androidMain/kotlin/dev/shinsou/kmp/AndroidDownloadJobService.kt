package dev.shinsou.kmp

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android 14+ user-initiated data transfer job that keeps downloads running in the background.
 * Unlike a dataSync foreground service it has no daily time cap, and when the system stops it
 * (network lost, process killed) the job is rescheduled; the rerun may be the first thing in a
 * new process, so it starts the app core itself and resumes the queues the stop paused.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class AndroidDownloadJobService : JobService() {
    private var scope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = true
        AndroidBackgroundDownloads.newRun()
        setNotification(
            params,
            AndroidDownloadNotification.ID,
            AndroidDownloadNotification.build(this, AndroidBackgroundDownloads.progress.value),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        jobScope.launch {
            val runtime = AndroidAppRuntime.get(applicationContext)
            try {
                withContext(Dispatchers.Default) {
                    runtime.start()
                    // Both queues are restored only once startup hydration has finished.
                    runtime.composition.awaitStartup()
                    runtime.composition.backgroundDownloads.resumeAfterSystem()
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "Could not start downloads for the background job", error)
            }
            // The queues themselves, not the published copy, which may still hold an earlier
            // "nothing to do" and would end the job right after it resumed the work.
            runtime.composition.backgroundDownloads.liveStatus.collect { value ->
                if (value == null) {
                    running = false
                    jobFinished(params, false)
                    jobScope.cancel()
                } else {
                    AndroidDownloadNotification.update(this@AndroidDownloadJobService, value)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running = false
        scope?.cancel()
        scope = null
        // Saved as a system pause, so the queues resume when this job reruns or the app returns.
        AndroidBackgroundDownloads.systemStop?.invoke()
        // Stopped from the Task Manager: the user asked for it to stop, so do not come back.
        return params.stopReason != JobParameters.STOP_REASON_USER
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShinsouDownloads"
        private const val JOB_ID = 2101

        @Volatile
        private var running = false

        /** True when the job is running or scheduled; false if the system refused it. */
        fun schedule(context: Context): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            if (running || scheduler.getPendingJob(JOB_ID) != null) return true
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, AndroidDownloadJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
                .build()
            return runCatching { scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS }
                // Only allowed while the app is visible; the caller falls back to the service.
                .onFailure { Log.w(TAG, "Could not schedule the download job", it) }
                .getOrDefault(false)
        }
    }
}
