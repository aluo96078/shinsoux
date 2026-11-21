package dev.shinsou.kmp.manga

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EfficiencyThreadTest {
    @Test
    fun aNativeCallThatFailsLeavesTheSaverThreadWorking() = runBlocking {
        val reported = CopyOnWriteArrayList<Throwable>()
        val dispatcher = efficiencyThreadDispatcher(onSetupFailure = { reported += it }) {
            throw UnsatisfiedLinkError("Unable to load library 'System'")
        }
        val (name, priority) = withContext(dispatcher) { Thread.currentThread().name to Thread.currentThread().priority }
        // in tests the coroutine debug agent appends " @coroutine#n" to the name
        assertTrue(name.startsWith("manga-saver"), name)
        assertEquals(Thread.MIN_PRIORITY, priority)
        assertEquals(1, reported.size)
        assertTrue(reported.single() is UnsatisfiedLinkError)
    }

    @Test
    fun aReporterThatFailsToReportDoesNotStopTheThreadEither() = runBlocking {
        val dispatcher = efficiencyThreadDispatcher(onSetupFailure = { error("no log") }) { error("refused") }
        assertEquals(42, withContext(dispatcher) { 42 })
    }

    @Test
    fun theRequestForThisSystemRunsOrFailsWithoutBreakingTheThread() = runBlocking {
        val dispatcher = efficiencyThreadDispatcher(onSetupFailure = {}, setup = efficiencyCoreRequest(System.getProperty("os.name").orEmpty()))
        assertEquals(42, withContext(dispatcher) { 42 })
    }

    @Test
    fun aSystemWithoutEfficiencyCoresAsksForNothing() {
        efficiencyCoreRequest("Linux")()
    }
}
