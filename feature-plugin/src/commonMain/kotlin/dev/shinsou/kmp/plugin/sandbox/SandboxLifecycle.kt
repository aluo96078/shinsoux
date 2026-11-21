package dev.shinsou.kmp.plugin.sandbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A runtime whose sandbox the lifecycle may stop between calls; it restarts on the next call. */
internal interface SandboxResident {
    val packageId: String

    /** Stops the sandbox unless a call is running (never waits); false when busy. */
    fun tryRelease(): Boolean
}

/** What counts against a package in the crash-loop window (ADR-0002 §7). */
internal enum class SandboxStrike { CRASH, LIMIT, TIMEOUT }

/** The package crashed or hit its limits too often; it stays off until the app restarts. */
internal class SandboxDisabledException(packageId: String) :
    IllegalStateException("Sandboxed plugin '$packageId' was disabled after repeated crashes or limit violations")

/**
 * Process/instance lifecycle shared by the platforms (ADR-0002 §4.6, §7): sandboxes start lazily on
 * a runtime's first call, stop after [idleMs] without calls, at most [maxLive] stay alive (least
 * recently used idle ones are stopped first; busy ones are never interrupted, so the cap can be
 * exceeded while every sandbox is mid-call), and [crashLimit] strikes within [crashWindowMs]
 * disable a package.
 *
 * [countTimeouts] lets the platform ignore time-limit strikes while the host cannot vouch for the
 * clock, e.g. Android's OEM freezers stopping background processes (§4.3): a frozen sandbox is
 * not a misbehaving one.
 */
internal class SandboxLifecycle(
    private val maxLive: Int,
    private val idleMs: Long = 60_000,
    private val crashLimit: Int = 3,
    private val crashWindowMs: Long = 10 * 60_000L,
    private val nowMs: () -> Long,
    private val countTimeouts: () -> Boolean = { true },
    scope: CoroutineScope,
) {
    private class Live(val resident: SandboxResident, var lastUsed: Long)

    private val lock = Mutex()
    private val live = ArrayList<Live>()
    private val strikes = HashMap<String, ArrayDeque<Long>>()
    private val disabled = HashSet<String>()

    init {
        require(maxLive > 0 && idleMs > 0 && crashLimit > 0)
        scope.launch {
            while (isActive) {
                delay((idleMs / 4).coerceIn(1_000, 15_000))
                sweep()
            }
        }
    }

    suspend fun requireEnabled(packageId: String) {
        if (lock.withLock { packageId in disabled }) throw SandboxDisabledException(packageId)
    }

    /** [resident] is about to run a call on a live sandbox; makes room under the cap. */
    suspend fun touch(resident: SandboxResident) = lock.withLock {
        val now = nowMs()
        val entry = live.firstOrNull { it.resident === resident }
        if (entry != null) entry.lastUsed = now else live += Live(resident, now)
        if (live.size > maxLive) {
            for (candidate in live.sortedBy { it.lastUsed }) {
                if (live.size <= maxLive) break
                if (candidate.resident !== resident && candidate.resident.tryRelease()) live -= candidate
            }
        }
    }

    /** The call ended; the idle clock starts now. */
    suspend fun idle(resident: SandboxResident) = lock.withLock {
        live.firstOrNull { it.resident === resident }?.lastUsed = nowMs()
    }

    /** [resident]'s sandbox is gone (closed, crashed or released). */
    suspend fun stopped(resident: SandboxResident) = lock.withLock {
        live.removeAll { it.resident === resident }
    }

    suspend fun strike(packageId: String, kind: SandboxStrike) {
        if (kind == SandboxStrike.TIMEOUT && !countTimeouts()) return
        lock.withLock {
            val now = nowMs()
            val times = strikes.getOrPut(packageId) { ArrayDeque() }
            times.addLast(now)
            while (times.isNotEmpty() && now - times.first() >= crashWindowMs) times.removeFirst()
            if (times.size >= crashLimit) disabled += packageId
        }
    }

    internal suspend fun liveCount(): Int = lock.withLock { live.size }

    /** Test hook: runs [block] holding the lifecycle lock, to make bookkeeping wait for it. */
    internal suspend fun holdForTest(block: suspend () -> Unit) = lock.withLock { block() }

    internal suspend fun sweep() = lock.withLock {
        val now = nowMs()
        live.filter { now - it.lastUsed >= idleMs && it.resident.tryRelease() }.forEach { live -= it }
    }
}
