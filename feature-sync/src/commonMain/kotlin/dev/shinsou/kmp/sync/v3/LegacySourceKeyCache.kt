package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.logging.Log

/** Synchronous view of the legacy-source lookup, refreshed from the (suspending) plugin manager. */
internal class LegacySourceKeyCache {
    private val lock = SynchronousLock()
    private var keys: Map<Long, SourceKey?> = emptyMap()

    fun lookup(sourceId: Long): SourceKey? = lock.withLock { keys[sourceId] }

    suspend fun refresh(sourceIds: Set<Long>, resolve: suspend (Long) -> SourceKey?) {
        val next = HashMap<Long, SourceKey?>()
        for (id in sourceIds) {
            if (id == 0L) continue
            next[id] = runCatchingCancellable { resolve(id) }
                .onFailure { Log.warn("Sync", it) { "Resolving a legacy source key failed" } }
                .getOrNull()
        }
        lock.withLock { keys = next }
    }

    /** Test hook. */
    fun put(sourceId: Long, key: SourceKey?) {
        lock.withLock { keys = keys + (sourceId to key) }
    }
}
