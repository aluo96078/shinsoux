package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublicationCoverFallbackTest {
    @Test
    fun detailWithoutCoverRetainsCatalogueCover() {
        val catalogueCover = "https://img.example/catalogue-cover.jpg"
        val detail = RemotePublicationV2(
            remoteId = "book-1",
            title = "Book",
            thumbnailUrl = null,
        )

        assertEquals(catalogueCover, detail.withFallbackThumbnail(catalogueCover).thumbnailUrl)
    }

    @Test
    fun detailCoverWinsAndBlankFallbackDoesNotInventCover() {
        val detailCover = "https://img.example/detail-cover.jpg"
        val detail = RemotePublicationV2(
            remoteId = "book-1",
            title = "Book",
            thumbnailUrl = detailCover,
        )

        assertEquals(detailCover, detail.withFallbackThumbnail("https://img.example/old.jpg").thumbnailUrl)
        assertNull(
            detail.copy(thumbnailUrl = null).withFallbackThumbnail(" ").thumbnailUrl,
        )
    }
}
