package dev.shinsou.kmp.plugin.browse

import kotlinx.coroutines.sync.Mutex
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.ExtensionRepo
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import dev.shinsou.kmp.plugin.contracts.BrowseRepositorySuggestion
import dev.shinsou.kmp.plugin.model.ExtensionRepository

private const val PORTABLE_REPOSITORY_MIGRATION_KEY: String =
    "plugin.repositories.portable-migration.v1"

private const val DISMISSED_SUGGESTIONS_KEY: String =
    "plugin.repositories.dismissed-suggestions.v1"
private const val LEGACY_BUNDLED_REPOSITORY_URL: String =
    "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/master"
private const val LEGACY_BUNDLED_REPOSITORY_REMOVAL_KEY: String =
    "plugin.repositories.remove-bundled-seed.v1"

/** The configured repositories: portable-snapshot mirroring, account suggestions and device-local package lists. */
internal class PluginBrowseRepositories(
    private val shared: PluginBrowseState,
    deps: PluginBrowseDependencies,
) {
    private val repositoryStore = deps.repositoryStore
    private val keyValueStore = deps.keyValueStore
    private val portableRepository = deps.portableRepository
    private val accountRepositories = deps.accountRepositories

    private val dismissedSuggestionsMutex = Mutex()
    private val packageListsMutex = Mutex()

    /**
     * On upgraded installs, imports the legacy KV list once when the portable snapshot has never
     * owned repository state. After that one-time bridge, the snapshot is authoritative: every
     * refresh replaces the KV mirror exactly, including removals made by restore or sync.
     */
    internal suspend fun reconcilePortableRepositories() {
        reconcilePortableRepositoryStorage()
    }

    internal suspend fun reconcilePortableRepositoryStorage() {
        portableRepository?.let { portable ->
            val migrationComplete = keyValueStore.getString(PORTABLE_REPOSITORY_MIGRATION_KEY) == "true"
            if (!migrationComplete) {
                val legacyRepositories = repositoryStore.list()
                    .distinctBy { it.baseUrl }
                    .map { it.toPortableRepository() }
                // Until the legacy list is in, or someone else put repositories there first.
                var settled = legacyRepositories.isEmpty()
                while (!settled) {
                    val current = portable.currentSnapshot
                    settled = current.extensionRepositories.isNotEmpty() ||
                        portable.replaceSnapshotIfRevision(
                            expectedRevision = current.revision,
                            imported = current.copy(extensionRepositories = legacyRepositories),
                        ) != null
                }
                keyValueStore.putString(PORTABLE_REPOSITORY_MIGRATION_KEY, "true")
            }
            removeLegacyBundledRepository(portable)
            mirrorPortableRepositoriesToKeyValue(portable)
        }
    }

    /**
     * Versions before the repository became user-configured silently seeded this URL. Remove that
     * one-time seed from upgraded portable snapshots; repositories added by the user afterwards are
     * not touched because the migration marker is persisted.
     */
    private suspend fun removeLegacyBundledRepository(portable: ShinsouRepository) {
        if (keyValueStore.getString(LEGACY_BUNDLED_REPOSITORY_REMOVAL_KEY) == "true") return
        val bundledUrl = normalizeRepositoryInput(LEGACY_BUNDLED_REPOSITORY_URL)
        if (portable.currentSnapshot.extensionRepositories.any { it.baseUrl == bundledUrl }) {
            portable.deleteExtensionRepository(bundledUrl)
        }
        keyValueStore.putString(LEGACY_BUNDLED_REPOSITORY_REMOVAL_KEY, "true")
    }

    internal suspend fun saveRepository(repository: ExtensionRepository) {
        val portable = portableRepository
        if (portable == null) {
            repositoryStore.put(repository)
        } else {
            portable.upsertExtensionRepository(repository.toPortableRepository())
            mirrorPortableRepositoriesToKeyValue(portable)
        }
        // Added on purpose (possibly after an earlier dismissal): it is no longer a suggestion to hide.
        val added = suggestionKey(repository.baseUrl)
        updateDismissedSuggestions { it - added }
    }

    // ---- packages from the account -----------------------------------------------------------

    /** A device-local set of install keys kept in the plugin KV store (see [DECLINED_PREINSTALL_KEY]). */
    internal suspend fun packageList(key: String): Set<String> =
        keyValueStore.getString(key)
            ?.let { raw -> runCatchingCancellable { Json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrNull() }
            .orEmpty()
            .toSet()

    internal suspend fun updatePackageList(key: String, change: (Set<String>) -> Set<String>) {
        packageListsMutex.withLock {
            val current = packageList(key)
            val updated = change(current)
            if (updated != current) {
                keyValueStore.putString(key, Json.encodeToString(ListSerializer(String.serializer()), updated.sorted()))
            }
        }
    }

    // ---- repository suggestions from the account ---------------------------------------------

    internal fun suggestionKey(url: String): String = runCatching { normalizeRepositoryInput(url) }.getOrDefault(url.trim())

    /** URLs the user removed or dismissed on this device. Device-local: not synced, kept across sign-out. */
    private suspend fun dismissedSuggestions(): Set<String> =
        keyValueStore.getString(DISMISSED_SUGGESTIONS_KEY)
            ?.let { raw ->
                runCatchingCancellable { Json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrNull()
            }
            .orEmpty()
            .toSet()

    internal suspend fun updateDismissedSuggestions(change: (Set<String>) -> Set<String>) {
        dismissedSuggestionsMutex.withLock {
            val current = dismissedSuggestions()
            val updated = change(current)
            if (updated != current) {
                keyValueStore.putString(
                    DISMISSED_SUGGESTIONS_KEY,
                    Json.encodeToString(ListSerializer(String.serializer()), updated.sorted()),
                )
            }
        }
    }

    /** The account's repositories minus those this device already has and those it dismissed. */
    internal suspend fun repositorySuggestions(): List<BrowseRepositorySuggestion> {
        val directory = accountRepositories ?: return emptyList()
        val suggested = directory.repositories.value
        if (suggested.isEmpty()) return emptyList()
        val local = (portableRepository?.currentSnapshot?.extensionRepositories?.map { it.baseUrl }
            ?: repositoryStore.list().map { it.baseUrl })
            .mapTo(hashSetOf(), ::suggestionKey)
        val dismissed = dismissedSuggestions()
        return suggested
            .filter { suggestionKey(it.url).let { key -> key !in local && key !in dismissed } }
            .distinctBy { suggestionKey(it.url) }
            .map { BrowseRepositorySuggestion(it.url, it.name, it.signingKeyFingerprint.takeIf(String::isNotBlank)) }
    }

    internal suspend fun publishRepositorySuggestions() {
        val suggestions = repositorySuggestions()
        // Atomic, so a concurrent snapshot rebuild is not overwritten with stale rows.
        shared.mutableState.update { current ->
            if (current.repositorySuggestions == suggestions) current else current.copy(repositorySuggestions = suggestions)
        }
    }

    internal suspend fun isConfiguredRepository(normalizedBaseUrl: String): Boolean =
        repositoryStore.list().any { stored ->
            stored.baseUrl == normalizedBaseUrl ||
                runCatchingCancellable { normalizeRepositoryInput(stored.baseUrl) == normalizedBaseUrl }.getOrDefault(false)
        }

    internal suspend fun deleteRepository(baseUrl: String) {
        val portable = portableRepository
        if (portable == null) {
            repositoryStore.remove(baseUrl)
            return
        }
        portable.deleteExtensionRepository(baseUrl)
        mirrorPortableRepositoriesToKeyValue(portable)
    }

    private suspend fun mirrorPortableRepositoriesToKeyValue(portable: ShinsouRepository) {
        while (true) {
            val snapshot = portable.currentSnapshot
            val repositories = snapshot.extensionRepositories.map { it.toPluginRepository() }
            val stored = repositoryStore.list()
            if (stored != repositories) {
                val selected = repositoryStore.selected()
                stored.forEach { repositoryStore.remove(it.baseUrl) }
                repositories.forEach { repositoryStore.put(it) }
                repositoryStore.select(
                    selected?.takeIf { selectedId -> repositories.any { it.baseUrl == selectedId } },
                )
            }
            if (portable.currentSnapshot.revision == snapshot.revision) return
        }
    }

    private fun ExtensionRepository.toPortableRepository(): ExtensionRepo = ExtensionRepo(
        baseUrl = baseUrl,
        name = name,
        shortName = shortName,
        website = website,
        signingKeyFingerprint = signingKeyFingerprint,
    )

    private fun ExtensionRepo.toPluginRepository(): ExtensionRepository = ExtensionRepository(
        baseUrl = baseUrl,
        name = name,
        shortName = shortName,
        website = website,
        signingKeyFingerprint = signingKeyFingerprint,
    )

    internal fun normalizeRepositoryInput(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        val pathWithoutQuery = trimmed.substringBefore('?').substringBefore('#')
        val fileName = pathWithoutQuery.substringAfterLast('/')
        val normalized = if (fileName == "index.json" || fileName == "index.min.json" || fileName == "repo.json") {
            pathWithoutQuery.substringBeforeLast('/').trimEnd('/')
        } else {
            trimmed
        }
        return normalized
    }
}
