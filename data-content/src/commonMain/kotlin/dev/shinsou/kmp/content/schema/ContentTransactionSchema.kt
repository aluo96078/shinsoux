package dev.shinsou.kmp.content.schema

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.shinsou.kmp.content.blob.ContentBlobPersistenceSchema
import dev.shinsou.kmp.content.sql.executeUncached
import dev.shinsou.kmp.content.transaction.SqlDriverContentTransactionStore

/**
 * SQLite schema shared by Desktop, Android and Native content transaction stores. [create] brings
 * an existing database up to [VERSION] as well ([SchemaMigrator]) and refuses one written by a
 * newer app ([SchemaTooNewException]). The version stays in `content_transaction_schema`, where
 * older versions of the app look for it.
 */
public object ContentTransactionSchema : SqlSchema<QueryResult.Value<Unit>> {
    public const val VERSION: Long = 5
    override val version: Long = VERSION

    internal val component: SchemaComponent = SchemaComponent(
        name = "content",
        version = VERSION,
        create = ::createTables,
        // Formats 2 to 5 only added tables, all of which createTables makes when they are missing.
        steps = mapOf(VERSION to ::createTables),
        // The marker was always written with the tables: none means an empty database.
        unrecordedVersion = { null },
        versionStore = SchemaVersionStore.Singleton(TABLE_SCHEMA),
    )

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        ContentBlobPersistenceSchema.create(driver)
        SchemaMigrator.migrate(driver, component)
        createLookupIndexes(driver)
        return QueryResult.Unit
    }

    /**
     * Reading one publication selects its acquisitions, rights, units and grants by `publication_id`.
     * Without these indexes every read scanned the whole table, so looking up each library title
     * in turn grew with the square of the library. They change no row and no older app reads
     * them, so they are made on every open (a no-op once they exist) rather than by a version
     * step an older app would refuse.
     */
    private fun createLookupIndexes(driver: SqlDriver) {
        listOf(TABLE_ACQUISITIONS, TABLE_ACQUISITION_RIGHTS, TABLE_UNITS, TABLE_RIGHTS_GRANTS).forEach { table ->
            driver.executeUncached("CREATE INDEX IF NOT EXISTS ${table}_publication ON $table(publication_id)", 0).value
        }
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        require(oldVersion <= newVersion) { "Content transaction database cannot migrate backwards" }
        create(driver)
        callbacks.sortedBy(AfterVersion::afterVersion).forEach { callback ->
            if (callback.afterVersion in oldVersion until newVersion) callback.block(driver)
        }
        return QueryResult.Unit
    }

    private fun createTables(driver: SqlDriver) {
        listOf(
            """
                CREATE TABLE IF NOT EXISTS $TABLE_SCHEMA(
                  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                  format_version INTEGER NOT NULL
                )
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_METADATA(
                  key TEXT NOT NULL PRIMARY KEY,
                  value TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_ALIASES(
                  alias TEXT NOT NULL PRIMARY KEY,
                  target TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_MIGRATIONS(
                  migration_key TEXT NOT NULL PRIMARY KEY,
                  namespace TEXT NOT NULL,
                  source_digest TEXT NOT NULL,
                  result_fingerprint TEXT NOT NULL,
                  commit_id TEXT NOT NULL,
                  mutation_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_PUBLICATIONS(
                  publication_id TEXT NOT NULL PRIMARY KEY,
                  publication_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_ACQUISITIONS(
                  acquisition_id TEXT NOT NULL PRIMARY KEY,
                  publication_id TEXT NOT NULL,
                  acquisition_json TEXT NOT NULL,
                  FOREIGN KEY(publication_id) REFERENCES $TABLE_PUBLICATIONS(publication_id) ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_ACQUISITION_RIGHTS(
                  acquisition_id TEXT NOT NULL PRIMARY KEY,
                  publication_id TEXT NOT NULL,
                  grant_ref_json TEXT NOT NULL,
                  FOREIGN KEY(acquisition_id) REFERENCES $TABLE_ACQUISITIONS(acquisition_id) ON DELETE CASCADE,
                  FOREIGN KEY(publication_id) REFERENCES $TABLE_PUBLICATIONS(publication_id) ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_RIGHTS_GRANTS(
                  grant_id TEXT NOT NULL PRIMARY KEY,
                  publication_id TEXT NOT NULL,
                  acquisition_id TEXT NOT NULL,
                  unit_id TEXT,
                  manifest_id TEXT,
                  content_revision INTEGER,
                  grant_json TEXT NOT NULL,
                  FOREIGN KEY(publication_id) REFERENCES $TABLE_PUBLICATIONS(publication_id)
                    ON DELETE CASCADE,
                  FOREIGN KEY(acquisition_id) REFERENCES $TABLE_ACQUISITIONS(acquisition_id)
                    ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_UNITS(
                  unit_id TEXT NOT NULL PRIMARY KEY,
                  publication_id TEXT NOT NULL,
                  acquisition_id TEXT NOT NULL,
                  unit_json TEXT NOT NULL,
                  FOREIGN KEY(publication_id) REFERENCES $TABLE_PUBLICATIONS(publication_id) ON DELETE CASCADE,
                  FOREIGN KEY(acquisition_id) REFERENCES $TABLE_ACQUISITIONS(acquisition_id) ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_WORK_LINKS(
                  publication_id TEXT NOT NULL,
                  ordinal INTEGER NOT NULL,
                  target_publication_id TEXT NOT NULL,
                  link_json TEXT NOT NULL,
                  PRIMARY KEY(publication_id, ordinal),
                  FOREIGN KEY(publication_id) REFERENCES $TABLE_PUBLICATIONS(publication_id) ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_ATTACHMENTS(
                  attachment_key TEXT NOT NULL PRIMARY KEY,
                  owner_scope TEXT NOT NULL,
                  manifest_id TEXT NOT NULL,
                  content_revision INTEGER NOT NULL,
                  manifest_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_ATTACHMENT_REFS(
                  attachment_key TEXT NOT NULL,
                  blob_id TEXT NOT NULL,
                  reference_json TEXT NOT NULL,
                  PRIMARY KEY(attachment_key, blob_id),
                  FOREIGN KEY(attachment_key) REFERENCES $TABLE_ATTACHMENTS(attachment_key) ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_AUXILIARY_ATTACHMENTS(
                  attachment_key TEXT NOT NULL PRIMARY KEY,
                  owner_id TEXT NOT NULL,
                  purpose TEXT NOT NULL,
                  attachment_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_AUXILIARY_ATTACHMENT_REFS(
                  attachment_key TEXT NOT NULL,
                  blob_id TEXT NOT NULL,
                  reference_json TEXT NOT NULL,
                  PRIMARY KEY(attachment_key, blob_id),
                  FOREIGN KEY(attachment_key) REFERENCES $TABLE_AUXILIARY_ATTACHMENTS(attachment_key)
                    ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_QUARANTINES(
                  quarantine_id TEXT NOT NULL PRIMARY KEY,
                  auxiliary_attachment_key TEXT NOT NULL,
                  package_id TEXT NOT NULL,
                  version TEXT NOT NULL,
                  origin TEXT NOT NULL,
                  ordinal INTEGER NOT NULL,
                  script_blob_id TEXT NOT NULL,
                  mutation_json TEXT NOT NULL,
                  FOREIGN KEY(auxiliary_attachment_key)
                    REFERENCES $TABLE_AUXILIARY_ATTACHMENTS(attachment_key)
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_OUTBOX(
                  draft_id TEXT NOT NULL PRIMARY KEY,
                  payload TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOB_SYNC_JOBS(
                  job_id TEXT NOT NULL PRIMARY KEY,
                  attachment_key TEXT NOT NULL,
                  blob_id TEXT NOT NULL,
                  owner_scope TEXT NOT NULL,
                  manifest_id TEXT NOT NULL,
                  content_revision INTEGER NOT NULL,
                  grant_id TEXT NOT NULL,
                  mutation_json TEXT NOT NULL,
                  UNIQUE(attachment_key, blob_id),
                  FOREIGN KEY(attachment_key) REFERENCES $TABLE_ATTACHMENTS(attachment_key),
                  FOREIGN KEY(grant_id) REFERENCES $TABLE_RIGHTS_GRANTS(grant_id)
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_PUBLICATION_REPLICA_CURSORS(
                  publication_id TEXT NOT NULL PRIMARY KEY,
                  instance_id TEXT NOT NULL,
                  workspace_id TEXT NOT NULL,
                  through_workspace_seq INTEGER NOT NULL CHECK(through_workspace_seq >= 0),
                  present INTEGER NOT NULL CHECK(present IN (0, 1)),
                  graph_fingerprint TEXT NOT NULL,
                  cursor_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOB_REMOVAL_INTENTS(
                  intent_id TEXT NOT NULL PRIMARY KEY,
                  publication_id TEXT NOT NULL,
                  through_workspace_seq INTEGER NOT NULL CHECK(through_workspace_seq >= 0),
                  reason TEXT NOT NULL,
                  intent_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_COMMITS(
                  commit_id TEXT NOT NULL PRIMARY KEY,
                  fingerprint TEXT NOT NULL,
                  result_json TEXT NOT NULL,
                  receipt_descriptors_json TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
        ).forEach { sql -> driver.executeUncached(sql, 0).value }
    }
}

/**
 * The INTEGER columns [SqlDriverContentTransactionStore] reads, by name in any of its tables (and
 * the `COUNT(*)` alias): they are read with `getLong`, every other column with `getString`.
 */
internal val INTEGER_COLUMNS = setOf(
    "content_revision",
    "ordinal",
    "through_workspace_seq",
    "present",
    "draft_count",
)

internal const val TABLE_SCHEMA = "content_transaction_schema"
internal const val TABLE_METADATA = "content_transaction_metadata"
internal const val TABLE_ALIASES = "content_transaction_aliases"
internal const val TABLE_MIGRATIONS = "content_transaction_migrations"
internal const val TABLE_PUBLICATIONS = "content_publications"
internal const val TABLE_ACQUISITIONS = "content_acquisitions"
internal const val TABLE_ACQUISITION_RIGHTS = "content_acquisition_rights"
internal const val TABLE_RIGHTS_GRANTS = "content_rights_grants"
internal const val TABLE_UNITS = "content_units"
internal const val TABLE_WORK_LINKS = "content_work_links"
internal const val TABLE_ATTACHMENTS = "content_transaction_attachments"
internal const val TABLE_ATTACHMENT_REFS = "content_transaction_attachment_refs"
internal const val TABLE_AUXILIARY_ATTACHMENTS = "content_auxiliary_attachments"
internal const val TABLE_AUXILIARY_ATTACHMENT_REFS = "content_auxiliary_attachment_refs"
internal const val TABLE_QUARANTINES = "content_quarantines"
internal const val TABLE_OUTBOX = "content_transaction_outbox"
internal const val TABLE_BLOB_SYNC_JOBS = "content_blob_sync_jobs"
internal const val TABLE_PUBLICATION_REPLICA_CURSORS = "content_publication_replica_cursors"
internal const val TABLE_BLOB_REMOVAL_INTENTS = "content_blob_removal_intents"
internal const val TABLE_COMMITS = "content_transaction_commits"
internal const val RIGHTS_GRANT_COLUMNS =
    "grant_id, publication_id, acquisition_id, unit_id, manifest_id, content_revision, grant_json"
