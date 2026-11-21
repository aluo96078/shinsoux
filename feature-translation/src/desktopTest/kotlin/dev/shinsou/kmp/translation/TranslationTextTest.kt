package dev.shinsou.kmp.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationTextTest {
    @Test
    fun punctuationAfterCjkBecomesFullWidthAndLatinTextIsLeftAlone() {
        assertEquals("你真的要去嗎？她焦急地問。", TranslationText.localizePunctuation("你真的要去嗎?她焦急地問。", "zh-Hant"))
        assertEquals("別擔心，只要我在！", TranslationText.localizePunctuation("別擔心 , 只要我在!", "zh-Hans"))
        assertEquals("見 Chapter 3, part 2: the end", TranslationText.localizePunctuation("見 Chapter 3, part 2: the end", "zh-Hant"))
        assertEquals("Hello, world!", TranslationText.localizePunctuation("Hello, world!", "en"))
    }

    @Test
    fun segmentIdentityIgnoresSurroundingWhitespaceOnly() {
        assertEquals(TranslationText.hash("一行"), TranslationText.hash("  一行\t"))
        assertFalse(TranslationText.hash("一行") == TranslationText.hash("一 行"))
        assertEquals(listOf("a", "", "b", "c"), TranslationText.segments("a\r\n\nb\rc"))
    }

    @Test
    fun glossaryCodesRoundTripAndTheLongestTermWins() {
        val glossary = TranslationGlossary(
            listOf(GlossaryEntry(1, "山田", "山田"), GlossaryEntry(2, "山田太郎", "山田太郎先生"), GlossaryEntry(12, "葵", "小葵")),
        )
        val protected = glossary.protect("山田太郎と葵と山田")
        assertEquals("N2XとN12XとN1X", protected)
        assertEquals("山田太郎先生和小葵和山田", glossary.restore("N2X和N12X和N1X"))
        // The engine pads codes with spaces next to CJK text, and keeps them inside Latin text.
        assertEquals("小葵拔出了劍，站在山田面前", glossary.restore("N12X 拔出了劍，站在 N1X 面前"))
        assertEquals("Then 小葵 left", glossary.restore("Then N12X left"))
        val latin = TranslationGlossary(listOf(GlossaryEntry(1, "葵", "Alice"), GlossaryEntry(2, "健太", "Bob")))
        assertEquals("Alice Bob", latin.restore("N1X N2X"))
        assertTrue(TranslationGlossary.codesPreserved(protected, "N2X 和 N12X 和 N1X"))
        assertFalse(TranslationGlossary.codesPreserved(protected, "N2X 和 N12X 和 N12X"))
    }

    @Test
    fun textThatAlreadyLooksLikeACodeIsNotProtected() {
        val glossary = TranslationGlossary(listOf(GlossaryEntry(1, "葵", "小葵")))
        assertEquals("葵は N7X を見た", glossary.protect("葵は N7X を見た"))
        assertEquals("unknown N7X stays", glossary.restore("unknown N7X stays"))
        assertEquals("葵", TranslationGlossary.EMPTY.protect("葵"))
    }
}
