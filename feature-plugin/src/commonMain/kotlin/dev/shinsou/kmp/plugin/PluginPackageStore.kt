package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.crypto.Sha256
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.files.KeyValueStore
import dev.shinsou.kmp.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Atomic persistence boundary. Platform stores should stage then rename in [put]. */
public interface PluginPackageStore {
    public suspend fun list(): List<StoredPlugin>
    public suspend fun get(pluginId: String): StoredPlugin?
    public suspend fun put(plugin: StoredPlugin)
    public suspend fun remove(pluginId: String)
}

/**
 * Host-owned durable-package boundary shared by every persistent store.
 *
 * An executable package is valid only when both durable trust fields name the exact byte array
 * that will be reconstructed. Metadata-only compatibility packages are represented by three
 * empty values; no other partially-empty combination is accepted.
 */
internal fun requireValidStoredPluginPackage(plugin: StoredPlugin): String? {
    val manifest = plugin.manifest
    requireValidPluginPackageId(manifest.id)
    PluginVerifier.validateSafeFileName(manifest.script)
    require(pluginUtf8ByteCountAtMost(manifest.script, MAX_PLUGIN_PACKAGE_SCRIPT_NAME_BYTES) != null) {
        "Plugin package script name is too large"
    }
    require(manifest.sources.orEmpty().size <= MAX_PLUGIN_PACKAGE_SOURCES) {
        "Plugin package declares too many sources"
    }
    manifest.sources.orEmpty().forEach { source ->
        source.canonicalSourceId?.let { canonicalSourceId ->
            require(canonicalSourceId.isNotBlank() &&
                pluginUtf8ByteCountAtMost(canonicalSourceId, MAX_PLUGIN_PACKAGE_SOURCE_ID_BYTES) != null &&
                canonicalSourceId.none(Char::isISOControl)
            ) { "Plugin canonical source id is invalid or too large" }
        }
    }
    require(plugin.scriptBytes.size <= MAX_PLUGIN_PACKAGE_SCRIPT_BYTES) {
        "Plugin package script is too large"
    }

    val recorded = plugin.metadata.installedSha256
    val signature = manifest.signature
    if (plugin.scriptBytes.isEmpty()) {
        require(recorded.isEmpty() && signature.isEmpty()) {
            "An empty plugin package must be metadata-only"
        }
        return null
    }

    require(PLUGIN_PACKAGE_SHA256_HEX.matches(recorded) && PLUGIN_PACKAGE_SHA256_HEX.matches(signature)) {
        "Plugin package digests must be canonical SHA-256 values"
    }
    // Script runtimes consume text, not the original ByteArray. Reject replacement decoding so
    // the exact byte sequence being hashed has one lossless UTF-8 representation at execution.
    plugin.scriptBytes.decodeToString(throwOnInvalidSequence = true)
    val actual = Sha256.hex(plugin.scriptBytes)
    require(recorded == actual) { "Installed plugin digest does not match its script bytes" }
    require(signature == actual) { "Plugin manifest signature does not match its script bytes" }
    return actual
}

internal fun requireValidPluginPackageId(pluginId: String) {
    PluginVerifier.validateSafeFileComponent(pluginId)
    require(pluginUtf8ByteCountAtMost(pluginId, MAX_PLUGIN_PACKAGE_ID_BYTES) != null) {
        "Plugin package id is too large"
    }
}

public class InMemoryPluginPackageStore : PluginPackageStore {
    private val mutex = Mutex()
    private val plugins = mutableMapOf<String, StoredPlugin>()

    override suspend fun list(): List<StoredPlugin> = mutex.withLock {
        plugins.values.map(::copyPlugin)
    }

    override suspend fun get(pluginId: String): StoredPlugin? = mutex.withLock {
        plugins[pluginId]?.let(::copyPlugin)
    }

    override suspend fun put(plugin: StoredPlugin): Unit = mutex.withLock {
        plugins[plugin.manifest.id] = copyPlugin(plugin)
    }

