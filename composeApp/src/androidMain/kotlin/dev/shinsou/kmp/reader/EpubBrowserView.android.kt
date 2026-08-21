package dev.shinsou.kmp.reader

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import java.io.ByteArrayInputStream
import org.json.JSONTokener

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
@Composable
public actual fun EpubBrowserView(
    request: EpubRenderRequest,
    modifier: Modifier,
    selectionRequestKey: Long,
    onLocatorChanged: (ReadingLocator.Epub) -> Unit,
    onSelectionChanged: (ReadingRange?) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentLocatorChanged = rememberUpdatedState(onLocatorChanged)
    val currentSelectionChanged = rememberUpdatedState(onSelectionChanged)
    val currentError = rememberUpdatedState(onError)
    val state = remember(request.publicationRootUrl) {
        val webView = WebView(context).apply {
            settings.apply {
                // Publisher scripts remain blocked by the injected/response CSP. JavaScript is
                // enabled only so the host can query the native DOM selection on explicit demand.
                javaScriptEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                domStorageEnabled = false
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                blockNetworkLoads = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                safeBrowsingEnabled = true
                setSupportMultipleWindows(false)
                builtInZoomControls = true
                displayZoomControls = false
            }
            removeJavascriptInterface("searchBoxJavaBridge_")
            removeJavascriptInterface("accessibility")
            removeJavascriptInterface("accessibilityTraversal")
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        }
        AndroidEpubBrowserState(webView, request).also { browserState ->
            webView.setOnScrollChangeListener { _, _, _, _, _ ->
                browserState.sampleViewport(force = false) { locator ->
                    currentLocatorChanged.value.invoke(locator)
                }
            }
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    navigation: WebResourceRequest,
                ): WebResourceResponse = browserState.response(navigation.url.toString())

                @Deprecated("Legacy WebView callback retained for subresources on old engines")
                override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse =
                    browserState.response(url.orEmpty())

                override fun shouldOverrideUrlLoading(view: WebView?, navigation: WebResourceRequest): Boolean {
                    val target = navigation.url.toString()
                    if (navigation.isForMainFrame) {
                        val admitted = browserState.navigationRequested(target)
                        if (admitted == null) {
                            currentError.value.invoke(BLOCKED_NAVIGATION_MESSAGE)
                            return true
                        }
                        if (admitted != target) {
                            view?.loadUrl(admitted)
                            return true
                        }
                        return false
                    }
                    return true
                }

                @Deprecated("Legacy WebView callback retained for old engines")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val target = url.orEmpty()
                    val admitted = browserState.navigationRequested(target)
                    if (admitted == null) {
                        currentError.value.invoke(BLOCKED_NAVIGATION_MESSAGE)
                        return true
                    }
                    if (admitted != target) {
                        view?.loadUrl(admitted)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    browserState.documentLoaded(
                        url = url.orEmpty(),
                        onLocator = { locator -> currentLocatorChanged.value.invoke(locator) },
                        onError = { message -> currentError.value.invoke(message) },
                    )
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    val target = browserState.locationChanged(url.orEmpty()) ?: return
                    if (target.hasExplicitAnchor) {
                        browserState.sameDocumentLoaded(
                            url = url.orEmpty(),
                            onLocator = { locator -> currentLocatorChanged.value.invoke(locator) },
                            onError = { message -> currentError.value.invoke(message) },
                        )
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    navigation: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (navigation.isForMainFrame &&
                        browserState.documentLoadFailed(view, navigation.url.toString())
                    ) {
                        currentError.value.invoke(error.description.toString())
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    navigation: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (navigation.isForMainFrame &&
                        browserState.documentLoadFailed(view, navigation.url.toString())
                    ) {
                        currentError.value.invoke(
                            "The EPUB document request failed with HTTP ${errorResponse.statusCode}.",
                        )
                    }
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                    handler.cancel()
                    if (browserState.documentLoadFailed(view, error?.url.orEmpty())) {
                        currentError.value.invoke("Blocked TLS request from EPUB content.")
                    }
                }
            }
        }
    }

    LaunchedEffect(state, request) {
        state.webView.stopLoading()
        val documentUrl = state.update(request)
        state.webView.loadUrl(documentUrl)
    }

    LaunchedEffect(state, selectionRequestKey) {
        if (selectionRequestKey > 0L) {
            state.captureSelection { range -> currentSelectionChanged.value.invoke(range) }
        }
    }

    AndroidView(
        factory = { state.webView },
        modifier = modifier,
    )

    DisposableEffect(state) {
        onDispose {
            state.webView.stopLoading()
            state.webView.webViewClient = WebViewClient()
            state.webView.loadUrl("about:blank")
            state.close()
            state.webView.destroy()
        }
    }
}

