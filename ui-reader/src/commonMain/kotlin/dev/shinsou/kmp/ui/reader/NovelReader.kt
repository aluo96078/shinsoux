package dev.shinsou.kmp.ui.reader

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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import dev.shinsou.kmp.content.acquisition.textChapterHeadingStarts
import dev.shinsou.kmp.contract.ReaderRemoteImageFetcher
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.domain.content.PlainTextNavigation
import dev.shinsou.kmp.reader.ReaderTapAction
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.reader.readerLogicalPageIndex
import dev.shinsou.kmp.reader.readerPhysicalPageIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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

/**
 * A key that turns pages and that a covered reader swallows, so nothing behind the page in front
 * moves. Volume, Back and Escape are not among them: they belong to the platform and that page.
 */
internal fun readerPagingKeyIsSwallowedWhileCovered(key: Key): Boolean = isReaderNavigationKey(key) &&
    key != Key.VolumeDown && key != Key.VolumeUp &&
    key != Key.Escape && key != Key.Back && key != Key.NavigatePrevious

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

/**
 * Only the EPUB surface (a web view) pages from the keyboard itself, so only it is handed the
 * paging keys; hardware volume keys and back stay with the host even there. The image reader has
 * no key handling of its own and pages through the host's page request, so arrow keys, space and
 * Page Up/Down must stay with the host: handing them over left manga unturnable from the keyboard.
 */
