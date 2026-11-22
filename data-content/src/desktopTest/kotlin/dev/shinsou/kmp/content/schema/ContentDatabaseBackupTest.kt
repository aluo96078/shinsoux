package dev.shinsou.kmp.content.schema

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.content.sql.ContentConnection
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** R3-1 (docs/architecture/0006): the database file is copied before a table group is upgraded. */
class ContentDatabaseBackupTest {
    private val directory = Files.createTempDirectory("content-backup")
    private val database = directory.resolve("content.db")

    private fun driver() = JdbcSqliteDriver("jdbc:sqlite:$database").also(ContentConnection::configure)

    private fun notes(version: Long) = SchemaComponent(
        name = "notes",
        version = version,
        create = { it.execute(null, "CREATE TABLE IF NOT EXISTS notes(text TEXT NOT NULL, pinned INTEGER NOT NULL DEFAULT 0)", 0) },
        steps = if (version >= 2) mapOf(2L to { it.execute(null, "ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0", 0) }) else emptyMap(),
        unrecordedVersion = { if (tableExists(it, "notes")) 1 else null },
    )

    private fun count(driver: SqlDriver): Long =
        driver.executeQuery(null, "SELECT COUNT(*) FROM notes", { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0)!!) }, 0).value

    @Test
    fun anUpgradeIsPrecededByACopyOfTheDataAsItWas() {
        driver().use { driver ->
            driver.execute(null, "CREATE TABLE notes(text TEXT NOT NULL)", 0)
            SchemaVersionStore.Shared("notes").write(driver, 1)
            driver.execute(null, "INSERT INTO notes(text) VALUES ('kept')", 0)

            val copy = backUpBeforeUpgrade(driver, database.toString(), listOf(notes(2)))
            assertEquals("$database$BEFORE_UPGRADE_SUFFIX", copy)
            SchemaMigrator.migrate(driver, notes(2))
        }
        JdbcSqliteDriver("jdbc:sqlite:$database$BEFORE_UPGRADE_SUFFIX").use { copy ->
            assertEquals(1, count(copy))
            assertFalse(columnExists(copy, "notes", "pinned"), "the copy is from before the upgrade")
        }
    }

    @Test
    fun tablesFromBeforeVersionsWereRecordedAreCopiedToo() {
        driver().use { driver ->
            driver.execute(null, "CREATE TABLE notes(text TEXT NOT NULL)", 0) // no version row at all
            assertEquals("$database$BEFORE_UPGRADE_SUFFIX", backUpBeforeUpgrade(driver, database.toString(), listOf(notes(2))))
            assertFalse(directory.resolve("content.db$BEFORE_UPGRADE_SUFFIX.partial").exists(), "nothing half-written is left")
        }
    }

    @Test
    fun aDatabaseStillInTheFormatsItWasCopiedInIsNotCopiedAgain() {
        val copy = directory.resolve("content.db$BEFORE_UPGRADE_SUFFIX")
        driver().use { driver ->
            driver.execute(null, "CREATE TABLE notes(text TEXT NOT NULL)", 0)
            backUpBeforeUpgrade(driver, database.toString(), listOf(notes(2)))
            val first = Files.getLastModifiedTime(copy)
            driver.execute(null, "INSERT INTO notes(text) VALUES ('written since')", 0)
            // a worker that opened another store: this one is still to be upgraded
            assertEquals(copy.toString(), backUpBeforeUpgrade(driver, database.toString(), listOf(notes(2))))
            assertEquals(first, Files.getLastModifiedTime(copy), "the first copy is the one from before anything changed")
        }
        JdbcSqliteDriver("jdbc:sqlite:$copy").use { assertEquals(0, count(it)) }
    }

    @Test
    fun anotherDatabaseInTheSameFormatsIsCopiedAfresh() {
        val copy = directory.resolve("content.db$BEFORE_UPGRADE_SUFFIX")
        driver().use { driver ->
            driver.execute(null, "CREATE TABLE notes(text TEXT NOT NULL)", 0)
            backUpBeforeUpgrade(driver, database.toString(), listOf(notes(2)))
        }
        // an older database put back by hand: the same formats, not the one that was copied
        listOf("content.db", "content.db-wal", "content.db-shm").forEach { Files.deleteIfExists(directory.resolve(it)) }
        driver().use { driver ->
            driver.execute(null, "CREATE TABLE notes(text TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO notes(text) VALUES ('put back')", 0)
            assertEquals(copy.toString(), backUpBeforeUpgrade(driver, database.toString(), listOf(notes(2))))
        }
        JdbcSqliteDriver("jdbc:sqlite:$copy").use { assertEquals(1, count(it)) }
    }

    @Test
    fun aCopyThatFailsLeavesNothingHalfMade() {
        driver().use { driver ->
            driver.execute(null, "CREATE TABLE notes(text TEXT NOT NULL)", 0)
            // the copy's place is taken by a directory that is not empty: the last move fails
            Files.createDirectories(directory.resolve("content.db$BEFORE_UPGRADE_SUFFIX").resolve("in-the-way"))
            assertNull(backUpBeforeUpgrade(driver, database.toString(), listOf(notes(2))))
        }
        assertFalse(directory.resolve("content.db$BEFORE_UPGRADE_SUFFIX.partial").exists())
        assertFalse(directory.resolve("content.db$BEFORE_UPGRADE_SUFFIX.of").exists())
    }

    @Test
    fun nothingIsCopiedForAFreshOrCurrentDatabase() {
        driver().use { driver ->
            assertNull(backUpBeforeUpgrade(driver, database.toString(), listOf(notes(2))), "a fresh database")
            SchemaMigrator.migrate(driver, notes(2))
            assertNull(backUpBeforeUpgrade(driver, database.toString(), listOf(notes(2))), "already the current format")
        }
        assertFalse(directory.resolve("content.db$BEFORE_UPGRADE_SUFFIX").exists())
    }
}
