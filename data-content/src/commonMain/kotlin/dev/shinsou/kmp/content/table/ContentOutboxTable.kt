package dev.shinsou.kmp.content.table

import dev.shinsou.kmp.content.schema.TABLE_OUTBOX
import dev.shinsou.kmp.content.sql.SqliteUpsert
import dev.shinsou.kmp.content.transaction.ContentOutboxPersistenceAdapter
import dev.shinsou.kmp.content.transaction.ContentTransactionFailurePoint

/** Outbox drafts, stored through the host's [ContentOutboxPersistenceAdapter]. */
internal class ContentOutboxTable<D : Any>(
    sql: ContentSqlOps,
    val outboxAdapter: ContentOutboxPersistenceAdapter<D>,
) : ContentSqlOps by sql {
    fun pending(): List<D> =
        queryRows(TABLE_OUTBOX, "draft_id, payload ORDER BY draft_id").map { row ->
            val id = row.string("draft_id")
            val draft = decodeOutbox(row.string("payload"))
            outboxAdapter.validate(draft)
            check(outboxAdapter.id(draft) == id) { "Outbox key/body mismatch" }
            draft
        }

    fun acknowledge(draftIds: Set<String>): Int =
        acknowledgeRows(TABLE_OUTBOX, "draft_id", draftIds, "Outbox draft id")

    fun count(): Int =
        queryRows(TABLE_OUTBOX, "COUNT(*) AS draft_count").single().longOrNull("draft_count")?.toInt() ?: 0

    fun page(afterDraftId: String?, limit: Int): List<D> {
        require(limit in 1..MAX_OUTBOX_PAGE) { "Outbox page limit is out of range" }
        afterDraftId?.let { requireSafeSqlKey(it, "Outbox draft id") }
        val page = "ORDER BY draft_id LIMIT $limit"
        val rows = if (afterDraftId == null) {
            queryRows(TABLE_OUTBOX, "draft_id, payload $page")
        } else {
            queryRows(TABLE_OUTBOX, "draft_id, payload WHERE draft_id > ? $page", afterDraftId)
        }
        return rows.map { row ->
            val id = row.string("draft_id")
            val draft = decodeOutbox(row.string("payload"))
            outboxAdapter.validate(draft)
            check(outboxAdapter.id(draft) == id) { "Outbox key/body mismatch" }
            draft
        }
    }

    fun draft(draftId: String): D? {
        requireSafeSqlKey(draftId, "Outbox draft id")
        return findString(TABLE_OUTBOX, "draft_id", draftId, "payload")?.let { payload ->
            decodeOutbox(payload).also { draft ->
                outboxAdapter.validate(draft)
                check(outboxAdapter.id(draft) == draftId) { "Outbox key/body mismatch" }
            }
        }
    }

    fun replace(remove: Set<String>, add: List<D>): Int {
        remove.forEach { requireSafeSqlKey(it, "Outbox draft id") }
        add.forEach { draft ->
            outboxAdapter.validate(draft)
            requireSafeSqlKey(outboxAdapter.id(draft), "Outbox draft id")
            require(outboxAdapter.encode(draft).length <= MAX_SQL_PAYLOAD_LENGTH) { "Outbox payload is too large" }
        }
        require(add.map(outboxAdapter::id).distinct().size == add.size) {
            "Replacement outbox draft ids must be unique"
        }
        return transactions.transactionWithResult<Int>(false) {
            val removed = remove.sorted().count { id ->
                val exists = findString(TABLE_OUTBOX, "draft_id", id, "draft_id") != null
                if (exists) execute("DELETE FROM $TABLE_OUTBOX WHERE draft_id = ?", 1) { bindString(0, id) }
                exists
            }
            add.forEach { draft ->
                val id = outboxAdapter.id(draft)
                check(findString(TABLE_OUTBOX, "draft_id", id, "draft_id") == null) {
                    "Replacement outbox draft id already exists"
                }
                execute("INSERT INTO $TABLE_OUTBOX(draft_id, payload) VALUES (?, ?)", 2) {
                    bindString(0, id)
                    bindString(1, outboxAdapter.encode(draft))
                }
            }
            removed
        }
    }

    fun decodeOutbox(value: String): D {
        require(value.length <= MAX_SQL_PAYLOAD_LENGTH) { "Outbox payload is too large" }
        return outboxAdapter.decode(value)
    }

    fun writeOutbox(drafts: List<D>) {
        drafts.forEach { draft ->
            val id = outboxAdapter.id(draft)
            val payload = outboxAdapter.encode(draft)
            // SqliteUpsert: Android 10's SQLite has no `ON CONFLICT ... DO NOTHING`.
            outboxInsert.run(listOf(id, payload), ::execute)
            maybeFail(ContentTransactionFailurePoint.AFTER_OUTBOX_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_OUTBOX_WRITE)
        }
    }
}

private const val MAX_OUTBOX_PAGE = 1_024

private val outboxInsert = SqliteUpsert(TABLE_OUTBOX, listOf("draft_id", "payload"), keyCount = 1, updated = emptyList())
