package dev.shinsou.kmp.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.domain.model.ReadingLocator

/**
 * A text longer than this is laid out a section at a time ([NovelSectionedPagination]); a shorter
 * one is laid out whole, in one go, as every text used to be.
 */
internal const val NOVEL_SECTIONED_TEXT_THRESHOLD: Int = 64_000

/** The most characters laid out in one go: a section longer than this is cut at a paragraph. */
internal const val NOVEL_SECTION_MAX_CHARS: Int = 24_000

/**
 * A page of an unmeasured section is taken to hold this many characters, or more, until enough of
 * the text has been measured to say how much a page holds.
 */
private const val NOVEL_ASSUMED_CHARS_PER_PAGE: Double = 600.0

/** How much measured text the characters per page of the measured pages are trusted on. */
private const val NOVEL_TRUSTED_MEASURED_CHARS: Long = 12_000

/** The most placeholders a whole text is estimated to fill, however little a measured page held. */
private const val NOVEL_MAX_ESTIMATED_PAGES: Int = 60_000

/** How many full pages (not the last of a section) make what a page holds known. */
private const val NOVEL_TRUSTED_FULL_PAGES: Long = 3

/** How far the estimated page count may drift from the one shown before the new one is shown. */
private const val NOVEL_REPUBLISH_DRIFT: Double = 0.01

/** A placeholder this near the page being read is replaced as soon as its section is laid out. */
private const val NOVEL_PENDING_LOOKAHEAD_PAGES: Int = 3

/** How far back a tag or an image marker that a cut would fall inside may have begun. */
private const val NOVEL_MARKUP_LOOKBEHIND: Int = 512

/**
 * Where the sections of [text] begin, ascending and starting with 0. A section is what is laid
 * out in one go, and always begins a page.
 *
 * A chapter is a section: [headingStarts] are the starts of the chapter heading lines. A chapter
 * longer than [maxSectionChars] is cut into several, at a line start where there is one. Blank
 * text before a heading does not make a section of its own, and no cut falls inside a tag or an
 * image marker, which would then be shown as text.
 */
internal fun novelSectionStarts(
    text: String,
    headingStarts: List<Int>,
    maxSectionChars: Int = NOVEL_SECTION_MAX_CHARS,
): IntArray {
    val starts = ArrayList<Int>()
    starts += 0
    var chapterStart = 0
    // Found in the whole text: a marker with a long URL can be longer than any window around a cut.
    val images = novelImageSpans(text)
    val cutter = NovelSectionCutter(text, images, maxSectionChars)
    headingStarts.forEach { heading ->
        // A heading-like line inside a multiline image marker (its alt text) is no chapter.
        val begins = heading > chapterStart && heading < text.length &&
            previousUtf16Boundary(text, heading) == heading && images.startAround(heading) == null
        if (begins && !text.isBlankBetween(chapterStart, heading)) {
            cutter.addSizeCuts(starts, heading)
            starts += heading
            chapterStart = heading
        }
    }
    cutter.addSizeCuts(starts, text.length)
    return starts.toIntArray()
}

/** The image markers of a text, ascending: where each begins and ends. */
internal class NovelImageSpans(private val starts: IntArray, private val ends: IntArray) {
    /** Where the marker that [at] falls strictly inside begins; null when it is inside none. */
    fun startAround(at: Int): Int? = indexAround(at)?.let { starts[it] }

    /** Where the marker that [at] falls strictly inside ends; null when it is inside none. */
    fun endAround(at: Int): Int? = indexAround(at)?.let { ends[it] }

    private fun indexAround(at: Int): Int? {
        var low = 0
        var high = starts.lastIndex
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (starts[middle] < at) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found.takeIf { it >= 0 && at < ends[it] }
    }
}

/** The image markers of [text] ([parseNovelReaderSegments]). Reads the whole text: off the main thread. */
internal fun novelImageSpans(text: String): NovelImageSpans {
    val images = parseNovelReaderSegments(text).filterIsInstance<NovelReaderSegment.Image>()
    return NovelImageSpans(
        images.map { it.sourceStartUtf16 }.toIntArray(),
        images.map { it.sourceEndUtf16 }.toIntArray(),
    )
}

