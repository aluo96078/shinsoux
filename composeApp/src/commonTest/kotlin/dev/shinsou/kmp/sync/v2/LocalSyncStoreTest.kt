package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.domain.model.ReadingMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalSyncStoreTest {
    private val manga = SyncEntityKey.manga("1", "/m")
    private val chapter = SyncEntityKey.chapter("1", "/c")
    private val context = EventSealContext("instance", "workspace", "device-a")

    @Test
    fun failedAtomicPersistenceRollsBackReplicaClockAndDraft() = runTest {
        val persistence = InMemorySyncStatePersistence()
        val store = PersistentLocalSyncStore.open(persistence)
        persistence.failNextSave = IllegalStateException("disk full")

        assertFailsWith<IllegalStateException> {
            store.transaction {
                val clock = nextLocalHlc("device-a", 100)
                applyLocalEvent(event("op", clock, LibraryEntryPatch(manga, emptyMap())), 100)
            }
        }

        assertEquals(LocalSyncStoreState(), store.readState())
        assertEquals(LocalSyncStoreState(), persistence.current())
    }

    @Test
    fun readerDraftCoalescingPreservesPositionWhenMarkUnreadArrives() = runTest {
        val store = InMemoryLocalSyncStore()
        store.transaction {
            val first = nextLocalHlc("device-a", 100)
            applyLocalEvent(
                event(
                    "position",
                    first,
                    ReadingProgressSet(
                        chapter,
                        manga,
                        ReaderPosition(ReadingMode.PAGER_LTR, 8),
                        historyTouchedAt = 100,
                        sessionId = "session",
                    ),
                ),
                nowMillis = 100,
                coalescingKey = "reader-session",
            )
            val second = nextLocalHlc("device-a", 101)
            applyLocalEvent(
                event("unread", second, ReadingProgressSet(chapter, manga, readState = false)),
                nowMillis = 101,
                coalescingKey = "reader-session",
            )
        }

        val draft = store.readState().drafts.values.single()
        val mutation = draft.event.mutations.single() as ReadingProgressSet
        assertEquals(8, mutation.position?.pageIndex)
        assertFalse(requireNotNull(mutation.readState))
        assertEquals("unread", draft.event.opId)
        assertEquals(8, store.readState().replica.readingProgress.getValue(chapter).position?.position?.pageIndex)
    }

    @Test
    fun sealingConsumesSequenceAtomicallyAndRetryNeverChangesEnvelope() = runTest {
        val store = InMemoryLocalSyncStore()
        val draftId = addDraft(store, "one", 100)
        val sealed = store.sealDraft(draftId, context, 1, 101, testSealer("first"))

        assertEquals(1, sealed.deviceSeq)
        assertEquals(2, store.readState().nextDeviceSeq)
        assertTrue(store.readState().drafts.isEmpty())
        assertEquals(sealed, store.nextOutboxEvent())

        store.transaction { markUploadAttempt(1, 102) }
        val attempted = store.nextOutboxEvent()
        assertEquals(sealed.envelope, attempted?.envelope)
        assertEquals(1, attempted?.attemptCount)
    }

    @Test
    fun incompleteSealingIntentDoesNotSkipSequenceAfterCrash() = runTest {
        val event = event(
            "pending",
            HlcTimestamp(100, 0, "device-a"),
            LibraryEntryPatch(manga, emptyMap()),
        )
        val draft = SyncDraft("pending", event, createdAtMillis = 100)
        val crashed = LocalSyncStoreState(
            replica = SyncReducer.reduce(SyncState(), event),
            lastLocalHlc = event.hlc,
            drafts = mapOf(draft.draftId to draft),
            nextDeviceSeq = 2,
            sealingIntent = SealingIntent(draft.draftId, 1, 1, 101),
        )

        val repaired = crashed.reconciledAfterCrash()

        assertEquals(1, repaired.nextDeviceSeq)
        assertEquals(null, repaired.sealingIntent)
        assertTrue(draft.draftId in repaired.drafts)
    }

    @Test
    fun receiptsCanArriveOutOfOrderButMustAuthenticateExactImmutableRows() = runTest {
        val store = InMemoryLocalSyncStore()
        val first = store.sealDraft(addDraft(store, "one", 100), context, 1, 101, testSealer("a"))
        val second = store.sealDraft(addDraft(store, "two", 102), context, 1, 103, testSealer("b"))

        store.transaction { recordReceipt(second.receipt(workspaceSeq = 12)) }
        assertEquals(0, store.readState().committedDeviceSeq)
        assertEquals(setOf(2L), store.readState().verifiedReceipts.keys)
        assertTrue(1L in store.readState().sealedOutbox)
        assertFalse(2L in store.readState().sealedOutbox)

        assertFailsWith<SyncInvariantViolation> {
            store.transaction {
                recordReceipt(first.receipt(workspaceSeq = 11).copy(ciphertextSha256Base64Url = "wrong"))
            }
        }
        store.transaction { recordReceipt(first.receipt(workspaceSeq = 11)) }
        assertEquals(2, store.readState().committedDeviceSeq)
        assertTrue(store.readState().sealedOutbox.isEmpty())
        assertTrue(store.readState().verifiedReceipts.isEmpty())

        // A late duplicate at or below the compacted high-watermark is idempotent and cannot
        // recreate an unbounded verified-receipt journal.
        store.transaction { recordReceipt(first.receipt(workspaceSeq = 11)) }
        assertEquals(2, store.readState().committedDeviceSeq)
        assertTrue(store.readState().verifiedReceipts.isEmpty())
    }

    @Test
    fun explicitStaleEpochArchivesOldBytesAndResealsSameLogicalSequence() = runTest {
        val store = InMemoryLocalSyncStore()
        val old = store.sealDraft(addDraft(store, "one", 100), context, 1, 101, testSealer("old"))
        store.transaction {
            retainKeyEpoch(KeyEpochMetadata(2, "epoch-2", KeyEpochStatus.ACTIVE, 102))
        }

        val replacement = store.transaction {
            resealAfterExplicitStaleKeyEpoch(1, context, 2, 103, testSealer("new"))
        }

        assertEquals(old.deviceSeq, replacement.deviceSeq)
        assertEquals(old.logicalEvent, replacement.logicalEvent)
        assertNotEquals(old.envelope, replacement.envelope)
        assertEquals(old, store.readState().archivedSealedEvents.single().event)
        assertEquals(2, store.readState().sealedOutbox.getValue(1).keyEpoch)
    }

    @Test
    fun keyEpochCannotBePrunedWhileRecoveryOrOutboxNeedsIt() = runTest {
        val initial = LocalSyncStoreState(
            keyEpochs = mapOf(
                1 to KeyEpochMetadata(1, "one", KeyEpochStatus.ACTIVE, 1),
            ),
        )
        val store = InMemoryLocalSyncStore(initial)
        store.transaction {
            retainKeyEpoch(KeyEpochMetadata(2, "two", KeyEpochStatus.ACTIVE, 2))
            moveRecoveryBaseTo(2)
        }
        val sealed = store.sealDraft(addDraft(store, "one", 100), context, 2, 101, testSealer("two"))
        assertTrue(1 in store.transaction { prunableKeyEpochs() })
        assertFalse(sealed.keyEpoch in store.transaction { prunableKeyEpochs() })
    }

    @Test
    fun materializationReportAndExactTrustApprovalSurviveCrashUntilProjectionCompletes() = runTest {
        val repositoryKey = SyncEntityKey.extensionRepository("https://repo.example/index.json")
        val request = RepositoryTrustConfirmation(
            repositoryKey = repositoryKey,
            baseUrl = repositoryKey.canonicalValue,
            trustedFingerprint = "old-key",
            proposedFingerprint = "new-key",
        )
        val issue = MaterializationIssue(
            MaterializationIssueKind.ORPHAN,
            chapter,
            "Chapter parent is absent or tombstoned",
        )
        val persistence = InMemorySyncStatePersistence()
        var store = PersistentLocalSyncStore.open(persistence)
        store.transaction { requestMaterializationRetry() }
        val requested = store.readState()

        assertTrue(
            store.transaction {
                completeMaterialization(
                    expectedReplica = requested.replica,
                    expectedIdentityMap = requested.identityMap,
                    expectedRepositoryTrustApprovals = requested.repositoryTrustApprovals,
                    materializedIdentityMap = requested.identityMap,
                    issues = listOf(issue),
                    repositoryTrustConfirmations = listOf(request),
                )
            },
        )
        store = PersistentLocalSyncStore.open(persistence)
        assertEquals(listOf(issue), store.readState().materializationIssues)
        assertEquals(listOf(request), store.readState().repositoryTrustConfirmations)

        store.transaction { rejectRepositoryTrust(request.baseUrl, request.proposedFingerprint) }
        assertEquals(
            RepositoryTrustConfirmationStatus.REJECTED,
            store.readState().repositoryTrustConfirmations.single().status,
        )
        store.transaction { acceptRepositoryTrust(request.baseUrl, request.proposedFingerprint) }
        store = PersistentLocalSyncStore.open(persistence)
        val accepted = store.readState()
        assertTrue(accepted.materializationPending)
        assertEquals(
            RepositoryTrustConfirmationStatus.ACCEPTED,
            accepted.repositoryTrustApprovals.single().status,
        )
        assertFailsWith<IllegalArgumentException> {
            store.transaction { acceptRepositoryTrust(request.baseUrl, "different-key") }
        }

        assertTrue(
            store.transaction {
                completeMaterialization(
                    expectedReplica = accepted.replica,
                    expectedIdentityMap = accepted.identityMap,
                    expectedRepositoryTrustApprovals = accepted.repositoryTrustApprovals,
                    materializedIdentityMap = accepted.identityMap,
                    issues = emptyList(),
                    repositoryTrustConfirmations = emptyList(),
                )
            },
        )
        val completed = store.readState()
        assertFalse(completed.materializationPending)
        assertTrue(completed.materializationIssues.isEmpty())
        assertTrue(completed.repositoryTrustConfirmations.isEmpty())
        assertTrue(completed.repositoryTrustApprovals.isEmpty())
    }

    @Test
    fun staleProjectionCannotClearAConcurrentMaterializationReportOrApproval() = runTest {
        val repositoryKey = SyncEntityKey.extensionRepository("https://repo.example/index.json")
        val request = RepositoryTrustConfirmation(
            repositoryKey,
            repositoryKey.canonicalValue,
            trustedFingerprint = "old-key",
            proposedFingerprint = "new-key",
        )
        val store = InMemoryLocalSyncStore(
            LocalSyncStoreState(repositoryTrustConfirmations = listOf(request)),
        )
        store.transaction { requestMaterializationRetry() }
        val stale = store.readState()
        store.transaction { acceptRepositoryTrust(request.baseUrl, request.proposedFingerprint) }

        val completed = store.transaction {
            completeMaterialization(
                expectedReplica = stale.replica,
                expectedIdentityMap = stale.identityMap,
                expectedRepositoryTrustApprovals = stale.repositoryTrustApprovals,
                materializedIdentityMap = stale.identityMap,
                issues = emptyList(),
                repositoryTrustConfirmations = emptyList(),
            )
        }

        assertFalse(completed)
        assertTrue(store.readState().materializationPending)
        assertEquals("new-key", store.readState().repositoryTrustApprovals.single().proposedFingerprint)
    }

    @Test
    fun outerProjectionMarkerCannotClearConcurrentTrustAcceptOrReject() = runTest {
        val repositoryKey = SyncEntityKey.extensionRepository("https://repo.example/index.json")
        val request = RepositoryTrustConfirmation(
            repositoryKey,
            repositoryKey.canonicalValue,
            trustedFingerprint = "old-key",
            proposedFingerprint = "new-key",
        )
        val acceptedStore = InMemoryLocalSyncStore(
            LocalSyncStoreState(
                repositoryTrustConfirmations = listOf(request),
                materializationPending = true,
            ),
        )
        val beforeAccept = acceptedStore.readState()
        acceptedStore.transaction { acceptRepositoryTrust(request.baseUrl, request.proposedFingerprint) }

        val acceptMarked = acceptedStore.transaction {
            markMaterialized(
                expectedReplica = beforeAccept.replica,
                expectedIdentityMap = beforeAccept.identityMap,
                expectedRepositoryTrustConfirmations = beforeAccept.repositoryTrustConfirmations,
                expectedRepositoryTrustApprovals = beforeAccept.repositoryTrustApprovals,
            )
        }
        assertFalse(acceptMarked)
        assertTrue(acceptedStore.readState().materializationPending)

        val rejectedStore = InMemoryLocalSyncStore(
            LocalSyncStoreState(
                repositoryTrustConfirmations = listOf(request),
                materializationPending = true,
            ),
        )
        val beforeReject = rejectedStore.readState()
        rejectedStore.transaction { rejectRepositoryTrust(request.baseUrl, request.proposedFingerprint) }

        val rejectMarked = rejectedStore.transaction {
            markMaterialized(
                expectedReplica = beforeReject.replica,
                expectedIdentityMap = beforeReject.identityMap,
                expectedRepositoryTrustConfirmations = beforeReject.repositoryTrustConfirmations,
                expectedRepositoryTrustApprovals = beforeReject.repositoryTrustApprovals,
            )
        }
        assertFalse(rejectMarked)
        assertTrue(rejectedStore.readState().materializationPending)
    }

    @Test
    fun explicitCollisionRepairKeepsFinalMappingAndDetachesObsoleteRemapAlias() = runTest {
        val oldKey = SyncEntityKey.manga("1", "/legacy", version = 1)
        val finalKey = SyncEntityKey.manga("1", "/canonical", version = 2)
        val replica = SyncState(keyRemaps = mapOf(oldKey to finalKey))
        val issue = MaterializationIssue(
            MaterializationIssueKind.IDENTITY_COLLISION,
            finalKey,
            "Remap target already uses a different local id",
        )
        val store = InMemoryLocalSyncStore(
            LocalSyncStoreState(
                replica = replica,
                identityMap = SyncIdentityMap()
                    .bind(oldKey, 10)
                    .bind(finalKey, 20)
                    .block(oldKey)
                    .block(finalKey),
                materializationIssues = listOf(issue),
            ),
        )

        store.transaction { repairIdentityCollision(finalKey) }

        val repaired = store.readState()
        assertEquals(null, repaired.identityMap.localId(oldKey))
        assertEquals(20, repaired.identityMap.localId(finalKey))
        assertTrue(oldKey !in repaired.identityMap.blockedKeys)
        assertTrue(finalKey !in repaired.identityMap.blockedKeys)
        assertTrue(repaired.materializationPending)
        // The durable diagnostic is cleared only after this exact replica projects successfully.
        assertEquals(listOf(issue), repaired.materializationIssues)
    }

    private suspend fun addDraft(store: LocalSyncStore, id: String, now: Long): String = store.transaction {
        val hlc = nextLocalHlc("device-a", now)
        applyLocalEvent(
            event(id, hlc, LibraryEntryPatch(manga, mapOf(SyncFields.Manga.TITLE to SyncValue.StringValue(id)))),
            now,
        ).draftId
    }

    private fun event(id: String, clock: HlcTimestamp, mutation: SyncMutation): SyncEvent =
        SyncEvent(id, clock, listOf(mutation))

    private fun testSealer(suffix: String): SyncEventSealer = SyncEventSealer { request ->
        val hash = "hash-${request.deviceSeq}-$suffix"
        EncryptedSyncEvent(
            header = SyncEventHeader(
                cipherSuite = SyncCipherSuite.AES_256_GCM,
                nonceBase64Url = "nonce-${request.deviceSeq}-$suffix",
                instanceId = request.context.instanceId,
                workspaceId = request.context.workspaceId,
                eventId = "event-${request.deviceSeq}-$suffix",
                deviceId = request.context.deviceId,
                deviceSeq = request.deviceSeq,
                keyEpoch = request.keyEpoch,
                ciphertextSha256Base64Url = hash,
            ),
            authenticatedHeaderBase64Url = "header-$suffix",
            ciphertextBase64Url = "ciphertext-$suffix",
            signatureBase64Url = "signature-$suffix",
        )
    }

    private fun SealedOutboxEvent.receipt(workspaceSeq: Long): SyncReceipt = SyncReceipt(
        eventId = eventId,
        deviceSeq = deviceSeq,
        workspaceSeq = workspaceSeq,
        ciphertextSha256Base64Url = ciphertextSha256Base64Url,
    )
}
