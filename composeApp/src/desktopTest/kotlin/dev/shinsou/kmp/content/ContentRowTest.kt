package dev.shinsou.kmp.content

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun aRowReadsByNameAndRefusesUnselectedOrNullColumns() {
        val row = ContentRow(ContentRow.columns("a, b"), listOf("1", null))
        assertEquals("1", row.string("a"))
        assertEquals(null, row.stringOrNull("b"))
        assertFailsWith<IllegalArgumentException> { row.string("b") }
        assertFailsWith<IllegalArgumentException> { row.stringOrNull("c") }
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
}
