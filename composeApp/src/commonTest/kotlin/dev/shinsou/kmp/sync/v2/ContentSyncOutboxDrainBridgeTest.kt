package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.InMemorySharedContentTransactionStore
import dev.shinsou.kmp.content.SharedContentTransactionStore
import dev.shinsou.kmp.content.SyncDraftContentOutboxAdapter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentSyncOutboxDrainBridgeTest {
    @Test
    fun drainReclocksDraftAfterObservedRemoteClockThenAcknowledgesContentRow() = runTest {
        val content = contentStore()
        val draft = draft("outbox-op")
        content.commit(ContentCommitBatch(commitId = "content-commit", outbox = listOf(draft)))
        val remoteHlc = HlcTimestamp(1_000, 9, "remote-device")
        val local = InMemoryLocalSyncStore(
            LocalSyncStoreState(maxObservedRemoteHlc = remoteHlc),
        )

        val result = ContentSyncOutboxDrainBridge(content, local, "local-device") { 900 }
            .drain()

        assertEquals(1, result.installed)
        assertEquals(1, result.acknowledged)
        assertEquals(0, result.remaining)
        assertTrue(content.pendingOutbox().isEmpty())
        val installed = local.readState().drafts.getValue(draft.draftId)
        assertEquals(draft.event.mutations, installed.event.mutations)
        assertEquals("local-device", installed.event.hlc.deviceId)
        assertTrue(installed.event.hlc > remoteHlc)
    }

    @Test
    fun crashAfterLocalCommitReplaysByOperationIdWithoutCreatingDuplicateDraft() = runTest {
        val delegate = contentStore()
        val draft = draft("restart-safe-op")
        delegate.commit(ContentCommitBatch(commitId = "restart-safe-content", outbox = listOf(draft)))
        val failing = FailOnceAcknowledgementStore(delegate)
        val local = InMemoryLocalSyncStore()
        val bridge = ContentSyncOutboxDrainBridge(failing, local, "device-a") { 100 }

        assertFailsWith<IllegalStateException> { bridge.drain() }
        assertEquals(1, delegate.pendingOutbox().size)
        assertEquals(1, local.readState().drafts.size)

        val replay = bridge.drain()
        assertEquals(0, replay.installed)
        assertEquals(1, replay.replayed)
        assertEquals(1, replay.acknowledged)
        assertTrue(delegate.pendingOutbox().isEmpty())
        assertEquals(1, local.readState().drafts.size)
    }

    private fun contentStore() = InMemorySharedContentTransactionStore(
        blobStore = InMemoryContentBlobStore(),
        outboxAdapter = SyncDraftContentOutboxAdapter,
        syncModeProvider = { ContentSyncMode.V2_ACTIVE },
    )

    private fun draft(opId: String): SyncDraft = SyncDraft(
        draftId = opId,
        event = SyncEvent(
            opId = opId,
            hlc = HlcTimestamp(0, 0, "pending-import"),
            mutations = listOf(
                PublicationPatchV2(
                    key = SyncEntityKey.publication("11111111-1111-4111-8111-111111111111"),
                    fields = mapOf(
                        ContentSyncFields.Publication.TITLE to SyncValue.StringValue("Imported"),
                    ),
                ),
            ),
        ),
        createdAtMillis = 0,
    )

    private class FailOnceAcknowledgementStore(
        private val delegate: SharedContentTransactionStore<SyncDraft>,
    ) : SharedContentTransactionStore<SyncDraft> by delegate {
        private var fail = true

        override fun acknowledgeOutbox(draftIds: Set<String>): Int {
            if (fail) {
                fail = false
                throw IllegalStateException("simulated crash before content acknowledgement")
            }
            return delegate.acknowledgeOutbox(draftIds)
        }
    }
}
