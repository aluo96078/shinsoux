package dev.shinsou.kmp

import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.ui.BrowseCallbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * iOS has no foreground service, so manga and novel downloads get the background time the system grants an
 * app that leaves the screen (usually about 30 seconds). When that runs out the queue is paused
 * cleanly instead of being frozen mid-request, and it resumes when the app returns. Under
 * LiveContainer this is the host's UIApplication, which grants the same background time.
 */
internal class IosDownloadBackgroundKeeper(
    private val browse: BrowseCallbacks,
    private val mangaQueue: StateFlow<List<DownloadQueueItem>>,
    private val pauseManga: suspend (Boolean) -> Unit,
    private val scope: CoroutineScope,
) {
    private val foreground = MutableStateFlow(true)
    private var taskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    /** Serializes system pauses and resumes; both re-check visibility once they hold it. */
    private val transitions = Mutex()
    private var novelsPausedBySystem = false
    private var mangaPausedBySystem = false

    fun start() {
        scope.launch {
            combine(foreground, browse.extensionDownloadsV2, mangaQueue) { visible, novels, manga ->
                !visible && (novels.any { it.state.pending() } || manga.any { it.state.pending() })
            }
                .distinctUntilChanged()
                .collect { needsTime -> withContext(Dispatchers.Main) { if (needsTime) begin() else end() } }
        }
    }

    fun setForeground(visible: Boolean) {
        foreground.value = visible
        if (!visible) return
        scope.launch {
            transitions.withLock {
                if (!foreground.value) return@withLock
                // Only queues this keeper paused are resumed, so a pause the user chose stays.
                if (novelsPausedBySystem) {
                    novelsPausedBySystem = false
                    runCatching { browse.pauseExtensionDownloadsV2(false) }
                }
                if (mangaPausedBySystem) {
                    mangaPausedBySystem = false
                    runCatching { pauseManga(false) }
                }
            }
        }
    }

    private fun begin() {
        if (taskId != UIBackgroundTaskInvalid) return
        taskId = UIApplication.sharedApplication.beginBackgroundTaskWithName("shinsou-downloads") {
            // Called on the main thread just before suspension.
            scope.launch {
                transitions.withLock {
                    // Skipped when the app came back before this ran.
                    if (!foreground.value) {
                        if (browse.extensionDownloadsV2.value.any { it.state.pending() }) {
                            novelsPausedBySystem = true
                            runCatching { browse.pauseExtensionDownloadsV2(true) }
                        }
                        if (mangaQueue.value.any { it.state.pending() }) {
                            mangaPausedBySystem = true
                            runCatching { pauseManga(true) }
                        }
                    }
                }
                withContext(Dispatchers.Main) { end() }
            }
        }
    }

    private fun end() {
        if (taskId == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(taskId)
        taskId = UIBackgroundTaskInvalid
    }
}

private fun DownloadState.pending() = this == DownloadState.QUEUED || this == DownloadState.DOWNLOADING
