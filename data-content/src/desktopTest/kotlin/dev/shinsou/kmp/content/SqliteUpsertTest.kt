package dev.shinsou.kmp.content

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** [SqliteUpsert]: an UPSERT that Android 10's SQLite 3.22 can run. */
class SqliteUpsertTest {
    private fun withTable(block: (JdbcSqliteDriver) -> Unit) = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
        driver.execute(null, "CREATE TABLE t(k TEXT NOT NULL, k2 INTEGER NOT NULL, v TEXT NOT NULL, n INTEGER, PRIMARY KEY(k, k2))", 0)
        block(driver)
    }

    private fun JdbcSqliteDriver.run(upsert: SqliteUpsert, vararg values: Any?) =
        upsert.run(values.toList()) { sql, parameters, bind -> execute(null, sql, parameters, bind) }

    private fun JdbcSqliteDriver.rows(): List<String> = executeQuery(
        null,
        "SELECT k, k2, v, n FROM t ORDER BY k, k2",
        { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(
                buildList { while (cursor.next().value) add("${cursor.getString(0)}/${cursor.getLong(1)}=${cursor.getString(2)}:${cursor.getLong(3)}") },
            )
        },
        0,
    ).value

    @Test
    fun insertsANewRowAndUpdatesTheRowWithItsKey() = withTable { driver ->
        val upsert = SqliteUpsert("t", listOf("k", "k2", "v", "n"), keyCount = 2)
        driver.run(upsert, "a", 1L, "first", 1L)
        driver.run(upsert, "a", 2L, "other", 2L)
        driver.run(upsert, "a", 1L, "second", null)
        assertEquals(listOf("a/1=second:null", "a/2=other:2"), driver.rows())
    }

    @Test
    fun anInsertIfAbsentKeepsTheExistingRow() = withTable { driver ->
        val insert = SqliteUpsert("t", listOf("k", "k2", "v", "n"), keyCount = 2, updated = emptyList())
        driver.run(insert, "a", 1L, "first", 1L)
        driver.run(insert, "a", 1L, "second", 2L)
        assertEquals(listOf("a/1=first:1"), driver.rows())
    }

    @Test
    fun aRowThatBreaksAnotherConstraintStillFails() = withTable { driver ->
        val upsert = SqliteUpsert("t", listOf("k", "k2", "v", "n"), keyCount = 2)
        assertFails { driver.run(upsert, "a", 1L, null, 1L) }
        val insert = SqliteUpsert("t", listOf("k", "k2", "v", "n"), keyCount = 2, updated = emptyList())
        assertFails { driver.run(insert, "b", 1L, null, 1L) }
    }

    @Test
    fun theStatementsAvoidTheUpsertClause() {
        val upsert = SqliteUpsert("t", listOf("k", "v"), keyCount = 1)
        assertEquals("INSERT INTO t(k, v) SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM t WHERE k = ?)", upsert.insert)
        assertEquals("UPDATE t SET v = ? WHERE k = ?", upsert.update)
    }

    /**
     * No product SQL uses `ON CONFLICT`: Android 10's SQLite (3.22) rejects the UPSERT clause, and
     * the other `ON CONFLICT` forms are best avoided with it. Use [SqliteUpsert] or `INSERT OR ...`.
     */
    @Test
    fun noProductSqlUsesOnConflict() {
        val root = File("..")
        val offenders = root.listFiles().orEmpty().filter { File(it, "src").isDirectory }.flatMap { module ->
            File(module, "src").walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "sq") }
                .filter { file -> file.relativeTo(module).invariantSeparatorsPath.let { "Main/" in it || it.startsWith("src/main/") } }
                .filter { it.name != "SqliteUpsert.kt" }
                .filter { file ->
                    file.readLines().any { line ->
                        val code = line.substringBefore("//").trim()
                        !code.startsWith("*") && Regex("""ON\s+CONFLICT\b""", RegexOption.IGNORE_CASE).containsMatchIn(code)
                    }
                }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        }
        assertTrue(offenders.isEmpty(), "SQL with ON CONFLICT (unsupported by Android 10's SQLite 3.22): $offenders")
    }
}
