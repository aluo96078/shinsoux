package dev.shinsou.kmp.content.blob

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.content.schema.columnExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R3-1 (docs/architecture/0006): the blob table of format one opens with its rows intact (from SchemaMigratorTest). */
class ContentBlobSchemaMigrationTest {
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @AfterTest
    fun close() = driver.close()

    @Test
    fun blobsOfFormatOneGainTheOpaqueName() {
        exec(
            "CREATE TABLE content_blobs(blob_id TEXT NOT NULL PRIMARY KEY, reference_json TEXT NOT NULL, " +
                "payload BLOB NOT NULL, incarnation INTEGER NOT NULL, generation INTEGER NOT NULL, " +
                "published_at_epoch_millis INTEGER NOT NULL, lifecycle_state TEXT NOT NULL, " +
                "discovered_at_epoch_millis INTEGER) WITHOUT ROWID",
        )
        exec("INSERT INTO content_blobs VALUES ('b', '{}', x'00', 1, 0, 1, 'PUBLISHED', NULL)")
        assertEquals(1, ContentBlobPersistenceSchema.component.unrecordedVersion(driver))
        ContentBlobPersistenceSchema.create(driver)
        assertTrue(columnExists(driver, "content_blobs", "opaque_name"))
        assertEquals(2, shared("content_blobs"))
        assertEquals("{}", text("SELECT reference_json FROM content_blobs WHERE blob_id = 'b'"))
    }

    private fun exec(sql: String) {
        driver.execute(null, sql, 0).value
    }

    private fun text(sql: String): String? =
        driver.executeQuery(null, sql, { QueryResult.Value(if (it.next().value) it.getString(0) else null) }, 0).value

    private fun shared(component: String): Long? =
        text("SELECT version FROM shinsou_schema_versions WHERE component = '$component'")?.toLong()
}
