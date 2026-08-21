package dev.shinsou.kmp.sync.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.sync.v2.BlobDekEnvelopeRewrappedV2
import dev.shinsou.kmp.sync.v2.BlobDekEnvelopeV2
import dev.shinsou.kmp.sync.v2.BlobEnvelopeRewrapRequestV2
import dev.shinsou.kmp.sync.v2.BlobGcReceiptV2
import dev.shinsou.kmp.sync.v2.BlobRewrapCheckpointEvidenceV2
import dev.shinsou.kmp.sync.v2.BlobTombstoneAckRequestV2
import dev.shinsou.kmp.sync.v2.BlobTombstoneHandleV2
import dev.shinsou.kmp.sync.v2.BlobTombstoneDispositionV2
import dev.shinsou.kmp.sync.v2.DurableBlobLifecycleIntentV2
import dev.shinsou.kmp.sync.v2.PreparedBlobEnvelopeRewrapV2
import dev.shinsou.kmp.sync.v2.RetainedCheckpointDescriptor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class SqlDriverBlobLifecycleJournalV2Test {
    @Test
    fun exactRewrapAndCompletedTombstoneSurviveRestartWithoutStageRegression() = runTest {
        val directory = Files.createTempDirectory("blob-lifecycle-journal-restart")
        val database = directory.resolve("sync.db")
        try {
            driver(database.toString()).use { firstDriver ->
                val journal = SqlDriverBlobLifecycleJournalV2(firstDriver)
                journal.save(REWRAP_PREPARED)
                assertEquals(REWRAP_PREPARED, journal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID))
            }

            driver(database.toString()).use { responseDriver ->
                val journal = SqlDriverBlobLifecycleJournalV2(responseDriver)
                journal.save(REWRAP_COMMITTED)
                assertEquals(listOf(REWRAP_COMMITTED), journal.entries(INSTANCE_ID, WORKSPACE_ID))
                assertFailsWith<IllegalArgumentException> { journal.save(REWRAP_PREPARED) }
                assertEquals(true, journal.remove(REWRAP_COMMITTED))
                journal.save(TOMBSTONE_PREPARED)
            }

            driver(database.toString()).use { ackDriver ->
                val journal = SqlDriverBlobLifecycleJournalV2(ackDriver)
                assertEquals(TOMBSTONE_PREPARED, journal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID))
                journal.save(TOMBSTONE_ACKED)
                journal.save(TOMBSTONE_COMPLETED)
            }

            driver(database.toString()).use { finalDriver ->
                val journal = SqlDriverBlobLifecycleJournalV2(finalDriver)
                assertEquals(TOMBSTONE_COMPLETED, journal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID))
                assertEquals(listOf(TOMBSTONE_COMPLETED), journal.entries(INSTANCE_ID, WORKSPACE_ID))
                assertFailsWith<IllegalArgumentException> { journal.save(TOMBSTONE_ACKED) }
                assertEquals(false, journal.remove(TOMBSTONE_ACKED))
                assertEquals(true, journal.remove(TOMBSTONE_COMPLETED))
                assertNull(journal.load(INSTANCE_ID, WORKSPACE_ID, BLOB_ID))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun driver(path: String): JdbcSqliteDriver = JdbcSqliteDriver("jdbc:sqlite:$path")

    private companion object {
        const val INSTANCE_ID = "71000000-0000-4000-8000-000000000001"
        const val WORKSPACE_ID = "71000000-0000-4000-8000-000000000002"
        const val BLOB_ID = "71000000-0000-4000-8000-000000000003"
        const val MANIFEST_ID = "71000000-0000-4000-8000-000000000004"
        const val CHECKPOINT_ID = "71000000-0000-4000-8000-000000000005"
        const val TOMBSTONE_ID = "71000000-0000-4000-8000-000000000006"
        const val GC_RECEIPT_ID = "71000000-0000-4000-8000-000000000007"
        const val WORKER_TOMBSTONE_ID = "71000000-0000-4000-8000-000000000008"
        val HASH_1: String = "A".repeat(43)
        val HASH_2: String = "B".repeat(43)
        val SIGNATURE: String = "C".repeat(86)

        val CHECKPOINT = RetainedCheckpointDescriptor(
            checkpointId = CHECKPOINT_ID,
            throughWorkspaceSeq = 20,
            keyEpoch = 2,
            ciphertextSha256Base64Url = HASH_2,
        )
        val EVIDENCE = BlobRewrapCheckpointEvidenceV2(CHECKPOINT_ID, HASH_2, 20)
        val ENVELOPE = BlobDekEnvelopeV2(
            blobId = BLOB_ID,
            keyEpoch = 2,
            nonceBase64Url = "AQ",
            wrappedDekBase64Url = "Ag",
            envelopeSha256Base64Url = HASH_2,
            previousEnvelopeSha256Base64Url = HASH_1,
        )
        val PREPARED = PreparedBlobEnvelopeRewrapV2(
            instanceId = INSTANCE_ID,
            workspaceId = WORKSPACE_ID,
            blobId = BLOB_ID,
            request = BlobEnvelopeRewrapRequestV2(
                manifestId = MANIFEST_ID,
                envelope = ENVELOPE,
                checkpointEvidence = EVIDENCE,
            ),
        )
        val MUTATION = BlobDekEnvelopeRewrappedV2(BLOB_ID, MANIFEST_ID, ENVELOPE, EVIDENCE)
        val REWRAP_PREPARED = DurableBlobLifecycleIntentV2.EnvelopeRewrap(PREPARED)
        val REWRAP_COMMITTED = REWRAP_PREPARED.copy(committedMutation = MUTATION)

        val TOMBSTONE = BlobTombstoneHandleV2(
            instanceId = INSTANCE_ID,
            workspaceId = WORKSPACE_ID,
            tombstoneId = TOMBSTONE_ID,
            blobId = BLOB_ID,
            manifestId = MANIFEST_ID,
            referenceThroughWorkspaceSeq = 20,
            requestedCreatedAtEpochMillis = 1_000,
        )
        val ACK = BlobTombstoneAckRequestV2(
            tombstoneId = WORKER_TOMBSTONE_ID,
            checkpointId = CHECKPOINT_ID,
            checkpointCiphertextSha256Base64Url = HASH_2,
            throughWorkspaceSeq = 20,
            signatureBase64Url = SIGNATURE,
        )
        val RECEIPT = BlobGcReceiptV2(GC_RECEIPT_ID, BLOB_ID, 3, 100, 2_000)
        val TOMBSTONE_PREPARED = DurableBlobLifecycleIntentV2.ReferenceTombstone(
            handle = TOMBSTONE,
            referenceCheckpoint = CHECKPOINT,
        )
        val TOMBSTONE_ACKED = TOMBSTONE_PREPARED.copy(
            handle = TOMBSTONE.copy(
                tombstoneId = WORKER_TOMBSTONE_ID,
                executeAfterEpochMillis = 2_000,
            ),
            createdOnWorker = true,
            creationDisposition = BlobTombstoneDispositionV2.ACTIVE,
            acknowledgement = ACK,
            acknowledgementCommitted = true,
        )
        val TOMBSTONE_COMPLETED = TOMBSTONE_ACKED.copy(gcReceipt = RECEIPT)
    }
}
