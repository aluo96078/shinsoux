package dev.shinsou.kmp.ui

import dev.shinsou.kmp.domain.model.MainSection
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalLibraryExtensionHostTest {
    @Test
    fun openingAnExtensionFavoriteDoesNotSwitchAwayFromTheLibrary() {
        assertEquals(
            MainSection.LIBRARY,
            localLibraryExtensionHostSection(),
        )
    }

    @Test
    fun openingALegacyFavoriteCannotLeaveTheBrowseCatalogueUnderItsDetail() {
        assertEquals(
            MainSection.LIBRARY,
            legacyLibraryFavoriteHostSection(MainSection.BROWSE, favorite = true),
        )
    }

    @Test
    fun ordinaryBrowseResultsKeepTheirBrowseHost() {
        assertEquals(
            MainSection.BROWSE,
            legacyLibraryFavoriteHostSection(MainSection.BROWSE, favorite = false),
        )
    }

    @Test
    fun updatesAndHistoryKeepTheirExistingDetailHost() {
        listOf(MainSection.UPDATES, MainSection.HISTORY).forEach { current ->
            assertEquals(
                current,
                legacyLibraryFavoriteHostSection(current, favorite = true),
            )
        }
    }
}
