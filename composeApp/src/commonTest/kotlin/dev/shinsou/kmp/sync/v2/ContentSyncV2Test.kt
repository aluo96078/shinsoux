package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.annotation.ContentAnnotationKind
import dev.shinsou.kmp.annotation.ContentAnnotationState
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentSyncV2Test {
    @Test
    fun contentMutationsReduceDeterministicallyAndSurviveCheckpointRoundTrip() {
        val publicationKey = SyncEntityKey.publication(PUBLICATION_ID)
        val acquisitionKey = SyncEntityKey.acquisition(ACQUISITION_ID)
        val unitKey = SyncEntityKey.publicationUnit(UNIT_ID)
        val manifestKey = SyncEntityKey.contentManifest(MANIFEST_ID)
        val annotation = annotation()
        val blob = blob()
        val envelope1 = envelope(epoch = 1)
        val remote = remote(blob.blobId)
        val older = event(
            opId = OP_1,
            millis = 10,
            mutations = listOf(
                PublicationPatchV2(publicationKey, mapOf(
                    ContentSyncFields.Publication.TITLE to SyncValue.StringValue("Old title"),
                )),
                AcquisitionPatchV2(acquisitionKey, publicationKey, mapOf(
                    ContentSyncFields.Acquisition.AVAILABILITY to SyncValue.StringValue("complete"),
                )),
                PublicationUnitPatchV2(unitKey, acquisitionKey, mapOf(
                    ContentSyncFields.Unit.TITLE to SyncValue.StringValue("Chapter 1"),
                )),
                ContentManifestPatchV2(manifestKey, unitKey, mapOf(
                    ContentSyncFields.Manifest.CONTENT_REVISION to SyncValue.LongValue(1),
                )),
                ContentAnnotationPutV2(annotation),
                BlobReferenceCommitV2(blob, remote, envelope1),
            ),
        )
        val newer = event(
            opId = OP_2,
            millis = 20,
            mutations = listOf(
                PublicationPatchV2(publicationKey, mapOf(
                    ContentSyncFields.Publication.TITLE to SyncValue.StringValue("New title"),
                )),
            ),
        )

        val forward = SyncReducer.reduce(SyncReducer.reduce(SyncState(schemaVersion = 1), older), newer)
        val reverse = SyncReducer.reduce(SyncReducer.reduce(SyncState(schemaVersion = 1), newer), older)
        assertEquals(forward, reverse)
        assertEquals(SYNC_STATE_SCHEMA_VERSION, forward.schemaVersion)
        assertEquals(
            SyncValue.StringValue("New title"),
            forward.entities.getValue(publicationKey).fields
                .getValue(ContentSyncFields.Publication.TITLE).value,
        )
        assertEquals(publicationKey, (forward.entities.getValue(acquisitionKey).fields
            .getValue(ContentSyncFields.Acquisition.PUBLICATION_KEY).value as SyncValue.EntityKeyValue).value)
        assertTrue(forward.contentAnnotations.getValue(ANNOTATION_ID).isPresent)
        assertTrue(forward.blobReferences.getValue(blob.blobId).isRemotelyAvailable)

        val codec = DeterministicSyncEventCodec()
        val canonical = codec.canonicalCheckpointState(forward)
        assertEquals(forward.normalized(), codec.decodeCheckpointState(canonical))
    }

    @Test
    fun rewrapChainAndTombstonesConvergeWithoutDeletingRecoveryMetadata() {
        val blob = blob()
        val first = envelope(1)
        val committed = SyncReducer.reduce(
            SyncState(),
            event(OP_1, 1, listOf(BlobReferenceCommitV2(blob, remote(blob.blobId), first))),
        )
        val second = envelope(2, previous = first.envelopeSha256Base64Url)
        val rewrapped = SyncReducer.reduce(
            committed,
            event(
                OP_2,
                2,
                listOf(
                    BlobDekEnvelopeRewrappedV2(
                        blobId = blob.blobId,
                        manifestId = MANIFEST_ID,
                        envelope = second,
                        checkpointEvidence = BlobRewrapCheckpointEvidenceV2(
                            checkpointId = CHECKPOINT_ID,
                            checkpointCiphertextSha256Base64Url = HASH_B,
                            throughWorkspaceSeq = 12,
                        ),
                    ),
                ),
            ),
        )
        val tombstoned = SyncReducer.reduce(
            rewrapped,
            event(OP_3, 3, listOf(BlobReferencePresenceSetV2(blob.blobId, false))),
        )
        val record = tombstoned.blobReferences.getValue(blob.blobId)
        assertFalse(record.isRemotelyAvailable)
        assertEquals(setOf(1, 2), record.dekEnvelopes.keys)
        assertEquals(MANIFEST_ID, record.remoteManifest?.value?.manifestId)
    }

    @Test
    fun terminalEvidenceAtomicallyReincarnatesBlobAndRejectsArbitraryManifestSwap() {
        val blob = blob()
        val firstEnvelope = envelope(1)
        val generationOne = SyncReducer.reduce(
            SyncState(),
            event(OP_1, 1, listOf(BlobReferenceCommitV2(blob, remote(blob.blobId), firstEnvelope))),
        )
        val retainedEnvelope = envelope(2, firstEnvelope.envelopeSha256Base64Url)
        val withRetainedChain = SyncReducer.reduce(
            generationOne,
            event(
                OP_2,
                2,
                listOf(
                    BlobDekEnvelopeRewrappedV2(
                        blobId = blob.blobId,
                        manifestId = MANIFEST_ID,
                        envelope = retainedEnvelope,
                        checkpointEvidence = BlobRewrapCheckpointEvidenceV2(CHECKPOINT_ID, HASH_B, 2),
                    ),
                ),
            ),
        )
        val replacementRemote = remote(blob.blobId).copy(
            manifestId = NEW_MANIFEST_ID,
            commitReceiptId = NEW_RECEIPT_ID,
        )
        val replacementEnvelope = BlobDekEnvelopeV2(
            blobId = blob.blobId,
            keyEpoch = 2,
            nonceBase64Url = "Aw",
            wrappedDekBase64Url = "BA",
            envelopeSha256Base64Url = HASH_C,
        )
        val evidence = BlobReincarnationEvidenceV2(
            previousManifestId = MANIFEST_ID,
            tombstoneId = TOMBSTONE_ID,
            terminalKind = BlobReincarnationTerminalKindV2.REUPLOAD_REQUIRED,
        )
        val reincarnated = SyncReducer.reduce(
            withRetainedChain,
            event(
                OP_3,
                3,
                listOf(
                    BlobReferenceReincarnationCommitV2(
                        blob,
                        replacementRemote,
                        replacementEnvelope,
                        generation = 2,
                        evidence = evidence,
                    ),
                ),
            ),
        ).blobReferences.getValue(blob.blobId)

        assertEquals(2, reincarnated.generation)
        assertEquals(NEW_MANIFEST_ID, reincarnated.remoteManifest?.value?.manifestId)
        assertEquals(setOf(2), reincarnated.dekEnvelopes.keys)
        assertEquals(replacementEnvelope, reincarnated.dekEnvelopes.getValue(2).value)
        assertTrue(reincarnated.isRemotelyAvailable)

        assertFailsWith<SyncInvariantViolation> {
            SyncReducer.reduce(
                withRetainedChain,
                event(
                    "arbitrary-swap",
                    4,
                    listOf(BlobReferenceCommitV2(blob, replacementRemote, replacementEnvelope)),
                ),
            )
        }
        assertFailsWith<SyncInvariantViolation> {
            SyncReducer.reduce(
                withRetainedChain,
                event(
                    "wrong-evidence",
                    4,
                    listOf(
                        BlobReferenceReincarnationCommitV2(
                            blob,
                            replacementRemote,
                            replacementEnvelope,
                            2,
                            evidence.copy(previousManifestId = OTHER_MANIFEST_ID),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun annotationTombstoneWinsButRetainsPortableAnchor() {
        val active = annotation()
        val tombstone = active.copy(
            state = ContentAnnotationState.TOMBSTONE,
            updatedAtEpochMillis = 20,
            tombstoneReason = "deleted",
        )
        val state = SyncReducer.reduce(
            SyncReducer.reduce(SyncState(), event(OP_1, 1, listOf(ContentAnnotationPutV2(active)))),
            event(OP_2, 2, listOf(ContentAnnotationPutV2(tombstone))),
        )
        val record = state.contentAnnotations.getValue(ANNOTATION_ID)
        assertFalse(record.isPresent)
        assertEquals(tombstone.range, record.annotation?.value?.range)
    }

    private fun event(opId: String, millis: Long, mutations: List<SyncMutation>): SyncEvent = SyncEvent(
        opId = opId,
        hlc = HlcTimestamp(millis, 0, DEVICE_ID),
        mutations = mutations,
        schemaVersion = 2,
    )

    private fun annotation(): ContentAnnotation {
        val publication = PublicationKey(PUBLICATION_ID)
        val scope = ReadingScope(
            schemaVersion = 1,
            publicationId = publication,
            acquisitionId = ACQUISITION_ID,
            unitId = UnitKey(publication, UNIT_ID),
            contentRevision = 1,
        )
        val locator = ReadingLocator.Image(
            schemaVersion = 1,
            scope = scope,
            pageResourceId = "page-1",
            pageIndexHint = 0,
        )
        return ContentAnnotation(
            schemaVersion = 1,
            annotationId = ANNOTATION_ID,
            kind = ContentAnnotationKind.BOOKMARK,
            range = ReadingRange(locator, locator),
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 10,
        )
    }

    private fun blob(): BlobRef = BlobRef(
        blobId = BLOB_ID,
        schemaVersion = 1,
        digestAlgorithm = "SHA-256",
        plaintextDigest = "0".repeat(64),
        byteSize = 0,
        mediaType = "application/octet-stream",
    )

    private fun envelope(epoch: Int, previous: String? = null): BlobDekEnvelopeV2 = BlobDekEnvelopeV2(
        blobId = BLOB_ID,
        keyEpoch = epoch,
        nonceBase64Url = "AQ",
        wrappedDekBase64Url = "Ag",
        envelopeSha256Base64Url = if (epoch == 1) HASH_A else HASH_B,
        previousEnvelopeSha256Base64Url = previous,
    )

    private fun remote(blobId: String): RemoteBlobBodyManifestRefV2 = RemoteBlobBodyManifestRefV2(
        manifestId = MANIFEST_ID,
        blobId = blobId,
        manifestCiphertextSha256Base64Url = HASH_A,
        manifestCiphertextByteSize = 16,
        bodyCiphertextByteSize = 0,
        chunkCount = 0,
        chunkSizeBytes = 64 * 1024,
        committedAtEpochMillis = 1,
        commitReceiptId = RECEIPT_ID,
    )

    companion object {
        private const val DEVICE_ID = "30000000-0000-4000-8000-000000000001"
        private const val PUBLICATION_ID = "30000000-0000-4000-8000-000000000002"
        private const val ACQUISITION_ID = "30000000-0000-4000-8000-000000000003"
        private const val UNIT_ID = "30000000-0000-4000-8000-000000000004"
        private const val MANIFEST_ID = "30000000-0000-4000-8000-000000000005"
        private const val ANNOTATION_ID = "30000000-0000-4000-8000-000000000006"
        private const val BLOB_ID = "30000000-0000-4000-8000-000000000007"
        private const val RECEIPT_ID = "30000000-0000-4000-8000-000000000008"
        private const val CHECKPOINT_ID = "30000000-0000-4000-8000-000000000009"
        private const val NEW_MANIFEST_ID = "30000000-0000-4000-8000-00000000000a"
        private const val NEW_RECEIPT_ID = "30000000-0000-4000-8000-00000000000b"
        private const val TOMBSTONE_ID = "30000000-0000-4000-8000-00000000000c"
        private const val OTHER_MANIFEST_ID = "30000000-0000-4000-8000-00000000000d"
        private const val OP_1 = "30000000-0000-4000-8000-000000000010"
        private const val OP_2 = "30000000-0000-4000-8000-000000000011"
        private const val OP_3 = "30000000-0000-4000-8000-000000000012"
        private const val HASH_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        private const val HASH_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        private const val HASH_C = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"
    }
}
