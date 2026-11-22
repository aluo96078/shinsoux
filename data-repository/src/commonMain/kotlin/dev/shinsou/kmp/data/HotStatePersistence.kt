package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.persistence.ChapterProgress
import dev.shinsou.kmp.domain.persistence.HotDelta
import dev.shinsou.kmp.domain.persistence.HotRows
import dev.shinsou.kmp.domain.persistence.HotState
import dev.shinsou.kmp.domain.persistence.HotStatePairing
import dev.shinsou.kmp.domain.persistence.HotStateStore
import dev.shinsou.kmp.domain.persistence.Ordered
import dev.shinsou.kmp.domain.persistence.SnapshotStorage
import dev.shinsou.kmp.domain.persistence.WholeJsonSnapshotStorage
import dev.shinsou.kmp.domain.persistence.withProgress
import dev.shinsou.kmp.logging.Log
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/*
 * C3-3 and C3-4 (docs/architecture/0007 §6.3.5-6.3.6): the snapshot JSON together with the hot
 * rows kept apart from it in SQL.
 *
 * The JSON keeps carrying every field, hot ones included, as of its last whole write, so a version
 * that knows nothing of the hot rows still reads a complete library (D1). Which of the two is
 * current is told by the pairing the rows record: the JSON on disk carries the marker
 * `hot-state-sql:v1:<token>` and the rows go with it when they record the same token and the JSON's
 * revision as theirs. Anything else (an older version wrote the JSON after this one, the database
 * is new or lost, a JSON from elsewhere) and the JSON wins: the rows are rebuilt from it.
 *
 * Every write that carries the JSON goes: record its revision as pending, write the file, then in
 * one transaction the rows and the pairing with the new revision. A JSON on disk at the pending
 * revision is therefore newer than the rows (a crash came between the file and the transaction):
 * it wins, and loses nothing.
 */

/** The prefix of the marker the JSON carries in [AppSnapshot.contentAuthorityProjectionMarkers]. */
const val HOT_STATE_MARKER_PREFIX: String = "hot-state-sql:"

private const val HOT_STATE_MARKER_V1: String = "${HOT_STATE_MARKER_PREFIX}v1:"

/** Beside `shinsou-state.json`: its copy taken once, before its hot rows first moved into SQL. */
const val BEFORE_HOT_STATE_SUFFIX: String = ".before-hot-state"

/**
 * The emergency off-switch of C3, beside `shinsou-state.json` on every platform: while a file of
 * this name exists, the repository writes the whole JSON every time, as before C3, and never
 * writes the hot rows (it still reads them once, when they are current). Removing it switches back.
 */
const val WHOLE_JSON_SWITCH_FILE: String = "shinsou-state.whole-json"

/** The platform's snapshot JSON file (`shinsou-state.json`). */
interface SnapshotFile {
    /** Replaces the file with [json], atomically. */
    fun save(json: String)

    /**
     * Copies the file to `shinsou-state.json.before-hot-state` unless that copy is already there,
     * through a temporary file so that a copy cut short is never taken for it (§6.3.5 step 1).
     * True when the copy is there afterwards, or there is no file to copy.
     */
    fun keepCopyBeforeHotState(): Boolean
}

/** How [openSnapshotPersistence] found the two halves and what the repository writes from now on. */
enum class HotStateOpening(val keepsHotApart: Boolean) {
    /** The rows were current and are applied over the JSON. */
    SQL_CURRENT(true),

    /** First start with hot rows: the JSON was copied, its rows written and the JSON marked. */
    MIGRATED(true),

    /** The JSON was newer than the rows, or the rows were not this JSON's: rebuilt from it. */
    REBUILT_FROM_JSON(true),

    /** The off-switch is set: the rows are read (when current) but never written. */
    OFF_SWITCH(false),

    /** No hot store could be opened or read: whole JSON, as before C3. */
    STORE_UNAVAILABLE(false),

    /** The copy or the migration failed: whole JSON for this run; the next start tries again. */
    MIGRATION_FAILED(false),
}

/** The snapshot to start from and where to write it. */
class OpenedSnapshot(
    val snapshot: AppSnapshot,
    val storage: SnapshotStorage,
    val opening: HotStateOpening,
)

/**
 * Pairs [loaded], the decoded snapshot JSON, with the hot rows of [store] (null when the store could
 * not be opened) and returns the snapshot to start from. Runs the one-time migration (§6.3.5), each
 * of whose steps is safe to repeat after a crash before the next. Blocking SQL and file work: never
 * on the main thread. With [hotStateEnabled] false (the off-switch), nothing is written to the
 * store and every later write is the whole JSON.
 */
