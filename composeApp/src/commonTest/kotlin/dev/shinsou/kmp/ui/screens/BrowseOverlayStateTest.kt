package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.ui.BrowseManga
import dev.shinsou.kmp.ui.BrowseSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowseOverlayStateTest {
    private val firstSourceKey = SourceKey(packageId = "zh.bilimanga", sourceId = "manga")
    private val secondSourceKey = SourceKey(packageId = "zh.bilimanga", sourceId = "novel")
    private val firstSource = BrowseSource(
        id = 1L,
        name = "Manga",
        language = "zh",
        sourceKey = firstSourceKey,
    )
    private val secondSource = BrowseSource(
        id = 2L,
        name = "Novel",
        language = "zh",
        sourceKey = secondSourceKey,
    )
    private val publication = BrowseManga(
        sourceId = firstSource.id,
        url = "/book/1",
        title = "Publication",
        sourceKey = firstSourceKey,
        remotePublicationId = "book-1",
    )

    @Test
    fun closingSourceAlsoClosesItsPublicationAndReader() {
        val state = BrowseOverlayState()
            .openSource(firstSource)
            .openPublication(publication)
            .setReaderVisible(true)

        val closed = state.closeSource()

        assertNull(closed.activeSource)
        assertNull(closed.activeV2Publication)
        assertFalse(closed.activeV2Reader)
        assertFalse(closed.hasOverlay)
    }

    @Test
    fun switchingSourceCannotRetainPreviousPublication() {
        val state = BrowseOverlayState()
            .openSource(firstSource)
            .openPublication(publication)

        val switched = state.openSource(secondSource)

        assertEquals(secondSource, switched.activeSource)
        assertNull(switched.activeV2Publication)
        assertFalse(switched.activeV2Reader)
        assertTrue(switched.hasOverlay)
    }

    @Test
    fun closingPublicationReturnsToItsSourceCatalogue() {
        val state = BrowseOverlayState()
            .openSource(firstSource)
            .openPublication(publication)

        val closed = state.closePublication()

        assertEquals(firstSource, closed.activeSource)
        assertNull(closed.activeV2Publication)
        assertFalse(closed.activeV2Reader)
        assertTrue(closed.hasOverlay)
    }

    @Test
    fun closingGlobalSearchAlsoClosesPublicationOpenedFromIt() {
        val state = BrowseOverlayState()
            .openGlobalSearch()
            .openPublication(publication)

        val closed = state.closeGlobalSearch()

        assertFalse(closed.globalSearchVisible)
        assertNull(closed.activeV2Publication)
        assertFalse(closed.hasOverlay)
    }
}
