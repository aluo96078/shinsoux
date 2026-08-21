package dev.shinsou.kmp.sync.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.sync.v2.BlobBodyCommitReceiptV2
import dev.shinsou.kmp.sync.v2.BlobTransferJournalV2
import dev.shinsou.kmp.sync.v2.BlobTransferKeyV2
import dev.shinsou.kmp.sync.v2.BlobUploadIntentV2
import dev.shinsou.kmp.sync.v2.validateLifecycleTenant
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Restart-safe authority/generation-scoped body-transfer journal. */
public class SqlDriverBlobTransferJournalV2(
    private val driver: SqlDriver,
    json: Json = BlobTransferJournalJson,
) : BlobTransferJournalV2 {
    private val mutex = Mutex()
    private val codec = Json(json) {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        allowStructuredMapKeys = true
    }

    init {
        migrateUnscopedSchemaFailClosed()
        createScopedTables()
    }

    override suspend fun loadIntent(key: BlobTransferKeyV2): BlobUploadIntentV2? = locked {
        readRow(key)?.intentJson?.let {
            decodeCanonical(BlobUploadIntentV2.serializer(), it, "blob upload intent")
                .also { intent -> check(intent.transferKey == key) { "Blob intent row identity mismatch" } }
        }
    }

    override suspend fun saveIntent(intent: BlobUploadIntentV2) = locked {
        val key = intent.transferKey
        val existing = readRow(key)
        existing?.intentJson?.let { encoded ->
            val current = decodeCanonical(BlobUploadIntentV2.serializer(), encoded, "blob upload intent")
            require(current == intent) { "A different upload intent already exists for this transfer" }
            return@locked
        }
        driver.execute(
            null,
            """
                INSERT INTO $TABLE_NAME(
                  instance_id, workspace_id, blob_id, generation, intent_json, receipt_json
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(instance_id, workspace_id, blob_id, generation)
                DO UPDATE SET intent_json = excluded.intent_json
            """.trimIndent(),
            6,
        ) {
            bindString(0, key.instanceId)
            bindString(1, key.workspaceId)
            bindString(2, key.blobId)
            bindLong(3, key.generation)
            bindString(4, codec.encodeToString(BlobUploadIntentV2.serializer(), intent))
            bindString(5, existing?.receiptJson)
        }.await()
    }

    override suspend fun loadCommitted(key: BlobTransferKeyV2): BlobBodyCommitReceiptV2? = locked {
        readRow(key)?.receiptJson?.let {
            decodeCanonical(BlobBodyCommitReceiptV2.serializer(), it, "blob body receipt")
                .also { receipt ->
                    check(receipt.manifest.blobId == key.blobId) { "Blob receipt row identity mismatch" }
                }
        }
    }

    override suspend fun committedKeys(
        instanceId: String,
        workspaceId: String,
    ): List<BlobTransferKeyV2> = locked {
        validateLifecycleTenant(instanceId, workspaceId)
        driver.executeQuery(
            null,
            """
                SELECT blob_id, generation FROM $TABLE_NAME
                WHERE instance_id = ? AND workspace_id = ? AND receipt_json IS NOT NULL
                ORDER BY blob_id, generation
            """.trimIndent(),
            { cursor ->
                val keys = mutableListOf<BlobTransferKeyV2>()
                while (cursor.next().value) {
                    keys += BlobTransferKeyV2(
                        instanceId,
                        workspaceId,
                        requireNotNull(cursor.getString(0)),
                        requireNotNull(cursor.getLong(1)),
                    )
                }
                QueryResult.Value(keys)
            },
            2,
        ) {
            bindString(0, instanceId)
            bindString(1, workspaceId)
        }.await()
    }

    override suspend fun markCommitted(
        key: BlobTransferKeyV2,
        receipt: BlobBodyCommitReceiptV2,
    ) = locked {
        require(receipt.manifest.blobId == key.blobId)
        val row = requireNotNull(readRow(key)) { "A blob commit receipt requires its durable upload intent" }
        val intentJson = requireNotNull(row.intentJson) { "A blob commit receipt lost its upload intent" }
        val intent = decodeCanonical(BlobUploadIntentV2.serializer(), intentJson, "blob upload intent")
        require(intent.transferKey == key) { "Blob commit intent belongs to another authority or generation" }
        row.receiptJson?.let { encoded ->
            val current = decodeCanonical(BlobBodyCommitReceiptV2.serializer(), encoded, "blob body receipt")
            require(current == receipt) { "A different blob commit receipt is already durable" }
            return@locked
        }
        driver.execute(
            null,
            """
                UPDATE $TABLE_NAME SET receipt_json = ?
                WHERE instance_id = ? AND workspace_id = ? AND blob_id = ? AND generation = ?
            """.trimIndent(),
            5,
        ) {
            bindString(0, codec.encodeToString(BlobBodyCommitReceiptV2.serializer(), receipt))
            bindString(1, key.instanceId)
            bindString(2, key.workspaceId)
            bindString(3, key.blobId)
            bindLong(4, key.generation)
        }.await()
    }

    override suspend fun removeIntent(key: BlobTransferKeyV2) = locked {
        val row = readRow(key) ?: return@locked
        require(row.receiptJson == null) { "A committed upload must be cleared with removeCompleted" }
        deleteRow(key)
    }

    override suspend fun removeCompleted(key: BlobTransferKeyV2) = locked {
        val row = requireNotNull(readRow(key)) { "Completed blob transfer is missing" }
        require(row.intentJson != null && row.receiptJson != null) {
            "Completed blob transfer requires both its intent and receipt"
        }
        deleteRow(key)
    }

    override suspend fun loadSchedulingCursor(instanceId: String, workspaceId: String): String? = locked {
        validateLifecycleTenant(instanceId, workspaceId)
        driver.executeQuery(
            null,
            "SELECT job_id FROM $CURSOR_TABLE WHERE instance_id = ? AND workspace_id = ?",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            2,
        ) {
            bindString(0, instanceId)
            bindString(1, workspaceId)
        }.await()
    }

    override suspend fun saveSchedulingCursor(instanceId: String, workspaceId: String, jobId: String) = locked {
        validateLifecycleTenant(instanceId, workspaceId)
        require(jobId.isNotBlank() && jobId.length <= 512) { "Blob scheduling cursor is invalid" }
        driver.execute(
            null,
            """
                INSERT INTO $CURSOR_TABLE(instance_id, workspace_id, job_id) VALUES (?, ?, ?)
                ON CONFLICT(instance_id, workspace_id) DO UPDATE SET job_id = excluded.job_id
            """.trimIndent(),
            3,
        ) {
            bindString(0, instanceId)
            bindString(1, workspaceId)
            bindString(2, jobId)
        }.await()
        Unit
    }

    override suspend fun clearAuthority(instanceId: String, workspaceId: String): Int = locked {
        validateLifecycleTenant(instanceId, workspaceId)
        val count = countRows(instanceId, workspaceId)
        driver.execute(null, "BEGIN IMMEDIATE", 0).await()
        try {
            driver.execute(
                null,
                "DELETE FROM $TABLE_NAME WHERE instance_id = ? AND workspace_id = ?",
                2,
            ) {
                bindString(0, instanceId)
                bindString(1, workspaceId)
            }.await()
            driver.execute(
                null,
                "DELETE FROM $CURSOR_TABLE WHERE instance_id = ? AND workspace_id = ?",
                2,
            ) {
                bindString(0, instanceId)
                bindString(1, workspaceId)
            }.await()
            driver.execute(null, "COMMIT", 0).await()
        } catch (failure: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0).await() }
            throw failure
        }
        count
    }

    private suspend fun countRows(instanceId: String, workspaceId: String): Int = driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM $TABLE_NAME WHERE instance_id = ? AND workspace_id = ?",
        { cursor ->
            check(cursor.next().value)
            QueryResult.Value(requireNotNull(cursor.getLong(0)).toInt())
        },
        2,
    ) {
        bindString(0, instanceId)
        bindString(1, workspaceId)
    }.await()

    private suspend fun readRow(key: BlobTransferKeyV2): JournalRow? = driver.executeQuery(
        null,
        """
            SELECT intent_json, receipt_json FROM $TABLE_NAME
            WHERE instance_id = ? AND workspace_id = ? AND blob_id = ? AND generation = ?
        """.trimIndent(),
        { cursor ->
            QueryResult.Value(
                if (cursor.next().value) JournalRow(cursor.getString(0), cursor.getString(1)) else null,
            )
        },
        4,
    ) {
        bindString(0, key.instanceId)
        bindString(1, key.workspaceId)
        bindString(2, key.blobId)
        bindLong(3, key.generation)
    }.await()

    private suspend fun deleteRow(key: BlobTransferKeyV2) {
        driver.execute(
            null,
            """
                DELETE FROM $TABLE_NAME
                WHERE instance_id = ? AND workspace_id = ? AND blob_id = ? AND generation = ?
            """.trimIndent(),
            4,
        ) {
            bindString(0, key.instanceId)
            bindString(1, key.workspaceId)
            bindString(2, key.blobId)
            bindLong(3, key.generation)
        }.await()
    }

    /** Unscoped legacy rows cannot prove their tenant, so migration discards them atomically. */
    private fun migrateUnscopedSchemaFailClosed() {
        val columns = tableColumns(TABLE_NAME)
        if (columns.isEmpty() || REQUIRED_COLUMNS.all(columns::contains)) return
        driver.execute(null, "BEGIN IMMEDIATE", 0).value
        try {
            driver.execute(null, "DROP TABLE $TABLE_NAME", 0).value
            createTransferTable()
            driver.execute(null, "COMMIT", 0).value
        } catch (failure: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0).value }
            throw failure
        }
    }

    private fun createScopedTables() {
        createTransferTable()
        driver.execute(
            null,
            """
                CREATE TABLE IF NOT EXISTS $CURSOR_TABLE(
                  instance_id TEXT NOT NULL,
                  workspace_id TEXT NOT NULL,
                  job_id TEXT NOT NULL,
                  PRIMARY KEY(instance_id, workspace_id)
                ) WITHOUT ROWID
            """.trimIndent(),
            0,
        ).value
    }

    private fun createTransferTable() {
        driver.execute(
            null,
            """
                CREATE TABLE IF NOT EXISTS $TABLE_NAME(
                  instance_id TEXT NOT NULL,
                  workspace_id TEXT NOT NULL,
                  blob_id TEXT NOT NULL,
                  generation INTEGER NOT NULL CHECK(generation > 0),
                  intent_json TEXT,
                  receipt_json TEXT,
                  CHECK(intent_json IS NOT NULL OR receipt_json IS NOT NULL),
                  PRIMARY KEY(instance_id, workspace_id, blob_id, generation)
                ) WITHOUT ROWID
            """.trimIndent(),
            0,
        ).value
    }

    private fun tableColumns(table: String): Set<String> = driver.executeQuery(
        null,
        "PRAGMA table_info($table)",
        { cursor ->
            val columns = linkedSetOf<String>()
            while (cursor.next().value) columns += requireNotNull(cursor.getString(1))
            QueryResult.Value(columns)
        },
        0,
    ).value

    private fun <T> decodeCanonical(serializer: KSerializer<T>, encoded: String, label: String): T {
        val decoded = codec.decodeFromString(serializer, encoded)
        check(codec.encodeToString(serializer, decoded) == encoded) { "Persisted $label is not canonical" }
        return decoded
    }

    private suspend fun <T> locked(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    private data class JournalRow(val intentJson: String?, val receiptJson: String?)

    internal companion object {
        const val TABLE_NAME: String = "sync_blob_transfer_journal_v2"
        const val CURSOR_TABLE: String = "sync_blob_transfer_scheduler_v2"
        private val REQUIRED_COLUMNS = setOf(
            "instance_id",
            "workspace_id",
            "blob_id",
            "generation",
            "intent_json",
            "receipt_json",
        )
    }
}

private val BlobTransferJournalJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    allowStructuredMapKeys = true
}
