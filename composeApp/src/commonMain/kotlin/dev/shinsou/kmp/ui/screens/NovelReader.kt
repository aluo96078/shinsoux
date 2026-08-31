package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.reader.PlainTextNavigation
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.readerLogicalPageIndex
import dev.shinsou.kmp.reader.readerPhysicalPageIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal val NOVEL_READER_BACKGROUND: Color = Color(0xFF211D1B)
internal val NOVEL_READER_TEXT: Color = Color(0xFFF2E9DE)

internal fun isNovelContinuousMode(mode: ReadingMode): Boolean = when (mode) {
    ReadingMode.PAGER_LTR,
    ReadingMode.PAGER_RTL,
    -> false

    ReadingMode.PAGER_VERTICAL,
    ReadingMode.WEBTOON,
    ReadingMode.CONTINUOUS_VERTICAL,
    -> true
}

internal fun isReaderNavigationKey(key: Key): Boolean = when (key) {
    Key.VolumeDown,
    Key.VolumeUp,
    Key.DirectionRight,
    Key.NumPadDirectionRight,
    Key.DirectionLeft,
    Key.NumPadDirectionLeft,
    Key.DirectionDown,
    Key.NumPadDirectionDown,
    Key.DirectionUp,
    Key.NumPadDirectionUp,
    Key.Spacebar,
    Key.PageDown,
    Key.NumPadPageDown,
    Key.PageUp,
    Key.NumPadPageUp,
    Key.Escape,
    Key.Back,
    Key.NavigatePrevious,
    -> true

    else -> false
}

/** ShuYue's useful interaction grammar: 30% previous, 40% chrome, 30% next. */
internal fun novelTapAction(
    horizontalPosition: Float,
    viewportWidth: Float,
    readingMode: ReadingMode,
): ReaderTapAction {
    if (viewportWidth <= 0f || !horizontalPosition.isFinite()) return ReaderTapAction.TOGGLE_CHROME
    val position = horizontalPosition.coerceIn(0f, viewportWidth)
    if (position in (viewportWidth * 0.3f)..(viewportWidth * 0.7f)) {
        return ReaderTapAction.TOGGLE_CHROME
    }
    val leftEdge = position < viewportWidth * 0.3f
    val rightToLeft = readingMode == ReadingMode.PAGER_RTL
    return when {
        leftEdge && rightToLeft -> ReaderTapAction.NEXT_PAGE
        !leftEdge && rightToLeft -> ReaderTapAction.PREVIOUS_PAGE
        leftEdge -> ReaderTapAction.PREVIOUS_PAGE
        else -> ReaderTapAction.NEXT_PAGE
    }
}

internal data class NovelDisplayText(
    val value: String,
    /** For each display boundary, the matching UTF-16 offset in the source segment. */
    val sourceOffsets: IntArray,
)

private val NOVEL_HTML_TAGS = setOf(
    "a", "article", "b", "blockquote", "body", "br", "center", "cite", "code", "dd",
    "div", "dl", "dt", "em", "font", "h1", "h2", "h3", "h4", "h5", "h6", "head",
    "html", "i", "li", "main", "ol", "p", "pre", "section", "small", "span", "strong",
    "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "title", "tr", "u", "ul",
)
private val NOVEL_BLOCK_HTML_TAGS = setOf(
    "article", "blockquote", "dd", "div", "dl", "dt", "h1", "h2", "h3", "h4", "h5", "h6",
    "li", "main", "p", "pre", "section", "table", "tr",
)

/**
 * Keeps common publisher HTML out of the visible novel text while retaining an offset map for
 * portable progress locators. This is deliberately a display sanitizer, not an HTML renderer.
 */
