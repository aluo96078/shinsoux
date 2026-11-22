@file:OptIn(dev.shinsou.kmp.testing.TestOnlyApi::class)

package dev.shinsou.kmp.ui.challenge

import dev.shinsou.kmp.plugin.PluginTestAccess
import dev.shinsou.kmp.contract.SourceCookie
import dev.shinsou.kmp.plugin.contracts.SourceWebChallengeCapability
import dev.shinsou.kmp.plugin.contracts.SourceWebChallengeRequest
import dev.shinsou.kmp.contract.WebChallengeEmbeddedPolicy
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
import dev.shinsou.kmp.plugin.browser.webChallengeProcessCommand

class DesktopWebChallengeCookieSessionTest {
    @Test
    fun credentialsTravelOnlyInStdinLaunchProtocol() {
        val username = "member@example.test"
        val password = "fixture-password"
        val helper = Path.of("/private/tmp/shinsou-web-challenge")
        val command = webChallengeProcessCommand(helper).joinToString(" ")
        val launch = webChallengeLaunchLine(
            SourceWebChallengeRequest(
                capability = PluginTestAccess.webChallengeCapability(),
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
                capability = PluginTestAccess.webChallengeCapability(),
                sourceId = 1L,
                sourceName = "Fixture",
                url = "https://example.test/login.php",
                userAgent = "fixture-agent",
            ),
            SourceWebChallengeRequest(
                capability = PluginTestAccess.webChallengeCapability(),
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
            capability = PluginTestAccess.webChallengeCapability(),
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
                capability = PluginTestAccess.webChallengeCapability(),
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
                capability = PluginTestAccess.webChallengeCapability(),
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
                capability = PluginTestAccess.webChallengeCapability(),
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
    fun nativeLaunchIncludesOnlyDeclaredStorageKeyNamesAndNoValues() {
        val line = webChallengeLaunchLine(
            SourceWebChallengeRequest(
                capability = PluginTestAccess.webChallengeCapability(),
                sourceId = 1L,
                sourceName = "Fixture",
                url = "https://example.test/",
                userAgent = "fixture-agent",
                localStorageKeys = listOf("token", "nonce", "bad key"),
            ),
        )
        val payload = Json.parseToJsonElement(line).jsonObject
        val keys = payload.getValue("localStorageKeys").jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("token", "nonce"), keys)
        assertFalse(line.contains("member-token"))
    }

    @Test
    fun nativeLaunchCarriesReviewedOriginCapability() {
        val launch = webChallengeLaunchLine(
            SourceWebChallengeRequest(
                capability = PluginTestAccess.webChallengeCapability(),
                sourceId = 1L,
                sourceName = "Reviewed",
                url = "https://example.test/challenge",
                userAgent = "fixture-agent",
                embeddedPolicy = WebChallengeEmbeddedPolicy.ALLOW_REVIEWED_ORIGINS,
                allowedNavigationOrigins = setOf("https://example.test"),
                allowedSubresourceOrigins = setOf("https://cdn.example.test", "https://example.test"),
            ),
        )
        val payload = Json.parseToJsonElement(launch).jsonObject

        assertEquals("ALLOW_REVIEWED_ORIGINS", payload.getValue("embeddedPolicy").jsonPrimitive.content)
        assertEquals(
            listOf("https://example.test"),
            payload.getValue("allowedNavigationOrigins").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("https://cdn.example.test", "https://example.test"),
            payload.getValue("allowedSubresourceOrigins").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun nativeHelperProtocolCanReturnBrowserUserAgentWithoutPuttingItInProcessArguments() {
        val helperSource = Path.of(
            "src/desktopMain/swift/ShinsouWebChallenge/main.swift",
        ).let { local ->
            if (Files.isRegularFile(local)) local else Path.of("../composeApp").resolve(local)
        }
        val sourceText = Files.readString(helperSource)

        assertTrue(sourceText.contains("navigator.userAgent"))
        assertTrue(sourceText.contains("browser.customUserAgent = nil"))
        assertTrue(sourceText.contains("userAgent: userAgent"))
    }

    @Test
    fun nativeHelperKeepsLegacyChallengeAndAddsHeadlessBrowserSessionProtocol() {
        val helperSource = Path.of(
            "src/desktopMain/swift/ShinsouWebChallenge/main.swift",
        ).let { local ->
            if (Files.isRegularFile(local)) local else Path.of("../composeApp").resolve(local)
        }
        val sourceText = Files.readString(helperSource)

        assertTrue(sourceText.contains("?? \"challenge\""), "Legacy launch payload must default to challenge mode")
        assertTrue(sourceText.contains("launch.mode == \"browserSession\""))
        assertTrue(sourceText.contains("NSApp.setActivationPolicy(.prohibited)"))
        assertTrue(sourceText.contains("case \"evaluate\":"))
        assertTrue(sourceText.contains("type: \"evaluated\""))
        assertTrue(sourceText.contains("id: id"), "Evaluation replies must correlate with their command ID")
        assertTrue(sourceText.contains("script.utf8.count <= 4_194_304"))
        assertTrue(sourceText.contains("BoundedLineReader"))
        assertTrue(sourceText.contains("helperEventMaxBytes"))
        assertFalse(sourceText.contains("readLine(strippingNewline:"))
    }

    @Test
    fun desktopHelperReadersUseBoundedFramesForStdoutAndStderr() {
        val challengeSource = Path.of(
            "src/desktopMain/kotlin/dev/shinsou/kmp/ui/challenge/PlatformWebChallengeView.desktop.kt",
        ).let { local -> if (Files.isRegularFile(local)) local else Path.of("ui-browse").resolve(local) }
        // R5-1: the plugin transport is in :feature-plugin, next to this module.
        val sessionSource = Path.of(
            "feature-plugin/src/desktopMain/kotlin/dev/shinsou/kmp/plugin/browser/DesktopPluginBrowserSessionTransport.kt",
        ).let { fromRoot -> Path.of("..").resolve(fromRoot).takeIf(Files::isRegularFile) ?: fromRoot }

        listOf(Files.readString(challengeSource), Files.readString(sessionSource)).forEach { source ->
            assertTrue(source.contains("BoundedUtf8LineReader"))
            assertFalse(source.contains("BufferedReader"))
            assertFalse(source.contains("useLines"))
        }
        val sessionText = Files.readString(sessionSource)
        assertTrue(sessionText.contains("Native browser-session request failed"))
        assertFalse(sessionText.contains("event.message?.takeIf"))
    }

    @Test
    fun nativeHelperUsesArgumentBoundSameOriginAutomaticLogin() {
        val helperSource = Path.of(
            "src/desktopMain/swift/ShinsouWebChallenge/main.swift",
        ).let { local ->
            if (Files.isRegularFile(local)) local else Path.of("../composeApp").resolve(local)
        }
        val sourceText = Files.readString(helperSource)

        assertTrue(sourceText.contains("callAsyncJavaScript"))
        assertTrue(sourceText.contains("\"username\": username"))
        assertTrue(sourceText.contains("\"password\": password"))
        assertTrue(sourceText.contains("action.origin !== location.origin"))
        assertTrue(sourceText.contains("form.requestSubmit"))
        assertTrue(sourceText.contains("didSubmitAutomaticLogin"))
        assertTrue(sourceText.contains("MutationObserver"))
        assertFalse(sourceText.contains("evaluateJavaScript(launch.password"))
        assertTrue(sourceText.contains("WKContentRuleListStore.default()"))
        assertTrue(sourceText.contains("installContentRulesAndStart()"))
        assertTrue(sourceText.contains("launch.mode == \"browserSession\""))
        assertTrue(sourceText.contains("loadHTMLString"))
        assertTrue(sourceText.contains("allowedNavigationOrigins.contains"))
        assertTrue(sourceText.contains("allowedSubresourceOrigins"))
        assertTrue(sourceText.contains("navigationOrigins.isSubset(of: subresourceOrigins)"))
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

    @Test
    fun challengeUiDoesNotProjectHelperControlledDiagnostics() {
        val source = Path.of(
            "src/desktopMain/kotlin/dev/shinsou/kmp/ui/challenge/PlatformWebChallengeView.desktop.kt",
        ).let { local -> if (Files.isRegularFile(local)) local else Path.of("ui-browse").resolve(local) }
            .let { Files.readString(it) }

        assertTrue(source.contains("dispatchError(\"The native browser could not be initialized.\")"))
        assertTrue(source.contains("dispatchError(\"The native browser stopped unexpectedly.\")"))
        assertTrue(source.contains("\"error\" -> dispatchError(\"The native browser encountered an error.\")"))
        assertFalse(source.contains("stopped unexpectedly\$suffix"))
        assertFalse(source.contains("event.message?.let(::sanitizeDiagnosticLine)"))
    }
}
