package dev.shinsou.kmp.content

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.annotation.ContentAnnotationSchema
import dev.shinsou.kmp.annotation.sql.ContentAnnotationDatabase
import dev.shinsou.kmp.content.hotstate.LegacyHotStateSchema
import dev.shinsou.kmp.content.hotstate.sql.LegacyHotStateDatabase
import dev.shinsou.kmp.sync.v3.SyncV3Schema
import dev.shinsou.kmp.sync.v3.sql.SyncV3Database
import dev.shinsou.kmp.translation.TranslationMemorySchema
import dev.shinsou.kmp.translation.TranslationMemoryStore
import dev.shinsou.kmp.translation.sql.TranslationMemoryDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C4 (docs/architecture/0007 §6.2): the tables of a table group whose DDL is generated from a `.sq`
 * file are the same whether a database was upgraded by `SchemaMigrator` from any format the app
 * shipped or made new by the generated `Schema.create`: columns (`pragma_table_info`), indexes
 * (`pragma_index_list`, `pragma_index_xinfo`, partial-index condition) and `WITHOUT ROWID`.
 *
 * The shipped formats are written below as the app wrote them (the hand-written DDL before C4).
 */
class GeneratedSchemaMatchesMigratorTest {
    @Test
    fun contentAnnotationsOfEveryShippedFormatMatchTheGeneratedTables() {
        val generated = generated(TABLE_PREFIX_ANNOTATIONS) { ContentAnnotationDatabase.Schema.create(it).value }
        fixtures(ANNOTATIONS_V1, component = "content_annotations", version = 1).forEach { (name, fixture) ->
            assertMigratesTo(generated, TABLE_PREFIX_ANNOTATIONS, name, fixture) { ContentAnnotationSchema.create(it).value }
        }
    }

    @Test
    fun syncV3OfEveryShippedFormatMatchesTheGeneratedTables() {
        val generated = generated(TABLE_PREFIX_SYNC) { SyncV3Database.Schema.create(it).value }
        fixtures(SYNC_V1, component = "sync_v3", version = 1).forEach { (name, fixture) ->
            assertMigratesTo(generated, TABLE_PREFIX_SYNC, name, fixture) { SyncV3Schema.create(it).value }
        }
    }

    /** C3-2: format 1 is the first; a change to the .sq file needs a step and a fixture here. */
    @Test
    fun legacyHotStateOfEveryShippedFormatMatchesTheGeneratedTables() {
        val generated = generated(TABLE_PREFIX_HOT_STATE) { LegacyHotStateDatabase.Schema.create(it).value }
        assertEquals(HOT_STATE_TABLES, generated.tables.keys)
        fixtures(HOT_STATE_V1, component = "legacy_hot_state", version = 1).forEach { (name, fixture) ->
            assertMigratesTo(generated, TABLE_PREFIX_HOT_STATE, name, fixture) { LegacyHotStateSchema.create(it).value }
        }
    }

    @Test
    fun translationMemoryOfEveryShippedFormatMatchesTheGeneratedTables() {
        val generated = generated(TABLE_PREFIX_TM) { TranslationMemoryDatabase.Schema.create(it).value }
        val formats = buildList<Pair<String, (SqlDriver) -> Unit>> {
            add("empty" to { _ -> })
            for (format in 1..TranslationMemorySchema.VERSION.toInt()) {
                add("unrecorded format $format" to { driver -> writeTranslationMemory(driver, format) })
                add("recorded format $format" to { driver ->
                    writeTranslationMemory(driver, format)
                    recordVersion(driver, "translation_memory", format.toLong())
                })
            }
            // SchemaMigratorTest's partial databases: only the first two tables, columns inline.
            for (format in 1..TranslationMemorySchema.VERSION.toInt()) {
                add("partial format $format" to { driver -> writePartialTranslationMemory(driver, format) })
            }
        }
        formats.forEach { (name, fixture) ->
            assertMigratesTo(generated, TABLE_PREFIX_TM, name, fixture) { TranslationMemoryStore(it) }
        }
    }

    @Test
    fun theComparisonSeesAColumnThatIsMissing() {
        val generated = generated(TABLE_PREFIX_TM) { TranslationMemoryDatabase.Schema.create(it).value }
        val older = describe(TABLE_PREFIX_TM) { writeTranslationMemory(it, format = 3) }
        assertTrue(generated != older, "format 3 lacks tm_work.from_picked")
    }

