package dev.shinsou.kmp.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun idleHostStatesAreEvictedInsteadOfPermanentlyExhaustingTheGate() = runTest {
        val gate = PerHostRequestGate(
            limits = PluginRateLimitProvider { PluginRateLimit(permits = 1, periodMillis = 0) },
            maxHostStates = 2,
        )

        assertEquals("a", gate.run("a.test") { "a" })
        assertEquals("b", gate.run("b.test") { "b" })
        assertEquals("c", gate.run("c.test") { "c" })
        assertEquals("a-again", gate.run("a.test") { "a-again" })
    }

    @Test
    fun hostStateWithAnActiveReservationIsNeverEvicted() = runTest {
        val releaseDelay = CompletableDeferred<Unit>()
        val delayStarted = CompletableDeferred<Unit>()
        var now = 0L
        val gate = PerHostRequestGate(
            limits = PluginRateLimitProvider { PluginRateLimit(permits = 1, periodMillis = 1_000) },
            nowEpochMillis = { now },
            delayMillis = {
                delayStarted.complete(Unit)
                releaseDelay.await()
            },
            maxHostStates = 1,
        )
        gate.run("active.test") { Unit }
        val active = async { gate.run("active.test") { "done" } }
        runCurrent()
        delayStarted.await()

        assertFailsWith<IllegalStateException> { gate.run("other.test") { Unit } }
        releaseDelay.complete(Unit)
        assertEquals("done", active.await())
        now = 10_000L
        assertEquals("other", gate.run("other.test") { "other" })
    }

    @Test
    fun idleHostAllowsABurstButKeepsTheSustainedRate() = runTest {
        var now = 1_000L
        val delays = mutableListOf<Long>()
        val gate = PerHostRequestGate(
            limits = PluginRateLimitProvider { PluginRateLimit(permits = 5, periodMillis = 1_000) },
            nowEpochMillis = { now },
            delayMillis = { wait ->
                delays += wait
                now += wait
            },
        )

        // A visible grid's first covers start together instead of one every 200 ms.
        repeat(5) { gate.run("cdn.example") { Unit } }
        assertEquals(emptyList(), delays)
        repeat(3) { gate.run("cdn.example") { Unit } }
        assertEquals(listOf(200L, 200L, 200L), delays)

        // The burst allowance refills only at the configured rate.
        now += 400
        delays.clear()
        repeat(3) { gate.run("cdn.example") { Unit } }
        assertEquals(listOf(200L), delays)
    }

    @Test
    fun periodShorterThanThePermitCountStillLimitsTheRate() = runTest {
        var now = 1_000L
        val delays = mutableListOf<Long>()
        val gate = PerHostRequestGate(
            limits = PluginRateLimitProvider { PluginRateLimit(permits = 5, periodMillis = 1) },
            nowEpochMillis = { now },
            delayMillis = { wait ->
                delays += wait
                now += wait
            },
        )

        // Floor division made the spacing zero, so every request started at once forever.
        repeat(8) { gate.run("cdn.example") { Unit } }

        assertEquals(listOf(1L, 1L, 1L), delays)
    }

    @Test
    fun zeroPeriodRemainsUnlimited() = runTest {
        val delays = mutableListOf<Long>()
        val gate = PerHostRequestGate(
            limits = PluginRateLimitProvider { PluginRateLimit(permits = 5, periodMillis = 0) },
            nowEpochMillis = { 1_000L },
            delayMillis = { delays += it },
        )

        repeat(20) { gate.run("cdn.example") { Unit } }

        assertEquals(emptyList(), delays)
    }

    @Test
    fun ratePeriodsThatCouldOverflowTheScheduleAreRejected() {
        assertFailsWith<IllegalArgumentException> { PluginRateLimit(permits = 2, periodMillis = Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> {
            PluginRateLimit(permits = 1, periodMillis = PluginRateLimit.MAX_PERIOD_MILLIS + 1)
        }
        assertFailsWith<IllegalArgumentException> { PluginRateLimit(permits = 1, periodMillis = -1) }
    }

    @Test
    fun longestAllowedPeriodWithManyPermitsStillSchedulesCorrectly() = runTest {
        var now = 1_800_000_000_000L
        val delays = mutableListOf<Long>()
        val gate = PerHostRequestGate(
            limits = PluginRateLimitProvider {
                PluginRateLimit(permits = Int.MAX_VALUE, periodMillis = PluginRateLimit.MAX_PERIOD_MILLIS)
            },
            nowEpochMillis = { now },
            delayMillis = { wait ->
                delays += wait
                now += wait
            },
        )

        repeat(10) { gate.run("cdn.example") { Unit } }

        // The burst allowance is huge but finite; nothing wrapped into a negative or giant wait.
        assertEquals(emptyList(), delays)
    }
}
