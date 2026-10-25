package dev.shinsou.kmp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ExtensionOperation(val extensionId: String, val labelKey: String)

/** UI-owned lifecycle: claim immediately, work off-thread, publish results on the UI scope. */
internal class ExtensionOperationRunner(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val beforeWork: suspend () -> Unit,
) {
    var active: ExtensionOperation? by mutableStateOf(null)
        private set

    fun <T> start(
        operation: ExtensionOperation,
        work: suspend () -> T,
        onSuccess: (T) -> Unit = {},
        onError: suspend (Throwable) -> Unit,
    ): Boolean {
        if (active != null) return false
        active = operation
        // Enter try/finally before the first suspension, even if the screen is leaving.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failure: Throwable? = null
            try {
                beforeWork()
                val result = withContext(workerDispatcher) { work() }
                onSuccess(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            } finally {
                active = null
            }
            // A snackbar may suspend until dismissed; it must not keep controls disabled.
            failure?.let { onError(it) }
        }
        return true
    }
}
