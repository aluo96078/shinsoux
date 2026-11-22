package dev.shinsou.kmp.content

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.annotation.ContentAnnotationSchema
import dev.shinsou.kmp.app.contentSchemaComponents
import dev.shinsou.kmp.content.hotstate.LegacyHotStateSchema
import dev.shinsou.kmp.search.LocalFullTextIndexSchema
import dev.shinsou.kmp.sync.v3.SyncV3Schema
import dev.shinsou.kmp.translation.TranslationMemorySchema
import dev.shinsou.kmp.translation.TranslationMemoryStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R3-1 (docs/architecture/0006): every format the app shipped opens with its data intact. */
class SchemaMigratorTest {
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @AfterTest
    fun close() = driver.close()

    @Test
    fun aFreshDatabaseRecordsEveryVersion() {
        createAll()
        assertEquals(5, singleton("content_transaction_schema"))
        assertEquals(2, shared("content_blobs"))
        assertEquals(1, shared("content_annotations"))
        assertEquals(1, shared("sync_v3"))
        assertEquals(1, shared("legacy_hot_state"))
        assertEquals(TranslationMemorySchema.VERSION, shared("translation_memory"))
        assertEquals(2, singleton("content_search_v2_schema"))
        assertTrue(columnExists(driver, "tm_work", "from_picked"))
        assertNull(SchemaMigrator.tooNew(driver, contentSchemaComponents()))
    }

    @Test
    fun openingTwiceChangesNothing() {
        createAll()
        exec("INSERT INTO sync3_meta(k, v) VALUES ('cursor', '42')")
        createAll()
        assertEquals("42", text("SELECT v FROM sync3_meta WHERE k = 'cursor'"))
    }

    @Test
    fun translationMemoryOfEveryUnrecordedFormatUpgradesWithItsRows() {
        for (format in 1..4) {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { old ->
                writeTranslationMemoryFormat(old, format)
                TranslationMemoryStore(old)
                assertEquals(TranslationMemorySchema.VERSION, shared("translation_memory", old), "format $format")
                assertEquals("譯文", text("SELECT text FROM tm_segment WHERE source_hash = 'h'", old), "format $format")
                listOf("exportable" to "tm_segment", "turned_off" to "tm_work", "from_picked" to "tm_work").forEach {
                    assertTrue(columnExists(old, it.second, it.first), "format $format lacks ${it.first}")
                }
                // Format 4 knew which source languages were picked; older ones are inferred.
                val picked = text("SELECT from_picked FROM tm_work WHERE work_key = 'w'", old)
                assertEquals(if (format < 4) "1" else "0", picked, "format $format")
            }
        }
    }

    @Test
    fun aNewerFormatIsRefusedBeforeAnythingChanges() {
        createAll()
        exec("UPDATE content_transaction_schema SET format_version = 9 WHERE singleton_id = 1")
        val found = SchemaMigrator.tooNew(driver, contentSchemaComponents())
        assertEquals("content", found?.component)
        assertEquals(9, found?.found)
        val thrown = assertFailsWith<SchemaTooNewException> { ContentTransactionSchema.create(driver) }
        assertEquals(5, thrown.supported)
        assertEquals(9, singleton("content_transaction_schema"))
    }

    @Test
    fun aNewerSharedVersionIsRefusedToo() {
        createAll()
        exec("UPDATE shinsou_schema_versions SET version = 7 WHERE component = 'translation_memory'")
        assertEquals("translation_memory", SchemaMigrator.tooNew(driver, contentSchemaComponents())?.component)
        assertFailsWith<SchemaTooNewException> { TranslationMemoryStore(driver) }
    }

    @Test
    fun theSearchIndexOfAnotherFormatIsRebuilt() {
        createAll()
        exec(
            "INSERT INTO content_search_v2_contexts(context_key, publication_id, acquisition_id, unit_id, " +
                "content_revision, resource_id, canonical_length_utf16, context_json) VALUES (1, 'p', 'a', 'u', 1, 'r', 0, '{}')",
        )
        exec("UPDATE content_search_v2_schema SET format_version = 3 WHERE singleton_id = 1")
        assertNull(SchemaMigrator.tooNew(driver, contentSchemaComponents()))
        LocalFullTextIndexSchema.create(driver)
        assertEquals(2, singleton("content_search_v2_schema"))
        assertEquals("0", text("SELECT COUNT(*) FROM content_search_v2_contexts"))
    }

