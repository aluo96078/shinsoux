package dev.shinsou.kmp.sync.v3

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import dev.shinsou.kmp.content.ContentCursorRow
import dev.shinsou.kmp.content.ContentRow
import dev.shinsou.kmp.content.SchemaComponent
import dev.shinsou.kmp.content.SchemaMigrator
import dev.shinsou.kmp.content.tableExists
import kotlinx.serialization.json.Json

/**
 * One locally observed change that has not been turned into a pushable record yet.
 *
 * [ref] is a device-local entity reference (`m:<mangaId>`, `c:<chapterId>`, `k:<categoryId>`); it is
 * resolved to a record key only at push time, so marking stays cheap. [field] is a wire field name
 * or [DELETED_FIELD], whose [aux] is `"true"`/`"false"`. For removals the observer already knows the
 * record key from the previous snapshot and stores it in [tombKey] (with [tombWork]/[tombUnit] for
 * records that need a work descriptor).
 */
internal data class DirtyEntry(
    val kind: String,
    val ref: String,
    val field: String,
    val clock: String,
    val aux: String? = null,
    val tombKey: String? = null,
    val tombWork: String? = null,
    val tombUnit: String? = null,
) {
    companion object {
        const val DELETED_FIELD: String = "~deleted"
    }
}

/**
 * The reject reason kept for a record whose kind the server does not know yet (an older server
 * answers `invalid_record` for every `package` record). Retried slowly, never shown as a failure.
 */
internal const val REJECT_UNSUPPORTED_KIND = "unsupported_kind"

/** A record as persisted on this device: the merged server view plus unpushed local edits. */
internal data class StoredRecord(
    val kind: String,
    val key: String,
    val workHash: String? = null,
    val unitRemoteId: String? = null,
    val workJson: String? = null,
    val state: LwwMerge.LocalState = LwwMerge.LocalState(),
    val seq: Long = 0,
    /** True while the merged state has not (fully) been projected onto the app snapshot. */
    val unapplied: Boolean = false,
    /** Why an unapplied record cannot be projected yet (kept until it becomes resolvable). */
    val pendingReason: String? = null,
    val rejectReason: String? = null,
    val rejectedAt: Long? = null,
)

/**
 * Sync v3 state in the shared content database. It never touches the content schema version: its
 * tables have their own ([SyncV3Schema]).
 */
internal class SyncV3Store(private val driver: SqlDriver) {
    private val transactions = object : TransacterImpl(driver) {}

    init {
        SyncV3Schema.create(driver).value
    }

    fun <T> transaction(block: () -> T): T = transactions.transactionWithResult(noEnclosing = false) { block() }

    // ---- meta -------------------------------------------------------------------------------

    fun meta(key: String): String? = namedQuery(
        "SELECT v FROM sync3_meta WHERE k = ?",
        "sync3_meta",
        1,
        { bindString(0, key) },
    ) { it.stringOrNull("v") }.firstOrNull()

    fun setMeta(key: String, value: String?) {
        if (value == null) {
            exec("DELETE FROM sync3_meta WHERE k = ?", 1) { bindString(0, key) }
        } else {
            exec("INSERT OR REPLACE INTO sync3_meta(k, v) VALUES (?, ?)", 2) {
                bindString(0, key)
                bindString(1, value)
            }
        }
    }

    // ---- dirty set --------------------------------------------------------------------------

    fun mark(entries: Collection<DirtyEntry>) {
        if (entries.isEmpty()) return
        transaction {
            entries.forEach { e ->
                exec(
                    "INSERT OR REPLACE INTO sync3_dirty(kind, ref, field, clock, aux, tomb_key, tomb_work, tomb_unit) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    8,
                ) {
                    bindString(0, e.kind)
                    bindString(1, e.ref)
                    bindString(2, e.field)
                    bindString(3, e.clock)
                    bindString(4, e.aux)
                    bindString(5, e.tombKey)
                    bindString(6, e.tombWork)
                    bindString(7, e.tombUnit)
                }
            }
        }
    }

    /** Distinct `(kind, ref)` pairs with unpromoted dirty fields. */
    fun dirtyRefs(limit: Int): List<Pair<String, String>> = namedQuery(
        "SELECT DISTINCT kind, ref FROM sync3_dirty ORDER BY kind, ref LIMIT ?",
        "sync3_dirty",
        1,
        { bindLong(0, limit.toLong()) },
    ) { it.string("kind") to it.string("ref") }

