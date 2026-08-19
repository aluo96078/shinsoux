package dev.shinsou.kmp.ui.challenge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
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
    onCookiesCaptured: (List<SourceCookie>) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val currentPageLoaded = rememberUpdatedState(onPageLoaded)
    val currentCookiesCaptured = rememberUpdatedState(onCookiesCaptured)
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
            onLoaded = {
                currentPageLoaded.value.invoke()
                challengeState.capture(request.url) { cookies ->
                    if (!challengeState.autoReported && cookies.any { it.name == "cf_clearance" }) {
                        challengeState.autoReported = true
                        currentCookiesCaptured.value.invoke(cookies)
                    }
                }
            },
            onError = { currentError.value.invoke(it) },
        )
        challengeState.delegate = delegate
        webView.navigationDelegate = delegate
        challengeState
    }

    LaunchedEffect(state, request) {
        state.load(request) { currentError.value.invoke(it) }
    }

    LaunchedEffect(state, captureRequest) {
        if (captureRequest > 0) {
            state.capture(request.url) { cookies ->
                currentCookiesCaptured.value.invoke(cookies)
            }
        }
    }

    UIKitView(
        factory = { state.webView },
        modifier = modifier,
        update = {},
        onRelease = { released ->
            released.stopLoading()
            released.navigationDelegate = null
        },
    )
}

@OptIn(ExperimentalForeignApi::class)
private class IosChallengeState(val webView: WKWebView) {
    var delegate: IosChallengeNavigationDelegate? = null
    var autoReported: Boolean = false

    fun load(request: SourceWebChallengeRequest, onError: (String) -> Unit) {
        val url = NSURL.URLWithString(request.url)
        if (url == null) {
            onError("The source URL is invalid.")
            return
        }
        val cookieStore = webView.configuration.websiteDataStore.httpCookieStore
        val nativeCookies = normalizeWebChallengeCookies(request.url, request.cookies)
            .mapNotNull(SourceCookie::toNativeCookie)
        fun open() {
            autoReported = false
            webView.loadRequest(NSURLRequest.requestWithURL(url))
        }
        if (nativeCookies.isEmpty()) {
            open()
            return
        }
        var pending = nativeCookies.size
        nativeCookies.forEach { cookie ->
            cookieStore.setCookie(cookie) {
                pending -= 1
                if (pending == 0) open()
            }
        }
    }

    fun capture(requestUrl: String, onCaptured: (List<SourceCookie>) -> Unit) {
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { values ->
            val cookies = values.orEmpty().filterIsInstance<NSHTTPCookie>().map { it.toSourceCookie() }
            onCaptured(normalizeWebChallengeCookies(requestUrl, cookies))
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosChallengeNavigationDelegate(
    private val onLoaded: () -> Unit,
    private val onError: (String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onLoaded()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        onError(withError.localizedDescription)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        onError(withError.localizedDescription)
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (platform.WebKit.WKNavigationActionPolicy) -> Unit,
    ) {
        val scheme = decidePolicyForNavigationAction.request.URL?.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            onError("Blocked unsupported navigation scheme: ${scheme.orEmpty()}")
        }
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
