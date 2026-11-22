package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.validate
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
import kotlin.time.Duration.Companion.seconds
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
 * is new, a JSON from elsewhere) and the JSON wins: the rows are rebuilt from it.
 *
 * Every write that carries the JSON goes: record its revision as pending, write the file under a
 * token of its own (a new one for every JSON written, so two copies of one library never share
 * one), then in one transaction the rows and the pairing with the new revision and token. A JSON
 * on disk that this transaction did not follow (a crash or a failed commit came between the file
 * and it) pairs with nothing: it wins, and loses nothing. The pairing advances in memory only once
 * its commit succeeded.
 *
 * The file left by such a write carries a token the rows do not record, which is what tells it
 * from the file before it (the pending revision is only logged): a file whose token and revision
 * are the pairing's was not replaced, and its rows are current.
 *
 * A restore (C3-5) journals its whole JSON beside its rows; until a whole write pairs, that JSON is
 * the newest cold part there is, and it goes in place of the file's (missing, the paired one, or
 * one whose pairing commit was cut short before the restore) unless the file's revision is at
 * least the journal's: only a whole write after the restore, which carries it, gets there.
 *
 * The rows are never cleared on behalf of a JSON that is not there: when the JSON is missing or
 * unreadable (quarantined), or the rows do not fit it, they are kept as they are for that JSON to
 * come back (a copy put back, a backup restored), and the app writes its JSON whole, marked
 * `hot-state-sql:kept:<token>` so that the next start keeps them too (a JSON that pairs, or a
 * journal, still goes first). A paired JSON its rows do not fit is copied aside before the first
 * such write replaces it ([HOT_STATE_KEPT_SUFFIX]); the quarantined file and the `.before-hot-state`
 * copy are never written.
 */

/** The prefix of the marker the JSON carries in [AppSnapshot.contentAuthorityProjectionMarkers]. */
const val HOT_STATE_MARKER_PREFIX: String = "hot-state-sql:"

private const val HOT_STATE_MARKER_V1: String = "${HOT_STATE_MARKER_PREFIX}v1:"

/** The marker of a JSON written while the rows of the pairing with its token were kept, unused. */
private const val HOT_STATE_MARKER_KEPT: String = "${HOT_STATE_MARKER_PREFIX}kept:"

/** Beside `shinsou-state.json`: its copy taken once, before its hot rows first moved into SQL. */
const val BEFORE_HOT_STATE_SUFFIX: String = ".before-hot-state"

/**
 * Beside `shinsou-state.json`: its copy taken as the hot rows it is paired with were found not to
 * fit it and were kept apart, before the first JSON marked kept replaced it (the only JSON those
 * rows go with, for a later version that can repair them).
 */
const val HOT_STATE_KEPT_SUFFIX: String = ".hot-state-kept"

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
     * Copies the file to `shinsou-state.json<suffix>` ([BEFORE_HOT_STATE_SUFFIX], §6.3.5 step 1, or
     * [HOT_STATE_KEPT_SUFFIX]) through a temporary file, so that a copy cut short is never taken for
     * it; a copy already there is left as it is unless [replace]. True when the copy is there
     * afterwards, or there is no file to copy (a fresh install: a JSON missing beside a pairing never
     * gets as far as the migration).
     */
    fun keepCopy(suffix: String, replace: Boolean): Boolean
}

/** How [openSnapshotPersistence] found the two halves and what the repository writes from now on. */
enum class HotStateOpening(val keepsHotApart: Boolean) {
    /** The rows were current and are applied over the JSON. */
    SQL_CURRENT(true),

    /** A restore had committed its rows and its JSON to the database, not yet to the file: written now. */
    RESTORE_REPLAYED(true),

    /** First start with hot rows: the JSON was copied, its rows written and the JSON marked. */
    MIGRATED(true),

    /** The JSON was newer than the rows, or the rows were not this JSON's: rebuilt from it. */
    REBUILT_FROM_JSON(true),

    /**
     * The JSON the rows go with is missing or unreadable, or the rows do not fit it: the rows are
     * kept, untouched, for that JSON to come back; whole JSON meanwhile, marked as written beside them.
     */
    ROWS_KEPT(false),

    /** The off-switch is set: the rows are read (when current) but never written. */
    OFF_SWITCH(false),

    /** No hot store could be opened or read: whole JSON, as before C3. */
    STORE_UNAVAILABLE(false),

    /** The copy, the migration or a write at opening failed: whole JSON for this run; the next start tries again. */
    MIGRATION_FAILED(false),
}

