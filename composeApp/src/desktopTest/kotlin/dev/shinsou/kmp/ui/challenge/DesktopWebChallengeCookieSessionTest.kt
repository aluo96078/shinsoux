package dev.shinsou.kmp.ui.challenge

import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopWebChallengeCookieSessionTest {
    @Test
    fun packagedWkWebViewHelperIsPresentAndNative() {
        val helper = MacOsWebChallengeHelperLocator().resolve()

        assertTrue(Files.isRegularFile(helper), "WKWebView helper is missing")
        val magic = Files.newInputStream(helper).use { input ->
            ByteArray(4).also { bytes -> assertEquals(bytes.size, input.read(bytes)) }
        }
        assertTrue(
            magic.contentEquals(byteArrayOf(0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte())),
            "WKWebView helper is not a 64-bit Mach-O executable",
        )
    }

    @Test
    fun helperLaunchCommandNeverContainsSecretsOrRequestMetadata() {
        val helper = Path.of("/private/tmp/shinsou-web-challenge")
        val command = webChallengeProcessCommand(helper)
        val rendered = command.joinToString(" ")

        assertEquals(listOf(helper.toAbsolutePath().toString()), command)
        assertFalse(rendered.contains("bilimanga"))
        assertFalse(rendered.contains("cookie"))
        assertFalse(rendered.contains("password"))
        assertFalse(rendered.contains("http"))
    }

    @Test
    fun credentialsTravelOnlyInStdinLaunchProtocol() {
        val username = "member@example.test"
        val password = "fixture-password"
        val helper = Path.of("/private/tmp/shinsou-web-challenge")
        val command = webChallengeProcessCommand(helper).joinToString(" ")
        val launch = webChallengeLaunchLine(
            SourceWebChallengeRequest(
                sourceId = 1L,
                sourceName = "Fixture",
                url = "https://example.test/login.php",
                userAgent = "fixture-agent",
                username = username,
                password = password,
            ),
        )
        val payload = Json.parseToJsonElement(launch).jsonObject

        assertFalse(command.contains(username))
        assertFalse(command.contains(password))
        assertEquals(username, payload.getValue("username").jsonPrimitive.content)
        assertEquals(password, payload.getValue("password").jsonPrimitive.content)
    }

    @Test
    fun incompleteCredentialsAreOmittedFromNativeLaunch() {
        listOf(
            SourceWebChallengeRequest(
                sourceId = 1L,
                sourceName = "Fixture",
                url = "https://example.test/login.php",
                userAgent = "fixture-agent",
            ),
            SourceWebChallengeRequest(
                sourceId = 1L,
                sourceName = "Fixture",
                url = "https://example.test/login.php",
                userAgent = "fixture-agent",
                username = "member",
                password = "",
            ),
        ).forEach { request ->
            val payload = Json.parseToJsonElement(webChallengeLaunchLine(request)).jsonObject

            assertFalse("username" in payload)
            assertFalse("password" in payload)
        }
    }

    @Test
    fun challengeRequestToStringRedactsTransportAndSecrets() {
        val request = SourceWebChallengeRequest(
            sourceId = 1L,
            sourceName = "Fixture",
            url = "https://secret.example.test/login.php",
            userAgent = "secret-browser-agent",
            cookies = listOf(SourceCookie("session", "secret-cookie", "secret.example.test")),
            username = "secret-user",
            password = "secret-password",
        )
        val rendered = request.toString()

        assertTrue(rendered.contains("hasCredentials=true"))
        assertTrue(rendered.contains("cookieCount=1"))
        listOf(
            "secret.example.test",
            "secret-browser-agent",
            "secret-cookie",
            "secret-user",
            "secret-password",
        ).forEach { secret -> assertFalse(rendered.contains(secret)) }
    }

    @Test
    fun requiredChallengeCookieIsNotSeededIntoNativeSession() {
        val staleClearance = "stale-clearance-value"
        val keptSession = "keep-session-value"
        val line = webChallengeLaunchLine(
            SourceWebChallengeRequest(
                sourceId = 1L,
                sourceName = "Fixture",
                url = "https://www.bilimanga.net/login.php",
                userAgent = "fixture-agent",
                cookies = listOf(
                    SourceCookie("cf_clearance", staleClearance, ".bilimanga.net", hostOnly = false),
                    SourceCookie("session", keptSession, ".bilimanga.net", hostOnly = false),
                ),
                requiredCookieName = "cf_clearance",
            ),
        )

        assertFalse(line.contains(staleClearance))
        assertTrue(line.contains(keptSession))
        assertFalse(line.contains("cf_clearance"))
    }

    @Test
    fun nativeLaunchProtocolEscapesSourceTextAsOneJsonLine() {
        val line = webChallengeLaunchLine(
            SourceWebChallengeRequest(
                sourceId = 1L,
                sourceName = "Fixture \"quoted\"\nsource",
                url = "https://www.bilimanga.net/login.php",
                userAgent = "agent\nvalue",
            ),
        )

        assertEquals(1, line.lineSequence().count())
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("\\n"))
        assertTrue(line.contains("\\\"quoted\\\""))
    }

    @Test
    fun nativeLaunchProtocolIncludesCookieDefaultsRequiredBySwiftCodable() {
        val launch = webChallengeLaunchLine(
            SourceWebChallengeRequest(
                sourceId = 1L,
                sourceName = "Fixture",
                url = "https://example.test/login",
                userAgent = "fixture-agent",
                cookies = listOf(SourceCookie("session", "value", "example.test")),
            ),
        )
        val cookie = Json.parseToJsonElement(launch).jsonObject
            .getValue("cookies").jsonArray.single().jsonObject

        assertEquals("/", cookie.getValue("path").jsonPrimitive.content)
        assertEquals("false", cookie.getValue("secure").jsonPrimitive.content)
        assertEquals("false", cookie.getValue("httpOnly").jsonPrimitive.content)
        assertEquals("true", cookie.getValue("hostOnly").jsonPrimitive.content)
    }

    @Test
    fun nativeHelperProtocolCanReturnBrowserUserAgentWithoutPuttingItInProcessArguments() {
        val helperSource = Path.of(
            "src/desktopMain/swift/ShinsouWebChallenge/main.swift",
        ).let { local ->
            if (Files.isRegularFile(local)) local else Path.of("composeApp").resolve(local)
        }
        val sourceText = Files.readString(helperSource)

        assertTrue(sourceText.contains("navigator.userAgent"))
        assertTrue(sourceText.contains("browser.customUserAgent = nil"))
        assertTrue(sourceText.contains("userAgent: userAgent"))
    }

    @Test
    fun nativeHelperUsesArgumentBoundSameOriginAutomaticLogin() {
        val helperSource = Path.of(
            "src/desktopMain/swift/ShinsouWebChallenge/main.swift",
        ).let { local ->
            if (Files.isRegularFile(local)) local else Path.of("composeApp").resolve(local)
        }
        val sourceText = Files.readString(helperSource)

        assertTrue(sourceText.contains("callAsyncJavaScript"))
        assertTrue(sourceText.contains("arguments: [\"username\": username, \"password\": password]"))
        assertTrue(sourceText.contains("action.origin !== location.origin"))
        assertTrue(sourceText.contains("form.requestSubmit"))
        assertTrue(sourceText.contains("didSubmitAutomaticLogin"))
        assertFalse(sourceText.contains("evaluateJavaScript(launch.password"))
    }

    @Test
    fun locatorPrefersPackagedHelperAndMakesPrivateExecutableCopy() {
        val root = Files.createTempDirectory("shinsou-helper-test-")
        val packaged = root.resolve("resources")
        val fallback = root.resolve("fallback-helper")
        Files.createDirectories(packaged)
        Files.write(packaged.resolve("shinsou-web-challenge"), byteArrayOf(1, 2, 3))
        Files.write(fallback, byteArrayOf(9, 8, 7))

        try {
            val locator = MacOsWebChallengeHelperLocator(
                osName = "Mac OS X",
                resourcesDirectory = packaged.toString(),
                developmentCandidates = listOf(fallback),
            )
            assertEquals(packaged.resolve("shinsou-web-challenge"), locator.resolve())

            val copied = locator.prepareExecutableCopy()
            try {
                assertTrue(Files.isExecutable(copied))
                assertTrue(Files.readAllBytes(copied).contentEquals(byteArrayOf(1, 2, 3)))
                assertFalse(copied.startsWith(root), "Runtime copy must not modify packaged resources")
            } finally {
                Files.deleteIfExists(copied)
                Files.deleteIfExists(copied.parent)
            }
        } finally {
            Files.deleteIfExists(packaged.resolve("shinsou-web-challenge"))
            Files.deleteIfExists(packaged)
            Files.deleteIfExists(fallback)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun initializationDiagnosticRedactsUrlAndJsonProtocol() {
        assertEquals(
            "IllegalStateException: native runtime error",
            webChallengeInitializationDiagnostic(
                IllegalStateException("failed https://example.test/{secret}"),
            ),
        )
    }
}
