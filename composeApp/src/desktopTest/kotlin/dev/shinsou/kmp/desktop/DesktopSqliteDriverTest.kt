package dev.shinsou.kmp.desktop

import app.cash.sqldelight.TransacterImpl
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DesktopSqliteDriverTest {
    @Test
    fun aFailedTransactionRollsBackAndLaterStatementsStillCommit() {
        val directory = Files.createTempDirectory("shinsou-sqlite-driver")
        val driver = DesktopSqliteDriver("jdbc:sqlite:${directory.resolve("test.db")}")
        try {
            driver.execute(null, "CREATE TABLE item (id INTEGER PRIMARY KEY)", 0)
            val transacter = object : TransacterImpl(driver) {}
            assertFailsWith<IllegalStateException> {
                transacter.transaction {
                    driver.execute(null, "INSERT INTO item (id) VALUES (1)", 0)
                    // A nested transaction joins the outer one.
                    transacter.transaction { driver.execute(null, "INSERT INTO item (id) VALUES (2)", 0) }
                    error("abort")
                }
            }
            transacter.transaction { driver.execute(null, "INSERT INTO item (id) VALUES (3)", 0) }
            driver.execute(null, "INSERT INTO item (id) VALUES (4)", 0)

            val ids = driver.executeQuery(
                null,
                "SELECT id FROM item ORDER BY id",
                { cursor ->
                    val values = mutableListOf<Long>()
                    while (cursor.next().value) values += cursor.getLong(0)!!
                    app.cash.sqldelight.db.QueryResult.Value(values)
                },
                0,
            ).value
            assertEquals(listOf(3L, 4L), ids)
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aWriterWaitsForAnOpenTransactionInsteadOfBreakingIt() {
        val directory = Files.createTempDirectory("shinsou-sqlite-driver")
        val driver = DesktopSqliteDriver("jdbc:sqlite:${directory.resolve("test.db")}")
        try {
            driver.executeQuery(null, "PRAGMA journal_mode = WAL", { it.next() }, 0)
            driver.execute(null, "CREATE TABLE item (id INTEGER PRIMARY KEY)", 0)
            val transacter = object : TransacterImpl(driver) {}

            val inTransaction = CountDownLatch(1)
            val otherDone = CountDownLatch(1)
            val failures = AtomicReference<Throwable?>()
            val transaction = thread {
                runCatching {
                    transacter.transaction {
                        // Read first, then write: the shape that SQLITE_BUSY_SNAPSHOT breaks.
                        driver.executeQuery(null, "SELECT COUNT(*) FROM item", { it.next() }, 0)
                        inTransaction.countDown()
                        // The other connection cannot commit while this transaction holds the lock.
                        otherDone.await(500, TimeUnit.MILLISECONDS)
                        driver.execute(null, "INSERT INTO item (id) VALUES (1)", 0)
                    }
                }.onFailure(failures::set)
            }
            val other = thread {
                inTransaction.await()
                runCatching { driver.execute(null, "INSERT INTO item (id) VALUES (2)", 0) }
                    .onFailure(failures::set)
                otherDone.countDown()
            }
            transaction.join()
            other.join()

            assertNull(failures.get())
            val count = driver.executeQuery(
                null,
                "SELECT COUNT(*) FROM item",
                { cursor -> cursor.next(); app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0)) },
                0,
            ).value
            assertEquals(2L, count)
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }
}
