package dev.shinsou.kmp.navigation

import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.ui.screens.MoreDestination

/**
 * R4-3 (docs/architecture/0006): what the app shell shows. The shell's back stack is one main
 * [MainSection] with at most one page pushed above it; [NavigationState.destination] names that
 * page (or [Section] when none is).
 */
internal sealed interface Destination {
    /** Nothing pushed: the main section itself. */
    data object Section : Destination

    /** A beta-era extension favorite whose source identity is being searched for. */
    data class Recovering(val mangaId: Long) : Destination

    /** An extension publication opened from the local library. */
    data class LocalExtension(val item: BrowseManga) : Destination

    /** A source title that is still being resolved into a library row. */
    data class Pending(val item: BrowseManga) : Destination

    /** A title's detail page. */
    data class Manga(val mangaId: Long) : Destination

    /** A page of the More section. */
    data class More(val destination: MoreDestination) : Destination
}

/**
 * Everything the shell needs to know to draw its pages and route Back. Request counters are
 * one-shot events for the page that owns the nested Back (a change asks it to go back once).
 */
internal data class NavigationState(
    val section: MainSection,
    val selectedMangaId: Long? = null,
    val moreDestination: MoreDestination? = null,
    /** A source title shown at once while it is resolved; see [AppNavigator.openBrowseManga]. */
    val pendingBrowseManga: BrowseManga? = null,
    /** Bumped whenever the pending title changes or is cancelled, so a stale resolution is dropped. */
    val pendingBrowseRequest: Long = 0L,
    /** A legacy detail opened from a Browse catalogue, closed again with that catalogue. */
    val browseOwnedLegacyMangaId: Long? = null,
    val localLibraryPublication: BrowseManga? = null,
    val localLibraryReaderBackRequest: Long = 0L,
    val recoveringLocalLibraryMangaId: Long? = null,
    /** Bumped when a recovery starts or is abandoned, so a cancelled one cannot navigate later. */
    val localLibraryRecoveryRequest: Long = 0L,
    /** An extension reader (Browse or local library) covers the whole window. */
    val browseReaderOpen: Boolean = false,
    val detailBackRequest: Long = 0L,
    val detailNestedBackAvailable: Boolean = false,
    val moreBackRequest: Long = 0L,
    val moreNestedBackAvailable: Boolean = false,
    val browseBackRequest: Long = 0L,
    val browseBackAvailable: Boolean = false,
) {
    /** The page pushed above the section, in the order the phone layout stacks them. */
    val destination: Destination
        get() = recoveringLocalLibraryMangaId?.let(Destination::Recovering)
            ?: localLibraryPublication?.let(Destination::LocalExtension)
            ?: pendingBrowseManga?.let(Destination::Pending)
            ?: selectedMangaId?.let(Destination::Manga)
            ?: moreDestination?.let(Destination::More)
            ?: Destination.Section
}
