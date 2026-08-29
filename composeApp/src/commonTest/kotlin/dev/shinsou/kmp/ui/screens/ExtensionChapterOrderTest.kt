package dev.shinsou.kmp.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class ExtensionChapterOrderTest {
    @Test
    fun preservesWebsiteOrderWithoutAlphabeticallyInterpretingChapterTitles() {
        val websiteOrder = listOf(
            "特典",
            "第1.1話",
            "第1.2話",
            "第1.3話",
            "第1.4話",
            "第1.5話",
            "第10.1話",
            "第2.1話",
        )

        assertEquals(websiteOrder, websiteOrderedItems(websiteOrder, reverseWebsiteOrder = false))
    }

    @Test
    fun reverseActionOnlyReversesTheCompleteWebsiteSequence() {
        val websiteOrder = listOf(
            "第12話 終章・2",
            "第12話 尾聲2",
            "第11話 終章・1",
            "第11話 終章",
            "第11話 尾聲1",
            "第11話 尾聲",
            "第10話 終章",
        )

        assertEquals(websiteOrder.reversed(), websiteOrderedItems(websiteOrder, reverseWebsiteOrder = true))
    }
}
