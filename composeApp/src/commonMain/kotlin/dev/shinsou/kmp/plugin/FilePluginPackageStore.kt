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
    private var cachedAuthoritativePluginIds: MutableSet<String>? = null

    override suspend fun list(): List<StoredPlugin> = mutex.withLock {
        loadPlugins().values.map(::copyPlugin)
    }

    override suspend fun get(pluginId: String): StoredPlugin? = mutex.withLock {
        requireValidPluginPackageId(pluginId)
        loadPlugins()[pluginId]?.let(::copyPlugin)
    }

    override suspend fun put(plugin: StoredPlugin): Unit = mutex.withLock {
        // ByteArray is mutable. Capture one immutable snapshot before hashing or suspending so
        // the verified bytes, content filename, disk bytes, and cache entry cannot diverge.
        val stablePlugin = snapshotPlugin(plugin)
        requireValidStoredPluginPackage(stablePlugin)
        val plugins = loadPlugins()
        val authoritativePluginIds = checkNotNull(cachedAuthoritativePluginIds)
        require(stablePlugin.manifest.id in authoritativePluginIds ||
            authoritativePluginIds.size < MAX_PLUGIN_PACKAGE_COUNT
        ) {
            "Too many installed plugin packages"
        }
        val previous = plugins[stablePlugin.manifest.id]
        // Once the metadata commit starts, publish the matching cache entry even if the caller is
        // cancelled while a platform dispatcher returns from its atomic replace.
        val obsoleteScript = try {
            withContext(NonCancellable) {
                val obsolete = persistPlugin(stablePlugin, previous)
                authoritativePluginIds += stablePlugin.manifest.id
                plugins[stablePlugin.manifest.id] = stablePlugin
                obsolete
            }
        } catch (error: Throwable) {
            // An atomic replace may have reached durable storage before a platform API reports
            // failure. Force the next access to reconstruct instead of serving a stale cache.
            cachedPlugins = null
            cachedAuthoritativePluginIds = null
            throw error
        }
        // package.json is the commit point. Failure to collect the now-unreferenced script must
        // neither roll back that commit nor make PluginManager discard the matching live runtime.
        cleanupPreviousScript(stablePlugin.manifest.id, obsoleteScript)
    }

    override suspend fun remove(pluginId: String): Unit = mutex.withLock {
        requireValidPluginPackageId(pluginId)
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
            cachedAuthoritativePluginIds?.remove(pluginId)
        }
    }

    private suspend fun loadPlugins(): MutableMap<String, StoredPlugin> {
        cachedPlugins?.let { return it }

        val loaded = linkedMapOf<String, StoredPlugin>()
        val discoveredPaths = fileSystem.list(ROOT_DIRECTORY, MAX_PLUGIN_PACKAGE_DIRECTORY_FILES)
        val authoritativePluginIds = discoveredPaths.mapNotNullTo(linkedSetOf()) { path ->
            authoritativePluginId(path)
        }
        require(authoritativePluginIds.size <= MAX_PLUGIN_PACKAGE_COUNT) {
            "Too many installed plugin packages"
        }
        val metadataPaths = discoveredPaths
            .filter { authoritativePluginId(it, METADATA_FILE) != null }
            .sorted()
        metadataPaths.forEach { path ->
            readPlugin(path)?.let { plugin -> loaded[plugin.manifest.id] = plugin }
        }

        // File ownership is determined before decoding package contents. A corrupt/missing
        // package.json or content-addressed script must fail closed instead of reopening fallback
        // to an older executable which a failed legacy cleanup left behind.
        legacyStore?.list().orEmpty().forEach { legacyPlugin ->
            val id = legacyPlugin.manifest.id
            if (id !in authoritativePluginIds) {
                try {
                    require(authoritativePluginIds.size < MAX_PLUGIN_PACKAGE_COUNT) {
                        "Too many installed plugin packages"
                    }
                    withContext(NonCancellable) {
                        val stablePlugin = snapshotPlugin(legacyPlugin)
                        requireValidStoredPluginPackage(stablePlugin)
                        persistPlugin(stablePlugin, previous = null)
                        authoritativePluginIds += id
                        loaded[id] = stablePlugin
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
        cachedAuthoritativePluginIds = authoritativePluginIds
        return loaded
    }

    private suspend fun readPlugin(metadataPath: String): StoredPlugin? = try {
        val relative = metadataPath.removePrefix("$ROOT_DIRECTORY/")
        val pluginId = relative.substringBefore('/')
        require(relative == "$pluginId/$METADATA_FILE") { "Unexpected package metadata path" }
        requireValidPluginPackageId(pluginId)

        val metadataBytes = requireNotNull(fileSystem.read(metadataPath))
        require(metadataBytes.size <= MAX_PLUGIN_PACKAGE_METADATA_BYTES) {
            "Plugin package metadata is too large"
        }
        val record = json.decodeFromString(
            FilePluginRecord.serializer(),
            metadataBytes.decodeToString(throwOnInvalidSequence = true),
        )
        require(record.metadata.manifest.id == pluginId) { "Package metadata id mismatch" }
        val scriptBytes = record.scriptFile?.let { scriptFile ->
            PluginVerifier.validateSafeFileName(scriptFile)
            val digest = scriptFile.removePrefix(SCRIPT_FILE_PREFIX).removeSuffix(SCRIPT_FILE_SUFFIX)
            require(scriptFile == "$SCRIPT_FILE_PREFIX$digest$SCRIPT_FILE_SUFFIX" &&
                PLUGIN_PACKAGE_SHA256_HEX.matches(digest)
            ) {
                "Invalid content-addressed plugin script name"
            }
            requireNotNull(fileSystem.read("${pluginDirectory(pluginId)}/$scriptFile")).also { bytes ->
                require(bytes.size <= MAX_PLUGIN_PACKAGE_SCRIPT_BYTES) {
                    "Plugin package script is too large"
                }
                require(Sha256.hex(bytes) == digest) {
                    "Plugin package script does not match its content address"
                }
            }
        } ?: ByteArray(0)
        StoredPlugin(record.metadata, scriptBytes).also(::requireValidStoredPluginPackage)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun persistPlugin(plugin: StoredPlugin, previous: StoredPlugin?): String? {
        val digest = requireValidStoredPluginPackage(plugin)
        val pluginId = plugin.manifest.id
        val directory = pluginDirectory(pluginId)
        val previousScriptFile = previous?.scriptBytes
            ?.takeIf(ByteArray::isNotEmpty)
            ?.let { "$SCRIPT_FILE_PREFIX${Sha256.hex(it)}$SCRIPT_FILE_SUFFIX" }
        val scriptFile = digest?.let { "$SCRIPT_FILE_PREFIX$it$SCRIPT_FILE_SUFFIX" }

        if (scriptFile != null) {
            val scriptPath = "$directory/$scriptFile"
            var persisted = fileSystem.read(scriptPath)
            if (scriptFile != previousScriptFile || persisted == null ||
                persisted.size > MAX_PLUGIN_PACKAGE_SCRIPT_BYTES ||
                !persisted.contentEquals(plugin.scriptBytes)
            ) {
                fileSystem.writeAtomically(scriptPath, plugin.scriptBytes)
                persisted = fileSystem.read(scriptPath)
            }
            val verifiedPersisted = requireNotNull(persisted)
            require(verifiedPersisted.size <= MAX_PLUGIN_PACKAGE_SCRIPT_BYTES &&
                verifiedPersisted.contentEquals(plugin.scriptBytes) && Sha256.hex(verifiedPersisted) == digest
            ) { "Unable to verify persisted plugin script bytes" }
        }
        val record = FilePluginRecord(plugin.metadata, scriptFile)
        val metadataBytes = json.encodeToString(FilePluginRecord.serializer(), record).encodeToByteArray()
        require(metadataBytes.size <= MAX_PLUGIN_PACKAGE_METADATA_BYTES) {
            "Plugin package metadata is too large"
        }
        fileSystem.writeAtomically(
            "$directory/$METADATA_FILE",
            metadataBytes,
        )
        val persistedMetadata = requireNotNull(fileSystem.read("$directory/$METADATA_FILE"))
        require(persistedMetadata.contentEquals(metadataBytes)) {
            "Unable to verify persisted plugin package metadata"
        }
        // This marker outlives accidental loss of package.json and permanently records that this
        // id moved to file storage. Package validity still comes exclusively from package.json.
        persistAuthoritativeMarker(pluginId)
        return previousScriptFile?.takeIf { it != scriptFile }
    }

    private suspend fun persistAuthoritativeMarker(pluginId: String) {
        val markerPath = "${pluginDirectory(pluginId)}/$AUTHORITATIVE_MARKER_FILE"
        val existing = fileSystem.read(markerPath)
        if (existing == null || !existing.contentEquals(AUTHORITATIVE_MARKER_BYTES)) {
            fileSystem.writeAtomically(markerPath, AUTHORITATIVE_MARKER_BYTES)
        }
        require(fileSystem.read(markerPath)?.contentEquals(AUTHORITATIVE_MARKER_BYTES) == true) {
            "Unable to verify plugin file-store ownership marker"
        }
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

    private fun authoritativePluginId(path: String, requiredFile: String? = null): String? {
        val prefix = "$ROOT_DIRECTORY/"
        if (!path.startsWith(prefix)) return null
        val relative = path.removePrefix(prefix)
        val pluginId = relative.substringBefore('/')
        val fileName = relative.substringAfter('/', missingDelimiterValue = "")
        if ((requiredFile != null && fileName != requiredFile) ||
            (fileName != METADATA_FILE && fileName != AUTHORITATIVE_MARKER_FILE) ||
            relative != "$pluginId/$fileName"
        ) return null
        return runCatching {
            requireValidPluginPackageId(pluginId)
            pluginId
        }.getOrNull()
    }

    private fun copyPlugin(plugin: StoredPlugin): StoredPlugin =
        plugin.copy(scriptBytes = plugin.scriptBytes.copyOf())

    private fun snapshotPlugin(plugin: StoredPlugin): StoredPlugin {
        val encodedMetadata = json.encodeToString(InstalledPluginMetadata.serializer(), plugin.metadata)
            .encodeToByteArray()
        require(encodedMetadata.size <= MAX_PLUGIN_PACKAGE_METADATA_BYTES) {
            "Plugin package metadata is too large"
        }
        return StoredPlugin(
            metadata = json.decodeFromString(
                InstalledPluginMetadata.serializer(),
                encodedMetadata.decodeToString(),
            ),
            scriptBytes = plugin.scriptBytes.copyOf(),
        )
    }

    private companion object {
        const val ROOT_DIRECTORY = "plugins/packages"
        const val METADATA_FILE = "package.json"
        const val AUTHORITATIVE_MARKER_FILE = ".file-store-authoritative-v1"
        const val SCRIPT_FILE_PREFIX = "script-"
        const val SCRIPT_FILE_SUFFIX = ".js"
        const val MAX_PLUGIN_PACKAGE_DIRECTORY_FILES = MAX_PLUGIN_PACKAGE_COUNT * 4
        val AUTHORITATIVE_MARKER_BYTES = "shinsou-plugin-file-store-v1\n".encodeToByteArray()
    }
}

@Serializable
private data class FilePluginRecord(
    val metadata: InstalledPluginMetadata,
    val scriptFile: String? = null,
)
