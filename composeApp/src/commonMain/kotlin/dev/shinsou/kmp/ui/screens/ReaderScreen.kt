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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
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
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.ReaderColorFilter
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.reader.readerPrefetchIndices
import dev.shinsou.kmp.reader.readerTapAction
import dev.shinsou.kmp.reader.toCoilTransformation
import dev.shinsou.kmp.ui.ReaderPage
import dev.shinsou.kmp.ui.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.readerVolumeKeyAction
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    volumeKeyEvents: Flow<ReaderVolumeKeyEvent> = emptyFlow(),
    systemBackRequest: Long = 0L,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onOpenWeb: (() -> Unit)?,
    onSettingsChange: (ReaderSettings) -> Unit,
    onPageChanged: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalShinsouStrings.current
    var controlsVisible by remember(chapter.id) { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }
    var chapterListVisible by remember { mutableStateOf(false) }
    var boundaryTransition by remember(chapter.id) { mutableStateOf<ReaderChapterBoundary?>(null) }
    var currentPage by remember(chapter.id, pages.size) {
        mutableStateOf(chapter.lastPageRead.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
    }
    val focusRequester = remember { FocusRequester() }
    val platformContext = LocalPlatformContext.current

    fun openPreviousChapter() {
        boundaryTransition = null
        if (previousChapter != null) onPreviousChapter()
    }

    fun openNextChapter() {
        boundaryTransition = null
        if (nextChapter != null) onNextChapter()
    }

    fun requestPage(index: Int) {
        when {
            index < 0 -> boundaryTransition = ReaderChapterBoundary.PREVIOUS
            index >= pages.size -> boundaryTransition = ReaderChapterBoundary.NEXT
            pages.isNotEmpty() -> {
                boundaryTransition = null
                currentPage = index.coerceIn(0, pages.lastIndex)
            }
        }
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
            else -> onClose()
        }
        return true
    }

    /**
     * Hardware volume buttons follow the original Shinsou reader semantics: move exactly one
     * page, and cross a chapter boundary immediately. Tap zones and pager swipes intentionally
     * keep the in-reader transition card so users can preview the destination chapter.
     */
    fun handleVolumeKey(event: ReaderVolumeKeyEvent) {
        if (pages.isEmpty()) return

        val forward = event == ReaderVolumeKeyEvent.VOLUME_DOWN
        val action = if (forward) ReaderTapAction.NEXT_PAGE else ReaderTapAction.PREVIOUS_PAGE
        val atBoundary = if (forward) {
            currentPage >= pages.lastIndex
        } else {
            currentPage <= 0
        }

        if (atBoundary) {
            if (forward) openNextChapter() else openPreviousChapter()
        } else {
            handlePageAction(action)
        }
    }

    DisposableEffect(settings.keepScreenOn) {
        onDispose { }
    }
    // Modal sheets own a separate focus tree on desktop and iPadOS. Reclaim focus when they close
    // so hardware paging and Escape/back keep working without requiring another pointer click.
    LaunchedEffect(chapter.id, settingsVisible, chapterListVisible) {
        if (!settingsVisible && !chapterListVisible) focusRequester.requestFocus()
    }
    // ReaderScreen is first composed while the async chapter request still exposes an empty page
    // list. A long-lived collector that captures that first composition keeps calling the stale
    // `pages.isEmpty()` handler forever; toggling the setting happened to restart it after pages
    // loaded, which made the feature appear to require an off/on cycle. Keep the collector stable
    // while forwarding every event to the handler from the latest composition instead.
    val currentVolumeKeyHandler by rememberUpdatedState<(ReaderVolumeKeyEvent) -> Unit> { event ->
        val action = readerVolumeKeyAction(
            event = event,
            readerOpen = true,
            volumeKeysEnabled = settings.volumeKeys,
        )
        if (action != null) {
            val pageBefore = currentPage
            handleVolumeKey(event)
            println(
                "[ShinsouX.VolumeKeys] reader handled event=$event " +
                    "pages=${pages.size} pageBefore=$pageBefore pageAfter=$currentPage",
            )
        }
    }
    LaunchedEffect(volumeKeyEvents, chapter.id) {
        volumeKeyEvents.collect { event ->
            currentVolumeKeyHandler(event)
        }
    }
    LaunchedEffect(systemBackRequest) {
        if (systemBackRequest == 0L) return@LaunchedEffect
        handleReaderBack()
    }
    LaunchedEffect(chapter.id, currentPage, pages.size, loading) {
        if (!loading && pages.isNotEmpty()) onPageChanged(currentPage)
    }
    LaunchedEffect(chapter.id, currentPage, pages) {
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
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.VolumeDown -> {
                        readerVolumeKeyAction(
                            event = ReaderVolumeKeyEvent.VOLUME_DOWN,
                            readerOpen = true,
                            volumeKeysEnabled = settings.volumeKeys,
                        )?.let { handleVolumeKey(ReaderVolumeKeyEvent.VOLUME_DOWN) } != null
                    }
                    Key.VolumeUp -> {
                        readerVolumeKeyAction(
                            event = ReaderVolumeKeyEvent.VOLUME_UP,
                            readerOpen = true,
                            volumeKeysEnabled = settings.volumeKeys,
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
            pages.isEmpty() -> ReaderError(
                message = strings.text("This chapter has no pages."),
                onRetry = onRetry,
                onOpenWeb = onOpenWeb,
                modifier = Modifier.align(Alignment.Center),
            )
            boundaryTransition != null -> ReaderChapterTransitionCard(
                boundary = boundaryTransition!!,
                currentChapter = chapter,
                targetChapter = if (boundaryTransition == ReaderChapterBoundary.PREVIOUS) previousChapter else nextChapter,
                readingMode = settings.readingMode,
                onNavigate = {
                    if (boundaryTransition == ReaderChapterBoundary.PREVIOUS) openPreviousChapter() else openNextChapter()
                },
                onReturn = { boundaryTransition = null },
                onToggleChrome = { controlsVisible = !controlsVisible },
                modifier = Modifier.fillMaxSize(),
            )
            else -> key(chapter.id, settings.readingMode) {
                when (settings.readingMode) {
                ReadingMode.PAGER_LTR,
                ReadingMode.PAGER_RTL,
                -> ReaderHorizontalPager(
                    pages = pages,
                    currentPage = currentPage,
                    rtl = settings.readingMode == ReadingMode.PAGER_RTL,
                    settings = settings,
                    onPageChanged = { currentPage = it },
                    onTap = ::handlePageAction,
                )

                ReadingMode.PAGER_VERTICAL -> ReaderVerticalPager(
                    pages = pages,
                    currentPage = currentPage,
                    settings = settings,
                    onPageChanged = { currentPage = it },
                    onTap = ::handlePageAction,
                )

                ReadingMode.WEBTOON,
                ReadingMode.CONTINUOUS_VERTICAL,
                -> ReaderWebtoon(
                    pages = pages,
                    currentPage = currentPage,
                    settings = settings,
                    onPageChanged = { currentPage = it },
                    onTap = ::handlePageAction,
                )
            }
            }
        }

        if (settings.showPageNumber && !controlsVisible && pages.isNotEmpty() && boundaryTransition == null) {
            Surface(
                color = Color.Black.copy(alpha = 0.66f),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            ) {
                Text(
                    "${currentPage + 1} / ${pages.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }

        ReaderControls(
            visible = controlsVisible && boundaryTransition == null,
            manga = manga,
            chapter = chapter,
            page = currentPage,
            pageCount = pages.size,
            inLibrary = inLibrary,
            hasPreviousChapter = previousChapter != null,
            hasNextChapter = nextChapter != null,
            onClose = onClose,
            onOpenWeb = onOpenWeb,
            onPageChange = ::requestPage,
            onFavorite = onToggleFavorite,
            onPreviousChapter = { boundaryTransition = ReaderChapterBoundary.PREVIOUS },
            onNextChapter = { boundaryTransition = ReaderChapterBoundary.NEXT },
            onChapterList = { chapterListVisible = true },
            onSettings = { settingsVisible = true },
        )
    }

    if (settingsVisible) {
        ReaderSettingsSheet(
            settings = settings,
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
                if (chapterId != chapter.id) onChapterSelected(chapterId)
            },
            onDismiss = { chapterListVisible = false },
        )
    }
}

@Composable
private fun ReaderHorizontalPager(
    pages: List<ReaderPage>,
    currentPage: Int,
    rtl: Boolean,
    settings: ReaderSettings,
    onPageChanged: (Int) -> Unit,
    onTap: (ReaderTapAction) -> Unit,
) {
    fun physical(logical: Int): Int = if (rtl) pages.lastIndex - logical else logical
    fun logical(physical: Int): Int = if (rtl) pages.lastIndex - physical else physical
    val pagerState = rememberPagerState(initialPage = physical(currentPage)) { pages.size }

    LaunchedEffect(currentPage, pages.size, rtl, settings.animatePageTransitions) {
        val target = physical(currentPage).coerceIn(0, pages.lastIndex)
        if (pagerState.currentPage != target) {
            if (settings.animatePageTransitions) pagerState.animateScrollToPage(target)
            else pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(pagerState, rtl) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect {
            onPageChanged(logical(it))
        }
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 2,
        modifier = Modifier.fillMaxSize(),
    ) { physicalIndex ->
        ReaderPageImage(
            page = pages[logical(physicalIndex)],
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

@Composable
private fun ReaderWebtoon(
    pages: List<ReaderPage>,
    currentPage: Int,
    settings: ReaderSettings,
    onPageChanged: (Int) -> Unit,
    onTap: (ReaderTapAction) -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = currentPage)
    LaunchedEffect(currentPage, settings.animatePageTransitions) {
        if (listState.firstVisibleItemIndex != currentPage) {
            if (settings.animatePageTransitions) listState.animateScrollToItem(currentPage)
            else listState.scrollToItem(currentPage)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect(onPageChanged)
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
                    IconButton(onClick = onChapterList) {
                        Icon(Icons.Outlined.MenuBook, strings.text("Chapter list"), tint = Color.White)
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, strings.readerSettings, tint = Color.White)
                    }
                }
            }
            Column(
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
            Icon(
                if (forward) Icons.Outlined.ArrowForward else Icons.Outlined.ArrowBack,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(42.dp),
            )
            Text(
                if (forward) strings.text("Finished {0}", currentChapter.name) else strings.text("Beginning of {0}", currentChapter.name),
                color = Color.White.copy(alpha = 0.66f),
                style = MaterialTheme.typography.labelLarge,
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            Text(
                targetChapter?.name ?: if (forward) strings.text("No next chapter") else strings.text("No previous chapter"),
                color = if (targetChapter == null) Color.White.copy(alpha = 0.46f) else Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (targetChapter != null) {
                Button(onClick = onNavigate) {
                    Text(if (forward) strings.text("Read next chapter") else strings.text("Read previous chapter"))
                }
            }
            TextButton(onClick = onReturn) { Text(strings.text("Return to chapter"), color = Color.White) }
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
private fun ReaderSettingsSheet(
    settings: ReaderSettings,
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
                    listOf(
                        ReadingMode.PAGER_LTR to strings.text("Left to right"),
                        ReadingMode.PAGER_RTL to strings.text("Right to left"),
                        ReadingMode.PAGER_VERTICAL to strings.text("Vertical paging"),
                        ReadingMode.WEBTOON to strings.text("Webtoon"),
                    ).forEach { (mode, label) ->
                        item(key = mode.name) {
                            FilterChip(
                                selected = settings.readingMode == mode ||
                                    (mode == ReadingMode.WEBTOON && settings.readingMode == ReadingMode.CONTINUOUS_VERTICAL),
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
            item { ReaderToggle(strings.text("Show page number"), settings.showPageNumber) { onChange(settings.copy(showPageNumber = it)) } }
            item { ReaderToggle(strings.text("Keep screen on"), settings.keepScreenOn) { onChange(settings.copy(keepScreenOn = it)) } }
            item { ReaderToggle(strings.text("Fullscreen"), settings.fullscreen) { onChange(settings.copy(fullscreen = it)) } }
            item { ReaderToggle(strings.text("Double-tap to zoom"), settings.doubleTapToZoom) { onChange(settings.copy(doubleTapToZoom = it)) } }
            item { ReaderToggle(strings.text("Skip read chapters"), settings.skipReadChapters) { onChange(settings.copy(skipReadChapters = it)) } }
            item { ReaderToggle(strings.text("Skip filtered chapters"), settings.skipFilteredChapters) { onChange(settings.copy(skipFilteredChapters = it)) } }
            item { ReaderToggle(strings.text("Skip duplicate chapters"), settings.skipDuplicateChapters) { onChange(settings.copy(skipDuplicateChapters = it)) } }
            item { ReaderToggle(strings.text("Volume keys"), settings.volumeKeys) { onChange(settings.copy(volumeKeys = it)) } }
            item {
                ReaderToggle(strings.text("Page turn animation"), settings.animatePageTransitions) {
                    onChange(settings.copy(animatePageTransitions = it))
                }
            }
            item { ReaderToggle(strings.text("Split tall images"), settings.splitTallImages) { onChange(settings.copy(splitTallImages = it)) } }
            if (settings.readingMode == ReadingMode.WEBTOON || settings.readingMode == ReadingMode.CONTINUOUS_VERTICAL) {
                item {
                    Text(strings.text("Webtoon side padding · {0}%", settings.webtoonSidePadding.toInt()))
                    Slider(
                        value = settings.webtoonSidePadding.toFloat(),
                        onValueChange = { onChange(settings.copy(webtoonSidePadding = it.toDouble())) },
                        valueRange = 0f..25f,
                    )
                }
            }
            item {
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
