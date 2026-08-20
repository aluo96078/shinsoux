package dev.shinsou.kmp.sync.v2

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineTest {
    private val manga = SyncEntityKey.manga("1", "/m")
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
    fun startCatchesUpSealsUploadsAndAdvancesOwnReceiptEvent() = runTest {
        val store = InMemoryLocalSyncStore()
        store.transaction {
            val clock = nextLocalHlc("local", 100)
            applyLocalEvent(titleEvent("local-pending", clock, "local"), 100)
        }
        val crypto = EngineFakeCrypto()
        val remote = titleEvent("remote", HlcTimestamp(1, 0, "remote"), "remote")
        val api = EngineFakeApi(session, crypto, remote, AppendMode.COMMIT)
        var projectionFlushes = 0
        val engine = engine(this, store, api, crypto) { projectionFlushes += 1 }

        engine.start()

        assertEquals(SyncEnginePhase.READY, engine.state.value.phase)
        assertEquals(2, engine.state.value.cursor)
        assertEquals(0, engine.state.value.outboxCount)
        assertEquals(2, store.readState().replica.throughWorkspaceSeq)
        assertTrue(store.readState().drafts.isEmpty())
        assertTrue(store.readState().sealedOutbox.isEmpty())
        assertEquals(1, store.readState().committedDeviceSeq)
        assertTrue(projectionFlushes >= 2)
        val title = store.readState().replica.entities.getValue(manga).fields.getValue(SyncFields.Manga.TITLE).value
        assertEquals(SyncValue.StringValue("local"), title)
        engine.close()
    }

    @Test
    fun readerPrioritySealsAndUploadsBeforeOrdinaryDrafts() = runTest {
        val store = InMemoryLocalSyncStore()
        store.transaction {
            val ordinaryClock = nextLocalHlc("local", 100)
            applyLocalEvent(titleEvent("ordinary", ordinaryClock, "offline"), 100)
            val readerClock = nextLocalHlc("local", 101)
            applyLocalEvent(
                SyncEvent(
                    opId = "reader",
                    hlc = readerClock,
                    mutations = listOf(
                        ReadingProgressSet(
                            chapterKey = SyncEntityKey.chapter("1", "/c"),
                            mangaKey = manga,
                            position = ReaderPosition(
                                readingMode = dev.shinsou.kmp.domain.model.ReadingMode.PAGER_LTR,
                                pageIndex = 4,
                                normalizedOffsetFraction = 0.0,
                                resetEpoch = 0,
                            ),
                            sessionId = "reader-session",
                        ),
                    ),
                ),
                101,
                coalescingKey = "reader|reader-session|chapter",
            )
        }
        val crypto = EngineFakeCrypto()
        val api = EngineFakeApi(session, crypto, remoteEvent = null, appendMode = AppendMode.COMMIT)
        val engine = engine(this, store, api, crypto) { }

        engine.syncReaderProgress()

        assertEquals(1, api.appendCalls)
        assertTrue(store.readState().drafts.containsKey("ordinary"))
        assertTrue(store.readState().sealedOutbox.isEmpty())
        assertTrue(api.lastAppended?.header?.deviceSeq == 1L)
        engine.close()
    }

    @Test
    fun rateLimitKeepsImmutableOutboxAndVisibleEnginePhase() = runTest {
        val store = InMemoryLocalSyncStore()
        store.transaction {
            val clock = nextLocalHlc("local", 100)
            applyLocalEvent(titleEvent("local-pending", clock, "local"), 100)
        }
        val crypto = EngineFakeCrypto()
        val api = EngineFakeApi(session, crypto, remoteEvent = null, appendMode = AppendMode.RATE_LIMIT)
        val engine = engine(this, store, api, crypto) { }

        engine.start()

        assertEquals(SyncEnginePhase.RATE_LIMITED, engine.state.value.phase)
        assertEquals(5_000, engine.state.value.retryAfterMillis)
        assertEquals(1, engine.state.value.outboxCount)
        assertEquals(1, store.readState().sealedOutbox.size)
        assertEquals(0, store.readState().committedDeviceSeq)
        engine.close()
    }

    @Test
    fun rotationGateKeepsOutboxAndPublishesTypedWakeupPhase() = runTest {
        val store = InMemoryLocalSyncStore()
        store.transaction {
            val clock = nextLocalHlc("local", 100)
            applyLocalEvent(titleEvent("local-pending", clock, "local"), 100)
        }
        val crypto = EngineFakeCrypto()
        val api = EngineFakeApi(session, crypto, remoteEvent = null, appendMode = AppendMode.ROTATION_REQUIRED)
        val engine = engine(this, store, api, crypto) { }

        engine.start()

        assertEquals(SyncEnginePhase.KEY_ROTATION_REQUIRED, engine.state.value.phase)
        assertEquals(1, engine.state.value.outboxCount)
        assertEquals(1, store.readState().sealedOutbox.size)
        assertEquals(0, store.readState().committedDeviceSeq)
        engine.close()
    }

    @Test
    fun attemptedAppendReconcilesExactReceiptBeforeCatchUpWithoutReuploading() = runTest {
        val crypto = EngineFakeCrypto()
        val (store, sealed) = attemptedOutbox(crypto)
        val api = ReceiptReconciliationApi(sealed, ReceiptLookupMode.MATCH)
        val engine = engine(this, store, api, crypto) { }

        engine.start()

        assertEquals(SyncEnginePhase.READY, engine.state.value.phase)
        assertEquals(1, api.receiptCalls)
        assertEquals(0, api.appendCalls)
        assertEquals("receipt", api.callOrder.first())
        assertTrue(api.callOrder.indexOf("receipt") < api.callOrder.indexOf("catch_up"))
        assertEquals(1, store.readState().committedDeviceSeq)
        assertTrue(store.readState().sealedOutbox.isEmpty())
        engine.close()
    }

    @Test
    fun conflictingReconciliationReceiptFailsClosedWithoutChangingJournal() = runTest {
        val crypto = EngineFakeCrypto()
        val (store, sealed) = attemptedOutbox(crypto)
        val before = store.readState()
        val api = ReceiptReconciliationApi(sealed, ReceiptLookupMode.MISMATCH)
        val engine = engine(this, store, api, crypto) { }

        engine.start()

        assertEquals(SyncEnginePhase.ERROR, engine.state.value.phase)
        assertTrue(engine.state.value.diagnostic.orEmpty().contains("Receipt does not authenticate"))
        assertEquals(before, store.readState())
        assertEquals(0, api.appendCalls)
        assertEquals(0, api.catchUpCalls)
        engine.close()
    }

    @Test
    fun receiptLookupTransportFailureLeavesAttemptedJournalUntouched() = runTest {
        val crypto = EngineFakeCrypto()
        val (store, sealed) = attemptedOutbox(crypto)
        val before = store.readState()
        val api = ReceiptReconciliationApi(sealed, ReceiptLookupMode.TRANSPORT_FAILURE)
        val engine = engine(this, store, api, crypto) { }

        engine.start()

        assertEquals(SyncEnginePhase.ERROR, engine.state.value.phase)
        assertEquals(before, store.readState())
        assertEquals(0, api.appendCalls)
        assertEquals(0, api.catchUpCalls)
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
    )

    private fun titleEvent(id: String, clock: HlcTimestamp, title: String): SyncEvent = SyncEvent(
        id,
        clock,
        listOf(
            LibraryEntryPatch(
                manga,
                mapOf(SyncFields.Manga.TITLE to SyncValue.StringValue(title)),
            ),
        ),
    )

    private suspend fun attemptedOutbox(
        crypto: EngineFakeCrypto,
    ): Pair<InMemoryLocalSyncStore, SealedOutboxEvent> {
        val store = InMemoryLocalSyncStore()
        val event = titleEvent("ambiguous", HlcTimestamp(100, 0, "local"), "offline")
        store.transaction { applyLocalEvent(event, 100) }
        val sealer = crypto.prepareEventSealer(session, 1)
        val sealed = try {
            store.sealDraft(
                draftId = event.opId,
                context = EventSealContext(session.instanceId, session.workspaceId, session.deviceId),
                keyEpoch = 1,
                nowMillis = 100,
                sealer = sealer,
            )
        } finally {
            sealer.close()
        }
        store.transaction { markUploadAttempt(sealed.deviceSeq, 101) }
        return store to store.readState().sealedOutbox.getValue(sealed.deviceSeq)
    }

    private enum class AppendMode { COMMIT, RATE_LIMIT, ROTATION_REQUIRED }

    private enum class ReceiptLookupMode { MATCH, MISMATCH, TRANSPORT_FAILURE }

    private class ReceiptReconciliationApi(
        private val sealed: SealedOutboxEvent,
        private val mode: ReceiptLookupMode,
    ) : CloudflareSyncApi {
        var receiptCalls = 0
        var appendCalls = 0
        var catchUpCalls = 0
        val callOrder = mutableListOf<String>()

        override suspend fun capabilities(endpoint: String): SyncCapabilities = SyncCapabilities(
            protocolVersion = 1,
            minReaderVersion = 1,
            minWriterVersion = 1,
            schemaVersion = 1,
            minSchemaReaderVersion = 1,
            minSchemaWriterVersion = 1,
            realtimeAvailable = false,
            maxEventBytes = 32 * 1024,
            maxBatchBytes = 256 * 1024,
            maxCheckpointBytes = 32 * 1024 * 1024,
        )

        override suspend fun obtainWorkspaceCapability(session: SyncSession): WorkspaceCapability =
            WorkspaceCapability(
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

        override suspend fun appendEvent(
            session: SyncSession,
            capability: WorkspaceCapability,
            event: EncryptedSyncEvent,
        ): AppendEventResult {
            appendCalls += 1
            error("A receipt-authenticated event must not be uploaded again")
        }

        override suspend fun eventReceipt(
            session: SyncSession,
            capability: WorkspaceCapability,
            deviceSeq: Long,
        ): SyncReceipt? {
            receiptCalls += 1
            callOrder += "receipt"
            assertEquals(sealed.deviceSeq, deviceSeq)
            if (mode == ReceiptLookupMode.TRANSPORT_FAILURE) error("receipt transport failed")
            return SyncReceipt(
                eventId = if (mode == ReceiptLookupMode.MISMATCH) "conflicting-event" else sealed.eventId,
                deviceSeq = sealed.deviceSeq,
                workspaceSeq = 1,
                ciphertextSha256Base64Url = sealed.ciphertextSha256Base64Url,
            )
        }

        override suspend fun catchUp(
            session: SyncSession,
            capability: WorkspaceCapability,
            afterExclusive: Long,
            untilInclusive: Long?,
            limit: Int,
        ): CatchUpPage {
            catchUpCalls += 1
            callOrder += "catch_up"
            val fixed = untilInclusive ?: 1L
            val events = if (afterExclusive == 0L) {
                listOf(RemoteCommittedEnvelope(1, sealed.envelope))
            } else {
                emptyList()
            }
            val next = events.lastOrNull()?.workspaceSeq ?: afterExclusive
            return CatchUpPage(
                fromExclusive = afterExclusive,
                untilInclusive = fixed,
                nextCursor = next,
                hasMore = next < fixed,
                headSeq = 1,
                stableCheckpointSeq = 0,
                events = events,
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

    private class EngineFakeApi(
        private val session: SyncSession,
        private val crypto: EngineFakeCrypto,
        private val remoteEvent: SyncEvent?,
        private val appendMode: AppendMode,
    ) : CloudflareSyncApi {
        private var appended: EncryptedSyncEvent? = null
        var appendCalls = 0
        var lastAppended: EncryptedSyncEvent? = null

        override suspend fun capabilities(endpoint: String): SyncCapabilities = SyncCapabilities(
            protocolVersion = 1,
            minReaderVersion = 1,
            minWriterVersion = 1,
            schemaVersion = 1,
            minSchemaReaderVersion = 1,
            minSchemaWriterVersion = 1,
            realtimeAvailable = false,
            maxEventBytes = 32 * 1024,
            maxBatchBytes = 256 * 1024,
            maxCheckpointBytes = 32 * 1024 * 1024,
        )

        override suspend fun obtainWorkspaceCapability(session: SyncSession): WorkspaceCapability = WorkspaceCapability(
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

        override suspend fun appendEvent(
            session: SyncSession,
            capability: WorkspaceCapability,
            event: EncryptedSyncEvent,
        ): AppendEventResult {
            appendCalls += 1
            if (appendMode == AppendMode.RATE_LIMIT) return AppendEventResult.RateLimited(5_000)
            if (appendMode == AppendMode.ROTATION_REQUIRED) {
                return AppendEventResult.KeyRotationRequired(session.activeKeyEpoch)
            }
            appended = event
            lastAppended = event
            return AppendEventResult.Committed(
                receipt = SyncReceipt(
                    eventId = event.header.eventId,
                    deviceSeq = event.header.deviceSeq,
                    workspaceSeq = 2,
                    ciphertextSha256Base64Url = event.header.ciphertextSha256Base64Url,
                ),
                headSeq = 2,
            )
        }

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
            val events = when {
                afterExclusive == 0L && remoteEvent != null -> listOf(
                    RemoteCommittedEnvelope(1, crypto.remoteEnvelope(remoteEvent, 1)),
                )

                afterExclusive == 1L && appended != null -> listOf(RemoteCommittedEnvelope(2, requireNotNull(appended)))
                else -> emptyList()
            }
            val head = when {
                appended != null -> 2L
                remoteEvent != null -> 1L
                else -> 0L
            }
            val fixed = untilInclusive ?: head
            val bounded = events.filter { it.workspaceSeq <= fixed }
            val next = bounded.lastOrNull()?.workspaceSeq ?: afterExclusive
            return CatchUpPage(
                fromExclusive = afterExclusive,
                untilInclusive = fixed,
                nextCursor = next,
                hasMore = next < fixed,
                headSeq = head,
                stableCheckpointSeq = 0,
                events = bounded,
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

    private class EngineFakeCrypto : SyncCrypto {
        private val operations = mutableMapOf<String, SyncEvent>()

        override suspend fun prepareEventSealer(session: SyncSession, keyEpoch: Int): PreparedSyncEventSealer =
            object : PreparedSyncEventSealer {
                override fun seal(request: SealEventRequest): EncryptedSyncEvent {
                    val envelope = remoteEnvelope(request.event, request.deviceSeq)
                    operations[envelope.header.eventId] = request.event
                    return envelope
                }

                override fun close() = Unit
            }

        fun remoteEnvelope(event: SyncEvent, deviceSeq: Long): EncryptedSyncEvent {
            val eventId = "event-${event.opId}-$deviceSeq"
            operations[eventId] = event
            return EncryptedSyncEvent(
                header = SyncEventHeader(
                    cipherSuite = SyncCipherSuite.AES_256_GCM,
                    nonceBase64Url = "nonce-$eventId",
                    instanceId = "instance",
                    workspaceId = "workspace",
                    eventId = eventId,
                    deviceId = event.hlc.deviceId,
                    deviceSeq = deviceSeq,
                    keyEpoch = 1,
                    ciphertextSha256Base64Url = "hash-$eventId",
                ),
                authenticatedHeaderBase64Url = "header-$eventId",
                ciphertextBase64Url = "ciphertext-$eventId",
                signatureBase64Url = "signature-$eventId",
            )
        }

        override suspend fun openAndVerifyEvent(
            session: SyncSession,
            remote: RemoteCommittedEnvelope,
        ): OpenedRemoteEvent = OpenedRemoteEvent(
            event = requireNotNull(operations[remote.envelope.header.eventId]),
            authenticatedHeaderBytes = BinaryData.Empty,
        )

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
    }
}
