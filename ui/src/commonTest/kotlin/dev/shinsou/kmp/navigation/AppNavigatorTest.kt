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

class AppNavigatorTest {
    private val selection = LibrarySelectionState()
    private val navigator = AppNavigator(MainSection.LIBRARY, selection)
    private val state get() = navigator.state.value
    private val item = BrowseManga(sourceId = 7L, url = "/title", title = "Title")

    @Test
    fun startsOnTheGivenSectionWithNothingPushed() {
        assertEquals(MainSection.LIBRARY, state.section)
        assertEquals(Destination.Section, state.destination)
        assertFalse(navigator.back())
    }

    @Test
    fun aSettingsLinkClosesAnOpenLibraryExtensionPageAndItsReader() {
        navigator.openLocalExtension(item, MainSection.LIBRARY)
        navigator.setBrowseReaderOpen(true)
        navigator.openSettings()
        assertNull(state.localLibraryPublication)
        assertFalse(state.browseReaderOpen)
        assertEquals(MoreDestination.Settings, state.moreDestination)
    }

    @Test
    fun selectingASectionClosesPushedPagesAndSelections() {
        navigator.openMangaDetail(4L, favorite = null)
        selection.selectLibraryTitles(setOf(1L))
        selection.selectChapters(setOf(2L))
        navigator.selectSection(MainSection.HISTORY)
        assertEquals(MainSection.HISTORY, state.section)
        assertEquals(Destination.Section, state.destination)
        assertTrue(selection.state.value.libraryIds.isEmpty())
        assertTrue(selection.state.value.chapterIds.isEmpty())
    }

    @Test
    fun browseKeepsItsNestedBackOnlyWhenBrowseIsSelectedAgain() {
        navigator.selectSection(MainSection.BROWSE)
        navigator.setBrowseBackAvailable(true)
        navigator.selectSection(MainSection.BROWSE)
        assertTrue(state.browseBackAvailable)
        navigator.selectSection(MainSection.LIBRARY)
        assertFalse(state.browseBackAvailable)
    }

    @Test
    fun aLibraryFavoriteOpenedWhileBrowsingMovesToTheLibrary() {
        navigator.selectSection(MainSection.BROWSE)
        navigator.setBrowseBackAvailable(true)
        navigator.openMangaDetail(4L, favorite = true)
        assertEquals(MainSection.LIBRARY, state.section)
        assertFalse(state.browseBackAvailable)
        assertEquals(Destination.Manga(4L), state.destination)
    }

    @Test
    fun onlyOnePendingSourceTitleAtATime() {
        assertTrue(navigator.openBrowseManga(item))
        val request = state.pendingBrowseRequest
        assertFalse(navigator.openBrowseManga(item.copy(title = "Other")))
        assertEquals(Destination.Pending(item), state.destination)
        assertTrue(navigator.isPendingBrowse(item, request))
    }

    @Test
    fun aResolvedPendingTitleOpensItsDetailAsTheBrowseCatalogueChild() {
        navigator.openBrowseManga(item)
        val request = state.pendingBrowseRequest
        assertTrue(navigator.finishPendingBrowse(item, request, mangaId = 9L))
        assertEquals(Destination.Manga(9L), state.destination)
        assertEquals(9L, state.browseOwnedLegacyMangaId)
        navigator.closeBrowseParentDetail()
        assertNull(state.selectedMangaId)
        assertNull(state.browseOwnedLegacyMangaId)
    }

    @Test
    fun aCancelledPendingTitleCannotNavigateLater() {
        navigator.openBrowseManga(item)
        val request = state.pendingBrowseRequest
        assertTrue(navigator.back())
        assertFalse(navigator.finishPendingBrowse(item, request, mangaId = 9L))
        assertNull(state.selectedMangaId)
    }

    @Test
    fun aTitleStillResolvingWhenTheTabChangesDoesNotOpenOverTheNewTab() {
        navigator.selectSection(MainSection.BROWSE)
        navigator.openBrowseManga(item)
        val request = state.pendingBrowseRequest
        navigator.selectSection(MainSection.HISTORY)
        assertNull(state.pendingBrowseManga)
        assertEquals(MainSection.HISTORY, state.section)
        assertEquals(Destination.Section, state.destination)
        assertFalse(navigator.finishPendingBrowse(item, request, mangaId = 7L))
        assertEquals(MainSection.HISTORY, state.section)
        assertEquals(Destination.Section, state.destination)
        assertNull(state.selectedMangaId)
    }

    @Test
    fun aTitleStillResolvingDoesNotOpenOverTheSettingsLink() {
        navigator.openBrowseManga(item)
        val request = state.pendingBrowseRequest
        navigator.openSettings()
        assertFalse(navigator.finishPendingBrowse(item, request, mangaId = 7L))
        assertEquals(Destination.More(MoreDestination.Settings), state.destination)
    }

    @Test
    fun aTitleStillResolvingDoesNotOpenUnderALinkedPage() {
        // A link opens a library extension title, starts a recovery, or opens a reader.
        listOf<() -> Unit>(
            { navigator.openLocalExtension(item, MainSection.LIBRARY) },
            { navigator.beginRecovery(5L) },
            { navigator.onReaderOpened(5L) },
        ).forEach { openLinkedPage ->
            navigator.openBrowseManga(item)
            val request = state.pendingBrowseRequest
            openLinkedPage()
            val linked = state
            assertNull(linked.pendingBrowseManga)
            assertFalse(navigator.finishPendingBrowse(item, request, mangaId = 7L))
            assertEquals(linked, state, "the resolved title changes nothing")
        }
    }

