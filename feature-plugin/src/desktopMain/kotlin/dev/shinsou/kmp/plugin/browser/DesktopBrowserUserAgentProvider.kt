package dev.shinsou.kmp.plugin.browser

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.web.WebView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.plugin.network.PluginUserAgentProvider
import dev.shinsou.kmp.plugin.network.normalizePluginUserAgent

/** Reads the UA from the JavaFX WebKit runtime bundled with the desktop application. */
public class DesktopBrowserUserAgentProvider internal constructor(
    private val readBrowserUserAgent: () -> String?,
    private val probeTimeoutMillis: Long = JAVAFX_PROBE_TIMEOUT_MILLIS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PluginUserAgentProvider {
    public constructor() : this(::readJavaFxUserAgent)

    private val probeExpired = AtomicBoolean(false)

    /**
     * One probe per process, on its own daemon thread. If the JavaFX toolkit fails to start,
     * `Platform.runLater` parks forever in `PlatformImpl.waitForStart` before any caller-side
     * timeout is reached; that thread must never be a plugin request thread.
     */
    private val probe: CompletableFuture<String?> by lazy {
        CompletableFuture<String?>().also { future ->
            Thread(
                { future.complete(runCatching(readBrowserUserAgent).getOrNull()) },
                "shinsou-javafx-user-agent",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    override suspend fun userAgent(host: String): String = withContext(ioDispatcher) {
        browserUserAgent() ?: fallbackUserAgent()
    }

    private fun browserUserAgent(): String? {
        // After one timeout, later requests only take a probe that has since completed.
        if (probeExpired.get()) return probe.getNow(null)
        return try {
            probe.get(probeTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // A probe that never returns is expected; the fallback user agent is used.
            probeExpired.set(true)
            null
        }
    }

    private fun fallbackUserAgent(): String =
        System.getProperty("http.agent").orEmpty().trim().takeIf(String::isNotEmpty)
            ?: "Mozilla/5.0 (Desktop) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36"

    private companion object {
        const val JAVAFX_PROBE_TIMEOUT_MILLIS = 5_000L
    }
}

private fun readJavaFxUserAgent(): String? {
    JFXPanel()
    val result = AtomicReference<String?>(null)
    val completed = CountDownLatch(1)
    Platform.runLater {
        try {
            result.set(normalizePluginUserAgent(WebView().engine.userAgent))
        } finally {
            completed.countDown()
        }
    }
    completed.await(5, TimeUnit.SECONDS)
    return result.get()
}
