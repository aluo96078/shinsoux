package dev.shinsou.kmp.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/** Reading state that arrives late must not undo read/unread marks the user set meanwhile. */
class ReadMarksMergeTest {
    @Test
    fun handSetMarksWinOverLateReadingState() {
        val loaded = setOf("c1", "c2", "c3")
        // c2 was marked unread and c9 read by hand while the stored state was loading.
        val marks = mapOf("c2" to false, "c9" to true)
        assertEquals(setOf("c1", "c3", "c9"), (emptySet<String>() + loaded).withReadMarks(marks))
        assertEquals(loaded, loaded.withReadMarks(emptyMap()))
    }
}
