package dev.shinsou.kmp.navigation

import dev.shinsou.kmp.domain.model.MainSection
import dev.shinsou.kmp.plugin.BrowseManga
import dev.shinsou.kmp.ui.LibrarySelectionState
import dev.shinsou.kmp.ui.screens.MoreDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0007 C2: the page stack, its transitions, and Back handed to the page that owns it. */
class PageStackTest {
    private val navigator = AppNavigator(MainSection.LIBRARY, LibrarySelectionState())
    private val state get() = navigator.state.value
    private val item = BrowseManga(sourceId = 7L, url = "/title", title = "Title")

    @Test
    fun pagesStackByLayerAndALayerHoldsOnePage() {
        val stack = PageStack.of(
            PageEntry.LocalExtension(item),
            PageEntry.More(MoreDestination.About),
            PageEntry.Detail(3L),
        )
        assertEquals(PageEntry.LocalExtension(item), stack.top)
        assertEquals(PageStack.of(PageEntry.More(MoreDestination.About), PageEntry.Detail(3L), PageEntry.LocalExtension(item)), stack)

        val replaced = stack.push(PageEntry.Detail(4L))
        assertEquals(PageEntry.Detail(4L), replaced.detail)
        assertEquals(PageEntry.LocalExtension(item), replaced.top)

        val popped = replaced.pop(PageLayer.LOCAL_EXTENSION, PageLayer.MORE)
        assertEquals(PageEntry.Detail(4L), popped.top)
        assertNull(popped.more)
        assertTrue(popped.pop(PageLayer.DETAIL).isEmpty)
        assertTrue(stack.clear().isEmpty)
    }

    @Test
    fun aPageUpdateTouchesOnlyAPageThatIsThere() {
        val stack = PageStack.of(PageEntry.Detail(3L))
        assertEquals(stack, stack.update(stack.more) { it.copy(nestedBackAvailable = true) })
        assertEquals(PageEntry.Detail(3L, nestedBackAvailable = true), stack.update(stack.detail) { it.copy(nestedBackAvailable = true) }.top)
    }

    @Test
    fun theQueriesOfThePagesShownAreReadOffTheStack() {
        val navigation = NavigationState(
            section = MainSection.LIBRARY,
            stack = PageStack.of(PageEntry.More(MoreDestination.Settings, nestedBackAvailable = true), PageEntry.Detail(5L)),
        )
        assertEquals(Destination.Manga(5L), navigation.destination)
        assertEquals(5L, navigation.selectedMangaId)
        assertEquals(MoreDestination.Settings, navigation.moreDestination)
        assertTrue(navigation.moreNestedBackAvailable)
        assertFalse(navigation.detailNestedBackAvailable)
        assertNull(navigation.pendingBrowseManga)
        assertNull(navigation.localLibraryPublication)
        assertNull(navigation.recoveringLocalLibraryMangaId)
    }

    @Test
    fun aReaderOpenedUnderALibraryPublicationPutsItsTitleBeneathIt() {
        // Tablets show both side by side; a phone keeps the publication in front.
        navigator.openLocalExtension(item, MainSection.LIBRARY)
        navigator.onReaderOpened(6L)
        assertEquals(Destination.LocalExtension(item), state.destination)
        assertEquals(6L, state.selectedMangaId)
        assertTrue(navigator.back())
        assertEquals(Destination.Manga(6L), state.destination)
    }

    @Test
    fun aPagesNestedBackGoesAwayWithThePage() {
        navigator.openMangaDetail(2L, favorite = null)
        navigator.setDetailNestedBackAvailable(true)
        navigator.closeDetail()
        // A late report from the closed page opens nothing.
        navigator.setDetailNestedBackAvailable(true)
        assertNull(state.stack.detail)
        navigator.openMangaDetail(3L, favorite = null)
        assertFalse(state.detailNestedBackAvailable)

        navigator.openMore(MoreDestination.Settings)
        navigator.setMoreNestedBackAvailable(true)
        // The same More page instance moving to another page keeps what it reported.
        navigator.setMoreDestination(MoreDestination.Downloads)
        assertTrue(state.moreNestedBackAvailable)
        navigator.openMore(MoreDestination.About)
        assertFalse(state.moreNestedBackAvailable)
    }

    @Test
    fun aNestedBackReportIsNotAPageChange() {
        navigator.openMangaDetail(2L, favorite = null)
        val changes = navigator.pageChanges
        navigator.setDetailNestedBackAvailable(true)
        navigator.setDetailNestedBackAvailable(false)
        assertEquals(changes, navigator.pageChanges)
        navigator.closeDetail()
        assertEquals(changes + 1, navigator.pageChanges)
    }