    fun dirtyFor(kind: String, ref: String): List<DirtyEntry> = namedQuery(
        "SELECT $DIRTY_PROJECTION FROM sync3_dirty WHERE kind = ? AND ref = ?",
        "sync3_dirty",
        2,
        { bindString(0, kind); bindString(1, ref) },
    ) { it.readDirty() }

    fun allDirty(): List<DirtyEntry> = namedQuery(
        "SELECT $DIRTY_PROJECTION FROM sync3_dirty ORDER BY kind, ref, field",
        "sync3_dirty",
    ) { it.readDirty() }

    fun hasDirty(kind: String, ref: String): Boolean = query(
        "SELECT 1 FROM sync3_dirty WHERE kind = ? AND ref = ? LIMIT 1", 2, { bindString(0, kind); bindString(1, ref) },
    ) { 1 }.isNotEmpty()

    /** Removes exactly the observed rows; a row re-marked with a newer clock stays. */
    fun deleteDirty(entries: Collection<DirtyEntry>) {
        if (entries.isEmpty()) return
        transaction {
            entries.forEach { e ->
                exec("DELETE FROM sync3_dirty WHERE kind = ? AND ref = ? AND field = ? AND clock = ?", 4) {
                    bindString(0, e.kind)
                    bindString(1, e.ref)
                    bindString(2, e.field)
                    bindString(3, e.clock)
                }
            }
        }
    }

    /** Moves [entry] to [clock] unless it was marked again since it was read. */
    fun rebaseDirtyClock(entry: DirtyEntry, clock: String) {
        exec("UPDATE sync3_dirty SET clock = ? WHERE kind = ? AND ref = ? AND field = ? AND clock = ?", 5) {
            bindString(0, clock)
            bindString(1, entry.kind)
            bindString(2, entry.ref)
            bindString(3, entry.field)
            bindString(4, entry.clock)
        }
    }

    // ---- acknowledged clock floor -----------------------------------------------------------

    /**
     * The newest clock (millis, counter) seen in a record from the server. The local clock must
     * stay above it. It counts every record ingested since it was first kept; it covers the whole
     * account only once [establishAcknowledgedFloor] was called after a pull from the beginning.
     */
    fun acknowledgedClockFloor(): Pair<Long, Int>? = meta(META_ACK_FLOOR)?.let(::clockHead)

    fun acknowledgedFloorEstablished(): Boolean = meta(META_ACK_FLOOR_COMPLETE) == "1"

    /** Call after every record of the account has been ingested once (a pull from sequence zero). */
    fun establishAcknowledgedFloor() {
        if (acknowledgedClockFloor() == null) setMeta(META_ACK_FLOOR, "0:0")
        setMeta(META_ACK_FLOOR_COMPLETE, "1")
    }

    /** Records whose removal register or removal floor satisfies [matches] (clock strings do not sort in SQL). */
    fun recordsWithRemovalClocks(matches: (deleted: String?, cleared: String?) -> Boolean): List<StoredRecord> =
        namedQuery(
            "SELECT kind, key, del_clock, cleared FROM sync3_record " +
                "WHERE del_clock IS NOT NULL OR cleared IS NOT NULL",
            "sync3_record",
        ) { row ->
            Triple(
                row.string("kind"),
                row.string("key"),
                row.stringOrNull("del_clock") to row.stringOrNull("cleared"),
            )
        }.filter { (_, _, clocks) -> matches(clocks.first, clocks.second) }
            .mapNotNull { (kind, key, _) -> loadRecord(kind, key) }

    fun noteAcknowledgedClocks(clocks: Collection<String>) {
        if (clocks.isEmpty()) return
        val known = acknowledgedClockFloor()
        var best = known ?: (0L to 0)
        var raised = known == null
        for (clock in clocks) {
            val head = clockHead(clock) ?: continue
            if (head.first > best.first || (head.first == best.first && head.second > best.second)) {
                best = head
                raised = true
            }
        }
        // Read and written through the database only: a cached value would outlive a rolled
        // back transaction and hide the floor from the retry.
        if (raised) setMeta(META_ACK_FLOOR, "${best.first}:${best.second}")
    }

