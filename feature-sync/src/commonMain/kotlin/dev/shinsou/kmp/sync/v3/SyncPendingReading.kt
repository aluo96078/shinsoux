package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.domain.model.ReadingScope
import kotlinx.serialization.json.JsonObject

/**
 * Reading of a title that other devices synced, held back because this device has not stored
 * the chapters it is about; it is applied once they are. Chapters are named by the source's own
 * unit ids, so a title's page can show it before any of them is opened here.
 */
public class SyncPendingReading internal constructor(
    /** The chapter read last on another device, and when. */
    public val lastReadUnitId: String?,
    public val lastReadAtMillis: Long?,
    /** Chapters finished on another device. */
    public val readUnitIds: Set<String>,
    /** Zero-based last page of chapters started (not finished) on another device. */
    public val lastPageIndexByUnit: Map<String, Int>,
    private val positionUnitId: String?,
    private val position: JsonObject?,
) {
    /** The synced position inside [unitId], placed in this device's [scope]; null when none was synced. */
    public fun locator(unitId: String, scope: ReadingScope): ReadingLocator? =
        position?.takeIf { unitId == positionUnitId }?.let { PortableLocator.fromWire(it, scope) }

    internal companion object {
        /** Null when [records] hold nothing to show. */
        fun of(records: List<StoredRecord>): SyncPendingReading? =
            Collector().apply { records.filter { it.state.isLive }.forEach(::add) }.result()
    }

    private class Collector {
        private var lastReadUnitId: String? = null
        private var lastReadAt: Long? = null
        private var positionUnitId: String? = null
        private var position: JsonObject? = null
        private val read = LinkedHashSet<String>()
        private val pages = LinkedHashMap<String, Int>()

        fun add(record: StoredRecord) {
            fun field(name: String) = record.state.fields[name]?.value
            when (record.kind) {
                SyncKinds.HISTORY -> {
                    lastReadUnitId = field("unitRemoteId")?.str()
                    lastReadAt = field("lastReadAt")?.long()
                }
                SyncKinds.PROGRESS -> {
                    positionUnitId = (field("unitRemoteId") ?: field("unitId"))?.str()
                    position = field("locator") as? JsonObject
                }
                SyncKinds.UNIT -> record.unitRemoteId?.let { unitId ->
                    if (field("read")?.bool() == true) {
                        read += unitId
                    } else {
                        field("lastPageIndex")?.long()?.takeIf { it > 0 }?.let { pages[unitId] = it.toInt() }
                    }
                }
            }
        }

        fun result(): SyncPendingReading? {
            val empty = read.isEmpty() && pages.isEmpty()
            if (lastReadUnitId == null && position == null && empty) return null
            return SyncPendingReading(
                lastReadUnitId,
                lastReadAt?.takeIf { lastReadUnitId != null },
                read,
                pages,
                positionUnitId,
                position,
            )
        }
    }
}
