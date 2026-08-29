package dev.shinsou.kmp.ui.challenge

import dev.shinsou.kmp.plugin.PluginCookie
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceWebChallengeCookiesTest {
    @Test
    fun requiredChallengeCookieIsNeverSeededIntoAnIsolatedBrowser() {
        val seeded = webChallengeSeedCookies(
            SourceWebChallengeRequest(
                sourceId = 1L,
                sourceName = "Fixture",
                url = "https://www.bilimanga.net/login.php",
                userAgent = "fixture",
                cookies = listOf(
                    SourceCookie("cf_clearance", "stale", ".bilimanga.net", hostOnly = false),
                    SourceCookie("member_session", "keep", ".bilimanga.net", hostOnly = false),
                ),
                requiredCookieName = "cf_clearance",
            ),
        )

        assertEquals(listOf("member_session"), seeded.map(SourceCookie::name))
    }

    @Test
    fun androidCookieHeaderParsingIsDeterministicAndOriginBound() {
        val parsed = parseWebViewCookieHeader(
            header = "cf_clearance=token==; session=abc; invalid name=no; broken",
            requestUrl = "https://reader.example.com/challenge",
            nowEpochMillis = 1_000L,
        )

        assertEquals(listOf("cf_clearance", "session"), parsed.map { it.name })
        assertEquals("token==", parsed.first().value)
        assertTrue(parsed.all { it.domain == "reader.example.com" && it.hostOnly })
        assertTrue(parsed.all { it.path == "/challenge" && it.secure })
    }

    @Test
    fun normalizationRejectsExpiredUnrelatedAndOverlappingPaths() {
        val normalized = normalizeWebChallengeCookies(
            requestUrl = "https://reader.sub.example.com/challenge/page",
            cookies = listOf(
                SourceCookie("host", "one", "reader.sub.example.com", "/challenge", hostOnly = true),
                SourceCookie("parent", "two", ".example.com", "/", hostOnly = false),
                SourceCookie("wrong-host-only", "x", "example.com", "/", hostOnly = true),
                SourceCookie("wrong-domain", "x", ".evil.example", "/", hostOnly = false),
                SourceCookie("wrong-path", "x", ".example.com", "/challenge-page", hostOnly = false),
                SourceCookie("expired", "x", ".example.com", "/", expiresAtEpochMillis = 999L),
                SourceCookie("bad\nname", "x", ".example.com", "/", hostOnly = false),
            ),
            nowEpochMillis = 1_000L,
        )

        assertEquals(listOf("host", "parent"), normalized.map { it.name })
        assertEquals("reader.sub.example.com", normalized[0].domain)
        assertEquals(".example.com", normalized[1].domain)
    }

    @Test
    fun normalizationUsesLastCookieForAnIdenticalJarKey() {
        val normalized = normalizeWebChallengeCookies(
            requestUrl = "https://example.com/",
            cookies = listOf(
                SourceCookie("session", "old", "example.com"),
                SourceCookie("session", "new", "example.com"),
            ),
            nowEpochMillis = 1_000L,
        )

        assertEquals(listOf("new"), normalized.map { it.value })
    }

    @Test
    fun webViewSeedHeaderPreservesScopeAndExpiryDeterministically() {
        assertEquals(
            "session=abc; Path=/reader; Domain=.example.com; Max-Age=60; Secure; HttpOnly; SameSite=Lax",
            webChallengeSetCookieValue(
                SourceCookie(
                    name = "session",
                    value = "abc",
                    domain = ".example.com",
                    path = "/reader",
                    expiresAtEpochMillis = 61_000L,
                    secure = true,
                    httpOnly = true,
                    hostOnly = false,
                ),
                nowEpochMillis = 1_000L,
            ),
        )
        assertEquals(
            "host=only; Path=/; SameSite=Lax",
            webChallengeSetCookieValue(SourceCookie("host", "only", "reader.example.com"), 1_000L),
        )
    }

    @Test
    fun pluginCookieHonorsHostOnlySecureAndRfcPathBoundary() {
        val hostOnly = PluginCookie("one", "1", "example.com", hostOnly = true)
        assertTrue(hostOnly.matches(Url("https://example.com/"), 0L))
        assertFalse(hostOnly.matches(Url("https://sub.example.com/"), 0L))

        val domain = PluginCookie("two", "2", "example.com", "/reader", secure = true, hostOnly = false)
        assertTrue(domain.matches(Url("https://sub.example.com/reader/page"), 0L))
        assertFalse(domain.matches(Url("https://sub.example.com/readership"), 0L))
        assertFalse(domain.matches(Url("http://sub.example.com/reader/page"), 0L))
    }

    @Test
    fun browserUserAgentNormalizationRejectsControlCharactersAndOversizedValues() {
        assertEquals("Native WebKit/1.0", normalizeWebChallengeUserAgent("  Native WebKit/1.0  "))
        assertEquals(null, normalizeWebChallengeUserAgent("bad\nagent"))
        assertEquals(null, normalizeWebChallengeUserAgent("x".repeat(513)))
    }
}
