package dev.shinsou.kmp.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationDisplayTextTest {
    private val source = "第一行\n\n第二行はとても長い\n𠮷😀"
    private val translation = ChapterTranslation(
        segments = listOf(
            TranslatedSegment("第一行", "Line one"),
            TranslatedSegment("", null),
            TranslatedSegment("第二行はとても長い", "The second line is very long indeed"),
            TranslatedSegment("𠮷😀", null),
        ),
        reusedSegments = 0,
        translatedSegments = 2,
    )

    @Test
    fun translationModeReplacesTranslatedLinesAndKeepsTheRest() {
        val display = TranslationDisplayText.build(source, translation, TranslationDisplayMode.TRANSLATION)!!
        assertEquals("Line one\n\nThe second line is very long indeed\n𠮷😀", display.text)
        assertEquals(0, display.toSource(0))
        assertEquals(source.length, display.toSource(display.text.length))
        assertEquals(0, display.toDisplay(0))
        assertEquals(display.text.length, display.toDisplay(source.length))
        // The start of a line maps to the start of the same line, in both directions.
        val secondSource = source.indexOf("第二行")
        val secondDisplay = display.text.indexOf("The second")
        assertEquals(secondSource, display.toSource(secondDisplay))
        assertEquals(secondDisplay, display.toDisplay(secondSource))
        // Halfway through the translated line is about halfway through the original line.
        val middle = display.toSource(secondDisplay + 17)
        assertTrue(middle in secondSource + 3..secondSource + 6, "got $middle")
    }

    @Test
    fun bilingualModeShowsBothAndPositionsStayInsideTheirLine() {
        val display = TranslationDisplayText.build(source, translation, TranslationDisplayMode.BILINGUAL)!!
        assertEquals("第一行\nLine one\n\n第二行はとても長い\nThe second line is very long indeed\n𠮷😀", display.text)
        for (offset in 0..display.text.length) {
            val mapped = display.toSource(offset)
            assertTrue(mapped in 0..source.length)
            assertTrue(mapped == 0 || mapped == source.length || !source[mapped].isLowSurrogate(), "offset $offset splits a pair")
        }
        var previous = 0
        for (offset in 0..source.length) {
            val mapped = display.toDisplay(offset)
            assertTrue(mapped >= previous, "positions keep their order")
            previous = mapped
        }
    }

    @Test
    fun nothingIsShownDifferentlyWithoutATranslationOrWhenLinesDoNotLineUp() {
        assertNull(TranslationDisplayText.build(source, translation, TranslationDisplayMode.ORIGINAL))
        val none = translation.copy(segments = translation.segments.map { it.copy(translation = null) })
        assertNull(TranslationDisplayText.build(source, none, TranslationDisplayMode.TRANSLATION))
        assertNull(TranslationDisplayText.build(source + "\n多一行", translation, TranslationDisplayMode.TRANSLATION))
    }

    @Test
    fun theSourceLanguageIsGuessedFromItsScript() {
        assertEquals("ja", ReaderTranslation.detectLanguage("葵は剣を抜き、健太の前に立ちはだかった。"))
        assertEquals("ko", ReaderTranslation.detectLanguage("정말 갈 거야? 그녀가 불안한 듯 물었다."))
        assertEquals("zh-Hans", ReaderTranslation.detectLanguage("你真的要去吗？她不安地问道。"))
        assertEquals("en", ReaderTranslation.detectLanguage("The old lighthouse keeper had not spoken to anyone."))
        assertEquals("zh-Hant", ReaderTranslation.targetLanguage("zh-TW"))
        assertEquals("zh-Hant", ReaderTranslation.targetLanguage("fr-FR"))
    }
}
