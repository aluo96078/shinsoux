package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.ui.SettingsEdit
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.ui.components.ShinsouLoadingIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.domain.content.UnifiedReaderContent
import dev.shinsou.kmp.reader.readerPrefetchIndices
import dev.shinsou.kmp.ui.effectiveReaderVolumeKeysEnabled
import dev.shinsou.kmp.reader.readerTapAction
import dev.shinsou.kmp.reader.readerImageTransform
import dev.shinsou.kmp.reader.ReaderPosition
import dev.shinsou.kmp.contract.ReaderPage
import dev.shinsou.kmp.ui.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.ReaderVolumeKeyHandlerSlot
import dev.shinsou.kmp.ui.ReaderVolumeKeyRouter
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.readerVolumeKeyAction
import dev.shinsou.kmp.ui.readerStatusBarsPadding
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.roundToInt

data class UnifiedReaderRenderState(
    val settings: ReaderSettings,
    val requestedPageIndex: Int,
    val pageRequestSerial: Long,
    val navigationAction: ReaderTapAction?,
    val navigationRequestKey: Long,
    val controlsVisible: Boolean,
    /** Bumped when the toolbar's translation button is pressed. */
    val translationRequestSerial: Long = 0L,
    /** Bumped when the toolbar's translation button is tapped: the translation turns on or off. */
    val translationToggleSerial: Long = 0L,
    /** Told whether the chapter is shown translated, so the button can show it. */
    val onTranslationActiveChanged: (Boolean) -> Unit = {},
)

/**
 * Gives the reader its keyboard focus back (arrow keys, Page Up/Down, Escape): for the sheets an
 * embedded reader opens, which take focus with them on the desktop and iPadOS.
 */
internal val LocalReaderFocusReturn = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/** What [ReaderScreen] shows: the title, the open chapter and its neighbours. */
internal data class ReaderScreenContent(
    val manga: Manga,
    val chapter: Chapter,
    val pages: List<ReaderPage>,
    val loading: Boolean,
    val errorMessage: String?,
    val inLibrary: Boolean,
    /** The chapters in story order, for the chapter list. */
    val chapters: List<Chapter>,
    val previousChapter: Chapter?,
    val nextChapter: Chapter?,
)

/** The reader session [ReaderScreen] belongs to, and what the host asks of it. */
internal class ReaderScreenSession(
    val id: String,
    /** Where the page reader starts instead of the chapter's stored page (a restored session). */
    val initialPosition: ReaderPosition? = null,
    val remotePositionSuggestion: ReaderPosition? = null,
    val positionReportingEnabled: Boolean = true,
    /** One-shot: a change asks the reader to go back once. */
    val systemBackRequest: Long = 0L,
)

/** What [ReaderScreen] reports to its host. */
internal data class ReaderScreenCallbacks(
    val onClose: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenWeb: (() -> Unit)?,
    val onSettingsChange: (SettingsEdit<ReaderSettings>) -> Unit,
    val onPositionChanged: (ReaderPosition) -> Unit,
    val onToggleFavorite: () -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onChapterSelected: (Long) -> Unit,
    val onApplyRemotePosition: (ReaderPosition) -> Unit = {},
    val onDismissRemotePositionSuggestion: () -> Unit = {},
)

/** Draws typed content: the content, its modifier and render state, then its page, page-count, boundary and tap callbacks. */
internal typealias UnifiedReaderRenderer = @Composable (
    UnifiedReaderContent,
    Modifier,
    UnifiedReaderRenderState,
    (Int, Int) -> Unit,
    () -> Unit,
    (ReaderTapAction) -> Unit,
    (ReaderTapAction) -> Unit,
) -> Unit

/** Typed content and the renderer that draws it; they are always supplied together. */
internal class UnifiedReaderSlot(
    val content: UnifiedReaderContent,
    val renderer: UnifiedReaderRenderer,
    val initialPageIndex: Int? = null,
    val initialPageCount: Int? = null,
    /** True when the unified reader can translate this chapter: the toolbar then offers it. */
    val translationAvailable: Boolean = false,
) {
    init {
        require(initialPageIndex == null || initialPageIndex >= 0) {
            "Unified reader initial page index must be non-negative"
        }
        require(initialPageCount == null || initialPageCount > 0) {
            "Unified reader initial page count must be positive"
        }
        require(initialPageCount == null || initialPageIndex == null || initialPageIndex < initialPageCount) {
            "Unified reader initial page must be inside its page count"
        }
    }
}

