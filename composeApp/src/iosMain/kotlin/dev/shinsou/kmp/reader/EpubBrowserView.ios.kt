package dev.shinsou.kmp.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
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
import platform.UIKit.UIScrollView
import platform.UIKit.UIScrollViewDelegateProtocol
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
public actual fun EpubBrowserView(
    request: EpubRenderRequest,
    modifier: Modifier,
    selectionRequestKey: Long,
    onLocatorChanged: (ReadingLocator.Epub) -> Unit,
    onSelectionChanged: (ReadingRange?) -> Unit,
    onError: (String) -> Unit,
) {
    val currentLocatorChanged = rememberUpdatedState(onLocatorChanged)
    val currentSelectionChanged = rememberUpdatedState(onSelectionChanged)
    val currentError = rememberUpdatedState(onError)
    val state = remember(request.publicationRootUrl) {
        val documentState = EpubBrowserDocumentState(request)
        val resolverHolder = IosEpubResolverHolder(
            resolver = EpubPublicationResourceResolver(request),
            policy = documentState.urlPolicy,
        )
        val schemeHandler = IosEpubSchemeHandler(resolverHolder)
        val configuration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            // Publisher execution stays disabled below and by CSP; the host still needs
            // evaluateJavaScript for an explicit DOM-selection snapshot.
            preferences.javaScriptEnabled = true
            defaultWebpagePreferences.allowsContentJavaScript = false
            setURLSchemeHandler(schemeHandler, request.securityPolicy.publicationScheme)
        }
        val webView = WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), configuration).apply {
            allowsBackForwardNavigationGestures = false
            allowsLinkPreview = false
        }
        val scrollDelegate = IosEpubScrollDelegate(
            webView = webView,
            initialRequest = request,
            canSampleViewport = { documentState.canSampleViewport },
            onLocatorChanged = { locator -> currentLocatorChanged.value.invoke(locator) },
        )
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
                    scrollDelegate.update(rollback.request)
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
        )
    }

    LaunchedEffect(state, request) {
        state.load(
            request = request,
            onError = { currentError.value.invoke(it) },
        )
    }

    LaunchedEffect(state, selectionRequestKey) {
        if (selectionRequestKey > 0L) {
            state.captureSelection { range -> currentSelectionChanged.value.invoke(range) }
        }
    }

    UIKitView(
        factory = { state.webView },
        modifier = modifier,
        update = {},
        onRelease = { released ->
            released.stopLoading()
            released.navigationDelegate = null
            released.scrollView.delegate = null
            state.close()
        },
    )
}

@OptIn(ExperimentalForeignApi::class)
private class IosEpubBrowserState(
    val webView: WKWebView,
    val resolverHolder: IosEpubResolverHolder,
    @Suppress("unused") val schemeHandler: IosEpubSchemeHandler,
    val navigationDelegate: IosEpubNavigationDelegate,
    @Suppress("unused") val scrollDelegate: IosEpubScrollDelegate,
    val documentState: EpubBrowserDocumentState,
) {
    private var contentRulesReady: Boolean = false
    private var compilingContentRules: Boolean = false
    private var pendingDocumentUrl: String? = null
    private var pendingError: ((String) -> Unit)? = null

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
        scrollDelegate.update(rollback.request)
        pendingDocumentUrl = null
        pendingError?.invoke(message)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosEpubScrollDelegate(
    private val webView: WKWebView,
    initialRequest: EpubRenderRequest,
    private val canSampleViewport: () -> Boolean,
    private val onLocatorChanged: (ReadingLocator.Epub) -> Unit,
) : NSObject(), UIScrollViewDelegateProtocol {
    var request: EpubRenderRequest = initialRequest
        private set
    private val locatorEvents = EpubViewportLocatorCoalescer(initialRequest)
    private var viewportEvaluationPending: Boolean = false
    private var forceViewportAfterPending: Boolean = false
    private var lastViewportEvaluationAtMillis: Long = 0L

    fun update(request: EpubRenderRequest) {
        if (this.request === request) return
        this.request = request
        locatorEvents.updateRequest(request)
    }

    fun documentLoaded(
        loaded: EpubBrowserDocumentLoad,
        browserPublicationRootUrl: String,
        onError: (String) -> Unit,
    ) {
        webView.evaluateJavaScript(epubBrowserNavigationGuardScript(browserPublicationRootUrl)) { value, error ->
            if (value !is Boolean || !value || error != null) {
                onError(error?.localizedDescription ?: "The iOS EPUB navigation guard could not start.")
            }
        }
        val script = if (loaded.restoreInitialLocator) {
            epubBrowserRestoreScript(loaded.target.request)
        } else {
            epubBrowserViewportScript(loaded.target.request)
        }
        evaluateViewport(loaded.target.request, script, force = true)
    }

    override fun scrollViewDidScroll(scrollView: UIScrollView) {
        sampleViewport(force = false)
    }

    private fun sampleViewport(force: Boolean) {
        if (!canSampleViewport()) return
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
    ) {
        viewportEvaluationPending = true
        lastViewportEvaluationAtMillis = currentTimeMillis()
        webView.evaluateJavaScript(script) { value, _ ->
            viewportEvaluationPending = false
            if (canSampleViewport() && request === sampledRequest) {
                (value as? String)?.let(::decodeEpubBrowserViewport)?.let { viewport ->
                    locatorEvents.offerViewport(
                        viewport = viewport,
                        nowMillis = currentTimeMillis(),
                        force = force,
                    )?.let(onLocatorChanged)
                }
            }
            if (forceViewportAfterPending) {
                forceViewportAfterPending = false
                sampleViewport(force = true)
            }
        }
    }

    private fun currentTimeMillis(): Long =
        (NSDate().timeIntervalSince1970 * 1_000.0).toLong().coerceAtLeast(0L)
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
