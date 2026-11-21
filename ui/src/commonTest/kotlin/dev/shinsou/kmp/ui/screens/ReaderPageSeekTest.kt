package dev.shinsou.kmp.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderPageSeekTest {
    @Test
    fun sliderValueRoundsToTheNearestPageInsideTheChapter() {
        assertEquals(0, readerSliderPage(0.2f, 20))
        assertEquals(5, readerSliderPage(4.6f, 20))
        assertEquals(19, readerSliderPage(25f, 20))
        assertEquals(0, readerSliderPage(-3f, 20))
        assertNull(readerSliderPage(3f, 0))
    }

    @Test
    fun onlyAdjacentPageTurnsAnimateSoSliderJumpsLandOnTheTarget() {
        assertTrue(readerPageSeekAnimates(fromPage = 3, toPage = 4, animatePageTransitions = true))
        assertTrue(readerPageSeekAnimates(fromPage = 4, toPage = 3, animatePageTransitions = true))
        assertFalse(readerPageSeekAnimates(fromPage = 0, toPage = 12, animatePageTransitions = true))
        assertFalse(readerPageSeekAnimates(fromPage = 3, toPage = 4, animatePageTransitions = false))
    }
}

class ReadingProgressPresentationTest {
    private val strings = dev.shinsou.kmp.ui.i18n.shinsouStringsFor("zh-TW")

    @Test
    fun pageLabelIsOneBasedAndShowsTheTotalOnlyWhenMeasured() {
        assertEquals("第 5 / 18 頁", readingProgressPageLabel(strings, 4, 18))
        assertEquals("第 5 頁", readingProgressPageLabel(strings, 4, null))
        assertEquals("第 1 頁", readingProgressPageLabel(strings, -2, null))
    }

    @Test
    fun continueHintNamesTheChapterAndItsPage() {
        assertEquals(
            "上次讀到：第 3 話 · 第 5 / 18 頁",
            extensionContinueReadingDetail(
                strings,
                "第 3 話",
                dev.shinsou.kmp.plugin.v2.ExtensionUnitPageProgressV2(4, 18),
            ),
        )
        assertEquals("上次讀到：第 3 話", extensionContinueReadingDetail(strings, "第 3 話", null))
    }
}
