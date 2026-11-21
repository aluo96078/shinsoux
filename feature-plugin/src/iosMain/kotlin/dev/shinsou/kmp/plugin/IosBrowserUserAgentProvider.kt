package dev.shinsou.kmp.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.coroutines.resume

/** Reads the UA from WKWebView so proxy traffic matches the browser available on this device. */
public class IosBrowserUserAgentProvider : PluginUserAgentProvider {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var cached: String? = null

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun userAgent(host: String): String = mutex.withLock {
        cached ?: withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WKWebView(
                    frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                    configuration = WKWebViewConfiguration(),
                )
                webView.evaluateJavaScript("navigator.userAgent") { value, _ ->
                    val userAgent = normalizePluginUserAgent(value as? String)
                        ?: "Mozilla/5.0 (iPhone; CPU iPhone OS like Mac OS X) " +
                            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Safari/604.1"
                    cached = userAgent
                    if (continuation.isActive) continuation.resume(userAgent)
                }
            }
        }
    }
}
