package dev.shinsou.kmp.content

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * Platform-owned durable storage for the content foundation.
 *
 * Implementations own the single local SQLite driver ([contentDriver]) and release it in [close].
 * The database file and blob directory keep the locations they had when this facility also hosted
 * the (removed) sync client, so existing libraries open unchanged.
 */
interface PlatformContentInfrastructure {
    /** Unified local SQLite authority; callers must never close the returned driver directly. */
    fun contentDriver(): SqlDriver

    /**
     * App-private root for immutable content bodies.
     *
     * This path is device-local adapter state: it must never enter a BlobRef or backup. The content
     * foundation stores only an opaque file name beside SQLite lifecycle metadata.
     */
    fun contentBlobDirectory(): String

    fun close()
}

/**
 * SQLDelight schema handed to the Android and Native drivers when they open the content database.
 *
 * The version and DDL are exactly those the database was created with while it also stored the
 * sync journal (`user_version` 2): keeping them means existing files are neither upgraded nor
 * downgraded, and a fresh install gets an identical schema. The `sync_*` tables are no longer read
 * or written. Content tables are created by the content stores themselves.
 */
object PlatformContentDatabaseSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = DATABASE_FORMAT_VERSION

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        createLegacyTable(driver)
        createV2Tables(driver)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        require(oldVersion <= newVersion) { "Content database cannot migrate backwards" }
        if (oldVersion < 1L) createLegacyTable(driver)
        if (oldVersion < 2L && newVersion >= 2L) createV2Tables(driver)
        callbacks.sortedBy(AfterVersion::afterVersion).forEach { callback ->
            if (callback.afterVersion in oldVersion until newVersion) callback.block(driver)
        }
        return QueryResult.Unit
    }

    private fun createLegacyTable(driver: SqlDriver) {
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE IF NOT EXISTS sync_local_state(
                  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                  format_version INTEGER NOT NULL,
                  state_json TEXT NOT NULL
                )
            """.trimIndent(),
            parameters = 0,
        ).value
    }

    private fun createV2Tables(driver: SqlDriver) {
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE IF NOT EXISTS sync_local_meta(
                  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                  format_version INTEGER NOT NULL,
                  scalars_json TEXT NOT NULL
                )
            """.trimIndent(),
            parameters = 0,
        ).value
        LEGACY_SHARD_TABLES.forEach { table ->
            driver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE IF NOT EXISTS $table(
                      row_key TEXT NOT NULL PRIMARY KEY,
                      payload_json TEXT NOT NULL
                    ) WITHOUT ROWID
                """.trimIndent(),
                parameters = 0,
            ).value
        }
    }

    private const val DATABASE_FORMAT_VERSION = 2L

    private val LEGACY_SHARD_TABLES = listOf(
        "sync_replica_entities",
        "sync_replica_memberships",
        "sync_replica_reading_progress",
        "sync_replica_content_annotations_v2",
        "sync_replica_content_memberships_v2",
        "sync_replica_content_reading_progress_v2",
        "sync_replica_blob_references_v2",
        "sync_replica_settings",
        "sync_replica_remaps",
        "sync_replica_applied_ops",
        "sync_identity_mappings",
        "sync_identity_blocked_keys",
        "sync_drafts",
        "sync_sealed_outbox",
        "sync_archived_events",
        "sync_receipts",
        "sync_key_epochs",
        "sync_materialization_issues",
        "sync_repository_trust_confirmations",
        "sync_repository_trust_approvals",
    )
}