/** The snapshot to start from and where to write it. */
class OpenedSnapshot(
    val snapshot: AppSnapshot,
    val storage: SnapshotStorage,
    val opening: HotStateOpening,
)

/**
 * Pairs [loaded], the decoded snapshot JSON (null when there is none, or it could not be read and
 * was quarantined), with the hot rows of [store] (null when the store could not be opened) and
 * returns the snapshot to start from. Runs the one-time migration (§6.3.5), each of whose steps is
 * safe to repeat after a crash before the next. Blocking SQL and file work: never on the main
 * thread. With [hotStateEnabled] false (the off-switch), nothing is written to the store and every
 * later write is the whole JSON.
 */
fun openSnapshotPersistence(
    loaded: AppSnapshot?,
    file: SnapshotFile,
    store: HotStateStore?,
    hotStateEnabled: Boolean = true,
    newToken: () -> String = ::randomHotStateToken,
): OpenedSnapshot {
    val json = (loaded ?: AppSnapshot()).withHotStateMarker(null)
    val keptToken = loaded?.markerToken(HOT_STATE_MARKER_KEPT)
    val read = store?.let(::readPairing)
    if (store == null || read == null) return wholeJson(json, file, HotStateOpening.STORE_UNAVAILABLE, keptToken)
    val pairing = read.pairing
    val token = loaded?.markerToken(HOT_STATE_MARKER_V1)
    val paired = pairing?.pairsWith(token, json) == true
    val current = pairing?.takeIf { paired || it.journalGoesFirst(loaded, json) }
    // The rows need not fit the file when the JSON a restore journaled goes in place of it.
    val pairedFile = paired && current?.journaledJsonRevision == null
    return when {
        current == null && pairing.waitsForItsJson(loaded, keptToken) ->
            keepRows(json, file, checkNotNull(pairing), missing = loaded == null)
        !hotStateEnabled -> openWithOffSwitch(json, file, store, current, pairedFile)
        current != null -> withCurrentRows(json, file, store, current, newToken)
            ?: keepRows(json, file, current, missing = false, pairedFile = pairedFile)
        else -> {
            if (token != null && pairing?.pendingJsonRevision == json.revision) {
                Log.info(LOG_TAG) { "A whole write of revision ${json.revision} was cut short after its file: the file wins" }
            }
            migrate(json, file, store, first = token == null, newToken)
        }
    }
}

/** Whether the file, carrying [token] and decoded as [json], is the JSON these rows go with. */
private fun HotStatePairing.pairsWith(token: String?, json: AppSnapshot): Boolean = this.token == token && jsonRevision == json.revision

/**
 * Whether these rows (none on a fresh install) wait for their JSON: a pairing without its JSON is a
 * JSON lost, and a JSON marked kept ([keptToken]) was written beside these rows while they waited.
 */
private fun HotStatePairing?.waitsForItsJson(loaded: AppSnapshot?, keptToken: String?): Boolean =
    this != null && (loaded == null || keptToken == token)

/**
 * Whether the JSON this pairing journaled (a restore) goes in place of the file's: it is the newest
 * cold part there is unless the file ([loaded], decoded as [json]) is at least as new, which only a
 * whole write after the restore (its commit cut short) gets to.
 */
private fun HotStatePairing.journalGoesFirst(loaded: AppSnapshot?, json: AppSnapshot): Boolean =
    journaledJsonRevision?.let { journal -> loaded == null || json.revision < journal } == true

/** The pairing [store] holds (null inside when none); null when the store cannot be read. */
private class PairingRead(val pairing: HotStatePairing?)

