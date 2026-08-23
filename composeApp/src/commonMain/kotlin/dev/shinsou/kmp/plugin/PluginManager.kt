@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin

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
import dev.shinsou.kmp.plugin.v2.LegacyMangaPackageRuntimeV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        loadPlugins()[pluginId]?.let(::copyPlugin)
    }

    override suspend fun put(plugin: StoredPlugin): Unit = mutex.withLock {
        PluginVerifier.validateSafeFileComponent(plugin.manifest.id)
        val id = plugin.manifest.id
        val plugins = loadPlugins()
        keyValueStore.putString(metadataKey(id), json.encodeToString(InstalledPluginMetadata.serializer(), plugin.metadata))
        keyValueStore.putString(scriptKey(id), plugin.scriptBytes.toHex())
        writeIds(plugins.keys + id)
        plugins[id] = copyPlugin(plugin)
    }

    override suspend fun remove(pluginId: String): Unit = mutex.withLock {
        val plugins = loadPlugins()
        keyValueStore.remove(metadataKey(pluginId))
        keyValueStore.remove(scriptKey(pluginId))
        writeIds(plugins.keys - pluginId)
        plugins.remove(pluginId)
    }

    /**
     * Installed scripts are immutable between package operations. Keep their decoded bytes in
     * process memory so every extension refresh does not repeatedly decode all hex scripts.
     */
    private suspend fun loadPlugins(): MutableMap<String, StoredPlugin> {
        cachedPlugins?.let { return it }
        return readIds()
            .mapNotNull { id -> readPlugin(id)?.let { id to it } }
            .toMap(linkedMapOf())
            .also { cachedPlugins = it }
    }

    private suspend fun readPlugin(pluginId: String): StoredPlugin? {
        val metadata = keyValueStore.getString(metadataKey(pluginId)) ?: return null
        val script = keyValueStore.getString(scriptKey(pluginId)) ?: return null
        return runCatching {
            StoredPlugin(
                json.decodeFromString(InstalledPluginMetadata.serializer(), metadata),
                script.hexToBytes(),
            )
        }.getOrNull()
    }

    private suspend fun readIds(): Set<String> {
        val encoded = keyValueStore.getString(indexKey) ?: return emptySet()
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), encoded).toSet() }
            .getOrDefault(emptySet())
    }

    private suspend fun writeIds(ids: Set<String>) {
        if (ids.isEmpty()) keyValueStore.remove(indexKey)
        else keyValueStore.putString(indexKey, json.encodeToString(ListSerializer(String.serializer()), ids.sorted()))
    }

    private fun metadataKey(pluginId: String): String = "plugin.package.$pluginId.metadata"
    private fun scriptKey(pluginId: String): String = "plugin.package.$pluginId.script.hex"

    private fun copyPlugin(plugin: StoredPlugin): StoredPlugin =
        plugin.copy(scriptBytes = plugin.scriptBytes.copyOf())

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

