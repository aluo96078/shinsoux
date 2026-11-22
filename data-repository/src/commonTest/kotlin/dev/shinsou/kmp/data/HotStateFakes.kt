package dev.shinsou.kmp.data

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadState
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.persistence.HotDelta
import dev.shinsou.kmp.domain.persistence.HotRows
import dev.shinsou.kmp.domain.persistence.HotState
import dev.shinsou.kmp.domain.persistence.HotStatePairing
import dev.shinsou.kmp.domain.persistence.HotStateStore
import dev.shinsou.kmp.domain.persistence.asDelta

/** A process death: an [Error], so no `catch (Exception)` on the way takes it for a failure. */
internal class Crash : Error("simulated process death")

/** Counts the durable steps of the fakes below and dies at step [at] (0-based), if set. */
internal class Crashes(var at: Int? = null) {
    var steps = 0
        private set

    fun step() {
        val step = steps++
        if (step == at) throw Crash()
    }
}

/** The hot tables, each call durable (or not at all) like one SQL transaction. */
internal class FakeHotStateStore(private val crashes: Crashes = Crashes()) : HotStateStore {
    /** What the tables hold: the rows, the pairing and the journaled JSON. */
    private data class Tables(val rows: HotState, val meta: HotStatePairing?, val journal: String?)

    private var tables = Tables(HotState.EMPTY, null, null)

    var rows: HotState
        get() = tables.rows
        set(value) {
            tables = tables.copy(rows = value)
        }

    var meta: HotStatePairing?
        get() = tables.meta
        set(value) {
            tables = tables.copy(meta = value)
        }

    val journal: String? get() = tables.journal

    var calls = 0
        private set

    /** Thrown by every call while set: an unreadable database. */
    var failure: Exception? = null

    /** Thrown by the next [commit] only, once: a transaction that fails while the process lives. */
    var failNextCommit: Exception? = null

    /** A host transaction open on the store: its writes, kept apart until [hostTransaction] returns. */
    private var host: Tables? = null

    private fun step() {
        failure?.let { throw it }
        calls++
        if (host == null) crashes.step()
    }

    private val current: Tables get() = host ?: tables

    override fun load(): HotState = step().let { current.rows }

    override fun apply(delta: HotDelta) = write { it.copy(rows = it.rows.applying(delta)) }

    override fun clear() = write { Tables(HotState.EMPTY, null, null) }

    override fun pairing(): HotStatePairing? = step().let { current.meta }

    override fun setPairing(pairing: HotStatePairing?) = write { it.withPairing(pairing) }

    override fun journaledJson(): String? = step().let { current.journal }

    override fun setJournaledJson(json: String?) = write { it.copy(journal = json) }

    override fun commit(rows: HotRows, pairing: HotStatePairing) {
        failNextCommit?.let {
            failNextCommit = null
            throw it
        }
        write { tables ->
            val next = when (rows) {
                HotRows.Unchanged -> tables.rows
                is HotRows.Changed -> tables.rows.applying(rows.delta)
                is HotRows.All -> HotState.EMPTY.applying(rows.state.asDelta())
            }
            (if (rows is HotRows.All) Tables(next, null, null) else tables.copy(rows = next)).withPairing(pairing)
        }
    }

    private fun Tables.withPairing(pairing: HotStatePairing?): Tables =
        copy(meta = pairing, journal = journal.takeIf { pairing?.journaledJsonRevision != null })

    /** What another process would find in these tables now: a store of its own, with no crashes. */
    fun copyOf(): FakeHotStateStore = FakeHotStateStore().also { it.tables = tables }

    /** Runs [block] as a host transaction: its writes land only when it returns. */
    suspend fun <T> hostTransaction(block: suspend () -> T): T {
        host = tables
        try {
            val result = block()
            tables = checkNotNull(host)
            return result
        } finally {
            host = null
        }
    }

    private fun write(change: (Tables) -> Tables) {
        step()
        val open = host
        if (open != null) host = change(open) else tables = change(tables)
    }
}

/** `shinsou-state.json` and its copies beside it (`.before-hot-state`, `.hot-state-kept`), by suffix. */
internal class FakeSnapshotFile(var json: String? = null, private val crashes: Crashes = Crashes()) : SnapshotFile {
    val copies = mutableMapOf<String, String>()

    /** The `.before-hot-state` copy. */
    val copy: String? get() = copies[BEFORE_HOT_STATE_SUFFIX]
    var saves = 0
        private set
    var copyFails = false

    override fun save(json: String) {
        crashes.step()
        this.json = json
        saves++
    }

    override fun keepCopy(suffix: String, replace: Boolean): Boolean {
        val source = json
        if ((!replace && suffix in copies) || source == null) return true
        crashes.step()
        if (!copyFails) copies[suffix] = source
        return !copyFails
    }

    /** What a platform's load() gives: the decoded file, or null when there is none. */
    fun read(): AppSnapshot? = json?.let(AppSnapshotJson::decode)

    /** The decoded file, which must be there. */
    fun load(): AppSnapshot = checkNotNull(read()) { "No snapshot file" }
}

/** A small library whose every hot collection has rows. */
internal fun hotLibrary(revision: Long = 7): AppSnapshot {
    val mangas = (1L..3L).map { Manga(id = it, source = 1, url = "/m$it", title = "M$it", favorite = true) }
    val chapters = (1L..12L).map { id ->
        Chapter(
            id = id,
            mangaId = (id - 1) / 4 + 1,
            url = "/c$id",
            name = "C$id",
            read = id % 3 == 0L,
            bookmark = id == 5L,
            lastPageRead = if (id % 2 == 0L) id.toInt() else 0,
        )
    }
    val histories = listOf(4L, 9L, 1L, 6L).mapIndexed { index, chapterId ->
        History(id = index + 1L, chapterId = chapterId, lastRead = 1_000L - index, timeRead = 30L * index)
    }
    val downloads = listOf(2L, 7L, 11L).mapIndexed { index, chapterId ->
        val chapter = chapters.single { it.id == chapterId }
        DownloadQueueItem(
            id = DownloadQueueItem.id(chapter.mangaId, chapter.id),
            mangaId = chapter.mangaId,
            chapterId = chapter.id,
            state = if (index == 0) DownloadState.DOWNLOADED else DownloadState.QUEUED,
            progress = if (index == 0) 1.0 else 0.0,
            downloadedPages = if (index == 0) 9 else 0,
            totalPages = 9,
            position = 2 - index,
        )
    }
    return AppSnapshot(revision = revision, mangas = mangas, chapters = chapters, histories = histories, downloadQueue = downloads).validate()
}
