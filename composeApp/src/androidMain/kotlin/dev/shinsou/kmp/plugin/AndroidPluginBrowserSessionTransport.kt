package dev.shinsou.kmp.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reviewed Bika uses pinned native HTTPS; the legacy unpinned WebView path is development-only. */
public class AndroidPluginBrowserSessionTransport(context: Context) : PluginBrowserSessionTransport {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val states = mutableMapOf<Long, AndroidBrowserSessionState>()
    private val pinnedTransport = requireNotNull(createPlatformPinnedPluginHttpTransport())
    private val userAgentProvider = AndroidBrowserUserAgentProvider(appContext)

    override suspend fun executeResolved(
        sourceId: Long,
        sourceOrigin: String,
        allowedOrigins: Set<String>,
        request: PluginHttpRequest,
        resolution: PluginHostResolution,
    ): PluginHttpResponse {
        val nativeRequest = bikaPinnedSessionRequest(
            sourceOrigin, allowedOrigins, request, resolution,
            userAgentProvider.userAgent(resolution.host),
        )
        return pinnedTransport.executeResolved(nativeRequest, resolution)
    }

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

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createState(sourceOrigin: String): AndroidBrowserSessionState =
        withContext(Dispatchers.Main.immediate) {
            val ready = CompletableDeferred<Unit>()
            val webView = WebView(appContext).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = false
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    safeBrowsingEnabled = true
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!ready.isCompleted) ready.complete(Unit)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame && !ready.isCompleted) {
                            ready.completeExceptionally(
                                IllegalStateException("Browser-session bootstrap failed"),
                            )
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler,
                        error: SslError?,
                    ) {
                        handler.cancel()
                        if (!ready.isCompleted) {
                            ready.completeExceptionally(
                                IllegalStateException("Browser-session TLS validation failed"),
                            )
                        }
                    }
                }
                // Establish the source security origin without running a remote bootstrap page.
                // A /robots.txt redirect to attacker HTML would otherwise execute before the
                // host-issued Fetch script and could scan local/private network resources.
                loadDataWithBaseURL(
                    sourceOrigin,
                    "<!doctype html><meta charset=\"utf-8\"><title>Shinsou browser session</title>",
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            AndroidBrowserSessionState(sourceOrigin, webView, ready)
        }

    override suspend fun close() {
        mutex.withLock {
            states.values.forEach { it.release() }
            states.clear()
        }
    }
}

private class AndroidBrowserSessionState(
    val sourceOrigin: String,
    val webView: WebView,
    val ready: CompletableDeferred<Unit>,
) {
    suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main.immediate) {
        val result = CompletableDeferred<String?>()
        webView.evaluateJavascript(script) { value -> result.complete(value) }
        result.await()
    }

    suspend fun release() = withContext(Dispatchers.Main.immediate) {
        if (!ready.isCompleted) ready.cancel()
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.destroy()
    }
}
