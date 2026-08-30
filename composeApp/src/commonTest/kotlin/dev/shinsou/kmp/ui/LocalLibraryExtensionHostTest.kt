package dev.shinsou.kmp.ui

import dev.shinsou.kmp.domain.model.MainSection
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalLibraryExtensionHostTest {
    @Test
    fun openingAnExtensionFavoriteDoesNotSwitchAwayFromTheLibrary() {
        assertEquals(
            MainSection.LIBRARY,
            localLibraryExtensionHostSection(MainSection.LIBRARY),
        )
    }
}