    @Test
    fun closingTheBrowseParentKeepsADetailItDidNotOpen() {
        navigator.openMangaDetail(3L, favorite = null)
        navigator.closeBrowseParentDetail()
        assertEquals(3L, state.selectedMangaId)
    }

    @Test
    fun anUnfavoriteStoredLateClosesOnlyItsOwnTitle() {
        navigator.openMangaDetail(3L, favorite = null)
        // The user opened another title before the unfavorite of title 3 was stored.
        navigator.openMangaDetail(4L, favorite = null)
        navigator.closeDetailOf(3L)
        assertEquals(4L, state.selectedMangaId)

        navigator.closeDetailOf(4L)
        assertNull(state.selectedMangaId)
    }

    @Test
    fun backGoesThroughTheNestedPagesInOrder() {
        navigator.openMore(MoreDestination.Settings)
        assertTrue(navigator.back())
        assertEquals(1L, state.moreBackRequest)
        navigator.setMoreDestination(null)
        navigator.openMangaDetail(5L, favorite = null)
        assertTrue(navigator.back())
        assertEquals(1L, state.detailBackRequest)
        navigator.closeDetail()
        selection.selectLibraryTitles(setOf(1L, 2L))
        assertTrue(navigator.back())
        assertTrue(selection.state.value.libraryIds.isEmpty())
        assertFalse(navigator.back())
    }

    @Test
    fun backInsideALocalExtensionReaderGoesToTheReader() {
        navigator.openLocalExtension(item, MainSection.LIBRARY)
        navigator.setBrowseReaderOpen(true)
        assertTrue(navigator.back())
        assertEquals(1L, state.localLibraryReaderBackRequest)
        assertEquals(item, state.localLibraryPublication)
        navigator.setBrowseReaderOpen(false)
        assertTrue(navigator.back())
        assertNull(state.localLibraryPublication)
    }

    @Test
    fun aRecoveryEndsOnlyForItsOwnRequest() {
        val first = navigator.beginRecovery(1L)
        assertEquals(Destination.Recovering(1L), state.destination)
        navigator.cancelRecovery()
        val second = navigator.beginRecovery(1L)
        navigator.endRecovery(first, 1L)
        assertEquals(1L, state.recoveringLocalLibraryMangaId)
        assertFalse(navigator.isRecovering(first, 1L))
        assertTrue(navigator.isRecovering(second, 1L))
        navigator.endRecovery(second, 1L)
        assertNull(state.recoveringLocalLibraryMangaId)
    }

    @Test
    fun selectingASectionAbandonsARecovery() {
        val request = navigator.beginRecovery(1L)
        navigator.selectSection(MainSection.UPDATES)
        assertFalse(navigator.isRecovering(request, 1L))
        assertNull(state.recoveringLocalLibraryMangaId)
    }

    @Test
    fun theDestinationFollowsThePhoneStackingOrder() {
        navigator.openMore(MoreDestination.About)
        assertEquals(Destination.More(MoreDestination.About), state.destination)
        navigator.openMangaDetail(2L, favorite = null)
        assertEquals(Destination.Manga(2L), state.destination)
        navigator.openLocalExtension(item, MainSection.LIBRARY)
        assertEquals(Destination.LocalExtension(item), state.destination)
        navigator.beginRecovery(3L)
        assertEquals(Destination.Recovering(3L), state.destination)
    }

    @Test
    fun aBackSwipeSlidesPagesUnlessTheDetailHasItsOwnNestedBack() {
        assertFalse(navigator.gestureMovesVisiblePage())
        navigator.openMangaDetail(2L, favorite = null)
        assertTrue(navigator.gestureMovesVisiblePage())
        navigator.setDetailNestedBackAvailable(true)
        assertFalse(navigator.gestureMovesVisiblePage())
    }

    @Test
    fun removedTitlesCloseTheirPages() {
        navigator.openMangaDetail(2L, favorite = null)
        selection.selectChapters(setOf(5L))
        navigator.closeRemovedTitles(setOf(2L), openExtensionRemoved = false)
        assertNull(state.selectedMangaId)
        assertTrue(selection.state.value.chapterIds.isEmpty())
        navigator.openLocalExtension(item, MainSection.LIBRARY)
        navigator.closeRemovedTitles(setOf(8L), openExtensionRemoved = true)
        assertNull(state.localLibraryPublication)
    }

    @Test
    fun openingTheReaderSelectsItsTitleWithoutAPendingBack() {
        navigator.openMangaDetail(2L, favorite = null)
        navigator.back()
        navigator.onReaderOpened(6L)
        assertEquals(6L, state.selectedMangaId)
        assertEquals(0L, state.detailBackRequest)
    }

    @Test
    fun theSettingsDeepLinkOpensSettingsInMore() {
        navigator.openMangaDetail(2L, favorite = null)
        navigator.openSettings()
        assertEquals(MainSection.MORE, state.section)
        assertEquals(Destination.More(MoreDestination.Settings), state.destination)
    }
}