    /** `millis:counter` of a clock (or of the stored floor), without the cost of full validation. */
    private fun clockHead(clock: String): Pair<Long, Int>? {
        val first = clock.indexOf(':')
        if (first <= 0) return null
        val second = clock.indexOf(':', first + 1).let { if (it < 0) clock.length else it }
        val millis = clock.substring(0, first).toLongOrNull() ?: return null
        val counter = clock.substring(first + 1, second).toIntOrNull() ?: return null
        return millis to counter
    }

    /** Pending local edits, not counting the kinds in [excluded] (switched off on this device). */
    fun dirtyRefCount(excluded: Set<String> = emptySet()): Int = namedQuery(
        "SELECT COUNT(*) AS row_count FROM (SELECT DISTINCT kind, ref FROM sync3_dirty WHERE 1 = 1${notIn(excluded)})",
        "sync3_dirty",
    ) { it.long("row_count").toInt() }.first()

    // ---- records ----------------------------------------------------------------------------

    /** The stored descriptor of every work this account has live records for (library, history, progress, units). */
    fun liveWorkJson(): List<String> = namedQuery(
        "SELECT work_hash, MAX(work_json) AS work_json FROM sync3_record " +
            "WHERE work_hash IS NOT NULL AND work_json IS NOT NULL " +
            "AND (del_value IS NULL OR del_value = 0) GROUP BY work_hash",
        "sync3_record",
    ) { it.string("work_json") }

    fun loadRecord(kind: String, key: String): StoredRecord? {
        val head = namedQuery(
            RECORD_SELECT + " WHERE kind = ? AND key = ?",
            "sync3_record",
            2,
            { bindString(0, kind); bindString(1, key) },
        ) { it.readRecordHead() }.firstOrNull() ?: return null
        return head.withFields(loadFields(kind, key))
    }

    private fun loadFields(kind: String, key: String): Map<String, LwwMerge.LocalField> = namedQuery(
        "SELECT field, value, clock, dirty FROM sync3_field WHERE kind = ? AND key = ?",
        "sync3_field",
        2,
        { bindString(0, kind); bindString(1, key) },
    ) { row ->
        row.string("field") to LwwMerge.LocalField(
            value = Json.parseToJsonElement(row.string("value")),
            clock = row.string("clock"),
            dirty = row.longOrNull("dirty") == 1L,
        )
    }.toMap()

    fun saveRecord(record: StoredRecord) {
        transaction {
            val hasDirty = record.state.hasDirty()
            exec(
                "INSERT OR REPLACE INTO sync3_record(kind, key, work_hash, unit_remote_id, work_json, del_value, del_clock, " +
                    "del_dirty, cleared, seq, unapplied, pending_reason, reject_reason, reject_at, has_dirty) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                15,
            ) {
                bindString(0, record.kind)
                bindString(1, record.key)
                bindString(2, record.workHash)
                bindString(3, record.unitRemoteId)
                bindString(4, record.workJson)
                bindLong(5, record.state.deleted?.let { if (it.value) 1L else 0L })
                bindString(6, record.state.deleted?.clock)
                bindLong(7, if (record.state.deleted?.dirty == true) 1L else 0L)
                bindString(8, record.state.cleared)
                bindLong(9, record.seq)
                bindLong(10, if (record.unapplied) 1L else 0L)
                bindString(11, record.pendingReason)
                bindString(12, record.rejectReason)
                bindLong(13, record.rejectedAt)
                bindLong(14, if (hasDirty) 1L else 0L)
            }
            exec("DELETE FROM sync3_field WHERE kind = ? AND key = ?", 2) {
                bindString(0, record.kind)
                bindString(1, record.key)
            }
            record.state.fields.forEach { (name, field) ->
                exec("INSERT INTO sync3_field(kind, key, field, value, clock, dirty) VALUES (?, ?, ?, ?, ?, ?)", 6) {
                    bindString(0, record.kind)
                    bindString(1, record.key)
                    bindString(2, name)
                    bindString(3, field.value.toString())
                    bindString(4, field.clock)
                    bindLong(5, if (field.dirty) 1L else 0L)
                }
            }
        }
    }

    fun deleteRecord(kind: String, key: String) {
        transaction {
            exec("DELETE FROM sync3_field WHERE kind = ? AND key = ?", 2) { bindString(0, kind); bindString(1, key) }
            exec("DELETE FROM sync3_record WHERE kind = ? AND key = ?", 2) { bindString(0, kind); bindString(1, key) }
        }
    }

