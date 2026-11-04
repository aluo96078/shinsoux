package dev.shinsou.kmp.plugin.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemotePublicationKnownDetailsTest {
    private val remoteId = "https://www.wenku8.net/book/2161.htm"
    private val known = RemotePublicationV2(
        remoteId = remoteId,
        title = "尚未開始的末日戰爭與我們那已經結束的青春鬧劇",
        thumbnailUrl = "https://img.wenku8.com/image/2/2161/2161s.jpg",
        author = "王雀孫",
        description = "這是一出喜劇。",
    )

    @Test
    fun recognisesASourcesIdPlaceholder() {
        assertTrue(isPlaceholderPublicationTitle("Wenku8 #2161", remoteId))
        assertTrue(isPlaceholderPublicationTitle(" ", remoteId))
        assertFalse(isPlaceholderPublicationTitle(known.title, remoteId))
        // A real title that merely ends in a number is not the id, even when the id contains it.
        assertFalse(isPlaceholderPublicationTitle("Volume #3", remoteId))
        assertFalse(isPlaceholderPublicationTitle("Volume #1", remoteId))
        assertFalse(isPlaceholderPublicationTitle("Issue #216", remoteId))
        assertFalse(isPlaceholderPublicationTitle("Issue #10", "wenku-10x"))
    }

    @Test
    fun aRealAnswerMayClearFields() {
        val cleared = known.copy(author = null, description = null)

        assertEquals(cleared, cleared.overKnownDetails(known))
    }

    @Test
    fun aDegradedOfflineAnswerKeepsTheKnownDetails() {
        val degraded = RemotePublicationV2(remoteId = remoteId, title = "Wenku8 #2161")

        assertEquals(known, degraded.overKnownDetails(known))
    }

    @Test
    fun realDetailsStillReplaceTheKnownOnes() {
        val fresh = known.copy(title = "New title", description = "Longer introduction")

        assertEquals(fresh, fresh.overKnownDetails(known))
        // A placeholder that was known is replaced by a real title.
        assertEquals(fresh, fresh.overKnownDetails(known.copy(title = "Wenku8 #2161")))
    }
}
