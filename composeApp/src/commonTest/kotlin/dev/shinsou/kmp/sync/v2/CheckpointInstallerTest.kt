package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CheckpointInstallerTest {
    private val codec = DeterministicSyncEventCodec()
    private val manga = SyncEntityKey.manga("1", "/m")

    @Test
    fun installReplaysFixedTailAndRebasesPendingOperationsWithoutDroppingDrafts() = runTest {
        val remoteOne = event("remote-1", 1, "remote", "remote one")
        val remoteTwo = event("remote-2", 2, "remote", "remote two")
        val checkpointState = SyncReducer.reduceCommitted(SyncState(), CommittedSyncEvent(1, remoteOne))
        val checkpoint = verified("checkpoint", checkpointState, ciphertextHash = "checkpoint-hash")

        val store = InMemoryLocalSyncStore()
        store.transaction {
            val localClock = nextLocalHlc("local", 100)
            applyLocalEvent(event("pending", localClock.millis, "local", "local pending"), 100)
            updateIdentityMap(SyncIdentityMap().bind(manga, 7))
        }

        val result = CheckpointInstaller.install(
            store = store,
            checkpoint = checkpoint,
            tail = listOf(CommittedSyncEvent(2, remoteTwo)),
            fixedRemoteHead = 2,
            codec = codec,
        )

        val installed = result.state
        assertEquals(2, installed.replica.throughWorkspaceSeq)
        assertEquals(1, result.rebasedPendingOperationCount)
        assertEquals(1, installed.drafts.size)
        assertEquals(7, installed.identityMap.localId(manga))
        val title = installed.replica.entities.getValue(manga).fields.getValue(SyncFields.Manga.TITLE).value
        assertEquals(SyncValue.StringValue("local pending"), title)
        assertTrue(installed.materializationPending)
    }

    @Test
    fun installObservesGreatestCheckpointRegisterBeforeNextLocalClock() = runTest {
        val remoteClock = HlcTimestamp(50_000, 7, "remote")
        val checkpointState = SyncReducer.reduceCommitted(
            SyncState(),
            CommittedSyncEvent(
                workspaceSeq = 1,
                event = SyncEvent(
                    opId = "remote-high-clock",
                    hlc = remoteClock,
                    mutations = listOf(
                        LibraryEntryPatch(
                            key = manga,
                            fields = mapOf(SyncFields.Manga.TITLE to SyncValue.StringValue("remote")),
                        ),
                    ),
                ),
            ),
        )
        val store = InMemoryLocalSyncStore()

        CheckpointInstaller.install(
            store = store,
            checkpoint = verified("high-clock", checkpointState, ciphertextHash = "checkpoint-hash"),
            tail = emptyList(),
            fixedRemoteHead = 1,
            codec = codec,
        )

        assertEquals(remoteClock, store.readState().maxObservedRemoteHlc)
        val nextLocal = store.transaction { nextLocalHlc("local", wallMillis = 100) }
        assertTrue(nextLocal > remoteClock)
    }

    @Test
    fun invalidCanonicalCheckpointLeavesStoreUntouched() = runTest {
        val store = InMemoryLocalSyncStore()
        val before = store.readState()
        val state = SyncState()
        val checkpoint = VerifiedSyncCheckpoint(
            header = header("bad", state, "bad-hash"),
            state = state,
            canonicalState = BinaryData.copyOf(byteArrayOf(1, 2, 3)),
        )

        assertFailsWith<SyncInvariantViolation> {
            CheckpointInstaller.install(store, checkpoint, emptyList(), 0, codec)
        }
        assertEquals(before, store.readState())
    }

    @Test
    fun candidateAckRequiresByteExactIndependentReplayAndChain() {
        val firstEvent = event("one", 1, "remote", "one")
        val secondEvent = event("two", 2, "remote", "two")
        val firstState = SyncReducer.reduceCommitted(SyncState(), CommittedSyncEvent(1, firstEvent))
        val previous = verified("previous", firstState, ciphertextHash = "previous-ciphertext")
        val secondState = SyncReducer.reduceCommitted(firstState, CommittedSyncEvent(2, secondEvent))
        val candidate = verified(
            id = "candidate",
            state = secondState.copy(previousStableCheckpointHash = "previous-ciphertext"),
            ciphertextHash = "candidate-ciphertext",
            previousHash = "previous-ciphertext",
        )

        CheckpointInstaller.verifyCandidateByReplay(
            previous,
            candidate,
            listOf(CommittedSyncEvent(2, secondEvent)),
            codec,
        )

        val corrupt = candidate.copy(canonicalState = BinaryData.copyOf(byteArrayOf(0)))
        assertFailsWith<SyncInvariantViolation> {
            CheckpointInstaller.verifyCandidateByReplay(
                previous,
                corrupt,
                listOf(CommittedSyncEvent(2, secondEvent)),
                codec,
            )
        }
    }

    private fun verified(
        id: String,
        state: SyncState,
        ciphertextHash: String,
        previousHash: String? = state.previousStableCheckpointHash,
    ): VerifiedSyncCheckpoint = VerifiedSyncCheckpoint(
        header = header(id, state, ciphertextHash, previousHash),
        state = state,
        canonicalState = codec.canonicalCheckpointState(state.normalized()),
    )

    private fun header(
        id: String,
        state: SyncState,
        ciphertextHash: String,
        previousHash: String? = state.previousStableCheckpointHash,
    ): SyncCheckpointHeader = SyncCheckpointHeader(
        cipherSuite = SyncCipherSuite.AES_256_GCM,
        nonceBase64Url = "nonce-$id",
        instanceId = "instance",
        workspaceId = "workspace",
        checkpointId = id,
        deviceId = "remote",
        throughWorkspaceSeq = state.throughWorkspaceSeq,
        keyEpoch = state.keyEpoch,
        previousStableCiphertextSha256Base64Url = previousHash,
        compression = SyncCheckpointCompression.LZ4_BLOCK_V1,
        uncompressedSize = codec.canonicalCheckpointState(state.normalized()).size,
        ciphertextSha256Base64Url = ciphertextHash,
    )

    private fun event(id: String, millis: Long, device: String, title: String): SyncEvent = SyncEvent(
        opId = id,
        hlc = HlcTimestamp(millis, 0, device),
        mutations = listOf(
            LibraryEntryPatch(
                manga,
                mapOf(SyncFields.Manga.TITLE to SyncValue.StringValue(title)),
            ),
        ),
    )
}
