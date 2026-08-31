package dev.shinsou.kmp.plugin

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.web.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the UA from the JavaFX WebKit runtime bundled with the desktop application. */
public class DesktopBrowserUserAgentProvider : PluginUserAgentProvider {
    private val initialized = AtomicBoolean(false)
    private val cached = AtomicReference<String?>(null)

    override suspend fun userAgent(host: String): String = cached.get() ?: withContext(Dispatchers.IO) {
        cached.get() ?: readJavaFxUserAgent().also { cached.compareAndSet(null, it) }
    }

    private fun readJavaFxUserAgent(): String {
        if (initialized.compareAndSet(false, true)) JFXPanel()
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
            ?: System.getProperty("http.agent").orEmpty().trim().takeIf(String::isNotEmpty)
            ?: "Mozilla/5.0 (Desktop) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36"
    }
}
