package dev.shinsou.kmp.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIScrollView
import platform.UIKit.UIScrollViewDelegateProtocol
import platform.UIKit.UITapGestureRecognizer
import platform.WebKit.WKContentRuleListStore
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.javaScriptEnabled
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
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
    val currentLocatorChanged = rememberUpdatedState(onLocatorChanged)
    val currentSelectionChanged = rememberUpdatedState(onSelectionChanged)
    val currentReaderTap = rememberUpdatedState(onReaderTap)
    val currentError = rememberUpdatedState(onError)
    val state = remember(request.publicationRootUrl) {
        val documentState = EpubBrowserDocumentState(request)
        val resolverHolder = IosEpubResolverHolder(
            resolver = EpubPublicationResourceResolver(request),
            policy = documentState.urlPolicy,
        )
        val schemeHandler = IosEpubSchemeHandler(resolverHolder)
        val webConfiguration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            // Publisher execution stays disabled below and by CSP; the host still needs
            // evaluateJavaScript for an explicit DOM-selection snapshot.
            preferences.javaScriptEnabled = true
            defaultWebpagePreferences.allowsContentJavaScript = false
            setURLSchemeHandler(schemeHandler, request.securityPolicy.publicationScheme)
        }
        val webView = WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), webConfiguration).apply {
            allowsBackForwardNavigationGestures = false
            allowsLinkPreview = false
        }
        val scrollDelegate = IosEpubScrollDelegate(
            webView = webView,
            initialRequest = request,
            initialConfiguration = configuration,
            canSampleViewport = { documentState.canSampleViewport },
            onLocatorChanged = { locator -> currentLocatorChanged.value.invoke(locator) },
            onReaderTap = { action -> currentReaderTap.value.invoke(action) },
            onError = { message -> currentError.value.invoke(message) },
        )
        val tapHandler = IosEpubTapHandler(webView) { horizontalFraction, verticalFraction ->
            scrollDelegate.handleTap(horizontalFraction, verticalFraction)
        }
        val tapGestureRecognizer = UITapGestureRecognizer(
            target = tapHandler,
            action = NSSelectorFromString("handleTap:"),
        ).apply {
            cancelsTouchesInView = false
        }
        webView.addGestureRecognizer(tapGestureRecognizer)
        val delegate = IosEpubNavigationDelegate(
            allowNavigation = { url ->
                documentState.navigationRequested(url)?.let { target ->
                    IosEpubNavigationAdmission(
                        browserUrl = url,
                        generation = requireNotNull(documentState.pendingLoadGeneration),
                        sameDocument = target.hasExplicitAnchor &&
                            target.documentIndex == documentState.committedRequest.documentIndex &&
                            target.resourceHref == documentState.committedRequest.document.href,
                    )
                }
            },
            onLoaded = { url, generation ->
                documentState.documentLoaded(url, generation)?.let { loaded ->
                    resolverHolder.commit()
                    scrollDelegate.update(loaded.target.request)
                    scrollDelegate.documentLoaded(
                        loaded = loaded,
                        browserPublicationRootUrl = documentState.urlPolicy.browserPublicationRootUrl,
                        onError = { currentError.value.invoke(it) },
                    )
                }
            },
            onLoadFailed = { message, generation ->
                if (documentState.ownsPendingLoad(generation)) {
                    resolverHolder.rollback()
                    val rollback = checkNotNull(documentState.documentLoadFailed(generation))
                    val visibleUrl = webView.URL?.absoluteString
                    scrollDelegate.documentLoadFailed(
                        rollbackRequest = rollback.request,
                        oldDocumentAvailable = visibleUrl != null &&
                            epubBrowserUrlWithoutLoadGeneration(visibleUrl) ==
                            epubBrowserUrlWithoutLoadGeneration(rollback.browserUrl),
                    )
                    currentError.value.invoke(message)
                }
            },
            onPolicyError = { currentError.value.invoke(it) },
        )
        webView.navigationDelegate = delegate
        webView.scrollView.delegate = scrollDelegate
        IosEpubBrowserState(
            webView,
            resolverHolder,
            schemeHandler,
            delegate,
            scrollDelegate,
            documentState,
            tapHandler,
            tapGestureRecognizer,
        )
    }

    LaunchedEffect(state, request) {
        state.load(
            request = request,
            onError = { currentError.value.invoke(it) },
        )
    }

    LaunchedEffect(state, configuration) {
        state.updateConfiguration(configuration)
    }

    LaunchedEffect(state, navigationRequestKey, navigationAction) {
        if (navigationRequestKey > 0L && navigationAction != null) {
            state.navigate(navigationAction, navigationRequestKey)
        }
    }

    LaunchedEffect(state, selectionRequestKey) {
        if (selectionRequestKey > 0L) {
            state.captureSelection { range -> currentSelectionChanged.value.invoke(range) }
        }
    }

    key(state) {
        UIKitView(
            factory = { state.webView },
            modifier = modifier,
            update = {},
            onRelease = { state.close() },
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosEpubBrowserState(
    val webView: WKWebView,
    val resolverHolder: IosEpubResolverHolder,
    @Suppress("unused") val schemeHandler: IosEpubSchemeHandler,
    val navigationDelegate: IosEpubNavigationDelegate,
    val scrollDelegate: IosEpubScrollDelegate,
    val documentState: EpubBrowserDocumentState,
    private val tapHandler: IosEpubTapHandler,
    private val tapGestureRecognizer: UITapGestureRecognizer,
) {
    private var contentRulesReady: Boolean = false
    private var compilingContentRules: Boolean = false
    private var pendingDocumentUrl: String? = null
    private var pendingError: ((String) -> Unit)? = null
    private var lastNavigationRequestKey: Long = 0L
    private var closed: Boolean = false

    fun load(request: EpubRenderRequest, onError: (String) -> Unit) {
        webView.stopLoading()
        val documentUrl = documentState.updateHostRequest(request)
        resolverHolder.stage(
            resolver = EpubPublicationResourceResolver(request),
            policy = documentState.urlPolicy,
        )
        pendingDocumentUrl = documentUrl
        pendingError = onError
        if (contentRulesReady) {
            openPendingDocument()
        } else if (!compilingContentRules) {
            compileExternalNetworkBlocker()
        }
    }

    fun captureSelection(onRange: (ReadingRange?) -> Unit) {
        if (!documentState.canSampleViewport) return onRange(null)
        val request = documentState.activeRequest
        webView.evaluateJavaScript(epubBrowserSelectionScript(request)) { value, _ ->
            if (!documentState.canSampleViewport || documentState.activeRequest !== request) {
                onRange(null)
            } else {
                val snapshot = (value as? String)?.let(::decodeEpubBrowserSelection)
                onRange(snapshot?.let(request::rangeForSelection))
            }
        }
    }

    fun updateConfiguration(configuration: EpubBrowserConfiguration) {
        if (closed) return
        scrollDelegate.updateConfiguration(configuration)
    }

    fun navigate(action: ReaderTapAction, requestKey: Long) {
        if (closed || requestKey == lastNavigationRequestKey) return
        lastNavigationRequestKey = requestKey
        scrollDelegate.navigate(action)
    }

    private fun compileExternalNetworkBlocker() {
        val store = WKContentRuleListStore.defaultStore()
        if (store == null) {
            failPending("The secure EPUB network blocker is unavailable.")
            return
        }
        compilingContentRules = true
        store.compileContentRuleListForIdentifier(
            identifier = CONTENT_RULE_IDENTIFIER,
            encodedContentRuleList = EXTERNAL_NETWORK_BLOCK_RULES,
        ) { rules, error ->
            compilingContentRules = false
            if (rules == null || error != null) {
                failPending(error?.localizedDescription ?: "The secure EPUB network blocker could not start.")
                return@compileContentRuleListForIdentifier
            }
            webView.configuration.userContentController.addContentRuleList(rules)
            contentRulesReady = true
            openPendingDocument()
        }
    }

    private fun openPendingDocument() {
        val value = pendingDocumentUrl ?: return
        val url = NSURL.URLWithString(value)
        if (url == null) {
            failPending("The EPUB document URL is invalid.")
            return
        }
        val generation = documentState.pendingLoadGeneration
        if (generation == null) {
            failPending("The EPUB document load identity is unavailable.")
            return
        }
        val navigation = webView.loadRequest(NSURLRequest.requestWithURL(url))
        if (navigation == null) {
            failPending("The EPUB document navigation could not start.")
            return
        }
        navigationDelegate.registerHostNavigation(navigation, generation)
    }

    fun close() {
        if (closed) return
        closed = true
        scrollDelegate.flushViewport()
        scrollDelegate.close()
        webView.stopLoading()
        webView.navigationDelegate = null
        webView.scrollView.delegate = null
        webView.removeGestureRecognizer(tapGestureRecognizer)
        tapHandler.close()
        pendingDocumentUrl = null
        pendingError = null
        navigationDelegate.close()
        resolverHolder.close()
    }

    private fun failPending(message: String) {
        val generation = documentState.pendingLoadGeneration ?: return
        if (!documentState.ownsPendingLoad(generation)) return
        resolverHolder.rollback()
        val rollback = documentState.documentLoadFailed(generation) ?: return
        val visibleUrl = webView.URL?.absoluteString
        scrollDelegate.documentLoadFailed(
            rollbackRequest = rollback.request,
            oldDocumentAvailable = visibleUrl != null &&
                epubBrowserUrlWithoutLoadGeneration(visibleUrl) ==
                epubBrowserUrlWithoutLoadGeneration(rollback.browserUrl),
        )
        pendingDocumentUrl = null
        pendingError?.invoke(message)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosEpubScrollDelegate(
    private val webView: WKWebView,
    initialRequest: EpubRenderRequest,
    initialConfiguration: EpubBrowserConfiguration,
    private val canSampleViewport: () -> Boolean,
    private val onLocatorChanged: (ReadingLocator.Epub) -> Unit,
    private val onReaderTap: (ReaderTapAction) -> Unit,
    private val onError: (String) -> Unit,
) : NSObject(), UIScrollViewDelegateProtocol {
    var request: EpubRenderRequest = initialRequest
        private set
    private val locatorEvents = EpubViewportLocatorCoalescer(initialRequest)
    private var viewportEvaluationPending: Boolean = false
    private var forceViewportAfterPending: Boolean = false
    private var lastViewportEvaluationAtMillis: Long = 0L
    private var configuration: EpubBrowserConfiguration = initialConfiguration
    private var configuredConfiguration: EpubBrowserConfiguration? = null
    private var documentReady: Boolean = false
    private var lastViewportSnapshot: EpubBrowserViewportSnapshot? = null
    private var pendingNavigationAction: ReaderTapAction? = null
    private var closed: Boolean = false

    fun update(request: EpubRenderRequest) {
        if (closed) return
        if (this.request === request) return
        this.request = request
        lastViewportSnapshot = null
        locatorEvents.updateRequest(request)
    }

    fun documentLoaded(
        loaded: EpubBrowserDocumentLoad,
        browserPublicationRootUrl: String,
        onError: (String) -> Unit,
    ) {
        if (closed) return
        documentReady = false
        configuredConfiguration = null
        webView.evaluateJavaScript(epubBrowserNavigationGuardScript(browserPublicationRootUrl)) { value, error ->
            if (value !is Boolean || !value || error != null) {
                onError(error?.localizedDescription ?: "The iOS EPUB navigation guard could not start.")
            }
            configureLoadedDocument(loaded)
        }
    }

    fun documentLoadFailed(
        rollbackRequest: EpubRenderRequest,
        oldDocumentAvailable: Boolean,
    ) {
        if (closed) return
        val oldDocumentWasReady = documentReady && configuredConfiguration != null
        update(rollbackRequest)
        if (!oldDocumentAvailable || !oldDocumentWasReady) {
            documentReady = false
            return
        }
        if (configuredConfiguration != configuration) {
            documentReady = false
            configureReadyDocument(rollbackRequest)
        } else {
            documentReady = true
            drainPendingNavigation()
        }
    }

    fun updateConfiguration(configuration: EpubBrowserConfiguration) {
        if (closed) return
        if (this.configuration == configuration) return
        this.configuration = configuration
        if (documentReady && canSampleViewport()) {
            documentReady = false
            configureReadyDocument(request)
        }
    }

    fun navigate(action: ReaderTapAction) {
        if (closed) return
        if (!documentReady || !canSampleViewport()) {
            pendingNavigationAction = action
            return
        }
        evaluateAction(
            sampledRequest = request,
            script = epubBrowserNavigationScript(request, configuration, action),
        )
    }

    fun handleTap(horizontalFraction: Double, verticalFraction: Double) {
        if (closed || !documentReady || !canSampleViewport()) return
        evaluateAction(
            sampledRequest = request,
            script = epubBrowserTapScript(
                request = request,
                configuration = configuration,
                horizontalFraction = horizontalFraction,
                verticalFraction = verticalFraction,
            ),
        )
    }

    override fun scrollViewDidScroll(scrollView: UIScrollView) {
        sampleViewport(force = false)
    }

    override fun scrollViewDidEndDragging(scrollView: UIScrollView, willDecelerate: Boolean) {
        if (!willDecelerate) sampleViewport(force = true)
    }

    override fun scrollViewDidEndDecelerating(scrollView: UIScrollView) {
        sampleViewport(force = true)
    }

    override fun scrollViewDidEndScrollingAnimation(scrollView: UIScrollView) {
        sampleViewport(force = true)
    }

    fun flushViewport() {
        if (closed || !documentReady || !canSampleViewport()) return
        val viewport = lastViewportSnapshot ?: return sampleViewport(force = true)
        val progression = currentNativeProgression(viewport)
        locatorEvents.offerViewport(
            viewport = viewport.copy(
                progression = progression,
                anchorCfi = viewport.anchorCfi.takeIf { progression == viewport.progression },
            ),
            nowMillis = currentTimeMillis(),
            force = true,
        )?.let(onLocatorChanged)
    }

    fun close() {
        closed = true
        documentReady = false
        forceViewportAfterPending = false
        pendingNavigationAction = null
        configuredConfiguration = null
        lastViewportSnapshot = null
    }

    private fun configureLoadedDocument(loaded: EpubBrowserDocumentLoad) {
        if (closed) return
        val sampledRequest = loaded.target.request
        val appliedConfiguration = configuration
        webView.evaluateJavaScript(
            epubBrowserConfigureScript(sampledRequest, appliedConfiguration),
        ) { value, error ->
            if (closed || request !== sampledRequest || !canSampleViewport()) return@evaluateJavaScript
            if (error != null || (value as? String)?.let(::decodeEpubBrowserActionResult) == null) {
                onError(error?.localizedDescription ?: "The iOS EPUB reader layout could not start.")
                return@evaluateJavaScript
            }
            if (configuration != appliedConfiguration) {
                configureLoadedDocument(loaded)
                return@evaluateJavaScript
            }
            val script = if (loaded.restoreInitialLocator) {
                epubBrowserRestoreScript(sampledRequest)
            } else {
                epubBrowserViewportScript(sampledRequest)
            }
            evaluateViewport(sampledRequest, script, force = true) {
                if (closed || request !== sampledRequest || !canSampleViewport()) {
                    return@evaluateViewport
                }
                if (configuration != appliedConfiguration) {
                    configureLoadedDocument(loaded)
                } else {
                    configuredConfiguration = appliedConfiguration
                    documentReady = true
                    drainPendingNavigation()
                }
            }
        }
    }

    private fun configureReadyDocument(sampledRequest: EpubRenderRequest) {
        if (closed) return
        val appliedConfiguration = configuration
        webView.evaluateJavaScript(
            epubBrowserConfigureScript(sampledRequest, appliedConfiguration),
        ) { value, error ->
            if (closed || request !== sampledRequest || !canSampleViewport()) return@evaluateJavaScript
            if (error != null || (value as? String)?.let(::decodeEpubBrowserActionResult) == null) {
                onError(error?.localizedDescription ?: "The iOS EPUB reader layout could not update.")
                return@evaluateJavaScript
            }
            if (configuration != appliedConfiguration) {
                configureReadyDocument(sampledRequest)
                return@evaluateJavaScript
            }
            evaluateViewport(
                sampledRequest,
                epubBrowserViewportScript(sampledRequest),
                force = true,
            ) {
                if (closed || request !== sampledRequest || !canSampleViewport()) {
                    return@evaluateViewport
                }
                if (configuration != appliedConfiguration) {
                    configureReadyDocument(sampledRequest)
                } else {
                    configuredConfiguration = appliedConfiguration
                    documentReady = true
                    drainPendingNavigation()
                }
            }
        }
    }

    private fun drainPendingNavigation() {
        if (closed || !documentReady || !canSampleViewport()) return
        val action = pendingNavigationAction ?: return
        pendingNavigationAction = null
        navigate(action)
    }

    private fun evaluateAction(sampledRequest: EpubRenderRequest, script: String) {
        webView.evaluateJavaScript(script) { value, error ->
            if (closed || !documentReady || !canSampleViewport() || request !== sampledRequest) {
                return@evaluateJavaScript
            }
            if (error != null) {
                onError(error.localizedDescription)
                return@evaluateJavaScript
            }
            val result = (value as? String)?.let(::decodeEpubBrowserActionResult) ?: return@evaluateJavaScript
            result.boundaryAction()?.let(onReaderTap)
        }
    }

    private fun sampleViewport(force: Boolean) {
        if (closed || !documentReady || !canSampleViewport()) return
        val now = currentTimeMillis()
        if (viewportEvaluationPending) {
            forceViewportAfterPending = forceViewportAfterPending || force
            return
        }
        if (!force && now - lastViewportEvaluationAtMillis < VIEWPORT_EVALUATION_INTERVAL_MILLIS) return
        evaluateViewport(request, epubBrowserViewportScript(request), force)
    }

    private fun evaluateViewport(
        sampledRequest: EpubRenderRequest,
        script: String,
        force: Boolean,
        onComplete: () -> Unit = {},
    ) {
        viewportEvaluationPending = true
        lastViewportEvaluationAtMillis = currentTimeMillis()
        webView.evaluateJavaScript(script) { value, _ ->
            viewportEvaluationPending = false
            if (!closed && canSampleViewport() && request === sampledRequest) {
                (value as? String)?.let(::decodeEpubBrowserViewport)?.let { viewport ->
                    lastViewportSnapshot = viewport
                    locatorEvents.offerViewport(
                        viewport = viewport,
                        nowMillis = currentTimeMillis(),
                        force = force,
                    )?.let(onLocatorChanged)
                }
            }
            onComplete()
            if (forceViewportAfterPending) {
                forceViewportAfterPending = false
                sampleViewport(force = true)
            }
        }
    }

    private fun currentNativeProgression(viewport: EpubBrowserViewportSnapshot): Double {
        val contentSize = webView.scrollView.contentSize.useContents { width to height }
        val boundsSize = webView.scrollView.bounds.useContents { size.width to size.height }
        val offset = webView.scrollView.contentOffset.useContents { x to y }
        val range = when (viewport.axis) {
            EpubBrowserScrollAxis.HORIZONTAL -> contentSize.first - boundsSize.first
            EpubBrowserScrollAxis.VERTICAL -> contentSize.second - boundsSize.second
        }.coerceAtLeast(0.0)
        if (range <= 0.0) return 0.0
        val physical = when (viewport.axis) {
            EpubBrowserScrollAxis.HORIZONTAL -> offset.first
            EpubBrowserScrollAxis.VERTICAL -> offset.second
        }.coerceIn(0.0, range)
        val logical = if (
            viewport.axis == EpubBrowserScrollAxis.HORIZONTAL &&
            viewport.direction == EpubBrowserScrollDirection.REVERSE
        ) {
            range - physical
        } else {
            physical
        }
        return (logical / range).coerceIn(0.0, 1.0)
    }

    private fun currentTimeMillis(): Long =
        (NSDate().timeIntervalSince1970 * 1_000.0).toLong().coerceAtLeast(0L)
}

@OptIn(ExperimentalForeignApi::class)
private class IosEpubTapHandler(
    webView: WKWebView,
    onTap: (horizontalFraction: Double, verticalFraction: Double) -> Unit,
) : NSObject() {
    private var webView: WKWebView? = webView
    private var onTap: ((Double, Double) -> Unit)? = onTap

    @ObjCAction
    fun handleTap(recognizer: UITapGestureRecognizer) {
        val activeWebView = webView ?: return
        val bounds = activeWebView.bounds.useContents { size.width to size.height }
        if (bounds.first <= 0.0 || bounds.second <= 0.0) return
        val location = recognizer.locationInView(activeWebView).useContents { x to y }
        onTap?.invoke(
            (location.first / bounds.first).coerceIn(0.0, 1.0),
            (location.second / bounds.second).coerceIn(0.0, 1.0),
        )
    }

    fun close() {
        onTap = null
        webView = null
    }
}

private class IosEpubResolverHolder(
    resolver: EpubPublicationResourceResolver,
    var policy: EpubBrowserUrlPolicy,
) {
    private val resolverSlot = EpubBrowserResolverSlot(resolver)

    fun stage(
        resolver: EpubPublicationResourceResolver,
        policy: EpubBrowserUrlPolicy,
    ) {
        resolverSlot.stage(resolver)
        this.policy = policy
    }

    fun commit() {
        resolverSlot.commit()
    }

    fun rollback() {
        resolverSlot.rollback()
    }

    fun resolve(browserUrl: String): EpubRenderResponse? {
        val canonical = policy.canonicalUrl(browserUrl) ?: return null
        return resolverSlot.read { resolver ->
            if (!runCatching { resolver.contains(canonical) }.getOrDefault(false)) return@read null
            resolver.resolve(canonical)
        }
    }

    fun close() {
        resolverSlot.close()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosEpubSchemeHandler(
    private val holder: IosEpubResolverHolder,
) : NSObject(), WKURLSchemeHandlerProtocol {
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val url = startURLSchemeTask.request.URL ?: return respondNotFound(startURLSchemeTask, null)
        val response = runCatching {
            holder.resolve(url.absoluteString.orEmpty())
        }.getOrNull() ?: return respondNotFound(startURLSchemeTask, url)
        val headers = response.headers.toMutableMap<Any?, Any>().apply {
            this["Cache-Control"] = "no-store"
            if (response.textEncoding != null) {
                this["Content-Type"] = "${response.mediaType}; charset=${response.textEncoding}"
            }
        }
        startURLSchemeTask.didReceiveResponse(
            NSHTTPURLResponse(url, 200, "HTTP/1.1", headers),
        )
        startURLSchemeTask.didReceiveData(response.bytes.toNSData())
        startURLSchemeTask.didFinish()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) = Unit

    private fun respondNotFound(task: WKURLSchemeTaskProtocol, url: NSURL?) {
        val responseUrl = url ?: NSURL.URLWithString("shinsou-epub://publication/not-found") ?: return
        task.didReceiveResponse(
            NSHTTPURLResponse(
                responseUrl,
                404,
                "HTTP/1.1",
                mapOf<Any?, Any>(
                    "Content-Type" to "text/plain; charset=UTF-8",
                    "Content-Security-Policy" to "default-src 'none'",
                    "Cache-Control" to "no-store",
                ),
            ),
        )
        task.didReceiveData(ByteArray(0).toNSData())
        task.didFinish()
    }
}

private data class IosEpubNavigationAdmission(
    val browserUrl: String,
    val generation: Long,
    val sameDocument: Boolean,
)

@OptIn(ExperimentalForeignApi::class)
private class IosEpubNavigationDelegate(
    private val allowNavigation: (String) -> IosEpubNavigationAdmission?,
    private val onLoaded: (String, Long) -> Unit,
    private val onLoadFailed: (String, Long) -> Unit,
    private val onPolicyError: (String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    private data class NavigationGeneration(
        val navigation: WKNavigation,
        val generation: Long,
    )

    private val navigationGenerations = mutableListOf<NavigationGeneration>()
    private val admittedGenerations = mutableListOf<Long>()

    fun registerHostNavigation(navigation: WKNavigation, generation: Long) {
        admittedGenerations.remove(generation)
        register(navigation, generation)
    }

    fun close() {
        navigationGenerations.clear()
        admittedGenerations.clear()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        val navigation = didStartProvisionalNavigation ?: return
        val registered = navigationGenerations.firstOrNull { it.navigation === navigation }
        if (registered != null) {
            admittedGenerations.remove(registered.generation)
            return
        }
        val generation = admittedGenerations.removeFirstOrNull() ?: return
        register(navigation, generation)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        val generation = takeGeneration(didFinishNavigation) ?: return
        webView.URL?.absoluteString?.let { url -> onLoaded(url, generation) }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        settleFailure(didFailNavigation, withError)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        settleFailure(didFailProvisionalNavigation, withError)
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val target = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
        val targetsMainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame == true
        val admission = if (targetsMainFrame) {
            runCatching { allowNavigation(target) }.getOrNull()
        } else {
            null
        }
        if (admission != null) {
            if (!admission.sameDocument) {
                admittedGenerations += admission.generation
                if (admittedGenerations.size > MAX_TRACKED_NAVIGATIONS) admittedGenerations.removeAt(0)
            }
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            if (admission.sameDocument) {
                // Fragment-only navigations need not create a WKNavigation or provisional callback.
                // Policy acceptance is their terminal event; evaluateJavaScript remains queued behind
                // WebKit applying the fragment before the viewport delegate samples it.
                onLoaded(admission.browserUrl, admission.generation)
            }
        } else {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            onPolicyError("Blocked EPUB navigation outside this publication.")
        }
    }

    private fun register(navigation: WKNavigation, generation: Long) {
        navigationGenerations.removeAll { it.navigation === navigation }
        navigationGenerations += NavigationGeneration(navigation, generation)
        if (navigationGenerations.size > MAX_TRACKED_NAVIGATIONS) navigationGenerations.removeAt(0)
    }

    private fun takeGeneration(navigation: WKNavigation?): Long? {
        navigation ?: return null
        val index = navigationGenerations.indexOfFirst { it.navigation === navigation }
        if (index < 0) return null
        return navigationGenerations.removeAt(index).generation.also { generation ->
            admittedGenerations.remove(generation)
        }
    }

    private fun settleFailure(navigation: WKNavigation?, error: NSError) {
        val generation = takeGeneration(navigation) ?: return
        if (error.code != NAVIGATION_CANCELLED_ERROR_CODE) {
            onLoadFailed(error.localizedDescription, generation)
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

private const val CONTENT_RULE_IDENTIFIER: String = "dev.aluo.shinsoux.epub.external-network-v2"
private const val EXTERNAL_NETWORK_BLOCK_RULES: String =
    "[{\"trigger\":{\"url-filter\":\"^(https?|wss?|ftps?|file|filesystem|blob):.*\"," +
        "\"url-filter-is-case-sensitive\":false},\"action\":{\"type\":\"block\"}}]"
private const val VIEWPORT_EVALUATION_INTERVAL_MILLIS: Long = 80L
private const val NAVIGATION_CANCELLED_ERROR_CODE: Long = -999L
private const val MAX_TRACKED_NAVIGATIONS: Int = 32