/** Cuts the text between chapters into sections of at most [maxSectionChars], never inside markup. */
private class NovelSectionCutter(
    private val text: String,
    private val images: NovelImageSpans,
    private val maxSectionChars: Int,
) {
    /** Cuts what lies between the last of [starts] and [end]. */
    fun addSizeCuts(starts: MutableList<Int>, end: Int) {
        var from = starts.last()
        while (end - from > maxSectionChars) {
            val cut = novelSectionCut(text, from, from + maxSectionChars, images)
            if (cut <= from || cut >= end) break
            starts += cut
            from = cut
        }
    }
}

/**
 * Where to cut a section that began at [from] and may run to [limit] at most: the last line start
 * in its second half that is not inside markup, else [limit] itself, moved before any markup it
 * would fall inside.
 */
private fun novelSectionCut(text: String, from: Int, limit: Int, images: NovelImageSpans): Int {
    val earliest = from + (limit - from) / 2
    // Never looked for before the second half: a text with few line breaks would otherwise be
    // read back towards its start for every cut.
    var newline = text.lastIndexOfAtOrAfter('\n', limit - 1, earliest)
    while (newline >= earliest) {
        if (novelMarkupStartAround(text, newline + 1, images) == null) return newline + 1
        newline = text.lastIndexOfAtOrAfter('\n', newline - 1, earliest)
    }
    val hard = previousUtf16Boundary(text, limit)
    val markup = novelMarkupStartAround(text, hard, images)
    return when {
        markup == null -> hard
        markup > from -> markup
        // A marker longer than a section, from where it begins: the section runs to its end.
        else -> images.endAround(hard) ?: hard
    }
}

/** The first index of [char] at or after [from] and before [end]; -1 when there is none. */
private fun String.firstIndexOfBefore(char: Char, from: Int, end: Int): Int {
    var index = from.coerceAtLeast(0)
    while (index < end.coerceAtMost(length) && this[index] != char) index++
    return if (index < end.coerceAtMost(length)) index else -1
}

/** The last index of [char] at or before [from] and at or after [lowest]; -1 when there is none. */
internal fun String.lastIndexOfAtOrAfter(char: Char, from: Int, lowest: Int): Int {
    var index = from.coerceAtMost(lastIndex)
    while (index >= lowest.coerceAtLeast(0) && this[index] != char) index--
    return if (index >= lowest.coerceAtLeast(0)) index else -1
}

/**
 * Where a tag or an image marker ([images], of the whole of [text]) that a cut at [at] would fall
 * inside begins; null when the cut falls between them. A tag is looked for near the cut only:
 * being wrong about ordinary text with a `<` in it only moves a cut.
 */
internal fun novelMarkupStartAround(text: String, at: Int, images: NovelImageSpans): Int? {
    val windowStart = previousUtf16Boundary(text, (at - NOVEL_MARKUP_LOOKBEHIND).coerceAtLeast(0))
    val windowEnd = previousUtf16Boundary(text, (at + NOVEL_MARKUP_LOOKBEHIND).coerceAtMost(text.length))
    // Both looked for in the window only, not back to the start of the text or on to its end.
    val tag = text.lastIndexOfAtOrAfter('<', at - 1, windowStart).takeIf { open ->
        open >= 0 && text.firstIndexOfBefore('>', open, windowEnd) in at until windowEnd
    }
    return images.startAround(at) ?: tag ?: novelEntityStartAround(text, at)
}

/** The longest character reference a cut is kept out of, `&` and `;` included (`&thetasym;`). */
private const val NOVEL_MAX_ENTITY_LENGTH: Int = 12

/**
 * Where an HTML character reference (`&amp;`, `&#38;`) that a cut at [at] would fall inside
 * begins; null when it falls inside none. Looked for near the cut only, as tags are.
 */
