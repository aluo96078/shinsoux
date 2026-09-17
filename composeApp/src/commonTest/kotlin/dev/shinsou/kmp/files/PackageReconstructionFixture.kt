package dev.shinsou.kmp.files

import dev.shinsou.kmp.plugin.FilePluginPackageStore
import dev.shinsou.kmp.plugin.InstalledPluginMetadata
import dev.shinsou.kmp.plugin.PluginManifest
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.plugin.StoredPlugin
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

internal suspend fun verifyPackageReconstruction(newFileSystem: () -> AppFileSystem) {
    val bytes = "var source = {};".encodeToByteArray()
    val hash = Sha256.hex(bytes)
    val original = StoredPlugin(
        InstalledPluginMetadata(
            PluginManifest("all.fixture", "Fixture", "1.0", lang = "all",
                script = "all.fixture.js", signature = hash),
            repositoryBaseUrl = "https://fixture.example", installedSha256 = hash,
        ), bytes,
    )
    FilePluginPackageStore(newFileSystem()).put(original)
    val files = newFileSystem()
    val expected = setOf(
        "plugins/packages/all.fixture/package.json",
        "plugins/packages/all.fixture/script-$hash.js",
        "plugins/packages/all.fixture/.file-store-authoritative-v1",
    )
    assertEquals(expected, files.list("plugins/packages").toSet())
    assertEquals(expected, files.list("plugins/packages", 3).toSet())
    assertFailsWith<IllegalArgumentException> { files.list("plugins/packages", 2) }
    val restored = assertNotNull(FilePluginPackageStore(newFileSystem()).get("all.fixture"))
    assertEquals(original.metadata, restored.metadata)
    assertContentEquals(bytes, restored.scriptBytes)
}
