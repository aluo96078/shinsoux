package dev.shinsou.kmp.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

class ShellUserActionsTest {
    /** A work dispatcher that remembers it ran something, on the test scheduler. */
    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatched = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            delegate.dispatch(context, block)
        }
    }

    @Test
    fun storeActionsRunOnTheWorkDispatcherInTheOrderAsked() = runTest {
        val work = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val messages = mutableListOf<String>()
        val actions = ShellUserActions(
            HolderEnvironment(this, AppMessages({ dev.shinsou.kmp.ui.i18n.shinsouStringsFor("en") }) { messages += it }, work),
        )
        val order = mutableListOf<Int>()
        repeat(5) { index -> actions.inStores { order += index } }
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3, 4), order)
        // Every action went through the work dispatcher (its serial view batches them).
        assertEquals(true, work.dispatched > 0)
        assertEquals(emptyList(), messages)
    }

    @Test
    fun failuresAreReportedAndUiActionsCanHopToTheStores() = runTest {
        val work = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val messages = mutableListOf<String>()
        val actions = ShellUserActions(
            HolderEnvironment(this, AppMessages({ dev.shinsou.kmp.ui.i18n.shinsouStringsFor("en") }) { messages += it }, work),
        )
        actions.inStores { error("store broke") }
        var result = 0
        actions.onUi { result = actions.stores { 41 } + 1 }
        advanceUntilIdle()
        assertEquals(listOf("store broke"), messages)
        assertEquals(42, result)
    }
}
