package dev.shinsou.kmp.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import dev.shinsou.kmp.reader.protocol.EpubProtocolRegistry
import dev.shinsou.kmp.reader.protocol.EpubProtocolSession
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import javafx.animation.AnimationTimer
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javax.swing.SwingUtilities

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
        DesktopEpubBrowserState(
            initialRequest = request,
            onLocatorChanged = { locator ->
                SwingUtilities.invokeLater { currentLocatorChanged.value.invoke(locator) }
            },
            onError = { message ->
                SwingUtilities.invokeLater { currentError.value.invoke(message) }
            },
        )
    }

    LaunchedEffect(state, request) {
        state.load(request)
    }

    LaunchedEffect(state, selectionRequestKey) {
        if (selectionRequestKey > 0L) {
            state.captureSelection { range ->
                SwingUtilities.invokeLater { currentSelectionChanged.value.invoke(range) }
            }
        }
    }

    SwingPanel(
        factory = { state.createPanel() },
        modifier = modifier,
    )

    DisposableEffect(state) {
        onDispose { state.close() }
    }
}

private class DesktopEpubBrowserState(
    initialRequest: EpubRenderRequest,
    private val onLocatorChanged: (ReadingLocator.Epub) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val owner: Any = Any()
    private val closed = AtomicBoolean(false)
    private val protocolSession: EpubProtocolSession = EpubProtocolRegistry.register(
        owner,
        EpubPublicationResourceResolver(initialRequest),
    )
    private val documentState = EpubBrowserDocumentState(
        initialRequest = initialRequest,
        browserPublicationRootUrl = protocolSession.browserPublicationRootUrl,
    )
    @Volatile private var engine: WebEngine? = null
    @Volatile private var pendingDocumentUrl: String = documentState.initialBrowserDocumentUrl
    private var locatorRequest: EpubRenderRequest = initialRequest
    private val locatorEvents = EpubViewportLocatorCoalescer(initialRequest)
    private var viewportTimer: AnimationTimer? = null

    init {
        checkNotNull(documentState.navigationRequested(pendingDocumentUrl))
        pendingDocumentUrl = taggedPendingNavigation(pendingDocumentUrl)
        // Forces the service provider to resolve before JavaFX's network thread starts.
        runCatching { URI.create(pendingDocumentUrl).toURL() }.getOrElse {
            EpubProtocolRegistry.unregister(owner, protocolSession)
            throw IllegalStateException("The shinsou-epub URL provider is unavailable", it)
        }
    }

    fun createPanel(): JFXPanel = JFXPanel().also { panel ->
        Platform.runLater {
            if (closed.get()) return@runLater
            val view = WebView().apply {
                isContextMenuEnabled = false
            }
            val browser = view.engine.apply {
                // CSP blocks publisher scripts; host evaluation remains available for portable
                // scroll/selection locators without altering publisher HTML or CSS.
                isJavaScriptEnabled = true
                loadWorker.stateProperty().addListener { _, _, state ->
                    if (closed.get()) return@addListener
                    when (state) {
                        Worker.State.SUCCEEDED -> location?.let { location ->
                            val generation = epubBrowserLoadGeneration(location) ?: return@let
                            val loaded = documentState.documentLoaded(location, generation) ?: run {
                                if (!documentState.ownsPendingLoad(generation)) {
                                    // JavaFX may repeat SUCCEEDED for an already committed document.
                                    if (documentState.pendingTarget == null) installNavigationGuard(this)
                                    return@let
                                }
                                EpubProtocolRegistry.rollback(owner, protocolSession)
                                val rollback = documentState.documentLoadFailed(generation) ?: return@let
                                activate(rollback.request)
                                if (epubBrowserUrlWithoutLoadGeneration(location) != rollback.browserUrl) {
                                    stageNavigation(rollback.browserUrl)?.let { retryUrl -> this.load(retryUrl) }
                                }
                                onError("The desktop EPUB browser committed an unexpected document.")
                                return@let
                            }
                            EpubProtocolRegistry.commit(owner, protocolSession)
                            activate(loaded.target.request)
                            installNavigationGuard(this)
                            if (loaded.restoreInitialLocator) {
                                emitViewport(
                                    browser = this,
                                    sampledRequest = loaded.target.request,
                                    script = epubBrowserRestoreScript(loaded.target.request),
                                    force = true,
                                )
                            } else {
                                emitViewport(
                                    browser = this,
                                    sampledRequest = loaded.target.request,
                                    script = epubBrowserViewportScript(loaded.target.request),
                                    force = true,
                                )
                            }
                        }
                        Worker.State.FAILED -> {
                            val failedLocation = location ?: return@addListener
                            val generation = epubBrowserLoadGeneration(failedLocation) ?: return@addListener
                            if (!documentState.ownsPendingLoad(generation)) return@addListener
                            EpubProtocolRegistry.rollback(owner, protocolSession)
                            val rollback = documentState.documentLoadFailed(generation) ?: return@addListener
                            activate(rollback.request)
                            if (epubBrowserUrlWithoutLoadGeneration(failedLocation) != rollback.browserUrl) {
                                stageNavigation(rollback.browserUrl)?.let { retryUrl -> this.load(retryUrl) }
                            }
                            onError(
                                loadWorker.exception?.message
                                    ?: "The desktop EPUB browser could not load this resource.",
                            )
                        }
                        else -> Unit
                    }
                }
                locationProperty().addListener { _, oldLocation, newLocation ->
                    if (closed.get() || newLocation.isNullOrBlank()) return@addListener
                    val target = documentState.navigationRequested(newLocation)
                    if (target != null) {
                        if (target.hasExplicitAnchor) {
                            val generation = requireNotNull(documentState.pendingLoadGeneration)
                            documentState.sameDocumentNavigationLoaded(newLocation, generation)?.let { loaded ->
                                EpubProtocolRegistry.commit(owner, protocolSession)
                                activate(loaded.target.request)
                                Platform.runLater {
                                    emitViewport(
                                        browser = this,
                                        sampledRequest = loaded.target.request,
                                        script = epubBrowserViewportScript(loaded.target.request),
                                        force = true,
                                    )
                                }
                                return@addListener
                            }
                        }
                        val taggedLocation = taggedPendingNavigation(newLocation)
                        if (taggedLocation != newLocation) {
                            loadWorker.cancel()
                            load(taggedLocation)
                        }
                        return@addListener
                    }
                    loadWorker.cancel()
                    onError("Blocked EPUB navigation outside this publication.")
                    if (!oldLocation.isNullOrBlank() &&
                        documentState.urlPolicy.canonicalUrl(oldLocation) != null
                    ) {
                        stageNavigation(oldLocation)?.let { retryUrl -> this.load(retryUrl) }
                    }
                }
            }
            engine = browser
            viewportTimer = object : AnimationTimer() {
                private var previousSampleNanos: Long = 0L

                override fun handle(now: Long) {
                    if (!documentState.canSampleViewport) return
                    if (now - previousSampleNanos < VIEWPORT_SAMPLE_NANOS) return
                    previousSampleNanos = now
                    val request = documentState.activeRequest
                    emitViewport(
                        browser = browser,
                        sampledRequest = request,
                        script = epubBrowserViewportScript(request),
                        force = false,
                        nowMillis = (now / 1_000_000L).coerceAtLeast(0L),
                    )
                }
            }.also { timer -> timer.start() }
            panel.scene = Scene(view)
            browser.load(pendingDocumentUrl)
        }
    }

    fun load(request: EpubRenderRequest) {
        val updated = EpubPublicationResourceResolver(request)
        Platform.runLater {
            if (closed.get()) {
                updated.close()
                return@runLater
            }
            engine?.loadWorker?.cancel()
            EpubProtocolRegistry.update(owner, protocolSession, updated)
            pendingDocumentUrl = documentState.updateHostRequest(
                request = request,
                browserPublicationRootUrl = protocolSession.browserPublicationRootUrl,
            )
            pendingDocumentUrl = taggedPendingNavigation(pendingDocumentUrl)
            engine?.apply {
                load(pendingDocumentUrl)
            }
        }
    }

    private fun stageNavigation(browserUrl: String): String? {
        documentState.navigationRequested(browserUrl) ?: return null
        return taggedPendingNavigation(browserUrl)
    }

    private fun taggedPendingNavigation(browserUrl: String): String =
        epubBrowserUrlWithLoadGeneration(
            browserUrl,
            requireNotNull(documentState.pendingLoadGeneration),
        )

    fun captureSelection(onRange: (ReadingRange?) -> Unit) {
        Platform.runLater {
            if (closed.get()) return@runLater onRange(null)
            if (!documentState.canSampleViewport) return@runLater onRange(null)
            val request = documentState.activeRequest
            val json = runCatching {
                engine?.executeScript(epubBrowserSelectionScript(request)) as? String
            }.getOrNull()
            val snapshot = json?.let(::decodeEpubBrowserSelection)
            onRange(snapshot?.let(request::rangeForSelection))
        }
    }

    private fun activate(request: EpubRenderRequest) {
        if (locatorRequest === request) return
        locatorRequest = request
        locatorEvents.updateRequest(request)
    }

    private fun installNavigationGuard(browser: WebEngine) {
        runCatching {
            browser.executeScript(
                epubBrowserNavigationGuardScript(protocolSession.browserPublicationRootUrl),
            )
        }.onFailure {
            onError("The desktop EPUB navigation guard could not start.")
        }
    }

    private fun emitViewport(
        browser: WebEngine,
        sampledRequest: EpubRenderRequest,
        script: String,
        force: Boolean,
        nowMillis: Long = (System.nanoTime() / 1_000_000L).coerceAtLeast(0L),
    ) {
        if (closed.get() || !documentState.canSampleViewport ||
            documentState.activeRequest !== sampledRequest
        ) return
        val json = runCatching { browser.executeScript(script) as? String }.getOrNull() ?: return
        val viewport = decodeEpubBrowserViewport(json) ?: return
        locatorEvents.offerViewport(
            viewport = viewport,
            nowMillis = nowMillis,
            force = force,
        )?.let(onLocatorChanged)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        EpubProtocolRegistry.unregister(owner, protocolSession)
        Platform.runLater {
            viewportTimer?.stop()
            viewportTimer = null
            engine?.load(null)
            engine = null
        }
    }
}

private const val VIEWPORT_SAMPLE_NANOS: Long = 100_000_000L