internal fun novelEntityStartAround(text: String, at: Int): Int? {
    fun isNamePart(char: Char): Boolean = char.isLetterOrDigit() || char == '#'
    var open = at - 1
    while (open >= 0 && at - open < NOVEL_MAX_ENTITY_LENGTH && isNamePart(text[open])) open--
    if (open < 0 || text[open] != '&') return null
    var close = at
    while (close < text.length && close - open < NOVEL_MAX_ENTITY_LENGTH && isNamePart(text[close])) close++
    return open.takeIf { close < text.length && text[close] == ';' && close - open > 1 }
}

private fun String.isBlankBetween(start: Int, end: Int): Boolean {
    for (index in start until end) {
        if (!this[index].isWhitespace()) return false
    }
    return true
}

/** The index of the last of the ascending [starts] that is at or before [offset]; 0 when none is. */
internal fun novelSectionIndexFor(starts: IntArray, offset: Int): Int {
    var low = 0
    var high = starts.lastIndex
    while (low < high) {
        val middle = (low + high + 1) ushr 1
        if (starts[middle] <= offset) low = middle else high = middle - 1
    }
    return low.coerceAtLeast(0)
}

/**
 * The page [offset] is on: the last of [pages] (in reading order) that starts at or before it,
 * the first page when none does. What `indexOfLast` gave, without visiting every page.
 */
internal fun novelPageIndexForOffset(pages: List<NovelVisualPage>, offset: Int): Int {
    var low = 0
    var high = pages.lastIndex
    while (low < high) {
        val middle = (low + high + 1) ushr 1
        if (pages[middle].sourceStartUtf16 <= offset) low = middle else high = middle - 1
    }
    return low.coerceAtLeast(0)
}

/**
 * Whether a page between [from] and [to] of [pages] (either included) is a placeholder. A jump
 * that would scroll past one is not animated: arriving on it on the way would measure its
 * section and recreate the reader there, not on the page asked for.
 */
internal fun novelPendingBetween(pages: List<NovelVisualPage>, from: Int, to: Int): Boolean {
    val range = minOf(from, to).coerceAtLeast(0)..maxOf(from, to).coerceAtMost(pages.lastIndex)
    return range.any { pages[it] is NovelVisualPage.Pending }
}

/**
 * How many characters a page not measured yet is taken to hold, in a text of [textLength]
 * characters of which [measuredChars] filled [measuredPages] pages, [fullChars] of them the
 * [fullPages] pages that are not the last of their section (a full page; the last is mostly not).
 *
 * What a full page held, as soon as a few were measured ([NOVEL_TRUSTED_FULL_PAGES]): pages hold
 * what the screen, the font and the script make them hold (some 200 CJK characters on a phone),
 * and only measuring says how much. Till then, or with sections too short to fill a page (a
 * title page), at least [NOVEL_ASSUMED_CHARS_PER_PAGE] unless enough was measured
 * ([NOVEL_TRUSTED_MEASURED_CHARS], or a quarter of a short text): a short first section then does
 * not make the rest of a long book many times as many placeholders as it has pages. Never so
 * little that the whole text makes more than [NOVEL_MAX_ESTIMATED_PAGES] placeholders.
 */
internal fun novelEstimatedCharsPerPage(
    measuredChars: Long,
    measuredPages: Long,
    textLength: Int,
    fullChars: Long = 0,
    fullPages: Long = 0,
): Double {
    val estimate = when {
        fullPages >= NOVEL_TRUSTED_FULL_PAGES -> fullChars.toDouble() / fullPages
        measuredPages > 0 && measuredChars >= minOf(NOVEL_TRUSTED_MEASURED_CHARS, textLength / 4L) ->
            measuredChars.toDouble() / measuredPages
        measuredPages > 0 -> maxOf(measuredChars.toDouble() / measuredPages, NOVEL_ASSUMED_CHARS_PER_PAGE)
        else -> NOVEL_ASSUMED_CHARS_PER_PAGE
    }
    return maxOf(estimate, textLength.toDouble() / NOVEL_MAX_ESTIMATED_PAGES, 1.0)
}

