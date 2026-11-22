package dev.shinsou.kmp.navigation

import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.ui.LibrarySelectionState
import dev.shinsou.kmp.ui.screens.MoreDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0008 (0007 §7 item 7): what is drawn in front and what Back dismisses first both come from the
 * [PageLayer] order. Each pair of layers, on a phone and in a wide window, with and without room
 * for a detail column.
 *
 * Before 0008 the shell's Back order was LOCAL_EXTENSION, RECOVERING, DETAIL, MORE, PENDING while
 * a phone drew RECOVERING, LOCAL_EXTENSION, PENDING, DETAIL, MORE in front-to-back order; wide
 * windows had a third order (pending, recovering, detail over the section; More hiding the rest).
 */
class PageLayerOrderTest {
    private val item = BrowseManga(sourceId = 7L, url = "/title", title = "Title")

    private fun entry(layer: PageLayer): PageEntry = when (layer) {
        PageLayer.MORE -> PageEntry.More(MoreDestination.About)
        PageLayer.DETAIL -> PageEntry.Detail(1L)
        PageLayer.PENDING -> PageEntry.Pending(item, 1L)
        PageLayer.LOCAL_EXTENSION -> PageEntry.LocalExtension(item)
        PageLayer.RECOVERING -> PageEntry.Recovering(2L, 2L)
    }

    private fun stackOf(vararg layers: PageLayer) = PageStack.of(*layers.map(::entry).toTypedArray())

    private fun state(vararg layers: PageLayer, readerOpen: Boolean = false) =
        NavigationState(section = MainSection.LIBRARY, stack = stackOf(*layers), browseReaderOpen = readerOpen)

    /** Every non-empty combination of layers, as the pure functions see them. */
    private val combinations: List<List<PageLayer>> = (1 until (1 shl PageLayer.entries.size)).map { mask ->
        PageLayer.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
    }

    private val pairs: List<Pair<PageLayer, PageLayer>> = PageLayer.entries.flatMapIndexed { index, low ->
        PageLayer.entries.drop(index + 1).map { high -> low to high }
    }

    /** The layer one Back press acts on, checking that no other page was touched. */
    private fun backActsOn(before: NavigationState): PageLayer {
        val after = checkNotNull(backTransition(before)) { "Back found nothing to close in ${before.stack}" }
        val layer = checkNotNull(before.stack.top).layer
        val target = if (before.browseReaderOpen) layer.backTargetWhileReaderOpen else layer.backTarget
        if (target == null) {
            assertNull(after.stack.entry(layer), "$layer closes")
            assertEquals(before.stack.pop(layer), after.stack)
            assertEquals(before.backRequests, after.backRequests)
        } else {
            assertEquals(before.stack, after.stack, "$layer keeps its page and decides itself")
            assertEquals(before.backRequests.after(target), after.backRequests)
        }
        return layer
    }

    @Test
    fun theLayerOrderIsTheStackingOrder() {
        assertEquals(
            listOf(PageLayer.MORE, PageLayer.DETAIL, PageLayer.PENDING, PageLayer.LOCAL_EXTENSION, PageLayer.RECOVERING),
            PageLayer.entries,
        )
    }

    @Test
    fun theStackKeepsThePagesInLayerOrderWhateverTheOrderTheyWerePushedIn() {
        for ((low, high) in pairs) {
            assertEquals(listOf(entry(low), entry(high)), PageStack.of(entry(high), entry(low)).pages, "$low under $high")
            assertEquals(entry(high), PageStack.of(entry(high), entry(low)).top)
        }
    }

    @Test
    fun backActsOnTheHigherLayerOfEveryPair() {
        for ((low, high) in pairs) {
            assertEquals(high, backActsOn(state(low, high)), "Back with $low under $high")
        }
    }

    @Test
    fun backActsOnTheTopLayerOfEveryCombination() {
        for (layers in combinations) {
            for (readerOpen in listOf(false, true)) {
                assertEquals(layers.max(), backActsOn(state(*layers.toTypedArray(), readerOpen = readerOpen)), "$layers")
            }
        }
    }

    @Test
    fun aPhoneShowsTheLayerBackActsOn() {
        for (layers in combinations) {
            val state = state(*layers.toTypedArray())
            assertEquals(entry(layers.max()).destination, state.destination, "$layers")
            assertEquals(state.destination, state.stack.top?.destination)
        }
    }

