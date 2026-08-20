package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import dev.shinsou.kmp.sync.network.SyncApiException
import dev.shinsou.kmp.sync.network.encodeBase64Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncCheckpointCoordinatorTest {
    private val codec = DeterministicSyncEventCodec()
    private val manga = SyncEntityKey.manga("1", "/checkpoint")
    private val session = SyncSession(
        endpoint = "https://sync.example",
        instanceId = "instance",
        userId = "user",
        workspaceId = "workspace",
        deviceId = "validator",
        deviceDisplayName = "Desktop",
        platform = "desktop",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = 1,
    )
    private val capability = WorkspaceCapability(
        token = SecretMaterial(listOf(1)),
        binding = CapabilityBinding("validator", "workspace", 1, 1, 1, 10_000),
    )

    @Test
    fun independentlyReplaysCandidateBeforeAcknowledgingIt() = runTest {
        val event1 = titleEvent("one", 1, "one")
        val event2 = titleEvent("two", 2, "two")
        val previousState = SyncReducer.reduceCommitted(SyncState(), CommittedSyncEvent(1, event1))
        val candidateState = SyncReducer.reduceCommitted(
            previousState.copy(previousStableCheckpointHash = "previous-hash"),
            CommittedSyncEvent(2, event2),
        )
        val candidate = candidate(previousHash = "previous-hash")
        val crypto = FakeCrypto(codec, mapOf("previous" to previousState, "candidate" to candidateState), event2)
        val api = FakeApi(bootstrap(candidate), crypto, event2)
        val coordinator = SyncCheckpointCoordinator(api, crypto, codec, InMemoryLocalSyncStore())

        val outcome = coordinator.coordinate(session, capability)

        assertEquals(CheckpointCoordinatorOutcome.VALIDATED, outcome)
        val acknowledgement = assertNotNull(api.acknowledgement)
        assertTrue(acknowledgement.valid)
        assertEquals(1, acknowledgement.replayFromSeq)
        assertEquals(2, acknowledgement.replayedThroughSeq)
        assertEquals(1, acknowledgement.replayedEventCount)
        assertTrue(crypto.signedMessage.copyBytes().decodeToString().startsWith("shinsou:checkpoint-ack:v1\u0000{"))
    }

    @Test
    fun semanticMismatchRejectsCandidateWithoutInstallingIt() = runTest {
        val event1 = titleEvent("one", 1, "one")
        val event2 = titleEvent("two", 2, "two")
        val previousState = SyncReducer.reduceCommitted(SyncState(), CommittedSyncEvent(1, event1))
        val tampered = previousState.copy(
            throughWorkspaceSeq = 2,
            previousStableCheckpointHash = "previous-hash",
        )
        val candidate = candidate(previousHash = "previous-hash")
        val crypto = FakeCrypto(codec, mapOf("previous" to previousState, "candidate" to tampered), event2)
        val api = FakeApi(bootstrap(candidate), crypto, event2, invalidStatus = RemoteCheckpointStatus.REJECTED)
        val store = InMemoryLocalSyncStore()
        val before = store.readState()

        val outcome = SyncCheckpointCoordinator(api, crypto, codec, store).coordinate(session, capability)

        assertEquals(CheckpointCoordinatorOutcome.REJECTED, outcome)
        assertFalse(assertNotNull(api.acknowledgement).valid)
        assertEquals(before, store.readState())
    }

    @Test
    fun corruptDownloadedCandidateProducesSignedInvalidAckWithExactTailCount() = runTest {
        val event1 = titleEvent("one", 1, "one")
        val event2 = titleEvent("two", 2, "two")
        val previousState = SyncReducer.reduceCommitted(SyncState(), CommittedSyncEvent(1, event1))
        val candidate = candidate(previousHash = "previous-hash")
        val crypto = FakeCrypto(
            codec = codec,
            checkpointStates = mapOf("previous" to previousState),
            tailEvent = event2,
            checkpointFailures = mapOf(
                "candidate" to RemoteCheckpointVerificationException("ciphertext hash mismatch"),
            ),
        )
        val api = FakeApi(bootstrap(candidate), crypto, event2, invalidStatus = RemoteCheckpointStatus.REJECTED)

        val outcome = SyncCheckpointCoordinator(api, crypto, codec, InMemoryLocalSyncStore())
            .coordinate(session, capability)

        assertEquals(CheckpointCoordinatorOutcome.REJECTED, outcome)
        val acknowledgement = assertNotNull(api.acknowledgement)
        assertFalse(acknowledgement.valid)
        assertEquals(1, acknowledgement.replayFromSeq)
        assertEquals(2, acknowledgement.replayedThroughSeq)
        assertEquals(1, acknowledgement.replayedEventCount)
        assertTrue(crypto.signedMessage.size > 0)
    }

    @Test
    fun localSecretFailureIsNotMisreportedAsCandidateCorruption() = runTest {
        val event1 = titleEvent("one", 1, "one")
        val event2 = titleEvent("two", 2, "two")
        val previousState = SyncReducer.reduceCommitted(SyncState(), CommittedSyncEvent(1, event1))
        val candidate = candidate(previousHash = "previous-hash")
        val localFailure = SyncSecretAccessException.Unavailable(
            SyncSecretKey.WorkspaceEpochKey(session.workspaceId, 1),
            "keychain locked",
        )
        val crypto = FakeCrypto(
            codec = codec,
            checkpointStates = mapOf("previous" to previousState),
            tailEvent = event2,
            checkpointFailures = mapOf("candidate" to localFailure),
        )
        val api = FakeApi(bootstrap(candidate), crypto, event2, invalidStatus = RemoteCheckpointStatus.REJECTED)

        assertFailsWith<SyncSecretAccessException.Unavailable> {
            SyncCheckpointCoordinator(api, crypto, codec, InMemoryLocalSyncStore())
                .coordinate(session, capability)
        }
        assertEquals(null, api.acknowledgement)
    }

    @Test
    fun checkpointDownloadFailureIsNotMisreportedAsCandidateCorruption() = runTest {
        val event1 = titleEvent("one", 1, "one")
        val event2 = titleEvent("two", 2, "two")
        val previousState = SyncReducer.reduceCommitted(SyncState(), CommittedSyncEvent(1, event1))
        val candidate = candidate(previousHash = "previous-hash")
        val crypto = FakeCrypto(codec, mapOf("previous" to previousState), event2)
        val api = FakeApi(
            bootstrap = bootstrap(candidate),
            crypto = crypto,
            tailEvent = event2,
            downloadFailures = mapOf("candidate" to SyncApiException(503, "r2_unavailable")),
        )

        assertFailsWith<SyncApiException> {
            SyncCheckpointCoordinator(api, crypto, codec, InMemoryLocalSyncStore())
                .coordinate(session, capability)
        }
        assertEquals(null, api.acknowledgement)
    }

    @Test
    fun checkpointRotationRequiredIsSurfacedAsTypedEngineSignal() = runTest {
        val event1 = titleEvent("one", 1, "one")
        val event2 = titleEvent("two", 2, "two")
        val previousState = SyncReducer.reduceCommitted(SyncState(), CommittedSyncEvent(1, event1))
        val candidate = candidate(previousHash = "previous-hash")
        val crypto = FakeCrypto(codec, mapOf("previous" to previousState), event2)
        val api = FakeApi(
            bootstrap = bootstrap(candidate),
            crypto = crypto,
            tailEvent = event2,
            downloadFailures = mapOf(
                "candidate" to SyncApiException(409, "checkpoint_rotation_required"),
            ),
        )

        assertFailsWith<KeyRotationRequiredException> {
            SyncCheckpointCoordinator(api, crypto, codec, InMemoryLocalSyncStore())
                .coordinate(session, capability)
        }
        assertEquals(null, api.acknowledgement)
    }

    @Test
    fun firstWorkspaceCheckpointIsSealedUploadedAndCommitted() = runTest {
        val store = InMemoryLocalSyncStore()
        val crypto = FakeCrypto(codec, emptyMap(), null)
        val api = FakeApi(
            BootstrapResponse(
                headSeq = 0,
                activeKeyEpoch = 1,
                retainedStableCheckpoints = emptyList(),
                requiredKeyEpochs = setOf(1),
            ),
            crypto,
            null,
        )

        val outcome = SyncCheckpointCoordinator(api, crypto, codec, store).coordinate(session, capability)

        assertEquals(CheckpointCoordinatorOutcome.PROPOSED, outcome)
        assertEquals("new-checkpoint", api.createdLease?.checkpointId)
        assertTrue(api.uploaded)
        assertTrue(api.committed)
        assertEquals(0, crypto.sealedState?.throughWorkspaceSeq)
        assertEquals(1, crypto.sealedState?.keyEpoch)
    }

    @Test
    fun sameWatermarkStableChainUsesServerPromotionOrderNotRandomUuidOrder() = runTest {
        val store = InMemoryLocalSyncStore(
            LocalSyncStoreState(
                replica = SyncState(throughWorkspaceSeq = 600),
            ),
        )
        val crypto = FakeCrypto(codec, emptyMap(), null)
        val api = FakeApi(
            BootstrapResponse(
                headSeq = 600,
                activeKeyEpoch = 1,
                // The Worker orders equal watermarks by promoted_at DESC. The older checkpoint's
                // random UUID is deliberately lexicographically greater to catch client sorting.
                retainedStableCheckpoints = listOf(
                    RetainedCheckpointDescriptor("00000000-newest", 100, 1, "newest-hash", null),
                    RetainedCheckpointDescriptor("ffffffff-older", 100, 1, "older-hash", null),
                ),
                requiredKeyEpochs = setOf(1),
            ),
            crypto,
            null,
        )

        val outcome = SyncCheckpointCoordinator(api, crypto, codec, store).coordinate(session, capability)

        assertEquals(CheckpointCoordinatorOutcome.PROPOSED, outcome)
        assertEquals("newest-hash", crypto.sealedState?.previousStableCheckpointHash)
    }

    @Test
    fun checkpointZeroUsesOnlyTheMatchingDurableGenesisSeed() = runTest {
        val genesisState = SyncReducer.reduce(SyncState(), titleEvent("genesis", 1, "local snapshot"))
        val store = InMemoryLocalSyncStore(
            LocalSyncStoreState(
                replica = genesisState,
                genesisCheckpointSeed = GenesisCheckpointSeed("uploader", 1),
            ),
        )
        val candidate = CheckpointCandidateDescriptor(
            checkpointId = "candidate",
            throughWorkspaceSeq = 0,
            keyEpoch = 1,
            ciphertextSha256Base64Url = "candidate-hash",
            uploaderDeviceId = "uploader",
            createdAtMillis = 1,
        )
        val bootstrap = BootstrapResponse(
            headSeq = 0,
            activeKeyEpoch = 1,
            retainedStableCheckpoints = emptyList(),
            requiredKeyEpochs = setOf(1),
            candidateCheckpoint = candidate,
        )
        val crypto = FakeCrypto(codec, mapOf("candidate" to genesisState), null)
        val api = FakeApi(bootstrap, crypto, null)

        val outcome = SyncCheckpointCoordinator(api, crypto, codec, store).coordinate(
            session.copy(deviceId = "uploader"),
            capability,
        )

        assertEquals(CheckpointCoordinatorOutcome.VALIDATED, outcome)
        val acknowledgement = assertNotNull(api.acknowledgement)
        assertTrue(acknowledgement.valid)
        assertEquals(0, acknowledgement.replayedEventCount)
        assertEquals(0, acknowledgement.replayFromSeq)
        assertEquals(0, acknowledgement.replayedThroughSeq)
    }

    @Test
    fun checkpointZeroFromAnotherUploaderCannotUseTheLocalGenesisException() = runTest {
        val genesisState = SyncReducer.reduce(SyncState(), titleEvent("genesis", 1, "local snapshot"))
        val store = InMemoryLocalSyncStore(
            LocalSyncStoreState(
                replica = genesisState,
                genesisCheckpointSeed = GenesisCheckpointSeed("uploader", 1),
            ),
        )
        val candidate = CheckpointCandidateDescriptor(
            checkpointId = "candidate",
            throughWorkspaceSeq = 0,
            keyEpoch = 1,
            ciphertextSha256Base64Url = "candidate-hash",
            uploaderDeviceId = "attacker",
            createdAtMillis = 1,
        )
        val bootstrap = BootstrapResponse(
            headSeq = 0,
            activeKeyEpoch = 1,
            retainedStableCheckpoints = emptyList(),
            requiredKeyEpochs = setOf(1),
            candidateCheckpoint = candidate,
        )
        val crypto = FakeCrypto(codec, mapOf("candidate" to genesisState), null)
        val api = FakeApi(bootstrap, crypto, null, invalidStatus = RemoteCheckpointStatus.REJECTED)

        val outcome = SyncCheckpointCoordinator(api, crypto, codec, store).coordinate(
            session.copy(deviceId = "uploader"),
            capability,
        )

        assertEquals(CheckpointCoordinatorOutcome.REJECTED, outcome)
        assertFalse(assertNotNull(api.acknowledgement).valid)
    }

    private fun bootstrap(candidate: CheckpointCandidateDescriptor) = BootstrapResponse(
        headSeq = 2,
        activeKeyEpoch = 1,
        retainedStableCheckpoints = listOf(
            RetainedCheckpointDescriptor("previous", 1, 1, "previous-hash", null),
        ),
        requiredKeyEpochs = setOf(1),
        candidateCheckpoint = candidate,
    )

    private fun candidate(previousHash: String?) = CheckpointCandidateDescriptor(
        checkpointId = "candidate",
        throughWorkspaceSeq = 2,
        keyEpoch = 1,
        ciphertextSha256Base64Url = "candidate-hash",
        uploaderDeviceId = "uploader",
        createdAtMillis = 1,
        previousStableCheckpointId = previousHash?.let { "previous" },
        previousStableThroughWorkspaceSeq = if (previousHash == null) 0 else 1,
        previousStableCiphertextSha256Base64Url = previousHash,
    )

    private fun titleEvent(id: String, sequence: Long, title: String) = SyncEvent(
        opId = id,
        hlc = HlcTimestamp(sequence, 0, "remote"),
        mutations = listOf(
            LibraryEntryPatch(
                manga,
                mapOf(SyncFields.Manga.TITLE to SyncValue.StringValue(title)),
            ),
        ),
    )

    private class FakeApi(
        private val bootstrap: BootstrapResponse,
        private val crypto: FakeCrypto,
        private val tailEvent: SyncEvent?,
        private val invalidStatus: RemoteCheckpointStatus = RemoteCheckpointStatus.STABLE,
        private val downloadFailures: Map<String, Throwable> = emptyMap(),
    ) : CloudflareSyncApi {
        var acknowledgement: CheckpointReplayAcknowledgement? = null
        var createdLease: CheckpointLease? = null
        var uploaded = false
        var committed = false

        override suspend fun capabilities(endpoint: String) = error("unused")
        override suspend fun obtainWorkspaceCapability(session: SyncSession) = error("unused")
        override suspend fun appendEvent(
            session: SyncSession,
            capability: WorkspaceCapability,
            event: EncryptedSyncEvent,
        ) = error("unused")

        override suspend fun eventReceipt(
            session: SyncSession,
            capability: WorkspaceCapability,
            deviceSeq: Long,
        ): SyncReceipt? = null

        override suspend fun catchUp(
            session: SyncSession,
            capability: WorkspaceCapability,
            afterExclusive: Long,
            untilInclusive: Long?,
            limit: Int,
        ): CatchUpPage {
            val fixed = requireNotNull(untilInclusive)
            val events = if (tailEvent != null && afterExclusive < 2 && fixed >= 2) {
                listOf(RemoteCommittedEnvelope(2, crypto.eventEnvelope(tailEvent)))
            } else {
                emptyList()
            }
            val next = events.lastOrNull()?.workspaceSeq ?: afterExclusive
            return CatchUpPage(afterExclusive, fixed, next, next < fixed, bootstrap.headSeq, 1, events)
        }

        override suspend fun bootstrap(
            session: SyncSession,
            capability: WorkspaceCapability,
        ): BootstrapResponse = bootstrap

        override suspend fun downloadCheckpoint(
            session: SyncSession,
            capability: WorkspaceCapability,
            descriptor: RetainedCheckpointDescriptor,
        ): EncryptedSyncCheckpoint {
            downloadFailures[descriptor.checkpointId]?.let { throw it }
            return crypto.encryptedCheckpoint(descriptor)
        }

        override suspend fun createCheckpointLease(
            session: SyncSession,
            capability: WorkspaceCapability,
            checkpointId: String,
            ciphertextSha256Base64Url: String,
            expectedByteSize: Int,
            throughWorkspaceSeq: Long,
        ): CheckpointLease = CheckpointLease(
            leaseId = "lease",
            checkpointId = checkpointId,
            ciphertextSha256Base64Url = ciphertextSha256Base64Url,
            throughWorkspaceSeq = throughWorkspaceSeq,
            keyEpoch = 1,
            expiresAtMillis = 10_000,
        ).also { createdLease = it }

        override suspend fun uploadCheckpoint(
            session: SyncSession,
            capability: WorkspaceCapability,
            lease: CheckpointLease,
            checkpoint: EncryptedSyncCheckpoint,
        ) {
            uploaded = true
        }

        override suspend fun commitCheckpoint(
            session: SyncSession,
            capability: WorkspaceCapability,
            lease: CheckpointLease,
        ): CheckpointCandidateDescriptor {
            committed = true
            return CheckpointCandidateDescriptor(
                lease.checkpointId,
                lease.throughWorkspaceSeq,
                lease.keyEpoch,
                lease.ciphertextSha256Base64Url,
                session.deviceId,
                1,
            )
        }

        override suspend fun acknowledgeCheckpoint(
            session: SyncSession,
            capability: WorkspaceCapability,
            acknowledgement: CheckpointReplayAcknowledgement,
        ): CheckpointAcknowledgementResult {
            this.acknowledgement = acknowledgement
            return CheckpointAcknowledgementResult(
                acknowledgement.checkpointId,
                acknowledgement.ciphertextSha256Base64Url,
                if (acknowledgement.valid) RemoteCheckpointStatus.STABLE else invalidStatus,
                acknowledgement.replayedThroughSeq,
            )
        }
    }

    private class FakeCrypto(
        private val codec: SyncEventCodec,
        private val checkpointStates: Map<String, SyncState>,
        private val tailEvent: SyncEvent?,
        private val checkpointFailures: Map<String, Throwable> = emptyMap(),
    ) : SyncCrypto {
        var signedMessage: BinaryData = BinaryData.Empty
        var sealedState: SyncState? = null

        fun eventEnvelope(event: SyncEvent) = EncryptedSyncEvent(
            SyncEventHeader(
                cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
                nonceBase64Url = "nonce",
                instanceId = "instance",
                workspaceId = "workspace",
                eventId = "event-two",
                deviceId = "remote",
                deviceSeq = 2,
                keyEpoch = 1,
                ciphertextSha256Base64Url = "event-hash",
            ),
            "header",
            "ciphertext",
            "signature",
        )

        fun encryptedCheckpoint(descriptor: RetainedCheckpointDescriptor) = EncryptedSyncCheckpoint(
            checkpointHeader(descriptor),
            "header-${descriptor.checkpointId}",
            "AQ",
            "signature-${descriptor.checkpointId}",
        )

        override suspend fun prepareEventSealer(session: SyncSession, keyEpoch: Int) = error("unused")

        override suspend fun openAndVerifyEvent(
            session: SyncSession,
            remote: RemoteCommittedEnvelope,
        ) = OpenedRemoteEvent(requireNotNull(tailEvent), BinaryData.Empty)

        override suspend fun openAndVerifyCheckpoint(
            session: SyncSession,
            checkpoint: EncryptedSyncCheckpoint,
            descriptor: RetainedCheckpointDescriptor,
        ): VerifiedSyncCheckpoint {
            checkpointFailures[descriptor.checkpointId]?.let { throw it }
            val state = requireNotNull(checkpointStates[descriptor.checkpointId]).normalized()
            return VerifiedSyncCheckpoint(checkpointHeader(descriptor), state, codec.canonicalCheckpointState(state))
        }

        override suspend fun sealCheckpoint(
            session: SyncSession,
            checkpointId: String,
            state: SyncState,
            previousStableCiphertextSha256Base64Url: String?,
        ): EncryptedSyncCheckpoint {
            sealedState = state
            val descriptor = RetainedCheckpointDescriptor(
                checkpointId,
                state.throughWorkspaceSeq,
                state.keyEpoch,
                "proposed-hash",
                previousStableCiphertextSha256Base64Url,
            )
            return encryptedCheckpoint(descriptor).copy(ciphertextBase64Url = encodeBase64Url(byteArrayOf(1, 2, 3)))
        }

        override suspend fun generateCheckpointId(): String = "new-checkpoint"
        override suspend fun generateWorkspaceEpochKey() = SecretMaterial(listOf(1))
        override suspend fun keyCommitment(material: SecretMaterial) = BinaryData.Empty
        override suspend fun wrapWorkspaceKey(material: SecretMaterial, recipientPublicKey: BinaryData) = BinaryData.Empty
        override suspend fun signDeviceMessage(message: BinaryData): BinaryData {
            signedMessage = message
            return BinaryData.copyOf(ByteArray(64) { 1 })
        }

        override suspend fun verifyDeviceSignature(
            message: BinaryData,
            signature: BinaryData,
            publicKey: BinaryData,
        ) = true

        private fun checkpointHeader(descriptor: RetainedCheckpointDescriptor) = SyncCheckpointHeader(
            cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
            nonceBase64Url = "nonce-${descriptor.checkpointId}",
            instanceId = "instance",
            workspaceId = "workspace",
            checkpointId = descriptor.checkpointId,
            deviceId = "uploader",
            throughWorkspaceSeq = descriptor.throughWorkspaceSeq,
            keyEpoch = descriptor.keyEpoch,
            previousStableCiphertextSha256Base64Url = descriptor.previousStableCiphertextSha256Base64Url,
            compression = SyncCheckpointCompression.LZ4_BLOCK_V1,
            uncompressedSize = 1,
            ciphertextSha256Base64Url = descriptor.ciphertextSha256Base64Url,
        )
    }
}
