@file:OptIn(ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.domain.model.SourceKey
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionRuntimeV2Test {
    @Test
    fun immutablePackageAndRegistryRouteEverySourceByExactOpaqueKey() = runTest {
        val first = descriptor("pkg.multi", "source-a")
        val second = descriptor("pkg.multi", "source-b")
        val runtime = ImmutableExtensionPackageRuntimeV2(
            packageDescriptor(first, second),
            listOf(StubSource(second), StubSource(first)),
        )
        val registry = ExtensionRuntimeRegistryV2()

        registry.install(runtime)

        assertEquals("source-a", assertNotNull(registry.source(first.sourceKey)).descriptor.sourceKey.sourceId)
        assertEquals("source-b", assertNotNull(registry.source(second.sourceKey)).descriptor.sourceKey.sourceId)
        assertNull(registry.source(SourceKey(2, "pkg.multi", "missing")))
        assertFailsWith<IllegalArgumentException> { registry.install(runtime) }
        assertTrue(registry.uninstall("pkg.multi"))
        assertNull(registry.source(first.sourceKey))
    }

    @Test
    fun sameSourceAndUnitCanReturnImageAndTextRepresentationsTogether() = runTest {
        val descriptor = SourceDescriptorV2(
            sourceKey = SourceKey(2, "pkg.hybrid", "hybrid"),
            displayName = "Hybrid",
            languageTag = "en",
            supportedContentKinds = setOf(ContentKind.IMAGE_SEQUENCE, ContentKind.PLAIN_TEXT),
            capabilities = setOf(ExtensionCapability.CONTENT),
        )
        val providers = listOf(
            provider("images") { request ->
                UnitContentPayload.ImageSequence(
                    schemaVersion = 2,
                    representationId = "images",
                    sourceKey = request.sourceKey,
                    remoteUnitId = request.remoteUnitId,
                    pages = listOf(
                        ImagePageV2(
                            "page-1",
                            RemoteRequestPlanV2(HttpMethodV2.GET, url = "https://example.test/page.webp"),
                            "image/webp",
                        ),
                    ),
                )
            },
            provider("text") { request ->
                UnitContentPayload.InlineTextPayload(
                    schemaVersion = 2,
                    representationId = "text",
                    sourceKey = request.sourceKey,
                    remoteUnitId = request.remoteUnitId,
                    source = TextPayloadSourceV2.InlineTextPayload("same chapter"),
                    blocks = listOf(TextBlock("body", 0, 12)),
                )
            },
        )
        val source = MultiRepresentationExtensionSourceV2(
            StubSource(descriptor),
            MultiRepresentationContentResolverV2(providers),
        )
        val host = ExtensionHostFacadeV2(
            ImmutableExtensionPackageRuntimeV2(packageDescriptor(descriptor), listOf(source)),
        )

        val result = assertNotNull(host.source(descriptor.sourceKey)).content("book", "chapter")

        assertEquals(listOf("images", "text"), result.representations.map { it.representationId })
        assertEquals(
            setOf(ContentKind.IMAGE_SEQUENCE, ContentKind.PLAIN_TEXT),
            result.representations.map { it.kind }.toSet(),
        )
        assertFailsWith<IllegalArgumentException> {
            MultiRepresentationContentResolverV2(listOf(providers[0], providers[0]))
        }
    }

    @Test
    fun legacyMangaAdapterPreservesLongIdentityAndProducesHostFetchImagePlan() = runTest {
        val legacy = LegacyFixtureSource()
        val adapter = LegacyMangaExtensionSourceV2(legacy, "legacy.pkg")
        val key = adapter.descriptor.sourceKey

        assertEquals("1817081", key.sourceId)
        assertEquals(1_817_081L, key.legacyLongId)
        val search = adapter.search("needle", 0)
        assertEquals("/book/1", search.items.single().remoteId)

        val result = adapter.content("/book/1", "/chapter/1")
        val payload = assertIs<UnitContentPayload.ImageSequence>(result.representations.single())
        val page = payload.pages.single()
        assertEquals("https://legacy.example/images/one.jpg", page.request.effectiveUri)
        assertEquals("fixture-agent", page.request.headerHints["User-Agent"])
        assertEquals("reverse-vertical-segments", page.transform?.transformId)
        assertNotNull(page.transform?.parameters?.get("segmentCount"))
    }

    private fun descriptor(packageId: String, sourceId: String): SourceDescriptorV2 = SourceDescriptorV2(
        sourceKey = SourceKey(2, packageId, sourceId),
        displayName = sourceId,
        languageTag = "en",
        supportedContentKinds = setOf(ContentKind.PLAIN_TEXT),
        capabilities = setOf(ExtensionCapability.CONTENT),
    )

    private fun packageDescriptor(vararg sources: SourceDescriptorV2): ExtensionPackageV2 = ExtensionPackageV2(
        contractVersion = 2,
        packageId = sources.first().sourceKey.packageId,
        version = "2.0.0",
        displayName = "Fixture package",
        sources = sources.toList(),
        supportedContentKinds = sources.flatMapTo(linkedSetOf()) { it.supportedContentKinds },
    )

    private fun provider(
        id: String,
        block: suspend (UnitContentRequestV2) -> UnitContentPayload?,
    ): UnitContentRepresentationProviderV2 = object : UnitContentRepresentationProviderV2 {
        override val representationId: String = id
        override suspend fun load(request: UnitContentRequestV2): UnitContentPayload? = block(request)
    }

    private class LegacyFixtureSource : CatalogueSource {
        override val id: Long = 1_817_081L
        override val name: String = "Legacy"
        override val lang: String = "en"
        override val baseUrl: String = "https://legacy.example"
        override val supportsLatest: Boolean = true
        override val headers: Map<String, String> = mapOf("User-Agent" to "fixture-agent", "Cookie" to "secret")

        override suspend fun getPopularManga(page: Int): MangasPage = result()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = result()
        override suspend fun getLatestUpdates(page: Int): MangasPage = result()
        override suspend fun getFilterList(): FilterList = emptyList()
        override suspend fun getMangaDetails(manga: SManga): SManga = manga.copy(title = "Book")
        override suspend fun getChapterList(manga: SManga): List<SChapter> =
            listOf(SChapter("/chapter/1", "Chapter"))
        override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(
            Page(
                index = 0,
                imageUrl = "/images/one.jpg#Shinsou-JM-Scramble-Id=1&" +
                    "Shinsou-JM-Photo-Id=300000&Shinsou-JM-Filename=one.jpg",
            ),
        )

        private fun result(): MangasPage = MangasPage(listOf(SManga("/book/1", "Book")), false)
    }
}

private open class StubSource(
    override val descriptor: SourceDescriptorV2,
) : ExtensionSourceV2 {
    override suspend fun browseOptions(): BrowseOptionsSchemaV2 = BrowseOptionsSchemaV2()
    override suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2> =
        PagedResultV2(emptyList(), false)
    override suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2> = PagedResultV2(emptyList(), false)
    override suspend fun browse(options: BrowseOptionsV2, page: Int): PagedResultV2<RemotePublicationV2> =
        PagedResultV2(emptyList(), false)
    override suspend fun details(remotePublicationId: String): RemotePublicationV2 =
        RemotePublicationV2(remotePublicationId, "Book")
    override suspend fun units(remotePublicationId: String, page: Int): PagedResultV2<RemoteUnitV2> =
        PagedResultV2(emptyList(), false)
    override suspend fun content(remotePublicationId: String, remoteUnitId: String): UnitContentResultV2 =
        error("content delegate not configured")
    override suspend fun openTextStream(streamId: String): TextChunkStreamV2 = error("no stream")
    override suspend fun login(credentials: LoginCredentialsV2): LoginResultV2 = LoginResultV2(false)
    override suspend fun logout(): Unit = Unit
    override suspend fun preferences(): List<PreferenceV2> = emptyList()
    override suspend fun favorite(remotePublicationId: String, favorite: Boolean): Unit = Unit
}
