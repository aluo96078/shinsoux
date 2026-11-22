package dev.shinsou.kmp.content.sql

import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * An UPSERT that SQLite 3.22 runs. `INSERT ... ON CONFLICT ... DO UPDATE/DO NOTHING` needs SQLite
 * 3.24, but Android 10 (the app's minimum) ships 3.22 and rejects it as a syntax error, so every
 * write through it failed there.
 *
 * [insert] adds the row only when no row has its key (`INSERT ... SELECT ... WHERE NOT EXISTS`,
 * not `INSERT OR IGNORE`: a NOT NULL or CHECK violation still fails as it did before); [update]
 * then sets the [updated] columns on the row with that key. Both run in the caller's transaction,
 * so the pair is atomic like the single statement it replaces.
 */
internal class SqliteUpsert(
    private val table: String,
    /** Key columns first, then the others, in the order their values are given. */
    private val columns: List<String>,
    private val keyCount: Int,
    /** The columns an existing row takes from the new values; none for "insert if absent". */
    private val updated: List<String> = columns.drop(keyCount),
) {
    init {
        require(keyCount in 1..columns.size) { "An upsert needs its key columns" }
        require(updated.all { it in columns.drop(keyCount) }) { "Only non-key columns are updated" }
    }

    private val keys = columns.take(keyCount)
    private val keyMatch = keys.joinToString(" AND ") { "$it = ?" }

    val insert: String =
        "INSERT INTO $table(${columns.joinToString(", ")}) SELECT ${columns.joinToString(", ") { "?" }} " +
            "WHERE NOT EXISTS (SELECT 1 FROM $table WHERE $keyMatch)"

    val update: String? = updated.takeIf { it.isNotEmpty() }?.let { set ->
        "UPDATE $table SET ${set.joinToString(", ") { "$it = ?" }} WHERE $keyMatch"
    }

    /** Runs the upsert of [values] (one per column, in [columns] order) through [execute]. */
    fun run(
        values: List<Any?>,
        execute: (sql: String, parameters: Int, bind: SqlPreparedStatement.() -> Unit) -> Unit,
    ) {
        require(values.size == columns.size) { "An upsert of $table needs ${columns.size} values" }
        val keyValues = values.take(keyCount)
        val inserted = values + keyValues
        execute(insert, inserted.size) { inserted.forEachIndexed { index, value -> bindValue(index, value) } }
        val update = update ?: return
        val byColumn = columns.zip(values).toMap()
        val updatedValues = updated.map(byColumn::getValue) + keyValues
        execute(update, updatedValues.size) { updatedValues.forEachIndexed { index, value -> bindValue(index, value) } }
    }
}

private fun SqlPreparedStatement.bindValue(index: Int, value: Any?) {
    when (value) {
        null -> bindString(index, null)
        is String -> bindString(index, value)
        is Long -> bindLong(index, value)
        is Int -> bindLong(index, value.toLong())
        is Boolean -> bindLong(index, if (value) 1L else 0L)
        is ByteArray -> bindBytes(index, value)
        is Double -> bindDouble(index, value)
        else -> throw IllegalArgumentException("Unsupported SQL value ${value::class.simpleName}")
    }
}
