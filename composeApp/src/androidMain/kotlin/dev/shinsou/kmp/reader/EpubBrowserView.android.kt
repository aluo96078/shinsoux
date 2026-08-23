package dev.shinsou.kmp.reader

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Suppress("DEPRECATION")
@Composable
public actual fun EpubBrowserView(
    request: EpubRenderRequest,
    modifier: Modifier,
    configuration: EpubBrowserConfiguration,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    selectionRequestKey: Long,
    onLocatorChanged: (ReadingLocator.Epub) -> Unit,
    onSelectionChanged: (ReadingRange?) -> Unit,
    onReaderTap: (ReaderTapAction) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentLocatorChanged = rememberUpdatedState(onLocatorChanged)
    val currentSelectionChanged = rememberUpdatedState(onSelectionChanged)
    val currentReaderTap = rememberUpdatedState(onReaderTap)
    val currentError = rememberUpdatedState(onError)
    val state = remember(request.publicationRootUrl) {
        val webView = WebView(context).apply {
            settings.apply {
                // Publisher scripts remain blocked by the injected/response CSP. JavaScript is
                // enabled only for host-authored layout, navigation, viewport and selection work.
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
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = true
            isFocusableInTouchMode = true
            removeJavascriptInterface("searchBoxJavaBridge_")
            removeJavascriptInterface("accessibility")
            removeJavascriptInterface("accessibilityTraversal")
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        }
        AndroidEpubBrowserState(webView, request, configuration).also { browserState ->
            val tapDetector = GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(event: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                        val width = webView.width
                        val height = webView.height
                        if (width <= 0 || height <= 0) return false
                        browserState.tap(
                            horizontalFraction = (event.x / width.toDouble()).coerceIn(0.0, 1.0),
                            verticalFraction = (event.y / height.toDouble()).coerceIn(0.0, 1.0),
                            onLocator = { locator -> currentLocatorChanged.value.invoke(locator) },
                            onReaderTap = { action -> currentReaderTap.value.invoke(action) },
                            onError = { message -> currentError.value.invoke(message) },
                        )
                        return true
                    }
                },
            )
            webView.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) view.requestFocus()
                tapDetector.onTouchEvent(event)
                // Observation only: WebView retains native scrolling, links, selection and zoom.
                false
            }
            webView.setOnKeyListener { _, keyCode, event ->
                browserState.handleKeyEvent(
                    keyCode = keyCode,
                    event = event,
                    onLocator = { locator -> currentLocatorChanged.value.invoke(locator) },
                    onReaderTap = { action -> currentReaderTap.value.invoke(action) },
                    onError = { message -> currentError.value.invoke(message) },
                )
            }
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

    LaunchedEffect(state, configuration) {
        state.configure(
            configuration = configuration,
            onLocator = { locator -> currentLocatorChanged.value.invoke(locator) },
            onError = { message -> currentError.value.invoke(message) },
        )
    }

    LaunchedEffect(state, navigationRequestKey) {
        if (navigationRequestKey > 0L && navigationAction != null) {
            state.navigate(
                action = navigationAction,
                configuration = configuration,
                onLocator = { locator -> currentLocatorChanged.value.invoke(locator) },
                onReaderTap = { action -> currentReaderTap.value.invoke(action) },
                onError = { message -> currentError.value.invoke(message) },
            )
        }
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
            state.webView.setOnTouchListener(null)
            state.webView.setOnKeyListener(null)
            state.webView.setOnScrollChangeListener(null)
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
    initialConfiguration: EpubBrowserConfiguration,
) {
    private val documentState = EpubBrowserDocumentState(initialRequest)
    private val resolverSlot = EpubBrowserResolverSlot(EpubPublicationResourceResolver(initialRequest))
    private var locatorRequest: EpubRenderRequest = initialRequest
    private val locatorEvents = EpubViewportLocatorCoalescer(initialRequest)
    private var viewportEvaluationPending: Boolean = false
    private var forceViewportAfterPending: Boolean = false
    private var lastViewportEvaluationAtMillis: Long = 0L
    private var configuration: EpubBrowserConfiguration = initialConfiguration
    private var configurationGeneration: Long = 0L
    private var documentReady: Boolean = false
    private var closed: Boolean = false
    private var pendingNavigation: PendingAndroidEpubNavigation? = null

    fun update(request: EpubRenderRequest): String {
        documentReady = false
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
        if (!sameCommittedDocument) documentReady = false
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
        documentReady = false
        webView.evaluateJavascript(
            epubBrowserNavigationGuardScript(documentState.urlPolicy.browserPublicationRootUrl),
        ) { raw ->
            if (raw != "true") onError("The Android EPUB navigation guard could not start.")
        }
        configureAndRestoreDocument(
            request = loaded.target.request,
            restoreInitialLocator = loaded.restoreInitialLocator,
            onLocator = onLocator,
            onError = onError,
        )
    }

    fun documentLoadFailed(view: WebView?, failedUrl: String): Boolean {
        val generation = epubBrowserLoadGeneration(failedUrl) ?: return false
        if (!documentState.ownsPendingLoad(generation)) return false
        resolverSlot.rollback()
        val rollback = documentState.documentLoadFailed(generation) ?: return false
        activate(rollback.request)
        documentReady = true
        val currentUrl = view?.url
        if (!currentUrl.isNullOrBlank() &&
            epubBrowserUrlWithoutLoadGeneration(currentUrl) != rollback.browserUrl
        ) {
            val target = documentState.navigationRequested(rollback.browserUrl)
            if (target != null) {
                documentReady = false
                val retryUrl = epubBrowserUrlWithLoadGeneration(
                    rollback.browserUrl,
                    requireNotNull(documentState.pendingLoadGeneration),
                )
                view.post { view.loadUrl(retryUrl) }
            }
        }
        if (documentReady) drainPendingNavigation()
        return true
    }

    fun sampleViewport(force: Boolean, onLocator: (ReadingLocator.Epub) -> Unit) {
        if (!documentReady || closed || !documentState.canSampleViewport) return
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
        if (!documentReady || closed || !documentState.canSampleViewport) return onRange(null)
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
        closed = true
        documentReady = false
        pendingNavigation = null
        resolverSlot.close()
    }

    fun configure(
        configuration: EpubBrowserConfiguration,
        onLocator: (ReadingLocator.Epub) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (closed || this.configuration == configuration) return
        this.configuration = configuration
        configurationGeneration++
        if (!documentState.canSampleViewport) return
        val request = documentState.activeRequest
        if (!documentReady) return
        documentReady = false
        configureAndRestoreDocument(
            request = request,
            restoreInitialLocator = false,
            onLocator = onLocator,
            onError = onError,
        )
    }

    fun navigate(
        action: ReaderTapAction,
        configuration: EpubBrowserConfiguration,
        onLocator: (ReadingLocator.Epub) -> Unit,
        onReaderTap: (ReaderTapAction) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (closed) return
        if (!documentReady || !documentState.canSampleViewport) {
            pendingNavigation = PendingAndroidEpubNavigation(
                action = action,
                onLocator = onLocator,
                onReaderTap = onReaderTap,
                onError = onError,
            )
            return
        }
        val request = documentState.activeRequest
        evaluateAction(
            request = request,
            script = epubBrowserNavigationScript(request, configuration, action),
            onLocator = onLocator,
            onReaderTap = onReaderTap,
            onError = onError,
        )
    }

    fun tap(
        horizontalFraction: Double,
        verticalFraction: Double,
        onLocator: (ReadingLocator.Epub) -> Unit,
        onReaderTap: (ReaderTapAction) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!documentReady || closed || !documentState.canSampleViewport) return
        val request = documentState.activeRequest
        val activeConfiguration = configuration
        evaluateAction(
            request = request,
            script = epubBrowserTapScript(
                request = request,
                configuration = activeConfiguration,
                horizontalFraction = horizontalFraction,
                verticalFraction = verticalFraction,
            ),
            onLocator = onLocator,
            onReaderTap = onReaderTap,
            onError = onError,
        )
    }

    fun handleKeyEvent(
        keyCode: Int,
        event: KeyEvent,
        onLocator: (ReadingLocator.Epub) -> Unit,
        onReaderTap: (ReaderTapAction) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        val action = readerActionForKey(keyCode, event, configuration) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            navigate(action, configuration, onLocator, onReaderTap, onError)
        }
        return true
    }

    private fun activate(request: EpubRenderRequest) {
        if (locatorRequest === request) return
        locatorRequest = request
        locatorEvents.updateRequest(request)
    }

    private fun evaluateAction(
        request: EpubRenderRequest,
        script: String,
        onLocator: (ReadingLocator.Epub) -> Unit,
        onReaderTap: (ReaderTapAction) -> Unit,
        onError: (String) -> Unit,
    ) {
        webView.evaluateJavascript(script) { raw ->
            if (!ownsReadyDocument(request)) return@evaluateJavascript
            val result = raw.decodeJavascriptString()?.let(::decodeEpubBrowserActionResult)
            if (result == null) {
                onError("The Android EPUB page action could not be completed.")
                return@evaluateJavascript
            }
            result.boundaryAction()?.let(onReaderTap)
            webView.post {
                if (ownsReadyDocument(request)) sampleViewport(force = true, onLocator = onLocator)
            }
        }
    }

    private fun ownsReadyDocument(request: EpubRenderRequest): Boolean =
        !closed && documentReady && documentState.canSampleViewport && documentState.activeRequest === request

    private fun ownsCommittedDocument(request: EpubRenderRequest): Boolean =
        !closed && documentState.canSampleViewport && documentState.activeRequest === request

    private fun configureAndRestoreDocument(
        request: EpubRenderRequest,
        restoreInitialLocator: Boolean,
        onLocator: (ReadingLocator.Epub) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!ownsCommittedDocument(request)) return
        val generation = configurationGeneration
        val appliedConfiguration = configuration
        webView.evaluateJavascript(epubBrowserConfigureScript(request, appliedConfiguration)) { raw ->
            if (!ownsCommittedDocument(request)) return@evaluateJavascript
            if (generation != configurationGeneration) {
                configureAndRestoreDocument(request, restoreInitialLocator, onLocator, onError)
                return@evaluateJavascript
            }
            if (raw.decodeJavascriptString()?.let(::decodeEpubBrowserActionResult) == null) {
                onError("The Android EPUB reader layout could not be applied.")
            }
            webView.post {
                if (!ownsCommittedDocument(request)) return@post
                if (generation != configurationGeneration) {
                    configureAndRestoreDocument(request, restoreInitialLocator, onLocator, onError)
                    return@post
                }
                val script = if (restoreInitialLocator) {
                    epubBrowserRestoreScript(request)
                } else {
                    epubBrowserViewportScript(request)
                }
                evaluateViewport(
                    request = request,
                    script = script,
                    force = true,
                    onLocator = onLocator,
                    onComplete = {
                        if (!ownsCommittedDocument(request)) return@evaluateViewport
                        if (generation != configurationGeneration) {
                            configureAndRestoreDocument(request, restoreInitialLocator, onLocator, onError)
                        } else {
                            documentReady = true
                            drainPendingNavigation()
                        }
                    },
                )
            }
        }
    }

    private fun drainPendingNavigation() {
        val pending = pendingNavigation ?: return
        pendingNavigation = null
        webView.post {
            navigate(
                action = pending.action,
                configuration = configuration,
                onLocator = pending.onLocator,
                onReaderTap = pending.onReaderTap,
                onError = pending.onError,
            )
        }
    }

    private fun evaluateViewport(
        request: EpubRenderRequest,
        script: String,
        force: Boolean,
        onLocator: (ReadingLocator.Epub) -> Unit,
        onComplete: () -> Unit = {},
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
            onComplete()
            if (forceViewportAfterPending) {
                forceViewportAfterPending = false
                webView.post { sampleViewport(force = true, onLocator = onLocator) }
            }
        }
    }
}