internal fun sanitizeNovelDisplayText(source: String): NovelDisplayText {
    val display = StringBuilder(source.length)
    val offsets = ArrayList<Int>(source.length + 1)

    fun append(value: String, sourceOffset: Int) {
        value.forEach { character ->
            offsets += sourceOffset
            display.append(character)
        }
    }

    var cursor = 0
    while (cursor < source.length) {
        if (source[cursor] == '<') {
            val close = source.indexOf('>', startIndex = cursor + 1)
            if (close in (cursor + 1)..(cursor + 512)) {
                val rawTag = source.substring(cursor + 1, close).trim().lowercase()
                val closingTag = rawTag.startsWith('/')
                val tagName = rawTag
                    .removePrefix("/")
                    .substringBefore(' ')
                    .removeSuffix("/")
                if (tagName in NOVEL_HTML_TAGS) {
                    if ((tagName == "br" || closingTag && tagName in NOVEL_BLOCK_HTML_TAGS) &&
                        display.isNotEmpty() && display.last() != '\n'
                    ) {
                        append("\n", cursor)
                    }
                    cursor = close + 1
                    continue
                }
            }
        }
        if (source[cursor] == '&') {
            val close = source.indexOf(';', startIndex = cursor + 1)
            if (close in (cursor + 2)..(cursor + 12)) {
                val decoded = decodeNovelDisplayEntity(source.substring(cursor, close + 1))
                if (decoded != null) {
                    append(decoded, cursor)
                    cursor = close + 1
                    continue
                }
            }
        }
        append(source[cursor].toString(), cursor)
        cursor++
    }
    offsets += source.length
    return NovelDisplayText(display.toString(), offsets.toIntArray())
}

private fun decodeNovelDisplayEntity(entity: String): String? {
    return when (entity.lowercase()) {
        "&amp;" -> "&"
        "&quot;" -> "\""
        "&#39;", "&apos;" -> "'"
        "&lt;" -> "<"
        "&gt;" -> ">"
        "&nbsp;" -> " "
        else -> {
            val body = entity.removePrefix("&").removeSuffix(";")
            val codePoint = when {
                body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)
                body.startsWith('#') -> body.drop(1).toIntOrNull()
                else -> null
            } ?: return null
            when (codePoint) {
                in 0..0xD7FF, in 0xE000..0xFFFF -> codePoint.toChar().toString()
                in 0x10000..0x10FFFF -> buildString(2) {
                    val value = codePoint - 0x10000
                    append(((value ushr 10) + 0xD800).toChar())
                    append(((value and 0x3FF) + 0xDC00).toChar())
                }
                else -> null
            }
        }
    }
}

internal sealed interface NovelVisualPage {
    val sourceStartUtf16: Int
    val sourceEndUtf16: Int

    data class Text(
        val value: String,
        override val sourceStartUtf16: Int,
        override val sourceEndUtf16: Int,
    ) : NovelVisualPage

    data class Image(
        val url: String,
        val alt: String,
        override val sourceStartUtf16: Int,
        override val sourceEndUtf16: Int,
    ) : NovelVisualPage
}

internal data class NovelViewportPosition(
    val pageIndex: Int,
    val pageOffsetFraction: Double,
    val atDocumentEnd: Boolean,
)

/** Mutable without Compose invalidation; geometry reflow reads the latest stable UTF-16 cursor. */
private class NovelSourcePosition(var offsetUtf16: Int)

/** Pure boundary preference used after geometry measurement; kept testable without Compose UI. */
internal fun preferredNovelPageBreak(text: String, start: Int, measuredEnd: Int): Int {
    if (measuredEnd >= text.length) return text.length
    if (measuredEnd <= start) return nextUtf16Boundary(text, start)
    val minimum = start + ((measuredEnd - start) * 0.72f).toInt()
    for (index in measuredEnd downTo minimum.coerceAtLeast(start + 1)) {
        val previous = text[index - 1]
        if (previous == '\n') return index
    }
    for (index in measuredEnd downTo minimum.coerceAtLeast(start + 1)) {
        val previous = text[index - 1]
        if (previous.isWhitespace() || previous in NOVEL_BREAK_PUNCTUATION) return index
    }
    return previousUtf16Boundary(text, measuredEnd).coerceAtLeast(nextUtf16Boundary(text, start))
}

private const val NOVEL_BREAK_PUNCTUATION: String = "，。！？；：、,.!?;:)]}》〉」』】"

