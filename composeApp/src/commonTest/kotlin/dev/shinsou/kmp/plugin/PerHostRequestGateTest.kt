package dev.shinsou.kmp.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PerHostRequestGateTest {
    @Test
    fun reservationDoesNotSerializeTheEntireNetworkResponse() = runTest {
        val gate = PerHostRequestGate(
            limits = PluginRateLimitProvider { PluginRateLimit(permits = 1, periodMillis = 0) },
        )
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            gate.run("example.test") {
                firstStarted.complete(Unit)
                releaseFirst.await()
                1
            }
        }

        runCurrent()
        firstStarted.await()
        val second = async { gate.run("example.test") { 2 } }
        try {
            // Both requests share the test scheduler, so completion proves the reservation mutex
            // was released before the first response block suspended. A wall-clock timeout here
            // raced the iOS worker startup and made this concurrency regression test flaky.
            runCurrent()
            assertTrue(second.isCompleted)
            assertEquals(2, second.await())
        } finally {
            releaseFirst.complete(Unit)
        }
        assertEquals(1, first.await())
    }
}