private data class PendingAndroidEpubNavigation(
    val action: ReaderTapAction,
    val onLocator: (ReadingLocator.Epub) -> Unit,
    val onReaderTap: (ReaderTapAction) -> Unit,
    val onError: (String) -> Unit,
)

private fun readerActionForKey(
    keyCode: Int,
    event: KeyEvent,
    configuration: EpubBrowserConfiguration,
): ReaderTapAction? {
    if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return null
    if (event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) return null
    val rightToLeft = configuration.readingMode == EpubBrowserReadingMode.PAGED_RIGHT_TO_LEFT
    return when (keyCode) {
        KeyEvent.KEYCODE_PAGE_DOWN -> ReaderTapAction.NEXT_PAGE
        KeyEvent.KEYCODE_PAGE_UP -> ReaderTapAction.PREVIOUS_PAGE
        KeyEvent.KEYCODE_SPACE -> if (event.isShiftPressed) {
            ReaderTapAction.PREVIOUS_PAGE
        } else {
            ReaderTapAction.NEXT_PAGE
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> ReaderTapAction.NEXT_PAGE
        KeyEvent.KEYCODE_DPAD_UP -> ReaderTapAction.PREVIOUS_PAGE
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (rightToLeft) {
            ReaderTapAction.PREVIOUS_PAGE
        } else {
            ReaderTapAction.NEXT_PAGE
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> if (rightToLeft) {
            ReaderTapAction.NEXT_PAGE
        } else {
            ReaderTapAction.PREVIOUS_PAGE
        }
        else -> null
    }
}

private fun String.decodeJavascriptString(): String? =
    runCatching { JSONTokener(this).nextValue() as? String }.getOrNull()

private const val VIEWPORT_EVALUATION_INTERVAL_MILLIS: Long = 80L
private const val BLOCKED_NAVIGATION_MESSAGE: String =
    "Blocked EPUB navigation outside this publication."
