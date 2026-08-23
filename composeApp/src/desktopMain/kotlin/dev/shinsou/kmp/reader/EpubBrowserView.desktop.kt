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
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javax.swing.SwingUtilities
import kotlin.math.abs

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
        DesktopEpubBrowserState(
            initialRequest = request,
            initialConfiguration = configuration,
            onLocatorChanged = { locator ->
                SwingUtilities.invokeLater { currentLocatorChanged.value.invoke(locator) }
            },
            onReaderTap = { action ->
                SwingUtilities.invokeLater { currentReaderTap.value.invoke(action) }
            },
            onError = { message ->
                SwingUtilities.invokeLater { currentError.value.invoke(message) }
            },
        )
    }

    LaunchedEffect(state, configuration) {
        state.updateConfiguration(configuration)
    }

    LaunchedEffect(state, request) {
        state.load(request)
    }

    LaunchedEffect(state, navigationAction, navigationRequestKey) {
        if (navigationRequestKey > 0L && navigationAction != null) {
            state.navigate(navigationAction, configuration)
        }
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
    initialConfiguration: EpubBrowserConfiguration,
    private val onLocatorChanged: (ReadingLocator.Epub) -> Unit,
    private val onReaderTap: (ReaderTapAction) -> Unit,
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
    private var configuration: EpubBrowserConfiguration = initialConfiguration
    private var configuredRequest: EpubRenderRequest? = null
    private var configuredValue: EpubBrowserConfiguration? = null
    private var pendingReaderAction: ReaderTapAction? = null
    private val heldNavigationKeys = mutableSetOf<KeyCode>()
    private var wheelDistance: Double = 0.0
    private var lastWheelEventNanos: Long = 0L
    private var lastWheelTurnNanos: Long = 0L

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
            installInputHandlers(view)
            val browser = view.engine.apply {
                // Publisher resources remain immutable and CSP blocks their scripts. Host-authored
                // evaluation installs the isolated reader style and drives portable reader actions.
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
                            val configured = installReaderConfiguration(this, loaded.target.request)
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
                            if (configured) drainPendingReaderAction()
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
                                    val configured = installReaderConfiguration(this, loaded.target.request)
                                    emitViewport(
                                        browser = this,
                                        sampledRequest = loaded.target.request,
                                        script = epubBrowserViewportScript(loaded.target.request),
                                        force = true,
                                    )
                                    if (configured) drainPendingReaderAction()
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
            view.requestFocus()
            browser.load(pendingDocumentUrl)
            Platform.runLater {
                if (!closed.get()) view.requestFocus()
            }
        }
    }

    fun updateConfiguration(next: EpubBrowserConfiguration) {
        Platform.runLater {
            if (closed.get() || configuration == next) return@runLater
            configuration = next
            heldNavigationKeys.clear()
            resetWheelState()
            val browser = engine ?: return@runLater
            if (!documentState.canSampleViewport) return@runLater
            val request = documentState.activeRequest
            if (installReaderConfiguration(browser, request)) {
                emitViewport(
                    browser = browser,
                    sampledRequest = request,
                    script = epubBrowserViewportScript(request),
                    force = true,
                )
            }
        }
    }

    fun navigate(action: ReaderTapAction, requestedConfiguration: EpubBrowserConfiguration) {
        Platform.runLater {
            if (closed.get()) return@runLater
            if (configuration != requestedConfiguration) {
                configuration = requestedConfiguration
                heldNavigationKeys.clear()
                resetWheelState()
            }
            performReaderAction(action)
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
        configuredRequest = null
        configuredValue = null
    }

    private fun installInputHandlers(view: WebView) {
        view.addEventFilter(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.button != MouseButton.PRIMARY || event.clickCount != 1 || !event.isStillSincePress) {
                return@addEventFilter
            }
            val width = view.width
            val height = view.height
            if (width <= 0.0 || height <= 0.0) return@addEventFilter
            val horizontalFraction = (event.x / width).coerceIn(0.0, 1.0)
            val verticalFraction = (event.y / height).coerceIn(0.0, 1.0)
            // Let WebKit settle links and selections first. The common tap script then rejects
            // interactive elements and non-empty selections before applying the reader zones.
            Platform.runLater { performReaderTap(horizontalFraction, verticalFraction) }
        }
        view.addEventFilter(KeyEvent.KEY_RELEASED) { event ->
            heldNavigationKeys.remove(event.code)
        }
        view.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            val action = readerActionForKey(event) ?: return@addEventFilter
            event.consume()
            if (!heldNavigationKeys.add(event.code)) return@addEventFilter
            performReaderAction(action)
        }
        view.addEventFilter(ScrollEvent.SCROLL) { event ->
            if (handlePagedScroll(event)) event.consume()
        }
        view.addEventFilter(ScrollEvent.SCROLL_FINISHED) {
            wheelDistance = 0.0
            lastWheelEventNanos = 0L
        }
    }

    private fun readerActionForKey(event: KeyEvent): ReaderTapAction? {
        if (event.isAltDown || event.isControlDown || event.isMetaDown) return null
        val rightToLeft = configuration.readingMode == EpubBrowserReadingMode.PAGED_RIGHT_TO_LEFT
        return when (event.code) {
            KeyCode.RIGHT, KeyCode.KP_RIGHT ->
                if (rightToLeft) ReaderTapAction.PREVIOUS_PAGE else ReaderTapAction.NEXT_PAGE
            KeyCode.LEFT, KeyCode.KP_LEFT ->
                if (rightToLeft) ReaderTapAction.NEXT_PAGE else ReaderTapAction.PREVIOUS_PAGE
            KeyCode.DOWN, KeyCode.KP_DOWN, KeyCode.PAGE_DOWN -> ReaderTapAction.NEXT_PAGE
            KeyCode.UP, KeyCode.KP_UP, KeyCode.PAGE_UP -> ReaderTapAction.PREVIOUS_PAGE
            KeyCode.SPACE ->
                if (event.isShiftDown) ReaderTapAction.PREVIOUS_PAGE else ReaderTapAction.NEXT_PAGE
            else -> null
        }
    }

    private fun handlePagedScroll(event: ScrollEvent): Boolean {
        if (configuration.readingMode == EpubBrowserReadingMode.CONTINUOUS_VERTICAL) return false
        if (event.isAltDown || event.isControlDown || event.isMetaDown) return false
        val delta = if (abs(event.deltaY) >= abs(event.deltaX)) {
            -event.deltaY
        } else if (configuration.readingMode == EpubBrowserReadingMode.PAGED_RIGHT_TO_LEFT) {
            -event.deltaX
        } else {
            event.deltaX
        }
        if (!delta.isFinite() || delta == 0.0) return true
        val now = System.nanoTime()
        if (lastWheelEventNanos == 0L || now - lastWheelEventNanos > WHEEL_SEQUENCE_RESET_NANOS) {
            wheelDistance = 0.0
        }
        lastWheelEventNanos = now
        wheelDistance += delta
        if (abs(wheelDistance) < WHEEL_TURN_THRESHOLD) return true
        val action = if (wheelDistance > 0.0) ReaderTapAction.NEXT_PAGE else ReaderTapAction.PREVIOUS_PAGE
        wheelDistance = 0.0
        if (lastWheelTurnNanos != 0L && now - lastWheelTurnNanos < WHEEL_TURN_COOLDOWN_NANOS) return true
        lastWheelTurnNanos = now
        performReaderAction(action)
        return true
    }

    private fun resetWheelState() {
        wheelDistance = 0.0
        lastWheelEventNanos = 0L
        lastWheelTurnNanos = 0L
    }

    private fun performReaderTap(horizontalFraction: Double, verticalFraction: Double) {
        if (closed.get() || !documentState.canSampleViewport) return
        val browser = engine ?: return
        val request = documentState.activeRequest
        if (!ensureReaderConfiguration(browser, request)) return
        executeReaderAction(
            browser = browser,
            request = request,
            script = epubBrowserTapScript(
                request = request,
                configuration = configuration,
                horizontalFraction = horizontalFraction,
                verticalFraction = verticalFraction,
            ),
        )
    }

    private fun performReaderAction(action: ReaderTapAction) {
        if (closed.get()) return
        if (!documentState.canSampleViewport) {
            pendingReaderAction = action
            return
        }
        val browser = engine ?: run {
            pendingReaderAction = action
            return
        }
        val request = documentState.activeRequest
        if (!ensureReaderConfiguration(browser, request)) return
        executeReaderAction(
            browser = browser,
            request = request,
            script = epubBrowserNavigationScript(request, configuration, action),
        )
    }

    private fun executeReaderAction(
        browser: WebEngine,
        request: EpubRenderRequest,
        script: String,
    ) {
        if (closed.get() || !documentState.canSampleViewport || documentState.activeRequest !== request) return
        val json = runCatching { browser.executeScript(script) as? String }.getOrNull()
        val result = json?.let(::decodeEpubBrowserActionResult)
        if (result == null) {
            onError("The desktop EPUB reader action could not be completed.")
            return
        }
        result.boundaryAction()?.let(onReaderTap)
        if (result.outcome == EpubBrowserActionOutcome.MOVED) {
            Platform.runLater {
                emitViewport(
                    browser = browser,
                    sampledRequest = request,
                    script = epubBrowserViewportScript(request),
                    force = true,
                )
            }
        }
    }

    private fun ensureReaderConfiguration(browser: WebEngine, request: EpubRenderRequest): Boolean =
        if (configuredRequest === request && configuredValue == configuration) {
            true
        } else {
            installReaderConfiguration(browser, request)
        }

    private fun installReaderConfiguration(browser: WebEngine, request: EpubRenderRequest): Boolean {
        if (closed.get() || !documentState.canSampleViewport || documentState.activeRequest !== request) return false
        val json = runCatching {
            browser.executeScript(epubBrowserConfigureScript(request, configuration)) as? String
        }.getOrNull()
        val configured = json?.let(::decodeEpubBrowserActionResult) != null
        if (configured) {
            configuredRequest = request
            configuredValue = configuration
        } else {
            onError("The desktop EPUB reader layout could not be applied.")
        }
        return configured
    }

    private fun drainPendingReaderAction() {
        val action = pendingReaderAction ?: return
        pendingReaderAction = null
        Platform.runLater { performReaderAction(action) }
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
            heldNavigationKeys.clear()
            resetWheelState()
            pendingReaderAction = null
            configuredRequest = null
            configuredValue = null
            engine?.load(null)
            engine = null
        }
    }
}

private const val VIEWPORT_SAMPLE_NANOS: Long = 100_000_000L
private const val WHEEL_TURN_THRESHOLD: Double = 24.0
private const val WHEEL_SEQUENCE_RESET_NANOS: Long = 500_000_000L
private const val WHEEL_TURN_COOLDOWN_NANOS: Long = 320_000_000L
