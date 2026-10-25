package dev.shinsou.kmp.ui.challenge

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

internal actual val platformWebChallengeMode: PlatformWebChallengeMode =
    if (
        runCatching {
            androidWebChallengeSupportsIsolation(
                multiProfileSupported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
                deleteBrowsingDataSupported =
                    WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA),
            )
        }.getOrDefault(false)
    ) {
        PlatformWebChallengeMode.Embedded
    } else {
        PlatformWebChallengeMode.ExternalBrowserOnly
    }

internal fun androidWebChallengeSupportsIsolation(
    multiProfileSupported: Boolean,
    deleteBrowsingDataSupported: Boolean,
): Boolean = multiProfileSupported && deleteBrowsingDataSupported

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
        AndroidChallengeState(context = context, request = request)
    }

    LaunchedEffect(state, request) {
        if (platformWebChallengeMode != PlatformWebChallengeMode.Embedded) {
            currentError.value.invoke(
                "Android System WebView does not support a separate browser session with complete data deletion.",
            )
            return@LaunchedEffect
        }
        if (!request.allowsEmbeddedWebChallenge()) {
            currentError.value.invoke("Embedded browser access is not authorized for this source.")
            return@LaunchedEffect
        }
        val initial = webChallengeSeedCookies(request)
        state.start(
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (!state.isActive()) return
                    currentPageLoaded.value.invoke()
                    view.evaluateJavascript(ANDROID_WEB_CHALLENGE_VIEWPORT_FIX, null)
                    automaticWebChallengeLoginScript(request)?.let { script ->
                        view.evaluateJavascript(script, null)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, navigation: WebResourceRequest): Boolean {
                    if (!state.isActive()) return true
                    val target = navigation.url.toString()
                    val allowed = if (navigation.isForMainFrame) {
                        request.allowsWebChallengeNavigation(target)
                    } else {
                        request.allowsWebChallengeSubresource(target) ||
                            request.allowsWebChallengeInternalResource(target, isMainFrame = false)
                    }
                    if (allowed) return false
                    currentError.value.invoke("Blocked browser navigation outside the reviewed source origins.")
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    resource: WebResourceRequest,
                ): WebResourceResponse? {
                    if (!state.isActive()) return blockedWebChallengeResponse()
                    val scheme = resource.url.scheme?.lowercase()
                    val target = resource.url.toString()
                    val allowed = when (scheme) {
                        "http", "https" -> request.allowsWebChallengeSubresource(target)
                        else -> request.allowsWebChallengeInternalResource(target, resource.isForMainFrame)
                    }
                    if (allowed) return null
                    return blockedWebChallengeResponse()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (state.isActive() && request.isForMainFrame) {
                        // WebView/provider diagnostics may embed the requested URL, redirect
                        // target, proxy details, or site-controlled text. Keep source/session data
                        // out of UI diagnostics and logs crossing this platform boundary.
                        currentError.value.invoke(
                            "The isolated browser could not load the source page safely.",
                        )
                    }
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                    handler.cancel()
                    if (state.isActive()) {
                        currentError.value.invoke("TLS certificate validation failed. The page was not opened.")
                    }
                }
            },
            initialCookies = initial,
            onReady = { state.loadWhenMeasured() },
            onError = { currentError.value.invoke(it) },
        )
    }

    LaunchedEffect(state, captureRequest) {
        if (captureRequest > 0) {
            state.capture(
                onCaptured = { cookies, userAgent, encoded ->
                    val storage = decodeWebChallengeLocalStorageCapture(encoded, request.localStorageKeys)
                    if (storage.error != null) {
                        currentError.value.invoke(storage.error)
                    } else {
                        currentSessionCaptured.value.invoke(
                            WebChallengeCapture(
                                capability = request.capability,
                                cookies = cookies,
                                userAgent = userAgent,
                                localStorage = storage.values,
                            ),
                        )
                    }
                },
                onError = { currentError.value.invoke(it) },
            )
        }
    }

    AndroidView(
        factory = { state.container },
        modifier = modifier,
        update = { state.resumePendingLoad() },
    )

    DisposableEffect(state) {
        onDispose {
            state.release()
        }
    }
}

