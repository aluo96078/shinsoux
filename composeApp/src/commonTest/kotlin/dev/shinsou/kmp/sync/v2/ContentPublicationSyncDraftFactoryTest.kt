package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContentPublicationSyncDraftFactoryTest {
    @Test
    fun fullManifestAndRightsGraphSurvivesBoundedMultiEventRoundTrip() {
        val fixture = fixture(blockCount = 2_000)
        val plan = ContentPublicationSyncDraftFactory.build(
            publication = fixture.publication,
            rightsGrants = listOf(fixture.grant),
            operationNamespace = "large-typed-publication",
            createdAtMillis = 10,
        )

        assertTrue(plan.drafts.size > 1)
        assertTrue(plan.drafts.all(::isProtocolSizeSafe))
        var state = SyncState()
        plan.drafts.forEach { draft -> state = SyncReducer.reduce(state, draft.event) }

        val publicationRecord = assertNotNull(
            state.entities[SyncEntityKey.publication(fixture.publication.key.value)],
        )
        val acquisitionRecord = assertNotNull(
            state.entities[SyncEntityKey.acquisition(fixture.acquisition.id)],
        )
        val unitRecord = assertNotNull(
            state.entities[SyncEntityKey.publicationUnit(fixture.unit.key.value)],
        )
        val manifestRecord = assertNotNull(
            state.entities[SyncEntityKey.contentManifest(fixture.manifest.manifestId)],
        )

        assertEquals(fixture.publication.copy(acquisitions = emptyList()),
            ContentSyncDocumentCodec.decodePublication(publicationRecord.fields))
        assertEquals(fixture.acquisition.copy(units = emptyList()),
            ContentSyncDocumentCodec.decodeAcquisition(acquisitionRecord.fields))
        assertEquals(fixture.unit.copy(manifestRevisions = emptyList()),
            ContentSyncDocumentCodec.decodeUnit(unitRecord.fields))
        assertEquals(fixture.manifest, ContentSyncDocumentCodec.decodeManifest(manifestRecord.fields))
        assertEquals(fixture.grant, ContentSyncDocumentCodec.decodeRightsGrant(acquisitionRecord.fields))
    }

    @Test
    fun missingOrTamperedDocumentChunkFailsClosed() {
        val fixture = fixture(blockCount = 2_000)
        val plan = ContentPublicationSyncDraftFactory.build(
            fixture.publication,
            listOf(fixture.grant),
            "tamper-test",
            0,
        )
        var state = SyncState()
        plan.drafts.forEach { draft -> state = SyncReducer.reduce(state, draft.event) }
        val key = SyncEntityKey.contentManifest(fixture.manifest.manifestId)
        val record = assertNotNull(state.entities[key])
        val chunkKey = record.fields.keys.first { it.startsWith(ContentSyncFields.Manifest.DOCUMENT_CHUNK_PREFIX) }

        assertEquals(null, ContentSyncDocumentCodec.decodeManifest(record.fields - chunkKey))
        val tampered = record.fields + (chunkKey to requireNotNull(record.fields[chunkKey]).copy(
            value = SyncValue.StringValue("AAAA"),
        ))
        assertEquals(null, ContentSyncDocumentCodec.decodeManifest(tampered))
    }

    @Test
    fun largeChildIdentityProjectionIsChunkedAndRoundTripsWithinCanonicalBudget() {
        val publicationKey = PublicationKey("11111111-1111-4111-8111-111111111111")
        val acquisitions = List(500) { index ->
            Acquisition(
                id = portableUuid(index + 1),
                origin = AcquisitionOrigin.LocalText,
            )
        }
        val publication = Publication(publicationKey, "Many acquisitions", acquisitions)
        val plan = ContentPublicationSyncDraftFactory.build(
            publication = publication,
            rightsGrants = emptyList(),
            operationNamespace = "large-child-list",
            createdAtMillis = 0,
        )

        assertTrue(plan.drafts.all(::isProtocolSizeSafe))
        var state = SyncState()
        plan.drafts.forEach { state = SyncReducer.reduce(state, it.event) }
        val record = assertNotNull(state.entities[SyncEntityKey.publication(publicationKey.value)])
        val projection = assertNotNull(ContentSyncDocumentCodec.decodeProjection(
            record.fields,
            ContentSyncFields.Publication.PROJECTION_SHA256,
            ContentSyncFields.Publication.PROJECTION_CHUNK_COUNT,
            ContentSyncFields.Publication.PROJECTION_CHUNK_PREFIX,
        ))
        assertEquals(
            acquisitions.map(Acquisition::id).sorted(),
            (projection.getValue(ContentSyncFields.Publication.ACQUISITION_IDS) as
                SyncValue.StringListValue).value,
        )
        assertTrue(record.fields.keys.count {
            it.startsWith(ContentSyncFields.Publication.PROJECTION_CHUNK_PREFIX)
        } > 1)
    }
}