    /**
     * Records that still hold unpushed edits. Fresh ones come first; records parked after a server
     * rejection come back only once [retryAfterMillis] says their waiting time is over.
     */
    fun pushableRecords(
        limit: Int,
        nowMillis: Long,
        retryAfterMillis: (String) -> Long?,
        excluded: Set<String> = emptySet(),
    ): List<StoredRecord> {
        val out = ArrayList<StoredRecord>()
        namedQuery(
            RECORD_SELECT + " WHERE has_dirty = 1 AND reject_reason IS NULL${notIn(excluded)} ORDER BY kind, key LIMIT ?",
            "sync3_record",
            1,
            { bindLong(0, limit.toLong()) },
        ) { it.readRecordHead() }.forEach { out += it.withFields(loadFields(it.kind, it.key)) }
        if (out.size >= limit) return out
        namedQuery(
            RECORD_SELECT + " WHERE has_dirty = 1 AND reject_reason IS NOT NULL${notIn(excluded)} ORDER BY kind, key",
            "sync3_record",
        ) { it.readRecordHead() }.forEach { head ->
            val wait = retryAfterMillis(head.rejectReason.orEmpty()) ?: return@forEach
            val rejectedAt = head.rejectedAt ?: 0L
            // A rejection time in the future was written while the device's time was set ahead;
            // waiting for it would park the record for as long as the time was wrong by.
            if (out.size < limit && (rejectedAt > nowMillis || rejectedAt + wait <= nowMillis)) {
                out += head.withFields(loadFields(head.kind, head.key))
            }
        }
        return out
    }

    /** Dirty records that were parked after a server rejection (shown in the UI). */
    fun rejectedCount(excluded: Set<String> = emptySet()): Int = namedQuery(
        "SELECT COUNT(*) AS row_count FROM sync3_record WHERE has_dirty = 1 AND reject_reason IS NOT NULL " +
            "AND reject_reason <> '$REJECT_UNSUPPORTED_KIND'${notIn(excluded)}",
        "sync3_record",
    ) { it.long("row_count").toInt() }.first()

    /**
     * Records holding unpushed edits. Records an older server refused as an unknown kind are not
     * counted: they are retried in the background and are not the user's backlog.
     */
    fun dirtyRecordCount(excluded: Set<String> = emptySet()): Int = namedQuery(
        "SELECT COUNT(*) AS row_count FROM sync3_record WHERE has_dirty = 1 " +
            "AND (reject_reason IS NULL OR reject_reason <> '$REJECT_UNSUPPORTED_KIND')${notIn(excluded)}",
        "sync3_record",
    ) { it.long("row_count").toInt() }.first()

    fun lastRejection(): String? = namedQuery(
        "SELECT kind, reject_reason FROM sync3_record WHERE has_dirty = 1 AND reject_reason IS NOT NULL LIMIT 1",
        "sync3_record",
    ) { "${it.stringOrNull("kind")}: ${it.stringOrNull("reject_reason")}" }.firstOrNull()

    /** Unapplied records of one kind with `key > afterKey`, in key order (pending ones stay unapplied). */
    fun unappliedRecords(kind: String, afterKey: String, limit: Int): List<StoredRecord> {
        val heads = namedQuery(
            RECORD_SELECT + " WHERE unapplied = 1 AND kind = ? AND key > ? ORDER BY key LIMIT ?",
            "sync3_record",
            3,
            { bindString(0, kind); bindString(1, afterKey); bindLong(2, limit.toLong()) },
        ) { it.readRecordHead() }
        return heads.map { it.withFields(loadFields(it.kind, it.key)) }
    }

    /**
     * The chapter-level reading of [workHash] (history, position, read marks) parked until this
     * device stores the chapter it is about, not counting the kinds in [excluded].
     */
    fun readingWaitingForUnit(workHash: String, excluded: Set<String> = emptySet()): List<StoredRecord> {
        val heads = namedQuery(
            RECORD_SELECT + " WHERE unapplied = 1 AND pending_reason = ? AND work_hash = ? AND kind IN (?, ?, ?)" + notIn(excluded),
            "sync3_record",
            5,
            {
                bindString(0, PENDING_UNIT_UNKNOWN)
                bindString(1, workHash)
                bindString(2, SyncKinds.HISTORY)
                bindString(3, SyncKinds.PROGRESS)
                bindString(4, SyncKinds.UNIT)
            },
        ) { it.readRecordHead() }
        return heads.map { it.withFields(loadFields(it.kind, it.key)) }
    }

