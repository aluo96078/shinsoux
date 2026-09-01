package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.files.AppFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FilePluginPackageStoreTest {
    @Test
    fun cancellationWhileReadingPackageIsNotTreatedAsCorruption() = runTest {
        val files = PackageMemoryFileSystem()
        files.write("plugins/packages/all.files/package.json", byteArrayOf(1))
        files.cancelReads = true

        assertFailsWith<CancellationException> {
            FilePluginPackageStore(files).list()
        }
    }

    @Test
    fun migratesLegacyKvPackageToIndependentFilesAndSurvivesReconstruction() = runTest {
        val bytes = "var source = { baseUrl: 'https://example.test' };".encodeToByteArray()
        val hash = Sha256.hex(bytes)
        val plugin = StoredPlugin(
            metadata = InstalledPluginMetadata(
                manifest = PluginManifest(
                    id = "all.files",
                    name = "Files",
                    version = "1.0.0",
                    versionCode = 1,
                    lang = "all",
                    script = "all.files.js",
                    signature = hash,
                ),
                repositoryBaseUrl = "https://repo.example",
                installedSha256 = hash,
            ),
            scriptBytes = bytes,
        )
        val keyValues = InMemoryPluginKeyValueStore()
        val legacy = KeyValuePluginPackageStore(keyValues)
        legacy.put(plugin)
        val files = PackageMemoryFileSystem()

        val migrated = assertNotNull(FilePluginPackageStore(files, legacy).get("all.files"))
        assertContentEquals(bytes, migrated.scriptBytes)
        assertTrue(legacy.list().isEmpty())
        assertTrue(files.paths.any { it.endsWith("/package.json") })
        assertTrue(files.paths.any { it.endsWith(".js") })

        val reconstructed = assertNotNull(FilePluginPackageStore(files).get("all.files"))
        assertEquals(plugin.metadata, reconstructed.metadata)
        assertContentEquals(bytes, reconstructed.scriptBytes)
    }

    @Test
    fun obsoleteScriptCleanupFailureCannotRollbackCommittedPackageOrCache() = runTest {
        val files = PackageMemoryFileSystem()
        val store = FilePluginPackageStore(files)
        val original = storedPlugin("old script")
        val updated = storedPlugin("new script", versionCode = 2)
        store.put(original)
        files.failScriptDeletes = true

        store.put(updated)

        assertContentEquals(updated.scriptBytes, assertNotNull(store.get(updated.manifest.id)).scriptBytes)
        assertContentEquals(
            updated.scriptBytes,
            assertNotNull(FilePluginPackageStore(files).get(updated.manifest.id)).scriptBytes,
        )
    }

    @Test
    fun failedTreeDeletionRetainsCacheWhileMetadataStillExists() = runTest {
        val files = PackageMemoryFileSystem()
        val store = FilePluginPackageStore(files)
        val plugin = storedPlugin("installed script")
        store.put(plugin)
        files.failTreeDeletes = true

        assertFailsWith<IllegalStateException> { store.remove(plugin.manifest.id) }

        assertContentEquals(plugin.scriptBytes, assertNotNull(store.get(plugin.manifest.id)).scriptBytes)
        assertContentEquals(
            plugin.scriptBytes,
            assertNotNull(FilePluginPackageStore(files).get(plugin.manifest.id)).scriptBytes,
        )
    }

    @Test
    fun reconstructsPackageWithEventPermissionMetadata() = runTest {
        val files = PackageMemoryFileSystem()
        val scriptFile = "script-932d74b2d0ef1df660d8f491a64662d26023955fc8e77b995f00f4b15d259cb8.js"
        files.write(
            "plugins/packages/zh.bika/package.json",
            """{"metadata":{"manifest":{"id":"zh.bika","name":"哔咔漫画","version":"1.0.8","versionCode":9,"lang":"zh","nsfw":true,"script":"zh.bika.js","signature":"932d74b2d0ef1df660d8f491a64662d26023955fc8e77b995f00f4b15d259cb8","sources":[{"name":"哔咔漫画","lang":"zh","id":8123456,"baseUrl":"https://manhuabika.com","contentType":"manga"}],"systemEvents":{"minVersion":1,"maxVersion":1,"optional":["command.auth.login.request"]},"requestedHostPermissions":["REQUEST_LOGIN_UI"]},"repositoryBaseUrl":"http://127.0.0.1:18082","installedSha256":"932d74b2d0ef1df660d8f491a64662d26023955fc8e77b995f00f4b15d259cb8"},"scriptFile":"$scriptFile"}""".encodeToByteArray(),
        )
        files.write("plugins/packages/zh.bika/$scriptFile", ByteArray(31_982))

        val reconstructed = assertNotNull(FilePluginPackageStore(files).get("zh.bika"))

        assertEquals("1.0.8", reconstructed.manifest.version)
        assertEquals(9, reconstructed.manifest.versionCode)
        assertEquals(31_982, reconstructed.scriptBytes.size)
    }

    private fun storedPlugin(script: String, versionCode: Int = 1): StoredPlugin {
        val bytes = script.encodeToByteArray()
        val hash = Sha256.hex(bytes)
        return StoredPlugin(
            metadata = InstalledPluginMetadata(
                manifest = PluginManifest(
                    id = "all.files",
                    name = "Files",
                    version = "1.0.$versionCode",
                    versionCode = versionCode,
                    lang = "all",
                    script = "all.files.js",
                    signature = hash,
                ),
                repositoryBaseUrl = "https://repo.example",
                installedSha256 = hash,
            ),
            scriptBytes = bytes,
        )
    }
}

private class PackageMemoryFileSystem : AppFileSystem {
    private val values = linkedMapOf<String, ByteArray>()
    val paths: Set<String> get() = values.keys
    var cancelReads: Boolean = false
    var failScriptDeletes: Boolean = false
    var failTreeDeletes: Boolean = false

    override suspend fun write(relativePath: String, bytes: ByteArray) {
        values[relativePath] = bytes.copyOf()
    }

    override suspend fun writeAtomically(relativePath: String, bytes: ByteArray) {
        write(relativePath, bytes)
    }

    override suspend fun read(relativePath: String): ByteArray? {
        if (cancelReads) throw CancellationException("cancelled read")
        return values[relativePath]?.copyOf()
    }

    override suspend fun exists(relativePath: String): Boolean = relativePath in values

    override suspend fun delete(relativePath: String): Boolean {
        if (failScriptDeletes && relativePath.endsWith(".js")) error("script cleanup failed")
        return values.remove(relativePath) != null
    }

    override suspend fun deleteTree(relativeDirectory: String): Boolean {
        if (failTreeDeletes) return false
        val prefix = relativeDirectory.trimEnd('/') + "/"
        val removed = values.keys.filter { it == relativeDirectory || it.startsWith(prefix) }
        removed.forEach(values::remove)
        return removed.isNotEmpty()
    }

    override suspend fun list(relativeDirectory: String): List<String> {
        val prefix = relativeDirectory.trimEnd('/') + "/"
        return values.keys.filter { it.startsWith(prefix) }
    }

    override fun uri(relativePath: String): String = "memory://$relativePath"
}