internal fun readerPreviewKeyIsHostOwned(
    key: Key,
    embeddedSurfaceOwnsPaging: Boolean,
): Boolean {
    if (key == Key.VolumeUp || key == Key.VolumeDown) return true
    if (!embeddedSurfaceOwnsPaging) return true
    return key == Key.Escape || key == Key.Back || key == Key.NavigatePrevious
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

    /**
     * Stands for one of the [estimatedCount] pages a section of a long text is taken to fill
     * until it is measured (see [NovelSectionedPagination]); shown blank, and replaced by the
     * section's real pages as soon as the reader gets near it.
     */
    data class Pending(
        val sectionIndex: Int,
        val ordinal: Int,
        val estimatedCount: Int,
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
    /** [requestedPageIndex] is the slider taken to its end, not a page turn onto the last page. */
    requestedDocumentEnd: Boolean = false,
    /** [initialLocator] is a saved position ([dev.shinsou.kmp.domain.content.UnifiedReaderContent.initialLocatorSaved]). */
    initialLocatorSaved: Boolean = false,
    navigationAction: ReaderTapAction? = null,
    navigationRequestKey: Long = 0L,
    onPageChanged: (pageIndex: Int, pageCount: Int) -> Unit,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit = {},
    onTapAction: (ReaderTapAction) -> Unit,
    modifier: Modifier = Modifier,
    remoteAssetScope: ReaderRemoteImageFetcher? = null,
    /**
     * Shows this text (a translation) instead of [canonicalText]. Positions reported to the
     * callbacks and taken from [initialLocator] stay positions in [canonicalText].
     */
    display: dev.shinsou.kmp.translation.TranslationDisplayText? = null,
) {
    val shownText = display?.text ?: canonicalText
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
    // Offsets below are offsets in the text on screen.
    val currentSourcePosition = remember(shownText, initialLocator) {
        val sourceOffset = novelResumeOffset(
            canonicalText,
            initialLocator,
            initialLocatorSaved,
            initialVisualPageIndex,
            initialVisualPageCount,
        )
        NovelSourcePosition(display?.toDisplay(sourceOffset) ?: sourceOffset)
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
        val geometry = remember(textMeasurer, textStyle, widthPixels, heightPixels) {
            NovelPageGeometry(textMeasurer, textStyle, widthPixels, heightPixels)
        }
        val interaction = remember { NovelReaderInteraction() }
        // A long text has nothing to show for the moment its sections are being found.
        val shown = rememberNovelShownPages(shownText, geometry, currentSourcePosition, interaction)
            ?: return@BoxWithConstraints
        val pages = shown.pages
        val locatorPage = novelPageIndexForOffset(pages, currentSourcePosition.offsetUtf16)
        // Consume a saved visual page only for this first pagination. Reflows recreate the keyed
        // reader from currentSourcePosition and keep the stable source position instead.
        var initialVisualPageConsumed by remember(shownText, initialLocator) {
            mutableStateOf(false)
        }
        // A long text's page numbers are estimates until all of it is measured, so a saved one
        // says nothing about where its reader was: the locator alone restores that.
        val restoredVisualPage = initialVisualPageIndex
            ?.takeIf { shown.pagination == null }
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

        NovelReaderPages(
            shown = shown,
            shownText = shownText,
            navigation = navigation,
            display = display,
            settings = settings,
            textStyle = textStyle,
            columnWidth = columnWidth,
            widthPixels = widthPixels,
            heightPixels = heightPixels,
            initialPage = initialPage,
            initialPageOffsetFraction = initialPageOffsetFraction,
            currentSourcePosition = currentSourcePosition,
            requestedPageIndex = requestedPageIndex,
            pageRequestSerial = pageRequestSerial,
            requestedDocumentEnd = requestedDocumentEnd,
            navigationAction = navigationAction,
            navigationRequestKey = navigationRequestKey,
            remoteAssetScope = remoteAssetScope,
            onScrollingChanged = { interaction.scrolling = it },
            onPageChanged = onPageChanged,
            onLocatorChanged = onLocatorChanged,
            onNavigationBoundary = onNavigationBoundary,
            onTapAction = onTapAction,
        )
    }
}

/** The pages on screen and, for a long text, the layout still measuring them. */
private class NovelShownPages(
    val pages: List<NovelVisualPage>,
    val pagination: NovelSectionedPagination?,
) {
    /** Which of the long text's page lists this is; callbacks of a pager showing an older one are stale. */
    val generation: Int = pagination?.generation ?: 0

    val stale: Boolean get() = pagination != null && pagination.generation != generation

    /** How a long text's pages are numbered to the user; null for a text measured whole, numbered as listed. */
    val numbering: NovelPageNumbering? = pagination?.numbering
}

/**
 * The page of the list a request for page [requested] of the numbering shown is for. The slider
 * at its end ([toDocumentEnd]) asks for the list's last page, whatever the numbering makes of it.
 */
private fun NovelShownPages.listIndexOfRequest(requested: Int?, toDocumentEnd: Boolean, currentListIndex: Int): Int? {
    val numbering = numbering
    // Asked for an earlier page than the one on screen (a page turned back): a section shown as
    // one page is entered at its end.
    val backward = numbering != null && currentListIndex >= 0 && requested != null &&
        requested < numbering.shown(currentListIndex)
    return when {
        requested == null -> null
        toDocumentEnd -> pages.lastIndex
        else -> numbering?.list(requested, backward) ?: requested
    }
}

/** The page of a list last reported ([rememberNumberedPageReports]). */
private class NovelLastReport(var listIndex: Int = -1)

/** Page reports to the reader's shell, in the numbering shown to the user. */
private class NovelPageReports(
    val page: (pageIndex: Int, pageCount: Int) -> Unit,
    val locator: (ReadingLocator, pageIndex: Int, pageCount: Int) -> Unit,
    /** The page of the list last reported; -1 before any. */
    val currentListIndex: () -> Int,
)

/**
 * [onPageChanged] and [onLocatorChanged] for page indexes of [shown]'s list, numbered as shown to
 * the user. When the numbering is renewed while the list stays (the count came nearer the real
 * one), the page last reported is reported again in it: the shell's total follows, and its page
 * stays the page on screen.
 */
@Composable
private fun rememberNumberedPageReports(
    shown: NovelShownPages,
    onPageChanged: (pageIndex: Int, pageCount: Int) -> Unit,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int) -> Unit,
): NovelPageReports {
    val numbering = shown.numbering
    // What was last reported, of this list only: a new list (a new generation) has other indexes.
    val last = remember(shown.pagination, shown.generation) { NovelLastReport() }
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    // Only the toolbar follows: the reader has not moved, so there is no progress to write (a
    // write marks the title for sync, and at a chapter's end completes it again). The progress
    // kept is in the numbering of the last page arrived at, and once the layout settles the
    // reader is recreated on the whole list and reports its place in the final numbering.
    LaunchedEffect(numbering) {
        val index = last.listIndex
        if (numbering != null && index >= 0) latestOnPageChanged(numbering.shown(index), numbering.count)
    }
    return NovelPageReports(
        page = { index, count ->
            last.listIndex = index
            if (numbering == null) onPageChanged(index, count) else onPageChanged(numbering.shown(index), numbering.count)
        },
        locator = { locator, index, count ->
            last.listIndex = index
            if (numbering == null) {
                onLocatorChanged(locator, index, count)
            } else {
                onLocatorChanged(locator, numbering.shown(index), numbering.count)
            }
        },
        currentListIndex = { last.listIndex },
    )
}

