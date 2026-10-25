@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.shinsou.kmp.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlinx.cinterop.ObjCSignatureOverride

/** WKWebView transport used only for manifest-approved API origins. */
public class IosPluginBrowserSessionTransport : PluginBrowserSessionTransport {
    private val mutex = Mutex()
    private val states = mutableMapOf<Long, IosBrowserSessionState>()

    override suspend fun execute(
        sourceId: Long,
        sourceOrigin: String,
        allowedOrigins: Set<String>,
        request: PluginHttpRequest,
    ): PluginHttpResponse {
        val prepared = preparePluginBrowserSessionRequest(sourceOrigin, allowedOrigins, request)
        return mutex.withLock {
            val existing = states[sourceId]
            val state = if (existing?.sourceOrigin == prepared.sourceOrigin) {
                existing
            } else {
                existing?.release()
                createState(prepared.sourceOrigin).also { states[sourceId] = it }
            }
            state.ready.await()
            executePluginBrowserSessionFetch(prepared, state::evaluate)
        }
    }

    private suspend fun createState(sourceOrigin: String): IosBrowserSessionState =
        withContext(Dispatchers.Main) {
            val ready = CompletableDeferred<Unit>()
            val configuration = WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            }
            val webView = WKWebView(CGRectMake(0.0, 0.0, 1.0, 1.0), configuration)
            val delegate = IosBrowserSessionNavigationDelegate(ready)
            webView.navigationDelegate = delegate
            val baseUrl = requireNotNull(NSURL.URLWithString(sourceOrigin))
            // Establish the source security origin from a host-owned empty document. Loading a
            // remote robots.txt can redirect to executable HTML before the bounded Fetch script.
            webView.loadHTMLString(
                "<!doctype html><meta charset=\"utf-8\"><title>Shinsou browser session</title>",
                baseURL = baseUrl,
            )
            IosBrowserSessionState(sourceOrigin, webView, delegate, ready)
        }

    override suspend fun close() {
        mutex.withLock {
            states.values.forEach { it.release() }
            states.clear()
        }
    }
}

private class IosBrowserSessionState(
    val sourceOrigin: String,
    val webView: WKWebView,
    @Suppress("unused") val delegate: IosBrowserSessionNavigationDelegate,
    val ready: CompletableDeferred<Unit>,
) {
    suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()
        webView.evaluateJavaScript(script) { value, error ->
            if (error != null) result.completeExceptionally(IllegalStateException("Browser-session JavaScript failed"))
            else result.complete(value as? String)
        }
        result.await()
    }

    suspend fun release() = withContext(Dispatchers.Main) {
        if (!ready.isCompleted) ready.cancel()
        webView.stopLoading()
        webView.navigationDelegate = null
    }
}

private class IosBrowserSessionNavigationDelegate(
    private val ready: CompletableDeferred<Unit>,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (!ready.isCompleted) ready.complete(Unit)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        fail()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        fail()
    }

    private fun fail() {
        if (!ready.isCompleted) {
            ready.completeExceptionally(IllegalStateException("Browser-session bootstrap failed"))
        }
    }
}
