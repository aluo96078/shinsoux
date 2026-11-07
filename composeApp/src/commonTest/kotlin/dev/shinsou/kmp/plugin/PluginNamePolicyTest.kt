package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginNamePolicyTest {
    @Test
    fun namesClaimingToBeOfficialAreRefusedInEverySpelling() {
        for (name in listOf("Shinsou Official", "shinsou_offical", "ＯＦＦＩＣＩＡＬ Plugins", "官方插件", "官 方", "O-f-f-i-c-i-a-l", "公式ソース", "Unofficial Official mirror")) {
            assertTrue(PluginNamePolicy.isMisleading(name), name)
        }
        for (name in listOf("Shinsou Community Plugins", "MangaDex", "輕小說文庫", "Office Reader", "Unofficial Manga Sources", "非官方插件", "非公式")) {
            assertFalse(PluginNamePolicy.isMisleading(name), name)
        }
        assertFailsWith<MisleadingPluginNameException> { PluginNamePolicy.requireNotMisleading("Fine", null, "Official mirror") }
    }
}
