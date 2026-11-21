package dev.shinsou.kmp.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineModelCatalogTest {
    @Test
    fun languageTagsMapToTheEnginesLanguages() {
        assertEquals("zh-Hant", OfflineModelCatalog.language("zh-TW"))
        assertEquals("zh-Hant", OfflineModelCatalog.language("zh_Hant_HK"))
        assertEquals("zh-Hans", OfflineModelCatalog.language("zh"))
        assertEquals("zh-Hans", OfflineModelCatalog.language("zh-CN"))
        assertEquals("ja", OfflineModelCatalog.language("ja-JP"))
        assertEquals("en", OfflineModelCatalog.language("en-US"))
        assertNull(OfflineModelCatalog.language("fr"))
    }

    @Test
    fun everyPairGoesThroughEnglishAndUnsupportedPairsHaveNoRoute() {
        assertEquals(listOf("ja" to "en", "en" to "zh-Hant"), OfflineModelCatalog.route("ja", "zh-TW")?.map { it.from to it.to })
        assertEquals(listOf("ko" to "en"), OfflineModelCatalog.route("ko", "en")?.map { it.from to it.to })
        assertEquals(listOf("zh-Hans" to "en", "en" to "zh-Hant"), OfflineModelCatalog.route("zh-CN", "zh-TW")?.map { it.from to it.to })
        assertNull(OfflineModelCatalog.route("ja", "ja"))
        assertNull(OfflineModelCatalog.route("zh-TW", "zh-HK"))
        assertNull(OfflineModelCatalog.route("fr", "en"))
    }

    @Test
    fun everyFileIsPinnedAndEngineIdentitiesAreDistinct() {
        val models = OfflineModelCatalog.models
        assertEquals(models.size, models.map { it.engine }.toSet().size)
        for (model in models) {
            assertTrue(model.from == "en" || model.to == "en", model.id)
            for (file in model.files) {
                assertTrue(Regex("[0-9a-f]{64}").matches(file.sha256), file.name)
                assertTrue(file.size > 0 && file.location.isNotBlank(), file.name)
            }
        }
    }
}
