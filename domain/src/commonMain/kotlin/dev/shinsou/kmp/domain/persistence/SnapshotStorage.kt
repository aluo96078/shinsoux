package dev.shinsou.kmp.domain.persistence

/**
 * C3 (docs/architecture/0007 §6.3.3): where the repository writes the app snapshot. The snapshot
 * JSON is the platform's file; the hot rows may be kept apart from it (a [HotStateStore]), so a
 * page turn need not rewrite the whole library.
 *
 * Calls come from one writer at a time, never on the UI thread.
 */
public interface SnapshotStorage {
    /**
     * Whether the hot rows are kept apart from the JSON: a change of hot rows only is then written
     * as [HotRows] alone. When false, every write carries the whole JSON.
     */
    public val keepsHotApart: Boolean get() = false

    /** The marker the JSON must carry among its projection markers (§6.3.5); null for none. */
    public val jsonMarker: String? get() = null

    /**
     * The revision of the snapshot whose JSON is on disk, when it is not the snapshot the
     * repository starts from (whose hot rows were read from apart); null when it is that one.
     */
    public val revisionOnDisk: Long? get() = null

    /** Whether a change of hot rows only should still rewrite the JSON now, to keep its copy recent. */
    public fun jsonRefreshDue(): Boolean = false

    /**
     * Writes the snapshot of [revision]: its whole [json] when not null (always, when the hot rows
     * are not kept apart), then [hot], the hot rows that changed since the last write that
     * succeeded. A write that throws may have written part of it; the next one is complete.
     */
    public fun write(revision: Long, json: String?, hot: HotRows)
}

/** The hot rows one [SnapshotStorage.write] brings up to date. */
public sealed interface HotRows {
    /** None changed. */
    public data object Unchanged : HotRows

    /** The rows that changed since the last write. */
    public data class Changed(val delta: HotDelta) : HotRows

    /** Every row, in place of whatever is there (after a write that may have failed half-way). */
    public data class All(val state: HotState) : HotRows
}

/**
 * The storage every platform used before C3, and the off-switch of C3: each write replaces the
 * whole JSON, hot fields included, through [persist]; nothing is kept apart from it.
 */
public class WholeJsonSnapshotStorage(
    private val persist: (String) -> Unit,
    override val revisionOnDisk: Long? = null,
) : SnapshotStorage {
    override fun write(revision: Long, json: String?, hot: HotRows) {
        if (json != null) persist(json)
    }
}