/** Whether the reader is being scrolled or is turning a page: no time to measure or swap pages under it. */
private class NovelReaderInteraction(var scrolling: Boolean = false)

/**
 * The pages of [shownText] for [geometry]. A text up to [NOVEL_SECTIONED_TEXT_THRESHOLD] long is
 * measured whole, here and now; a longer one a section at a time ([NovelSectionedPagination]),
 * and is null for the moment its sections are being found.
 */
@Composable
private fun rememberNovelShownPages(
    shownText: String,
    geometry: NovelPageGeometry,
    position: NovelSourcePosition,
    interaction: NovelReaderInteraction,
): NovelShownPages? {
    if (shownText.length <= NOVEL_SECTIONED_TEXT_THRESHOLD) {
        val pages = remember(shownText, geometry) { paginateNovelContent(shownText, geometry) }
        return NovelShownPages(pages, pagination = null)
    }
    val pagination = rememberNovelSectionedPagination(shownText, geometry, position, interaction)
    return pagination?.let { NovelShownPages(it.pages, it) }
}

@Composable
private fun rememberNovelSectionedPagination(
    shownText: String,
    geometry: NovelPageGeometry,
    position: NovelSourcePosition,
    interaction: NovelReaderInteraction,
    workDispatcher: CoroutineDispatcher = Dispatchers.Default,
): NovelSectionedPagination? {
    // Finding the chapters reads the whole text: off the main thread, which only measures.
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var sections by remember(shownText) { mutableStateOf<NovelTextSections?>(null) }
    LaunchedEffect(shownText, workDispatcher) {
        sections = withContext(workDispatcher) {
            NovelTextSections(
                starts = novelSectionStarts(shownText, textChapterHeadingStarts(shownText) { ensureActive() }),
                // What identifies the text to [NovelPageCountMemory], read here with the rest of it.
                textHash = shownText.hashCode(),
            )
        }
    }
    // A new geometry is a new layout, begun at the section the reader is in.
    val pagination = remember(sections, geometry) {
        sections?.let { found ->
            NovelSectionedPagination(
                text = shownText,
                sectionStarts = found.starts,
                layout = { sectionText, sourceOffset -> MeasuredNovelPageSteps(sectionText, sourceOffset, geometry, quickBreaks = true) },
                focusOffsetUtf16 = position.offsetUtf16,
                rememberedCounts = NovelPageCountMemory.countsFor(
                    key = NovelPageCountKey(
                        text = shownText.length to found.textHash,
                        textStyle = geometry.textStyle,
                        sizePixels = geometry.maxWidthPixels to geometry.maxHeightPixels,
                        // sp become pixels by these: another font scale or display is another layout.
                        density = density.density to density.fontScale,
                        layoutDirection = layoutDirection,
                    ),
                    sections = found.starts.size,
                ),
            )
        }
    }
    LaunchedEffect(pagination) {
        if (pagination != null) measureNovelSectionsBetweenFrames(pagination, interaction)
    }
    return pagination
}

/** Where the sections of a long text begin ([novelSectionStarts]), and its hash. */
private class NovelTextSections(val starts: IntArray, val textHash: Int)

/** A text and the layout its pages were measured under ([NovelPageCountMemory]). */
private data class NovelPageCountKey(
    /** The text's length and hash. */
    val text: Pair<Int, Int>,
    val textStyle: TextStyle,
    val sizePixels: Pair<Int, Int>,
    /** The density and font scale sp are resolved with. */
    val density: Pair<Float, Float>,
    val layoutDirection: LayoutDirection,
)