    /**
     * When each work was last read on another device, by work hash, for the history parked until
     * this device stores the chapter it is about; nothing when [SyncKinds.HISTORY] is [excluded].
     */
    fun lastReadWaitingForUnit(excluded: Set<String> = emptySet()): Map<String, Long> {
        if (SyncKinds.HISTORY in excluded) return emptyMap()
        val heads = namedQuery(
            RECORD_SELECT + " WHERE unapplied = 1 AND pending_reason = ? AND kind = ?",
            "sync3_record",
            2,
            {
                bindString(0, PENDING_UNIT_UNKNOWN)
                bindString(1, SyncKinds.HISTORY)
            },
        ) { it.readRecordHead() }
        val result = HashMap<String, Long>()
        for (head in heads) {
            val work = head.record.workHash ?: continue
            head.withFields(loadFields(head.kind, head.key))
                .takeIf { it.state.isLive }
                ?.state?.fields?.get("lastReadAt")?.value?.long()
                ?.takeIf { at -> at > (result[work] ?: Long.MIN_VALUE) }
                ?.let { at -> result[work] = at }
        }
        return result
    }

    /** Marks a record as projected (or parked with [pendingReason]) without touching its fields. */
    fun setApplied(kind: String, key: String, pendingReason: String?) {
        exec("UPDATE sync3_record SET unapplied = ?, pending_reason = ? WHERE kind = ? AND key = ?", 4) {
            bindLong(0, if (pendingReason == null) 0L else 1L)
            bindString(1, pendingReason)
            bindString(2, kind)
            bindString(3, key)
        }
    }

    /** Descriptors of titles from the account that wait for a source this device has not installed. */
    fun worksWaitingForSource(): List<String> = namedQuery(
        "SELECT work_json FROM sync3_record WHERE kind = ? AND unapplied = 1 AND pending_reason = ? " +
            "AND work_json IS NOT NULL AND (del_value IS NULL OR del_value = 0)",
        "sync3_record",
        2,
        { bindString(0, SyncKinds.LIBRARY); bindString(1, PENDING_SOURCE_NOT_INSTALLED) },
    ) { it.string("work_json") }

    fun pendingRemoteCount(excluded: Set<String> = emptySet()): Int = namedQuery(
        "SELECT COUNT(*) AS row_count FROM sync3_record WHERE unapplied = 1 AND pending_reason IS NOT NULL" +
            notIn(excluded),
        "sync3_record",
    ) { it.long("row_count").toInt() }.first()

    /** Records waiting to be applied, not counting the kinds in [excluded] (switched off on this device). */
    fun unappliedCount(excluded: Set<String> = emptySet()): Int = namedQuery(
        "SELECT COUNT(*) AS row_count FROM sync3_record WHERE unapplied = 1${notIn(excluded)}",
        "sync3_record",
    ) { it.long("row_count").toInt() }.first()

