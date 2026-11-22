package dev.shinsou.kmp.content.sql

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.content.table.escapeLike
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R3-2/R3-3 (docs/architecture/0006): values are bound, and rows are read by column name. */
class ContentRowTest {
    @Test
    fun columnsAreNamedByAliasOrByNameWithoutTheTable() {
        assertEquals(
            mapOf("a" to 0, "b" to 1, "c" to 2),
            ContentRow.columns("a, t.b, COUNT(*) AS c"),
        )
        assertFailsWith<IllegalArgumentException> { ContentRow.columns("a, t.a") }
    }

    @Test
    fun plainColumnsAndAliasesKeepTheirPositions() {
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2), ContentRow.columns("a,b ,  c"))
        assertEquals(mapOf("x" to 0, "y" to 1), ContentRow.columns("t.a AS x, b as y"))
        assertEquals(mapOf("n" to 0), ContentRow.columns("COUNT(*) AS n"))
    }

    @Test
    fun commasInsideCallsAndLiteralsDoNotSplitColumns() {
        assertEquals(
            mapOf("s" to 0, "total" to 1, "lit" to 2, "k" to 3),
            ContentRow.columns("substr(a, 1, 2) AS s, COALESCE(SUM(bytes), 0) AS total, 'x, y' AS lit, k"),
        )
        // The last alias names the item, so a value ending in a cast is still split correctly.
        assertEquals(mapOf("v" to 0), ContentRow.columns("CAST(a AS TEXT) AS v"))
    }

    @Test
    fun anExpressionWithoutAnAliasIsRejected() {
        listOf("COUNT(*)", "a + 1", "MAX(a)", "a, lower(b)", "'text'").forEach { projection ->
            val error = assertFailsWith<IllegalArgumentException>(projection) { ContentRow.columns(projection) }
            assertTrue("add an AS alias" in error.message.orEmpty(), projection)
        }
        assertFailsWith<IllegalArgumentException> { ContentRow.columns("a, , b") }
    }

    @Test
    fun aSelectIsReadByItsOwnProjection() {
        assertEquals(
            mapOf("kind" to 0, "ref" to 1),
            ContentRow.selectColumns("SELECT DISTINCT kind, ref FROM sync3_dirty ORDER BY kind, ref LIMIT ?"),
        )
        // A subquery in FROM is not part of the projection.
        assertEquals(
            mapOf("row_count" to 0),
            ContentRow.selectColumns(
                "SELECT COUNT(*) AS row_count FROM (SELECT DISTINCT kind, ref FROM sync3_dirty WHERE 1 = 1)",
            ),
        )
        assertEquals(
            mapOf("work_hash" to 0, "work_json" to 1),
            ContentRow.selectColumns("select work_hash, MAX(work_json) AS work_json\nfrom sync3_record GROUP BY work_hash"),
        )
        assertEquals(
            mapOf("work_key" to 0, "work_title" to 1, "source" to 2),
            ContentRow.selectColumns(
                "SELECT c.work_key, w.title AS work_title, w.source FROM tm_chapter c " +
                    "LEFT JOIN tm_label w ON w.work_key = c.work_key",
            ),
        )
        // A column named like the keyword is not the keyword.
        assertEquals(mapOf("from_lang" to 0), ContentRow.selectColumns("SELECT from_lang FROM tm_segment"))
        assertFailsWith<IllegalArgumentException> { ContentRow.selectColumns("DELETE FROM t") }
        assertFailsWith<IllegalArgumentException> { ContentRow.selectColumns("SELECT a + 1 FROM t") }
    }

    @Test
    fun aRowReadsByNameAndRefusesUnselectedOrNullColumns() {
        val row = ContentRow(ContentRow.columns("a, b"), listOf("1", null))
        assertEquals("1", row.string("a"))
        assertEquals(null, row.stringOrNull("b"))
        assertFailsWith<IllegalArgumentException> { row.string("b") }
        val unknown = assertFailsWith<IllegalArgumentException> { row.stringOrNull("c") }
        assertEquals("Column c is not selected", unknown.message)
    }

    @Test
    fun aCopiedRowReadsIntegerColumnsAsIntegersAndKeepsNulls() = withTable { driver ->
        val rows = driver.executeQuery(
            null,
            "SELECT k, n, missing_n, missing_s FROM t ORDER BY k",
            { cursor ->
                val columns = ContentRow.selectColumns("SELECT k, n, missing_n, missing_s FROM t")
                val rows = mutableListOf<ContentRow>()
                while (cursor.next().value) rows += ContentRow.read(cursor, columns, setOf("n", "missing_n"))
                QueryResult.Value(rows)
            },
            0,
        ).value
        val row = rows.single()
        assertEquals("key", row.string("k"))
        assertEquals(42L, row.long("n"))
        assertNull(row.longOrNull("missing_n"))
        assertNull(row.stringOrNull("missing_s"))
        assertFalse(row.flag("missing_n"))
        assertFailsWith<IllegalArgumentException> { row.long("missing_n") }
        assertFailsWith<IllegalArgumentException> { row.string("missing_s") }
        // An integer column is not quietly turned into text, nor text into a number.
        assertFailsWith<IllegalArgumentException> { row.string("n") }
        assertFailsWith<IllegalArgumentException> { row.long("k") }
    }

    @Test
    fun aCursorRowUsesTheGetterOfEachType() = withTable { driver ->
        val sql = "SELECT k, n, f, d, b, missing_n, missing_s FROM t"
        val columns = ContentRow.selectColumns(sql)
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                check(cursor.next().value)
                val row = ContentCursorRow(cursor, "t", columns)
                assertEquals("key", row.string("k"))
                assertEquals(42L, row.long("n"))
                assertTrue(row.flag("f"))
                assertEquals(1.5, row.doubleOrNull("d"))
                assertContentEquals(byteArrayOf(1, 2, 3), row.bytesOrNull("b"))
                assertNull(row.longOrNull("missing_n"))
                assertFalse(row.flag("missing_n"))
                assertNull(row.stringOrNull("missing_s"))
                assertEquals("t.missing_s is null", assertFailsWith<IllegalArgumentException> { row.string("missing_s") }.message)
                assertEquals("t.missing_n is null", assertFailsWith<IllegalArgumentException> { row.long("missing_n") }.message)
                assertEquals("t.zzz is not selected", assertFailsWith<IllegalArgumentException> { row.string("zzz") }.message)
                QueryResult.Unit
            },
            0,
        ).value
    }

    @Test
    fun anEscapedPrefixMatchesOnlyItsOwnKeys() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "CREATE TABLE t(k TEXT NOT NULL)", 0).value
            listOf("a_b/1", "axb/1", "a%b/1", "a\\b/1").forEach { key ->
                driver.execute(null, "INSERT INTO t(k) VALUES (?)", 1) { bindString(0, key) }.value
            }
            fun matching(prefix: String): List<String> = driver.executeQuery(
                null,
                "SELECT k FROM t WHERE k LIKE ? ESCAPE '\\' ORDER BY k",
                { cursor ->
                    val keys = mutableListOf<String>()
                    while (cursor.next().value) keys += cursor.getString(0)!!
                    QueryResult.Value(keys)
                },
                1,
            ) { bindString(0, escapeLike(prefix) + "/%") }.value
            assertEquals(listOf("a_b/1"), matching("a_b"))
            assertEquals(listOf("a%b/1"), matching("a%b"))
            assertEquals(listOf("a\\b/1"), matching("a\\b"))
        }
    }

    private fun withTable(block: (SqlDriver) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(
                null,
                "CREATE TABLE t(k TEXT NOT NULL, n INTEGER, f INTEGER, d REAL, b BLOB, missing_n INTEGER, missing_s TEXT)",
                0,
            ).value
            driver.execute(null, "INSERT INTO t(k, n, f, d, b) VALUES ('key', 42, 1, 1.5, X'010203')", 0).value
            block(driver)
        }
    }
}
