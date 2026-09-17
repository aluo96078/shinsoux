package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceFailureMarkerTest {
    @Test
    fun recoveryUsesOnlyExactHostOwnedMarkers() {
        assertEquals(
            "SHINSOU_SOURCE_HTTP_CHALLENGE",
            IllegalStateException("wrapper", IllegalStateException("Error: SHINSOU_SOURCE_HTTP_CHALLENGE (source.js#12)"))
                .sourceFailureMarker(),
        )
        for (message in listOf(
            "prefix SHINSOU_SOURCE_HTTP_CHALLENGE",
            "SHINSOU_SOURCE_HTTP_CHALLENGE trailing",
            "<html>SHINSOU_SOURCE_HTTP_CHALLENGE</html>",
            "SHINSOU_SOURCE_HTTP_CHALLENGE" + " ".repeat(512),
        )) {
            assertNull(IllegalStateException(message).sourceFailureMarker())
        }
    }

    @Test
    fun nestedFailuresAreBoundedAndPreserveFirstClassification() {
        assertEquals(
            "SHINSOU_SOURCE_HTTP_BLOCKED",
            IllegalStateException("SHINSOU_SOURCE_HTTP_BLOCKED", IllegalStateException("SHINSOU_SOURCE_HTTP_CHALLENGE"))
                .sourceFailureMarker(),
        )
        var nested: Throwable = IllegalStateException("SHINSOU_SOURCE_HTTP_CHALLENGE")
        repeat(7) { nested = IllegalStateException("wrapper", nested) }
        assertEquals("SHINSOU_SOURCE_HTTP_CHALLENGE", nested.sourceFailureMarker())
        assertNull(IllegalStateException("wrapper", nested).sourceFailureMarker())
    }
}