    /**
     * ` AND kind NOT IN (...)` for [excluded], or nothing. The values are the kind constants of
     * [SyncKinds], never user input, so they are written into the statement.
     */
    private fun notIn(excluded: Set<String>): String =
        if (excluded.isEmpty()) "" else " AND kind NOT IN (${excluded.joinToString(",") { "'${it.replace("'", "")}'" }})"

    /** Keys of every non-deleted record of [kind]; used by 410 recovery. */
    fun recordKeys(kind: String): List<String> = namedQuery(
        "SELECT key FROM sync3_record WHERE kind = ?",
        "sync3_record",
        1,
        { bindString(0, kind) },
    ) { it.string("key") }

    /** Every record of [kind] that is not a tombstone. */
    fun liveRecords(kind: String): List<StoredRecord> =
        recordKeys(kind).mapNotNull { loadRecord(kind, it) }.filter { it.state.isLive }

    // ---- category uuid map ------------------------------------------------------------------

    fun categoryUuid(localId: Long): String? = namedQuery(
        "SELECT uuid FROM sync3_category WHERE local_id = ?",
        "sync3_category",
        1,
        { bindLong(0, localId) },
    ) { it.string("uuid") }.firstOrNull()

    fun categoryLocalId(uuid: String): Long? = namedQuery(
        "SELECT local_id FROM sync3_category WHERE uuid = ?",
        "sync3_category",
        1,
        { bindString(0, uuid) },
    ) { it.long("local_id") }.firstOrNull()

    fun bindCategory(localId: Long, uuid: String) {
        exec("INSERT OR REPLACE INTO sync3_category(local_id, uuid) VALUES (?, ?)", 2) {
            bindLong(0, localId)
            bindString(1, uuid)
        }
    }

    fun unbindCategory(localId: Long) {
        exec("DELETE FROM sync3_category WHERE local_id = ?", 1) { bindLong(0, localId) }
    }

    fun allCategoryBindings(): Map<Long, String> = namedQuery(
        "SELECT local_id, uuid FROM sync3_category",
        "sync3_category",
    ) { it.long("local_id") to it.string("uuid") }.toMap()

    // ---- work identity map ------------------------------------------------------------------

    fun rememberWork(mangaId: Long, recordKey: String, workHash: String?) {
        exec("INSERT OR REPLACE INTO sync3_work_identity(manga_id, record_key, work_hash) VALUES (?, ?, ?)", 3) {
            bindLong(0, mangaId)
            bindString(1, recordKey)
            bindString(2, workHash)
        }
    }

    fun forgetWork(mangaId: Long) {
        exec("DELETE FROM sync3_work_identity WHERE manga_id = ?", 1) { bindLong(0, mangaId) }
    }

    /** `recordKey to workHash` last known for a local title, used to tombstone a removed title. */
    fun rememberedWork(mangaId: Long): Pair<String, String?>? = namedQuery(
        "SELECT record_key, work_hash FROM sync3_work_identity WHERE manga_id = ?",
        "sync3_work_identity",
        1,
        { bindLong(0, mangaId) },
    ) { it.string("record_key") to it.stringOrNull("work_hash") }.firstOrNull()

    // ---- lifecycle --------------------------------------------------------------------------

    /** Drops every sync table row (logout, or signing in to a different account). */
    fun clearAll() {
        transaction {
            listOf("sync3_dirty", "sync3_field", "sync3_record", "sync3_category", "sync3_work_identity", "sync3_meta")
                .forEach { exec("DELETE FROM $it", 0, null) }
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------

    private fun exec(sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?) {
        driver.execute(null, sql, parameters, binders).value
    }

    private fun <T> query(
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
        map: (SqlCursor) -> T,
    ): List<T> = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val rows = ArrayList<T>()
            while (cursor.next().value) rows += map(cursor)
            QueryResult.Value(rows)
        },
        parameters = parameters,
        binders = binders,
    ).value

    /**
     * [query], with each row read by column name instead of by position. The names are those of
     * [sql]'s own projection, so they cannot drift from it; [table] names the rows in errors.
     */
    private fun <T> namedQuery(
        sql: String,
        table: String,
        parameters: Int = 0,
        binders: (SqlPreparedStatement.() -> Unit)? = null,
        map: (ContentCursorRow) -> T,
    ): List<T> {
        val columns = ContentRow.selectColumns(sql)
        return query(sql, parameters, binders) { cursor -> map(ContentCursorRow(cursor, table, columns)) }
    }

    private fun ContentCursorRow.readDirty() = DirtyEntry(
        kind = string("kind"),
        ref = string("ref"),
        field = string("field"),
        clock = string("clock"),
        aux = stringOrNull("aux"),
        tombKey = stringOrNull("tomb_key"),
        tombWork = stringOrNull("tomb_work"),
        tombUnit = stringOrNull("tomb_unit"),
    )

    private data class Head(
        val record: StoredRecord,
        val deletedValue: Boolean?,
        val deletedClock: String?,
        val deletedDirty: Boolean,
    ) {
        val kind get() = record.kind
        val key get() = record.key
        val rejectReason get() = record.rejectReason
        val rejectedAt get() = record.rejectedAt

        fun withFields(fields: Map<String, LwwMerge.LocalField>): StoredRecord = record.copy(
            state = LwwMerge.LocalState(
                fields = fields,
                deleted = if (deletedValue != null && deletedClock != null) {
                    LwwMerge.LocalDeleted(deletedValue, deletedClock, deletedDirty)
                } else {
                    null
                },
                cleared = record.state.cleared,
            ),
        )
    }

    private fun ContentCursorRow.readRecordHead(): Head {
        val delValue = longOrNull("del_value")
        return Head(
            record = StoredRecord(
                kind = string("kind"),
                key = string("key"),
                workHash = stringOrNull("work_hash"),
                unitRemoteId = stringOrNull("unit_remote_id"),
                workJson = stringOrNull("work_json"),
                state = LwwMerge.LocalState(cleared = stringOrNull("cleared")),
                seq = long("seq"),
                unapplied = longOrNull("unapplied") == 1L,
                pendingReason = stringOrNull("pending_reason"),
                rejectReason = stringOrNull("reject_reason"),
                rejectedAt = longOrNull("reject_at"),
            ),
            deletedValue = delValue?.let { it == 1L },
            deletedClock = stringOrNull("del_clock"),
            deletedDirty = longOrNull("del_dirty") == 1L,
        )
    }

    private companion object {
        const val META_ACK_FLOOR = "ack_floor"
        const val META_ACK_FLOOR_COMPLETE = "ack_floor_complete"
        const val DIRTY_PROJECTION =
            "kind, ref, field, clock, aux, tomb_key, tomb_work, tomb_unit"
        const val RECORD_PROJECTION =
            "kind, key, work_hash, unit_remote_id, work_json, del_value, del_clock, del_dirty, cleared, seq, " +
                "unapplied, pending_reason, reject_reason, reject_at"
        const val RECORD_SELECT = "SELECT " + RECORD_PROJECTION + " FROM sync3_record"
    }
}

