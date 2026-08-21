package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.sync.crypto.SodiumBlobBodyCryptoV2
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BlobLifecycleCoordinatorV2Test {
    @Test
    fun dekRewrapUsesActiveEpochAndReturnsExactMetadataMutation() = runTest {
        val secrets = InMemorySyncSecretStore().apply {
            write(
                SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 1),
                SecretMaterial(ByteArray(32) { (it + 1).toByte() }.asList()),
            )
            write(
                SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 2),
                SecretMaterial(ByteArray(32) { (it + 33).toByte() }.asList()),
            )
        }
        val blobCrypto = SodiumBlobBodyCryptoV2(secrets)
        val blob = blob()
        val oldSession = session(activeKeyEpoch = 1)
        val currentSession = session(activeKeyEpoch = 2)
        val uploadIntent = blobCrypto.createUploadIntent(
            session = oldSession,
            blob = blob,
            keyEpoch = 1,
            chunkSizeBytes = 64 * 1024,
            createdAtEpochMillis = 100,
        )
        val privateManifest = BlobPrivateManifestV2(blob = blob, chunkPlaintextByteSizes = emptyList())
        val encryptedManifest = blobCrypto.encryptPrivateManifest(oldSession, uploadIntent, privateManifest)
        val reference = syncedReference(
            present = true,
            envelope = uploadIntent.dekEnvelope,
            encryptedManifest = encryptedManifest,
        )
        val api = RecordingBlobBodyApi()
        val coordinator = coordinator(api, blobCrypto)

        val prepared = assertNotNull(
            coordinator.prepareEnvelopeRewrap(currentSession, reference, checkpoint(throughSeq = 30, keyEpoch = 2)),
        )

        assertEquals(2, prepared.request.envelope.keyEpoch)
        assertEquals(
            uploadIntent.dekEnvelope.envelopeSha256Base64Url,
            prepared.request.envelope.previousEnvelopeSha256Base64Url,
        )
        assertEquals(CHECKPOINT_ID, prepared.request.checkpointEvidence.checkpointId)
        assertEquals(30, prepared.request.checkpointEvidence.throughWorkspaceSeq)
        assertEquals(
            privateManifest,
            blobCrypto.decryptPrivateManifest(
                currentSession,
                encryptedManifest,
                prepared.request.envelope,
                uploadIntent.manifestId,
            ),
        )

        val recoveredPreparation = LIFECYCLE_JSON.decodeFromString<PreparedBlobEnvelopeRewrapV2>(
            LIFECYCLE_JSON.encodeToString(prepared),
        )
        assertEquals(prepared, recoveredPreparation)
        val mutation = coordinator.commitEnvelopeRewrap(currentSession, capability(2), recoveredPreparation)

        assertEquals(prepared.request.envelope, api.rewrapRequest?.envelope)
        assertEquals(prepared.request.envelope, mutation.envelope)
        assertEquals(MANIFEST_ID, mutation.manifestId)
        assertEquals(prepared.request.checkpointEvidence, mutation.checkpointEvidence)

        val alreadyCurrent = reference.copy(
            dekEnvelopes = reference.dekEnvelopes +
                (2 to LwwRegister(mutation.envelope, HlcTimestamp(2, 0, DEVICE_ID))),
        )
        assertNull(
            coordinator.prepareEnvelopeRewrap(
                currentSession,
                alreadyCurrent,
                checkpoint(throughSeq = 30, keyEpoch = 2),
            ),
        )
        assertEquals(1, api.rewrapCalls)
    }

    @Test
    fun rewrapRejectsAWorkerEnvelopeThatDiffersFromDurablePreparation() = runTest {
        val secrets = InMemorySyncSecretStore().apply {
            write(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 1), SecretMaterial(ByteArray(32) { 1 }.asList()))
            write(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 2), SecretMaterial(ByteArray(32) { 2 }.asList()))
        }
        val blobCrypto = SodiumBlobBodyCryptoV2(secrets)
        val initial = blobCrypto.createUploadIntent(session(1), blob(), 1, 64 * 1024, 1).dekEnvelope
        val reference = syncedReference(present = true, envelope = initial)
        val api = RecordingBlobBodyApi().apply { alterRewrapResponse = true }
        val coordinator = coordinator(api, blobCrypto)
        val prepared = assertNotNull(
            coordinator.prepareEnvelopeRewrap(session(2), reference, checkpoint(20, 2)),
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.commitEnvelopeRewrap(session(2), capability(2), prepared)
        }
        assertEquals(1, api.rewrapCalls)
    }

    @Test
    fun tombstoneAckSignsExactWorkerV2MessageThenRequestsServerGc() = runTest {
        val order = mutableListOf<String>()
        val signer = RecordingSyncCrypto(order)
        val api = RecordingBlobBodyApi(order)
        val coordinator = coordinator(
            api = api,
            blobCrypto = SodiumBlobBodyCryptoV2(InMemorySyncSecretStore()),
            syncCrypto = signer,
        )
        val activeSession = session(activeKeyEpoch = 2)
        val tombstone = coordinator.prepareTombstone(
            session = activeSession,
            reference = syncedReference(present = false, envelope = fakeEnvelope(keyEpoch = 2)),
            referenceThroughWorkspaceSeq = 70,
        )

        assertEquals(TOMBSTONE_ID, tombstone.tombstoneId)
        val recoveredTombstone = LIFECYCLE_JSON.decodeFromString<BlobTombstoneHandleV2>(
            LIFECYCLE_JSON.encodeToString(tombstone),
        )
        assertEquals(tombstone, recoveredTombstone)
        val creation = coordinator.createTombstone(activeSession, capability(2), recoveredTombstone)
        val canonicalTombstone = creation.handle
        assertEquals(BlobTombstoneDispositionV2.ACTIVE, creation.disposition)
        assertNotNull(canonicalTombstone.executeAfterEpochMillis)
        val acknowledgement = coordinator.acknowledgeTombstone(
            activeSession,
            capability(2),
            canonicalTombstone,
            checkpoint(throughSeq = 71, keyEpoch = 2),
        )
        val receipt = coordinator.garbageCollect(activeSession, capability(2), canonicalTombstone)

        assertEquals(listOf("create", "sign", "ack", "gc"), order)
        assertEquals(tombstone.request(), api.tombstoneRequest)
        assertEquals(acknowledgement, api.ackRequest)
        assertEquals(71, acknowledgement.throughWorkspaceSeq)
        assertEquals(BLOB_ID, receipt.blobId)
        assertEquals(1, receipt.deletedObjectCount)

        val canonical =
            "{\"blobId\":\"$BLOB_ID\"," +
                "\"checkpointCiphertextSha256Base64Url\":\"$CHECKPOINT_HASH\"," +
                "\"checkpointId\":\"$CHECKPOINT_ID\"," +
                "\"instanceId\":\"$INSTANCE_ID\"," +
                "\"protocolVersion\":2,\"schemaVersion\":2," +
                "\"throughWorkspaceSeq\":71," +
                "\"tombstoneId\":\"$TOMBSTONE_ID\"," +
                "\"validatorDeviceId\":\"$DEVICE_ID\"," +
                "\"workspaceId\":\"$WORKSPACE_ID\"}"
        assertContentEquals(
            "shinsou:blob-tombstone-ack:v2\u0000".encodeToByteArray() + canonical.encodeToByteArray(),
            assertNotNull(signer.signedMessage).copyBytes(),
        )
    }

    @Test
    fun liveReferenceRevivalSignsStrictlyNewerCheckpointBeforeWorkerCancellation() = runTest {
        val order = mutableListOf<String>()
        val signer = RecordingSyncCrypto(order)
        val api = RecordingBlobBodyApi(order)
        val coordinator = coordinator(
            api = api,
            blobCrypto = SodiumBlobBodyCryptoV2(InMemorySyncSecretStore()),
            syncCrypto = signer,
        )
        val activeSession = session(activeKeyEpoch = 2)
        val provisional = coordinator.prepareTombstone(
            session = activeSession,
            reference = syncedReference(present = false, envelope = fakeEnvelope(keyEpoch = 2)),
            referenceThroughWorkspaceSeq = 70,
        )
        val canonical = coordinator.createTombstone(
            activeSession,
            capability(2),
            provisional,
        ).handle

        val request = coordinator.prepareReferenceRevival(
            session = activeSession,
            tombstone = canonical,
            reference = syncedReference(present = true, envelope = fakeEnvelope(keyEpoch = 2)),
            stableCheckpoint = checkpoint(throughSeq = 71, keyEpoch = 2),
        )
        val result = coordinator.commitReferenceRevival(
            activeSession,
            capability(2),
            canonical,
            request,
        )

        assertEquals(listOf("create", "sign", "revive"), order)
        assertEquals(request, api.revivalRequest)
        assertEquals(BlobTombstoneDispositionV2.CANCELLED, result.disposition)
        val canonicalJson =
            "{\"blobId\":\"$BLOB_ID\"," +
                "\"checkpointCiphertextSha256Base64Url\":\"$CHECKPOINT_HASH\"," +
                "\"checkpointId\":\"$CHECKPOINT_ID\"," +
                "\"instanceId\":\"$INSTANCE_ID\"," +
                "\"manifestId\":\"$MANIFEST_ID\"," +
                "\"protocolVersion\":2,\"schemaVersion\":2," +
                "\"throughWorkspaceSeq\":71," +
                "\"tombstoneId\":\"$TOMBSTONE_ID\"," +
                "\"validatorDeviceId\":\"$DEVICE_ID\"," +
                "\"workspaceId\":\"$WORKSPACE_ID\"}"
        assertContentEquals(
            "shinsou:blob-tombstone-revival:v2\u0000".encodeToByteArray() +
                canonicalJson.encodeToByteArray(),
            assertNotNull(signer.signedMessage).copyBytes(),
        )
    }

    @Test
    fun tombstoneRequiresCommittedAbsenceAndACoveringStableCheckpoint() = runTest {
        val order = mutableListOf<String>()
        val signer = RecordingSyncCrypto(order)
        val api = RecordingBlobBodyApi(order)
        val coordinator = coordinator(
            api,
            SodiumBlobBodyCryptoV2(InMemorySyncSecretStore()),
            signer,
        )
        val activeSession = session(2)
        assertFailsWith<IllegalArgumentException> {
            coordinator.prepareTombstone(
                activeSession,
                syncedReference(present = true, envelope = fakeEnvelope(2)),
                referenceThroughWorkspaceSeq = 80,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator.prepareTombstone(
                activeSession,
                syncedReference(present = false, envelope = fakeEnvelope(1)),
                referenceThroughWorkspaceSeq = 80,
            )
        }
        val tombstone = coordinator.prepareTombstone(
            activeSession,
            syncedReference(present = false, envelope = fakeEnvelope(2)),
            referenceThroughWorkspaceSeq = 80,
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.acknowledgeTombstone(
                activeSession,
                capability(2),
                tombstone,
                checkpoint(throughSeq = 79, keyEpoch = 2),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator.acknowledgeTombstone(
                activeSession,
                capability(2),
                tombstone,
                checkpoint(throughSeq = 80, keyEpoch = 1),
            )
        }

        assertNull(signer.signedMessage)
        assertNull(api.ackRequest)
        assertTrue(order.isEmpty())
    }

    private fun coordinator(
        api: RecordingBlobBodyApi,
        blobCrypto: BlobBodyCryptoV2,
        syncCrypto: RecordingSyncCrypto = RecordingSyncCrypto(),
    ): BlobLifecycleCoordinatorV2 = BlobLifecycleCoordinatorV2(
        bodyApi = api,
        blobCrypto = blobCrypto,
        syncCrypto = syncCrypto,
        nowEpochMillis = { 1_000 },
        newTombstoneId = { TOMBSTONE_ID },
    )

    private fun syncedReference(
        present: Boolean,
        envelope: BlobDekEnvelopeV2,
        encryptedManifest: EncryptedBlobPrivateManifestV2? = null,
    ): SyncedBlobReferenceRecord {
        val timestamp = HlcTimestamp(1, 0, DEVICE_ID)
        return SyncedBlobReferenceRecord(
            blobId = BLOB_ID,
            blob = LwwRegister(blob(), timestamp),
            remoteManifest = LwwRegister(remoteManifest(encryptedManifest), timestamp),
            dekEnvelopes = mapOf(envelope.keyEpoch to LwwRegister(envelope, timestamp)),
            presence = LwwRegister(present, timestamp),
        )
    }

    private fun blob(): BlobRef = BlobRef(
        blobId = BLOB_ID,
        schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
        digestAlgorithm = BlobRef.SHA_256,
        plaintextDigest = "0".repeat(64),
        byteSize = 0,
        mediaType = "text/plain",
    )

    private fun remoteManifest(encrypted: EncryptedBlobPrivateManifestV2? = null): RemoteBlobBodyManifestRefV2 =
        RemoteBlobBodyManifestRefV2(
            manifestId = MANIFEST_ID,
            blobId = BLOB_ID,
            manifestCiphertextSha256Base64Url = encrypted?.ciphertextSha256Base64Url ?: MANIFEST_HASH,
            manifestCiphertextByteSize = encrypted?.ciphertextByteSize?.toLong() ?: 16,
            bodyCiphertextByteSize = 0,
            chunkCount = 0,
            chunkSizeBytes = 64 * 1024,
            committedAtEpochMillis = 100,
            commitReceiptId = COMMIT_RECEIPT_ID,
        )

    private fun fakeEnvelope(keyEpoch: Int): BlobDekEnvelopeV2 = BlobDekEnvelopeV2(
        blobId = BLOB_ID,
        keyEpoch = keyEpoch,
        nonceBase64Url = "AQ",
        wrappedDekBase64Url = "Ag",
        envelopeSha256Base64Url = if (keyEpoch == 1) ENVELOPE_HASH_1 else ENVELOPE_HASH_2,
        previousEnvelopeSha256Base64Url = if (keyEpoch == 1) null else ENVELOPE_HASH_1,
    )

    private fun checkpoint(throughSeq: Long, keyEpoch: Int): RetainedCheckpointDescriptor =
        RetainedCheckpointDescriptor(
            checkpointId = CHECKPOINT_ID,
            throughWorkspaceSeq = throughSeq,
            keyEpoch = keyEpoch,
            ciphertextSha256Base64Url = CHECKPOINT_HASH,
        )

    private fun session(activeKeyEpoch: Int): SyncSession = SyncSession(
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
        activeKeyEpoch = activeKeyEpoch,
    )

    private fun capability(keyEpoch: Int): WorkspaceCapability = WorkspaceCapability(
        token = SecretMaterial("capability".encodeToByteArray().asList()),
        binding = CapabilityBinding(
            deviceId = DEVICE_ID,
            workspaceId = WORKSPACE_ID,
            deviceAuthEpoch = 1,
            membershipAuthEpoch = 1,
            keyEpoch = keyEpoch,
            expiresAtMillis = Long.MAX_VALUE,
        ),
    )

    private class RecordingSyncCrypto(
        private val order: MutableList<String>? = null,
    ) : SyncCrypto {
        var signedMessage: BinaryData? = null

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

        override suspend fun generateCheckpointId(): String = TOMBSTONE_ID
        override suspend fun generateWorkspaceEpochKey(): SecretMaterial = error("unused")
        override suspend fun keyCommitment(material: SecretMaterial): BinaryData = error("unused")
        override suspend fun wrapWorkspaceKey(
            material: SecretMaterial,
            recipientPublicKey: BinaryData,
        ): BinaryData = error("unused")

        override suspend fun signDeviceMessage(message: BinaryData): BinaryData {
            order?.add("sign")
            signedMessage = message
            return BinaryData.copyOf(ByteArray(64) { it.toByte() })
        }

        override suspend fun verifyDeviceSignature(
            message: BinaryData,
            signature: BinaryData,
            publicKey: BinaryData,
        ): Boolean = error("unused")
    }

    private class RecordingBlobBodyApi(
        private val order: MutableList<String>? = null,
    ) : CloudflareBlobBodyApiV2 {
        var rewrapCalls: Int = 0
        var rewrapRequest: BlobEnvelopeRewrapRequestV2? = null
        var tombstoneRequest: BlobReferenceTombstoneRequestV2? = null
        var ackRequest: BlobTombstoneAckRequestV2? = null
        var revivalRequest: BlobReferenceRevivalRequestV2? = null
        var alterRewrapResponse: Boolean = false

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
        ): BlobDekEnvelopeV2 {
            rewrapCalls += 1
            rewrapRequest = request
            return if (alterRewrapResponse) {
                request.envelope.copy(wrappedDekBase64Url = "AQ")
            } else {
                request.envelope
            }
        }

        override suspend fun createTombstone(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobReferenceTombstoneRequestV2,
        ): BlobTombstoneCreationResultV2 {
            order?.add("create")
            tombstoneRequest = request
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
            order?.add("revive")
            revivalRequest = request
            return BlobReferenceRevivalResultV2(
                tombstoneId = request.tombstoneId,
                blobId = request.blobId,
                manifestId = request.manifestId,
                disposition = BlobTombstoneDispositionV2.CANCELLED,
                cancelledAtEpochMillis = 2_000,
            )
        }

        override suspend fun acknowledgeTombstone(
            session: SyncSession,
            capability: WorkspaceCapability,
            blobId: String,
            request: BlobTombstoneAckRequestV2,
        ) {
            order?.add("ack")
            ackRequest = request
        }

        override suspend fun garbageCollect(
            session: SyncSession,
            capability: WorkspaceCapability,
            request: BlobGcRequestV2,
        ): BlobGcReceiptV2 {
            order?.add("gc")
            assertEquals(BLOB_ID, request.blobId)
            assertEquals(TOMBSTONE_ID, request.tombstoneId)
            return BlobGcReceiptV2(
                receiptId = GC_RECEIPT_ID,
                blobId = request.blobId,
                deletedObjectCount = 1,
                deletedCiphertextBytes = 16,
                completedAtEpochMillis = 2_000,
            )
        }
    }

    private companion object {
        val LIFECYCLE_JSON: Json = Json { encodeDefaults = true }
        const val INSTANCE_ID = "10000000-0000-4000-8000-000000000001"
        const val USER_ID = "10000000-0000-4000-8000-000000000002"
        const val WORKSPACE_ID = "10000000-0000-4000-8000-000000000003"
        const val DEVICE_ID = "10000000-0000-4000-8000-000000000004"
        const val BLOB_ID = "20000000-0000-4000-8000-000000000001"
        const val MANIFEST_ID = "20000000-0000-4000-8000-000000000002"
        const val COMMIT_RECEIPT_ID = "20000000-0000-4000-8000-000000000003"
        const val CHECKPOINT_ID = "30000000-0000-4000-8000-000000000001"
        const val TOMBSTONE_ID = "30000000-0000-4000-8000-000000000002"
        const val GC_RECEIPT_ID = "30000000-0000-4000-8000-000000000003"
        val MANIFEST_HASH: String = "A".repeat(43)
        val CHECKPOINT_HASH: String = "B".repeat(43)
        val ENVELOPE_HASH_1: String = "C".repeat(43)
        val ENVELOPE_HASH_2: String = "D".repeat(43)
    }
}