/**
 * Where page [pageIndex] of a saved pagination of a text of [textLength] characters began,
 * roughly: in proportion when its page count [pageCount] is known, else at
 * [NOVEL_ASSUMED_CHARS_PER_PAGE] characters a page. For a long text resumed from a saved page
 * alone, without a text position; its section is then laid out first.
 */
internal fun novelOffsetForSavedPage(textLength: Int, pageIndex: Int, pageCount: Int?): Int {
    val estimate = if (pageCount != null && pageCount > 0) {
        textLength.toLong() * pageIndex.coerceIn(0, pageCount) / pageCount
    } else {
        (pageIndex.coerceAtLeast(0) * NOVEL_ASSUMED_CHARS_PER_PAGE).toLong()
    }
    return estimate.coerceIn(0L, textLength.toLong()).toInt()
}

/**
 * Where a reader of [text] resumes: what [locator] resolves to, except for a long text whose
 * locator says nothing (it cannot be found, or is the start a session without a saved text
 * position gets, [locatorSaved] false) but whose saved page [pageIndex] (of [pageCount]) does: an older record or a
 * synced chapter may hold only that. Its page numbers were those of the whole text laid out at
 * once, so the page is placed in proportion ([novelOffsetForSavedPage]).
 */
internal fun novelResumeOffset(
    text: String,
    locator: ReadingLocator,
    locatorSaved: Boolean,
    pageIndex: Int?,
    pageCount: Int?,
): Int {
    val located = locator.resolveOffset(text)
    // A saved locator at the very start is where the reader was (a sync can move it back there
    // and leave the page as it was): only one not saved, or not found, says nothing.
    val locatorSaysNothing = located == null || located == 0 && !locatorSaved
    val savedPage = pageIndex?.takeIf { it > 0 && text.length > NOVEL_SECTIONED_TEXT_THRESHOLD }
    return if (locatorSaysNothing && savedPage != null) {
        previousUtf16Boundary(text, novelOffsetForSavedPage(text.length, savedPage, pageCount))
    } else {
        located ?: 0
    }
}

/**
 * How many placeholders stand for a section of [chars] characters not measured yet: as many
 * pages as it had when it was last measured under the same layout ([remembered]), else the
 * estimate ([estimatedNovelPageCount]). Never none.
 */
internal fun novelPlaceholderCount(chars: Int, charsPerPage: Double, remembered: Int?): Int =
    remembered?.takeIf { it > 0 } ?: estimatedNovelPageCount(chars, charsPerPage)

/** How many pages [chars] characters are taken to fill before they are measured; never none. */
internal fun estimatedNovelPageCount(chars: Int, charsPerPage: Double): Int {
    val perPage = charsPerPage.coerceAtLeast(1.0)
    val whole = (chars / perPage).toInt()
    val pages = if (whole * perPage < chars) whole + 1 else whole
    return pages.coerceIn(1, chars.coerceAtLeast(1))
}

/**
 * Every page of [text] in reading order: the measured pages of the sections in [measured], and
 * for a section not measured yet as many [NovelVisualPage.Pending] pages as it is estimated to
 * fill, spread evenly over it. Page indexes and the page count are therefore estimates until
 * every section is measured, and exact after.
 */
internal fun assembleNovelPages(
    text: String,
    sectionStarts: IntArray,
    measured: Array<List<NovelVisualPage>?>,
    charsPerPage: Double,
    rememberedCounts: IntArray? = null,
): List<NovelVisualPage> {
    val pages = ArrayList<NovelVisualPage>()
    sectionStarts.forEachIndexed { section, start ->
        val known = measured[section]
        if (known != null) {
            pages += known
        } else {
            val length = sectionStarts.getOrElse(section + 1) { text.length } - start
            val count = novelPlaceholderCount(length, charsPerPage, rememberedCounts?.getOrNull(section))
            repeat(count) { ordinal ->
                pages += NovelVisualPage.Pending(
                    sectionIndex = section,
                    ordinal = ordinal,
                    estimatedCount = count,
                    sourceStartUtf16 = previousUtf16Boundary(text, start + (length.toLong() * ordinal / count).toInt()),
                    sourceEndUtf16 = previousUtf16Boundary(text, start + (length.toLong() * (ordinal + 1) / count).toInt()),
                )
            }
        }
    }
    return pages.ifEmpty { listOf(NovelVisualPage.Text(NOVEL_EMPTY_TEXT_PAGE, 0, text.length)) }
}

