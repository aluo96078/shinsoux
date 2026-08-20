package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.domain.model.ReadingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncReducerTest {
    private val manga = SyncEntityKey.manga("source", "/m")
    private val chapter = SyncEntityKey.chapter("source", "/c")
    private val category = SyncEntityKey.category("category-1")

    @Test
    fun fieldRegistersAndTombstoneConvergeIndependentOfArrivalOrder() {
        val oldPatch = event(
            "old",
            10,
            "a",
            LibraryEntryPatch(manga, mapOf(SyncFields.Manga.TITLE to SyncValue.StringValue("old"))),
        )
        val newPatch = event(
            "new",
            20,
            "b",
            LibraryEntryPatch(manga, mapOf(SyncFields.Manga.TITLE to SyncValue.StringValue("new"))),
        )
        val delete = event("delete", 30, "a", EntityPresenceSet(manga, false))

        val forward = listOf(oldPatch, newPatch, delete).fold(SyncState()) { state, item -> SyncReducer.reduce(state, item) }
        val reversed = listOf(delete, newPatch, oldPatch).fold(SyncState()) { state, item -> SyncReducer.reduce(state, item) }

        assertEquals(forward, reversed)
        assertEquals("new", (forward.entities.getValue(manga).fields.getValue(SyncFields.Manga.TITLE).value as SyncValue.StringValue).value)
        assertFalse(forward.entities.getValue(manga).isPresent)

        val resurrected = SyncReducer.reduce(
            forward,
            event(
                "resurrect",
                40,
                "b",
                LibraryEntryPatch(manga, mapOf(SyncFields.Manga.FAVORITE to SyncValue.BooleanValue(true))),
            ),
        )
        assertTrue(resurrected.entities.getValue(manga).isPresent)
    }

    @Test
    fun membershipIsAnIndependentLwwBoolean() {
        val add = event("add", 10, "a", CategoryMembershipSet(manga, category, true))
        val remove = event("remove", 20, "b", CategoryMembershipSet(manga, category, false))

        val state = SyncReducer.reduce(SyncReducer.reduce(SyncState(), remove), add)

        assertFalse(state.categoryMemberships.getValue(CategoryMembershipKey(manga, category)).value)
    }

    @Test
    fun readingBackwardsResetCompletionAndMarkUnreadUseSeparateAuthorities() {
        val pageTen = progress("p10", 100, page = 10, resetEpoch = 0)
        val pageTwo = progress("p2", 110, page = 2, resetEpoch = 0)
        val reset = progress("reset", 120, page = 0, resetEpoch = 1, read = false)
        // Even a later HLC from an old reset epoch cannot restore the obsolete larger page.
        val delayedOldEpoch = progress("delayed", 999, page = 50, resetEpoch = 0)
        val completed = progress("complete", 130, page = 4, resetEpoch = 1, read = true, history = 500)
        val unread = event(
            "unread",
            140,
            "a",
            ReadingProgressSet(chapter, manga, readState = false),
        )

        var state = SyncState()
        listOf(pageTen, pageTwo, reset, delayedOldEpoch, completed, unread).forEach { state = SyncReducer.reduce(state, it) }
        val result = state.readingProgress.getValue(chapter)

        assertEquals(4, result.position?.position?.pageIndex)
        assertEquals(1, result.position?.position?.resetEpoch)
        assertFalse(requireNotNull(result.readState).value)
        assertEquals(500, result.historyTouchedAt?.value)
        assertTrue(requireNotNull(result.presence).value)

        val reordered = listOf(delayedOldEpoch, unread, completed, reset, pageTwo, pageTen)
            .fold(SyncState()) { accumulator, item -> SyncReducer.reduce(accumulator, item) }
        assertEquals(result, reordered.readingProgress.getValue(chapter))
    }

    @Test
    fun keyRemapRewritesParentsMembershipsAndProgress() {
        val mangaV2 = SyncEntityKey.manga("source", "/canonical-m", version = 2)
        var state = SyncState()
        listOf(
            event("m", 1, "a", LibraryEntryPatch(manga, emptyMap())),
            event("c", 2, "a", ChapterStatePatch(chapter, manga, emptyMap())),
            event("membership", 3, "a", CategoryMembershipSet(manga, category, true)),
            progress("progress", 4, page = 1, resetEpoch = 0),
            event("remap", 5, "a", EntityKeyRemap(manga, mangaV2)),
        ).forEach { state = SyncReducer.reduce(state, it) }

        assertTrue(mangaV2 in state.entities)
        assertTrue(manga !in state.entities)
        val parent = state.entities.getValue(chapter).fields.getValue(SyncFields.Chapter.MANGA_KEY).value
        assertEquals(SyncValue.EntityKeyValue(mangaV2), parent)
        assertEquals(mangaV2, state.readingProgress.getValue(chapter).mangaKey)
        assertTrue(state.categoryMemberships.keys.single().mangaKey == mangaV2)

        val patchUsingOldKey = event(
            "post-remap",
            6,
            "b",
            LibraryEntryPatch(manga, mapOf(SyncFields.Manga.TITLE to SyncValue.StringValue("resolved"))),
        )
        state = SyncReducer.reduce(state, patchUsingOldKey)
        assertEquals("resolved", (state.entities.getValue(mangaV2).fields.getValue(SyncFields.Manga.TITLE).value as SyncValue.StringValue).value)
    }

    @Test
    fun concurrentKeyRemapsConvergeWithoutDowngradingTheWinningVersion() {
        val mangaV2 = SyncEntityKey.manga("source", "/canonical-v2", version = 2)
        val mangaV3 = SyncEntityKey.manga("source", "/canonical-v3", version = 3)
        val toV2 = event("remap-v2", 10, "a", EntityKeyRemap(manga, mangaV2))
        val toV3 = event("remap-v3", 11, "b", EntityKeyRemap(manga, mangaV3))

        val v2ThenV3 = listOf(toV2, toV3).fold(SyncState()) { state, item -> SyncReducer.reduce(state, item) }
        val v3ThenV2 = listOf(toV3, toV2).fold(SyncState()) { state, item -> SyncReducer.reduce(state, item) }

        assertEquals(v2ThenV3, v3ThenV2)
        assertEquals(mangaV3, v3ThenV2.resolveKey(manga))
        assertEquals(mangaV3, v3ThenV2.resolveKey(mangaV2))
        assertEquals(mangaV3, v3ThenV2.resolveKey(mangaV3))
    }

    @Test
    fun concurrentSameVersionRemapForkUsesAStableWinner() {
        val left = SyncEntityKey.manga("source", "/canonical-left", version = 2)
        val right = SyncEntityKey.manga("source", "/canonical-right", version = 2)
        val leftEvent = event("remap-left", 10, "a", EntityKeyRemap(manga, left))
        val rightEvent = event("remap-right", 10, "b", EntityKeyRemap(manga, right))

        val leftThenRight = listOf(leftEvent, rightEvent)
            .fold(SyncState()) { state, item -> SyncReducer.reduce(state, item) }
        val rightThenLeft = listOf(rightEvent, leftEvent)
            .fold(SyncState()) { state, item -> SyncReducer.reduce(state, item) }
        val expectedWinner = maxOf(left, right)

        assertEquals(leftThenRight, rightThenLeft)
        assertEquals(expectedWinner, leftThenRight.resolveKey(manga))
        assertEquals(expectedWinner, leftThenRight.resolveKey(left))
        assertEquals(expectedWinner, leftThenRight.resolveKey(right))
    }

    @Test
    fun operationReplayIsIdempotentAndWorkspaceGapsAreRejected() {
        val operation = event("same", 1, "a", LibraryEntryPatch(manga, emptyMap()))
        val once = SyncReducer.reduce(SyncState(), operation)
        assertEquals(once, SyncReducer.reduce(once, operation))

        val committed = SyncReducer.reduceCommitted(SyncState(), CommittedSyncEvent(1, operation))
        assertEquals(1, committed.throughWorkspaceSeq)
        assertFailsWith<SyncSequenceGapException> {
            SyncReducer.reduceCommitted(committed, CommittedSyncEvent(3, event("gap", 3, "a", EntityPresenceSet(manga, false))))
        }
    }

    private fun progress(
        id: String,
        millis: Long,
        page: Int,
        resetEpoch: Long,
        read: Boolean? = null,
        history: Long? = null,
    ): SyncEvent = event(
        id,
        millis,
        "a",
        ReadingProgressSet(
            chapterKey = chapter,
            mangaKey = manga,
            position = ReaderPosition(ReadingMode.PAGER_LTR, page, 0.0, resetEpoch),
            readState = read,
            historyTouchedAt = history,
            sessionId = "reader",
        ),
    )

    private fun event(
        id: String,
        millis: Long,
        deviceId: String,
        vararg mutations: SyncMutation,
    ): SyncEvent = SyncEvent(id, HlcTimestamp(millis, 0, deviceId), mutations.toList())
}