/** Time per frame given to measuring the rest of a long text while its reader is idle. */
private val NOVEL_LAYOUT_SLICE: Duration = 4.milliseconds
private const val NOVEL_LAYOUT_MAX_SKIPPED_FRAMES: Int = 8

/**
 * Measures the rest of a long text on the composing thread, where text is measured, a few
 * milliseconds a frame and never while the reader scrolls. A device on which one page takes longer
 * than that is left as many frames to itself. Ends once every page is measured and shown, or
 * with the caller.
 */
private suspend fun measureNovelSectionsBetweenFrames(
    pagination: NovelSectionedPagination,
    interaction: NovelReaderInteraction,
) {
    var framesToSkip = 0
    while (!pagination.settled) {
        withFrameNanos { }
        when {
            interaction.scrolling -> Unit
            framesToSkip > 0 -> framesToSkip--
            else -> {
                val started = TimeSource.Monotonic.markNow()
                pagination.layOutMore { started.elapsedNow() < NOVEL_LAYOUT_SLICE }
                pagination.publishIfUseful()
                framesToSkip = ((started.elapsedNow() / NOVEL_LAYOUT_SLICE).toInt() - 1)
                    .coerceIn(0, NOVEL_LAYOUT_MAX_SKIPPED_FRAMES)
            }
        }
    }
}

/**
 * The page request the pagers are to act on. A request for a placeholder of a long text is acted
 * on here instead: its section is measured and the pagers are recreated on the page asked for,
 * where scrolling them to the placeholder would stop on whichever one came into view first.
 */
@Composable
private fun requestedNovelPage(
    shown: NovelShownPages,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    requestedDocumentEnd: Boolean,
    currentSourcePosition: NovelSourcePosition,
    /** The page asked for in the numbering shown, which can tell a section's pages apart where the list cannot. */
    requestedShownIndex: Int?,
): Int? {
    val pagination = shown.pagination ?: return requestedPageIndex
    // A request older than this layout has been acted on already.
    val firstSerial = remember(pagination) { pageRequestSerial }
    val target = requestedPageIndex?.takeIf { pageRequestSerial != firstSerial }?.coerceIn(shown.pages.indices)
    val pending = target?.let { shown.pages[it] as? NovelVisualPage.Pending }
    // Where in its section the page asked for is: a section measured since it was listed has
    // more pages than placeholders, and the one asked for is found among them in proportion.
    val within = requestedShownIndex?.let { shown.numbering?.positionInSection(it) }
    LaunchedEffect(pagination, pageRequestSerial) {
        if (pending != null) {
            currentSourcePosition.offsetUtf16 = pagination.arrive(
                page = pending,
                fromOffsetUtf16 = currentSourcePosition.offsetUtf16,
                // Only the slider taken to its end asks for the last page, however many the
                // section has; a page turn onto its one placeholder shows its first page.
                toEnd = requestedDocumentEnd && target == shown.pages.lastIndex,
                within = within,
            )
        }
    }
    return if (pending != null) null else requestedPageIndex
}

