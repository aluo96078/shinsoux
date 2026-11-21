package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.domain.model.HlcTimestamp
import dev.shinsou.kmp.domain.model.HybridLogicalClock
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_HLC

/**
 * The one owner of this device's hybrid logical clock: stamping edits, following remote clocks and
 * bringing a clock that ran ahead back down ([rebaseClockIfAhead]).
 */
internal class SyncClockKeeper(
    private val store: SyncV3Store,
    private val nowMillis: () -> Long,
) {
    private val clockLock = SynchronousLock()

    /**
     * Held while edits are stamped and marked, so [rebaseClockIfAhead] never interleaves with one.
     * Always taken inside a store transaction ([marking]), never around one: a backup restore
     * reaches the observer with the shared database transaction already open, and the opposite
     * order elsewhere would deadlock against it.
     */
    private val markLock = SynchronousLock()

    private var clock: HybridLogicalClock? = null

    fun <T> marking(block: () -> T): T = store.transaction { markLock.withLock { block() } }

    /** Restores the clock of the session's sync device. */
    fun install(deviceId: String) {
        clockLock.withLock { clock = buildClock(deviceId) }
    }

    fun isInstalled(): Boolean = clockLock.withLock { clock } != null

    /** Read without the lock, as the cycle always did before activating a session. */
    fun isInstalledUnlocked(): Boolean = clock != null

    private fun buildClock(deviceId: String): HybridLogicalClock {
        // A stored clock that does not parse restarts from zero; the next remote clock moves it on.
        val stored = store.meta(META_HLC)?.let { runCatching { SyncClock.parse(it) }.getOrNull() }
        val initial = if (stored != null) HlcTimestamp(stored.millis, stored.counter, deviceId) else HlcTimestamp(0, 0, deviceId)
        return HybridLogicalClock(deviceId, initial, nowMillis)
    }

    fun nextClock(): String = clockLock.withLock {
        val c = clock ?: error("Sync clock is not initialised")
        val tick = c.tick()
        SyncClock.format(tick)
    }

    fun persistClock() {
        val last = clockLock.withLock { clock?.current() } ?: return
        store.setMeta(META_HLC, SyncClock.format(last))
    }

    fun clockAt(millis: Long): String {
        val deviceId = clockLock.withLock { clock?.deviceId } ?: error("Sync clock is not initialised")
        return SyncClock.format(HlcTimestamp(millis.coerceIn(0, nowMillis()), 0, deviceId))
    }

    fun observeRemote(clocks: Collection<String>) {
        if (clocks.isEmpty()) return
        clockLock.withLock {
            val c = clock ?: return
            // A clock that does not parse is ignored; the records carrying it are rejected elsewhere.
            clocks.mapNotNull { runCatching { SyncClock.parse(it) }.getOrNull() }.maxOrNull()?.let { c.observe(it) }
        }
        persistClock()
    }

    /**
     * Edits made while this device's time was set ahead carry clocks from the future, and so does
     * every later edit (the clock never runs backwards): the server refuses them all until real
     * time catches up, which can be days. Once the device's own time shows the clock is ahead, it
     * is brought back, and everything not yet accepted by the server (marked edits, promoted
     * fields, removal registers and the removal floors that go with them) moves to fresh clocks in
     * its original order.
     *
     * The clock never goes below a clock the server holds ([SyncV3Store.acknowledgedClockFloor]):
     * accepted values keep their clocks, and an edit stamped below one would be dropped as older.
     * That also covers a device whose time was set *back*: there the clock stays where it is.
     * Runs inside a cycle, after the pull and before the push, and excludes concurrent marking.
     * [pullFromZero] pulls every record the server holds.
     */
    suspend fun rebaseClockIfAhead(pullFromZero: suspend () -> Unit) {
        val now = nowMillis()
        val current = clockLock.withLock { clock?.current() } ?: return
        if (current.millis <= now + LwwMerge.MAX_CLOCK_SKEW_MILLIS) return
        // The floor is only trusted once every record the server holds has been seen: stored
        // records are a merged local view and cannot tell which clocks the server accepted.
        if (!store.acknowledgedFloorEstablished()) {
            pullFromZero()
            store.establishAcknowledgedFloor()
        }
        val floor = store.acknowledgedClockFloor()
        val target = if (floor != null && floor.first >= now) floor else now to 0
        fun above(millis: Long, counter: Int): Boolean =
            millis > target.first || (millis == target.first && counter > target.second)
        if (!above(current.millis, current.counter)) return
        fun above(clock: String?): Boolean {
            // A parse probe: a clock that does not parse is not above anything.
            val parsed = clock?.let { runCatching { SyncClock.parse(it) }.getOrNull() } ?: return false
            return above(parsed.millis, parsed.counter)
        }
        val original = clockLock.withLock { clock } ?: return
        // Everything below either commits with the lowered clock saved, or leaves the clock as it
        // was: pending clocks above a lowered clock would never be looked at again.
        try {
        marking {
            clockLock.withLock {
                clock = HybridLogicalClock(current.deviceId, HlcTimestamp(target.first, target.second, current.deviceId), nowMillis)
            }
            val rows = store.allDirty().filter { above(it.clock) }
            // Unpushed records, plus records pruned locally by a resync: their removal register is
            // stamped here without being pushed, so it is not dirty yet can be above the target.
            val pending = store.pushableRecords(Int.MAX_VALUE, Long.MAX_VALUE, { 0L })
            val pendingKeys = pending.mapTo(HashSet()) { it.kind to it.key }
            val local = store.recordsWithRemovalClocks { deleted, cleared -> above(deleted) || above(cleared) }
                .filter { (it.kind to it.key) !in pendingKeys }
            val records = (pending + local).filter { record ->
                val state = record.state
                above(state.cleared) || above(state.deleted?.clock) ||
                    state.fields.values.any { it.dirty && above(it.clock) }
            }
            val old = HashSet<String>()
            rows.forEach { old += it.clock }
            for (record in records) {
                val state = record.state
                state.cleared?.takeIf(::above)?.let { old += it }
                state.deleted?.takeIf { above(it.clock) }?.let { old += it.clock }
                state.fields.values.forEach { if (it.dirty && above(it.clock)) old += it.clock }
            }
            val fresh = HashMap<String, String>(old.size * 2)
            old.sortedWith { a, b -> LwwMerge.compare(a, b) }.forEach { fresh[it] = nextClock() }
            store.transaction {
                for (record in records) {
                    val state = record.state
                    val rebased = LwwMerge.LocalState(
                        fields = state.fields.mapValues { (_, field) ->
                            if (field.dirty) fresh[field.clock]?.let { field.copy(clock = it) } ?: field else field
                        },
                        deleted = state.deleted?.let { deleted -> fresh[deleted.clock]?.let { deleted.copy(clock = it) } ?: deleted },
                        cleared = state.cleared?.let { fresh[it] ?: it },
                    )
                    val skewed = record.rejectReason == "clock_skew"
                    store.saveRecord(
                        record.copy(
                            state = rebased,
                            rejectReason = if (skewed) null else record.rejectReason,
                            rejectedAt = if (skewed) null else record.rejectedAt,
                        ),
                    )
                }
                rows.forEach { row -> store.rebaseDirtyClock(row, fresh.getValue(row.clock)) }
            }
            persistClock()
        }
        } catch (error: Throwable) {
            clockLock.withLock { clock = original }
            throw error
        }
    }
}
