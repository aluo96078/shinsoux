package dev.shinsou.kmp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowseParentDetailTest {
    @Test
    fun exitingASourceClosesTheLegacyDetailOpenedByThatSource() {
        assertNull(
            selectedMangaAfterBrowseParentDismissed(
                selectedMangaId = 52L,
                browseOwnedMangaId = 52L,
            ),
        )
    }

    @Test
    fun exitingASourceDoesNotCloseAnUnrelatedDestination() {
        assertEquals(
            45L,
            selectedMangaAfterBrowseParentDismissed(
                selectedMangaId = 45L,
                browseOwnedMangaId = 52L,
            ),
        )
    }

    @Test
    fun exitingASourceWithoutALegacyChildLeavesSelectionAlone() {
        assertEquals(
            45L,
            selectedMangaAfterBrowseParentDismissed(
                selectedMangaId = 45L,
                browseOwnedMangaId = null,
            ),
        )
    }
}
