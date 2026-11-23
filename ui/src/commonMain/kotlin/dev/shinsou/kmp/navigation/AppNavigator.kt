package dev.shinsou.kmp.navigation

import dev.shinsou.kmp.plugin.contracts.BrowseManga
import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.ui.library.LibrarySelectionState
import dev.shinsou.kmp.ui.legacyLibraryFavoriteHostSection
import dev.shinsou.kmp.ui.settings.MoreDestination
import dev.shinsou.kmp.ui.selectedMangaAfterBrowseParentDismissed
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R4-3 (docs/architecture/0006): the app shell's navigation, outside composition. Every event is
 * a pure transition of [NavigationState]; the composables only draw [state] and report events.
 * Selections that a navigation step discards live in [selection].
 *
 * 0007 C2: a transition pushes, pops or replaces pages of [NavigationState.stack]; what a page
 * owns (its nested Back, its request token) goes with it, so closing a page resets nothing else.
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
                section = next,
                stack = current.stack.clear(),
                browseReaderOpen = false,
                browseOwnedLegacyMangaId = null,
                browseBackAvailable = current.browseBackAvailable && next == MainSection.BROWSE,
            )
        }
        selection.clearAll()
    }

    /** The settings page, as a deep link opens it. */
    fun openSettings() = transition {
        it.copy(
            section = MainSection.MORE,
            // A local-library extension page (and its reader) would otherwise stay in front, and
            // a Browse title still resolving would open over it.
            stack = it.stack.pop(PageLayer.LOCAL_EXTENSION, PageLayer.PENDING, PageLayer.DETAIL)
                .push(PageEntry.More(MoreDestination.Settings, it.moreNestedBackAvailable)),
            browseReaderOpen = false,
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
                browseBackAvailable = if (moved) false else current.browseBackAvailable,
                stack = current.stack.pop(PageLayer.LOCAL_EXTENSION, PageLayer.PENDING, PageLayer.MORE)
                    .push(PageEntry.Detail(mangaId)),
                detailOpenings = current.detailOpenings + 1,
                browseReaderOpen = false,
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
        if (mutableState.value.stack.pending != null) return false
        transition {
            val request = it.requestSerial + 1
            it.copy(
                stack = it.stack.pop(PageLayer.LOCAL_EXTENSION, PageLayer.DETAIL, PageLayer.MORE)
                    .push(PageEntry.Pending(item, request)),
                requestSerial = request,
                browseReaderOpen = false,
                browseOwnedLegacyMangaId = null,
            )
        }
        return true
    }

    /** Whether [item] under [request] is still the pending title (not cancelled or replaced). */
    fun isPendingBrowse(item: BrowseManga, request: Long): Boolean =
        mutableState.value.stack.pending == PageEntry.Pending(item, request)

    fun cancelPendingBrowse() = transition { it.copy(stack = it.stack.pop(PageLayer.PENDING)) }

    /**
     * The pending [item] resolved to [mangaId] (null: it could not be opened). Returns false, and
     * changes nothing, when the pending title was cancelled or replaced in the meantime.
     */
    fun finishPendingBrowse(item: BrowseManga, request: Long, mangaId: Long?): Boolean {
        var finished = false
        // Checked inside the transition: a tab change racing this result wins instead of being
        // overwritten by a check made against an older state.
        transition { current ->
            finished = current.stack.pending == PageEntry.Pending(item, request)
            val stack = current.stack.pop(PageLayer.PENDING)
            when {
                !finished -> current
                mangaId == null -> current.copy(stack = stack)
                else -> current.copy(
                    stack = stack.push(PageEntry.Detail(mangaId)),
                    browseOwnedLegacyMangaId = mangaId,
                    detailOpenings = current.detailOpenings + 1,
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
            val stack = current.stack.pop(PageLayer.PENDING)
            current.copy(
                stack = if (selectionChanged) stack.pop(PageLayer.DETAIL) else stack,
                browseOwnedLegacyMangaId = null,
            )
        }
        if (selectionChanged) selection.clearChapters()
    }

    fun openMore(destination: MoreDestination) = transition {
        it.copy(
            stack = it.stack.pop(PageLayer.LOCAL_EXTENSION, PageLayer.DETAIL).push(PageEntry.More(destination)),
            browseReaderOpen = false,
        )
    }

    /** A More page moved to another page, or back to the More section ([destination] null). */
    fun setMoreDestination(destination: MoreDestination?) = transition {
        it.copy(
            stack = if (destination == null) {
                it.stack.pop(PageLayer.MORE)
            } else {
                // The same page instance, showing another More page: its nested Back is kept.
                it.stack.push(PageEntry.More(destination, it.moreNestedBackAvailable))
            },
        )
    }

    fun closeDetail() {
        transition { it.copy(stack = it.stack.pop(PageLayer.DETAIL)) }
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
        if (openExtensionRemoved) closeLocalExtension()
        if (current.selectedMangaId in mangaIds) closeDetail()
    }

    /** Opens a local-library extension publication [item] in [hostSection]. */
    fun openLocalExtension(item: BrowseManga, hostSection: MainSection) = transition {
        it.copy(
            section = hostSection,
            // A Browse title still resolving would otherwise open beneath this one.
            stack = it.stack.pop(PageLayer.DETAIL, PageLayer.MORE, PageLayer.PENDING)
                .push(PageEntry.LocalExtension(item)),
        )
    }

    fun closeLocalExtension() = transition {
        it.copy(stack = it.stack.pop(PageLayer.LOCAL_EXTENSION), browseReaderOpen = false)
    }

    /** Starts recovering [mangaId]; the returned token tells a later result whether it still applies. */
    fun beginRecovery(mangaId: Long): Long {
        var request = 0L
        transition {
            request = it.requestSerial + 1
            it.copy(
                stack = it.stack.pop(PageLayer.PENDING).push(PageEntry.Recovering(mangaId, request)),
                requestSerial = request,
            )
        }
        return request
    }

    fun isRecovering(request: Long, mangaId: Long): Boolean =
        mutableState.value.stack.recovering == PageEntry.Recovering(mangaId, request)

    /** Ends the recovery [request] of [mangaId] when it is still the current one. */
    fun endRecovery(request: Long, mangaId: Long) = transition { current ->
        if (current.stack.recovering == PageEntry.Recovering(mangaId, request)) {
            current.copy(stack = current.stack.pop(PageLayer.RECOVERING))
        } else {
            current
        }
    }

    fun cancelRecovery() = transition { it.copy(stack = it.stack.pop(PageLayer.RECOVERING)) }

    fun setBrowseReaderOpen(open: Boolean) = transition { it.copy(browseReaderOpen = open) }

    /**
     * The detail beside Browse when the user started opening a chapter there
     * ([browseSectionReaderOpening]): its title and which opening ([NavigationState.detailOpenings]).
     */
    private var detailBesideOpeningReader: Pair<Long, Long>? = null

    /** The user started opening a chapter in the Browse section's own reader; it shows once loaded. */
    fun browseSectionReaderOpening() {
        val state = mutableState.value
        detailBesideOpeningReader = state.stack.detail?.let { it.mangaId to state.detailOpenings }
    }

    /**
     * The Browse section's own reader opened or closed. It fills the window, so the detail column
     * that was beside the section (a wide window with room for one) when the chapter was chosen
     * is closed as it opens, like [closeDetail]: left open, it would be the top page, drawn over
     * the reader and the first one Back closes. A title opened while the chapter loaded (a link)
     * is newer than the reader and stays in front of it.
     */
    fun setBrowseSectionReaderOpen(open: Boolean) {
        val beside = detailBesideOpeningReader
        detailBesideOpeningReader = null
        var closesDetail = false
        transition {
            // The same title opened again (a link) is a newer page and stays.
            closesDetail = open && beside != null &&
                it.stack.detail?.mangaId == beside.first && it.detailOpenings == beside.second
            it.copy(stack = if (closesDetail) it.stack.pop(PageLayer.DETAIL) else it.stack, browseReaderOpen = open)
        }
        if (closesDetail) selection.clearChapters()
    }

    fun setBrowseBackAvailable(available: Boolean) = transition { it.copy(browseBackAvailable = available) }

    /** The open detail page reports whether it has a nested Back of its own (an open sheet). */
    fun setDetailNestedBackAvailable(available: Boolean) = transition {
        it.copy(stack = it.stack.update(it.stack.detail) { detail -> detail.copy(nestedBackAvailable = available) })
    }

    /** The open More page reports whether it has a nested Back of its own. */
    fun setMoreNestedBackAvailable(available: Boolean) = transition {
        it.copy(stack = it.stack.update(it.stack.more) { more -> more.copy(nestedBackAvailable = available) })
    }

    /**
     * A reader opened on [mangaId]: its detail page (a new instance, without a nested Back) sits
     * beneath it, and no Browse title still resolving replaces that page.
     */
    fun onReaderOpened(mangaId: Long) = transition {
        it.copy(
            stack = it.stack.pop(PageLayer.PENDING).push(PageEntry.Detail(mangaId)),
            detailOpenings = it.detailOpenings + 1,
        )
    }

    /**
     * Handles one Back press below the reader and dialogs; false when there is nothing to go back
     * from (the platform then leaves the app).
     */
    fun back(): Boolean {
        // Computed from the state the transition replaces, so a concurrent change is not lost.
        var handled = false
        transition { current ->
            val next = backTransition(current)
            handled = next != null
            next ?: current
        }
        return handled || selection.clearLibraryTitles()
    }

    /** Whether a committed back-swipe slides the visible page away ([gestureMovesTopPage]). */
    fun gestureMovesVisiblePage(): Boolean = gestureMovesTopPage(mutableState.value)
}

/**
 * Whether a committed back-swipe slides the visible page itself away (so the gesture finishes the
 * pop), rather than leaving it to a page's own nested Back. It asks about the page Back would
 * act on, [PageStack.top].
 */
internal fun gestureMovesTopPage(state: NavigationState): Boolean = when (state.stack.top?.layer) {
    PageLayer.RECOVERING, PageLayer.PENDING -> true
    PageLayer.LOCAL_EXTENSION -> !state.browseReaderOpen
    PageLayer.DETAIL -> !state.detailNestedBackAvailable
    // Settings supplies its own sliding child when nested; every other More page slides the
    // outer page.
    PageLayer.MORE -> true
    null -> state.section == MainSection.BROWSE && state.browseBackAvailable
}

/**
 * The state after one Back press, or null when navigation itself has nothing to close. Back acts
 * on the page the user sees in front, [PageStack.top], so the order is [PageLayer]'s and nothing
 * else's. A page with a nested Back of its own gets the press as a [BackRequests] change and
 * decides itself; every other page is popped. With nothing pushed, Browse's own nested Back.
 */
internal fun backTransition(state: NavigationState): NavigationState? {
    val stack = state.stack
    fun handTo(target: BackTarget) = state.copy(backRequests = state.backRequests.after(target))
    val layer = stack.top?.layer
    if (layer == null) {
        return if (state.section == MainSection.BROWSE && state.browseBackAvailable) handTo(BackTarget.BROWSE) else null
    }
    val target = if (state.browseReaderOpen) layer.backTargetWhileReaderOpen else layer.backTarget
    return if (target != null) handTo(target) else state.copy(stack = stack.pop(layer))
}
