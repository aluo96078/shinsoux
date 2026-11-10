package dev.shinsou.kmp.sync.v3

import kotlinx.serialization.json.JsonElement

/** One field of a record: an arbitrary JSON value and the HLC of its last write. */
public data class FieldState(val value: JsonElement, val clock: String)

/** The `deleted` LWW register of a record. */
public data class DeletedRegister(val value: Boolean, val clock: String)

/**
 * Merged record state as defined by shinsou_sync_service `docs/spec/sync-semantics.md` §2.
 * [cleared] is the largest clock of any deletion ever seen.
 */
public data class RecordState(
    val fields: Map<String, FieldState> = emptyMap(),
    val deleted: DeletedRegister? = null,
    val cleared: String? = null,
) {
    /** A record exists unless its deleted register currently says so. */
    public val isLive: Boolean get() = deleted?.value != true
}

/** What a client writes in one push: only the fields it changed. */
public data class PushInput(
    val fields: Map<String, JsonElement> = emptyMap(),
    val clocks: Map<String, String> = emptyMap(),
    val deleted: DeletedRegister? = null,
)

public sealed interface MergeOutcome {
    public val state: RecordState?

    public data class Applied(override val state: RecordState) : MergeOutcome
    public data class Unchanged(override val state: RecordState?) : MergeOutcome
    public data class Rejected(val code: String, override val state: RecordState?) : MergeOutcome {
        /** `rejected:<code>`, the form used in push responses and test vectors. */
        public val wire: String get() = "rejected:$code"
    }
}

/**
 * Kotlin implementation of the server's merge (sync-semantics §4). It is used by the vector tests,
 * by the in-process test server, and — through [applyRemote] — by the client when it receives
 * records from the server.
 */
public object LwwMerge {
    public const val MAX_CLOCK_SKEW_MILLIS: Long = 300_000L
    private const val MAX_FIELDS: Int = 64
    private val FIELD_NAME = Regex("^[a-z][A-Za-z0-9]{0,63}$")

    public fun compare(a: String, b: String): Int = SyncClock.parse(a).compareTo(SyncClock.parse(b))

    public fun merge(state: RecordState?, push: PushInput, nowMillis: Long): MergeOutcome {
        validate(push, nowMillis)?.let { return MergeOutcome.Rejected(it, state) }

        val base = state ?: RecordState()
        var deleted = base.deleted
        var cleared = base.cleared
        val pushedDeleted = push.deleted
        if (pushedDeleted != null) {
            if (deleted == null || compare(pushedDeleted.clock, deleted.clock) > 0) deleted = pushedDeleted
            if (pushedDeleted.value && (cleared == null || compare(pushedDeleted.clock, cleared) > 0)) {
                cleared = pushedDeleted.clock
            }
        }
        val fields = LinkedHashMap(base.fields)
        for ((name, value) in push.fields) {
            val clock = push.clocks.getValue(name)
            val existing = fields[name]
            if (existing == null || compare(clock, existing.clock) > 0) fields[name] = FieldState(value, clock)
        }
        if (cleared != null) {
            val floor = cleared
            fields.entries.removeAll { compare(it.value.clock, floor) <= 0 }
        }
        val merged = RecordState(fields, deleted, cleared)
        return if (state != null && merged == state) MergeOutcome.Unchanged(state) else if (state == null && merged == RecordState()) {
            MergeOutcome.Unchanged(null)
        } else {
            MergeOutcome.Applied(merged)
        }
    }

    /** Returns a rejection code, or null when the push is acceptable. */
    private fun validate(push: PushInput, nowMillis: Long): String? {
        if (push.fields.isEmpty() && push.clocks.isEmpty() && push.deleted == null) return "invalid_record"
        if (push.fields.size > MAX_FIELDS) return "invalid_record"
        for (name in push.fields.keys) {
            if (!FIELD_NAME.matches(name)) return "invalid_record"
            if (name !in push.clocks) return "missing_clock"
        }
        for (name in push.clocks.keys) if (name !in push.fields) return "invalid_record"
        val all = push.clocks.values + listOfNotNull(push.deleted?.clock)
        for (clock in all) {
            val parsed = runCatching { SyncClock.parse(clock) }.getOrNull() ?: return "invalid_clock"
            if (parsed.millis > nowMillis + MAX_CLOCK_SKEW_MILLIS) return "clock_skew"
        }
        return null
    }

    /** Local view of a record on the client: server-known state plus unpushed edits. */
    public data class LocalField(val value: JsonElement, val clock: String, val dirty: Boolean)

    public data class LocalDeleted(val value: Boolean, val clock: String, val dirty: Boolean)

    public data class LocalState(
        val fields: Map<String, LocalField> = emptyMap(),
        val deleted: LocalDeleted? = null,
        val cleared: String? = null,
    ) {
        public val isLive: Boolean get() = deleted?.value != true
        public fun hasDirty(): Boolean = deleted?.dirty == true || fields.values.any { it.dirty }

        public fun toRecordState(): RecordState = RecordState(
            fields.mapValues { FieldState(it.value.value, it.value.clock) },
            deleted?.let { DeletedRegister(it.value, it.clock) },
            cleared,
        )
    }

    /**
     * Client side of §6. A local field that has not been pushed survives only when its clock is
     * strictly newer than the server's; in every other case (including equal clocks) the server
     * value wins. Fields the server no longer has are dropped unless they are unpushed.
     */
    public fun applyRemote(local: LocalState?, remote: RecordState): LocalState {
        val current = local ?: LocalState()
        val fields = LinkedHashMap<String, LocalField>()
        for ((name, remoteField) in remote.fields) {
            val mine = current.fields[name]
            fields[name] = if (mine != null && mine.dirty && compare(mine.clock, remoteField.clock) > 0) {
                mine
            } else {
                LocalField(remoteField.value, remoteField.clock, dirty = false)
            }
        }
        for ((name, mine) in current.fields) if (mine.dirty && name !in fields) fields[name] = mine

        val myDeleted = current.deleted
        val remoteDeleted = remote.deleted
        val deleted = when {
            myDeleted != null && myDeleted.dirty &&
                (remoteDeleted == null || compare(myDeleted.clock, remoteDeleted.clock) > 0) -> myDeleted
            remoteDeleted != null -> LocalDeleted(remoteDeleted.value, remoteDeleted.clock, dirty = false)
            else -> null
        }
        var cleared = remote.cleared
        if (deleted != null && deleted.dirty && deleted.value && (cleared == null || compare(deleted.clock, cleared) > 0)) {
            cleared = deleted.clock
        }
        if (cleared != null) {
            val floor = cleared
            fields.entries.removeAll { compare(it.value.clock, floor) <= 0 }
        }
        return LocalState(fields, deleted, cleared)
    }
}
