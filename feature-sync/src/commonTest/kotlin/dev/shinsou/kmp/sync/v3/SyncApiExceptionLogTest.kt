package dev.shinsou.kmp.sync.v3

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncApiExceptionLogTest {
    @Test
    fun logsCarryTheStatusAndAStableCodeButNothingElseTheServerSent() {
        val stable = SyncApiException(422, "unknown_work", "token secret-value rejected")
        assertEquals("SyncApiException(status=422, code=unknown_work)", stable.loggableSummary)

        assertEquals("SyncApiException(status=503, code=http_503)", SyncApiException(503, "http_503", "x").loggableSummary)

        val odd = SyncApiException(500, "Bearer secret-value\nforged line", "x")
        assertEquals("SyncApiException(status=500, code=other)", odd.loggableSummary)
        // Shaped like a code, but not one this app knows: it may be a token.
        val tokenLike = SyncApiException(400, "sk_live_secretvalue", "x")
        assertEquals("SyncApiException(status=400, code=other)", tokenLike.loggableSummary)
    }
}
