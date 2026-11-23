package dev.shinsou.kmp.ui.reader

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NovelPaginationTest {
    @Test
    fun aChapterIsASectionAndBlankTextBeforeAHeadingIsNotOne() {
        val text = "\n\n第一章\n甲乙\n第二章\n丙丁\n"
        val first = text.indexOf("第一章")
        val second = text.indexOf("第二章")

        assertContentEquals(intArrayOf(0, second), novelSectionStarts(text, listOf(first, second)))
    }

    @Test
    fun aLongChapterIsCutAtALineStartAndNeverInsideAnImageMarker() {
        val line = "abcdefghi\n"
        val text = line.repeat(30)
        val starts = novelSectionStarts(text, emptyList(), maxSectionChars = 95)

        assertEquals(0, starts.first())
        assertTrue(starts.size > 1)
        starts.drop(1).forEach { start -> assertEquals('\n', text[start - 1]) }
        starts.toList().zipWithNext().forEach { (from, to) -> assertTrue(to - from <= 95) }

        val image = "![alt](https://example.com/a.png)"
        val marked = "x".repeat(40) + image + "y".repeat(40)
        val cut = novelSectionStarts(marked, emptyList(), maxSectionChars = 50)
        val imageStart = marked.indexOf(image)
        cut.forEach { start -> assertFalse(start in imageStart + 1 until imageStart + image.length) }
    }

    @Test
    fun noSectionStartsBetweenTheHalvesOfASurrogatePair() {
        val text = "😀".repeat(60)
        novelSectionStarts(text, emptyList(), maxSectionChars = 25).forEach { start ->
            assertFalse(start in 1 until text.length && text[start].isLowSurrogate())
        }
    }

    @Test
    fun markupAroundACutIsFound() {
        val text = "ab<span class=\"x\">cd</span>"
        assertEquals(2, novelMarkupStartAround(text, 5, novelImageSpans(text)))
        assertNull(novelMarkupStartAround(text, 1, novelImageSpans(text)))
    }

    @Test
    fun anImageMarkerLongerThanTheWindowAroundACutIsNeverCut() {
        val image = "![alt](https://example.com/" + "a".repeat(2_000) + ".png)"
        val text = "x".repeat(23_000) + image + "y".repeat(5_000)
        val imageStart = text.indexOf(image)
        val starts = novelSectionStarts(text, emptyList())

        assertTrue(starts.size > 1)
        starts.forEach { start -> assertFalse(start in imageStart + 1 until imageStart + image.length, "cut at $start") }
        val spans = novelImageSpans(text)
        assertEquals(imageStart, spans.startAround(imageStart + 1_500))
        assertNull(spans.startAround(imageStart))
        assertNull(spans.startAround(imageStart + image.length))
    }

    @Test
    fun aHeadingLikeLineInsideAMultilineImageMarkerIsNoChapter() {
        val image = "![\n第二章 插圖\n](https://example.com/a.png)"
        val text = "第一章\n" + "x".repeat(100) + "\n" + image + "\n" + "y".repeat(100) + "\n第三章\nz"
        val inside = text.indexOf("第二章")
        val third = text.indexOf("第三章")

        assertContentEquals(intArrayOf(0, third), novelSectionStarts(text, listOf(0, inside, third)))
    }

    @Test
    fun aTextWithoutLineBreaksIsCutInLinearTime() {
        val text = "x".repeat(4_000_000)
        val starts = novelSectionStarts(text, emptyList())
        assertEquals(167, starts.size)
        starts.toList().zipWithNext().forEach { (from, to) -> assertEquals(24_000, to - from) }
        assertEquals(-1, "abc\ndef".lastIndexOfAtOrAfter('\n', 7, lowest = 4))
        assertEquals(3, "abc\ndef".lastIndexOfAtOrAfter('\n', 7, lowest = 3))
    }

    @Test
    fun anImageMarkerLongerThanASectionStaysInOneSection() {
        val image = "![alt](https://example.com/" + "a".repeat(30_000) + ".png)"
        val text = "x".repeat(10_000) + "\n" + image + "\n" + "y".repeat(10_000)
        val imageStart = text.indexOf(image)
        val starts = novelSectionStarts(text, emptyList())

        starts.forEach { start -> assertFalse(start in imageStart + 1 until imageStart + image.length, "cut at $start") }
        assertTrue(imageStart in starts && imageStart + image.length in starts, "${starts.toList()}")
    }

    @Test
    fun aSavedPageIsPlacedInProportionInTheText() {
        assertEquals(500_000, novelOffsetForSavedPage(1_000_000, pageIndex = 50, pageCount = 100))
        assertEquals(1_000_000, novelOffsetForSavedPage(1_000_000, pageIndex = 120, pageCount = 100))
        assertEquals(6_000, novelOffsetForSavedPage(1_000_000, pageIndex = 10, pageCount = null))
        assertEquals(100, novelOffsetForSavedPage(100, pageIndex = 10, pageCount = null))
    }

    @Test
    fun aLongTextWithOnlyASavedPageResumesNearIt() {
        val text = "x".repeat(200_000)
        val start = locatorAt(text, 0)
        assertEquals(100_000, novelResumeOffset(text, start, locatorSaved = false, pageIndex = 5, pageCount = 10))
        // A locator that says where the reader was wins over the page.
        assertEquals(1_234, novelResumeOffset(text, locatorAt(text, 1_234), locatorSaved = true, pageIndex = 5, pageCount = 10))
        assertEquals(0, novelResumeOffset(text, start, locatorSaved = false, pageIndex = null, pageCount = null))
        // A saved locator at the very start (a sync moved the reader back there) wins over a stale page.
        assertEquals(0, novelResumeOffset(text, start, locatorSaved = true, pageIndex = 5, pageCount = 10))
        // A short text keeps restoring its saved page as before, through the page itself.
        assertEquals(0, novelResumeOffset("short", locatorAt("short", 0), locatorSaved = false, pageIndex = 3, pageCount = 4))
    }

    private fun locatorAt(text: String, offset: Int): dev.shinsou.kmp.domain.model.ReadingLocator {
        val publication = dev.shinsou.kmp.domain.model.PublicationKey("88888888-8888-4888-8888-888888888888")
        val scope = dev.shinsou.kmp.domain.model.ReadingScope(
            schemaVersion = dev.shinsou.kmp.domain.model.ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = publication,
            acquisitionId = "99999999-9999-4999-8999-999999999999",
            unitId = dev.shinsou.kmp.domain.model.UnitKey(publication, "dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            contentRevision = 0,
        )
        return dev.shinsou.kmp.domain.model.ReadingLocator.Text(
            schemaVersion = scope.schemaVersion,
            scope = scope,
            resourceId = "text-body",
            blockId = "block",
            offset = offset.coerceAtMost(text.length),
        )
    }

    @Test
    fun aFewFullCjkPagesSayWhatAPageHolds() {
        // A 2,500-character chapter of some 220 CJK characters a page: too little text for the
        // old threshold, which took a page as 600 characters and so a third of the real count.
        assertEquals(220.0, novelEstimatedCharsPerPage(2_500, 12, 4_000_000, fullChars = 2_420, fullPages = 11))
        // A title page alone is no full page: the floor still holds.
        assertEquals(600.0, novelEstimatedCharsPerPage(12, 1, 4_000_000, fullChars = 0, fullPages = 0))
    }

    @Test
    fun aReaderOpenedAgainShowsThePageCountItEndedWith() {
        val text = "x".repeat(100_000)
        val starts = IntArray(20) { it * 5_000 }
        val remembered = IntArray(starts.size)
        val first = NovelSectionedPagination(text, starts, FixedPages(220), focusOffsetUtf16 = 50_000, rememberedCounts = remembered)
        while (!first.settled) {
            first.layOutMore { true }
            first.publishIfUseful()
        }
        val real = first.pages.size

        val again = NovelSectionedPagination(text, starts, FixedPages(220), focusOffsetUtf16 = 50_000, rememberedCounts = remembered)
        assertEquals(real, again.pages.size, "no estimate in between")
        assertTrue(again.pages.any { it is NovelVisualPage.Pending })
        // Under another layout nothing is remembered: an estimate, and it holds for the focus section.
        val other = NovelSectionedPagination(text, starts, FixedPages(300), focusOffsetUtf16 = 50_000, rememberedCounts = IntArray(starts.size))
        assertNotEquals(real, other.pages.size)
    }

    @Test
    fun thePageCountComesNearTheRealOneWithoutTheReaderBeingRecreated() {
        val text = "x".repeat(100_000)
        val starts = IntArray(20) { it * 5_000 }
        // The section read first is denser than the rest: the first estimate is off.
        val layout = FixedPages(200, lastFrom = 0, lastPageChars = 500)
        val pagination = NovelSectionedPagination(text, starts, layout, focusOffsetUtf16 = 0)
        val real = 10 + 19 * 25
        val first = pagination.numbering.count
        assertEquals(pagination.pages.size, first)
        assertTrue(kotlin.math.abs(first - real) > real / 10, "first estimate $first")
        pagination.shown(3)

        val generation = pagination.generation
        val numbering = pagination.numbering
        var steps = 0
        while (pagination.numbering === numbering && !pagination.settled && steps++ < 1_000) {
            pagination.layOutMore { false }
            pagination.publishIfUseful()
        }
        // Only the numbering is renewed: the list, and so the reader on it, stay.
        assertEquals(generation, pagination.generation)
        val renewed = pagination.numbering.count
        assertTrue(kotlin.math.abs(renewed - real) < kotlin.math.abs(first - real), "$first -> $renewed, real $real")
        // The page being read keeps its number: it is in the section measured first.
        assertEquals(3, pagination.numbering.shown(3))
        assertEquals(3, pagination.numbering.list(3))
    }

    @Test
    fun aNumberingMapsPagesWithinTheirSectionInProportion() {
        // Section 0 listed as 2 pages, now 2; section 1 listed as 2 placeholders, now taken as 6.
        val numbering = NovelPageNumbering(intArrayOf(2, 2), intArrayOf(2, 6))
        assertEquals(8, numbering.count)
        assertEquals(listOf(0, 1, 2, 7), (0..3).map(numbering::shown))
        assertEquals(listOf(0, 1, 2, 2, 2, 2, 2, 3), (0..7).map(numbering::list))
        // Equal counts: the list's own numbering.
        val same = NovelPageNumbering(intArrayOf(3, 0, 4), intArrayOf(3, 0, 4))
        assertEquals((0..6).toList(), (0..6).map(same::shown))
    }

    @Test
    fun aNumberingKeepsTheFirstAndLastPageOfEverySectionAndOfTheDocument() {
        // The estimate shrank: the slider at its end (14 of 15) is the list's last page (19 of 20).
        val shrunk = NovelPageNumbering(intArrayOf(10, 10), intArrayOf(10, 5))
        assertEquals(15, shrunk.count)
        assertEquals(19, shrunk.list(14))
        assertEquals(14, shrunk.shown(19))
        // The estimate grew: the list's last page is shown as the last, 30 of 30.
        val grown = NovelPageNumbering(intArrayOf(10, 10), intArrayOf(10, 20))
        assertEquals(29, grown.shown(19))
        assertEquals(19, grown.list(29))
        // Every section's first and last page, both ways.
        val mixed = NovelPageNumbering(intArrayOf(10, 1, 7, 3), intArrayOf(5, 1, 12, 3))
        listOf(0 to 0, 9 to 4, 10 to 5, 11 to 6, 17 to 17, 18 to 18, 20 to 20).forEach { (list, shown) ->
            assertEquals(shown, mixed.shown(list), "list $list")
            assertEquals(list, mixed.list(shown), "shown $shown")
        }
        assertEquals(0, novelScaledOrdinal(0, 1, 5))
        assertEquals(4, novelScaledOrdinal(0, 1, 5, backward = true))
        assertEquals(0, novelScaledOrdinal(0, 1, 1, backward = true))
        assertEquals(4, novelScaledOrdinal(9, 10, 5))
        assertEquals(0, novelScaledOrdinal(3, 4, 1))
    }

    @Test
    fun aTurnBackAcrossAnUnmeasuredSectionReachesItsLastPage() {
        val text = "x".repeat(100_000)
        val starts = IntArray(20) { it * 5_000 }
        val pagination = NovelSectionedPagination(text, starts, FixedPages(200, lastFrom = 0, lastPageChars = 500), focusOffsetUtf16 = 0)
        val numbering = pagination.numbering
        var steps = 0
        while (pagination.numbering === numbering && steps++ < 1_000) {
            pagination.layOutMore { false }
            pagination.publishIfUseful()
        }
        assertTrue(pagination.numbering !== numbering, "renewed")
        // Two sections still placeholders, as listed: the turn back from the later one's first page.
        val pages = pagination.pages
        val later = pages.indexOfFirst { it is NovelVisualPage.Pending && it.sectionIndex == 19 }
        assertTrue(pages.any { it is NovelVisualPage.Pending && it.sectionIndex == 18 })
        val back = pagination.numbering.list(pagination.numbering.shown(later) - 1)
        val pending = assertIs<NovelVisualPage.Pending>(pages[back])
        assertEquals(18, pending.sectionIndex)
        assertEquals(pending.estimatedCount - 1, pending.ordinal, "the section's end, not its middle")
        assertEquals(text.length - 5_000 - 200, pagination.arrive(pending, fromOffsetUtf16 = starts[19]))
    }

    @Test
    fun aTurnBackIntoASectionShownAsOnePageEntersItAtItsEnd() {
        // Section 1 was listed as 5 placeholders; its estimate has shrunk to one page shown.
        val numbering = NovelPageNumbering(intArrayOf(3, 5, 4), intArrayOf(3, 1, 4))
        val firstOfNext = numbering.shown(8)
        assertEquals(4, firstOfNext)
        // Back from section 2: the last placeholder of section 1, whose arrival shows its end.
        assertEquals(7, numbering.list(firstOfNext - 1, backward = true))
        // Forward from section 0: its first.
        assertEquals(3, numbering.list(3, backward = false))
    }

    @Test
    fun pagesCutShortByAnImageDoNotSayWhatAPageHolds() {
        val text = "x".repeat(200_000)
        val starts = IntArray(10) { it * 20_000 }
        // An opening section of illustrations: 30 characters of text before each image.
        val illustrated = NovelSectionLayout { sectionText, sourceOffset ->
            object : NovelPageSteps {
                override val pages = ArrayList<NovelVisualPage>()
                override fun step(): Boolean {
                    var at = sourceOffset
                    repeat(6) {
                        pages += NovelVisualPage.Text("t", at, at + 30)
                        pages += NovelVisualPage.Image("https://example.com/$it.png", "", at + 30, at + 40)
                        at += 40
                    }
                    pages += NovelVisualPage.Text("t", at, sourceOffset + sectionText.length)
                    return false
                }
            }
        }
        val pagination = NovelSectionedPagination(text, starts, illustrated, focusOffsetUtf16 = 0)
        // No full text page was measured: the floor of 600 a page holds, not 30.
        assertTrue(pagination.pages.size < 13 + 9 * (20_000 / 600 + 1) + 1, "${pagination.pages.size} pages")
    }

    @Test
    fun noCutFallsInsideACharacterReference() {
        val text = "ab&amp;cd &#38; e & f &;"
        assertEquals(2, novelEntityStartAround(text, 4))
        assertEquals(2, novelEntityStartAround(text, 3))
        assertNull(novelEntityStartAround(text, 2), "before it")
        assertNull(novelEntityStartAround(text, 7), "after it")
        assertEquals(10, novelEntityStartAround(text, 12))
        assertNull(novelEntityStartAround(text, 18), "a lone ampersand")
        assertNull(novelEntityStartAround(text, 23), "no name")
        // A section cut that would fall inside one is moved before it.
        val long = "x".repeat(24_000 - 3) + "&amp;" + "y".repeat(10_000)
        val starts = novelSectionStarts(long, emptyList())
        assertEquals(24_000 - 3, starts[1])
    }

    @Test
    fun aSliderPickInASectionMeasuredSinceItWasListedLandsInProportion() {
        val text = "x".repeat(100_000)
        val starts = intArrayOf(0, 50_000)
        // Section 1 was listed as 2 placeholders (500 characters a page), but holds 5 pages of 10,000.
        val pagination = NovelSectionedPagination(text, starts, FixedPages(10_000, lastFrom = 0, lastPageChars = 25_000), focusOffsetUtf16 = 0)
        val pending = pagination.pages.filterIsInstance<NovelVisualPage.Pending>().first()
        assertEquals(2, pending.estimatedCount)
        // Asked for page 4 of 5 of the section as now numbered (its fourth page).
        assertEquals(80_000, pagination.arrive(pending, fromOffsetUtf16 = 0, within = NovelSectionPosition(3, 5)))
    }

    @Test
    fun aBackwardPickInsideASectionListedAsOnePlaceholderLandsOnThePagePicked() {
        val text = "x".repeat(125_000)
        val starts = intArrayOf(0, 50_000, 75_000)
        // Section 1 listed as one placeholder (25,000 characters a page), measured as 5 pages.
        val layout = FixedPages(5_000, lastFrom = 0, lastPageChars = 25_000)
        val pagination = NovelSectionedPagination(text, starts, layout, focusOffsetUtf16 = 0)
        val pending = pagination.pages.filterIsInstance<NovelVisualPage.Pending>().first { it.sectionIndex == 1 }
        assertEquals(1, pending.estimatedCount)
        // From section 2, the slider picks page 3 of the section's 5 now shown: page 3, not its last.
        assertEquals(60_000, pagination.arrive(pending, fromOffsetUtf16 = 100_000, within = NovelSectionPosition(2, 5)))
        // Turned back onto it while it is still shown as one page, it is entered at its end.
        val again = NovelSectionedPagination(text, starts, layout, focusOffsetUtf16 = 0)
        val onePage = again.pages.filterIsInstance<NovelVisualPage.Pending>().first { it.sectionIndex == 1 }
        assertEquals(70_000, again.arrive(onePage, fromOffsetUtf16 = 100_000, within = NovelSectionPosition(0, 1)))
    }

    @Test
    fun aShortFirstSectionDoesNotMakeALongBookMillionsOfPlaceholders() {
        // A title page of 12 characters, measured as one page, in a book of a million.
        assertEquals(600.0, novelEstimatedCharsPerPage(12, 1, 1_000_000))
        val text = "x".repeat(1_000_000)
        val starts = (listOf(0, 12) + (1 until 42).map { it * 24_000 }).toIntArray()
        val pagination = NovelSectionedPagination(text, starts, FixedPages(12), focusOffsetUtf16 = 0)
        assertTrue(pagination.pages.size < 2_000, "${pagination.pages.size} pages")

        // Enough measured: what a page held is trusted, though never past the cap.
        assertEquals(300.0, novelEstimatedCharsPerPage(15_000, 50, 1_000_000))
        assertEquals(10_000_000.0 / 60_000, novelEstimatedCharsPerPage(15_000, 1_500, 10_000_000))
        // A short text trusts a quarter of itself.
        assertEquals(10.0, novelEstimatedCharsPerPage(30, 3, 100))
    }

    @Test
    fun sectionAndPageIndexesAreTheLastAtOrBeforeAnOffset() {
        val starts = intArrayOf(0, 10, 20)
        assertEquals(0, novelSectionIndexFor(starts, 0))
        assertEquals(0, novelSectionIndexFor(starts, 9))
        assertEquals(1, novelSectionIndexFor(starts, 10))
        assertEquals(2, novelSectionIndexFor(starts, 500))

        val pages = listOf(text(0, 5), text(5, 9), text(9, 20))
        assertEquals(0, novelPageIndexForOffset(pages, -1))
        assertEquals(1, novelPageIndexForOffset(pages, 7))
        assertEquals(2, novelPageIndexForOffset(pages, 20))
        assertEquals(pages.indexOfLast { it.sourceStartUtf16 <= 8 }, novelPageIndexForOffset(pages, 8))
    }

    @Test
    fun aJumpPastAPlaceholderIsFound() {
        val pages = listOf(text(0, 5), NovelVisualPage.Pending(1, 0, 1, 5, 9), text(9, 20), text(20, 30))
        assertTrue(novelPendingBetween(pages, 0, 2))
        assertTrue(novelPendingBetween(pages, 3, 0), "backward too")
        assertFalse(novelPendingBetween(pages, 2, 3))
        assertFalse(novelPendingBetween(pages, 3, 99), "a target out of range is clamped")
    }

    @Test
    fun anEstimateIsNeverNoPagesNorMorePagesThanCharacters() {
        assertEquals(1, estimatedNovelPageCount(0, 600.0))
        assertEquals(1, estimatedNovelPageCount(600, 600.0))
        assertEquals(2, estimatedNovelPageCount(601, 600.0))
        assertEquals(3, estimatedNovelPageCount(3, 0.0))
    }

    @Test
    fun assembledPagesCoverTheWholeTextWithPlaceholdersForUnmeasuredSections() {
        val text = "a".repeat(100)
        val starts = intArrayOf(0, 40)
        val measured = arrayOf<List<NovelVisualPage>?>(listOf(text(0, 20), text(20, 40)), null)
        val pages = assembleNovelPages(text, starts, measured, charsPerPage = 20.0)

        assertEquals(5, pages.size)
        val pending = pages.drop(2).map { assertIs<NovelVisualPage.Pending>(it) }
        assertEquals(listOf(0, 1, 2), pending.map { it.ordinal })
        assertTrue(pending.all { it.sectionIndex == 1 && it.estimatedCount == 3 })
        assertEquals(40, pending.first().sourceStartUtf16)
        assertEquals(100, pending.last().sourceEndUtf16)
        pages.zipWithNext().forEach { (a, b) -> assertEquals(a.sourceEndUtf16, b.sourceStartUtf16) }
    }

    @Test
    fun aTextWithNothingToShowStillHasAPage() {
        val pages = assembleNovelPages("", intArrayOf(0), arrayOf(emptyList()), charsPerPage = 10.0)
        assertEquals(listOf<NovelVisualPage>(NovelVisualPage.Text(NOVEL_EMPTY_TEXT_PAGE, 0, 0)), pages)
    }

    @Test
    fun theSectionBeingReadIsMeasuredFirstAndTheRestMeasureToTheSamePages() {
        val text = "x".repeat(100)
        val starts = intArrayOf(0, 30, 60)
        val pagination = NovelSectionedPagination(text, starts, FixedPages(10), focusOffsetUtf16 = 65)

        assertTrue(pagination.pages.filter { it.sourceStartUtf16 >= 60 }.all { it is NovelVisualPage.Text })
        assertTrue(pagination.pages.any { it is NovelVisualPage.Pending })
        assertFalse(pagination.settled)

        while (!pagination.settled) {
            pagination.layOutMore { false }
            pagination.publishIfUseful()
        }
        val whole = NovelSectionedPagination(text, intArrayOf(0, 30, 60), FixedPages(10), focusOffsetUtf16 = 0)
        while (!whole.settled) {
            whole.layOutMore { true }
            whole.publishIfUseful()
        }
        assertEquals(whole.pages, pagination.pages)
        assertTrue(pagination.pages.none { it is NovelVisualPage.Pending })
        assertEquals(10, pagination.pages.size)
    }

    @Test
    fun theSectionAheadIsMeasuredBeforeTheOneBehind() {
        val text = "x".repeat(90)
        val layout = FixedPages(10)
        val pagination = NovelSectionedPagination(text, intArrayOf(0, 30, 60), layout, focusOffsetUtf16 = 40)
        pagination.layOutMore { false }
        assertEquals(listOf(30, 60), layout.begun)
    }

    @Test
    fun aPlaceholderIsRevealedAtTheEdgeItIsEnteredFrom() {
        val text = "x".repeat(100)
        val starts = intArrayOf(0, 50)

        val forward = NovelSectionedPagination(text, starts, FixedPages(10), focusOffsetUtf16 = 0)
        val first = forward.pages.filterIsInstance<NovelVisualPage.Pending>().first()
        assertEquals(50, forward.reveal(first, backward = false))
        assertTrue(forward.settled)

        val backward = NovelSectionedPagination(text, starts, FixedPages(10), focusOffsetUtf16 = 99)
        val last = backward.pages.filterIsInstance<NovelVisualPage.Pending>().last()
        assertEquals(40, backward.reveal(last, backward = true))

        // A jump to the end of the book lands on its last page.
        val jump = NovelSectionedPagination(text, starts, FixedPages(5), focusOffsetUtf16 = 0)
        val end = jump.pages.last()
        assertIs<NovelVisualPage.Pending>(end)
        assertEquals(95, jump.reveal(end, backward = false))
    }

    @Test
    fun scrollingOntoAPlaceholderArrivesWhereTheReaderCameFrom() {
        val text = "x".repeat(150)
        val starts = intArrayOf(0, 50, 100)

        // Scrolled up from the section being read: the last page of the section above.
        val up = NovelSectionedPagination(text, starts, FixedPages(10), focusOffsetUtf16 = 120)
        val above = up.pages.filterIsInstance<NovelVisualPage.Pending>().last { it.sectionIndex == 1 }
        assertEquals(90, up.arrive(above, fromOffsetUtf16 = 100))

        // A fling far ahead onto a placeholder in its middle: in proportion, never back before it.
        val fling = NovelSectionedPagination(text, starts, FixedPages(10), focusOffsetUtf16 = 0)
        val ahead = fling.pages.filterIsInstance<NovelVisualPage.Pending>().first { it.sectionIndex == 2 }
        val landed = fling.arrive(ahead, fromOffsetUtf16 = 10)
        assertTrue(landed >= 100, "landed at $landed")

        // Scrolled up across a section with nothing to show: the last page before it.
        val empty = NovelSectionedPagination(text, starts, FixedPages(10, emptyFrom = setOf(50)), focusOffsetUtf16 = 120)
        val blank = empty.pages.filterIsInstance<NovelVisualPage.Pending>().last { it.sectionIndex == 1 }
        assertEquals(40, empty.arrive(blank, fromOffsetUtf16 = 100))
    }

    @Test
    fun aJumpToTheEndLandsOnTheLastPageOfASectionEstimatedAsOne() {
        // The last section is estimated as one page (the first held 50 characters), but has five.
        val text = "x".repeat(100)
        val pagination = NovelSectionedPagination(text, intArrayOf(0, 50), FixedPages(50, lastFrom = 50, lastPageChars = 10), focusOffsetUtf16 = 0)
        val end = assertIs<NovelVisualPage.Pending>(pagination.pages.last())
        assertEquals(1, end.estimatedCount)

        assertEquals(90, pagination.arrive(end, fromOffsetUtf16 = 0, toEnd = true))
    }

    @Test
    fun pagingIntoASectionEstimatedAsOnePageShowsItsFirstPage() {
        val text = "x".repeat(100)
        val pagination = NovelSectionedPagination(text, intArrayOf(0, 50), FixedPages(50, lastFrom = 50, lastPageChars = 10), focusOffsetUtf16 = 0)
        assertEquals(50, pagination.arrive(pagination.pages.last() as NovelVisualPage.Pending, fromOffsetUtf16 = 0))
    }

    @Test
    fun revealingASectionWithNothingToShowPassesOnInTheDirectionOfTravel() {
        val text = "x".repeat(90)
        val starts = intArrayOf(0, 30, 60)
        val layout = FixedPages(10, emptyFrom = setOf(30))
        val pagination = NovelSectionedPagination(text, starts, layout, focusOffsetUtf16 = 0)
        val pending = pagination.pages.filterIsInstance<NovelVisualPage.Pending>().first { it.sectionIndex == 1 }

        assertEquals(60, pagination.reveal(pending, backward = false))
    }

    @Test
    fun aPlaceholderNearThePageBeingReadIsPublishedOnceMeasured() {
        val text = "x".repeat(1_000)
        val pagination = NovelSectionedPagination(text, intArrayOf(0, 50, 100), FixedPages(10), focusOffsetUtf16 = 0)
        val generation = pagination.generation
        pagination.shown(4)
        // The five pages of section 1, and nothing of section 2.
        var steps = 0
        pagination.layOutMore { ++steps < 5 }
        pagination.publishIfUseful()

        assertTrue(pagination.generation > generation)
        assertTrue(pagination.pages[5] is NovelVisualPage.Text)
        assertFalse(pagination.settled)
    }

    @Test
    fun aPlaceholderFarFromThePageBeingReadWaitsUntilEverythingIsMeasured() {
        val text = "x".repeat(300)
        val starts = intArrayOf(0, 100, 200)
        val pagination = NovelSectionedPagination(text, starts, FixedPages(10), focusOffsetUtf16 = 0)
        val generation = pagination.generation
        pagination.shown(0)
        // Measures section 1, which is no nearer page 0 than page 10.
        var steps = 0
        pagination.layOutMore { ++steps < 10 }
        pagination.publishIfUseful()
        assertEquals(generation, pagination.generation)
    }

    private fun text(start: Int, end: Int) = NovelVisualPage.Text("p", start, end)

    /** Pages of [pageChars] characters each, one per step; a section starting in [emptyFrom] has none. */
    private class FixedPages(
        private val pageChars: Int,
        private val emptyFrom: Set<Int> = emptySet(),
        private val lastFrom: Int = -1,
        private val lastPageChars: Int = pageChars,
    ) : NovelSectionLayout {
        val begun = mutableListOf<Int>()

        override fun begin(sectionText: String, sourceOffset: Int): NovelPageSteps {
            begun += sourceOffset
            return object : NovelPageSteps {
                override val pages = ArrayList<NovelVisualPage>()
                private var cursor = 0

                override fun step(): Boolean {
                    if (sourceOffset in emptyFrom) return false
                    if (cursor < sectionText.length) {
                        val chars = if (sourceOffset == lastFrom) lastPageChars else pageChars
                        val end = minOf(cursor + chars, sectionText.length)
                        pages += NovelVisualPage.Text(sectionText.substring(cursor, end), sourceOffset + cursor, sourceOffset + end)
                        cursor = end
                    }
                    return cursor < sectionText.length
                }
            }
        }
    }
}