    private fun fixtures(shipped: List<String>, component: String, version: Long): List<Pair<String, (SqlDriver) -> Unit>> =
        listOf(
            "empty" to { _ -> },
            "unrecorded format $version" to { driver -> shipped.forEach { exec(driver, it) } },
            "recorded format $version" to { driver ->
                shipped.forEach { exec(driver, it) }
                recordVersion(driver, component, version)
            },
        )

    private fun assertMigratesTo(
        generated: Schema,
        prefix: String,
        name: String,
        fixture: (SqlDriver) -> Unit,
        open: (SqlDriver) -> Unit,
    ) {
        val migrated = describe(prefix) { driver ->
            fixture(driver)
            open(driver)
        }
        assertEquals(generated, migrated, "$prefix tables migrated from $name differ from the generated ones")
    }

    private fun generated(prefix: String, create: (SqlDriver) -> Unit): Schema = describe(prefix, create).also {
        assertTrue(it.tables.isNotEmpty(), "no $prefix tables were created")
    }

    private fun describe(prefix: String, build: (SqlDriver) -> Unit): Schema =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            build(driver)
            val tables = rows(driver, "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name LIKE ? ORDER BY name", "$prefix%")
            Schema(
                tables.associate { (table, sql) ->
                    table!! to Table(
                        columns = rows(
                            driver,
                            "SELECT cid, name, type, \"notnull\", dflt_value, pk FROM pragma_table_info(?) ORDER BY cid",
                            table,
                        ),
                        indexes = rows(driver, "SELECT name, \"unique\", origin, partial FROM pragma_index_list(?) ORDER BY name", table)
                            .associate { index ->
                                index[0]!! to Index(
                                    flags = index.drop(1),
                                    columns = rows(
                                        driver,
                                        "SELECT seqno, cid, name, \"desc\", coll, key FROM pragma_index_xinfo(?) ORDER BY seqno",
                                        index[0]!!,
                                    ),
                                    condition = indexCondition(driver, index[0]!!),
                                )
                            },
                        withoutRowId = normalized(sql!!).endsWith("WITHOUT ROWID"),
                    )
                },
            )
        }

    /** The `WHERE` of a partial index, as written; null for any other. */
    private fun indexCondition(driver: SqlDriver, index: String): String? {
        val sql = rows(driver, "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?", index).singleOrNull()?.single()
            ?: return null
        return normalized(sql).substringAfter(" WHERE ", missingDelimiterValue = "").ifEmpty { null }
    }

    private fun normalized(sql: String): String = sql.replace(Regex("\\s+"), " ").trim().uppercase()

    private fun rows(driver: SqlDriver, sql: String, argument: String): List<List<String?>> =
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                val out = mutableListOf<List<String?>>()
                while (cursor.next().value) {
                    out += (0 until sql.substringBefore(" FROM ").split(",").size).map { cursor.getString(it) }
                }
                QueryResult.Value(out)
            },
            1,
        ) { bindString(0, argument) }.value

    private fun exec(driver: SqlDriver, sql: String) {
        driver.execute(null, sql, 0).value
    }

    private fun recordVersion(driver: SqlDriver, component: String, version: Long) {
        exec(driver, "CREATE TABLE IF NOT EXISTS shinsou_schema_versions(component TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL) WITHOUT ROWID")
        exec(driver, "INSERT INTO shinsou_schema_versions(component, version) VALUES ('$component', $version)")
    }

    /** The tables as the app left format [format]: the first tables, then each format's column. */
    private fun writeTranslationMemory(driver: SqlDriver, format: Int) {
        TM_V1.forEach { exec(driver, it) }
        TM_ADDED_COLUMNS.take(format - 1).forEach { (table, column) ->
            exec(driver, "ALTER TABLE $table ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun writePartialTranslationMemory(driver: SqlDriver, format: Int) {
        val exportable = if (format >= 2) ", exportable INTEGER NOT NULL DEFAULT 0" else ""
        val turnedOff = if (format >= 3) ", turned_off INTEGER NOT NULL DEFAULT 0" else ""
        val fromPicked = if (format >= 4) ", from_picked INTEGER NOT NULL DEFAULT 0" else ""
        exec(
            driver,
            "CREATE TABLE tm_segment(source_hash TEXT NOT NULL, from_lang TEXT NOT NULL, to_lang TEXT NOT NULL, " +
                "engine TEXT NOT NULL, text TEXT NOT NULL, paid INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL, " +
                "used_at INTEGER NOT NULL$exportable, PRIMARY KEY(source_hash, from_lang, to_lang, engine)) WITHOUT ROWID",
        )
        exec(
            driver,
            "CREATE TABLE tm_work(work_key TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, from_lang TEXT NOT NULL, " +
                "to_lang TEXT NOT NULL, mode TEXT NOT NULL$turnedOff$fromPicked) WITHOUT ROWID",
        )
    }

    private data class Schema(val tables: Map<String, Table>)

    private data class Table(
        val columns: List<List<String?>>,
        val indexes: Map<String, Index>,
        val withoutRowId: Boolean,
    )

    private data class Index(val flags: List<String?>, val columns: List<List<String?>>, val condition: String?)

    private companion object {
        const val TABLE_PREFIX_ANNOTATIONS = "content_annotations"
        const val TABLE_PREFIX_SYNC = "sync3_"
        const val TABLE_PREFIX_TM = "tm_"
        const val TABLE_PREFIX_HOT_STATE = "legacy_"

        val HOT_STATE_TABLES = setOf("legacy_chapter_progress", "legacy_download_queue", "legacy_history", "legacy_hot_state_meta")

        /** legacy_hot_state format 1, as it first shipped (LegacyHotState.sq of C3-2). */
        val HOT_STATE_V1 = listOf(
            "CREATE TABLE IF NOT EXISTS legacy_chapter_progress(chapter_id INTEGER NOT NULL PRIMARY KEY, " +
                "read INTEGER NOT NULL, bookmark INTEGER NOT NULL, last_page_read INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS legacy_history(history_id INTEGER NOT NULL PRIMARY KEY, ordinal INTEGER NOT NULL, " +
                "chapter_id INTEGER NOT NULL, last_read INTEGER NOT NULL, time_read INTEGER NOT NULL, " +
                "last_locator_json TEXT, last_page_count INTEGER)",
            "CREATE TABLE IF NOT EXISTS legacy_download_queue(id TEXT NOT NULL PRIMARY KEY, ordinal INTEGER NOT NULL, " +
                "manga_id INTEGER NOT NULL, chapter_id INTEGER NOT NULL, state TEXT NOT NULL, progress REAL NOT NULL, " +
                "downloaded_pages INTEGER NOT NULL, total_pages INTEGER NOT NULL, position INTEGER NOT NULL, " +
                "queued_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, destination_path TEXT, error_message TEXT, " +
                "visible INTEGER NOT NULL) WITHOUT ROWID",
            "CREATE TABLE IF NOT EXISTS legacy_hot_state_meta(k TEXT NOT NULL PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID",
        )

        /** content_annotations format 1, as SqlDriverContentAnnotationStore wrote it before C4. */
        val ANNOTATIONS_V1 = listOf(
            """
            CREATE TABLE IF NOT EXISTS content_annotations(
              annotation_id TEXT NOT NULL PRIMARY KEY,
              publication_id TEXT NOT NULL,
              acquisition_id TEXT NOT NULL,
              unit_id TEXT NOT NULL,
              content_revision INTEGER NOT NULL,
              state TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              annotation_json TEXT NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS content_annotations_scope
            ON content_annotations(publication_id, acquisition_id, unit_id, content_revision, state)
            """.trimIndent(),
        )

        /** sync_v3 format 1, as SyncV3Schema wrote it before C4. */
        val SYNC_V1 = listOf(
            "CREATE TABLE IF NOT EXISTS sync3_meta(k TEXT NOT NULL PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID",
            """
            CREATE TABLE IF NOT EXISTS sync3_record(
              kind TEXT NOT NULL, key TEXT NOT NULL,
              work_hash TEXT, unit_remote_id TEXT, work_json TEXT,
              del_value INTEGER, del_clock TEXT, del_dirty INTEGER NOT NULL DEFAULT 0,
              cleared TEXT, seq INTEGER NOT NULL DEFAULT 0,
              unapplied INTEGER NOT NULL DEFAULT 0, pending_reason TEXT,
              reject_reason TEXT, reject_at INTEGER,
              has_dirty INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(kind, key)
            ) WITHOUT ROWID
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS sync3_record_dirty ON sync3_record(has_dirty) WHERE has_dirty = 1",
            "CREATE INDEX IF NOT EXISTS sync3_record_unapplied ON sync3_record(unapplied) WHERE unapplied = 1",
            """
            CREATE TABLE IF NOT EXISTS sync3_field(
              kind TEXT NOT NULL, key TEXT NOT NULL, field TEXT NOT NULL,
              value TEXT NOT NULL, clock TEXT NOT NULL, dirty INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(kind, key, field)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS sync3_dirty(
              kind TEXT NOT NULL, ref TEXT NOT NULL, field TEXT NOT NULL, clock TEXT NOT NULL,
              aux TEXT, tomb_key TEXT, tomb_work TEXT, tomb_unit TEXT,
              PRIMARY KEY(kind, ref, field)
            ) WITHOUT ROWID
            """.trimIndent(),
            "CREATE TABLE IF NOT EXISTS sync3_category(local_id INTEGER NOT NULL PRIMARY KEY, uuid TEXT NOT NULL UNIQUE)",
            """
            CREATE TABLE IF NOT EXISTS sync3_work_identity(
              manga_id INTEGER NOT NULL PRIMARY KEY, record_key TEXT NOT NULL, work_hash TEXT
            )
            """.trimIndent(),
        )

        /** translation_memory format 1, as TranslationMemorySchema wrote it before C4 (its TABLES). */
        val TM_V1 = listOf(
            "CREATE TABLE IF NOT EXISTS tm_meta(k TEXT NOT NULL PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID",
            """
            CREATE TABLE IF NOT EXISTS tm_segment(
              source_hash TEXT NOT NULL, from_lang TEXT NOT NULL, to_lang TEXT NOT NULL, engine TEXT NOT NULL,
              text TEXT NOT NULL, paid INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL, used_at INTEGER NOT NULL,
              PRIMARY KEY(source_hash, from_lang, to_lang, engine)
            ) WITHOUT ROWID
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS tm_segment_free_age ON tm_segment(used_at) WHERE paid = 0",
            """
            CREATE TABLE IF NOT EXISTS tm_chapter(
              work_key TEXT NOT NULL, chapter_key TEXT NOT NULL, to_lang TEXT NOT NULL, provider TEXT NOT NULL,
              source_hash TEXT NOT NULL, segment_count INTEGER NOT NULL, complete INTEGER NOT NULL,
              paid INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL,
              PRIMARY KEY(work_key, chapter_key, to_lang, provider)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS tm_glossary(
              work_key TEXT NOT NULL, to_lang TEXT NOT NULL, id INTEGER NOT NULL, source TEXT NOT NULL, target TEXT NOT NULL,
              PRIMARY KEY(work_key, to_lang, id)
            ) WITHOUT ROWID
            """.trimIndent(),
            "CREATE UNIQUE INDEX IF NOT EXISTS tm_glossary_source ON tm_glossary(work_key, to_lang, source)",
            """
            CREATE TABLE IF NOT EXISTS tm_chapter_segment(
              work_key TEXT NOT NULL, chapter_key TEXT NOT NULL, chapter_to TEXT NOT NULL, provider TEXT NOT NULL,
              source_hash TEXT NOT NULL, from_lang TEXT NOT NULL, to_lang TEXT NOT NULL, engine TEXT NOT NULL,
              PRIMARY KEY(work_key, chapter_key, chapter_to, provider, source_hash, from_lang, to_lang, engine)
            ) WITHOUT ROWID
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS tm_chapter_segment_line ON tm_chapter_segment(source_hash, from_lang, to_lang, engine)",
            """
            CREATE TABLE IF NOT EXISTS tm_label(
              work_key TEXT NOT NULL, chapter_key TEXT NOT NULL, title TEXT NOT NULL, source TEXT,
              PRIMARY KEY(work_key, chapter_key)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS tm_work(
              work_key TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL,
              from_lang TEXT NOT NULL, to_lang TEXT NOT NULL, mode TEXT NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )

        /** The column formats 2, 3 and 4 each added. */
        val TM_ADDED_COLUMNS = listOf("tm_segment" to "exportable", "tm_work" to "turned_off", "tm_work" to "from_picked")
    }
}