private fun readPairing(store: HotStateStore): PairingRead? = try {
    PairingRead(store.pairing())
} catch (logged: Exception) {
    Log.warn(LOG_TAG, logged) { "The hot state could not be read; writing the whole snapshot" }
    null
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

private fun AppSnapshot.markerToken(prefix: String): String? =
    contentAuthorityProjectionMarkers.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

/**
 * Every write the whole JSON, as before C3, starting from [snapshot] (newer than [json] when rows
 * were applied to it); marked as written beside kept rows when [keptToken] is set.
 */
private fun wholeJson(
    json: AppSnapshot,
    file: SnapshotFile,
    opening: HotStateOpening,
    keptToken: String?,
    snapshot: AppSnapshot = json,
): OpenedSnapshot = OpenedSnapshot(
    snapshot = snapshot,
    storage = WholeJsonSnapshotStorage(
        persist = file::save,
        revisionOnDisk = json.revision.takeIf { snapshot !== json },
        jsonMarker = keptToken?.let { HOT_STATE_MARKER_KEPT + it },
    ),
    opening = opening,
)

/**
 * No JSON goes with the rows of [pairing] (it is [missing], or the rows do not fit it): the rows
 * stay as they are, never cleared here, for the JSON of that pairing to come back to. When the file
 * is that JSON ([pairedFile]: its rows do not fit it), it is copied aside first
 * ([HOT_STATE_KEPT_SUFFIX]), since the JSON written meanwhile, marked kept, replaces it.
 */
private fun keepRows(
    json: AppSnapshot,
    file: SnapshotFile,
    pairing: HotStatePairing,
    missing: Boolean,
    pairedFile: Boolean = false,
): OpenedSnapshot {
    Log.warn(LOG_TAG) {
        val reason = if (missing) "No readable snapshot goes with" else "The snapshot does not fit"
        "$reason the hot state of revision ${pairing.hotRevision ?: pairing.jsonRevision}; keeping it apart, unused"
    }
    if (pairedFile && !file.keepCopy(HOT_STATE_KEPT_SUFFIX, replace = true)) {
        Log.warn(LOG_TAG) { "The snapshot its hot state does not fit could not be copied aside" }
    }
    return wholeJson(json, file, HotStateOpening.ROWS_KEPT, pairing.token)
}

/**
 * The rows are read when they are [current], never written; every write is the whole JSON.
 * [pairedFile]: the file is the JSON of [current] (not one a restore journaled).
 */
private fun openWithOffSwitch(
    json: AppSnapshot,
    file: SnapshotFile,
    store: HotStateStore,
    current: HotStatePairing?,
    pairedFile: Boolean,
): OpenedSnapshot {
    Log.info(LOG_TAG) { "The hot state is switched off; writing the whole snapshot" }
    val combined = current?.let { combine(json, store, it) }
    return when {
        current == null -> wholeJson(json, file, HotStateOpening.OFF_SWITCH, keptToken = null)
        combined == null -> keepRows(json, file, current, missing = false, pairedFile = pairedFile)
        else -> wholeJson(json, file, HotStateOpening.OFF_SWITCH, keptToken = null, snapshot = combined.snapshot)
    }
}

/** The JSON (or the one a restore journaled) with the rows applied, and the rows to prune. */
private class Combined(val snapshot: AppSnapshot, val pruned: HotDelta, val journaled: Boolean)

/** [json], or the JSON [pairing] journaled, with the rows of [store]; null when they do not fit it. */
private fun combine(json: AppSnapshot, store: HotStateStore, pairing: HotStatePairing): Combined? = try {
    val journaled = pairing.journaledJsonRevision?.let { revision ->
        val decoded = store.journaledJson()?.let(AppSnapshotJson::decode)?.withHotStateMarker(null)
        checkNotNull(decoded?.takeIf { it.revision == revision }) { "The journaled snapshot of revision $revision is missing" }
    }
    val (snapshot, pruned) = withRows(journaled ?: json, store.load(), pairing.hotRevision)
    Combined(snapshot, pruned, journaled != null)
} catch (logged: Exception) {
    // Unreadable rows, or rows that do not validate against this JSON even once repaired.
    Log.warn(LOG_TAG, logged) { "The hot state did not fit the snapshot" }
    null
}

/**
 * The JSON with the current rows applied, the rows of deleted chapters pruned; a JSON a restore
 * journaled goes in place of the file's and is written to the file first. Null when the rows do
 * not fit (they are then kept, unused).
 */
private fun withCurrentRows(
    json: AppSnapshot,
    file: SnapshotFile,
    store: HotStateStore,
    pairing: HotStatePairing,
    newToken: () -> String,
): OpenedSnapshot? {
    val combined = combine(json, store, pairing) ?: return null
    val snapshot = combined.snapshot
    return try {
        val current = pairing.copy(pendingJsonRevision = null, hotRevision = snapshot.revision)
        if (!combined.pruned.isEmpty || current != pairing) store.commit(HotRows.Changed(combined.pruned), current)
        if (combined.journaled) {
            val storage = HotStateSnapshotStorage(file, store, current, newToken = newToken)
            val encoded = AppSnapshotJson.encode(snapshot.withHotStateMarker(storage.jsonMarker).validate())
            storage.write(snapshot.revision, encoded, HotRows.Unchanged)
            Log.info(LOG_TAG) { "Wrote the snapshot of an interrupted restore (revision ${snapshot.revision})" }
            OpenedSnapshot(snapshot, storage, HotStateOpening.RESTORE_REPLAYED)
        } else {
            val storage = HotStateSnapshotStorage(file, store, current, revisionOnDisk = json.revision, newToken = newToken)
            OpenedSnapshot(snapshot, storage, HotStateOpening.SQL_CURRENT)
        }
    } catch (logged: Exception) {
        // The rows were read: the snapshot is complete. Written whole and unmarked, it is migrated
        // again on the next start; never written, the rows still pair with the JSON there.
        Log.warn(LOG_TAG, logged) { "Writing the hot state at opening failed; writing the whole snapshot for now" }
        wholeJson(json, file, HotStateOpening.MIGRATION_FAILED, keptToken = null, snapshot = snapshot)
    }
}

/**
 * [json] with [rows] in place of its hot fields, and the delta that deletes the rows of chapters
 * the JSON no longer has (C3-4: a chapter deleted while its rows were written), puts back the
 * histories repaired to fit their chapter, and renumbers the lists after them.
 */
private fun withRows(json: AppSnapshot, rows: HotState, hotRevision: Long?): Pair<AppSnapshot, HotDelta> {
    val chapterById = json.chapters.associateBy { it.id }
    var historiesRepaired = false
    val histories = rows.histories.mapNotNull { history ->
        if (history.chapterId !in chapterById) return@mapNotNull null
        val lastPageRead = (rows.chapterProgress[history.chapterId] ?: ChapterProgress.DEFAULT).lastPageRead
        history.repairedFor(lastPageRead).also { if (it !== history) historiesRepaired = true }
    }
    val downloads = rows.downloads.filter { chapterById[it.chapterId]?.mangaId == it.mangaId }
    val snapshot = json.copy(
        revision = maxOf(json.revision, hotRevision ?: 0L),
        chapters = json.chapters.map { it.withProgress(rows.chapterProgress[it.id] ?: ChapterProgress.DEFAULT) },
        histories = histories,
        downloadQueue = downloads,
    ).validate()
    val historiesRewritten = historiesRepaired || histories.size != rows.histories.size
    val downloadsPruned = downloads.size != rows.downloads.size
    val pruned = HotDelta(
        removedProgress = rows.chapterProgress.keys.filterTo(HashSet()) { it !in chapterById },
        histories = if (historiesRewritten) histories.mapIndexed(::Ordered) else emptyList(),
        removedHistories = if (historiesRewritten) keysNotIn(rows.histories, histories) { it.id } else emptySet(),
        downloads = if (downloadsPruned) downloads.mapIndexed(::Ordered) else emptyList(),
        removedDownloads = if (downloadsPruned) keysNotIn(rows.downloads, downloads) { it.id } else emptySet(),
    )
    return snapshot to pruned
}

private inline fun <T, K> keysNotIn(all: List<T>, kept: List<T>, key: (T) -> K): Set<K> {
    val keptKeys = kept.mapTo(HashSet()) { key(it) }
    return all.mapNotNullTo(HashSet()) { value -> key(value).takeIf { it !in keptKeys } }
}

/**
 * This history as validate() takes it beside a chapter at [lastPageRead]: a reader page count that
 * no longer holds the page, or a locator that does not validate, is dropped (both are hints the
 * reader computes again). Rows kept apart from the JSON were not validated whole when written.
 */
private fun History.repairedFor(lastPageRead: Int): History {
    val fitted = fittingPage(lastPageRead)
    val locator = fitted.lastLocator
    return if (locator == null || runCatching { locator.validate() }.isSuccess) fitted else fitted.copy(lastLocator = null)
}

/**
 * The JSON wins (§6.3.5-6.3.6). A JSON never marked is migrated: copied once ([first]), its rows
 * written in one transaction, then marked and written whole. A marked one has its rows rebuilt and
 * is written again under a new token, so a copy of it elsewhere never pairs with these rows. A
 * crash between any two steps leaves an unmarked or unpaired JSON with its data, and the next start
 * does the same again.
 */
private fun migrate(
    json: AppSnapshot,
    file: SnapshotFile,
    store: HotStateStore,
    first: Boolean,
    newToken: () -> String,
): OpenedSnapshot {
    if (first && !file.keepCopy(BEFORE_HOT_STATE_SUFFIX, replace = false)) {
        Log.warn(LOG_TAG) { "The snapshot could not be copied before its hot state moved; writing it whole for now" }
        return wholeJson(json, file, HotStateOpening.MIGRATION_FAILED, keptToken = null)
    }
    return try {
        // A token no JSON carries: until the JSON below is written, the rows pair with nothing.
        val pairing = HotStatePairing(json.revision, newToken(), hotRevision = json.revision)
        store.commit(HotRows.All(json.hotState()), pairing)
        val storage = HotStateSnapshotStorage(file, store, pairing, newToken = newToken)
        storage.write(json.revision, AppSnapshotJson.encode(json.withHotStateMarker(storage.jsonMarker).validate()), HotRows.Unchanged)
        if (first) {
            Log.info(LOG_TAG) { "Moved the hot state of the snapshot (revision ${json.revision}) into the content database" }
        } else {
            Log.info(LOG_TAG) { "The snapshot (revision ${json.revision}) is newer than its hot state; rebuilt the hot state from it" }
        }
        OpenedSnapshot(json, storage, if (first) HotStateOpening.MIGRATED else HotStateOpening.REBUILT_FROM_JSON)
    } catch (logged: Exception) {
        Log.warn(LOG_TAG, logged) { "Moving the hot state failed; writing the whole snapshot for now" }
        wholeJson(json, file, HotStateOpening.MIGRATION_FAILED, keptToken = null)
    }
}

/**
 * The snapshot written in two halves: hot-only changes as rows of [store], the whole JSON to
 * [file] when the cold part changed, on a lifecycle flush, and at least every [JsonRefresh.every] while
 * hot rows keep changing (so the JSON an older version, or a backup of the app's files, would read
 * stays recent); the writer also schedules such a write on its own ([jsonCheckpointDelay]).
 */
class HotStateSnapshotStorage internal constructor(
    private val file: SnapshotFile,
    private val store: HotStateStore,
    /** The pairing as the store holds it: advanced only once a commit of it succeeded. */
    private var committed: HotStatePairing,
    override val revisionOnDisk: Long? = null,
    private val refresh: JsonRefresh = JsonRefresh(),
    private val newToken: () -> String = ::randomHotStateToken,
) : SnapshotStorage {
    /** How often hot changes alone rewrite the JSON, timed by [timeSource]. */
    class JsonRefresh(val every: Duration = JSON_REFRESH_INTERVAL, val timeSource: TimeSource = TimeSource.Monotonic)

    private val timeSource: TimeSource get() = refresh.timeSource
    private val refreshEvery: Duration get() = refresh.every

    override val keepsHotApart: Boolean get() = true

    /** The token of the next JSON written: a new one for every JSON. */
    private var nextToken = newToken()

    override val jsonMarker: String get() = HOT_STATE_MARKER_V1 + nextToken

    /** The pairing a host transaction wrote, until it is known to have committed. */
    private var joined: HotStatePairing? = null

    private var lastJson = timeSource.markNow()

    override fun jsonRefreshDue(): Boolean = lastJson.elapsedNow() >= refreshEvery

    override fun jsonCheckpointDelay(atOpen: Boolean): Duration = if (atOpen) CHECKPOINT_AFTER_OPEN else refreshEvery

    /** [json], when not null, carries the [jsonMarker] read just before this call. */
    override fun write(revision: Long, json: String?, hot: HotRows) {
        var paired = committed
        if (json != null) {
            // Whatever becomes of this write, no later JSON carries its token.
            val token = nextToken
            nextToken = newToken()
            // From here until the commit below, a JSON at this revision is newer than the rows.
            val pending = committed.copy(pendingJsonRevision = revision)
            store.setPairing(pending)
            committed = pending
            file.save(json)
            lastJson = timeSource.markNow()
            paired = pending.copy(jsonRevision = revision, token = token, journaledJsonRevision = null)
        }
        val next = paired.copy(pendingJsonRevision = null, hotRevision = revision)
        store.commit(hot, next)
        committed = next
    }

    override fun writeInHostTransaction(revision: Long, json: String?, hot: HotRows) {
        val next = committed.copy(
            pendingJsonRevision = null,
            hotRevision = revision,
            journaledJsonRevision = if (json != null) revision else committed.journaledJsonRevision,
        )
        store.commit(hot, next)
        if (json != null) store.setJournaledJson(json)
        joined = next
    }

    override fun hostTransactionEnded(committed: Boolean) {
        val written = joined
        joined = null
        if (committed && written != null) this.committed = written
    }

    internal companion object {
        val JSON_REFRESH_INTERVAL: Duration = 10.minutes

        /** Hot rows an earlier run wrote but no JSON has yet: written whole soon after the start. */
        val CHECKPOINT_AFTER_OPEN: Duration = 30.seconds
    }
}

private const val LOG_TAG = "Storage"