    @Test
    fun everyPendingTitleAndRecoveryGetsATokenOfItsOwn() {
        navigator.openBrowseManga(item)
        val first = checkNotNull(state.stack.pending).request
        navigator.cancelPendingBrowse()
        navigator.openBrowseManga(item)
        val second = checkNotNull(state.stack.pending).request
        assertNotEquals(first, second)
        assertFalse(navigator.isPendingBrowse(item, first))
        assertTrue(navigator.isPendingBrowse(item, second))

        val recovery = navigator.beginRecovery(1L)
        assertNotEquals(second, recovery)
        assertNull(state.stack.pending, "a recovery replaces the title still resolving")
    }

    @Test
    fun backIsHandedToEachPageThatOwnsANestedBack() {
        navigator.selectSection(MainSection.BROWSE)
        navigator.setBrowseBackAvailable(true)
        assertTrue(navigator.back())
        assertEquals(1L, state.backRequests[BackTarget.BROWSE])

        navigator.openMore(MoreDestination.Settings)
        assertTrue(navigator.back())
        assertEquals(1L, state.backRequests[BackTarget.MORE])
        assertEquals(Destination.More(MoreDestination.Settings), state.destination, "the page decides itself")

        navigator.openMangaDetail(4L, favorite = null)
        assertTrue(navigator.back())
        assertEquals(1L, state.backRequests[BackTarget.DETAIL])
        assertEquals(1L, state.backRequests[BackTarget.MORE], "only the visible page gets the press")
    }

    @Test
    fun pagesWithoutANestedBackArePopped() {
        navigator.openBrowseManga(item)
        val untouched = state.backRequests
        assertTrue(navigator.back())
        assertNull(state.stack.pending)

        navigator.beginRecovery(2L)
        assertTrue(navigator.back())
        assertNull(state.stack.recovering)

        navigator.openLocalExtension(item, MainSection.LIBRARY)
        assertTrue(navigator.back())
        assertNull(state.stack.localExtension)
        assertEquals(untouched, state.backRequests)
    }

    /** 0008: Back used to close the page under the recovery first; it now closes what is in front. */
    @Test
    fun aRecoveryStartedOverALibraryPublicationTakesBackBeforeIt() {
        navigator.openLocalExtension(item, MainSection.LIBRARY)
        navigator.beginRecovery(2L)
        assertEquals(Destination.Recovering(2L), state.destination)
        assertTrue(navigator.back())
        assertNull(state.stack.recovering)
        assertEquals(Destination.LocalExtension(item), state.destination, "the page under it is still open")
        assertTrue(navigator.back())
        assertEquals(Destination.Section, state.destination)
    }

    /**
     * The bug class the counters had: opening a page used to set its count back to 0, which a
     * page still showing (the same title or More page) took for a Back press.
     */
    @Test
    fun openingAPageAgainIsNeverMistakenForBack() {
        navigator.openMore(MoreDestination.Settings)
        navigator.setMoreNestedBackAvailable(true)
        navigator.back()
        val seenBySettings = state.backRequests[BackTarget.MORE]
        navigator.openSettings()
        assertEquals(seenBySettings, state.backRequests[BackTarget.MORE])

        navigator.openLocalExtension(item, MainSection.LIBRARY)
        navigator.setBrowseReaderOpen(true)
        navigator.back()
        val seenByReader = state.backRequests[BackTarget.LOCAL_EXTENSION_READER]
        navigator.openLocalExtension(item, MainSection.LIBRARY)
        assertEquals(seenByReader, state.backRequests[BackTarget.LOCAL_EXTENSION_READER])

        navigator.selectSection(MainSection.BROWSE)
        navigator.setBrowseBackAvailable(true)
        navigator.back()
        val seenByBrowse = state.backRequests[BackTarget.BROWSE]
        navigator.selectSection(MainSection.LIBRARY)
        navigator.selectSection(MainSection.BROWSE)
        assertEquals(seenByBrowse, state.backRequests[BackTarget.BROWSE])
    }

    @Test
    fun backCountsOnlyGrowWhateverHappens() {
        val steps = listOf<() -> Unit>(
            { navigator.openMangaDetail(2L, favorite = null) },
            { navigator.setDetailNestedBackAvailable(true) },
            { navigator.back() },
            { navigator.openMore(MoreDestination.Settings) },
            { navigator.back() },
            { navigator.openSettings() },
            { navigator.openLocalExtension(item, MainSection.LIBRARY) },
            { navigator.setBrowseReaderOpen(true) },
            { navigator.back() },
            { navigator.closeLocalExtension() },
            { navigator.selectSection(MainSection.BROWSE) },
            { navigator.setBrowseBackAvailable(true) },
            { navigator.back() },
            { navigator.openBrowseManga(item) },
            { navigator.back() },
            { navigator.onReaderOpened(3L) },
            { navigator.back() },
            { navigator.selectSection(MainSection.HISTORY) },
        )
        var previous = state.backRequests
        steps.forEach { step ->
            step()
            val next = state.backRequests
            BackTarget.entries.forEach { target -> assertTrue(next[target] >= previous[target], "$target went back") }
            previous = next
        }
        BackTarget.entries.forEach { target -> assertTrue(previous[target] > 0L, "$target got a Back") }
    }
}
