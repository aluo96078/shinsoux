package dev.shinsou.kmp.sync.v2

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineDeliveryLifecycleTest {
    private val session = SyncSession(
        endpoint = "https://sync.example.test",
        instanceId = "instance",
        userId = "user",
        workspaceId = "workspace",
        deviceId = "device",
        deviceDisplayName = "Test device",
        platform = "other",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = 1,
    )

    @Test
    fun liteProfilePollsOnlyWhileForeground() = runTest {
        val api = DeliveryApi(realtime = false)
        val engine = engine(this, api, realtime = null)

        engine.start()
        val immediateCatchUps = api.catchUpCalls
        advanceTimeBy(100)
        runCurrent()
        assertTrue(api.catchUpCalls > immediateCatchUps)

        engine.onBackground()
        val backgroundCatchUps = api.catchUpCalls
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(backgroundCatchUps, api.catchUpCalls)
        engine.close()
    }

    @Test
    fun realtimeConnectFailureKeepsHealthyEngineAndFallsBackToLitePolling() = runTest {
        val api = DeliveryApi(realtime = true)
        val realtime = FailingRealtimeClient()
        val engine = engine(this, api, realtime)

        engine.start()
        runCurrent()

        assertEquals(SyncEnginePhase.READY, engine.state.value.phase)
        assertNull(engine.state.value.diagnostic)
        val afterConnectFailure = api.catchUpCalls

        advanceTimeBy(100)
        runCurrent()

        assertTrue(realtime.connectCalls >= 1)
        assertTrue(api.catchUpCalls > afterConnectFailure)
        assertEquals(SyncEnginePhase.READY, engine.state.value.phase)
        assertNull(engine.state.value.diagnostic)
        engine.close()
    }

    @Test
    fun reauthRefreshesCapabilityAndReconnects() = runTest {
        val api = DeliveryApi(realtime = true)
        val realtime = DeliveryRealtimeClient()
        val engine = engine(this, api, realtime)

        engine.start()
        runCurrent()
        assertEquals(1, realtime.connectCalls)
        val capabilityCalls = api.capabilityCalls

        realtime.emit(RealtimeWorkspaceMessage.ReauthRequired)
        runCurrent()

        assertTrue(api.capabilityCalls > capabilityCalls)
        assertEquals(2, realtime.connectCalls)
        assertTrue(realtime.closeCalls >= 1)
        engine.close()
    }

    @Test
    fun repeatedBackgroundEdgeRunsOneOrderedLocalAndRemoteFlush() = runTest {
        val order = mutableListOf<String>()
        val api = DeliveryApi(realtime = true, order = order)
        var localFlushes = 0
        val engine = engine(
            scope = this,
            api = api,
            realtime = null,
            backgroundFlusher = SyncBackgroundFlusher {
                localFlushes += 1
                order += "local"
            },
        )
        engine.start()
        order.clear()

        engine.onBackground()
        val afterFirst = order.toList()
        engine.onBackground()

        assertEquals(1, localFlushes)
        assertEquals(afterFirst, order)
        assertEquals("local", order.first())
        assertTrue(order.drop(1).all { it == "remote" })
        engine.close()
    }

    @Test
    fun concurrentDuplicateBackgroundCallbacksShareOneFlush() = runTest {
        val api = DeliveryApi(realtime = true)
        val flushEntered = CompletableDeferred<Unit>()
        val releaseFlush = CompletableDeferred<Unit>()
        var localFlushes = 0
        val engine = engine(
            scope = this,
            api = api,
            realtime = null,
            backgroundFlusher = SyncBackgroundFlusher {
                localFlushes += 1
                flushEntered.complete(Unit)
                releaseFlush.await()
            },
        )
        engine.start()

        val first = async { engine.onBackground() }
        flushEntered.await()
        val duplicate = async { engine.onBackground() }
        runCurrent()
        releaseFlush.complete(Unit)
        awaitAll(first, duplicate)

        assertEquals(1, localFlushes)
        engine.close()
    }

    private fun engine(
        scope: TestScope,
        api: CloudflareSyncApi,
        realtime: RealtimeWorkspaceClient?,
        backgroundFlusher: SyncBackgroundFlusher? = null,
    ) = SyncEngine(
        scope = scope.backgroundScope,
        sessionStore = InMemorySyncSessionStore(session),
        localStore = InMemoryLocalSyncStore(),
        api = api,
        realtimeClient = realtime,
        crypto = NoopCrypto,
        projectionSink = SyncProjectionSink { },
        nowMillis = { 1_000 },
        backgroundFlusher = backgroundFlusher,
        litePollingInitialDelayMillis = 100,
        litePollingMaxDelayMillis = 400,
    )

    private class DeliveryApi(
        private val realtime: Boolean,
        private val order: MutableList<String>? = null,
    ) : CloudflareSyncApi {
        var catchUpCalls = 0
        var capabilityCalls = 0

        override suspend fun capabilities(endpoint: String) = SyncCapabilities(
            protocolVersion = SYNC_PROTOCOL_VERSION,
            minReaderVersion = 1,
            minWriterVersion = 1,
            schemaVersion = SYNC_STATE_SCHEMA_VERSION,
            minSchemaReaderVersion = 1,
            minSchemaWriterVersion = 1,
            realtimeAvailable = realtime,
            maxEventBytes = 32 * 1024,
            maxBatchBytes = 256 * 1024,
            maxCheckpointBytes = 32 * 1024 * 1024,
        )

        override suspend fun obtainWorkspaceCapability(session: SyncSession): WorkspaceCapability {
            capabilityCalls += 1
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
        ): AppendEventResult = error("unused")

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
            catchUpCalls += 1
            order?.add("remote")
            return CatchUpPage(
                fromExclusive = afterExclusive,
                untilInclusive = untilInclusive ?: afterExclusive,
                nextCursor = afterExclusive,
                hasMore = false,
                headSeq = afterExclusive,
                stableCheckpointSeq = 0,
                events = emptyList(),
            )
        }

        override suspend fun bootstrap(
            session: SyncSession,
            capability: WorkspaceCapability,
        ): BootstrapResponse = error("unused")

        override suspend fun downloadCheckpoint(
            session: SyncSession,
            capability: WorkspaceCapability,
            descriptor: RetainedCheckpointDescriptor,
        ): EncryptedSyncCheckpoint = error("unused")
    }

    private class DeliveryRealtimeClient : RealtimeWorkspaceClient {
        var connectCalls = 0
        var closeCalls = 0
        private var callback: (suspend (RealtimeWorkspaceMessage) -> Unit)? = null

        override suspend fun connect(
            session: SyncSession,
            capability: WorkspaceCapability,
            cursor: Long,
            onMessage: suspend (RealtimeWorkspaceMessage) -> Unit,
        ) {
            connectCalls += 1
            callback = onMessage
        }

        override suspend fun close() {
            closeCalls += 1
            callback = null
        }

        suspend fun emit(message: RealtimeWorkspaceMessage) {
            requireNotNull(callback)(message)
        }
    }

    private class FailingRealtimeClient : RealtimeWorkspaceClient {
        var connectCalls = 0

        override suspend fun connect(
            session: SyncSession,
            capability: WorkspaceCapability,
            cursor: Long,
            onMessage: suspend (RealtimeWorkspaceMessage) -> Unit,
        ) {
            connectCalls++
            throw IllegalStateException("websocket unavailable")
        }

        override suspend fun close() = Unit
    }

    private object NoopCrypto : SyncCrypto {
        override suspend fun prepareEventSealer(
            session: SyncSession,
            keyEpoch: Int,
        ): PreparedSyncEventSealer = error("unused")

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
}