/** Schema for the sync v3 tables, versioned on its own ([SchemaMigrator]). */
object SyncV3Schema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    val component: SchemaComponent = SchemaComponent(
        name = "sync_v3",
        version = version,
        create = ::createTables,
        unrecordedVersion = { driver -> if (tableExists(driver, "sync3_meta")) 1 else null },
    )

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        SchemaMigrator.migrate(driver, component)
        return QueryResult.Unit
    }

    private fun createTables(driver: SqlDriver) {
        listOf(
            "CREATE TABLE IF NOT EXISTS sync3_meta(k TEXT NOT NULL PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID",
            """
            CREATE TABLE IF NOT EXISTS sync3_record(
              kind TEXT NOT NULL, key TEXT NOT NULL,
              work_hash TEXT, unit_remote_id TEXT, work_json TEXT,
              del_value INTEGER, del_clock TEXT, del_dirty INTEGER NOT NULL DEFAULT 0,
              cleared TEXT, seq INTEGER NOT NULL DEFAULT 0,
              unapplied INTEGER NOT NULL DEFAULT 0, pending_reason TEXT,
              reject_reason TEXT, reject_at INTEGER,
              has_dirty INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(kind, key)
            ) WITHOUT ROWID
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS sync3_record_dirty ON sync3_record(has_dirty) WHERE has_dirty = 1",
            "CREATE INDEX IF NOT EXISTS sync3_record_unapplied ON sync3_record(unapplied) WHERE unapplied = 1",
            """
            CREATE TABLE IF NOT EXISTS sync3_field(
              kind TEXT NOT NULL, key TEXT NOT NULL, field TEXT NOT NULL,
              value TEXT NOT NULL, clock TEXT NOT NULL, dirty INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(kind, key, field)
            ) WITHOUT ROWID
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS sync3_dirty(
              kind TEXT NOT NULL, ref TEXT NOT NULL, field TEXT NOT NULL, clock TEXT NOT NULL,
              aux TEXT, tomb_key TEXT, tomb_work TEXT, tomb_unit TEXT,
              PRIMARY KEY(kind, ref, field)
            ) WITHOUT ROWID
            """.trimIndent(),
            "CREATE TABLE IF NOT EXISTS sync3_category(local_id INTEGER NOT NULL PRIMARY KEY, uuid TEXT NOT NULL UNIQUE)",
            """
            CREATE TABLE IF NOT EXISTS sync3_work_identity(
              manga_id INTEGER NOT NULL PRIMARY KEY, record_key TEXT NOT NULL, work_hash TEXT
            )
            """.trimIndent(),
        ).forEach { driver.execute(null, it, 0).value }
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        require(oldVersion <= newVersion) { "Sync database cannot migrate backwards" }
        create(driver)
        return QueryResult.Unit
    }
}
