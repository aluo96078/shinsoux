package dev.shinsou.kmp.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetainedDeepLinkQueueTest {
    @Test
    fun distinctLinksRemainFifoUntilTheirExactEventIsHandled() = runTest {
        val queue = RetainedDeepLinkQueue()
        val first = ShinsouDeepLink.OpenManga(1)
        val second = ShinsouDeepLink.OpenManga(2)

        assertTrue(queue.tryEnqueue(first))
        assertTrue(queue.tryEnqueue(second))
        assertSame(first, queue.events.first())
        assertTrue(queue.hasPending)

        queue.handled(first)
        assertSame(second, queue.events.first())
        queue.handled(second)
        assertNull(withTimeoutOrNull(1) { queue.events.first() })
        assertFalse(queue.hasPending)
    }

    @Test
    fun staleEquivalentAcknowledgementCannotClearTheNextEvent() = runTest {
        val queue = RetainedDeepLinkQueue()
        val first = ShinsouDeepLink.OpenManga(42)
        val second = ShinsouDeepLink.OpenManga(42)
        queue.tryEnqueue(first)
        queue.tryEnqueue(second)

        queue.handled(first)
        queue.handled(first)

        assertSame(second, queue.events.first())
    }

    @Test
    fun fullQueueRejectsNewInputWithoutReplacingAcceptedHead() = runTest {
        val queue = RetainedDeepLinkQueue(maximumPending = 1)
        val accepted = ShinsouDeepLink.OpenManga(1)

        assertTrue(queue.tryEnqueue(accepted))
        assertFalse(queue.tryEnqueue(ShinsouDeepLink.OpenManga(2)))
        assertSame(accepted, queue.events.first())
    }

    @Test
    fun eachShellTakesTheLinksItsWindowReceived() = runTest {
        val queue = RetainedDeepLinkQueue()
        // The same link without arguments, received by two windows (tags "a" and "b").
        assertTrue(queue.tryEnqueue(ShinsouDeepLink.OpenSettings, "a"))
        assertTrue(queue.tryEnqueue(ShinsouDeepLink.OpenSettings, "b"))
        val third = ShinsouDeepLink.OpenManga(3L)
        assertTrue(queue.tryEnqueue(third, "b"))
        assertSame(ShinsouDeepLink.OpenSettings, queue.eventsWhere { it == "b" }.first())
        // Window b handles its own: window a's stays waiting.
        assertEquals("b", queue.handled(ShinsouDeepLink.OpenSettings) { it == "b" })
        assertEquals(listOf<Any?>("a", "b"), queue.pendingTags)
        assertSame(third, queue.eventsWhere { it == "b" }.first())
        // A stale acknowledgement changes nothing.
        assertNull(queue.handled(ShinsouDeepLink.OpenSettings) { it == "b" })
        // Window a is closed by the user: its links go.
        queue.discardWhere { it == "a" }
        assertEquals(listOf<ShinsouDeepLink>(third), queue.pendingLinks)
    }

    @Test
    fun receiversThatAreNotTakingLinksCannotCrowdOutANewOne() {
        val queue = RetainedDeepLinkQueue(maximumPending = 2)
        assertTrue(queue.tryEnqueue(ShinsouDeepLink.OpenManga(1), "stopped") { it == "stopped" })
        assertTrue(queue.tryEnqueue(ShinsouDeepLink.OpenManga(2), "stopped") { it == "stopped" })
        assertFalse(queue.tryEnqueue(ShinsouDeepLink.OpenManga(3), "stopped") { it == "stopped" })
        assertTrue(queue.tryEnqueue(ShinsouDeepLink.OpenManga(4), "new") { it == "new" })
    }
}
