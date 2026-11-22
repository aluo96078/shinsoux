package dev.shinsou.kmp.content.sql

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.shinsou.kmp.content.table.ContentSqlAccess

class SqlStatementIdentifiersTest {
    @Test
    fun theSameSqlKeepsItsIdentifierAndDifferentSqlNeverSharesOne() {
        val registry = SqlStatementIdentifiers()

        val first = registry.identifierFor("SELECT 1")
        val second = registry.identifierFor("SELECT 2")

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first, second)
        assertEquals(first, registry.identifierFor("SELECT 1"))
        assertEquals(second, registry.identifierFor("SELECT 2"))
    }

    @Test
    fun textsBeyondTheCapRunUncachedButRegisteredOnesStayCached() {
        val registry = SqlStatementIdentifiers(maxEntries = 3)
        val ids = (0 until 3).map { registry.identifierFor("SELECT $it") }

        assertEquals(3, ids.toSet().size)
        assertNull(registry.identifierFor("SELECT 99"))
        assertNull(registry.identifierFor("SELECT 99"))
        assertEquals(ids[1], registry.identifierFor("SELECT 1"))
    }

    @Test
    fun theSharedRegistryIsCappedAtTheDefault() {
        val registry = SqlStatementIdentifiers()
        repeat(SqlStatementIdentifiers.DEFAULT_MAX_ENTRIES) { assertNotNull(registry.identifierFor("SELECT $it")) }
        assertNull(registry.identifierFor("SELECT overflow"))
    }

    @Test
    fun numbersStayInsideTheRangeTheBuildKeepsFreeOfGeneratedIdentifiers() {
        val registry = SqlStatementIdentifiers()
        val first = SqlStatementIdentifiers.FIRST_IDENTIFIER
        val ids = (0 until SqlStatementIdentifiers.DEFAULT_MAX_ENTRIES).map { requireNotNull(registry.identifierFor("SELECT $it")) }

        assertEquals(first, ids.first())
        assertEquals(first + SqlStatementIdentifiers.DEFAULT_MAX_ENTRIES - 1, ids.last())
        assertTrue(ids.all { it in first until first + SqlStatementIdentifiers.DEFAULT_MAX_ENTRIES })
        assertFailsWith<IllegalArgumentException> { SqlStatementIdentifiers(SqlStatementIdentifiers.DEFAULT_MAX_ENTRIES + 1) }
    }

    @Test
    fun aRepeatedCachedStatementReturnsTheRowsOfEachCallsParameters() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT NOT NULL)", 0).value
            val access = ContentSqlAccess(driver, Json)

            repeat(3) { round ->
                listOf("a", "b", "c").forEach { key ->
                    access.execute("INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)", 2) {
                        bindString(0, key)
                        bindString(1, "$key$round")
                    }
                }
                listOf("a", "b", "c", "missing").forEach { key ->
                    val expected = if (key == "missing") null else "$key$round"
                    assertEquals(expected, access.findString("kv", "k", key, "v"))
                }
            }
            assertEquals(mapOf("a" to "a2", "b" to "b2", "c" to "c2"), access.readPairs("kv", "k", "v"))
            assertEquals(2, access.acknowledgeRows("kv", "k", setOf("a", "b", "zzz"), "key"))
            assertEquals(mapOf("c" to "c2"), access.readPairs("kv", "k", "v"))
        }
    }

    @Test
    fun theStatementHelpersRunWithAndWithoutACacheIdentifier() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.executeUncached("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT NOT NULL)", 0).value
            repeat(3) { round ->
                driver.executeStatement("INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)", 2) {
                    bindString(0, "k")
                    bindString(1, "v$round")
                }.value
            }
            val select = "SELECT v FROM kv WHERE k = ?"
            val read = { cursor: app.cash.sqldelight.db.SqlCursor ->
                app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
            }
            assertEquals("v2", driver.queryStatement(select, read, 1) { bindString(0, "k") }.value)
            assertEquals("v2", driver.queryUncached(select, read, 1) { bindString(0, "k") }.value)
            assertEquals(null, driver.queryStatement(select, read, 1) { bindString(0, "none") }.value)
            assertNotNull(SqlStatementIdentifiers.shared.identifierFor(select))
        }
    }
}
