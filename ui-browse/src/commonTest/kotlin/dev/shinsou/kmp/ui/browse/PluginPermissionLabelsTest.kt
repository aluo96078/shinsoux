package dev.shinsou.kmp.ui.browse

import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PluginPermissionLabelsTest {
    @Test
    fun pluginPermissionLabelsAreLocalizedForEverySupportedLocale() {
        val locales = listOf("en-US", "zh-TW", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "pt-BR")
        val english = shinsouStringsFor("en-US")
        locales.forEach { locale ->
            val strings = shinsouStringsFor(locale)
            PluginRuntimePermission.entries.forEach { permission ->
                val label = permission.localizedLabel(strings)
                assertFalse(label == permission.name, "$locale exposed ${permission.name}")
                if (locale != "en-US") {
                    assertFalse(label == permission.localizedLabel(english), "$locale fell back for ${permission.name}")
                }
            }
            PluginHostPermission.entries.forEach { permission ->
                val label = permission.localizedLabel(strings)
                assertFalse(label == permission.name, "$locale exposed ${permission.name}")
                if (locale != "en-US") {
                    assertFalse(label == permission.localizedLabel(english), "$locale fell back for ${permission.name}")
                }
            }
        }
        assertEquals("Request login UI", PluginHostPermission.REQUEST_LOGIN_UI.localizedLabel(english))
        assertEquals("Run extension script", PluginRuntimePermission.EXECUTE_SCRIPT.localizedLabel(english))
    }
}
