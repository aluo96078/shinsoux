package dev.shinsou.kmp.ui

import dev.shinsou.kmp.domain.model.MainSection

/** The app section that remains selected while an app-owned extension favorite is open. */
internal fun localLibraryExtensionHostSection(): MainSection = MainSection.LIBRARY

/**
 * Legacy source favorites still open through the ordinary Manga detail route. Browse titles use
 * their own preview route, so an app-owned favorite reaching that route while Browse is selected
 * must have come from library navigation (for example, a retained deep link or continue action).
 */
internal fun legacyLibraryFavoriteHostSection(
    current: MainSection,
    favorite: Boolean,
): MainSection = if (favorite && current == MainSection.BROWSE) {
    MainSection.LIBRARY
} else {
    current
}
