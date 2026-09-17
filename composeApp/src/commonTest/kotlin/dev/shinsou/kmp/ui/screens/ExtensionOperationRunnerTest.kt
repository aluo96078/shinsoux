package dev.shinsou.kmp.ui.screens

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionOperationRunnerTest {
    private val install = ExtensionOperation("test.plugin", "Installing…")

    @Test
    fun claimsImmediatelyRejectsRepeatedClicksAndWaitsForFrameBeforeWork() = runTest {
        val frame = CompletableDeferred<Unit>()
        var calls = 0
        val runner = ExtensionOperationRunner(this, StandardTestDispatcher(testScheduler)) { frame.await() }
        assertTrue(runner.start(install, work = { calls++ }, onError = { throw it }))
        assertEquals(install, runner.active)
        assertFalse(runner.start(install, work = { calls++ }, onError = { throw it }))
        runCurrent()
        assertEquals(0, calls)
        frame.complete(Unit)
        runCurrent()
        assertEquals(1, calls)
        assertNull(runner.active)
    }

    @Test
    fun performsWorkOnWorkerAndPublishesResultOnUiScope() = runTest {
        val delegate = StandardTestDispatcher(testScheduler, "extension-worker")
        var onWorker = false
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                delegate.dispatch(context, Runnable {
                    onWorker = true
                    try { block.run() } finally { onWorker = false }
                })
            }
        }
        val runner = ExtensionOperationRunner(this, worker) {}
        var delivered = false
        runner.start(
            install,
            work = {
                assertSame(worker, currentCoroutineContext()[ContinuationInterceptor])
                assertTrue(onWorker)
                "review"
            },
            onSuccess = { result ->
                assertEquals("review", result)
                assertFalse(onWorker)
                delivered = true
            },
            onError = { throw it },
        )
        runCurrent()
        assertTrue(delivered)
        assertNull(runner.active)
    }

    @Test
    fun clearsBusyBeforeSuspendingErrorFeedbackAndAllowsRetry() = runTest {
        val dismiss = CompletableDeferred<Unit>()
        val failure = IllegalStateException("install failed")
        var seen: Throwable? = null
        val runner = ExtensionOperationRunner(this, StandardTestDispatcher(testScheduler)) {}
        runner.start(install, work = { throw failure }, onError = {
            seen = it
            assertNull(runner.active)
            dismiss.await()
        })
        runCurrent()
        // Coroutine stack-trace recovery may copy exceptions across dispatcher boundaries.
        assertTrue(seen is IllegalStateException)
        assertEquals(failure.message, seen?.message)
        assertTrue(runner.start(install, work = {}, onError = { throw it }))
        runCurrent()
        dismiss.complete(Unit)
    }

    @Test
    fun leavingScreenDuringWorkClearsBusyAndDoesNotPublishOrShowFailure() = runTest {
        val screenJob = Job(coroutineContext[Job])
        val screenScope = CoroutineScope(coroutineContext + screenJob)
        val runner = ExtensionOperationRunner(screenScope, StandardTestDispatcher(testScheduler)) {}
        var published = false
        var failed = false
        runner.start(install, work = { awaitCancellation() }, onSuccess = { published = true }, onError = { failed = true })
        runCurrent()
        screenJob.cancelAndJoin()
        assertNull(runner.active)
        assertFalse(published)
        assertFalse(failed)
    }

    @Test
    fun cancellationBeforeFirstFrameAlsoReleasesClaim() = runTest {
        val screenJob = Job(coroutineContext[Job])
        val runner = ExtensionOperationRunner(
            CoroutineScope(coroutineContext + screenJob), StandardTestDispatcher(testScheduler),
        ) { awaitCancellation() }
        var started = false
        runner.start(install, work = { started = true }, onError = { throw it })
        screenJob.cancelAndJoin()
        assertNull(runner.active)
        assertFalse(started)
    }
}
