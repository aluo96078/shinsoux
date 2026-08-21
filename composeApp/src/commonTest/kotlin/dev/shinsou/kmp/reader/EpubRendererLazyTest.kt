package dev.shinsou.kmp.reader

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.BlobReadLease
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.EpubPackage
import dev.shinsou.kmp.content.EpubResource
import dev.shinsou.kmp.content.EpubSpineDocument
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpubRendererLazyTest {
    @Test
    fun requestCreationAndUrlAdmissionAreMetadataOnlyAndEveryBodyAccessRechecksRights() {
        val fixture = fixture()
        val observing = ObservingBlobStore(fixture.store)
        var allowed = true
        var gateChecks = 0
        val request = EpubRenderRequestFactory(observing).create(
            navigation = fixture.navigation,
            documentIndex = 0,
            resourceReadGate = EpubResourceReadGate { read ->
                gateChecks++
                check(allowed) { "DISPLAY revoked" }
                read()
            },
        )
        val resolver = EpubPublicationResourceResolver(request)

        assertEquals(emptyList(), observing.readBlobIds)
        assertEquals(0, gateChecks)
        assertTrue(resolver.contains(request.documentUrl))
        assertEquals(emptyList(), observing.readBlobIds)
        assertEquals(0, gateChecks)

        val document = requireNotNull(resolver.resolve(request.documentUrl))
        assertTrue(document.bytes.decodeToString().contains("Content-Security-Policy"))
        assertEquals(listOf(fixture.documentBlob.blobId), observing.readBlobIds)
        assertEquals(1, gateChecks)

        allowed = false
        assertFailsWith<IllegalStateException> { resolver.resolve(request.documentUrl) }
        assertEquals(listOf(fixture.documentBlob.blobId), observing.readBlobIds)
        assertEquals(2, gateChecks)

        allowed = true
        val stylesheet = request.publisherStyleSheets.single()
        assertContentEquals(
            fixture.cssBytes,
            requireNotNull(resolver.resolve(request.publicationUrl(stylesheet))).bytes,
        )
        assertEquals(
            listOf(fixture.documentBlob.blobId, fixture.cssBlob.blobId),
            observing.readBlobIds,
        )
        assertEquals(observing.readBlobIds, observing.closedBlobIds)
        assertEquals(3, gateChecks)
    }

    @Test
    fun userStyleLayerFollowsPublisherCssAndPrivateCspBlocksActiveContent() {
        val fixture = fixture()
        val observing = ObservingBlobStore(fixture.store)
        val request = EpubRenderRequestFactory(observing).create(
            navigation = fixture.navigation,
            documentIndex = 0,
            userStyleSheets = listOf(
                EpubUserStyleSheet("reader-theme", "body { color: rebeccapurple; }"),
            ),
        )
        val resolver = EpubPublicationResourceResolver(request)

        val documentResponse = requireNotNull(resolver.resolve(request.documentUrl))
        val rendered = documentResponse.bytes.decodeToString()
        val publisherLink = rendered.indexOf("href=\"style.css\"")
        val publisherInline = rendered.indexOf("body { line-height: 1.2; }")
        val userLayer = rendered.indexOf(resolver.userStyleUrls.single())
        val closingHead = rendered.indexOf("</head>")

        assertTrue(publisherLink >= 0)
        assertTrue(publisherInline > publisherLink)
        assertTrue(userLayer > publisherInline)
        assertTrue(closingHead > userLayer)
        assertTrue(rendered.contains("script-src 'none'"))
        assertTrue(rendered.contains("connect-src 'none'"))
        assertTrue(rendered.contains("style-src shinsou-epub: 'unsafe-inline'"))
        assertEquals("no-store", documentResponse.headers["Cache-Control"])

        val mimeScriptUrl = request.publicationUrl(requireNotNull(request.resourceByHref("OPS/mime-script.bin")))
        val propertyScriptUrl = request.publicationUrl(
            requireNotNull(request.resourceByHref("OPS/property-script.dat")),
        )
        assertFalse(resolver.contains(mimeScriptUrl))
        assertNull(resolver.resolve(mimeScriptUrl))
        assertFalse(resolver.contains(propertyScriptUrl))
        assertNull(resolver.resolve(propertyScriptUrl))
        assertNull(resolver.resolve("https://example.invalid/tracker.js"))
        assertNull(resolver.resolve("${request.publicationRootUrl}../OPS/chapter.xhtml"))
        assertNull(resolver.resolve("${request.publicationRootUrl}%2e%2e/OPS/chapter.xhtml"))
        val foreignRoot = request.publicationRootUrl.replace(
            request.navigation.scope.publicationId.value,
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        )
        val foreignDocumentUrl = "$foreignRoot${request.document.href}"
        assertFalse(resolver.contains(foreignDocumentUrl))
        assertNull(resolver.resolve(foreignDocumentUrl))
        assertNull(resolver.locatorForUrl(foreignDocumentUrl))
        assertEquals(listOf(fixture.documentBlob.blobId), observing.readBlobIds)
    }

    @Test
    fun deterministicUserStyleNamespaceCannotShadowPublisherResourcesAndUsesBoundedCache() {
        val fixture = fixture()
        val publisherCss = "publisher collision { color: red; }".encodeToByteArray()
        val collisionBlob = fixture.store.put(publisherCss, "text/css").reference
        val collision = EpubResource(
            id = "publisher-reader-style-collision",
            href = ".shinsou-reader/user-style-layer/0.css",
            resource = ResourceRef("publisher-reader-style-collision", collisionBlob),
        )
        val representation = fixture.navigation.representation.copy(
            packageGraph = fixture.navigation.representation.packageGraph.copy(
                resources = fixture.navigation.representation.packageGraph.resources + collision,
            ),
        )
        val navigation = EpubSpineNavigation(fixture.navigation.scope, representation)
        val userCss = "body { color: blue; }"
        val request = EpubRenderRequestFactory(fixture.store).create(
            navigation = navigation,
            documentIndex = 0,
            userStyleSheets = listOf(EpubUserStyleSheet("reader", userCss)),
        )
        val policy = EpubRenderMemoryPolicy(
            maximumResolvedResourceBytes = 128,
            maximumDocumentBytes = 128,
            maximumCacheBytes = 128,
            maximumCachedResourceBytes = 128,
            readChunkBytes = 16,
        )
        val resolver = EpubPublicationResourceResolver(request, policy)
        val repeated = EpubPublicationResourceResolver(request, policy)
        val publisherUrl = request.publicationUrl(requireNotNull(request.resourceByHref(collision.href)))
        val userStyleUrl = resolver.userStyleUrls.single()
        val userCssBytes = userCss.encodeToByteArray()

        assertEquals(resolver.userStyleUrls, repeated.userStyleUrls)
        assertTrue(userStyleUrl.contains("user-style-layer-1/0.css"))
        assertTrue(userStyleUrl != publisherUrl)
        assertContentEquals(publisherCss, requireNotNull(resolver.resolve(publisherUrl)).bytes)
        assertContentEquals(userCssBytes, requireNotNull(resolver.resolve(userStyleUrl)).bytes)
        assertContentEquals(userCssBytes, requireNotNull(resolver.resolve(userStyleUrl)).bytes)
        assertEquals(2, resolver.cachedResourceCount)
        assertEquals(publisherCss.size + userCssBytes.size, resolver.cachedByteSize)
    }

    @Test
    fun largeContinuousPublisherCollisionSetSelectsTheFirstNamespaceGap() {
        val fixture = fixture()
        val baseRequest = EpubRenderRequestFactory(fixture.store).create(
            navigation = fixture.navigation,
            documentIndex = 0,
        )
        val collisionCount = 4_096
        val collisions = (0 until collisionCount).map { suffix ->
            val directory = ".shinsou-reader/user-style-layer" + if (suffix == 0) "" else "-$suffix"
            EpubRenderResource(
                resourceId = "reader-style-collision-$suffix",
                href = "$directory/publisher.css",
                mediaType = "text/css",
                bytes = "/* publisher $suffix */".encodeToByteArray(),
            )
        }
        val request = EpubRenderRequest(
            navigation = fixture.navigation,
            documentIndex = 0,
            initialLocator = fixture.navigation.locatorAt(0),
            publisherResources = baseRequest.publisherResources + collisions,
            userStyleSheets = listOf(EpubUserStyleSheet("reader", "body { color: navy; }")),
        )

        val resolver = EpubPublicationResourceResolver(request)

        assertEquals(
            "${request.publicationRootUrl}.shinsou-reader/user-style-layer-$collisionCount/0.css",
            resolver.userStyleUrls.single(),
        )
    }

    @Test
    fun userStyleUtf8BodyMustFitTheResolverResponsePolicyBeforeResolution() {
        val fixture = fixture()
        val request = EpubRenderRequestFactory(fixture.store).create(
            navigation = fixture.navigation,
            documentIndex = 0,
            userStyleSheets = listOf(EpubUserStyleSheet("oversized", "界".repeat(16))),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            EpubPublicationResourceResolver(
                request,
                EpubRenderMemoryPolicy(
                    maximumResolvedResourceBytes = 32,
                    maximumDocumentBytes = 32,
                    maximumCacheBytes = 32,
                    maximumCachedResourceBytes = 32,
                    readChunkBytes = 8,
                ),
            )
        }
        assertEquals("EPUB user style exceeds the per-response limit: oversized", failure.message)
    }

    @Test
    fun headScannerSkipsFakeMarkupContextsQuotedTagsAndRawText() {
        val fixture = fixture(
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html [<!ENTITY fake "<head id='doctype-fake'></head>">]>
                <html data-fake="<head id='quoted-fake'></head>">
                  <!-- <head id="comment-fake"></head> -->
                  <?probe value="<head id='pi-fake'></head>"?>
                  <![CDATA[<head id="cdata-fake"></head>]]>
                  <head id="real" data-note="> <head id='attribute-fake'>">
                    <title>literal <head id="title-fake"></head></title>
                    <style>.probe::before { content: '<head id="style-fake">'; }</style>
                    <style>.unmatched::before { content: "<head data-css='"; }</style>
                    <script>const fake = "<head data-script='"; const less = left < right;</script>
                  </head>
                  <body>chapter</body>
                </html>
            """.trimIndent(),
        )
        val request = EpubRenderRequestFactory(fixture.store).create(
            navigation = fixture.navigation,
            documentIndex = 0,
            userStyleSheets = listOf(EpubUserStyleSheet("reader", "body { color: teal; }")),
        )
        val resolver = EpubPublicationResourceResolver(request)

        val rendered = requireNotNull(resolver.resolve(request.documentUrl)).bytes.decodeToString()
        val realHead = rendered.indexOf("<head id=\"real\"")
        val securityLayer = rendered.indexOf("Content-Security-Policy")
        val title = rendered.indexOf("<title>")
        val userStyle = rendered.indexOf(resolver.userStyleUrls.single())
        val closingHead = rendered.indexOf("</head>", userStyle)

        assertTrue(realHead >= 0)
        assertTrue(securityLayer > realHead)
        assertTrue(title > securityLayer)
        assertTrue(userStyle > title)
        assertTrue(closingHead > userStyle)
        assertTrue(rendered.contains("content: \"<head data-css='\""))
        assertTrue(rendered.contains("const fake = \"<head data-script='\""))
        assertEquals(1, Regex("Content-Security-Policy").findAll(rendered).count())
    }

    @Test
    fun fakeHeadMarkupWithoutARealHeadFailsClosed() {
        val fixture = fixture(
            """
                <!DOCTYPE html [<!ENTITY fake "<head></head>">]>
                <html data-fake="<head></head>">
                  <!-- <head></head> -->
                  <?probe value="<head></head>"?>
                  <![CDATA[<head></head>]]>
                  <body><script>const fake = '<head></head>';</script></body>
                </html>
            """.trimIndent(),
        )
        val request = EpubRenderRequestFactory(fixture.store).create(fixture.navigation, documentIndex = 0)
        val failure = assertFailsWith<IllegalArgumentException> {
            EpubPublicationResourceResolver(request).resolve(request.documentUrl)
        }

        assertEquals(
            "EPUB browser document has no bounded head element: OPS/chapter.xhtml",
            failure.message,
        )
    }

    @Test
    fun boundedLruEvictsBodiesAndCloseDropsThePublicationCache() {
        val fixture = fixture()
        val observing = ObservingBlobStore(fixture.store)
        val request = EpubRenderRequestFactory(observing).create(fixture.navigation, documentIndex = 0)
        val resolver = EpubPublicationResourceResolver(
            request = request,
            memoryPolicy = EpubRenderMemoryPolicy(
                maximumResolvedResourceBytes = 1_024,
                maximumDocumentBytes = 1_024,
                maximumCacheBytes = 24,
                maximumCachedResourceBytes = 24,
                readChunkBytes = 3,
            ),
        )
        val stylesheetUrl = request.publicationUrl(requireNotNull(request.resourceByHref("OPS/style.css")))
        val packageUrl = request.publicationUrl(requireNotNull(request.resourceByHref("OPS/package.opf")))

        assertContentEquals(fixture.cssBytes, requireNotNull(resolver.resolve(stylesheetUrl)).bytes)
        requireNotNull(resolver.resolve(packageUrl))
        assertContentEquals(fixture.cssBytes, requireNotNull(resolver.resolve(stylesheetUrl)).bytes)

        assertEquals(
            listOf(fixture.cssBlob.blobId, fixture.packageBlob.blobId, fixture.cssBlob.blobId),
            observing.readBlobIds,
        )
        assertEquals(observing.readBlobIds, observing.closedBlobIds)
        assertEquals(1, resolver.cachedResourceCount)
        assertEquals(fixture.cssBytes.size, resolver.cachedByteSize)

        resolver.close()

        assertTrue(resolver.isClosed)
        assertEquals(0, resolver.cachedResourceCount)
        assertEquals(0, resolver.cachedByteSize)
        assertFalse(resolver.contains(stylesheetUrl))
        assertNull(resolver.resolve(stylesheetUrl))
        assertEquals(3, observing.readBlobIds.size)
    }

    @Test
    fun largePackageIsMetadataOnlyAndOversizedResourceFailsBeforeOpeningALease() {
        val fixture = fixture()
        val observing = ObservingBlobStore(fixture.store)
        val hugeArchive = fixture.navigation.representation.packageGraph.archive.copy(
            byteSize = 512L * 1024L * 1024L,
        )
        val navigation = EpubSpineNavigation(
            scope = fixture.navigation.scope,
            representation = fixture.navigation.representation.copy(
                packageGraph = fixture.navigation.representation.packageGraph.copy(archive = hugeArchive),
            ),
        )
        val request = EpubRenderRequestFactory(observing).create(navigation, documentIndex = 0)
        assertEquals(emptyList(), observing.readBlobIds)

        val resolver = EpubPublicationResourceResolver(
            request,
            EpubRenderMemoryPolicy(
                maximumResolvedResourceBytes = 32,
                maximumDocumentBytes = 32,
                maximumCacheBytes = 0,
                maximumCachedResourceBytes = 0,
                readChunkBytes = 8,
            ),
        )
        val failure = assertFailsWith<IllegalArgumentException> { resolver.resolve(request.documentUrl) }
        assertEquals(
            "EPUB renderer resource exceeds the per-response limit: chapter",
            failure.message,
        )
        assertEquals(emptyList(), observing.readBlobIds)
        assertEquals(emptyList(), observing.closedBlobIds)
    }

    @Test
    fun aLazilyLoadedResourceStillFailsClosedOnDigestTamper() {
        val fixture = fixture()
        val observing = ObservingBlobStore(
            delegate = fixture.store,
            replacementBytes = mapOf(
                fixture.documentBlob.blobId to
                    fixture.documentBytes.copyOf().also { bytes -> bytes[bytes.lastIndex] = '!'.code.toByte() },
            ),
        )
        val request = EpubRenderRequestFactory(observing).create(
            navigation = fixture.navigation,
            documentIndex = 0,
        )

        assertEquals(emptyList(), observing.readBlobIds)
        val failure = assertFailsWith<IllegalArgumentException> { request.document.bytes }
        assertEquals(
            "EPUB renderer resource failed integrity verification: chapter",
            failure.message,
        )
        assertEquals(listOf(fixture.documentBlob.blobId), observing.readBlobIds)
        assertEquals(observing.readBlobIds, observing.closedBlobIds)
    }

    private fun fixture(documentSource: String = DEFAULT_DOCUMENT_SOURCE): Fixture {
        val store = InMemoryContentBlobStore()
        val archive = store.put("archive".encodeToByteArray(), "application/epub+zip").reference
        val packageBlob = store.put("package".encodeToByteArray(), "application/oebps-package+xml").reference
        val documentBytes = documentSource.encodeToByteArray()
        val documentBlob = store.put(documentBytes, "application/xhtml+xml").reference
        val cssBytes = "body { color: black; }".encodeToByteArray()
        val cssBlob = store.put(cssBytes, "text/css").reference
        val mimeScriptBlob = store.put(
            "window.mimeBad = true;".encodeToByteArray(),
            "application/javascript",
        ).reference
        val propertyScriptBlob = store.put(
            "window.propertyBad = true;".encodeToByteArray(),
            "application/octet-stream",
        ).reference
        val resources = listOf(
            EpubResource(
                id = "package",
                href = "OPS/package.opf",
                resource = ResourceRef("package", packageBlob),
            ),
            EpubResource(
                id = "chapter",
                href = "OPS/chapter.xhtml",
                resource = ResourceRef("chapter", documentBlob),
            ),
            EpubResource(
                id = "style",
                href = "OPS/style.css",
                resource = ResourceRef("style", cssBlob),
            ),
            EpubResource(
                id = "mime-script",
                href = "OPS/mime-script.bin",
                resource = ResourceRef("mime-script", mimeScriptBlob),
            ),
            EpubResource(
                id = "property-script",
                href = "OPS/property-script.dat",
                resource = ResourceRef("property-script", propertyScriptBlob),
                properties = setOf("scripted"),
            ),
        )
        val representation = ContentRepresentation.EpubSpine(
            representationId = "44444444-4444-4444-8444-444444444444",
            packageGraph = EpubPackage(
                archive = archive,
                packageDocumentId = "package",
                resources = resources,
            ),
            documents = listOf(
                EpubSpineDocument(
                    id = "spine-chapter",
                    href = "OPS/chapter.xhtml",
                    resourceId = "chapter",
                ),
            ),
        )
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        val navigation = EpubSpineNavigation(
            scope = ReadingScope(
                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                publicationId = publication,
                acquisitionId = "22222222-2222-4222-8222-222222222222",
                unitId = UnitKey(publication, "33333333-3333-4333-8333-333333333333"),
                contentRevision = 1,
            ),
            representation = representation,
        )
        return Fixture(store, navigation, packageBlob, documentBlob, documentBytes, cssBlob, cssBytes)
    }

    private companion object {
        const val DEFAULT_DOCUMENT_SOURCE: String =
            "<html><head><link rel=\"stylesheet\" href=\"style.css\"/>" +
                "<style>body { line-height: 1.2; }</style>" +
                "<script src=\"mime-script.bin\"></script></head><body>chapter</body></html>"
    }

    private data class Fixture(
        val store: InMemoryContentBlobStore,
        val navigation: EpubSpineNavigation,
        val packageBlob: BlobRef,
        val documentBlob: BlobRef,
        val documentBytes: ByteArray,
        val cssBlob: BlobRef,
        val cssBytes: ByteArray,
    )

    private class ObservingBlobStore(
        private val delegate: ContentBlobStore,
        private val replacementBytes: Map<String, ByteArray> = emptyMap(),
    ) : ContentBlobStore by delegate {
        val readBlobIds = mutableListOf<String>()
        val closedBlobIds = mutableListOf<String>()

        override fun openRead(reference: BlobRef): BlobReadLease? {
            readBlobIds += reference.blobId
            val replacement = replacementBytes[reference.blobId]
            val lease = if (replacement != null) {
                ByteArrayReadLease(reference, replacement)
            } else {
                delegate.openRead(reference) ?: return null
            }
            return ObservingReadLease(lease) { closedBlobIds += reference.blobId }
        }
    }

    private class ObservingReadLease(
        private val delegate: BlobReadLease,
        private val onClose: () -> Unit,
    ) : BlobReadLease by delegate {
        private var closeObserved = false

        override fun close() {
            delegate.close()
            if (!closeObserved) {
                closeObserved = true
                onClose()
            }
        }
    }

    private class ByteArrayReadLease(
        override val reference: BlobRef,
        bytes: ByteArray,
    ) : BlobReadLease {
        private val body = bytes.copyOf()
        private var offset = 0
        override var isClosed: Boolean = false
            private set
        override val isPinned: Boolean get() = !isClosed

        override fun readChunk(maxBytes: Int): ByteArray? {
            check(!isClosed)
            require(maxBytes > 0)
            if (offset == body.size) return null
            val end = minOf(body.size, offset + maxBytes)
            return body.copyOfRange(offset, end).also { offset = end }
        }

        override fun pin(): BlobReadLease = this

        override fun close() {
            isClosed = true
        }
    }
}
