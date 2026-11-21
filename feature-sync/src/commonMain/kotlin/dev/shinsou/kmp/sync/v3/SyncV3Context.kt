package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_CURSOR
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_SERVER_URL

/**
 * The fixed dependencies every collaborator of [SyncV3Service] reads. It holds no mutable state of
 * its own: the sync tables are owned by [store], the library by [repository].
 */
internal class SyncV3Context(
    val repository: ShinsouRepository,
    val store: SyncV3Store,
    val identity: SyncIdentityResolver,
    val api: SyncApi,
    val nowMillis: () -> Long,
) {
    val baseUrl: String get() = store.meta(META_SERVER_URL) ?: error("Not signed in")
    val cursor: Long get() = store.meta(META_CURSOR)?.toLongOrNull() ?: 0L

    /** The kinds this device sends and applies; the others keep their pending work untouched. */
    fun enabledKinds(): Set<String> = SyncKinds.enabled(repository.currentSnapshot.settings.sync.kinds)

    /** The kinds switched off here, for the store's counters and queries. */
    fun disabledKinds(): Set<String> = SyncKinds.ALL.toSet() - enabledKinds()

    fun pendingLocalCount(): Int {
        val off = disabledKinds()
        return store.dirtyRefCount(off) + store.dirtyRecordCount(off)
    }
}
