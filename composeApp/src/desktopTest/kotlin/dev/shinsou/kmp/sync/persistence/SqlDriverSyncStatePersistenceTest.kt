package dev.shinsou.kmp.sync.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.sync.v2.ArchivedSealedEvent
import dev.shinsou.kmp.sync.v2.CategoryMembershipKey
import dev.shinsou.kmp.sync.v2.EncryptedSyncEvent
import dev.shinsou.kmp.sync.v2.GenesisCheckpointSeed
import dev.shinsou.kmp.sync.v2.HlcTimestamp
import dev.shinsou.kmp.sync.v2.KeyEpochMetadata
import dev.shinsou.kmp.sync.v2.KeyEpochPruningIntent
import dev.shinsou.kmp.sync.v2.KeyEpochStatus
import dev.shinsou.kmp.sync.v2.LibraryEntryPatch
import dev.shinsou.kmp.sync.v2.LocalSyncStoreState
import dev.shinsou.kmp.sync.v2.LwwRegister
import dev.shinsou.kmp.sync.v2.MaterializationIssue
import dev.shinsou.kmp.sync.v2.MaterializationIssueKind
import dev.shinsou.kmp.sync.v2.ReaderPosition
import dev.shinsou.kmp.sync.v2.ReadingPositionRegister
import dev.shinsou.kmp.sync.v2.ReadingProgressState
import dev.shinsou.kmp.sync.v2.RepositoryTrustConfirmation
import dev.shinsou.kmp.sync.v2.RepositoryTrustConfirmationStatus
import dev.shinsou.kmp.sync.v2.SealedOutboxEvent
import dev.shinsou.kmp.sync.v2.SealingIntent
import dev.shinsou.kmp.sync.v2.SyncCipherSuite
import dev.shinsou.kmp.sync.v2.SyncDraft
import dev.shinsou.kmp.sync.v2.SyncEntityKey
import dev.shinsou.kmp.sync.v2.SyncEntityRecord
import dev.shinsou.kmp.sync.v2.SyncEvent
import dev.shinsou.kmp.sync.v2.SyncEventHeader
import dev.shinsou.kmp.sync.v2.SyncFields
import dev.shinsou.kmp.sync.v2.SyncIdentityMap
import dev.shinsou.kmp.sync.v2.SyncIdentityMapping
import dev.shinsou.kmp.sync.v2.SyncReceipt
import dev.shinsou.kmp.sync.v2.SyncState
import dev.shinsou.kmp.sync.v2.SyncValue
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqlDriverSyncStatePersistenceTest {
    @Test
    fun everyV2ShardAndScalarSurvivesDatabaseReopen() = runTest {
        withDatabase("shinsou-sync-state") { database ->
            val initial = comprehensiveState()
            SqlDriverSyncStatePersistence(JdbcSqliteDriver("jdbc:sqlite:$database")).also { persistence ->
                persistence.saveAtomically(initial)
                persistence.close()
            }

            SqlDriverSyncStatePersistence(JdbcSqliteDriver("jdbc:sqlite:$database")).also { reopened ->
                assertEquals(initial, reopened.load())
                reopened.close()
            }
        }
    }

    @Test
    fun malformedDurableStateFailsClosed() = runTest {
        withDatabase("shinsou-sync-corrupt") { database ->
            val driver = JdbcSqliteDriver("jdbc:sqlite:$database")
            val persistence = SqlDriverSyncStatePersistence(driver)
            driver.execute(
                identifier = null,
                sql = "INSERT OR REPLACE INTO sync_local_state VALUES (1, 1, '{not-json')",
                parameters = 0,
            ).await()

            assertFailsWith<SyncPersistenceCorruptException> { persistence.load() }
            persistence.close()
        }
    }

    @Test
    fun v1ReopenMigratesLosslesslyAndMigrationFailureRollsBackEveryV2Shard() = runTest {
        withDatabase("shinsou-sync-v1") { database ->
            val expected = comprehensiveState()
            val driver = JdbcSqliteDriver("jdbc:sqlite:$database")
            driver.execute(
                null,
                """
                    CREATE TABLE sync_local_state(
                      singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                      format_version INTEGER NOT NULL,
                      state_json TEXT NOT NULL
                    )
                """.trimIndent(),
                0,
            ).await()
            driver.execute(
                null,
                "INSERT INTO sync_local_state(singleton_id, format_version, state_json) VALUES (1, 1, ?)",
                1,
            ) { bindString(0, LegacyJson.encodeToString(expected)) }.await()

            // This is the exact SqlSchema path used by AndroidSqliteDriver and NativeSqliteDriver.
            SyncLocalSchema.migrate(driver, oldVersion = 1, newVersion = 2).value
            val persistence = SqlDriverSyncStatePersistence(driver)
            driver.execute(
                null,
                """
                    CREATE TRIGGER fail_v1_promotion
                    BEFORE INSERT ON sync_local_meta
                    BEGIN SELECT RAISE(ABORT, 'injected migration failure'); END
                """.trimIndent(),
                0,
            ).await()

            assertFails { persistence.load() }
            assertEquals(1L, scalarLong(driver, "SELECT COUNT(*) FROM sync_local_state"))
            assertEquals(0L, scalarLong(driver, "SELECT COUNT(*) FROM sync_replica_entities"))

            driver.execute(null, "DROP TRIGGER fail_v1_promotion", 0).await()
            assertEquals(expected, persistence.load())
            assertEquals(0L, scalarLong(driver, "SELECT COUNT(*) FROM sync_local_state"))
            assertEquals(2L, scalarLong(driver, "SELECT format_version FROM sync_local_meta"))
            persistence.close()

            SqlDriverSyncStatePersistence(JdbcSqliteDriver("jdbc:sqlite:$database")).also { reopened ->
                assertEquals(expected, reopened.load())
                reopened.close()
            }
        }
    }

    @Test
    fun incrementalSaveUpdatesOnlyTheChangedEntityRow() = runTest {
        withDatabase("shinsou-sync-diff") { database ->
            val driver = JdbcSqliteDriver("jdbc:sqlite:$database")
            val persistence = SqlDriverSyncStatePersistence(driver)
            val initial = comprehensiveState()
            persistence.saveAtomically(initial)
            driver.execute(null, "CREATE TABLE entity_update_audit(row_key TEXT NOT NULL)", 0).await()
            driver.execute(
                null,
                """
                    CREATE TRIGGER audit_entity_updates
                    AFTER UPDATE ON sync_replica_entities
                    BEGIN INSERT INTO entity_update_audit(row_key) VALUES (NEW.row_key); END
                """.trimIndent(),
                0,
            ).await()

            val changedKey = mangaV2
            val changedRecord = initial.replica.entities.getValue(changedKey).copy(
                fields = initial.replica.entities.getValue(changedKey).fields +
                    (SyncFields.Manga.TITLE to LwwRegister(SyncValue.StringValue("changed"), HlcTimestamp(99, 0, "device-a"))),
            )
            val changed = initial.copy(
                replica = initial.replica.copy(entities = initial.replica.entities + (changedKey to changedRecord)),
            )
            persistence.saveAtomically(changed)

            assertEquals(listOf(changedKey.stableString()), scalarStrings(driver, "SELECT row_key FROM entity_update_audit"))
            persistence.close()
        }
    }

    @Test
    fun transactionFailureRollsBackEarlierRowsAndDoesNotAdvanceDiffCache() = runTest {
        withDatabase("shinsou-sync-rollback") { database ->
            val driver = JdbcSqliteDriver("jdbc:sqlite:$database")
            val persistence = SqlDriverSyncStatePersistence(driver)
            val initial = comprehensiveState()
            persistence.saveAtomically(initial)

            val changedRecord = initial.replica.entities.getValue(mangaV2).copy(
                fields = initial.replica.entities.getValue(mangaV2).fields +
                    (SyncFields.Manga.TITLE to LwwRegister(SyncValue.StringValue("after"), HlcTimestamp(100, 0, "device-a"))),
            )
            val changed = initial.copy(
                replica = initial.replica.copy(
                    entities = initial.replica.entities + (mangaV2 to changedRecord),
                    portableSettings = initial.replica.portableSettings +
                        ("appearance.theme" to LwwRegister(SyncValue.StringValue("dark"), HlcTimestamp(101, 0, "device-a"))),
                ),
            )
            driver.execute(
                null,
                """
                    CREATE TRIGGER fail_setting_update
                    BEFORE UPDATE ON sync_replica_settings
                    BEGIN SELECT RAISE(ABORT, 'injected transaction failure'); END
                """.trimIndent(),
                0,
            ).await()

            assertFails { persistence.saveAtomically(changed) }
            driver.execute(null, "DROP TRIGGER fail_setting_update", 0).await()
            // If the failed save had advanced the cache, this retry would incorrectly emit no rows.
            persistence.saveAtomically(changed)
            persistence.close()

            SqlDriverSyncStatePersistence(JdbcSqliteDriver("jdbc:sqlite:$database")).also { reopened ->
                assertEquals(changed, reopened.load())
                reopened.close()
            }
        }
    }

    @Test
    fun corruptUnknownAndMissingShardsFailClosed() = runTest {
        assertCorruptionFailsClosed("corrupt-json") { driver ->
            driver.execute(
                null,
                "UPDATE sync_replica_entities SET payload_json = '{not-json' WHERE row_key = ?",
                1,
            ) { bindString(0, mangaV2.stableString()) }.await()
        }
        assertCorruptionFailsClosed("unknown-field") { driver ->
            val payload = scalarString(
                driver,
                "SELECT payload_json FROM sync_replica_entities WHERE row_key = '${mangaV2.stableString()}'",
            )
            val withUnknownField = payload.dropLast(1) + ",\"unknownField\":true}"
            driver.execute(
                null,
                "UPDATE sync_replica_entities SET payload_json = ? WHERE row_key = ?",
                2,
            ) {
                bindString(0, withUnknownField)
                bindString(1, mangaV2.stableString())
            }.await()
        }
        assertCorruptionFailsClosed("missing-row") { driver ->
            driver.execute(
                null,
                "DELETE FROM sync_replica_entities WHERE row_key = ?",
                1,
            ) { bindString(0, mangaV2.stableString()) }.await()
        }
        assertCorruptionFailsClosed("row-key-mismatch") { driver ->
            val payload = scalarString(
                driver,
                "SELECT payload_json FROM sync_replica_entities WHERE row_key = '${mangaV2.stableString()}'",
            ).replace(
                "\"rowKey\":\"${mangaV2.stableString()}\"",
                "\"rowKey\":\"different\"",
            )
            driver.execute(
                null,
                "UPDATE sync_replica_entities SET payload_json = ? WHERE row_key = ?",
                2,
            ) {
                bindString(0, payload)
                bindString(1, mangaV2.stableString())
            }.await()
        }
    }

    @Test
    fun databaseReopensAfterMultipleIncrementalWrites() = runTest {
        withDatabase("shinsou-sync-incremental-reopen") { database ->
            val persistence = SqlDriverSyncStatePersistence(JdbcSqliteDriver("jdbc:sqlite:$database"))
            val initial = comprehensiveState()
            persistence.saveAtomically(initial)
            val second = initial.copy(
                replica = initial.replica.copy(appliedOpIds = initial.replica.appliedOpIds + "op-3"),
                materializationPending = false,
            )
            persistence.saveAtomically(second)
            val third = second.copy(
                replica = second.replica.copy(appliedOpIds = second.replica.appliedOpIds + (4..40).map { "op-$it" }),
                archivedSealedEvents = second.archivedSealedEvents + second.archivedSealedEvents,
            )
            persistence.saveAtomically(third)
            persistence.close()

            SqlDriverSyncStatePersistence(JdbcSqliteDriver("jdbc:sqlite:$database")).also { reopened ->
                assertEquals(third, reopened.load())
                assertEquals(40, reopened.load()?.replica?.appliedOpIds?.size)
                assertEquals(2, reopened.load()?.archivedSealedEvents?.size)
                reopened.close()
            }
        }
    }

    private suspend fun assertCorruptionFailsClosed(
        suffix: String,
        corrupt: suspend (SqlDriver) -> Unit,
    ) {
        withDatabase("shinsou-sync-$suffix") { database ->
            val driver = JdbcSqliteDriver("jdbc:sqlite:$database")
            val persistence = SqlDriverSyncStatePersistence(driver)
            persistence.saveAtomically(comprehensiveState())
            corrupt(driver)
            assertFailsWith<SyncPersistenceCorruptException> { persistence.load() }
            persistence.close()
        }
    }

    private fun comprehensiveState(): LocalSyncStoreState {
        val localHlc = HlcTimestamp(30, 1, "device-a")
        val remoteHlc = HlcTimestamp(31, 0, "device-b")
        val repositoryKey = SyncEntityKey.extensionRepository("https://repo.example/index.json")
        val trustRequest = RepositoryTrustConfirmation(
            repositoryKey,
            repositoryKey.canonicalValue,
            trustedFingerprint = "old-key",
            proposedFingerprint = "new-key",
        )
        val mangaRecord = SyncEntityRecord(
            key = mangaV2,
            fields = mapOf(
                SyncFields.Manga.TITLE to LwwRegister(SyncValue.StringValue("title"), localHlc),
            ),
            presence = LwwRegister(true, localHlc),
        )
        val repositoryRecord = SyncEntityRecord(repositoryKey, presence = LwwRegister(true, remoteHlc))
        val logicalEvent = SyncEvent(
            opId = "sealed-op",
            hlc = HlcTimestamp(20, 0, "device-a"),
            mutations = listOf(LibraryEntryPatch(mangaV2, emptyMap())),
        )
        val sealed = SealedOutboxEvent(
            draftId = "sealed-draft",
            logicalEvent = logicalEvent,
            envelope = EncryptedSyncEvent(
                header = SyncEventHeader(
                    cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
                    nonceBase64Url = "nonce",
                    instanceId = "instance",
                    workspaceId = "workspace",
                    eventId = "event-2",
                    deviceId = "device-a",
                    deviceSeq = 2,
                    keyEpoch = 1,
                    ciphertextSha256Base64Url = "ciphertext-hash",
                ),
                authenticatedHeaderBase64Url = "header",
                ciphertextBase64Url = "ciphertext",
                signatureBase64Url = "signature",
            ),
            sealedAtMillis = 21,
            attemptCount = 1,
            lastAttemptAtMillis = 22,
        )
        val draftEvent = SyncEvent(
            opId = "draft-op",
            hlc = HlcTimestamp(25, 0, "device-a"),
            mutations = listOf(LibraryEntryPatch(mangaV2, mapOf(SyncFields.Manga.FAVORITE to SyncValue.BooleanValue(true)))),
        )
        val category = SyncEntityKey.category("category-portable-id")
        val chapter = SyncEntityKey.chapter("source", "/chapter")
        return LocalSyncStoreState(
            replica = SyncState(
                keyEpoch = 3,
                throughWorkspaceSeq = 17,
                previousStableCheckpointHash = "previous-checkpoint",
                entities = linkedMapOf(mangaV2 to mangaRecord, repositoryKey to repositoryRecord),
                categoryMemberships = mapOf(CategoryMembershipKey(mangaV2, category) to LwwRegister(true, localHlc)),
                readingProgress = mapOf(
                    chapter to ReadingProgressState(
                        chapterKey = chapter,
                        mangaKey = mangaV2,
                        position = ReadingPositionRegister(
                            ReaderPosition(ReadingMode.PAGER_LTR, pageIndex = 7),
                            localHlc,
                            "reader-session",
                        ),
                        readState = LwwRegister(false, localHlc),
                        historyTouchedAt = LwwRegister(123L, localHlc),
                        presence = LwwRegister(true, localHlc),
                    ),
                ),
                portableSettings = mapOf(
                    "appearance.theme" to LwwRegister(SyncValue.StringValue("system"), localHlc),
                ),
                keyRemaps = mapOf(mangaV1 to mangaV2),
                appliedOpIds = linkedSetOf("op-1", "op-2"),
            ),
            lastLocalHlc = localHlc,
            maxObservedRemoteHlc = remoteHlc,
            identityMap = SyncIdentityMap(
                mappings = listOf(SyncIdentityMapping(mangaV2, 10), SyncIdentityMapping(chapter, 11)),
                blockedKeys = setOf(category),
            ),
            drafts = mapOf("draft-1" to SyncDraft("draft-1", draftEvent, "reader", 25, 26)),
            sealedOutbox = mapOf(2L to sealed),
            archivedSealedEvents = listOf(ArchivedSealedEvent(sealed, "stale key epoch", 23)),
            nextDeviceSeq = 4,
            committedDeviceSeq = 1,
            verifiedReceipts = mapOf(3L to SyncReceipt("event-3", 3, 19, "receipt-hash")),
            activeKeyEpoch = 3,
            keyEpochs = mapOf(
                1 to KeyEpochMetadata(1, "epoch-1", KeyEpochStatus.RETAINED, 1),
                2 to KeyEpochMetadata(2, "epoch-2", KeyEpochStatus.RETAINED, 2),
                3 to KeyEpochMetadata(3, "epoch-3", KeyEpochStatus.ACTIVE, 3),
            ),
            recoveryBaseKeyEpoch = 2,
            serverRequiredKeyEpochs = listOf(2, 3),
            keyEpochPruningIntent = KeyEpochPruningIntent(2, listOf(1)),
            sealingIntent = SealingIntent("draft-1", 3, 3, 27),
            genesisCheckpointSeed = GenesisCheckpointSeed("device-a", 10),
            materializationIssues = listOf(
                MaterializationIssue(MaterializationIssueKind.INVALID_FIELD, repositoryKey, "Invalid field"),
            ),
            repositoryTrustConfirmations = listOf(trustRequest),
            repositoryTrustApprovals = listOf(
                trustRequest.copy(status = RepositoryTrustConfirmationStatus.ACCEPTED),
            ),
            materializationPending = true,
        )
    }

    private suspend fun scalarLong(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else error("Missing row")) },
        0,
    ).await()

    private suspend fun scalarString(driver: SqlDriver, sql: String): String = driver.executeQuery(
        null,
        sql,
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0)!! else error("Missing row")) },
        0,
    ).await()

    private suspend fun scalarStrings(driver: SqlDriver, sql: String): List<String> = driver.executeQuery(
        null,
        sql,
        { cursor ->
            val values = mutableListOf<String>()
            while (cursor.next().value) values += cursor.getString(0)!!
            QueryResult.Value(values)
        },
        0,
    ).await()

    private suspend fun withDatabase(prefix: String, block: suspend (Path) -> Unit) {
        val database = Files.createTempFile(prefix, ".db")
        try {
            block(database)
        } finally {
            Files.deleteIfExists(database.resolveSibling("${database.fileName}-shm"))
            Files.deleteIfExists(database.resolveSibling("${database.fileName}-wal"))
            Files.deleteIfExists(database)
        }
    }

    private companion object {
        val mangaV1 = SyncEntityKey.manga("source", "/legacy", version = 1)
        val mangaV2 = SyncEntityKey.manga("source", "/canonical", version = 2)
        val LegacyJson = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
            allowStructuredMapKeys = true
        }
    }
}
