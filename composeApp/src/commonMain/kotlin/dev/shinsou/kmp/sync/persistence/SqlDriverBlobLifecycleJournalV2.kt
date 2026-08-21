package dev.shinsou.kmp.sync.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.sync.v2.BlobLifecycleJournalV2
import dev.shinsou.kmp.sync.v2.DurableBlobLifecycleIntentV2
import dev.shinsou.kmp.sync.v2.journalKey
import dev.shinsou.kmp.sync.v2.requireMonotonicTransitionTo
import dev.shinsou.kmp.sync.v2.validateLifecycleTenant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

/** Restart-safe re-wrap/tombstone/ack/GC journal beside the authoritative sync SQLite state. */
public class SqlDriverBlobLifecycleJournalV2(
    private val driver: SqlDriver,
    json: Json = BlobLifecycleJournalJson,
) : BlobLifecycleJournalV2 {
    private val mutex = Mutex()
    private val codec = Json(json) {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        allowStructuredMapKeys = true
        classDiscriminator = "intentType"
    }

    init {
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE IF NOT EXISTS $TABLE_NAME(
                  instance_id TEXT NOT NULL,
                  workspace_id TEXT NOT NULL,
                  blob_id TEXT NOT NULL,
                  intent_json TEXT NOT NULL,
                  PRIMARY KEY(instance_id, workspace_id, blob_id)
                ) WITHOUT ROWID
            """.trimIndent(),
            parameters = 0,
        ).value
    }

    override suspend fun entries(
        instanceId: String,
        workspaceId: String,
    ): List<DurableBlobLifecycleIntentV2> = mutex.withLock {
        validateLifecycleTenant(instanceId, workspaceId)
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT blob_id, intent_json
                FROM $TABLE_NAME
                WHERE instance_id = ? AND workspace_id = ?
                ORDER BY blob_id
            """.trimIndent(),
            mapper = { cursor ->
                val intents = mutableListOf<DurableBlobLifecycleIntentV2>()
                while (cursor.next().value) {
                    val blobId = requireNotNull(cursor.getString(0))
                    val intent = decodeCanonical(requireNotNull(cursor.getString(1)))
                    check(intent.instanceId == instanceId && intent.workspaceId == workspaceId &&
                        intent.blobId == blobId) {
                        "Blob lifecycle journal row identity mismatch"
                    }
                    intents += intent
                }
                QueryResult.Value(intents)
            },
            parameters = 2,
        ) {
            bindString(0, instanceId)
            bindString(1, workspaceId)
        }.await()
    }

    override suspend fun load(
        instanceId: String,
        workspaceId: String,
        blobId: String,
    ): DurableBlobLifecycleIntentV2? = mutex.withLock {
        read(instanceId, workspaceId, blobId)
    }

    override suspend fun save(intent: DurableBlobLifecycleIntentV2) {
        mutex.withLock {
            val key = intent.journalKey()
            val existing = read(key.instanceId, key.workspaceId, key.blobId)
            existing?.requireMonotonicTransitionTo(intent)
            val encoded = codec.encodeToString(DurableBlobLifecycleIntentV2.serializer(), intent)
            driver.execute(
                identifier = null,
                sql = """
                    INSERT INTO $TABLE_NAME(instance_id, workspace_id, blob_id, intent_json)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(instance_id, workspace_id, blob_id)
                    DO UPDATE SET intent_json = excluded.intent_json
                """.trimIndent(),
                parameters = 4,
            ) {
                bindString(0, key.instanceId)
                bindString(1, key.workspaceId)
                bindString(2, key.blobId)
                bindString(3, encoded)
            }.await()
        }
    }

    override suspend fun remove(intent: DurableBlobLifecycleIntentV2): Boolean = mutex.withLock {
        val key = intent.journalKey()
        if (read(key.instanceId, key.workspaceId, key.blobId) != intent) return@withLock false
        driver.execute(
            identifier = null,
            sql = """
                DELETE FROM $TABLE_NAME
                WHERE instance_id = ? AND workspace_id = ? AND blob_id = ?
            """.trimIndent(),
            parameters = 3,
        ) {
            bindString(0, key.instanceId)
            bindString(1, key.workspaceId)
            bindString(2, key.blobId)
        }.await()
        true
    }

    override suspend fun clearAuthority(instanceId: String, workspaceId: String): Int = mutex.withLock {
        validateLifecycleTenant(instanceId, workspaceId)
        val count = driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM $TABLE_NAME WHERE instance_id = ? AND workspace_id = ?",
            mapper = { cursor ->
                check(cursor.next().value)
                QueryResult.Value(requireNotNull(cursor.getLong(0)).toInt())
            },
            parameters = 2,
        ) {
            bindString(0, instanceId)
            bindString(1, workspaceId)
        }.await()
        driver.execute(
            identifier = null,
            sql = "DELETE FROM $TABLE_NAME WHERE instance_id = ? AND workspace_id = ?",
            parameters = 2,
        ) {
            bindString(0, instanceId)
            bindString(1, workspaceId)
        }.await()
        count
    }

    private suspend fun read(
        instanceId: String,
        workspaceId: String,
        blobId: String,
    ): DurableBlobLifecycleIntentV2? {
        val key = dev.shinsou.kmp.sync.v2.BlobLifecycleJournalKeyV2(instanceId, workspaceId, blobId)
        return driver.executeQuery(
            identifier = null,
            sql = """
                SELECT intent_json
                FROM $TABLE_NAME
                WHERE instance_id = ? AND workspace_id = ? AND blob_id = ?
            """.trimIndent(),
            mapper = { cursor ->
                QueryResult.Value(
                    if (!cursor.next().value) {
                        null
                    } else {
                        decodeCanonical(requireNotNull(cursor.getString(0))).also { intent ->
                            check(intent.journalKey() == key) { "Blob lifecycle journal row identity mismatch" }
                        }
                    },
                )
            },
            parameters = 3,
        ) {
            bindString(0, instanceId)
            bindString(1, workspaceId)
            bindString(2, blobId)
        }.await()
    }

    private fun decodeCanonical(encoded: String): DurableBlobLifecycleIntentV2 {
        val decoded = codec.decodeFromString(DurableBlobLifecycleIntentV2.serializer(), encoded)
        val canonical = codec.encodeToString(DurableBlobLifecycleIntentV2.serializer(), decoded)
        check(canonical == encoded || encoded in legacyCanonicalEncodings(decoded)) {
            "Persisted blob lifecycle intent is not canonical"
        }
        return decoded
    }

    /**
     * Generation and scheduler rotation were added to the already-deployed v2 journal. Accept
     * only byte-canonical encodings produced by that older serializer; arbitrary omitted fields,
     * reordered JSON, and non-default values still fail closed.
     */
    private fun legacyCanonicalEncodings(
        decoded: DurableBlobLifecycleIntentV2,
    ): Set<String> {
        val modern = codec.encodeToJsonElement(
            DurableBlobLifecycleIntentV2.serializer(),
            decoded,
        ) as JsonObject
        var variants = setOf(modern)
        variants = variants.withOptionalFieldRemoved("attemptCount")
        variants = when (decoded) {
            is DurableBlobLifecycleIntentV2.EnvelopeRewrap -> variants
                .withOptionalNestedFieldRemoved("prepared", "generation")
                .withOptionalNestedFieldRemoved("committedMutation", "generation")
            is DurableBlobLifecycleIntentV2.ReferenceTombstone -> variants
                .withOptionalNestedFieldRemoved("handle", "generation")
        }
        return variants
            .asSequence()
            .filterNot { it == modern }
            .mapTo(linkedSetOf()) { candidate ->
                codec.encodeToString(JsonElement.serializer(), candidate)
            }
    }

    internal companion object {
        const val TABLE_NAME: String = "sync_blob_lifecycle_journal_v2"
    }
}

private fun Set<JsonObject>.withOptionalFieldRemoved(field: String): Set<JsonObject> =
    flatMapTo(linkedSetOf()) { candidate ->
        listOf(candidate, candidate.withoutField(field))
    }

private fun Set<JsonObject>.withOptionalNestedFieldRemoved(
    objectField: String,
    nestedField: String,
): Set<JsonObject> = flatMapTo(linkedSetOf()) { candidate ->
    val nested = candidate[objectField] as? JsonObject
    if (nested == null || nestedField !in nested) {
        listOf(candidate)
    } else {
        val updated = LinkedHashMap(candidate)
        updated[objectField] = nested.withoutField(nestedField)
        listOf(candidate, JsonObject(updated))
    }
}

private fun JsonObject.withoutField(field: String): JsonObject =
    JsonObject(LinkedHashMap(this).apply { remove(field) })

private val BlobLifecycleJournalJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    allowStructuredMapKeys = true
    classDiscriminator = "intentType"
}
