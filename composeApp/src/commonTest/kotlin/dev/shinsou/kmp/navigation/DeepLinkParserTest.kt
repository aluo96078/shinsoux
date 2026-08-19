package dev.shinsou.kmp.navigation

import dev.shinsou.kmp.ui.DeepLinkSection
import dev.shinsou.kmp.ui.ShinsouDeepLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinkParserTest {
    @Test
    fun parsesPublishedRoutes() {
        assertEquals(ShinsouDeepLink.OpenManga(42), DeepLinkParser.parse("shinsou://manga/42"))
        assertEquals(
            ShinsouDeepLink.OpenChapter(mangaId = -1, chapterId = 9),
            DeepLinkParser.parse("shinsou://chapter/9"),
        )
        assertEquals(
            ShinsouDeepLink.OpenSection(DeepLinkSection.Updates),
            DeepLinkParser.parse("shinsou://updates"),
        )
    }

    @Test
    fun rejectsForeignAndMalformedLinks() {
        assertNull(DeepLinkParser.parse("https://example.com/manga/42"))
        assertNull(DeepLinkParser.parse("shinsou://manga/not-a-number"))
    }
}
