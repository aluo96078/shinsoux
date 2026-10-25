package dev.shinsou.kmp.plugin

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.web.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the UA from the JavaFX WebKit runtime bundled with the desktop application. */
public class DesktopBrowserUserAgentProvider internal constructor(
    private val readBrowserUserAgent: () -> String?,
    private val probeTimeoutMillis: Long = JAVAFX_PROBE_TIMEOUT_MILLIS,
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

    override suspend fun userAgent(host: String): String = withContext(Dispatchers.IO) {
        browserUserAgent() ?: fallbackUserAgent()
    }

    private fun browserUserAgent(): String? {
        // After one timeout, later requests only take a probe that has since completed.
        if (probeExpired.get()) return probe.getNow(null)
        return try {
            probe.get(probeTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
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
