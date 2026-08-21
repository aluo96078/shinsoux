package dev.shinsou.kmp.acquisition

import dev.shinsou.kmp.content.ContentBlobStage
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.EpubPackageNavigationKind
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.reader.EpubBrowserRenderSession
import dev.shinsou.kmp.reader.EpubBrowserRenderer
import dev.shinsou.kmp.reader.EpubRenderRequest
import dev.shinsou.kmp.reader.EpubRenderRequestFactory
import dev.shinsou.kmp.reader.EpubPublicationResourceResolver
import dev.shinsou.kmp.reader.EpubResourceReadGate
import dev.shinsou.kmp.reader.EpubSpineNavigation
import dev.shinsou.kmp.reader.EpubUserStyleSheet
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EpubAcquisitionServiceTest {
    @Test
    fun acquisitionPreservesCompleteResourceAndCssGraphWithRelativeDeclarations() {
        val fixture = fixture()
        val archive = "exact fake zip bytes".encodeToByteArray()
        val store = InMemoryContentBlobStore()
        var extractorCalled = false
        val service = EpubAcquisitionService(
            blobStore = store,
            archiveExtractor = EpubArchiveExtractor { received, limits ->
                assertContentEquals(archive, received)
                assertEquals(EpubArchiveLimits(), limits)
                extractorCalled = true
                fixture
            },
        )

        val result = service.acquire(EpubAcquisitionRequest(target(), archive))
        val representation = assertIs<ContentRepresentation.EpubSpine>(result.representation)

        assertTrue(extractorCalled)
        assertContentEquals(archive, store.read(representation.packageGraph.archive))
        assertEquals(fixture.size, representation.packageGraph.resources.size)
        assertEquals("OPS/package.opf", result.metadata.packageDocumentHref)
        assertEquals("測試 😀", result.metadata.title)
        assertEquals("Text/chapter%201.xhtml", result.metadata.manifest.first().declaredHref)
        assertEquals("OPS/Text/chapter%201.xhtml", result.metadata.manifest.first().resolvedHref)
        assertEquals("OPS/Text/chapter%201.xhtml", representation.documents.single().href)
        val epub3Navigation = result.metadata.navigation.single { it.kind == EpubNavigationKind.EPUB3_NAV }
        assertEquals("OPS/Text/chapter%201.xhtml", epub3Navigation.points.single().resolvedHref)
        assertEquals(representation.documents.single().resourceId, epub3Navigation.points.single().resourceId)

        val packageGraph = representation.packageGraph
        assertEquals("3.0", packageGraph.packageMetadata?.packageVersion)
        assertEquals("book-id", packageGraph.packageMetadata?.uniqueIdentifierIdRef)
        assertEquals("urn:uuid:book-1", packageGraph.packageMetadata?.uniqueIdentifier)
        assertEquals("ncx", packageGraph.spineTocManifestIdRef)
        assertEquals(
            setOf(EpubPackageNavigationKind.EPUB3_NAV, EpubPackageNavigationKind.NCX),
            packageGraph.navigation.mapTo(linkedSetOf()) { it.kind },
        )
        val chapterDeclaration = packageGraph.manifest.single { it.manifestIdRef == "chapter" }
        assertEquals("fallback", chapterDeclaration.fallbackIdRef)
        assertEquals("overlay", chapterDeclaration.mediaOverlayIdRef)
        val spineDocument = representation.documents.single()
        assertEquals("chapter", spineDocument.manifestIdRef)
        assertEquals(setOf("rendition:layout-pre-paginated", "page-spread-left"), spineDocument.properties)
        assertEquals("pre-paginated", spineDocument.rendition?.layout)

        val css = representation.packageGraph.resources.single { it.href == "OPS/Styles/main.css" }
        assertContentEquals(CSS_BYTES, store.read(css.resource.blob))
        val cssGraph = result.metadata.cssDependencies.single { it.stylesheetHref == "OPS/Styles/main.css" }
        assertEquals("OPS/Styles/main.css", cssGraph.stylesheetHref)
        assertEquals(
            setOf("OPS/Styles/theme.css", "OPS/Images/cover.png", "OPS/Fonts/book.woff2"),
            cssGraph.references.mapNotNull(EpubCssReference::resolvedHref).toSet(),
        )
        assertEquals(fixture.size + 1, result.publishedBlobs.size)

        val repeated = EpubAcquisitionService(
            InMemoryContentBlobStore(),
            extractor(fixture.reversed()),
        ).acquire(EpubAcquisitionRequest(target(), archive))
        val repeatedRepresentation = repeated.representation as ContentRepresentation.EpubSpine
        assertEquals(representation.representationId, repeatedRepresentation.representationId)
        assertEquals(
            representation.packageGraph.resources.map { it.id to it.href },
            repeatedRepresentation.packageGraph.resources.map { it.id to it.href },
        )
        assertEquals(result.manifest.manifestId, repeated.manifest.manifestId)
    }

    @Test
    fun commonEpub3NavAndEpub2NcxDoctypesAreAcceptedAsOpaqueDeclarations() {
        val result = EpubAcquisitionService(
            InMemoryContentBlobStore(),
            extractor(fixture()),
        ).acquire(EpubAcquisitionRequest(target(), "archive".encodeToByteArray()))
        val graph = (result.representation as ContentRepresentation.EpubSpine).packageGraph

        assertEquals(
            setOf(EpubPackageNavigationKind.EPUB3_NAV, EpubPackageNavigationKind.NCX),
            graph.navigation.mapTo(linkedSetOf()) { it.kind },
        )
        assertTrue(graph.navigation.all { it.points.single().label.isNotBlank() })
    }

    @Test
    fun epubTypeUsesItsExpandedNamespaceAndRejectsNamespaceCollisionsBeforePublication() {
        val alternatePrefixNavigation = """
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:book="http://www.idpf.org/2007/ops">
              <body>
                <nav><a href="Text/fallback.xhtml">Not the TOC</a></nav>
                <nav book:type="toc"><a href="Text/chapter%201.xhtml#start">Namespaced TOC</a></nav>
              </body>
            </html>
        """.trimIndent().encodeToByteArray()
        val accepted = EpubAcquisitionService(
            InMemoryContentBlobStore(),
            extractor(fixture().replaceBody("OPS/nav.xhtml", alternatePrefixNavigation)),
        ).acquire(EpubAcquisitionRequest(target(), "archive".encodeToByteArray()))
        val epub3 = accepted.metadata.navigation.single { it.kind == EpubNavigationKind.EPUB3_NAV }
        assertEquals("Namespaced TOC", epub3.points.single().label)

        val invalidNavigationDocuments = listOf(
            """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:book="https://attacker.invalid/ops">
                  <body><nav book:type="toc"><a href="Text/chapter%201.xhtml">Wrong namespace</a></nav></body>
                </html>
            """.trimIndent(),
            """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:book="http://www.idpf.org/2007/ops">
                  <body><nav book:type="toc" type="landmarks"><a href="Text/chapter%201.xhtml">Collision</a></nav></body>
                </html>
            """.trimIndent(),
            """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <body><nav undeclared:type="toc"><a href="Text/chapter%201.xhtml">Undeclared</a></nav></body>
                </html>
            """.trimIndent(),
            """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:a="http://www.idpf.org/2007/ops" xmlns:b="http://www.idpf.org/2007/ops">
                  <body><nav a:type="toc" b:type="toc"><a href="Text/chapter%201.xhtml">Duplicate</a></nav></body>
                </html>
            """.trimIndent(),
            """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:XML="https://attacker.invalid/xml">
                  <body><nav><a href="Text/chapter%201.xhtml">Reserved prefix</a></nav></body>
                </html>
            """.trimIndent(),
        )
        invalidNavigationDocuments.forEach { navigationXml ->
            val store = InMemoryContentBlobStore()
            assertFailsWith<EpubAcquisitionException.InvalidPackage> {
                EpubAcquisitionService(
                    store,
                    extractor(fixture().replaceBody("OPS/nav.xhtml", navigationXml.encodeToByteArray())),
                ).acquire(EpubAcquisitionRequest(target(), "archive".encodeToByteArray()))
            }
            assertEquals(0, store.currentGeneration)
            assertEquals(0, store.pendingReceiptCount)
        }
    }

    @Test
    fun structuralElementNamespacesCannotSpoofTheEpubGraphOrOpenBlobStages() {
        val wrongContainerRoot = CONTAINER_XML.replace(
            "urn:oasis:names:tc:opendocument:xmlns:container",
            "https://attacker.invalid/container",
        )
        val wrongRootfile = """
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile xmlns="https://attacker.invalid/container"
                          full-path="OPS/package.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()
        val wrongPackageRoot = OPF_XML.replaceFirst(
            "http://www.idpf.org/2007/opf",
            "https://attacker.invalid/opf",
        )
        val wrongManifest = OPF_XML.replace(
            "<manifest>",
            "<manifest xmlns=\"https://attacker.invalid/opf\">",
        )
        val wrongSpine = OPF_XML.replace(
            "<spine toc=\"ncx\"",
            "<spine xmlns=\"https://attacker.invalid/opf\" toc=\"ncx\"",
        )
        val wrongDc = OPF_XML.replace(
            "http://purl.org/dc/elements/1.1/",
            "https://attacker.invalid/dc",
        )
        val wrongNavRoot = NAV_BYTES.decodeToString().replaceFirst(
            "http://www.w3.org/1999/xhtml",
            "https://attacker.invalid/xhtml",
        )
        val wrongNav = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body><nav xmlns="https://attacker.invalid/xhtml" epub:type="toc">
                <a href="Text/chapter%201.xhtml">Spoofed TOC</a>
              </nav></body>
            </html>
        """.trimIndent()
        val wrongAnchor = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"
                  xmlns:evil="https://attacker.invalid/xhtml">
              <body><nav epub:type="toc">
                <evil:a href="Text/chapter%201.xhtml">Spoofed link</evil:a>
              </nav></body>
            </html>
        """.trimIndent()
        val wrongNcxRoot = NCX_BYTES.decodeToString().replaceFirst(
            "http://www.daisy.org/z3986/2005/ncx/",
            "https://attacker.invalid/ncx",
        )
        val wrongNcxPoint = """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
              <navMap><navPoint xmlns="https://attacker.invalid/ncx">
                <navLabel><text>Spoofed point</text></navLabel>
                <content src="Text/chapter%201.xhtml"/>
              </navPoint></navMap>
            </ncx>
        """.trimIndent()
        val wrongEncryptedData = """
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <EncryptedData xmlns="https://attacker.invalid/xmlenc">
                <EncryptionMethod Algorithm="https://example.test/drm"/>
                <CipherData><CipherReference URI="OPS/Text/chapter%201.xhtml"/></CipherData>
              </EncryptedData>
            </encryption>
        """.trimIndent()
        val invalidEntrySets = listOf(
            fixture().replaceBody("META-INF/container.xml", wrongContainerRoot.encodeToByteArray()),
            fixture().replaceBody("META-INF/container.xml", wrongRootfile.encodeToByteArray()),
            fixture().replaceBody("OPS/package.opf", wrongPackageRoot.encodeToByteArray()),
            fixture().replaceBody("OPS/package.opf", wrongManifest.encodeToByteArray()),
            fixture().replaceBody("OPS/package.opf", wrongSpine.encodeToByteArray()),
            fixture().replaceBody("OPS/package.opf", wrongDc.encodeToByteArray()),
            fixture().replaceBody("OPS/nav.xhtml", wrongNavRoot.encodeToByteArray()),
            fixture().replaceBody("OPS/nav.xhtml", wrongNav.encodeToByteArray()),
            fixture().replaceBody("OPS/nav.xhtml", wrongAnchor.encodeToByteArray()),
            fixture().replaceBody("OPS/toc.ncx", wrongNcxRoot.encodeToByteArray()),
            fixture().replaceBody("OPS/toc.ncx", wrongNcxPoint.encodeToByteArray()),
            fixture() + entry("META-INF/encryption.xml", wrongEncryptedData.encodeToByteArray()),
        )

        invalidEntrySets.forEach { entries ->
            val store = CountingContentBlobStore()
            assertFailsWith<EpubAcquisitionException.InvalidPackage> {
                EpubAcquisitionService(store, extractor(entries)).acquire(
                    EpubAcquisitionRequest(target(), "archive".encodeToByteArray()),
                )
            }
            assertEquals(0, store.delegate.currentGeneration)
            assertEquals(0, store.delegate.pendingReceiptCount)
            assertEquals(0, store.beginStageCount)
        }
    }

    @Test
    fun doctypeEntitiesInternalSubsetsAndOversizedIdentifiersNeverResolveOrPublish() {
        val maliciousNavigationDocuments = listOf(
            """
                <!DOCTYPE html [<!ENTITY xxe SYSTEM "file:///private/secret">]>
                <html><body><nav><a href="Text/chapter%201.xhtml">&xxe;</a></nav></body></html>
            """.trimIndent(),
            """
                <!DOCTYPE html SYSTEM "file:///private/secret">
                <html><body><nav><a href="Text/chapter%201.xhtml">&xxe;</a></nav></body></html>
            """.trimIndent(),
            "<!DOCTYPE html SYSTEM \"https://example.test/${"x".repeat(2_100)}\"><html/>",
        )

        maliciousNavigationDocuments.forEach { navigationXml ->
            val store = InMemoryContentBlobStore()
            val entries = fixture().map { archiveEntry ->
                if (archiveEntry.path == "OPS/nav.xhtml") {
                    entry(archiveEntry.path, navigationXml.encodeToByteArray())
                } else {
                    archiveEntry
                }
            }
            assertFailsWith<EpubAcquisitionException.InvalidPackage> {
                EpubAcquisitionService(store, extractor(entries)).acquire(
                    EpubAcquisitionRequest(target(), "archive".encodeToByteArray()),
                )
            }
            assertEquals(0, store.currentGeneration)
        }
    }

    @Test
    fun browserRequestKeepsPublisherCssAndUserStylesInSeparateLayers() = runTest {
        val store = InMemoryContentBlobStore()
        val result = EpubAcquisitionService(store, extractor(fixture())).acquire(
            EpubAcquisitionRequest(target(), "archive".encodeToByteArray()),
        )
        val representation = result.representation as ContentRepresentation.EpubSpine
        val scope = ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = result.publicationDraft.key,
            acquisitionId = result.acquisition.id,
            unitId = result.unit.key,
            contentRevision = result.manifest.contentRevision,
        )
        val navigation = EpubSpineNavigation(scope, representation)
        val request = EpubRenderRequestFactory(store).create(
            navigation = navigation,
            documentIndex = 0,
            userStyleSheets = listOf(EpubUserStyleSheet("reader-font", "body { font-size: 200%; }")),
        )

        assertContentEquals(CSS_BYTES, request.publisherStyleSheets.first { it.href.endsWith("main.css") }.bytes)
        assertEquals("body { font-size: 200%; }", request.userStyleSheets.single().css)
        assertFalse(request.securityPolicy.allowExternalNetwork)
        assertFalse(request.securityPolicy.allowScriptedContent)
        assertTrue(request.documentUrl.startsWith("shinsou-epub://publication/"))
        assertTrue(request.resourceByPublicationUrl(request.publicationUrl(request.document)) === request.document)

        var captured: EpubRenderRequest? = null
        val renderer = object : EpubBrowserRenderer {
            override suspend fun open(request: EpubRenderRequest): EpubBrowserRenderSession {
                captured = request
                return object : EpubBrowserRenderSession {
                    override var currentLocator: ReadingLocator.Epub = request.initialLocator
                    override suspend fun navigate(locator: ReadingLocator.Epub) {
                        currentLocator = locator
                    }
                    override fun close() = Unit
                }
            }
        }
        renderer.open(request).close()
        assertTrue(captured === request)
    }

    @Test
    fun browserRequestHydratesOnlyRequestedBodiesAndRechecksRightsForCachedReads() {
        val store = InMemoryContentBlobStore()
        val result = EpubAcquisitionService(store, extractor(fixture())).acquire(
            EpubAcquisitionRequest(target(), "archive".encodeToByteArray()),
        )
        val representation = result.representation as ContentRepresentation.EpubSpine
        val navigation = EpubSpineNavigation(
            ReadingScope(
                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                publicationId = result.publicationDraft.key,
                acquisitionId = result.acquisition.id,
                unitId = result.unit.key,
                contentRevision = result.manifest.contentRevision,
            ),
            representation,
        )
        var allowed = true
        var authorizedBodyReads = 0
        val request = EpubRenderRequestFactory(store).create(
            navigation = navigation,
            documentIndex = 0,
            resourceReadGate = EpubResourceReadGate { operation ->
                if (!allowed) null else operation().also { authorizedBodyReads++ }
            },
        )
        val resolver = EpubPublicationResourceResolver(request)

        assertEquals(0, authorizedBodyReads, "Creating a request must remain metadata-only")
        assertTrue(resolver.contains(request.documentUrl))
        assertEquals(0, authorizedBodyReads, "Navigation admission must not hydrate the document")
        requireNotNull(resolver.resolve(request.documentUrl))
        assertEquals(1, authorizedBodyReads)

        allowed = false
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(request.documentUrl)
        }
        assertEquals(1, authorizedBodyReads, "A cached body must still fail the current rights gate")
    }

    @Test
    fun largeArchiveSourcePublishesInBoundedChunksWithoutLegacyMaterialization() {
        val archive = ByteArray(200_000) { index -> (index % 251).toByte() }
        val source = RecordingArchiveSource(archive)
        var legacyExtractorCalled = false
        val extractor = object : EpubArchiveExtractor {
            override fun extract(archiveBytes: ByteArray, limits: EpubArchiveLimits): List<EpubArchiveEntry> {
                legacyExtractorCalled = true
                error("The source-aware extractor must not materialize the archive")
            }

            override fun extract(archive: EpubArchiveSource, limits: EpubArchiveLimits): List<EpubArchiveEntry> =
                fixture()
        }
        val store = InMemoryContentBlobStore()

        val result = EpubAcquisitionService(store, extractor).acquire(
            EpubAcquisitionRequest(target(), source),
        )

        assertFalse(legacyExtractorCalled)
        assertTrue(source.readSizes.size > 1)
        assertTrue(source.readSizes.all { it in 1..64 * 1024 })
        val representation = result.representation as ContentRepresentation.EpubSpine
        assertContentEquals(archive, store.read(representation.packageGraph.archive))
    }

    @Test
    fun oversizedArchiveSourceFailsBeforeReadOrBlobPublication() {
        var sourceReads = 0
        var extractorCalls = 0
        val source = object : EpubArchiveSource {
            override val byteSize: Long = EpubArchiveLimits().maximumArchiveBytes + 1
            override fun read(offset: Long, byteCount: Int): ByteArray {
                sourceReads++
                return ByteArray(byteCount)
            }
        }
        val store = InMemoryContentBlobStore()
        assertFailsWith<EpubAcquisitionException.InvalidArchive> {
            EpubAcquisitionService(
                store,
                EpubArchiveExtractor { _, _ ->
                    extractorCalls++
                    fixture()
                },
            ).acquire(EpubAcquisitionRequest(target(), source))
        }
        assertEquals(0, sourceReads)
        assertEquals(0, extractorCalls)
        assertEquals(0, store.currentGeneration)
    }

    @Test
    fun blobStoreBodyLimitIsPreflightedBeforePublishingTheArchive() {
        val store = InMemoryContentBlobStore(maximumBlobSizeBytes = 128)

        assertFailsWith<EpubAcquisitionException.InvalidArchive> {
            EpubAcquisitionService(store, extractor(fixture())).acquire(
                EpubAcquisitionRequest(target(), "archive".encodeToByteArray()),
            )
        }

        assertEquals(0, store.currentGeneration)
        assertEquals(0, store.pendingReceiptCount)
    }

    @Test
    fun unsafeArchivePathsAndExternalEntitiesFailBeforeBlobPublication() {
        val unsafeStore = InMemoryContentBlobStore()
        val unsafe = fixture().toMutableList().apply {
            add(entry("../private.txt", "secret".encodeToByteArray()))
        }
        assertFailsWith<EpubAcquisitionException.InvalidArchive> {
            EpubAcquisitionService(unsafeStore, extractor(unsafe)).acquire(
                EpubAcquisitionRequest(target(), "archive".encodeToByteArray()),
            )
        }
        assertEquals(0, unsafeStore.currentGeneration)

        val entityStore = InMemoryContentBlobStore()
        val withDoctype = fixture().map { archiveEntry ->
            if (archiveEntry.path == "META-INF/container.xml") {
                entry(
                    archiveEntry.path,
                    "<!DOCTYPE container [<!ENTITY xxe SYSTEM 'file:///private'>]>$CONTAINER_XML"
                        .encodeToByteArray(),
                )
            } else {
                archiveEntry
            }
        }
        assertFailsWith<EpubAcquisitionException.InvalidPackage> {
            EpubAcquisitionService(entityStore, extractor(withDoctype)).acquire(
                EpubAcquisitionRequest(target(), "archive".encodeToByteArray()),
            )
        }
        assertEquals(0, entityStore.currentGeneration)
    }

    @Test
    fun unsupportedEncryptionFailsClosedBeforeBlobPublication() {
        val store = InMemoryContentBlobStore()
        val encrypted = fixture() + entry(
            "META-INF/encryption.xml",
            """
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                    <EncryptionMethod Algorithm="https://example.test/drm"/>
                    <CipherData><CipherReference URI="OPS/Text/chapter%201.xhtml"/></CipherData>
                  </EncryptedData>
                </encryption>
            """.trimIndent().encodeToByteArray(),
        )

        val error = assertFailsWith<EpubAcquisitionException.UnsupportedEncryption> {
            EpubAcquisitionService(store, extractor(encrypted)).acquire(
                EpubAcquisitionRequest(target(), "archive".encodeToByteArray()),
            )
        }
        assertEquals(setOf("https://example.test/drm"), error.algorithms)
        assertEquals(0, store.currentGeneration)

        val supported = EpubAcquisitionService(
            blobStore = InMemoryContentBlobStore(),
            archiveExtractor = extractor(encrypted),
            policy = EpubAcquisitionPolicy(
                supportedEncryptionAlgorithms = setOf("https://example.test/drm"),
            ),
        ).acquire(EpubAcquisitionRequest(target(), "archive".encodeToByteArray()))
        val representation = supported.representation as ContentRepresentation.EpubSpine
        assertEquals(
            "https://example.test/drm",
            representation.packageGraph.encryption?.descriptors?.single()?.algorithm,
        )
    }

    @Test
    fun encryptionAttributeNamespaceCollisionsFailClosedBeforeBlobPublication() {
        val collisionDocuments = listOf(
            """
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#" xmlns:evil="https://attacker.invalid/xmlenc">
                    <EncryptionMethod evil:Algorithm="https://attacker.invalid/allowed"
                                      Algorithm="https://example.test/drm"/>
                    <CipherData><CipherReference URI="OPS/Text/chapter%201.xhtml"/></CipherData>
                  </EncryptedData>
                </encryption>
            """.trimIndent(),
            """
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#" xmlns:evil="https://attacker.invalid/xmlenc">
                    <EncryptionMethod Algorithm="https://example.test/drm"/>
                    <CipherData><CipherReference evil:URI="OPS/Text/fallback.xhtml"
                                                       URI="OPS/Text/chapter%201.xhtml"/></CipherData>
                  </EncryptedData>
                </encryption>
            """.trimIndent(),
        )

        collisionDocuments.forEach { encryptionXml ->
            val store = InMemoryContentBlobStore()
            val entries = fixture() + entry(
                "META-INF/encryption.xml",
                encryptionXml.encodeToByteArray(),
            )
            assertFailsWith<EpubAcquisitionException.InvalidPackage> {
                EpubAcquisitionService(store, extractor(entries)).acquire(
                    EpubAcquisitionRequest(target(), "archive".encodeToByteArray()),
                )
            }
            assertEquals(0, store.currentGeneration)
            assertEquals(0, store.pendingReceiptCount)
        }
    }

    @Test
    fun cssTokenizerIgnoresCommentsAndStringsWhileDecodingEscapedAndDataReferences() {
        val css = """
            /* url('../Images/ignored-comment.png') */
            p::before { content: "url('../Images/ignored-string.png')"; }
            @import "\74 heme.css";
            body { background: url("../Images/cover(1).png"); }
            @font-face { src: url('..\2f Fonts\2f book.woff2'); }
            aside { background: url(data:image/png;base64,AA==); }
        """.trimIndent().encodeToByteArray()
        val entries = fixture()
            .replaceBody("OPS/Styles/main.css", css) +
            entry("OPS/Images/cover(1).png", byteArrayOf(4, 5, 6))

        val result = EpubAcquisitionService(
            InMemoryContentBlobStore(),
            extractor(entries),
        ).acquire(EpubAcquisitionRequest(target(), "archive".encodeToByteArray()))
        val graph = result.metadata.cssDependencies.single { it.stylesheetHref == "OPS/Styles/main.css" }

        assertEquals(
            setOf(
                "theme.css",
                "../Images/cover(1).png",
                "../Fonts/book.woff2",
                "data:image/png;base64,AA==",
            ),
            graph.references.mapTo(linkedSetOf(), EpubCssReference::declaredReference),
        )
        assertEquals(
            setOf(
                "OPS/Styles/theme.css",
                "OPS/Images/cover%281%29.png",
                "OPS/Fonts/book.woff2",
            ),
            graph.references.mapNotNullTo(linkedSetOf(), EpubCssReference::resolvedHref),
        )
        val dataReference = graph.references.single { it.declaredReference.startsWith("data:") }
        assertTrue(dataReference.external)
        assertEquals(null, dataReference.resolvedHref)
    }

    @Test
    fun cssReferenceAndPersistedMetadataLimitsRejectBeforeAnyBlobPublication() {
        val oversizedReference = "body { background: url(\"${"x".repeat(4_097)}\"); }".encodeToByteArray()
        val oversizedMetadata = buildString {
            repeat(1_100) { index ->
                append(".n$index{background:url(\"")
                append("asset-$index-")
                append("x".repeat(3_900))
                append("\")}\n")
            }
        }.encodeToByteArray()

        listOf(oversizedReference, oversizedMetadata).forEach { css ->
            val store = InMemoryContentBlobStore()
            assertFailsWith<EpubAcquisitionException.InvalidPackage> {
                EpubAcquisitionService(
                    store,
                    extractor(fixture().replaceBody("OPS/Styles/main.css", css)),
                ).acquire(EpubAcquisitionRequest(target(), "archive".encodeToByteArray()))
            }
            assertEquals(0, store.currentGeneration)
            assertEquals(0, store.pendingReceiptCount)
        }
    }

    @Test
    fun compressionRatioLimitRejectsExpandedBombBeforePublication() {
        val store = InMemoryContentBlobStore()
        val bomb = fixture().map { archiveEntry ->
            if (archiveEntry.path == "OPS/Text/chapter 1.xhtml") {
                EpubArchiveEntry(archiveEntry.path, ByteArray(1_000) { 'x'.code.toByte() }, compressedSizeBytes = 1)
            } else {
                archiveEntry
            }
        }
        assertFailsWith<EpubAcquisitionException.InvalidArchive> {
            EpubAcquisitionService(
                blobStore = store,
                archiveExtractor = extractor(bomb),
                policy = EpubAcquisitionPolicy(
                    archiveLimits = EpubArchiveLimits(maximumCompressionRatio = 10.0),
                ),
            ).acquire(EpubAcquisitionRequest(target(), "archive".encodeToByteArray()))
        }
        assertEquals(0, store.currentGeneration)
    }

    private fun target(): LocalAcquisitionTarget = LocalAcquisitionTarget(
        publicationKey = PublicationKey("11111111-1111-4111-8111-111111111111"),
        publicationTitle = "EPUB Book",
        stableImportId = "fixtures/epub/book-1",
        unitTitle = "EPUB Unit",
        contentRevision = 2,
    )

    private fun extractor(entries: List<EpubArchiveEntry>): EpubArchiveExtractor =
        EpubArchiveExtractor { _, _ -> entries }

    private class RecordingArchiveSource(private val bytes: ByteArray) : EpubArchiveSource {
        val readSizes = ArrayList<Int>()
        override val byteSize: Long get() = bytes.size.toLong()
        override fun read(offset: Long, byteCount: Int): ByteArray {
            readSizes += byteCount
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + byteCount)
        }
    }

    private class CountingContentBlobStore(
        val delegate: InMemoryContentBlobStore = InMemoryContentBlobStore(),
    ) : ContentBlobStore by delegate {
        var beginStageCount: Int = 0
            private set

        override fun beginStage(expectedSizeBytes: Long?, mediaType: String): ContentBlobStage {
            beginStageCount += 1
            return delegate.beginStage(expectedSizeBytes, mediaType)
        }
    }
}

private fun fixture(): List<EpubArchiveEntry> = listOf(
    entry("mimetype", "application/epub+zip".encodeToByteArray()),
    entry("META-INF/container.xml", CONTAINER_XML.encodeToByteArray()),
    entry("OPS/package.opf", OPF_XML.encodeToByteArray()),
    entry("OPS/Text/chapter 1.xhtml", XHTML_BYTES),
    entry("OPS/Text/fallback.xhtml", XHTML_BYTES),
    entry("OPS/nav.xhtml", NAV_BYTES),
    entry("OPS/toc.ncx", NCX_BYTES),
    entry("OPS/Audio/chapter.smil", SMIL_BYTES),
    entry("OPS/Styles/main.css", CSS_BYTES),
    entry("OPS/Styles/theme.css", "p { line-height: 1.4; }".encodeToByteArray()),
    entry("OPS/Images/cover.png", byteArrayOf(0x01, 0x02, 0x03)),
    entry("OPS/Fonts/book.woff2", byteArrayOf(0x77, 0x4f, 0x46, 0x32)),
)

private fun entry(path: String, bytes: ByteArray): EpubArchiveEntry =
    EpubArchiveEntry(path, bytes, compressedSizeBytes = bytes.size.toLong().coerceAtLeast(1))

private fun List<EpubArchiveEntry>.replaceBody(path: String, bytes: ByteArray): List<EpubArchiveEntry> = map { entry ->
    if (entry.path == path) EpubArchiveEntry(path, bytes, bytes.size.toLong().coerceAtLeast(1)) else entry
}

private val XHTML_BYTES = """
    <html xmlns="http://www.w3.org/1999/xhtml">
      <head><link rel="stylesheet" href="../Styles/main.css"/></head>
      <body><p>Publisher document</p></body>
    </html>
""".trimIndent().encodeToByteArray()

private val NAV_BYTES = """
    <!DOCTYPE html>
    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
      <body><nav epub:type="toc"><ol><li><a href="Text/chapter%201.xhtml#start">Start</a></li></ol></nav></body>
    </html>
""".trimIndent().encodeToByteArray()

private val NCX_BYTES = """
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
      <navMap>
        <navPoint id="start"><navLabel><text>NCX Start</text></navLabel><content src="Text/chapter%201.xhtml#start"/></navPoint>
      </navMap>
    </ncx>
""".trimIndent().encodeToByteArray()

private val SMIL_BYTES = """
    <smil xmlns="http://www.w3.org/ns/SMIL"><body><seq/></body></smil>
""".trimIndent().encodeToByteArray()

private val CSS_BYTES = """
    @import url("theme.css");
    body { background-image: url('../Images/cover.png'); }
    @font-face { font-family: Book; src: url('../Fonts/book.woff2'); }
""".trimIndent().encodeToByteArray()

private const val CONTAINER_XML: String = """
    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
      <rootfiles>
        <rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/>
      </rootfiles>
    </container>
"""

private const val OPF_XML: String = """
    <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="book-id" version="3.0">
      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:identifier id="book-id">urn:uuid:book-1</dc:identifier>
        <dc:title>測試 😀</dc:title>
        <dc:language>zh-Hant</dc:language>
        <meta property="rendition:layout">reflowable</meta>
        <meta property="rendition:orientation">auto</meta>
        <meta property="rendition:spread">both</meta>
      </metadata>
      <manifest>
        <item id="chapter" href="Text/chapter%201.xhtml" media-type="application/xhtml+xml"
              fallback="fallback" media-overlay="overlay"/>
        <item id="fallback" href="Text/fallback.xhtml" media-type="application/xhtml+xml"/>
        <item id="overlay" href="Audio/chapter.smil" media-type="application/smil+xml"/>
        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
        <item id="main-css" href="Styles/main.css" media-type="text/css"/>
        <item id="theme-css" href="Styles/theme.css" media-type="text/css"/>
        <item id="cover" href="Images/cover.png" media-type="image/png" properties="cover-image"/>
        <item id="font" href="Fonts/book.woff2" media-type="font/woff2"/>
      </manifest>
      <spine toc="ncx" page-progression-direction="ltr">
        <itemref idref="chapter" properties="rendition:layout-pre-paginated page-spread-left"/>
      </spine>
    </package>
"""
