package dev.shinsou.kmp.plugin.browse

import kotlinx.coroutines.sync.Mutex
import dev.shinsou.kmp.crypto.Sha256
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.domain.content.ContentKind
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.ExtensionBrowseContentGatewayV2
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationCacheV2
import dev.shinsou.kmp.plugin.v2.ExtensionCapability
import dev.shinsou.kmp.plugin.v2.ExtensionSourceResolverV2
import dev.shinsou.kmp.plugin.v2.PreferenceV2
import dev.shinsou.kmp.contract.SourceCookie as BrowseSourceCookie
import dev.shinsou.kmp.contract.SourcePreference as BrowseSourcePreference
import dev.shinsou.kmp.contract.SourcePreferenceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dev.shinsou.kmp.plugin.contracts.BrowseExtension
import dev.shinsou.kmp.plugin.contracts.BrowseRepository
import dev.shinsou.kmp.plugin.contracts.BrowseSnapshot
import dev.shinsou.kmp.plugin.contracts.BrowseSource
import dev.shinsou.kmp.plugin.contracts.ConfigurableSource
import dev.shinsou.kmp.plugin.contracts.LoginSource
import dev.shinsou.kmp.plugin.contracts.PageRequestMetadata
import dev.shinsou.kmp.plugin.contracts.SourcePreference
import dev.shinsou.kmp.plugin.contracts.TextChapterSource
import dev.shinsou.kmp.plugin.contracts.resolvedAgainst
import dev.shinsou.kmp.plugin.contracts.toBrowseFilter
import dev.shinsou.kmp.plugin.model.ExtensionRepository
import dev.shinsou.kmp.plugin.model.ExtensionState
import dev.shinsou.kmp.plugin.model.PluginContentType
import dev.shinsou.kmp.plugin.model.PluginInstallKeys
import dev.shinsou.kmp.plugin.model.PluginRuntimePermission
import dev.shinsou.kmp.plugin.model.installedContentType
import dev.shinsou.kmp.plugin.model.toPluginContentType
import dev.shinsou.kmp.plugin.network.ConfiguredPluginProxyResolver
import dev.shinsou.kmp.plugin.network.PluginContentNetworkClient
import dev.shinsou.kmp.plugin.network.SourceNetworkOverride
import dev.shinsou.kmp.plugin.network.imageBodyForDecoderOrNull
import dev.shinsou.kmp.plugin.network.sanitizePluginContentHeaders
import dev.shinsou.kmp.plugin.storage.PluginCookie
import dev.shinsou.kmp.plugin.storage.PluginStorage
import dev.shinsou.kmp.plugin.trust.PluginBlockEntry

/** Startup/refresh discovery must never leave the browse surface behind a dead network host. */
private const val BROWSE_REMOTE_REFRESH_TIMEOUT_MILLIS: Long = 8_000L

/** Keys the removed reviewed ShuYue runtime stored; deleted best-effort on first load. */
private val LEGACY_SHUYUE_KV_KEYS: List<String> = listOf(
    "plugin.shuyue.v2.installations",
    "plugin.shuyue.v2.repository-urls",
)

/** Install keys of account packages the user removed here: never pre-installed again. */
internal const val DECLINED_PREINSTALL_KEY: String =
    "plugin.packages.declined-preinstall.v1"

/** Install keys installed from the account's list and not approved yet: the UI says where they came from. */
internal const val PREINSTALLED_KEY: String =
    "plugin.packages.preinstalled.v1"

/**
 * What every part of [PluginBrowseAdapter] shares: loading installed packages, refreshing and
 * rebuilding the browse snapshot, and the host-owned storage of a source row.
 */