    override suspend fun remove(pluginId: String): Unit = mutex.withLock {
        plugins.remove(pluginId)
    }

    private fun copyPlugin(plugin: StoredPlugin): StoredPlugin =
        plugin.copy(scriptBytes = plugin.scriptBytes.copyOf())
}

/** Persistent package store requiring only the same KV primitive used by app settings. */
internal class KeyValuePluginPackageStore(
    private val keyValueStore: KeyValueStore,
    private val json: Json = PluginJson,
) : PluginPackageStore {
    private val mutex = Mutex()
    private val indexKey = "plugin.packages.index"
    private var cachedPlugins: MutableMap<String, StoredPlugin>? = null

    override suspend fun list(): List<StoredPlugin> = mutex.withLock {
        loadPlugins().values.map(::copyPlugin)
    }

    override suspend fun get(pluginId: String): StoredPlugin? = mutex.withLock {
        requireValidPluginPackageId(pluginId)
        loadPlugins()[pluginId]?.let(::copyPlugin)
    }

    override suspend fun put(plugin: StoredPlugin): Unit = mutex.withLock {
        // Snapshot before the first suspension. ByteArray is mutable, and a caller must not be
        // able to change the bytes between hashing, persistence, and cache publication.
        val stablePlugin = snapshotPlugin(plugin)
        val digest = requireValidStoredPluginPackage(stablePlugin)
        val id = stablePlugin.manifest.id
        val plugins = loadPlugins()
        require(id in plugins || plugins.size < MAX_PLUGIN_PACKAGE_COUNT) {
            "Too many installed plugin packages"
        }

        val record = KeyValuePluginRecord(stablePlugin.metadata, digest)
        val encodedRecord = json.encodeToString(KeyValuePluginRecord.serializer(), record)
        requireBoundedPackageMetadata(encodedRecord)
        val previousDigest = plugins[id]?.scriptBytes
            ?.takeIf(ByteArray::isNotEmpty)
            ?.let(Sha256::hex)
        try {
            withContext(NonCancellable) {
                val scriptKey = digest?.let { contentScriptKey(id, it) }
                if (scriptKey != null) {
                    val encodedScript = stablePlugin.scriptBytes.toHex()
                    keyValueStore.putString(scriptKey, encodedScript)
                    require(keyValueStore.getString(scriptKey) == encodedScript) {
                        "Unable to verify persisted plugin script bytes"
                    }
                }
                keyValueStore.putString(recordKey(id), encodedRecord)
                require(keyValueStore.getString(recordKey(id)) == encodedRecord) {
                    "Unable to verify persisted plugin package metadata"
                }
                writeIds(plugins.keys + id)
                plugins[id] = stablePlugin

                // The v2 record is the commit point. Fixed-name v1 values and an obsolete content blob
                // cannot participate in reconstruction once that record exists, so cleanup is best effort.
                runCatchingCancellable { keyValueStore.remove(metadataKey(id)) }
                runCatchingCancellable { keyValueStore.remove(legacyScriptKey(id)) }
                previousDigest?.takeIf { it != digest }?.let { obsolete ->
                    runCatchingCancellable { keyValueStore.remove(contentScriptKey(id, obsolete)) }
                }
            }
        } catch (error: Throwable) {
            cachedPlugins = null
            throw error
        }
    }

    override suspend fun remove(pluginId: String): Unit = mutex.withLock {
        requireValidPluginPackageId(pluginId)
        val plugins = loadPlugins()
        // The bounded index is the uninstall commit point. Leftover records are unreachable and
        // cannot resurrect a package after a cleanup failure.
        try {
            withContext(NonCancellable) {
                writeIds(plugins.keys - pluginId)
                val removed = plugins.remove(pluginId)
                runCatchingCancellable { keyValueStore.remove(recordKey(pluginId)) }
                runCatchingCancellable { keyValueStore.remove(metadataKey(pluginId)) }
                runCatchingCancellable { keyValueStore.remove(legacyScriptKey(pluginId)) }
                removed?.scriptBytes?.takeIf(ByteArray::isNotEmpty)?.let { bytes ->
                    runCatchingCancellable { keyValueStore.remove(contentScriptKey(pluginId, Sha256.hex(bytes))) }
                }
            }
        } catch (error: Throwable) {
            cachedPlugins = null
            throw error
        }
    }

    /**
     * Installed scripts are immutable between package operations. Keep their decoded bytes in
     * process memory so every extension refresh does not repeatedly decode all hex scripts.
     */
    private suspend fun loadPlugins(): MutableMap<String, StoredPlugin> {
        cachedPlugins?.let { return it }
        val ids = readIds()
        val loaded = linkedMapOf<String, StoredPlugin>()
        for (id in ids) {
            readPlugin(id)?.let { loaded[id] = it }
        }
        cachedPlugins = loaded
        return loaded
    }

    private suspend fun readPlugin(pluginId: String): StoredPlugin? {
        requireValidPluginPackageId(pluginId)
        val encodedRecord = keyValueStore.getString(recordKey(pluginId))
        if (encodedRecord != null) {
            return try {
                requireBoundedPackageMetadata(encodedRecord)
                val record = json.decodeFromString(KeyValuePluginRecord.serializer(), encodedRecord)
                require(record.metadata.manifest.id == pluginId) { "Package metadata id mismatch" }
                val scriptBytes = record.scriptSha256?.let { digest ->
                    require(PLUGIN_PACKAGE_SHA256_HEX.matches(digest)) { "Invalid package script digest" }
                    val script = requireNotNull(keyValueStore.getString(contentScriptKey(pluginId, digest)))
                    require(script.length <= MAX_PLUGIN_PACKAGE_SCRIPT_HEX_CHARS) {
                        "Plugin package script is too large"
                    }
                    script.hexToBytes()
                } ?: ByteArray(0)
                StoredPlugin(record.metadata, scriptBytes).also { stored ->
                    require(requireValidStoredPluginPackage(stored) == record.scriptSha256) {
                        "Package script reference does not match its bytes"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Exception) {
                Log.warn("Plugin", logged) {
                    "Failed to read the stored plugin package record; treating it as not installed"
                }
                null
            }
        }

        // Read-only v1 compatibility. A present but corrupt v2 commit never falls back to these
        // values, preventing stale split keys from becoming a rollback path.
        val metadata = keyValueStore.getString(metadataKey(pluginId)) ?: return null
        val script = keyValueStore.getString(legacyScriptKey(pluginId)) ?: return null
        return try {
            requireBoundedPackageMetadata(metadata)
            require(script.length <= MAX_PLUGIN_PACKAGE_SCRIPT_HEX_CHARS) {
                "Plugin package script is too large"
            }
            StoredPlugin(
                json.decodeFromString(InstalledPluginMetadata.serializer(), metadata),
                script.hexToBytes(),
            ).also {
                require(it.manifest.id == pluginId) { "Package metadata id mismatch" }
                requireValidStoredPluginPackage(it)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Exception) {
            Log.warn("Plugin", logged) {
                "Failed to read legacy plugin package metadata; treating it as not installed"
            }
            null
        }
    }

    private suspend fun readIds(): Set<String> {
        val encoded = keyValueStore.getString(indexKey) ?: return emptySet()
        return try {
            require(pluginUtf8ByteCountAtMost(encoded, MAX_PLUGIN_PACKAGE_INDEX_BYTES) != null) {
                "Plugin package index is too large"
            }
            val ids = json.decodeFromString(ListSerializer(String.serializer()), encoded)
            require(ids.size <= MAX_PLUGIN_PACKAGE_COUNT) { "Too many installed plugin packages" }
            ids.onEach { id ->
                requireValidPluginPackageId(id)
            }.toSet()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Exception) {
            Log.warn("Plugin", logged) {
                "Failed to read the plugin package index; starting with no installed packages"
            }
            emptySet()
        }
    }

    private suspend fun writeIds(ids: Set<String>) {
        require(ids.size <= MAX_PLUGIN_PACKAGE_COUNT) { "Too many installed plugin packages" }
        if (ids.isEmpty()) {
            keyValueStore.remove(indexKey)
        } else {
            val encoded = json.encodeToString(ListSerializer(String.serializer()), ids.sorted())
            require(pluginUtf8ByteCountAtMost(encoded, MAX_PLUGIN_PACKAGE_INDEX_BYTES) != null) {
                "Plugin package index is too large"
            }
            keyValueStore.putString(indexKey, encoded)
            require(keyValueStore.getString(indexKey) == encoded) {
                "Unable to verify persisted plugin package index"
            }
        }
    }

    private fun recordKey(pluginId: String): String = "plugin.package.$pluginId.record.v2"
    private fun metadataKey(pluginId: String): String = "plugin.package.$pluginId.metadata"
    private fun legacyScriptKey(pluginId: String): String = "plugin.package.$pluginId.script.hex"
    private fun contentScriptKey(pluginId: String, sha256: String): String =
        "plugin.package.$pluginId.script.$sha256.hex"

    private fun copyPlugin(plugin: StoredPlugin): StoredPlugin =
        plugin.copy(scriptBytes = plugin.scriptBytes.copyOf())

    private fun snapshotPlugin(plugin: StoredPlugin): StoredPlugin {
        val encodedMetadata = json.encodeToString(InstalledPluginMetadata.serializer(), plugin.metadata)
        requireBoundedPackageMetadata(encodedMetadata)
        return StoredPlugin(
            metadata = json.decodeFromString(InstalledPluginMetadata.serializer(), encodedMetadata),
            scriptBytes = plugin.scriptBytes.copyOf(),
        )
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val encoded = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            encoded[index * 2] = digits[value ushr 4]
            encoded[index * 2 + 1] = digits[value and 0x0f]
        }
        return encoded.concatToString()
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Invalid script hex" }
        return ByteArray(length / 2) { index ->
            val offset = index * 2
            ((hexNibble(this[offset]) shl 4) or hexNibble(this[offset + 1])).toByte()
        }
    }

    private fun hexNibble(value: Char): Int = when (value) {
        in '0'..'9' -> value - '0'
        in 'a'..'f' -> value - 'a' + 10
        in 'A'..'F' -> value - 'A' + 10
        else -> throw IllegalArgumentException("Invalid script hex")
    }
}

@Serializable
private data class KeyValuePluginRecord(
    val metadata: InstalledPluginMetadata,
    val scriptSha256: String? = null,
)

private fun requireBoundedPackageMetadata(value: String) {
    require(pluginUtf8ByteCountAtMost(value, MAX_PLUGIN_PACKAGE_METADATA_BYTES) != null) {
        "Plugin package metadata is too large"
    }
}

internal const val MAX_PLUGIN_PACKAGE_SCRIPT_BYTES: Int = 8 * 1_024 * 1_024
internal const val MAX_PLUGIN_PACKAGE_METADATA_BYTES: Int = 512 * 1_024
internal const val MAX_PLUGIN_PACKAGE_COUNT: Int = 256
private const val MAX_PLUGIN_PACKAGE_ID_BYTES: Int = 256
private const val MAX_PLUGIN_PACKAGE_SCRIPT_NAME_BYTES: Int = 512
private const val MAX_PLUGIN_PACKAGE_SOURCE_ID_BYTES: Int = 256
private const val MAX_PLUGIN_PACKAGE_SOURCES: Int = 256
private const val MAX_PLUGIN_PACKAGE_INDEX_BYTES: Int = 128 * 1_024
private const val MAX_PLUGIN_PACKAGE_SCRIPT_HEX_CHARS: Int = MAX_PLUGIN_PACKAGE_SCRIPT_BYTES * 2
internal val PLUGIN_PACKAGE_SHA256_HEX: Regex = Regex("^[0-9a-f]{64}$")
