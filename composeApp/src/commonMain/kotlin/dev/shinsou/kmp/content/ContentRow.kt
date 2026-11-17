package dev.shinsou.kmp.content

/**
 * One result row read by column name (R3-3, docs/architecture/0006): a column added to or moved
 * in a projection can no longer shift the values the code reads.
 */
internal class ContentRow(private val columns: Map<String, Int>, private val values: List<String?>) {
    fun stringOrNull(column: String): String? = values[index(column)]

    fun string(column: String): String = requireNotNull(stringOrNull(column)) { "Column $column is null" }

    private fun index(column: String): Int = requireNotNull(columns[column]) { "Column $column is not selected" }

    companion object {
        /**
         * Column positions of a projection such as `a, t.b, COUNT(*) AS c`: a column is named by
         * its alias, else by its name without the table.
         */
        fun columns(projection: String): Map<String, Int> =
            projection.split(',').withIndex().associate { (index, item) ->
                val name = item.trim().split(WHITESPACE).last().substringAfterLast('.')
                require(name.isNotEmpty()) { "Projection column ${index + 1} has no name" }
                name to index
            }.also { require(it.size == projection.split(',').size) { "Projection names a column twice" } }

        private val WHITESPACE = Regex("\\s+")
    }
}
