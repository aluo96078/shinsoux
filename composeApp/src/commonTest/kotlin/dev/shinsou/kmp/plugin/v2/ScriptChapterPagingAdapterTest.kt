package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.plugin.CatalogueSource
import dev.shinsou.kmp.plugin.ChapterListPage
import dev.shinsou.kmp.plugin.FilterList
import dev.shinsou.kmp.plugin.MangasPage
import dev.shinsou.kmp.plugin.Page
import dev.shinsou.kmp.plugin.SChapter
import dev.shinsou.kmp.plugin.SManga
import dev.shinsou.kmp.plugin.TextChapterSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A title opened while browsing shows its first chapters without the whole list being fetched. */
@OptIn(ExtensionImplementationApi::class)
class ScriptChapterPagingAdapterTest {
    private class Serial(private val chapters: Int, private val pages: Boolean) : CatalogueSource, TextChapterSource {
        var completeCalls = 0
        val pageCalls = ArrayList<Int>()
        override val id: Long = 1
        override val name: String = "Serial"
        override val lang: String = "ko"
        override val supportsLatest: Boolean = false
        override val baseUrl: String = "https://novel.example"
        override val supportsChapterText: Boolean = true
        private fun chapter(index: Int) = SChapter(url = "c$index", name = "Chapter $index", chapterNumber = index.toDouble())
        override suspend fun getMangaDetails(manga: SManga): SManga = manga
        override suspend fun getChapterList(manga: SManga): List<SChapter> {
            completeCalls++
            return (1..chapters).map(::chapter)
        }
        override suspend fun getChapterListPage(manga: SManga, page: Int): ChapterListPage? {
            if (!pages) return null
            pageCalls += page
            val from = page * 100 + 1
            val to = minOf(chapters, from + 99)
            return ChapterListPage((from..to).map(::chapter), hasNextPage = to < chapters)
        }
        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getFilterList(): FilterList = emptyList()
        override suspend fun getChapterText(chapter: SChapter): String? = "text"
    }

    private fun source(serial: Serial): ExtensionSourceV2 {
        val runtime = LegacyMangaPackageRuntimeV2(
            packageId = "example.serial",
            version = "1.0.0",
            displayName = "Example",
            sources = listOf(serial),
            declaredContentKindsByLegacyId = mapOf(serial.id to setOf(ContentKind.PLAIN_TEXT)),
        )
        return runtime.source(runtime.descriptor.sources.single().sourceKey)!!
    }

    @Test
    fun aSourceThatPagesItsListIsAskedForOnePageAtATime() = runTest {
        val serial = Serial(chapters = 250, pages = true)
        val units = source(serial)

        val first = units.units("book", 0)
        assertEquals(100, first.items.size)
        assertTrue(first.hasNextPage)
        assertEquals("c1", first.items.first().remoteId)
        assertEquals(listOf(0), serial.pageCalls)
        assertEquals(0, serial.completeCalls, "the whole list is never fetched for a browsed title")

        val last = units.units("book", 2)
        assertEquals((201..250).map { "c$it" }, last.items.map { it.remoteId })
        assertFalse(last.hasNextPage)
        assertEquals(listOf(0, 2), serial.pageCalls)
    }

    @Test
    fun aSourceWithoutPagingFetchesItsWholeListOnceNotOncePerPage() = runTest {
        val serial = Serial(chapters = 250, pages = false)
        val units = source(serial)

        assertEquals(100, units.units("book", 0).items.size)
        assertEquals("c101", units.units("book", 1).items.first().remoteId)
        assertEquals(50, units.units("book", 2).items.size)
        assertEquals(1, serial.completeCalls, "later pages are cut from the list page 0 fetched")

        // Opening the title again asks the source again: a new chapter must show up.
        units.units("book", 0)
        assertEquals(2, serial.completeCalls)
        // Another title never reads the first one's list.
        assertEquals("c101", units.units("other", 1).items.first().remoteId)
        assertEquals(3, serial.completeCalls)
    }
}
