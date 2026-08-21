@file:OptIn(ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExtensionBrowseContentGatewayV2Test {
    @Test
    fun exactGatewayKeepsSiblingSourcesPackagesAndRepresentationsIndependent() = runTest {
        val first = SourceKey(2, "fixture.multi", "opaque:first")
        val second = SourceKey(2, "fixture.multi", "opaque:second")
        val other = SourceKey(2, "fixture.other", "opaque:first")
        val registry = ExtensionRuntimeRegistryV2()
        registry.install(runtime("fixture.multi", listOf(first, second)))
        registry.install(runtime("fixture.other", listOf(other)))
        val gateway = ExtensionBrowseContentGatewayV2(
            ExtensionSourceResolverV2(registry::source),
        )

        assertNotNull(gateway.source(first))
        assertNotNull(gateway.source(second))
        assertNotNull(gateway.source(other))
        assertNull(gateway.source(SourceKey(2, "fixture.multi", "opaque:missing")))
        assertNull(gateway.source(SourceKey(2, "fixture.missing", "opaque:first")))

        val publication = gateway.browse(second).items.single()
        assertEquals("opaque:second:book", publication.remoteId)
        assertEquals(publication.remoteId, gateway.details(second, publication.remoteId).remoteId)
        val unit = gateway.units(second, publication.remoteId).items.single()
        val content = gateway.content(second, publication.remoteId, unit.remoteId)

        assertEquals(second, content.sourceKey)
        assertEquals(listOf("primary-text", "alternate-text"), content.representations.map { it.representationId })
        assertFailsWith<IllegalArgumentException> {
            gateway.content(SourceKey(2, "fixture.multi", "opaque:missing"), "book", "unit")
        }
        registry.close()
    }

    private fun runtime(packageId: String, keys: List<SourceKey>): ImmutableExtensionPackageRuntimeV2 {
        val sources = keys.map(::FixtureSource)
        return ImmutableExtensionPackageRuntimeV2(
            ExtensionPackageV2(
                contractVersion = 2,
                packageId = packageId,
                version = "1.0.0",
                displayName = packageId,
                sources = sources.map(FixtureSource::descriptor),
                supportedContentKinds = setOf(ContentKind.PLAIN_TEXT),
            ),
            sources,
        )
    }

    private class FixtureSource(sourceKey: SourceKey) : ExtensionSourceV2 {
        override val descriptor: SourceDescriptorV2 = SourceDescriptorV2(
            sourceKey = sourceKey,
            displayName = sourceKey.sourceId,
            languageTag = "en",
            supportedContentKinds = setOf(ContentKind.PLAIN_TEXT),
            capabilities = setOf(
                ExtensionCapability.BROWSE,
                ExtensionCapability.METADATA,
                ExtensionCapability.UNITS,
                ExtensionCapability.CONTENT,
            ),
            baseUrl = "https://fixture.example",
        )

        override suspend fun browseOptions(): BrowseOptionsSchemaV2 = BrowseOptionsSchemaV2()
        override suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2> =
            error("not supported")
        override suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2> = error("not supported")
        override suspend fun browse(options: BrowseOptionsV2, page: Int): PagedResultV2<RemotePublicationV2> =
            PagedResultV2(
                listOf(RemotePublicationV2("${descriptor.sourceKey.sourceId}:book", "Book")),
                false,
            )

        override suspend fun details(remotePublicationId: String): RemotePublicationV2 =
            RemotePublicationV2(remotePublicationId, "Book")

        override suspend fun units(remotePublicationId: String, page: Int): PagedResultV2<RemoteUnitV2> =
            PagedResultV2(listOf(RemoteUnitV2("${descriptor.sourceKey.sourceId}:unit", "Unit")), false)

        override suspend fun content(
            remotePublicationId: String,
            remoteUnitId: String,
        ): UnitContentResultV2 = UnitContentResultV2(
            schemaVersion = 2,
            sourceKey = descriptor.sourceKey,
            remotePublicationId = remotePublicationId,
            remoteUnitId = remoteUnitId,
            representations = listOf(
                payload("primary-text", remoteUnitId, "primary"),
                payload("alternate-text", remoteUnitId, "alternate"),
            ),
        )

        override suspend fun openTextStream(streamId: String): TextChunkStreamV2 = error("not supported")
        override suspend fun login(credentials: LoginCredentialsV2): LoginResultV2 = error("not supported")
        override suspend fun logout(): Unit = error("not supported")
        override suspend fun preferences(): List<PreferenceV2> = error("not supported")
        override suspend fun favorite(remotePublicationId: String, favorite: Boolean): Unit = error("not supported")

        private fun payload(
            representationId: String,
            remoteUnitId: String,
            text: String,
        ): UnitContentPayload.InlineTextPayload = UnitContentPayload.InlineTextPayload(
            schemaVersion = 2,
            representationId = representationId,
            sourceKey = descriptor.sourceKey,
            remoteUnitId = remoteUnitId,
            source = TextPayloadSourceV2.InlineTextPayload(text),
            blocks = listOf(TextBlock("body", 0, text.length)),
        )
    }
}