/** Manga translation (ADR-0005) of the page reader. */
internal class PageReaderTranslation(
    /** Opens manga translation; null hides it. */
    val onOpen: (() -> Unit)? = null,
    /** Turns manga translation on or off from the toolbar; [onOpen] then opens it on a long press. */
    val onToggle: (() -> Unit)? = null,
    val active: Boolean = false,
    /** A sheet the app shows over the reader is open: the reader takes its keys back when it closes. */
    val sheetOpen: Boolean = false,
)

@Composable
internal fun ReaderScreen(
    content: ReaderScreenContent,
    session: ReaderScreenSession,
    settings: ReaderSettings,
    callbacks: ReaderScreenCallbacks,
    modifier: Modifier = Modifier,
    unified: UnifiedReaderSlot? = null,
    translation: PageReaderTranslation = PageReaderTranslation(),
    volumeKeyRouter: ReaderVolumeKeyRouter? = null,
) {
    val chapter = content.chapter
    val pages = content.pages
    val loading = content.loading
    val state = remember { ReaderScreenState() }
    state.bind(
        ReaderScreenInputs(
            chapterId = chapter.id,
            sessionId = session.id,
            settings = settings,
            pageCount = pages.size,
            storedPageIndex = chapter.lastPageRead,
            initialPosition = session.initialPosition,
            loading = loading,
            hasPreviousChapter = content.previousChapter != null,
            hasNextChapter = content.nextChapter != null,
            positionReportingEnabled = session.positionReportingEnabled,
            unified = unified?.let {
                remember(it.content, it.initialPageIndex, it.initialPageCount) {
                    it.content.shape(it.initialPageIndex, it.initialPageCount)
                }
            },
        ),
        ReaderNavigationCallbacks(
            onClose = callbacks.onClose,
            onPositionChanged = callbacks.onPositionChanged,
            onPreviousChapter = callbacks.onPreviousChapter,
            onNextChapter = callbacks.onNextChapter,
            onChapterSelected = callbacks.onChapterSelected,
        ),
    )
    val effectiveVolumeKeysEnabled = effectiveReaderVolumeKeysEnabled(settings.volumeKeys)
    val onUnifiedTranslationActive: (Boolean) -> Unit = remember(session.id) { { state.unifiedTranslationActive = it } }
    val focusRequester = remember { FocusRequester() }
    val heldNavigationKeys = remember(chapter.id, session.id) { mutableSetOf<Key>() }

    ReaderScreenEffects(state, content, session, settings, unified != null, translation.sheetOpen, focusRequester, heldNavigationKeys)
    ReaderVolumeKeyRegistration(
        chapterId = chapter.id,
        readerSessionId = session.id,
        router = volumeKeyRouter,
        enabled = effectiveVolumeKeysEnabled,
        // A lambda has a fresh identity for each composition, so rememberUpdatedState below
        // cannot retain Kotlin/Native's structurally equal local callable reference from the
        // initial loading/pages-empty composition.
        onEvent = { event -> state.handleVolumeKey(event) },
    )

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            // Android is edge-to-edge. SwiftUI already supplies the iOS safe-area frame, so the
            // platform-aware modifier avoids reserving the Face ID/status-bar inset twice.
            .readerStatusBarsPadding()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) {
                    heldNavigationKeys.remove(event.key)
                    return@onPreviewKeyEvent false
                }
                // Let the injected text/EPUB surface own swipe and keyboard paging. Hardware
                // volume keys still belong to the host so manga paging cannot miss HID events.
                val hostOwned = readerPreviewKeyIsHostOwned(key = event.key, embeddedSurfaceOwnsPaging = state.epubReader)
                if (event.type != KeyEventType.KeyDown || !hostOwned) return@onPreviewKeyEvent false
                val suppressRepeat = when (event.key) {
                    Key.VolumeDown, Key.VolumeUp -> effectiveVolumeKeysEnabled
                    else -> isReaderNavigationKey(event.key)
                }
                if (suppressRepeat && !heldNavigationKeys.add(event.key)) return@onPreviewKeyEvent true
                state.onKeyDown(event.key, settings.readingMode, effectiveVolumeKeysEnabled)
            },
    ) {
        ReaderPageSurface(state, content, settings, callbacks, unified, focusRequester, onUnifiedTranslationActive)
        ReaderOverlays(state, content, session, settings, callbacks, unified, translation)
    }

    if (state.settingsVisible) {
        ReaderSettingsSheet(
            settings = settings,
            textContent = state.proseReader,
            onChange = callbacks.onSettingsChange,
            onDismiss = { state.settingsVisible = false },
        )
    }
    if (state.chapterListVisible) {
        ReaderChapterListSheet(
            chapters = content.chapters,
            currentChapterId = chapter.id,
            onSelect = state::selectChapter,
            onDismiss = { state.chapterListVisible = false },
        )
    }
}