@Composable
internal fun NovelReaderSurface(
    canonicalText: String,
    navigation: PlainTextNavigation,
    initialLocator: ReadingLocator,
    settings: ReaderSettings,
    initialVisualPageIndex: Int?,
    initialVisualPageCount: Int?,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction? = null,
    navigationRequestKey: Long = 0L,
    onPageChanged: (pageIndex: Int, pageCount: Int) -> Unit,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit = {},
    onTapAction: (ReaderTapAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = settings.novelFontSizeSp.coerceIn(12f, 36f).sp,
        lineHeight = (
            settings.novelFontSizeSp * settings.novelLineHeightMultiplier.coerceIn(1.15f, 2.4f)
        ).sp,
        letterSpacing = 0.12.sp,
        color = NOVEL_READER_TEXT,
    )
    val currentSourcePosition = remember(canonicalText, initialLocator) {
        NovelSourcePosition(initialLocator.resolveOffset(canonicalText) ?: 0)
    }

    BoxWithConstraints(modifier.fillMaxSize().background(NOVEL_READER_BACKGROUND)) {
        val horizontalGutter = 28.dp
        val columnWidth = minOf(
            (maxWidth - horizontalGutter * 2).coerceAtLeast(1.dp),
            settings.novelMaxWidthDp.coerceIn(480f, 1000f).dp,
        )
        val pageTextHeight = (maxHeight - 88.dp).coerceAtLeast(1.dp)
        val widthPixels = with(density) { columnWidth.roundToPx() }.coerceAtLeast(1)
        val heightPixels = with(density) { pageTextHeight.roundToPx() }.coerceAtLeast(1)
        val pages = remember(
            canonicalText,
            widthPixels,
            heightPixels,
            textStyle,
            textMeasurer,
        ) {
            paginateNovelContent(
                source = canonicalText,
                textMeasurer = textMeasurer,
                textStyle = textStyle,
                maxWidthPixels = widthPixels,
                maxHeightPixels = heightPixels,
            )
        }
        val locatorPage = pages.indexOfLast { page ->
            page.sourceStartUtf16 <= currentSourcePosition.offsetUtf16
        }
            .coerceAtLeast(0)
        // Consume a saved visual page only for this first pagination. Reflows recreate the keyed
        // reader from currentSourcePosition and keep the stable source position instead.
        var initialVisualPageConsumed by remember(canonicalText, initialLocator) {
            mutableStateOf(false)
        }
        val restoredVisualPage = initialVisualPageIndex
            ?.takeIf { initialVisualPageCount == null || initialVisualPageCount == pages.size }
            ?.takeUnless { initialVisualPageConsumed }
        val initialPage = (restoredVisualPage ?: locatorPage).coerceIn(pages.indices)
        if (restoredVisualPage != null) {
            SideEffect {
                currentSourcePosition.offsetUtf16 = novelPagedSourceOffset(pages, initialPage)
                initialVisualPageConsumed = true
            }
        }
        val initialPageOffsetFraction = pages[initialPage].let { page ->
            val sourceLength = page.sourceEndUtf16 - page.sourceStartUtf16
            if (sourceLength <= 0) 0.0 else {
                (currentSourcePosition.offsetUtf16 - page.sourceStartUtf16).toDouble()
                    .div(sourceLength.toDouble())
                    .coerceIn(0.0, 1.0)
            }
        }

        key(
            navigation.scope,
            widthPixels,
            heightPixels,
            textStyle,
            settings.novelFontSizeSp,
            settings.novelLineHeightMultiplier,
            settings.readingMode,
            pages.size,
        ) {
            if (isNovelContinuousMode(settings.readingMode)) {
                ContinuousNovelReader(
                    pages = pages,
                    initialPage = initialPage,
                    initialPageOffsetFraction = initialPageOffsetFraction,
                    requestedPageIndex = requestedPageIndex,
                    pageRequestSerial = pageRequestSerial,
                    navigationAction = navigationAction,
                    navigationRequestKey = navigationRequestKey,
                    animatePageTransitions = settings.animatePageTransitions,
                    readingMode = settings.readingMode,
                    columnWidth = columnWidth,
                    textStyle = textStyle,
                    onPageChanged = { index ->
                        onPageChanged(index, pages.size)
                    },
                    onViewportObserved = { viewport ->
                        currentSourcePosition.offsetUtf16 = novelViewportSourceOffset(
                            source = canonicalText,
                            pages = pages,
                            viewport = viewport,
                        )
                    },
                    onViewportChanged = { viewport ->
                        val pageIndex = if (viewport.atDocumentEnd) pages.lastIndex else viewport.pageIndex
                        onPageChanged(pageIndex, pages.size)
                        val sourceOffset = novelViewportSourceOffset(
                            source = canonicalText,
                            pages = pages,
                            viewport = viewport,
                        )
                        currentSourcePosition.offsetUtf16 = sourceOffset
                        onLocatorChanged(navigation.locatorForOffset(sourceOffset), pageIndex, pages.size)
                    },
                    onNavigationBoundary = onNavigationBoundary,
                    onTapAction = onTapAction,
                )
            } else {
                PagedNovelReader(
                    pages = pages,
                    initialPage = initialPage,
                    requestedPageIndex = requestedPageIndex,
                    pageRequestSerial = pageRequestSerial,
                    navigationAction = navigationAction,
                    navigationRequestKey = navigationRequestKey,
                    settings = settings,
                    columnWidth = columnWidth,
                    textStyle = textStyle,
                    onPageChanged = { index ->
                        currentSourcePosition.offsetUtf16 = novelPagedSourceOffset(pages, index)
                        reportNovelPage(index, pages, navigation, onPageChanged, onLocatorChanged)
                    },
                    onNavigationBoundary = onNavigationBoundary,
                    onTapAction = onTapAction,
                )
            }
        }
    }
}