/**
 * The pages of a long text, measured a section at a time instead of all at once.
 *
 * Sections ([sectionStarts]) are measured independently, each beginning a page, so the pages of a
 * section are the same whichever section was measured first. The section being read is measured
 * when this is made; the rest are measured a page at a time by [layOutMore], nearest the reader
 * first. [pages] always covers the whole text: sections not measured yet stand in it as
 * [NovelVisualPage.Pending] pages, so a page index is a position in the whole text from the start.
 *
 * [pages] is only replaced by [publish]: a new list is a new document to the pager showing it,
 * which is recreated at the reader's text position, so it is replaced when that is needed
 * ([reveal], [publishIfUseful]) and not once per section.
 *
 * Not thread-safe: it measures text, so everything here belongs to the composing thread.
 */
internal class NovelSectionedPagination(
    private val text: String,
    private val sectionStarts: IntArray,
    private val layout: NovelSectionLayout,
    focusOffsetUtf16: Int,
    /**
     * How many pages each section had when it was last measured under the same layout (0 when it
     * was not), kept by the caller across readers ([NovelPageCountMemory]): a reader opened again
     * shows the page count it ended with, not an estimate. Measuring here fills it in.
     */
    private val rememberedCounts: IntArray? = null,
) {
    private val measured = arrayOfNulls<List<NovelVisualPage>>(sectionStarts.size)
    private var measuredSections = 0
    private var measuredChars = 0L
    private var measuredPages = 0L
    private var fullChars = 0L
    private var fullPages = 0L
    private var publishedSections = -1
    private var underway: Pair<Int, NovelPageSteps>? = null
    private var focusSection = novelSectionIndexFor(sectionStarts, focusOffsetUtf16)
    private var shownIndex: Int? = null

    /** Counts the lists published: a pager is recreated for each, even one as long as the last. */
    var generation: Int = 0
        private set

    var pages: List<NovelVisualPage> by mutableStateOf(emptyList())
        private set

    /**
     * How the reader numbers the pages of [pages] to the user: by the page counts of the sections
     * as best known now, which [pages] may not have caught up with ([publishIfUseful]).
     */
    var numbering: NovelPageNumbering by mutableStateOf(NovelPageNumbering(IntArray(0), IntArray(0)))
        private set

    private var publishedCounts = IntArray(0)

    /** Every section is measured and [pages] shows them all: nothing is left to do. */
    val settled: Boolean get() = complete && publishedSections == measuredSections

    private val complete: Boolean get() = measuredSections == sectionStarts.size

    init {
        measureNow(focusSection)
        publish()
    }

    /** The reader is on page [index] of [pages]: sections near it are measured first. */
    fun shown(index: Int) {
        val page = pages.getOrNull(index) ?: return
        shownIndex = index
        focusSection = novelSectionIndexFor(sectionStarts, page.sourceStartUtf16)
    }

    /**
     * The reader arrived on [page], a placeholder: measures its section now, publishes, and
     * returns the text offset to show. Paging [backward] onto a section's last placeholder shows
     * the section's last page and arriving on its first shows the first, so turning a page into
     * a section never skips any of it; landing on its last placeholder, or a jump [toEnd] of the
     * book, shows its last page, and elsewhere the offset is in proportion. A section with
     * nothing to show is passed over in the direction of travel.
     */
    fun reveal(page: NovelVisualPage.Pending, backward: Boolean, toEnd: Boolean = false, within: NovelSectionPosition? = null): Int {
        val direction = if (backward) -1 else 1
        var section = page.sectionIndex
        measureNow(section)
        while (measured[section].isNullOrEmpty() && section + direction in sectionStarts.indices) {
            section += direction
            measureNow(section)
        }
        focusSection = section
        // Asked again about a section already shown (a pager reports its page as it leaves), there is nothing new.
        if (publishedSections != measuredSections) publish()
        val real = measured[section].orEmpty()
        if (real.isEmpty()) return page.sourceStartUtf16
        val local = novelArrivalPage(page, real.size, passedOver = section != page.sectionIndex, NovelArrival(backward, toEnd, within))
        return real[local].sourceStartUtf16
    }

    /**
     * The reader, at [fromOffsetUtf16] until now, arrived on [page]: [reveal]s it in the
     * direction of travel (backward when the placeholder lies before where the reader was, as
     * one scrolled up to is) and returns the text offset to show.
     */
    fun arrive(
        page: NovelVisualPage.Pending,
        fromOffsetUtf16: Int,
        toEnd: Boolean = false,
        within: NovelSectionPosition? = null,
    ): Int = reveal(page, backward = page.sourceStartUtf16 < fromOffsetUtf16, toEnd = toEnd, within = within)

    /**
     * Measures pages of the sections not measured yet, nearest the reader first, for as long as
     * [mayContinue] allows after each page; at least one page.
     */
    fun layOutMore(mayContinue: () -> Boolean) {
        do {
            val (section, steps) = underway ?: nextUnmeasured()?.let { it to begin(it) } ?: return
            underway = if (steps.step()) {
                section to steps
            } else {
                finish(section, steps.pages)
                null
            }
        } while (mayContinue())
    }

    /**
     * Publishes what has been measured since the last time, when the reader gains by it now: a
     * placeholder near the page being read has been measured, or everything has. Publishing
     * recreates the reader on the new list, so it is not done for anything else: when what has
     * been measured moves the page count by more than [NOVEL_REPUBLISH_DRIFT], only the
     * [numbering] the reader reports its pages in is renewed, which needs no new list. The count
     * so comes near the real one by itself, and the page being read stays where it is.
     */
    fun publishIfUseful() {
        if (publishedSections == measuredSections) return
        val index = shownIndex
        val nearby = index != null && (index - NOVEL_PENDING_LOOKAHEAD_PAGES..index + NOVEL_PENDING_LOOKAHEAD_PAGES).any {
            val pending = pages.getOrNull(it) as? NovelVisualPage.Pending
            pending != null && measured[pending.sectionIndex] != null
        }
        when {
            complete || nearby -> publish()
            else -> {
                val estimated = estimatedCounts()
                val drift = kotlin.math.abs(estimated.sum() - numbering.count)
                if (drift > numbering.count * NOVEL_REPUBLISH_DRIFT) numbering = NovelPageNumbering(publishedCounts, estimated)
            }
        }
    }

    /** The page count a list published now would have: what [publish] would assemble, counted. */
    fun estimatedPageCount(): Int = estimatedCounts().sum().coerceAtLeast(1)

    /** Each section's page count as best known now: measured, remembered or estimated. */
    private fun estimatedCounts(): IntArray {
        val charsPerPage = charsPerPage()
        return IntArray(sectionStarts.size) { section ->
            val length = sectionEnd(section) - sectionStarts[section]
            measured[section]?.size ?: novelPlaceholderCount(length, charsPerPage, rememberedCounts?.getOrNull(section))
        }
    }

    private fun charsPerPage(): Double =
        novelEstimatedCharsPerPage(measuredChars, measuredPages, text.length, fullChars, fullPages)

    private fun publish() {
        publishedSections = measuredSections
        // The old index says nothing about the new list; the pager reports its page again.
        shownIndex = null
        generation++
        pages = assembleNovelPages(text, sectionStarts, measured, charsPerPage(), rememberedCounts)
        publishedCounts = IntArray(sectionStarts.size).also { counts ->
            pages.forEach { page -> novelSectionOf(page)?.let { counts[it]++ } }
        }
        numbering = NovelPageNumbering(publishedCounts, publishedCounts)
    }

    /** The section [page] of [pages] belongs to. */
    private fun novelSectionOf(page: NovelVisualPage): Int? = when (page) {
        is NovelVisualPage.Pending -> page.sectionIndex
        else -> novelSectionIndexFor(sectionStarts, page.sourceStartUtf16).takeIf { sectionStarts.isNotEmpty() }
    }

    private fun sectionEnd(section: Int): Int = sectionStarts.getOrElse(section + 1) { text.length }

    private fun begin(section: Int): NovelPageSteps =
        layout.begin(text.substring(sectionStarts[section], sectionEnd(section)), sectionStarts[section])

    private fun measureNow(section: Int) {
        if (measured[section] != null) return
        val steps = underway?.takeIf { it.first == section }?.second ?: begin(section)
        if (underway?.first == section) underway = null
        steps.stepToTheEnd()
        finish(section, steps.pages)
    }

    private fun finish(section: Int, pages: List<NovelVisualPage>) {
        measured[section] = pages
        measuredSections++
        measuredChars += sectionEnd(section) - sectionStarts[section]
        measuredPages += pages.size
        // A text page that another text page follows is full: what it holds is what a page
        // holds. Not an image, not text an image cut short, not a section's last page.
        pages.zipWithNext().forEach { (page, next) ->
            if (page is NovelVisualPage.Text && next is NovelVisualPage.Text) {
                fullChars += next.sourceStartUtf16 - page.sourceStartUtf16
                fullPages++
            }
        }
        rememberedCounts?.let { counts -> if (section in counts.indices) counts[section] = pages.size }
    }

    /** The unmeasured section nearest the one being read, the one ahead before the one behind. */
    private fun nextUnmeasured(): Int? {
        var found: Int? = null
        var distance = 0
        while (found == null && distance < sectionStarts.size) {
            found = unmeasuredOrNull(focusSection + distance) ?: unmeasuredOrNull(focusSection - distance)
            distance++
        }
        return found
    }

    private fun unmeasuredOrNull(section: Int): Int? =
        section.takeIf { it in sectionStarts.indices && measured[it] == null }
}