/** The pages of one paginated text, read continuously or paged; rebuilt whenever the text or its layout changes. */
@Composable
private fun NovelReaderPages(
    shown: NovelShownPages,
    shownText: String,
    navigation: PlainTextNavigation,
    display: dev.shinsou.kmp.translation.TranslationDisplayText?,
    settings: ReaderSettings,
    textStyle: TextStyle,
    columnWidth: Dp,
    widthPixels: Int,
    heightPixels: Int,
    initialPage: Int,
    initialPageOffsetFraction: Double,
    currentSourcePosition: NovelSourcePosition,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    requestedDocumentEnd: Boolean,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    remoteAssetScope: ReaderRemoteImageFetcher?,
    onScrollingChanged: (Boolean) -> Unit,
    onPageChanged: (pageIndex: Int, pageCount: Int) -> Unit,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onTapAction: (ReaderTapAction) -> Unit,
) {
    val pages = shown.pages
    // Pages are reported, and asked for, in the numbering shown to the user ([NovelPageNumbering]).
    val reports = rememberNumberedPageReports(shown, onPageChanged, onLocatorChanged)
    val pagerRequestedPageIndex = requestedNovelPage(
        shown,
        shown.listIndexOfRequest(requestedPageIndex, requestedDocumentEnd, reports.currentListIndex()),
        pageRequestSerial,
        requestedDocumentEnd,
        currentSourcePosition,
        requestedPageIndex,
    )
    key(
        navigation.scope,
        widthPixels,
        heightPixels,
        textStyle,
        settings.novelFontSizeSp,
        settings.novelLineHeightMultiplier,
        settings.readingMode,
        pages.size,
        // A long text's pages are replaced as they are measured: each list is another document too.
        shown.generation,
        // Another text on screen (a translation switched on or off) is another document to
        // the pager and list states, even when it happens to paginate into as many pages.
        display,
    ) {
        if (isNovelContinuousMode(settings.readingMode)) {
            ContinuousNovelReader(
                pages = pages,
                initialPage = initialPage,
                initialPageOffsetFraction = initialPageOffsetFraction,
                requestedPageIndex = pagerRequestedPageIndex,
                pageRequestSerial = pageRequestSerial,
                navigationAction = navigationAction,
                navigationRequestKey = navigationRequestKey,
                animatePageTransitions = settings.animatePageTransitions,
                readingMode = settings.readingMode,
                columnWidth = columnWidth,
                textStyle = textStyle,
                remoteAssetScope = remoteAssetScope,
                onScrollingChanged = onScrollingChanged,
                onPageChanged = { index ->
                    // Scrolled onto a placeholder, the list is recreated where its section begins
                    // (or ends, scrolled up to it): there is no page to report until then.
                    if (!shown.stale && shown.arrivedOn(index, currentSourcePosition)) {
                        reports.page(index, pages.size)
                    }
                },
                onViewportObserved = { viewport ->
                    // A position in a placeholder is an estimate, and would make the arrival on it
                    // look like one from inside it: the reader stays where it last was on a page.
                    if (!shown.stale && !shown.onPlaceholder(viewport)) {
                        currentSourcePosition.offsetUtf16 = novelViewportSourceOffset(
                            source = shownText,
                            pages = pages,
                            viewport = viewport,
                        )
                    }
                },
                onViewportChanged = { viewport ->
                    if (!shown.stale && !shown.onPlaceholder(viewport)) {
                        val pageIndex = if (viewport.atDocumentEnd) pages.lastIndex else viewport.pageIndex
                        reports.page(pageIndex, pages.size)
                        val sourceOffset = novelViewportSourceOffset(
                            source = shownText,
                            pages = pages,
                            viewport = viewport,
                        )
                        currentSourcePosition.offsetUtf16 = sourceOffset
                        reports.locator(navigation.locatorForOffset(display?.toSource(sourceOffset) ?: sourceOffset), pageIndex, pages.size)
                    }
                },
                onNavigationBoundary = onNavigationBoundary,
                onTapAction = onTapAction,
            )
        } else {
            PagedNovelReader(
                pages = pages,
                initialPage = initialPage,
                requestedPageIndex = pagerRequestedPageIndex,
                pageRequestSerial = pageRequestSerial,
                navigationAction = navigationAction,
                navigationRequestKey = navigationRequestKey,
                settings = settings,
                columnWidth = columnWidth,
                textStyle = textStyle,
                remoteAssetScope = remoteAssetScope,
                onScrollingChanged = onScrollingChanged,
                onPageChanged = { index ->
                    if (!shown.stale && shown.arrivedOn(index, currentSourcePosition)) {
                        currentSourcePosition.offsetUtf16 = novelPagedSourceOffset(pages, index)
                        reportNovelPage(index, pages, navigation, display, reports.page, reports.locator)
                    }
                },
                onNavigationBoundary = onNavigationBoundary,
                onTapAction = onTapAction,
            )
        }
    }
}

/** The viewport a continuous reader saw last, and the one it reported last. */
private class NovelViewportReports {
    var last: NovelViewportPosition? = null
    private var reported: NovelViewportPosition? = null

    /** Reports [last] unless it is reported already. */
    fun flush(report: (NovelViewportPosition) -> Unit) {
        val viewport = last ?: return
        if (viewport == reported) return
        reported = viewport
        report(viewport)
    }
}