fun openSnapshotPersistence(
    loaded: AppSnapshot,
    file: SnapshotFile,
    store: HotStateStore?,
    hotStateEnabled: Boolean = true,
    newToken: () -> String = ::randomHotStateToken,
): OpenedSnapshot {
    val json = loaded.withHotStateMarker(null)
    val pairing = try {
        store?.pairing()
    } catch (logged: Exception) {
        Log.warn(LOG_TAG, logged) { "The hot state could not be read; writing the whole snapshot" }
        return OpenedSnapshot(json, WholeJsonSnapshotStorage(file::save), HotStateOpening.STORE_UNAVAILABLE)
    }
    val token = loaded.hotStateToken()
    val paired = pairing != null && pairing.token == token && pairing.jsonRevision == loaded.revision
    return when {
        store == null -> OpenedSnapshot(json, WholeJsonSnapshotStorage(file::save), HotStateOpening.STORE_UNAVAILABLE)
        !hotStateEnabled -> openWithOffSwitch(json, file, store, pairing.takeIf { paired })
        else -> (if (paired) withCurrentRows(json, file, store, checkNotNull(pairing)) else null)
            ?: migrate(json, file, store, token, newToken)
    }
}

/** A random token for a pairing: 128 bits as hex. */
fun randomHotStateToken(): String =
    Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** This snapshot with [marker] as its only hot-state marker (none when null). */
internal fun AppSnapshot.withHotStateMarker(marker: String?): AppSnapshot {
    val others = contentAuthorityProjectionMarkers.filterNotTo(LinkedHashSet()) { it.startsWith(HOT_STATE_MARKER_PREFIX) }
    val markers = if (marker == null) others else others + marker
    return if (markers == contentAuthorityProjectionMarkers) this else copy(contentAuthorityProjectionMarkers = markers)
}

private fun AppSnapshot.hotStateToken(): String? =
    contentAuthorityProjectionMarkers.firstOrNull { it.startsWith(HOT_STATE_MARKER_V1) }?.removePrefix(HOT_STATE_MARKER_V1)

/** The rows are read when they are current, never written; every write is the whole JSON. */
private fun openWithOffSwitch(json: AppSnapshot, file: SnapshotFile, store: HotStateStore, current: HotStatePairing?): OpenedSnapshot {
    val combined = current?.let { readCombined(json, store, it.hotRevision)?.first }
    Log.info(LOG_TAG) { "The hot state is switched off; writing the whole snapshot" }
    return OpenedSnapshot(
        snapshot = combined ?: json,
        storage = WholeJsonSnapshotStorage(file::save, revisionOnDisk = json.revision),
        opening = HotStateOpening.OFF_SWITCH,
    )
}

/** The JSON with the current rows applied, the rows of deleted chapters pruned; null when they do not fit it. */
private fun withCurrentRows(json: AppSnapshot, file: SnapshotFile, store: HotStateStore, pairing: HotStatePairing): OpenedSnapshot? {
    val (snapshot, pruned) = readCombined(json, store, pairing.hotRevision) ?: return null
    return try {
        val revision = snapshot.revision
        val current = pairing.copy(pendingJsonRevision = null, hotRevision = revision)
        if (!pruned.isEmpty || current != pairing) store.commit(HotRows.Changed(pruned), current)
        OpenedSnapshot(snapshot, HotStateSnapshotStorage(file, store, current, revisionOnDisk = json.revision), HotStateOpening.SQL_CURRENT)
    } catch (logged: Exception) {
        Log.warn(LOG_TAG, logged) { "Pruning the hot state failed; rebuilding it from the snapshot" }
        null
    }
}

private fun readCombined(json: AppSnapshot, store: HotStateStore, hotRevision: Long?): Pair<AppSnapshot, HotDelta>? = try {
    withRows(json, store.load(), hotRevision)
} catch (logged: Exception) {
    // Unreadable rows, or rows that do not validate against this JSON: the JSON is used whole.
    Log.warn(LOG_TAG, logged) { "The hot state did not fit the snapshot; using the snapshot's own" }
    null
}

/**
 * [json] with [rows] in place of its hot fields, and the delta that deletes the rows of chapters
 * the JSON no longer has (C3-4: a chapter deleted while its rows were written) and renumbers the
 * lists after them.
 */
