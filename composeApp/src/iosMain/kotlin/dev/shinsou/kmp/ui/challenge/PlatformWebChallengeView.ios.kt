package dev.shinsou.kmp.ui.challenge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieExpires
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKContentRuleListStore
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWindowFeatures
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

internal actual val platformWebChallengeMode: PlatformWebChallengeMode =
    PlatformWebChallengeMode.Embedded

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
internal actual fun PlatformWebChallengeView(
    request: SourceWebChallengeRequest,
    captureRequest: Int,
    onPageLoaded: () -> Unit,
    onSessionCaptured: (WebChallengeCapture) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val currentPageLoaded = rememberUpdatedState(onPageLoaded)
    val currentSessionCaptured = rememberUpdatedState(onSessionCaptured)
    val currentError = rememberUpdatedState(onError)
    val state = remember(request) {
        val configuration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
        }
        val webView = WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), configuration).apply {
            if (request.userAgent.isNotBlank()) customUserAgent = request.userAgent
            allowsBackForwardNavigationGestures = true
        }
        val challengeState = IosChallengeState(webView)
        val delegate = IosChallengeNavigationDelegate(
            request = request,
            onLoaded = { loadedWebView ->
                currentPageLoaded.value.invoke()
                automaticWebChallengeLoginScript(request)?.let { script ->
                    loadedWebView.evaluateJavaScript(script, completionHandler = null)
                }
            },
            onError = { currentError.value.invoke(it) },
        )
        challengeState.delegate = delegate
        webView.navigationDelegate = delegate
        webView.UIDelegate = delegate
        challengeState
    }

    LaunchedEffect(state, request) {
        state.load(request) { currentError.value.invoke(it) }
    }

    LaunchedEffect(state, captureRequest) {
        if (captureRequest > 0) {
            state.capture(request.url) { cookies ->
                if (!state.isActive()) return@capture
                state.webView.evaluateJavaScript(webChallengeLocalStorageCaptureScript(request)) { encoded, _ ->
                    if (!state.isActive()) return@evaluateJavaScript
                    val storage = decodeWebChallengeLocalStorageCapture(
                        encoded as? String,
                        request.localStorageKeys,
                    )
                    if (storage.error != null) {
                        currentError.value.invoke(storage.error)
                    } else {
                        state.webView.evaluateJavaScript("navigator.userAgent") { value, _ ->
                            if (state.isActive()) {
                                currentSessionCaptured.value.invoke(
                                    WebChallengeCapture(
                                        capability = request.capability,
                                        cookies = cookies,
                                        userAgent = value as? String ?: "",
                                        localStorage = storage.values,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    UIKitView(
        factory = { state.webView },
        modifier = modifier,
        update = {},
        onRelease = {
            state.release()
        },
        properties = UIKitInteropProperties(
            // Login controls, Cloudflare widgets, and scrolling must receive UIKit touches
            // immediately. Cooperative mode lets the surrounding Compose dialog win the gesture
            // arena first, which can make an otherwise visible WKWebView entirely non-interactive.
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = true,
            placedAsOverlay = true,
        ),
    )
}

@OptIn(ExperimentalForeignApi::class)
private class IosChallengeState(val webView: WKWebView) {
    var delegate: IosChallengeNavigationDelegate? = null
    private var released = false

    fun load(request: SourceWebChallengeRequest, onError: (String) -> Unit) {
        if (!request.allowsEmbeddedWebChallenge()) {
            onError("Embedded browser access is not authorized for this source.")
            return
        }
        val url = NSURL.URLWithString(request.url)
        if (url == null) {
            onError("The source URL is invalid.")
            return
        }
        val encodedRules = request.appleWebChallengeContentRuleList()
        if (encodedRules == null) {
            onError("The reviewed browser network policy is invalid.")
            return
        }
        val ruleIdentifier = "dev.aluo.shinsoux.web-challenge.${encodedRules.hashCode()}"
        val ruleStore = WKContentRuleListStore.defaultStore()
        if (ruleStore == null) {
            onError("The isolated browser network policy is unavailable.")
            return
        }
        ruleStore.compileContentRuleListForIdentifier(
            ruleIdentifier,
            encodedRules,
        ) { ruleList, ruleError ->
            if (released) return@compileContentRuleListForIdentifier
            if (ruleList == null || ruleError != null) {
                onError("The isolated browser network policy could not be installed.")
                return@compileContentRuleListForIdentifier
            }
            webView.configuration.userContentController.addContentRuleList(ruleList)
            val cookieStore = webView.configuration.websiteDataStore.httpCookieStore
            val nativeCookies = webChallengeSeedCookies(request)
                .mapNotNull(SourceCookie::toNativeCookie)
            fun open() {
                if (released) return
                webView.loadRequest(NSURLRequest.requestWithURL(url))
            }
            if (nativeCookies.isEmpty()) {
                open()
                return@compileContentRuleListForIdentifier
            }
            var pending = nativeCookies.size
            nativeCookies.forEach { cookie ->
                cookieStore.setCookie(cookie) {
                    if (released) return@setCookie
                    pending -= 1
                    if (pending == 0) open()
                }
            }
        }
    }

    fun release() {
        released = true
        webView.stopLoading()
        delegate?.release()
        webView.navigationDelegate = null
        webView.UIDelegate = null
        delegate = null
    }

    fun isActive(): Boolean = !released

    fun capture(requestUrl: String, onCaptured: (List<SourceCookie>) -> Unit) {
        if (released) return
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { values ->
            if (released) return@getAllCookies
            val cookies = values.orEmpty().filterIsInstance<NSHTTPCookie>().map { it.toSourceCookie() }
            onCaptured(normalizeWebChallengeCookies(requestUrl, cookies))
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosChallengeNavigationDelegate(
    private val request: SourceWebChallengeRequest,
    private val onLoaded: (WKWebView) -> Unit,
    private val onError: (String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol, WKUIDelegateProtocol {
    private var released: Boolean = false

    fun release() {
        released = true
    }

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (!released) onLoaded(webView)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        // NSError text may include the request URL and other site/session details. Do not expose
        // native diagnostics across the challenge UI boundary.
        if (!released) onError("The isolated browser could not load the source page safely.")
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        if (!released) {
            onError("The isolated browser could not establish a safe connection to the source page.")
        }
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (platform.WebKit.WKNavigationActionPolicy) -> Unit,
    ) {
        if (released) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            return
        }
        val target = decidePolicyForNavigationAction.request.URL?.absoluteString
        val mainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame != false
        val scheme = decidePolicyForNavigationAction.request.URL?.scheme?.lowercase()
        val allowed = when {
            mainFrame -> request.allowsWebChallengeNavigation(target)
            scheme in setOf("about", "blob", "data") ->
                request.allowsWebChallengeInternalResource(target, isMainFrame = false)
            else -> request.allowsWebChallengeSubresource(target)
        }
        if (allowed) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            onError("Blocked browser navigation outside the reviewed source origins.")
        }
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        if (released) return null
        val target = forNavigationAction.request.URL?.absoluteString
        if (request.allowsWebChallengeNavigation(target) && target != null) {
            NSURL.URLWithString(target)?.let { webView.loadRequest(NSURLRequest.requestWithURL(it)) }
        } else {
            onError("Blocked browser popup outside the reviewed source origins.")
        }
        return null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun SourceCookie.toNativeCookie(): NSHTTPCookie? = runCatching {
    val properties = mutableMapOf<Any?, Any>(
        NSHTTPCookieName to name,
        NSHTTPCookieValue to value,
        NSHTTPCookieDomain to domain.trimStart('.'),
        NSHTTPCookiePath to path,
    )
    if (secure) properties[NSHTTPCookieSecure] = "TRUE"
    if (httpOnly) properties["HttpOnly"] = "TRUE"
    expiresAtEpochMillis?.let { expires ->
        properties[NSHTTPCookieExpires] = platform.Foundation.NSDate.create(
            timeIntervalSince1970 = expires.toDouble() / 1_000.0,
        )
    }
    NSHTTPCookie.cookieWithProperties(properties)
}.getOrNull()

@OptIn(ExperimentalForeignApi::class)
private fun NSHTTPCookie.toSourceCookie(): SourceCookie = SourceCookie(
    name = name,
    value = value,
    domain = domain,
    path = path,
    expiresAtEpochMillis = expiresDate?.timeIntervalSince1970?.times(1_000.0)?.toLong(),
    secure = secure,
    httpOnly = HTTPOnly,
    hostOnly = !domain.startsWith('.'),
)