private fun reportNovelPage(
    index: Int,
    pages: List<NovelVisualPage>,
    navigation: PlainTextNavigation,
    onPageChanged: (Int, Int) -> Unit,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int) -> Unit,
) {
    if (index !in pages.indices) return
    onPageChanged(index, pages.size)
    val sourceOffset = novelPagedSourceOffset(pages, index)
    onLocatorChanged(navigation.locatorForOffset(sourceOffset), index, pages.size)
}

internal fun novelPagedSourceOffset(pages: List<NovelVisualPage>, pageIndex: Int): Int {
    if (pages.isEmpty()) return 0
    val safeIndex = pageIndex.coerceIn(pages.indices)
    return if (safeIndex == pages.lastIndex) {
        pages[safeIndex].sourceEndUtf16
    } else {
        pages[safeIndex].sourceStartUtf16
    }
}

internal fun novelViewportSourceOffset(
    source: String,
    pages: List<NovelVisualPage>,
    viewport: NovelViewportPosition,
): Int {
    if (pages.isEmpty()) return 0
    val page = pages[viewport.pageIndex.coerceIn(pages.indices)]
    val rawOffset = if (viewport.atDocumentEnd) {
        pages.last().sourceEndUtf16
    } else {
        val sourceLength = (page.sourceEndUtf16 - page.sourceStartUtf16).coerceAtLeast(0)
        page.sourceStartUtf16 +
            (sourceLength * viewport.pageOffsetFraction.coerceIn(0.0, 1.0)).roundToInt()
    }
    return previousUtf16Boundary(source, rawOffset.coerceIn(0, source.length))
}