/**
 * The page counts of the sections of the long texts laid out lately, each under its layout
 * ([NovelSectionedPagination.rememberedCounts]): a reader opened again on one shows the count it
 * ended with straight away. Kept in memory for the few texts read last; only the composing thread
 * uses it.
 */
internal object NovelPageCountMemory {
    private const val TEXTS_KEPT = 8
    private val counts = LinkedHashMap<Any, IntArray>()

    /** The counts kept for [key] (a text and its layout), made for [sections] sections when there are none. */
    fun countsFor(key: Any, sections: Int): IntArray {
        val kept = counts.remove(key)?.takeIf { it.size == sections } ?: IntArray(sections)
        counts[key] = kept
        while (counts.size > TEXTS_KEPT) counts.remove(counts.keys.first())
        return kept
    }
}

/**
 * Page [local] of [from] pages of a section as one of [to] pages of it: in proportion, with its
 * first page the first and its last the last, whichever count is the larger. A section's last
 * page stays its last, so turning back onto it, or the slider at the end, finds its end. A
 * section of one page is both its first and its last: reached [backward], it is its last.
 */
internal fun novelScaledOrdinal(local: Int, from: Int, to: Int, backward: Boolean = false): Int = when {
    to <= 1 -> 0
    from <= 1 -> if (backward) to - 1 else 0
    local >= from - 1 -> to - 1
    else -> (local.toLong() * (to - 1) / (from - 1)).toInt()
}

