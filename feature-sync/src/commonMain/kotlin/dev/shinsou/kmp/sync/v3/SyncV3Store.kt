package dev.shinsou.kmp.sync.v3

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import dev.shinsou.kmp.content.schema.SchemaComponent
import dev.shinsou.kmp.content.schema.SchemaMigrator
import dev.shinsou.kmp.content.schema.tableExists
import dev.shinsou.kmp.content.sql.ContentCursorRow
import dev.shinsou.kmp.content.sql.ContentRow
import dev.shinsou.kmp.content.sql.queryStatement
import dev.shinsou.kmp.content.sql.queryUncached
import dev.shinsou.kmp.sync.v3.sql.Sync3_record
import dev.shinsou.kmp.sync.v3.sql.SyncV3Database
import dev.shinsou.kmp.sync.v3.sql.SyncV3Queries
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

    /** The fixed statements (SyncV3.sq); those whose text depends on the call are written below. */
    private val queries = SyncV3Queries(driver)

    init {
        SyncV3Schema.create(driver).value
    }

    fun <T> transaction(block: () -> T): T = transactions.transactionWithResult(noEnclosing = false) { block() }

    // ---- meta -------------------------------------------------------------------------------

    fun meta(key: String): String? = queries.meta(key).executeAsList().firstOrNull()

    fun setMeta(key: String, value: String?) {
        if (value == null) queries.deleteMeta(key).value else queries.putMeta(key, value).value
    }

    // ---- dirty set --------------------------------------------------------------------------

    fun mark(entries: Collection<DirtyEntry>) {
        if (entries.isEmpty()) return
        transaction {
            entries.forEach { e ->
                queries.markDirty(e.kind, e.ref, e.field, e.clock, e.aux, e.tombKey, e.tombWork, e.tombUnit).value
            }
        }
    }

    /** Distinct `(kind, ref)` pairs with unpromoted dirty fields. */
    fun dirtyRefs(limit: Int): List<Pair<String, String>> =
        queries.dirtyRefs(limit.toLong()) { kind, ref -> kind to ref }.executeAsList()

    fun dirtyFor(kind: String, ref: String): List<DirtyEntry> = queries.dirtyFor(kind, ref, ::DirtyEntry).executeAsList()

    fun allDirty(): List<DirtyEntry> = queries.allDirty(::DirtyEntry).executeAsList()

    fun hasDirty(kind: String, ref: String): Boolean = queries.hasDirty(kind, ref).executeAsList().isNotEmpty()

    /** Removes exactly the observed rows; a row re-marked with a newer clock stays. */
    fun deleteDirty(entries: Collection<DirtyEntry>) {
        if (entries.isEmpty()) return
        transaction {
            entries.forEach { e -> queries.deleteDirty(e.kind, e.ref, e.field, e.clock).value }
        }
    }

    /** Moves [entry] to [clock] unless it was marked again since it was read. */
    fun rebaseDirtyClock(entry: DirtyEntry, clock: String) {
        queries.rebaseDirtyClock(clock = clock, kind = entry.kind, ref = entry.ref, field = entry.field, readClock = entry.clock).value
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
        queries.removalClocks { kind, key, deleted, cleared -> Triple(kind, key, deleted to cleared) }
            .executeAsList()
            .filter { (_, _, clocks) -> matches(clocks.first, clocks.second) }
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
    fun liveWorkJson(): List<String> = queries.liveWorkJson { _, workJson ->
        // Never null: the statement keeps only rows that have one.
        requireNotNull(workJson) { "sync3_record.work_json is null" }
    }.executeAsList()

    fun loadRecord(kind: String, key: String): StoredRecord? {
        val head = queries.record(kind, key).executeAsList().firstOrNull()?.toHead() ?: return null
        return head.withFields(loadFields(kind, key))
    }

    private fun loadFields(kind: String, key: String): Map<String, LwwMerge.LocalField> =
        queries.fields(kind, key) { field, value, clock, dirty ->
            field to LwwMerge.LocalField(
                value = Json.parseToJsonElement(value),
                clock = clock,
                dirty = dirty == 1L,
            )
        }.executeAsList().toMap()

    fun saveRecord(record: StoredRecord) {
        transaction {
            val hasDirty = record.state.hasDirty()
            queries.putRecord(
                kind = record.kind,
                key = record.key,
                work_hash = record.workHash,
                unit_remote_id = record.unitRemoteId,
                work_json = record.workJson,
                del_value = record.state.deleted?.let { if (it.value) 1L else 0L },
                del_clock = record.state.deleted?.clock,
                del_dirty = if (record.state.deleted?.dirty == true) 1L else 0L,
                cleared = record.state.cleared,
                seq = record.seq,
                unapplied = if (record.unapplied) 1L else 0L,
                pending_reason = record.pendingReason,
                reject_reason = record.rejectReason,
                reject_at = record.rejectedAt,
                has_dirty = if (hasDirty) 1L else 0L,
            ).value
            queries.deleteFields(record.kind, record.key).value
            record.state.fields.forEach { (name, field) ->
                queries.insertField(
                    record.kind,
                    record.key,
                    name,
                    field.value.toString(),
                    field.clock,
                    if (field.dirty) 1L else 0L,
                ).value
            }
        }
    }

    fun deleteRecord(kind: String, key: String) {
        transaction {
            queries.deleteFields(kind, key).value
            queries.deleteRecord(kind, key).value
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

    fun lastRejection(): String? =
        queries.lastRejection { kind, reason -> "$kind: $reason" }.executeAsList().firstOrNull()

    /** Unapplied records of one kind with `key > afterKey`, in key order (pending ones stay unapplied). */
    fun unappliedRecords(kind: String, afterKey: String, limit: Int): List<StoredRecord> {
        val heads = queries.unappliedAfter(kind, afterKey, limit.toLong()).executeAsList().map { it.toHead() }
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
        val heads = queries.pendingOfKind(PENDING_UNIT_UNKNOWN, SyncKinds.HISTORY).executeAsList().map { it.toHead() }
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
        queries.setApplied(if (pendingReason == null) 0L else 1L, pendingReason, kind, key).value
    }

    /** Descriptors of titles from the account that wait for a source this device has not installed. */
    fun worksWaitingForSource(): List<String> =
        queries.worksWaitingForSource(SyncKinds.LIBRARY, PENDING_SOURCE_NOT_INSTALLED).executeAsList()

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
        if (excluded.isEmpty()) "" else "$NOT_IN_KINDS(${excluded.joinToString(",") { "'${it.replace("'", "")}'" }})"

    /** Keys of every non-deleted record of [kind]; used by 410 recovery. */
    fun recordKeys(kind: String): List<String> = queries.recordKeys(kind).executeAsList()

    /** Every record of [kind] that is not a tombstone. */
    fun liveRecords(kind: String): List<StoredRecord> =
        recordKeys(kind).mapNotNull { loadRecord(kind, it) }.filter { it.state.isLive }

    // ---- category uuid map ------------------------------------------------------------------

    fun categoryUuid(localId: Long): String? = queries.categoryUuid(localId).executeAsList().firstOrNull()

    fun categoryLocalId(uuid: String): Long? = queries.categoryLocalId(uuid).executeAsList().firstOrNull()

    fun bindCategory(localId: Long, uuid: String) {
        queries.bindCategory(localId, uuid).value
    }

    fun unbindCategory(localId: Long) {
        queries.unbindCategory(localId).value
    }

    fun allCategoryBindings(): Map<Long, String> =
        queries.categoryBindings { localId, uuid -> localId to uuid }.executeAsList().toMap()

    // ---- work identity map ------------------------------------------------------------------

    fun rememberWork(mangaId: Long, recordKey: String, workHash: String?) {
        queries.rememberWork(mangaId, recordKey, workHash).value
    }

    fun forgetWork(mangaId: Long) {
        queries.forgetWork(mangaId).value
    }

    /** `recordKey to workHash` last known for a local title, used to tombstone a removed title. */
    fun rememberedWork(mangaId: Long): Pair<String, String?>? =
        queries.rememberedWork(mangaId) { recordKey, workHash -> recordKey to workHash }.executeAsList().firstOrNull()

    // ---- lifecycle --------------------------------------------------------------------------

    /** Drops every sync table row (logout, or signing in to a different account). */
    fun clearAll() {
        transaction {
            queries.clearDirty().value
            queries.clearFields().value
            queries.clearRecords().value
            queries.clearCategories().value
            queries.clearWorkIdentities().value
            queries.clearMeta().value
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------

    private fun <T> query(
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
        map: (SqlCursor) -> T,
    ): List<T> {
        val mapper: (SqlCursor) -> QueryResult<List<T>> = { cursor ->
            val rows = ArrayList<T>()
            while (cursor.next().value) rows += map(cursor)
            QueryResult.Value(rows)
        }
        // A `NOT IN (kinds)` list is written into the text, one text per set of switched-off kinds:
        // those run uncached rather than fill the shared identifier registry with combinations.
        return if (NOT_IN_KINDS in sql) {
            driver.queryUncached(sql, mapper, parameters, binders).value
        } else {
            driver.queryStatement(sql, mapper, parameters, binders).value
        }
    }

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

    private fun Sync3_record.toHead(): Head = Head(
        record = StoredRecord(
            kind = kind,
            key = key,
            workHash = work_hash,
            unitRemoteId = unit_remote_id,
            workJson = work_json,
            state = LwwMerge.LocalState(cleared = cleared),
            seq = seq,
            unapplied = unapplied == 1L,
            pendingReason = pending_reason,
            rejectReason = reject_reason,
            rejectedAt = reject_at,
        ),
        deletedValue = del_value?.let { it == 1L },
        deletedClock = del_clock,
        deletedDirty = del_dirty == 1L,
    )

    /** [toHead] for the statements written here, read by column name. */
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
        const val RECORD_PROJECTION =
            "kind, key, work_hash, unit_remote_id, work_json, del_value, del_clock, del_dirty, cleared, seq, " +
                "unapplied, pending_reason, reject_reason, reject_at"
        const val RECORD_SELECT = "SELECT " + RECORD_PROJECTION + " FROM sync3_record"
    }
}

/** Schema for the sync v3 tables, versioned on its own ([SchemaMigrator]). */
public object SyncV3Schema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    public val component: SchemaComponent = SchemaComponent(
        name = "sync_v3",
        version = version,
        // The generated DDL (SyncV3.sq); its own Schema version is not used.
        create = { driver -> SyncV3Database.Schema.create(driver).value },
        unrecordedVersion = { driver -> if (tableExists(driver, "sync3_meta")) 1 else null },
    )

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        SchemaMigrator.migrate(driver, component)
        return QueryResult.Unit
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

/** Starts the `kind` exclusion list of [SyncV3Store.notIn]. */
private const val NOT_IN_KINDS = " AND kind NOT IN "
