package dev.shinsou.kmp.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AniListOAuthCallbackParserTest {
    @Test
    fun parsesEncodedImplicitFlowFragmentAndExpiry() {
        val token = AniListOAuthCallbackParser.parse(
            "anilist://oauth#access_token=a%2Bb%2Fc&token_type=Bearer&expires_in=3600&scope=read%20write",
            nowEpochMillis = 1_000,
        )

        assertEquals("a+b/c", token.accessToken)
        assertEquals("Bearer", token.tokenType)
        assertEquals(3_601_000L, token.expiresAt)
        assertEquals(setOf("read", "write"), token.scopes)
    }

    @Test
    fun acceptsExplicitRawTokenWithoutInventingExpiry() {
        val token = AniListOAuthCallbackParser.parse("  raw-token_123  ", nowEpochMillis = 5_000)

        assertEquals("raw-token_123", token.accessToken)
        assertNull(token.expiresAt)
    }

    @Test
    fun rejectsCallbackWithoutTokenAndSurfacesOAuthError() {
        assertFailsWith<TrackerAuthenticationException> {
            AniListOAuthCallbackParser.parse("anilist://oauth#state=abc", nowEpochMillis = 0)
        }
        val error = assertFailsWith<TrackerAuthenticationException> {
            AniListOAuthCallbackParser.parse(
                "anilist://oauth#error=access_denied&error_description=User%20cancelled",
                nowEpochMillis = 0,
            )
        }
        assertEquals("AniList authorization failed: User cancelled", error.message)
    }
}
