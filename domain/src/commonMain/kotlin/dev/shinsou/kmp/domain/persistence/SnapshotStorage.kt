package dev.shinsou.kmp.domain.persistence

/**
 * C3 (docs/architecture/0007 §6.3.3): where the repository writes the app snapshot. The snapshot
 * JSON ([writeCold]) is the platform's file; the hot rows ([writeHot]) may be kept apart from it
 * (a [HotStateStore]), so a page turn need not rewrite the whole library.
 *
 * Calls come from one writer at a time, never on the UI thread.
 */
public interface SnapshotStorage {
    /** Replaces the snapshot JSON with [json], the encoded snapshot of [revision]. */
    public fun writeCold(json: String, revision: Long)

    /** Writes the hot rows that changed since the last [writeHot]. */
    public fun writeHot(delta: HotDelta)

    /** The hot rows kept apart from the JSON; null when the JSON holds them (it is the authority). */
    public fun loadHot(): HotState?

    /** The JSON revision the hot rows were last paired with; null when there are none apart. */
    public val pairedRevision: Long?
}

/**
 * The storage every platform used before C3: each write replaces the whole JSON, hot fields
 * included, through [persist]; nothing is kept apart from it.
 */
public class WholeJsonSnapshotStorage(private val persist: (String) -> Unit) : SnapshotStorage {
    override fun writeCold(json: String, revision: Long): Unit = persist(json)

    override fun writeHot(delta: HotDelta): Unit = Unit

    override fun loadHot(): HotState? = null

    override val pairedRevision: Long? get() = null
}
