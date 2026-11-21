@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

private fun Throwable.isUntrustedRepository(): Boolean =
    generateSequence(this) { it.cause }.any { it is RepositoryTrustException.UntrustedRepository }

/** Repository index refresh and the install keys (D6) a repository's packages get on this device. */
internal class PluginRepositoryRefresher(
    private val state: PluginManagerState,
    deps: PluginManagerDependencies,
) {
    private val repositoryClient = deps.repositoryClient
    private val packageStore = deps.packageStore
    private val executionTierGate = deps.executionTierGate
    private val developerRepository = deps.developerRepository

    internal suspend fun refreshWithAdmissions(
        repositories: List<ExtensionRepository>,
    ): PluginManager.RepositoryRefreshResult {
        // A repository refresh changes the metadata/admission snapshot from which host actions
        // are selected. Revoke before and after the fetch so neither an old grant nor one issued
        // while remote I/O was in flight survives the snapshot boundary.
        state.packageMutationMutex.withLock { state.invalidateWebChallengeGrants() }
        try {
        val installed = packageStore.list().associateBy { it.manifest.id }
        val result = linkedMapOf<String, ExtensionDescriptor>()

        installed.values.forEach { stored -> result[stored.manifest.id] = installedDescriptor(stored) }

        val snapshots = linkedMapOf<String, RepositoryIndexSnapshot>()
        val needingTrust = linkedSetOf<String>()
        repositories.forEach { repository ->
            val snapshot = try {
                repositoryClient.fetchIndexSnapshot(repository.baseUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Only a developer-mode server can be plain HTTP: one that is stopped, or refused
                // because the mode is off, must not fail every other repository's refresh.
                if (repository.baseUrl.trim().startsWith("http://", ignoreCase = true)) return@forEach
                // A repository that switched to signed publishing waits for the user to trust its
                // publisher; installed packages keep running, and the others still refresh.
                if (error.isUntrustedRepository()) {
                    needingTrust += repository.baseUrl
                    return@forEach
                }
                throw error
            }
            snapshots[repository.baseUrl] = snapshot
            val installKeyOf = installKeyResolver(repository)
            addIndexDescriptors(snapshot.index, repository, installKeyOf, installed, result)
        }
        // Only a repository that answered replaces what it was last known to publish: a list
        // that could not be fetched says nothing about what was withdrawn.
        state.repositoryPackages = state.repositoryPackages + snapshots.keys.associateWith { baseUrl ->
            result.values.filter { it.repositoryBaseUrl == baseUrl }
        }
        return PluginManager.RepositoryRefreshResult(result.values.sortedWith(
            compareBy<ExtensionDescriptor> { it.state == ExtensionState.AVAILABLE }
                .thenBy { it.name.lowercase() },
        ), snapshots.toMap(), needingTrust)
        } finally {
            state.packageMutationMutex.withLock { state.invalidateWebChallengeGrants() }
        }
    }

    private fun installedDescriptor(stored: StoredPlugin): ExtensionDescriptor {
        val manifest = stored.manifest
        return ExtensionDescriptor(
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

    /** Adds the descriptors a repository's index publishes, with their state against what is installed. */
    private fun addIndexDescriptors(
        index: RepositoryIndex,
        repository: ExtensionRepository,
        installKeyOf: (String) -> String?,
        installed: Map<String, StoredPlugin>,
        result: MutableMap<String, ExtensionDescriptor>,
    ) {
        when (index) {
            is RepositoryIndex.Plugins -> index.entries.forEach { entry ->
                val key = installKeyOf(entry.id) ?: return@forEach
                result[key] = pluginEntryDescriptor(entry, key, repository, installed[key])
            }

            is RepositoryIndex.Legacy -> index.entries.forEach { entry ->
                result[entry.pkg] = legacyEntryDescriptor(entry, repository, installed[entry.pkg])
            }
            is RepositoryIndex.Combined -> index.plugins.forEach { entry ->
                val key = installKeyOf(entry.id) ?: return@forEach
                result[key] = pluginEntryDescriptor(entry, key, repository, installed[key])
            }
        }
    }

    private fun legacyEntryDescriptor(
        entry: LegacyExtensionIndexEntry,
        repository: ExtensionRepository,
        local: StoredPlugin?,
    ): ExtensionDescriptor {
        val localCode = local?.manifest?.versionCode
            ?: local?.manifest?.version?.let(PluginVerifier::versionInt)
        val state = when {
            local == null -> ExtensionState.AVAILABLE
            localCode == null || entry.code > localCode ||
                (entry.code == localCode && entry.version != local.manifest.version) ->
                ExtensionState.UPDATE_AVAILABLE
            else -> ExtensionState.INSTALLED
        }
        return entry.toDescriptor(
            repository,
            state,
            local?.manifest?.version,
            local?.manifest?.installedContentType(),
        )
    }

    private fun pluginEntryDescriptor(
        entry: PluginIndexEntry,
        key: String,
        repository: ExtensionRepository,
        local: StoredPlugin?,
    ): ExtensionDescriptor {
        val state = when {
            local == null -> ExtensionState.AVAILABLE
            entry.versionCode > (local.manifest.versionCode
                ?: PluginVerifier.versionInt(local.manifest.version)) -> ExtensionState.UPDATE_AVAILABLE
            entry.versionCode == (local.manifest.versionCode
                ?: PluginVerifier.versionInt(local.manifest.version)) &&
                entry.version != local.manifest.version -> ExtensionState.UPDATE_AVAILABLE
            // A development build changes without a version bump.
            PluginInstallKeys.isDeveloperKey(key) && entry.sha256 != null &&
                !entry.sha256.equals(local.metadata.installedSha256, ignoreCase = true) -> ExtensionState.UPDATE_AVAILABLE
            else -> ExtensionState.INSTALLED
        }
        return entry.toDescriptor(
            repository,
            state,
            local?.manifest?.version,
            local?.manifest?.installedContentType(),
        ).copy(id = key)
    }

    /**
     * The install key a [repository]'s package ids get on this device (D6); null for an id that
     * cannot be installed, and for every id of a repository URL this client refuses. Refresh, the
     * UI lookup and install all derive keys here, so they always agree.
     *
     * The original community repository URL keeps bare keys even once it is signed (ADR-0002 S8):
     * its existing library rows are keyed by them. That is no wider than before: only one publisher
     * key can sign that URL (a different key is refused as a substitution), and every other
     * publisher's package is always scoped, so nothing else can take the bare ids.
     */
    internal suspend fun installKeyResolver(repository: ExtensionRepository): (String) -> String? {
        val canonical = runCatchingCancellable { repositoryClient.canonicalBaseUrl(repository.baseUrl) }.getOrNull() ?: return { null }
        val publisherId = executionTierGate?.publisherForRepository(canonical)
        // ADR-0002 §8.1: an unsigned developer-mode repository gets its own `~dev` identities.
        if (publisherId == null && developerRepository(canonical)) {
            return { packageId -> runCatching { PluginInstallKeys.developerInstallKey(packageId) }.getOrNull() }
        }
        val legacyCommunity = canonical == repositoryClient.canonicalBaseUrl(OFFICIAL_SHINSOU_REPOSITORY_BASE_URL)
        return { packageId -> runCatching { PluginInstallKeys.installKey(packageId, publisherId, legacyCommunity) }.getOrNull() }
    }

    /** The install key [entry] of [repository] has, or would have, on this device. */
    suspend fun installKeyFor(repository: ExtensionRepository, entry: PluginIndexEntry): String? =
        installKeyResolver(repository)(entry.id)

    /**
     * What the added repositories last said they publish, installed here or not. Empty until a
     * repository has been refreshed in this run; starts nothing.
     */
    fun knownRepositoryPackages(): Collection<ExtensionDescriptor> = state.repositoryPackages.values.flatten()

    /** Forgets what repositories other than [baseUrls] published: they are no longer added. */
    fun retainRepositoryPackages(baseUrls: Collection<String>) {
        val kept = baseUrls.toSet()
        state.repositoryPackages = state.repositoryPackages.filterKeys { it in kept }
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
