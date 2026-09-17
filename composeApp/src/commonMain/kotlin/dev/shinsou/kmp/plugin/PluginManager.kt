@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedPluginAdmissionV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedInstallCoordinatorV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedInstallationStoreV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueExecutionApprovalStoreV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueScriptQuarantineStoreV2
import dev.shinsou.kmp.plugin.shuyue.productionShuYueReviewedAdmissionV2
import dev.shinsou.kmp.plugin.v2.CloseableExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.ExtensionHostFacadeV2
import dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi
import dev.shinsou.kmp.plugin.v2.ExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.ExtensionPackageV2
import dev.shinsou.kmp.plugin.v2.ExtensionRuntimeRegistryV2
import dev.shinsou.kmp.plugin.v2.HostExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.ArtifactBoundExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.SourceLifecycleControlledExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.LegacyLoginCredentialsResolverV2
import dev.shinsou.kmp.plugin.events.BoundPluginScope
import dev.shinsou.kmp.plugin.events.BoundPluginScopeFactory
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.PluginSystemEventGateway
import dev.shinsou.kmp.plugin.events.PluginSystemCapabilityNegotiator
import dev.shinsou.kmp.plugin.events.PluginEventRuntimeStatus
import dev.shinsou.kmp.plugin.events.PluginRuntimeLifecycle
import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.events.ExactPluginSourceTarget
import dev.shinsou.kmp.plugin.events.PluginSystemEventNegotiation
import dev.shinsou.kmp.plugin.events.stablePluginRuntimeInstanceId
import dev.shinsou.kmp.plugin.v2.LegacyMangaPackageRuntimeV2
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeCapability
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
public class KeyValuePluginPackageStore(
    private val keyValueStore: PluginKeyValueStore,
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
                runCatching { keyValueStore.remove(metadataKey(id)) }
                runCatching { keyValueStore.remove(legacyScriptKey(id)) }
                previousDigest?.takeIf { it != digest }?.let { obsolete ->
                    runCatching { keyValueStore.remove(contentScriptKey(id, obsolete)) }
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
                runCatching { keyValueStore.remove(recordKey(pluginId)) }
                runCatching { keyValueStore.remove(metadataKey(pluginId)) }
                runCatching { keyValueStore.remove(legacyScriptKey(pluginId)) }
                removed?.scriptBytes?.takeIf(ByteArray::isNotEmpty)?.let { bytes ->
                    runCatching { keyValueStore.remove(contentScriptKey(pluginId, Sha256.hex(bytes))) }
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
            } catch (_: Exception) {
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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

/**
 * Controls the final host execution gate after repository and artifact verification.
 *
 * The compatibility value is deliberately verbose and must be selected by tests or an explicit
 * developer host. Production callers use the default exact-artifact approval policy.
 */
public enum class PluginExecutionAdmissionMode {
    REQUIRE_EXACT_ARTIFACT_APPROVAL,
    UNSAFE_DEVELOPER_COMPATIBILITY,
}

public class PluginManager(
    private val repositoryClient: ExtensionRepositoryClient,
    private val packageStore: PluginPackageStore,
    private val verifier: PluginVerifier,
    private val runtimeFactory: ScriptPluginRuntimeFactory,
    private val environment: ScriptPluginEnvironment,
    private val eventGrantAdmission: KeyValuePluginEventGrantAdmission? = null,
    private val executionAdmissionMode: PluginExecutionAdmissionMode =
        PluginExecutionAdmissionMode.REQUIRE_EXACT_ARTIFACT_APPROVAL,
    private val reviewedImageTransport: PluginHttpTransport? = null,
    /** Immutable startup admission for exact compiled-review artifacts from fixed loopback only. */
    private val reviewedLocalRepositoryPolicy: ReviewedLocalRepositoryPolicy =
        ReviewedLocalRepositoryPolicy.DISABLED,
) {
    private val reviewedImagePermits = kotlinx.coroutines.sync.Semaphore(2)
    /** Serializes package mutations without blocking readers of the live source/runtime maps. */
    private val packageMutationMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val interactionMutex = Mutex()
    private val interactionCounts = mutableMapOf<String, Int>()
    private var pluginUiAvailable: Boolean = false
    private val runtimes = mutableMapOf<String, List<ScriptPluginRuntime>>()
    private val sources = mutableMapOf<Long, CatalogueSource>()
    private val sourceOwners = mutableMapOf<Long, String>()
    /** Inert source reservations; populated before any executable factory is invoked. */
    private val sourceReservations = mutableMapOf<Long, String>()
    private val extensionRegistryV2 = ExtensionRuntimeRegistryV2()
    private val nativeArtifactIdentities = mutableMapOf<String, PluginArtifactIdentity>()
    private val nativeLifecycleRuntimes = mutableMapOf<String, SourceLifecycleControlledExtensionPackageRuntimeV2>()
    private val reviewedReloaders = mutableMapOf<String, suspend () -> ExtensionPackageRuntimeV2>()
    private val reviewedContentAuthorizers = mutableMapOf<String, suspend () -> Boolean>()
    private val disabledNativeSources = mutableSetOf<SourceKey>()
    private val eventScopeFactory = BoundPluginScopeFactory()
    private val eventScopes = mutableMapOf<ScriptPluginRuntime, BoundPluginScope>()
    private val legacyLoginCompatibilityScopes = mutableSetOf<String>()
    /** Process-local fail-closed tombstones when durable revocation cannot be confirmed. */
    private val locallyRevokedPluginIds = mutableSetOf<String>()
    private val locallyRevokedArtifacts = mutableSetOf<PluginArtifactIdentity>()
    private var runtimeGeneration: Long = 0
    private var nativeRuntimeGeneration: Long = 0
    /** Object-identity keyed, process-local and deliberately non-serializable browser grants. */
    private val webChallengeGrants = mutableListOf<WebChallengeGrant>()

    private data class WebChallengeGrant(
        val capability: SourceWebChallengeCapability,
        val sourceKey: SourceKey,
        val artifactIdentity: PluginArtifactIdentity,
        val runtimeEpoch: Long,
        val canonicalOrigin: String,
        val storageId: Long,
        val storage: PluginStorage,
        val localStorageKeys: Set<String>,
        val requiredLocalStorageKeys: Set<String>,
        val requiredCookieName: String?,
        val reviewedShuYue: Boolean,
        val reviewedOfficialPackage: Boolean,
    )

    /** Host-internal snapshot used to build an isolated browser without exposing raw storage. */
    internal class WebChallengeAuthorization internal constructor(
        internal val capability: SourceWebChallengeCapability,
        internal val sourceKey: SourceKey,
        internal val storageId: Long,
        internal val canonicalOrigin: String,
        internal val source: HostExtensionSourceV2,
        internal val localStorageKeys: Set<String>,
        internal val requiredLocalStorageKeys: Set<String>,
        internal val allowedSubresourceOrigins: Set<String>,
        internal val requiredCookieName: String?,
        internal val storage: PluginStorage,
    )

    internal data class RepositoryRefreshResult(
        val descriptors: List<ExtensionDescriptor>,
        val snapshots: Map<String, RepositoryIndexSnapshot>,
    )

    public suspend fun refresh(repositories: List<ExtensionRepository>): List<ExtensionDescriptor> =
        refreshWithAdmissions(repositories).descriptors

    internal suspend fun refreshWithAdmissions(
        repositories: List<ExtensionRepository>,
    ): RepositoryRefreshResult {
        // A repository refresh changes the metadata/admission snapshot from which host actions
        // are selected. Revoke before and after the fetch so neither an old grant nor one issued
        // while remote I/O was in flight survives the snapshot boundary.
        packageMutationMutex.withLock { invalidateWebChallengeGrants() }
        try {
        val installed = packageStore.list().associateBy { it.manifest.id }
        val result = linkedMapOf<String, ExtensionDescriptor>()

        installed.values.forEach { stored ->
            val manifest = stored.manifest
            result[manifest.id] = ExtensionDescriptor(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                versionCode = manifest.versionCode ?: PluginVerifier.versionInt(manifest.version),
                lang = manifest.lang,
                nsfw = manifest.nsfw,
                sources = manifest.sources.orEmpty(),
                repositoryBaseUrl = stored.metadata.repositoryBaseUrl,
                scriptUrl = null,
                iconUrl = manifest.sources?.firstOrNull()?.baseUrl?.trimEnd('/')?.plus("/favicon.ico"),
                description = null,
                state = ExtensionState.INSTALLED,
                installedVersion = manifest.version,
                contentType = manifest.installedContentType(),
            )
        }

        val snapshots = linkedMapOf<String, RepositoryIndexSnapshot>()
        repositories.forEach { repository ->
            val snapshot = repositoryClient.fetchIndexSnapshot(repository.baseUrl)
            snapshots[repository.baseUrl] = snapshot
            when (val index = snapshot.index) {
                is RepositoryIndex.Plugins -> index.entries.forEach { entry ->
                    val local = installed[entry.id]
                    val state = when {
                        local == null -> ExtensionState.AVAILABLE
                        entry.versionCode > (local.manifest.versionCode
                            ?: PluginVerifier.versionInt(local.manifest.version)) -> ExtensionState.UPDATE_AVAILABLE
                        entry.versionCode == (local.manifest.versionCode
                            ?: PluginVerifier.versionInt(local.manifest.version)) &&
                            entry.version != local.manifest.version -> ExtensionState.UPDATE_AVAILABLE
                        else -> ExtensionState.INSTALLED
                    }
                    result[entry.id] = entry.toDescriptor(
                        repository,
                        state,
                        local?.manifest?.version,
                        local?.manifest?.installedContentType(),
                    )
                }

                is RepositoryIndex.Legacy -> index.entries.forEach { entry ->
                    val local = installed[entry.pkg]
                    val localCode = local?.manifest?.versionCode
                        ?: local?.manifest?.version?.let(PluginVerifier::versionInt)
                    val state = when {
                        local == null -> ExtensionState.AVAILABLE
                        localCode == null || entry.code > localCode ||
                            (entry.code == localCode && entry.version != local.manifest.version) ->
                            ExtensionState.UPDATE_AVAILABLE
                        else -> ExtensionState.INSTALLED
                    }
                    result[entry.pkg] = entry.toDescriptor(
                        repository,
                        state,
                        local?.manifest?.version,
                        local?.manifest?.installedContentType(),
                    )
                }
                is RepositoryIndex.Combined -> index.plugins.forEach { entry ->
                    val local = installed[entry.id]
                    val state = when {
                        local == null -> ExtensionState.AVAILABLE
                        entry.versionCode > (local.manifest.versionCode
                            ?: PluginVerifier.versionInt(local.manifest.version)) -> ExtensionState.UPDATE_AVAILABLE
                        entry.versionCode == (local.manifest.versionCode
                            ?: PluginVerifier.versionInt(local.manifest.version)) &&
                            entry.version != local.manifest.version -> ExtensionState.UPDATE_AVAILABLE
                        else -> ExtensionState.INSTALLED
                    }
                    result[entry.id] = entry.toDescriptor(
                        repository,
                        state,
                        local?.manifest?.version,
                        local?.manifest?.installedContentType(),
                    )
                }
            }
        }
        return RepositoryRefreshResult(result.values.sortedWith(
            compareBy<ExtensionDescriptor> { it.state == ExtensionState.AVAILABLE }
                .thenBy { it.name.lowercase() },
        ), snapshots.toMap())
        } finally {
            packageMutationMutex.withLock { invalidateWebChallengeGrants() }
        }
    }

    public suspend fun install(
        repository: ExtensionRepository,
        entry: PluginIndexEntry,
        repositoryAdmission: RepositoryIndexAdmission? = null,
    ): ScriptPluginRuntime =
        withContext(Dispatchers.Default) {
            packageMutationMutex.withLock {
            invalidateWebChallengeGrants()
            PluginVerifier.validateSafeFileComponent(entry.id)
            require(entry.installable && !entry.referenceOnly && !entry.legacyCompatibilityOnly) {
                "Plugin '${entry.id}' is migration/reference metadata and cannot be installed"
            }
            requireReviewedLocalRepositoryEntry(repository, entry)
            requireSupportedGenericRuntime(entry)
            require(entry.runtimePermissions != null) {
                "New plugin '${entry.id}' must declare runtimePermissions"
            }
            val previousStored = packageStore.get(entry.id)
            val previousVersionCode = previousStored?.manifest?.versionCode
                ?: previousStored?.manifest?.version?.let(PluginVerifier::versionInt)
            require(previousVersionCode == null || entry.versionCode >= previousVersionCode) {
                "Plugin '${entry.id}' downgrade is not allowed"
            }
            if (entry.sha256 != null) {
                repositoryClient.requireArtifactNotDowngraded(repository.baseUrl, entry, repositoryAdmission)
            }
            repositoryClient.verifyPluginV2Sidecar(repository.baseUrl, entry)
            val scriptBytes = repositoryClient.downloadPluginScript(repository.baseUrl, entry.scriptUrl)
            require(entry.byteSize == null || scriptBytes.size == entry.byteSize) {
                "Plugin '${entry.id}' artifact size does not match its V2 declaration"
            }
            val actualHash = Sha256.hex(scriptBytes)
            val expectedHash = entry.sha256?.trim()?.lowercase()
            val manifest = PluginManifest(
                id = entry.id,
                name = entry.name,
                version = entry.version,
                versionCode = entry.versionCode,
                lang = entry.lang,
                nsfw = entry.nsfw == 1,
                script = "${entry.id}.js",
                // Current repositories have no digest field. Hashing the received bytes and
                // recording the resulting trust token preserves Shinsou's legacy TOFU behavior.
                signature = expectedHash ?: actualHash,
                minRuntimeVersion = entry.minRuntimeVersion,
                sources = entry.sources,
                contentKinds = entry.contentKinds.takeIf {
                    entry.sidecarUrl != null ||
                        entry.sources.orEmpty().any { source -> source.contentKindsDeclared != null }
                },
                systemEvents = entry.systemEvents,
                requestedHostPermissions = entry.requestedHostPermissions,
                runtimePermissions = entry.runtimePermissions,
                contract = entry.contract,
                runtime = entry.runtime,
                sidecarUrl = entry.sidecarUrl,
            )
            // Installation validates integrity but permission review remains the sole execution
            // admission. The older trust token is retained for explicit user revocation and
            // persisted-byte tamper checks; it must never cause runtime construction here.
            val verified = verifier.verify(scriptBytes, manifest)
            val stored = StoredPlugin(
                metadata = InstalledPluginMetadata(
                    manifest = manifest,
                    repositoryBaseUrl = repository.baseUrl,
                    installedSha256 = verified.sha256,
                    legacyTrustOnInstall = expectedHash == null,
                    legacyStorageMigrationAllowed = false,
                ),
                scriptBytes = scriptBytes,
            )
            requireValidStoredPluginPackage(stored)
            val grantReview = requireNotNull(stored.eventGrantReview(includeEmpty = true))
            val previousReview = previousStored?.eventGrantReview(includeEmpty = true)
            val alreadyGranted = executionAdmissionGranted(grantReview)
            val reservation = lifecycleMutex.withLock { reserveManifestSources(manifest) }
            var candidates: List<ScriptPluginRuntime> = emptyList()
            var committed = false
            var liveStateReplaced = false
            try {
                require(extensionRegistryV2.packageFacade(entry.id) == null) {
                    "Extension package '${entry.id}' is already loaded by the V2 runtime"
                }
                if (alreadyGranted) {
                    candidates = createRuntimeCandidates(stored)
                }
                val compatibilityResult = candidates.takeIf(List<*>::isNotEmpty)
                    ?.let { runtimeCompatibilityResult(manifest, candidates) }
                    ?: InertScriptPluginRuntimeHandle(manifest)
                // Downloading, verification and runtime construction remain cancellable. Once the
                // durable/live commit starts, finish both halves so cancellation cannot leave a new
                // package paired with the previous runtime (or no runtime on first installation).
                currentCoroutineContext().ensureActive()
                // Permission approval is mutable state. Recheck it immediately before crossing
                // the durable/live commit boundary so a concurrent or external revocation cannot
                // race a runtime into existence from a stale decision.
                if (candidates.isNotEmpty() && !executionAdmissionGranted(grantReview)) {
                    closeRuntimes(candidates)
                    candidates = emptyList()
                }
                if (candidates.isNotEmpty()) {
                    lifecycleMutex.withLock { validateRuntimeCandidates(entry.id, candidates) }
                }
                withContext(NonCancellable) {
                    packageStore.put(stored)
                    requirePersistedPackageMatches(stored)
                    lifecycleMutex.withLock {
                        if (candidates.isEmpty()) unloadPlugin(entry.id)
                        else replaceRuntimes(entry.id, candidates)
                        // The live maps have changed as soon as unload/replace returns. Mark that
                        // boundary before committing the inert reservation: if the latter throws,
                        // rollback must rebuild the previous runtime instead of merely closing the
                        // candidate and leaving the live map pointing at that closed object.
                        liveStateReplaced = true
                        commitReservation(reservation)
                    }
                    if (entry.sha256 != null) {
                        repositoryClient.recordCommittedArtifact(
                            repository.baseUrl,
                            entry,
                            verified.sha256,
                            repositoryAdmission,
                        )
                    }
                    committed = true
                }
                // An obsolete exact-artifact grant cannot authorize the replacement runtime. It
                // is removed only after the new durable/live state is committed, so a failed
                // update never destroys the still-installed version's approval. Cleanup failure
                // is safe (the key is artifact-bound) and must not roll back a valid new commit.
                if (previousReview != null && previousReview.artifact != grantReview.artifact) {
                    runCatching { eventGrantAdmission?.revoke(previousReview.artifact) }
                }
                // Restore a matching in-memory authorizer or stage a new review only after the
                // replacement is durable and the old runtime has closed. A failed update cannot
                // grant the candidate or revoke the still-installed artifact's authorization.
                eventGrantAdmission?.hydrate(grantReview)
                return@withLock compatibilityResult
            } catch (error: Throwable) {
                if (!committed) {
                    withContext(NonCancellable) {
                        if (!liveStateReplaced) {
                            runCatching { closeRuntimes(candidates) }
                                .exceptionOrNull()?.let(error::addSuppressed)
                        }
                        runCatching {
                            if (previousStored == null) packageStore.remove(entry.id)
                            else packageStore.put(previousStored)
                        }.exceptionOrNull()?.let(error::addSuppressed)
                        if (liveStateReplaced) {
                            runCatching { restorePreviousRuntime(entry.id, previousStored) }
                                .onFailure { restoreFailure ->
                                    error.addSuppressed(restoreFailure)
                                    // If restoration itself fails, do not leave the replacement
                                    // artifact live beside an uncertain durable package.
                                    runCatching {
                                        lifecycleMutex.withLock { unloadPlugin(entry.id) }
                                    }.exceptionOrNull()?.let(error::addSuppressed)
                                }
                        }
                        if (!alreadyGranted && previousReview?.artifact != grantReview.artifact) {
                            runCatching { eventGrantAdmission?.revoke(grantReview.artifact) }
                                .exceptionOrNull()?.let(error::addSuppressed)
                        }
                    }
                } else {
                    // The package and anti-rollback ledger are already committed, but admission
                    // hydration/staging failed. Keep the installed artifact manageable while
                    // making its executable state explicitly inert; returning a live runtime here
                    // would make a storage outage an execution-admission bypass.
                    withContext(NonCancellable) {
                        runCatching {
                            lifecycleMutex.withLock { unloadPlugin(entry.id) }
                        }.exceptionOrNull()?.let(error::addSuppressed)
                    }
                }
                withContext(NonCancellable) {
                    runCatching { lifecycleMutex.withLock { rollbackReservation(reservation) } }
                        .exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        }
        }

    public suspend fun update(
        repository: ExtensionRepository,
        entry: PluginIndexEntry,
        repositoryAdmission: RepositoryIndexAdmission? = null,
    ): ScriptPluginRuntime = install(repository, entry, repositoryAdmission)

    /** Installs Mihon metadata as non-executable stub sources, matching the Swift fallback. */
    public suspend fun installLegacy(
        repository: ExtensionRepository,
        entry: LegacyExtensionIndexEntry,
    ): List<CatalogueSource> = withContext(Dispatchers.Default) {
        packageMutationMutex.withLock {
        require(!isKnownReviewedLocalRepositoryBaseUrl(repository.baseUrl.trim().trimEnd('/'))) {
            "Reviewed local repository supports Shinsou V2 entries only"
        }
        invalidateWebChallengeGrants()
        PluginVerifier.validateSafeFileComponent(entry.pkg)
        val manifest = PluginManifest(
            id = entry.pkg,
            name = entry.name,
            version = entry.version,
            versionCode = entry.code,
            lang = entry.lang,
            nsfw = entry.nsfw == 1,
            script = "${entry.pkg}.js",
            signature = "",
            sources = entry.sources,
        )
        val stored = StoredPlugin(
            InstalledPluginMetadata(
                manifest,
                repository.baseUrl,
                "",
                legacyTrustOnInstall = true,
                legacyStorageMigrationAllowed = false,
            ),
            ByteArray(0),
        )
        val previous = packageStore.get(entry.pkg)
        val reservation = lifecycleMutex.withLock { reserveManifestSources(manifest) }
        currentCoroutineContext().ensureActive()
        try {
            withContext(NonCancellable) {
                packageStore.put(stored)
                requirePersistedPackageMatches(stored)
                verifier.trustMetadataOnly(manifest, stored.metadata.installedSha256)
                lifecycleMutex.withLock {
                    unloadPlugin(entry.pkg)
                    addMetadataSources(stored).also {
                        commitReservation(reservation)
                    }
                }
            }
        } catch (error: Throwable) {
            // Trust-store failure happens after the package commit point. Restore the previous
            // durable package so the current live sources still describe what will load next time.
            withContext(NonCancellable) {
                runCatching {
                    if (previous == null) packageStore.remove(entry.pkg) else packageStore.put(previous)
                }
            }
            lifecycleMutex.withLock { rollbackReservation(reservation) }
            throw error
        }
        }
    }

    public suspend fun uninstall(pluginId: String) {
        val failures = withContext(Dispatchers.Default) {
            packageMutationMutex.withLock {
            requireValidPluginPackageId(pluginId)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                // User intent is the security commit point. Install the process-local denial
                // before calling any fallible persistence boundary, then attempt every teardown
                // independently. A package-store outage must never leave script/native facades,
                // browser capabilities, or host-event authority live.
                locallyRevokedPluginIds += pluginId
                invalidateWebChallengeGrants()
                val cleanupFailures = mutableListOf<Throwable>()
                var removalFailure: Throwable? = null
                fun captureCleanup(error: Throwable) {
                    cleanupFailures += error
                }

                val artifacts = linkedSetOf<PluginArtifactIdentity>().apply {
                    nativeArtifactIdentities[pluginId]?.let(::add)
                    lifecycleMutex.withLock {
                        eventScopes.values
                            .filter { it.artifactIdentity.packageId == pluginId }
                            .mapTo(this@apply, BoundPluginScope::artifactIdentity)
                    }
                }
                locallyRevokedArtifacts += artifacts

                // Make all already-published entry points unreachable before consulting any
                // potentially slow durable store. Legacy unload closes its event generations;
                // native registry removal detaches the facade before awaiting runtime close.
                runCatching { lifecycleMutex.withLock { unloadPlugin(pluginId) } }
                    .exceptionOrNull()?.let(::captureCleanup)
                nativeArtifactIdentities.remove(pluginId)
                nativeLifecycleRuntimes.remove(pluginId)
                reviewedReloaders.remove(pluginId)
                reviewedContentAuthorizers.remove(pluginId)
                disabledNativeSources.removeAll { it.packageId == pluginId }
                runCatching { extensionRegistryV2.uninstall(pluginId) }
                    .exceptionOrNull()?.let(::captureCleanup)
                artifacts.forEach { artifact ->
                    runCatching { eventGrantAdmission?.revoke(artifact) }
                        .exceptionOrNull()?.let(::captureCleanup)
                }

                val stored = runCatching { packageStore.get(pluginId) }
                    .onFailure(::captureCleanup)
                    .getOrNull()
                stored?.artifactIdentity()?.let {
                    if (artifacts.add(it)) {
                        locallyRevokedArtifacts += it
                        runCatching { eventGrantAdmission?.revoke(it) }
                            .exceptionOrNull()?.let(::captureCleanup)
                    }
                }
                // Generic execution trust is a second durable authority. Revoke it even if the
                // package bytes remain available for a later uninstall retry.
                runCatching { verifier.revokeAll(pluginId) }
                    .exceptionOrNull()?.let(::captureCleanup)
                // Legacy trust-on-install is deliberately readable without the secure trust
                // store. Clear that durable compatibility bit before deletion so a retained
                // package cannot execute after restart merely because remove() failed.
                stored?.takeIf { it.metadata.legacyTrustOnInstall }?.let { installed ->
                    runCatching {
                        packageStore.put(
                            installed.copy(
                                metadata = installed.metadata.copy(legacyTrustOnInstall = false),
                            ),
                        )
                    }.exceptionOrNull()?.let(::captureCleanup)
                }

                try {
                    packageStore.remove(pluginId)
                } catch (error: Throwable) {
                    removalFailure = error
                }

                // Only a confirmed durable deletion can retire the denial floor. If deletion
                // fails, retained bytes remain inert for this manager and on restart because the
                // durable trust/event grants above were revoked.
                if (removalFailure == null && cleanupFailures.isEmpty()) {
                    locallyRevokedPluginIds.remove(pluginId)
                    locallyRevokedArtifacts.removeAll { it.packageId == pluginId }
                }
                removalFailure to cleanupFailures.toList()
            }
        }
        }

        // Assemble and throw after crossing the coroutine context boundary. Coroutine stack-trace
        // recovery may copy a thrown exception at that boundary without its suppressed failures.
        // Returning the failures as data preserves the package-store error as the observable
        // primary exception while retaining every independent teardown error for diagnostics.
        failures.first?.let { primary ->
            failures.second.filter { it !== primary }.forEach(primary::addSuppressed)
            throw primary
        }
        failures.second.firstOrNull()?.let { primary ->
            failures.second.drop(1).filter { it !== primary }.forEach(primary::addSuppressed)
            throw primary
        }
    }

    /**
     * Changes the package's execution grant and applies it to the live runtime immediately.
     * Revocation keeps the package metadata and bytes installed for UI management/uninstall.
     */
    public suspend fun setPluginTrusted(pluginId: String, trusted: Boolean): Unit =
        withContext(Dispatchers.Default) {
        packageMutationMutex.withLock packageMutation@{
            invalidateWebChallengeGrants()
            val stored = packageStore.get(pluginId)
                ?: throw IllegalArgumentException("Extension '$pluginId' is not installed")
            if (!trusted) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    // Install the denial before touching fallible stores. Even a total storage
                    // outage cannot make a later in-process reload revive this package.
                    locallyRevokedPluginIds += pluginId
                    locallyRevokedArtifacts += stored.artifactIdentity()
                    var failure: Throwable? = null
                    fun capture(error: Throwable) {
                        if (failure == null) failure = error else failure?.addSuppressed(error)
                    }

                    // Attempt every durable revocation even if an earlier store is unavailable;
                    // independent stores may still successfully remove their authority.
                    if (stored.metadata.legacyTrustOnInstall) {
                        runCatching {
                            packageStore.put(
                                stored.copy(
                                    metadata = stored.metadata.copy(legacyTrustOnInstall = false),
                                ),
                            )
                        }.exceptionOrNull()?.let(::capture)
                    }
                    runCatching { verifier.revokeAll(pluginId) }
                        .exceptionOrNull()?.let(::capture)
                    runCatching { eventGrantAdmission?.revoke(stored.artifactIdentity()) }
                        .exceptionOrNull()?.let(::capture)

                    // Revocation is fail-closed in memory. A durable-store failure must never
                    // leave attacker-controlled code running with the permissions it had before
                    // the user requested revocation.
                    runCatching { lifecycleMutex.withLock { unloadPlugin(pluginId) } }
                        .exceptionOrNull()?.let(::capture)
                    failure?.let { throw it }
                }
                return@packageMutation
            }

            if (!isAdmittedReviewedLocalInstall(stored)) {
                lifecycleMutex.withLock { unloadPlugin(pluginId) }
                throw IllegalArgumentException(
                    "Local repository artifact is not admitted by the current compiled review policy",
                )
            }

            if (stored.isMetadataOnly()) {
                val reservation = lifecycleMutex.withLock { reserveManifestSources(stored.manifest) }
                currentCoroutineContext().ensureActive()
                try {
                    withContext(NonCancellable) {
                        verifier.trustMetadataOnly(stored.manifest, stored.metadata.installedSha256)
                        locallyRevokedPluginIds.remove(pluginId)
                        locallyRevokedArtifacts.remove(stored.artifactIdentity())
                        lifecycleMutex.withLock {
                            unloadPlugin(pluginId)
                            addMetadataSources(stored)
                            commitReservation(reservation)
                        }
                    }
                } catch (error: Throwable) {
                    lifecycleMutex.withLock { rollbackReservation(reservation) }
                    throw error
                }
                return@packageMutation
            }

            if (!stored.hasExecutableRuntimeDeclaration()) {
                lifecycleMutex.withLock { unloadPlugin(pluginId) }
                return@packageMutation
            }

            val wasTrusted = verifier.isTrusted(stored.manifest, stored.metadata.installedSha256)
            try {
                verifier.trustInstalled(
                    stored.scriptBytes,
                    stored.manifest,
                    stored.metadata.installedSha256,
                )
            } catch (error: Throwable) {
                throw error
            }
            val review = requireNotNull(stored.eventGrantReview(includeEmpty = true))
            // The legacy developer mode treats this toggle as its explicit execution action.
            // Production exact admission may clear a revocation tombstone only through approve().
            if (executionAdmissionMode == PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY) {
                locallyRevokedPluginIds.remove(pluginId)
                locallyRevokedArtifacts.remove(review.artifact)
            }
            if (!executionAdmissionGranted(review)) {
                eventGrantAdmission?.hydrate(review)
                return@packageMutation
            }
            val reservation = lifecycleMutex.withLock { reserveManifestSources(stored.manifest) }
            val candidates = try {
                require(extensionRegistryV2.packageFacade(pluginId) == null) {
                    "Extension package '$pluginId' is already loaded by the V2 runtime"
                }
                createRuntimeCandidates(stored)
            } catch (_: ScriptRuntimeUnavailableException) {
                // A user trust toggle cannot override the host's in-process isolation boundary.
                // Keep the verified package installed, but convert the old grant to a pending
                // review so this same artifact cannot be resurrected on the next restart.
                lifecycleMutex.withLock { rollbackReservation(reservation) }
                locallyRevokedArtifacts += review.artifact
                quarantineUnavailableRuntime(review)
                return@packageMutation
            } catch (error: Throwable) {
                lifecycleMutex.withLock { rollbackReservation(reservation) }
                if (!wasTrusted) {
                    withContext(NonCancellable) { verifier.revokeAll(pluginId) }
                }
                throw error
            }
            var committed = false
            try {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    lifecycleMutex.withLock {
                        replaceRuntimes(pluginId, candidates)
                        commitReservation(reservation)
                    }
                    committed = true
                }
            } catch (error: Throwable) {
                if (!committed) closeRuntimes(candidates)
                lifecycleMutex.withLock { rollbackReservation(reservation) }
                if (!committed && !wasTrusted) {
                    withContext(NonCancellable) { verifier.revokeAll(pluginId) }
                }
                throw error
            }
        }
        }

    /**
     * Reconstructs trusted packages independently. An untrusted, corrupt, or unloadable package
     * remains installed but cannot prevent other packages from becoming available.
     */
    public suspend fun loadInstalled(): List<CatalogueSource> = withContext(Dispatchers.Default) {
        packageMutationMutex.withLock {
            invalidateWebChallengeGrants()
            lifecycleMutex.withLock {
                closeAllRuntimes()
                sources.clear()
                sourceOwners.clear()
                sourceReservations.clear()
                val storedPlugins = packageStore.list()
                preflightInstalledManifestSources(storedPlugins)
                storedPlugins.forEach { stored ->
                    try {
                        loadStored(stored)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: ScriptRuntimeUnavailableException) {
                        // A durable exact-artifact grant may have been created on a host which
                        // could safely isolate this runtime. If the current host cannot, convert
                        // that old grant back to a pending review just like an explicit approval
                        // or re-enable attempt does. This prevents every restart from presenting
                        // an inert package as approved while repeatedly retrying construction.
                        locallyRevokedArtifacts += stored.artifactIdentity()
                        quarantineUnavailableRuntime(
                            requireNotNull(stored.eventGrantReview(includeEmpty = true)),
                        )
                    } catch (_: Exception) {
                        // Per-package isolation is intentional. The package stays installed so the UI can
                        // show it as blocked/corrupt and still let the user re-authorize or uninstall it.
                    }
                }
                sources.values.toList()
            }
        }
    }

    public suspend fun installedPlugins(): List<StoredPlugin> = packageStore.list()

    /** Returns the verified exact-digest permission review, if this artifact requested host access. */
    public suspend fun pendingEventGrantReview(pluginId: String): PluginEventGrantReview? =
        packageStore.get(pluginId)?.let { stored ->
            eventGrantAdmission?.pending(stored.artifactIdentity())
        }

    /** True only when this exact installed digest may currently create executable runtimes. */
    public suspend fun hasExecutionApproval(pluginId: String): Boolean = packageMutationMutex.withLock {
        val stored = packageStore.get(pluginId) ?: return@withLock false
        if (stored.isMetadataOnly()) return@withLock true
        if (!isAdmittedReviewedLocalInstall(stored)) return@withLock false
        if (!stored.hasExecutableRuntimeDeclaration()) return@withLock false
        executionAdmissionGranted(requireNotNull(stored.eventGrantReview(includeEmpty = true)))
    }

    /** Explicitly approves the complete verified set, then rebuilds the runtime negotiation. */
    public suspend fun approveEventGrantReview(
        pluginId: String,
        expectedReview: PluginEventGrantReview,
        permissions: Set<PluginHostPermission>,
    ): Unit = packageMutationMutex.withLock {
        invalidateWebChallengeGrants()
        val stored = requireNotNull(packageStore.get(pluginId)) { "Extension '$pluginId' is not installed" }
        if (!isAdmittedReviewedLocalInstall(stored)) {
            withContext(NonCancellable) { lifecycleMutex.withLock { unloadPlugin(pluginId) } }
            throw IllegalArgumentException(
                "Local repository artifact is not admitted by the current compiled review policy",
            )
        }
        if (!stored.hasExecutableRuntimeDeclaration()) {
            withContext(NonCancellable) { lifecycleMutex.withLock { unloadPlugin(pluginId) } }
            throw IllegalArgumentException(
                "Extension '$pluginId' has no admissible executable runtime declaration",
            )
        }
        val admission = requireNotNull(eventGrantAdmission) { "Plugin event grant admission is unavailable" }
        val currentReview = requireNotNull(stored.eventGrantReview(includeEmpty = true))
        require(expectedReview.artifact.packageId == pluginId) {
            "Reviewed plugin identity does not match the requested extension"
        }
        require(expectedReview == currentReview) {
            "Installed plugin artifact or requested permissions changed after review"
        }
        require(admission.pending(currentReview.artifact) == currentReview) {
            "The exact displayed plugin review is no longer pending"
        }
        admission.approve(stored.artifactIdentity(), permissions)
        locallyRevokedPluginIds.remove(pluginId)
        locallyRevokedArtifacts.remove(stored.artifactIdentity())
        try {
            lifecycleMutex.withLock { loadStored(stored) }
        } catch (_: ScriptRuntimeUnavailableException) {
            // Approval is still a valid user decision, but this host cannot safely execute
            // the generic in-process engine. Keep the verified artifact installed and
            // explicitly inert rather than surfacing a misleading install failure or
            // leaving a durable grant that could resurrect on restart.
            locallyRevokedArtifacts += stored.artifactIdentity()
            quarantineUnavailableRuntime(requireNotNull(stored.eventGrantReview(includeEmpty = true)))
        }
    }

    public suspend fun revokeEventGrants(pluginId: String): Unit = packageMutationMutex.withLock {
        invalidateWebChallengeGrants()
        val stored = packageStore.get(pluginId) ?: return@withLock
        val review = requireNotNull(stored.eventGrantReview(includeEmpty = true))
        withContext(NonCancellable) {
            locallyRevokedArtifacts += review.artifact
            var failure: Throwable? = null
            runCatching { eventGrantAdmission?.revoke(stored.artifactIdentity()) }
                .exceptionOrNull()?.let { failure = it }
            // Always tear down the runtime even when grant persistence fails. In particular, do
            // not call loadStored here: a partially removed durable grant could recreate the same
            // sensitive runtime during the failure path.
            runCatching { lifecycleMutex.withLock { unloadPlugin(pluginId) } }
                .exceptionOrNull()?.let { unloadFailure ->
                    if (failure == null) failure = unloadFailure else failure?.addSuppressed(unloadFailure)
                }
            failure?.let { throw it }

            // Successful revocation stages the exact artifact for a fresh review while leaving
            // it installed and inert. If staging itself fails, the runtime remains unloaded.
            eventGrantAdmission?.hydrate(review)
        }
    }
    public suspend fun catalogueSources(): List<CatalogueSource> = lifecycleMutex.withLock {
        sources.values.toList()
    }

    public suspend fun source(sourceId: Long): CatalogueSource? = lifecycleMutex.withLock { sources[sourceId] }

    /** Resolves a legacy UI row to its exact live manifest identity; a bare number is never enough. */
    public suspend fun exactSourceKeyForLegacyId(sourceId: Long): SourceKey? = packageMutationMutex.withLock {
        val packageId = lifecycleMutex.withLock { sourceOwners[sourceId] } ?: return@withLock null
        val stored = packageStore.get(packageId) ?: return@withLock null
        if (lifecycleMutex.withLock { sourceOwners[sourceId] } != packageId) return@withLock null
        val declared = stored.manifest.sources.orEmpty().singleOrNull { it.id == sourceId }
            ?: return@withLock null
        sourceKeyFor(packageId, sourceId, declared.canonicalSourceId)
    }

    /** Exact fixed storage namespace only for the currently live reviewed ShuYue artifact. */
    public suspend fun reviewedShuYueStorageScope(sourceKey: SourceKey): Long? = packageMutationMutex.withLock {
        if (sourceKey.legacyLongId != null) return@withLock null
        val identity = nativeArtifactIdentities[sourceKey.packageId] ?: return@withLock null
        val profile = exactReviewedShuYueProfile(identity, sourceKey) ?: return@withLock null
        if (reviewedContentAuthorizers[sourceKey.packageId]?.invoke() != true) return@withLock null
        if (extensionRegistryV2.packageFacade(sourceKey.packageId)?.source(sourceKey) == null) return@withLock null
        dev.shinsou.kmp.plugin.shuyue.BuiltInShuYueExecutionScopesV2.resolve(profile.identity, sourceKey)
    }

    /**
     * Host-only exact storage lookup for source settings/import UI. Package identity is resolved
     * from the manager's live ownership map and cannot be supplied by extension code.
     */
    public suspend fun storageForSource(sourceId: Long): PluginStorage? = packageMutationMutex.withLock {
        val packageId = lifecycleMutex.withLock { sourceOwners[sourceId] } ?: return@withLock null
        val stored = packageStore.get(packageId) ?: return@withLock null
        // Recheck after storage I/O so an unload can never hand UI a stale attacker-selected scope.
        if (lifecycleMutex.withLock { sourceOwners[sourceId] } != packageId) return@withLock null
        val declared = stored.manifest.sources.orEmpty().singleOrNull { it.id == sourceId }
        BoundPluginStorage(
            delegate = environment.storage,
            sourceKey = sourceKeyFor(packageId, sourceId, declared?.canonicalSourceId),
            allowLegacyMigration = stored.metadata.legacyStorageMigrationAllowed,
        )
    }

    /** Host-only content plane; its allowlist comes from the exact live source manifest. */
    public suspend fun contentNetworkForSource(sourceId: Long): PluginContentNetworkClient? =
        packageMutationMutex.withLock {
            val packageId = lifecycleMutex.withLock { sourceOwners[sourceId] } ?: return@withLock null
            val stored = packageStore.get(packageId) ?: return@withLock null
            if (lifecycleMutex.withLock { sourceOwners[sourceId] } != packageId) return@withLock null
            val declared = stored.manifest.sources.orEmpty().singleOrNull { it.id == sourceId }
                ?: return@withLock null
            val reviewed = OfficialShinsouReviewedCatalog.matchContent(
                stored, declared, reviewedLocalRepositoryPolicy,
            )
            if (isDisabledReviewedLocalInstall(stored)) return@withLock null
            reviewedImageContentClient(
                sourceKeyFor(packageId, sourceId, declared.canonicalSourceId), stored.artifactIdentity(),
                environment.network.scopedToContentPolicy(reviewed?.networkPolicy ?: declared.networkPolicy()),
            )
        }

    /**
     * Exact v2 content scope for UI thumbnails and inline reader assets. Generic native runtimes
     * have no repository-defined network declaration and therefore remain denied. Reviewed
     * ShuYue runtimes derive this policy only from the host-pinned profile catalogue.
     */
    public suspend fun contentNetworkScopeForSource(sourceKey: SourceKey): TypedReaderRemoteAssetScope? =
        packageMutationMutex.withLock {
            sourceKey.legacyLongId?.let { legacyId ->
                val packageId = lifecycleMutex.withLock { sourceOwners[legacyId] }
                    ?: return@withLock null
                if (packageId != sourceKey.packageId) return@withLock null
                val stored = packageStore.get(packageId) ?: return@withLock null
                val declared = stored.manifest.sources.orEmpty().singleOrNull { it.id == legacyId }
                    ?: return@withLock null
                // The numeric id is only a compatibility projection. Require the complete
                // opaque key to match the live manifest too, otherwise a caller could attach a
                // valid legacy id to an unrelated source id and borrow its content authority.
                if (sourceKey != sourceKeyFor(packageId, legacyId, declared.canonicalSourceId)) {
                    return@withLock null
                }
                val reviewed = OfficialShinsouReviewedCatalog.matchContent(
                    stored, declared, reviewedLocalRepositoryPolicy,
                )
                if (isDisabledReviewedLocalInstall(stored)) return@withLock null
                return@withLock TypedReaderRemoteAssetScope(
                    sourceKey = sourceKey,
                    sourceId = legacyId,
                    network = reviewedImageContentClient(
                        sourceKey, stored.artifactIdentity(),
                        environment.network.scopedToContentPolicy(reviewed?.networkPolicy ?: declared.networkPolicy()),
                    ),
                )
            }
            val liveIdentity = nativeArtifactIdentities[sourceKey.packageId] ?: return@withLock null
            if (sourceKey in disabledNativeSources) return@withLock null
            if (reviewedContentAuthorizers[sourceKey.packageId]?.invoke() != true) return@withLock null
            val profile = dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedPluginCatalogV2.profiles
                .singleOrNull { candidate ->
                    candidate.identity.packageId == liveIdentity.packageId &&
                        candidate.identity.version == liveIdentity.version &&
                        candidate.identity.versionCode == liveIdentity.versionCode &&
                        candidate.identity.sha256 == liveIdentity.sha256 &&
                        sourceKey.sourceId in candidate.sourceIds
                } ?: return@withLock null
            val sourceProfile = profile.sourceProfiles.single { it.sourceId == sourceKey.sourceId }
            val origins = sourceProfile.contentOrigins
            if (origins.isEmpty()) return@withLock null
            val sourceId = dev.shinsou.kmp.plugin.shuyue.BuiltInShuYueExecutionScopesV2
                .resolve(profile.identity, sourceKey)
            TypedReaderRemoteAssetScope(
                sourceKey = sourceKey,
                sourceId = sourceId,
                network = reviewedImageContentClient(sourceKey, liveIdentity, environment.network
                    .forReviewedInProcessArtifact()
                    .scopedToContentPolicy(
                        PluginNetworkPolicy(
                            contentOrigins = origins,
                            maxResponseBytes = PLUGIN_NETWORK_MAX_RESPONSE_BYTES,
                        ),
                    )),
            )
        }

    /** No script-selected origin or identity can grant the browser image transport. */
    private fun reviewedImageContentClient(
        sourceKey: SourceKey,
        identity: PluginArtifactIdentity,
        native: PluginContentNetworkClient,
    ): PluginContentNetworkClient {
        val transport = reviewedImageTransport ?: return native
        if (!sourceKey.isReviewedBrowserImageCandidate()) return native
        return native.withReviewedImageTransport { request ->
            if (request.method != "GET" || !isReviewedBrowserImageUrl(request.url)) return@withReviewedImageTransport null
            // The reader's legacy Referer/UA hints are not browser credentials. Ignore them;
            // the isolated same-origin browser uses its real UA and no referrer/cookies.
            val imageRequest = request.copy(
                headers = emptyMap(),
                maxResponseBytes = minOf(request.maxResponseBytes, REVIEWED_BROWSER_IMAGE_MAX_BYTES),
            )
            reviewedImagePermits.acquire()
            try {
                require(isLiveReviewedBrowserImageSource(sourceKey, identity)) { "Reviewed browser image source is no longer authorized" }
                val resolution = resolvePluginTransportHost(Url(imageRequest.url), environment.hostResolver, false)
                validateReviewedBrowserImageRequest(imageRequest, resolution)
                val result = kotlinx.coroutines.withTimeout(30_000) {
                    environment.network.withContentRequestGate(resolution.host) {
                        transport.executeResolved(imageRequest, resolution)
                    }
                }
                require(isLiveReviewedBrowserImageSource(sourceKey, identity)) { "Reviewed browser image source changed during loading" }
                require(result.body.size <= imageRequest.maxResponseBytes && result.imageBodyForDecoderOrNull() != null) {
                    "Reviewed browser image returned invalid content"
                }
                result
            } finally { reviewedImagePermits.release() }
        }
    }

    private suspend fun isLiveReviewedBrowserImageSource(
        sourceKey: SourceKey,
        identity: PluginArtifactIdentity,
    ): Boolean = packageMutationMutex.withLock {
        if (!sourceKey.isReviewedBrowserImageCandidate() || isLocallyRevoked(identity)) return@withLock false
        sourceKey.legacyLongId?.let { sourceId ->
            if (sourceKey.packageId != "zh.bilimanga.manga") return@withLock false
            if (lifecycleMutex.withLock { sourceOwners[sourceId] } != sourceKey.packageId) return@withLock false
            val stored = packageStore.get(sourceKey.packageId) ?: return@withLock false
            if (stored.artifactIdentity() != identity) return@withLock false
            val declared = stored.manifest.sources.orEmpty().singleOrNull { it.id == sourceId }
                ?: return@withLock false
            val review = stored.eventGrantReview(includeEmpty = true) ?: return@withLock false
            return@withLock executionAdmissionGranted(review) &&
                sourceKeyFor(sourceKey.packageId, sourceId, declared.canonicalSourceId) == sourceKey &&
                OfficialShinsouReviewedCatalog.matchContent(
                    stored, declared, reviewedLocalRepositoryPolicy,
                ) != null
        }
        if (sourceKey.packageId != "zh.bilimanga" || nativeArtifactIdentities[sourceKey.packageId] != identity ||
            sourceKey in disabledNativeSources) return@withLock false
        exactReviewedShuYueProfile(identity, sourceKey) != null &&
            reviewedContentAuthorizers[sourceKey.packageId]?.invoke() == true &&
            extensionRegistryV2.packageFacade(sourceKey.packageId)?.source(sourceKey) != null
    }

    private fun SourceKey.isReviewedBrowserImageCandidate(): Boolean =
        if (legacyLongId != null) packageId == "zh.bilimanga.manga" &&
            legacyLongId == 7_289_707_411_592_168_382L
        else packageId == "zh.bilimanga" && sourceId == "zh.bilimanga.manga"

    public suspend fun contentNetworkForSource(sourceKey: SourceKey): PluginContentNetworkClient? =
        contentNetworkScopeForSource(sourceKey)?.network

    /** Exact event lookup; a recycled legacy Long can never cross artifact ownership. */
    public suspend fun exactLegacySource(target: ExactPluginSourceTarget): CatalogueSource? =
        lifecycleMutex.withLock {
            val runtime = eventScopes.entries.singleOrNull { (_, scope) ->
                scope.artifactIdentity == target.artifactIdentity && scope.sourceKey == target.sourceKey
            }?.key ?: return@withLock null
            val live = runtimes[target.artifactIdentity.packageId].orEmpty().any { it === runtime }
            runtime.takeIf { live && it.id == target.sourceKey.legacyLongId }
        }

    /** Materializes the currently live v1 package as an exact-keyed Extension v2 runtime. */
    @OptIn(ExtensionImplementationApi::class)
    public suspend fun extensionPackageRuntimeV2(pluginId: String): LegacyMangaPackageRuntimeV2? =
        packageMutationMutex.withLock { legacyPackageRuntimeV2WhilePackageLocked(pluginId) }

    /** Caller holds packageMutationMutex; avoids recursive acquisition in browser operations. */
    private suspend fun legacyPackageRuntimeV2WhilePackageLocked(pluginId: String): LegacyMangaPackageRuntimeV2? {
        val stored = packageStore.get(pluginId) ?: return null
        return lifecycleMutex.withLock lifecycleLock@{
            val live = runtimes[pluginId].orEmpty()
            if (live.isEmpty()) return@lifecycleLock null
            val declaredContentKinds = stored.manifest.declaredV2ContentKindsByLegacyId()
            if (live.none { declaredContentKinds[it.id]?.isNotEmpty() != false }) {
                return@lifecycleLock null
            }
            LegacyMangaPackageRuntimeV2(
                packageId = pluginId,
                version = stored.manifest.version,
                displayName = stored.manifest.name,
                sources = live,
                sourceKeysByLegacyId = stored.manifest.sources.orEmpty().associate { source ->
                    source.id to sourceKeyFor(pluginId, source.id, source.canonicalSourceId)
                },
                declaredContentKindsByLegacyId = declaredContentKinds,
            )
        }
    }

    private suspend fun sourceWhilePackageLocked(sourceKey: SourceKey): HostExtensionSourceV2? =
        extensionRegistryV2.packageFacade(sourceKey.packageId)?.source(sourceKey)
            ?: legacyPackageRuntimeV2WhilePackageLocked(sourceKey.packageId)
                ?.let { ExtensionHostFacadeV2(it).source(sourceKey) }

    /** Installs an already-admitted native v2 runtime; replacement and lifecycle are explicit. */
    @OptIn(ExtensionImplementationApi::class)
    public suspend fun installExtensionRuntimeV2(
        runtime: ExtensionPackageRuntimeV2,
        replace: Boolean = false,
    ): ExtensionHostFacadeV2 = packageMutationMutex.withLock {
        invalidateWebChallengeGrants()
        val facade = extensionRegistryV2.install(runtime, replace)
        // A generic replacement must never inherit an earlier reviewed runtime's identity.
        nativeArtifactIdentities.remove(runtime.descriptor.packageId)
        (runtime as? ArtifactBoundExtensionPackageRuntimeV2)?.let {
            nativeArtifactIdentities[runtime.descriptor.packageId] = it.artifactIdentity
        }
        (runtime as? SourceLifecycleControlledExtensionPackageRuntimeV2)?.let {
            nativeLifecycleRuntimes[runtime.descriptor.packageId] = it
        }
        reviewedReloaders.remove(runtime.descriptor.packageId)
        reviewedContentAuthorizers.remove(runtime.descriptor.packageId)
        disabledNativeSources.removeAll { it.packageId == runtime.descriptor.packageId }
        facade
    }

    /** Admits reviewed ShuYue bytes and atomically publishes their guarded runtime to v2 browse. */
    @OptIn(ExtensionImplementationApi::class)
    public suspend fun installReviewedShuYueRuntimeV2(
        admission: ShuYueReviewedPluginAdmissionV2,
        quarantineId: String,
        replace: Boolean = false,
    ): ExtensionHostFacadeV2 = packageMutationMutex.withLock {
        invalidateWebChallengeGrants()
        val review = admission.inspectQuarantine(quarantineId)
        require(packageStore.get(review.identity.packageId) == null) {
            "Extension package '${review.identity.packageId}' is already installed by the generic runtime"
        }
        if (!replace) require(extensionRegistryV2.packageFacade(review.identity.packageId) == null) {
            "Extension package '${review.identity.packageId}' is already loaded"
        }
        val runtime = admission.createRuntime(quarantineId)
        try {
            require(runtime.descriptor.packageId == review.identity.packageId) {
                "Reviewed runtime package differs from inspected quarantine"
            }
            require(runtime.descriptor.sources.map { it.sourceKey.sourceId } == review.sourceIds) {
                "Reviewed runtime source set differs from inspected quarantine"
            }
            extensionRegistryV2.install(runtime, replace).also {
                // The admitted review is the authority used to construct this runtime. Record it
                // directly so content scopes cannot disappear if a runtime decorator does not
                // expose ArtifactBoundExtensionPackageRuntimeV2 at this integration boundary.
                nativeArtifactIdentities[runtime.descriptor.packageId] = PluginArtifactIdentity(
                    review.identity.packageId,
                    review.identity.version,
                    review.identity.versionCode,
                    review.identity.sha256,
                )
                (runtime as? SourceLifecycleControlledExtensionPackageRuntimeV2)?.let { controlled ->
                    nativeLifecycleRuntimes[runtime.descriptor.packageId] = controlled
                }
                reviewedReloaders[runtime.descriptor.packageId] = {
                    admission.createRuntime(quarantineId)
                }
                reviewedContentAuthorizers[runtime.descriptor.packageId] = {
                    admission.isCurrentlyAuthorized(review.identity)
                }
                disabledNativeSources.removeAll { it.packageId == runtime.descriptor.packageId }
            }
        } catch (error: Throwable) {
            if (runtime is CloseableExtensionPackageRuntimeV2) runCatching { runtime.close() }
            throw error
        }
    }

    /** Builds the production reviewed installer without exposing runtimeFactory/environment. */
    public fun reviewedShuYueInstallCoordinatorV2(
        quarantineStore: ShuYueScriptQuarantineStoreV2,
        approvalStore: ShuYueExecutionApprovalStoreV2,
        installationStore: ShuYueReviewedInstallationStoreV2,
        credentialsResolver: LegacyLoginCredentialsResolverV2? = null,
    ): ShuYueReviewedInstallCoordinatorV2 {
        val admission = productionShuYueReviewedAdmissionV2(
            quarantineStore = quarantineStore,
            trustStore = approvalStore,
            permissionStore = approvalStore,
            runtimeFactory = runtimeFactory,
            environment = environment,
            credentialsResolver = credentialsResolver,
        )
        return ShuYueReviewedInstallCoordinatorV2(admission, approvalStore, this, installationStore)
    }

    public suspend fun uninstallExtensionRuntimeV2(packageId: String): Boolean =
        packageMutationMutex.withLock {
            invalidateWebChallengeGrants()
            nativeArtifactIdentities.remove(packageId)
            nativeLifecycleRuntimes.remove(packageId)
            reviewedReloaders.remove(packageId)
            reviewedContentAuthorizers.remove(packageId)
            disabledNativeSources.removeAll { it.packageId == packageId }
            extensionRegistryV2.uninstall(packageId)
        }

    /** Host-gated facade for an admitted native runtime or a currently live legacy package. */
    @OptIn(ExtensionImplementationApi::class)
    public suspend fun extensionFacadeV2(pluginId: String): ExtensionHostFacadeV2? =
        extensionRegistryV2.packageFacade(pluginId)
            ?: extensionPackageRuntimeV2(pluginId)?.let { ExtensionHostFacadeV2(it) }

    /** Exact opaque source lookup used by the production browse/detail/content gateway. */
    @OptIn(ExtensionImplementationApi::class)
    public suspend fun extensionSourceV2(sourceKey: SourceKey): HostExtensionSourceV2? =
        extensionFacadeV2(sourceKey.packageId)?.source(sourceKey)

    /**
     * Issues one exact-runtime browser capability. Generic packages are deliberately denied an
     * embedded browser: browser origins in repository/manifest data are not host review.
     */
    @OptIn(ExtensionImplementationApi::class)
    internal suspend fun issueWebChallenge(sourceKey: SourceKey): WebChallengeAuthorization? =
        packageMutationMutex.withLock {
            require(sourceKey !in disabledNativeSources) { "Browser challenge source is disabled" }
            val stored = packageStore.get(sourceKey.packageId)
            val identity = nativeArtifactIdentities[sourceKey.packageId]
                ?: stored?.artifactIdentity()
                ?: return@withLock null
            val reviewedOfficial = stored?.let { installed ->
                val declared = installed.manifest.sources.orEmpty().singleOrNull { source ->
                    sourceKeyFor(installed.manifest.id, source.id, source.canonicalSourceId) == sourceKey
                }
                OfficialShinsouReviewedCatalog.match(
                    installed, declared, reviewedLocalRepositoryPolicy,
                )
            }
            if (stored != null && reviewedOfficial == null) {
                throw IllegalArgumentException("Generic extensions cannot receive an embedded browser challenge")
            }
            val profile = exactReviewedShuYueProfile(identity, sourceKey)
            if (profile != null) require(
                dev.shinsou.kmp.plugin.shuyue.ShuYueExecutionPermissionV2.BROWSER_CHALLENGE in profile.requiredPermissions,
            ) { "Reviewed artifact lacks browser challenge permission" }
            require(profile != null ||
                PluginRuntimePermission.BROWSER_CHALLENGE in stored!!.manifest.runtimePermissions.orEmpty()
            ) {
                "Reviewed artifact lacks browser challenge permission"
            }
            val source = sourceWhilePackageLocked(sourceKey)
                ?: return@withLock null
            val origin = canonicalWebChallengeOrigin(source.webChallengeUrl() ?: source.descriptor.baseUrl)
                ?: return@withLock null
            val liveOrigins = canonicalWebChallengeOrigins(source.webChallengeBrowserSessionOrigins())
            val reviewedOrigins = if (profile != null) {
                canonicalWebChallengeOrigins(profile.sourceProfiles.single { it.sourceId == sourceKey.sourceId }.browserSessionOrigins)
            } else {
                canonicalWebChallengeOrigins(requireNotNull(reviewedOfficial).webChallengeOrigins)
            }
            require(origin in reviewedOrigins && (profile == null || origin in liveOrigins)) {
                "Web challenge URL is outside the exact reviewed origin"
            }
            val reviewedSubresourceOrigins = if (profile != null) setOf(origin) else {
                canonicalWebChallengeOrigins(requireNotNull(reviewedOfficial).webChallengeSubresourceOrigins)
            }
            require(origin in reviewedSubresourceOrigins) {
                "Web challenge document origin must remain an allowed subresource origin"
            }
            val storageId = if (profile != null) dev.shinsou.kmp.plugin.shuyue.BuiltInShuYueExecutionScopesV2
                .resolve(
                    dev.shinsou.kmp.plugin.shuyue.ShuYueArtifactIdentityV2(
                        identity.packageId, identity.version, identity.versionCode, identity.sha256,
                    ),
                    sourceKey,
                ) else requireNotNull(sourceKey.legacyLongId)
            val storage = if (profile != null) environment.storage else BoundPluginStorage(
                delegate = environment.storage,
                sourceKey = sourceKey,
                allowLegacyMigration = requireNotNull(stored).metadata.legacyStorageMigrationAllowed,
            )
            val localKeys = normalizeWebChallengeStorageKeys(source.webChallengeLocalStorageKeys())
            val requiredKeys = normalizeWebChallengeStorageKeys(source.requiredWebChallengeLocalStorageKeys()) -
                reviewedOfficial?.optionalWebChallengeStorageKeys.orEmpty()
            require(requiredKeys.all(localKeys::contains)) {
                "Required browser storage keys must be included in the source allowlist"
            }
            val capability = SourceWebChallengeCapability()
            val epoch = nativeRuntimeGeneration
            // Only the newest dialog for an exact source can retain import authority.
            webChallengeGrants.removeAll { it.sourceKey == sourceKey }
            while (webChallengeGrants.size >= MAX_WEB_CHALLENGE_GRANTS) {
                webChallengeGrants.removeAt(0)
            }
            webChallengeGrants += WebChallengeGrant(
                capability = capability,
                sourceKey = sourceKey,
                artifactIdentity = identity,
                runtimeEpoch = epoch,
                canonicalOrigin = origin,
                storageId = storageId,
                storage = storage,
                localStorageKeys = localKeys,
                requiredLocalStorageKeys = requiredKeys,
                requiredCookieName = reviewedOfficial?.requiredWebChallengeCookieName,
                reviewedShuYue = profile != null,
                reviewedOfficialPackage = reviewedOfficial != null,
            )
            WebChallengeAuthorization(
                capability, sourceKey, storageId, origin, source, localKeys, requiredKeys,
                allowedSubresourceOrigins = reviewedSubresourceOrigins,
                requiredCookieName = reviewedOfficial?.requiredWebChallengeCookieName,
                storage = storage,
            )
        }

    /** Host-only helper for deterministic native/reviewed test runtimes. */
    @OptIn(ExtensionImplementationApi::class)
    internal suspend fun installReviewedRuntimeForTest(
        runtime: ExtensionPackageRuntimeV2,
        identity: PluginArtifactIdentity,
        replace: Boolean = false,
    ): ExtensionHostFacadeV2 = packageMutationMutex.withLock {
        require(exactReviewedShuYueProfile(identity, runtime.descriptor.sources.single().sourceKey) != null) {
            "Test runtime is not an exact reviewed ShuYue artifact"
        }
        invalidateWebChallengeGrants()
        extensionRegistryV2.install(runtime, replace).also {
            nativeArtifactIdentities[runtime.descriptor.packageId] = identity
            reviewedContentAuthorizers[runtime.descriptor.packageId] = { true }
        }
    }

    /** Compatibility predicate; it does not issue import authority. */
    public suspend fun authorizeWebChallenge(sourceKey: SourceKey): Boolean =
        packageMutationMutex.withLock {
            if (sourceKey in disabledNativeSources) return@withLock false
            nativeArtifactIdentities[sourceKey.packageId]?.let { identity ->
                val profile = exactReviewedShuYueProfile(identity, sourceKey) ?: return@withLock false
                return@withLock !isLocallyRevoked(identity) &&
                    dev.shinsou.kmp.plugin.shuyue.ShuYueExecutionPermissionV2.BROWSER_CHALLENGE in profile.requiredPermissions &&
                    profile.sourceProfiles.singleOrNull { it.sourceId == sourceKey.sourceId }
                        ?.browserSessionOrigins?.isNotEmpty() == true &&
                    reviewedContentAuthorizers[sourceKey.packageId]?.invoke() == true &&
                    extensionRegistryV2.packageFacade(sourceKey.packageId)?.source(sourceKey) != null
            }
            val stored = packageStore.get(sourceKey.packageId) ?: return@withLock false
            if (isLocallyRevoked(stored.artifactIdentity())) return@withLock false
            val declared = stored.manifest.sources.orEmpty().singleOrNull { source ->
                sourceKeyFor(stored.manifest.id, source.id, source.canonicalSourceId) == sourceKey
            }
            val review = stored.eventGrantReview(includeEmpty = true) ?: return@withLock false
            OfficialShinsouReviewedCatalog.match(
                stored, declared, reviewedLocalRepositoryPolicy,
            )?.webChallengeOrigins?.isNotEmpty() == true &&
                PluginRuntimePermission.BROWSER_CHALLENGE in stored.manifest.runtimePermissions.orEmpty() &&
                executionAdmissionGranted(review) && sourceWhilePackageLocked(sourceKey) != null
        }

    /** Generic/legacy manifests are not equivalent to a host-reviewed embedded-browser scope. */
    public suspend fun authorizeWebChallenge(sourceId: Long): Boolean = false

    /** Dismissal consumes the exact token so stale platform callbacks cannot import later. */
    public suspend fun cancelWebChallenge(capability: SourceWebChallengeCapability) {
        packageMutationMutex.withLock {
            webChallengeGrants.removeAll { it.capability === capability }
        }
    }

    /**
     * Consumes and revalidates an import under the package mutation lock, then completes every
     * storage write before replacement/unload/revocation can enter the same critical section.
     */
    public suspend fun importWebChallengeSession(
        sourceKey: SourceKey,
        capability: SourceWebChallengeCapability,
        cookies: List<SourceCookie>,
        userAgent: String,
        localStorage: Map<String, String>,
    ): Unit = packageMutationMutex.withLock {
        val grantIndex = webChallengeGrants.indexOfFirst { it.capability === capability }
        require(grantIndex >= 0) { "Browser challenge capability is invalid or already consumed" }
        val grant = webChallengeGrants.removeAt(grantIndex)
        require(grant.sourceKey == sourceKey) { "Browser challenge source does not match its capability" }
        require(sourceKey !in disabledNativeSources) { "Browser challenge source is disabled" }

        val stored = packageStore.get(sourceKey.packageId)
        val liveIdentity = nativeArtifactIdentities[sourceKey.packageId] ?: stored?.artifactIdentity()
        require(liveIdentity == grant.artifactIdentity && nativeRuntimeGeneration == grant.runtimeEpoch) {
            "Browser challenge runtime is no longer live"
        }
        val profile = exactReviewedShuYueProfile(liveIdentity, sourceKey)
        val reviewedOfficial = stored?.let { installed ->
            val declared = installed.manifest.sources.orEmpty().singleOrNull { source ->
                sourceKeyFor(installed.manifest.id, source.id, source.canonicalSourceId) == sourceKey
            }
            OfficialShinsouReviewedCatalog.match(
                installed, declared, reviewedLocalRepositoryPolicy,
            )
        }
        require((grant.reviewedShuYue && profile != null && stored == null) ||
            (grant.reviewedOfficialPackage && reviewedOfficial != null)
        ) {
            "Browser challenge storage scope is no longer reviewed"
        }
        val source = sourceWhilePackageLocked(sourceKey)
            ?: throw IllegalArgumentException("Browser challenge source is no longer live")
        val liveOrigin = canonicalWebChallengeOrigin(source.webChallengeUrl() ?: source.descriptor.baseUrl)
        val liveOrigins = canonicalWebChallengeOrigins(source.webChallengeBrowserSessionOrigins())
        val reviewedOrigins = if (profile != null) {
            canonicalWebChallengeOrigins(profile.sourceProfiles.single { it.sourceId == sourceKey.sourceId }.browserSessionOrigins)
        } else {
            canonicalWebChallengeOrigins(requireNotNull(reviewedOfficial).webChallengeOrigins)
        }
        require(liveOrigin == grant.canonicalOrigin && liveOrigin in reviewedOrigins &&
            (profile == null || liveOrigin in liveOrigins)
        ) {
            "Browser challenge origin changed before import"
        }
        val liveKeys = normalizeWebChallengeStorageKeys(source.webChallengeLocalStorageKeys())
        val liveRequired = normalizeWebChallengeStorageKeys(source.requiredWebChallengeLocalStorageKeys()) -
            reviewedOfficial?.optionalWebChallengeStorageKeys.orEmpty()
        require(liveKeys == grant.localStorageKeys && liveRequired == grant.requiredLocalStorageKeys &&
            liveRequired.all(liveKeys::contains)
        ) { "Browser challenge storage declaration changed before import" }
        val safeStorage = validateImportedWebChallengeStorage(localStorage, liveKeys)
        require(liveRequired.all { !safeStorage[it].isNullOrEmpty() }) {
            "Required browser session data is missing"
        }
        val safeUserAgent = requireNotNull(normalizePluginUserAgent(userAgent)) {
            "Invalid browser User-Agent"
        }
        require(cookies.size <= MAX_WEB_CHALLENGE_IMPORT_COOKIES) {
            "Too many browser cookies were supplied"
        }
        var cookieBytes = 0
        val safeCookies = cookies.map { cookie ->
            cookieBytes += cookie.name.encodeToByteArray().size + cookie.value.encodeToByteArray().size +
                cookie.domain.encodeToByteArray().size + cookie.path.encodeToByteArray().size
            require(cookieBytes <= MAX_WEB_CHALLENGE_IMPORT_COOKIE_BYTES) {
                "Browser cookie import is too large"
            }
            validateImportedWebChallengeCookie(cookie, grant.canonicalOrigin)
        }
        grant.requiredCookieName?.let { requiredName ->
            val challengeUrl = io.ktor.http.Url(requireNotNull(source.webChallengeUrl() ?: source.descriptor.baseUrl))
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            require(safeCookies.any { cookie ->
                cookie.name == requiredName && cookie.value.isNotEmpty() && cookie.matches(challengeUrl, now)
            }) { "Required browser cookie is missing or does not apply to the challenge URL" }
        }
        require(safeCookies.isNotEmpty() || safeStorage.isNotEmpty()) {
            "No browser session data was supplied"
        }
        commitWebChallengeSession(grant, safeCookies, safeStorage, safeUserAgent)
    }

    /**
     * PluginStorage has no cross-key transaction primitive. Snapshot every touched value and
     * restore both values and true absence on failure. If even rollback fails, unload the reviewed
     * runtime so residual bytes cannot be consumed in this process. The UA is always written last
     * and restored last as a commit marker convention.
     */
    private suspend fun commitWebChallengeSession(
        grant: WebChallengeGrant,
        cookies: List<PluginCookie>,
        localStorage: Map<String, String>,
        userAgent: String,
    ) = withContext(NonCancellable) {
        val previousCookies = grant.storage.getCookies(grant.storageId)
        val previousStorage = localStorage.keys.associateWith {
            grant.storage.getPreference(grant.storageId, it)
        }
        val previousUserAgent = grant.storage.getWebChallengeUserAgent(grant.storageId)
        try {
            cookies.forEach { grant.storage.setCookie(grant.storageId, it) }
            localStorage.forEach { (key, value) -> grant.storage.setPreference(grant.storageId, key, value) }
            grant.storage.setWebChallengeUserAgent(grant.storageId, userAgent)
        } catch (writeFailure: Throwable) {
            val rollbackFailure = runCatching {
                grant.storage.clearCookies(grant.storageId)
                previousCookies.forEach { grant.storage.setCookie(grant.storageId, it) }
                previousStorage.forEach { (key, value) ->
                    if (value == null) grant.storage.removePreference(grant.storageId, key)
                    else grant.storage.setPreference(grant.storageId, key, value)
                }
                if (previousUserAgent == null) grant.storage.clearWebChallengeUserAgent(grant.storageId)
                else grant.storage.setWebChallengeUserAgent(grant.storageId, previousUserAgent)
            }.exceptionOrNull()
            if (rollbackFailure != null) {
                writeFailure.addSuppressed(rollbackFailure)
                runCatching { grant.storage.clearCookies(grant.storageId) }
                    .exceptionOrNull()?.let(writeFailure::addSuppressed)
                grant.requiredLocalStorageKeys.forEach { key ->
                    runCatching { grant.storage.removePreference(grant.storageId, key) }
                        .exceptionOrNull()?.let(writeFailure::addSuppressed)
                }
                runCatching { grant.storage.clearWebChallengeUserAgent(grant.storageId) }
                    .exceptionOrNull()?.let(writeFailure::addSuppressed)
                nativeArtifactIdentities.remove(grant.sourceKey.packageId)
                nativeLifecycleRuntimes.remove(grant.sourceKey.packageId)
                reviewedReloaders.remove(grant.sourceKey.packageId)
                runCatching { extensionRegistryV2.uninstall(grant.sourceKey.packageId) }
                    .exceptionOrNull()?.let(writeFailure::addSuppressed)
                invalidateWebChallengeGrants()
            }
            throw writeFailure
        }
    }

    @OptIn(ExtensionImplementationApi::class)
    public suspend fun exactExtensionSource(target: ExactPluginSourceTarget): HostExtensionSourceV2? =
        packageMutationMutex.withLock {
            val packageId = target.sourceKey.packageId
            val legacy = lifecycleMutex.withLock {
                val exactRuntime = eventScopes.entries.singleOrNull { (_, scope) ->
                    scope.artifactIdentity == target.artifactIdentity && scope.sourceKey == target.sourceKey
                }?.key ?: return@withLock null
                val live = runtimes[packageId].orEmpty()
                if (live.none { it === exactRuntime }) return@withLock null
                val stored = packageStore.get(packageId) ?: return@withLock null
                val declaredContentKinds = stored.manifest.declaredV2ContentKindsByLegacyId()
                if (live.none { declaredContentKinds[it.id]?.isNotEmpty() != false }) {
                    return@withLock null
                }
                LegacyMangaPackageRuntimeV2(
                    packageId = packageId,
                    version = stored.manifest.version,
                    displayName = stored.manifest.name,
                    sources = live,
                    sourceKeysByLegacyId = stored.manifest.sources.orEmpty().associate { source ->
                        source.id to sourceKeyFor(packageId, source.id, source.canonicalSourceId)
                    },
                    declaredContentKindsByLegacyId = declaredContentKinds,
                )
            }
            if (legacy != null) return@withLock ExtensionHostFacadeV2(legacy).source(target.sourceKey)
            if (nativeArtifactIdentities[packageId] != target.artifactIdentity) return@withLock null
            extensionRegistryV2.packageFacade(packageId)?.source(target.sourceKey)
        }

    /** Enables modal event permissions only for one foreground UI-triggered source invocation. */
    public suspend fun <T> withUserInteractionContext(sourceKey: SourceKey, block: suspend () -> T): T {
        val scopes = lifecycleMutex.withLock {
            eventScopes.values.filter { it.sourceKey == sourceKey }
        }
        // Legacy-backed V2 sources execute the same engine as the v1 UI path, but their opaque
        // SourceKey otherwise bypasses the old source-id wrapper.  Issue the host context handle
        // for the exact live scope across the complete detail/units/content call.
        val gateway = environment.systemEventSink as? PluginSystemEventGateway
        val interactionGranted = interactionMutex.withLock {
            if (!pluginUiAvailable) {
                false
            } else {
                scopes.forEach { scope ->
                    val next = (interactionCounts[scope.runtimeKey] ?: 0) + 1
                    interactionCounts[scope.runtimeKey] = next
                    if (next == 1) gateway?.setUserInteractionContext(scope, true)
                }
                true
            }
        }
        return try {
            // UI availability gates only modal host-event authority. The source operation itself
            // must still run after an already-started user action if a desktop foreground event is
            // delayed or the app moves to the background while network authentication is active.
            if (interactionGranted) {
                extensionSourceV2(sourceKey)?.withUserInteractionContext(block) ?: block()
            } else {
                block()
            }
        } finally {
            if (interactionGranted) {
                interactionMutex.withLock {
                    scopes.forEach { scope ->
                        val next = (interactionCounts[scope.runtimeKey] ?: 1) - 1
                        if (next <= 0) {
                            interactionCounts.remove(scope.runtimeKey)
                            gateway?.setUserInteractionContext(scope, false)
                        } else interactionCounts[scope.runtimeKey] = next
                    }
                }
            }
        }
    }

    /** Legacy UI row counterpart; resolves only the currently bound exact runtime scope. */
    public suspend fun <T> withUserInteractionContext(sourceId: Long, block: suspend () -> T): T {
        val scope = lifecycleMutex.withLock {
            eventScopes.values.singleOrNull { it.sourceKey.legacyLongId == sourceId }
        } ?: return block()
        val gateway = environment.systemEventSink as? PluginSystemEventGateway
        val interactionGranted = interactionMutex.withLock {
            if (!pluginUiAvailable) {
                false
            } else {
                val next = (interactionCounts[scope.runtimeKey] ?: 0) + 1
                interactionCounts[scope.runtimeKey] = next
                if (next == 1) gateway?.setUserInteractionContext(scope, true)
                true
            }
        }
        return try {
            block()
        } finally {
            if (interactionGranted) {
                interactionMutex.withLock {
                    val next = (interactionCounts[scope.runtimeKey] ?: 1) - 1
                    if (next <= 0) {
                        interactionCounts.remove(scope.runtimeKey)
                        gateway?.setUserInteractionContext(scope, false)
                    } else interactionCounts[scope.runtimeKey] = next
                }
            }
        }
    }

    public suspend fun <T> withVisibleEventContext(
        sourceKey: SourceKey,
        publicationId: String,
        unitId: String? = null,
        block: suspend () -> T,
    ): T {
        val scope = lifecycleMutex.withLock {
            eventScopes.values.singleOrNull { it.sourceKey == sourceKey }
        }
        val registry = environment.systemEventContextRegistry
            ?: (environment.systemEventSink as? PluginSystemEventGateway)?.contextRegistry
        if (registry == null || scope == null) return block()
        return registry.withInvocation(
            scope,
            dev.shinsou.kmp.plugin.events.PluginEventContextRegistry.VisibleContext(
                publicationId,
                unitId,
            ),
        ) { block() }
    }

    public suspend fun setPluginUiAvailable(available: Boolean) {
        val scopes = lifecycleMutex.withLock { eventScopes.values.toList() }
        val gateway = environment.systemEventSink as? PluginSystemEventGateway
        interactionMutex.withLock {
            pluginUiAvailable = available
            scopes.forEach { scope ->
                gateway?.setRuntimeLifecycle(
                    scope,
                    if (available) PluginRuntimeLifecycle.OPEN_FOREGROUND_UNLOCKED
                    else PluginRuntimeLifecycle.OPEN_BACKGROUND,
                )
                if (!available) interactionCounts.remove(scope.runtimeKey)
            }
        }
        extensionRegistryV2.descriptors().forEach { descriptor ->
            val facade = extensionRegistryV2.packageFacade(descriptor.packageId) ?: return@forEach
            descriptor.sources.forEach { sourceDescriptor ->
                facade.source(sourceDescriptor.sourceKey)?.setHostUiAvailable(available)
            }
        }
    }

    public suspend fun setEventSourceEnabled(sourceKey: SourceKey, enabled: Boolean) {
        packageMutationMutex.withLock {
            invalidateWebChallengeGrants()
            if (!enabled) {
                val scopes = lifecycleMutex.withLock {
                    eventScopes.values.filter { it.sourceKey == sourceKey }
                }
                val gateway = environment.systemEventSink as? PluginSystemEventGateway
                scopes.forEach { gateway?.closeRuntime(it) }
                nativeLifecycleRuntimes[sourceKey.packageId]?.setSourceEnabled(sourceKey, false)
                disabledNativeSources += sourceKey
                return@withLock
            }
            val reloader = reviewedReloaders[sourceKey.packageId]
            if (reloader != null) {
                val runtime = reloader()
                try {
                    extensionRegistryV2.install(runtime, replace = true)
                    (runtime as? ArtifactBoundExtensionPackageRuntimeV2)?.let {
                        nativeArtifactIdentities[sourceKey.packageId] = it.artifactIdentity
                    }
                    // Guarded reviewed reloaders intentionally hide the artifact-bound delegate.
                    // Preserve the already-authorized exact identity across that replacement.
                    require(nativeArtifactIdentities[sourceKey.packageId] != null) {
                        "Reviewed reload lost exact artifact identity"
                    }
                    (runtime as? SourceLifecycleControlledExtensionPackageRuntimeV2)?.let {
                        nativeLifecycleRuntimes[sourceKey.packageId] = it
                    }
                    disabledNativeSources.remove(sourceKey)
                } catch (error: Throwable) {
                    if (runtime is CloseableExtensionPackageRuntimeV2) runCatching { runtime.close() }
                    throw error
                }
                return@withLock
            }
            val stored = packageStore.get(sourceKey.packageId) ?: return@withLock
            try {
                lifecycleMutex.withLock { loadStored(stored) }
                if (sourceWhilePackageLocked(sourceKey) != null) disabledNativeSources.remove(sourceKey)
            } catch (_: ScriptRuntimeUnavailableException) {
                // Approval is still a valid user decision, but this host cannot safely execute
                // the generic in-process engine. Keep the verified artifact installed and
                // explicitly inert rather than surfacing a misleading install failure or
                // leaving a durable grant that could resurrect on restart.
                locallyRevokedArtifacts += stored.artifactIdentity()
                quarantineUnavailableRuntime(requireNotNull(stored.eventGrantReview(includeEmpty = true)))
            }
        }
    }

    public suspend fun setEventSourceEnabled(sourceId: Long, enabled: Boolean) {
        val sourceKey = lifecycleMutex.withLock {
            eventScopes.values.singleOrNull { it.sourceKey.legacyLongId == sourceId }?.sourceKey
        } ?: return
        setEventSourceEnabled(sourceKey, enabled)
    }

    public suspend fun extensionDescriptorsV2(): List<ExtensionPackageV2> = extensionRegistryV2.descriptors()

    public suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.Default) {
        packageMutationMutex.withLock {
            invalidateWebChallengeGrants()
            lifecycleMutex.withLock {
                closeAllRuntimes()
                sources.clear()
                sourceOwners.clear()
                sourceReservations.clear()
            }
            nativeLifecycleRuntimes.clear()
            reviewedReloaders.clear()
            reviewedContentAuthorizers.clear()
            disabledNativeSources.clear()
            nativeArtifactIdentities.clear()
            extensionRegistryV2.close()
        }
    }

    private suspend fun replaceRuntimes(
        pluginId: String,
        candidates: List<ScriptPluginRuntime>,
    ) {
        validateRuntimeCandidates(pluginId, candidates)
        unloadPlugin(pluginId)
        runtimes[pluginId] = candidates.toList()
        candidates.forEach { candidate ->
            sources[candidate.id] = candidate
            sourceOwners[candidate.id] = pluginId
        }
    }

    /** Rebuilds the last durable package after a late commit failure (for example ledger I/O). */
    private suspend fun restorePreviousRuntime(pluginId: String, previous: StoredPlugin?) {
        lifecycleMutex.withLock {
            unloadPlugin(pluginId)
            if (previous == null) return@withLock
            val reservation = reserveManifestSources(previous.manifest)
            try {
                if (previous.isMetadataOnly()) {
                    addMetadataSources(previous)
                    commitReservation(reservation)
                    return@withLock
                }
                if (!previous.hasExecutableRuntimeDeclaration()) {
                    commitReservation(reservation)
                    return@withLock
                }
                val review = requireNotNull(previous.eventGrantReview(includeEmpty = true))
                val executable = executionAdmissionGranted(review)
                if (executable) {
                    val restored = createRuntimeCandidates(previous)
                    replaceRuntimes(pluginId, restored)
                }
                commitReservation(reservation)
            } catch (restoreError: Throwable) {
                rollbackReservation(reservation)
                throw restoreError
            }
        }
    }

    private fun validateRuntimeCandidates(
        pluginId: String,
        candidates: List<ScriptPluginRuntime>,
    ) {
        require(candidates.isNotEmpty()) { "Plugin '$pluginId' produced no executable sources" }
        val candidateIds = candidates.map(ScriptPluginRuntime::id)
        require(candidateIds.distinct().size == candidateIds.size) {
            "Plugin '$pluginId' produced duplicate executable source ids"
        }
        candidateIds.forEach { sourceId ->
            val owner = sourceOwners[sourceId]
            require(owner == null || owner == pluginId) {
                "Source id $sourceId is already owned by plugin '$owner'"
            }
            require(sourceReservations[sourceId] == pluginId) {
                "Source id $sourceId was not reserved for plugin '$pluginId'"
            }
        }
    }

    private suspend fun loadStored(stored: StoredPlugin) {
        // Package stores are replaceable host dependencies. Re-establish the complete durable
        // invariant here so even a weak/custom implementation cannot split event-grant identity,
        // manifest signature, and the exact bytes handed to a runtime.
        requireValidStoredPluginPackage(stored)
        if (!isAdmittedReviewedLocalInstall(stored)) {
            unloadPlugin(stored.manifest.id)
            return
        }
        if (stored.isMetadataOnly()) {
            val trusted = PluginVerifier.isLegacyTrustValid(stored) ||
                verifier.isTrusted(stored.manifest, stored.metadata.installedSha256)
            if (!trusted) {
                val versionCode = stored.manifest.versionCode
                    ?: PluginVerifier.versionInt(stored.manifest.version)
                throw PluginVerificationException.Untrusted(
                    stored.manifest.id,
                    versionCode,
                    stored.metadata.installedSha256,
                )
            }
            val reservation = reserveManifestSources(stored.manifest)
            try {
                addMetadataSources(stored)
                commitReservation(reservation)
            } catch (error: Throwable) {
                rollbackReservation(reservation)
                throw error
            }
            return
        }

        if (!stored.hasExecutableRuntimeDeclaration()) {
            unloadPlugin(stored.manifest.id)
            return
        }

        // The app-shipped reviewed catalogue is an independent code-review authority.  Its
        // exact artifact identity plus the persisted event-grant approval is sufficient to
        // reconstruct a runtime; do not make a non-sensitive, already-reviewed package depend
        // on reopening the encrypted desktop trust-token store during cold start.  Generic
        // repository packages still require the durable verifier token, so this is not a way to
        // turn an arbitrary repository entry into executable code.
        if (!PluginVerifier.isLegacyTrustValid(stored) && !stored.isBuiltInReviewedArtifact()) {
            verifier.verify(
                stored.scriptBytes,
                stored.manifest,
                trustOnValidatedDigest = false,
            )
        }
        val review = requireNotNull(stored.eventGrantReview(includeEmpty = true))
        val admitted = try {
            prepareExecutionAdmission(review)
        } catch (error: Throwable) {
            // loadStored is also used to re-enable an already materialized source. Admission I/O
            // failure must tear down that existing runtime instead of leaving it callable.
            unloadPlugin(stored.manifest.id)
            throw error
        }
        if (!admitted) {
            unloadPlugin(stored.manifest.id)
            return
        }
        require(extensionRegistryV2.packageFacade(stored.manifest.id) == null) {
            "Extension package '${stored.manifest.id}' is already loaded by the V2 runtime"
        }
        val reservation = reserveManifestSources(stored.manifest)
        var candidates: List<ScriptPluginRuntime> = emptyList()
        var liveStatePublished = false
        try {
            candidates = createRuntimeCandidates(stored)
            replaceRuntimes(stored.manifest.id, candidates)
            liveStatePublished = true
            commitReservation(reservation)
        } catch (error: Throwable) {
            if (liveStatePublished) unloadPlugin(stored.manifest.id)
            else closeRuntimes(candidates)
            rollbackReservation(reservation)
            throw error
        }
    }

    /** Builds every declared source independently; a package list position is never executed. */
    private suspend fun createRuntimeCandidates(stored: StoredPlugin): List<ScriptPluginRuntime> {
        val manifest = stored.manifest
        requireSupportedGenericRuntime(manifest)
        val declaredRuntimePermissions = requireNotNull(manifest.runtimePermissions) {
            "Plugin '${manifest.id}' has no executable runtime permission declaration"
        }
        require(PluginRuntimePermission.EXECUTE_SCRIPT in declaredRuntimePermissions) {
            "Plugin '${manifest.id}' lacks EXECUTE_SCRIPT permission"
        }
        val declared = manifest.sources.orEmpty()
        require(declared.map(SourceIndexEntry::id).distinct().size == declared.size) {
            "Plugin '${manifest.id}' declares duplicate source ids"
        }
        val candidates = mutableListOf<ScriptPluginRuntime>()
        try {
            if (declared.isEmpty()) {
                candidates += createScopedRuntime(stored, null)
            } else {
                declared.forEach { source ->
                    candidates += createScopedRuntime(stored, source)
                }
            }
            require(candidates.all { it.pluginId == manifest.id }) {
                "Plugin runtime package identity does not match '${manifest.id}'"
            }
            if (declared.isNotEmpty()) {
                require(candidates.map(ScriptPluginRuntime::id).toSet() == declared.map(SourceIndexEntry::id).toSet()) {
                    "Plugin runtime source identities do not match '${manifest.id}' declarations"
                }
            }
            return candidates.toList()
        } catch (error: Throwable) {
            closeRuntimes(candidates)
            throw error
        }
    }

    private suspend fun createScopedRuntime(
        stored: StoredPlugin,
        source: SourceIndexEntry?,
    ): ScriptPluginRuntime {
        val manifest = stored.manifest
        check(runtimeGeneration < Long.MAX_VALUE) { "Plugin runtime generation space is exhausted" }
        val generation = ++runtimeGeneration
        val legacyId = source?.id ?: stableSourceId(manifest.id)
        val sourceKey = sourceKeyFor(manifest.id, legacyId, source?.canonicalSourceId)
        val artifactIdentity = PluginArtifactIdentity(
            packageId = manifest.id,
            version = manifest.version,
            versionCode = manifest.versionCode ?: PluginVerifier.versionInt(manifest.version),
            sha256 = stored.metadata.installedSha256,
        )
        val scope = eventScopeFactory.bind(
            artifactIdentity = artifactIdentity,
            sourceKey = sourceKey,
            runtimeInstanceId = stablePluginRuntimeInstanceId(artifactIdentity, sourceKey),
            runtimeGeneration = generation,
        )
        val negotiated = manifest.systemEvents?.let { declaration ->
            (environment.systemEventSink as? PluginSystemEventGateway)?.negotiate(scope, declaration)
                ?: PluginSystemCapabilityNegotiator(supportedCapabilities = emptySet()).negotiate(declaration)
        }
        val legacyLoginCompatibility = manifest.systemEvents == null
        val visibleNegotiation = negotiated ?: PluginSystemEventNegotiation(enabled = false)
        val runtimePermissions = effectiveRuntimePermissions(stored)
        val reviewedOfficialSource = OfficialShinsouReviewedCatalog.match(
            stored, source, reviewedLocalRepositoryPolicy,
        )
        val boundStorage = PermissionFilteredPluginStorage(
            BoundPluginStorage(
                delegate = environment.storage,
                sourceKey = sourceKey,
                allowLegacyMigration = stored.metadata.legacyStorageMigrationAllowed,
            ),
            runtimePermissions,
        )
        val scopedEnvironment = environment.copy(
            network = environment.network
                .scopedToStorage(boundStorage)
                .let { network ->
                    if (reviewedOfficialSource == null) network
                    else network.forReviewedInProcessArtifact()
                }
                .scopedToPolicy(
                    reviewedOfficialSource?.networkPolicy
                        ?: source?.networkPolicy()
                        ?: PluginNetworkPolicy.Default,
                ),
            storage = boundStorage,
            runtimePermissions = runtimePermissions,
            // A generic repository, exact-artifact user grant, or trusted digest is not a host
            // code review. Only the app-shipped official catalogue may mint new provenance here;
            // a caller-supplied capability is still discarded even if its public fields match.
            inProcessScriptProvenance = reviewedOfficialSource?.provenance(
                artifact = artifactIdentity,
                sourceKey = sourceKey,
                evaluatedScript = stored.script,
            ),
            // Legacy code receives only the native ingress needed by bridge.requestLogin. Its
            // capability response remains disabled, and the exact authorizer below grants login
            // only after the trusted runtime proves it implements LoginSource.
            systemEventSink = environment.systemEventSink.takeIf {
                negotiated?.enabled == true || legacyLoginCompatibility
            },
            boundPluginScope = scope.takeIf {
                negotiated?.enabled == true || legacyLoginCompatibility
            },
            systemEventNegotiation = visibleNegotiation,
            systemEventDeclaration = manifest.systemEvents,
        )
        val runtime = if (source == null) {
            runtimeFactory.create(stored.script, manifest, scopedEnvironment)
        } else {
            runtimeFactory.createForSource(stored.script, manifest, source, scopedEnvironment)
        }
        eventScopes[runtime] = scope
        (environment.systemEventSink as? PluginSystemEventGateway)?.let { gateway ->
            if (legacyLoginCompatibility && runtime.supportsLogin) {
                gateway.grantRuntimePermissions(scope, setOf(PluginHostPermission.REQUEST_LOGIN_UI))
                legacyLoginCompatibilityScopes += scope.runtimeKey
            }
            gateway.openRuntime(scope, PluginEventRuntimeStatus(
                sourceCapabilities = buildSet {
                    add("CATALOGUE")
                    if (runtime.supportsLogin) add("LOGIN")
                    if (runtime.supportsLatest) add("LATEST")
                },
            ))
        }
        return runtime
    }

    private fun StoredPlugin.artifactIdentity(): PluginArtifactIdentity = PluginArtifactIdentity(
        packageId = manifest.id,
        version = manifest.version,
        versionCode = manifest.versionCode ?: PluginVerifier.versionInt(manifest.version),
        sha256 = metadata.installedSha256,
    )

    /** Confirms the package commit before any prebuilt runtime becomes live. */
    private suspend fun requirePersistedPackageMatches(expected: StoredPlugin) {
        val persisted = requireNotNull(packageStore.get(expected.manifest.id)) {
            "Plugin package commit could not be reconstructed"
        }
        requireValidStoredPluginPackage(persisted)
        require(persisted.metadata == expected.metadata &&
            persisted.scriptBytes.contentEquals(expected.scriptBytes)
        ) { "Persisted plugin package differs from its verified installation bytes" }
    }

    private fun StoredPlugin.eventGrantReview(includeEmpty: Boolean = false): PluginEventGrantReview? {
        val effectiveRuntimePermissions = effectiveRuntimePermissions(this)
        if (!includeEmpty && manifest.requestedHostPermissions.isEmpty() && effectiveRuntimePermissions.isEmpty()) return null
        val artifact = artifactIdentity()
        val declaredSources = manifest.sources.orEmpty()
        val sourceKeys = if (declaredSources.isEmpty()) {
            val id = stableSourceId(manifest.id)
            listOf(SourceKey(packageId = manifest.id, sourceId = id.toString(), legacyLongId = id))
        } else {
            declaredSources.map { source ->
                sourceKeyFor(manifest.id, source.id, source.canonicalSourceId)
            }
        }
        return PluginEventGrantReview(
            artifact,
            sourceKeys,
            manifest.requestedHostPermissions,
            effectiveRuntimePermissions,
        )
    }

    /** Hydrates a pending review or restores a matching exact grant; missing admission denies. */
    private suspend fun prepareExecutionAdmission(review: PluginEventGrantReview): Boolean {
        // Do not hydrate an in-memory authorizer from a durable grant which this process has
        // already tombstoned after a failed revocation. There is no runtime to authorize, and
        // retaining that grant in memory only widens the consequences of a teardown defect.
        if (isLocallyRevoked(review.artifact)) return false
        val admission = eventGrantAdmission
        if (admission != null) {
            admission.hydrate(review)
            return executionAdmissionGranted(review)
        }
        return executionAdmissionGranted(review)
    }

    private suspend fun executionAdmissionGranted(review: PluginEventGrantReview): Boolean {
        if (isLocallyRevoked(review.artifact)) return false
        return eventGrantAdmission?.isGranted(review)
            ?: (executionAdmissionMode == PluginExecutionAdmissionMode.UNSAFE_DEVELOPER_COMPATIBILITY)
    }

    /**
     * Converts an engine-isolation failure into a durable pending/quarantine state.
     *
     * A user approval is an admission decision for one exact artifact, not an override of the
     * host's inability to isolate an in-process JavaScript engine.  If runtime construction fails
     * for that reason, revoke the exact grant first and then stage the same review as pending so
     * the verified bytes remain available for inspection/uninstall without becoming executable on
     * the next restart.
     */
    private suspend fun quarantineUnavailableRuntime(review: PluginEventGrantReview) {
        locallyRevokedArtifacts += review.artifact
        eventGrantAdmission?.revoke(review.artifact)
        eventGrantAdmission?.hydrate(review)
    }

    private fun isLocallyRevoked(artifact: PluginArtifactIdentity): Boolean =
        artifact.packageId in locallyRevokedPluginIds || artifact in locallyRevokedArtifacts

    /** Existing install callers receive the single v1 source, or an explicit non-source handle. */
    private fun runtimeCompatibilityResult(
        manifest: PluginManifest,
        candidates: List<ScriptPluginRuntime>,
    ): ScriptPluginRuntime = candidates.singleOrNull()
        ?: MultiSourceScriptPluginRuntimeHandle(manifest, candidates)

    private fun StoredPlugin.isMetadataOnly(): Boolean =
        scriptBytes.isEmpty() &&
            metadata.installedSha256.isBlank() &&
            manifest.signature.isBlank()

    /** True only for an exact app-shipped code-review profile and every declared source. */
    private fun StoredPlugin.isBuiltInReviewedArtifact(): Boolean {
        val declaredSources = manifest.sources.orEmpty()
        return declaredSources.isNotEmpty() && declaredSources.all { source ->
            OfficialShinsouReviewedCatalog.match(this, source, reviewedLocalRepositoryPolicy) != null
        }
    }

    private fun isReviewedLocalInstall(stored: StoredPlugin): Boolean =
        isKnownReviewedLocalRepositoryBaseUrl(
            stored.metadata.repositoryBaseUrl?.trim()?.trimEnd('/').orEmpty(),
        )

    /** Local provenance is executable only while opt-in and every compiled-review check hold. */
    private fun isAdmittedReviewedLocalInstall(stored: StoredPlugin): Boolean {
        if (!isReviewedLocalInstall(stored)) return true
        val repositoryBaseUrl = stored.metadata.repositoryBaseUrl?.trim()?.trimEnd('/')
        return reviewedLocalRepositoryPolicy.admits(repositoryBaseUrl.orEmpty()) &&
            stored.isBuiltInReviewedArtifact()
    }

    private fun isDisabledReviewedLocalInstall(stored: StoredPlugin): Boolean =
        isReviewedLocalInstall(stored) && !isAdmittedReviewedLocalInstall(stored)

    private fun requireReviewedLocalRepositoryEntry(
        repository: ExtensionRepository,
        entry: PluginIndexEntry,
    ) {
        val normalized = repository.baseUrl.trim().trimEnd('/')
        if (!isKnownReviewedLocalRepositoryBaseUrl(normalized)) return
        require(reviewedLocalRepositoryPolicy.admits(normalized)) {
            "Reviewed local repository development mode is disabled"
        }
        require(
            OfficialShinsouReviewedCatalog.matchReviewedRepositoryEntry(
                entry, reviewedLocalRepositoryPolicy, normalized,
            ) != null,
        ) { "Local repository entry is not an exact current app-reviewed artifact" }
    }

    /**
     * Absence from this map is pre-V2 compatibility. V2 omission is represented by `false` and
     * inherits only the verified package union; `true` preserves even an explicitly empty set.
     */
    private fun PluginManifest.declaredV2ContentKindsByLegacyId(): Map<Long, Set<ContentKind>> =
        sources.orEmpty().mapNotNull { source ->
            val declared = when (source.contentKindsDeclared) {
                null -> return@mapNotNull null
                false -> contentKinds.orEmpty()
                true -> source.contentKinds
            }
            source.id to declared.mapTo(linkedSetOf()) { value ->
                ContentKind.entries.singleOrNull { it.name == value }
                    ?: throw IllegalArgumentException("Unknown V2 content kind '$value'")
            }
        }.toMap()

    private fun addMetadataSources(stored: StoredPlugin): List<CatalogueSource> =
        stored.manifest.sources.orEmpty().map {
            MetadataStubCatalogueSource(stored.manifest.id, it)
        }.also { stubs ->
            require(stubs.map(CatalogueSource::id).distinct().size == stubs.size) {
                "Plugin '${stored.manifest.id}' declares duplicate source ids"
            }
            stubs.forEach { source ->
                val owner = sourceOwners[source.id]
                require(owner == null || owner == stored.manifest.id) {
                    "Source id ${source.id} is already owned by plugin '$owner'"
                }
            }
            stubs.forEach { source ->
                sources[source.id] = source
                sourceOwners[source.id] = stored.manifest.id
            }
        }

    private suspend fun unloadPlugin(pluginId: String) {
        removeSourcesFor(pluginId)
        runtimes.remove(pluginId)?.let { runtime -> closeRuntimes(runtime) }
    }

    private suspend fun closeAllRuntimes() {
        runtimes.values.flatten().toList().forEach { runtime -> closeRuntime(runtime) }
        runtimes.clear()
    }

    /** Runtime teardown must finish even when the operation that made it unreachable is cancelled. */
    private suspend fun closeRuntime(runtime: ScriptPluginRuntime) {
        eventScopes.remove(runtime)?.let { scope ->
            (environment.systemEventSink as? PluginSystemEventGateway)?.let { gateway ->
                gateway.closeRuntime(scope)
                if (legacyLoginCompatibilityScopes.remove(scope.runtimeKey)) {
                    gateway.revokeRuntimePermissions(scope)
                }
            }
        }
        withContext(NonCancellable) { runCatching { runtime.close() } }
    }

    private suspend fun closeRuntimes(values: Iterable<ScriptPluginRuntime>) {
        values.forEach { runtime -> closeRuntime(runtime) }
    }

    private fun removeSourcesFor(pluginId: String) {
        val ownedRuntimeObjects = runtimes[pluginId].orEmpty()
        // Remove by object identity so a malfunctioning runtime getter cannot block revocation.
        // Also remove persisted IDs for legacy multi-source entries.
        val known = sources.filterValues { source ->
            ownedRuntimeObjects.any { it === source } ||
                source is MetadataStubCatalogueSource && source.pluginId == pluginId
        }.keys
        known.forEach { sourceId ->
            sources.remove(sourceId)
            if (sourceOwners[sourceId] == pluginId) sourceOwners.remove(sourceId)
        }
    }

    private data class SourceReservation(val packageId: String, val sourceIds: Set<Long>)

    /** Must run under [lifecycleMutex] before creating/evaluating any package runtime. */
    private fun reserveManifestSources(manifest: PluginManifest): SourceReservation {
        val sourceIds = declaredSourceIds(manifest)
        require(sourceIds.size == manifest.sources.orEmpty().size.coerceAtLeast(1)) {
            "Plugin '${manifest.id}' declares duplicate source ids"
        }
        sourceIds.forEach { sourceId ->
            val liveOwner = sourceOwners[sourceId]
            require(liveOwner == null || liveOwner == manifest.id) {
                "Source id $sourceId is already owned by plugin '$liveOwner'"
            }
            val reservedOwner = sourceReservations[sourceId]
            require(reservedOwner == null || reservedOwner == manifest.id) {
                "Source id $sourceId is reserved by plugin '$reservedOwner'"
            }
        }
        sourceIds.forEach { sourceReservations[it] = manifest.id }
        return SourceReservation(manifest.id, sourceIds)
    }

    /** One inert pass ensures a corrupt stored collision cannot run whichever package lists first. */
    private fun preflightInstalledManifestSources(storedPlugins: List<StoredPlugin>) {
        val declaredOwners = mutableMapOf<Long, String>()
        storedPlugins.forEach { stored ->
            val manifest = stored.manifest
            val sourceIds = declaredSourceIds(manifest)
            require(sourceIds.size == manifest.sources.orEmpty().size.coerceAtLeast(1)) {
                "Plugin '${manifest.id}' declares duplicate source ids"
            }
            sourceIds.forEach { sourceId ->
                val owner = declaredOwners[sourceId]
                require(owner == null || owner == manifest.id) {
                    "Stored source id $sourceId collides between plugins '$owner' and '${manifest.id}'"
                }
                declaredOwners[sourceId] = manifest.id
            }
        }
    }

    private fun commitReservation(reservation: SourceReservation) {
        reservation.sourceIds.forEach { sourceId ->
            check(sourceReservations[sourceId] == reservation.packageId)
            sourceReservations.remove(sourceId)
        }
    }

    private fun rollbackReservation(reservation: SourceReservation) {
        reservation.sourceIds.forEach { sourceId ->
            if (sourceReservations[sourceId] == reservation.packageId) sourceReservations.remove(sourceId)
        }
    }

    private fun declaredSourceIds(manifest: PluginManifest): Set<Long> =
        manifest.sources.orEmpty().mapTo(linkedSetOf(), SourceIndexEntry::id)
            .ifEmpty { setOf(stableSourceId(manifest.id)) }

    private fun sourceKeyFor(packageId: String, legacyId: Long, canonicalSourceId: String? = null): SourceKey =
        SourceKey(packageId = packageId, sourceId = canonicalSourceId ?: legacyId.toString(), legacyLongId = legacyId)

    /** Every package/native runtime lifecycle boundary advances the process-local grant epoch. */
    private fun invalidateWebChallengeGrants() {
        nativeRuntimeGeneration += 1
        webChallengeGrants.clear()
    }

    private fun exactReviewedShuYueProfile(
        identity: PluginArtifactIdentity,
        sourceKey: SourceKey,
    ): dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedPluginProfileV2? =
        dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedPluginCatalogV2.profiles.singleOrNull { profile ->
            profile.identity.packageId == identity.packageId &&
                profile.identity.version == identity.version &&
                profile.identity.versionCode == identity.versionCode &&
                profile.identity.sha256 == identity.sha256 &&
                sourceKey.packageId == identity.packageId &&
                sourceKey.sourceId in profile.sourceIds
        }

    private fun canonicalWebChallengeOrigin(value: String?): String? = value?.let { raw ->
        runCatching {
            val parsed = io.ktor.http.Url(raw.trim())
            require(parsed.protocol.name.equals("https", ignoreCase = true) && parsed.host.isNotBlank())
            require(parsed.user.isNullOrEmpty() && parsed.password.isNullOrEmpty())
            require(!isBlockedPluginAddress(parsed.host))
            pluginOrigin(parsed)
        }.getOrNull()
    }

    private fun canonicalWebChallengeOrigins(values: Iterable<String>): Set<String> =
        values.mapTo(linkedSetOf()) { value ->
            requireNotNull(canonicalWebChallengeOrigin(value)) { "Invalid Web challenge origin" }
        }

    private fun normalizeWebChallengeStorageKeys(values: Iterable<String>): Set<String> =
        values.asSequence()
            .map(String::trim)
            .onEach { key ->
                require(key.length in 1..64 && key.all { it.isLetterOrDigit() || it in "._-" }) {
                    "Invalid browser storage key declaration"
                }
            }
            .distinct()
            .take(9)
            .toSet()
            .also { require(it.size <= 8) { "Too many browser storage keys were declared" } }

    private fun validateImportedWebChallengeStorage(
        values: Map<String, String>,
        allowedKeys: Set<String>,
    ): Map<String, String> {
        require(values.keys.all(allowedKeys::contains)) {
            "Browser session contained an undeclared storage key"
        }
        var totalBytes = 0
        return values.entries.associate { (key, value) ->
            require(value.none(Char::isISOControl)) { "Invalid browser storage value" }
            val bytes = value.encodeToByteArray().size
            require(bytes <= 16 * 1_024 && totalBytes + bytes <= 32 * 1_024) {
                "Browser storage value is too large"
            }
            totalBytes += bytes
            key to value
        }
    }

    private fun validateImportedWebChallengeCookie(
        cookie: SourceCookie,
        canonicalOrigin: String,
    ): PluginCookie {
        val origin = io.ktor.http.Url(canonicalOrigin)
        val domain = canonicalCookieDomain(cookie.domain)
        // Imported browser state is narrowed to the exact reviewed host. Domain cookies for a
        // parent/public suffix or sibling host must never become a cross-origin cookie injector.
        require(domain == origin.host.lowercase().trimEnd('.')) {
            "Browser cookie is outside the reviewed challenge origin"
        }
        require(cookie.path.startsWith('/') && cookie.path.none(Char::isISOControl)) {
            "Invalid browser cookie path"
        }
        return requireNotNull(
            normalizedPluginCookieOrNull(
                PluginCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = domain,
                    path = cookie.path,
                    expiresAtEpochMillis = cookie.expiresAtEpochMillis,
                    secure = true,
                    httpOnly = cookie.httpOnly,
                    hostOnly = true,
                ),
            ),
        ) { "Invalid browser cookie" }
    }

    private companion object {
        const val GENERIC_V2_CONTRACT: String = "shinsou"
        const val GENERIC_V2_RUNTIME: String = "legacy-shinsou-adapter-v2"
        const val MAX_WEB_CHALLENGE_GRANTS: Int = 8
        const val MAX_WEB_CHALLENGE_IMPORT_COOKIES: Int = 128
        const val MAX_WEB_CHALLENGE_IMPORT_COOKIE_BYTES: Int = 256 * 1_024
    }

    /** Missing permissions are retained for decoding only and never grant executable services. */
    private fun effectiveRuntimePermissions(stored: StoredPlugin): Set<PluginRuntimePermission> =
        stored.manifest.runtimePermissions.orEmpty()

    private fun StoredPlugin.hasExecutableRuntimeDeclaration(): Boolean =
        manifest.runtimePermissions?.contains(PluginRuntimePermission.EXECUTE_SCRIPT) == true &&
            runCatching { requireSupportedGenericRuntime(manifest) }.isSuccess

    private fun requireSupportedGenericRuntime(entry: PluginIndexEntry) {
        val isV2 = entry.sidecarUrl != null || entry.contract != null || entry.runtime != null
        if (!isV2) return
        require(entry.contract == GENERIC_V2_CONTRACT && entry.runtime == GENERIC_V2_RUNTIME) {
            "Unsupported generic V2 contract/runtime '${entry.contract}'/'${entry.runtime}'"
        }
    }

    private fun requireSupportedGenericRuntime(manifest: PluginManifest) {
        val isV2 = manifest.sidecarUrl != null || manifest.contract != null || manifest.runtime != null
        if (!isV2) return
        require(manifest.contract == GENERIC_V2_CONTRACT && manifest.runtime == GENERIC_V2_RUNTIME) {
            "Unsupported generic V2 contract/runtime '${manifest.contract}'/'${manifest.runtime}'"
        }
    }

    private fun PluginIndexEntry.toDescriptor(
        repository: ExtensionRepository,
        state: ExtensionState,
        installedVersion: String?,
        installedContentType: PluginContentType? = null,
    ): ExtensionDescriptor = ExtensionDescriptor(
        id = id,
        name = name,
        version = version,
        versionCode = versionCode,
        lang = lang,
        nsfw = nsfw == 1,
        sources = sources.orEmpty(),
        repositoryBaseUrl = repository.baseUrl,
        scriptUrl = scriptUrl,
        iconUrl = iconUrl?.let { repositoryClient.resolveAssetUrl(repository.baseUrl, it) }
            ?: sources?.firstOrNull()?.baseUrl?.trimEnd('/')?.plus("/favicon.ico"),
        description = description,
        state = state,
        installedVersion = installedVersion,
        contentType = resolveEntryContentType(type, contentType, sources.orEmpty())
            .withInstalledFallback(installedContentType),
        admissionFingerprint = admissionFingerprint(),
    )

    private fun LegacyExtensionIndexEntry.toDescriptor(
        repository: ExtensionRepository,
        state: ExtensionState,
        installedVersion: String?,
        installedContentType: PluginContentType? = null,
    ): ExtensionDescriptor = ExtensionDescriptor(
        id = pkg,
        name = name,
        version = version,
        versionCode = code,
        lang = lang,
        nsfw = nsfw == 1,
        sources = sources.orEmpty(),
        repositoryBaseUrl = repository.baseUrl,
        scriptUrl = null,
        iconUrl = repository.baseUrl.trimEnd('/') + "/icon/$pkg.png",
        description = null,
        state = state,
        installedVersion = installedVersion,
        contentType = resolveEntryContentType(type, contentType, sources.orEmpty())
            .withInstalledFallback(installedContentType),
        admissionFingerprint = admissionFingerprint(),
    )
}

