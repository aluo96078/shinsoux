package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderProgressReporterTest {
    private val manga = SyncEntityKey.manga("1", "/m")
    private val chapter = SyncEntityKey.chapter("1", "/c")
    private val session = SyncSession(
        endpoint = "https://sync.example",
        instanceId = "instance",
        userId = "user",
        workspaceId = "workspace",
        deviceId = "device",
        deviceDisplayName = "Phone",
        platform = "ios",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = 1,
    )

    @Test
    fun positionsConflateUntilBackgroundFlushThenFollowRequiredOrder() = runTest {
        var now = 1_000L
        var id = 0
        val order = mutableListOf<String>()
        val store = InMemoryLocalSyncStore()
        val reporter = ReaderProgressReporter(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(session),
            crypto = FakeCrypto(order),
            projectionSink = SyncProjectionSink { order += "projection" },
            remoteOutboxFlusher = RemoteOutboxFlusher { order += "remote" },
            operationIdGenerator = SyncOperationIdGenerator { "op-${++id}" },
            nowMillis = { now },
            isIncognito = { false },
            beforeBackgroundSeal = { order += "persistence" },
            sealIntervalMillis = 500,
        )

        reporter.recordReadingProgress(
            chapter,
            manga,
            ReadingMode.WEBTOON,
            pageIndex = 2,
            normalizedOffsetFraction = 0.25,
            sessionId = "reader-session",
            completed = false,
            historyTouchedAt = now,
        )
        now += 100
        reporter.recordReadingProgress(
            chapter,
            manga,
            ReadingMode.WEBTOON,
            pageIndex = 4,
            normalizedOffsetFraction = 0.75,
            sessionId = "reader-session",
            completed = true,
            historyTouchedAt = now,
        )

        assertEquals(1, store.readState().drafts.size)
        assertTrue(store.readState().sealedOutbox.isEmpty())
        assertEquals(4, store.readState().replica.readingProgress.getValue(chapter).position?.position?.pageIndex)
        assertEquals(listOf("projection", "projection"), order)

        reporter.flushForBackground()

        assertEquals(listOf("projection", "projection", "persistence", "seal"), order)
        assertTrue(store.readState().drafts.isEmpty())
        assertEquals(1, store.readState().sealedOutbox.size)
        val mutation = store.readState().sealedOutbox.values.single().logicalEvent.mutations.single() as ReadingProgressSet
        assertEquals(4, mutation.position?.pageIndex)
        assertEquals(0.75, mutation.position?.normalizedOffsetFraction)
        assertTrue(requireNotNull(mutation.readState))
        assertFalse(store.readState().materializationPending)
    }

    @Test
    fun typedLocatorsConflateRestoreAndSealThroughTheSameReaderPriorityBoundary() = runTest {
        var now = 1_000L
        var id = 0
        val order = mutableListOf<String>()
        val store = InMemoryLocalSyncStore()
        val reporter = ReaderProgressReporter(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(session),
            crypto = FakeCrypto(order),
            projectionSink = SyncProjectionSink { order += "projection" },
            remoteOutboxFlusher = RemoteOutboxFlusher { order += "remote" },
            operationIdGenerator = SyncOperationIdGenerator { "typed-${++id}" },
            nowMillis = { now },
            isIncognito = { false },
            beforeBackgroundSeal = { order += "persistence" },
            sealIntervalMillis = 500,
        )
        val scope = ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = PublicationKey("11111111-1111-4111-8111-111111111111"),
            acquisitionId = "22222222-2222-4222-8222-222222222222",
            unitId = UnitKey(
                PublicationKey("11111111-1111-4111-8111-111111111111"),
                "33333333-3333-4333-8333-333333333333",
            ),
            contentRevision = 1,
        )
        val first = ReadingLocator.Text(
            schemaVersion = scope.schemaVersion,
            scope = scope,
            resourceId = "resource-text",
            blockId = "block-1",
            offset = 10,
            progression = 0.1,
        )
        val second = first.copy(blockId = "block-2", offset = 90, progression = 0.9)

        reporter.recordContentReadingProgress(first, "typed-reader", completed = false, historyTouchedAt = now)
        now += 100
        val result = reporter.recordContentReadingProgress(
            second,
            "typed-reader",
            completed = true,
            historyTouchedAt = now,
        )

        assertEquals(1, store.readState().drafts.size)
        assertEquals(second, result.locatorRegister?.value)
        assertEquals(second, reporter.currentContentReadingLocator(ContentProgressKeyV2.from(second))?.value)
        reporter.flushForBackground()
        assertTrue(store.readState().drafts.isEmpty())
        val mutation = store.readState().sealedOutbox.values.single().logicalEvent.mutations.single()
            as ContentReadingProgressSetV2
        assertEquals(second, mutation.locator)
        assertEquals(true, mutation.readState)
        assertEquals(listOf("projection", "projection", "persistence", "seal"), order)
    }

    @Test
    fun exposesTypedWinningPositionAndGeneratesReaderSessionIds() = runTest {
        var id = 0
        val store = InMemoryLocalSyncStore()
        val reporter = ReaderProgressReporter(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(session),
            crypto = FakeCrypto(mutableListOf()),
            projectionSink = SyncProjectionSink { },
            remoteOutboxFlusher = RemoteOutboxFlusher { },
            operationIdGenerator = SyncOperationIdGenerator { "id-${++id}" },
            nowMillis = { 1_000 },
            isIncognito = { false },
        )

        assertEquals("id-1", reporter.newReaderSessionId())
        val result = reporter.recordReadingProgress(
            chapter,
            manga,
            ReadingMode.PAGER_VERTICAL,
            pageIndex = 7,
            normalizedOffsetFraction = 0.0,
            sessionId = "reader",
            completed = false,
            historyTouchedAt = 1_000,
        )

        assertEquals(result.positionRegister, reporter.currentReadingPosition(chapter))
        assertEquals(7, result.positionRegister?.position?.pageIndex)
        assertFalse(store.readState().materializationPending)
    }

    @Test
    fun finalSettledPositionIsSealedAndUploadedWithoutAnotherPageChange() = runTest {
        val order = mutableListOf<String>()
        val store = InMemoryLocalSyncStore()
        val reporter = ReaderProgressReporter(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(session),
            crypto = FakeCrypto(order),
            projectionSink = SyncProjectionSink { order += "projection" },
            remoteOutboxFlusher = RemoteOutboxFlusher { order += "remote" },
            operationIdGenerator = SyncOperationIdGenerator { "operation" },
            nowMillis = { 1_000 + testScheduler.currentTime },
            isIncognito = { false },
            scope = this,
            sealIntervalMillis = 500,
        )

        reporter.recordReadingProgress(
            chapter,
            manga,
            ReadingMode.PAGER_LTR,
            pageIndex = 3,
            normalizedOffsetFraction = 0.0,
            sessionId = "reader-session",
            completed = false,
            historyTouchedAt = 1_000,
        )
        assertEquals(listOf("projection"), order)

        advanceTimeBy(500)
        runCurrent()

        assertEquals(listOf("projection", "seal", "remote"), order)
        assertTrue(store.readState().drafts.isEmpty())
        assertEquals(1, store.readState().sealedOutbox.size)
    }

    @Test
    fun aSlowRemoteFlushCannotBlockTheNextReaderMutation() = runTest {
        val remoteEntered = CompletableDeferred<Unit>()
        val releaseRemote = CompletableDeferred<Unit>()
        val store = InMemoryLocalSyncStore()
        val reporter = ReaderProgressReporter(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(session),
            crypto = FakeCrypto(mutableListOf()),
            projectionSink = SyncProjectionSink { },
            remoteOutboxFlusher = RemoteOutboxFlusher {
                remoteEntered.complete(Unit)
                releaseRemote.await()
            },
            operationIdGenerator = SyncOperationIdGenerator { "operation-${testScheduler.currentTime}" },
            nowMillis = { 1_000 + testScheduler.currentTime },
            isIncognito = { false },
            scope = this,
            sealIntervalMillis = 500,
        )

        reporter.recordReadingProgress(
            chapter,
            manga,
            ReadingMode.PAGER_LTR,
            pageIndex = 3,
            normalizedOffsetFraction = 0.0,
            sessionId = "reader-session",
            completed = false,
            historyTouchedAt = 1_000,
        )
        advanceTimeBy(500)
        runCurrent()
        remoteEntered.await()

        // The first sealed event is still waiting on the network, but local reader interaction
        // remains immediately writable and coalesces into the next draft.
        val result = reporter.recordReadingProgress(
            chapter,
            manga,
            ReadingMode.PAGER_LTR,
            pageIndex = 4,
            normalizedOffsetFraction = 0.0,
            sessionId = "reader-session",
            completed = false,
            historyTouchedAt = 1_001,
        )
        assertTrue(result.recorded)
        assertEquals(1, store.readState().drafts.size)
        assertEquals(1, store.readState().sealedOutbox.size)

        releaseRemote.complete(Unit)
        runCurrent()
        reporter.close()
    }

    @Test
    fun closeCancelsTimersAndTheirFinallyBlocksCannotReschedule() = runTest {
        val order = mutableListOf<String>()
        val store = InMemoryLocalSyncStore()
        val reporter = ReaderProgressReporter(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(session),
            crypto = FakeCrypto(order),
            projectionSink = SyncProjectionSink { order += "projection" },
            remoteOutboxFlusher = RemoteOutboxFlusher { order += "remote" },
            operationIdGenerator = SyncOperationIdGenerator { "operation" },
            nowMillis = { 1_000 + testScheduler.currentTime },
            isIncognito = { false },
            scope = this,
            sealIntervalMillis = 500,
        )
        reporter.recordReadingProgress(
            chapter,
            manga,
            ReadingMode.PAGER_LTR,
            pageIndex = 3,
            normalizedOffsetFraction = 0.0,
            sessionId = "reader-session",
            completed = false,
            historyTouchedAt = 1_000,
        )

        reporter.close()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf("projection"), order)
        assertEquals(1, store.readState().drafts.size)
        assertTrue(store.readState().sealedOutbox.isEmpty())
        assertFailsWith<IllegalStateException> { reporter.newReaderSessionId() }
    }

    @Test
    fun incognitoProducesNoReplicaDraftOrOutboxActivity() = runTest {
        val store = InMemoryLocalSyncStore()
        val reporter = ReaderProgressReporter(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(),
            crypto = FakeCrypto(mutableListOf()),
            projectionSink = SyncProjectionSink { error("must not materialize") },
            remoteOutboxFlusher = RemoteOutboxFlusher { error("must not upload") },
            operationIdGenerator = SyncOperationIdGenerator { error("must not allocate id") },
            nowMillis = { 1_000 },
            isIncognito = { true },
        )

        val result = reporter.recordReadingProgress(
            chapter,
            manga,
            ReadingMode.PAGER_LTR,
            pageIndex = 1,
            normalizedOffsetFraction = 0.0,
            sessionId = "incognito",
            completed = false,
            historyTouchedAt = 1_000,
        )

        assertFalse(result.recorded)
        assertEquals(LocalSyncStoreState(), store.readState())
    }

    @Test
    fun explicitResetIncrementsEpochAndOldHighPageCannotReturn() = runTest {
        var now = 1_000L
        var id = 0
        val store = InMemoryLocalSyncStore()
        val reporter = ReaderProgressReporter(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(session),
            crypto = FakeCrypto(mutableListOf()),
            projectionSink = SyncProjectionSink { },
            remoteOutboxFlusher = RemoteOutboxFlusher { },
            operationIdGenerator = SyncOperationIdGenerator { "op-${++id}" },
            nowMillis = { now },
            isIncognito = { false },
        )
        reporter.recordReadingProgress(
            chapter,
            manga,
            ReadingMode.PAGER_RTL,
            pageIndex = 20,
            normalizedOffsetFraction = 0.0,
            sessionId = "reader",
            completed = false,
            historyTouchedAt = now,
        )
        now += 1
        reporter.resetProgress(chapter, manga, ReadingMode.PAGER_RTL, "reader")

        val position = store.readState().replica.readingProgress.getValue(chapter).position?.position
        assertEquals(0, position?.pageIndex)
        assertEquals(1, position?.resetEpoch)
    }

    @Test
    fun remappedPublishUnreadResetAndFlushUseCanonicalKeyWithoutResettingEpoch() = runTest {
        var now = 1_000L
        var readerOperation = 0
        var remapOperation = 0
        val mangaV2 = SyncEntityKey.manga("1", "/canonical-m", version = 2)
        val chapterV2 = SyncEntityKey.chapter("1", "/canonical-c", version = 2)
        val store = InMemoryLocalSyncStore(
            LocalSyncStoreState(
                identityMap = SyncIdentityMap()
                    .bind(manga, 1)
                    .bind(chapter, 10),
            ),
        )
        val order = mutableListOf<String>()
        val reporter = ReaderProgressReporter(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(session),
            crypto = FakeCrypto(order),
            projectionSink = SyncProjectionSink { order += "projection" },
            remoteOutboxFlusher = RemoteOutboxFlusher { order += "remote" },
            operationIdGenerator = SyncOperationIdGenerator { "reader-${++readerOperation}" },
            nowMillis = { now },
            isIncognito = { false },
        )
        val bridge = RepositorySyncBridge(
            localStore = store,
            sessionStore = InMemorySyncSessionStore(session),
            idGenerator = SyncPortableIdGenerator { "remap-${++remapOperation}" },
            nowMillis = { now },
        )

        reporter.recordReadingProgress(
            chapter,
            manga,
            ReadingMode.PAGER_RTL,
            pageIndex = 20,
            normalizedOffsetFraction = 0.0,
            sessionId = "reader-session",
            completed = false,
            historyTouchedAt = now,
        )
        now += 1
        reporter.resetProgress(chapter, manga, ReadingMode.PAGER_RTL, "reader-session")
        assertEquals(1, reporter.currentReadingPosition(chapter)?.position?.resetEpoch)

        now += 1
        bridge.remapEntityKey(manga, mangaV2)
        now += 1
        bridge.remapEntityKey(chapter, chapterV2)
        assertEquals(1, reporter.currentReadingPosition(chapter)?.position?.resetEpoch)
        assertTrue(chapter !in store.readState().replica.readingProgress)

        now += 1
        val published = reporter.recordReadingProgress(
            chapter,
            manga,
            ReadingMode.WEBTOON,
            pageIndex = 8,
            normalizedOffsetFraction = 0.5,
            sessionId = "reader-session",
            completed = false,
            historyTouchedAt = now,
        )
        assertEquals(1, published.positionRegister?.position?.resetEpoch)
        val publishedMutation = store.readState().drafts.getValue(requireNotNull(published.draftId))
            .event.mutations.single() as ReadingProgressSet
        assertEquals(chapterV2, publishedMutation.chapterKey)
        assertEquals(mangaV2, publishedMutation.mangaKey)
        assertEquals(1, publishedMutation.position?.resetEpoch)

        now += 1
        val reset = reporter.resetProgress(
            chapter,
            manga,
            ReadingMode.WEBTOON,
            "reader-session",
        )
        assertEquals(2, reset.positionRegister?.position?.resetEpoch)
        now += 1
        reporter.markUnread(chapter, manga, "reader-session")

        val latestDraft = store.readState().drafts.getValue(requireNotNull(reset.draftId))
        val latestMutation = latestDraft.event.mutations.single() as ReadingProgressSet
        assertEquals(chapterV2, latestMutation.chapterKey)
        assertEquals(mangaV2, latestMutation.mangaKey)
        assertEquals(2, latestMutation.position?.resetEpoch)
        assertEquals(false, latestMutation.readState)

        reporter.flushReaderSession("reader-session")
        val latestSealedMutation = store.readState().sealedOutbox.maxBy { it.key }
            .value.logicalEvent.mutations.single() as ReadingProgressSet
        assertEquals(chapterV2, latestSealedMutation.chapterKey)
        assertEquals(mangaV2, latestSealedMutation.mangaKey)
        assertEquals(2, latestSealedMutation.position?.resetEpoch)
        assertEquals(2, reporter.currentReadingPosition(chapter)?.position?.resetEpoch)
    }

    private class FakeCrypto(private val order: MutableList<String>) : SyncCrypto {
        override suspend fun prepareEventSealer(session: SyncSession, keyEpoch: Int): PreparedSyncEventSealer =
            object : PreparedSyncEventSealer {
                override fun seal(request: SealEventRequest): EncryptedSyncEvent {
                    order += "seal"
                    return EncryptedSyncEvent(
                        header = SyncEventHeader(
                            cipherSuite = SyncCipherSuite.AES_256_GCM,
                            nonceBase64Url = "nonce-${request.deviceSeq}",
                            instanceId = request.context.instanceId,
                            workspaceId = request.context.workspaceId,
                            eventId = "event-${request.deviceSeq}",
                            deviceId = request.context.deviceId,
                            deviceSeq = request.deviceSeq,
                            keyEpoch = request.keyEpoch,
                            ciphertextSha256Base64Url = "hash-${request.deviceSeq}",
                        ),
                        authenticatedHeaderBase64Url = "header",
                        ciphertextBase64Url = "ciphertext",
                        signatureBase64Url = "signature",
                    )
                }

                override fun close() = Unit
            }

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

        override suspend fun generateWorkspaceEpochKey(): SecretMaterial = SecretMaterial(listOf(1))

        override suspend fun keyCommitment(material: SecretMaterial): BinaryData = BinaryData.Empty

        override suspend fun wrapWorkspaceKey(
            material: SecretMaterial,
            recipientPublicKey: BinaryData,
        ): BinaryData = BinaryData.Empty

        override suspend fun signDeviceMessage(message: BinaryData): BinaryData = BinaryData.Empty

        override suspend fun verifyDeviceSignature(
            message: BinaryData,
            signature: BinaryData,
            publicKey: BinaryData,
        ): Boolean = false
    }
}