    @Test
    fun aWideWindowDrawsTheLayerBackActsOnInFront() {
        for (layers in combinations) {
            for (split in listOf(false, true)) {
                for (readerOpen in listOf(false, true)) {
                    val state = state(*layers.toTypedArray(), readerOpen = readerOpen)
                    val arrangement = wideArrangement(state, split)
                    val label = "$layers split=$split readerOpen=$readerOpen"
                    val top = checkNotNull(state.stack.top)
                    if (top.layer == PageLayer.MORE) {
                        // The More page is the main area itself: nothing is drawn over it.
                        assertNull(arrangement.front, label)
                    } else {
                        assertEquals(top, arrangement.front, label)
                    }
                    // One page is never drawn twice.
                    assertTrue(arrangement.cover == null || arrangement.cover != arrangement.side, label)
                }
            }
        }
    }

    @Test
    fun aWideWindowWithoutRoomShowsOnePageLikeAPhone() {
        for (layers in combinations) {
            for (readerOpen in listOf(false, true)) {
                val arrangement = wideArrangement(state(*layers.toTypedArray(), readerOpen = readerOpen), splitDetail = false)
                assertEquals(false, arrangement.sideBeside)
                assertTrue(arrangement.cover == null || arrangement.side == null, "$layers")
            }
        }
    }

    @Test
    fun aReaderCoversTheWholeWindowEvenWhereTheDetailWouldSitBeside() {
        val arrangement = wideArrangement(state(PageLayer.LOCAL_EXTENSION, readerOpen = true), splitDetail = true)
        assertEquals(false, arrangement.sideBeside)
        assertEquals(entry(PageLayer.LOCAL_EXTENSION), arrangement.side)
    }

    @Test
    fun theDetailColumnHoldsTheHigherOfThePublicationAndTheDetail() {
        val arrangement = wideArrangement(state(PageLayer.DETAIL, PageLayer.LOCAL_EXTENSION), splitDetail = true)
        assertEquals(entry(PageLayer.LOCAL_EXTENSION), arrangement.side)
        assertEquals(true, arrangement.sideBeside)
    }

    @Test
    fun aBackSwipeActsOnTheLayerBackActsOn() {
        for (layers in combinations) {
            // Every top page slides away, but a detail with a nested Back of its own (an open sheet).
            assertTrue(gestureMovesTopPage(state(*layers.toTypedArray())), "$layers")
            if (layers.max() == PageLayer.DETAIL) {
                val nested = state(*layers.toTypedArray())
                val withSheet = nested.copy(stack = nested.stack.update(nested.stack.detail) { it.copy(nestedBackAvailable = true) })
                assertFalse(gestureMovesTopPage(withSheet), "$layers with a sheet open")
            }
        }
    }

    // The combinations the shell's own transitions can reach: a recovery is never replaced by the
    // pages opened over it, and More does not close a title that is still resolving.

    private val navigator = AppNavigator(MainSection.LIBRARY, LibrarySelectionState())
    private val current get() = navigator.state.value

    @Test
    fun aRecoveryIsDismissedByBackWhateverIsUnderIt() {
        val opens = mapOf<PageLayer, () -> Unit>(
            PageLayer.DETAIL to { navigator.openMangaDetail(1L, favorite = null) },
            PageLayer.MORE to { navigator.openMore(MoreDestination.About) },
            PageLayer.PENDING to { navigator.openBrowseManga(item) },
        )
        for ((layer, open) in opens) {
            navigator.selectSection(MainSection.LIBRARY)
            open()
            navigator.beginRecovery(2L)
            // A recovery replaces the title still resolving, as it always has.
            val under = current.stack.pages.map { it.layer }.filter { it != PageLayer.RECOVERING }
            assertTrue(navigator.back())
            assertNull(current.stack.recovering, "Back cancels the recovery over $layer first")
            assertEquals(under, current.stack.pages.map { it.layer }, "$layer is left open")
        }
    }

    @Test
    fun aTitleStillResolvingIsClosedBeforeTheMorePageUnderIt() {
        navigator.openMore(MoreDestination.About)
        navigator.openBrowseManga(item)
        // openBrowseManga closes More; More opened afterwards sits under the title.
        navigator.openMore(MoreDestination.About)
        assertEquals(Destination.Pending(item), current.destination)
        assertTrue(navigator.back())
        assertNull(current.stack.pending)
        assertEquals(0L, current.backRequests[BackTarget.MORE], "the More page was not handed the press")
        assertTrue(navigator.back())
        assertEquals(1L, current.backRequests[BackTarget.MORE])
    }
}