private fun isProtocolSizeSafe(draft: SyncDraft): Boolean {
    val plaintext = CanonicalSyncDraftPacker.encodedSize(draft.event)
    return plaintext <= CanonicalSyncDraftPacker.MAX_EVENT_PLAINTEXT_BYTES &&
        plaintext + CanonicalSyncDraftPacker.AEAD_TAG_BYTES <=
        CanonicalSyncDraftPacker.MAX_EVENT_CIPHERTEXT_BYTES
}

private fun portableUuid(index: Int): String =
    index.toString(16).padStart(8, '0') + "-0000-4000-8000-" +
        index.toString(16).padStart(12, '0')

private data class ContentSyncFixture(
    val publication: Publication,
    val acquisition: Acquisition,
    val unit: PublicationUnit,
    val manifest: ContentManifest,
    val grant: RightsGrant,
)

private fun fixture(blockCount: Int): ContentSyncFixture {
    val publicationKey = PublicationKey("11111111-1111-4111-8111-111111111111")
    val acquisitionId = "22222222-2222-4222-8222-222222222222"
    val unitKey = UnitKey(publicationKey, "33333333-3333-4333-8333-333333333333")
    val manifest = ContentManifest(
        manifestId = "44444444-4444-4444-8444-444444444444",
        schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
        contentRevision = 7,
        representations = listOf(
            ContentRepresentation.PlainText(
                representationId = "55555555-5555-4555-8555-555555555555",
                resource = ResourceRef(
                    id = "resource-document",
                    blob = BlobRef(
                        blobId = "66666666-6666-4666-8666-666666666666",
                        schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
                        digestAlgorithm = BlobRef.SHA_256,
                        plaintextDigest = "00".repeat(32),
                        byteSize = blockCount.toLong(),
                        mediaType = "text/plain",
                    ),
                ),
                canonicalUtf16Length = blockCount,
                sourceEncoding = "UTF-8",
                blocks = List(blockCount) { index ->
                    TextBlock("block-${index.toString().padStart(6, '0')}", index, index + 1)
                },
            ),
        ),
        declaredSizeBytes = blockCount.toLong(),
    )
    val grantRef = RightsGrantRef("77777777-7777-4777-8777-777777777777")
    val unit = PublicationUnit(unitKey, "Large document", listOf(manifest))
    val acquisition = Acquisition(
        id = acquisitionId,
        origin = AcquisitionOrigin.LocalText,
        units = listOf(unit),
        contentRevision = 7,
        rightsGrantRef = grantRef,
    )
    val publication = Publication(publicationKey, "Large document", listOf(acquisition))
    val grant = RightsGrant(
        schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
        grantId = grantRef,
        scope = RightsScope(publicationKey, acquisitionId),
        provenance = RightsProvenance.HostPolicy("test-local-owned"),
        protectionScheme = ProtectionScheme.None,
        validFromEpochMillis = 0,
        validUntilEpochMillis = null,
        allowedOperations = ContentOperation.entries.toSet(),
    )
    return ContentSyncFixture(publication, acquisition, unit, manifest, grant)
}
