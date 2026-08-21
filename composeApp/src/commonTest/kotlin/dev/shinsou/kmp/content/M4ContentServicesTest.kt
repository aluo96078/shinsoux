package dev.shinsou.kmp.content

import dev.shinsou.kmp.annotation.AnnotationReanchorStatus
import dev.shinsou.kmp.annotation.ContentAnnotationKind
import dev.shinsou.kmp.annotation.ContentAnnotationState
import dev.shinsou.kmp.annotation.InMemoryContentAnnotationStore
import dev.shinsou.kmp.annotation.RightsEnforcedAnnotationService
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationDeniedException
import dev.shinsou.kmp.content.access.HostContentOperationGate
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.TextQuote
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.InMemoryRightsAuthority
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.search.CjkLatinFullTextTokenizer
import dev.shinsou.kmp.search.InMemoryDerivedLocalFullTextIndex
import dev.shinsou.kmp.search.SearchableTextDocument
import dev.shinsou.kmp.tts.PlatformSpeechRequest
import dev.shinsou.kmp.tts.PlatformSpeechResult
import dev.shinsou.kmp.tts.PlatformTextToSpeechEngine
import dev.shinsou.kmp.tts.RightsEnforcedTextToSpeechService
import dev.shinsou.kmp.tts.SpeakableTextDocument
import dev.shinsou.kmp.tts.SpeechPlaybackStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class M4ContentServicesTest {
    @Test
    fun hostGateFailsClosedAndRechecksRevocationBeforeSideEffects() {
        val fixture = fixture(setOf(ContentOperation.DISPLAY))
        var sideEffects = 0

        fixture.gate.execute(fixture.access, ContentOperation.DISPLAY) { sideEffects++ }
        assertEquals(1, sideEffects)

        fixture.authority.revoke(fixture.reference)
        assertFailsWith<ContentOperationDeniedException> {
            fixture.gate.execute(fixture.access, ContentOperation.DISPLAY) { sideEffects++ }
        }
        assertEquals(1, sideEffects)

        val missing = HostContentOperationGate(InMemoryRightsAuthority()) { 1 }
        assertFailsWith<ContentOperationDeniedException> {
            missing.execute(fixture.access.copy(grantReference = null), ContentOperation.DISPLAY) {
                sideEffects++
            }
        }
        assertEquals(1, sideEffects)
    }

    @Test
    fun derivedFtsTokenizesCjkAndLatinAndPurgesRevokedDocuments() {
        val fixture = fixture(setOf(ContentOperation.SEARCH_INDEX))
        val index = InMemoryDerivedLocalFullTextIndex(fixture.gate)
        val text = "Shinsou 搜尋功能支援 CJK and LATIN."
        val tokens = CjkLatinFullTextTokenizer.tokenize(text)
        assertTrue(tokens.any { it.value == "shinsou" && it.startUtf16 == 0 })
        assertTrue(tokens.any { it.value == "搜" })
        assertTrue(tokens.any { it.value == "cjk" })
        assertTrue(tokens.any { it.value == "latin" })

        index.upsert(
            SearchableTextDocument(
                documentId = "chapter-1",
                scope = fixture.readingScope,
                resourceId = "body",
                blockId = "paragraph-1",
                text = text,
                access = fixture.access,
            ),
        )
        val hit = index.search("搜尋 latin").single()
        assertEquals("chapter-1", hit.documentId)
        assertTrue(hit.snippet.contains("搜尋"))
        assertEquals(hit.locator.offset, hit.locator.resolveOffset(text))

        fixture.authority.revoke(fixture.reference)
        assertTrue(index.search("搜尋 latin").isEmpty())
        assertEquals(0, index.documentCount, "Revoked plaintext and tokens must be physically purged")
    }

    @Test
    fun searchSnippetLimitNeverSplitsASurrogatePair() {
        val fixture = fixture(setOf(ContentOperation.SEARCH_INDEX))
        val index = InMemoryDerivedLocalFullTextIndex(fixture.gate)
        val token = "a".repeat(255)
        index.upsert(
            SearchableTextDocument(
                documentId = "surrogate-snippet",
                scope = fixture.readingScope,
                resourceId = "body",
                blockId = "paragraph-1",
                text = "$token😀 tail",
                access = fixture.access,
            ),
        )

        val snippet = index.search(token).single().snippet
        assertEquals(token, snippet)
        assertFalse(snippet.last().isHighSurrogate())
    }

    @Test
    fun ttsSegmentsCarryLocatorsAndRevocationStopsBeforeNextNativeCall() = runTest {
        val fixture = fixture(setOf(ContentOperation.TTS))
        val engine = RecordingSpeechEngine {
            if (it == 1) fixture.authority.revoke(fixture.reference)
        }
        val service = RightsEnforcedTextToSpeechService(fixture.gate, engine)
        val text = buildString {
            repeat(20) { append("第一段文字需要穩定定位。Second sentence is also spoken. ") }
        }
        val document = SpeakableTextDocument(
            scope = fixture.readingScope,
            resourceId = "body",
            blockId = "paragraph-1",
            text = text,
            access = fixture.access,
        )
        val segments = service.segments(document, maxSegmentChars = 128)
        assertTrue(segments.size > 1)
        assertEquals(0, (segments.first().range.start as ReadingLocator.Text).offset)
        assertEquals(segments.first().text, segments.first().range.quote?.exact)

        assertFailsWith<ContentOperationDeniedException> {
            service.speak(document, maxSegmentChars = 128)
        }
        assertEquals(1, engine.requests.size)
        assertEquals(1, engine.stopCount)
    }

    @Test
    fun searchAndTtsLocatorsRetainNonZeroCanonicalBlockOffsets() {
        val fixture = fixture(setOf(ContentOperation.SEARCH_INDEX, ContentOperation.TTS))
        val blockText = "Alpha 😀 target sentence."
        val blockStartUtf16 = "Lead 😀\n\n".length
        val canonicalLength = blockStartUtf16 + blockText.length + "\nTail".length
        val expectedMatchOffset = blockStartUtf16 + blockText.indexOf("target")
        val index = InMemoryDerivedLocalFullTextIndex(fixture.gate)

        index.upsert(
            SearchableTextDocument(
                documentId = "chapter-1-paragraph-2",
                scope = fixture.readingScope,
                resourceId = "body",
                blockId = "paragraph-2",
                text = blockText,
                access = fixture.access,
                baseOffsetUtf16 = blockStartUtf16,
                canonicalDocumentUtf16Length = canonicalLength,
            ),
        )

        val hit = index.search("target").single()
        assertEquals(expectedMatchOffset, hit.locator.offset)
        assertEquals(expectedMatchOffset.toDouble() / canonicalLength, hit.locator.progression)

        val speech = RightsEnforcedTextToSpeechService(fixture.gate, RecordingSpeechEngine {})
        val segments = speech.segments(
            SpeakableTextDocument(
                scope = fixture.readingScope,
                resourceId = "body",
                blockId = "paragraph-2",
                text = blockText,
                access = fixture.access,
                baseOffsetUtf16 = blockStartUtf16,
                canonicalDocumentUtf16Length = canonicalLength,
            ),
            maxSegmentChars = 64,
        )
        val firstStart = segments.first().range.start as ReadingLocator.Text
        val finalEnd = segments.last().range.end as ReadingLocator.Text
        assertEquals(blockStartUtf16, firstStart.offset)
        assertEquals(blockStartUtf16.toDouble() / canonicalLength, firstStart.progression)
        assertEquals(blockStartUtf16 + blockText.length, finalEnd.offset)
        assertEquals((blockStartUtf16 + blockText.length).toDouble() / canonicalLength, finalEnd.progression)
    }

    @Test
    fun annotationReanchorsByQuoteAndTombstonesWhenQuoteDisappears() {
        val fixture = fixture(setOf(ContentOperation.ANNOTATE))
        val store = InMemoryContentAnnotationStore()
        var reconciliationSignals = 0
        val service = RightsEnforcedAnnotationService(fixture.gate, store) {
            reconciliationSignals++
        }
        val oldText = "alpha selected text omega"
        val start = oldText.indexOf("selected")
        val end = start + "selected text".length
        val range = textRange(fixture.readingScope, oldText, start, end)
        val created = service.create(
            annotationId = "77777777-7777-4777-8777-777777777777",
            kind = ContentAnnotationKind.NOTE,
            range = range,
            access = fixture.access,
            nowEpochMillis = 1,
            body = "Keep this note",
        )
        assertEquals(ContentAnnotationState.ACTIVE, created.state)

        val newScope = fixture.readingScope.copy(contentRevision = 1)
        val newAccess = fixture.access.copy(
            scope = fixture.access.scope.copy(contentRevision = 1),
        )
        admitGrant(fixture.authority, fixture.reference, newAccess.scope, setOf(ContentOperation.ANNOTATE))
        val reanchored = service.reanchorText(
            annotationId = created.annotationId,
            newScope = newScope,
            newText = "prefix alpha selected text omega suffix",
            access = newAccess,
            nowEpochMillis = 2,
        )
        assertEquals(AnnotationReanchorStatus.REANCHORED, reanchored.status)
        assertEquals(13, (reanchored.annotation.range.start as ReadingLocator.Text).offset)
        assertEquals(1, reanchored.annotation.scope.contentRevision)

        val nextScope = newScope.copy(contentRevision = 2)
        val nextAccess = newAccess.copy(scope = newAccess.scope.copy(contentRevision = 2))
        admitGrant(fixture.authority, fixture.reference, nextAccess.scope, setOf(ContentOperation.ANNOTATE))
        val tombstoned = service.reanchorText(
            annotationId = created.annotationId,
            newScope = nextScope,
            newText = "the selected passage is gone",
            access = nextAccess,
            nowEpochMillis = 3,
        )
        assertEquals(AnnotationReanchorStatus.TOMBSTONED, tombstoned.status)
        assertEquals(ContentAnnotationState.TOMBSTONE, tombstoned.annotation.state)
        assertFalse(store.list().any())
        assertEquals(1, store.list(includeTombstones = true).size)
        assertEquals(3, reconciliationSignals)
    }

    @Test
    fun rightsRevocationRedactsAnnotationBodyAndQuoteButRetainsTombstoneIdentity() {
        val fixture = fixture(setOf(ContentOperation.ANNOTATE))
        val store = InMemoryContentAnnotationStore()
        var reconciliationSignals = 0
        val service = RightsEnforcedAnnotationService(fixture.gate, store) {
            reconciliationSignals++
        }
        val text = "alpha selected omega"
        val created = service.create(
            annotationId = "88888888-8888-4888-8888-888888888888",
            kind = ContentAnnotationKind.NOTE,
            range = textRange(fixture.readingScope, text, 6, 14),
            access = fixture.access,
            nowEpochMillis = 1,
            body = "private note",
        )
        fixture.authority.revoke(fixture.reference)

        assertEquals(1, service.purgeRevokedDerivedData(fixture.readingScope, fixture.access, 2))
        val tombstone = requireNotNull(store.find(created.annotationId))
        assertEquals(ContentAnnotationState.TOMBSTONE, tombstone.state)
        assertEquals(null, tombstone.body)
        assertEquals(null, tombstone.range.quote)
        assertEquals(null, (tombstone.range.start as ReadingLocator.Text).quote)
        assertEquals(2, reconciliationSignals)
    }

    @Test
    fun deniedAnnotationMutationsDoNotRevealWhetherAnIdentityExists() {
        val fixture = fixture(setOf(ContentOperation.ANNOTATE))
        val store = InMemoryContentAnnotationStore()
        val service = RightsEnforcedAnnotationService(fixture.gate, store)
        val text = "alpha selected omega"
        val created = service.create(
            annotationId = "99999999-9999-4999-8999-999999999999",
            kind = ContentAnnotationKind.NOTE,
            range = textRange(fixture.readingScope, text, 6, 14),
            access = fixture.access,
            nowEpochMillis = 1,
            body = "private note",
        )
        fixture.authority.revoke(fixture.reference)
        val missingId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

        listOf(created.annotationId, missingId).forEach { annotationId ->
            assertFailsWith<ContentOperationDeniedException> {
                service.update(annotationId, fixture.access, 2, "changed", null)
            }
            assertFailsWith<ContentOperationDeniedException> {
                service.tombstone(annotationId, fixture.access, 2)
            }
            assertFailsWith<ContentOperationDeniedException> {
                service.reanchorText(
                    annotationId = annotationId,
                    newScope = fixture.readingScope,
                    newText = text,
                    access = fixture.access,
                    nowEpochMillis = 2,
                )
            }
        }

        assertEquals(created, store.find(created.annotationId))
    }

    private fun textRange(scope: ReadingScope, text: String, start: Int, end: Int): ReadingRange {
        val exact = text.substring(start, end)
        val quote = TextQuote(
            exact = exact,
            prefix = text.substring(0, start),
            suffix = text.substring(end),
        )
        return ReadingRange(
            start = ReadingLocator.Text(
                schemaVersion = scope.schemaVersion,
                scope = scope,
                resourceId = "body",
                blockId = "paragraph-1",
                offset = start,
                quote = quote,
            ),
            end = ReadingLocator.Text(
                schemaVersion = scope.schemaVersion,
                scope = scope,
                resourceId = "body",
                blockId = "paragraph-1",
                offset = end,
            ),
            quote = quote,
        )
    }

    private fun fixture(operations: Set<ContentOperation>): Fixture {
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        val unit = UnitKey(publication, "22222222-2222-4222-8222-222222222222")
        val rightsScope = RightsScope(
            publicationId = publication,
            acquisitionId = "33333333-3333-4333-8333-333333333333",
            unitId = unit,
            contentRevision = 0,
        )
        val readingScope = ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = publication,
            acquisitionId = rightsScope.acquisitionId,
            unitId = unit,
            contentRevision = 0,
        )
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
        val authority = InMemoryRightsAuthority()
        admitGrant(authority, reference, rightsScope, operations)
        return Fixture(
            authority = authority,
            reference = reference,
            gate = HostContentOperationGate(authority) { 1 },
            readingScope = readingScope,
            access = ContentAccessRequest(reference, rightsScope),
        )
    }

    private data class Fixture(
        val authority: InMemoryRightsAuthority,
        val reference: RightsGrantRef,
        val gate: HostContentOperationGate,
        val readingScope: ReadingScope,
        val access: ContentAccessRequest,
    )

    private fun admitGrant(
        authority: InMemoryRightsAuthority,
        reference: RightsGrantRef,
        scope: RightsScope,
        operations: Set<ContentOperation>,
    ) {
        authority.admit(
            RightsGrant(
                schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
                grantId = reference,
                scope = scope,
                provenance = RightsProvenance.HostPolicy("m4-test"),
                protectionScheme = ProtectionScheme.None,
                validFromEpochMillis = 0,
                validUntilEpochMillis = null,
                allowedOperations = operations,
            ),
        )
    }

    private class RecordingSpeechEngine(
        private val afterSpeak: (Int) -> Unit,
    ) : PlatformTextToSpeechEngine {
        val requests = mutableListOf<PlatformSpeechRequest>()
        var stopCount: Int = 0

        override suspend fun speak(request: PlatformSpeechRequest): PlatformSpeechResult {
            requests += request
            afterSpeak(requests.size)
            return PlatformSpeechResult(request.utteranceId, SpeechPlaybackStatus.COMPLETED)
        }

        override fun stop() {
            stopCount++
        }
    }
}
