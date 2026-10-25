package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.files.AppFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun exactV2SourceIdentitySurvivesFileStoreReconstruction() = runTest {
        val files = PackageMemoryFileSystem()
        val original = storedPlugin("exact identity script").let { plugin ->
            plugin.copy(
                metadata = plugin.metadata.copy(
                    manifest = plugin.manifest.copy(
                        contentKinds = setOf("IMAGE_SEQUENCE"),
                        sources = listOf(
                            SourceIndexEntry(
                                name = "Exact source",
                                lang = "en",
                                id = 101L,
                                baseUrl = "https://exact.example",
                                contentKindsDeclared = false,
                                originPolicyVersion = 2,
                                legacyLongId = "101",
                                canonicalSourceId = "exact.source/a",
                            ),
                        ),
                    ),
                ),
            )
        }

        FilePluginPackageStore(files).put(original)

        val reconstructed = assertNotNull(FilePluginPackageStore(files).get(original.manifest.id))
        val source = reconstructed.manifest.sources.orEmpty().single()
        assertEquals("exact.source/a", source.canonicalSourceId)
        assertEquals("101", source.legacyLongId)
        assertEquals(2, source.originPolicyVersion)
        assertEquals(false, source.contentKindsDeclared)
        assertEquals(setOf("IMAGE_SEQUENCE"), reconstructed.manifest.contentKinds)
    }

    @Test
    fun corruptFilePackagePermanentlyShadowsLegacyCopyLeftByCleanupFailure() = runTest {
        val stale = storedPlugin("stale legacy script")
        val updated = storedPlugin("new file script", versionCode = 2)
        val legacyDelegate = InMemoryPluginPackageStore().also { it.put(stale) }
        val legacy = FailingRemovePluginPackageStore(legacyDelegate)
        val files = PackageMemoryFileSystem()
        val migratedStore = FilePluginPackageStore(files, legacy)

        assertContentEquals(stale.scriptBytes, assertNotNull(migratedStore.get(stale.manifest.id)).scriptBytes)
        assertContentEquals(stale.scriptBytes, assertNotNull(legacyDelegate.get(stale.manifest.id)).scriptBytes)
        migratedStore.put(updated)

        val metadataPath = files.paths.single { it.endsWith("/package.json") }
        val markerPath = files.paths.single { it.endsWith("/.file-store-authoritative-v1") }
        val scriptPath = files.paths.single { it.endsWith(".js") }
        val persistedMetadata = assertNotNull(files.read(metadataPath))
        val persistedScript = assertNotNull(files.read(scriptPath))

        files.write(scriptPath, "corrupt script".encodeToByteArray())
        assertNull(FilePluginPackageStore(files, legacy).get(stale.manifest.id))
        assertContentEquals("corrupt script".encodeToByteArray(), files.read(scriptPath))

        files.write(scriptPath, persistedScript)
        files.write(metadataPath, "{corrupt metadata".encodeToByteArray())
        assertNull(FilePluginPackageStore(files, legacy).get(stale.manifest.id))
        assertContentEquals("{corrupt metadata".encodeToByteArray(), files.read(metadataPath))

        files.write(metadataPath, persistedMetadata)
        files.delete(metadataPath)
        assertTrue(files.exists(markerPath))
        assertNull(FilePluginPackageStore(files, legacy).get(stale.manifest.id))
        assertFalse(files.exists(metadataPath))
        assertContentEquals(stale.scriptBytes, assertNotNull(legacyDelegate.get(stale.manifest.id)).scriptBytes)
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
    fun rejectsCallerPackageWhoseTrustMetadataDoesNotMatchItsBytes() = runTest {
        val files = PackageMemoryFileSystem()
        val store = FilePluginPackageStore(files)
        val plugin = storedPlugin("verified script")

        assertFailsWith<IllegalArgumentException> {
            store.put(plugin.copy(scriptBytes = "substituted script".encodeToByteArray()))
        }

        assertTrue(files.paths.isEmpty())
    }

    @Test
    fun rejectsTamperedContentAddressOnReconstruction() = runTest {
        val files = PackageMemoryFileSystem()
        val plugin = storedPlugin("verified script")
        FilePluginPackageStore(files).put(plugin)
        val scriptPath = files.paths.single { it.endsWith(".js") }
        files.write(scriptPath, "tampered script".encodeToByteArray())

        assertNull(FilePluginPackageStore(files).get(plugin.manifest.id))
    }

    @Test
    fun rejectsOversizedPersistedMetadataBeforeDecoding() = runTest {
        val files = PackageMemoryFileSystem()
        files.write(
            "plugins/packages/all.files/package.json",
            ByteArray(MAX_PLUGIN_PACKAGE_METADATA_BYTES + 1) { '{'.code.toByte() },
        )

        assertTrue(FilePluginPackageStore(files).list().isEmpty())
    }

    @Test
    fun packageDiscoveryRequestsABoundedFileListing() = runTest {
        val files = PackageMemoryFileSystem()

        FilePluginPackageStore(files).list()

        assertEquals(MAX_PLUGIN_PACKAGE_COUNT * 4, files.lastListMaximumEntries)
    }

    @Test
    fun rejectsOversizedScriptBeforeWritingIt() = runTest {
        val bytes = ByteArray(MAX_PLUGIN_PACKAGE_SCRIPT_BYTES + 1)
        val hash = Sha256.hex(bytes)
        val oversized = StoredPlugin(
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
                installedSha256 = hash,
            ),
            scriptBytes = bytes,
        )
        val files = PackageMemoryFileSystem()

        assertFailsWith<IllegalArgumentException> { FilePluginPackageStore(files).put(oversized) }
        assertTrue(files.paths.isEmpty())
    }

    @Test
    fun kvStoreReconstructsOnlyTheContentAddressedBytesNamedByItsRecord() = runTest {
        val keyValues = InspectablePluginKeyValueStore()
        val store = KeyValuePluginPackageStore(keyValues)
        val original = storedPlugin("old script")
        val updated = storedPlugin("new script", versionCode = 2)
        store.put(original)
        store.put(updated)

        val recordKey = "plugin.package.${updated.manifest.id}.record.v2"
        val updatedHash = Sha256.hex(updated.scriptBytes)
        keyValues.putString(
            "plugin.package.${updated.manifest.id}.script.$updatedHash.hex",
            "tampered script".encodeToByteArray().toHex(),
        )

        assertTrue(recordKey in keyValues.keys)
        assertNull(KeyValuePluginPackageStore(keyValues).get(updated.manifest.id))
    }

    @Test
    fun kvStoreReadsLegacySplitRecordsForFileMigration() = runTest {
        val keyValues = InspectablePluginKeyValueStore()
        val plugin = storedPlugin("legacy script")
        val id = plugin.manifest.id
        keyValues.putString("plugin.packages.index", "[\"$id\"]")
        keyValues.putString(
            "plugin.package.$id.metadata",
            PluginJson.encodeToString(InstalledPluginMetadata.serializer(), plugin.metadata),
        )
        keyValues.putString("plugin.package.$id.script.hex", plugin.scriptBytes.toHex())

        val reconstructed = assertNotNull(KeyValuePluginPackageStore(keyValues).get(id))

        assertEquals(plugin.metadata, reconstructed.metadata)
        assertContentEquals(plugin.scriptBytes, reconstructed.scriptBytes)
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
        val scriptBytes = ByteArray(31_982)
        val hash = Sha256.hex(scriptBytes)
        val scriptFile = "script-$hash.js"
        files.write(
            "plugins/packages/zh.bika/package.json",
            """{"metadata":{"manifest":{"id":"zh.bika","name":"哔咔漫画","version":"1.0.8","versionCode":9,"lang":"zh","nsfw":true,"script":"zh.bika.js","signature":"$hash","sources":[{"name":"哔咔漫画","lang":"zh","id":8123456,"baseUrl":"https://manhuabika.com","contentType":"manga"}],"systemEvents":{"minVersion":1,"maxVersion":1,"optional":["command.auth.login.request"]},"requestedHostPermissions":["REQUEST_LOGIN_UI"]},"repositoryBaseUrl":"http://127.0.0.1:18082","installedSha256":"$hash"},"scriptFile":"$scriptFile"}""".encodeToByteArray(),
        )
        files.write("plugins/packages/zh.bika/$scriptFile", scriptBytes)

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

private class InspectablePluginKeyValueStore : PluginKeyValueStore {
    private val values = linkedMapOf<String, String>()
    val keys: Set<String> get() = values.keys

    override suspend fun getString(key: String): String? = values[key]
    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

private class FailingRemovePluginPackageStore(
    private val delegate: PluginPackageStore,
) : PluginPackageStore by delegate {
    override suspend fun remove(pluginId: String) {
        throw IllegalStateException("legacy cleanup failed")
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    byte.toUByte().toString(16).padStart(2, '0')
}

private class PackageMemoryFileSystem : AppFileSystem {
    private val values = linkedMapOf<String, ByteArray>()
    val paths: Set<String> get() = values.keys
    var cancelReads: Boolean = false
    var failScriptDeletes: Boolean = false
    var failTreeDeletes: Boolean = false
    var lastListMaximumEntries: Int? = null

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

    override suspend fun list(relativeDirectory: String, maximumEntries: Int): List<String> {
        lastListMaximumEntries = maximumEntries
        return super.list(relativeDirectory, maximumEntries)
    }

    override fun uri(relativePath: String): String = "memory://$relativePath"
}
