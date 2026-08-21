package dev.shinsou.kmp.reader

import dev.shinsou.kmp.annotation.ContentAnnotationKind
import dev.shinsou.kmp.annotation.InMemoryContentAnnotationStore
import dev.shinsou.kmp.annotation.RightsEnforcedAnnotationService
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.EpubPackage
import dev.shinsou.kmp.content.EpubResource
import dev.shinsou.kmp.content.EpubSpineDocument
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationDeniedException
import dev.shinsou.kmp.content.access.HostContentOperationGate
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.InMemoryRightsAuthority
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.search.EpubSearchAnchor
import dev.shinsou.kmp.search.InMemoryDerivedLocalFullTextIndex
import dev.shinsou.kmp.search.SearchableTextDocument
import dev.shinsou.kmp.search.fullTextDocumentId
import dev.shinsou.kmp.tts.EpubSpeakableTextDocument
import dev.shinsou.kmp.tts.PlatformSpeechRequest
import dev.shinsou.kmp.tts.PlatformSpeechResult
import dev.shinsou.kmp.tts.PlatformTextToSpeechEngine
import dev.shinsou.kmp.tts.RightsEnforcedTextToSpeechService
import dev.shinsou.kmp.tts.SpeechPlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class EpubSemanticServicesTest {
    @Test
    fun rendererViewportAndSelectionShareExtractorHrefCfiAndQuoteIdentity() {
        val fixture = fixture()
        val semantic = EpubSemanticExtractor.extract(fixture.navigation, 0, XHTML)
        assertEquals("Alpha 搜尋 Beta\nGamma target", semantic.canonicalText)
        assertTrue("publisher-hidden" !in semantic.canonicalText)
        assertTrue("script-hidden" !in semantic.canonicalText)

        val request = EpubRenderRequest(
            navigation = fixture.navigation,
            documentIndex = 0,
            initialLocator = fixture.navigation.locatorAt(0),
            publisherResources = listOf(
                EpubRenderResource("chapter", "OPS/chapter.xhtml", "application/xhtml+xml", XHTML),
            ),
            semanticDocument = semantic,
        )
        val events = EpubViewportLocatorCoalescer(request)
        val scrolled = assertNotNull(events.offer(0.72, nowMillis = 1_000))
        assertEquals("OPS/chapter.xhtml", scrolled.resourceHref)
        assertNotEquals(fixture.navigation.startCfi(0), scrolled.cfi)
        assertNotNull(scrolled.quote)
        assertNull(events.offer(0.721, nowMillis = 1_030), "per-pixel events must be coalesced")

        val selection = assertNotNull(
            request.rangeForSelection(EpubBrowserSelectionSnapshot(0.72, "Gamma target")),
        )
        val selectionStart = assertIs<ReadingLocator.Epub>(selection.start)
        assertEquals(scrolled.resourceHref, selectionStart.resourceHref)
        assertEquals(semantic.blocks.last().cfiBase, selectionStart.cfi)
        assertEquals("Gamma target", selection.quote?.exact)

        val mixedContent = EpubSemanticExtractor.extract(
            fixture.navigation,
            0,
            "<html><head/><body><p><em>Alpha</em> tail</p></body></html>".encodeToByteArray(),
        )
        assertEquals("Alpha tail", mixedContent.canonicalText)
        assertEquals("epubcfi(/6/2!/4/2/3:0)", mixedContent.blocks.last().cfiBase)
    }

    @Test
    fun epubSearchTtsAndAnnotationUsePortableLocatorsAndFailClosedAfterRevocation() = runTest {
        val fixture = fixture(
            setOf(ContentOperation.SEARCH_INDEX, ContentOperation.TTS, ContentOperation.ANNOTATE),
        )
        val semantic = EpubSemanticExtractor.extract(fixture.navigation, 0, XHTML)
        val target = semantic.blocks.last()
        val targetText = semantic.canonicalText.substring(target.startUtf16, target.endUtf16)
        val index = InMemoryDerivedLocalFullTextIndex(fixture.gate)
        index.upsert(
            SearchableTextDocument(
                documentId = fullTextDocumentId(fixture.navigation.representationId, target.blockId),
                scope = fixture.navigation.scope,
                resourceId = semantic.resourceId,
                blockId = target.blockId,
                text = targetText,
                access = fixture.access,
                baseOffsetUtf16 = target.startUtf16,
                canonicalDocumentUtf16Length = semantic.canonicalText.length,
                epubAnchor = EpubSearchAnchor(
                    resourceHref = semantic.resourceHref,
                    cfiBase = target.cfiBase,
                    blockStartUtf16 = target.startUtf16,
                ),
            ),
        )
        val searchLocator = assertIs<ReadingLocator.Epub>(index.search("target").single().locator)
        assertEquals(semantic.resourceHref, searchLocator.resourceHref)
        assertTrue(searchLocator.cfi.startsWith(target.cfiBase.substringBeforeLast(':')))
        assertNotNull(searchLocator.quote)

        val engine = RecordingSpeechEngine()
        val speech = RightsEnforcedTextToSpeechService(fixture.gate, engine)
        val speechDocument = EpubSpeakableTextDocument(
            navigation = fixture.navigation,
            semanticDocument = semantic,
            block = target,
            access = fixture.access,
        )
        val segment = speech.segments(speechDocument, maxSegmentChars = 64).single()
        assertIs<ReadingLocator.Epub>(segment.range.start)
        assertEquals(target.cfiBase, (segment.range.start as ReadingLocator.Epub).cfi)
        assertNotNull(segment.range.quote)

        val annotationService = RightsEnforcedAnnotationService(
            fixture.gate,
            InMemoryContentAnnotationStore(),
        )
        val range = semantic.rangeForBlock(fixture.navigation, target)
        val annotation = annotationService.create(
            annotationId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            kind = ContentAnnotationKind.NOTE,
            range = range,
            access = fixture.access,
            nowEpochMillis = 1,
            body = "EPUB note",
        )
        assertIs<ReadingLocator.Epub>(annotation.range.start)
        assertNotNull(annotation.range.quote)

        fixture.authority.revoke(fixture.reference)
        assertTrue(index.search("target").isEmpty())
        assertFailsWith<ContentOperationDeniedException> { speech.speak(speechDocument, maxSegmentChars = 64) }
        assertFailsWith<ContentOperationDeniedException> {
            annotationService.create(
                annotationId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                kind = ContentAnnotationKind.NOTE,
                range = range,
                access = fixture.access,
                nowEpochMillis = 2,
                body = "denied",
            )
        }
        assertTrue(engine.requests.isEmpty())
    }

    @Test
    fun semanticExtractionRejectsMalformedAndOversizeAndObservesCancellation() {
        val fixture = fixture()
        assertFailsWith<IllegalArgumentException> {
            EpubSemanticExtractor.extract(
                fixture.navigation,
                0,
                "<html><body><p>broken</body></html>".encodeToByteArray(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EpubSemanticExtractor.extract(
                fixture.navigation,
                0,
                ByteArray(16 * 1024 * 1024 + 1),
            )
        }

        val large = buildString {
            append("<html><head></head><body>")
            repeat(20_000) { append("<p>Alpha 搜尋 Beta</p>") }
            append("</body></html>")
        }.encodeToByteArray()
        var checkpoints = 0
        assertFailsWith<CancellationException> {
            EpubSemanticExtractor.extract(fixture.navigation, 0, large) {
                if (++checkpoints == 8) throw CancellationException("cancel semantic extraction")
            }
        }
        assertEquals(8, checkpoints)
    }

    private fun fixture(
        operations: Set<ContentOperation> = setOf(
            ContentOperation.SEARCH_INDEX,
            ContentOperation.TTS,
            ContentOperation.ANNOTATE,
        ),
    ): Fixture {
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        val unit = UnitKey(publication, "22222222-2222-4222-8222-222222222222")
        val scope = ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = publication,
            acquisitionId = "33333333-3333-4333-8333-333333333333",
            unitId = unit,
            contentRevision = 1,
        )
        val packageResource = EpubResource(
            id = "package",
            href = "OPS/package.opf",
            resource = ResourceRef("package", emptyBlob("application/oebps-package+xml")),
        )
        val chapter = EpubResource(
            id = "chapter",
            href = "OPS/chapter.xhtml",
            resource = ResourceRef("chapter", emptyBlob("application/xhtml+xml")),
        )
        val representation = ContentRepresentation.EpubSpine(
            representationId = "44444444-4444-4444-8444-444444444444",
            packageGraph = EpubPackage(
                archive = emptyBlob("application/epub+zip"),
                packageDocumentId = packageResource.id,
                resources = listOf(packageResource, chapter),
            ),
            documents = listOf(EpubSpineDocument("spine", chapter.href, chapter.id)),
        )
        val rightsScope = RightsScope(
            publicationId = publication,
            acquisitionId = scope.acquisitionId,
            unitId = unit,
            contentRevision = scope.contentRevision,
        )
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
        val authority = InMemoryRightsAuthority().also { current ->
            current.admit(
                RightsGrant(
                    schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
                    grantId = reference,
                    scope = rightsScope,
                    provenance = RightsProvenance.HostPolicy("epub-semantic-test"),
                    protectionScheme = ProtectionScheme.None,
                    validFromEpochMillis = 0,
                    validUntilEpochMillis = null,
                    allowedOperations = operations,
                ),
            )
        }
        return Fixture(
            navigation = EpubSpineNavigation(scope, representation),
            authority = authority,
            reference = reference,
            gate = HostContentOperationGate(authority) { 1 },
            access = ContentAccessRequest(reference, rightsScope),
        )
    }

    private fun emptyBlob(mediaType: String): BlobRef = BlobRef(
        blobId = "66666666-6666-4666-8666-666666666666",
        schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
        digestAlgorithm = BlobRef.SHA_256,
        plaintextDigest = EMPTY_SHA256,
        byteSize = 0,
        mediaType = mediaType,
    )

    private data class Fixture(
        val navigation: EpubSpineNavigation,
        val authority: InMemoryRightsAuthority,
        val reference: RightsGrantRef,
        val gate: HostContentOperationGate,
        val access: ContentAccessRequest,
    )

    private class RecordingSpeechEngine : PlatformTextToSpeechEngine {
        val requests = mutableListOf<PlatformSpeechRequest>()

        override suspend fun speak(request: PlatformSpeechRequest): PlatformSpeechResult {
            requests += request
            return PlatformSpeechResult(request.utteranceId, SpeechPlaybackStatus.COMPLETED)
        }

        override fun stop() = Unit
    }

    private companion object {
        val XHTML: ByteArray = (
            "<html><head><style>publisher-hidden</style></head><body>" +
                "<p>Alpha 搜尋 <em>Beta</em></p>" +
                "<script>script-hidden</script><p>Gamma target</p>" +
                "</body></html>"
            ).encodeToByteArray()
        const val EMPTY_SHA256: String =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
