package dev.shinsou.kmp.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.transformations
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.ReaderColorFilter
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.reader.ReaderViewportSample
import dev.shinsou.kmp.reader.UnifiedReaderContent
import dev.shinsou.kmp.reader.coerceReaderPosition
import dev.shinsou.kmp.reader.continuousReaderPosition
import dev.shinsou.kmp.reader.isContinuousReaderMode
import dev.shinsou.kmp.reader.pagedReaderPosition
import dev.shinsou.kmp.reader.readerLogicalPageIndex
import dev.shinsou.kmp.reader.readerPhysicalPageIndex
import dev.shinsou.kmp.reader.readerPrefetchIndices
import dev.shinsou.kmp.ui.effectiveReaderVolumeKeysEnabled
import dev.shinsou.kmp.ui.shouldShowReaderVolumeKeySetting
import dev.shinsou.kmp.reader.readerTapAction
import dev.shinsou.kmp.reader.restoredReaderPageOffsetPixels
import dev.shinsou.kmp.reader.toCoilTransformation
import dev.shinsou.kmp.sync.v2.ReaderPosition
import dev.shinsou.kmp.ui.ReaderPage
import dev.shinsou.kmp.ui.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.ReaderVolumeKeyHandlerSlot
import dev.shinsou.kmp.ui.ReaderVolumeKeyRouter
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.readerNavigationBarsPadding
import dev.shinsou.kmp.ui.readerVolumeKeyAction
import dev.shinsou.kmp.ui.readerMayCrossChapterBoundary
import dev.shinsou.kmp.ui.readerStatusBarsPadding
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

