package dev.shinsou.kmp.content.sql

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.content.ContentFoundationRuntime

/**
 * Settings of the one content connection (R3-4, docs/architecture/0006). They belong to the
 * connection, not to any store, so the owner of the driver ([ContentFoundationRuntime]) applies
 * them before a store opens its tables.
 */
internal object ContentConnection {
    fun configure(driver: SqlDriver) {
        // WAL is a database property on SQLite.  Do not require it here: Android/iOS may choose
        // a platform-specific journal mode, while the transaction semantics remain identical.
        // journal_mode is a value-returning PRAGMA. NativeSqliteDriver rejects it when it is
        // sent through execute (which is reserved for non-query statements), while the JDBC
        // driver happens to tolerate that misuse, so the result row is read through executeQuery.
        driver.queryUncached(
            sql = "PRAGMA journal_mode = WAL",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            parameters = 0,
        ).value
        driver.executeUncached("PRAGMA synchronous = FULL", 0).value
        // Publication rows cascade to their acquisitions and units.
        driver.executeUncached("PRAGMA foreign_keys = ON", 0).value
    }
}
