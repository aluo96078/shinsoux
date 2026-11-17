package dev.shinsou.kmp.ui.screens

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import dev.shinsou.kmp.domain.content.ContentRepresentation
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.reader.UnifiedReaderContent
import dev.shinsou.kmp.reader.coerceReaderPosition
import dev.shinsou.kmp.reader.isContinuousReaderMode
import dev.shinsou.kmp.reader.pagedReaderPosition
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
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.readerVolumeKeyAction
import dev.shinsou.kmp.ui.readerMayCrossChapterBoundary
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

@Composable
fun ReaderScreen(
    manga: Manga,
    chapter: Chapter,
    pages: List<ReaderPage>,
    settings: ReaderSettings,
    loading: Boolean,
    errorMessage: String?,
    inLibrary: Boolean,
    chapters: List<Chapter>,
    previousChapter: Chapter?,
    nextChapter: Chapter?,
    readerSessionId: String,
    initialPosition: ReaderPosition? = null,
    remotePositionSuggestion: ReaderPosition? = null,
    positionReportingEnabled: Boolean = true,
    volumeKeyRouter: ReaderVolumeKeyRouter? = null,
    systemBackRequest: Long = 0L,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onOpenWeb: (() -> Unit)?,
    onSettingsChange: (ReaderSettings) -> Unit,
    onPositionChanged: (ReaderPosition) -> Unit,
    onApplyRemotePosition: (ReaderPosition) -> Unit = {},
    onDismissRemotePositionSuggestion: () -> Unit = {},
    onToggleFavorite: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
    unifiedReaderContent: UnifiedReaderContent? = null,
    unifiedReaderInitialPageIndex: Int? = null,
    unifiedReaderInitialPageCount: Int? = null,
    /** True when the unified reader can translate this chapter: the toolbar then offers it. */
    unifiedReaderTranslationAvailable: Boolean = false,
    /** Opens manga translation (ADR-0005) for the page reader; null hides it. */
    onMangaTranslation: (() -> Unit)? = null,
    /** Turns manga translation on or off from the toolbar; [onMangaTranslation] then opens it on a long press. */
    onMangaTranslationToggle: (() -> Unit)? = null,
    mangaTranslationActive: Boolean = false,
    /** A sheet the app shows over the reader (manga translation) is open: the reader takes its keys back when it closes. */
    overlayOpen: Boolean = false,
    unifiedReaderRenderer: (@Composable (
        UnifiedReaderContent,
        Modifier,
        UnifiedReaderRenderState,
        (Int, Int) -> Unit,
        () -> Unit,
        (ReaderTapAction) -> Unit,
        (ReaderTapAction) -> Unit,
    ) -> Unit)? = null,
) {
    require((unifiedReaderContent == null) == (unifiedReaderRenderer == null)) {
        "Unified reader content and renderer must be supplied together"
    }
    require(unifiedReaderInitialPageIndex == null || unifiedReaderInitialPageIndex >= 0) {
        "Unified reader initial page index must be non-negative"
    }
    require(unifiedReaderInitialPageCount == null || unifiedReaderInitialPageCount > 0) {
        "Unified reader initial page count must be positive"
    }
    require(unifiedReaderInitialPageCount == null || unifiedReaderInitialPageIndex == null ||
        unifiedReaderInitialPageIndex < unifiedReaderInitialPageCount
    ) { "Unified reader initial page must be inside its page count" }
    val strings = LocalShinsouStrings.current
    val effectiveVolumeKeysEnabled = effectiveReaderVolumeKeysEnabled(settings.volumeKeys)
    val unifiedTextContent = unifiedReaderContent?.representation is ContentRepresentation.PlainText
    val unifiedEpubContent = unifiedReaderContent?.representation is ContentRepresentation.EpubSpine
    val unifiedImageContent = unifiedReaderContent?.representation is ContentRepresentation.ImageSequence
    val unifiedProseContent = unifiedTextContent || unifiedEpubContent
    var controlsVisible by remember(chapter.id, readerSessionId, unifiedProseContent) {
        mutableStateOf(!unifiedProseContent)
    }
    var settingsVisible by remember(readerSessionId) { mutableStateOf(false) }
    var chapterListVisible by remember(readerSessionId) { mutableStateOf(false) }
    var boundaryTransition by remember(chapter.id, readerSessionId) { mutableStateOf<ReaderChapterBoundary?>(null) }
    val unifiedReaderEnabled = unifiedReaderContent != null && unifiedReaderRenderer != null
    var translationRequest by remember { mutableStateOf(0L) }
    var translationToggle by remember { mutableStateOf(0L) }
    var unifiedTranslationActive by remember(readerSessionId) { mutableStateOf(false) }
    val onUnifiedTranslationActive: (Boolean) -> Unit = remember(readerSessionId) { { unifiedTranslationActive = it } }
    var unifiedReaderPageCount by remember(
        chapter.id,
        readerSessionId,
        unifiedReaderContent?.representation?.representationId,
        unifiedReaderInitialPageIndex,
        unifiedReaderInitialPageCount,
    ) {
        mutableStateOf(
            when (unifiedReaderContent?.representation) {
                is ContentRepresentation.ImageSequence -> unifiedReaderContent.navigation.itemCount
                is ContentRepresentation.PlainText,
                is ContentRepresentation.EpubSpine,
                -> unifiedReaderInitialPageCount
                    ?: unifiedReaderInitialPageIndex?.plus(1)
                    ?: 1
                null -> 0
            }.coerceAtLeast(0),
        )
    }
    var unifiedReaderPageCountMeasured by remember(
        chapter.id,
        readerSessionId,
        unifiedReaderContent?.representation?.representationId,
        settings.readingMode,
        settings.novelFontSizeSp,
        settings.novelLineHeightMultiplier,
        settings.novelMaxWidthDp,
    ) {
        mutableStateOf(
            unifiedReaderContent?.representation is ContentRepresentation.ImageSequence,
        )
    }
    val readerPageCount = if (unifiedReaderEnabled) unifiedReaderPageCount else pages.size
    var unifiedReaderPageIndex by remember(
        chapter.id,
        readerSessionId,
        unifiedReaderContent?.representation?.representationId,
        unifiedReaderInitialPageIndex,
    ) {
        mutableStateOf(
            (unifiedReaderInitialPageIndex
                ?: unifiedReaderContent?.navigation?.indexOf(unifiedReaderContent.initialLocator))
                ?.coerceIn(0, (unifiedReaderPageCount - 1).coerceAtLeast(0))
            ?: 0,
        )
    }
    var unifiedReaderPageRequest by remember(
        chapter.id,
        readerSessionId,
        unifiedReaderContent?.representation?.representationId,
        unifiedReaderInitialPageIndex,
    ) {
        mutableStateOf(UnifiedReaderPageRequest(unifiedReaderPageIndex))
    }
    var unifiedReaderNavigationAction by remember(chapter.id, readerSessionId) {
        mutableStateOf<ReaderTapAction?>(null)
    }
    var unifiedReaderNavigationRequestKey by remember(chapter.id, readerSessionId) { mutableStateOf(0L) }
    var currentPosition by remember(chapter.id, readerSessionId) {
        mutableStateOf(
            coerceReaderPosition(
                position = if (unifiedReaderEnabled) {
                    ReaderPosition(
                        readingMode = settings.readingMode,
                        pageIndex = unifiedReaderPageIndex,
                    )
                } else {
                    initialPosition ?: ReaderPosition(
                    readingMode = settings.readingMode,
                        pageIndex = chapter.lastPageRead.coerceAtLeast(0),
                    )
                },
                readingMode = settings.readingMode,
                pageCount = readerPageCount,
            ),
        )
    }
    var viewportRequestSerial by remember(chapter.id, readerSessionId) { mutableStateOf(0L) }
    val currentPage = currentPosition.pageIndex
    val unifiedTextReader = unifiedReaderEnabled &&
        unifiedReaderContent?.representation is ContentRepresentation.PlainText
    val unifiedEpubReader = unifiedReaderEnabled &&
        unifiedReaderContent?.representation is ContentRepresentation.EpubSpine
    val unifiedProseReader = unifiedTextReader || unifiedEpubReader
    val focusRequester = remember { FocusRequester() }
    val heldNavigationKeys = remember(chapter.id, readerSessionId) { mutableSetOf<Key>() }
    val platformContext = LocalPlatformContext.current

    fun currentModeIsContinuous(): Boolean =
        isContinuousReaderMode(settings.readingMode) ||
            (unifiedTextReader && isNovelContinuousMode(settings.readingMode))

    fun commitCurrentPosition() {
        if (positionReportingEnabled && !loading && readerPageCount > 0) {
            onPositionChanged(currentPosition)
        }
    }

    fun openPreviousChapter() {
        boundaryTransition = null
        if (previousChapter != null) {
            commitCurrentPosition()
            onPreviousChapter()
        }
    }

    fun openNextChapter() {
        boundaryTransition = null
        if (nextChapter != null) {
            commitCurrentPosition()
            onNextChapter()
        }
    }

    fun requestPage(index: Int) {
        when {
            index !in 0 until readerPageCount &&
                !readerMayCrossChapterBoundary(
                    proseReader = unifiedProseReader,
                    pageCountMeasured = unifiedReaderPageCountMeasured,
                    interactionBlocked = loading,
                ) -> Unit
            index < 0 && unifiedProseReader -> openPreviousChapter()
            index < 0 -> boundaryTransition = ReaderChapterBoundary.PREVIOUS
            index >= readerPageCount && unifiedProseReader -> openNextChapter()
            index >= readerPageCount -> boundaryTransition = ReaderChapterBoundary.NEXT
            readerPageCount > 0 -> {
                boundaryTransition = null
                if (unifiedReaderEnabled) {
                    unifiedReaderPageRequest = unifiedReaderPageRequest.request(
                        index.coerceIn(0, (readerPageCount - 1).coerceAtLeast(0)),
                    )
                }
                currentPosition = if (currentModeIsContinuous()) {
                    ReaderPosition(
                        readingMode = settings.readingMode,
                        pageIndex = index.coerceIn(0, (readerPageCount - 1).coerceAtLeast(0)),
                        normalizedOffsetFraction = 0.0,
                        resetEpoch = currentPosition.resetEpoch,
                    )
                } else {
                    pagedReaderPosition(
                        readingMode = settings.readingMode,
                        logicalPageIndex = index,
                        pageCount = readerPageCount,
                        resetEpoch = currentPosition.resetEpoch,
                    )
                }
                viewportRequestSerial++
            }
        }
    }

    fun requestUnifiedReaderNavigation(action: ReaderTapAction) {
        unifiedReaderNavigationAction = action
        unifiedReaderNavigationRequestKey++
    }

    fun handleUnifiedNavigationBoundary(action: ReaderTapAction) {
        if (loading) return
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> if (previousChapter != null) openPreviousChapter()
            ReaderTapAction.NEXT_PAGE -> if (nextChapter != null) openNextChapter()
            ReaderTapAction.TOGGLE_CHROME -> controlsVisible = !controlsVisible
        }
    }

    fun applyExternalPosition(position: ReaderPosition) {
        currentPosition = coerceReaderPosition(position, settings.readingMode, readerPageCount)
        if (unifiedReaderEnabled && readerPageCount > 0) {
            unifiedReaderPageRequest = unifiedReaderPageRequest.request(currentPosition.pageIndex)
        }
        boundaryTransition = null
        viewportRequestSerial++
    }

    fun handlePageAction(action: ReaderTapAction) {
        val boundary = boundaryTransition
        if (boundary != null) {
            when {
                action == ReaderTapAction.TOGGLE_CHROME -> controlsVisible = !controlsVisible
                boundary == ReaderChapterBoundary.PREVIOUS && action == ReaderTapAction.PREVIOUS_PAGE -> openPreviousChapter()
                boundary == ReaderChapterBoundary.NEXT && action == ReaderTapAction.NEXT_PAGE -> openNextChapter()
                else -> boundaryTransition = null
            }
            return
        }
        val imagePage = unifiedReaderPageRequest.navigationIndex(unifiedReaderPageIndex)
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> requestPage(
                (if (unifiedImageContent) imagePage else currentPage) - 1,
            )
            ReaderTapAction.TOGGLE_CHROME -> controlsVisible = !controlsVisible
            ReaderTapAction.NEXT_PAGE -> requestPage(
                (if (unifiedImageContent) imagePage else currentPage) + 1,
            )
        }
    }

    fun handleReaderBack(): Boolean {
        when {
            chapterListVisible -> chapterListVisible = false
            settingsVisible -> settingsVisible = false
            boundaryTransition != null -> boundaryTransition = null
            else -> {
                commitCurrentPosition()
                onClose()
            }
        }
        return true
    }

    /**
     * Hardware volume buttons follow the original Shinsou reader semantics: move exactly one
     * page, and cross a chapter boundary immediately. Tap zones and pager swipes intentionally
     * keep the in-reader transition card so users can preview the destination chapter.
     */
    fun handleVolumeKey(event: ReaderVolumeKeyEvent): Boolean {
        if (readerPageCount <= 0) return false
        // Unified image/EPUB surfaces already have a page graph. A parent loading flag must
        // not drop hardware paging, or manga volume keys stay silent after the first frame.
        if (loading && !unifiedReaderEnabled) return false

        val forward = event == ReaderVolumeKeyEvent.VOLUME_DOWN
        val action = if (forward) ReaderTapAction.NEXT_PAGE else ReaderTapAction.PREVIOUS_PAGE
        if (unifiedImageContent) {
            val target = imageSequenceNavigationTarget(
                currentIndex = unifiedReaderPageRequest.navigationIndex(unifiedReaderPageIndex),
                pageCount = readerPageCount,
                action = action,
            )
            if (target != null) {
                // Image pages already expose a durable page-index request channel. Use it
                // directly so a volume press cannot be mistaken for the initial value when the
                // pager is mounted or recomposed after chapter loading.
                requestPage(target)
            } else {
                val adjacentChapter = if (forward) nextChapter else previousChapter
                if (adjacentChapter == null) return false
                if (forward) openNextChapter() else openPreviousChapter()
            }
            return true
        }
        if (unifiedReaderEnabled) {
            requestUnifiedReaderNavigation(action)
            return true
        }
        val atBoundary = if (forward) {
            currentPage >= (readerPageCount - 1).coerceAtLeast(0)
        } else {
            currentPage <= 0
        }

        if (atBoundary) {
            // Plain text and EPUB start with only a provisional visual-page count. Until the
            // renderer reports its actual pagination, that provisional last page is not proof of
            // a chapter boundary and must never trigger an adjacent chapter.
            if (
                !readerMayCrossChapterBoundary(
                    proseReader = unifiedProseReader,
                    pageCountMeasured = unifiedReaderPageCountMeasured,
                    interactionBlocked = loading,
                )
            ) return false
            val adjacentChapter = if (forward) nextChapter else previousChapter
            if (adjacentChapter == null) return false
            if (forward) openNextChapter() else openPreviousChapter()
        } else {
            handlePageAction(action)
        }
        return true
    }

    DisposableEffect(settings.keepScreenOn) {
        onDispose { }
    }
    LaunchedEffect(chapter.id, readerSessionId, initialPosition, pages.size, unifiedReaderEnabled) {
        if (!unifiedReaderEnabled) initialPosition?.let(::applyExternalPosition)
    }
    LaunchedEffect(chapter.id, readerSessionId, settings.readingMode, readerPageCount) {
        currentPosition = coerceReaderPosition(currentPosition, settings.readingMode, readerPageCount)
        viewportRequestSerial++
    }
    // Modal sheets own a separate focus tree on desktop and iPadOS. Reclaim focus when they close
    // so hardware paging and Escape/back keep working without requiring another pointer click.
    // The toolbar's buttons take focus when clicked and go when it hides, and the app's own sheets
    // (manga translation) take it with them: the reader takes it back each time.
    LaunchedEffect(chapter.id, readerSessionId, settingsVisible, chapterListVisible, unifiedReaderEnabled, controlsVisible, overlayOpen) {
        heldNavigationKeys.clear()
        if (!settingsVisible && !chapterListVisible && !overlayOpen) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    ReaderVolumeKeyRegistration(
        chapterId = chapter.id,
        readerSessionId = readerSessionId,
        router = volumeKeyRouter,
        enabled = effectiveVolumeKeysEnabled,
        // A lambda has a fresh identity for each composition, so rememberUpdatedState below
        // cannot retain Kotlin/Native's structurally equal local callable reference from the
        // initial loading/pages-empty composition.
        onEvent = { event -> handleVolumeKey(event) },
    )
    LaunchedEffect(systemBackRequest) {
        if (systemBackRequest == 0L) return@LaunchedEffect
        handleReaderBack()
    }
    LaunchedEffect(
        chapter.id,
        readerSessionId,
        currentPosition,
        pages.size,
        loading,
        positionReportingEnabled,
        unifiedReaderEnabled,
    ) {
        if (!unifiedReaderEnabled && positionReportingEnabled && !loading && pages.isNotEmpty()) {
            onPositionChanged(currentPosition)
        }
    }
    LaunchedEffect(chapter.id, readerSessionId, currentPage, pages, unifiedReaderEnabled) {
        if (unifiedReaderEnabled) return@LaunchedEffect
        val loader = SingletonImageLoader.get(platformContext)
        val requests = readerPrefetchIndices(currentPage, pages.size).mapNotNull { index ->
            val page = pages[index]
            // Viewer-backed pages are resolved by their visible page composable. Prefetching the
            // placeholder would hand HTML to Coil and duplicate the viewer request.
            // A remote URL is never a valid prefetch input. Plugin pages must have already
            // crossed the host content plane and carry bytes; only explicitly local pages may
            // use their file URI directly. This keeps prefetch from becoming a second,
            // policy-free network path for legacy/foreign ReaderPage providers.
            if (page.imageResolver != null || readerImageData(page) == null) return@mapNotNull null
            val networkHeaders = NetworkHeaders.Builder().apply {
                page.headers.forEach { (name, value) -> set(name, value) }
            }.build()
            val request = ImageRequest.Builder(platformContext)
                .data(requireNotNull(readerImageData(page)))
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
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (!readerPreviewKeyIsHostOwned(
                        key = event.key,
                        embeddedSurfaceOwnsPaging = unifiedEpubReader,
                    )
                ) {
                    // Let the injected text/EPUB surface own swipe and keyboard paging. Hardware
                    // volume keys still belong to the host so manga paging cannot miss HID events.
                    return@onPreviewKeyEvent false
                }
                val suppressRepeat = when (event.key) {
                    Key.VolumeDown, Key.VolumeUp -> effectiveVolumeKeysEnabled
                    else -> isReaderNavigationKey(event.key)
                }
                if (suppressRepeat && !heldNavigationKeys.add(event.key)) {
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.VolumeDown -> {
                        readerVolumeKeyAction(
                            event = ReaderVolumeKeyEvent.VOLUME_DOWN,
                            readerOpen = true,
                            volumeKeysEnabled = effectiveVolumeKeysEnabled,
                        )?.let { handleVolumeKey(ReaderVolumeKeyEvent.VOLUME_DOWN) } != null
                    }
                    Key.VolumeUp -> {
                        readerVolumeKeyAction(
                            event = ReaderVolumeKeyEvent.VOLUME_UP,
                            readerOpen = true,
                            volumeKeysEnabled = effectiveVolumeKeysEnabled,
                        )?.let { handleVolumeKey(ReaderVolumeKeyEvent.VOLUME_UP) } != null
                    }
                    Key.DirectionRight, Key.NumPadDirectionRight, Key.Spacebar, Key.PageDown, Key.NumPadPageDown -> {
                        handlePageAction(
                            if (
                                (event.key == Key.DirectionRight || event.key == Key.NumPadDirectionRight) &&
                                settings.readingMode == ReadingMode.PAGER_RTL
                            ) {
                                ReaderTapAction.PREVIOUS_PAGE
                            } else {
                                ReaderTapAction.NEXT_PAGE
                            },
                        )
                        true
                    }
                    Key.DirectionLeft, Key.NumPadDirectionLeft, Key.PageUp, Key.NumPadPageUp -> {
                        handlePageAction(
                            if (
                                (event.key == Key.DirectionLeft || event.key == Key.NumPadDirectionLeft) &&
                                settings.readingMode == ReadingMode.PAGER_RTL
                            ) {
                                ReaderTapAction.NEXT_PAGE
                            } else {
                                ReaderTapAction.PREVIOUS_PAGE
                            },
                        )
                        true
                    }
                    Key.DirectionDown, Key.NumPadDirectionDown -> {
                        if (
                            settings.readingMode == ReadingMode.PAGER_VERTICAL ||
                            settings.readingMode == ReadingMode.WEBTOON ||
                            settings.readingMode == ReadingMode.CONTINUOUS_VERTICAL
                        ) {
                            handlePageAction(ReaderTapAction.NEXT_PAGE)
                        } else {
                            controlsVisible = !controlsVisible
                        }
                        true
                    }
                    Key.DirectionUp, Key.NumPadDirectionUp -> {
                        if (
                            settings.readingMode == ReadingMode.PAGER_VERTICAL ||
                            settings.readingMode == ReadingMode.WEBTOON ||
                            settings.readingMode == ReadingMode.CONTINUOUS_VERTICAL
                        ) {
                            handlePageAction(ReaderTapAction.PREVIOUS_PAGE)
                        } else {
                            controlsVisible = !controlsVisible
                        }
                        true
                    }
                    Key.Escape, Key.Back, Key.NavigatePrevious -> handleReaderBack()
                    else -> false
                }
            },
    ) {
        when {
            loading -> ShinsouLoadingIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                captionColor = Color.White,
            )
            errorMessage != null -> ReaderError(
                message = errorMessage,
                onRetry = onRetry,
                onOpenWeb = onOpenWeb,
                modifier = Modifier.align(Alignment.Center),
            )
            pages.isEmpty() && !unifiedReaderEnabled -> ReaderError(
                message = strings.text("This chapter has no pages."),
                onRetry = onRetry,
                onOpenWeb = onOpenWeb,
                modifier = Modifier.align(Alignment.Center),
            )
            unifiedReaderEnabled -> key(
                chapter.id,
                readerSessionId,
                requireNotNull(unifiedReaderContent).representation.representationId,
            ) {
                // the translation sheets give focus back here when they close, so the keys page again
                androidx.compose.runtime.CompositionLocalProvider(LocalReaderFocusReturn provides { runCatching { focusRequester.requestFocus() } }) {
                requireNotNull(unifiedReaderRenderer).invoke(
                    requireNotNull(unifiedReaderContent),
                    Modifier.fillMaxSize(),
                    UnifiedReaderRenderState(
                        settings = settings,
                        requestedPageIndex = unifiedReaderPageRequest.targetIndex,
                        pageRequestSerial = unifiedReaderPageRequest.serial,
                        navigationAction = unifiedReaderNavigationAction,
                        navigationRequestKey = unifiedReaderNavigationRequestKey,
                        controlsVisible = controlsVisible,
                        translationRequestSerial = translationRequest,
                        translationToggleSerial = translationToggle,
                        onTranslationActiveChanged = onUnifiedTranslationActive,
                    ),
                    { pageIndex, pageCount ->
                        val safePageCount = pageCount.coerceAtLeast(1)
                        unifiedReaderPageCountMeasured = true
                        unifiedReaderPageCount = safePageCount
                        if (pageIndex in 0 until safePageCount) {
                            unifiedReaderPageIndex = pageIndex
                            unifiedReaderPageRequest = unifiedReaderPageRequest.observe(pageIndex)
                            currentPosition = if (currentModeIsContinuous()) {
                                ReaderPosition(
                                    readingMode = settings.readingMode,
                                    pageIndex = pageIndex,
                                    normalizedOffsetFraction = 0.0,
                                    resetEpoch = currentPosition.resetEpoch,
                                )
                            } else {
                                pagedReaderPosition(
                                    readingMode = settings.readingMode,
                                    logicalPageIndex = pageIndex,
                                    pageCount = safePageCount,
                                    resetEpoch = currentPosition.resetEpoch,
                                )
                            }
                        }
                    },
                    { unifiedReaderPageCountMeasured = false },
                    ::handleUnifiedNavigationBoundary,
                    ::handlePageAction,
                )
                }
            }
            else -> key(chapter.id, settings.readingMode) {
                when (settings.readingMode) {
                ReadingMode.PAGER_LTR,
                ReadingMode.PAGER_RTL,
                -> ReaderHorizontalPager(
                    pages = pages,
                    currentPage = currentPage,
                    viewportRequestSerial = viewportRequestSerial,
                    settings = settings,
                    onPageChanged = { logicalPage ->
                        currentPosition = pagedReaderPosition(
                            readingMode = settings.readingMode,
                            logicalPageIndex = logicalPage,
                            pageCount = pages.size,
                            resetEpoch = currentPosition.resetEpoch,
                        )
                    },
                    onTap = ::handlePageAction,
                )

                ReadingMode.PAGER_VERTICAL -> ReaderVerticalPager(
                    pages = pages,
                    currentPage = currentPage,
                    viewportRequestSerial = viewportRequestSerial,
                    settings = settings,
                    onPageChanged = { logicalPage ->
                        currentPosition = pagedReaderPosition(
                            readingMode = settings.readingMode,
                            logicalPageIndex = logicalPage,
                            pageCount = pages.size,
                            resetEpoch = currentPosition.resetEpoch,
                        )
                    },
                    onTap = ::handlePageAction,
                )

                ReadingMode.WEBTOON,
                ReadingMode.CONTINUOUS_VERTICAL,
                -> ReaderWebtoon(
                    pages = pages,
                    position = currentPosition,
                    viewportRequestSerial = viewportRequestSerial,
                    settings = settings,
                    // Keep the in-memory cursor current on every viewport sample so an immediate
                    // close commits the actual final page, even before the settled-write debounce.
                    onPositionObserved = { currentPosition = it },
                    onPositionSettled = { settled ->
                        if (positionReportingEnabled && !loading) onPositionChanged(settled)
                    },
                    onTap = ::handlePageAction,
                )
            }
            }
        }

        // Prose readers cross chapters directly and never add a full-screen intermediary.
        boundaryTransition?.takeUnless { unifiedProseReader }?.let { boundary ->
            ReaderChapterTransitionCard(
                boundary = boundary,
                currentChapter = chapter,
                targetChapter = if (boundary == ReaderChapterBoundary.PREVIOUS) previousChapter else nextChapter,
                readingMode = settings.readingMode,
                onNavigate = {
                    if (boundary == ReaderChapterBoundary.PREVIOUS) openPreviousChapter() else openNextChapter()
                },
                onReturn = { boundaryTransition = null },
                onToggleChrome = { controlsVisible = !controlsVisible },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (
            settings.showPageNumber && !unifiedProseReader && !controlsVisible &&
            readerPageCount > 0 && boundaryTransition == null
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.66f),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            ) {
                Text(
                    "${currentPage + 1} / $readerPageCount",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }

        remotePositionSuggestion?.takeUnless { unifiedReaderEnabled }?.let { suggestion ->
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
                        strings.text("Another device has a newer position (page {0}).", suggestion.pageIndex + 1),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            applyExternalPosition(suggestion)
                            onApplyRemotePosition(suggestion)
                        },
                    ) {
                        Text(strings.text("Apply"))
                    }
                    TextButton(onClick = onDismissRemotePositionSuggestion) {
                        Text(strings.text("Dismiss"))
                    }
                }
            }
        }

        ReaderControls(
            visible = controlsVisible && boundaryTransition == null,
            manga = manga,
            chapter = chapter,
            page = currentPage,
            pageCount = readerPageCount,
            showPageControls = !unifiedReaderEnabled,
            minimal = unifiedProseReader,
            novelToolbar = unifiedTextReader,
            onTranslation = if (unifiedReaderEnabled && unifiedReaderTranslationAvailable) {
                { translationRequest++; Unit }
            } else if (!unifiedReaderEnabled) {
                onMangaTranslation
            } else {
                null
            },
            inLibrary = inLibrary,
            onTranslationToggle = if (unifiedReaderEnabled && unifiedReaderTranslationAvailable) {
                { translationToggle++; Unit }
            } else if (!unifiedReaderEnabled) {
                onMangaTranslationToggle
            } else {
                null
            },
            translationActive = if (unifiedReaderEnabled) unifiedTranslationActive else mangaTranslationActive,
            hasPreviousChapter = previousChapter != null,
            hasNextChapter = nextChapter != null,
            onClose = { handleReaderBack() },
            onOpenWeb = onOpenWeb,
            onPageChange = ::requestPage,
            onFavorite = onToggleFavorite,
            onPreviousChapter = {
                if (unifiedProseReader) openPreviousChapter()
                else boundaryTransition = ReaderChapterBoundary.PREVIOUS
            },
            onNextChapter = {
                if (unifiedProseReader) openNextChapter()
                else boundaryTransition = ReaderChapterBoundary.NEXT
            },
            onChapterList = { chapterListVisible = true },
            onSettings = { settingsVisible = true },
        )
    }

    if (settingsVisible) {
        ReaderSettingsSheet(
            settings = settings,
            textContent = unifiedProseReader,
            onChange = onSettingsChange,
            onDismiss = { settingsVisible = false },
        )
    }
    if (chapterListVisible) {
        ReaderChapterListSheet(
            chapters = chapters,
            currentChapterId = chapter.id,
            onSelect = { chapterId ->
                chapterListVisible = false
                if (chapterId != chapter.id) {
                    commitCurrentPosition()
                    onChapterSelected(chapterId)
                }
            },
            onDismiss = { chapterListVisible = false },
        )
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

private enum class ReaderChapterBoundary {
    PREVIOUS,
    NEXT,
}

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
                if (forward) strings.text("Finished {0}", currentChapter.name) else strings.text("Beginning of {0}", currentChapter.name),
                color = Color.White.copy(alpha = 0.66f),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                targetChapter?.name ?: if (forward) strings.text("No next chapter") else strings.text("No previous chapter"),
                color = if (targetChapter == null) Color.White.copy(alpha = 0.46f) else Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (targetChapter != null) {
                    strings.text("Use the page edge to continue")
                } else {
                    strings.text("Use the opposite edge to return")
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
                strings.text("Newest first · {0} chapters", chapters.size),
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
                                    contentDescription = strings.text("Read"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp),
                                )
                            } else {
                                Spacer(Modifier.size(19.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                Text(
                                    item.name.ifBlank { strings.text("Chapter {0}", item.chapterNumber) },
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
                                Icon(Icons.Filled.Bookmark, strings.text("Bookmarked"), Modifier.size(18.dp))
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
                    Text(strings.text("Open in browser"))
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
                onClickLabel = strings.text(if (active) "Turn translation off" else "Turn translation on"),
                onLongClickLabel = strings.text("Translation settings"),
                onClick = onToggle,
                onLongClick = onOpen,
            )
            .testTag("reader-translation-toggle"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Translate,
            strings.text("Translation"),
            tint = if (active) MaterialTheme.colorScheme.primary else tint,
        )
    }
}
