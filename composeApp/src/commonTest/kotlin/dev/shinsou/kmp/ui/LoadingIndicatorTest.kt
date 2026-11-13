package dev.shinsou.kmp.ui

import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.DeveloperSettings
import dev.shinsou.kmp.ui.components.DeveloperDiagnostics
import dev.shinsou.kmp.ui.components.ElapsedDisplay
import dev.shinsou.kmp.ui.components.diagnosticUrl
import dev.shinsou.kmp.ui.components.elapsedDisplay
import dev.shinsou.kmp.ui.components.toDiagnostics
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LoadingIndicatorTest {
    @Test
    fun elapsedUnderAMinuteUsesOneDecimal() {
        assertEquals(ElapsedDisplay(0, "0.0"), elapsedDisplay(0))
        assertEquals(ElapsedDisplay(0, "3.2"), elapsedDisplay(3_250))
        assertEquals(ElapsedDisplay(0, "59.9"), elapsedDisplay(59_999))
        assertEquals(ElapsedDisplay(0, "0.0"), elapsedDisplay(-5))
    }

    @Test
    fun elapsedOverAMinuteUsesMinutesAndPaddedSeconds() {
        assertEquals(ElapsedDisplay(1, "00"), elapsedDisplay(60_000))
        assertEquals(ElapsedDisplay(1, "05"), elapsedDisplay(65_400))
        assertEquals(ElapsedDisplay(12, "34"), elapsedDisplay(754_000))
    }

    @Test
    fun diagnosticUrlDropsFragmentAndBlank() {
        assertEquals("https://a.example/p/1.jpg", diagnosticUrl("https://a.example/p/1.jpg#User-Agent=x&Referer=y"))
        assertEquals("/p/2.jpg?x=1", diagnosticUrl("/p/2.jpg?x=1"))
        assertNull(diagnosticUrl("#only=headers"))
        assertNull(diagnosticUrl("  "))
        assertNull(diagnosticUrl(null))
    }

    @Test
    fun subSwitchesOnlyApplyWhileDeveloperModeIsOn() {
        assertEquals(DeveloperDiagnostics(), DeveloperSettings().toDiagnostics())
        assertEquals(
            DeveloperDiagnostics(),
            DeveloperSettings(enabled = false, showLoadingElapsed = true, showImageRequests = true).toDiagnostics(),
        )
        assertEquals(
            DeveloperDiagnostics(showLoadingElapsed = true, showImageRequests = false),
            DeveloperSettings(enabled = true, showLoadingElapsed = true).toDiagnostics(),
        )
    }

    @Test
    fun settingsWithoutDeveloperFieldDecodeToDefaults() {
        val json = Json { encodeDefaults = true }
        val encoded = json.parseToJsonElement(json.encodeToString(AppSettings())).jsonObject
        val legacy = JsonObject(encoded.filterKeys { it != "developer" })
        val decoded = json.decodeFromJsonElement(AppSettings.serializer(), legacy)
        assertFalse(decoded.developer.enabled)
        assertEquals(AppSettings(), decoded)
        val restored = dev.shinsou.kmp.data.ShinsouRepository.decodeSnapshot("""{"settings":{"advanced":{}}}""")
        assertEquals(DeveloperSettings(), restored.settings.developer)
    }
}
