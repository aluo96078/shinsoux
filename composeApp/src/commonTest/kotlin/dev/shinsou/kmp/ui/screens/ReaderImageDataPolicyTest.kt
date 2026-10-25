package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.ui.ReaderPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReaderImageDataPolicyTest {
    @Test
    fun remoteUrlWithoutHostMaterializedBytesIsNotPassedToCoil() {
        assertNull(readerImageData(ReaderPage(index = 0, imageUrl = "https://cdn.example/page.jpg")))
    }

    @Test
    fun localFileUriRemainsDirectlyRenderable() {
        assertEquals(
            "file:///downloads/page.jpg",
            readerImageData(ReaderPage(index = 0, imageUrl = "file:///downloads/page.jpg", local = true)),
        )
    }

    @Test
    fun hostMaterializedBytesTakePrecedenceOverAnyUrl() {
        val bytes = byteArrayOf(1, 2, 3)
        assertSame(
            bytes,
            readerImageData(
                ReaderPage(index = 0, imageUrl = "https://cdn.example/page.jpg", imageBytes = bytes),
            ),
        )
    }

    @Test
    fun emptyHostMaterializedBytesAreNotPassedToCoil() {
        assertNull(
            readerImageData(
                ReaderPage(index = 0, imageBytes = byteArrayOf()),
            ),
        )
    }
}
