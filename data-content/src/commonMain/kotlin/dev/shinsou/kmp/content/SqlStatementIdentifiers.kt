package dev.shinsou.kmp.content

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock

/**
 * Hands out the `identifier` SQLDelight drivers use as the key of their prepared-statement cache.
 * A null identifier disables that cache, so every call would compile its statement again.
 *
 * The key must never be shared by two different SQL texts, or the driver would run the wrong
 * statement. A hash cannot promise that, so each distinct text is registered once and numbered in
 * order of first use, from [FIRST_IDENTIFIER]. The numbering is only meaningful inside one process,
 * which is all a driver's in-memory cache needs.
 *
 * The same driver also runs SQLDelight's generated queries (C4, docs/architecture/0007), whose
 * identifiers are compile-time hashes anywhere in the `Int` range: the numbers start far from zero,
 * and the build's `checkSqlStatementIdentifiers` (build-logic SqlDelightPlugin) fails when a
 * generated identifier falls in `[FIRST_IDENTIFIER, FIRST_IDENTIFIER + DEFAULT_MAX_ENTRIES)` or two
 * generated ones are equal. It reads both constants from this file.
 *
 * Product code never hands a driver a literal null identifier: [executeStatement] and
 * [queryStatement] take the identifier from this registry, and [executeUncached] / [queryUncached]
 * are the one named exemption for DDL, PRAGMA and statements whose text is unbounded. The build's
 * `checkSqlStatementIdentifiers` fails on any `execute(null, ...)`, `executeQuery(null, ...)` or
 * `identifier = null` in a module's product sources, so a new null cannot slip back in.
 *
 * Statements whose text is built from values (a variable `IN (?, ?, ...)` list, or an embedded
 * literal) could register without bound, so the registry stops at [maxEntries]; later new texts get
 * null and run uncached, exactly as before.
 */
internal class SqlStatementIdentifiers(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val lock = SynchronousLock()
    private val registry = HashMap<String, Int>()

    init {
        // More would run past the range the build checks generated identifiers against.
        require(maxEntries in 0..DEFAULT_MAX_ENTRIES) { "At most $DEFAULT_MAX_ENTRIES statements can be numbered" }
    }

    /** The stable identifier of [sql], or null when the registry is full and [sql] is not in it. */
    fun identifierFor(sql: String): Int? = lock.withLock {
        registry[sql] ?: if (registry.size < maxEntries) (FIRST_IDENTIFIER + registry.size).also { registry[sql] = it } else null
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 512

        /** The first number handed out; [DEFAULT_MAX_ENTRIES] numbers from it are the registry's. */
        const val FIRST_IDENTIFIER = 2_000_000_000

        /** The process-wide registry; statement caches live on drivers, but texts and numbers are universal. */
        val shared = SqlStatementIdentifiers()
    }
}

/**
 * The cache identifier for a fixed [sql] text, or null to run it uncached. Use it for plain
 * SELECT/INSERT/UPDATE/DELETE. DDL, PRAGMA and ATTACH stay uncached: they change the schema or
 * connection state, run once, and gain nothing from a cached statement.
 */
internal fun statementIdentifier(sql: String): Int? = SqlStatementIdentifiers.shared.identifierFor(sql)

/**
 * `driver.execute` with the registry's identifier for [sql], so the driver prepares the statement
 * once. For fixed texts and texts with a small, bounded set of variants (a `?` list of one of a few
 * lengths); [executeUncached] otherwise.
 */
public fun SqlDriver.executeStatement(
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)? = null,
): QueryResult<Long> = execute(statementIdentifier(sql), sql, parameters, binders)

/** `driver.executeQuery` with the registry's identifier for [sql]; see [executeStatement]. */
public fun <R> SqlDriver.queryStatement(
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)? = null,
): QueryResult<R> = executeQuery(statementIdentifier(sql), sql, mapper, parameters, binders)

/**
 * Runs [sql] without a statement-cache identifier. The explicit exemption of
 * `checkSqlStatementIdentifiers`: DDL, PRAGMA and ATTACH (they change the schema or connection
 * state, run once and gain nothing from a cached statement), and text that varies without a bound.
 */
public fun SqlDriver.executeUncached(
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)? = null,
): QueryResult<Long> = execute(null, sql, parameters, binders)

/** [executeUncached] for a query (`PRAGMA wal_checkpoint`, `PRAGMA journal_mode`, ...). */
public fun <R> SqlDriver.queryUncached(
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)? = null,
): QueryResult<R> = executeQuery(null, sql, mapper, parameters, binders)