/** The effects that follow the chapter, the session and the sheets. */
@Composable
private fun ReaderScreenEffects(
    state: ReaderScreenState,
    content: ReaderScreenContent,
    session: ReaderScreenSession,
    settings: ReaderSettings,
    unifiedEnabled: Boolean,
    overlayOpen: Boolean,
    focusRequester: FocusRequester,
    heldNavigationKeys: MutableSet<Key>,
) {
    val chapter = content.chapter
    val pages = content.pages
    val readerPageCount = state.pageCount
    LaunchedEffect(chapter.id, session.id, session.initialPosition, pages.size, unifiedEnabled) {
        if (!unifiedEnabled) session.initialPosition?.let(state::applyExternalPosition)
    }
    LaunchedEffect(chapter.id, session.id, settings.readingMode, readerPageCount) { state.coercePosition() }
    // Modal sheets own a separate focus tree on desktop and iPadOS. Reclaim focus when they close
    // so hardware paging and Escape/back keep working without requiring another pointer click.
    // The toolbar's buttons take focus when clicked and go when it hides, and the app's own sheets
    // (manga translation) take it with them: the reader takes it back each time.
    LaunchedEffect(
        chapter.id,
        session.id,
        state.settingsVisible,
        state.chapterListVisible,
        unifiedEnabled,
        state.controlsVisible,
        overlayOpen,
    ) {
        heldNavigationKeys.clear()
        if (!state.settingsVisible && !state.chapterListVisible && !overlayOpen) {
            // Throws only while the requester is not attached yet; focus then stays where it is.
            runCatchingCancellable { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(session.systemBackRequest) {
        if (session.systemBackRequest == 0L) return@LaunchedEffect
        state.handleReaderBack()
    }
    LaunchedEffect(
        chapter.id,
        session.id,
        state.currentPosition,
        pages.size,
        content.loading,
        session.positionReportingEnabled,
        unifiedEnabled,
    ) {
        val pageReaderShowing = !unifiedEnabled && !content.loading && pages.isNotEmpty()
        if (pageReaderShowing && session.positionReportingEnabled) state.commitCurrentPosition()
    }
    ReaderPagePrefetch(chapter.id, session.id, state.currentPage, pages, unifiedEnabled)
}

/** Prefetches the page reader's neighbouring pages that already carry their bytes. */
@Composable
private fun ReaderPagePrefetch(
    chapterId: Long,
    sessionId: String,
    currentPage: Int,
    pages: List<ReaderPage>,
    unifiedEnabled: Boolean,
) {
    val platformContext = LocalPlatformContext.current
    LaunchedEffect(chapterId, sessionId, currentPage, pages, unifiedEnabled) {
        if (unifiedEnabled) return@LaunchedEffect
        val loader = SingletonImageLoader.get(platformContext)
        val requests = readerPrefetchIndices(currentPage, pages.size).mapNotNull { index ->
            val page = pages[index]
            // Viewer-backed pages are resolved by their visible page composable. Prefetching the
            // placeholder would hand HTML to Coil and duplicate the viewer request.
            // A remote URL is never a valid prefetch input. Plugin pages must have already
            // crossed the host content plane and carry bytes; only explicitly local pages may
            // use their file URI directly. This keeps prefetch from becoming a second,
            // policy-free network path for legacy/foreign ReaderPage providers.
            val data = readerImageData(page)
            if (page.imageResolver != null || data == null) return@mapNotNull null
            val networkHeaders = NetworkHeaders.Builder().apply {
                page.headers.forEach { (name, value) -> set(name, value) }
            }.build()
            val request = ImageRequest.Builder(platformContext)
                .data(data)
                .httpHeaders(networkHeaders)
                .readerImageTransform(page.imageTransform, readerImageNaturalHeight(page))
                .build()
            loader.enqueue(request)
        }
        try {
            awaitCancellation()
        } finally {
            requests.forEach { it.dispose() }
        }
    }
}

/** A key pressed on the reader (not a repeat of a held one); true when the reader used it. */
private fun ReaderScreenState.onKeyDown(key: Key, readingMode: ReadingMode, volumeKeysEnabled: Boolean): Boolean = when (key) {
    Key.VolumeDown, Key.VolumeUp -> {
        val event = if (key == Key.VolumeDown) ReaderVolumeKeyEvent.VOLUME_DOWN else ReaderVolumeKeyEvent.VOLUME_UP
        readerVolumeKeyAction(event = event, readerOpen = true, volumeKeysEnabled = volumeKeysEnabled)
            ?.let { handleVolumeKey(event) } != null
    }
    Key.Escape, Key.Back, Key.NavigatePrevious -> handleReaderBack()
    else -> readerKeyAction(key, readingMode)?.let { action ->
        if (action == ReaderTapAction.TOGGLE_CHROME) toggleControls() else handlePageAction(action)
        true
    } ?: false
}

/**
 * What a paging key does in [readingMode]: right and left follow the reading direction, down and
 * up page vertical modes and otherwise show or hide the controls; null for any other key.
 */
private fun readerKeyAction(key: Key, readingMode: ReadingMode): ReaderTapAction? {
    val vertical = readingMode == ReadingMode.PAGER_VERTICAL ||
        readingMode == ReadingMode.WEBTOON ||
        readingMode == ReadingMode.CONTINUOUS_VERTICAL
    val rtl = readingMode == ReadingMode.PAGER_RTL
    return when (key) {
        Key.DirectionRight, Key.NumPadDirectionRight ->
            if (rtl) ReaderTapAction.PREVIOUS_PAGE else ReaderTapAction.NEXT_PAGE
        Key.Spacebar, Key.PageDown, Key.NumPadPageDown -> ReaderTapAction.NEXT_PAGE
        Key.DirectionLeft, Key.NumPadDirectionLeft ->
            if (rtl) ReaderTapAction.NEXT_PAGE else ReaderTapAction.PREVIOUS_PAGE
        Key.PageUp, Key.NumPadPageUp -> ReaderTapAction.PREVIOUS_PAGE
        Key.DirectionDown, Key.NumPadDirectionDown ->
            if (vertical) ReaderTapAction.NEXT_PAGE else ReaderTapAction.TOGGLE_CHROME
        Key.DirectionUp, Key.NumPadDirectionUp ->
            if (vertical) ReaderTapAction.PREVIOUS_PAGE else ReaderTapAction.TOGGLE_CHROME
        else -> null
    }
}

/** The chapter itself: loading, an error, the unified renderer or the page reader. */
@Composable
private fun BoxScope.ReaderPageSurface(
    state: ReaderScreenState,
    content: ReaderScreenContent,
    settings: ReaderSettings,
    callbacks: ReaderScreenCallbacks,
    unified: UnifiedReaderSlot?,
    focusRequester: FocusRequester,
    onUnifiedTranslationActive: (Boolean) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val errorMessage = content.errorMessage
    when {
        // A tap shows the controls (and their close button) while a chapter loads: a novel's
        // chapter opens with them hidden, so a touch screen otherwise had no way out until it came.
        content.loading -> Box(
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { state.toggleControls() } },
        ) {
            ShinsouLoadingIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                captionColor = Color.White,
            )
        }
        errorMessage != null -> ReaderError(
            message = errorMessage,
            onRetry = callbacks.onRetry,
            onOpenWeb = callbacks.onOpenWeb,
            modifier = Modifier.align(Alignment.Center),
        )
        content.pages.isEmpty() && unified == null -> ReaderError(
            message = strings.text(StringKey.THIS_CHAPTER_HAS_NO_PAGES),
            onRetry = callbacks.onRetry,
            onOpenWeb = callbacks.onOpenWeb,
            modifier = Modifier.align(Alignment.Center),
        )
        unified != null -> key(content.chapter.id, state.sessionId, unified.content.representation.representationId) {
            // the translation sheets give focus back here when they close, so the keys page again
            // (requestFocus throws only while the requester is detached; focus then stays put)
            androidx.compose.runtime.CompositionLocalProvider(
                LocalReaderFocusReturn provides { runCatching { focusRequester.requestFocus() } },
            ) {
                unified.renderer(
                    unified.content,
                    Modifier.fillMaxSize(),
                    UnifiedReaderRenderState(
                        settings = settings,
                        requestedPageIndex = state.pageRequest.targetIndex,
                        pageRequestSerial = state.pageRequest.serial,
                        navigationAction = state.navigationAction,
                        navigationRequestKey = state.navigationRequestKey,
                        controlsVisible = state.controlsVisible,
                        translationRequestSerial = state.translationRequest,
                        translationToggleSerial = state.translationToggle,
                        onTranslationActiveChanged = onUnifiedTranslationActive,
                    ),
                    state::onUnifiedPageObserved,
                    { state.pageCountMeasured = false },
                    state::handleUnifiedNavigationBoundary,
                    state::handlePageAction,
                )
            }
        }
        else -> key(content.chapter.id, settings.readingMode) { PageReader(state, content.pages, settings, callbacks, content.loading) }
    }
}

/** The page reader in the chosen reading mode. */
@Composable
private fun PageReader(
    state: ReaderScreenState,
    pages: List<ReaderPage>,
    settings: ReaderSettings,
    callbacks: ReaderScreenCallbacks,
    loading: Boolean,
) {
    when (settings.readingMode) {
        ReadingMode.PAGER_LTR,
        ReadingMode.PAGER_RTL,
        -> ReaderHorizontalPager(
            pages = pages,
            currentPage = state.currentPage,
            viewportRequestSerial = state.viewportRequestSerial,
            settings = settings,
            onPageChanged = state::onPagerPageChanged,
            onTap = state::handlePageAction,
        )

        ReadingMode.PAGER_VERTICAL -> ReaderVerticalPager(
            pages = pages,
            currentPage = state.currentPage,
            viewportRequestSerial = state.viewportRequestSerial,
            settings = settings,
            onPageChanged = state::onPagerPageChanged,
            onTap = state::handlePageAction,
        )

        ReadingMode.WEBTOON,
        ReadingMode.CONTINUOUS_VERTICAL,
        -> ReaderWebtoon(
            pages = pages,
            position = state.currentPosition,
            viewportRequestSerial = state.viewportRequestSerial,
            settings = settings,
            // Keep the in-memory cursor current on every viewport sample so an immediate
            // close commits the actual final page, even before the settled-write debounce.
            onPositionObserved = { state.currentPosition = it },
            onPositionSettled = { settled ->
                if (state.positionReportingEnabled && !loading) callbacks.onPositionChanged(settled)
            },
            onTap = state::handlePageAction,
        )
    }
}

/** What lies over the chapter: the boundary card, the page number, a remote position and the controls. */
@Composable
private fun BoxScope.ReaderOverlays(
    state: ReaderScreenState,
    content: ReaderScreenContent,
    session: ReaderScreenSession,
    settings: ReaderSettings,
    callbacks: ReaderScreenCallbacks,
    unified: UnifiedReaderSlot?,
    translation: PageReaderTranslation,
) {
    val unifiedEnabled = unified != null
    val readerPageCount = state.pageCount
    // Prose readers cross chapters directly and never add a full-screen intermediary.
    state.boundaryTransition?.takeUnless { state.proseReader }?.let { boundary ->
        ReaderChapterTransitionCard(
            boundary = boundary,
            currentChapter = content.chapter,
            targetChapter = if (boundary == ReaderChapterBoundary.PREVIOUS) content.previousChapter else content.nextChapter,
            readingMode = settings.readingMode,
            onNavigate = {
                if (boundary == ReaderChapterBoundary.PREVIOUS) state.openPreviousChapter() else state.openNextChapter()
            },
            onReturn = { state.boundaryTransition = null },
            onToggleChrome = state::toggleControls,
            modifier = Modifier.fillMaxSize(),
        )
    }
    val chromeHidden = !state.controlsVisible && state.boundaryTransition == null
    val pageNumberShown = settings.showPageNumber && !state.proseReader && readerPageCount > 0
    if (pageNumberShown && chromeHidden) {
        Surface(
            color = Color.Black.copy(alpha = 0.66f),
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        ) {
            Text(
                "${state.currentPage + 1} / $readerPageCount",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
    session.remotePositionSuggestion?.takeUnless { unifiedEnabled }?.let { suggestion ->
        RemotePositionSuggestion(
            suggestion = suggestion,
            onApply = {
                state.applyExternalPosition(suggestion)
                callbacks.onApplyRemotePosition(suggestion)
            },
            onDismiss = callbacks.onDismissRemotePositionSuggestion,
        )
    }
    ReaderToolbar(state, content, callbacks, unified, translation)
}

/** The reader's controls: page slider, chapters, translation, settings. */
@Composable
private fun ReaderToolbar(
    state: ReaderScreenState,
    content: ReaderScreenContent,
    callbacks: ReaderScreenCallbacks,
    unified: UnifiedReaderSlot?,
    translation: PageReaderTranslation,
) {
    val unifiedEnabled = unified != null
    val unifiedTranslation = unified != null && unified.translationAvailable
    ReaderControls(
        visible = state.controlsVisible && state.boundaryTransition == null,
        manga = content.manga,
        chapter = content.chapter,
        page = state.currentPage,
        pageCount = state.pageCount,
        showPageControls = !unifiedEnabled,
        minimal = state.proseReader,
        novelToolbar = state.textReader,
        onTranslation = when {
            unifiedTranslation -> state::requestTranslation
            !unifiedEnabled -> translation.onOpen
            else -> null
        },
        inLibrary = content.inLibrary,
        onTranslationToggle = when {
            unifiedTranslation -> state::toggleTranslation
            !unifiedEnabled -> translation.onToggle
            else -> null
        },
        translationActive = if (unifiedEnabled) state.unifiedTranslationActive else translation.active,
        hasPreviousChapter = content.previousChapter != null,
        hasNextChapter = content.nextChapter != null,
        onClose = { state.handleReaderBack() },
        onOpenWeb = callbacks.onOpenWeb,
        onPageChange = state::requestPage,
        onFavorite = callbacks.onToggleFavorite,
        onPreviousChapter = state::previousChapterControl,
        onNextChapter = state::nextChapterControl,
        onChapterList = { state.chapterListVisible = true },
        onSettings = { state.settingsVisible = true },
    )
}

/** Another device has a newer position: apply it or keep reading here. */
@Composable
private fun BoxScope.RemotePositionSuggestion(
    suggestion: ReaderPosition,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 6.dp,
        modifier = Modifier
            .align(Alignment.TopCenter)
            // Below the controls, which keep clear of the iOS Dynamic Island.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(top = 76.dp, start = 18.dp, end = 18.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                strings.text(StringKey.ANOTHER_DEVICE_HAS_A_NEWER_POSITION_PAGE, suggestion.pageIndex + 1),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onApply) {
                Text(strings.text(StringKey.APPLY))
            }
            TextButton(onClick = onDismiss) {
                Text(strings.text(StringKey.DISMISS))
            }
        }
    }
}

/**
 * Keeps the native volume-key registration outside ReaderScreen's already large Compose state
 * machine. Besides making ownership explicit, this prevents JVM shrinkers from merging the
 * registration slot with unrelated long-valued reader state while rebuilding stack-map frames.
 */
@Composable
private fun ReaderVolumeKeyRegistration(
    chapterId: Long,
    readerSessionId: String,
    router: ReaderVolumeKeyRouter?,
    enabled: Boolean,
    onEvent: (ReaderVolumeKeyEvent) -> Boolean,
) {
    // ReaderScreen is first composed while the async chapter request still exposes an empty page
    // list. A long-lived collector that captures that first composition keeps calling the stale
    // handler forever. Keep the collector stable while forwarding every event to the handler from
    // the latest composition.
    val handlerSlot = remember(chapterId, readerSessionId) { ReaderVolumeKeyHandlerSlot() }
    val currentOnEvent by rememberUpdatedState(onEvent)
    handlerSlot.update { event ->
        readerVolumeKeyAction(
            event = event,
            readerOpen = true,
            volumeKeysEnabled = enabled,
        ) != null && currentOnEvent(event)
    }
    DisposableEffect(router, handlerSlot) {
        val registration = router?.register(handlerSlot::dispatch)
        onDispose { registration?.unregister() }
    }
}

internal fun readerSliderPage(value: Float, pageCount: Int): Int? =
    if (pageCount <= 0) null else value.roundToInt().coerceIn(0, pageCount - 1)

/** Adjacent page turns animate; slider jumps land immediately instead of flipping every page. */
internal fun readerPageSeekAnimates(fromPage: Int, toPage: Int, animatePageTransitions: Boolean): Boolean =
    animatePageTransitions && abs(toPage - fromPage) <= 1

@Composable
private fun ReaderChapterTransitionCard(
    boundary: ReaderChapterBoundary,
    currentChapter: Chapter,
    targetChapter: Chapter?,
    readingMode: ReadingMode,
    onNavigate: () -> Unit,
    onReturn: () -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    val forward = boundary == ReaderChapterBoundary.NEXT
    Box(
        modifier
            .background(Color.Black)
            .pointerInput(boundary, targetChapter?.id, readingMode) {
                detectTapGestures { position ->
                    when (readerTapAction(position.x, size.width.toFloat(), readingMode)) {
                        ReaderTapAction.TOGGLE_CHROME -> onToggleChrome()
                        ReaderTapAction.NEXT_PAGE -> if (forward && targetChapter != null) onNavigate() else onReturn()
                        ReaderTapAction.PREVIOUS_PAGE -> if (!forward && targetChapter != null) onNavigate() else onReturn()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                if (forward) strings.text(StringKey.FINISHED_X, currentChapter.name) else strings.text(StringKey.BEGINNING_OF_X, currentChapter.name),
                color = Color.White.copy(alpha = 0.66f),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                targetChapter?.name ?: if (forward) strings.text(StringKey.NO_NEXT_CHAPTER) else strings.text(StringKey.NO_PREVIOUS_CHAPTER),
                color = if (targetChapter == null) Color.White.copy(alpha = 0.46f) else Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (targetChapter != null) {
                    strings.text(StringKey.USE_THE_PAGE_EDGE_TO_CONTINUE)
                } else {
                    strings.text(StringKey.USE_THE_OPPOSITE_EDGE_TO_RETURN)
                },
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderChapterListSheet(
    chapters: List<Chapter>,
    currentChapterId: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                strings.chapters,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            )
            Text(
                strings.text(StringKey.NEWEST_FIRST_X_CHAPTERS, chapters.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
            ) {
                items(chapters.asReversed(), key = Chapter::id) { item ->
                    val current = item.id == currentChapterId
                    TextButton(
                        onClick = { onSelect(item.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (item.read) {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = strings.text(StringKey.READ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp),
                                )
                            } else {
                                Spacer(Modifier.size(19.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                Text(
                                    item.name.ifBlank { strings.text(StringKey.CHAPTER_X, item.chapterNumber) },
                                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                item.scanlator?.takeIf(String::isNotBlank)?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (item.bookmark) {
                                Icon(Icons.Filled.Bookmark, strings.text(StringKey.BOOKMARKED), Modifier.size(18.dp))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ReaderError(
    message: String,
    onRetry: () -> Unit,
    onOpenWeb: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            onOpenWeb?.let { openWeb ->
                Button(onClick = openWeb) {
                    Icon(Icons.Outlined.OpenInNew, null)
                    Spacer(Modifier.width(7.dp))
                    Text(strings.text(StringKey.OPEN_IN_BROWSER))
                }
            }
            Button(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(7.dp))
                Text(strings.retry)
            }
        }
    }
}

/**
 * The toolbar's translation button: a tap turns the translation on or off, a long press (or a
 * right click) opens its panel. Lit while the translation is on.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TranslationToggleButton(active: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, tint: Color = Color.White) {
    val strings = LocalShinsouStrings.current
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent)
            .pointerInput(onOpen) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
                        event.changes.forEach { it.consume() }
                        onOpen()
                    }
                }
            }
            .combinedClickable(
                onClickLabel = strings.text(if (active) StringKey.TURN_TRANSLATION_OFF else StringKey.TURN_TRANSLATION_ON),
                onLongClickLabel = strings.text(StringKey.TRANSLATION_SETTINGS),
                onClick = onToggle,
                onLongClick = onOpen,
            )
            .testTag("reader-translation-toggle"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Translate,
            strings.text(StringKey.TRANSLATION),
            tint = if (active) MaterialTheme.colorScheme.primary else tint,
        )
    }
}
