package dev.shinsou.kmp.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
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

        queue.handled(first)
        assertSame(second, queue.events.first())
        queue.handled(second)
        assertNull(withTimeoutOrNull(1) { queue.events.first() })
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
}