internal class PluginBrowseCore(
    private val shared: PluginBrowseState,
    deps: PluginBrowseDependencies,
    private val repositoryConfig: PluginBrowseRepositories,
) {
    private val manager = deps.manager
    private val developerMode = deps.developerMode
    private val repositoryStore = deps.repositoryStore
    private val keyValueStore = deps.keyValueStore
    private val migrationProvider = deps.migrationProvider
    private val fileSystem = deps.fileSystem
    private val packageSecurity = deps.packageSecurity
    private val defaultDispatcher = deps.defaultDispatcher

    /**
     * Source calls can arrive from the local library before the background Browse refresh. Keep
     * installation hydration independently serialized so those calls wait for the exact reviewed
     * runtime (and its fixed credential/cookie storage scope) without deadlocking operationMutex.
     */
    private val installedLoadMutex = Mutex()
    private var loadedInstalled = false

    internal val extensionGatewayV2: ExtensionBrowseContentGatewayV2 = deps.extensionGatewayV2
        ?: ExtensionBrowseContentGatewayV2(
            ExtensionSourceResolverV2 { sourceKey ->
                ensureInstalledLoaded()
                manager.extensionSourceV2(sourceKey)
            },
        )

    /**
     * Shows the installed sources at once. Startup runs content migrations before the full
     * [refresh], and on a large library those take long enough that the Sources page stayed
     * empty until the user refreshed it by hand.
     */
    suspend fun showInstalledSources(): Unit = shared.operationMutex.withLock {
        withContext(defaultDispatcher) {
            ensureInstalledLoaded()
            rebuildSnapshot(errorMessage = null)
        }
    }

    internal suspend fun fetchExtensionThumbnailV2(
        sourceKey: SourceKey,
        url: String,
        headers: Map<String, String>,
    ): ByteArray? {
        val descriptor = manager.extensionSourceV2(sourceKey)?.descriptor ?: return null
        val metadata = PageRequestMetadata.parse(url)
        val resolved = resolveSourceHttpUrl(descriptor.baseUrl, metadata.cleanUrl) ?: return null
        // Use the exact live artifact scope. Looking the id up another way can select an
        // older version when several admitted ones share a package id, causing the content
        // request (and its source-scoped UA/preferences) to use the wrong namespace.
        val contentScope = manager.contentNetworkScopeForSource(sourceKey) ?: return null
        return fetchPluginThumbnail(
            content = contentScope.network,
            sourceId = contentScope.sourceId,
            url = resolved,
            headers = metadata.headers + headers,
            referer = descriptor.baseUrl,
        )
    }

    internal suspend fun fetchPluginThumbnail(
        content: PluginContentNetworkClient,
        sourceId: Long,
        url: String,
        headers: Map<String, String>,
        referer: String?,
    ): ByteArray? = try {
        val response = content.get(
            sourceId = sourceId,
            url = url,
            headers = linkedMapOf<String, String>().apply {
                // Metadata headers are extension-controlled. Content fetches must discard
                // credential-bearing values instead of letting PluginContentNetworkClient reject
                // the whole cover request (which the UI otherwise observes only as a null image).
                putAll(sanitizePluginContentHeaders(headers))
                if (!referer.isNullOrBlank() && keys.none { it.equals("Referer", ignoreCase = true) }) {
                    put("Referer", referer)
                }
            },
        )
        response.imageBodyForDecoderOrNull()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (logged: Throwable) {
        Log.warn("Plugin", logged) { "Fetching a cover image failed; returning no image" }
        null
    }

    internal val publicationCacheV2 = fileSystem?.let(::ExtensionPublicationCacheV2)

    /**
     * Whether the user signed in to a source, kept beside its settings and holding no secret: the
     * sources list shows it without opening protected storage. Set by what the user does (a login,
     * a browser sign-in, a cookie they add), never by cookies a site sets on its own.
     */
    internal suspend fun markSourceSignedIn(storageId: Long, signedIn: Boolean) {
        if (signedIn) keyValueStore.putString(sourceSignedInKey(storageId), "1")
        else keyValueStore.remove(sourceSignedInKey(storageId))
    }

    internal fun sourceSignedInKey(storageId: Long): String = "source.$storageId.signed-in"

    internal suspend fun refreshLocked(): Unit =
        withContext(defaultDispatcher) {
        shared.mutableState.value = shared.mutableState.value.copy(isRefreshing = true, errorMessage = null)
        try {
            ensureInstalledLoaded()
            repositoryConfig.reconcilePortableRepositories()
            // Publish installed/local sources before touching remote repositories. A cold launch
            // can therefore render immediately while discovery continues in the background.
            rebuildSnapshot(errorMessage = null, isRefreshing = true)
            val repositories = repositoryStore.list()
            manager.retainRepositoryPackages(repositories.map { it.baseUrl })
            val selected = repositoryStore.selected().takeIf { selected ->
                repositories.any { it.baseUrl == selected }
            }
            if (selected != repositoryStore.selected()) repositoryStore.select(selected)
            val visibleRepositories = selected?.let { selectedId ->
                repositories.filter { it.baseUrl == selectedId }
            } ?: repositories
            // A repository is optional metadata; it must not hold the reader/browse UI hostage
            // when a host is offline, a GitHub branch moved, or a LAN server is unreachable.
            withTimeoutOrNull(BROWSE_REMOTE_REFRESH_TIMEOUT_MILLIS) {
                // Publishers' rotations, withdrawals and key revocations first, so this refresh
                // already sees the current key and an installed package goes inert at once.
                packageSecurity?.let { security ->
                    for (repository in repositories) {
                        // Re-evaluated as soon as a change is stored, even if a later fetch times out.
                        security.syncPublisherDocuments(repository.baseUrl) { manager.loadInstalled() }
                    }
                }
                val refreshed = manager.refreshWithAdmissions(visibleRepositories)
                shared.descriptors = refreshed.descriptors.associateBy { it.id }
                shared.repositorySnapshots = refreshed.snapshots
                shared.repositoriesNeedingTrust = refreshed.repositoriesNeedingTrust
            }
            rebuildSnapshot(errorMessage = null)
            // The repositories and their indexes are as fresh as they get: pre-install what the account has used.
            shared.preinstallSignal.trySend(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            rebuildSnapshot(errorMessage = error.message ?: "Unable to refresh extensions")
            throw error
        } finally {
            // Cancellation makes further suspension unsafe, but this synchronous StateFlow update
            // must still release the loading scrim. A successful rebuild already cleared the flag.
            val current = shared.mutableState.value
            if (current.isRefreshing) shared.mutableState.value = current.copy(isRefreshing = false)
        }
    }

    internal suspend fun ensureInstalledLoaded(): Unit = installedLoadMutex.withLock {
        if (!loadedInstalled) {
            // Which installed packages load depends on developer mode (the `~dev` ones): its stored
            // setting is read first, even when a restored chapter gets here before startup does.
            loadDeveloperMode()
            manager.loadInstalled()
            removeLegacyShuYueKeys()
            loadedInstalled = true
        }
    }

    private suspend fun loadDeveloperMode() {
        try {
            developerMode?.ensureLoaded()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Throwable) {
            Log.warn("Plugin", logged) { "Could not read developer mode; it stays off" }
        }
    }

    /**
     * Best-effort cleanup of the only fixed keys the removed reviewed ShuYue runtime left on
     * devices. Unknown keys are deliberately not enumerated; failure is irrelevant.
     */
    private suspend fun removeLegacyShuYueKeys() {
        for (key in LEGACY_SHUYUE_KV_KEYS) {
            try {
                keyValueStore.remove(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Throwable) {
                Log.warn("Plugin", logged) {
                    "Removing a legacy preference key failed; ignoring it"
                }
            }
        }
    }

    internal suspend fun rebuildSnapshot(
        errorMessage: String?,
        isRefreshing: Boolean = false,
        reuseUnchangedSources: Boolean = false,
    ): Unit = withContext(defaultDispatcher) {
        val repositories = repositoryStore.list()
        val repositoryRows = repositories.map { it.toBrowseRepository() }
            .distinctBy(BrowseRepository::url)
        val selectedRowId = repositoryStore.selected()
        val installed = manager.installedPlugins().associateBy { it.manifest.id }
        val blockedPackages = packageSecurity?.blocklist?.entries().orEmpty()
            .filterIsInstance<PluginBlockEntry.Package>()
            .associateBy { it.packageId }
        val extensionDescriptorsV2 = manager.extensionDescriptorsV2()
        // A package that keeps cookies can hold a sign-in even when its script has no login hook:
        // the user signs in through the browser challenge or by importing cookies.
        val sessionPackages = manager.installedPlugins().filter {
            PluginRuntimePermission.COOKIE_STORAGE in it.manifest.runtimePermissions.orEmpty()
        }
        val sessionPackageIds = sessionPackages.mapTo(hashSetOf()) { it.manifest.id }
        val sessionSourceIds = sessionPackages.flatMapTo(hashSetOf()) { stored -> stored.manifest.sources.orEmpty().map { it.id } }
        val liveV2SourceKeys = extensionDescriptorsV2.flatMap { it.sources }
            .mapTo(linkedSetOf()) { it.sourceKey }
        shared.pruneV2SourceMappings(liveV2SourceKeys)
        val sourceDescriptors = shared.descriptors.values.flatMap { descriptor ->
            descriptor.sources.map { source -> source.id to descriptor }
        }.toMap()
        val rebuiltSourceProjections = linkedMapOf<Long, BrowseSourceProjection>()
        // Text-chapter script sources (A5) are listed with the V2 sources below instead: exactly
        // those the V2 list really contains, so no source can drop out of both lists.
        val scriptTextSourceIds = extensionDescriptorsV2
            .flatMap { it.sources }
            .filter { ContentKind.PLAIN_TEXT in it.supportedContentKinds }
            .mapNotNullTo(hashSetOf()) { it.sourceKey.legacyLongId }
        val legacySources = manager.catalogueSources()
            .filterNot { (it as? TextChapterSource)?.supportsChapterText == true && it.id in scriptTextSourceIds }
            .map { source ->
            val reused = shared.sourceProjections[source.id]
                ?.takeIf { reuseUnchangedSources && it.source === source }
                ?.value
            val projected = reused ?: run {
            val descriptor = sourceDescriptors[source.id]
            val supportsLogin = (source as? LoginSource)?.supportsLogin == true
            val filters = try {
                source.getFilterList().map { it.toBrowseFilter() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Throwable) {
                Log.warn("Plugin", logged) {
                    "Fetching source filters failed; showing the source without filters"
                }
                emptyList()
            }
            val pluginPreferences = (source as? ConfigurableSource)
                ?.let { configurableSource ->
                    try {
                        configurableSource.getPreferenceDefinitions()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (logged: Throwable) {
                        Log.warn("Plugin", logged) {
                            "Fetching source preference definitions failed; showing the source without them"
                        }
                        emptyList()
                    }
                }
                .orEmpty()
                .filterNot { it.key == ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE }
                .map { preference -> preference.toBrowsePreference(source.id) }
            val preferences = listOf(networkProxyPreference(source.id)) + pluginPreferences
            BrowseSource(
                id = source.id,
                name = source.name,
                language = source.lang,
                baseUrl = source.baseUrl,
                iconUrl = descriptor?.iconUrl,
                enabled = keyValueStore.getString(sourceEnabledKey(source.id))?.toBooleanStrictOrNull() ?: true,
                isNsfw = descriptor?.nsfw ?: false,
                supportsLatest = source.supportsLatest,
                supportsLogin = supportsLogin,
                canSignIn = supportsLogin || source.id in sessionSourceIds,
                signedIn = keyValueStore.getString(sourceSignedInKey(source.id)) != null,
                requiresBrowserSessionLogin = source.requiredWebChallengeLocalStorageKeys.isNotEmpty(),
                supportsFavorites = source.supportsFavorites,
                // Credentials and cookies are intentionally not decrypted while rebuilding the
                // public source catalogue. Desktop protected storage may need interactive OS
                // authorization; making that part of startup used to hold the complete browse
                // refresh mutex indefinitely. Secret state is accessed only by explicit login,
                // cookie-management, challenge, and network operations.
                credential = null,
                cookies = emptyList(),
                preferences = preferences,
                filters = filters,
                contentType = descriptor?.contentType
                    ?: installed.values.firstOrNull { stored ->
                        stored.manifest.sources.orEmpty().any { it.id == source.id }
                    }?.manifest?.installedContentType()
                    ?: PluginContentType.BOTH,
            )
            }
            rebuiltSourceProjections[source.id] = BrowseSourceProjection(source, projected)
            projected
        }
        val occupiedSourceIds = legacySources.mapTo(hashSetOf(), BrowseSource::id)
        val nativeSources = buildList {
            extensionDescriptorsV2
                .flatMap { it.sources }
                .filterNot { descriptor -> legacySources.any { it.sourceKey == descriptor.sourceKey } }
                .forEach { descriptor ->
                    val sourceKey = descriptor.sourceKey
                    val rowId = shared.v2UiRowId(sourceKey, occupiedSourceIds)
                    val storageId = shared.v2StorageSourceId(sourceKey)
                    val hostSource = manager.extensionSourceV2(sourceKey)
                    val supportsLogin = ExtensionCapability.LOGIN in descriptor.capabilities
                    val preferences = buildList {
                        storageId?.let { add(networkProxyPreference(it)) }
                        if (hostSource != null && ExtensionCapability.PREFERENCES in descriptor.capabilities) {
                            try {
                                hostSource.preferences()
                                    .filterNot {
                                        it.key == ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE
                                    }
                                    .forEach { preference ->
                                    storageId?.let { add(preference.toBrowsePreference(it)) }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (logged: Throwable) {
                                // A source's optional preference endpoint must not make the
                                // source list disappear; the raw proxy setting remains usable.
                                Log.warn("Plugin", logged) {
                                    "Fetching extension preferences failed; showing only the proxy setting"
                                }
                            }
                        }
                    }
                    val filters = if (hostSource != null && ExtensionCapability.BROWSE in descriptor.capabilities) {
                        try {
                            hostSource.browseOptions().filters.map { it.toBrowseFilter() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (logged: Throwable) {
                            // Optional filter discovery must not hide an otherwise valid source.
                            Log.warn("Plugin", logged) {
                                "Fetching extension browse filters failed; showing the source without filters"
                            }
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                    add(
                        BrowseSource(
                            id = rowId,
                            name = descriptor.displayName,
                            language = descriptor.languageTag,
                            baseUrl = descriptor.baseUrl.orEmpty(),
                            enabled = keyValueStore.getString(sourceEnabledV2Key(sourceKey))
                                ?.toBooleanStrictOrNull() ?: true,
                            supportsLatest = ExtensionCapability.LATEST in descriptor.capabilities,
                            supportsLogin = supportsLogin,
                            canSignIn = supportsLogin || sourceKey.packageId in sessionPackageIds,
                            signedIn = storageId?.let { keyValueStore.getString(sourceSignedInKey(it)) } != null,
                            requiresBrowserSessionLogin = hostSource
                                ?.requiredWebChallengeLocalStorageKeys()
                                ?.isNotEmpty() == true,
                            supportsFavorites = ExtensionCapability.FAVORITE in descriptor.capabilities,
                            favoriteBrowseOptionKey = if (ExtensionCapability.FAVORITE in descriptor.capabilities) {
                                DEFAULT_FAVORITE_BROWSE_OPTION_KEY
                            } else {
                                null
                            },
                            // See the legacy-source projection above. Never make repository/source
                            // discovery depend on unlocking protected credential storage.
                            credential = null,
                            cookies = emptyList(),
                            preferences = preferences,
                            filters = filters,
                            sourceKey = sourceKey,
                            contentType = descriptor.supportedContentKinds.toPluginContentType(),
                        ),
                    )
                }
        }
        // Which repository provided each source: the installed package that declares it.
        val repositoryBySourceId = installed.values.flatMap { stored ->
            val repository = stored.metadata.repositoryBaseUrl ?: return@flatMap emptyList<Pair<Long, String>>()
            stored.manifest.sources.orEmpty().map { it.id to repository }
        }.toMap()
        fun BrowseSource.withRepository(): BrowseSource = copy(
            repositoryId = repositoryId
                ?: sourceKey?.legacyLongId?.let(repositoryBySourceId::get)
                ?: sourceKey?.sourceId?.toLongOrNull()?.let(repositoryBySourceId::get)
                ?: (if (sourceKey == null) repositoryBySourceId[id] ?: sourceDescriptors[id]?.repositoryBaseUrl else null),
        )
        val sources = (legacySources + nativeSources)
            .map { it.withRepository() }
            .distinctBy(BrowseSource::identityKey)
            .sortedWith(compareBy<BrowseSource> { it.language }.thenBy { it.name.lowercase() })
        val preinstalledIds = repositoryConfig.packageList(PREINSTALLED_KEY)
        val extensions = shared.descriptors.values.map { descriptor ->
            val local = installed[descriptor.id]
            // A verified digest is integrity state, not permission to execute sensitive host
            // services. The UI's execution switch reflects the exact-artifact approval gate.
            val trusted = local != null && manager.hasExecutionApproval(descriptor.id)
            BrowseExtension(
                id = descriptor.id,
                name = descriptor.name,
                version = descriptor.version,
                language = descriptor.lang,
                iconUrl = descriptor.iconUrl,
                installed = descriptor.state != ExtensionState.AVAILABLE,
                updateAvailable = descriptor.state == ExtensionState.UPDATE_AVAILABLE,
                trusted = trusted,
                blockedByUser = blockedPackages.containsKey(PluginInstallKeys.publishedPackageId(descriptor.id)),
                blockEntryKey = blockedPackages[PluginInstallKeys.publishedPackageId(descriptor.id)]?.let { entry ->
                    if (entry.publisherId.isEmpty()) "package::${entry.packageId}"
                    else "package:${entry.publisherId}:${entry.packageId}"
                },
                isNsfw = descriptor.nsfw,
                sourceIds = descriptor.sources.map { it.id },
                contentType = descriptor.contentType,
                inDevelopment = PluginInstallKeys.isDeveloperKey(descriptor.id),
                repositoryId = descriptor.repositoryBaseUrl,
                preinstalledFromAccount = local != null && !trusted && descriptor.id in preinstalledIds,
            )
        }
        shared.sourceProjections = rebuiltSourceProjections
        shared.mutableState.value = BrowseSnapshot(
            repositories = repositoryRows,
            selectedRepositoryId = selectedRowId,
            repositorySuggestions = repositoryConfig.repositorySuggestions(),
            sources = sources,
            extensions = extensions,
            migrations = migrationProvider.candidates().map { it.resolvedAgainst(sources) },
            isRefreshing = isRefreshing,
            errorMessage = errorMessage,
        )
    }

    internal fun ExtensionRepository.toBrowseRepository(): BrowseRepository = BrowseRepository(
        id = baseUrl,
        url = baseUrl,
        name = name,
        website = website,
        signingFingerprint = signingKeyFingerprint.takeIf { it.isNotBlank() },
        needsPublisherTrust = baseUrl in shared.repositoriesNeedingTrust,
    )

    internal fun sourceEnabledKey(sourceId: Long): String = "source.$sourceId.enabled"

    internal fun sourceEnabledV2Key(sourceKey: SourceKey): String =
        "source.v2.${Sha256.hex(sourceKey.canonicalId.encodeToByteArray())}.enabled"

    /** Resolve UI state through the manager's exact package/source ownership map. */
    internal suspend fun storageForSourceUi(sourceId: Long): PluginStorage {
        val scope = shared.requireV2SourceScope(sourceId)
        if (scope != null) {
            val sourceKey = scope.first
            val ownedSourceId = requireNotNull(sourceKey.legacyLongId) {
                "Generic source has no host-assigned storage id: ${sourceKey.canonicalId}"
            }
            return requireNotNull(manager.storageForSource(ownedSourceId)) {
                "No host-owned storage is available for ${sourceKey.canonicalId}"
            }
        }
        return requireNotNull(manager.storageForSource(sourceId)) {
            "No host-owned storage is available for source $sourceId"
        }
    }

    private suspend fun networkProxyPreference(sourceId: Long): BrowseSourcePreference {
        val stored = storageForSourceUi(sourceId).getPreference(
            sourceId,
            ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE,
        )
        val value = SourceNetworkOverride.fromStored(
            stored,
            SourceNetworkOverride.GLOBAL,
        ).storedValue
        return BrowseSourcePreference(
            key = ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE,
            title = "Cloudflare Worker proxy",
            summary = "Follow global uses the Advanced setting. Force enable and Force disable override it for this source.",
            value = value,
            choices = listOf("Follow global", "Force enable", "Force disable"),
            choiceValues = listOf("global", "on", "off"),
            kind = SourcePreferenceKind.Choice,
        )
    }

    private suspend fun SourcePreference.toBrowsePreference(sourceId: Long): BrowseSourcePreference = when (this) {
        is SourcePreference.TextField -> BrowseSourcePreference(
            key = key,
            title = title,
            summary = summary.takeIf { it.isNotBlank() },
            value = storageForSourceUi(sourceId).getPreference(sourceId, key) ?: defaultValue,
            kind = SourcePreferenceKind.Text,
        )
        is SourcePreference.Toggle -> BrowseSourcePreference(
            key = key,
            title = title,
            summary = summary.takeIf { it.isNotBlank() },
            value = storageForSourceUi(sourceId).getPreference(sourceId, key) ?: defaultValue.toString(),
            kind = SourcePreferenceKind.Toggle,
        )
        is SourcePreference.Select -> BrowseSourcePreference(
            key = key,
            title = title,
            value = storageForSourceUi(sourceId).getPreference(sourceId, key) ?: defaultValue,
            choices = entries,
            choiceValues = entryValues.takeIf { it.size == entries.size } ?: entries,
            kind = SourcePreferenceKind.Choice,
        )
        is SourcePreference.MultiSelect -> BrowseSourcePreference(
            key = key,
            title = title,
            value = storageForSourceUi(sourceId).getPreference(sourceId, key) ?: defaultValues.sorted().joinToString(","),
            choices = entries,
            choiceValues = entryValues.takeIf { it.size == entries.size } ?: entries,
            kind = SourcePreferenceKind.MultiChoice,
        )
    }

    private suspend fun PreferenceV2.toBrowsePreference(sourceId: Long): BrowseSourcePreference {
        val stored = storageForSourceUi(sourceId).getPreference(sourceId, key)
        return BrowseSourcePreference(
            key = key,
            title = label,
            value = stored ?: value.orEmpty(),
            kind = SourcePreferenceKind.Text,
        )
    }
}

internal fun toBrowseCookie(cookie: PluginCookie): BrowseSourceCookie = BrowseSourceCookie(
    name = cookie.name,
    value = cookie.value,
    domain = cookie.domain,
    path = cookie.path,
    expiresAtEpochMillis = cookie.expiresAtEpochMillis,
    secure = cookie.secure,
    httpOnly = cookie.httpOnly,
    hostOnly = cookie.hostOnly,
)
