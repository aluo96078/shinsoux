package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.files.AppFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Stores each extension independently instead of embedding every script in the shared settings
 * JSON. The metadata file is the commit point: a new content-addressed script is written first,
 * then metadata atomically switches to it. A crash can leave only an unreferenced script, never a
 * partially replaced installed package.
 *
 * [legacyStore] is read on first access so existing KeyValuePluginPackageStore installations move
 * to files without user action. Legacy entries are removed only after their file commit succeeds.
 */
public class FilePluginPackageStore(
    private val fileSystem: AppFileSystem,
    private val legacyStore: PluginPackageStore? = null,
    private val json: Json = PluginJson,
) : PluginPackageStore {
    private val mutex = Mutex()
    private var cachedPlugins: MutableMap<String, StoredPlugin>? = null

    override suspend fun list(): List<StoredPlugin> = mutex.withLock {
        loadPlugins().values.map(::copyPlugin)
    }

    override suspend fun get(pluginId: String): StoredPlugin? = mutex.withLock {
        loadPlugins()[pluginId]?.let(::copyPlugin)
    }

    override suspend fun put(plugin: StoredPlugin): Unit = mutex.withLock {
        val plugins = loadPlugins()
        val previous = plugins[plugin.manifest.id]
        // Once the metadata commit starts, publish the matching cache entry even if the caller is
        // cancelled while a platform dispatcher returns from its atomic replace.
        val obsoleteScript = withContext(NonCancellable) {
            val obsolete = persistPlugin(plugin, previous)
            plugins[plugin.manifest.id] = copyPlugin(plugin)
            obsolete
        }
        // package.json is the commit point. Failure to collect the now-unreferenced script must
        // neither roll back that commit nor make PluginManager discard the matching live runtime.
        cleanupPreviousScript(plugin.manifest.id, obsoleteScript)
    }

    override suspend fun remove(pluginId: String): Unit = mutex.withLock {
        PluginVerifier.validateSafeFileComponent(pluginId)
        val plugins = loadPlugins()
        // Remove a not-yet-cleaned legacy copy first so a failed file deletion cannot resurrect it
        // during the next migration pass.
        legacyStore?.remove(pluginId)
        withContext(NonCancellable) {
            val directory = pluginDirectory(pluginId)
            val metadataPath = "$directory/$METADATA_FILE"
            val deletionFailure = try {
                if (fileSystem.deleteTree(directory)) null
                else IllegalStateException("Unable to delete plugin package: $pluginId")
            } catch (error: Exception) {
                error
            }
            // Some platform APIs report a partial directory-cleanup failure after package.json
            // has already gone. The package is uninstalled at that point; leftover scripts are
            // harmless and can never be rediscovered. If metadata remains, retain the cache entry
            // and surface the failure so this process cannot disagree with the next one.
            if (deletionFailure != null && fileSystem.exists(metadataPath)) throw deletionFailure
            plugins.remove(pluginId)
        }
    }

    private suspend fun loadPlugins(): MutableMap<String, StoredPlugin> {
        cachedPlugins?.let { return it }

        val loaded = linkedMapOf<String, StoredPlugin>()
        fileSystem.list(ROOT_DIRECTORY)
            .filter { it.endsWith("/$METADATA_FILE") }
            .sorted()
            .forEach { path ->
                readPlugin(path)?.let { plugin -> loaded[plugin.manifest.id] = plugin }
            }

        // Migrate packages independently. One corrupt package never blocks healthy extensions.
        legacyStore?.list().orEmpty().forEach { legacyPlugin ->
            val id = legacyPlugin.manifest.id
            if (id !in loaded) {
                try {
                    withContext(NonCancellable) {
                        persistPlugin(legacyPlugin, previous = null)
                        loaded[id] = copyPlugin(legacyPlugin)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@forEach
                }
            }
            // Cleanup is retryable and must not hide a successfully migrated file package.
            try {
                legacyStore?.remove(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A future process reconstruction retries cleanup.
            }
        }

        cachedPlugins = loaded
        return loaded
    }

    private suspend fun readPlugin(metadataPath: String): StoredPlugin? = try {
        val relative = metadataPath.removePrefix("$ROOT_DIRECTORY/")
        val pluginId = relative.substringBefore('/')
        require(relative == "$pluginId/$METADATA_FILE") { "Unexpected package metadata path" }
        PluginVerifier.validateSafeFileComponent(pluginId)

        val metadataBytes = requireNotNull(fileSystem.read(metadataPath))
        val record = json.decodeFromString(
            FilePluginRecord.serializer(),
            metadataBytes.decodeToString(),
        )
        require(record.metadata.manifest.id == pluginId) { "Package metadata id mismatch" }
        val scriptBytes = record.scriptFile?.let { scriptFile ->
            PluginVerifier.validateSafeFileName(scriptFile)
            requireNotNull(fileSystem.read("${pluginDirectory(pluginId)}/$scriptFile"))
        } ?: ByteArray(0)
        StoredPlugin(record.metadata, scriptBytes)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun persistPlugin(plugin: StoredPlugin, previous: StoredPlugin?): String? {
        val pluginId = plugin.manifest.id
        PluginVerifier.validateSafeFileComponent(pluginId)
        val directory = pluginDirectory(pluginId)
        val previousScriptFile = previous?.scriptBytes
            ?.takeIf(ByteArray::isNotEmpty)
            ?.let { "script-${Sha256.hex(it)}.js" }
        val scriptFile = if (plugin.scriptBytes.isEmpty()) {
            null
        } else {
            "script-${Sha256.hex(plugin.scriptBytes)}.js"
        }

        if (scriptFile != null && scriptFile != previousScriptFile) {
            fileSystem.writeAtomically("$directory/$scriptFile", plugin.scriptBytes)
        }
        val record = FilePluginRecord(plugin.metadata, scriptFile)
        fileSystem.writeAtomically(
            "$directory/$METADATA_FILE",
            json.encodeToString(FilePluginRecord.serializer(), record).encodeToByteArray(),
        )
        return previousScriptFile?.takeIf { it != scriptFile }
    }

    private suspend fun cleanupPreviousScript(pluginId: String, obsoleteScript: String?) {
        obsoleteScript ?: return
        try {
            fileSystem.delete("${pluginDirectory(pluginId)}/$obsoleteScript")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Content-addressed scripts are immutable. An orphan costs disk space only and is not
            // observable because discovery follows package.json, so cleanup failure is harmless.
        }
    }

    private fun pluginDirectory(pluginId: String): String = "$ROOT_DIRECTORY/$pluginId"

    private fun copyPlugin(plugin: StoredPlugin): StoredPlugin =
        plugin.copy(scriptBytes = plugin.scriptBytes.copyOf())

    private companion object {
        const val ROOT_DIRECTORY = "plugins/packages"
        const val METADATA_FILE = "package.json"
    }
}

@Serializable
private data class FilePluginRecord(
    val metadata: InstalledPluginMetadata,
    val scriptFile: String? = null,
)
