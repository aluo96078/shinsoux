package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.plugin.CatalogueSource
import dev.shinsou.kmp.plugin.FilterList
import dev.shinsou.kmp.plugin.MangasPage
import dev.shinsou.kmp.plugin.Page
import dev.shinsou.kmp.plugin.SChapter
import dev.shinsou.kmp.plugin.SManga
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A7b: any plugin can expose a site's account collection (bookcase) and change it. */
@OptIn(ExtensionImplementationApi::class)
class ScriptFavoriteAdapterTest {
    private class AccountSource(
        override val supportsFavorites: Boolean,
        override val supportsFavoriteMutation: Boolean,
    ) : CatalogueSource {
        val changes = mutableListOf<Pair<String, Boolean>>()
        override val id: Long = 1
        override val name: String = "Account"
        override val lang: String = "zh"
        override val supportsLatest: Boolean = false
        override val baseUrl: String = "https://novel.example"
        override suspend fun getMangaDetails(manga: SManga): SManga = manga
        override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(listOf(SManga(url = "/popular", title = "Popular")), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getFilterList(): FilterList = emptyList()
        override suspend fun getFavoriteManga(page: Int): MangasPage = MangasPage(listOf(SManga(url = "/mine", title = "Mine")), false)
        override suspend fun setFavorite(manga: SManga, favorite: Boolean): Boolean {
            changes += manga.url to favorite
            return true
        }
    }

    private fun sourceOf(account: AccountSource): ExtensionSourceV2 {
        val package_ = LegacyMangaPackageRuntimeV2(
            packageId = "example.account",
            version = "1.0.0",
            displayName = "Example",
            sources = listOf(account),
            declaredContentKindsByLegacyId = mapOf(account.id to setOf(ContentKind.IMAGE_SEQUENCE)),
        )
        return package_.source(package_.descriptor.sources.single().sourceKey)!!
    }

    @Test
    fun theBookcaseIsTheFavoriteBrowseOption() = runTest {
        val source = sourceOf(AccountSource(supportsFavorites = true, supportsFavoriteMutation = true))
        assertTrue(ExtensionCapability.FAVORITE in source.descriptor.capabilities)
        assertEquals(listOf("option"), source.browseOptions().keys)
        val bookcase = source.browse(BrowseOptionsV2(values = mapOf("option" to "bookcase")), 0)
        assertEquals(listOf("/mine"), bookcase.items.map { it.remoteId })
        assertEquals(listOf("/popular"), source.browse(BrowseOptionsV2(), 0).items.map { it.remoteId })
        assertFailsWith<IllegalArgumentException> { source.browse(BrowseOptionsV2(values = mapOf("option" to "other")), 0) }
    }

    @Test
    fun favoritesChangeOnlyThroughTheSourcesHook() = runTest {
        val account = AccountSource(supportsFavorites = true, supportsFavoriteMutation = true)
        val source = sourceOf(account)
        source.favorite("/book/1", true)
        source.favorite("/book/1", false)
        assertEquals(listOf("/book/1" to true, "/book/1" to false), account.changes)

        val readOnly = sourceOf(AccountSource(supportsFavorites = true, supportsFavoriteMutation = false))
        assertFailsWith<UnsupportedOperationException> { readOnly.favorite("/book/1", true) }
    }

    @Test
    fun aSourceWithoutAnAccountCollectionHasNoBookcase() = runTest {
        val source = sourceOf(AccountSource(supportsFavorites = false, supportsFavoriteMutation = false))
        assertFalse(ExtensionCapability.FAVORITE in source.descriptor.capabilities)
        assertEquals(emptyList(), source.browseOptions().keys)
        assertFailsWith<IllegalArgumentException> { source.browse(BrowseOptionsV2(values = mapOf("option" to "bookcase")), 0) }
    }
}
