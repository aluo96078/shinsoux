package dev.shinsou.build

import kotlin.test.Test
import kotlin.test.assertEquals

/** [nullIdentifierCalls]: the driver calls that would turn the prepared-statement cache off. */
class NullIdentifierCallsTest {
    private fun lines(source: String): List<Int> =
        nullIdentifierCalls(source).map { offset -> source.substring(0, offset).count { it == '\n' } + 1 }

    @Test
    fun aPositionalNullFirstArgumentIsFoundWithOrWithoutAReceiver() {
        val source = """
            driver.execute(null, sql, 0)
            driver.executeQuery<String>(null, sql, mapper, 0)
            with(driver) { execute(null, sql, 0) }
            execute (
                null,
                sql,
            )
        """.trimIndent()
        assertEquals(listOf(1, 2, 3, 4), lines(source))
    }

    @Test
    fun aNamedNullIdentifierIsFoundInAnyPosition() {
        val source = """
            driver.execute(identifier = null, sql = sql, parameters = 0)
            driver.execute(sql = sql, identifier = null, parameters = 0)
            execute(sql = sql, parameters = 0, identifier = null) { bindLong(0, 1) }
        """.trimIndent()
        assertEquals(listOf(1, 2, 3), lines(source))
    }

    @Test
    fun anIdentifierOrANullElsewhereIsNotAFinding() {
        val source = """
            driver.execute(12, sql, 0)
            driver.execute(identifier, sql, 0, binder = null)
            execute("DELETE FROM t WHERE id = ?", 1) { bindString(0, null) }
            gate.execute(request) { store(identifier = null) }
            override fun <R> executeQuery(identifier: Int?, sql: String): R = TODO()
            fun execute(sql: String, parameters: Int)
            execute("cleanup", null)
            driver.execute(sql = sql, null)
            execute(
                "DELETE FROM t",
                null,
            )
        """.trimIndent()
        assertEquals(emptyList(), lines(source))
    }

    @Test
    fun anOrdinaryFunctionNamedLikeTheDriverCallIsNotAFinding() {
        // Not the driver's shape: a statement identifier is never the only argument.
        val source = """
            execute(null)
            runner.execute(null) { retry() }
            cache.executeQuery<String>(null)
            execute(
                null,
            )
        """.trimIndent()
        assertEquals(emptyList(), lines(source))
        // With the SQL after it, it still is one, trailing binder lambda or not.
        assertEquals(listOf(1, 2), lines("execute(null, sql) { bindLong(0, 1) }\nexecuteQuery(null, sql, mapper, 0)"))
    }

    @Test
    fun stringsAndCommentsAreNotCode() {
        val q = "\"\"\""
        val source = listOf(
            "val a = \"driver.execute(null, sql, 0)\"",
            "// driver.execute(null, sql, 0)",
            "/* outer /* inner */ driver.execute(null, sql, 0) */",
            "val b = Regex(${q}x=\"([^\"]+)\"$q\"); driver.execute(1, sql, 0); val c = \"driver.execute(null)\"",
            "val d = '\"'; driver.execute(null, sql, 0)",
        ).joinToString("\n")
        assertEquals(listOf(5), lines(source))
    }
}