/**
 * Compatibility-only return value for the historical `install(): ScriptPluginRuntime` surface.
 * It deliberately cannot execute catalogue calls because doing so would reintroduce an implicit
 * "first source" decision. Callers select one of [PluginManager.catalogueSources] instead.
 */
private class MultiSourceScriptPluginRuntimeHandle(
    private val manifest: PluginManifest,
    private val delegates: List<ScriptPluginRuntime>,
) : ScriptPluginRuntime {
    override val pluginId: String = manifest.id
    override val id: Long = stableSourceId("${manifest.id}:multi-source-handle")
    override val name: String = manifest.name
    override val lang: String = manifest.lang
    override val baseUrl: String = ""
    override val supportsLatest: Boolean = false
    override val supportsLogin: Boolean = false
    override val recentLogs: List<String>
        get() = delegates.flatMap(ScriptPluginRuntime::recentLogs)

    override suspend fun getPopularManga(page: Int): MangasPage = ambiguous()
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = ambiguous()
    override suspend fun getLatestUpdates(page: Int): MangasPage = ambiguous()
    override suspend fun getFilterList(): FilterList = ambiguous()
    override suspend fun getMangaDetails(manga: SManga): SManga = ambiguous()
    override suspend fun getChapterList(manga: SManga): List<SChapter> = ambiguous()
    override suspend fun getPageList(chapter: SChapter): List<Page> = ambiguous()
    override suspend fun login(username: String, password: String): Boolean = ambiguous()
    override suspend fun logout(): Unit = ambiguous()

    // The package manager owns the real source runtimes; closing a compatibility view must not
    // tear down live sources behind other consumers.
    override suspend fun close(): Unit = Unit

    private fun ambiguous(): Nothing = throw ScriptRuntimeUnavailableException(
        "Plugin '${manifest.id}' contains multiple sources; select an exact source id",
    )
}

