package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobSyncJobMutation
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentCommitResult
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentPublicationMutation
import dev.shinsou.kmp.content.ContentRightsGrantMutation
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.content.ContentTransactionFailurePoint
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.InMemorySharedContentTransactionStore
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.SharedContentTransactionStore
import dev.shinsou.kmp.content.SyncDraftContentOutboxAdapter
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.content.ContentRepresentation
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BlobReuploadRecoveryCoordinatorV2Test {
    @Test
    fun terminalResultBecomesDurableJobBeforeExactJournalCleanup() = runTest {
        val fixture = fixture()

        val result = fixture.coordinator().drainSlice(INSTANCE_ID, WORKSPACE_ID)

        assertEquals(BlobReuploadRecoveryStatusV2.JOB_COMMITTED, result.status)
        val job = fixture.transactions.pendingBlobSyncJobs().single()
        assertEquals(result.jobId, job.jobId)
        assertEquals(fixture.body, job.blob)
        assertEquals(fixture.owner, job.owner)
        assertTrue(job.jobId.startsWith("blob-reupload-recovery-v2:job:"))
        assertNull(fixture.journal.load(INSTANCE_ID, WORKSPACE_ID, fixture.body.blobId))
        assertEquals(
            BlobReuploadRecoveryStatusV2.NO_ACTION,
            fixture.coordinator().drainSlice(INSTANCE_ID, WORKSPACE_ID).status,
        )
    }

    @Test
    fun failedAtomicCommitRetainsTerminalAndLeavesNoPartialJob() = runTest {
        val fixture = fixture()
        fixture.transactions.failureInjection = ContentTransactionFailurePoint.AFTER_JOURNAL_WRITE

        assertFailsWith<IllegalStateException> {
            fixture.coordinator().drainSlice(INSTANCE_ID, WORKSPACE_ID)
        }

        assertTrue(fixture.transactions.pendingBlobSyncJobs().isEmpty())
        assertNotNull(fixture.journal.load(INSTANCE_ID, WORKSPACE_ID, fixture.body.blobId))
        fixture.transactions.failureInjection = null
        assertEquals(
            BlobReuploadRecoveryStatusV2.JOB_COMMITTED,
            fixture.coordinator().drainSlice(INSTANCE_ID, WORKSPACE_ID).status,
        )
    }

    @Test
    fun restartReplaysCommittedJobThenFinishesJournalCas() = runTest {
        val fixture = fixture(revivalTerminal = true)
        val durableJournal = fixture.journal
        val interruptedJournal = FailOnceRemoveJournal(durableJournal)

        assertFailsWith<IllegalStateException> {
            fixture.coordinator(interruptedJournal).drainSlice(INSTANCE_ID, WORKSPACE_ID)
        }

        val firstJob = fixture.transactions.pendingBlobSyncJobs().single()
        assertNotNull(durableJournal.load(INSTANCE_ID, WORKSPACE_ID, fixture.body.blobId))
        val recovered = fixture.coordinator(durableJournal).drainSlice(INSTANCE_ID, WORKSPACE_ID)
        assertEquals(BlobReuploadRecoveryStatusV2.COMMIT_REPLAYED, recovered.status)
        assertEquals(firstJob, fixture.transactions.pendingBlobSyncJobs().single())
        assertNull(durableJournal.load(INSTANCE_ID, WORKSPACE_ID, fixture.body.blobId))
    }

    @Test
    fun runtimeRightsDenialRetainsTerminalWithoutReadingOrQueuingBody() = runTest {
        val fixture = fixture(authorize = false)

        val result = fixture.coordinator().drainSlice(INSTANCE_ID, WORKSPACE_ID)

        assertEquals(BlobReuploadRecoveryStatusV2.RIGHTS_DENIED, result.status)
        assertTrue(fixture.transactions.pendingBlobSyncJobs().isEmpty())
        assertNotNull(fixture.journal.load(INSTANCE_ID, WORKSPACE_ID, fixture.body.blobId))
    }

    @Test
    fun missingLocalBodyRetainsTerminalAndNeverCreatesNetworkWork() = runTest {
        val fixture = fixture()
        val emptyBodyStore = InMemoryContentBlobStore()

        val result = fixture.coordinator(blobStore = emptyBodyStore)
            .drainSlice(INSTANCE_ID, WORKSPACE_ID)

        assertEquals(BlobReuploadRecoveryStatusV2.LOCAL_BODY_MISSING, result.status)
        assertTrue(fixture.transactions.pendingBlobSyncJobs().isEmpty())
        assertNotNull(fixture.journal.load(INSTANCE_ID, WORKSPACE_ID, fixture.body.blobId))
    }

    @Test
    fun deterministicCandidateSelectionSkipsDeniedOwnerForLaterAllowedReference() = runTest {
        val fixture = fixture()
        val originalAcquisition = fixture.publication.acquisitions.single()
        val originalUnit = originalAcquisition.units.single()
        val deniedAcquisition = Acquisition(
            id = DENIED_ACQUISITION_ID,
            origin = AcquisitionOrigin.LocalText,
            units = listOf(
                PublicationUnit(
                    key = DENIED_UNIT_KEY,
                    title = "Denied owner sorts first",
                    manifestRevisions = originalUnit.manifestRevisions,
                ),
            ),
            rightsGrantRef = DENIED_GRANT_ID,
        )
        val multiReference = fixture.publication.copy(
            acquisitions = listOf(deniedAcquisition, originalAcquisition),
        )

        val result = fixture.coordinator(
            publications = { listOf(multiReference) },
            authorizeSync = { job -> job.grantReference == GRANT_ID },
        ).drainSlice(INSTANCE_ID, WORKSPACE_ID)

        assertEquals(BlobReuploadRecoveryStatusV2.JOB_COMMITTED, result.status)
        val queued = fixture.transactions.pendingBlobSyncJobs().single()
        assertEquals(GRANT_ID, queued.grantReference)
        assertEquals(fixture.owner, queued.owner)
        assertNull(fixture.journal.load(INSTANCE_ID, WORKSPACE_ID, fixture.body.blobId))
    }

    private suspend fun fixture(
        revivalTerminal: Boolean = false,
        authorize: Boolean = true,
    ): Fixture {
        val blobStore = InMemoryContentBlobStore(clock = { 1_000 })
        val published = blobStore.put("still-local-after-worker-gc".encodeToByteArray(), "text/plain")
        val body = published.reference
        val owner = ContentManifestOwner(PUBLICATION_KEY, ACQUISITION_ID, UNIT_KEY)
        val manifest = ContentManifest(
            manifestId = CONTENT_MANIFEST_ID,
            schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
            contentRevision = 7,
            representations = listOf(
                ContentRepresentation.PlainText(
                    representationId = REPRESENTATION_ID,
                    resource = ResourceRef("body", body),
                    canonicalUtf16Length = 27,
                    blocks = listOf(TextBlock("body", 0, 27)),
                ),
            ),
        )
        val grant = RightsGrant(
            schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
            grantId = GRANT_ID,
            scope = RightsScope(PUBLICATION_KEY, ACQUISITION_ID),
            provenance = RightsProvenance.HostPolicy("local-import"),
            protectionScheme = ProtectionScheme.None,
            validFromEpochMillis = 0,
            validUntilEpochMillis = null,
            allowedOperations = setOf(ContentOperation.SYNC_BLOB),
        )
        val publication = Publication(
            key = PUBLICATION_KEY,
            title = "Recovery",
            acquisitions = listOf(
                Acquisition(
                    id = ACQUISITION_ID,
                    origin = AcquisitionOrigin.LocalText,
                    units = listOf(
                        PublicationUnit(
                            key = UNIT_KEY,
                            title = "Unit",
                            manifestRevisions = listOf(manifest),
                        ),
                    ),
                    rightsGrantRef = GRANT_ID,
                ),
            ),
        )
        val transactions = InMemorySharedContentTransactionStore(
            blobStore = blobStore,
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        transactions.commit(
            ContentCommitBatch(
                commitId = "fixture-install",
                receipts = listOf(published),
                attachments = listOf(ManifestAttachment(owner, manifest)),
                publications = listOf(ContentPublicationMutation(publication)),
                rightsGrants = listOf(ContentRightsGrantMutation(grant)),
            ),
        )
        val journal = InMemoryBlobLifecycleJournalV2().apply {
            save(terminal(body, revivalTerminal))
        }
        return Fixture(blobStore, transactions, journal, publication, owner, body, authorize)
    }

    private fun terminal(
        body: BlobRef,
        revivalTerminal: Boolean,
    ): DurableBlobLifecycleIntentV2.ReferenceTombstone {
        val handle = BlobTombstoneHandleV2(
            instanceId = INSTANCE_ID,
            workspaceId = WORKSPACE_ID,
            tombstoneId = TOMBSTONE_ID,
            blobId = body.blobId,
            manifestId = REMOTE_MANIFEST_ID,
            referenceThroughWorkspaceSeq = 1,
            requestedCreatedAtEpochMillis = 1_000,
            executeAfterEpochMillis = 2_000,
        )
        val checkpoint = RetainedCheckpointDescriptor(
            checkpointId = CHECKPOINT_ID,
            throughWorkspaceSeq = 1,
            keyEpoch = 1,
            ciphertextSha256Base64Url = HASH,
        )
        if (!revivalTerminal) {
            return DurableBlobLifecycleIntentV2.ReferenceTombstone(
                handle = handle,
                referenceCheckpoint = checkpoint,
                createdOnWorker = true,
                creationDisposition = BlobTombstoneDispositionV2.REUPLOAD_REQUIRED,
            )
        }
        val revival = BlobReferenceRevivalRequestV2(
            tombstoneId = TOMBSTONE_ID,
            blobId = body.blobId,
            manifestId = REMOTE_MANIFEST_ID,
            checkpointId = LIVE_CHECKPOINT_ID,
            checkpointCiphertextSha256Base64Url = HASH,
            throughWorkspaceSeq = 2,
            signatureBase64Url = SIGNATURE,
        )
        return DurableBlobLifecycleIntentV2.ReferenceTombstone(
            handle = handle,
            referenceCheckpoint = checkpoint,
            createdOnWorker = true,
            creationDisposition = BlobTombstoneDispositionV2.ACTIVE,
            revival = revival,
            revivalResult = BlobReferenceRevivalResultV2(
                tombstoneId = TOMBSTONE_ID,
                blobId = body.blobId,
                manifestId = REMOTE_MANIFEST_ID,
                disposition = BlobTombstoneDispositionV2.REUPLOAD_REQUIRED,
            ),
        )
    }

    private data class Fixture(
        val blobStore: InMemoryContentBlobStore,
        val transactions: InMemorySharedContentTransactionStore<SyncDraft>,
        val journal: InMemoryBlobLifecycleJournalV2,
        val publication: Publication,
        val owner: ContentManifestOwner,
        val body: BlobRef,
        val authorize: Boolean,
    ) {
        fun coordinator(
            lifecycleJournal: BlobLifecycleJournalV2 = journal,
            blobStore: InMemoryContentBlobStore = this.blobStore,
            publications: () -> List<Publication> = { listOf(publication) },
            authorizeSync: (ContentBlobSyncJobMutation) -> Boolean = { authorize },
        ): BlobReuploadRecoveryCoordinatorV2 = BlobReuploadRecoveryCoordinatorV2(
            contentStore = transactions,
            blobStore = blobStore,
            lifecycleJournal = lifecycleJournal,
            publications = publications,
            authorizeSync = authorizeSync,
        )
    }

    private class FailOnceRemoveJournal(
        private val delegate: BlobLifecycleJournalV2,
    ) : BlobLifecycleJournalV2 by delegate {
        private var fail = true

        override suspend fun remove(intent: DurableBlobLifecycleIntentV2): Boolean {
            if (fail) {
                fail = false
                throw IllegalStateException("simulated crash after durable content commit")
            }
            return delegate.remove(intent)
        }
    }

    private companion object {
        const val INSTANCE_ID = "81000000-0000-4000-8000-000000000001"
        const val WORKSPACE_ID = "81000000-0000-4000-8000-000000000002"
        const val ACQUISITION_ID = "81000000-0000-4000-8000-000000000004"
        const val CONTENT_MANIFEST_ID = "81000000-0000-4000-8000-000000000006"
        const val REPRESENTATION_ID = "81000000-0000-4000-8000-000000000007"
        const val REMOTE_MANIFEST_ID = "81000000-0000-4000-8000-000000000008"
        const val TOMBSTONE_ID = "81000000-0000-4000-8000-000000000009"
        const val CHECKPOINT_ID = "81000000-0000-4000-8000-00000000000a"
        const val LIVE_CHECKPOINT_ID = "81000000-0000-4000-8000-00000000000b"
        val HASH = "A".repeat(43)
        val SIGNATURE = "B".repeat(86)
        val PUBLICATION_KEY = PublicationKey("81000000-0000-4000-8000-000000000003")
        val UNIT_KEY = UnitKey(PUBLICATION_KEY, "81000000-0000-4000-8000-000000000005")
        val GRANT_ID = RightsGrantRef("81000000-0000-4000-8000-00000000000c")
        const val DENIED_ACQUISITION_ID = "81000000-0000-4000-8000-000000000001"
        val DENIED_UNIT_KEY = UnitKey(PUBLICATION_KEY, "81000000-0000-4000-8000-000000000002")
        val DENIED_GRANT_ID = RightsGrantRef("81000000-0000-4000-8000-00000000000d")
    }
}
