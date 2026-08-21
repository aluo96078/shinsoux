package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.ExtensionBrowseContentGatewayV2
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerV2
import dev.shinsou.kmp.plugin.v2.ExtensionContentMaterializationV2
import dev.shinsou.kmp.plugin.v2.ExtensionCapability
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationPageV2
import dev.shinsou.kmp.plugin.v2.ExtensionSourceResolverV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.plugin.v2.HostExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.PagedResultV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import dev.shinsou.kmp.plugin.v2.UnitContentResultV2
import dev.shinsou.kmp.plugin.shuyue.KeyValueShuYueReviewedStoreV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueQuarantineReviewV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedInstallApprovalV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedPluginCatalogV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedRepositoryCoordinatorV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedRepositoryPackageV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryIndexLoader
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryLocation
import dev.shinsou.kmp.plugin.shuyue.ShuYueScriptCandidateV2
import dev.shinsou.kmp.ui.BrowseCallbacks
import dev.shinsou.kmp.ui.BrowseExtension
import dev.shinsou.kmp.ui.BrowseFilter
import dev.shinsou.kmp.ui.BrowseManga
import dev.shinsou.kmp.ui.BrowsePage
import dev.shinsou.kmp.ui.BrowseRepository
import dev.shinsou.kmp.ui.BrowseSnapshot
import dev.shinsou.kmp.ui.BrowseSource
import dev.shinsou.kmp.ui.ContentCallbacks
import dev.shinsou.kmp.ui.MigrationCandidate
import dev.shinsou.kmp.ui.ReaderChapter
import dev.shinsou.kmp.ui.ReaderPage
import dev.shinsou.kmp.ui.SourceLoginRequest
import dev.shinsou.kmp.ui.SourceCookie as BrowseSourceCookie
import dev.shinsou.kmp.ui.SourceCredential as BrowseSourceCredential
import dev.shinsou.kmp.ui.SourcePreference as BrowseSourcePreference
import dev.shinsou.kmp.ui.SourcePreferenceKind
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.TypedReaderContentSession
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public fun interface PluginMangaResolver {
    public suspend fun resolve(manga: BrowseManga): Long?

    public companion object {
        public val None: PluginMangaResolver = PluginMangaResolver { null }
    }
}

public fun interface PluginMigrationHandler {
    public suspend fun migrate(mangaId: Long, target: BrowseManga)

    public companion object {
        public val None: PluginMigrationHandler = PluginMigrationHandler { _, _ -> }
    }
}

public fun interface PluginMigrationProvider {
    public suspend fun candidates(): List<MigrationCandidate>

    public companion object {
        public val None: PluginMigrationProvider = PluginMigrationProvider { emptyList() }
    }
}

private data class BrowseSourceProjection(
    val source: CatalogueSource,
    val value: BrowseSource,
)

/**
 * Adapts the executable plugin subsystem to the UI's stable browse DTOs. Packages, trust tokens,
 * and credentials use the plugin KV boundary; when [portableRepository] is supplied, configured
 * repositories use its portable snapshot and KV is retained only as a runtime compatibility mirror.
 */
