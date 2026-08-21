package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.ContentBlobSyncJobMutation
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentCommitResult
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentReplicaAuthority
import dev.shinsou.kmp.content.ContentReplicaAuthorityDepartureResult
import dev.shinsou.kmp.content.SharedContentTransactionStore
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.content.access.ContentOperationDeniedException
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsOperationContext
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.sync.crypto.SodiumBlobBodyCryptoV2
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class BlobBodyPlaneTest {
    @Test
    fun clientMatchesWorkerChunkAndCiphertextBoundaries() {
        assertEquals(1_024, MAX_BLOB_BODY_CHUNKS)
        val nearBoundary = MAX_BLOB_BODY_CIPHERTEXT_BYTES - 1_000
        val selected = selectBlobUploadChunkSize(nearBoundary, DEFAULT_BLOB_BODY_CHUNK_BYTES)
        val count = (nearBoundary + selected - 1) / selected
        assertTrue(selected > DEFAULT_BLOB_BODY_CHUNK_BYTES)
        assertTrue(count <= MAX_BLOB_BODY_CHUNKS)
        assertTrue(nearBoundary + count * BLOB_AEAD_TAG_BYTES <= MAX_BLOB_BODY_CIPHERTEXT_BYTES)
        assertFailsWith<IllegalArgumentException> {
            selectBlobUploadChunkSize(MAX_BLOB_BODY_CIPHERTEXT_BYTES)
        }
    }

    @Test
    fun pendingIntentCannotCrossWorkspaceForTheSameBlob() = runTest {
        val sourceStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val published = sourceStore.put("authority-scoped-pending".encodeToByteArray(), "text/plain")
        val secrets = InMemorySyncSecretStore().apply {
            listOf(WORKSPACE_ID, FOREIGN_WORKSPACE_ID).forEachIndexed { index, workspaceId ->
                write(
                    SyncSecretKey.WorkspaceEpochKey(workspaceId, 1),
                    SecretMaterial(ByteArray(32) { (it + index + 1).toByte() }.asList()),
                )
            }
        }
        val journal = InMemoryBlobTransferJournalV2()
        val failingApi = FakeBlobBodyApi().apply { failReservations = true }
        val firstUploader = EncryptedBlobUploaderV2(
            sourceStore,
            failingApi,
            SodiumBlobBodyCryptoV2(secrets),
            journal,
            AllowingGate,
            { 1_000 },
        )
        assertFailsWith<IllegalStateException> {
            firstUploader.upload(
                session(WORKSPACE_ID),
                capability(WORKSPACE_ID),
                published.reference,
                access(),
                64 * 1024,
            )
        }
        val firstKey = BlobTransferKeyV2(INSTANCE_ID, WORKSPACE_ID, published.reference.blobId, 1)
        val foreignKey = BlobTransferKeyV2(
            INSTANCE_ID,
            FOREIGN_WORKSPACE_ID,
            published.reference.blobId,
            1,
        )
        assertTrue(journal.loadIntent(firstKey) != null)
        assertTrue(journal.loadIntent(foreignKey) == null)

        val foreignApi = FakeBlobBodyApi()
        EncryptedBlobUploaderV2(
            sourceStore,
            foreignApi,
            SodiumBlobBodyCryptoV2(secrets),
            journal,
            AllowingGate,
            { 2_000 },
        ).upload(
            session(FOREIGN_WORKSPACE_ID),
            capability(FOREIGN_WORKSPACE_ID),
            published.reference,
            access(),
            64 * 1024,
        )

        assertEquals(1, failingApi.reservations)
        assertEquals(1, foreignApi.reservations)
        assertTrue(journal.loadIntent(firstKey) != null)
        assertTrue(journal.loadCommitted(firstKey) == null)
        assertTrue(journal.loadCommitted(foreignKey) != null)
    }

    @Test
    fun committedReceiptCannotCrossWorkspaceForTheSameBlob() = runTest {
        val sourceStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val published = sourceStore.put("authority-scoped-committed".encodeToByteArray(), "text/plain")
        val secrets = InMemorySyncSecretStore().apply {
            listOf(WORKSPACE_ID, FOREIGN_WORKSPACE_ID).forEachIndexed { index, workspaceId ->
                write(
                    SyncSecretKey.WorkspaceEpochKey(workspaceId, 1),
                    SecretMaterial(ByteArray(32) { (it + index + 5).toByte() }.asList()),
                )
            }
        }
        val journal = InMemoryBlobTransferJournalV2()
        val firstApi = FakeBlobBodyApi()
        val first = EncryptedBlobUploaderV2(
            sourceStore,
            firstApi,
            SodiumBlobBodyCryptoV2(secrets),
            journal,
            AllowingGate,
            { 1_000 },
        ).upload(
            session(WORKSPACE_ID),
            capability(WORKSPACE_ID),
            published.reference,
            access(),
            64 * 1024,
        )
        val foreignApi = FakeBlobBodyApi()
        val foreign = EncryptedBlobUploaderV2(
            sourceStore,
            foreignApi,
            SodiumBlobBodyCryptoV2(secrets),
            journal,
            AllowingGate,
            { 2_000 },
        ).upload(
            session(FOREIGN_WORKSPACE_ID),
            capability(FOREIGN_WORKSPACE_ID),
            published.reference,
            access(),
            64 * 1024,
        )

        assertEquals(1, firstApi.reservations)
        assertEquals(1, foreignApi.reservations)
        assertEquals(WORKSPACE_ID, first.transferKey.workspaceId)
        assertEquals(FOREIGN_WORKSPACE_ID, foreign.transferKey.workspaceId)
        assertTrue(first.dekEnvelope != foreign.dekEnvelope)
        assertTrue(journal.loadCommitted(first.transferKey) != null)
        assertTrue(journal.loadCommitted(foreign.transferKey) != null)
    }

    @Test
    fun encryptedUploadResumesAndDownloadPublishesOnlyVerifiedPlaintext() = runTest {
        val bytes = ByteArray(96 * 1024) { index -> (index * 31).toByte() }
        val sourceStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 2 * 1024 * 1024)
        val published = sourceStore.put(bytes, "text/plain")
        val secretStore = InMemorySyncSecretStore().apply {
            write(
                SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 1),
                SecretMaterial(ByteArray(32) { (it + 1).toByte() }.asList()),
            )
        }
        val crypto = SodiumBlobBodyCryptoV2(secretStore)
        val api = FakeBlobBodyApi()
        val journal = InMemoryBlobTransferJournalV2()
        val uploader = EncryptedBlobUploaderV2(
            blobStore = sourceStore,
            bodyApi = api,
            crypto = crypto,
            journal = journal,
            operationGate = AllowingGate,
            nowEpochMillis = { 1_000 },
        )
        val committed = uploader.upload(
            session = session(),
            capability = capability(),
            blob = published.reference,
            access = access(),
            chunkSizeBytes = 64 * 1024,
        )
        assertEquals(2, api.uploadedChunks)
        assertEquals(published.reference.blobId, committed.remoteManifest.blobId)
        assertTrue(api.storedManifest.encryptedPrivateManifest.ciphertextBase64Url
            .encodeToByteArray().contentEquals(bytes).not())

        // A durable commit is idempotent and does not re-reserve or re-upload body chunks.
        uploader.upload(session(), capability(), published.reference, access(), 64 * 1024)
        assertEquals(1, api.reservations)
        assertEquals(2, api.uploadedChunks)

        val timestamp = HlcTimestamp(2_000, 0, DEVICE_ID)
        val synced = SyncedBlobReferenceRecord(
            blobId = published.reference.blobId,
            blob = LwwRegister(published.reference, timestamp),
            remoteManifest = LwwRegister(committed.remoteManifest, timestamp),
            dekEnvelopes = mapOf(1 to LwwRegister(committed.dekEnvelope, timestamp)),
            presence = LwwRegister(true, timestamp),
        )
        val destination = InMemoryContentBlobStore(maximumBlobSizeBytes = 2 * 1024 * 1024)
        val receipt = EncryptedBlobDownloaderV2(destination, api, crypto, AllowingGate).download(
            session(),
            capability(),
            synced,
            access(),
        )
        assertEquals(published.reference, receipt.reference)
        assertContentEquals(bytes, destination.read(receipt.reference))
    }

    @Test
    fun uploadPlanningYieldsSoForegroundCancellationStopsBeforeRemoteReservation() = runTest {
        val bytes = ByteArray(3 * 64 * 1024) { index -> (index * 13).toByte() }
        val sourceStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 2 * 1024 * 1024)
        val published = sourceStore.put(bytes, "text/plain")
        val secrets = InMemorySyncSecretStore().apply {
            write(
                SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 1),
                SecretMaterial(ByteArray(32) { (it + 1).toByte() }.asList()),
            )
        }
        val api = FakeBlobBodyApi()
        val uploader = EncryptedBlobUploaderV2(
            blobStore = sourceStore,
            bodyApi = api,
            crypto = SodiumBlobBodyCryptoV2(secrets),
            journal = InMemoryBlobTransferJournalV2(),
            operationGate = AllowingGate,
            nowEpochMillis = { 1_000 },
        )

        val upload = launch(start = CoroutineStart.UNDISPATCHED) {
            uploader.upload(
                session(),
                capability(),
                published.reference,
                access(),
                chunkSizeBytes = 64 * 1024,
            )
        }

        // The first explicit per-chunk yield returns control before planning can reserve remote
        // quota. This is the same cancellation edge used when the app returns to foreground.
        assertTrue(upload.isActive)
        assertEquals(0, api.reservations)
        upload.cancelAndJoin()
        assertTrue(upload.isCancelled)
        assertEquals(0, api.reservations)
    }

    @Test
    fun cancellationObservedAfterSynchronousChunkCryptoPreventsUploadIo() = runTest {
        val bytes = ByteArray(3 * 64 * 1024) { index -> (index * 19).toByte() }
        val sourceStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 2 * 1024 * 1024)
        val published = sourceStore.put(bytes, "text/plain")
        val secrets = InMemorySyncSecretStore().apply {
            write(
                SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 1),
                SecretMaterial(ByteArray(32) { (it + 3).toByte() }.asList()),
            )
        }
        val api = FakeBlobBodyApi()
        val crypto = CancellingAfterChunkCrypto(
            delegate = SodiumBlobBodyCryptoV2(secrets),
            cancelAfterEncryption = 4, // Three planning chunks, then the first upload-pass chunk.
        )
        val uploader = EncryptedBlobUploaderV2(
            blobStore = sourceStore,
            bodyApi = api,
            crypto = crypto,
            journal = InMemoryBlobTransferJournalV2(),
            operationGate = AllowingGate,
            nowEpochMillis = { 1_000 },
        )

        val upload = async {
            uploader.upload(
                session(),
                capability(),
                published.reference,
                access(),
                chunkSizeBytes = 64 * 1024,
            )
        }

        assertFailsWith<CancellationException> { upload.await() }
        assertEquals(1, api.reservations)
        assertEquals(0, api.uploadedChunks)
    }

    @Test
    fun tamperedCiphertextFailsBeforeLocalPublication() = runTest {
        val bytes = ByteArray(70 * 1024) { it.toByte() }
        val sourceStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val published = sourceStore.put(bytes)
        val secrets = InMemorySyncSecretStore().apply {
            write(
                SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 1),
                SecretMaterial(ByteArray(32) { 7 }.asList()),
            )
        }
        val crypto = SodiumBlobBodyCryptoV2(secrets)
        val api = FakeBlobBodyApi()
        val committed = EncryptedBlobUploaderV2(
            sourceStore,
            api,
            crypto,
            InMemoryBlobTransferJournalV2(),
            AllowingGate,
            { 1 },
        ).upload(session(), capability(), published.reference, access(), 64 * 1024)
        api.tamperNextDownload = true
        val timestamp = HlcTimestamp(3, 0, DEVICE_ID)
        val synced = SyncedBlobReferenceRecord(
            blobId = published.reference.blobId,
            blob = LwwRegister(published.reference, timestamp),
            remoteManifest = LwwRegister(committed.remoteManifest, timestamp),
            dekEnvelopes = mapOf(1 to LwwRegister(committed.dekEnvelope, timestamp)),
            presence = LwwRegister(true, timestamp),
        )
        val destination = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        assertFailsWith<IllegalArgumentException> {
            EncryptedBlobDownloaderV2(destination, api, crypto, AllowingGate)
                .download(session(), capability(), synced, access())
        }
        assertTrue(!destination.contains(published.reference))
    }

    @Test
    fun bodyCoordinatorReplaysCommittedUploadAfterLocalDraftCrash() = runTest {
        val localDelegate = InMemoryLocalSyncStore()
        val local = FailOnceLocalStore(localDelegate)
        val fixture = coordinatorFixture(localStore = local)
        val coordinator = fixture.coordinator()

        val interrupted = coordinator.drain(session(), capability())
        assertEquals(1, interrupted.uploaded)
        assertEquals(1, interrupted.failed)
        assertEquals(1, fixture.api.reservations)
        assertEquals(1, fixture.content.pendingBlobSyncJobs().size)
        assertTrue(fixture.journal.loadCommitted(fixture.transferKey) != null)
        assertTrue(localDelegate.readState().drafts.isEmpty())

        val replay = coordinator.drain(session(), capability())

        assertEquals(0, replay.uploaded)
        assertEquals(1, replay.replayed)
        assertEquals(1, replay.acknowledged)
        assertEquals(1, fixture.api.reservations)
        assertTrue(fixture.content.pendingBlobSyncJobs().isEmpty())
        assertEquals(1, localDelegate.readState().drafts.size)
        assertTrue(fixture.journal.loadCommitted(fixture.transferKey) == null)
        assertTrue(fixture.journal.loadIntent(fixture.transferKey) == null)
    }

    @Test
    fun bodyCoordinatorReplaysLocalMutationAfterContentAckCrash() = runTest {
        val local = InMemoryLocalSyncStore()
        val fixture = coordinatorFixture(localStore = local, failFirstAcknowledgement = true)
        val coordinator = fixture.coordinator()

        val interrupted = coordinator.drain(session(), capability())
        assertEquals(1, interrupted.uploaded)
        assertEquals(1, interrupted.failed)
        assertEquals(1, fixture.content.pendingBlobSyncJobs().size)
        assertEquals(1, local.readState().drafts.size)
        assertTrue(fixture.journal.loadCommitted(fixture.transferKey) != null)

        val replay = coordinator.drain(session(), capability())

        assertEquals(1, replay.replayed)
        assertEquals(1, replay.acknowledged)
        assertEquals(1, fixture.api.reservations)
        assertEquals(1, local.readState().drafts.size)
        assertTrue(fixture.content.pendingBlobSyncJobs().isEmpty())
        assertTrue(fixture.journal.committedKeys(INSTANCE_ID, WORKSPACE_ID).isEmpty())
    }

    @Test
    fun bodyCoordinatorFinishesJournalCleanupAfterJobAckCrash() = runTest {
        val local = InMemoryLocalSyncStore()
        val durableJournal = InMemoryBlobTransferJournalV2()
        val failingJournal = FailOnceCleanupJournal(durableJournal)
        val fixture = coordinatorFixture(localStore = local, journal = failingJournal)
        val coordinator = fixture.coordinator()

        val interrupted = coordinator.drain(session(), capability())
        assertEquals(1, interrupted.uploaded)
        assertEquals(1, interrupted.acknowledged)
        assertEquals(1, interrupted.failed)
        assertTrue(fixture.content.pendingBlobSyncJobs().isEmpty())
        assertEquals(listOf(fixture.transferKey), durableJournal.committedKeys(INSTANCE_ID, WORKSPACE_ID))
        assertEquals(1, local.readState().drafts.size)

        val recovered = coordinator.drain(session(), capability())

        assertEquals(0, recovered.inspected)
        assertEquals(0, recovered.remaining)
        assertTrue(durableJournal.committedKeys(INSTANCE_ID, WORKSPACE_ID).isEmpty())
        assertTrue(durableJournal.loadIntent(fixture.transferKey) == null)
        assertEquals(1, fixture.api.reservations)
    }

    @Test
    fun deniedFirstBodyJobDoesNotStarveLaterAllowedUpload() = runTest {
        val sourceStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val deniedBody = sourceStore.put("denied".encodeToByteArray(), "text/plain").reference
        val allowedBody = sourceStore.put("allowed".encodeToByteArray(), "text/plain").reference
        val owner = ContentManifestOwner(
            PublicationKey(PUBLICATION_ID),
            ACQUISITION_ID,
            UnitKey(PublicationKey(PUBLICATION_ID), UNIT_ID),
        )
        val deniedJob = ContentBlobSyncJobMutation(
            jobId = "a-denied-body",
            blob = deniedBody,
            owner = owner,
            manifestId = MANIFEST_ID,
            contentRevision = 1,
            grantReference = RightsGrantRef(DENIED_GRANT_ID),
        )
        val allowedJob = ContentBlobSyncJobMutation(
            jobId = "b-allowed-body",
            blob = allowedBody,
            owner = owner,
            manifestId = SECOND_MANIFEST_ID,
            contentRevision = 1,
            grantReference = RightsGrantRef(GRANT_ID),
        )
        val content = PendingBlobJobStore(listOf(deniedJob, allowedJob), false)
        val secrets = InMemorySyncSecretStore().apply {
            write(
                SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 1),
                SecretMaterial(ByteArray(32) { (it + 1).toByte() }.asList()),
            )
        }
        val journal = InMemoryBlobTransferJournalV2()
        val api = FakeBlobBodyApi()
        val uploader = EncryptedBlobUploaderV2(
            blobStore = sourceStore,
            bodyApi = api,
            crypto = SodiumBlobBodyCryptoV2(secrets),
            journal = journal,
            operationGate = GrantSelectiveGate(RightsGrantRef(GRANT_ID)),
            nowEpochMillis = { 1_000 },
        )
        val result = ContentBlobSyncCoordinatorV2(
            contentStore = content,
            localStore = InMemoryLocalSyncStore(),
            uploader = uploader,
            journal = journal,
            nowEpochMillis = { 2_000 },
        ).drain(session(), capability())

        assertEquals(2, result.inspected)
        assertEquals(1, result.rightsDenied)
        assertEquals(1, result.uploaded)
        assertEquals(1, result.acknowledged)
        assertEquals(listOf(deniedJob), content.pendingBlobSyncJobs())
        assertEquals(1, api.reservations)
    }

    private suspend fun coordinatorFixture(
        localStore: LocalSyncStore,
        failFirstAcknowledgement: Boolean = false,
        journal: BlobTransferJournalV2 = InMemoryBlobTransferJournalV2(),
    ): CoordinatorFixture {
        val bytes = ByteArray(4 * 1024) { (it * 17).toByte() }
        val sourceStore = InMemoryContentBlobStore(maximumBlobSizeBytes = 1024 * 1024)
        val published = sourceStore.put(bytes, "text/plain")
        val job = ContentBlobSyncJobMutation(
            jobId = "blob-upload:${published.reference.blobId}",
            blob = published.reference,
            owner = ContentManifestOwner(
                PublicationKey(PUBLICATION_ID),
                ACQUISITION_ID,
                UnitKey(PublicationKey(PUBLICATION_ID), UNIT_ID),
            ),
            manifestId = MANIFEST_ID,
            contentRevision = 1,
            grantReference = RightsGrantRef(GRANT_ID),
        )
        val content = PendingBlobJobStore(job, failFirstAcknowledgement)
        val secrets = InMemorySyncSecretStore().apply {
            write(
                SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 1),
                SecretMaterial(ByteArray(32) { (it + 1).toByte() }.asList()),
            )
        }
        val api = FakeBlobBodyApi()
        val uploader = EncryptedBlobUploaderV2(
            blobStore = sourceStore,
            bodyApi = api,
            crypto = SodiumBlobBodyCryptoV2(secrets),
            journal = journal,
            operationGate = AllowingGate,
            nowEpochMillis = { 1_000 },
        )
        return CoordinatorFixture(content, localStore, uploader, journal, api, published.reference.blobId)
    }

    private data class CoordinatorFixture(
        val content: PendingBlobJobStore,
        val local: LocalSyncStore,
        val uploader: EncryptedBlobUploaderV2,
        val journal: BlobTransferJournalV2,
        val api: FakeBlobBodyApi,
        val blobId: String,
    ) {
        val transferKey: BlobTransferKeyV2
            get() = BlobTransferKeyV2(INSTANCE_ID, WORKSPACE_ID, blobId, 1)

        fun coordinator(): ContentBlobSyncCoordinatorV2 = ContentBlobSyncCoordinatorV2(
            contentStore = content,
            localStore = local,
            uploader = uploader,
            journal = journal,
            nowEpochMillis = { 2_000 },
        )
    }

    private class PendingBlobJobStore(
        pending: List<ContentBlobSyncJobMutation>,
        private var failFirstAcknowledgement: Boolean,
    ) : SharedContentTransactionStore<SyncDraft> {
        constructor(
            job: ContentBlobSyncJobMutation,
            failFirstAcknowledgement: Boolean,
        ) : this(listOf(job), failFirstAcknowledgement)

        private val jobs = linkedMapOf<String, ContentBlobSyncJobMutation>().apply {
            pending.forEach { job -> put(job.jobId, job) }
        }

        override fun commit(batch: ContentCommitBatch<SyncDraft>): ContentCommitResult =
            error("The coordinator test store does not accept new commits")

        override fun pendingBlobSyncJobs(): List<ContentBlobSyncJobMutation> = jobs.values.toList()

        override fun acknowledgeBlobSyncJobs(jobIds: Set<String>): Int {
            if (failFirstAcknowledgement) {
                failFirstAcknowledgement = false
                throw IllegalStateException("simulated crash before content job acknowledgement")
            }
            val removed = jobIds.count(jobs::containsKey)
            jobIds.forEach(jobs::remove)
            return removed
        }

        override fun detachReplicaAuthority(
            authority: ContentReplicaAuthority,
        ): ContentReplicaAuthorityDepartureResult = ContentReplicaAuthorityDepartureResult(
            authority = authority,
            removedCursorCount = 0,
            removedCommitCount = 0,
            removedBlobRemovalIntentCount = 0,
        )

        override fun lookupMigrationLedger(
            namespace: String,
            sourceDigestSha256: String,
            resultFingerprintSha256: String,
        ) = error("The coordinator test store has no migration ledger")
    }

    private class FailOnceLocalStore(
        private val delegate: LocalSyncStore,
    ) : LocalSyncStore {
        private var fail = true

        override suspend fun readState(): LocalSyncStoreState = delegate.readState()

        override suspend fun <T> transaction(block: SyncStoreTransaction.() -> T): T {
            if (fail) {
                fail = false
                throw IllegalStateException("simulated crash before local metadata commit")
            }
            return delegate.transaction(block)
        }
    }

    private class FailOnceCleanupJournal(
        private val delegate: BlobTransferJournalV2,
    ) : BlobTransferJournalV2 by delegate {
        private var fail = true

        override suspend fun removeCompleted(key: BlobTransferKeyV2) {
            if (fail) {
                fail = false
                throw IllegalStateException("simulated crash after content job acknowledgement")
            }
            delegate.removeCompleted(key)
        }
    }

    private class CancellingAfterChunkCrypto(
        private val delegate: BlobBodyCryptoV2,
        private val cancelAfterEncryption: Int,
    ) : BlobBodyCryptoV2 by delegate {
        private var encryptions = 0

        override suspend fun encryptChunk(
            session: SyncSession,
            intent: BlobUploadIntentV2,
            chunkIndex: Int,
            plaintext: ByteArray,
        ): EncryptedBlobChunkV2 {
            val encrypted = delegate.encryptChunk(session, intent, chunkIndex, plaintext)
            encryptions += 1
            if (encryptions == cancelAfterEncryption) {
                currentCoroutineContext()[Job]?.cancel(
                    CancellationException("simulated foreground cancellation after chunk crypto"),
                )
            }
            return encrypted
        }
    }

    private class FakeBlobBodyApi : CloudflareBlobBodyApiV2 {
        var reservations = 0
        var uploadedChunks = 0
        var tamperNextDownload = false
        var failReservations = false
        private lateinit var request: BlobUploadReservationRequestV2
        private val chunks = mutableMapOf<Int, EncryptedBlobChunkV2>()
        lateinit var storedManifest: CommittedEncryptedBlobManifestV2
            private set

        override suspend fun reserveUpload(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobUploadReservationRequestV2,
        ): BlobUploadSessionV2 {
            reservations += 1
            if (failReservations) throw IllegalStateException("simulated reservation failure")
            if (this::request.isInitialized) require(this.request == request) else this.request = request
            return uploadSession()
        }

        override suspend fun uploadStatus(
            session: SyncSession,
            capability: WorkspaceCapability,
            uploadSessionId: String,
        ): BlobUploadSessionV2 = uploadSession()

        override suspend fun uploadChunk(
            session: SyncSession,
            capability: WorkspaceCapability,
            uploadSessionId: String,
            chunk: EncryptedBlobChunkV2,
        ): BlobChunkReceiptV2 {
            val previous = chunks[chunk.plan.index]
            require(previous == null || previous.plan == chunk.plan)
            if (previous == null) uploadedChunks += 1
            chunks[chunk.plan.index] = chunk
            return BlobChunkReceiptV2(
                chunk.plan.index,
                chunk.plan.ciphertextByteSize,
                chunk.plan.ciphertextSha256Base64Url,
            )
        }

        override suspend fun commitUpload(
            session: SyncSession,
            capability: WorkspaceCapability,
            uploadSessionId: String,
            request: BlobManifestCommitRequestV2,
        ): BlobBodyCommitReceiptV2 {
            require(chunks.keys.sorted() == this.request.chunks.indices.toList())
            val remote = RemoteBlobBodyManifestRefV2(
                manifestId = this.request.manifestId,
                blobId = this.request.blobId,
                manifestCiphertextSha256Base64Url = request.encryptedPrivateManifest
                    .ciphertextSha256Base64Url,
                manifestCiphertextByteSize = request.encryptedPrivateManifest.ciphertextByteSize.toLong(),
                bodyCiphertextByteSize = this.request.expectedBodyCiphertextBytes,
                chunkCount = this.request.chunks.size,
                chunkSizeBytes = this.request.chunkSizeBytes,
                committedAtEpochMillis = 2_000,
                commitReceiptId = RECEIPT_ID,
            )
            storedManifest = CommittedEncryptedBlobManifestV2(
                remote = remote,
                chunks = this.request.chunks,
                encryptedPrivateManifest = request.encryptedPrivateManifest,
                dekEnvelopes = listOf(this.request.initialDekEnvelope),
            )
            return BlobBodyCommitReceiptV2(RECEIPT_ID, UPLOAD_SESSION_ID, remote)
        }

        override suspend fun downloadManifest(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
        ): CommittedEncryptedBlobManifestV2 = storedManifest

        override suspend fun downloadChunk(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            chunk: EncryptedBlobChunkPlanV2,
        ): BinaryData {
            val bytes = requireNotNull(chunks[chunk.index]).ciphertext.copyBytes()
            if (tamperNextDownload) {
                tamperNextDownload = false
                bytes[0] = (bytes[0].toInt() xor 1).toByte()
            }
            return BinaryData.copyOf(bytes)
        }

        override suspend fun rewrapEnvelope(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobEnvelopeRewrapRequestV2,
        ): BlobDekEnvelopeV2 = request.envelope

        override suspend fun createTombstone(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobReferenceTombstoneRequestV2,
        ): BlobTombstoneCreationResultV2 = BlobTombstoneCreationResultV2(
            handle = BlobTombstoneHandleV2(
                instanceId = session.instanceId,
                workspaceId = session.workspaceId,
                tombstoneId = request.tombstoneId,
                blobId = request.blobId,
                manifestId = request.manifestId,
                referenceThroughWorkspaceSeq = request.throughWorkspaceSeq,
                requestedCreatedAtEpochMillis = request.createdAtEpochMillis,
                executeAfterEpochMillis = request.createdAtEpochMillis + 1_000,
            ),
            disposition = BlobTombstoneDispositionV2.ACTIVE,
        )

        override suspend fun reviveBlobReference(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobReferenceRevivalRequestV2,
        ): BlobReferenceRevivalResultV2 = BlobReferenceRevivalResultV2(
            tombstoneId = request.tombstoneId,
            blobId = request.blobId,
            manifestId = request.manifestId,
            disposition = BlobTombstoneDispositionV2.CANCELLED,
            cancelledAtEpochMillis = 1,
        )

        override suspend fun acknowledgeTombstone(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobTombstoneAckRequestV2,
        ) = Unit

        override suspend fun garbageCollect(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobGcRequestV2,
        ): BlobGcReceiptV2 = BlobGcReceiptV2(GC_RECEIPT_ID, request.blobId, 0, 0, 0)

        private fun uploadSession(): BlobUploadSessionV2 = BlobUploadSessionV2(
            sessionId = UPLOAD_SESSION_ID,
            blobId = request.blobId,
            manifestId = request.manifestId,
            keyEpoch = request.keyEpoch,
            expiresAtEpochMillis = 100_000,
            reservedBytes = request.totalReservedBytes,
            receivedChunks = chunks.values.sortedBy { it.plan.index }.map { chunk ->
                BlobChunkReceiptV2(
                    chunk.plan.index,
                    chunk.plan.ciphertextByteSize,
                    chunk.plan.ciphertextSha256Base64Url,
                )
            },
        )
    }

    private object AllowingGate : ContentOperationGate {
        override fun decide(request: ContentAccessRequest, operation: ContentOperation): RightsDecision =
            RightsDecision.ALLOW

        override fun requireAllowed(request: ContentAccessRequest, operation: ContentOperation) = Unit

        override fun <T> execute(
            request: ContentAccessRequest,
            operation: ContentOperation,
            block: () -> T,
        ): T = block()

        override suspend fun <T> executeSuspending(
            request: ContentAccessRequest,
            operation: ContentOperation,
            block: suspend () -> T,
        ): T = block()
    }

    private class GrantSelectiveGate(
        private val allowedGrant: RightsGrantRef,
    ) : ContentOperationGate {
        override fun decide(request: ContentAccessRequest, operation: ContentOperation): RightsDecision =
            if (request.grantReference == allowedGrant) RightsDecision.ALLOW else RightsDecision.DENY

        override fun requireAllowed(request: ContentAccessRequest, operation: ContentOperation) {
            if (decide(request, operation) != RightsDecision.ALLOW) {
                throw ContentOperationDeniedException(operation)
            }
        }

        override fun <T> execute(
            request: ContentAccessRequest,
            operation: ContentOperation,
            block: () -> T,
        ): T {
            requireAllowed(request, operation)
            return block()
        }

        override suspend fun <T> executeSuspending(
            request: ContentAccessRequest,
            operation: ContentOperation,
            block: suspend () -> T,
        ): T {
            requireAllowed(request, operation)
            return block()
        }
    }

    companion object {
        private const val INSTANCE_ID = "10000000-0000-4000-8000-000000000001"
        private const val USER_ID = "10000000-0000-4000-8000-000000000002"
        private const val WORKSPACE_ID = "10000000-0000-4000-8000-000000000003"
        private const val FOREIGN_WORKSPACE_ID = "10000000-0000-4000-8000-000000000008"
        private const val DEVICE_ID = "10000000-0000-4000-8000-000000000004"
        private const val UPLOAD_SESSION_ID = "10000000-0000-4000-8000-000000000005"
        private const val RECEIPT_ID = "10000000-0000-4000-8000-000000000006"
        private const val GC_RECEIPT_ID = "10000000-0000-4000-8000-000000000007"
        private const val PUBLICATION_ID = "20000000-0000-4000-8000-000000000001"
        private const val ACQUISITION_ID = "20000000-0000-4000-8000-000000000002"
        private const val UNIT_ID = "20000000-0000-4000-8000-000000000003"
        private const val MANIFEST_ID = "20000000-0000-4000-8000-000000000004"
        private const val GRANT_ID = "20000000-0000-4000-8000-000000000005"
        private const val DENIED_GRANT_ID = "20000000-0000-4000-8000-000000000006"
        private const val SECOND_MANIFEST_ID = "20000000-0000-4000-8000-000000000007"

        private fun session(workspaceId: String = WORKSPACE_ID): SyncSession = SyncSession(
            endpoint = "https://sync.example.test",
            instanceId = INSTANCE_ID,
            userId = USER_ID,
            workspaceId = workspaceId,
            deviceId = DEVICE_ID,
            deviceDisplayName = "test",
            platform = "desktop",
            status = SyncSessionStatus.READY,
            deviceAuthEpoch = 1,
            membershipAuthEpoch = 1,
            activeKeyEpoch = 1,
        )

        private fun capability(workspaceId: String = WORKSPACE_ID): WorkspaceCapability = WorkspaceCapability(
            token = SecretMaterial("capability".encodeToByteArray().asList()),
            binding = CapabilityBinding(DEVICE_ID, workspaceId, 1, 1, 1, Long.MAX_VALUE),
        )

        private fun access(): ContentAccessRequest {
            val publication = PublicationKey(PUBLICATION_ID)
            return ContentAccessRequest(
                grantReference = null,
                scope = RightsScope(
                    publicationId = publication,
                    acquisitionId = ACQUISITION_ID,
                    unitId = UnitKey(publication, UNIT_ID),
                ),
                context = RightsOperationContext(),
            )
        }
    }
}