public class PluginManager(
    private val repositoryClient: ExtensionRepositoryClient,
    private val packageStore: PluginPackageStore,
    private val verifier: PluginVerifier,
    private val runtimeFactory: ScriptPluginRuntimeFactory,
    private val environment: ScriptPluginEnvironment,
    private val eventGrantAdmission: KeyValuePluginEventGrantAdmission? = null,
) {
    /** Serializes package mutations without blocking readers of the live source/runtime maps. */
    private val packageMutationMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val interactionMutex = Mutex()
    private val interactionCounts = mutableMapOf<String, Int>()
    private var pluginUiAvailable: Boolean = false
    private val runtimes = mutableMapOf<String, List<ScriptPluginRuntime>>()
    private val sources = mutableMapOf<Long, CatalogueSource>()
    private val sourceOwners = mutableMapOf<Long, String>()
    private val extensionRegistryV2 = ExtensionRuntimeRegistryV2()
    private val nativeArtifactIdentities = mutableMapOf<String, PluginArtifactIdentity>()
    private val nativeLifecycleRuntimes = mutableMapOf<String, SourceLifecycleControlledExtensionPackageRuntimeV2>()
    private val reviewedReloaders = mutableMapOf<String, suspend () -> ExtensionPackageRuntimeV2>()
    private val eventScopeFactory = BoundPluginScopeFactory()
    private val eventScopes = mutableMapOf<ScriptPluginRuntime, BoundPluginScope>()
    private val legacyLoginCompatibilityScopes = mutableSetOf<String>()
    private var runtimeGeneration: Long = 0

    public suspend fun refresh(repositories: List<ExtensionRepository>): List<ExtensionDescriptor> {
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
            )
        }

        repositories.forEach { repository ->
            when (val index = repositoryClient.fetchIndex(repository.baseUrl)) {
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
                    result[entry.id] = entry.toDescriptor(repository, state, local?.manifest?.version)
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
                    result[entry.pkg] = entry.toDescriptor(repository, state, local?.manifest?.version)
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
                    result[entry.id] = entry.toDescriptor(repository, state, local?.manifest?.version)
                }
            }
        }
        return result.values.sortedWith(
            compareBy<ExtensionDescriptor> { it.state == ExtensionState.AVAILABLE }
                .thenBy { it.name.lowercase() },
        )
    }

    public suspend fun install(repository: ExtensionRepository, entry: PluginIndexEntry): ScriptPluginRuntime =
        withContext(Dispatchers.Default) {
            packageMutationMutex.withLock {
            PluginVerifier.validateSafeFileComponent(entry.id)
            require(entry.installable && !entry.referenceOnly && !entry.legacyCompatibilityOnly) {
                "Plugin '${entry.id}' is migration/reference metadata and cannot be installed"
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
                systemEvents = entry.systemEvents,
                requestedHostPermissions = entry.requestedHostPermissions,
            )
            val verified = verifier.verify(scriptBytes, manifest)
            val stored = StoredPlugin(
                metadata = InstalledPluginMetadata(
                    manifest = manifest,
                    repositoryBaseUrl = repository.baseUrl,
                    installedSha256 = verified.sha256,
                    legacyTrustOnInstall = expectedHash == null,
                ),
                scriptBytes = scriptBytes,
            )
            val grantReview = stored.eventGrantReview()
            val previousStored = packageStore.get(entry.id)
            val previousReview = previousStored?.eventGrantReview(includeEmpty = true)
            val nextReview = stored.eventGrantReview(includeEmpty = true)
            if (previousReview != null && previousReview != nextReview) {
                eventGrantAdmission?.revoke(previousReview.artifact)
            }
            if (grantReview != null) eventGrantAdmission?.hydrate(grantReview)
            val alreadyGranted = grantReview?.let { eventGrantAdmission?.isGranted(it) } == true
            val candidates = createRuntimeCandidates(stored)
            val compatibilityResult = runtimeCompatibilityResult(manifest, candidates)
            var committed = false
            try {
                // Downloading, verification and runtime construction remain cancellable. Once the
                // durable/live commit starts, finish both halves so cancellation cannot leave a new
                // package paired with the previous runtime (or no runtime on first installation).
                currentCoroutineContext().ensureActive()
                lifecycleMutex.withLock { validateRuntimeCandidates(entry.id, candidates) }
                withContext(NonCancellable) {
                    packageStore.put(stored)
                    lifecycleMutex.withLock {
                        replaceRuntimes(entry.id, candidates)
                    }
                    committed = true
                }
                if (grantReview != null && !alreadyGranted) eventGrantAdmission?.stage(grantReview)
            } catch (error: Throwable) {
                if (!committed) closeRuntimes(candidates)
                throw error
            }
            compatibilityResult
        }
        }

    public suspend fun update(repository: ExtensionRepository, entry: PluginIndexEntry): ScriptPluginRuntime =
        install(repository, entry)

    /** Installs Mihon metadata as non-executable stub sources, matching the Swift fallback. */
    public suspend fun installLegacy(
        repository: ExtensionRepository,
        entry: LegacyExtensionIndexEntry,
    ): List<CatalogueSource> = withContext(Dispatchers.Default) {
        packageMutationMutex.withLock {
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
            InstalledPluginMetadata(manifest, repository.baseUrl, "", legacyTrustOnInstall = true),
            ByteArray(0),
        )
        val previous = packageStore.get(entry.pkg)
        currentCoroutineContext().ensureActive()
        try {
            withContext(NonCancellable) {
                packageStore.put(stored)
                verifier.trustMetadataOnly(manifest, stored.metadata.installedSha256)
                lifecycleMutex.withLock {
                    unloadPlugin(entry.pkg)
                    addMetadataSources(stored)
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
            throw error
        }
        }
    }

    public suspend fun uninstall(pluginId: String): Unit = withContext(Dispatchers.Default) {
        packageMutationMutex.withLock {
            val artifact = packageStore.get(pluginId)?.artifactIdentity()
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                if (artifact != null) eventGrantAdmission?.revoke(artifact)
                packageStore.remove(pluginId)
                lifecycleMutex.withLock { unloadPlugin(pluginId) }
                nativeArtifactIdentities.remove(pluginId)
                extensionRegistryV2.uninstall(pluginId)
            }
        }
    }

    /**
     * Changes the package's execution grant and applies it to the live runtime immediately.
     * Revocation keeps the package metadata and bytes installed for UI management/uninstall.
     */
    public suspend fun setPluginTrusted(pluginId: String, trusted: Boolean): Unit =
        withContext(Dispatchers.Default) {
        packageMutationMutex.withLock packageMutation@{
            val stored = packageStore.get(pluginId)
                ?: throw IllegalArgumentException("Extension '$pluginId' is not installed")
            if (!trusted) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    if (stored.metadata.legacyTrustOnInstall) {
                        // A legacy package can be restored from its verified digest without
                        // consulting the platform secure store.  Persist the explicit revocation
                        // so that fallback cannot silently re-enable it on the next launch.
                        packageStore.put(
                            stored.copy(
                                metadata = stored.metadata.copy(legacyTrustOnInstall = false),
                            ),
                        )
                    }
                    // Revoke every token for this id so stale version/hash grants cannot revive it.
                    verifier.revokeAll(pluginId)
                    eventGrantAdmission?.revoke(stored.artifactIdentity())
                    // Keep the currently trusted runtime live until every durable revocation step
                    // succeeds. A storage failure can then be retried without a disk/live split.
                    lifecycleMutex.withLock { unloadPlugin(pluginId) }
                }
                return@packageMutation
            }

            if (stored.isMetadataOnly()) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    verifier.trustMetadataOnly(stored.manifest, stored.metadata.installedSha256)
                    lifecycleMutex.withLock {
                        unloadPlugin(pluginId)
                        addMetadataSources(stored)
                    }
                }
                return@packageMutation
            }

            val wasTrusted = verifier.isTrusted(stored.manifest, stored.metadata.installedSha256)
            stored.eventGrantReview()?.let { eventGrantAdmission?.stage(it) }
            verifier.trustInstalled(
                stored.scriptBytes,
                stored.manifest,
                stored.metadata.installedSha256,
            )
            val candidates = try {
                createRuntimeCandidates(stored)
            } catch (error: Throwable) {
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
                    }
                    committed = true
                }
            } catch (error: Throwable) {
                if (!committed) closeRuntimes(candidates)
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
            lifecycleMutex.withLock {
                closeAllRuntimes()
                sources.clear()
                sourceOwners.clear()
                packageStore.list().forEach { stored ->
                    try {
                        loadStored(stored)
                    } catch (error: CancellationException) {
                        throw error
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

    /** Explicitly approves the complete verified set, then rebuilds the runtime negotiation. */
    public suspend fun approveEventGrantReview(
        pluginId: String,
        permissions: Set<PluginHostPermission>,
    ): Unit = packageMutationMutex.withLock {
        val stored = requireNotNull(packageStore.get(pluginId)) { "Extension '$pluginId' is not installed" }
        val admission = requireNotNull(eventGrantAdmission) { "Plugin event grant admission is unavailable" }
        admission.approve(stored.artifactIdentity(), permissions)
        lifecycleMutex.withLock { loadStored(stored) }
    }

    public suspend fun revokeEventGrants(pluginId: String): Unit = packageMutationMutex.withLock {
        val stored = packageStore.get(pluginId) ?: return@withLock
        eventGrantAdmission?.revoke(stored.artifactIdentity())
        lifecycleMutex.withLock {
            unloadPlugin(pluginId)
            loadStored(stored)
        }
    }
    public suspend fun catalogueSources(): List<CatalogueSource> = lifecycleMutex.withLock {
        sources.values.toList()
    }

    public suspend fun source(sourceId: Long): CatalogueSource? = lifecycleMutex.withLock { sources[sourceId] }

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
        packageMutationMutex.withLock packageLock@{
            val stored = packageStore.get(pluginId) ?: return@packageLock null
            lifecycleMutex.withLock lifecycleLock@{
                val live = runtimes[pluginId].orEmpty()
                if (live.isEmpty()) return@lifecycleLock null
                LegacyMangaPackageRuntimeV2(
                    packageId = pluginId,
                    version = stored.manifest.version,
                    displayName = stored.manifest.name,
                    sources = live,
                )
            }
        }

    /** Installs an already-admitted native v2 runtime; replacement and lifecycle are explicit. */
    @OptIn(ExtensionImplementationApi::class)
    public suspend fun installExtensionRuntimeV2(
        runtime: ExtensionPackageRuntimeV2,
        replace: Boolean = false,
    ): ExtensionHostFacadeV2 = packageMutationMutex.withLock {
        val facade = extensionRegistryV2.install(runtime, replace)
        (runtime as? ArtifactBoundExtensionPackageRuntimeV2)?.let {
            nativeArtifactIdentities[runtime.descriptor.packageId] = it.artifactIdentity
        }
        (runtime as? SourceLifecycleControlledExtensionPackageRuntimeV2)?.let {
            nativeLifecycleRuntimes[runtime.descriptor.packageId] = it
        }
        reviewedReloaders.remove(runtime.descriptor.packageId)
        facade
    }

    /** Admits reviewed ShuYue bytes and atomically publishes their guarded runtime to v2 browse. */
    @OptIn(ExtensionImplementationApi::class)
    public suspend fun installReviewedShuYueRuntimeV2(
        admission: ShuYueReviewedPluginAdmissionV2,
        quarantineId: String,
        replace: Boolean = false,
    ): ExtensionHostFacadeV2 = packageMutationMutex.withLock {
        val runtime = admission.createRuntime(quarantineId)
        try {
            extensionRegistryV2.install(runtime, replace).also {
                (runtime as? ArtifactBoundExtensionPackageRuntimeV2)?.let { bound ->
                    nativeArtifactIdentities[runtime.descriptor.packageId] = bound.artifactIdentity
                }
                (runtime as? SourceLifecycleControlledExtensionPackageRuntimeV2)?.let { controlled ->
                    nativeLifecycleRuntimes[runtime.descriptor.packageId] = controlled
                }
                reviewedReloaders[runtime.descriptor.packageId] = {
                    admission.createRuntime(quarantineId)
                }
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
            nativeArtifactIdentities.remove(packageId)
            nativeLifecycleRuntimes.remove(packageId)
            reviewedReloaders.remove(packageId)
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
                LegacyMangaPackageRuntimeV2(
                    packageId = packageId,
                    version = stored.manifest.version,
                    displayName = stored.manifest.name,
                    sources = live,
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
        interactionMutex.withLock {
            check(pluginUiAvailable) { "Plugin UI interaction is unavailable" }
            scopes.forEach { scope ->
                val next = (interactionCounts[scope.runtimeKey] ?: 0) + 1
                interactionCounts[scope.runtimeKey] = next
                if (next == 1) gateway?.setUserInteractionContext(scope, true)
            }
        }
        return try {
            extensionSourceV2(sourceKey)?.withUserInteractionContext(block) ?: block()
        } finally {
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

    /** Legacy UI row counterpart; resolves only the currently bound exact runtime scope. */
    public suspend fun <T> withUserInteractionContext(sourceId: Long, block: suspend () -> T): T {
        val scope = lifecycleMutex.withLock {
            eventScopes.values.singleOrNull { it.sourceKey.legacyLongId == sourceId }
        } ?: return block()
        val gateway = environment.systemEventSink as? PluginSystemEventGateway
        interactionMutex.withLock {
            check(pluginUiAvailable) { "Plugin UI interaction is unavailable" }
            val next = (interactionCounts[scope.runtimeKey] ?: 0) + 1
            interactionCounts[scope.runtimeKey] = next
            if (next == 1) gateway?.setUserInteractionContext(scope, true)
        }
        return try {
            block()
        } finally {
            interactionMutex.withLock {
                val next = (interactionCounts[scope.runtimeKey] ?: 1) - 1
                if (next <= 0) {
                    interactionCounts.remove(scope.runtimeKey)
                    gateway?.setUserInteractionContext(scope, false)
                } else interactionCounts[scope.runtimeKey] = next
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
            if (!enabled) {
                val scopes = lifecycleMutex.withLock {
                    eventScopes.values.filter { it.sourceKey == sourceKey }
                }
                val gateway = environment.systemEventSink as? PluginSystemEventGateway
                scopes.forEach { gateway?.closeRuntime(it) }
                nativeLifecycleRuntimes[sourceKey.packageId]?.setSourceEnabled(sourceKey, false)
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
                    (runtime as? SourceLifecycleControlledExtensionPackageRuntimeV2)?.let {
                        nativeLifecycleRuntimes[sourceKey.packageId] = it
                    }
                } catch (error: Throwable) {
                    if (runtime is CloseableExtensionPackageRuntimeV2) runCatching { runtime.close() }
                    throw error
                }
                return@withLock
            }
            val stored = packageStore.get(sourceKey.packageId) ?: return@withLock
            lifecycleMutex.withLock { loadStored(stored) }
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
            lifecycleMutex.withLock {
                closeAllRuntimes()
                sources.clear()
                sourceOwners.clear()
            }
            nativeLifecycleRuntimes.clear()
            reviewedReloaders.clear()
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
        }
    }

    private suspend fun loadStored(stored: StoredPlugin) {
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
            addMetadataSources(stored)
            return
        }

        if (!PluginVerifier.isLegacyTrustValid(stored)) {
            verifier.verify(
                stored.scriptBytes,
                stored.manifest,
                trustOnValidatedDigest = false,
            )
        }
        stored.eventGrantReview()?.let { eventGrantAdmission?.hydrate(it) }
        val candidates = createRuntimeCandidates(stored)
        try {
            replaceRuntimes(stored.manifest.id, candidates)
        } catch (error: Throwable) {
            closeRuntimes(candidates)
            throw error
        }
    }

    /** Builds every declared source independently; a package list position is never executed. */
    private suspend fun createRuntimeCandidates(stored: StoredPlugin): List<ScriptPluginRuntime> {
        val manifest = stored.manifest
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
        val generation = ++runtimeGeneration
        val legacyId = source?.id ?: stableSourceId(manifest.id)
        val scope = eventScopeFactory.bind(
            artifactIdentity = PluginArtifactIdentity(
                packageId = manifest.id,
                version = manifest.version,
                versionCode = manifest.versionCode ?: PluginVerifier.versionInt(manifest.version),
                sha256 = stored.metadata.installedSha256,
            ),
            sourceKey = SourceKey(
                packageId = manifest.id,
                sourceId = legacyId.toString(),
                legacyLongId = legacyId,
            ),
            runtimeInstanceId = "${manifest.id}-$generation",
            runtimeGeneration = generation,
        )
        val negotiated = manifest.systemEvents?.let { declaration ->
            (environment.systemEventSink as? PluginSystemEventGateway)?.negotiate(scope, declaration)
                ?: PluginSystemCapabilityNegotiator(supportedCapabilities = emptySet()).negotiate(declaration)
        }
        val legacyLoginCompatibility = manifest.systemEvents == null
        val visibleNegotiation = negotiated ?: PluginSystemEventNegotiation(enabled = false)
        val scopedEnvironment = environment.copy(
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

    private fun StoredPlugin.eventGrantReview(includeEmpty: Boolean = false): PluginEventGrantReview? {
        if (!includeEmpty && manifest.requestedHostPermissions.isEmpty()) return null
        val artifact = artifactIdentity()
        val declaredSources = manifest.sources.orEmpty()
        val sourceKeys = if (declaredSources.isEmpty()) {
            val id = stableSourceId(manifest.id)
            listOf(SourceKey(packageId = manifest.id, sourceId = id.toString(), legacyLongId = id))
        } else {
            declaredSources.map { source ->
                SourceKey(packageId = manifest.id, sourceId = source.id.toString(), legacyLongId = source.id)
            }
        }
        return PluginEventGrantReview(artifact, sourceKeys, manifest.requestedHostPermissions)
    }

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

    private fun PluginIndexEntry.toDescriptor(
        repository: ExtensionRepository,
        state: ExtensionState,
        installedVersion: String?,
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
        contentType = resolveEntryContentType(type, contentType, sources.orEmpty()),
    )

    private fun LegacyExtensionIndexEntry.toDescriptor(
        repository: ExtensionRepository,
        state: ExtensionState,
        installedVersion: String?,
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
        contentType = resolveEntryContentType(type, contentType, sources.orEmpty()),
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
