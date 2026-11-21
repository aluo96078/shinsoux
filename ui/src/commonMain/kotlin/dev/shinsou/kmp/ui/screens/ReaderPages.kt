package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.ui.components.ShinsouLoadingIndicator
import dev.shinsou.kmp.plugin.ungrantedContentHostSuffix
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.shinsou.kmp.domain.model.ReaderColorFilter
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.reader.ReaderViewportSample
import dev.shinsou.kmp.reader.coerceReaderPosition
import dev.shinsou.kmp.reader.continuousReaderPosition
import dev.shinsou.kmp.reader.readerLogicalPageIndex
import dev.shinsou.kmp.reader.readerPhysicalPageIndex
import dev.shinsou.kmp.reader.readerTapAction
import dev.shinsou.kmp.reader.restoredReaderPageOffsetPixels
import dev.shinsou.kmp.network.encodedImageHeight
import dev.shinsou.kmp.reader.readerImageTransform
import dev.shinsou.kmp.reader.ReaderPosition
import dev.shinsou.kmp.contract.ReaderPage
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.abs

// R4-4 (docs/architecture/0006): moved out of ReaderScreen.kt without changing it.

@Composable
internal fun ReaderHorizontalPager(
    pages: List<ReaderPage>,
    currentPage: Int,
    viewportRequestSerial: Long,
    settings: ReaderSettings,
    onPageChanged: (Int) -> Unit,
    onTap: (ReaderTapAction) -> Unit,
) {
    val mode = settings.readingMode
    val pagerState = rememberPagerState(
        initialPage = readerPhysicalPageIndex(currentPage, pages.size, mode),
    ) { pages.size }

    // Scroll only for explicit requests (taps, keys, the progress slider, restores). Keying this on
    // the observed page let every intermediate page of a multi-page animation cancel the seek, so
    // slider jumps stopped early or left the pager between pages.
    LaunchedEffect(viewportRequestSerial, pages.size, mode) {
        val target = readerPhysicalPageIndex(currentPage, pages.size, mode)
        pagerState.seekReaderPage(target, settings.animatePageTransitions)
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
internal fun ReaderVerticalPager(
    pages: List<ReaderPage>,
    currentPage: Int,
    viewportRequestSerial: Long,
    settings: ReaderSettings,
    onPageChanged: (Int) -> Unit,
    onTap: (ReaderTapAction) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = currentPage) { pages.size }
    LaunchedEffect(viewportRequestSerial, pages.size) {
        pagerState.seekReaderPage(currentPage, settings.animatePageTransitions)
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

private suspend fun PagerState.seekReaderPage(target: Int, animatePageTransitions: Boolean) {
    if (pageCount <= 0) return
    val safeTarget = target.coerceIn(0, pageCount - 1)
    if (currentPage == safeTarget && currentPageOffsetFraction == 0f) return
    if (readerPageSeekAnimates(currentPage, safeTarget, animatePageTransitions)) {
        animateScrollToPage(safeTarget)
    } else {
        scrollToPage(safeTarget)
    }
}

@OptIn(FlowPreview::class)
@Composable
internal fun ReaderWebtoon(
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
    val resolver = page.imageResolver
    if (resolver != null) {
        LazyResolvedReaderPageImage(
            page = page,
            settings = settings,
            zoomEnabled = zoomEnabled,
            onTap = onTap,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }
    ResolvedReaderPageImage(page, settings, zoomEnabled, onTap, modifier, contentScale)
}

/**
 * Returns only image data that is safe for Coil to consume directly.
 *
 * Remote plugin URLs must be materialized by the host content plane before reaching this helper;
 * otherwise a custom ContentCallbacks implementation (or stale persisted legacy state) could
 * accidentally restore the old direct-Coil network path.
 */
internal fun readerImageData(page: ReaderPage): Any? = when {
    // An empty host response is not image data. Treat it as unresolved so callers keep the
    // retry/error surface instead of enqueueing an inevitably failing Coil request.
    page.imageBytes?.takeIf { it.isNotEmpty() } != null -> page.imageBytes
    page.local && page.imageUrl.isNotBlank() -> page.imageUrl
    else -> null
}

/** Natural height for a transformed page, from its bytes' header or the value read for a local file. */
internal fun readerImageNaturalHeight(page: ReaderPage): Int? {
    if (page.imageTransform == null) return null
    return page.imageBytes?.takeIf { it.isNotEmpty() }?.let(::encodedImageHeight) ?: page.imageNaturalHeight
}

@Composable
private fun LazyResolvedReaderPageImage(
    page: ReaderPage,
    settings: ReaderSettings,
    zoomEnabled: Boolean,
    onTap: (ReaderTapAction) -> Unit,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    val strings = LocalShinsouStrings.current
    var retryKey by remember(page.index, page.imageResolver) { mutableStateOf(0) }
    var resolved by remember(page.index, page.imageResolver, retryKey) { mutableStateOf<ReaderPage?>(null) }
    var resolutionFailed by remember(page.index, page.imageResolver, retryKey) { mutableStateOf(false) }
    var ungrantedSuffix by remember(page.index, page.imageResolver, retryKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(page.index, page.imageResolver, retryKey) {
        resolutionFailed = false
        ungrantedSuffix = null
        resolved = try {
            requireNotNull(page.imageResolver).invoke()
                .takeIf { it.imageResolver == null && readerImageData(it) != null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            ungrantedSuffix = failure.ungrantedContentHostSuffix()
            resolutionFailed = true
            null
        }
        if (resolved == null) resolutionFailed = true
    }

    resolved?.let {
        ResolvedReaderPageImage(it, settings, zoomEnabled, onTap, modifier, contentScale)
        return
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (resolutionFailed) {
            Surface(
                color = Color.Black.copy(alpha = 0.82f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        strings.text(StringKey.PAGE_X_FAILED_TO_LOAD, page.index + 1),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    ungrantedSuffix?.let { suffix ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            strings.text(StringKey.ITS_IMAGES_COME_FROM_X_WHICH_THIS, suffix.displaySuffix(strings)),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { retryKey++ }) {
                        Icon(Icons.Outlined.Refresh, null, tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(strings.text(StringKey.RETRY_THIS_PAGE), color = Color.White)
                    }
                }
            }
        } else {
            ShinsouLoadingIndicator(color = Color.White, captionColor = Color.White, requestUrl = page.diagnosticUrl)
        }
    }
}

@Composable
private fun ResolvedReaderPageImage(
    page: ReaderPage,
    settings: ReaderSettings,
    zoomEnabled: Boolean,
    onTap: (ReaderTapAction) -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val strings = LocalShinsouStrings.current
    // A resolver/retry can replace the bytes while retaining the same page index. Reset the
    // gesture state for the new image so a zoomed previous image does not carry over.
    var scale by remember(page.index, page.imageUrl, page.imageBytes) { mutableFloatStateOf(1f) }
    var offset by remember(page.index, page.imageUrl, page.imageBytes) { mutableStateOf(Offset.Zero) }
    var retryKey by remember(page.index, page.imageUrl, page.imageBytes) { mutableStateOf(0) }
    var imageError by remember(page.index, page.imageUrl, page.imageBytes, retryKey) { mutableStateOf(false) }
    val animatedScale by animateFloatAsState(scale, label = "reader-zoom")
    val transformable = rememberTransformableState { zoom, pan, _ ->
        if (zoomEnabled) {
            scale = (scale * zoom).coerceIn(1f, 5f)
            offset = if (scale <= 1f) Offset.Zero else offset + pan
        }
    }
    val platformContext = LocalPlatformContext.current
    val imageData = readerImageData(page)
    val imageRequest = remember(imageData, page.headers, page.imageTransform, retryKey, platformContext) {
        val networkHeaders = NetworkHeaders.Builder().apply {
            page.headers.forEach { (name, value) -> set(name, value) }
        }.build()
        ImageRequest.Builder(platformContext)
            .data(imageData)
            .httpHeaders(networkHeaders)
            .readerImageTransform(page.imageTransform, readerImageNaturalHeight(page))
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
            contentDescription = strings.text(StringKey.PAGE_X, page.index + 1),
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
            ReaderPageLoadError(page.index) {
                imageError = false
                retryKey++
            }
        }
    }
}

/** The retry card shown over a page whose image failed to load. */
@Composable
private fun ReaderPageLoadError(pageIndex: Int, onRetry: () -> Unit) {
    val strings = LocalShinsouStrings.current
    Surface(
        color = Color.Black.copy(alpha = 0.82f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                strings.text(StringKey.PAGE_X_FAILED_TO_LOAD, pageIndex + 1),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(strings.text(StringKey.RETRY_THIS_PAGE), color = Color.White)
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
