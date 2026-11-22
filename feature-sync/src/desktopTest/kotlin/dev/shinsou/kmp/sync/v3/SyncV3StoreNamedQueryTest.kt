package dev.shinsou.kmp.sync.v3

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sync store reads every row by the names of the very SELECT it runs
 * ([dev.shinsou.kmp.content.sql.ContentRow.selectColumns]), so these round trips cover each projection:
 * aliases, aggregates, joins, DISTINCT, and NULL columns. (Split from the app's NamedQueryStoreTest.)
 */
class SyncV3StoreNamedQueryTest {
    @Test
    fun syncRecordsRoundTripThroughTheirProjections() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
        val store = SyncV3Store(driver)
        store.mark(
            listOf(
                DirtyEntry("library", "m:1", "title", "1:0:a"),
                DirtyEntry("library", "m:1", DirtyEntry.DELETED_FIELD, "1:1:a", aux = "true", tombKey = "key-1"),
                DirtyEntry("history", "c:2", "read", "2:0:a"),
            ),
        )
        assertEquals(2, store.dirtyRefCount())
        assertEquals(1, store.dirtyRefCount(excluded = setOf("history")))
        assertEquals(listOf("history" to "c:2", "library" to "m:1"), store.dirtyRefs(10))
        val deleted = store.dirtyFor("library", "m:1").single { it.field == DirtyEntry.DELETED_FIELD }
        assertEquals("true", deleted.aux)
        assertEquals("key-1", deleted.tombKey)
        assertNull(deleted.tombWork)
        assertNull(store.dirtyFor("library", "m:1").single { it.field == "title" }.aux)

        val record = StoredRecord(
            kind = "library",
            key = "key-1",
            workHash = "hash",
            workJson = "{\"w\":1}",
            state = LwwMerge.LocalState(
                fields = mapOf("title" to LwwMerge.LocalField(JsonPrimitive("T"), "1:0:a", dirty = true)),
                deleted = LwwMerge.LocalDeleted(value = false, clock = "1:1:a", dirty = false),
            ),
            seq = 7,
            unapplied = true,
            rejectReason = "invalid_record",
            rejectedAt = 99,
        )
        store.saveRecord(record)
        store.saveRecord(StoredRecord(kind = "library", key = "key-2", workHash = "hash", workJson = "{\"w\":2}"))

        assertEquals(record, store.loadRecord("library", "key-1"))
        // Every NULL column of a bare record reads back as NULL / false / zero.
        assertEquals(StoredRecord(kind = "library", key = "key-2", workHash = "hash", workJson = "{\"w\":2}"),
            store.loadRecord("library", "key-2"))
        assertEquals(listOf("{\"w\":2}"), store.liveWorkJson())
        assertEquals(1, store.rejectedCount())
        assertEquals(1, store.unappliedCount())
        assertEquals("library: invalid_record", store.lastRejection())

        store.bindCategory(5, "uuid-5")
        assertEquals(5L, store.categoryLocalId("uuid-5"))
        assertEquals("uuid-5", store.categoryUuid(5))
        assertEquals(mapOf(5L to "uuid-5"), store.allCategoryBindings())
        store.rememberWork(3, "key-3", null)
        assertEquals("key-3" to null, store.rememberedWork(3))
    }
}