private class AndroidEpubBrowserState(
    val webView: WebView,
    initialRequest: EpubRenderRequest,
) {
    private val documentState = EpubBrowserDocumentState(initialRequest)
    private val resolverSlot = EpubBrowserResolverSlot(EpubPublicationResourceResolver(initialRequest))
    private var locatorRequest: EpubRenderRequest = initialRequest
    private val locatorEvents = EpubViewportLocatorCoalescer(initialRequest)
    private var viewportEvaluationPending: Boolean = false
    private var forceViewportAfterPending: Boolean = false
    private var lastViewportEvaluationAtMillis: Long = 0L

    fun update(request: EpubRenderRequest): String {
        resolverSlot.stage(EpubPublicationResourceResolver(request))
        val documentUrl = documentState.updateHostRequest(request)
        return epubBrowserUrlWithLoadGeneration(
            documentUrl,
            requireNotNull(documentState.pendingLoadGeneration),
        )
    }

    fun navigationRequested(url: String): String? {
        val target = documentState.navigationRequested(url) ?: return null
        val generation = requireNotNull(documentState.pendingLoadGeneration)
        val sameCommittedDocument = target.hasExplicitAnchor &&
            target.documentIndex == documentState.committedRequest.documentIndex &&
            target.resourceHref == documentState.committedRequest.document.href
        return if (sameCommittedDocument) url else epubBrowserUrlWithLoadGeneration(url, generation)
    }

    fun locationChanged(url: String): EpubBrowserNavigationTarget? {
        return documentState.navigationRequested(url)
    }

    fun documentLoaded(
        url: String,
        onLocator: (ReadingLocator.Epub) -> Unit,
        onError: (String) -> Unit,
    ) {
        val generation = epubBrowserLoadGeneration(url) ?: return
        val loaded = documentState.documentLoaded(url, generation) ?: return
        finishDocumentLoad(loaded, onLocator, onError)
    }

    fun sameDocumentLoaded(
        url: String,
        onLocator: (ReadingLocator.Epub) -> Unit,
        onError: (String) -> Unit,
    ) {
        val generation = documentState.pendingLoadGeneration ?: return
        val loaded = documentState.sameDocumentNavigationLoaded(url, generation) ?: return
        finishDocumentLoad(loaded, onLocator, onError)
    }

    private fun finishDocumentLoad(
        loaded: EpubBrowserDocumentLoad,
        onLocator: (ReadingLocator.Epub) -> Unit,
        onError: (String) -> Unit,
    ) {
        resolverSlot.commit()
        activate(loaded.target.request)
        webView.evaluateJavascript(
            epubBrowserNavigationGuardScript(documentState.urlPolicy.browserPublicationRootUrl),
        ) { raw ->
            if (raw != "true") onError("The Android EPUB navigation guard could not start.")
        }
        webView.post {
            val script = if (loaded.restoreInitialLocator) {
                epubBrowserRestoreScript(loaded.target.request)
            } else {
                epubBrowserViewportScript(loaded.target.request)
            }
            evaluateViewport(loaded.target.request, script, force = true, onLocator = onLocator)
        }
    }

    fun documentLoadFailed(view: WebView?, failedUrl: String): Boolean {
        val generation = epubBrowserLoadGeneration(failedUrl) ?: return false
        if (!documentState.ownsPendingLoad(generation)) return false
        resolverSlot.rollback()
        val rollback = documentState.documentLoadFailed(generation) ?: return false
        activate(rollback.request)
        val currentUrl = view?.url
        if (!currentUrl.isNullOrBlank() &&
            epubBrowserUrlWithoutLoadGeneration(currentUrl) != rollback.browserUrl
        ) {
            val target = documentState.navigationRequested(rollback.browserUrl)
            if (target != null) {
                val retryUrl = epubBrowserUrlWithLoadGeneration(
                    rollback.browserUrl,
                    requireNotNull(documentState.pendingLoadGeneration),
                )
                view.post { view.loadUrl(retryUrl) }
            }
        }
        return true
    }

    fun sampleViewport(force: Boolean, onLocator: (ReadingLocator.Epub) -> Unit) {
        if (!documentState.canSampleViewport) return
        val now = SystemClock.uptimeMillis()
        if (viewportEvaluationPending) {
            forceViewportAfterPending = forceViewportAfterPending || force
            return
        }
        if (!force && now - lastViewportEvaluationAtMillis < VIEWPORT_EVALUATION_INTERVAL_MILLIS) return
        val request = documentState.activeRequest
        evaluateViewport(request, epubBrowserViewportScript(request), force, onLocator)
    }

    fun captureSelection(onRange: (ReadingRange?) -> Unit) {
        if (!documentState.canSampleViewport) return onRange(null)
        val request = documentState.activeRequest
        webView.evaluateJavascript(epubBrowserSelectionScript(request)) { raw ->
            if (!documentState.canSampleViewport || documentState.activeRequest !== request) {
                onRange(null)
            } else {
                val snapshot = raw.decodeJavascriptString()?.let(::decodeEpubBrowserSelection)
                onRange(snapshot?.let(request::rangeForSelection))
            }
        }
    }

    fun response(url: String): WebResourceResponse {
        val response = resolverSlot.read { currentResolver ->
            val policy = EpubBrowserUrlPolicy(currentResolver.request.publicationRootUrl)
            val canonical = policy.canonicalUrl(url)?.takeIf { canonicalUrl ->
                runCatching { currentResolver.contains(canonicalUrl) }.getOrDefault(false)
            } ?: return@read null
            runCatching { currentResolver.resolve(canonical) }.getOrNull()
        } ?: return blockedResponse()
        return WebResourceResponse(
            response.mediaType,
            response.textEncoding,
            200,
            "OK",
            response.headers,
            ByteArrayInputStream(response.bytes),
        )
    }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Forbidden",
        mapOf(
            "Content-Security-Policy" to "default-src 'none'",
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
        ),
        ByteArrayInputStream(ByteArray(0)),
    )

    fun close() {
        resolverSlot.close()
    }

    private fun activate(request: EpubRenderRequest) {
        if (locatorRequest === request) return
        locatorRequest = request
        locatorEvents.updateRequest(request)
    }

    private fun evaluateViewport(
        request: EpubRenderRequest,
        script: String,
        force: Boolean,
        onLocator: (ReadingLocator.Epub) -> Unit,
    ) {
        viewportEvaluationPending = true
        lastViewportEvaluationAtMillis = SystemClock.uptimeMillis()
        webView.evaluateJavascript(script) { raw ->
            viewportEvaluationPending = false
            if (documentState.canSampleViewport && documentState.activeRequest === request) {
                val viewport = raw.decodeJavascriptString()?.let(::decodeEpubBrowserViewport)
                viewport?.let { snapshot ->
                    locatorEvents.offerViewport(
                        viewport = snapshot,
                        nowMillis = SystemClock.uptimeMillis(),
                        force = force,
                    )?.let(onLocator)
                }
            }
            if (forceViewportAfterPending) {
                forceViewportAfterPending = false
                webView.post { sampleViewport(force = true, onLocator = onLocator) }
            }
        }
    }
}

private fun String.decodeJavascriptString(): String? =
    runCatching { JSONTokener(this).nextValue() as? String }.getOrNull()

private const val VIEWPORT_EVALUATION_INTERVAL_MILLIS: Long = 80L
private const val BLOCKED_NAVIGATION_MESSAGE: String =
    "Blocked EPUB navigation outside this publication."
