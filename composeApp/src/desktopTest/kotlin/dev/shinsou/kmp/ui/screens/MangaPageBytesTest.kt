package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.reader.ReaderImageTransform
import dev.shinsou.kmp.ui.ReaderPage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class MangaPageBytesTest {
    private val bytes = byteArrayOf(1, 2, 3)

    @Test
    fun aPageResolvedOnDemandIntoAScrambledOneIsLeftToTheReader() = runBlocking {
        val scrambled = ReaderPage(index = 0, imageResolver = {
            ReaderPage(index = 0, imageBytes = bytes, imageTransform = ReaderImageTransform.ReverseVerticalSegments(10))
        })
        assertNull(loadPageBytes(scrambled), "its bytes are scrambled until the reader descrambles them")
    }

    @Test
    fun aPageResolvedOnDemandIntoAPlainOneIsTranslated() = runBlocking {
        val plain = ReaderPage(index = 0, imageResolver = { ReaderPage(index = 0, imageBytes = bytes) })
        assertContentEquals(bytes, loadPageBytes(plain))
    }
}
