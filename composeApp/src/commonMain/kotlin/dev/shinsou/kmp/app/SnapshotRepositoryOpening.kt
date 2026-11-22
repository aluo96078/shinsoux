package dev.shinsou.kmp.app

import dev.shinsou.kmp.content.PlatformContentInfrastructure
import dev.shinsou.kmp.content.hotstate.SqlHotStateStore
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.data.SnapshotFile
import dev.shinsou.kmp.data.openSnapshotPersistence
import dev.shinsou.kmp.persistence.HotStateStore
import dev.shinsou.kmp.logging.Log

/**
 * C3-3 (docs/architecture/0007 §6.3): the repository every platform starts with, its snapshot JSON
 * ([loaded], read from [file]; null when there is none or it was quarantined) paired with the hot rows in the content database. Call on an I/O
 * thread, after [checkBeforeOpening] found the database usable; [contentInfrastructure] null (a
 * newer version wrote the database) writes the whole JSON, as before C3. [hotStateEnabled] false is
 * the off-switch ([dev.shinsou.kmp.data.WHOLE_JSON_SWITCH_FILE]).
 */
fun openShinsouRepository(
    loaded: AppSnapshot?,
    file: SnapshotFile,
    contentInfrastructure: PlatformContentInfrastructure?,
    hotStateEnabled: Boolean,
): ShinsouRepository {
    val store = contentInfrastructure?.let(::openHotStateStore)
    val opened = openSnapshotPersistence(loaded, file, store, hotStateEnabled)
    Log.info("Storage") { "Snapshot opened: ${opened.opening}" }
    return ShinsouRepository(opened.snapshot, opened.storage)
}

private fun openHotStateStore(infrastructure: PlatformContentInfrastructure): HotStateStore? = try {
    SqlHotStateStore(infrastructure.contentDriver())
} catch (logged: Exception) {
    // The JSON alone is complete: without the store the app writes it whole, as before C3.
    Log.warn("Storage", logged) { "The hot state tables could not be opened" }
    null
}
