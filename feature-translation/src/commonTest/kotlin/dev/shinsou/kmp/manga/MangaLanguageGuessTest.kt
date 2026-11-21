package dev.shinsou.kmp.manga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MangaLanguageGuessTest {
    @Test
    fun japaneseHasKanaInPlenty() {
        assertEquals(true, looksJapanese(listOf("おまえはもう死んでいる", "なにっ！？", "俺の名を言ってみろ")))
    }

    @Test
    fun chineseReadAsKanjiIsNotJapanese() {
        assertEquals(false, looksJapanese(listOf("你已經死了", "什麼！？", "說出我的名字來看看", "這是不可能的事情")))
    }

    @Test
    fun aStrayKanaInChineseIsNotEnough() {
        assertEquals(false, looksJapanese(listOf("你已經死了の", "什麼！？", "說出我的名字來看看吧", "這是不可能的事情啊")))
    }

    @Test
    fun tooLittleTextTellsNothing() {
        assertNull(looksJapanese(emptyList()))
        assertNull(looksJapanese(listOf("！？", "…", "12")))
    }
}
