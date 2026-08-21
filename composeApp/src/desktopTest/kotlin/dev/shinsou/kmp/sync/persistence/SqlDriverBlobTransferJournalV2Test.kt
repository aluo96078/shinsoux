package dev.shinsou.kmp.sync.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.sync.v2.BlobBodyCommitReceiptV2
import dev.shinsou.kmp.sync.v2.BlobDekEnvelopeV2
import dev.shinsou.kmp.sync.v2.BlobUploadIntentV2
import dev.shinsou.kmp.sync.v2.BlobTransferKeyV2
import dev.shinsou.kmp.sync.v2.RemoteBlobBodyManifestRefV2
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class SqlDriverBlobTransferJournalV2Test {
    @Test
    fun committedReceiptAndCleanupSurviveProcessReopen() = runTest {
        val directory = Files.createTempDirectory("blob-transfer-journal-restart")
        val database = directory.resolve("sync.db")
        try {
            driver(database.toString()).use { firstDriver ->
                val first = SqlDriverBlobTransferJournalV2(firstDriver)
                first.saveIntent(INTENT)
                assertEquals(INTENT, first.loadIntent(TRANSFER_KEY))
                assertNull(first.loadCommitted(TRANSFER_KEY))
            }

            driver(database.toString()).use { commitDriver ->
                val reopened = SqlDriverBlobTransferJournalV2(commitDriver)
                assertEquals(INTENT, reopened.loadIntent(TRANSFER_KEY))
                reopened.markCommitted(TRANSFER_KEY, RECEIPT)
            }

            driver(database.toString()).use { cleanupDriver ->
                val reopened = SqlDriverBlobTransferJournalV2(cleanupDriver)
                assertEquals(listOf(TRANSFER_KEY), reopened.committedKeys(INSTANCE_ID, WORKSPACE_ID))
                assertEquals(INTENT, reopened.loadIntent(TRANSFER_KEY))
                assertEquals(RECEIPT, reopened.loadCommitted(TRANSFER_KEY))
                reopened.removeCompleted(TRANSFER_KEY)
            }

            driver(database.toString()).use { finalDriver ->
                val reopened = SqlDriverBlobTransferJournalV2(finalDriver)
                assertEquals(emptyList(), reopened.committedKeys(INSTANCE_ID, WORKSPACE_ID))
                assertNull(reopened.loadIntent(TRANSFER_KEY))
                assertNull(reopened.loadCommitted(TRANSFER_KEY))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun sameBlobReceiptCannotCrossWorkspaceAuthority() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val journal = SqlDriverBlobTransferJournalV2(driver)
            journal.saveIntent(INTENT)
            journal.markCommitted(TRANSFER_KEY, RECEIPT)

            assertNull(journal.loadIntent(FOREIGN_TRANSFER_KEY))
            assertNull(journal.loadCommitted(FOREIGN_TRANSFER_KEY))
            assertEquals(emptyList(), journal.committedKeys(INSTANCE_ID, FOREIGN_WORKSPACE_ID))
            assertEquals(RECEIPT, journal.loadCommitted(TRANSFER_KEY))
        } finally {
            driver.close()
        }
    }

    @Test
    fun unscopedLegacyRowsAreDiscardedInsteadOfAssignedToActiveWorkspace() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(
                null,
                """
                    CREATE TABLE sync_blob_transfer_journal_v2(
                      blob_id TEXT NOT NULL PRIMARY KEY,
                      intent_json TEXT,
                      receipt_json TEXT
                    ) WITHOUT ROWID
                """.trimIndent(),
                0,
            ).value
            driver.execute(
                null,
                "INSERT INTO sync_blob_transfer_journal_v2(blob_id, intent_json) VALUES (?, ?)",
                2,
            ) {
                bindString(0, BLOB_ID)
                bindString(1, "{}")
            }.value

            val journal = SqlDriverBlobTransferJournalV2(driver)
            assertNull(journal.loadIntent(TRANSFER_KEY))
            assertEquals(emptyList(), journal.committedKeys(INSTANCE_ID, WORKSPACE_ID))
            journal.saveIntent(INTENT)
            assertEquals(INTENT, journal.loadIntent(TRANSFER_KEY))
        } finally {
            driver.close()
        }
    }

    private fun driver(path: String): JdbcSqliteDriver = JdbcSqliteDriver("jdbc:sqlite:$path")

    private companion object {
        const val BLOB_ID = "30000000-0000-4000-8000-000000000001"
        const val MANIFEST_ID = "30000000-0000-4000-8000-000000000002"
        const val SESSION_ID = "30000000-0000-4000-8000-000000000003"
        const val RECEIPT_ID = "30000000-0000-4000-8000-000000000004"
        const val INSTANCE_ID = "30000000-0000-4000-8000-000000000005"
        const val WORKSPACE_ID = "30000000-0000-4000-8000-000000000006"
        const val FOREIGN_WORKSPACE_ID = "30000000-0000-4000-8000-000000000007"
        val SHA256_BASE64_URL: String = "A".repeat(43)

        val BLOB: BlobRef = BlobRef(
            blobId = BLOB_ID,
            schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
            digestAlgorithm = BlobRef.SHA_256,
            plaintextDigest = "0".repeat(64),
            byteSize = 100,
            mediaType = "text/plain",
        )
        val ENVELOPE: BlobDekEnvelopeV2 = BlobDekEnvelopeV2(
            blobId = BLOB_ID,
            keyEpoch = 1,
            nonceBase64Url = "A".repeat(16),
            wrappedDekBase64Url = "A".repeat(64),
            envelopeSha256Base64Url = SHA256_BASE64_URL,
        )
        val TRANSFER_KEY = BlobTransferKeyV2(INSTANCE_ID, WORKSPACE_ID, BLOB_ID, 1)
        val FOREIGN_TRANSFER_KEY = BlobTransferKeyV2(INSTANCE_ID, FOREIGN_WORKSPACE_ID, BLOB_ID, 1)
        val INTENT: BlobUploadIntentV2 = BlobUploadIntentV2(
            transferKey = TRANSFER_KEY,
            manifestId = MANIFEST_ID,
            blob = BLOB,
            keyEpoch = 1,
            chunkSizeBytes = RemoteBlobBodyManifestRefV2.MIN_CHUNK_BYTES,
            dekEnvelope = ENVELOPE,
            createdAtEpochMillis = 1_000,
        )
        val REMOTE_MANIFEST: RemoteBlobBodyManifestRefV2 = RemoteBlobBodyManifestRefV2(
            manifestId = MANIFEST_ID,
            blobId = BLOB_ID,
            manifestCiphertextSha256Base64Url = SHA256_BASE64_URL,
            manifestCiphertextByteSize = 80,
            bodyCiphertextByteSize = 116,
            chunkCount = 1,
            chunkSizeBytes = RemoteBlobBodyManifestRefV2.MIN_CHUNK_BYTES,
            committedAtEpochMillis = 2_000,
            commitReceiptId = RECEIPT_ID,
        )
        val RECEIPT: BlobBodyCommitReceiptV2 = BlobBodyCommitReceiptV2(
            receiptId = RECEIPT_ID,
            sessionId = SESSION_ID,
            manifest = REMOTE_MANIFEST,
        )
    }
}