/** Non-executable receipt returned when bytes were installed but exact-artifact review is pending. */
private class InertScriptPluginRuntimeHandle(
    private val manifest: PluginManifest,
) : ScriptPluginRuntime {
    override val pluginId: String = manifest.id
    override val id: Long = stableSourceId("${manifest.id}:pending-permission-review")
    override val name: String = manifest.name
    override val lang: String = manifest.lang
    override val baseUrl: String = ""
    override val supportsLatest: Boolean = false
    override val supportsLogin: Boolean = false
    override val recentLogs: List<String> = emptyList()

    override suspend fun getPopularManga(page: Int): MangasPage = denied()
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = denied()
    override suspend fun getLatestUpdates(page: Int): MangasPage = denied()
    override suspend fun getFilterList(): FilterList = denied()
    override suspend fun getMangaDetails(manga: SManga): SManga = denied()
    override suspend fun getChapterList(manga: SManga): List<SChapter> = denied()
    override suspend fun getPageList(chapter: SChapter): List<Page> = denied()
    override suspend fun login(username: String, password: String): Boolean = denied()
    override suspend fun logout(): Unit = denied()
    override suspend fun close(): Unit = Unit

    private fun denied(): Nothing = throw ScriptRuntimeUnavailableException(
        "Plugin '${manifest.id}' is installed but awaits exact-artifact permission approval",
    )
}

private class MetadataStubCatalogueSource(
    val pluginId: String,
    private val entry: SourceIndexEntry,
) : CatalogueSource {
    override val id: Long = entry.id
    override val name: String = entry.name
    override val lang: String = entry.lang
    override val baseUrl: String = entry.baseUrl.orEmpty()
    override val supportsLatest: Boolean = false
    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getFilterList(): FilterList = emptyList()
    override suspend fun getMangaDetails(manga: SManga): SManga = manga
    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
}
