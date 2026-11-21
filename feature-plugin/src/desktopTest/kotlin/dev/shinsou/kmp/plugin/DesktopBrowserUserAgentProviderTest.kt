package dev.shinsou.kmp.plugin

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopBrowserUserAgentProviderTest {
    /**
     * Release images once stripped JavaFX's reflectively loaded QuantumToolkit. The toolkit then
     * never started and `Platform.runLater` parked forever, so every catalogue request hung.
     */
    @Test
    fun hungBrowserProbeFallsBackInsteadOfBlockingRequests() = runBlocking {
        val neverReleased = CountDownLatch(1)
        val probes = AtomicInteger()
        val provider = DesktopBrowserUserAgentProvider(
            readBrowserUserAgent = {
                probes.incrementAndGet()
                neverReleased.await()
                "unreachable"
            },
            probeTimeoutMillis = 200,
        )

        val first = withTimeout(5.seconds) { provider.userAgent("example.com") }
        assertTrue(first.isNotBlank())
        assertTrue("unreachable" != first)

        // Later requests must not each wait for the timeout again, nor start another probe.
        val started = TimeSource.Monotonic.markNow()
        repeat(5) { assertEquals(first, provider.userAgent("example.com")) }
        assertTrue(started.elapsedNow() < 1.seconds)
        assertEquals(1, probes.get())
        neverReleased.countDown()
    }

    @Test
    fun lateBrowserProbeIsUsedAfterTheInitialTimeout() = runBlocking {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val provider = DesktopBrowserUserAgentProvider(
            readBrowserUserAgent = {
                release.await()
                "Mozilla/5.0 Late JavaFX".also { finished.countDown() }
            },
            probeTimeoutMillis = 100,
        )
        assertTrue("Mozilla/5.0 Late JavaFX" != provider.userAgent("example.com"))
        release.countDown()
        finished.await()
        // Completion races the future's own completion by a few instructions; retry briefly.
        val late = withTimeout(5.seconds) {
            var value = provider.userAgent("example.com")
            while (value != "Mozilla/5.0 Late JavaFX") {
                delay(10)
                value = provider.userAgent("example.com")
            }
            value
        }
        assertEquals("Mozilla/5.0 Late JavaFX", late)
    }

    @Test
    fun concurrentFirstRequestsShareOneProbe() = runBlocking {
        val probes = AtomicInteger()
        val provider = DesktopBrowserUserAgentProvider(
            readBrowserUserAgent = {
                probes.incrementAndGet()
                Thread.sleep(50)
                "Mozilla/5.0 JavaFX"
            },
            probeTimeoutMillis = 5_000,
        )
        val results = coroutineScope {
            (1..16).map { async(Dispatchers.Default) { provider.userAgent("example.com") } }.awaitAll()
        }
        assertTrue(results.all { it == "Mozilla/5.0 JavaFX" })
        assertEquals(1, probes.get())
    }

    @Test
    fun completedBrowserProbeIsUsed() = runBlocking {
        val provider = DesktopBrowserUserAgentProvider(
            readBrowserUserAgent = { "Mozilla/5.0 JavaFX" },
            probeTimeoutMillis = 5_000,
        )
        assertEquals("Mozilla/5.0 JavaFX", provider.userAgent("example.com"))
    }

    @Test
    fun failingBrowserProbeFallsBack() = runBlocking {
        val provider = DesktopBrowserUserAgentProvider(
            readBrowserUserAgent = { error("JavaFX toolkit is unavailable") },
            probeTimeoutMillis = 5_000,
        )
        assertTrue(provider.userAgent("example.com").startsWith("Mozilla/5.0"))
    }
}
