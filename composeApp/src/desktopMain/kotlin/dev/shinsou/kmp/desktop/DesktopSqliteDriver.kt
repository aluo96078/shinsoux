package dev.shinsou.kmp.desktop

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * File-backed SQLite driver for the desktop app.
 *
 * Like SQLDelight's `JdbcSqliteDriver` for a file URL, each thread gets its own connection, so
 * the sync engine, content commits, search indexing and downloads write to one database through
 * several connections at once. Two settings keep them from failing each other:
 *
 * - Every transaction starts with `BEGIN IMMEDIATE` instead of a deferred `BEGIN`. A deferred
 *   transaction that reads and then writes after another connection has committed fails at once
 *   with SQLITE_BUSY_SNAPSHOT, and no busy timeout helps it. Taking the write lock up front makes
 *   it wait its turn instead.
 * - The busy timeout is [BUSY_TIMEOUT_MILLIS] instead of sqlite-jdbc's 3 seconds, so waiting
 *   behind a long sync or indexing transaction doesn't end in SQLITE_BUSY.
 */
internal class DesktopSqliteDriver(
    private val url: String,
    properties: Properties = Properties(),
) : JdbcDriver() {
    private val properties = Properties().apply {
        putAll(properties)
        putIfAbsent("busy_timeout", BUSY_TIMEOUT_MILLIS.toString())
    }
    private val connections = ThreadLocal<Connection>()
    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

    override fun getConnection(): Connection =
        connections.get() ?: DriverManager.getConnection(url, properties).also(connections::set)

    override fun closeConnection(connection: Connection) {
        check(connections.get() == connection) { "Connections must be closed on the thread that opened them" }
        // Kept open for the rest of a transaction; closed after a lone statement, as SQLDelight does.
        if (transaction == null) {
            connection.close()
            connections.remove()
        }
    }

    override fun Connection.beginTransaction() {
        prepareStatement("BEGIN IMMEDIATE").use { it.execute() }
    }

    override fun Connection.endTransaction() {
        prepareStatement("COMMIT").use { it.execute() }
    }

    override fun Connection.rollbackTransaction() {
        prepareStatement("ROLLBACK").use { it.execute() }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners.getOrPut(it, ::linkedSetOf).add(listener) }
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.remove(listener) }
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val notify = linkedSetOf<Query.Listener>()
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.let(notify::addAll) }
        }
        notify.forEach(Query.Listener::queryResultsChanged)
    }

    internal companion object {
        const val BUSY_TIMEOUT_MILLIS = 10_000
    }
}
