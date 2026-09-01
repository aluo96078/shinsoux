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
import android.view.View
import android.view.ViewGroup
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
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
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
                view.evaluateJavascript(ANDROID_WEB_CHALLENGE_VIEWPORT_FIX, null)
                automaticWebChallengeLoginScript(request)?.let { script ->
                    view.evaluateJavascript(script, null)
                }
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
            onReady = { state.loadWhenMeasured(request.url) },
            onError = { currentError.value.invoke(it) },
        )
    }

    LaunchedEffect(state, captureRequest) {
        if (captureRequest > 0) {
            state.cookieManager.flush()
            val cookies = state.cookies(request.url)
            val captureScript = webChallengeLocalStorageCaptureScript(request)
            state.webView.evaluateJavascript(captureScript) { encoded ->
                val storage = decodeWebChallengeLocalStorageCapture(encoded, request.localStorageKeys)
                if (storage.error != null) {
                    currentError.value.invoke(storage.error)
                } else {
                    currentSessionCaptured.value.invoke(
                        WebChallengeCapture(
                            cookies = cookies,
                            userAgent = state.webView.settings.userAgentString.orEmpty(),
                            localStorage = storage.values,
                        ),
                    )
                }
            }
        }
    }

    AndroidView(
        factory = { state.webView },
        modifier = modifier,
        update = { state.resumePendingLoad() },
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
    private var pendingUrl: String? = null
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        openPendingUrlIfMeasured()
    }

    init {
        webView.addOnLayoutChangeListener(layoutListener)
    }

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

    /**
     * Loading a WebView while Compose still measures it at 0 x 0 can permanently leave Chromium's
     * viewport units at zero even after the native view becomes visible. Wait for the first real
     * Android layout before opening the challenge page.
     */
    fun loadWhenMeasured(url: String) {
        if (released) return
        pendingUrl = url
        resumePendingLoad()
    }

    fun resumePendingLoad() {
        if (released || pendingUrl == null) return
        webView.post { openPendingUrlIfMeasured() }
    }

    private fun openPendingUrlIfMeasured() {
        if (released || !webView.isAttachedToWindow || webView.width <= 0 || webView.height <= 0) return
        val url = pendingUrl ?: return
        pendingUrl = null
        webView.loadUrl(url)
    }

    fun release() {
        released = true
        pendingUrl = null
        webView.removeOnLayoutChangeListener(layoutListener)
        webView.stopLoading()
        webView.destroy()
    }
}

/**
 * Some Chromium WebView builds retain a zero layout-viewport after being hosted by a measured
 * Compose AndroidView. `innerHeight` remains correct, but vh/dvh/svh/lvh all resolve to 0 px. The
 * website DOM is then present yet every full-screen surface is invisible. Apply this compatibility
 * layer only after proving that exact engine defect, and only to document roots plus zero-height
 * elements that explicitly span both vertical insets.
 */
private val ANDROID_WEB_CHALLENGE_VIEWPORT_FIX = """
    (() => {
      const stateKey = "__shinsouViewportUnitCompatibility";
      const viewportHeight = () => Math.max(0, Math.round(window.visualViewport?.height || window.innerHeight || 0));
      const viewportUnitsAreBroken = () => {
        const expected = viewportHeight();
        if (expected < 2 || !document.body) return false;
        const probe = document.createElement("div");
        probe.style.cssText = "position:fixed;left:-10000px;top:0;width:1px;height:100vh;pointer-events:none";
        document.body.appendChild(probe);
        const measured = probe.getBoundingClientRect().height;
        probe.remove();
        return measured < 1;
      };
      if (!viewportUnitsAreBroken()) return "not-needed";
      const apply = () => {
        const height = viewportHeight();
        if (height < 2) return;
        document.documentElement.style.setProperty("min-height", height + "px", "important");
        if (document.body) document.body.style.setProperty("min-height", height + "px", "important");
        for (const selector of ["#root", "#app", "#__next", "#__nuxt"]) {
          const root = document.querySelector(selector);
          if (root) root.style.setProperty("min-height", height + "px", "important");
        }
        for (const element of document.querySelectorAll("body *")) {
          const style = getComputedStyle(element);
          const rect = element.getBoundingClientRect();
          if ((style.position === "fixed" || style.position === "absolute") &&
              style.top !== "auto" && style.bottom !== "auto" && rect.height < 1) {
            element.style.setProperty("height", height + "px", "important");
            element.dataset.shinsouViewportHeight = "true";
          } else if (element.dataset.shinsouViewportHeight === "true") {
            element.style.setProperty("height", height + "px", "important");
          }
        }
      };
      apply();
      if (!window[stateKey]) {
        let queued = false;
        const schedule = () => {
          if (queued) return;
          queued = true;
          requestAnimationFrame(() => { queued = false; apply(); });
        };
        const observer = new MutationObserver(schedule);
        observer.observe(document.documentElement, { childList: true, subtree: true });
        window.addEventListener("resize", schedule, { passive: true });
        window.visualViewport?.addEventListener("resize", schedule, { passive: true });
        window[stateKey] = { observer, schedule };
      }
      return "applied";
    })()
""".trimIndent()
