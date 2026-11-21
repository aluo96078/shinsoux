package dev.shinsou.kmp.content

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock

/**
 * Hands out the `identifier` SQLDelight drivers use as the key of their prepared-statement cache.
 * A null identifier disables that cache, so every call would compile its statement again.
 *
 * The key must never be shared by two different SQL texts, or the driver would run the wrong
 * statement. A hash cannot promise that, so each distinct text is registered once and numbered in
 * order of first use. The numbering is only meaningful inside one process, which is all a driver's
 * in-memory cache needs.
 *
 * Statements whose text is built from values (a variable `IN (?, ?, ...)` list, or an embedded
 * literal) could register without bound, so the registry stops at [maxEntries]; later new texts get
 * null and run uncached, exactly as before.
 */
internal class SqlStatementIdentifiers(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val lock = SynchronousLock()
    private val registry = HashMap<String, Int>()

    /** The stable identifier of [sql], or null when the registry is full and [sql] is not in it. */
    fun identifierFor(sql: String): Int? = lock.withLock {
        registry[sql] ?: if (registry.size < maxEntries) registry.size.also { registry[sql] = it } else null
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 512

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