    @Test
    fun theSearchIndexOfAnOlderFormatIsRebuiltToo() {
        createAll()
        exec(
            "INSERT INTO content_search_v2_contexts(context_key, publication_id, acquisition_id, unit_id, " +
                "content_revision, resource_id, canonical_length_utf16, context_json) VALUES (1, 'p', 'a', 'u', 1, 'r', 0, '{}')",
        )
        exec("UPDATE content_search_v2_schema SET format_version = 1 WHERE singleton_id = 1")
        LocalFullTextIndexSchema.create(driver)
        assertEquals(2, singleton("content_search_v2_schema"))
        // not the old tables under the new version: what was indexed in the old format is gone
        assertEquals("0", text("SELECT COUNT(*) FROM content_search_v2_contexts"))
    }

    @Test
    fun aFailedStepLeavesTheOldFormat() {
        val component = SchemaComponent(
            name = "broken",
            version = 2,
            create = { it.execute(null, "CREATE TABLE broken(a INTEGER)", 0).value },
            steps = mapOf(2L to { d ->
                d.execute(null, "ALTER TABLE broken ADD COLUMN b INTEGER", 0).value
                error("step failed")
            }),
            unrecordedVersion = { null },
        )
        SchemaMigrator.migrate(driver, SchemaComponent("broken", 1, component.create, unrecordedVersion = { null }))
        assertFailsWith<IllegalStateException> { SchemaMigrator.migrate(driver, component) }
        assertEquals(1, shared("broken"))
        assertFalse(columnExists(driver, "broken", "b"))
    }

    private fun createAll() {
        ContentTransactionSchema.create(driver)
        ContentAnnotationSchema.create(driver)
        SyncV3Schema.create(driver)
        LegacyHotStateSchema.create(driver)
        LocalFullTextIndexSchema.create(driver)
        TranslationMemoryStore(driver)
    }

    /** The translation memory tables as each format left them, before versions were recorded. */
    private fun writeTranslationMemoryFormat(old: SqlDriver, format: Int) {
        val exportable = if (format >= 2) ", exportable INTEGER NOT NULL DEFAULT 0" else ""
        val turnedOff = if (format >= 3) ", turned_off INTEGER NOT NULL DEFAULT 0" else ""
        val fromPicked = if (format >= 4) ", from_picked INTEGER NOT NULL DEFAULT 0" else ""
        exec(
            "CREATE TABLE tm_segment(source_hash TEXT NOT NULL, from_lang TEXT NOT NULL, to_lang TEXT NOT NULL, " +
                "engine TEXT NOT NULL, text TEXT NOT NULL, paid INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL, " +
                "used_at INTEGER NOT NULL$exportable, PRIMARY KEY(source_hash, from_lang, to_lang, engine)) WITHOUT ROWID",
            old,
        )
        exec(
            "CREATE TABLE tm_work(work_key TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, from_lang TEXT NOT NULL, " +
                "to_lang TEXT NOT NULL, mode TEXT NOT NULL$turnedOff$fromPicked) WITHOUT ROWID",
            old,
        )
        exec("INSERT INTO tm_segment(source_hash, from_lang, to_lang, engine, text, bytes, used_at) VALUES ('h', 'ja', 'zh-TW', 'e', '譯文', 6, 1)", old)
        exec("INSERT INTO tm_work(work_key, enabled, from_lang, to_lang, mode) VALUES ('w', 1, 'ko', 'zh-TW', 'auto')", old)
    }

    private fun exec(sql: String, on: SqlDriver = driver) {
        on.execute(null, sql, 0).value
    }

    private fun text(sql: String, on: SqlDriver = driver): String? =
        on.executeQuery(null, sql, { QueryResult.Value(if (it.next().value) it.getString(0) else null) }, 0).value

    private fun singleton(table: String): Long? =
        text("SELECT format_version FROM $table WHERE singleton_id = 1")?.toLong()

    private fun shared(component: String, on: SqlDriver = driver): Long? =
        text("SELECT version FROM shinsou_schema_versions WHERE component = '$component'", on)?.toLong()
}
