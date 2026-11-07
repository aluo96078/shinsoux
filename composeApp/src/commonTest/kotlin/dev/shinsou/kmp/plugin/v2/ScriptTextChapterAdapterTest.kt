package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.CatalogueSource
import dev.shinsou.kmp.plugin.FilterList
import dev.shinsou.kmp.plugin.MangasPage
import dev.shinsou.kmp.plugin.Page
import dev.shinsou.kmp.plugin.SChapter
import dev.shinsou.kmp.plugin.SManga
import dev.shinsou.kmp.plugin.TextChapterSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** ADR-0002 A5: third-party script packages can publish novel and mixed sources. */
@OptIn(ExtensionImplementationApi::class)
class ScriptTextChapterAdapterTest {
    private class ScriptSource(
        override val id: Long,
        override val supportsChapterText: Boolean,
    ) : CatalogueSource, TextChapterSource {
        override val name: String = "Source $id"
        override val lang: String = "zh"
        override val supportsLatest: Boolean = false
        override val baseUrl: String = "https://novel.example"
        override suspend fun getMangaDetails(manga: SManga): SManga = manga
        override suspend fun getChapterList(manga: SManga): List<SChapter> = listOf(SChapter(url = "c1", name = "第一章"))
        override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, imageUrl = "https://novel.example/1.jpg"))
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getFilterList(): FilterList = emptyList()
        override suspend fun getChapterText(chapter: SChapter): String? = "${chapter.url}：正文第一段。\n第二段。"
    }

    private fun runtime(vararg sources: Pair<ScriptSource, Set<ContentKind>>) = LegacyMangaPackageRuntimeV2(
        packageId = "example.novel",
        version = "1.0.0",
        displayName = "Example",
        sources = sources.map { it.first },
        declaredContentKindsByLegacyId = sources.associate { (source, kinds) -> source.id to kinds },
    )

    @Test
    fun aNovelSourceServesItsChapterTextAsPlainText() = runTest {
        val novel = ScriptSource(1, supportsChapterText = true)
        val package_ = runtime(novel to setOf(ContentKind.PLAIN_TEXT))
        val descriptor = package_.descriptor.sources.single()
        assertEquals(setOf(ContentKind.PLAIN_TEXT), descriptor.supportedContentKinds)
        val content = package_.source(descriptor.sourceKey)!!.content("book", "c1")
        val text = assertIs<UnitContentPayload.InlineTextPayload>(content.representations.single())
        assertEquals("c1：正文第一段。\n第二段。", text.source.text)
    }

    @Test
    fun aMixedPackageKeepsEachSourcesOwnKinds() = runTest {
        val manga = ScriptSource(1, supportsChapterText = false)
        val both = ScriptSource(2, supportsChapterText = true)
        val package_ = runtime(manga to setOf(ContentKind.IMAGE_SEQUENCE), both to setOf(ContentKind.IMAGE_SEQUENCE, ContentKind.PLAIN_TEXT))
        val kinds = package_.descriptor.sources.associate { it.sourceKey.legacyLongId to it.supportedContentKinds }
        assertEquals(setOf(ContentKind.IMAGE_SEQUENCE), kinds[1L])
        assertEquals(setOf(ContentKind.IMAGE_SEQUENCE, ContentKind.PLAIN_TEXT), kinds[2L])
        assertEquals(setOf(ContentKind.IMAGE_SEQUENCE, ContentKind.PLAIN_TEXT), package_.descriptor.supportedContentKinds)
    }

    @Test
    fun declaredTextWithoutTheHookIsNotOffered() = runTest {
        val noHook = ScriptSource(1, supportsChapterText = false)
        // Text-only and no getChapterText: nothing to serve, so the package has no source.
        assertFailsWith<IllegalArgumentException> { runtime(noHook to setOf(ContentKind.PLAIN_TEXT)) }
        // Declared both, but only images are real.
        val package_ = runtime(noHook to setOf(ContentKind.IMAGE_SEQUENCE, ContentKind.PLAIN_TEXT))
        assertEquals(setOf(ContentKind.IMAGE_SEQUENCE), package_.descriptor.sources.single().supportedContentKinds)
        assertNull(package_.source(SourceKey.fromLegacy("other.package", 1)))
    }
}
