package dev.shinsou.kmp.sync.v2

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncKeyRetentionCoordinatorTest {
    @Test
    fun usesSecondServerOrderedStableAndPreservesEveryRequiredEpoch() = runTest {
        val localStore = InMemoryLocalSyncStore(keyState(activeEpoch = 5, epochs = 1..5))
        val secrets = RecordingSecretStore().apply { install(1..5) }
        val coordinator = coordinator(localStore, secrets)
        val candidate = CheckpointCandidateDescriptor(
            checkpointId = "candidate-is-not-stable",
            throughWorkspaceSeq = 300,
            keyEpoch = 5,
            ciphertextSha256Base64Url = "candidate-hash",
            uploaderDeviceId = "device-a",
            createdAtMillis = 10,
        )
        val bootstrap = bootstrap(
            activeEpoch = 5,
            requiredEpochs = setOf(1, 3, 4, 5),
            stable = listOf(
                stable("000-newest", 300, 5),
                // Random IDs deliberately sort in the opposite order from server promotion.
                stable("zzz-recovery", 200, 3),
                stable("aaa-older-fallback", 100, 1),
            ),
            candidate = candidate,
        )

        val result = coordinator.reconcile(session(5), bootstrap)

        val state = localStore.readState()
        assertEquals(3, result.recoveryBaseKeyEpoch)
        assertEquals(setOf(2), result.prunedKeyEpochs)
        assertEquals(3, state.recoveryBaseKeyEpoch)
        assertEquals(listOf(1, 3, 4, 5), state.serverRequiredKeyEpochs)
        assertEquals(setOf(1, 3, 4, 5), state.keyEpochs.keys)
        assertTrue(secrets.hasEpoch(1), "an older retained fallback remains required")
        assertTrue(!secrets.hasEpoch(2))
        assertNull(state.keyEpochPruningIntent)
    }

    @Test
    fun candidateDoesNotSubstituteForSecondVerifiedStable() = runTest {
        val initial = keyState(activeEpoch = 3, epochs = 1..3)
        val localStore = InMemoryLocalSyncStore(initial)
        val secrets = RecordingSecretStore().apply { install(1..3) }
        val bootstrap = bootstrap(
            activeEpoch = 3,
            requiredEpochs = setOf(1, 3),
            stable = listOf(stable("only-stable", 100, 1)),
            candidate = CheckpointCandidateDescriptor(
                checkpointId = "unverified-candidate",
                throughWorkspaceSeq = 200,
                keyEpoch = 3,
                ciphertextSha256Base64Url = "candidate-hash",
                uploaderDeviceId = "device-b",
                createdAtMillis = 20,
                previousStableCheckpointId = "only-stable",
                previousStableThroughWorkspaceSeq = 100,
                previousStableCiphertextSha256Base64Url = "hash-only-stable",
            ),
        )

        coordinator(localStore, secrets).reconcile(session(3), bootstrap)

        assertEquals(initial, localStore.readState())
        assertTrue(secrets.deletedEpochs.isEmpty())
    }

    @Test
    fun missingRequiredSecretPreventsEveryRetentionMutation() = runTest {
        val initial = keyState(activeEpoch = 5, epochs = 1..5)
        val localStore = InMemoryLocalSyncStore(initial)
        val secrets = RecordingSecretStore().apply { install(listOf(1, 2, 3, 5)) }
        val bootstrap = bootstrap(
            activeEpoch = 5,
            requiredEpochs = setOf(3, 4, 5),
            stable = listOf(stable("newest", 300, 5), stable("recovery", 200, 3)),
        )

        assertFailsWith<SyncSecretAccessException.Missing> {
            coordinator(localStore, secrets).reconcile(session(5), bootstrap)
        }

        assertEquals(initial, localStore.readState())
        assertTrue(secrets.deletedEpochs.isEmpty())
    }

    @Test
    fun bootstrapMustExplicitlyRequireItsActiveEpoch() = runTest {
        val initial = keyState(activeEpoch = 5, epochs = 1..5)
        val localStore = InMemoryLocalSyncStore(initial)
        val secrets = RecordingSecretStore().apply { install(1..5) }

        assertFailsWith<SyncInvariantViolation> {
            coordinator(localStore, secrets).reconcile(
                session(5),
                bootstrap(
                    activeEpoch = 5,
                    requiredEpochs = setOf(3),
                    stable = listOf(stable("newest", 300, 3), stable("recovery", 200, 3)),
                ),
            )
        }

        assertEquals(initial, localStore.readState())
        assertTrue(secrets.deletedEpochs.isEmpty())
    }

    @Test
    fun sealedOutboxEpochBlocksDeletionUntilItsReceiptIsDurable() = runTest {
        val sealed = sealedEvent(keyEpoch = 1)
        val initial = keyState(activeEpoch = 3, epochs = 1..3).copy(
            sealedOutbox = mapOf(1L to sealed),
            nextDeviceSeq = 2,
        )
        val localStore = InMemoryLocalSyncStore(initial)
        val secrets = RecordingSecretStore().apply { install(1..3) }
        val coordinator = coordinator(localStore, secrets)
        val bootstrap = bootstrap(
            activeEpoch = 3,
            requiredEpochs = setOf(2, 3),
            stable = listOf(stable("newest", 200, 3), stable("recovery", 100, 2)),
        )

        val blocked = coordinator.reconcile(session(3), bootstrap)

        assertEquals(setOf(1), blocked.outboxBlockedKeyEpochs)
        assertEquals(listOf(1), assertNotNull(localStore.readState().keyEpochPruningIntent).pendingEpochs)
        assertTrue(1 in localStore.readState().keyEpochs)
        assertTrue(secrets.hasEpoch(1))

        localStore.transaction {
            recordReceipt(
                SyncReceipt(
                    eventId = sealed.eventId,
                    deviceSeq = sealed.deviceSeq,
                    workspaceSeq = 1,
                    ciphertextSha256Base64Url = sealed.ciphertextSha256Base64Url,
                ),
            )
        }
        val resumed = coordinator.resumePendingPruning(WORKSPACE_ID)

        assertEquals(setOf(1), resumed.prunedKeyEpochs)
        assertTrue(1 !in localStore.readState().keyEpochs)
        assertTrue(!secrets.hasEpoch(1))
        assertNull(localStore.readState().keyEpochPruningIntent)
    }

    @Test
    fun secretDeletionFailureLeavesDurableIntentAndMetadataForRetry() = runTest {
        val localStore = InMemoryLocalSyncStore(keyState(activeEpoch = 5, epochs = 1..5))
        val secrets = RecordingSecretStore().apply {
            install(1..5)
            failNextDeleteEpoch = 1
        }
        val coordinator = coordinator(localStore, secrets)
        val bootstrap = bootstrap(
            activeEpoch = 5,
            requiredEpochs = setOf(3, 4, 5),
            stable = listOf(stable("newest", 300, 5), stable("recovery", 200, 3)),
        )

        assertFailsWith<IllegalStateException> {
            coordinator.reconcile(session(5), bootstrap)
        }

        val failed = localStore.readState()
        assertEquals(3, failed.recoveryBaseKeyEpoch)
        assertEquals(listOf(1, 2), assertNotNull(failed.keyEpochPruningIntent).pendingEpochs)
        assertTrue(1 in failed.keyEpochs)
        assertTrue(secrets.hasEpoch(1))

        val resumed = coordinator.resumePendingPruning(WORKSPACE_ID)
        assertEquals(setOf(1, 2), resumed.prunedKeyEpochs)
        assertNull(localStore.readState().keyEpochPruningIntent)
    }

    @Test
    fun restartRetriesCrashAfterSecretDeletionBeforeMetadataCommit() = runTest {
        val persistence = InMemorySyncStatePersistence(keyState(activeEpoch = 5, epochs = 1..5))
        val firstStore = PersistentLocalSyncStore.open(persistence)
        val secrets = RecordingSecretStore().apply { install(1..5) }
        var observedDeleteBeforeMetadata = false
        secrets.beforeDelete = { key ->
            if (key == SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 1)) {
                val durable = firstStore.readState()
                observedDeleteBeforeMetadata = 1 in durable.keyEpochs &&
                    1 in assertNotNull(durable.keyEpochPruningIntent).pendingEpochs
                persistence.failNextSave = IllegalStateException("simulated crash before metadata commit")
                secrets.beforeDelete = null
            }
        }
        val bootstrap = bootstrap(
            activeEpoch = 5,
            requiredEpochs = setOf(3, 4, 5),
            stable = listOf(stable("newest", 300, 5), stable("recovery", 200, 3)),
        )

        assertFailsWith<IllegalStateException> {
            coordinator(firstStore, secrets).reconcile(session(5), bootstrap)
        }
        assertTrue(observedDeleteBeforeMetadata)
        assertTrue(!secrets.hasEpoch(1), "secret deletion committed before local metadata failed")
        assertTrue(1 in firstStore.readState().keyEpochs)
        assertNotNull(firstStore.readState().keyEpochPruningIntent)

        val reopened = PersistentLocalSyncStore.open(persistence)
        val resumed = coordinator(reopened, secrets).resumePendingPruning(WORKSPACE_ID)

        assertEquals(setOf(1, 2), resumed.prunedKeyEpochs)
        assertEquals(setOf(3, 4, 5), reopened.readState().keyEpochs.keys)
        assertNull(reopened.readState().keyEpochPruningIntent)
        assertEquals(listOf(1, 2), secrets.deletedEpochs)
    }

    @Test
    fun authenticatedRecoveryBaseCanOnlyAdvanceMonotonically() = runTest {
        val localStore = InMemoryLocalSyncStore(keyState(activeEpoch = 5, epochs = 1..5))
        val secrets = RecordingSecretStore().apply { install(1..5) }
        val coordinator = coordinator(localStore, secrets)
        coordinator.reconcile(
            session(5),
            bootstrap(
                activeEpoch = 5,
                requiredEpochs = setOf(2, 3, 5),
                stable = listOf(stable("newest", 300, 5), stable("recovery-3", 200, 3), stable("old", 100, 2)),
            ),
        )
        val advanced = localStore.readState()
        assertEquals(3, advanced.recoveryBaseKeyEpoch)

        assertFailsWith<SyncInvariantViolation> {
            coordinator.reconcile(
                session(5),
                bootstrap(
                    activeEpoch = 5,
                    requiredEpochs = setOf(2, 5),
                    stable = listOf(stable("newest-2", 400, 5), stable("recovery-regressed", 300, 2)),
                ),
            )
        }

        assertEquals(advanced, localStore.readState())
        assertEquals(3, localStore.readState().recoveryBaseKeyEpoch)
    }

    private fun coordinator(
        localStore: LocalSyncStore,
        secrets: SyncSecretStore,
    ) = SyncKeyRetentionCoordinator(localStore, secrets) { 1_000 }

    private fun session(activeEpoch: Int) = SyncSession(
        endpoint = "https://sync.example",
        instanceId = "instance",
        userId = "user",
        workspaceId = WORKSPACE_ID,
        deviceId = "device-a",
        deviceDisplayName = "Test device",
        platform = "desktop",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = activeEpoch,
    )

    private fun bootstrap(
        activeEpoch: Int,
        requiredEpochs: Set<Int>,
        stable: List<RetainedCheckpointDescriptor>,
        candidate: CheckpointCandidateDescriptor? = null,
    ) = BootstrapResponse(
        headSeq = 500,
        activeKeyEpoch = activeEpoch,
        retainedStableCheckpoints = stable,
        requiredKeyEpochs = requiredEpochs,
        candidateCheckpoint = candidate,
    )

    private fun stable(id: String, throughSeq: Long, keyEpoch: Int) = RetainedCheckpointDescriptor(
        checkpointId = id,
        throughWorkspaceSeq = throughSeq,
        keyEpoch = keyEpoch,
        ciphertextSha256Base64Url = "hash-$id",
    )

    private fun keyState(activeEpoch: Int, epochs: IntRange): LocalSyncStoreState = LocalSyncStoreState(
        replica = SyncState(keyEpoch = activeEpoch),
        activeKeyEpoch = activeEpoch,
        keyEpochs = epochs.associateWith { epoch ->
            KeyEpochMetadata(
                epoch = epoch,
                secretKeyId = "workspace-epoch-$epoch",
                status = if (epoch == activeEpoch) KeyEpochStatus.ACTIVE else KeyEpochStatus.RETAINED,
                createdAtMillis = epoch.toLong(),
            )
        },
    )

    private fun sealedEvent(keyEpoch: Int): SealedOutboxEvent {
        val logical = SyncEvent(
            opId = "pending-op",
            hlc = HlcTimestamp(100, 0, "device-a"),
            mutations = listOf(LibraryEntryPatch(SyncEntityKey.manga("source", "/manga"), emptyMap())),
        )
        return SealedOutboxEvent(
            draftId = logical.opId,
            logicalEvent = logical,
            envelope = EncryptedSyncEvent(
                header = SyncEventHeader(
                    cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
                    nonceBase64Url = "nonce",
                    instanceId = "instance",
                    workspaceId = WORKSPACE_ID,
                    eventId = "event-1",
                    deviceId = "device-a",
                    deviceSeq = 1,
                    keyEpoch = keyEpoch,
                    ciphertextSha256Base64Url = "ciphertext-hash",
                ),
                authenticatedHeaderBase64Url = "header",
                ciphertextBase64Url = "ciphertext",
                signatureBase64Url = "signature",
            ),
            sealedAtMillis = 100,
        )
    }

    private class RecordingSecretStore : SyncSecretStore {
        private val values = mutableMapOf<SyncSecretKey, SecretMaterial>()
        val deletedEpochs = mutableListOf<Int>()
        var failNextDeleteEpoch: Int? = null
        var beforeDelete: (suspend (SyncSecretKey) -> Unit)? = null

        fun install(epochs: Iterable<Int>) {
            epochs.forEach { epoch ->
                values[SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, epoch)] = SecretMaterial(listOf(epoch.toByte()))
            }
        }

        suspend fun hasEpoch(epoch: Int): Boolean =
            read(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, epoch)) is SyncSecretReadResult.Available

        override suspend fun read(key: SyncSecretKey): SyncSecretReadResult =
            values[key]?.let(SyncSecretReadResult::Available) ?: SyncSecretReadResult.Missing

        override suspend fun write(key: SyncSecretKey, material: SecretMaterial) {
            values[key] = material
        }

        override suspend fun delete(key: SyncSecretKey) {
            beforeDelete?.invoke(key)
            val epoch = (key as? SyncSecretKey.WorkspaceEpochKey)?.epoch
            if (epoch != null) deletedEpochs += epoch
            if (epoch != null && failNextDeleteEpoch == epoch) {
                failNextDeleteEpoch = null
                throw IllegalStateException("protected store delete failed")
            }
            values.remove(key)
        }
    }

    private companion object {
        const val WORKSPACE_ID = "workspace"
    }
}
