package dev.shinsou.kmp.ui

/**
 * A legacy source detail is rendered by the app shell while its source/search parent remains in
 * [BrowseScreen]. Closing that parent must remove only the detail it owns, never an unrelated
 * library, history, update, or deep-link destination.
 */
internal fun selectedMangaAfterBrowseParentDismissed(
    selectedMangaId: Long?,
    browseOwnedMangaId: Long?,
): Long? = selectedMangaId.takeUnless {
    browseOwnedMangaId != null && it == browseOwnedMangaId
}
