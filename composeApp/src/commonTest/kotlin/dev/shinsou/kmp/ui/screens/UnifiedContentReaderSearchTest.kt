package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.content.TextBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UnifiedContentReaderSearchTest {
    @Test
    fun cancellationStopsBeforeADeferredLaterBlockIsMaterialized() = runTest {
        val canonicalText = "first"
        val blocks = listOf(
            TextBlock("first-block", 0, canonicalText.length),
            // This is structurally valid manifest metadata, but deliberately exceeds this body.
            // An eager all-book flatMap would fail here before exposing the first segment.
            TextBlock("deferred-invalid-block", 0, canonicalText.length + 1),
        )
        val visited = mutableListOf<String>()
        var cancellation: CancellationException? = null

        try {
            forEachReaderSearchSegment(REPRESENTATION_ID, blocks, canonicalText) { segment ->
                visited += segment.blockId
                throw CancellationException("reader closed")
            }
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
        }

        assertNotNull(cancellation)
        assertEquals(listOf("first-block"), visited)
    }

    private companion object {
        const val REPRESENTATION_ID: String = "11111111-1111-4111-8111-111111111111"
    }
}
