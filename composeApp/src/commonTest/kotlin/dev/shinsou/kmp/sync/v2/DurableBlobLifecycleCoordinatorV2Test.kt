package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.sync.crypto.SodiumBlobBodyCryptoV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

class DurableBlobLifecycleCoordinatorV2Test {
    @Test
    fun durableWorkerRewrapResponseInstallsNormalDraftWithoutRepeatingWorkerCall() = runTest {
        val localStore = localStore(reference(present = true, envelopes = mapOf(1 to OLD_ENVELOPE)))
        val journal = InMemoryBlobLifecycleJournalV2().apply { save(REWRAP_COMMITTED) }
        val bodyApi = RecordingBodyApi()
        val coordinator = coordinator(localStore, journal, bodyApi)

        val result = coordinator.drainSlice(SESSION, CAPABILITY)

        assertEquals(BlobLifecycleSliceStatusV2.ENVELOPE_REWRAP_DRAFTED, result.status)
        assertEquals(0, bodyApi.rewrapCalls)
        assertNull(journal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID))
        val state = localStore.readState()
        assertEquals(NEW_ENVELOPE, state.replica.blobReferences.getValue(BLOB_ID).dekEnvelopes.getValue(2).value)
        assertTrue(state.drafts.values.single().event.mutations.single() is BlobDekEnvelopeRewrappedV2)
    }

    @Test
    fun preparedAcknowledgementReplaysExactBytesThenRetainsDurableGcReceipt() = runTest {
        val localStore = localStore(reference(present = false, envelopes = mapOf(2 to NEW_ENVELOPE)))
        val journal = InMemoryBlobLifecycleJournalV2().apply { save(TOMBSTONE_ACK_PREPARED) }
        val bodyApi = RecordingBodyApi()
        val coordinator = coordinator(localStore, journal, bodyApi)

        val result = coordinator.drainSlice(SESSION, CAPABILITY)

        assertEquals(BlobLifecycleSliceStatusV2.TOMBSTONE_GC_COMPLETED, result.status)
        assertEquals(ACK, bodyApi.acknowledgement)
        assertEquals(1, bodyApi.ackCalls)
        assertEquals(1, bodyApi.gcCalls)
        val completed = journal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID)
            as DurableBlobLifecycleIntentV2.ReferenceTombstone
        assertEquals(GC_RECEIPT, completed.gcReceipt)
        assertTrue(completed.completed)

        // A retained receipt prevents a second tombstone identity or network request after restart.
        assertEquals(BlobLifecycleSliceStatusV2.NO_ACTION, coordinator.drainSlice(SESSION, CAPABILITY).status)
        assertEquals(1, bodyApi.ackCalls)
        assertEquals(1, bodyApi.gcCalls)
    }

    @Test
    fun stalePreparedRewrapRecoversWorkerWinnerBeforeDraftingNewerEpoch() = runTest {
        val localStore = localStore(
            reference(present = true, envelopes = mapOf(1 to OLD_ENVELOPE)),
            activeKeyEpoch = 3,
        )
        val journal = InMemoryBlobLifecycleJournalV2().apply {
            save(DurableBlobLifecycleIntentV2.EnvelopeRewrap(PREPARED_REWRAP))
        }
        val workerWinner = NEW_ENVELOPE.copy(
            nonceBase64Url = "BQ",
            wrappedDekBase64Url = "Bg",
            envelopeSha256Base64Url = HASH_3,
        )
        val bodyApi = RecordingBodyApi().apply {
            recoveredEnvelope = BlobEnvelopeRecoveryV2(
                manifestId = MANIFEST_ID,
                envelope = workerWinner,
                checkpointEvidence = EVIDENCE,
                status = BlobEnvelopeRetentionStatusV2.RETAINED,
            )
        }
        val coordinator = coordinator(localStore, journal, bodyApi)

        val result = coordinator.drainSlice(SESSION_EPOCH_3, CAPABILITY_EPOCH_3)

        assertEquals(BlobLifecycleSliceStatusV2.STALE_ENVELOPE_RECOVERED, result.status)
        assertEquals(1, bodyApi.recoverCalls)
        assertEquals(0, bodyApi.rewrapCalls)
        assertNull(journal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID))
        val mutation = localStore.readState().drafts.values.single().event.mutations.single()
            as BlobDekEnvelopeRewrappedV2
        assertEquals(workerWinner, mutation.envelope)
        assertEquals(EVIDENCE, mutation.checkpointEvidence)
    }

    @Test
    fun createTombstoneRechecksRightsAndPersistsProvisionalIntentWithoutWorkerCall() = runTest {
        val absentState = SyncState(
            keyEpoch = 2,
            throughWorkspaceSeq = 20,
            blobReferences = mapOf(BLOB_ID to reference(false, mapOf(2 to NEW_ENVELOPE))),
        )
        val metadata = MutableCheckpointSyncApi(absentState)
        val local = InMemoryLocalSyncStore(
            LocalSyncStoreState(replica = absentState, activeKeyEpoch = 2),
        )
        val journal = InMemoryBlobLifecycleJournalV2()
        val bodyApi = RecordingBodyApi()
        var authorizationChecks = 0
        val coordinator = coordinator(
            local,
            journal,
            bodyApi,
            metadata,
            CheckpointLifecycleCrypto(metadata, TOMBSTONE_ID, 3),
            authorizeBlobSync = { ++authorizationChecks == 1 },
        )

        val result = coordinator.drainSlice(SESSION, CAPABILITY)

        assertEquals(BlobLifecycleSliceStatusV2.RIGHTS_DENIED, result.status)
        assertEquals(0, bodyApi.createCalls)
        val durable = assertNotNull(journal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID))
            as DurableBlobLifecycleIntentV2.ReferenceTombstone
        assertEquals(false, durable.createdOnWorker)
        assertEquals(0L, durable.attemptCount)
    }

    @Test
    fun acknowledgementRechecksRightsBeforeWorkerAckOrGc() = runTest {
        val journal = InMemoryBlobLifecycleJournalV2().apply { save(TOMBSTONE_ACK_PREPARED) }
        val bodyApi = RecordingBodyApi()
        var authorizationChecks = 0
        val coordinator = coordinator(
            localStore(reference(false, mapOf(2 to NEW_ENVELOPE))),
            journal,
            bodyApi,
            authorizeBlobSync = { ++authorizationChecks < 3 },
        )

        val result = coordinator.drainSlice(SESSION, CAPABILITY)

        assertEquals(BlobLifecycleSliceStatusV2.RIGHTS_DENIED, result.status)
        assertEquals(0, bodyApi.ackCalls)
        assertEquals(0, bodyApi.gcCalls)
        val durable = journal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID)
            as DurableBlobLifecycleIntentV2.ReferenceTombstone
        assertEquals(1L, durable.attemptCount)
        assertEquals(false, durable.acknowledgementCommitted)
    }

    @Test
    fun garbageCollectionRechecksRightsAfterDurableAcknowledgement() = runTest {
        val acked = TOMBSTONE_ACK_PREPARED.copy(acknowledgementCommitted = true)
        val journal = InMemoryBlobLifecycleJournalV2().apply { save(acked) }
        val bodyApi = RecordingBodyApi()
        var authorizationChecks = 0
        val coordinator = coordinator(
            localStore(reference(false, mapOf(2 to NEW_ENVELOPE))),
            journal,
            bodyApi,
            authorizeBlobSync = { ++authorizationChecks < 3 },
        )

        val result = coordinator.drainSlice(SESSION, CAPABILITY)

        assertEquals(BlobLifecycleSliceStatusV2.RIGHTS_DENIED, result.status)
        assertEquals(0, bodyApi.gcCalls)
        assertNull(
            (journal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID)
                as DurableBlobLifecycleIntentV2.ReferenceTombstone).gcReceipt,
        )
    }

    @Test
    fun revivalRechecksRightsAfterPersistingSignedProof() = runTest {
        val liveState = SyncState(
            keyEpoch = 2,
            throughWorkspaceSeq = 30,
            blobReferences = mapOf(BLOB_ID to reference(true, mapOf(2 to NEW_ENVELOPE))),
        )
        val metadata = MutableCheckpointSyncApi(liveState)
        val local = InMemoryLocalSyncStore(
            LocalSyncStoreState(replica = liveState, activeKeyEpoch = 2),
        )
        val journal = InMemoryBlobLifecycleJournalV2().apply { save(TOMBSTONE_ACK_PREPARED) }
        val bodyApi = RecordingBodyApi()
        var authorizationChecks = 0
        val coordinator = coordinator(
            local,
            journal,
            bodyApi,
            metadata,
            CheckpointLifecycleCrypto(metadata, TOMBSTONE_ID, 4),
            authorizeBlobSync = { ++authorizationChecks < 3 },
        )

        val result = coordinator.drainSlice(SESSION, CAPABILITY)

        assertEquals(BlobLifecycleSliceStatusV2.RIGHTS_DENIED, result.status)
        assertEquals(0, bodyApi.revivalCalls)
        assertEquals(0, bodyApi.gcCalls)
        val durable = journal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID)
            as DurableBlobLifecycleIntentV2.ReferenceTombstone
        assertNotNull(durable.revival)
        assertNull(durable.revivalResult)
    }

    @Test
    fun deniedDurableIntentRotatesBehindAnotherBlob() = runTest {
        val secondIntent = DurableBlobLifecycleIntentV2.EnvelopeRewrap(
            prepared = PREPARED_REWRAP.copy(
                blobId = SECOND_BLOB_ID,
                request = REWRAP_REQUEST.copy(
                    manifestId = SECOND_MANIFEST_ID,
                    envelope = NEW_ENVELOPE.copy(blobId = SECOND_BLOB_ID),
                ),
            ),
            committedMutation = REWRAP_MUTATION.copy(
                blobId = SECOND_BLOB_ID,
                manifestId = SECOND_MANIFEST_ID,
                envelope = NEW_ENVELOPE.copy(blobId = SECOND_BLOB_ID),
            ),
        )
        val journal = InMemoryBlobLifecycleJournalV2().apply {
            save(REWRAP_COMMITTED)
            save(secondIntent)
        }
        val local = InMemoryLocalSyncStore(
            LocalSyncStoreState(
                replica = SyncState(
                    keyEpoch = 2,
                    throughWorkspaceSeq = 20,
                    blobReferences = mapOf(
                        BLOB_ID to reference(true, mapOf(1 to OLD_ENVELOPE)),
                        SECOND_BLOB_ID to reference(
                            blob = BLOB.copy(blobId = SECOND_BLOB_ID),
                            remote = REMOTE_MANIFEST.copy(
                                manifestId = SECOND_MANIFEST_ID,
                                blobId = SECOND_BLOB_ID,
                            ),
                            present = true,
                            envelopes = mapOf(1 to OLD_ENVELOPE.copy(blobId = SECOND_BLOB_ID)),
                        ),
                    ),
                ),
                activeKeyEpoch = 2,
            ),
        )
        val bodyApi = RecordingBodyApi()
        val coordinator = coordinator(
            local,
            journal,
            bodyApi,
            authorizeBlobSync = { blobId -> blobId == SECOND_BLOB_ID },
        )

        assertEquals(
            BlobLifecycleSliceResultV2(BlobLifecycleSliceStatusV2.RIGHTS_DENIED, BLOB_ID),
            coordinator.drainSlice(SESSION, CAPABILITY),
        )
        assertEquals(
            BlobLifecycleSliceResultV2(
                BlobLifecycleSliceStatusV2.ENVELOPE_REWRAP_DRAFTED,
                SECOND_BLOB_ID,
            ),
            coordinator.drainSlice(SESSION, CAPABILITY),
        )
        assertNotNull(journal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID))
        assertNull(journal.load(INSTANCE_ID, WORKSPACE_ID, SECOND_BLOB_ID))
        assertEquals(0, bodyApi.rewrapCalls)
    }

    @Test
    fun twoDeviceCoordinatorsAdoptOneWorkerTombstoneAndNewLiveReferenceCancelsGc() = runTest {
        val absentState = SyncState(
            keyEpoch = 2,
            throughWorkspaceSeq = 20,
            blobReferences = mapOf(BLOB_ID to reference(present = false, envelopes = mapOf(2 to NEW_ENVELOPE))),
        )
        val metadata = MutableCheckpointSyncApi(absentState)
        val worker = SharedLifecycleWorkerApi()
        val firstStore = InMemoryLocalSyncStore(
            LocalSyncStoreState(replica = absentState, activeKeyEpoch = 2),
        )
        val secondStore = InMemoryLocalSyncStore(
            LocalSyncStoreState(replica = absentState, activeKeyEpoch = 2),
        )
        val firstJournal = InMemoryBlobLifecycleJournalV2()
        val secondJournal = InMemoryBlobLifecycleJournalV2()
        val firstCrypto = CheckpointLifecycleCrypto(metadata, FIRST_PROVISIONAL_TOMBSTONE_ID, 1)
        val secondCrypto = CheckpointLifecycleCrypto(metadata, SECOND_PROVISIONAL_TOMBSTONE_ID, 2)
        val first = coordinator(firstStore, firstJournal, worker, metadata, firstCrypto)
        val second = coordinator(secondStore, secondJournal, worker, metadata, secondCrypto)

        val failures = listOf(
            async { runCatching { first.drainSlice(SESSION, CAPABILITY) }.exceptionOrNull() },
            async { runCatching { second.drainSlice(SESSION_B, CAPABILITY_B) }.exceptionOrNull() },
        ).awaitAll()
        assertTrue(failures.all { it is GcSafetyWindowPending })
        assertEquals(
            setOf(FIRST_PROVISIONAL_TOMBSTONE_ID, SECOND_PROVISIONAL_TOMBSTONE_ID),
            worker.provisionalTombstoneIds.toSet(),
        )

        val firstDurable = firstJournal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID)
            as DurableBlobLifecycleIntentV2.ReferenceTombstone
        val secondDurable = secondJournal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID)
            as DurableBlobLifecycleIntentV2.ReferenceTombstone
        assertEquals(firstDurable.handle, secondDurable.handle)
        assertEquals(worker.canonicalTombstoneId, firstDurable.handle.tombstoneId)
        assertTrue(firstDurable.createdOnWorker && secondDurable.createdOnWorker)
        assertEquals(BlobTombstoneDispositionV2.ACTIVE, firstDurable.creationDisposition)
        assertEquals(
            listOf(worker.canonicalTombstoneId, worker.canonicalTombstoneId),
            worker.acknowledgedTombstoneIds.sorted(),
        )

        val liveState = absentState.copy(
            throughWorkspaceSeq = 30,
            blobReferences = mapOf(BLOB_ID to reference(present = true, envelopes = mapOf(2 to NEW_ENVELOPE))),
        )
        metadata.state = liveState
        firstStore.transaction { installCheckpointAndRebase(liveState, 30) }
        secondStore.transaction { installCheckpointAndRebase(liveState, 30) }
        val ackCallsBeforeRevival = worker.acknowledgedTombstoneIds.size
        val gcCallsBeforeRevival = worker.gcCalls

        assertEquals(
            BlobLifecycleSliceStatusV2.TOMBSTONE_REVIVAL_CANCELLED,
            first.drainSlice(SESSION, CAPABILITY).status,
        )
        assertEquals(
            BlobLifecycleSliceStatusV2.TOMBSTONE_REVIVAL_CANCELLED,
            second.drainSlice(SESSION_B, CAPABILITY_B).status,
        )
        assertNull(firstJournal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID))
        assertNull(secondJournal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID))
        assertTrue(worker.cancelled)
        assertTrue(worker.bodyPresent)
        assertEquals(2, worker.revivalDeviceIds.toSet().size)
        assertEquals(ackCallsBeforeRevival, worker.acknowledgedTombstoneIds.size)
        assertEquals(gcCallsBeforeRevival, worker.gcCalls)
        assertEquals(BlobLifecycleSliceStatusV2.NO_ACTION, first.drainSlice(SESSION, CAPABILITY).status)
        assertEquals(BlobLifecycleSliceStatusV2.NO_ACTION, second.drainSlice(SESSION_B, CAPABILITY_B).status)
        assertEquals(gcCallsBeforeRevival, worker.gcCalls)
    }

    private fun coordinator(
        localStore: LocalSyncStore,
        journal: BlobLifecycleJournalV2,
        bodyApi: RecordingBodyApi,
        authorizeBlobSync: suspend (String) -> Boolean = { true },
    ): DurableBlobLifecycleCoordinatorV2 {
        val syncCrypto = UnusedSyncCrypto()
        return DurableBlobLifecycleCoordinatorV2(
            metadataApi = BootstrapOnlySyncApi(),
            bodyCoordinator = BlobLifecycleCoordinatorV2(
                bodyApi = bodyApi,
                blobCrypto = SodiumBlobBodyCryptoV2(InMemorySyncSecretStore()),
                syncCrypto = syncCrypto,
                nowEpochMillis = { 2_000 },
            ),
            syncCrypto = syncCrypto,
            localStore = localStore,
            journal = journal,
            authorizeBlobSync = authorizeBlobSync,
            nowEpochMillis = { 2_000 },
        )
    }

    private fun coordinator(
        localStore: LocalSyncStore,
        journal: BlobLifecycleJournalV2,
        bodyApi: CloudflareBlobBodyApiV2,
        metadataApi: CloudflareSyncApi,
        syncCrypto: SyncCrypto,
        authorizeBlobSync: suspend (String) -> Boolean = { true },
    ): DurableBlobLifecycleCoordinatorV2 = DurableBlobLifecycleCoordinatorV2(
        metadataApi = metadataApi,
        bodyCoordinator = BlobLifecycleCoordinatorV2(
            bodyApi = bodyApi,
            blobCrypto = SodiumBlobBodyCryptoV2(InMemorySyncSecretStore()),
            syncCrypto = syncCrypto,
            nowEpochMillis = { 2_000 },
        ),
        syncCrypto = syncCrypto,
        localStore = localStore,
        journal = journal,
        authorizeBlobSync = authorizeBlobSync,
        nowEpochMillis = { 2_000 },
    )

    private fun localStore(
        reference: SyncedBlobReferenceRecord,
        activeKeyEpoch: Int = 2,
    ): InMemoryLocalSyncStore =
        InMemoryLocalSyncStore(
            LocalSyncStoreState(
                replica = SyncState(
                    keyEpoch = activeKeyEpoch,
                    throughWorkspaceSeq = 20,
                    blobReferences = mapOf(BLOB_ID to reference),
                ),
                activeKeyEpoch = activeKeyEpoch,
            ),
        )

    private fun reference(
        present: Boolean,
        envelopes: Map<Int, BlobDekEnvelopeV2>,
    ): SyncedBlobReferenceRecord = reference(BLOB, REMOTE_MANIFEST, present, envelopes)

    private fun reference(
        blob: BlobRef,
        remote: RemoteBlobBodyManifestRefV2,
        present: Boolean,
        envelopes: Map<Int, BlobDekEnvelopeV2>,
    ): SyncedBlobReferenceRecord {
        val hlc = HlcTimestamp(1, 0, DEVICE_ID)
        return SyncedBlobReferenceRecord(
            blobId = blob.blobId,
            blob = LwwRegister(blob, hlc),
            remoteManifest = LwwRegister(remote, hlc),
            dekEnvelopes = envelopes.mapValues { LwwRegister(it.value, hlc) },
            presence = LwwRegister(present, hlc),
        )
    }

    private class MutableCheckpointSyncApi(
        var state: SyncState,
    ) : CloudflareSyncApi {
        fun descriptor(): RetainedCheckpointDescriptor = RetainedCheckpointDescriptor(
            checkpointId = if (state.throughWorkspaceSeq == 20L) CHECKPOINT_ID else LIVE_CHECKPOINT_ID,
            throughWorkspaceSeq = state.throughWorkspaceSeq,
            keyEpoch = state.keyEpoch,
            ciphertextSha256Base64Url = if (state.throughWorkspaceSeq == 20L) HASH_2 else HASH_3,
        )

        override suspend fun bootstrap(
            session: SyncSession,
            capability: WorkspaceCapability,
        ): BootstrapResponse = BootstrapResponse(
            headSeq = state.throughWorkspaceSeq,
            activeKeyEpoch = state.keyEpoch,
            retainedStableCheckpoints = listOf(descriptor()),
            requiredKeyEpochs = setOf(state.keyEpoch),
        )

        override suspend fun downloadCheckpoint(
            session: SyncSession,
            capability: WorkspaceCapability,
            descriptor: RetainedCheckpointDescriptor,
        ): EncryptedSyncCheckpoint {
            require(descriptor == this.descriptor())
            return EncryptedSyncCheckpoint(
                header = SyncCheckpointHeader(
                    cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
                    nonceBase64Url = "AQ",
                    instanceId = session.instanceId,
                    workspaceId = session.workspaceId,
                    checkpointId = descriptor.checkpointId,
                    deviceId = DEVICE_ID,
                    throughWorkspaceSeq = descriptor.throughWorkspaceSeq,
                    keyEpoch = descriptor.keyEpoch,
                    previousStableCiphertextSha256Base64Url = state.previousStableCheckpointHash,
                    compression = SyncCheckpointCompression.LZ4_BLOCK_V1,
                    uncompressedSize = 1,
                    ciphertextSha256Base64Url = descriptor.ciphertextSha256Base64Url,
                ),
                authenticatedHeaderBase64Url = "AQ",
                ciphertextBase64Url = "AQ",
                signatureBase64Url = "AQ",
            )
        }

        override suspend fun capabilities(endpoint: String): SyncCapabilities = error("unused")
        override suspend fun obtainWorkspaceCapability(session: SyncSession): WorkspaceCapability = error("unused")
        override suspend fun appendEvent(
            session: SyncSession,
            capability: WorkspaceCapability,
            event: EncryptedSyncEvent,
        ): AppendEventResult = error("unused")
        override suspend fun eventReceipt(
            session: SyncSession,
            capability: WorkspaceCapability,
            deviceSeq: Long,
        ): SyncReceipt? = error("unused")
        override suspend fun catchUp(
            session: SyncSession,
            capability: WorkspaceCapability,
            afterExclusive: Long,
            untilInclusive: Long?,
            limit: Int,
        ): CatchUpPage = error("unused")
    }

    private class CheckpointLifecycleCrypto(
        private val metadata: MutableCheckpointSyncApi,
        private val generatedId: String,
        private val signatureSeed: Int,
    ) : SyncCrypto {
        override suspend fun generateCheckpointId(): String = generatedId

        override suspend fun openAndVerifyCheckpoint(
            session: SyncSession,
            checkpoint: EncryptedSyncCheckpoint,
            descriptor: RetainedCheckpointDescriptor,
        ): VerifiedSyncCheckpoint {
            require(descriptor == metadata.descriptor())
            return VerifiedSyncCheckpoint(
                checkpoint.header,
                metadata.state,
                BinaryData.copyOf(byteArrayOf(1)),
            )
        }

        override suspend fun signDeviceMessage(message: BinaryData): BinaryData =
            BinaryData.copyOf(ByteArray(64) { (it + signatureSeed).toByte() })

        override suspend fun prepareEventSealer(session: SyncSession, keyEpoch: Int): PreparedSyncEventSealer =
            error("unused")
        override suspend fun openAndVerifyEvent(
            session: SyncSession,
            remote: RemoteCommittedEnvelope,
        ): OpenedRemoteEvent = error("unused")
        override suspend fun sealCheckpoint(
            session: SyncSession,
            checkpointId: String,
            state: SyncState,
            previousStableCiphertextSha256Base64Url: String?,
        ): EncryptedSyncCheckpoint = error("unused")
        override suspend fun generateWorkspaceEpochKey(): SecretMaterial = error("unused")
        override suspend fun keyCommitment(material: SecretMaterial): BinaryData = error("unused")
        override suspend fun wrapWorkspaceKey(
            material: SecretMaterial,
            recipientPublicKey: BinaryData,
        ): BinaryData = error("unused")
        override suspend fun verifyDeviceSignature(
            message: BinaryData,
            signature: BinaryData,
            publicKey: BinaryData,
        ): Boolean = error("unused")
    }

    private class GcSafetyWindowPending : IllegalStateException()

    private class SharedLifecycleWorkerApi : CloudflareBlobBodyApiV2 {
        private val stateMutex = Mutex()
        private val bothCreateCallsArrived = CompletableDeferred<Unit>()
        private var createEntrants: Int = 0
        private var canonical: BlobTombstoneHandleV2? = null

        val provisionalTombstoneIds = mutableListOf<String>()
        val acknowledgedTombstoneIds = mutableListOf<String>()
        val revivalDeviceIds = mutableListOf<String>()
        var gcCalls: Int = 0
            private set
        var cancelled: Boolean = false
            private set
        var bodyPresent: Boolean = true
            private set
        val canonicalTombstoneId: String get() = requireNotNull(canonical).tombstoneId

        override suspend fun createTombstone(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobReferenceTombstoneRequestV2,
        ): BlobTombstoneCreationResultV2 {
            val entrant = stateMutex.withLock {
                provisionalTombstoneIds += request.tombstoneId
                ++createEntrants
            }
            if (entrant < 2) {
                bothCreateCallsArrived.await()
            } else {
                bothCreateCallsArrived.complete(Unit)
            }
            val winner = stateMutex.withLock {
                canonical ?: BlobTombstoneHandleV2(
                    instanceId = session.instanceId,
                    workspaceId = session.workspaceId,
                    tombstoneId = request.tombstoneId,
                    blobId = request.blobId,
                    manifestId = request.manifestId,
                    referenceThroughWorkspaceSeq = request.throughWorkspaceSeq,
                    requestedCreatedAtEpochMillis = request.createdAtEpochMillis,
                    executeAfterEpochMillis = 5_000,
                ).also { canonical = it }
            }
            return BlobTombstoneCreationResultV2(
                handle = winner,
                disposition = if (cancelled) {
                    BlobTombstoneDispositionV2.CANCELLED
                } else {
                    BlobTombstoneDispositionV2.ACTIVE
                },
            )
        }

        override suspend fun acknowledgeTombstone(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobTombstoneAckRequestV2,
        ) {
            require(!cancelled)
            require(request.tombstoneId == canonicalTombstoneId)
            stateMutex.withLock { acknowledgedTombstoneIds += request.tombstoneId }
        }

        override suspend fun reviveBlobReference(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobReferenceRevivalRequestV2,
        ): BlobReferenceRevivalResultV2 {
            require(request.tombstoneId == canonicalTombstoneId)
            require(request.throughWorkspaceSeq > requireNotNull(canonical).referenceThroughWorkspaceSeq)
            stateMutex.withLock {
                revivalDeviceIds += session.deviceId
                cancelled = true
            }
            return BlobReferenceRevivalResultV2(
                tombstoneId = request.tombstoneId,
                blobId = request.blobId,
                manifestId = request.manifestId,
                disposition = BlobTombstoneDispositionV2.CANCELLED,
                cancelledAtEpochMillis = 3_000,
            )
        }

        override suspend fun garbageCollect(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobGcRequestV2,
        ): BlobGcReceiptV2 {
            stateMutex.withLock { gcCalls += 1 }
            require(!cancelled) { "A cancelled tombstone must never reach GC" }
            throw GcSafetyWindowPending()
        }

        override suspend fun reserveUpload(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobUploadReservationRequestV2,
        ): BlobUploadSessionV2 = error("unused")
        override suspend fun uploadStatus(
            session: SyncSession,
            capability: WorkspaceCapability,
            uploadSessionId: String,
        ): BlobUploadSessionV2 = error("unused")
        override suspend fun uploadChunk(
            session: SyncSession,
            capability: WorkspaceCapability,
            uploadSessionId: String,
            chunk: EncryptedBlobChunkV2,
        ): BlobChunkReceiptV2 = error("unused")
        override suspend fun commitUpload(
            session: SyncSession,
            capability: WorkspaceCapability,
            uploadSessionId: String,
            request: BlobManifestCommitRequestV2,
        ): BlobBodyCommitReceiptV2 = error("unused")
        override suspend fun downloadManifest(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
        ): CommittedEncryptedBlobManifestV2 = error("unused")
        override suspend fun downloadChunk(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            chunk: EncryptedBlobChunkPlanV2,
        ): BinaryData = error("unused")
        override suspend fun rewrapEnvelope(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobEnvelopeRewrapRequestV2,
        ): BlobDekEnvelopeV2 = request.envelope
    }

    private class BootstrapOnlySyncApi : CloudflareSyncApi {
        override suspend fun bootstrap(session: SyncSession, capability: WorkspaceCapability): BootstrapResponse =
            BootstrapResponse(
                headSeq = 20,
                activeKeyEpoch = session.activeKeyEpoch,
                retainedStableCheckpoints = listOf(
                    CHECKPOINT.copy(keyEpoch = session.activeKeyEpoch),
                ),
                requiredKeyEpochs = setOf(session.activeKeyEpoch),
            )

        override suspend fun capabilities(endpoint: String): SyncCapabilities = error("unused")
        override suspend fun obtainWorkspaceCapability(session: SyncSession): WorkspaceCapability = error("unused")
        override suspend fun appendEvent(
            session: SyncSession,
            capability: WorkspaceCapability,
            event: EncryptedSyncEvent,
        ): AppendEventResult = error("unused")

        override suspend fun eventReceipt(
            session: SyncSession,
            capability: WorkspaceCapability,
            deviceSeq: Long,
        ): SyncReceipt? = error("unused")

        override suspend fun catchUp(
            session: SyncSession,
            capability: WorkspaceCapability,
            afterExclusive: Long,
            untilInclusive: Long?,
            limit: Int,
        ): CatchUpPage = error("unused")

        override suspend fun downloadCheckpoint(
            session: SyncSession,
            capability: WorkspaceCapability,
            descriptor: RetainedCheckpointDescriptor,
        ): EncryptedSyncCheckpoint = error("unused")
    }

    private class RecordingBodyApi : CloudflareBlobBodyApiV2 {
        var rewrapCalls = 0
        var recoverCalls = 0
        var createCalls = 0
        var ackCalls = 0
        var gcCalls = 0
        var revivalCalls = 0
        var acknowledgement: BlobTombstoneAckRequestV2? = null
        var recoveredEnvelope: BlobEnvelopeRecoveryV2? = null

        override suspend fun rewrapEnvelope(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobEnvelopeRewrapRequestV2,
        ): BlobDekEnvelopeV2 {
            rewrapCalls++
            return request.envelope
        }

        override suspend fun recoverEnvelope(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            keyEpoch: Int,
        ): BlobEnvelopeRecoveryV2 {
            recoverCalls++
            return requireNotNull(recoveredEnvelope)
        }

        override suspend fun acknowledgeTombstone(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobTombstoneAckRequestV2,
        ) {
            ackCalls++
            acknowledgement = request
        }

        override suspend fun garbageCollect(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobGcRequestV2,
        ): BlobGcReceiptV2 {
            gcCalls++
            return GC_RECEIPT
        }

        override suspend fun createTombstone(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobReferenceTombstoneRequestV2,
        ): BlobTombstoneCreationResultV2 {
            createCalls++
            return BlobTombstoneCreationResultV2(
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
        }

        override suspend fun reviveBlobReference(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobReferenceRevivalRequestV2,
        ): BlobReferenceRevivalResultV2 {
            revivalCalls++
            return BlobReferenceRevivalResultV2(
                tombstoneId = request.tombstoneId,
                blobId = request.blobId,
                manifestId = request.manifestId,
                disposition = BlobTombstoneDispositionV2.CANCELLED,
                cancelledAtEpochMillis = 2_000,
            )
        }

        override suspend fun reserveUpload(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobUploadReservationRequestV2,
        ): BlobUploadSessionV2 = error("unused")

        override suspend fun uploadStatus(
            session: SyncSession,
            capability: WorkspaceCapability,
            uploadSessionId: String,
        ): BlobUploadSessionV2 = error("unused")

        override suspend fun uploadChunk(
            session: SyncSession,
            capability: WorkspaceCapability,
            uploadSessionId: String,
            chunk: EncryptedBlobChunkV2,
        ): BlobChunkReceiptV2 = error("unused")

        override suspend fun commitUpload(
            session: SyncSession,
            capability: WorkspaceCapability,
            uploadSessionId: String,
            request: BlobManifestCommitRequestV2,
        ): BlobBodyCommitReceiptV2 = error("unused")

        override suspend fun downloadManifest(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
        ): CommittedEncryptedBlobManifestV2 = error("unused")

        override suspend fun downloadChunk(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            chunk: EncryptedBlobChunkPlanV2,
        ): BinaryData = error("unused")
    }

    private class UnusedSyncCrypto : SyncCrypto {
        override suspend fun prepareEventSealer(session: SyncSession, keyEpoch: Int): PreparedSyncEventSealer =
            error("unused")
        override suspend fun openAndVerifyEvent(
            session: SyncSession,
            remote: RemoteCommittedEnvelope,
        ): OpenedRemoteEvent = error("unused")
        override suspend fun openAndVerifyCheckpoint(
            session: SyncSession,
            checkpoint: EncryptedSyncCheckpoint,
            descriptor: RetainedCheckpointDescriptor,
        ): VerifiedSyncCheckpoint = error("unused")
        override suspend fun sealCheckpoint(
            session: SyncSession,
            checkpointId: String,
            state: SyncState,
            previousStableCiphertextSha256Base64Url: String?,
        ): EncryptedSyncCheckpoint = error("unused")
        override suspend fun generateWorkspaceEpochKey(): SecretMaterial = error("unused")
        override suspend fun keyCommitment(material: SecretMaterial): BinaryData = error("unused")
        override suspend fun wrapWorkspaceKey(
            material: SecretMaterial,
            recipientPublicKey: BinaryData,
        ): BinaryData = error("unused")
        override suspend fun signDeviceMessage(message: BinaryData): BinaryData = error("unused")
        override suspend fun verifyDeviceSignature(
            message: BinaryData,
            signature: BinaryData,
            publicKey: BinaryData,
        ): Boolean = error("unused")
    }

    private companion object {
        const val INSTANCE_ID = "72000000-0000-4000-8000-000000000001"
        const val WORKSPACE_ID = "72000000-0000-4000-8000-000000000002"
        const val USER_ID = "72000000-0000-4000-8000-000000000003"
        const val DEVICE_ID = "72000000-0000-4000-8000-000000000004"
        const val DEVICE_B_ID = "72000000-0000-4000-8000-00000000000b"
        const val BLOB_ID = "72000000-0000-4000-8000-000000000005"
        const val MANIFEST_ID = "72000000-0000-4000-8000-000000000006"
        const val COMMIT_RECEIPT_ID = "72000000-0000-4000-8000-000000000007"
        const val CHECKPOINT_ID = "72000000-0000-4000-8000-000000000008"
        const val TOMBSTONE_ID = "72000000-0000-4000-8000-000000000009"
        const val GC_RECEIPT_ID = "72000000-0000-4000-8000-00000000000a"
        const val LIVE_CHECKPOINT_ID = "72000000-0000-4000-8000-00000000000c"
        const val FIRST_PROVISIONAL_TOMBSTONE_ID = "72000000-0000-4000-8000-00000000000d"
        const val SECOND_PROVISIONAL_TOMBSTONE_ID = "72000000-0000-4000-8000-00000000000e"
        const val SECOND_BLOB_ID = "72000000-0000-4000-8000-00000000000f"
        const val SECOND_MANIFEST_ID = "72000000-0000-4000-8000-000000000010"
        val HASH_1: String = "A".repeat(43)
        val HASH_2: String = "B".repeat(43)
        val HASH_3: String = "D".repeat(43)
        val SIGNATURE: String = "C".repeat(86)

        val SESSION = SyncSession(
            endpoint = "https://sync.example.test",
            instanceId = INSTANCE_ID,
            userId = USER_ID,
            workspaceId = WORKSPACE_ID,
            deviceId = DEVICE_ID,
            deviceDisplayName = "test",
            platform = "desktop",
            status = SyncSessionStatus.READY,
            deviceAuthEpoch = 1,
            membershipAuthEpoch = 1,
            activeKeyEpoch = 2,
        )
        val CAPABILITY = WorkspaceCapability(
            SecretMaterial("capability".encodeToByteArray().asList()),
            CapabilityBinding(DEVICE_ID, WORKSPACE_ID, 1, 1, 2, Long.MAX_VALUE),
        )
        val SESSION_B = SESSION.copy(deviceId = DEVICE_B_ID)
        val CAPABILITY_B = CAPABILITY.copy(
            binding = CAPABILITY.binding.copy(deviceId = DEVICE_B_ID),
        )
        val SESSION_EPOCH_3 = SESSION.copy(activeKeyEpoch = 3)
        val CAPABILITY_EPOCH_3 = CAPABILITY.copy(
            binding = CAPABILITY.binding.copy(keyEpoch = 3),
        )
        val BLOB = BlobRef(
            blobId = BLOB_ID,
            schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
            digestAlgorithm = BlobRef.SHA_256,
            plaintextDigest = "0".repeat(64),
            byteSize = 100,
            mediaType = "text/plain",
        )
        val REMOTE_MANIFEST = RemoteBlobBodyManifestRefV2(
            manifestId = MANIFEST_ID,
            blobId = BLOB_ID,
            manifestCiphertextSha256Base64Url = HASH_1,
            manifestCiphertextByteSize = 16,
            bodyCiphertextByteSize = 116,
            chunkCount = 1,
            chunkSizeBytes = RemoteBlobBodyManifestRefV2.MIN_CHUNK_BYTES,
            committedAtEpochMillis = 100,
            commitReceiptId = COMMIT_RECEIPT_ID,
        )
        val OLD_ENVELOPE = BlobDekEnvelopeV2(
            blobId = BLOB_ID,
            keyEpoch = 1,
            nonceBase64Url = "AQ",
            wrappedDekBase64Url = "Ag",
            envelopeSha256Base64Url = HASH_1,
        )
        val NEW_ENVELOPE = BlobDekEnvelopeV2(
            blobId = BLOB_ID,
            keyEpoch = 2,
            nonceBase64Url = "Aw",
            wrappedDekBase64Url = "BA",
            envelopeSha256Base64Url = HASH_2,
            previousEnvelopeSha256Base64Url = HASH_1,
        )
        val CHECKPOINT = RetainedCheckpointDescriptor(CHECKPOINT_ID, 20, 2, HASH_2)
        val EVIDENCE = BlobRewrapCheckpointEvidenceV2(CHECKPOINT_ID, HASH_2, 20)
        val REWRAP_REQUEST = BlobEnvelopeRewrapRequestV2(
            manifestId = MANIFEST_ID,
            envelope = NEW_ENVELOPE,
            checkpointEvidence = EVIDENCE,
        )
        val PREPARED_REWRAP = PreparedBlobEnvelopeRewrapV2(
            instanceId = INSTANCE_ID,
            workspaceId = WORKSPACE_ID,
            blobId = BLOB_ID,
            request = REWRAP_REQUEST,
        )
        val REWRAP_MUTATION = BlobDekEnvelopeRewrappedV2(BLOB_ID, MANIFEST_ID, NEW_ENVELOPE, EVIDENCE)
        val REWRAP_COMMITTED = DurableBlobLifecycleIntentV2.EnvelopeRewrap(
            PREPARED_REWRAP,
            REWRAP_MUTATION,
        )
        val TOMBSTONE = BlobTombstoneHandleV2(
            instanceId = INSTANCE_ID,
            workspaceId = WORKSPACE_ID,
            tombstoneId = TOMBSTONE_ID,
            blobId = BLOB_ID,
            manifestId = MANIFEST_ID,
            referenceThroughWorkspaceSeq = 20,
            requestedCreatedAtEpochMillis = 1_000,
            executeAfterEpochMillis = 2_000,
        )
        val ACK = BlobTombstoneAckRequestV2(
            tombstoneId = TOMBSTONE_ID,
            checkpointId = CHECKPOINT_ID,
            checkpointCiphertextSha256Base64Url = HASH_2,
            throughWorkspaceSeq = 20,
            signatureBase64Url = SIGNATURE,
        )
        val TOMBSTONE_ACK_PREPARED = DurableBlobLifecycleIntentV2.ReferenceTombstone(
            handle = TOMBSTONE,
            referenceCheckpoint = CHECKPOINT,
            createdOnWorker = true,
            creationDisposition = BlobTombstoneDispositionV2.ACTIVE,
            acknowledgement = ACK,
        )
        val GC_RECEIPT = BlobGcReceiptV2(GC_RECEIPT_ID, BLOB_ID, 3, 116, 2_000)
    }
}
