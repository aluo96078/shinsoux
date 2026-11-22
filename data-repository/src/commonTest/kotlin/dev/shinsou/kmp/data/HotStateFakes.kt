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
    var rows: HotState = HotState.EMPTY
    var meta: HotStatePairing? = null
    var calls = 0
        private set

    /** Thrown by every call while set: an unreadable database. */
    var failure: Exception? = null

    /** A host transaction open on the store: its writes, kept apart until [commitHost]. */
    private var host: Pair<HotState, HotStatePairing?>? = null

    private fun step() {
        failure?.let { throw it }
        calls++
        if (host == null) crashes.step()
    }

    override fun load(): HotState = step().let { host?.first ?: rows }

    override fun apply(delta: HotDelta) = write { state, pairing -> state.applying(delta) to pairing }

    override fun clear() = write { _, _ -> HotState.EMPTY to null }

    override fun pairing(): HotStatePairing? = step().let { host?.second ?: meta }

    override fun setPairing(pairing: HotStatePairing?) = write { state, _ -> state to pairing }

    override fun commit(rows: HotRows, pairing: HotStatePairing) = write { state, _ ->
        val next = when (rows) {
            HotRows.Unchanged -> state
            is HotRows.Changed -> state.applying(rows.delta)
            is HotRows.All -> HotState.EMPTY.applying(rows.state.asDelta())
        }
        next to pairing
    }

    /** Runs [block] as a host transaction: its writes land only when it returns. */
    suspend fun <T> hostTransaction(block: suspend () -> T): T {
        host = rows to meta
        try {
            val result = block()
            val (state, pairing) = checkNotNull(host)
            rows = state
            meta = pairing
            return result
        } finally {
            host = null
        }
    }

    private fun write(change: (HotState, HotStatePairing?) -> Pair<HotState, HotStatePairing?>) {
        step()
        val open = host
        if (open != null) {
            host = change(open.first, open.second)
        } else {
            val (state, pairing) = change(rows, meta)
            rows = state
            meta = pairing
        }
    }
}

/** `shinsou-state.json` and its `.before-hot-state` copy. */
internal class FakeSnapshotFile(var json: String? = null, private val crashes: Crashes = Crashes()) : SnapshotFile {
    var copy: String? = null
    var saves = 0
        private set
    var copyFails = false

    override fun save(json: String) {
        crashes.step()
        this.json = json
        saves++
    }

    override fun keepCopyBeforeHotState(): Boolean {
        if (copy != null || json == null) return true
        crashes.step()
        if (!copyFails) copy = json
        return !copyFails
    }

    /** What a platform's load() gives: the decoded file, or a fresh state when there is none. */
    fun load(): AppSnapshot = json?.let(AppSnapshotJson::decode) ?: AppSnapshot()
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