private fun withRows(json: AppSnapshot, rows: HotState, hotRevision: Long?): Pair<AppSnapshot, HotDelta> {
    val chapterById = json.chapters.associateBy { it.id }
    val histories = rows.histories.filter { it.chapterId in chapterById }
    val downloads = rows.downloads.filter { chapterById[it.chapterId]?.mangaId == it.mangaId }
    val snapshot = json.copy(
        revision = maxOf(json.revision, hotRevision ?: 0L),
        chapters = json.chapters.map { it.withProgress(rows.chapterProgress[it.id] ?: ChapterProgress.DEFAULT) },
        histories = histories,
        downloadQueue = downloads,
    ).validate()
    val historiesPruned = histories.size != rows.histories.size
    val downloadsPruned = downloads.size != rows.downloads.size
    val pruned = HotDelta(
        removedProgress = rows.chapterProgress.keys.filterTo(HashSet()) { it !in chapterById },
        histories = if (historiesPruned) histories.mapIndexed(::Ordered) else emptyList(),
        removedHistories = if (historiesPruned) (rows.histories - histories.toSet()).mapTo(HashSet()) { it.id } else emptySet(),
        downloads = if (downloadsPruned) downloads.mapIndexed(::Ordered) else emptyList(),
        removedDownloads = if (downloadsPruned) (rows.downloads - downloads.toSet()).mapTo(HashSet()) { it.id } else emptySet(),
    )
    return snapshot to pruned
}

/**
 * The JSON wins (§6.3.5-6.3.6). A JSON never marked is migrated: copied once, its rows written with
 * a new token in one transaction, then marked and written whole. A marked one only has its rows
 * rebuilt, under its own token. A crash between any two steps leaves an unmarked or unpaired JSON
 * with its data, and the next start does the same again.
 */
private fun migrate(
    json: AppSnapshot,
    file: SnapshotFile,
    store: HotStateStore,
    token: String?,
    newToken: () -> String,
): OpenedSnapshot {
    val first = token == null
    if (first && !file.keepCopyBeforeHotState()) {
        Log.warn(LOG_TAG) { "The snapshot could not be copied before its hot state moved; writing it whole for now" }
        return OpenedSnapshot(json, WholeJsonSnapshotStorage(file::save), HotStateOpening.MIGRATION_FAILED)
    }
    return try {
        val pairing = HotStatePairing(json.revision, token ?: newToken(), hotRevision = json.revision)
        store.commit(HotRows.All(json.hotState()), pairing)
        val storage = HotStateSnapshotStorage(file, store, pairing, revisionOnDisk = null)
        if (first) {
            storage.write(json.revision, AppSnapshotJson.encode(json.withHotStateMarker(storage.jsonMarker).validate()), HotRows.Unchanged)
            Log.info(LOG_TAG) { "Moved the hot state of the snapshot (revision ${json.revision}) into the content database" }
        } else {
            Log.info(LOG_TAG) { "The snapshot (revision ${json.revision}) is newer than its hot state; rebuilt the hot state from it" }
        }
        OpenedSnapshot(json, storage, if (first) HotStateOpening.MIGRATED else HotStateOpening.REBUILT_FROM_JSON)
    } catch (logged: Exception) {
        Log.warn(LOG_TAG, logged) { "Moving the hot state failed; writing the whole snapshot for now" }
        OpenedSnapshot(json, WholeJsonSnapshotStorage(file::save), HotStateOpening.MIGRATION_FAILED)
    }
}

/**
 * The snapshot written in two halves: hot-only changes as rows of [store], the whole JSON to
 * [file] when the cold part changed, on a lifecycle flush, and at least every [refreshEvery] while
 * hot rows keep changing (so the JSON an older version would read stays recent).
 */
class HotStateSnapshotStorage internal constructor(
    private val file: SnapshotFile,
    private val store: HotStateStore,
    /** The pairing as the store holds it. */
    private var pairing: HotStatePairing,
    override val revisionOnDisk: Long? = null,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val refreshEvery: Duration = JSON_REFRESH_INTERVAL,
) : SnapshotStorage {
    override val keepsHotApart: Boolean get() = true

    override val jsonMarker: String = HOT_STATE_MARKER_V1 + pairing.token

    private var lastJson = timeSource.markNow()

    override fun jsonRefreshDue(): Boolean = lastJson.elapsedNow() >= refreshEvery

    override fun write(revision: Long, json: String?, hot: HotRows) {
        if (json != null) {
            // From here until the commit below, a JSON at this revision is newer than the rows.
            store.setPairing(pairing.copy(pendingJsonRevision = revision))
            file.save(json)
            pairing = pairing.copy(jsonRevision = revision)
            lastJson = timeSource.markNow()
        }
        val next = pairing.copy(pendingJsonRevision = null, hotRevision = revision)
        store.commit(hot, next)
        pairing = next
    }

    internal companion object {
        val JSON_REFRESH_INTERVAL: Duration = 10.minutes
    }
}

private const val LOG_TAG = "Storage"