/** Whether [viewport] begins on a placeholder of a long text, or ends the document on one. */
private fun NovelShownPages.onPlaceholder(viewport: NovelViewportPosition): Boolean {
    val index = if (viewport.atDocumentEnd) pages.lastIndex else viewport.pageIndex
    return pages.getOrNull(index) is NovelVisualPage.Pending ||
        pages.getOrNull(viewport.pageIndex) is NovelVisualPage.Pending
}

/**
 * The pager or list arrived on page [index]. On a placeholder of a long text its section is
 * measured ([NovelSectionedPagination.arrive], in the direction of travel from [position]) and
 * [position] moved to the page to show, where the readers are recreated; this is then false:
 * there is no page to report yet. Otherwise the layout is told where its reader is, and this is true.
 */
private fun NovelShownPages.arrivedOn(index: Int, position: NovelSourcePosition): Boolean {
    val pending = pages.getOrNull(index) as? NovelVisualPage.Pending
    val layout = pagination
    when {
        layout == null -> Unit
        pending != null -> position.offsetUtf16 = layout.arrive(pending, fromOffsetUtf16 = position.offsetUtf16)
        else -> layout.shown(index)
    }
    return pending == null
}

private fun reportNovelPage(
    index: Int,
    pages: List<NovelVisualPage>,
    navigation: PlainTextNavigation,
    display: dev.shinsou.kmp.translation.TranslationDisplayText?,
    onPageChanged: (Int, Int) -> Unit,
    onLocatorChanged: (ReadingLocator, pageIndex: Int, pageCount: Int) -> Unit,
) {
    if (index !in pages.indices) return
    onPageChanged(index, pages.size)
    val shownOffset = novelPagedSourceOffset(pages, index)
    onLocatorChanged(navigation.locatorForOffset(display?.toSource(shownOffset) ?: shownOffset), index, pages.size)
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
    remoteAssetScope: ReaderRemoteImageFetcher?,
    onScrollingChanged: (Boolean) -> Unit,
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
    ReportNovelScrolling(onScrollingChanged) { listState.isScrollInProgress }
    // Restoration and external page requests share one cancellable effect. A new request cancels
    // an in-flight fractional restore instead of letting the two scroll operations race.
    LaunchedEffect(listState, pageRequestSerial, pages.size) {
        if (pageRequestSerial != initialPageRequestSerial) {
            val target = requestedPageIndex?.coerceIn(pages.indices)
            if (target != null) {
                // Not animated past a placeholder, which would stop the jump at its section.
                if (animatePageTransitions && !novelPendingBetween(pages, listState.firstVisibleItemIndex, target)) {
                    listState.animateScrollToItem(target)
                } else {
                    listState.scrollToItem(target)
                }
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
    // The viewport last seen and the one last reported; leaving reports the one in between at once.
    val viewports = remember(listState, pages.size) { NovelViewportReports() }
    val progressFlush = LocalReaderProgressFlush.current
    DisposableEffect(viewports, progressFlush) {
        val unregister = progressFlush?.register { viewports.flush(latestOnViewportChanged) }
        onDispose { unregister?.invoke() }
    }
    LaunchedEffect(listState, pages.size) {
        var settledReport: kotlinx.coroutines.Job? = null
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
                    viewports.last = viewport
                    latestOnViewportObserved(viewport)
                    settledReport?.cancel()
                    settledReport = launch {
                        delay(350)
                        viewports.flush(latestOnViewportChanged)
                    }
                }
        } finally {
            settledReport?.cancel()
            viewports.flush(latestOnViewportChanged)
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
                val kind = when (page) {
                    is NovelVisualPage.Text -> "text"
                    is NovelVisualPage.Image -> "image"
                    is NovelVisualPage.Pending -> "pending"
                }
                "${page.sourceStartUtf16}:$index:$kind"
            },
        ) { _, page ->
            NovelPage(
                page = page,
                columnWidth = columnWidth,
                textStyle = textStyle,
                remoteAssetScope = remoteAssetScope,
                // A placeholder has no text to give it a height: it takes a screenful.
                modifier = if (page is NovelVisualPage.Pending) {
                    Modifier.fillParentMaxWidth().fillParentMaxHeight()
                } else {
                    Modifier.fillParentMaxWidth()
                },
            )
        }
    }
}

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
    remoteAssetScope: ReaderRemoteImageFetcher?,
    onScrollingChanged: (Boolean) -> Unit,
    onPageChanged: (Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onTapAction: (ReaderTapAction) -> Unit,
) {
    if (!settings.animatePageTransitions) {
        InstantPagedNovelReader(
            pages = pages,
            initialPage = initialPage,
            requestedPageIndex = requestedPageIndex,
            pageRequestSerial = pageRequestSerial,
            navigationAction = navigationAction,
            navigationRequestKey = navigationRequestKey,
            settings = settings,
            columnWidth = columnWidth,
            textStyle = textStyle,
            remoteAssetScope = remoteAssetScope,
            onScrollingChanged = onScrollingChanged,
            onPageChanged = onPageChanged,
            onNavigationBoundary = onNavigationBoundary,
            onTapAction = onTapAction,
        )
    } else {
        AnimatedPagedNovelReader(
            pages = pages,
            initialPage = initialPage,
            requestedPageIndex = requestedPageIndex,
            pageRequestSerial = pageRequestSerial,
            navigationAction = navigationAction,
            navigationRequestKey = navigationRequestKey,
            settings = settings,
            columnWidth = columnWidth,
            textStyle = textStyle,
            remoteAssetScope = remoteAssetScope,
            onScrollingChanged = onScrollingChanged,
            onPageChanged = onPageChanged,
            onNavigationBoundary = onNavigationBoundary,
            onTapAction = onTapAction,
        )
    }
}

