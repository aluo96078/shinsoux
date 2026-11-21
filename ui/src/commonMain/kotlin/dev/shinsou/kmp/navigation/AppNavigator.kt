package dev.shinsou.kmp.navigation

import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.ui.LibrarySelectionState
import dev.shinsou.kmp.ui.legacyLibraryFavoriteHostSection
import dev.shinsou.kmp.ui.screens.MoreDestination
import dev.shinsou.kmp.ui.selectedMangaAfterBrowseParentDismissed
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R4-3 (docs/architecture/0006): the app shell's navigation, outside composition. Every event is
 * a pure transition of [NavigationState]; the composables only draw [state] and report events.
 * Selections that a navigation step discards live in [selection].
 */
internal class AppNavigator(
    initialSection: MainSection,
    private val selection: LibrarySelectionState,
) {
    private val mutableState = MutableStateFlow(NavigationState(section = initialSection))

    val state: StateFlow<NavigationState> = mutableState.asStateFlow()

    /**
     * How many times the visible page (section and destination) changed. Unlike comparing pages,
     * it tells "never left" from "left and came back", for work started on a page that must only
     * land on that same visit.
     */
    @Volatile
    var pageChanges: Long = 0L
        private set

    /** Every write of [state] goes through here, so [pageChanges] counts each change of page. */
    private inline fun transition(next: (NavigationState) -> NavigationState) {
        while (true) {
            val current = mutableState.value
            val updated = next(current)
            if (mutableState.compareAndSet(current, updated)) {
                if (current.section != updated.section || current.destination != updated.destination) pageChanges++
                return
            }
        }
    }

    /** A main section was chosen: everything pushed above the old one closes. */
    fun selectSection(next: MainSection) {
        transition { current ->
            current.copy(
                recoveringLocalLibraryMangaId = null,
                localLibraryRecoveryRequest = current.localLibraryRecoveryRequest +
                    if (current.recoveringLocalLibraryMangaId != null) 1 else 0,
                localLibraryPublication = null,
                browseReaderOpen = false,
                browseOwnedLegacyMangaId = null,
                // A Browse title still resolving belongs to the page being left.
                pendingBrowseRequest = current.pendingBrowseRequest + 1,
                pendingBrowseManga = null,
                section = next,
                selectedMangaId = null,
                moreDestination = null,
                detailBackRequest = 0L,
                detailNestedBackAvailable = false,
                moreBackRequest = 0L,
                moreNestedBackAvailable = false,
                browseBackRequest = 0L,
                browseBackAvailable = current.browseBackAvailable && next == MainSection.BROWSE,
            )
        }
        selection.clearAll()
    }

    /** The settings page, as a deep link opens it. */
    fun openSettings() = transition {
        it.copy(
            // A local-library extension page (and its reader) would otherwise stay in front.
            localLibraryPublication = null,
            browseReaderOpen = false,
            pendingBrowseRequest = it.pendingBrowseRequest + 1,
            pendingBrowseManga = null,
            section = MainSection.MORE,
            selectedMangaId = null,
            moreBackRequest = 0L,
            moreDestination = MoreDestination.Settings,
        )
    }

    /** A title opened anew is no longer the child of a Browse catalogue. */
    fun forgetBrowseOwnedDetail() = transition { it.copy(browseOwnedLegacyMangaId = null) }

    /**
     * Opens the detail page of [mangaId]. [favorite] is the title's library state when the row is
     * known: a library title opened from elsewhere moves to the section that hosts it.
     */
    fun openMangaDetail(mangaId: Long, favorite: Boolean?) {
        transition { current ->
            val hostSection = favorite?.let { legacyLibraryFavoriteHostSection(current.section, it) }
            val moved = hostSection != null && hostSection != current.section
            current.copy(
                section = if (moved) hostSection else current.section,
                browseBackRequest = if (moved) 0L else current.browseBackRequest,
                browseBackAvailable = if (moved) false else current.browseBackAvailable,
                localLibraryPublication = null,
                browseReaderOpen = false,
                pendingBrowseRequest = current.pendingBrowseRequest + 1,
                pendingBrowseManga = null,
                selectedMangaId = mangaId,
                moreDestination = null,
                detailBackRequest = 0L,
                detailNestedBackAvailable = false,
                moreBackRequest = 0L,
                moreNestedBackAvailable = false,
            )
        }
        selection.clearChapters()
    }

    /**
     * Shows [item] at once while it is resolved. Resolving can start a plugin and go to the
     * network; waiting for it before changing the screen made a tap feel ignored. False (and
     * nothing changes) while another title is still pending.
     */
    fun openBrowseManga(item: BrowseManga): Boolean {
        if (mutableState.value.pendingBrowseManga != null) return false
        transition {
            it.copy(
                localLibraryPublication = null,
                browseReaderOpen = false,
                browseOwnedLegacyMangaId = null,
                pendingBrowseManga = item,
                pendingBrowseRequest = it.pendingBrowseRequest + 1,
                selectedMangaId = null,
                moreDestination = null,
            )
        }
        return true
    }

    /** Whether [item] under [request] is still the pending title (not cancelled or replaced). */
    fun isPendingBrowse(item: BrowseManga, request: Long): Boolean {
        val current = mutableState.value
        return current.pendingBrowseRequest == request && current.pendingBrowseManga == item
    }

    fun cancelPendingBrowse() = transition {
        it.copy(pendingBrowseRequest = it.pendingBrowseRequest + 1, pendingBrowseManga = null)
    }

    /**
     * The pending [item] resolved to [mangaId] (null: it could not be opened). Returns false, and
     * changes nothing, when the pending title was cancelled or replaced in the meantime.
     */
    fun finishPendingBrowse(item: BrowseManga, request: Long, mangaId: Long?): Boolean {
        var finished = false
        // Checked inside the transition: a tab change racing this result wins instead of being
        // overwritten by a check made against an older state.
        transition { current ->
            finished = current.pendingBrowseRequest == request && current.pendingBrowseManga == item
            if (!finished) {
                current
            } else if (mangaId == null) {
                current.copy(pendingBrowseManga = null)
            } else {
                current.copy(
                    pendingBrowseManga = null,
                    detailBackRequest = 0L,
                    detailNestedBackAvailable = false,
                    browseOwnedLegacyMangaId = mangaId,
                    selectedMangaId = mangaId,
                )
            }
        }
        if (finished && mangaId != null) selection.clearChapters()
        return finished
    }

    /**
     * Legacy source details live in the shell while their catalogue lives inside Browse. When the
     * catalogue closes, an unresolved child is invalidated first so it cannot navigate later, then
     * only the resolved child that catalogue owned is closed.
     */
    fun closeBrowseParentDetail() {
        var selectionChanged = false
        transition { current ->
            val selected = selectedMangaAfterBrowseParentDismissed(
                selectedMangaId = current.selectedMangaId,
                browseOwnedMangaId = current.browseOwnedLegacyMangaId,
            )
            selectionChanged = selected != current.selectedMangaId
            current.copy(
                pendingBrowseRequest = current.pendingBrowseRequest + 1,
                pendingBrowseManga = null,
                selectedMangaId = selected,
                detailBackRequest = if (selectionChanged) 0L else current.detailBackRequest,
                detailNestedBackAvailable = current.detailNestedBackAvailable && !selectionChanged,
                browseOwnedLegacyMangaId = null,
            )
        }
        if (selectionChanged) selection.clearChapters()
    }

    fun openMore(destination: MoreDestination) = transition {
        it.copy(
            localLibraryPublication = null,
            browseReaderOpen = false,
            moreBackRequest = 0L,
            moreNestedBackAvailable = false,
            moreDestination = destination,
            selectedMangaId = null,
        )
    }

    /** A More page moved to another page, or back to the More section ([destination] null). */
    fun setMoreDestination(destination: MoreDestination?) = transition {
        it.copy(moreDestination = destination)
    }

    fun closeDetail() {
        transition { it.copy(selectedMangaId = null) }
        selection.clearChapters()
    }

    /** [closeDetail], only while [mangaId]'s page is the one open (after an asynchronous write). */
    fun closeDetailOf(mangaId: Long) {
        if (mutableState.value.selectedMangaId == mangaId) closeDetail()
    }

    /**
     * Titles left the library: their open pages close. [openExtensionRemoved] says whether the
     * open local-library extension publication was among them.
     */
    fun closeRemovedTitles(mangaIds: Set<Long>, openExtensionRemoved: Boolean) {
        val current = mutableState.value
        if (openExtensionRemoved) {
            transition { it.copy(localLibraryPublication = null, browseReaderOpen = false) }
        }
        if (current.selectedMangaId in mangaIds) closeDetail()
    }

    /** Opens a local-library extension publication [item] in [hostSection]. */
    fun openLocalExtension(item: BrowseManga, hostSection: MainSection) = transition {
        it.copy(
            section = hostSection,
            selectedMangaId = null,
            moreDestination = null,
            localLibraryReaderBackRequest = 0L,
            localLibraryPublication = item,
            // A Browse title still resolving would otherwise open beneath this one.
            pendingBrowseRequest = it.pendingBrowseRequest + 1,
            pendingBrowseManga = null,
        )
    }

    fun closeLocalExtension() = transition {
        it.copy(browseReaderOpen = false, localLibraryPublication = null)
    }

    /** Starts recovering [mangaId]; the returned token tells a later result whether it still applies. */
    fun beginRecovery(mangaId: Long): Long {
        var request = 0L
        transition {
            request = it.localLibraryRecoveryRequest + 1
            it.copy(
                localLibraryRecoveryRequest = request,
                recoveringLocalLibraryMangaId = mangaId,
                pendingBrowseRequest = it.pendingBrowseRequest + 1,
                pendingBrowseManga = null,
            )
        }
        return request
    }

    fun isRecovering(request: Long, mangaId: Long): Boolean {
        val current = mutableState.value
        return current.localLibraryRecoveryRequest == request && current.recoveringLocalLibraryMangaId == mangaId
    }

    /** Ends the recovery [request] of [mangaId] when it is still the current one. */
    fun endRecovery(request: Long, mangaId: Long) {
        transition { current ->
            if (
                current.localLibraryRecoveryRequest == request &&
                current.recoveringLocalLibraryMangaId == mangaId
            ) {
                current.copy(recoveringLocalLibraryMangaId = null)
            } else {
                current
            }
        }
    }

    fun cancelRecovery() = transition {
        it.copy(
            localLibraryRecoveryRequest = it.localLibraryRecoveryRequest + 1,
            recoveringLocalLibraryMangaId = null,
        )
    }

    fun setBrowseReaderOpen(open: Boolean) = transition { it.copy(browseReaderOpen = open) }

    fun setBrowseBackAvailable(available: Boolean) = transition { it.copy(browseBackAvailable = available) }

    fun setDetailNestedBackAvailable(available: Boolean) =
        transition { it.copy(detailNestedBackAvailable = available) }

    fun setMoreNestedBackAvailable(available: Boolean) =
        transition { it.copy(moreNestedBackAvailable = available) }

    /**
     * A reader opened on [mangaId]: its detail page sits beneath it, with no Back pending, and no
     * Browse title still resolving replaces that page.
     */
    fun onReaderOpened(mangaId: Long) = transition {
        it.copy(
            detailBackRequest = 0L,
            detailNestedBackAvailable = false,
            selectedMangaId = mangaId,
            pendingBrowseRequest = it.pendingBrowseRequest + 1,
            pendingBrowseManga = null,
        )
    }

    /**
     * Handles one Back press below the reader and dialogs; false when there is nothing to go back
     * from (the platform then leaves the app).
     */
    fun back(): Boolean {
        val current = mutableState.value
        val next = backTransition(current) ?: return selection.clearLibraryTitles()
        transition { next }
        return true
    }

    /**
     * Whether a committed back-swipe slides the visible page itself away (so the gesture
     * finishes the pop), rather than leaving it to a page's own nested Back.
     */
    fun gestureMovesVisiblePage(): Boolean {
        val current = mutableState.value
        return when {
            current.recoveringLocalLibraryMangaId != null -> true
            current.localLibraryPublication != null && !current.browseReaderOpen -> true
            current.pendingBrowseManga != null -> true
            current.selectedMangaId != null -> !current.detailNestedBackAvailable
            // Settings supplies its own sliding child when nested; every other More page slides
            // the outer page.
            current.moreDestination != null -> true
            else -> current.section == MainSection.BROWSE && current.browseBackAvailable
        }
    }
}

/** The state after one Back press, or null when navigation itself has nothing to close. */
internal fun backTransition(state: NavigationState): NavigationState? = when {
    state.localLibraryPublication != null && state.browseReaderOpen ->
        state.copy(localLibraryReaderBackRequest = state.localLibraryReaderBackRequest + 1)
    state.localLibraryPublication != null -> state.copy(localLibraryPublication = null)
    state.recoveringLocalLibraryMangaId != null -> state.copy(
        localLibraryRecoveryRequest = state.localLibraryRecoveryRequest + 1,
        recoveringLocalLibraryMangaId = null,
    )
    state.selectedMangaId != null -> state.copy(detailBackRequest = state.detailBackRequest + 1)
    state.moreDestination != null -> state.copy(moreBackRequest = state.moreBackRequest + 1)
    state.pendingBrowseManga != null -> state.copy(
        pendingBrowseRequest = state.pendingBrowseRequest + 1,
        pendingBrowseManga = null,
    )
    state.section == MainSection.BROWSE && state.browseBackAvailable ->
        state.copy(browseBackRequest = state.browseBackRequest + 1)
    else -> null
}