/** How the reader arrived on a placeholder ([NovelSectionedPagination.reveal]). */
private class NovelArrival(val backward: Boolean, val toEnd: Boolean, val within: NovelSectionPosition?)

/**
 * Which of the [size] measured pages of the section of [page] the reader arriving on it sees;
 * [passedOver] when its own section had nothing to show and this is the next one in the
 * direction of travel.
 */
private fun novelArrivalPage(page: NovelVisualPage.Pending, size: Int, passedOver: Boolean, arrival: NovelArrival): Int {
    val enteredAtItsEnd = passedOver || page.ordinal == page.estimatedCount - 1
    val within = arrival.within
    return when {
        // An explicit jump to the end of the book: its last page, however many there are.
        arrival.toEnd -> size - 1
        // A page asked for by its number shown: the one at that place in the section, whichever
        // way it was asked for (a section shown as one page is entered at its end going back).
        within != null && !passedOver -> novelScaledOrdinal(within.local, within.count, size, arrival.backward)
        arrival.backward && enteredAtItsEnd -> size - 1
        passedOver || page.ordinal == 0 -> 0
        // The last placeholder of a section is its last page.
        page.ordinal == page.estimatedCount - 1 -> size - 1
        else -> (page.ordinal.toLong() * size / page.estimatedCount).toInt().coerceIn(0, size - 1)
    }
}