/**
 * Tells [onScrollingChanged] whether the pager or list is moving, and that it is not once it has
 * left the screen.
 */
@Composable
private fun ReportNovelScrolling(onScrollingChanged: (Boolean) -> Unit, scrolling: () -> Boolean) {
    val latestOnScrollingChanged by rememberUpdatedState(onScrollingChanged)
    val latestScrolling by rememberUpdatedState(scrolling)
    LaunchedEffect(Unit) {
        snapshotFlow { latestScrolling() }.distinctUntilChanged().collect { latestOnScrollingChanged(it) }
    }
    DisposableEffect(Unit) {
        onDispose { latestOnScrollingChanged(false) }
    }
}

/** Pages that change at once, for a reader with page animations off. */
@Composable
private fun InstantPagedNovelReader(
    pages: List<NovelVisualPage>,
    initialPage: Int,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    settings: ReaderSettings,
    columnWidth: Dp,
    textStyle: TextStyle,
    remoteAssetScope: ReaderRemoteImageFetcher?,
    onScrollingChanged: (Boolean) -> Unit,
    onPageChanged: (Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onTapAction: (ReaderTapAction) -> Unit,
) {
    var pageIndex by remember(pages, initialPage) { mutableIntStateOf(initialPage) }
    // A page dragged is a page being turned: nothing is measured or swapped under it meanwhile.
    var dragging by remember { mutableStateOf(false) }
    ReportNovelScrolling(onScrollingChanged) { dragging }
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
        remoteAssetScope = remoteAssetScope,
        modifier = Modifier
            .fillMaxSize()
            .novelTapZones(settings.readingMode, onTapAction)
            .novelWheelPaging(settings.readingMode, onTapAction)
            .novelHorizontalDrag(settings.readingMode, onTapAction) { dragging = it }
            .padding(vertical = 44.dp),
    )
}

