package dev.shinsou.kmp.content

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

/** R3-4 (docs/architecture/0006): the runtime, which owns the driver, sets up the connection. */
class ContentConnectionTest {
    @Test
    fun theRuntimeTurnsOnForeignKeysAndFullSync() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            ContentFoundationRuntime(driver)
            assertEquals(1L, pragma(driver, "foreign_keys"))
            // FULL is 2.
            assertEquals(2L, pragma(driver, "synchronous"))
        }
    }

    private fun pragma(driver: SqlDriver, name: String): Long? = driver.executeQuery(
        null,
        "PRAGMA $name",
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
        0,
    ).value
}