data class UnifiedReaderRenderState(
    val settings: ReaderSettings,
    val requestedPageIndex: Int,
    val pageRequestSerial: Long,
    val navigationAction: ReaderTapAction?,
    val navigationRequestKey: Long,
    val controlsVisible: Boolean,
)

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
    val unifiedProseContent = unifiedTextContent || unifiedEpubContent
    var controlsVisible by remember(chapter.id, readerSessionId, unifiedProseContent) {
        mutableStateOf(!unifiedProseContent)
    }
    var settingsVisible by remember(readerSessionId) { mutableStateOf(false) }
    var chapterListVisible by remember(readerSessionId) { mutableStateOf(false) }
    var boundaryTransition by remember(chapter.id, readerSessionId) { mutableStateOf<ReaderChapterBoundary?>(null) }
    val unifiedReaderEnabled = unifiedReaderContent != null && unifiedReaderRenderer != null
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
    var unifiedReaderPageRequestSerial by remember(chapter.id, readerSessionId) { mutableStateOf(0L) }
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
                    unifiedReaderPageIndex = index.coerceIn(0, (readerPageCount - 1).coerceAtLeast(0))
                    unifiedReaderPageRequestSerial++
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
            unifiedReaderPageIndex = currentPosition.pageIndex
            unifiedReaderPageRequestSerial++
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
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> requestPage(currentPage - 1)
            ReaderTapAction.TOGGLE_CHROME -> controlsVisible = !controlsVisible
            ReaderTapAction.NEXT_PAGE -> requestPage(currentPage + 1)
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
        if (loading || readerPageCount <= 0) return false

        val forward = event == ReaderVolumeKeyEvent.VOLUME_DOWN
        val action = if (forward) ReaderTapAction.NEXT_PAGE else ReaderTapAction.PREVIOUS_PAGE
        if (unifiedProseReader) {
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
    LaunchedEffect(chapter.id, readerSessionId, settingsVisible, chapterListVisible, unifiedReaderEnabled) {
        heldNavigationKeys.clear()
        if ((!unifiedReaderEnabled || unifiedTextReader) && !settingsVisible && !chapterListVisible) {
            focusRequester.requestFocus()
        }
    }
    // ReaderScreen is first composed while the async chapter request still exposes an empty page
    // list. A long-lived collector that captures that first composition keeps calling the stale
    // `pages.isEmpty()` handler forever; toggling the setting happened to restart it after pages
    // loaded, which made the feature appear to require an off/on cycle. Keep the collector stable
    // while forwarding every event to the handler from the latest composition instead.
    val volumeKeyHandlerSlot = remember(chapter.id, readerSessionId) {
        ReaderVolumeKeyHandlerSlot()
    }
    volumeKeyHandlerSlot.update { event ->
        val action = readerVolumeKeyAction(
            event = event,
            readerOpen = true,
            volumeKeysEnabled = effectiveVolumeKeysEnabled,
        )
        if (action == null) false else handleVolumeKey(event)
    }
    DisposableEffect(volumeKeyRouter, volumeKeyHandlerSlot) {
        val registration = volumeKeyRouter?.register(volumeKeyHandlerSlot::dispatch)
        onDispose { registration?.unregister() }
    }
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
        val requests = readerPrefetchIndices(currentPage, pages.size).map { index ->
            val page = pages[index]
            val networkHeaders = NetworkHeaders.Builder().apply {
                page.headers.forEach { (name, value) -> set(name, value) }
            }.build()
            val request = ImageRequest.Builder(platformContext)
                .data(page.imageUrl)
                .httpHeaders(networkHeaders)
                .apply {
                    page.imageTransform?.let { transformations(it.toCoilTransformation()) }
                }
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
                if (unifiedReaderEnabled && !unifiedTextReader && event.key != Key.Escape && event.key != Key.Back &&
                    event.key != Key.NavigatePrevious
                ) {
                    // Let the injected text/EPUB surface own navigation keys. The host retains
                    // only the reader-level close/back behavior.
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
            loading -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
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
                requireNotNull(unifiedReaderRenderer).invoke(
                    requireNotNull(unifiedReaderContent),
                    Modifier.fillMaxSize(),
                    UnifiedReaderRenderState(
                        settings = settings,
                        requestedPageIndex = unifiedReaderPageIndex,
                        pageRequestSerial = unifiedReaderPageRequestSerial,
                        navigationAction = unifiedReaderNavigationAction,
                        navigationRequestKey = unifiedReaderNavigationRequestKey,
                        controlsVisible = controlsVisible,
                    ),
                    { pageIndex, pageCount ->
                        val safePageCount = pageCount.coerceAtLeast(1)
                        unifiedReaderPageCountMeasured = true
                        unifiedReaderPageCount = safePageCount
                        if (pageIndex in 0 until safePageCount) {
                            unifiedReaderPageIndex = pageIndex
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
            else -> key(chapter.id, settings.readingMode) {
                when (settings.readingMode) {
                ReadingMode.PAGER_LTR,
                ReadingMode.PAGER_RTL,
                -> ReaderHorizontalPager(
                    pages = pages,
                    currentPage = currentPage,
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
            inLibrary = inLibrary,
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

@Composable
private fun ReaderHorizontalPager(
    pages: List<ReaderPage>,
    currentPage: Int,
    settings: ReaderSettings,
    onPageChanged: (Int) -> Unit,
    onTap: (ReaderTapAction) -> Unit,
) {
    val mode = settings.readingMode
    val pagerState = rememberPagerState(
        initialPage = readerPhysicalPageIndex(currentPage, pages.size, mode),
    ) { pages.size }

    LaunchedEffect(currentPage, pages.size, mode, settings.animatePageTransitions) {
        val target = readerPhysicalPageIndex(currentPage, pages.size, mode)
        if (pagerState.currentPage != target) {
            if (settings.animatePageTransitions) pagerState.animateScrollToPage(target)
            else pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(pagerState, mode) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect {
            onPageChanged(readerLogicalPageIndex(it, pages.size, mode))
        }
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 2,
        modifier = Modifier.fillMaxSize(),
    ) { physicalIndex ->
        ReaderPageImage(
            page = pages[readerLogicalPageIndex(physicalIndex, pages.size, mode)],
            settings = settings,
            zoomEnabled = true,
            onTap = onTap,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ReaderVerticalPager(
    pages: List<ReaderPage>,
    currentPage: Int,
    settings: ReaderSettings,
    onPageChanged: (Int) -> Unit,
    onTap: (ReaderTapAction) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = currentPage) { pages.size }
    LaunchedEffect(currentPage, pages.size, settings.animatePageTransitions) {
        if (pagerState.currentPage != currentPage) {
            if (settings.animatePageTransitions) pagerState.animateScrollToPage(currentPage)
            else pagerState.scrollToPage(currentPage)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect(onPageChanged)
    }
    VerticalPager(
        state = pagerState,
        beyondViewportPageCount = 2,
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        ReaderPageImage(
            page = pages[index],
            settings = settings,
            zoomEnabled = true,
            onTap = onTap,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ReaderWebtoon(
    pages: List<ReaderPage>,
    position: ReaderPosition,
    viewportRequestSerial: Long,
    settings: ReaderSettings,
    onPositionObserved: (ReaderPosition) -> Unit,
    onPositionSettled: (ReaderPosition) -> Unit,
    onTap: (ReaderTapAction) -> Unit,
) {
    val latestOnPositionObserved by rememberUpdatedState(onPositionObserved)
    val latestOnPositionSettled by rememberUpdatedState(onPositionSettled)
    val safePosition = coerceReaderPosition(position, settings.readingMode, pages.size)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = safePosition.pageIndex,
    )
    LaunchedEffect(viewportRequestSerial, pages.size, settings.readingMode) {
        val target = coerceReaderPosition(position, settings.readingMode, pages.size)
        if (listState.firstVisibleItemIndex != target.pageIndex) {
            listState.scrollToItem(target.pageIndex)
        }
        val extent = snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == target.pageIndex }
                ?.size
        }.filterNotNull().first()
        val offset = restoredReaderPageOffsetPixels(target, extent)
        val alreadyThere = listState.firstVisibleItemIndex == target.pageIndex &&
            listState.firstVisibleItemScrollOffset == offset
        if (!alreadyThere) {
            if (settings.animatePageTransitions) listState.animateScrollToItem(target.pageIndex, offset)
            else listState.scrollToItem(target.pageIndex, offset)
        }
    }
    LaunchedEffect(listState, pages.size, settings.readingMode, position.resetEpoch) {
        snapshotFlow {
            val pageIndex = listState.firstVisibleItemIndex
            val extent = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == pageIndex }
                ?.size
                ?: 0
            ReaderViewportSample(
                pageIndex = pageIndex,
                pageOffsetPixels = listState.firstVisibleItemScrollOffset,
                pageExtentPixels = extent,
            )
        }
            .filter { it.pageExtentPixels > 0 }
            .map { sample ->
                continuousReaderPosition(
                    readingMode = settings.readingMode,
                    sample = sample,
                    pageCount = pages.size,
                    resetEpoch = position.resetEpoch,
                )
            }
            .distinctUntilChanged()
            .map { observed ->
                latestOnPositionObserved(observed)
                observed
            }
            .debounce(500)
            .collect(latestOnPositionSettled)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(pages, key = { it.index }) { page ->
            ReaderPageImage(
                page = page,
                settings = settings,
                zoomEnabled = false,
                onTap = onTap,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth(1f - (settings.webtoonSidePadding.coerceIn(0.0, 25.0) / 50.0).toFloat()),
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ReaderPageImage(
    page: ReaderPage,
    settings: ReaderSettings,
    zoomEnabled: Boolean,
    onTap: (ReaderTapAction) -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val strings = LocalShinsouStrings.current
    var scale by remember(page.index) { mutableFloatStateOf(1f) }
    var offset by remember(page.index) { mutableStateOf(Offset.Zero) }
    var retryKey by remember(page.index, page.imageUrl) { mutableStateOf(0) }
    var imageError by remember(page.index, page.imageUrl, retryKey) { mutableStateOf(false) }
    val animatedScale by animateFloatAsState(scale, label = "reader-zoom")
    val transformable = rememberTransformableState { zoom, pan, _ ->
        if (zoomEnabled) {
            scale = (scale * zoom).coerceIn(1f, 5f)
            offset = if (scale <= 1f) Offset.Zero else offset + pan
        }
    }
    val platformContext = LocalPlatformContext.current
    val imageRequest = remember(page.imageUrl, page.headers, page.imageTransform, retryKey, platformContext) {
        val networkHeaders = NetworkHeaders.Builder().apply {
            page.headers.forEach { (name, value) -> set(name, value) }
        }.build()
        ImageRequest.Builder(platformContext)
            .data(page.imageUrl)
            .httpHeaders(networkHeaders)
            .apply {
                page.imageTransform?.let { transformations(it.toCoilTransformation()) }
            }
            .build()
    }
    Box(
        modifier
            .background(Color.Black)
            .pointerInput(page.index, settings.doubleTapToZoom, settings.readingMode) {
                detectTapGestures(
                    onTap = { position ->
                        onTap(readerTapAction(position.x, size.width.toFloat(), settings.readingMode))
                    },
                    onDoubleTap = {
                        if (zoomEnabled && settings.doubleTapToZoom) {
                            scale = if (scale > 1.05f) 1f else 2.5f
                            if (scale == 1f) offset = Offset.Zero
                        }
                    },
                )
            }
            .pointerInput(page.index, zoomEnabled) {
                if (zoomEnabled) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val scroll = change.scrollDelta
                            val delta = if (abs(scroll.y) >= abs(scroll.x)) scroll.y else scroll.x
                            if (delta != 0f) {
                                val factor = if (delta < 0f) 1.14f else 1f / 1.14f
                                scale = (scale * factor).coerceIn(1f, 5f)
                                if (scale <= 1f) offset = Offset.Zero
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
            // At the default zoom a single-finger horizontal drag belongs to the pager.  The
            // transform gesture only claims panning after the user has zoomed in, otherwise the
            // child image competes with HorizontalPager and makes page swipes feel sticky.
            .transformable(
                state = transformable,
                enabled = zoomEnabled,
                canPan = { scale > 1f },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = strings.text("Page {0}", page.index + 1),
            contentScale = contentScale,
            colorFilter = settings.colorFilter.toComposeColorFilter(),
            onLoading = { imageError = false },
            onSuccess = { imageError = false },
            onError = { imageError = true },
            modifier = Modifier
                .then(if (contentScale == ContentScale.FillWidth) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        ReaderColorOverlay(settings.colorFilter)
        if (imageError) {
            Surface(
                color = Color.Black.copy(alpha = 0.82f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        strings.text("Page {0} failed to load.", page.index + 1),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            imageError = false
                            retryKey++
                        },
                    ) {
                        Icon(Icons.Outlined.Refresh, null, tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(strings.text("Retry this page"), color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderColorOverlay(filter: ReaderColorFilter) {
    if (!filter.enabled) return
    val brightnessAlpha = filter.brightness.coerceIn(-1f, 1f)
    if (brightnessAlpha != 0f) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (brightnessAlpha > 0) Color.White.copy(alpha = brightnessAlpha * 0.55f)
                    else Color.Black.copy(alpha = -brightnessAlpha * 0.8f),
                ),
        )
    }
    if (filter.alpha > 0f) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Color(
                        red = filter.red.coerceIn(0f, 1f),
                        green = filter.green.coerceIn(0f, 1f),
                        blue = filter.blue.coerceIn(0f, 1f),
                        alpha = filter.alpha.coerceIn(0f, 1f),
                    ),
                ),
        )
    }
}

private fun ReaderColorFilter.toComposeColorFilter(): ColorFilter? {
    if (!enabled || (!grayscale && !inverted)) return null
    val values = when {
        grayscale && inverted -> floatArrayOf(
            -0.2126f, -0.7152f, -0.0722f, 0f, 255f,
            -0.2126f, -0.7152f, -0.0722f, 0f, 255f,
            -0.2126f, -0.7152f, -0.0722f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
        grayscale -> floatArrayOf(
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        else -> floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
    return ColorFilter.colorMatrix(ColorMatrix(values))
}

@Composable
private fun ReaderControls(
    visible: Boolean,
    manga: Manga,
    chapter: Chapter,
    page: Int,
    pageCount: Int,
    showPageControls: Boolean,
    minimal: Boolean = false,
    novelToolbar: Boolean = false,
    inLibrary: Boolean,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onClose: () -> Unit,
    onOpenWeb: (() -> Unit)?,
    onPageChange: (Int) -> Unit,
    onFavorite: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterList: () -> Unit,
    onSettings: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent)),
                    )
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.ArrowBack, strings.text("Close reader"), tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            manga.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            chapter.name,
                            color = Color.White.copy(alpha = 0.74f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!minimal) {
                        onOpenWeb?.let { openWeb ->
                            IconButton(onClick = openWeb) {
                                Icon(Icons.Outlined.OpenInNew, strings.text("Open in browser"), tint = Color.White)
                            }
                        }
                        IconButton(onClick = onFavorite) {
                            Icon(
                                if (inLibrary) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                if (inLibrary) strings.unfavorite else strings.favorite,
                                tint = if (inLibrary) MaterialTheme.colorScheme.primary else Color.White,
                            )
                        }
                    }
                    IconButton(onClick = onChapterList) {
                        Icon(Icons.Outlined.MenuBook, strings.text("Chapter list"), tint = Color.White)
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, strings.readerSettings, tint = Color.White)
                    }
                }
            }
            if (showPageControls) Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))),
                    )
                    .padding(horizontal = 18.dp, vertical = 15.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${page + 1}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = page.toFloat(),
                        onValueChange = { onPageChange(it.toInt()) },
                        valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    Text("$pageCount", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onPreviousChapter, enabled = hasPreviousChapter) {
                        Icon(Icons.Outlined.ArrowBack, null)
                        Spacer(Modifier.width(4.dp))
                        Text(strings.previousChapter)
                    }
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.12f),
                    ) {
                        Text(
                            "${page + 1} / $pageCount",
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    TextButton(onClick = onNextChapter, enabled = hasNextChapter) {
                        Text(strings.nextChapter)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.ArrowForward, null)
                    }
                }
            }
            if (novelToolbar) NovelReaderProgressControls(
                page = page,
                pageCount = pageCount,
                hasPreviousChapter = hasPreviousChapter,
                hasNextChapter = hasNextChapter,
                onPageChange = onPageChange,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Novel progress chrome mirrors the manga reader: page scrubbing stays within the current chapter,
 * while the labelled edge actions always change chapters. The caller places this over the reader
 * surface, so showing or hiding controls never changes pagination or the visible text geometry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelReaderProgressControls(
    page: Int,
    pageCount: Int,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onPageChange: (Int) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
) {
    val strings = LocalShinsouStrings.current
    val safePageCount = pageCount.coerceAtLeast(1)
    val safePage = page.coerceIn(0, safePageCount - 1)
    val sliderEnabled = interactionEnabled && safePageCount > 1
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val sliderColors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = Color.White.copy(alpha = 0.30f),
        disabledThumbColor = Color.White.copy(alpha = 0.38f),
        disabledActiveTrackColor = Color.White.copy(alpha = 0.30f),
        disabledInactiveTrackColor = Color.White.copy(alpha = 0.18f),
    )
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.18f to Color.Black.copy(alpha = 0.86f),
                        1f to Color.Black.copy(alpha = 0.96f),
                    ),
                ),
            )
            .readerNavigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${safePage + 1}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(28.dp),
            )
            Slider(
                value = safePage.toFloat(),
                onValueChange = {
                    onPageChange(it.roundToInt().coerceIn(0, safePageCount - 1))
                },
                valueRange = 0f..(safePageCount - 1).coerceAtLeast(1).toFloat(),
                enabled = sliderEnabled,
                colors = sliderColors,
                interactionSource = sliderInteractionSource,
                thumb = {
                    Box(
                        Modifier
                            .size(18.dp)
                            .background(
                                if (sliderEnabled) MaterialTheme.colorScheme.primary
                                else Color.White.copy(alpha = 0.38f),
                                CircleShape,
                            ),
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(5.dp),
                        enabled = sliderEnabled,
                        colors = sliderColors,
                        drawStopIndicator = null,
                        thumbTrackGapSize = 0.dp,
                        trackInsideCornerSize = 0.dp,
                    )
                },
                modifier = Modifier.weight(1f).height(44.dp).padding(horizontal = 8.dp),
            )
            Text(
                "$safePageCount",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(28.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                TextButton(
                    onClick = onPreviousChapter,
                    enabled = interactionEnabled && hasPreviousChapter,
                ) {
                    Icon(Icons.Outlined.ArrowBack, null)
                    Spacer(Modifier.width(4.dp))
                    Text(strings.previousChapter, maxLines = 1)
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                ) {
                    Text(
                        "${safePage + 1} / $safePageCount",
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                TextButton(
                    onClick = onNextChapter,
                    enabled = interactionEnabled && hasNextChapter,
                ) {
                    Text(strings.nextChapter, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.ArrowForward, null)
                }
            }
        }
    }
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsSheet(
    settings: ReaderSettings,
    textContent: Boolean = false,
    onChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(strings.readerSettings, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text(strings.text("Reading mode"), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    val modes = if (textContent) {
                        listOf(
                            ReadingMode.CONTINUOUS_VERTICAL to strings.text("Scroll down"),
                            ReadingMode.PAGER_LTR to strings.text("Turn left"),
                            ReadingMode.PAGER_RTL to strings.text("Turn right"),
                        )
                    } else {
                        listOf(
                            ReadingMode.PAGER_LTR to strings.text("Left to right"),
                            ReadingMode.PAGER_RTL to strings.text("Right to left"),
                            ReadingMode.PAGER_VERTICAL to strings.text("Vertical paging"),
                            ReadingMode.WEBTOON to strings.text("Webtoon"),
                        )
                    }
                    modes.forEach { (mode, label) ->
                        item(key = mode.name) {
                            FilterChip(
                                selected = if (textContent && mode == ReadingMode.CONTINUOUS_VERTICAL) {
                                    isNovelContinuousMode(settings.readingMode)
                                } else {
                                    settings.readingMode == mode ||
                                        (mode == ReadingMode.WEBTOON &&
                                            settings.readingMode == ReadingMode.CONTINUOUS_VERTICAL)
                                },
                                onClick = { onChange(settings.copy(readingMode = mode)) },
                                label = { Text(label) },
                                leadingIcon = {
                                    Icon(
                                        when (mode) {
                                            ReadingMode.PAGER_LTR, ReadingMode.PAGER_RTL -> Icons.Outlined.SwapHoriz
                                            ReadingMode.PAGER_VERTICAL -> Icons.Outlined.SwapVert
                                            else -> Icons.Outlined.MenuBook
                                        },
                                        null,
                                        Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            if (textContent) {
                item {
                    var fontSize by remember(settings.novelFontSizeSp) {
                        mutableFloatStateOf(settings.novelFontSizeSp)
                    }
                    var lineHeight by remember(settings.novelLineHeightMultiplier) {
                        mutableFloatStateOf(settings.novelLineHeightMultiplier)
                    }
                    var readingWidth by remember(settings.novelMaxWidthDp) {
                        mutableFloatStateOf(settings.novelMaxWidthDp)
                    }
                    Text(strings.text("Novel typography"), style = MaterialTheme.typography.titleMedium)
                    Text(strings.text("Font size · {0}", fontSize.toInt()))
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        onValueChangeFinished = {
                            if (fontSize != settings.novelFontSizeSp) {
                                onChange(settings.copy(novelFontSizeSp = fontSize))
                            }
                        },
                        valueRange = 14f..30f,
                    )
                    Text(
                        strings.text(
                            "Line height · {0}%",
                            (lineHeight * 100f).toInt(),
                        ),
                    )
                    Slider(
                        value = lineHeight,
                        onValueChange = { lineHeight = it },
                        onValueChangeFinished = {
                            if (lineHeight != settings.novelLineHeightMultiplier) {
                                onChange(settings.copy(novelLineHeightMultiplier = lineHeight))
                            }
                        },
                        valueRange = 1.25f..2.2f,
                    )
                    Text(strings.text("Reading width · {0}", readingWidth.toInt()))
                    Slider(
                        value = readingWidth,
                        onValueChange = { readingWidth = it },
                        onValueChangeFinished = {
                            if (readingWidth != settings.novelMaxWidthDp) {
                                onChange(settings.copy(novelMaxWidthDp = readingWidth))
                            }
                        },
                        valueRange = 520f..920f,
                    )
                }
            } else {
                item { ReaderToggle(strings.text("Show page number"), settings.showPageNumber) { onChange(settings.copy(showPageNumber = it)) } }
            }
            item { ReaderToggle(strings.text("Keep screen on"), settings.keepScreenOn) { onChange(settings.copy(keepScreenOn = it)) } }
            item { ReaderToggle(strings.text("Fullscreen"), settings.fullscreen) { onChange(settings.copy(fullscreen = it)) } }
            if (!textContent) {
                item { ReaderToggle(strings.text("Double-tap to zoom"), settings.doubleTapToZoom) { onChange(settings.copy(doubleTapToZoom = it)) } }
            }
            item { ReaderToggle(strings.text("Skip read chapters"), settings.skipReadChapters) { onChange(settings.copy(skipReadChapters = it)) } }
            item { ReaderToggle(strings.text("Skip filtered chapters"), settings.skipFilteredChapters) { onChange(settings.copy(skipFilteredChapters = it)) } }
            item { ReaderToggle(strings.text("Skip duplicate chapters"), settings.skipDuplicateChapters) { onChange(settings.copy(skipDuplicateChapters = it)) } }
            if (shouldShowReaderVolumeKeySetting()) {
                item { ReaderToggle(strings.text("Volume keys"), settings.volumeKeys) { onChange(settings.copy(volumeKeys = it)) } }
            }
            item {
                ReaderToggle(strings.text("Page turn animation"), settings.animatePageTransitions) {
                    onChange(settings.copy(animatePageTransitions = it))
                }
            }
            if (!textContent) {
                item { ReaderToggle(strings.text("Split tall images"), settings.splitTallImages) { onChange(settings.copy(splitTallImages = it)) } }
            }
            if (!textContent &&
                (settings.readingMode == ReadingMode.WEBTOON ||
                    settings.readingMode == ReadingMode.CONTINUOUS_VERTICAL)
            ) {
                item {
                    Text(strings.text("Webtoon side padding · {0}%", settings.webtoonSidePadding.toInt()))
                    Slider(
                        value = settings.webtoonSidePadding.toFloat(),
                        onValueChange = { onChange(settings.copy(webtoonSidePadding = it.toDouble())) },
                        valueRange = 0f..25f,
                    )
                }
            }
            if (!textContent) item {
                Text(strings.text("Color filter"), style = MaterialTheme.typography.titleMedium)
                ReaderToggle(strings.text("Enable filter"), settings.colorFilter.enabled) {
                    onChange(settings.copy(colorFilter = settings.colorFilter.copy(enabled = it)))
                }
                Text(strings.text("Brightness"))
                Slider(
                    value = settings.colorFilter.brightness,
                    onValueChange = {
                        onChange(settings.copy(colorFilter = settings.colorFilter.copy(enabled = true, brightness = it)))
                    },
                    valueRange = -1f..1f,
                )
                ReaderToggle(strings.text("Grayscale"), settings.colorFilter.grayscale) {
                    onChange(settings.copy(colorFilter = settings.colorFilter.copy(enabled = true, grayscale = it)))
                }
                ReaderToggle(strings.text("Invert colors"), settings.colorFilter.inverted) {
                    onChange(settings.copy(colorFilter = settings.colorFilter.copy(enabled = true, inverted = it)))
                }
            }
            item {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(strings.done) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ReaderToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange)
    }
}
