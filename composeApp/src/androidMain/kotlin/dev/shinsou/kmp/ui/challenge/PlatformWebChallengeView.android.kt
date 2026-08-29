package dev.shinsou.kmp.ui.challenge

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeRequest

internal actual val platformWebChallengeMode: PlatformWebChallengeMode =
    PlatformWebChallengeMode.Embedded

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun PlatformWebChallengeView(
    request: SourceWebChallengeRequest,
    captureRequest: Int,
    onPageLoaded: () -> Unit,
    onSessionCaptured: (WebChallengeCapture) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val currentPageLoaded = rememberUpdatedState(onPageLoaded)
    val currentSessionCaptured = rememberUpdatedState(onSessionCaptured)
    val currentError = rememberUpdatedState(onError)
    val state = remember(request) {
        val cookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                if (request.userAgent.isNotBlank()) userAgentString = request.userAgent
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
        }
        AndroidChallengeState(webView, cookieManager)
    }

    LaunchedEffect(state, request) {
        val initial = webChallengeSeedCookies(request)
        state.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                currentPageLoaded.value.invoke()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, navigation: WebResourceRequest): Boolean {
                val scheme = navigation.url.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                currentError.value.invoke("Blocked unsupported navigation scheme: ${scheme.orEmpty()}")
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) currentError.value.invoke(error.description.toString())
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                handler.cancel()
                currentError.value.invoke("TLS certificate validation failed. The page was not opened.")
            }
        }
        state.prepareIsolatedSession(
            request = request,
            initialCookies = initial,
            onReady = { state.webView.loadUrl(request.url) },
            onError = { currentError.value.invoke(it) },
        )
    }

    LaunchedEffect(state, captureRequest) {
        if (captureRequest > 0) {
            state.cookieManager.flush()
            val cookies = state.cookies(request.url)
            currentSessionCaptured.value.invoke(
                WebChallengeCapture(cookies = cookies, userAgent = state.webView.settings.userAgentString.orEmpty()),
            )
        }
    }

    AndroidView(
        factory = { state.webView },
        modifier = modifier,
    )

    DisposableEffect(state) {
        onDispose {
            state.release()
        }
    }
}

private class AndroidChallengeState(
    val webView: WebView,
    val cookieManager: CookieManager,
) {
    private var released: Boolean = false

    fun cookies(requestUrl: String): List<SourceCookie> =
        parseWebViewCookieHeader(cookieManager.getCookie(requestUrl), requestUrl)

    fun prepareIsolatedSession(
        request: SourceWebChallengeRequest,
        initialCookies: List<SourceCookie>,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        cookieManager.removeAllCookies {
            if (released) return@removeAllCookies
            if (initialCookies.isEmpty()) {
                cookieManager.flush()
                onReady()
                return@removeAllCookies
            }
            var pending = initialCookies.size
            var failed = false
            initialCookies.forEach { cookie ->
                cookieManager.setCookie(request.url, webChallengeSetCookieValue(cookie)) { accepted ->
                    if (released) return@setCookie
                    if (!accepted) failed = true
                    pending -= 1
                    if (pending == 0) {
                        cookieManager.flush()
                        if (failed) onError("One or more existing source cookies could not be loaded.")
                        onReady()
                    }
                }
            }
        }
    }

    fun release() {
        released = true
        webView.stopLoading()
        webView.destroy()
    }
}
