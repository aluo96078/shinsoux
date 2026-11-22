package dev.shinsou.kmp.plugin.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import dev.shinsou.kmp.plugin.model.PluginResourceLimitException

/** R4-5 (docs/architecture/0006): a host that keeps failing is refused for a while, not waited on. */
class PluginSourceFailureBackoffTest {
    private var now = 1_000_000L
    private val backoff = PluginSourceFailureBackoff(threshold = 2, baseMillis = 1_000, maxMillis = 4_000, nowEpochMillis = { now })
    private var calls = 0

    private suspend fun fail(sourceId: Long = 1, host: String = "api.example") =
        assertFailsWith<IllegalStateException> { backoff.run(sourceId, host) { calls++; error("connection reset") } }

    private suspend fun succeed(sourceId: Long = 1, host: String = "api.example") =
        backoff.run(sourceId, host) { calls++; "ok" }

    @Test
    fun afterTheThresholdTheRequestIsNotSent() = runBlocking {
        fail()
        fail()
        assertFailsWith<PluginSourceBackoffException> { succeed() }
        assertEquals(2, calls)
    }

    @Test
    fun theWindowEndsAndDoublesWhileFailuresContinue() = runBlocking {
        fail(); fail()
        now += 1_000
        fail() // the window ran out; this attempt went through and failed again
        now += 1_999
        assertFailsWith<PluginSourceBackoffException> { succeed() }
        now += 1
        assertEquals("ok", succeed())
        fail() // a success started the count again
        assertEquals("ok", succeed())
    }

    @Test
    fun anAnswerThatCannotBeReadCountsLikeNoAnswer() = runBlocking<Unit> {
        repeat(2) {
            assertFailsWith<PluginMalformedResponseException> {
                backoff.run(1, "api.example") { calls++; requireWellFormedResponse(false) { "Invalid plugin chunk framing" }; "ok" }
            }
        }
        assertFailsWith<PluginSourceBackoffException> { succeed() }
        assertEquals(2, calls)
    }

    @Test
    fun otherHostsAndSourcesAreNotAffected() = runBlocking {
        fail(); fail()
        assertEquals("ok", succeed(host = "cdn.example"))
        assertEquals("ok", succeed(sourceId = 2))
    }

    @Test
    fun aFullTableDropsAHostThatIsNotHeldBackFirst() = runBlocking<Unit> {
        val small = PluginSourceFailureBackoff(threshold = 2, baseMillis = 1_000, maxMillis = 4_000, maxEntries = 2, nowEpochMillis = { now })
        suspend fun failOn(host: String) = assertFailsWith<IllegalStateException> { small.run(1, host) { error("reset") } }
        failOn("held.example"); failOn("held.example") // held back
        failOn("once.example") // one failure, not held back
        failOn("new.example") // the table is full: once.example goes, not the older held.example
        assertFailsWith<PluginSourceBackoffException> { small.check(1, "held.example") }
    }

    @Test
    fun aRefusedAnswerIsNotAHostFailure() = runBlocking {
        repeat(3) {
            assertFailsWith<PluginResourceLimitException> {
                backoff.run(1, "api.example") { throw PluginResourceLimitException("too large") }
            }
        }
        // what the transports really throw for an answer over the limit
        repeat(3) {
            assertFailsWith<IllegalArgumentException> {
                backoff.run(1, "api.example") { require(false) { "Plugin response is too large" } }
            }
        }
        assertEquals("ok", succeed())
    }

    @Test
    fun requestsFailingTogetherOpenOnlyTheFirstWindow() = runBlocking {
        val dropped = CompletableDeferred<Unit>()
        val inFlight = List(8) { // eight images in flight when the connection dropped
            async {
                runCatching { backoff.run(1, "api.example") { dropped.await(); error("connection reset") } }
            }
        }
        yield()
        dropped.complete(Unit)
        inFlight.awaitAll()
        now += 1_000 // the base window, not one doubled six times
        fail()
        now += 1_999
        assertFailsWith<PluginSourceBackoffException> { succeed() }
        now += 1
        assertEquals("ok", succeed())
    }

    @Test
    fun aPlatformTimeoutCountsButTheCallerGivingUpDoesNot() = runBlocking<Unit> {
        repeat(2) {
            withTimeoutOrNull(10) { backoff.run(1, "api.example") { delay(1_000) } }
        }
        assertEquals("ok", succeed())
        repeat(2) {
            assertFailsWith<TimeoutCancellationException> {
                backoff.run(1, "api.example") { withTimeout(10) { delay(1_000) } }
            }
        }
        assertFailsWith<PluginSourceBackoffException> { succeed() }
    }
}