public class PluginBrowseAdapter(
    private val manager: PluginManager,
    private val repositoryClient: ExtensionRepositoryClient,
    private val repositoryStore: ExtensionRepositoryStore,
    private val pluginStorage: PluginStorage,
    private val keyValueStore: PluginKeyValueStore,
    private val trustStore: PluginTrustStore,
    private val mangaResolver: PluginMangaResolver = PluginMangaResolver.None,
    private val migrationHandler: PluginMigrationHandler = PluginMigrationHandler.None,
    private val migrationProvider: PluginMigrationProvider = PluginMigrationProvider.None,
    private val requestBuilder: PluginRequestBuilder = PluginRequestBuilder(pluginStorage),
    private val portableRepository: ShinsouRepository? = null,
    private val loginRequestCoordinator: PluginLoginRequestCoordinator = PluginLoginRequestCoordinator(),
    /** Optional host-provided repository. The application passes an empty value by default. */
    private val defaultRepositoryUrl: String = "",
    private val extensionGatewayV2: ExtensionBrowseContentGatewayV2 = ExtensionBrowseContentGatewayV2(
        ExtensionSourceResolverV2 { sourceKey -> manager.extensionSourceV2(sourceKey) },
    ),
    /** Null only in previews/tests that intentionally omit the shared content foundation. */
    private val extensionContentConsumerV2: ExtensionContentConsumerV2? = null,
    /** Production supplies the bounded reviewed repository loader; previews may omit it. */
    private val reviewedShuYueRepositoryLoaderV2: ShuYueRepositoryIndexLoader? = null,
    private val reviewedShuYueRepositoryLocationV2: ShuYueRepositoryLocation =
        ShuYueRepositoryLocation.IndexUrl(
            ShuYueReviewedRepositoryCoordinatorV2.DEFAULT_REVIEWED_SHUYUE_INDEX_URL,
        ),
) : BrowseCallbacks {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(BrowseSnapshot())
    private val reviewedShuYueStoreV2 = KeyValueShuYueReviewedStoreV2(keyValueStore)
    private val reviewedShuYueInstallerV2 = manager.reviewedShuYueInstallCoordinatorV2(
        quarantineStore = reviewedShuYueStoreV2,
        approvalStore = reviewedShuYueStoreV2,
        installationStore = reviewedShuYueStoreV2,
    )
    private val reviewedShuYueRepositoryV2 = reviewedShuYueRepositoryLoaderV2?.let { loader ->
        ShuYueReviewedRepositoryCoordinatorV2(
            loader = loader,
            installer = reviewedShuYueInstallerV2,
            location = reviewedShuYueRepositoryLocationV2,
        )
    }
    private var loadedInstalled = false
    private var descriptors: Map<String, ExtensionDescriptor> = emptyMap()
    private var reviewedShuYuePackages: Map<String, ShuYueReviewedRepositoryPackageV2> = emptyMap()
    private var sourceProjections: Map<Long, BrowseSourceProjection> = emptyMap()
    private val v2SourceRowIds = linkedMapOf<SourceKey, Long>()

    override val state: StateFlow<BrowseSnapshot> = mutableState
    override val loginRequests: StateFlow<List<SourceLoginRequest>> = loginRequestCoordinator.loginRequests

    override fun dismissSourceLoginRequest(sourceId: Long) {
        loginRequestCoordinator.dismiss(sourceId)
    }

    override suspend fun refresh(): Unit = operationMutex.withLock {
        refreshLocked()
    }

    override suspend fun setSourceEnabled(sourceId: Long, enabled: Boolean): Unit = operationMutex.withLock {
        keyValueStore.putString(sourceEnabledKey(sourceId), enabled.toString())
        rebuildSnapshot(errorMessage = null)
    }

    override suspend fun setSourceEnabledV2(sourceKey: SourceKey, enabled: Boolean): Unit =
        operationMutex.withLock {
            check(manager.extensionSourceV2(sourceKey) != null) {
                "Unknown extension v2 source: ${sourceKey.canonicalId}"
            }
            keyValueStore.putString(sourceEnabledV2Key(sourceKey), enabled.toString())
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun addRepository(url: String): BrowseRepository? = operationMutex.withLock {
        reconcilePortableRepositories()
        val repository = repositoryClient.fetchRepository(normalizeRepositoryInput(url))
        val hadRepositories = repositoryStore.list().isNotEmpty()
        saveRepository(repository)
        if (!hadRepositories || repositoryStore.selected() == null) repositoryStore.select(repository.baseUrl)
        refreshLocked()
        repository.toBrowseRepository()
    }

    override suspend fun removeRepository(repositoryId: String): Unit = operationMutex.withLock {
        reconcilePortableRepositories()
        deleteRepository(repositoryId)
        val remaining = repositoryStore.list()
        if (repositoryStore.selected() !in remaining.map { it.baseUrl }) {
            repositoryStore.select(remaining.firstOrNull()?.baseUrl)
        }
        refreshLocked(addDefaultRepository = false)
    }

    override suspend fun selectRepository(repositoryId: String?): Unit = operationMutex.withLock {
        reconcilePortableRepositories()
        val repositories = repositoryStore.list()
        require(repositoryId == null || repositories.any { it.baseUrl == repositoryId }) {
            "Unknown extension repository: $repositoryId"
        }
        repositoryStore.select(repositoryId)
        refreshLocked(addDefaultRepository = false)
    }

    override suspend fun browseSource(
        sourceId: Long,
        query: String,
        page: Int,
        filters: List<BrowseFilter>?,
    ): BrowsePage = withContext(Dispatchers.Default) {
        val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
        val enabled = keyValueStore.getString(sourceEnabledKey(sourceId))?.toBooleanStrictOrNull() ?: true
        check(enabled) { "Source '$sourceId' is disabled" }
        val pluginPage = (page.coerceAtLeast(1) - 1)
        val result = if (query.isBlank() && filters == null) {
            source.getPopularManga(pluginPage)
        } else {
            source.getSearchManga(
                pluginPage,
                query,
                filters?.map { it.toPluginFilter() } ?: source.getFilterList(),
            )
        }
        BrowsePage(
            items = mapBrowseManga(source, result.mangas),
            hasNextPage = result.hasNextPage,
        )
    }

    override suspend fun browseSourceLatest(sourceId: Long, page: Int): BrowsePage =
        withContext(Dispatchers.Default) {
        val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
        val enabled = keyValueStore.getString(sourceEnabledKey(sourceId))?.toBooleanStrictOrNull() ?: true
        check(enabled) { "Source '$sourceId' is disabled" }
        check(source.supportsLatest) { "Source '$sourceId' does not support latest updates" }
        val result = source.getLatestUpdates(page.coerceAtLeast(1) - 1)
        BrowsePage(
            items = mapBrowseManga(source, result.mangas),
            hasNextPage = result.hasNextPage,
        )
    }

    override suspend fun extensionSourceV2(sourceKey: SourceKey): HostExtensionSourceV2? =
        extensionGatewayV2.source(sourceKey)

    override suspend fun browseSourceV2(
        sourceKey: SourceKey,
        options: BrowseOptionsV2,
        page: Int,
    ): PagedResultV2<RemotePublicationV2> = withContext(Dispatchers.Default) {
        extensionGatewayV2.browse(sourceKey, options, page)
    }

    override suspend fun searchSourceV2(
        sourceKey: SourceKey,
        query: String,
        page: Int,
    ): PagedResultV2<RemotePublicationV2> = withContext(Dispatchers.Default) {
        extensionGatewayV2.search(sourceKey, query, page)
    }

    override suspend fun latestSourceV2(
        sourceKey: SourceKey,
        page: Int,
    ): PagedResultV2<RemotePublicationV2> = withContext(Dispatchers.Default) {
        extensionGatewayV2.latest(sourceKey, page)
    }

    override suspend fun extensionDetailsV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
    ): RemotePublicationV2 = withContext(Dispatchers.Default) {
        extensionGatewayV2.details(sourceKey, remotePublicationId)
    }

    override suspend fun extensionUnitsV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int,
    ): PagedResultV2<RemoteUnitV2> = withContext(Dispatchers.Default) {
        extensionGatewayV2.units(sourceKey, remotePublicationId, page)
    }

    override suspend fun extensionContentV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitId: String,
    ): UnitContentResultV2 = withContext(Dispatchers.Default) {
        extensionGatewayV2.content(sourceKey, remotePublicationId, remoteUnitId)
    }

    override suspend fun extensionPublicationPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int,
    ): ExtensionPublicationPageV2 = withContext(Dispatchers.Default) {
        requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.publicationPage(sourceKey, remotePublicationId, page)
    }

    override suspend fun materializeExtensionContentV2(
        selection: ExtensionUnitSelectionV2,
        representationId: String?,
    ): ExtensionContentMaterializationV2 = withContext(Dispatchers.Default) {
        requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.materialize(selection, representationId)
    }

    override suspend fun openMaterializedExtensionContentV2(
        materialization: ExtensionContentMaterializationV2,
    ): TypedReaderContentSession = withContext(Dispatchers.Default) {
        val readable = requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.open(materialization)
        TypedReaderContentSession(
            content = readable.content,
            canonicalText = readable.canonicalText,
            access = readable.access,
        )
    }

    override suspend fun stageReviewedShuYueV2(
        candidate: ShuYueScriptCandidateV2,
    ): ShuYueQuarantineReviewV2 = withContext(Dispatchers.Default) {
        reviewedShuYueInstallerV2.stage(candidate)
    }

    override suspend fun stageReviewedShuYuePackageV2(
        packageId: String,
    ): ShuYueQuarantineReviewV2 = withContext(Dispatchers.Default) {
        requireNotNull(reviewedShuYueRepositoryV2) {
            "Reviewed ShuYue repository is unavailable"
        }.stage(packageId)
    }

    override suspend fun reviewShuYueQuarantineV2(
        quarantineId: String,
    ): ShuYueQuarantineReviewV2 = withContext(Dispatchers.Default) {
        reviewedShuYueInstallerV2.review(quarantineId)
    }

    override suspend fun approveAndInstallReviewedShuYueV2(
        decision: ShuYueReviewedInstallApprovalV2,
    ): Unit = withContext(Dispatchers.Default) {
        reviewedShuYueInstallerV2.approveAndInstall(decision)
        operationMutex.withLock { rebuildSnapshot(errorMessage = null) }
    }

    override suspend fun uninstallReviewedShuYueV2(packageId: String): Unit =
        withContext(Dispatchers.Default) {
            val installed = reviewedShuYueInstallerV2.installed(packageId)
                ?: throw IllegalArgumentException("Reviewed ShuYue package '$packageId' is not installed")
            reviewedShuYueInstallerV2.revokeAndUnload(installed.identity)
            operationMutex.withLock { rebuildSnapshot(errorMessage = null) }
        }

    override suspend fun resolveManga(item: BrowseManga): Long? {
        require(item.sourceKey == null) {
            "Extension v2 publications must use the exact details/unit/content workflow"
        }
        return mangaResolver.resolve(item)
    }

    override suspend fun installExtension(extensionId: String): Unit = withContext(Dispatchers.Default) {
        operationMutex.withLock installOperation@{
        require(ShuYueReviewedPluginCatalogV2.profiles.none { it.identity.packageId == extensionId }) {
            "Reviewed ShuYue packages must use the reviewed installation flow"
        }
        ensureInstalledLoaded()
        val descriptor = descriptors[extensionId]
            ?: throw IllegalArgumentException("Unknown extension: $extensionId")
        // A second tap can queue behind an in-flight install. Once the first succeeds, make the
        // queued call a no-op instead of downloading and installing the same package again.
        if (descriptor.state == ExtensionState.INSTALLED) return@installOperation
        val repositoryBaseUrl = descriptor.repositoryBaseUrl
            ?: throw IllegalStateException("Extension '$extensionId' has no repository")
        val repository = repositoryStore.list().firstOrNull { it.baseUrl == repositoryBaseUrl }
            ?: throw IllegalStateException("Repository is no longer configured: $repositoryBaseUrl")
        val installedVersion = when (val index = repositoryClient.fetchIndex(repository.baseUrl)) {
            is RepositoryIndex.Plugins -> {
                val entry = index.entries.firstOrNull { it.id == extensionId }
                    ?: throw IllegalStateException("Extension '$extensionId' is no longer in the repository")
                manager.install(repository, entry)
                entry.version
            }

            is RepositoryIndex.Legacy -> {
                val entry = index.entries.firstOrNull { it.pkg == extensionId }
                    ?: throw IllegalStateException("Extension '$extensionId' is no longer in the repository")
                manager.installLegacy(repository, entry)
                entry.version
            }
        }
        // The index used above is already authoritative for this operation. Reusing the current
        // descriptor avoids fetching the same index again and rescanning every installed package.
        descriptors = descriptors + (
            extensionId to descriptor.copy(
                state = ExtensionState.INSTALLED,
                installedVersion = installedVersion,
            )
        )
        // Installing one runtime cannot change the UI settings of every other live runtime. Reuse
        // projections only when the exact CatalogueSource instance is still installed; an update,
        // trust change, source-id collision, or replacement therefore rebuilds the affected source.
        rebuildSnapshot(errorMessage = null, reuseUnchangedSources = true)
        }
    }

    override suspend fun uninstallExtension(extensionId: String): Unit = operationMutex.withLock {
        manager.uninstall(extensionId)
        trustStore.revokeAll(extensionId)
        refreshLocked(addDefaultRepository = false)
    }

    override suspend fun setExtensionTrusted(extensionId: String, trusted: Boolean): Unit =
        operationMutex.withLock {
            // `trusted` is an execution grant, not a claim about repository authorship. Even a
            // package with a matching digest must be unloaded when this grant is revoked.
            manager.setPluginTrusted(extensionId, trusted)
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun migrateManga(mangaId: Long, target: BrowseManga) {
        migrationHandler.migrate(mangaId, target)
    }

    override suspend fun saveSourcePreferences(sourceId: Long, values: Map<String, String>): Unit =
        operationMutex.withLock {
            check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
            values.forEach { (key, value) -> pluginStorage.setPreference(sourceId, key, value) }
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun saveSourceCredentials(
        sourceId: Long,
        username: String,
        password: String,
    ): Boolean = operationMutex.withLock {
        require(username.isNotBlank()) { "Username cannot be blank" }
        val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
        val loginSource = source as? LoginSource
        val success = if (loginSource?.supportsLogin == true) {
            loginSource.login(username, password)
        } else {
            true
        }
        if (success) {
            pluginStorage.setCredential(sourceId, PluginCredential(username, password))
            loginRequestCoordinator.dismiss(sourceId)
        }
        rebuildSnapshot(errorMessage = null)
        success
    }

    override suspend fun logoutSource(sourceId: Long): Unit = operationMutex.withLock {
        val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
        val result = runCatching {
            (source as? LoginSource)?.takeIf { it.supportsLogin }?.logout()
        }
        pluginStorage.clearCredential(sourceId)
        rebuildSnapshot(errorMessage = null)
        result.getOrThrow()
    }

    override suspend fun setSourceCookie(sourceId: Long, cookie: BrowseSourceCookie): Unit =
        operationMutex.withLock {
            check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
            require(cookie.name.isNotBlank()) { "Cookie name cannot be blank" }
            require(cookie.domain.isNotBlank()) { "Cookie domain cannot be blank" }
            pluginStorage.setCookie(
                sourceId,
                PluginCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path.ifBlank { "/" },
                    expiresAtEpochMillis = cookie.expiresAtEpochMillis,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    hostOnly = cookie.hostOnly,
                ),
            )
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun deleteSourceCookie(sourceId: Long, name: String, domain: String): Unit =
        operationMutex.withLock {
            check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
            pluginStorage.deleteCookie(sourceId, name, domain)
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun clearSourceCookies(sourceId: Long): Unit = operationMutex.withLock {
        check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
        pluginStorage.clearCookies(sourceId)
        rebuildSnapshot(errorMessage = null)
    }

    override suspend fun sourceWebChallenge(sourceId: Long): SourceWebChallengeRequest? {
        val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
        val target = runCatching { Url(source.baseUrl.trim()) }.getOrNull() ?: return null
        if (target.protocol.name !in setOf("http", "https") || target.host.isBlank()) return null
        val built = requestBuilder.build(sourceId, PluginHttpRequest("GET", target.toString()))
        val userAgent = built.transportRequest.headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            .orEmpty()
        return SourceWebChallengeRequest(
            sourceId = sourceId,
            sourceName = source.name,
            url = target.toString(),
            userAgent = userAgent,
            cookies = pluginStorage.getCookies(sourceId).map { cookie ->
                BrowseSourceCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path,
                    expiresAtEpochMillis = cookie.expiresAtEpochMillis,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    hostOnly = cookie.hostOnly,
                )
            },
        )
    }

    private suspend fun refreshLocked(addDefaultRepository: Boolean = true): Unit =
        withContext(Dispatchers.Default) {
        mutableState.value = mutableState.value.copy(isRefreshing = true, errorMessage = null)
        try {
            ensureInstalledLoaded()
            reconcilePortableRepositories()
            var repositories = repositoryStore.list()
            if (repositories.isEmpty() && addDefaultRepository && defaultRepositoryUrl.isNotBlank()) {
                val defaultRepository = repositoryClient.fetchRepository(normalizeRepositoryInput(defaultRepositoryUrl))
                saveRepository(defaultRepository)
                repositoryStore.select(defaultRepository.baseUrl)
                repositories = listOf(defaultRepository)
            }
            val selected = repositoryStore.selected().takeIf { selected ->
                repositories.any { it.baseUrl == selected }
            }
            if (selected != repositoryStore.selected()) repositoryStore.select(selected)
            val visibleRepositories = selected?.let { selectedId ->
                repositories.filter { it.baseUrl == selectedId }
            } ?: repositories
            descriptors = manager.refresh(visibleRepositories).associateBy { it.id }
            reviewedShuYueRepositoryV2?.let { repository ->
                reviewedShuYuePackages = repository.refresh().associateBy { it.packageId }
            }
            rebuildSnapshot(errorMessage = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            rebuildSnapshot(errorMessage = error.message ?: "Unable to refresh extensions")
            throw error
        } finally {
            // Cancellation makes further suspension unsafe, but this synchronous StateFlow update
            // must still release the loading scrim. A successful rebuild already cleared the flag.
            val current = mutableState.value
            if (current.isRefreshing) mutableState.value = current.copy(isRefreshing = false)
        }
    }

    /**
     * On upgraded installs, imports the legacy KV list once when the portable snapshot has never
     * owned repository state. After that one-time bridge, the snapshot is authoritative: every
     * refresh replaces the KV mirror exactly, including removals made by restore or sync.
     */
    private suspend fun reconcilePortableRepositories() {
        val portable = portableRepository ?: return
        val migrationComplete = keyValueStore.getString(PORTABLE_REPOSITORY_MIGRATION_KEY) == "true"
        if (!migrationComplete) {
            val legacyRepositories = repositoryStore.list()
                .distinctBy { it.baseUrl }
                .map { it.toPortableRepository() }
            while (legacyRepositories.isNotEmpty()) {
                val current = portable.currentSnapshot
                if (current.extensionRepositories.isNotEmpty()) break
                val migrated = portable.replaceSnapshotIfRevision(
                    expectedRevision = current.revision,
                    imported = current.copy(extensionRepositories = legacyRepositories),
                )
                if (migrated != null) break
            }
            keyValueStore.putString(PORTABLE_REPOSITORY_MIGRATION_KEY, "true")
        }
        removeLegacyBundledRepository(portable)
        mirrorPortableRepositoriesToKeyValue(portable)
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

    private suspend fun saveRepository(repository: ExtensionRepository) {
        val portable = portableRepository
        if (portable == null) {
            repositoryStore.put(repository)
            return
        }
        portable.upsertExtensionRepository(repository.toPortableRepository())
        mirrorPortableRepositoriesToKeyValue(portable)
    }

    private suspend fun deleteRepository(baseUrl: String) {
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

    private suspend fun ensureInstalledLoaded() {
        if (!loadedInstalled) {
            manager.loadInstalled()
            reviewedShuYueInstallerV2.rehydrateInstalled()
            loadedInstalled = true
        }
    }

    private suspend fun rebuildSnapshot(
        errorMessage: String?,
        reuseUnchangedSources: Boolean = false,
    ): Unit = withContext(Dispatchers.Default) {
        val repositories = repositoryStore.list()
        val selected = repositoryStore.selected()
        val installed = manager.installedPlugins().associateBy { it.manifest.id }
        val sourceDescriptors = descriptors.values.flatMap { descriptor ->
            descriptor.sources.map { source -> source.id to descriptor }
        }.toMap()
        val rebuiltSourceProjections = linkedMapOf<Long, BrowseSourceProjection>()
        val legacySources = manager.catalogueSources().map { source ->
            val reused = sourceProjections[source.id]
                ?.takeIf { reuseUnchangedSources && it.source === source }
                ?.value
            val projected = reused ?: run {
            val descriptor = sourceDescriptors[source.id]
            val credential = pluginStorage.getCredential(source.id)
            val filters = try {
                source.getFilterList().map { it.toBrowseFilter() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
            val pluginPreferences = (source as? ConfigurableSource)
                ?.let { configurableSource ->
                    try {
                        configurableSource.getPreferenceDefinitions()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
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
                supportsLogin = (source as? LoginSource)?.supportsLogin == true,
                credential = credential?.let { BrowseSourceCredential(it.username, it.password) },
                cookies = pluginStorage.getCookies(source.id).map { cookie ->
                    BrowseSourceCookie(
                        name = cookie.name,
                        value = cookie.value,
                        domain = cookie.domain,
                        path = cookie.path,
                        expiresAtEpochMillis = cookie.expiresAtEpochMillis,
                        secure = cookie.secure,
                        httpOnly = cookie.httpOnly,
                        hostOnly = cookie.hostOnly,
                    )
                },
                preferences = preferences,
                filters = filters,
            )
            }
            rebuiltSourceProjections[source.id] = BrowseSourceProjection(source, projected)
            projected
        }
        val occupiedSourceIds = legacySources.mapTo(hashSetOf(), BrowseSource::id)
        val nativeSources = manager.extensionDescriptorsV2()
            .flatMap { it.sources }
            .filterNot { descriptor -> legacySources.any { it.sourceKey == descriptor.sourceKey } }
            .map { descriptor ->
                BrowseSource(
                    id = v2UiRowId(descriptor.sourceKey, occupiedSourceIds),
                    name = descriptor.displayName,
                    language = descriptor.languageTag,
                    baseUrl = descriptor.baseUrl.orEmpty(),
                    enabled = keyValueStore.getString(sourceEnabledV2Key(descriptor.sourceKey))
                        ?.toBooleanStrictOrNull() ?: true,
                    supportsLatest = ExtensionCapability.LATEST in descriptor.capabilities,
                    supportsLogin = ExtensionCapability.LOGIN in descriptor.capabilities,
                    sourceKey = descriptor.sourceKey,
                )
            }
        val sources = (legacySources + nativeSources)
            .distinctBy(BrowseSource::identityKey)
            .sortedWith(compareBy<BrowseSource> { it.language }.thenBy { it.name.lowercase() })
        val reservedReviewedIds = ShuYueReviewedPluginCatalogV2.profiles
            .mapTo(hashSetOf()) { it.identity.packageId }
        val liveV2Packages = manager.extensionDescriptorsV2().associateBy { it.packageId }
        val extensions = descriptors.values.filterNot { it.id in reservedReviewedIds }.map { descriptor ->
            val local = installed[descriptor.id]
            val trusted = local?.let {
                if (PluginVerifier.isLegacyTrustValid(it)) {
                    true
                } else {
                    val versionCode = it.manifest.versionCode
                        ?: PluginVerifier.versionInt(it.manifest.version)
                    try {
                        trustStore.isTrusted(it.manifest.id, versionCode, it.metadata.installedSha256)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        false
                    }
                }
            } ?: false
            BrowseExtension(
                id = descriptor.id,
                name = descriptor.name,
                version = descriptor.version,
                language = descriptor.lang,
                iconUrl = descriptor.iconUrl,
                installed = descriptor.state != ExtensionState.AVAILABLE,
                updateAvailable = descriptor.state == ExtensionState.UPDATE_AVAILABLE,
                trusted = trusted,
                isNsfw = descriptor.nsfw,
                sourceIds = descriptor.sources.map { it.id },
            )
        } + reviewedShuYuePackages.values.map { reviewed ->
            val profile = requireNotNull(ShuYueReviewedPluginCatalogV2.profiles.singleOrNull { profile ->
                profile.identity.packageId == reviewed.packageId &&
                    profile.identity.version == reviewed.version &&
                    profile.identity.versionCode == reviewed.versionCode
            })
            val installation = reviewedShuYueInstallerV2.installed(reviewed.packageId)
            val live = liveV2Packages[reviewed.packageId]
            val exactInstalled = installation?.identity == profile.identity &&
                live == profile.descriptor
            val trusted = exactInstalled &&
                reviewedShuYueStoreV2.isTrusted(profile.identity) &&
                reviewedShuYueStoreV2.grantedPermissions(profile.identity) == profile.requiredPermissions
            BrowseExtension(
                id = reviewed.packageId,
                name = reviewed.name,
                version = reviewed.version,
                language = reviewed.languageTag,
                installed = installation != null,
                updateAvailable = installation != null && installation.identity != profile.identity,
                trusted = trusted,
                isNsfw = reviewed.isNsfw,
                reviewedShuYueV2 = true,
                description = reviewed.description,
            )
        }
        sourceProjections = rebuiltSourceProjections
        mutableState.value = BrowseSnapshot(
            repositories = repositories.map { it.toBrowseRepository() },
            selectedRepositoryId = selected,
            sources = sources,
            extensions = extensions,
            migrations = migrationProvider.candidates(),
            isRefreshing = false,
            errorMessage = errorMessage,
        )
    }

    private fun ExtensionRepository.toBrowseRepository(): BrowseRepository = BrowseRepository(
        id = baseUrl,
        url = baseUrl,
        name = name,
        website = website,
        signingFingerprint = signingKeyFingerprint.takeIf { it.isNotBlank() },
        official = baseUrl == normalizeRepositoryInput(defaultRepositoryUrl),
    )

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

    private suspend fun mapBrowseManga(source: CatalogueSource, mangas: List<SManga>): List<BrowseManga> {
        val mapped = ArrayList<BrowseManga>(mangas.size)
        for (manga in mangas) mapped += manga.toBrowseManga(source)
        return mapped
    }

    private suspend fun SManga.toBrowseManga(source: CatalogueSource): BrowseManga {
        val resolvedThumbnail = thumbnailUrl?.let { resolveSourceAsset(source.baseUrl, it) }
        val preparedThumbnail = resolvedThumbnail?.let { url ->
            runCatching {
                val metadata = PageRequestMetadata.parse(url)
                requestBuilder.build(
                    sourceId = source.id,
                    request = PluginHttpRequest(
                        method = "GET",
                        url = metadata.cleanUrl,
                        headers = metadata.headers,
                    ),
                    sourceHeaders = source.headers,
                    referer = source.headers.header("Referer") ?: source.baseUrl,
                ).transportRequest
            }.getOrNull()
        }
        return BrowseManga(
            sourceId = source.id,
            url = url,
            title = title,
            thumbnailUrl = preparedThumbnail?.url ?: resolvedThumbnail,
            thumbnailHeaders = preparedThumbnail?.headers.orEmpty(),
            author = author ?: artist,
        )
    }

    private fun normalizeRepositoryInput(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        val pathWithoutQuery = trimmed.substringBefore('?').substringBefore('#')
        val fileName = pathWithoutQuery.substringAfterLast('/')
        return if (fileName == "index.json" || fileName == "index.min.json" || fileName == "repo.json") {
            pathWithoutQuery.substringBeforeLast('/').trimEnd('/')
        } else {
            trimmed
        }
    }

    private fun resolveSourceAsset(baseUrl: String, value: String): String = when {
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("//") -> baseUrl.substringBefore(':') + ":" + value
        value.startsWith('/') -> {
            val scheme = baseUrl.substringBefore("://", "https")
            val authority = baseUrl.substringAfter("://", baseUrl).substringBefore('/')
            "$scheme://$authority$value"
        }
        else -> baseUrl.trimEnd('/') + "/" + value.trimStart('/')
    }

    private fun sourceEnabledKey(sourceId: Long): String = "source.$sourceId.enabled"

    private fun sourceEnabledV2Key(sourceKey: SourceKey): String =
        "source.v2.${Sha256.hex(sourceKey.canonicalId.encodeToByteArray())}.enabled"

    /** Allocates a process-local row id only; every extension operation still uses SourceKey. */
    private fun v2UiRowId(sourceKey: SourceKey, occupied: Set<Long>): Long {
        v2SourceRowIds[sourceKey]?.takeIf { it !in occupied }?.let { return it }
        var candidate = Long.MIN_VALUE
        val allocated = v2SourceRowIds.values.toHashSet()
        while (candidate in occupied || candidate in allocated) {
            check(candidate != Long.MAX_VALUE) { "Unable to allocate an extension v2 UI row id" }
            candidate++
        }
        v2SourceRowIds[sourceKey] = candidate
        return candidate
    }

    private suspend fun networkProxyPreference(sourceId: Long): BrowseSourcePreference {
        val stored = pluginStorage.getPreference(
            sourceId,
            ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE,
        )
        val value = SourceNetworkOverride.fromStored(stored, SourceNetworkOverride.OFF).name.lowercase()
        return BrowseSourcePreference(
            key = ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE,
            title = "Cloudflare Worker proxy",
            summary = "Off is the source default. Follow global uses the Advanced settings switch.",
            value = value,
            choices = listOf("Off", "Follow global", "On"),
            choiceValues = listOf("off", "global", "on"),
            kind = SourcePreferenceKind.Choice,
        )
    }

    private suspend fun SourcePreference.toBrowsePreference(sourceId: Long): BrowseSourcePreference = when (this) {
        is SourcePreference.TextField -> BrowseSourcePreference(
            key = key,
            title = title,
            summary = summary.takeIf { it.isNotBlank() },
            value = pluginStorage.getPreference(sourceId, key) ?: defaultValue,
            kind = SourcePreferenceKind.Text,
        )
        is SourcePreference.Toggle -> BrowseSourcePreference(
            key = key,
            title = title,
            summary = summary.takeIf { it.isNotBlank() },
            value = pluginStorage.getPreference(sourceId, key) ?: defaultValue.toString(),
            kind = SourcePreferenceKind.Toggle,
        )
        is SourcePreference.Select -> BrowseSourcePreference(
            key = key,
            title = title,
            value = pluginStorage.getPreference(sourceId, key) ?: defaultValue,
            choices = entries,
            choiceValues = entryValues.takeIf { it.size == entries.size } ?: entries,
            kind = SourcePreferenceKind.Choice,
        )
        is SourcePreference.MultiSelect -> BrowseSourcePreference(
            key = key,
            title = title,
            value = pluginStorage.getPreference(sourceId, key) ?: defaultValues.sorted().joinToString(","),
            choices = entries,
            choiceValues = entryValues.takeIf { it.size == entries.size } ?: entries,
            kind = SourcePreferenceKind.MultiChoice,
        )
    }

    public companion object {
        private const val PORTABLE_REPOSITORY_MIGRATION_KEY: String =
            "plugin.repositories.portable-migration.v1"

        private const val LEGACY_BUNDLED_REPOSITORY_URL: String =
            "https://raw.githubusercontent.com/aluo96078/shinsou_plugin/master"
        private const val LEGACY_BUNDLED_REPOSITORY_REMOVAL_KEY: String =
            "plugin.repositories.remove-bundled-seed.v1"
    }
}

public data class PluginReaderChapterReference(
    val sourceId: Long,
    val chapter: SChapter,
)

public fun interface PluginReaderChapterResolver {
    public suspend fun resolve(mangaId: Long, chapterId: Long): PluginReaderChapterReference?
}

/** Builds reader requests through the same request builder used by the JS bridge and downloader. */
public class PluginContentAdapter(
    private val manager: PluginManager,
    private val chapterResolver: PluginReaderChapterResolver,
    private val requestBuilder: PluginRequestBuilder,
    private val delegate: ContentCallbacks = ContentCallbacks.None,
) : ContentCallbacks {
    override suspend fun refreshLibrary(mangaIds: Set<Long>) = delegate.refreshLibrary(mangaIds)
    override suspend fun refreshManga(mangaId: Long) = delegate.refreshManga(mangaId)
    override suspend fun resolveMangaOriginalUrl(mangaId: Long): String? =
        delegate.resolveMangaOriginalUrl(mangaId)
    override suspend fun resolveChapterOriginalUrl(mangaId: Long, chapterId: Long): String? =
        delegate.resolveChapterOriginalUrl(mangaId, chapterId)
    override suspend fun enqueueDownload(mangaId: Long, chapterId: Long) =
        delegate.enqueueDownload(mangaId, chapterId)
    override suspend fun retryDownload(itemId: String) = delegate.retryDownload(itemId)
    override suspend fun removeDownload(itemId: String) = delegate.removeDownload(itemId)
    override suspend fun reorderDownloads(orderedIds: List<String>) = delegate.reorderDownloads(orderedIds)
    override suspend fun clearCompletedDownloads() = delegate.clearCompletedDownloads()
    override suspend fun pauseDownloads(paused: Boolean) = delegate.pauseDownloads(paused)

    override suspend fun loadReaderChapter(mangaId: Long, chapterId: Long): ReaderChapter {
        val reference = chapterResolver.resolve(mangaId, chapterId)
            ?: return delegate.loadReaderChapter(mangaId, chapterId)
        val source = manager.source(reference.sourceId)
            ?: throw IllegalStateException("Reader source '${reference.sourceId}' is not loaded")
        val referer = source.headers.header("Referer") ?: reference.chapter.url.takeIf { it.isNotBlank() }
        val pages = source.getPageList(reference.chapter).mapIndexed { fallbackIndex, page ->
            val metadata = PageRequestMetadata.parse(page.imageUrl?.takeIf { it.isNotBlank() } ?: page.url)
            val built = requestBuilder.build(
                sourceId = source.id,
                request = PluginHttpRequest("GET", metadata.cleanUrl, headers = metadata.headers),
                sourceHeaders = source.headers,
                referer = referer,
            )
            ReaderPage(
                index = page.index.takeIf { it >= 0 } ?: fallbackIndex,
                imageUrl = built.transportRequest.url,
                headers = built.transportRequest.headers,
                imageTransform = metadata.imageTransform(source.id),
            )
        }
        return ReaderChapter(pages = pages, referer = referer, sourceHeaders = source.headers)
    }
}

private fun Map<String, String>.header(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
