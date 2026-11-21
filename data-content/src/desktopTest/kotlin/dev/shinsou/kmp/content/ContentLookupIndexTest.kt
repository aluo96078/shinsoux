package dev.shinsou.kmp.content

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reading one publication's rows uses an index instead of scanning every title's rows. */
class ContentLookupIndexTest {
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @AfterTest
    fun close() = driver.close()

    @Test
    fun aPublicationsRowsAreFoundByIndex() {
        ContentTransactionSchema.create(driver)
        listOf(TABLE_ACQUISITIONS, TABLE_ACQUISITION_RIGHTS, TABLE_UNITS, TABLE_RIGHTS_GRANTS).forEach { table ->
            val plan = plan("SELECT * FROM $table WHERE publication_id = 'p'")
            assertTrue("${table}_publication" in plan, plan)
            assertFalse(plan.startsWith("SCAN"), plan)
        }
    }

    @Test
    fun aDatabaseOpenedAgainKeepsItsIndexes() {
        ContentTransactionSchema.create(driver)
        driver.execute(null, "DROP INDEX ${TABLE_UNITS}_publication", 0).value
        // An existing database at the current version gains the index on its next open.
        ContentTransactionSchema.create(driver)
        assertTrue("${TABLE_UNITS}_publication" in plan("SELECT * FROM $TABLE_UNITS WHERE publication_id = 'p'"))
    }

    private fun plan(sql: String): String = driver.executeQuery(
        null,
        "EXPLAIN QUERY PLAN $sql",
        { cursor ->
            val details = mutableListOf<String>()
            while (cursor.next().value) details += cursor.getString(3).orEmpty()
            QueryResult.Value(details.joinToString("; "))
        },
        0,
    ).value
}
