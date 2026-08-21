package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineCheckpointRecoveryTest {
    private val codec = DeterministicSyncEventCodec()
    private val manga = SyncEntityKey.manga("1", "/checkpoint-manga")
    private val session = SyncSession(
        endpoint = "https://sync.example",
        instanceId = "instance",
        userId = "user",
        workspaceId = "workspace",
        deviceId = "local",
        deviceDisplayName = "Phone",
        platform = "ios",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = 1,
    )

    @Test
    fun corruptedNewestCheckpointFallsBackAndRebasesPendingDraft() = runTest {
        val store = InMemoryLocalSyncStore()
        store.transaction {
            val hlc = nextLocalHlc("local", 100)
            applyLocalEvent(titleEvent("pending-local", hlc, "local wins"), 100)
        }
        val base = SyncReducer.reduceCommitted(
            SyncState(),
            CommittedSyncEvent(1, titleEvent("base", HlcTimestamp(1, 0, "remote"), "base")),
        )
        val tail = listOf(
            CommittedSyncEvent(2, titleEvent("remote-2", HlcTimestamp(2, 0, "remote"), "remote 2")),
            CommittedSyncEvent(3, titleEvent("remote-3", HlcTimestamp(3, 0, "remote"), "remote 3")),
        )
        val newest = descriptor("newest", 3, "hash-newest", "hash-recovery")
        val recovery = descriptor("recovery", 1, "hash-recovery", null)
        val checkpointStates = mapOf(
            "newest" to SyncReducer.reduceCommitted(base, tail),
            "recovery" to base,
        )
        val crypto = CheckpointFakeCrypto(codec, checkpointStates, failedCheckpointIds = setOf("newest"))
        val api = CheckpointFakeApi(session, crypto, listOf(newest, recovery), tail)
        var projectionFlushes = 0
        val engine = engine(this, store, api, crypto) { projectionFlushes += 1 }

        engine.start()

        val recovered = store.readState()
        assertEquals(3, recovered.replica.throughWorkspaceSeq)
        assertEquals(1, recovered.sealedOutbox.size)
        assertTrue(recovered.drafts.isEmpty())
        assertEquals(
            SyncValue.StringValue("local wins"),
            recovered.replica.entities.getValue(manga).fields.getValue(SyncFields.Manga.TITLE).value,
        )
        assertEquals(SyncEnginePhase.RATE_LIMITED, engine.state.value.phase)
        assertTrue(projectionFlushes >= 1)
        assertEquals(listOf("newest", "recovery"), api.downloadedCheckpointIds)
        engine.close()
    }

    @Test
    fun allInvalidCheckpointsLeaveReplicaAndDraftUntouched() = runTest {
        val store = InMemoryLocalSyncStore()
        store.transaction {
            val hlc = nextLocalHlc("local", 100)
            applyLocalEvent(titleEvent("pending-local", hlc, "offline edit"), 100)
        }
        val before = store.readState()
        val recovery = descriptor("recovery", 1, "hash-recovery", null)
        val base = SyncReducer.reduceCommitted(
            SyncState(),
            CommittedSyncEvent(1, titleEvent("base", HlcTimestamp(1, 0, "remote"), "base")),
        )
        val crypto = CheckpointFakeCrypto(codec, mapOf("recovery" to base), setOf("recovery"))
        val api = CheckpointFakeApi(session, crypto, listOf(recovery), emptyList())
        val engine = engine(this, store, api, crypto) { error("Projection must not run") }

        engine.start()

        assertEquals(SyncEnginePhase.ERROR, engine.state.value.phase)
        assertTrue(engine.state.value.diagnostic.orEmpty().contains("No retained stable checkpoint"))
        assertEquals(before, store.readState())
        engine.close()
    }

    @Test
    fun equalWatermarkFallbackUsesServerPromotionOrderInsteadOfCheckpointId() = runTest {
        val store = InMemoryLocalSyncStore()
        store.transaction {
            val hlc = nextLocalHlc("local", 100)
            applyLocalEvent(titleEvent("pending-local", hlc, "offline edit"), 100)
        }
        val stateAtThree = listOf(
            CommittedSyncEvent(1, titleEvent("remote-1", HlcTimestamp(1, 0, "remote"), "one")),
            CommittedSyncEvent(2, titleEvent("remote-2", HlcTimestamp(2, 0, "remote"), "two")),
            CommittedSyncEvent(3, titleEvent("remote-3", HlcTimestamp(3, 0, "remote"), "three")),
        ).fold(SyncState()) { state, committed -> SyncReducer.reduceCommitted(state, committed) }
        // Lexicographic sorting would try z-second first. The authenticated server order is exact.
        val first = descriptor("a-first", 3, "hash-first", null)
        val second = descriptor("z-second", 3, "hash-second", null)
        val crypto = CheckpointFakeCrypto(
            codec = codec,
            checkpointStates = mapOf(first.checkpointId to stateAtThree, second.checkpointId to stateAtThree),
            failedCheckpointIds = setOf(first.checkpointId),
        )
        val api = CheckpointFakeApi(session, crypto, listOf(first, second), emptyList())
        val engine = engine(this, store, api, crypto) { }

        engine.start()

        assertEquals(listOf(first.checkpointId, second.checkpointId), api.downloadedCheckpointIds)
        assertEquals(3, store.readState().replica.throughWorkspaceSeq)
        assertEquals(SyncEnginePhase.RATE_LIMITED, engine.state.value.phase)
        engine.close()
    }

    private fun engine(
        scope: TestScope,
        store: LocalSyncStore,
        api: CloudflareSyncApi,
        crypto: SyncCrypto,
        projection: suspend (LocalSyncStoreState) -> Unit,
    ): SyncEngine = SyncEngine(
        scope = scope.backgroundScope,
        sessionStore = InMemorySyncSessionStore(session),
        localStore = store,
        api = api,
        realtimeClient = null,
        crypto = crypto,
        projectionSink = SyncProjectionSink(projection),
        nowMillis = { 1_000 },
        eventCodec = codec,
    )

    private fun titleEvent(id: String, clock: HlcTimestamp, title: String): SyncEvent = SyncEvent(
        opId = id,
        hlc = clock,
        mutations = listOf(
            LibraryEntryPatch(
                key = manga,
                fields = mapOf(SyncFields.Manga.TITLE to SyncValue.StringValue(title)),
            ),
        ),
    )

    private fun descriptor(
        id: String,
        through: Long,
        hash: String,
        previous: String?,
    ) = RetainedCheckpointDescriptor(
        checkpointId = id,
        throughWorkspaceSeq = through,
        keyEpoch = 1,
        ciphertextSha256Base64Url = hash,
        previousStableCiphertextSha256Base64Url = previous,
    )

    private class CheckpointFakeApi(
        private val expectedSession: SyncSession,
        private val crypto: CheckpointFakeCrypto,
        private val descriptors: List<RetainedCheckpointDescriptor>,
        private val tail: List<CommittedSyncEvent>,
    ) : CloudflareSyncApi {
        val downloadedCheckpointIds = mutableListOf<String>()

        override suspend fun capabilities(endpoint: String): SyncCapabilities = SyncCapabilities(
            protocolVersion = SYNC_PROTOCOL_VERSION,
            minReaderVersion = 1,
            minWriterVersion = 1,
            schemaVersion = SYNC_STATE_SCHEMA_VERSION,
            minSchemaReaderVersion = 1,
            minSchemaWriterVersion = 1,
            realtimeAvailable = false,
            maxEventBytes = 32 * 1024,
            maxBatchBytes = 256 * 1024,
            maxCheckpointBytes = 32 * 1024 * 1024,
        )

        override suspend fun obtainWorkspaceCapability(session: SyncSession): WorkspaceCapability {
            assertEquals(expectedSession.workspaceId, session.workspaceId)
            return WorkspaceCapability(
                token = SecretMaterial(listOf(1)),
                binding = CapabilityBinding(
                    deviceId = session.deviceId,
                    workspaceId = session.workspaceId,
                    deviceAuthEpoch = session.deviceAuthEpoch,
                    membershipAuthEpoch = session.membershipAuthEpoch,
                    keyEpoch = session.activeKeyEpoch,
                    expiresAtMillis = 10_000,
                ),
            )
        }

        override suspend fun appendEvent(
            session: SyncSession,
            capability: WorkspaceCapability,
            event: EncryptedSyncEvent,
        ): AppendEventResult = AppendEventResult.RateLimited(5_000)

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
            if (afterExclusive == 0L && untilInclusive == null) {
                throw CheckpointRequiredException(3, descriptors)
            }
            val fixed = untilInclusive ?: 3L
            val events = tail.filter { it.workspaceSeq > afterExclusive && it.workspaceSeq <= fixed }
                .map { committed ->
                    RemoteCommittedEnvelope(
                        workspaceSeq = committed.workspaceSeq,
                        envelope = crypto.eventEnvelope(committed.event, committed.workspaceSeq),
                    )
                }
            val next = events.lastOrNull()?.workspaceSeq ?: afterExclusive
            return CatchUpPage(
                fromExclusive = afterExclusive,
                untilInclusive = fixed,
                nextCursor = next,
                hasMore = next < fixed,
                headSeq = 3,
                stableCheckpointSeq = descriptors.maxOfOrNull { it.throughWorkspaceSeq } ?: 0,
                events = events,
            )
        }

        override suspend fun bootstrap(
            session: SyncSession,
            capability: WorkspaceCapability,
        ): BootstrapResponse = BootstrapResponse(
            headSeq = 3,
            activeKeyEpoch = 1,
            retainedStableCheckpoints = descriptors,
            requiredKeyEpochs = setOf(1),
        )

        override suspend fun downloadCheckpoint(
            session: SyncSession,
            capability: WorkspaceCapability,
            descriptor: RetainedCheckpointDescriptor,
        ): EncryptedSyncCheckpoint {
            downloadedCheckpointIds += descriptor.checkpointId
            return crypto.encryptedCheckpoint(descriptor)
        }
    }

    private class CheckpointFakeCrypto(
        private val codec: SyncEventCodec,
        private val checkpointStates: Map<String, SyncState>,
        private val failedCheckpointIds: Set<String>,
    ) : SyncCrypto {
        private val events = mutableMapOf<String, SyncEvent>()

        fun eventEnvelope(event: SyncEvent, deviceSeq: Long): EncryptedSyncEvent {
            val id = "event-${event.opId}-$deviceSeq"
            events[id] = event
            return EncryptedSyncEvent(
                header = SyncEventHeader(
                    cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
                    nonceBase64Url = "nonce-$id",
                    instanceId = "instance",
                    workspaceId = "workspace",
                    eventId = id,
                    deviceId = event.hlc.deviceId,
                    deviceSeq = deviceSeq,
                    keyEpoch = 1,
                    ciphertextSha256Base64Url = "hash-$id",
                ),
                authenticatedHeaderBase64Url = "header-$id",
                ciphertextBase64Url = "ciphertext-$id",
                signatureBase64Url = "signature-$id",
            )
        }

        fun encryptedCheckpoint(descriptor: RetainedCheckpointDescriptor): EncryptedSyncCheckpoint =
            EncryptedSyncCheckpoint(
                header = checkpointHeader(descriptor),
                authenticatedHeaderBase64Url = "header-${descriptor.checkpointId}",
                ciphertextBase64Url = "ciphertext-${descriptor.checkpointId}",
                signatureBase64Url = "signature-${descriptor.checkpointId}",
            )

        override suspend fun prepareEventSealer(
            session: SyncSession,
            keyEpoch: Int,
        ): PreparedSyncEventSealer = object : PreparedSyncEventSealer {
            override fun seal(request: SealEventRequest): EncryptedSyncEvent =
                eventEnvelope(request.event, request.deviceSeq)

            override fun close() = Unit
        }

        override suspend fun openAndVerifyEvent(
            session: SyncSession,
            remote: RemoteCommittedEnvelope,
        ): OpenedRemoteEvent = OpenedRemoteEvent(
            event = requireNotNull(events[remote.envelope.header.eventId]),
            authenticatedHeaderBytes = BinaryData.Empty,
        )

        override suspend fun openAndVerifyCheckpoint(
            session: SyncSession,
            checkpoint: EncryptedSyncCheckpoint,
            descriptor: RetainedCheckpointDescriptor,
        ): VerifiedSyncCheckpoint {
            if (descriptor.checkpointId in failedCheckpointIds) error("corrupt checkpoint")
            val state = requireNotNull(checkpointStates[descriptor.checkpointId]).normalized()
            return VerifiedSyncCheckpoint(
                header = checkpointHeader(descriptor),
                state = state,
                canonicalState = codec.canonicalCheckpointState(state),
            )
        }

        override suspend fun sealCheckpoint(
            session: SyncSession,
            checkpointId: String,
            state: SyncState,
            previousStableCiphertextSha256Base64Url: String?,
        ): EncryptedSyncCheckpoint = error("unused")

        override suspend fun generateWorkspaceEpochKey(): SecretMaterial = SecretMaterial(listOf(1))
        override suspend fun keyCommitment(material: SecretMaterial): BinaryData = BinaryData.Empty
        override suspend fun wrapWorkspaceKey(material: SecretMaterial, recipientPublicKey: BinaryData): BinaryData =
            BinaryData.Empty

        override suspend fun signDeviceMessage(message: BinaryData): BinaryData = BinaryData.Empty
        override suspend fun verifyDeviceSignature(
            message: BinaryData,
            signature: BinaryData,
            publicKey: BinaryData,
        ): Boolean = true

        private fun checkpointHeader(descriptor: RetainedCheckpointDescriptor) = SyncCheckpointHeader(
            cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
            nonceBase64Url = "nonce-${descriptor.checkpointId}",
            instanceId = "instance",
            workspaceId = "workspace",
            checkpointId = descriptor.checkpointId,
            deviceId = "remote",
            throughWorkspaceSeq = descriptor.throughWorkspaceSeq,
            keyEpoch = descriptor.keyEpoch,
            previousStableCiphertextSha256Base64Url = descriptor.previousStableCiphertextSha256Base64Url,
            compression = SyncCheckpointCompression.LZ4_BLOCK_V1,
            uncompressedSize = 1,
            ciphertextSha256Base64Url = descriptor.ciphertextSha256Base64Url,
        )
    }
}