/** Pages in a swipeable pager, for a reader with page animations on. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnimatedPagedNovelReader(
    pages: List<NovelVisualPage>,
    initialPage: Int,
    requestedPageIndex: Int?,
    pageRequestSerial: Long,
    navigationAction: ReaderTapAction?,
    navigationRequestKey: Long,
    settings: ReaderSettings,
    columnWidth: Dp,
    textStyle: TextStyle,
    remoteAssetScope: ReaderRemoteImageFetcher?,
    onScrollingChanged: (Boolean) -> Unit,
    onPageChanged: (Int) -> Unit,
    onNavigationBoundary: (ReaderTapAction) -> Unit,
    onTapAction: (ReaderTapAction) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = readerPhysicalPageIndex(initialPage, pages.size, settings.readingMode),
    ) { pages.size }
    val initialPageRequestSerial = remember { pageRequestSerial }
    val initialNavigationRequestKey = remember { navigationRequestKey }
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    val latestOnNavigationBoundary by rememberUpdatedState(onNavigationBoundary)
    val latestOnTapAction by rememberUpdatedState(onTapAction)
    ReportNovelScrolling(onScrollingChanged) { pagerState.isScrollInProgress }
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
        val current = readerLogicalPageIndex(pagerState.currentPage, pages.size, settings.readingMode)
        when {
            pagerState.currentPage == target -> Unit
            // Not animated past a placeholder, which would stop the jump at its section.
            novelPendingBetween(pages, current, logicalTarget) -> pagerState.scrollToPage(target)
            else -> pagerState.animateScrollToPage(target)
        }
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
            remoteAssetScope = remoteAssetScope,
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
    onDragging: (Boolean) -> Unit = {},
): Modifier = pointerInput(readingMode, onTapAction) {
    var dragDistance = 0f
    val threshold = 64.dp.toPx()
    // Cancelled mid-drag (the gesture restarts on new keys), it calls neither end nor cancel.
    try {
        detectHorizontalDragGestures(
            onDragStart = { onDragging(true) },
            onHorizontalDrag = { change, amount ->
                change.consume()
                dragDistance += amount
            },
            onDragEnd = {
                onDragging(false)
                if (abs(dragDistance) >= threshold) {
                    val forward = if (readingMode == ReadingMode.PAGER_RTL) dragDistance > 0f else dragDistance < 0f
                    onTapAction(if (forward) ReaderTapAction.NEXT_PAGE else ReaderTapAction.PREVIOUS_PAGE)
                }
                dragDistance = 0f
            },
            onDragCancel = {
                onDragging(false)
                dragDistance = 0f
            },
        )
    } finally {
        onDragging(false)
    }
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
    remoteAssetScope: ReaderRemoteImageFetcher?,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        when (page) {
            // Not selectable: the chapter text is not to be highlighted or copied out of the reader.
            is NovelVisualPage.Text -> Text(
                text = page.value,
                color = NOVEL_READER_TEXT,
                style = textStyle,
                modifier = Modifier
                    .widthIn(max = columnWidth)
                    .fillMaxWidth(),
            )
            is NovelVisualPage.Image -> ProtectedNovelImage(
                url = page.url,
                alt = page.alt,
                remoteAssetScope = remoteAssetScope,
                modifier = Modifier
                    .widthIn(max = columnWidth)
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 680.dp)
                    .padding(vertical = 12.dp),
            )
            // Blank until its section is measured, which arriving here has just asked for.
            is NovelVisualPage.Pending -> Unit
        }
    }
}

@Composable
private fun ProtectedNovelImage(
    url: String,
    alt: String,
    remoteAssetScope: ReaderRemoteImageFetcher?,
    modifier: Modifier,
) {
    val context = LocalPlatformContext.current
    var retryKey by remember(url, remoteAssetScope) { mutableStateOf(0) }
    val image = rememberNovelRemoteImage(url, remoteAssetScope, retryKey)
    var decodeFailed by remember(url, remoteAssetScope, retryKey) { mutableStateOf(false) }
    val body = image.bytes
    if (body == null || decodeFailed) {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (image.failed || decodeFailed) {
                androidx.compose.material3.TextButton(onClick = { retryKey++ }) {
                    Text("圖片載入失敗，重試", color = NOVEL_READER_TEXT)
                }
            }
        }
        return
    }
    var viewing by remember(url, retryKey) { mutableStateOf(false) }
    AsyncImage(
        model = ImageRequest.Builder(context).data(body).build(),
        contentDescription = alt.ifBlank { null },
        contentScale = ContentScale.Fit,
        onError = { decodeFailed = true },
        // A tap opens the picture full screen to zoom or save; swiping still turns the page.
        modifier = modifier.pointerInput(body) { detectTapGestures(onTap = { viewing = true }) },
    )
    if (viewing) {
        dev.shinsou.kmp.ui.components.ImageViewerDialog(
            bytes = body,
            suggestedName = dev.shinsou.kmp.ui.components.imageFileNameFor(url, fallbackStem = "illustration"),
            contentDescription = alt.ifBlank { null },
            onDismiss = { viewing = false },
        )
    }
}
