package dev.shinsou.kmp.ui.challenge

import dev.shinsou.kmp.plugin.PluginCookie
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeCapability
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.WebChallengeEmbeddedPolicy
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceWebChallengeCookiesTest {
    @Test
    fun embeddedChallengeCapabilityDefaultsClosed() {
        val request = SourceWebChallengeRequest(
            capability = SourceWebChallengeCapability(),
            sourceId = 1L,
            sourceName = "Generic",
            url = "https://example.test/challenge",
            userAgent = "fixture-agent",
        )

        assertFalse(request.allowsEmbeddedWebChallenge())
        assertFalse(request.allowsWebChallengeNavigation(request.url))
        assertFalse(request.allowsWebChallengeSubresource(request.url))
        assertNull(request.appleWebChallengeContentRuleList())
    }

    @Test
    fun reviewedChallengeUsesSeparateExactOriginGrants() {
        val request = reviewedChallengeRequest()

        assertTrue(request.allowsEmbeddedWebChallenge())
        assertTrue(request.allowsWebChallengeNavigation("https://example.test/account/login"))
        assertFalse(request.allowsWebChallengeNavigation("https://cdn.example.test/redirect"))
        assertFalse(request.allowsWebChallengeNavigation("https://example.test.evil.test/"))
        assertTrue(request.allowsWebChallengeSubresource("https://cdn.example.test/challenge.js"))
        assertFalse(request.allowsWebChallengeSubresource("https://127.0.0.1/admin"))
        assertFalse(request.allowsWebChallengeSubresource("https://metadata.google.internal/"))
        assertTrue(request.allowsWebChallengeInternalResource("about:blank", isMainFrame = false))
        assertTrue(request.allowsWebChallengeInternalResource("about:srcdoc", isMainFrame = false))
        assertTrue(request.allowsWebChallengeInternalResource("blob:https://example.test/id", isMainFrame = false))
        assertTrue(request.allowsWebChallengeInternalResource("data:text/plain,fixture", isMainFrame = false))
        assertFalse(request.allowsWebChallengeInternalResource("about:blank", isMainFrame = true))
        assertFalse(request.allowsWebChallengeInternalResource("blob:https://evil.test/id", isMainFrame = false))
        assertFalse(request.allowsWebChallengeInternalResource("file:///etc/passwd", isMainFrame = false))
        assertFalse(request.allowsWebChallengeInternalResource("content://settings/system", isMainFrame = false))
        assertFalse(request.allowsWebChallengeInternalResource("javascript:alert(1)", isMainFrame = false))
    }

    @Test
    fun captureRejectsAnAllowedLoginOriginUntilItReturnsToTheSource() {
        val request = reviewedChallengeRequest(
            navigationOrigins = setOf("https://example.test", "https://cdn.example.test"),
        )
        assertTrue(request.allowsWebChallengeNavigation("https://cdn.example.test/login"))
        assertFalse(request.allowsWebChallengeCapture("https://cdn.example.test/login"))
        assertFalse(request.allowsWebChallengeCapture(null))
        assertFalse(request.allowsWebChallengeCapture("https://example.test.evil.test/"))
        assertTrue(request.allowsWebChallengeCapture("https://example.test/account"))
    }

    @Test
    fun reviewedChallengeRejectsUncanonicalPrivateOrIncompleteGrants() {
        assertFailsWith<IllegalArgumentException> {
            reviewedChallengeRequest(navigationOrigins = setOf("https://EXAMPLE.test"))
        }
        assertFailsWith<IllegalArgumentException> {
            reviewedChallengeRequest(navigationOrigins = setOf("https://example.test/path"))
        }
        assertFailsWith<IllegalArgumentException> {
            reviewedChallengeRequest(
                url = "https://127.0.0.1/challenge",
                navigationOrigins = setOf("https://127.0.0.1"),
                subresourceOrigins = setOf("https://127.0.0.1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            reviewedChallengeRequest(subresourceOrigins = setOf("https://cdn.example.test"))
        }
    }

    @Test
    fun challengeUrlMayContainPathQueryAndFragmentWithoutWideningOriginDeclaration() {
        val request = reviewedChallengeRequest(
            url = "https://example.test/login/path?return=%2Freader#form",
        )

        assertTrue(request.allowsEmbeddedWebChallenge())
        assertTrue(request.allowsWebChallengeNavigation(request.url))
    }

    @Test
    fun appleContentRulesBlockNetworkByDefaultThenAllowOnlyReviewedOrigins() {
        val rules = reviewedChallengeRequest().appleWebChallengeContentRuleList().orEmpty()

        assertTrue(rules.contains("^(?:https?|wss?|ftp)://"))
        assertTrue(rules.contains("^https://example\\\\.test(?::443)?(?:[/?#]|$)"))
        assertTrue(rules.contains("^https://cdn\\\\.example\\\\.test(?::443)?(?:[/?#]|$)"))
        assertFalse(rules.contains("127.0.0.1"))
        assertFalse(rules.contains("metadata.google.internal"))
    }

    @Test
    fun requiredChallengeCookieIsNeverSeededIntoAnIsolatedBrowser() {
        val seeded = webChallengeSeedCookies(
            SourceWebChallengeRequest(
                capability = SourceWebChallengeCapability(),
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

    @Test
    fun browserStorageImportUsesOnlyTheDeclaredBoundedAllowlist() {
        val oversized = "x".repeat(MAX_WEB_CHALLENGE_STORAGE_VALUE_BYTES + 1)
        val normalized = normalizeWebChallengeLocalStorage(
            values = mapOf(
                "token" to "member-token",
                "nonce" to "device-nonce",
                "savedCredentials" to "must-not-cross-boundary",
                "oversized" to oversized,
            ),
            allowlist = listOf("token", "nonce", "oversized"),
        )

        assertEquals(setOf("token", "nonce"), normalized.keys)
        assertEquals(listOf("token", "nonce"), normalizeWebChallengeLocalStorageKeys(listOf(" token ", "nonce", "token")))
        assertTrue(normalizeWebChallengeLocalStorageKeys(listOf("bad key", "bad/key")).isEmpty())
    }

    @Test
    fun browserStorageCaptureRejectsOriginFailureAndDoubleEncodedAndroidResult() {
        val allowed = listOf("token", "nonce")
        val androidEncoded = "\"{\\\"ok\\\":true,\\\"values\\\":{\\\"token\\\":\\\"member-token\\\",\\\"other\\\":\\\"blocked\\\"}}\""
        val captured = decodeWebChallengeLocalStorageCapture(androidEncoded, allowed)

        assertEquals(mapOf("token" to "member-token"), captured.values)
        assertNull(captured.error)
        assertTrue(
            decodeWebChallengeLocalStorageCapture(
                """{"ok":false,"error":"origin"}""",
                allowed,
            ).error.orEmpty().contains("origin"),
        )
    }

    @Test
    fun browserStorageCaptureIsBoundedBeforeJsonDecode() {
        val oversizedWireValue = "x".repeat(MAX_WEB_CHALLENGE_STORAGE_CAPTURE_BYTES + 1)

        val captured = decodeWebChallengeLocalStorageCapture(oversizedWireValue, listOf("token"))
        val script = webChallengeLocalStorageCaptureScript(reviewedChallengeRequest())

        assertTrue(captured.values.isEmpty())
        assertTrue(captured.error.orEmpty().contains("could not be read"))
        assertTrue(script.contains("const maxValueBytes = $MAX_WEB_CHALLENGE_STORAGE_VALUE_BYTES"))
        assertTrue(script.contains("const maxTotalBytes = $MAX_WEB_CHALLENGE_STORAGE_TOTAL_BYTES"))
        assertTrue(script.contains("boundedUtf8Length"))
    }

    @Test
    fun automaticLoginAndStorageScriptsDoNotExposeValuesThroughRequestToString() {
        val request = SourceWebChallengeRequest(
            capability = SourceWebChallengeCapability(),
            sourceId = 1L,
            sourceName = "Fixture",
            url = "https://example.test/",
            userAgent = "fixture-agent",
            localStorageKeys = listOf("token", "nonce"),
            requiredLocalStorageKeys = setOf("token"),
            username = "member@example.test",
            password = "fixture-password",
        )

        val loginScript = automaticWebChallengeLoginScript(request).orEmpty()
        val storageScript = webChallengeLocalStorageCaptureScript(request)
        assertTrue(loginScript.contains("member@example.test"))
        assertTrue(storageScript.contains("token"))
        assertFalse(request.toString().contains("member@example.test"))
        assertFalse(request.toString().contains("fixture-password"))
        assertFalse(request.toString().contains("token"))
    }

    private fun reviewedChallengeRequest(
        url: String = "https://example.test/challenge",
        navigationOrigins: Set<String> = setOf("https://example.test"),
        subresourceOrigins: Set<String> = setOf("https://example.test", "https://cdn.example.test"),
    ) = SourceWebChallengeRequest(
        capability = SourceWebChallengeCapability(),
        sourceId = 1L,
        sourceName = "Reviewed",
        url = url,
        userAgent = "fixture-agent",
        embeddedPolicy = WebChallengeEmbeddedPolicy.ALLOW_REVIEWED_ORIGINS,
        allowedNavigationOrigins = navigationOrigins,
        allowedSubresourceOrigins = subresourceOrigins,
    )
}
