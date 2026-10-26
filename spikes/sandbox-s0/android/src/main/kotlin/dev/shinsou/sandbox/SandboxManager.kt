package dev.shinsou.sandbox

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lifecycle policy from ADR-0002 §4.6: lazy start, 60 s idle reclaim, LRU cap (initial 4), and a
 * per-package crash counter feeding the crash-loop breaker (§7).
 */
class SandboxManager(
    context: Context,
    private val maxLive: Int = 4,
    private val idleMs: Long = 60_000,
    private val crashLoopLimit: Int = 3,
    strategy: PayloadCodec.Strategy = PayloadCodec.Strategy.PIPE,
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newCachedThreadPool()
    private val codec = PayloadCodec(executor, strategy)
    private val lock = Any()
    // accessOrder = true gives LRU iteration order.
    private val live = LinkedHashMap<String, SandboxClient>(8, 0.75f, true)
    private val crashes = HashMap<String, Int>()
    private val sweeper = Executors.newSingleThreadScheduledExecutor().also {
        it.scheduleWithFixedDelay(::sweepIdle, idleMs / 4, idleMs / 4, TimeUnit.MILLISECONDS)
    }

    fun isBlocked(packageKey: String): Boolean = synchronized(lock) { (crashes[packageKey] ?: 0) >= crashLoopLimit }

    /** Returns a started client, creating it if needed. Evicts the LRU entry when over [maxLive]. */
    fun acquire(packageKey: String, handler: HostBridgeHandler, bootstrap: ByteArray, script: ByteArray, limits: LongArray): SandboxClient {
        val toStop = ArrayList<SandboxClient>()
        val client: SandboxClient
        var created = false
        synchronized(lock) {
            check(!isBlocked(packageKey)) { "crash loop: $packageKey" }
            val existing = live[packageKey]
            if (existing != null) client = existing
            else {
                client = SandboxClient(appContext, packageKey, handler, executor, codec, ::onDead)
                live[packageKey] = client
                created = true
                while (live.size > maxLive) {
                    val eldest = live.entries.first { it.value !== client }
                    live.remove(eldest.key)
                    toStop += eldest.value
                }
            }
        }
        toStop.forEach { it.stop() }
        if (created) client.start(bootstrap, script, limits)
        return client
    }

    fun shutdown() {
        sweeper.shutdownNow()
        val all = synchronized(lock) { live.values.toList().also { live.clear() } }
        all.forEach { it.stop() }
        executor.shutdown()
    }

    private fun sweepIdle() {
        val now = System.nanoTime() / 1_000_000
        val idle = synchronized(lock) {
            live.values.filter { now - it.lastUsedMs > idleMs }.also { l -> l.forEach { live.remove(it.packageKey) } }
        }
        idle.forEach { it.stop() }
    }

    private fun onDead(client: SandboxClient, crashed: Boolean) {
        synchronized(lock) {
            if (live[client.packageKey] === client) live.remove(client.packageKey)
            if (crashed) crashes.merge(client.packageKey, 1, Int::plus)
        }
    }
}
