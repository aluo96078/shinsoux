package dev.shinsou.kmp.content.sql

import app.cash.sqldelight.db.SqlCursor

/**
 * One result row read by column name (R3-3, docs/architecture/0006): a column added to or moved
 * in a projection can no longer shift the values the code reads.
 *
 * The values are copied out of the cursor ([read]) so the row outlives it: a caller may run
 * further queries while it walks a list of rows. Each column is read with the getter of its type,
 * an integer column with `getLong` and every other one with `getString`; reading a column with the
 * accessor of another type fails instead of converting it.
 */
public class ContentRow(private val columns: Map<String, Int>, private val values: List<Any?>) {
    public fun stringOrNull(column: String): String? = typed<String>(column, "text")

    public fun string(column: String): String = requireNotNull(stringOrNull(column)) { "Column $column is null" }

    public fun longOrNull(column: String): Long? = typed<Long>(column, "integer")

    public fun long(column: String): Long = requireNotNull(longOrNull(column)) { "Column $column is null" }

    /** A 0/1 flag column; NULL reads as false, as any value other than 1 does. */
    public fun flag(column: String): Boolean = longOrNull(column) == 1L

    private inline fun <reified T> typed(column: String, kind: String): T? {
        val value = values[index(column)]
        require(value == null || value is T) { "Column $column is not read as $kind" }
        return value as T?
    }

    private fun index(column: String): Int = requireNotNull(columns[column]) { "Column $column is not selected" }

    public companion object {
        /**
         * Copies the cursor's current row: the columns named in [integerColumns] with `getLong`,
         * every other one with `getString`.
         */
        public fun read(cursor: SqlCursor, columns: Map<String, Int>, integerColumns: Set<String>): ContentRow {
            val values = arrayOfNulls<Any>(columns.size)
            columns.forEach { (name, index) ->
                values[index] = if (name in integerColumns) cursor.getLong(index) else cursor.getString(index)
            }
            return ContentRow(columns, values.asList())
        }

        /**
         * Column positions of a projection such as `a, t.b, COUNT(*) AS c`: a column is named by
         * its alias, else by its name without the table. A comma inside parentheses (as in
         * `substr(a, 1, 2)`) or inside a quoted literal does not start a new column; an expression
         * must be aliased.
         */
        public fun columns(projection: String): Map<String, Int> {
            val items = splitProjection(projection)
            return items.withIndex().associate { (index, item) ->
                val segment = item.trim()
                require(segment.isNotEmpty()) { "Projection column ${index + 1} has no name" }
                val alias = ALIAS.find(segment)
                val expression = alias?.let { segment.substring(0, it.range.first).trim() } ?: segment
                val name = alias?.groupValues?.get(1) ?: simpleName(expression, index)
                require(name.isNotEmpty()) { "Projection column ${index + 1} has no name" }
                name to index
            }.also { require(it.size == items.size) { "Projection names a column twice" } }
        }

        /**
         * [columns] of the projection of a whole `SELECT [DISTINCT] … FROM …` statement, so the
         * names a query is read by always come from the very text it runs. The projection ends
         * at the first `FROM` outside parentheses and quotes; a subquery in the `FROM` clause is
         * not looked into.
         */
        public fun selectColumns(sql: String): Map<String, Int> {
            val text = sql.trim()
            require(text.regionMatches(0, SELECT, 0, SELECT.length, ignoreCase = true)) {
                "Not a SELECT statement"
            }
            var projection = text.substring(SELECT.length).trimStart()
            if (projection.regionMatches(0, DISTINCT, 0, DISTINCT.length, ignoreCase = true)) {
                projection = projection.substring(DISTINCT.length).trimStart()
            }
            val end = topLevelFrom(projection)
            return columns(if (end < 0) projection else projection.substring(0, end))
        }

        /**
         * The plain name of an unaliased projection item. An expression has no column name of its
         * own, so reading it by name would read another column; the caller must alias it instead.
         */
        private fun simpleName(expression: String, index: Int): String {
            require(expression.none { it in OPERATORS }) {
                "Projection column ${index + 1} is an expression; add an AS alias"
            }
            return expression.split(WHITESPACE).last().substringAfterLast('.')
        }

        /** Splits on the commas that separate columns; commas in parentheses or quotes are ignored. */
        private fun splitProjection(projection: String): List<String> {
            val items = mutableListOf<String>()
            val current = StringBuilder()
            val scanner = SqlScanner()
            projection.forEach { char ->
                if (scanner.atTopLevel && char == ',') {
                    items += current.toString()
                    current.clear()
                } else {
                    current.append(char)
                }
                scanner.advance(char)
            }
            items += current.toString()
            return items
        }

        /** Index of the whitespace before the first top-level `FROM`, or -1. */
        private fun topLevelFrom(text: String): Int {
            val scanner = SqlScanner()
            text.forEachIndexed { index, char ->
                if (scanner.atTopLevel && char.isWhitespace() && isFromKeyword(text, index + 1)) return index
                scanner.advance(char)
            }
            return -1
        }

        private fun isFromKeyword(text: String, start: Int): Boolean =
            text.regionMatches(start, FROM, 0, FROM.length, ignoreCase = true) &&
                (start + FROM.length == text.length || text[start + FROM.length].isWhitespace())

        /** An expression's `AS name` suffix; the last one, so a value ending in `CAST(...)` is still split. */
        private val ALIAS = Regex("(?i)\\s+AS\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*$")
        private val WHITESPACE = Regex("\\s+")
        private const val OPERATORS = "()+-*/%<>=|&~!'"
        private const val SELECT = "SELECT "
        private const val DISTINCT = "DISTINCT "
        private const val FROM = "FROM"
    }
}

/** Parenthesis depth and quoted-literal state while scanning SQL text one character at a time. */
private class SqlScanner {
    private var depth = 0
    private var quoted = false

    val atTopLevel: Boolean get() = depth == 0 && !quoted

    fun advance(char: Char) {
        when {
            char == '\'' -> quoted = !quoted
            quoted -> Unit
            char == '(' -> depth++
            char == ')' -> depth--
        }
    }
}

/**
 * One row of [table] read by column name straight from the cursor, while it is on that row; each
 * accessor uses the cursor getter of its type. For stores that map each row as it is read.
 */
public class ContentCursorRow(
    private val cursor: SqlCursor,
    private val table: String,
    private val columns: Map<String, Int>,
) {
    public fun stringOrNull(column: String): String? = cursor.getString(index(column))

    public fun string(column: String): String = requireNotNull(stringOrNull(column)) { "$table.$column is null" }

    public fun longOrNull(column: String): Long? = cursor.getLong(index(column))

    public fun long(column: String): Long = requireNotNull(longOrNull(column)) { "$table.$column is null" }

    /** A 0/1 flag column; NULL reads as false, as any value other than 1 does. */
    public fun flag(column: String): Boolean = longOrNull(column) == 1L

    public fun doubleOrNull(column: String): Double? = cursor.getDouble(index(column))

    internal fun bytesOrNull(column: String): ByteArray? = cursor.getBytes(index(column))

    private fun index(column: String): Int = requireNotNull(columns[column]) { "$table.$column is not selected" }
}
