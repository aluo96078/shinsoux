package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginContentTypeTest {
    @Test
    fun installedImageSequenceManifestWithoutSourceTypeIsManga() {
        val manifest = PluginManifest(
            id = "zh.manhuagui",
            name = "漫畫櫃",
            version = "1.0.0",
            versionCode = 1,
            lang = "zh",
            script = "zh.manhuagui.js",
            signature = "a".repeat(64),
            sources = listOf(
                SourceIndexEntry(
                    name = "漫畫櫃",
                    lang = "zh",
                    id = 1L,
                    baseUrl = "https://www.manhuagui.com",
                ),
            ),
            contentKinds = setOf("IMAGE_SEQUENCE"),
        )
        assertEquals(PluginContentType.MANGA, manifest.installedContentType())
    }

    @Test
    fun installedMixedKindsRemainBoth() {
        val manifest = PluginManifest(
            id = "example.dual",
            name = "Dual",
            version = "1.0.0",
            lang = "zh",
            script = "example.dual.js",
            signature = "b".repeat(64),
            sources = listOf(
                SourceIndexEntry(name = "Novel", lang = "zh", id = 1L, contentType = "novel"),
                SourceIndexEntry(name = "Manga", lang = "zh", id = 2L, contentType = "manga"),
            ),
            contentKinds = setOf("IMAGE_SEQUENCE", "PLAIN_TEXT"),
        )
        assertEquals(PluginContentType.BOTH, manifest.installedContentType())
    }

    @Test
    fun untypedRepositoryIndexKeepsAnInstalledMangaPackageAsManga() {
        val installed = PluginManifest(
            id = "zh.manhuagui",
            name = "漫畫櫃",
            version = "1.0.0",
            versionCode = 1,
            lang = "zh",
            script = "zh.manhuagui.js",
            signature = "c".repeat(64),
            sources = listOf(
                SourceIndexEntry(
                    name = "漫畫櫃",
                    lang = "zh",
                    id = 1L,
                    baseUrl = "https://www.manhuagui.com",
                ),
            ),
            contentKinds = setOf("IMAGE_SEQUENCE"),
        )
        val fromIndex = PluginContentType.resolve(
            packageType = null,
            sourceTypes = installed.sources.orEmpty().map { it.contentType ?: it.type },
        )
        assertEquals(PluginContentType.BOTH, fromIndex)
        assertEquals(
            PluginContentType.MANGA,
            fromIndex.withInstalledFallback(installed.installedContentType()),
        )
    }
}