/** Page [local] of the [count] pages of a section. */
internal data class NovelSectionPosition(val local: Int, val count: Int)

/**
 * How the pages of a published list ([listCounts] of them in each section) are numbered to the
 * user when the sections are now taken to have [shownCounts] pages: within a section in
 * proportion, so the page being read keeps its place while the total comes near the real one.
 * With equal counts it is the list's own numbering.
 */
internal class NovelPageNumbering(private val listCounts: IntArray, private val shownCounts: IntArray) {
    private val listStarts = prefixSums(listCounts)
    private val shownStarts = prefixSums(shownCounts)

    /** How many pages are shown. */
    val count: Int = shownCounts.sum()

    /** Where page [shownIndex] is in its section, in the numbering shown; null without sections. */
    fun positionInSection(shownIndex: Int): NovelSectionPosition? {
        if (shownCounts.isEmpty()) return null
        val section = novelSectionIndexFor(shownStarts, shownIndex).coerceIn(shownCounts.indices)
        val sectionCount = shownCounts[section]
        val local = (shownIndex - shownStarts[section]).coerceIn(0, (sectionCount - 1).coerceAtLeast(0))
        return NovelSectionPosition(local, sectionCount)
    }

    /** The number shown for page [listIndex] of the list. */
    fun shown(listIndex: Int): Int =
        map(listIndex, Numbered(listStarts, listCounts), Numbered(shownStarts, shownCounts), backward = false)

    /**
     * The page of the list shown as number [shownIndex], asked for [backward] (from a later page):
     * a section shown as one page is then its last page of the list, else its first.
     */
    fun list(shownIndex: Int, backward: Boolean = false): Int =
        map(shownIndex, Numbered(shownStarts, shownCounts), Numbered(listStarts, listCounts), backward)

    /** Where each section's pages begin, and how many it has, in one numbering. */
    private class Numbered(val starts: IntArray, val counts: IntArray)

    private fun map(index: Int, from: Numbered, to: Numbered, backward: Boolean): Int {
        val fromStarts = from.starts
        val fromCounts = from.counts
        val toCounts = to.counts
        if (fromCounts.isEmpty()) return index
        val section = novelSectionIndexFor(fromStarts, index).coerceIn(fromCounts.indices)
        val local = (index - fromStarts[section]).coerceIn(0, (fromCounts[section] - 1).coerceAtLeast(0))
        val toStarts = to.starts
        val scaled = novelScaledOrdinal(local, fromCounts[section], toCounts[section], backward)
        return (toStarts[section] + scaled).coerceIn(0, (toCounts.sum() - 1).coerceAtLeast(0))
    }

    private fun prefixSums(counts: IntArray): IntArray {
        var sum = 0
        return IntArray(counts.size) { index -> sum.also { sum += counts[index] } }
    }
}
