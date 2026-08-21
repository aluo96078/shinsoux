@file:OptIn(ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtensionV2ContractsTest {
    @Test
    fun taggedPayloadsValidateAgainstTheExactSourceAndUnitIdentity() {
        val sourceKey = SourceKey(2, "example.package", "catalogue")
        val descriptor = SourceDescriptorV2(
            sourceKey = sourceKey,
            displayName = "Example",
            languageTag = "en-US",
            supportedContentKinds = setOf(ContentKind.IMAGE_SEQUENCE, ContentKind.PLAIN_TEXT),
            capabilities = setOf(ExtensionCapability.CONTENT),
        )
        val packageV2 = ExtensionPackageV2(
            contractVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
            packageId = "example.package",
            version = "2.0.0",
            displayName = "Example package",
            sources = listOf(descriptor),
        )
        val request = RemoteRequestPlanV2(
            method = HttpMethodV2.GET,
            url = "https://example.test/assets/page.png",
            headerHints = mapOf("Accept" to "image/png"),
            maxResponseBytes = 1_024,
        )
        val image = UnitContentPayload.ImageSequence(
            schemaVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
            representationId = "image-representation",
            sourceKey = sourceKey,
            remoteUnitId = "unit-1",
            pages = listOf(ImagePageV2("page-1", request, "image/png")),
        )
        val text = UnitContentPayload.InlineTextPayload(
            schemaVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
            representationId = "text-representation",
            sourceKey = sourceKey,
            remoteUnitId = "unit-1",
            source = TextPayloadSourceV2.InlineTextPayload("hello"),
            blocks = listOf(TextBlock("block-1", 0, 5)),
        )
        val result = UnitContentResultV2(
            schemaVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
            sourceKey = sourceKey,
            remotePublicationId = "publication-1",
            remoteUnitId = "unit-1",
            representations = listOf(image, text),
        )

        packageV2.validatePayload(sourceKey, image)
        packageV2.validatePayload(sourceKey, text)
        assertEquals(2, result.representations.size)
        assertTrue(packageV2.supports(ContentKind.PLAIN_TEXT))

        val otherSource = SourceKey(2, "example.package", "other")
        assertFailsWith<IllegalStateException> {
            packageV2.validatePayload(otherSource, image)
        }
        assertFailsWith<IllegalArgumentException> {
            packageV2.validatePayload(sourceKey, image.copy(sourceKey = otherSource))
        }
        assertFailsWith<IllegalArgumentException> {
            UnitContentResultV2(
                schemaVersion = 2,
                sourceKey = sourceKey,
                remotePublicationId = "publication-1",
                remoteUnitId = "unit-1",
                representations = listOf(
                    image.copy(sourceKey = otherSource),
                ),
            )
        }
    }

    @Test
    fun requestPlansRejectUnsafeUrisAndPrivilegedHeaderHints() {
        assertFailsWith<IllegalArgumentException> {
            RemoteRequestPlanV2(HttpMethodV2.GET, url = "ftp://example.test/file")
        }
        listOf(
            "bad%",
            "safe/%2fescape",
            "safe/%5cescape",
            "http%3a/escape",
            "%252e%252e/escape",
        ).forEach { relative ->
            assertFailsWith<IllegalArgumentException>(relative) {
                RemoteRequestPlanV2(
                    HttpMethodV2.GET,
                    baseUri = "https://example.test/api",
                    relativePath = relative,
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            TextPayloadSourceV2.InlineTextPayload("not an image", mediaType = "image/png")
        }
        assertFailsWith<IllegalArgumentException> {
            ImagePageV2(
                resourceId = "page",
                request = RemoteRequestPlanV2(HttpMethodV2.GET, url = "https://example.test/page"),
                mediaType = "text/plain",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RemoteRequestPlanV2(
                HttpMethodV2.GET,
                baseUri = "https://example.test/api",
                relativePath = "../private",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RemoteRequestPlanV2(
                HttpMethodV2.GET,
                url = "https://example.test/file",
                headerHints = mapOf("Cookie" to "session=secret"),
            )
        }

        val composed = RemoteRequestPlanV2(
            method = HttpMethodV2.POST,
            baseUri = "https://example.test/api",
            relativePath = "units/Chapter%201",
            body = RequestBodyRefV2("body-ref", "application/json", 12),
        )
        assertEquals("https://example.test/api/units/Chapter%201", composed.effectiveUri)

        assertEquals(
            "https://example.test/Book%20One",
            RemoteRequestPlanV2(
                HttpMethodV2.GET,
                url = "https://example.test/Book%20One",
            ).effectiveUri,
        )
    }

    @Test
    fun pagedResultsAndChunkedTextAreBoundedAndMakeProgress() {
        val item = RemotePublicationV2("publication-1", "A title")
        assertEquals(1, PagedResultV2(listOf(item), hasNextPage = true).items.size)
        assertFailsWith<IllegalArgumentException> {
            PagedResultV2(List(101) { item }, hasNextPage = true)
        }

        assertFailsWith<IllegalArgumentException> {
            TextChunkResultV2("", nextCursor = "next", done = false)
        }
        assertFailsWith<IllegalArgumentException> {
            TextChunkResultV2("chunk", nextCursor = null, done = false)
        }
        val terminal = TextChunkResultV2("chunk", nextCursor = null, done = true)
        terminal.validate(maxChunkBytes = 5)
        assertFailsWith<IllegalArgumentException> { terminal.validate(maxChunkBytes = 2) }
        assertFailsWith<IllegalArgumentException> {
            TextPayloadSourceV2.ChunkedTextPayload(
                streamId = "stream",
                maxChunkBytes = 0,
                cancellationReference = "cancel",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TextPayloadSourceV2.ChunkedTextPayload(
                streamId = "stream",
                mediaType = "application/octet-stream",
                cancellationReference = "cancel",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TextPayloadSourceV2.ChunkedTextPayload(
                streamId = "stream",
                charset = "UTF-16",
                cancellationReference = "cancel",
            )
        }
    }

    @Test
    fun epubPayloadRequiresCanonicalArchiveMediaTypeAndResolvableGraph() {
        val sourceKey = SourceKey(2, "example.package", "epub-source")
        val archiveRequest = RemoteRequestPlanV2(
            HttpMethodV2.GET,
            url = "https://example.test/book.epub",
        )
        val archive = RemoteBlobPlanV2(
            RemoteResourceV2("archive", archiveRequest, "application/epub+zip"),
        )
        val packageRequest = RemoteRequestPlanV2(
            HttpMethodV2.GET,
            url = "https://example.test/OPS/content.opf",
        )
        val packageDocument = RemoteEpubResourceV2(
            id = "opf",
            href = "OPS/content.opf",
            body = RemoteBlobPlanV2(
                RemoteResourceV2("opf", packageRequest, "application/oebps-package+xml"),
            ),
            mediaType = "application/oebps-package+xml",
        )
        val graph = RemoteEpubPackageV2(archive, "opf", listOf(packageDocument))
        val payload = UnitContentPayload.EpubSpine(
            schemaVersion = 2,
            representationId = "epub-representation",
            sourceKey = sourceKey,
            remoteUnitId = "chapter",
            packageGraph = graph,
            documents = listOf(
                RemoteEpubSpineDocumentV2("spine-opf", packageDocument.href, packageDocument.id),
            ),
        )
        assertEquals(ContentKind.EPUB_SPINE, payload.kind)

        assertFailsWith<IllegalArgumentException> {
            graph.copy(
                archive = RemoteBlobPlanV2(
                    RemoteResourceV2("archive", archiveRequest, "application/zip"),
                ),
            )
        }
    }

    @Test
    fun hostFacadeEnforcesCapabilityBeforeCallingExtensionAndUsesExactSourceKey() = runTest {
        val key = SourceKey(2, "example.package", "source-a")
        val descriptor = sourceDescriptor(key, setOf(ExtensionCapability.CONTENT))
        val implementation = FakeSource(descriptor)
        val facade = ExtensionHostFacadeV2(FakeRuntime(packageDescriptor(descriptor), implementation))
        val hostSource = requireNotNull(facade.source(key))

        assertFailsWith<IllegalArgumentException> { hostSource.search("query", 0) }
        assertEquals(0, implementation.searchCalls)
        assertEquals(null, facade.source(SourceKey(2, "example.package", "source-b")))

        val searchableDescriptor = sourceDescriptor(
            key,
            setOf(ExtensionCapability.CONTENT, ExtensionCapability.SEARCH),
        )
        val searchable = FakeSource(searchableDescriptor)
        val searchableFacade = ExtensionHostFacadeV2(
            FakeRuntime(packageDescriptor(searchableDescriptor), searchable),
        )
        val page = requireNotNull(searchableFacade.source(key)).search("query", 0)
        assertEquals(listOf("book"), page.items.map(RemotePublicationV2::remoteId))
        assertEquals(1, searchable.searchCalls)
    }

    @Test
    fun hostTextStreamRejectsCursorCyclesAndAggregateOverflowAndCancelsDelegate() = runTest {
        val cycleDelegate = FakeStream(
            listOf(TextChunkResultV2("a", nextCursor = "cursor", done = false)),
        )
        val cycle = HostTextChunkStreamV2(
            TextPayloadSourceV2.ChunkedTextPayload(
                streamId = "stream",
                firstCursor = "cursor",
                maxTotalBytes = 10,
                maxChunks = 2,
                cancellationReference = "cancel",
            ),
            cycleDelegate,
        )
        assertFailsWith<IllegalArgumentException> { cycle.next("cursor") }
        assertTrue(cycleDelegate.cancelled)

        val overflowDelegate = FakeStream(
            listOf(
                TextChunkResultV2("abc", nextCursor = "next", done = false),
                TextChunkResultV2("def", nextCursor = null, done = true),
            ),
        )
        val overflow = HostTextChunkStreamV2(
            TextPayloadSourceV2.ChunkedTextPayload(
                streamId = "stream",
                maxTotalBytes = 5,
                maxChunks = 2,
                cancellationReference = "cancel",
            ),
            overflowDelegate,
        )
        assertEquals("next", overflow.next(null).nextCursor)
        assertFailsWith<IllegalArgumentException> { overflow.next("next") }
        assertTrue(overflowDelegate.cancelled)

        val invalidChunkDelegate = FakeStream(
            listOf(TextChunkResultV2("abc", nextCursor = null, done = true)),
            maxChunkBytes = 2,
        )
        val invalidChunk = HostTextChunkStreamV2(
            TextPayloadSourceV2.ChunkedTextPayload(
                streamId = "stream",
                maxChunkBytes = 2,
                maxTotalBytes = 10,
                maxChunks = 1,
                cancellationReference = "cancel",
            ),
            invalidChunkDelegate,
        )
        assertFailsWith<IllegalArgumentException> { invalidChunk.next(null) }
        assertTrue(invalidChunkDelegate.cancelled)
    }

    @Test
    fun shuyueAdapterProducesScopedV2TextContentWithStableBlock() = runTest {
        val key = SourceKey(2, "shuyue.package", "opaque.source")
        val adapter = object : ShuYueTextSourceAdapterV2 {
            override suspend fun chapterText(remoteUnitId: String): TextPayloadSourceV2.InlineTextPayload =
                TextPayloadSourceV2.InlineTextPayload("chapter body")
        }

        val result = adapter.contentResult(key, "book", "chapter")
        val payload = result.representations.single() as UnitContentPayload.InlineTextPayload
        assertEquals(key, result.sourceKey)
        assertEquals("chapter", payload.remoteUnitId)
        assertEquals(TextBlock("body", 0, 12), payload.blocks.single())
    }

    private fun sourceDescriptor(
        key: SourceKey,
        capabilities: Set<ExtensionCapability>,
    ): SourceDescriptorV2 = SourceDescriptorV2(
        sourceKey = key,
        displayName = "Source",
        languageTag = "en",
        supportedContentKinds = setOf(ContentKind.PLAIN_TEXT),
        capabilities = capabilities,
    )

    private fun packageDescriptor(source: SourceDescriptorV2): ExtensionPackageV2 = ExtensionPackageV2(
        contractVersion = 2,
        packageId = source.sourceKey.packageId,
        version = "1",
        displayName = "Package",
        sources = listOf(source),
    )

    private class FakeRuntime(
        override val descriptor: ExtensionPackageV2,
        private val implementation: ExtensionSourceV2,
    ) : ExtensionPackageRuntimeV2 {
        override fun source(sourceKey: SourceKey): ExtensionSourceV2? =
            implementation.takeIf { it.descriptor.sourceKey == sourceKey }
    }

    private class FakeSource(
        override val descriptor: SourceDescriptorV2,
    ) : ExtensionSourceV2 {
        var searchCalls: Int = 0

        override suspend fun browseOptions(): BrowseOptionsSchemaV2 = BrowseOptionsSchemaV2()
        override suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2> {
            searchCalls++
            return PagedResultV2(listOf(RemotePublicationV2("book", "Book")), false)
        }
        override suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2> = PagedResultV2(emptyList(), false)
        override suspend fun browse(
            options: BrowseOptionsV2,
            page: Int,
        ): PagedResultV2<RemotePublicationV2> = PagedResultV2(emptyList(), false)
        override suspend fun details(remotePublicationId: String): RemotePublicationV2 =
            RemotePublicationV2(remotePublicationId, "Book")
        override suspend fun units(remotePublicationId: String, page: Int): PagedResultV2<RemoteUnitV2> =
            PagedResultV2(emptyList(), false)
        override suspend fun content(remotePublicationId: String, remoteUnitId: String): UnitContentResultV2 =
            error("not used")
        override suspend fun openTextStream(streamId: String): TextChunkStreamV2 = FakeStream(emptyList())
        override suspend fun login(credentials: LoginCredentialsV2): LoginResultV2 = LoginResultV2(true)
        override suspend fun logout(): Unit = Unit
        override suspend fun preferences(): List<PreferenceV2> = emptyList()
        override suspend fun favorite(remotePublicationId: String, favorite: Boolean): Unit = Unit
    }

    private class FakeStream(
        private val results: List<TextChunkResultV2>,
        override val maxChunkBytes: Int = 64,
    ) : TextChunkStreamV2 {
        private var index = 0
        var cancelled: Boolean = false
        override suspend fun next(cursor: String?): TextChunkResultV2 = results[index++]
        override fun cancel() { cancelled = true }
    }
}
