package dev.shinsou.kmp.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/** The text measuring a page break is found with; pages laid out with equal geometry are the same pages. */
internal data class NovelPageGeometry(
    val textMeasurer: TextMeasurer,
    val textStyle: TextStyle,
    val maxWidthPixels: Int,
    val maxHeightPixels: Int,
)

/**
 * The pages of one text, laid out a page at a time so a long text never holds the main thread
 * for its whole length. [pages] grows as [step] is called.
 */
internal interface NovelPageSteps {
    val pages: List<NovelVisualPage>

    /** Lays out the next page; false once there is nothing left to lay out. */
    fun step(): Boolean
}

/** Starts the layout of the text of one section, whose first character is at [sourceOffset] of the whole text. */
internal fun interface NovelSectionLayout {
    fun begin(sectionText: String, sourceOffset: Int): NovelPageSteps
}

/**
 * Measures [source] into pages. Page offsets are offsets in [source] plus [sourceOffset]. Uses a
 * [TextMeasurer], so it belongs to the thread that composes.
 */
internal class MeasuredNovelPageSteps(
    private val source: String,
    private val sourceOffset: Int,
    private val geometry: NovelPageGeometry,
    /**
     * Finds a page end with a few layouts instead of a search. A long text's sections are
     * measured so; a text measured whole keeps the search, and so the page breaks it always had.
     */
    private val quickBreaks: Boolean,
) : NovelPageSteps {
    private val laidOut = ArrayList<NovelVisualPage>()
    private var segments: List<NovelReaderSegment>? = null
    private var segmentIndex = 0
    private var display: NovelDisplayText? = null
    private var cursor = 0

    /** How much text is laid out to find a page end in: twice the longest page so far. */
    private var pageReach = NOVEL_FIRST_PAGE_REACH

    override val pages: List<NovelVisualPage> get() = laidOut

    override fun step(): Boolean {
        val all = segments ?: parseNovelReaderSegments(source).also { segments = it }
        when (val segment = all.getOrNull(segmentIndex)) {
            null -> Unit
            is NovelReaderSegment.Image -> {
                laidOut += NovelVisualPage.Image(
                    url = segment.url,
                    alt = segment.alt,
                    sourceStartUtf16 = sourceOffset + segment.sourceStartUtf16,
                    sourceEndUtf16 = sourceOffset + segment.sourceEndUtf16,
                )
                segmentIndex++
            }
            is NovelReaderSegment.Text -> stepText(segment)
        }
        return segmentIndex < all.size
    }

    private fun stepText(segment: NovelReaderSegment.Text) {
        val shown = display ?: sanitizeNovelDisplayText(segment.value).also {
            display = it
            cursor = 0
        }
        val text = shown.value
        if (cursor < text.length) {
            // A text measured whole keeps the search and never pays for the quick layouts.
            val measuredEnd = (if (quickBreaks) quickNovelPageEnd(text, cursor, geometry, pageReach) else null)
                ?: searchedNovelPageEnd(text, cursor, geometry)
            pageReach = maxOf(pageReach, (measuredEnd - cursor) * 2)
            val end = preferredNovelPageBreak(text, cursor, measuredEnd)
            val visible = text.substring(cursor, end).trimEnd()
            if (visible.isNotEmpty()) {
                val segmentStart = sourceOffset + segment.sourceStartUtf16
                laidOut += NovelVisualPage.Text(
                    value = visible,
                    sourceStartUtf16 = segmentStart + shown.sourceOffsets[cursor],
                    sourceEndUtf16 = segmentStart + shown.sourceOffsets[end],
                )
            }
            cursor = end.coerceAtLeast(nextUtf16Boundary(text, cursor))
            while (cursor < text.length && (text[cursor] == ' ' || text[cursor] == '\t')) {
                cursor++
            }
        }
        if (cursor >= text.length) {
            display = null
            segmentIndex++
        }
    }
}

private const val NOVEL_FIRST_PAGE_REACH: Int = 768

/** Lays out every page left. */
internal fun NovelPageSteps.stepToTheEnd() {
    var more = true
    while (more) more = step()
}

internal const val NOVEL_EMPTY_TEXT_PAGE: String = "（本章沒有內容）"

/** The whole of [source] laid out at once: what a text short enough to lay out in one go gets. */
internal fun paginateNovelContent(source: String, geometry: NovelPageGeometry): List<NovelVisualPage> {
    val steps = MeasuredNovelPageSteps(source, sourceOffset = 0, geometry = geometry, quickBreaks = false)
    steps.stepToTheEnd()
    return steps.pages.ifEmpty {
        listOf(NovelVisualPage.Text(NOVEL_EMPTY_TEXT_PAGE, 0, source.length))
    }
}

private fun NovelPageGeometry.measure(text: String, start: Int, end: Int): TextLayoutResult = textMeasurer.measure(
    text = AnnotatedString(text.substring(start, end)),
    style = textStyle,
    overflow = TextOverflow.Clip,
    softWrap = true,
    constraints = Constraints(maxWidth = maxWidthPixels),
)

/** Whether `text[start, end)` fits a page of this geometry. */
private fun NovelPageGeometry.fits(text: String, start: Int, end: Int): Boolean {
    val layout = measure(text, start, end)
    return layout.size.height <= maxHeightPixels && !layout.hasVisualOverflow
}

/**
 * A page end read off one layout of more text than a page holds ([reach] characters): the end of
 * its last line that is wholly on the page. Null unless measuring confirms that the run up to
 * there fits by itself; [searchedNovelPageEnd], with many more layouts, is then asked instead.
 * The two mostly agree, and where they do not this run still fits its page.
 */
private fun quickNovelPageEnd(text: String, start: Int, geometry: NovelPageGeometry, reach: Int): Int? {
    val first = nextUtf16Boundary(text, start)
    val probeEnd = previousUtf16Boundary(text, (start + reach).coerceAtMost(text.length)).coerceAtLeast(first)
    val layout = geometry.measure(text, start, probeEnd)
    var lastLine = -1
    while (lastLine + 1 < layout.lineCount && layout.getLineBottom(lastLine + 1) <= geometry.maxHeightPixels) {
        lastLine++
    }
    // No line fits, or all of them do and the page may hold more than was laid out.
    if (lastLine !in 0 until layout.lineCount - 1) return null
    val end = previousUtf16Boundary(text, start + layout.getLineEnd(lastLine, visibleEnd = false))
    return end.takeIf {
        it in first until text.length && geometry.fits(text, start, it)
    }
}

/** The end of the longest run of [text] from [start] that fits one page. */
private fun searchedNovelPageEnd(text: String, start: Int, geometry: NovelPageGeometry): Int {
    fun fits(end: Int): Boolean = geometry.fits(text, start, end)

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

internal fun previousUtf16Boundary(text: String, requested: Int): Int {
    val index = requested.coerceIn(0, text.length)
    return if (
        index in 1 until text.length &&
        text[index - 1].isHighSurrogate() &&
        text[index].isLowSurrogate()
    ) index - 1 else index
}

internal fun nextUtf16Boundary(text: String, start: Int): Int {
    if (start >= text.length) return text.length
    return if (
        start + 1 < text.length &&
        text[start].isHighSurrogate() &&
        text[start + 1].isLowSurrogate()
    ) start + 2 else start + 1
}