@Composable
private fun ContinuousNovelReader(
    pages: List<NovelVisualPage>,
    initialPage: Int,
    initialPageOffsetFraction: Double,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    animatePageTransitions: Boolean,
    readingMode: ReadingMode,
    columnWidth: Dp,
    textStyle: TextStyle,
    onPageChanged: (Int) -> Unit,
    onViewportObserved: (NovelViewportPosition) -> Unit,
    onViewportChanged: (NovelViewportPosition) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onTapAction: (ReaderTapAction) -> Unit,
) {
    // Capture the restoration cursor for this list instance. Live viewport observations must not
    // restart scrollToItem on every frame; outer geometry keys create a new instance on reflow.
    val restoredInitialPage = remember { initialPage }
    val restoredInitialPageOffsetFraction = remember { initialPageOffsetFraction }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoredInitialPage)
    var restorationReady by remember {
        mutableStateOf(restoredInitialPageOffsetFraction <= 0.0)
    }
    // A request serial that already existed when this surface was created has already been
    // reflected in currentSourceOffset. Replaying it after a resize or typography reflow would
    // jump back to the old visual page number instead of preserving the reader's text position.
    val initialPageRequestSerial = remember { pageRequestSerial }
    val initialNavigationRequestKey = remember { navigationRequestKey }
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    val latestOnViewportObserved by rememberUpdatedState(onViewportObserved)
    val latestOnViewportChanged by rememberUpdatedState(onViewportChanged)
    val latestOnNavigationBoundary by rememberUpdatedState(onNavigationBoundary)
    val latestOnTapAction by rememberUpdatedState(onTapAction)
    // Restoration and external page requests share one cancellable effect. A new request cancels
    // an in-flight fractional restore instead of letting the two scroll operations race.
    LaunchedEffect(listState, pageRequestSerial, pages.size) {
        if (pageRequestSerial != initialPageRequestSerial) {
            val target = requestedPageIndex?.coerceIn(pages.indices)
            if (target != null) {
                if (animatePageTransitions) listState.animateScrollToItem(target)
                else listState.scrollToItem(target)
            }
            restorationReady = true
            return@LaunchedEffect
        }
        if (restorationReady) return@LaunchedEffect
        val extent = snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == restoredInitialPage }
                ?.size
                ?: 0
        }.filter { it > 0 }.first()
        listState.scrollToItem(
            restoredInitialPage,
            (extent * restoredInitialPageOffsetFraction.coerceIn(0.0, 1.0)).roundToInt(),
        )
        restorationReady = true
    }
    // Hardware navigation must consult the list that owns the visible viewport. The shell's
    // reported page can trail a gesture by one frame, which previously made a volume press at the
    // penultimate page escape to the next chapter. A request present when this keyed surface is
    // recreated has already been consumed by the previous surface and must not be replayed.
    LaunchedEffect(listState, navigationRequestKey, pages.size, restorationReady) {
        val action = navigationAction
        if (
            !restorationReady ||
            navigationRequestKey <= 0L ||
            navigationRequestKey == initialNavigationRequestKey ||
            action == null
        ) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            restorationReady &&
                layout.totalItemsCount == pages.size &&
                layout.visibleItemsInfo.isNotEmpty() &&
                layout.viewportEndOffset > layout.viewportStartOffset
        }.filter { it }.first()
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> {
                if (!listState.canScrollBackward) {
                    latestOnNavigationBoundary(action)
                } else {
                    val viewport = (
                        listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                        ).coerceAtLeast(1)
                    if (animatePageTransitions) listState.animateScrollBy(-viewport.toFloat())
                    else listState.scrollBy(-viewport.toFloat())
                }
            }
            ReaderTapAction.NEXT_PAGE -> {
                if (!listState.canScrollForward) {
                    latestOnNavigationBoundary(action)
                } else {
                    val viewport = (
                        listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                        ).coerceAtLeast(1)
                    if (animatePageTransitions) listState.animateScrollBy(viewport.toFloat())
                    else listState.scrollBy(viewport.toFloat())
                }
            }
            ReaderTapAction.TOGGLE_CHROME -> latestOnTapAction(action)
        }
    }
    LaunchedEffect(listState, pages.size) {
        snapshotFlow { listState.firstVisibleItemIndex.coerceIn(pages.indices) }
            .distinctUntilChanged()
            .collect(latestOnPageChanged)
    }
    LaunchedEffect(listState, pages.size) {
        var settledReport: kotlinx.coroutines.Job? = null
        var lastViewport: NovelViewportPosition? = null
        var lastReportedViewport: NovelViewportPosition? = null
        try {
            snapshotFlow {
                if (!restorationReady) {
                    null
                } else {
                    val pageIndex = listState.firstVisibleItemIndex.coerceIn(pages.indices)
                    val extent = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == pageIndex }
                        ?.size
                        ?: 0
                    Triple(
                        pageIndex,
                        listState.firstVisibleItemScrollOffset to extent,
                        !listState.canScrollForward,
                    )
                }
            }
                .filterNotNull()
                .filter { (_, offsetAndExtent, _) -> offsetAndExtent.second > 0 }
                .collect { (pageIndex, offsetAndExtent, atDocumentEnd) ->
                    val (offset, extent) = offsetAndExtent
                    val viewport = NovelViewportPosition(
                        pageIndex = pageIndex,
                        pageOffsetFraction = offset.toDouble().div(extent.toDouble()).coerceIn(0.0, 1.0),
                        atDocumentEnd = atDocumentEnd,
                    )
                    // Keep reflow state current on every scroll frame, but avoid writing portable
                    // progress until the viewport has been still for a short interval.
                    lastViewport = viewport
                    latestOnViewportObserved(viewport)
                    settledReport?.cancel()
                    settledReport = launch {
                        delay(350)
                        latestOnViewportChanged(viewport)
                        lastReportedViewport = viewport
                    }
                }
        } finally {
            settledReport?.cancel()
            lastViewport?.takeIf { it != lastReportedViewport }?.let(latestOnViewportChanged)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .novelTapZones(readingMode, onTapAction),
        contentPadding = PaddingValues(vertical = 44.dp),
    ) {
        itemsIndexed(
            pages,
            key = { index, page ->
                val kind = if (page is NovelVisualPage.Text) "text" else "image"
                "${page.sourceStartUtf16}:$index:$kind"
            },
        ) { _, page ->
            NovelPage(
                page = page,
                columnWidth = columnWidth,
                textStyle = textStyle,
                modifier = Modifier
                    .fillParentMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedNovelReader(
    pages: List<NovelVisualPage>,
    initialPage: Int,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    settings: ReaderSettings,
    columnWidth: Dp,
    textStyle: TextStyle,
    onPageChanged: (Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onTapAction: (ReaderTapAction) -> Unit,
) {
    if (!settings.animatePageTransitions) {
        var pageIndex by remember(pages, initialPage) { mutableIntStateOf(initialPage) }
        val initialPageRequestSerial = remember { pageRequestSerial }
        val initialNavigationRequestKey = remember { navigationRequestKey }
        val latestOnPageChanged by rememberUpdatedState(onPageChanged)
        val latestOnNavigationBoundary by rememberUpdatedState(onNavigationBoundary)
        val latestOnTapAction by rememberUpdatedState(onTapAction)
        LaunchedEffect(pageRequestSerial, pages.size) {
            if (pageRequestSerial == initialPageRequestSerial) return@LaunchedEffect
            requestedPageIndex?.let { pageIndex = it.coerceIn(pages.indices) }
        }
        LaunchedEffect(navigationRequestKey, pages.size) {
            val action = navigationAction
            if (
                navigationRequestKey <= 0L ||
                navigationRequestKey == initialNavigationRequestKey ||
                action == null
            ) return@LaunchedEffect
            when (action) {
                ReaderTapAction.PREVIOUS_PAGE -> {
                    if (pageIndex > 0) pageIndex-- else latestOnNavigationBoundary(action)
                }
                ReaderTapAction.NEXT_PAGE -> {
                    if (pageIndex < pages.lastIndex) pageIndex++ else latestOnNavigationBoundary(action)
                }
                ReaderTapAction.TOGGLE_CHROME -> latestOnTapAction(action)
            }
        }
        LaunchedEffect(pageIndex, pages.size) { onPageChanged(pageIndex) }
        DisposableEffect(pages) {
            onDispose { latestOnPageChanged(pageIndex) }
        }
        NovelPage(
            page = pages[pageIndex],
            columnWidth = columnWidth,
            textStyle = textStyle,
            modifier = Modifier
                .fillMaxSize()
                .novelTapZones(settings.readingMode, onTapAction)
                .novelWheelPaging(settings.readingMode, onTapAction)
                .novelHorizontalDrag(settings.readingMode, onTapAction)
                .padding(vertical = 44.dp),
        )
        return
    }

    val pagerState = rememberPagerState(
        initialPage = readerPhysicalPageIndex(initialPage, pages.size, settings.readingMode),
    ) { pages.size }
    val initialPageRequestSerial = remember { pageRequestSerial }
    val initialNavigationRequestKey = remember { navigationRequestKey }
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    val latestOnNavigationBoundary by rememberUpdatedState(onNavigationBoundary)
    val latestOnTapAction by rememberUpdatedState(onTapAction)
    DisposableEffect(pagerState, pages.size, settings.readingMode) {
        onDispose {
            latestOnPageChanged(
                readerLogicalPageIndex(pagerState.currentPage, pages.size, settings.readingMode),
            )
        }
    }
    LaunchedEffect(pageRequestSerial, pages.size, settings.readingMode) {
        if (pageRequestSerial == initialPageRequestSerial) return@LaunchedEffect
        val logicalTarget = requestedPageIndex?.coerceIn(pages.indices) ?: return@LaunchedEffect
        val target = readerPhysicalPageIndex(logicalTarget, pages.size, settings.readingMode)
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }
    LaunchedEffect(navigationRequestKey, pages.size, settings.readingMode) {
        val action = navigationAction
        if (
            navigationRequestKey <= 0L ||
            navigationRequestKey == initialNavigationRequestKey ||
            action == null
        ) return@LaunchedEffect
        val current = readerLogicalPageIndex(
            pagerState.currentPage,
            pages.size,
            settings.readingMode,
        )
        when (action) {
            ReaderTapAction.PREVIOUS_PAGE -> {
                if (current <= 0) {
                    latestOnNavigationBoundary(action)
                } else {
                    pagerState.animateScrollToPage(
                        readerPhysicalPageIndex(current - 1, pages.size, settings.readingMode),
                    )
                }
            }
            ReaderTapAction.NEXT_PAGE -> {
                if (current >= pages.lastIndex) {
                    latestOnNavigationBoundary(action)
                } else {
                    pagerState.animateScrollToPage(
                        readerPhysicalPageIndex(current + 1, pages.size, settings.readingMode),
                    )
                }
            }
            ReaderTapAction.TOGGLE_CHROME -> latestOnTapAction(action)
        }
    }
    LaunchedEffect(pagerState, pages.size, settings.readingMode) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { physical ->
                latestOnPageChanged(readerLogicalPageIndex(physical, pages.size, settings.readingMode))
            }
    }
    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize(),
    ) { physicalIndex ->
        val logicalIndex = readerLogicalPageIndex(physicalIndex, pages.size, settings.readingMode)
        NovelPage(
            page = pages[logicalIndex],
            columnWidth = columnWidth,
            textStyle = textStyle,
            modifier = Modifier
                .fillMaxSize()
                .novelTapZones(settings.readingMode, onTapAction)
                .novelWheelPaging(settings.readingMode, onTapAction)
                .padding(vertical = 44.dp),
        )
    }
}

private fun Modifier.novelTapZones(
    readingMode: ReadingMode,
    onTapAction: (ReaderTapAction) -> Unit,
): Modifier = pointerInput(readingMode, onTapAction) {
    detectTapGestures { position ->
        onTapAction(novelTapAction(position.x, size.width.toFloat(), readingMode))
    }
}

private fun Modifier.novelHorizontalDrag(
    readingMode: ReadingMode,
    onTapAction: (ReaderTapAction) -> Unit,
): Modifier = pointerInput(readingMode, onTapAction) {
    var dragDistance = 0f
    val threshold = 64.dp.toPx()
    detectHorizontalDragGestures(
        onHorizontalDrag = { change, amount ->
            change.consume()
            dragDistance += amount
        },
        onDragEnd = {
            if (abs(dragDistance) >= threshold) {
                val forward = if (readingMode == ReadingMode.PAGER_RTL) dragDistance > 0f else dragDistance < 0f
                onTapAction(if (forward) ReaderTapAction.NEXT_PAGE else ReaderTapAction.PREVIOUS_PAGE)
            }
            dragDistance = 0f
        },
        onDragCancel = { dragDistance = 0f },
    )
}

private fun Modifier.novelWheelPaging(
    readingMode: ReadingMode,
    onTapAction: (ReaderTapAction) -> Unit,
): Modifier = pointerInput(readingMode, onTapAction) {
    awaitPointerEventScope {
        var accumulated = 0f
        var lastTurnAtMillis = -1L
        val threshold = 24.dp.toPx()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            val scroll = change.scrollDelta
            val delta = if (abs(scroll.y) >= abs(scroll.x)) {
                scroll.y
            } else if (readingMode == ReadingMode.PAGER_RTL) {
                -scroll.x
            } else {
                scroll.x
            }
            accumulated += delta
            if (abs(accumulated) >= threshold) {
                if (lastTurnAtMillis < 0L || change.uptimeMillis - lastTurnAtMillis >= 320L) {
                    onTapAction(
                        if (accumulated > 0f) ReaderTapAction.NEXT_PAGE else ReaderTapAction.PREVIOUS_PAGE,
                    )
                    lastTurnAtMillis = change.uptimeMillis
                }
                accumulated = 0f
            }
            event.changes.forEach { it.consume() }
        }
    }
}

@Composable
private fun NovelPage(
    page: NovelVisualPage,
    columnWidth: Dp,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        when (page) {
            is NovelVisualPage.Text -> SelectionContainer {
                Text(
                    text = page.value,
                    color = NOVEL_READER_TEXT,
                    style = textStyle,
                    modifier = Modifier
                        .widthIn(max = columnWidth)
                        .fillMaxWidth(),
                )
            }
            is NovelVisualPage.Image -> AsyncImage(
                model = page.url,
                contentDescription = page.alt.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = columnWidth)
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 680.dp)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

private fun paginateNovelContent(
    source: String,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    textStyle: TextStyle,
    maxWidthPixels: Int,
    maxHeightPixels: Int,
): List<NovelVisualPage> {
    val result = ArrayList<NovelVisualPage>()
    parseNovelReaderSegments(source).forEach { segment ->
        when (segment) {
            is NovelReaderSegment.Image -> result += NovelVisualPage.Image(
                url = segment.url,
                alt = segment.alt,
                sourceStartUtf16 = segment.sourceStartUtf16,
                sourceEndUtf16 = segment.sourceEndUtf16,
            )
            is NovelReaderSegment.Text -> {
                val display = sanitizeNovelDisplayText(segment.value)
                var cursor = 0
                while (cursor < display.value.length) {
                    val measuredEnd = measuredNovelPageEnd(
                        text = display.value,
                        start = cursor,
                        textMeasurer = textMeasurer,
                        textStyle = textStyle,
                        maxWidthPixels = maxWidthPixels,
                        maxHeightPixels = maxHeightPixels,
                    )
                    val end = preferredNovelPageBreak(display.value, cursor, measuredEnd)
                    val visible = display.value.substring(cursor, end).trimEnd()
                    if (visible.isNotEmpty()) {
                        result += NovelVisualPage.Text(
                            value = visible,
                            sourceStartUtf16 = segment.sourceStartUtf16 + display.sourceOffsets[cursor],
                            sourceEndUtf16 = segment.sourceStartUtf16 + display.sourceOffsets[end],
                        )
                    }
                    cursor = end.coerceAtLeast(nextUtf16Boundary(display.value, cursor))
                    while (cursor < display.value.length && (display.value[cursor] == ' ' || display.value[cursor] == '\t')) {
                        cursor++
                    }
                }
            }
        }
    }
    return result.ifEmpty {
        listOf(NovelVisualPage.Text("（本章沒有內容）", 0, source.length))
    }
}

private fun measuredNovelPageEnd(
    text: String,
    start: Int,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    textStyle: TextStyle,
    maxWidthPixels: Int,
    maxHeightPixels: Int,
): Int {
    fun fits(end: Int): Boolean {
        val layout = textMeasurer.measure(
            text = AnnotatedString(text.substring(start, end)),
            style = textStyle,
            overflow = TextOverflow.Clip,
            softWrap = true,
            constraints = Constraints(maxWidth = maxWidthPixels),
        )
        return layout.size.height <= maxHeightPixels && !layout.hasVisualOverflow
    }

    var best = start
    var probe = (start + 384).coerceAtMost(text.length)
    var firstOverflow = text.length
    while (true) {
        val boundary = previousUtf16Boundary(text, probe).coerceAtLeast(nextUtf16Boundary(text, start))
        if (!fits(boundary)) {
            firstOverflow = boundary
            break
        }
        best = boundary
        if (boundary >= text.length) return text.length
        val grown = start + (boundary - start) * 2
        probe = grown.coerceAtMost(text.length)
    }

    var low = (best + 1).coerceAtMost(firstOverflow)
    var high = firstOverflow - 1
    while (low <= high) {
        val rawMiddle = (low + high) ushr 1
        val middle = previousUtf16Boundary(text, rawMiddle).coerceAtLeast(nextUtf16Boundary(text, start))
        if (fits(middle)) {
            best = maxOf(best, middle)
            low = rawMiddle + 1
        } else {
            high = rawMiddle - 1
        }
    }
    return best.coerceAtLeast(nextUtf16Boundary(text, start))
}

private fun previousUtf16Boundary(text: String, requested: Int): Int {
    val index = requested.coerceIn(0, text.length)
    return if (
        index in 1 until text.length &&
        text[index - 1].isHighSurrogate() &&
        text[index].isLowSurrogate()
    ) index - 1 else index
}

private fun nextUtf16Boundary(text: String, start: Int): Int {
    if (start >= text.length) return text.length
    return if (
        start + 1 < text.length &&
        text[start].isHighSurrogate() &&
        text[start + 1].isLowSurrogate()
    ) start + 2 else start + 1
}
