package dev.shinsou.kmp.ui

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.ui.i18n.StringKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How a pane runs what the user asked for; a failure is reported in the snackbar.
 *
 * Library, content and download stores are written off the UI thread: a repository mutation runs
 * the sync dirty tracker's SQL before it returns, and the content and download stores are SQL and
 * file backed (R3-4, [dev.shinsou.kmp.content.sql.ContentSqlThreadGuard]). Platform pickers, sheets,
 * navigation and screen state stay on the UI thread.
 */
internal interface UserActions {
    /** Runs [block] on the UI thread: platform pickers and sheets, navigation, screen state. */
    fun onUi(block: suspend () -> Unit)

    /** Runs [block] off the UI thread, after the store actions started before it. */
    fun inStores(block: suspend () -> Unit)

    /** From an [onUi] action: runs [block] off the UI thread, in the same order as [inStores]. */
    suspend fun <T> stores(block: suspend () -> T): T
}

/**
 * [UserActions] of the app shell. Store actions run one at a time on a serial view of the work
 * dispatcher, so they start in the order the user asked for them, exactly as they did when they
 * ran on the UI thread (a suspended action still lets the next one start).
 */
internal class ShellUserActions(private val environment: HolderEnvironment) : UserActions {
    private val storeDispatcher: CoroutineDispatcher = environment.workDispatcher.limitedParallelism(1)

    override fun onUi(block: suspend () -> Unit) {
        environment.scope.launch {
            runCatchingCancellable { block() }
                .onFailure { environment.messages.showFailure(it, StringKey.THE_OPERATION_COULD_NOT_BE_COMPLETED) }
        }
    }

    override fun inStores(block: suspend () -> Unit) = onUi { stores(block) }

    override suspend fun <T> stores(block: suspend () -> T): T = withContext(storeDispatcher) { block() }
}
