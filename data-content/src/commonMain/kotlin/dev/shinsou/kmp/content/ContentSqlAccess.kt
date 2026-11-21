package dev.shinsou.kmp.content

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.domain.content.MAX_CONTENT_METADATA_JSON_CHARS
import kotlinx.serialization.json.Json

/**
 * The SQL primitives every content table class uses. The tables take them by delegation, so the
 * statements read as they did when all of them lived in [SqlDriverContentTransactionStore].
 */
internal interface ContentSqlOps {
    val codec: Json
    val transactions: Transacter

    fun maybeFail(point: ContentTransactionFailurePoint)

    fun findString(table: String, keyColumn: String, key: String, valueColumn: String): String?

    fun readPairs(table: String, keyColumn: String, valueColumn: String): Map<String, String>

    fun queryRows(table: String, projectionAndClause: String, vararg arguments: String): List<ContentRow>

    fun execute(sql: String, parameters: Int, bind: app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit = {})

    fun acknowledgeRows(table: String, keyColumn: String, ids: Set<String>, label: String): Int

    fun <T> encodeJson(serializer: kotlinx.serialization.KSerializer<T>, value: T): String

    fun <T> decodeJson(serializer: kotlinx.serialization.KSerializer<T>, value: String): T
}

/**
 * The one owner of the store's SQL session: the driver, the transacter (and with it the open
 * transaction) and the failure-injection point. Callers hold the store's transaction lock.
 */
internal class ContentSqlAccess(
    private val driver: SqlDriver,
    override val codec: Json,
) : ContentSqlOps {
    override val transactions: Transacter = object : TransacterImpl(driver) {}

    /** Test and fault-injection seam, read through [SqlDriverContentTransactionStore.failureInjection]. */
    var failureInjection: ContentTransactionFailurePoint? = null

    override fun maybeFail(point: ContentTransactionFailurePoint) {
        if (failureInjection == point) {
            failureInjection = null
            throw IllegalStateException("Injected SQL content transaction failure at $point")
        }
    }

    override fun findString(table: String, keyColumn: String, key: String, valueColumn: String): String? =
        queryRows(table, "$valueColumn WHERE $keyColumn = ?", key).singleOrNull()?.stringOrNull(valueColumn)

    override fun readPairs(table: String, keyColumn: String, valueColumn: String): Map<String, String> =
        queryRows(table, "$keyColumn, $valueColumn ORDER BY $keyColumn").associate { row ->
            row.string(keyColumn) to row.string(valueColumn)
        }

    /** `SELECT <projection> FROM [table] <clause>`; each `?` in the clause is bound to the next of [arguments]. */
    override fun queryRows(table: String, projectionAndClause: String, vararg arguments: String): List<ContentRow> {
        val projection = projectionAndClause
            .substringBefore(" WHERE ")
            .substringBefore(" ORDER BY ")
            .trim()
        val suffix = projectionAndClause.substring(projection.length)
        val columns = ContentRow.columns(projection)
        return driver.executeQuery(
            identifier = null,
            sql = "SELECT $projection FROM $table$suffix",
            mapper = { cursor ->
                val rows = mutableListOf<ContentRow>()
                while (cursor.next().value) {
                    rows += ContentRow.read(cursor, columns, INTEGER_COLUMNS)
                }
                QueryResult.Value(rows)
            },
            parameters = arguments.size,
        ) {
            arguments.forEachIndexed { index, argument -> bindString(index, argument) }
        }.value
    }

    override fun execute(sql: String, parameters: Int, bind: app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit) {
        driver.execute(null, sql, parameters, bind).value
    }

    override fun acknowledgeRows(
        table: String,
        keyColumn: String,
        ids: Set<String>,
        label: String,
    ): Int {
        ids.forEach { requireSafeSqlKey(it, label) }
        if (ids.isEmpty()) return 0
        return transactions.transactionWithResult<Int>(false) {
            ids.sorted().count { id ->
                val exists = queryRows(
                    table,
                    "$keyColumn WHERE $keyColumn = ?",
                    id,
                ).isNotEmpty()
                if (exists) {
                    execute("DELETE FROM $table WHERE $keyColumn = ?", 1) { bindString(0, id) }
                }
                exists
            }
        }
    }

    override fun <T> encodeJson(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
        codec.encodeToString(serializer, value).also {
            require(it.length <= MAX_SQL_PAYLOAD_LENGTH) { "Content transaction payload is too large" }
        }

    override fun <T> decodeJson(serializer: kotlinx.serialization.KSerializer<T>, value: String): T {
        require(value.length <= MAX_SQL_PAYLOAD_LENGTH) {
            "Content transaction payload is too large"
        }
        return codec.decodeFromString(serializer, value)
    }
}

internal fun requireSafeSqlKey(value: String, label: String) {
    require(value.isNotBlank() && value.length <= 4096) { "$label must be non-blank and bounded" }
    require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
        "$label contains unsafe characters"
    }
}

/** [value] as literal text inside a `LIKE ? ESCAPE '\'` pattern. */
internal fun escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

internal const val MAX_SQL_PAYLOAD_LENGTH = MAX_CONTENT_METADATA_JSON_CHARS