private fun blockedWebChallengeResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    403,
    "Forbidden",
    mapOf(
        "Cache-Control" to "no-store",
        "Content-Security-Policy" to "default-src 'none'",
    ),
    ByteArrayInputStream(ByteArray(0)),
)

private class AndroidChallengeState(
    context: android.content.Context,
    private val request: SourceWebChallengeRequest,
) {
    val container = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startJob: Job? = null
    @Volatile
    private var released: Boolean = false
    private var leaseHeld: Boolean = false
    @Volatile
    private var phase: Phase = Phase.WAITING
    private var profile: Profile? = null
    private var webView: WebView? = null
    private var pendingUrl: String? = null
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        openPendingUrlIfMeasured()
    }

    fun start(
        webViewClient: WebViewClient,
        initialCookies: List<SourceCookie>,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (startJob != null || released) return
        startJob = scope.launch {
            try {
                AndroidChallengeProfileLease.acquire()
                leaseHeld = true
                if (released) {
                    finishAndReleaseLease()
                    return@launch
                }
                phase = Phase.PREPARING
                bindDedicatedProfile(webViewClient)
                clearProfileData()
                if (released) {
                    finishAndReleaseLease()
                    return@launch
                }
                seedCookies(initialCookies)
                if (released) {
                    finishAndReleaseLease()
                    return@launch
                }
                phase = Phase.ACTIVE
                onReady()
            } catch (cancelled: CancellationException) {
                if (!released) {
                    onError("The isolated Android browser session could not be prepared safely.")
                }
                if (leaseHeld) finishAndReleaseLease()
            } catch (_: Throwable) {
                if (!released) {
                    onError("The isolated Android browser session could not be prepared safely.")
                }
                if (leaseHeld) finishAndReleaseLease()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun bindDedicatedProfile(webViewClient: WebViewClient) {
        check(androidWebChallengeSupportsIsolation(
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA),
        ))
        check(ANDROID_CHALLENGE_PROFILE_NAME != Profile.DEFAULT_PROFILE_NAME)

        val dedicatedProfile = ProfileStore.getInstance().getOrCreateProfile(ANDROID_CHALLENGE_PROFILE_NAME)
        profile = dedicatedProfile
        // A challenge needs a foreground document, not persistent background workers. These
        // settings belong to this profile and never affect the app's default WebView profile.
        dedicatedProfile.serviceWorkerController.serviceWorkerWebSettings.apply {
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        val createdWebView = WebView(container.context)
        webView = createdWebView
        // This must be the first operation on the WebView: no navigation, script evaluation, or
        // access to the default profile may occur before the dedicated profile is attached.
        WebViewCompat.setProfile(createdWebView, ANDROID_CHALLENGE_PROFILE_NAME)
        check(WebViewCompat.getProfile(createdWebView).name == ANDROID_CHALLENGE_PROFILE_NAME)

        createdWebView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        createdWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            if (request.userAgent.isNotBlank()) userAgentString = request.userAgent
        }
        dedicatedProfile.cookieManager.apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(createdWebView, false)
        }
        createdWebView.webViewClient = webViewClient
        createdWebView.webChromeClient = WebChromeClient()
        createdWebView.addOnLayoutChangeListener(layoutListener)
        container.addView(createdWebView)
    }

    private suspend fun clearProfileData() {
        val completion = CompletableDeferred<Unit>()
        try {
            WebStorageCompat.deleteBrowsingData(requireNotNull(profile).webStorage) {
                completion.complete(Unit)
            }
            withTimeout(ANDROID_PROFILE_OPERATION_TIMEOUT_MILLIS) { completion.await() }
        } catch (failure: Throwable) {
            AndroidChallengeProfileLease.poison()
            throw failure
        }
    }

    private suspend fun seedCookies(initialCookies: List<SourceCookie>) {
        val cookieManager = requireNotNull(profile).cookieManager
        try {
            withTimeout(ANDROID_PROFILE_OPERATION_TIMEOUT_MILLIS) {
                initialCookies.forEach { cookie ->
                    val completion = CompletableDeferred<Boolean>()
                    cookieManager.setCookie(request.url, webChallengeSetCookieValue(cookie)) { accepted ->
                        completion.complete(accepted)
                    }
                    check(completion.await())
                }
            }
            cookieManager.flush()
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.TimeoutCancellationException) {
                // A late cookie callback could otherwise race the final deletion and contaminate
                // a later holder of the fixed profile.
                AndroidChallengeProfileLease.poison()
            }
            throw failure
        }
    }

    fun isActive(): Boolean = !released && phase == Phase.ACTIVE

    fun capture(onCaptured: (List<SourceCookie>, String, String?) -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                check(isActive())
                val activeProfile = requireNotNull(profile)
                val activeWebView = requireNotNull(webView)
                val documentUrl = activeWebView.url
                check(request.allowsWebChallengeCapture(documentUrl))
                activeProfile.cookieManager.flush()
                val cookies = parseWebViewCookieHeader(
                    activeProfile.cookieManager.getCookie(request.url), request.url,
                )
                val userAgent = activeWebView.settings.userAgentString.orEmpty()
                val completion = CompletableDeferred<String?>()
                activeWebView.evaluateJavascript(webChallengeLocalStorageCaptureScript(request)) {
                    completion.complete(it)
                }
                val encoded = withTimeout(ANDROID_PROFILE_OPERATION_TIMEOUT_MILLIS) { completion.await() }
                check(isActive() && activeWebView.url == documentUrl)
                onCaptured(cookies, userAgent, encoded)
            } catch (_: Throwable) {
                if (!released) onError("Return to the source page after signing in, then try importing again.")
            }
        }
    }

    /**
     * Loading a WebView while Compose still measures it at 0 x 0 can permanently leave Chromium's
     * viewport units at zero even after the native view becomes visible. Wait for the first real
     * Android layout before opening the challenge page.
     */
    fun loadWhenMeasured() {
        if (!isActive()) return
        pendingUrl = request.url
        resumePendingLoad()
    }

    fun resumePendingLoad() {
        if (!isActive() || pendingUrl == null) return
        webView?.post { openPendingUrlIfMeasured() }
    }

    private fun openPendingUrlIfMeasured() {
        if (!isActive()) return
        val activeWebView = webView ?: return
        if (!activeWebView.isAttachedToWindow || activeWebView.width <= 0 || activeWebView.height <= 0) return
        val url = pendingUrl ?: return
        pendingUrl = null
        activeWebView.loadUrl(url)
    }

    fun release() {
        if (released) return
        released = true
        pendingUrl = null
        if (!leaseHeld) {
            startJob?.cancel()
        } else if (phase == Phase.ACTIVE) {
            scope.launch { finishAndReleaseLease() }
        }
    }

    private suspend fun finishAndReleaseLease() {
        if (!leaseHeld || phase == Phase.CLOSING || phase == Phase.CLOSED) return
        phase = Phase.CLOSING
        pendingUrl = null
        try {
            webView?.let { activeWebView ->
                try {
                    activeWebView.removeOnLayoutChangeListener(layoutListener)
                    activeWebView.stopLoading()
                    container.removeView(activeWebView)
                    activeWebView.destroy()
                } catch (_: Throwable) {
                    // A partially destroyed WebView makes it unsafe to ever hand this fixed
                    // profile to another challenge in the same process.
                    AndroidChallengeProfileLease.poison()
                }
            }
            webView = null
            if (profile != null) clearProfileData()
        } catch (_: Throwable) {
            // clearProfileData poisons the process-global profile before returning a failure.
        } finally {
            profile = null
            phase = Phase.CLOSED
            leaseHeld = false
            AndroidChallengeProfileLease.release()
        }
    }

    private enum class Phase { WAITING, PREPARING, ACTIVE, CLOSING, CLOSED }
}

private object AndroidChallengeProfileLease {
    private val mutex = Mutex()

    @Volatile
    private var poisoned: Boolean = false

    suspend fun acquire() {
        mutex.lock()
        if (poisoned) {
            mutex.unlock()
            error("The dedicated WebView profile cannot be reused safely in this process.")
        }
    }

    fun poison() {
        poisoned = true
    }

    fun release() {
        mutex.unlock()
    }
}

private const val ANDROID_CHALLENGE_PROFILE_NAME = "shinsou_isolated_web_challenge"
private const val ANDROID_PROFILE_OPERATION_TIMEOUT_MILLIS = 15_000L

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
