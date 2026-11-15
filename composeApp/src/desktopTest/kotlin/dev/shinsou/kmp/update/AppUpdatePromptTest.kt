package dev.shinsou.kmp.update

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.shinsou.kmp.domain.model.UpdateSettings
import dev.shinsou.kmp.ui.AppUpdateHost
import dev.shinsou.kmp.ui.AppUpdateState
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import org.junit.Rule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppUpdatePromptTest {
    @get:Rule
    val rule = createComposeRule()

    private val listing = """[{"tag_name":"v1.0.2-beta.2","name":"Shinsou X 1.0.2-beta.2","html_url":"https://github.com/aluo96078/shinsoux/releases/tag/v1.0.2-beta.2","body":"- 漫畫翻譯\n- 檢查更新","draft":false,"prerelease":true},
        {"tag_name":"v1.0.1","name":"Shinsou X 1.0.1","html_url":"https://github.com/aluo96078/shinsoux/releases/tag/v1.0.1","body":"","draft":false,"prerelease":false}]"""

    @Test
    fun theUserIsOfferedTheNewestBetaAndMaySkipIt() {
        val http = HttpClient(MockEngine { respond(listing, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val state = AppUpdateState(AppUpdateChecker(http, "1.0.1"))
        var settings by mutableStateOf(UpdateSettings(checkForUpdates = true, includeBeta = true))
        val opened = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalShinsouStrings provides shinsouStringsFor("zh-TW")) {
                MaterialTheme {
                    Surface {
                        AppUpdateHost(state, settings, onSkip = { settings = settings.copy(skippedVersion = it) }, onOpenUrl = { opened += it })
                    }
                }
            }
        }
        rule.waitUntil(10_000) { state.prompt != null }
        rule.onNodeWithText("Shinsou X 1.0.2-beta.2 已推出").assertExists()
        rule.onNodeWithText("目前版本：1.0.1。").assertExists()
        File("build/update-test").apply { mkdirs() }.resolve("update-prompt-zh-TW.png").writeBytes(
            org.jetbrains.skia.Image.makeFromBitmap(rule.onNode(isDialog()).captureToImage().asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes,
        )
        rule.onNodeWithText("略過這個版本").performClick()
        rule.waitForIdle()
        assertEquals("1.0.2-beta.2", settings.skippedVersion)
        assertEquals(null, state.prompt)
        assertEquals(emptyList(), opened)
    }

    private var requests = 0
    private val counting = HttpClient(MockEngine { requests++; respond(listing, headers = headersOf(HttpHeaders.ContentType, "application/json")) })

    @Test
    fun switchingChecksOnDuringALaunchWaitsForTheNextLaunch() {
        var settings by mutableStateOf(UpdateSettings(checkForUpdates = false))
        var shown by mutableStateOf(true)
        val state = AppUpdateState(AppUpdateChecker(counting, "1.0.1"))
        rule.setContent { if (shown) AppUpdateHost(state, settings, onSkip = {}, onOpenUrl = {}) }
        rule.waitForIdle()
        settings = settings.copy(checkForUpdates = true)
        // even a screen built again (a rotated activity) does not check during this launch
        shown = false; rule.waitForIdle(); shown = true; rule.waitForIdle()
        assertEquals(0, requests)
        assertEquals(null, state.prompt)
    }

    @Test
    fun aLaunchChecksOnceHoweverOftenItsScreenIsBuilt() {
        var shown by mutableStateOf(true)
        val state = AppUpdateState(AppUpdateChecker(counting, "1.0.1"))
        val settings = UpdateSettings(checkForUpdates = true, includeBeta = true)
        rule.setContent { if (shown) AppUpdateHost(state, settings, onSkip = {}, onOpenUrl = {}) }
        rule.waitUntil(10_000) { state.prompt != null }
        assertEquals("v1.0.2-beta.2", state.prompt?.tag)
        shown = false; rule.waitForIdle(); shown = true; rule.waitForIdle()
        assertEquals(1, requests)
    }
}
