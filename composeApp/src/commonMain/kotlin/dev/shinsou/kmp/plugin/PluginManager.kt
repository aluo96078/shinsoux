package dev.shinsou.kmp.plugin

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
) {
    /** Serializes package mutations without blocking readers of the live source/runtime maps. */
    private val packageMutationMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val runtimes = mutableMapOf<String, ScriptPluginRuntime>()
    private val sources = mutableMapOf<Long, CatalogueSource>()

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
            val scriptBytes = repositoryClient.downloadPluginScript(repository.baseUrl, entry.scriptUrl)
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
            val candidate = runtimeFactory.create(stored.script, manifest, environment)
            val candidateSourceId = try {
                candidate.id
            } catch (error: Throwable) {
                closeRuntime(candidate)
                throw error
            }
            var committed = false
            try {
                // Downloading, verification and runtime construction remain cancellable. Once the
                // durable/live commit starts, finish both halves so cancellation cannot leave a new
                // package paired with the previous runtime (or no runtime on first installation).
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    packageStore.put(stored)
                    lifecycleMutex.withLock {
                        replaceRuntime(entry.id, candidate, candidateSourceId)
                    }
                    committed = true
                }
            } catch (error: Throwable) {
                if (!committed) closeRuntime(candidate)
                throw error
            }
            candidate
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
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                packageStore.remove(pluginId)
                lifecycleMutex.withLock { unloadPlugin(pluginId) }
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
            verifier.trustInstalled(
                stored.scriptBytes,
                stored.manifest,
                stored.metadata.installedSha256,
            )
            val candidate = try {
                runtimeFactory.create(stored.script, stored.manifest, environment)
            } catch (error: Throwable) {
                if (!wasTrusted) {
                    withContext(NonCancellable) { verifier.revokeAll(pluginId) }
                }
                throw error
            }
            val candidateSourceId = try {
                candidate.id
            } catch (error: Throwable) {
                closeRuntime(candidate)
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
                        replaceRuntime(pluginId, candidate, candidateSourceId)
                    }
                    committed = true
                }
            } catch (error: Throwable) {
                if (!committed) closeRuntime(candidate)
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
    public suspend fun catalogueSources(): List<CatalogueSource> = lifecycleMutex.withLock {
        sources.values.toList()
    }

    public suspend fun source(sourceId: Long): CatalogueSource? = lifecycleMutex.withLock { sources[sourceId] }

    public suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.Default) {
        packageMutationMutex.withLock {
            lifecycleMutex.withLock {
                closeAllRuntimes()
                sources.clear()
            }
        }
    }

    private suspend fun replaceRuntime(
        pluginId: String,
        candidate: ScriptPluginRuntime,
    ) {
        val candidateSourceId = try {
            candidate.id
        } catch (error: Throwable) {
            closeRuntime(candidate)
            throw error
        }
        replaceRuntime(pluginId, candidate, candidateSourceId)
    }

    private suspend fun replaceRuntime(
        pluginId: String,
        candidate: ScriptPluginRuntime,
        candidateSourceId: Long,
    ) {
        unloadPlugin(pluginId)
        // The current JS contract creates one runtime/source and uses the first manifest source.
        runtimes[pluginId] = candidate
        sources[candidateSourceId] = candidate
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
        val runtime = runtimeFactory.create(stored.script, stored.manifest, environment)
        replaceRuntime(stored.manifest.id, runtime)
    }

    private fun StoredPlugin.isMetadataOnly(): Boolean =
        scriptBytes.isEmpty() &&
            metadata.installedSha256.isBlank() &&
            manifest.signature.isBlank()

    private fun addMetadataSources(stored: StoredPlugin): List<CatalogueSource> =
        stored.manifest.sources.orEmpty().map {
            MetadataStubCatalogueSource(stored.manifest.id, it)
        }.also { stubs ->
            stubs.forEach { sources[it.id] = it }
        }

    private suspend fun unloadPlugin(pluginId: String) {
        removeSourcesFor(pluginId)
        runtimes.remove(pluginId)?.let { runtime -> closeRuntime(runtime) }
    }

    private suspend fun closeAllRuntimes() {
        runtimes.values.toList().forEach { runtime -> closeRuntime(runtime) }
        runtimes.clear()
    }

    /** Runtime teardown must finish even when the operation that made it unreachable is cancelled. */
    private suspend fun closeRuntime(runtime: ScriptPluginRuntime) {
        withContext(NonCancellable) { runCatching { runtime.close() } }
    }

    private fun removeSourcesFor(pluginId: String) {
        val runtime = runtimes[pluginId]
        // Remove by object identity so a malfunctioning runtime getter cannot block revocation.
        // Also remove persisted IDs for legacy multi-source entries.
        val known = sources.filterValues { source ->
            source === runtime || source is MetadataStubCatalogueSource && source.pluginId == pluginId
        }.keys
        known.forEach(sources::remove)
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
