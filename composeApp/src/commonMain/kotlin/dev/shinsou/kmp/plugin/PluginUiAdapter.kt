package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.content.ContentKind
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
import dev.shinsou.kmp.plugin.v2.exactExtensionLibraryRecoveryMatchV2
import dev.shinsou.kmp.plugin.v2.HostExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.LegacyLoginCredentialsResolverV2
import dev.shinsou.kmp.plugin.v2.LegacyLoginCredentialsV2
import dev.shinsou.kmp.plugin.v2.LoginCredentialsV2
import dev.shinsou.kmp.plugin.v2.PagedResultV2
import dev.shinsou.kmp.plugin.v2.PreferenceV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import dev.shinsou.kmp.plugin.v2.UnitContentResultV2
import dev.shinsou.kmp.plugin.shuyue.KeyValueShuYueReviewedStoreV2
import dev.shinsou.kmp.plugin.shuyue.BuiltInShuYueExecutionScopesV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueQuarantineReviewV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedInstallApprovalV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedPluginCatalogV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedPluginProfileV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedRepositoryCoordinatorV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedRepositoryPackageV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryIndexLoader
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryLocation
import dev.shinsou.kmp.plugin.shuyue.ShuYueScriptCandidateV2
import dev.shinsou.kmp.plugin.events.ExactSourceRefreshInvalidations
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
import dev.shinsou.kmp.ui.SourceLoginFailureStage
import dev.shinsou.kmp.ui.SourceLoginResult
import dev.shinsou.kmp.ui.SourceSecrets
import dev.shinsou.kmp.ui.SourceSecretsResult
import dev.shinsou.kmp.ui.SourceCookie as BrowseSourceCookie
import dev.shinsou.kmp.ui.SourceCredential as BrowseSourceCredential
import dev.shinsou.kmp.ui.SourcePreference as BrowseSourcePreference
import dev.shinsou.kmp.ui.SourcePreferenceKind
import dev.shinsou.kmp.ui.SourceWebChallengeRequest
import dev.shinsou.kmp.ui.TypedReaderContentSession
import dev.shinsou.kmp.local.encodeTypedLocalChapterUrl
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException

/** Startup/refresh discovery must never leave the browse surface behind a dead network host. */
private const val BROWSE_REMOTE_REFRESH_TIMEOUT_MILLIS: Long = 8_000L

/** Cooperative bound layered over the desktop protected-store caller timeout. */
private const val SOURCE_SECRET_ACCESS_TIMEOUT_MILLIS: Long = 12_000L

/** Stable v2 option key used by account-owned collection views (ShuYue bookcase). */
internal const val DEFAULT_FAVORITE_BROWSE_OPTION_KEY: String = "option"

/** Internal wrapper carrying only a stable stage to the UI boundary; its cause is never rendered. */
internal class SourceLoginStageException(
    val stage: SourceLoginFailureStage,
    cause: Throwable,
) : RuntimeException(cause)

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
    private val logoutRequestCoordinator: PluginLogoutRequestCoordinator? = null,
    private val exactSourceRefreshInvalidations: ExactSourceRefreshInvalidations = ExactSourceRefreshInvalidations(),
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
    override val sourceRefreshInvalidations: StateFlow<Map<SourceKey, Long>> =
        exactSourceRefreshInvalidations.generations
    override val logoutConfirmations: StateFlow<List<PluginLogoutConfirmation>> =
        logoutRequestCoordinator?.requests
            ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())

    override fun dismissPluginLogout(eventId: String) {
        logoutRequestCoordinator?.dismiss(eventId)
    }

    override fun dismissAllPluginLogouts() {
        logoutRequestCoordinator?.clear()
    }

    override suspend fun setPluginUiAvailable(available: Boolean) {
        manager.setPluginUiAvailable(available)
        if (!available) {
            loginRequestCoordinator.loginRequests.value
                .mapNotNull { it.eventId }
                .forEach(loginRequestCoordinator::dismissEvent)
            logoutRequestCoordinator?.clear()
        }
    }

    override suspend fun confirmPluginLogout(eventId: String): Boolean = withContext(Dispatchers.Default) {
        val request = logoutRequestCoordinator?.take(eventId) ?: return@withContext false
        val sourceKey = request.target.sourceKey
        val storageId = exactSessionStorageId(request) ?: return@withContext false
        val exact = manager.exactExtensionSource(request.target)
        val legacy = manager.exactLegacySource(request.target) as? LoginSource
        if (exact == null && legacy == null) return@withContext false
        // Remote logout is bounded. Host policy is fail-closed: timeout/failure preserves the
        // local session namespace so the user can retry and no unrelated credentials are erased.
        val remoteSucceeded = withTimeoutOrNull(10_000) {
            runCatching {
                if (exact != null) exact.logout() else legacy?.logout()
            }.isSuccess
        } == true
        if (!remoteSucceeded) return@withContext false
        // Credentials and cookies are the complete host-owned session namespace for this exact
        // execution/storage scope. Preferences remain source configuration, not session state.
        val ownerKey = ExactPluginSessionOwnership.ownerKey(storageId)
        if (!ExactPluginSessionOwnership.authorizesCleanup(keyValueStore.getString(ownerKey), request.target)) {
            return@withContext false
        }
        pluginStorage.clearCredential(storageId)
        pluginStorage.clearCookies(storageId)
        keyValueStore.remove(ownerKey)
        true
    }

    override suspend fun saveSourceEventCredentials(
        eventId: String,
        sourceId: Long,
        username: String,
        password: String,
    ): Boolean = saveSourceEventCredentialsResult(eventId, sourceId, username, password).succeeded

    override suspend fun saveSourceEventCredentialsResult(
        eventId: String,
        sourceId: Long,
        username: String,
        password: String,
    ): SourceLoginResult = try {
        val request = loginRequestCoordinator.event(eventId) ?: return SourceLoginResult(false)
        val target = request.exactTarget ?: return SourceLoginResult(false)
        if (request.sourceId != sourceId) return SourceLoginResult(false)
        val result = saveSourceCredentialsInternal(sourceId, username, password, dismissLegacy = false)
        if (result.succeeded) {
            keyValueStore.putString(
                ExactPluginSessionOwnership.ownerKey(sourceId),
                ExactPluginSessionOwnership.targetKey(target),
            )
            exactSourceRefreshInvalidations.invalidate(target)
            loginRequestCoordinator.dismissEvent(eventId)
        }
        return result
    } catch (failure: SourceLoginStageException) {
        SourceLoginResult(false, failureStage = failure.stage)
    }

    private suspend fun exactSessionStorageId(request: PluginLogoutConfirmation): Long? {
        val target = request.target
        val sourceKey = target.sourceKey
        val artifact = target.artifactIdentity
        val installed = manager.installedPlugins().singleOrNull { stored ->
            stored.manifest.id == artifact.packageId &&
                stored.manifest.version == artifact.version &&
                (stored.manifest.versionCode ?: PluginVerifier.versionInt(stored.manifest.version)) == artifact.versionCode &&
                stored.metadata.installedSha256 == artifact.sha256 &&
                stored.manifest.sources.orEmpty().any { it.id == sourceKey.legacyLongId }
        }
        if (installed != null && manager.extensionSourceV2(sourceKey) != null) {
            return sourceKey.legacyLongId
        }
        val reviewedIdentity = runCatching {
            dev.shinsou.kmp.plugin.shuyue.ShuYueArtifactIdentityV2(
                artifact.packageId, artifact.version, artifact.versionCode, artifact.sha256,
            )
        }.getOrNull() ?: return null
        val profile = ShuYueReviewedPluginCatalogV2.profiles.singleOrNull {
            it.identity == reviewedIdentity &&
                sourceKey.packageId == it.identity.packageId && sourceKey.sourceId in it.sourceIds
        } ?: return null
        return runCatching { BuiltInShuYueExecutionScopesV2.resolve(profile.identity, sourceKey) }.getOrNull()
    }
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(BrowseSnapshot())
    private val reviewedShuYueStoreV2 = KeyValueShuYueReviewedStoreV2(keyValueStore)
    private val reviewedShuYueInstallerV2 = manager.reviewedShuYueInstallCoordinatorV2(
        quarantineStore = reviewedShuYueStoreV2,
        approvalStore = reviewedShuYueStoreV2,
        installationStore = reviewedShuYueStoreV2,
        credentialsResolver = LegacyLoginCredentialsResolverV2(::resolveReviewedCredentials),
    )
    /**
     * ShuYue repositories use a different, reviewed `index.json` contract than legacy extension
     * repositories. Keep one coordinator per URL so adding a ShuYue repository cannot accidentally
     * feed its entries to the legacy manager/parser.
     */
    private val reviewedShuYueRepositoriesV2 = linkedMapOf<String, ShuYueReviewedRepositoryCoordinatorV2>()
    private var loadedInstalled = false
    private var descriptors: Map<String, ExtensionDescriptor> = emptyMap()
    private var reviewedShuYuePackages: Map<String, ShuYueReviewedRepositoryPackageV2> = emptyMap()
    private var reviewedShuYuePackageOwners: Map<String, ShuYueReviewedRepositoryCoordinatorV2> = emptyMap()
    private var reviewedShuYueRepositoryRows: List<BrowseRepository> = emptyList()
    private var sourceProjections: Map<Long, BrowseSourceProjection> = emptyMap()
    private val v2SourceRowIds = linkedMapOf<SourceKey, Long>()
    private val v2SourceKeysByRowId = linkedMapOf<Long, SourceKey>()
    private val v2SourceKeysByStorageId = linkedMapOf<Long, SourceKey>()

    override val state: StateFlow<BrowseSnapshot> = mutableState
    override val loginRequests: StateFlow<List<SourceLoginRequest>> = loginRequestCoordinator.loginRequests

    override fun dismissSourceLoginRequest(sourceId: Long) {
        loginRequestCoordinator.dismiss(sourceId)
    }

    override fun dismissSourceLoginEvent(eventId: String) {
        loginRequestCoordinator.dismissEvent(eventId)
    }

    override suspend fun refresh(): Unit = operationMutex.withLock {
        refreshLocked()
    }

    override suspend fun setSourceEnabled(sourceId: Long, enabled: Boolean): Unit = operationMutex.withLock {
        manager.setEventSourceEnabled(sourceId, enabled)
        keyValueStore.putString(sourceEnabledKey(sourceId), enabled.toString())
        rebuildSnapshot(errorMessage = null)
    }

    override suspend fun setSourceEnabledV2(sourceKey: SourceKey, enabled: Boolean): Unit =
        operationMutex.withLock {
            check(manager.extensionSourceV2(sourceKey) != null) {
                "Unknown extension v2 source: ${sourceKey.canonicalId}"
            }
            manager.setEventSourceEnabled(sourceKey, enabled)
            keyValueStore.putString(sourceEnabledV2Key(sourceKey), enabled.toString())
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun addRepository(url: String): BrowseRepository? = operationMutex.withLock {
        reconcilePortableRepositories()
        val input = url.trim()
        // Classification is response/schema based. A URL ending in index.json is valid for both
        // historical contracts and must never force the ShuYue parser.
        tryAddReviewedShuYueRepository(input)?.let { return@withLock it }
        val repository = repositoryClient.fetchRepository(normalizeRepositoryInput(input))
        val hadRepositories = repositoryStore.list().isNotEmpty()
        saveRepository(repository)
        if (!hadRepositories || repositoryStore.selected() == null) repositoryStore.select(repository.baseUrl)
        refreshLocked()
        repository.toBrowseRepository()
    }

    override suspend fun removeRepository(repositoryId: String): Unit = operationMutex.withLock {
        reconcilePortableRepositories()
        val reviewedLocation = when {
            repositoryId.startsWith(SHUYUE_REPOSITORY_ID_PREFIX) ->
                repositoryId.removePrefix(SHUYUE_REPOSITORY_ID_PREFIX)
            reviewedShuYueRepositoryRows.any { it.url == repositoryId } -> repositoryId
            else -> null
        }
        if (reviewedLocation != null) {
            val location = reviewedLocation
            val remaining = readReviewedShuYueRepositoryUrls()
                .filterNot { it == location }
            writeReviewedShuYueRepositoryUrls(remaining)
            reviewedShuYueRepositoriesV2.remove(location)
            // A unified row has a reviewed index URL and a legacy base URL. Removing the visible
            // ShuYue row must remove both halves; otherwise the manga half silently survives and
            // is restored again on the next refresh.
            val legacyBase = normalizeRepositoryInput(location)
            if (repositoryStore.list().any { it.baseUrl == legacyBase }) deleteRepository(legacyBase)
            clearUnifiedRepositoryMigrationAttempt(location)
            refreshLocked(addDefaultRepository = false)
            return@withLock
        }
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
        if (repositoryId?.startsWith(SHUYUE_REPOSITORY_ID_PREFIX) == true) {
            val location = repositoryId.removePrefix(SHUYUE_REPOSITORY_ID_PREFIX)
            require(reviewedShuYueRepositoryRows.any { it.id == repositoryId }) {
                "Unknown ShuYue repository: $repositoryId"
            }
            // A unified URL has one visible row but two readers. Selecting it also selects the
            // normalized Shinsou half. A plain ShuYue row clears an unrelated Shinsou selection.
            val unifiedBase = normalizeRepositoryInput(location)
            repositoryStore.select(unifiedBase.takeIf { base -> repositories.any { it.baseUrl == base } })
            refreshLocked(addDefaultRepository = false)
            return@withLock
        }
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
        val result = manager.withUserInteractionContext(sourceId) {
            if (query.isBlank() && filters == null) {
                source.getPopularManga(pluginPage)
            } else {
                source.getSearchManga(
                    pluginPage,
                    query,
                    filters?.map { it.toPluginFilter() } ?: source.getFilterList(),
                )
            }
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
        val result = manager.withUserInteractionContext(sourceId) {
            source.getLatestUpdates(page.coerceAtLeast(1) - 1)
        }
        BrowsePage(
            items = mapBrowseManga(source, result.mangas),
            hasNextPage = result.hasNextPage,
        )
    }

    override suspend fun browseSourceFavorites(sourceId: Long, page: Int): BrowsePage =
        withContext(Dispatchers.Default) {
            val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
            val enabled = keyValueStore.getString(sourceEnabledKey(sourceId))?.toBooleanStrictOrNull() ?: true
            check(enabled) { "Source '$sourceId' is disabled" }
            check(source.supportsFavorites) { "Source '$sourceId' does not support favorites" }
            val result = manager.withUserInteractionContext(sourceId) {
                source.getFavoriteManga(page.coerceAtLeast(1) - 1)
            }
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
        manager.withUserInteractionContext(sourceKey) { extensionGatewayV2.browse(sourceKey, options, page) }
    }

    override suspend fun searchSourceV2(
        sourceKey: SourceKey,
        query: String,
        page: Int,
        options: BrowseOptionsV2,
    ): PagedResultV2<RemotePublicationV2> = withContext(Dispatchers.Default) {
        manager.withUserInteractionContext(sourceKey) {
            extensionGatewayV2.search(sourceKey, query, page, options)
        }
    }

    override suspend fun latestSourceV2(
        sourceKey: SourceKey,
        page: Int,
    ): PagedResultV2<RemotePublicationV2> = withContext(Dispatchers.Default) {
        manager.withUserInteractionContext(sourceKey) { extensionGatewayV2.latest(sourceKey, page) }
    }

    override suspend fun extensionDetailsV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
    ): RemotePublicationV2 = withContext(Dispatchers.Default) {
        manager.withUserInteractionContext(sourceKey) {
            manager.withVisibleEventContext(sourceKey, remotePublicationId) {
                extensionGatewayV2.details(sourceKey, remotePublicationId)
            }
        }
    }

    override fun extensionLibraryBindingV2(
        publicationKey: dev.shinsou.kmp.domain.model.PublicationKey,
    ): dev.shinsou.kmp.plugin.v2.ExtensionLibraryBindingV2? =
        extensionContentConsumerV2?.extensionLibraryBinding(publicationKey)

    override suspend fun recoverExtensionLibraryBindingV2(
        publicationKey: dev.shinsou.kmp.domain.model.PublicationKey,
        title: String,
    ): dev.shinsou.kmp.plugin.v2.ExtensionLibraryBindingV2? = withContext(Dispatchers.Default) {
        if (title.isBlank()) return@withContext null
        val descriptors = manager.extensionDescriptorsV2()
            .flatMap { descriptor -> descriptor.sources }
            .filter { descriptor -> ExtensionCapability.SEARCH in descriptor.capabilities }
            // UUID-only local favorites were emitted by the beta app path only for reviewed
            // ShuYue extensions. Keep repair bounded to that package family so unrelated future
            // extensions are never queried for a legacy row they could not have created.
            .filter { descriptor -> descriptor.sourceKey.packageId in LEGACY_LOCAL_LIBRARY_PACKAGE_IDS }
        val candidates = buildList {
            for (descriptor in descriptors) {
                val enabled = keyValueStore.getString(sourceEnabledV2Key(descriptor.sourceKey))
                    ?.toBooleanStrictOrNull() ?: true
                if (enabled) add(descriptor)
            }
        }.sortedBy { descriptor -> descriptor.sourceKey.canonicalId }
        var recovered: dev.shinsou.kmp.plugin.v2.ExtensionLibraryBindingV2? = null
        for (descriptor in candidates) {
            val page = runCatching {
                manager.withUserInteractionContext(descriptor.sourceKey) {
                    extensionGatewayV2.search(descriptor.sourceKey, title, page = 0)
                }
            }.getOrNull() ?: continue
            exactExtensionLibraryRecoveryMatchV2(
                publicationKey = publicationKey,
                sourceKey = descriptor.sourceKey,
                publications = page.items,
            )?.let { match ->
                check(recovered == null || recovered == match) {
                    "Legacy extension library identity resolves to multiple sources"
                }
                recovered = match
            }
        }
        recovered
    }

    override suspend fun favoriteExtensionPublicationV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        favorite: Boolean,
    ): Unit = withContext(Dispatchers.Default) {
        manager.withUserInteractionContext(sourceKey) {
            // A favorite mutation is still a visible publication invocation.  Keeping the
            // publication identity bound here is important for plugins that emit an
            // ACTIVE_CONTEXT refresh (and also keeps the event context consistent with details,
            // units, and content requests).
            manager.withVisibleEventContext(sourceKey, remotePublicationId) {
                extensionGatewayV2.favorite(sourceKey, remotePublicationId, favorite)
            }
        }
    }

    override suspend fun extensionUnitsV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int,
    ): PagedResultV2<RemoteUnitV2> = withContext(Dispatchers.Default) {
        manager.withUserInteractionContext(sourceKey) {
            manager.withVisibleEventContext(sourceKey, remotePublicationId) {
                extensionGatewayV2.units(sourceKey, remotePublicationId, page)
            }
        }
    }

    override suspend fun extensionContentV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitId: String,
    ): UnitContentResultV2 = withContext(Dispatchers.Default) {
        manager.withUserInteractionContext(sourceKey) {
            manager.withVisibleEventContext(sourceKey, remotePublicationId, remoteUnitId) {
                extensionGatewayV2.content(sourceKey, remotePublicationId, remoteUnitId)
            }
        }
    }

    override suspend fun extensionPublicationPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int,
    ): ExtensionPublicationPageV2 = withContext(Dispatchers.Default) {
        requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.let { consumer ->
            manager.withUserInteractionContext(sourceKey) {
                consumer.publicationPage(sourceKey, remotePublicationId, page)
            }
        }
    }

    override suspend fun extensionPublicationUnitsPageV2(
        sourceKey: SourceKey,
        remotePublicationId: String,
        publication: RemotePublicationV2,
        page: Int,
    ): ExtensionPublicationPageV2 = withContext(Dispatchers.Default) {
        requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.let { consumer ->
            manager.withUserInteractionContext(sourceKey) {
                consumer.publicationUnitsPage(sourceKey, remotePublicationId, publication, page)
            }
        }
    }

    override suspend fun materializeExtensionContentV2(
        selection: ExtensionUnitSelectionV2,
        representationId: String?,
    ): ExtensionContentMaterializationV2 = withContext(Dispatchers.Default) {
        requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.let { consumer ->
            manager.withUserInteractionContext(selection.sourceKey) {
                consumer.materialize(selection, representationId)
            }
        }
    }

    override suspend fun openMaterializedExtensionContentV2(
        materialization: ExtensionContentMaterializationV2,
    ): TypedReaderContentSession = withContext(Dispatchers.Default) {
        val readable = requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.open(materialization)
        val localChapterUrl = encodeTypedLocalChapterUrl(
            publicationKey = materialization.publicationKey,
            acquisitionId = materialization.acquisitionId,
            unitKey = materialization.unitKey,
        )
        val localSnapshot = portableRepository?.currentSnapshot
        val localChapter = localSnapshot?.chapters?.firstOrNull { it.url == localChapterUrl }
        val localLocator = localChapter?.let { chapter ->
            localSnapshot.histories.firstOrNull { it.chapterId == chapter.id }?.lastLocator
        }?.takeIf { locator -> readable.content.navigation.indexOf(locator) != null }
        TypedReaderContentSession(
            content = readable.content.copy(
                initialLocator = localLocator ?: readable.content.initialLocator,
            ),
            canonicalText = readable.canonicalText,
            access = readable.access,
            initialVisualPageIndex = localChapter?.lastPageRead,
            initialVisualPageCount = localChapter?.let { chapter ->
                localSnapshot.histories.firstOrNull { it.chapterId == chapter.id }?.lastPageCount
            },
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
        val repository = reviewedShuYuePackageOwners[packageId]
            ?: refreshReviewedShuYueRepositories().let { reviewedShuYuePackageOwners[packageId] }
        requireNotNull(repository) {
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

            is RepositoryIndex.Combined -> {
                val entry = index.plugins.firstOrNull { it.id == extensionId }
                    ?: throw IllegalStateException("Extension '$extensionId' is no longer in the repository")
                manager.install(repository, entry)
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

    override suspend fun pendingPluginEventGrantReview(extensionId: String) =
        manager.pendingEventGrantReview(extensionId)

    override suspend fun approvePluginEventGrantReview(
        extensionId: String,
        permissions: Set<dev.shinsou.kmp.plugin.events.PluginHostPermission>,
    ) {
        manager.approveEventGrantReview(extensionId, permissions)
        operationMutex.withLock { rebuildSnapshot(errorMessage = null) }
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
            val v2Scope = requireV2SourceScope(sourceId)
            if (v2Scope != null) {
                values.forEach { (key, value) -> pluginStorage.setPreference(v2Scope.second, key, value) }
            } else {
                check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
                values.forEach { (key, value) -> pluginStorage.setPreference(sourceId, key, value) }
            }
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun saveSourceCredentials(
        sourceId: Long,
        username: String,
        password: String,
    ): Boolean = saveSourceCredentialsResult(sourceId, username, password).succeeded

    override suspend fun loadSourceSecrets(sourceId: Long): SourceSecretsResult = try {
        val v2Scope = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
            requireV2SourceScope(sourceId)
        }
        val storageId = v2Scope?.second ?: sourceId
        val supportsLogin = if (v2Scope != null) {
            val source = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                requireNotNull(manager.extensionSourceV2(v2Scope.first)) {
                    "Unknown extension v2 source: ${v2Scope.first.canonicalId}"
                }
            }
            ExtensionCapability.LOGIN in source.descriptor.capabilities
        } else {
            val source = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
            }
            (source as? LoginSource)?.supportsLogin == true
        }
        val credential = if (supportsLogin) {
            secureStorageStage(SourceLoginFailureStage.READ_CREDENTIALS) {
                pluginStorage.getCredential(storageId)
            }
        } else {
            null
        }
        val cookies = secureStorageStage(SourceLoginFailureStage.READ_CREDENTIALS) {
            pluginStorage.getCookies(storageId)
        }
        SourceSecretsResult(
            secrets = SourceSecrets(
                credential = credential?.let { BrowseSourceCredential(it.username, it.password) },
                cookies = cookies.map(::toBrowseCookie),
            ),
        )
    } catch (failure: SourceLoginStageException) {
        SourceSecretsResult(failureStage = failure.stage)
    }

    override suspend fun saveSourceCredentialsResult(
        sourceId: Long,
        username: String,
        password: String,
    ): SourceLoginResult = try {
        saveSourceCredentialsInternal(sourceId, username, password, dismissLegacy = true)
    } catch (failure: SourceLoginStageException) {
        SourceLoginResult(false, failureStage = failure.stage)
    }

    private suspend fun saveSourceCredentialsInternal(
        sourceId: Long,
        username: String,
        password: String,
        dismissLegacy: Boolean,
    ): SourceLoginResult = operationMutex.withLock {
        require(username.isNotBlank()) { "Username cannot be blank" }
        val v2Scope = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
            requireV2SourceScope(sourceId)
        }
        val result = if (v2Scope != null) {
            val (sourceKey, storageId) = v2Scope
            val source = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                requireNotNull(manager.extensionSourceV2(sourceKey)) {
                    "Unknown extension v2 source: ${sourceKey.canonicalId}"
                }
            }
            loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                check(ExtensionCapability.LOGIN in source.descriptor.capabilities) {
                    "Source does not implement the login contract: ${sourceKey.canonicalId}"
                }
            }
            val previous = secureStorageStage(SourceLoginFailureStage.READ_CREDENTIALS) {
                pluginStorage.getCredential(storageId)
            }
            secureStorageStage(SourceLoginFailureStage.WRITE_CREDENTIALS) {
                pluginStorage.setCredential(storageId, PluginCredential(username, password))
            }
            val loginResult = try {
                loginStage(SourceLoginFailureStage.AUTHENTICATE) {
                    manager.withUserInteractionContext(sourceKey) {
                        source.login(v2LoginCredentials(storageId))
                    }
                }
            } catch (failure: SourceLoginStageException) {
                restoreCredential(storageId, previous, failure)
                throw failure
            }
            if (!loginResult.loggedIn) {
                restoreCredential(storageId, previous)
            }
            SourceLoginResult(loginResult.loggedIn, loginResult.errorMessage)
        } else {
            val loginSource = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                val source = manager.source(sourceId)
                    ?: throw IllegalArgumentException("Unknown source: $sourceId")
                (source as? LoginSource)?.takeIf { it.supportsLogin }
                    ?: error("Source does not implement the login contract: $sourceId")
            }
            val loginResult = loginStage(SourceLoginFailureStage.AUTHENTICATE) {
                manager.withUserInteractionContext(sourceId) {
                    loginSource.loginResult(username, password)
                }
            }
            SourceLoginResult(loginResult.loggedIn, loginResult.errorMessage?.boundedLoginError())
        }
        if (result.succeeded) {
            val storageId = v2Scope?.second ?: sourceId
            // v2 login needs the credential in storage before the call; v1 stores it only after
            // success. Both paths converge here so the settings projection stays consistent.
            if (v2Scope == null) secureStorageStage(SourceLoginFailureStage.WRITE_CREDENTIALS) {
                pluginStorage.setCredential(storageId, PluginCredential(username, password))
            }
            if (dismissLegacy) loginRequestCoordinator.dismiss(sourceId)
        }
        loginStage(SourceLoginFailureStage.REFRESH_SOURCE_STATE) {
            rebuildSnapshot(errorMessage = null)
        }
        result
    }

    private suspend fun restoreCredential(
        storageId: Long,
        previous: PluginCredential?,
        originalFailure: SourceLoginStageException? = null,
    ) {
        try {
            secureStorageStage(SourceLoginFailureStage.RESTORE_CREDENTIALS) {
                if (previous == null) pluginStorage.clearCredential(storageId)
                else pluginStorage.setCredential(storageId, previous)
            }
        } catch (restoreFailure: SourceLoginStageException) {
            originalFailure?.let(restoreFailure::addSuppressed)
            throw restoreFailure
        }
    }

    private suspend inline fun <T> loginStage(
        stage: SourceLoginFailureStage,
        crossinline block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SourceLoginStageException) {
        throw failure
    } catch (failure: Throwable) {
        throw SourceLoginStageException(stage, failure)
    }

    private suspend inline fun <T> secureStorageStage(
        stage: SourceLoginFailureStage,
        crossinline block: suspend () -> T,
    ): T = try {
        withTimeout(SOURCE_SECRET_ACCESS_TIMEOUT_MILLIS) { block() }
    } catch (timeout: TimeoutCancellationException) {
        throw SourceLoginStageException(stage, timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SourceLoginStageException) {
        throw failure
    } catch (failure: Throwable) {
        throw SourceLoginStageException(stage, failure)
    }

    private fun String.boundedLoginError(): String? =
        filterNot(Char::isISOControl).trim().take(512).takeIf(String::isNotBlank)

    override suspend fun logoutSource(sourceId: Long): Unit = operationMutex.withLock {
        val v2Scope = requireV2SourceScope(sourceId)
        val result = runCatching {
            if (v2Scope != null) {
                val source = requireNotNull(manager.extensionSourceV2(v2Scope.first)) {
                    "Unknown extension v2 source: ${v2Scope.first.canonicalId}"
                }
                if (ExtensionCapability.LOGIN in source.descriptor.capabilities) source.logout()
            } else {
                val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
                (source as? LoginSource)?.takeIf { it.supportsLogin }?.logout()
            }
        }
        pluginStorage.clearCredential(v2Scope?.second ?: sourceId)
        rebuildSnapshot(errorMessage = null)
        result.getOrThrow()
    }

    override suspend fun setSourceCookie(sourceId: Long, cookie: BrowseSourceCookie): Unit =
        operationMutex.withLock {
            val storageId = requireV2SourceScope(sourceId)?.second ?: run {
                check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
                sourceId
            }
            require(cookie.name.isNotBlank()) { "Cookie name cannot be blank" }
            require(cookie.domain.isNotBlank()) { "Cookie domain cannot be blank" }
            pluginStorage.setCookie(
                storageId,
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

    override suspend fun importSourceWebChallengeSession(
        sourceId: Long,
        cookies: List<BrowseSourceCookie>,
        userAgent: String,
    ): Unit = operationMutex.withLock {
        val storageId = requireV2SourceScope(sourceId)?.second ?: run {
            check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
            sourceId
        }
        val safeUserAgent = requireNotNull(normalizePluginUserAgent(userAgent)) {
            "Invalid browser User-Agent"
        }
        require(cookies.isNotEmpty()) { "No browser cookies were supplied" }
        cookies.forEach { cookie ->
            require(cookie.name.isNotBlank()) { "Cookie name cannot be blank" }
            require(cookie.domain.isNotBlank()) { "Cookie domain cannot be blank" }
            pluginStorage.setCookie(
                storageId,
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
        }
        pluginStorage.setWebChallengeUserAgent(storageId, safeUserAgent)
        rebuildSnapshot(errorMessage = null)
    }

    override suspend fun deleteSourceCookie(sourceId: Long, name: String, domain: String): Unit =
        operationMutex.withLock {
            val storageId = requireV2SourceScope(sourceId)?.second ?: run {
                check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
                sourceId
            }
            pluginStorage.deleteCookie(storageId, name, domain)
            rebuildSnapshot(errorMessage = null)
        }

    override suspend fun clearSourceCookies(sourceId: Long): Unit = operationMutex.withLock {
        val storageId = requireV2SourceScope(sourceId)?.second ?: run {
            check(manager.source(sourceId) != null) { "Unknown source: $sourceId" }
            sourceId
        }
        pluginStorage.clearCookies(storageId)
        rebuildSnapshot(errorMessage = null)
    }

    override suspend fun sourceWebChallenge(
        sourceId: Long,
        username: String?,
        password: String?,
    ): SourceWebChallengeRequest? {
        val v2Scope = requireV2SourceScope(sourceId)
        val storageId = v2Scope?.second ?: sourceId
        val sourceName: String
        val baseUrl: String
        val challengeUrl: String
        val sourceHeaders: Map<String, String>
        val referer: String?
        val supportsLogin: Boolean
        if (v2Scope != null) {
            val source = requireNotNull(manager.extensionSourceV2(v2Scope.first)) {
                "Unknown extension v2 source: ${v2Scope.first.canonicalId}"
            }
            sourceName = source.descriptor.displayName
            baseUrl = source.descriptor.baseUrl.orEmpty()
            challengeUrl = source.webChallengeUrl() ?: baseUrl
            // A previously imported browser-bound UA stays paired with its cookie jar. On macOS
            // the native helper intentionally starts without a custom UA and captures WebKit's
            // actual value; other platforms can still seed their source-provided browser hint.
            sourceHeaders = pluginStorage.getWebChallengeUserAgent(storageId)
                ?.let(::normalizePluginUserAgent)
                ?.let { mapOf("User-Agent" to it) }
                ?: source.webChallengeUserAgent()
                ?.let { mapOf("User-Agent" to it) }
                .orEmpty()
            referer = baseUrl
            supportsLogin = ExtensionCapability.LOGIN in source.descriptor.capabilities
        } else {
            val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
            sourceName = source.name
            baseUrl = source.baseUrl
            challengeUrl = source.webChallengeUrl ?: baseUrl
            sourceHeaders = source.headers
            referer = source.headers.header("Referer") ?: source.baseUrl
            supportsLogin = (source as? LoginSource)?.supportsLogin == true
        }
        val sourceOrigin = runCatching { Url(baseUrl.trim()) }.getOrNull() ?: return null
        val target = runCatching { Url(challengeUrl.trim()) }.getOrNull() ?: return null
        if (target.protocol.name !in setOf("http", "https") || target.host.isBlank()) return null
        require(target.sameOrigin(sourceOrigin)) {
            "Web challenge URL must use the source's exact origin"
        }
        val built = requestBuilder.build(
            sourceId = storageId,
            request = PluginHttpRequest("GET", target.toString()),
            sourceHeaders = sourceHeaders,
            referer = referer,
        )
        val userAgent = built.transportRequest.headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            .orEmpty()
        // This method is called only from the explicit Web challenge action. Decrypt credentials
        // on demand here; catalogue discovery and ordinary snapshot refreshes never receive them.
        val hasSuppliedCredentialFields = username != null || password != null
        val suppliedCredential = username
            ?.takeIf(String::isNotBlank)
            ?.let { suppliedUsername ->
                password
                    ?.takeIf(String::isNotEmpty)
                    ?.let { suppliedPassword -> PluginCredential(suppliedUsername, suppliedPassword) }
            }
        val credential = if (supportsLogin) {
            if (hasSuppliedCredentialFields) {
                suppliedCredential
            } else {
                pluginStorage.getCredential(storageId)
                    ?.takeIf { it.username.isNotBlank() && it.password.isNotEmpty() }
            }
        } else {
            null
        }
        return SourceWebChallengeRequest(
            sourceId = sourceId,
            sourceName = sourceName,
            url = target.toString(),
            userAgent = userAgent,
            cookies = pluginStorage.getCookies(storageId).map(::toBrowseCookie),
            requiredCookieName = "cf_clearance".takeIf {
                v2Scope?.first?.let(::requiresCloudflareClearance) == true
            },
            username = credential?.username,
            password = credential?.password,
        )
    }

    private fun requiresCloudflareClearance(sourceKey: SourceKey): Boolean =
        sourceKey.packageId == "zh.bilimanga" && sourceKey.sourceId == "zh.bilimanga.manga"

    private fun Url.sameOrigin(other: Url): Boolean =
        protocol.name.equals(other.protocol.name, ignoreCase = true) &&
            host.equals(other.host, ignoreCase = true) &&
            port == other.port

    private suspend fun refreshLocked(addDefaultRepository: Boolean = true): Unit =
        withContext(Dispatchers.Default) {
        mutableState.value = mutableState.value.copy(isRefreshing = true, errorMessage = null)
        try {
            ensureInstalledLoaded()
            reconcilePortableRepositories()
            // Publish installed/local sources before touching remote repositories. A cold launch
            // can therefore render immediately while discovery continues in the background.
            rebuildSnapshot(errorMessage = null, isRefreshing = true)
            var repositories = repositoryStore.list()
            if (repositories.isEmpty() && addDefaultRepository && defaultRepositoryUrl.isNotBlank()) {
                val defaultRepository = withTimeoutOrNull(BROWSE_REMOTE_REFRESH_TIMEOUT_MILLIS) {
                    repositoryClient.fetchRepository(normalizeRepositoryInput(defaultRepositoryUrl))
                }
                if (defaultRepository != null) {
                    saveRepository(defaultRepository)
                    repositoryStore.select(defaultRepository.baseUrl)
                    repositories = listOf(defaultRepository)
                }
            }
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
                descriptors = manager.refresh(visibleRepositories).associateBy { it.id }
                refreshReviewedShuYueRepositories()
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
     * Tries the reviewed ShuYue contract for an add-repository input. Local/LAN URLs are probed
     * as well as GitHub URLs because their host name carries no ShuYue hint. A generic URL falls
     * back to the legacy repository parser when the probe is not a reviewed ShuYue index, so
     * existing extension repositories keep their old behavior.
     */
    private suspend fun tryAddReviewedShuYueRepository(input: String): BrowseRepository? {
        val loader = reviewedShuYueRepositoryLoaderV2 ?: return null
        // Probe every ordinary HTTP(S) repository once. A local/LAN ShuYue server has no
        // `shuyue` token in its hostname, so relying on the URL hint silently routes it to the
        // legacy manager and leaves the reviewed extension list empty. Non-ShuYue repositories
        // still fall back to the legacy path when this optimistic probe fails.
        val probeableUrl = input.startsWith("http://") || input.startsWith("https://")
        if (!probeableUrl) {
            return null
        }
        val location = normalizeShuYueRepositoryInput(input)
        val coordinator = reviewedShuYueCoordinator(location, loader)
        val packages = try {
            coordinator.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return null
        }
        if (packages.isEmpty()) {
            return null
        }
        // A unified envelope has a real legacy half as well. Keep that half in the legacy store,
        // while the reviewed coordinator owns the ShuYue half. Plain ShuYue repositories remain
        // reviewed-only and are not fed to the executable legacy manager.
        val legacyBase = normalizeRepositoryInput(input)
        clearUnifiedRepositoryMigrationAttempt(location)
        val unified = try {
            val index = repositoryClient.fetchIndex(legacyBase)
            if (index is RepositoryIndex.Combined && index.plugins.isNotEmpty()) {
                val repository = loadUnifiedRepositoryMetadata(legacyBase)
                saveRepository(repository)
                repositoryStore.select(repository.baseUrl)
                true
            } else {
                false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A normal ShuYue repository may not publish a Shinsou-compatible index; it remains
            // reviewed-only and is still usable through the explicit approval flow.
            false
        }
        val builtIn = normalizeShuYueRepositoryInput(reviewedShuYueRepositoryLocationV2.value)
        if (location !in readReviewedShuYueRepositoryUrls()) {
            writeReviewedShuYueRepositoryUrls(readReviewedShuYueRepositoryUrls() + location)
        }
        refreshReviewedShuYueRepositories()
        if (unified) refreshLocked(addDefaultRepository = false) else rebuildSnapshot(errorMessage = null)
        return reviewedRepositoryRow(location, official = location == builtIn)
    }

    /** Refreshes only user-configured ShuYue index URLs.
     *
     * The maintained ShuYue repository is available through the normal add-repository flow, but
     * it is deliberately not seeded here. A fresh install must not contact or display a remote
     * source until the user has explicitly configured one.
     */
    private suspend fun refreshReviewedShuYueRepositories() {
        val loader = reviewedShuYueRepositoryLoaderV2
        if (loader == null) {
            reviewedShuYuePackages = emptyMap()
            reviewedShuYuePackageOwners = emptyMap()
            reviewedShuYueRepositoryRows = emptyList()
            return
        }
        val builtIn = normalizeShuYueRepositoryInput(reviewedShuYueRepositoryLocationV2.value)
        val configured = readReviewedShuYueRepositoryUrls().distinct()
        val packages = linkedMapOf<String, ShuYueReviewedRepositoryPackageV2>()
        val owners = linkedMapOf<String, ShuYueReviewedRepositoryCoordinatorV2>()
        var successfulRepositories = 0
        var firstFailure: Throwable? = null
        configured.forEach { location ->
            val coordinator = reviewedShuYueCoordinator(location, loader)
            try {
                val refreshed = coordinator.refresh()
                successfulRepositories++
                refreshed.forEach { packageInfo ->
                    // The first configured source wins when repositories mirror the same package.
                    if (packageInfo.packageId !in packages) {
                        packages[packageInfo.packageId] = packageInfo
                        owners[packageInfo.packageId] = coordinator
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // One configured source can be unavailable while another user-provided source is
                // healthy. Keep refreshing the remaining locations so one failed source cannot
                // hide working ShuYue packages. The explicit add flow still surfaces errors for
                // the URL the user entered.
                if (firstFailure == null) firstFailure = failure
            }
        }
        // Preserve the existing error signal when every configured source is unavailable, while
        // allowing a healthy user repository to keep working during a built-in outage.
        if (successfulRepositories == 0) firstFailure?.let { throw it }
        reviewedShuYuePackages = packages.toMap()
        reviewedShuYuePackageOwners = owners.toMap()
        reviewedShuYueRepositoryRows = configured.map { location ->
            reviewedRepositoryRow(location, official = location == builtIn)
        }
    }

    private fun reviewedShuYueCoordinator(
        location: String,
        loader: ShuYueRepositoryIndexLoader,
    ): ShuYueReviewedRepositoryCoordinatorV2 =
        reviewedShuYueRepositoriesV2.getOrPut(location) {
            ShuYueReviewedRepositoryCoordinatorV2(
                loader = loader,
                installer = reviewedShuYueInstallerV2,
                location = ShuYueRepositoryLocation.IndexUrl(location),
            )
        }

    private suspend fun reviewedRepositoryRow(location: String, official: Boolean = false): BrowseRepository {
        val unified = repositoryStore.list().firstOrNull {
            it.baseUrl == normalizeRepositoryInput(location)
        }
        return BrowseRepository(
            id = SHUYUE_REPOSITORY_ID_PREFIX + location,
            url = location,
            name = unified?.name ?: "ShuYue",
            website = unified?.website ?: location.substringBeforeLast('/').ifBlank { location },
            signingFingerprint = unified?.signingKeyFingerprint?.takeIf { it.isNotBlank() },
            official = official,
        )
    }

    private suspend fun readReviewedShuYueRepositoryUrls(): List<String> =
        keyValueStore.getString(SHUYUE_REPOSITORY_URLS_KEY)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map(::normalizeShuYueRepositoryInput)
            ?.distinct()
            ?.toList()
            .orEmpty()

    private suspend fun writeReviewedShuYueRepositoryUrls(urls: Iterable<String>) {
        val normalized = urls.map(::normalizeShuYueRepositoryInput).distinct().sorted()
        if (normalized.isEmpty()) keyValueStore.remove(SHUYUE_REPOSITORY_URLS_KEY)
        else keyValueStore.putString(SHUYUE_REPOSITORY_URLS_KEY, normalized.joinToString("\n"))
    }

    /** Accepts a ShuYue base URL, index URL, GitHub repository URL, or GitHub tree URL. */
    private fun normalizeShuYueRepositoryInput(value: String): String {
        val trimmed = value.trim().trimEnd('/').substringBefore('#').substringBefore('?')
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "Invalid ShuYue repository URL: $value"
        }
        val authority = trimmed.substringAfter("://").substringBefore('/').lowercase()
        val path = trimmed.substringAfter("://").substringAfter('/', "")
        if (authority == "github.com") {
            val segments = path.split('/').filter(String::isNotEmpty)
            require(segments.size >= 2) { "Invalid GitHub ShuYue repository URL: $value" }
            val owner = segments[0]
            val repository = segments[1].removeSuffix(".git")
            val hasTreeOrBlob = segments.size >= 4 &&
                (segments[2] == "tree" || segments[2] == "blob")
            val branch = if (hasTreeOrBlob) segments[3] else "main"
            val tail = if (hasTreeOrBlob && segments.size > 4) {
                segments.drop(4).joinToString("/")
            } else {
                "index.json"
            }
            return "https://raw.githubusercontent.com/$owner/$repository/refs/heads/$branch/" +
                if (tail.endsWith(".json")) tail else "$tail/index.json"
        }
        return if (path.endsWith(".json")) trimmed else "$trimmed/index.json"
    }

    /**
     * On upgraded installs, imports the legacy KV list once when the portable snapshot has never
     * owned repository state. After that one-time bridge, the snapshot is authoritative: every
     * refresh replaces the KV mirror exactly, including removals made by restore or sync.
     */
    private suspend fun reconcilePortableRepositories() {
        portableRepository?.let { portable ->
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
        reconcileLegacyShuYueRepositories()
        reconcileUnifiedReviewedRepositories()
    }

    /**
     * Older builds accepted ShuYue's repository `repo.json` as a legacy repository. Remove that
     * stale mirror before the legacy manager sees its ShuYue `index.json`; preserve the URL in the
     * reviewed repository store so an upgrade repairs itself without user re-entry.
     */
    private suspend fun reconcileLegacyShuYueRepositories() {
        // Do not probe every configured repository during startup. Apart from making launch
        // unnecessarily network-bound, that would recreate the very URL-substring detection
        // bug this adapter is meant to avoid. Older builds stored the ShuYue repository name in
        // metadata, so only that explicit migration marker is eligible for a one-time bridge.
        val stale = repositoryStore.list().filter { repository ->
            val name = "${repository.name} ${repository.shortName.orEmpty()}".lowercase()
            "shuyue" in name || "syp" in name
        }
        if (stale.isEmpty()) return
        val builtIn = normalizeShuYueRepositoryInput(reviewedShuYueRepositoryLocationV2.value)
        val reviewedUrls = readReviewedShuYueRepositoryUrls().toMutableList()
        stale.map { normalizeShuYueRepositoryInput(it.baseUrl) }
            .filter { it != builtIn && it !in reviewedUrls }
            .forEach(reviewedUrls::add)
        writeReviewedShuYueRepositoryUrls(reviewedUrls)
        stale.forEach { deleteRepository(it.baseUrl) }
        val remaining = repositoryStore.list()
        if (repositoryStore.selected() !in remaining.map { it.baseUrl }) {
            repositoryStore.select(remaining.firstOrNull()?.baseUrl)
        }
    }

    /**
     * Repairs repositories created by builds that only knew about the reviewed ShuYue half.
     *
     * Those builds persisted the URL in [SHUYUE_REPOSITORY_URLS_KEY], so simply adding the new
     * unified fixture again is not enough: the old reviewed row remains and the legacy manager
     * never sees the Shinsou half. Probe each such URL once, and when its response is a unified
     * envelope restore the legacy repository row before [refreshLocked] asks the manager for
     * descriptors. A per-URL marker keeps ordinary ShuYue repositories from adding a network
     * probe to every launch; re-adding a URL clears the marker and retries it explicitly.
     */
    private suspend fun reconcileUnifiedReviewedRepositories() {
        val configured = readReviewedShuYueRepositoryUrls()
        if (configured.isEmpty()) return
        val attempted = readUnifiedRepositoryMigrationAttempts().toMutableSet()
        configured.forEach { location ->
            val base = normalizeRepositoryInput(location)
            if (repositoryStore.list().any { it.baseUrl == base } || location in attempted) return@forEach
            try {
                val index = withTimeoutOrNull(BROWSE_REMOTE_REFRESH_TIMEOUT_MILLIS) {
                    repositoryClient.fetchIndex(base)
                }
                if (index is RepositoryIndex.Combined && index.plugins.isNotEmpty()) {
                    val repository = withTimeoutOrNull(BROWSE_REMOTE_REFRESH_TIMEOUT_MILLIS) {
                        loadUnifiedRepositoryMetadata(base)
                    }
                    if (repository != null) {
                        saveRepository(repository)
                        if (repositoryStore.selected() == null) repositoryStore.select(repository.baseUrl)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The reviewed half remains usable when the legacy half is unavailable. Mark the
                // probe below so a temporary malformed/non-unified repository does not stall
                // every subsequent app launch; adding the URL again clears it for a retry.
            }
            attempted += location
            writeUnifiedRepositoryMigrationAttempts(attempted)
        }
    }

    /** Reads optional metadata, while still allowing a unified index without repo.json. */
    private suspend fun loadUnifiedRepositoryMetadata(baseUrl: String): ExtensionRepository =
        try {
            repositoryClient.fetchRepository(baseUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ExtensionRepository(
                baseUrl = baseUrl,
                name = "Unified repository",
                shortName = "Unified",
                website = baseUrl,
            )
        }

    private suspend fun readUnifiedRepositoryMigrationAttempts(): List<String> =
        keyValueStore.getString(UNIFIED_REPOSITORY_MIGRATION_KEY)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?.toList()
            .orEmpty()

    private suspend fun writeUnifiedRepositoryMigrationAttempts(attempts: Iterable<String>) {
        val normalized = attempts.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        if (normalized.isEmpty()) keyValueStore.remove(UNIFIED_REPOSITORY_MIGRATION_KEY)
        else keyValueStore.putString(UNIFIED_REPOSITORY_MIGRATION_KEY, normalized.joinToString("\n"))
    }

    private suspend fun clearUnifiedRepositoryMigrationAttempt(location: String) {
        val remaining = readUnifiedRepositoryMigrationAttempts().filterNot { it == location }
        writeUnifiedRepositoryMigrationAttempts(remaining)
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
        isRefreshing: Boolean = false,
        reuseUnchangedSources: Boolean = false,
    ): Unit = withContext(Dispatchers.Default) {
        val repositories = repositoryStore.list()
        // A unified repository is persisted twice internally (the executable Shinsou base and
        // the reviewed ShuYue index), but it must appear as one source in the UI. Plain ShuYue
        // repositories have no matching legacy base and therefore keep their own row.
        val unifiedLegacyBases = reviewedShuYueRepositoryRows
            .map { normalizeRepositoryInput(it.url) }
            .toSet()
        val repositoryRows = (
            repositories
                .filterNot { it.baseUrl in unifiedLegacyBases }
                .map { it.toBrowseRepository() } + reviewedShuYueRepositoryRows
            )
            .distinctBy(BrowseRepository::url)
        val selected = repositoryStore.selected()
        val selectedRowId = reviewedShuYueRepositoryRows.firstOrNull { row ->
            normalizeRepositoryInput(row.url) == selected
        }?.id ?: selected
        val installed = manager.installedPlugins().associateBy { it.manifest.id }
        val extensionDescriptorsV2 = manager.extensionDescriptorsV2()
        val liveV2SourceKeys = extensionDescriptorsV2.flatMap { it.sources }
            .mapTo(linkedSetOf()) { it.sourceKey }
        pruneV2SourceMappings(liveV2SourceKeys)
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
            val supportsLogin = (source as? LoginSource)?.supportsLogin == true
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
                supportsLogin = supportsLogin,
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
                contentType = descriptor?.contentType ?: PluginContentType.BOTH,
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
                .filterNot { descriptor ->
                    ShuYueReviewedPluginCatalogV2.profiles
                        .any { it.identity.packageId == descriptor.sourceKey.packageId && it.legacyCompatibilityOnly }
                }
                .forEach { descriptor ->
                    val sourceKey = descriptor.sourceKey
                    val rowId = v2UiRowId(sourceKey, occupiedSourceIds)
                    val storageId = v2StorageSourceId(sourceKey)
                    val hostSource = manager.extensionSourceV2(sourceKey)
                    val supportsLogin = ExtensionCapability.LOGIN in descriptor.capabilities
                    val preferences = buildList {
                        storageId?.let { add(networkProxyPreference(it)) }
                        if (hostSource != null && ExtensionCapability.PREFERENCES in descriptor.capabilities) {
                            try {
                                hostSource.preferences().forEach { preference ->
                                    storageId?.let { add(preference.toBrowsePreference(it)) }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                // A source's optional preference endpoint must not make the
                                // source list disappear; the raw proxy setting remains usable.
                            }
                        }
                    }
                    val filters = if (hostSource != null && ExtensionCapability.BROWSE in descriptor.capabilities) {
                        try {
                            hostSource.browseOptions().filters.map { it.toBrowseFilter() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            // Optional filter discovery must not hide an otherwise valid source.
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
        val sources = (legacySources + nativeSources)
            .distinctBy(BrowseSource::identityKey)
            .sortedWith(compareBy<BrowseSource> { it.language }.thenBy { it.name.lowercase() })
        val reservedReviewedIds = ShuYueReviewedPluginCatalogV2.profiles
            .mapTo(hashSetOf()) { it.identity.packageId }
        val liveV2Packages = extensionDescriptorsV2.associateBy { it.packageId }
        val compatibilityInstalled = ShuYueReviewedPluginCatalogV2.profiles
            .filter(ShuYueReviewedPluginProfileV2::legacyCompatibilityOnly)
            .mapNotNull { profile ->
                reviewedShuYueInstallerV2.installed(profile.identity.packageId)
                    ?.takeIf { it.identity == profile.identity }
                    ?.let { profile }
            }
            .distinctBy { it.identity.packageId }
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
                contentType = descriptor.contentType,
            )
        } + reviewedShuYuePackages.values.map { reviewed ->
            val profile = requireNotNull(
                ShuYueReviewedPluginCatalogV2.findRepositoryProfile(
                    packageId = reviewed.packageId,
                    version = reviewed.version,
                    versionCode = reviewed.versionCode,
                    sha256 = reviewed.sha256,
                ),
            )
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
                contentType = reviewed.contentType,
            )
        } + compatibilityInstalled.map { profile ->
            BrowseExtension(
                id = profile.identity.packageId,
                name = profile.displayName,
                version = profile.identity.version,
                language = profile.languageTag,
                installed = true,
                trusted = false,
                reviewedShuYueV2 = true,
                description = "停止維護；僅保留相容性安裝。請改用維護中的 ShuYue 來源。",
            )
        }
        sourceProjections = rebuiltSourceProjections
        mutableState.value = BrowseSnapshot(
            repositories = repositoryRows,
            selectedRepositoryId = selectedRowId,
            sources = sources,
            extensions = extensions,
            migrations = migrationProvider.candidates(),
            isRefreshing = isRefreshing,
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
        v2SourceRowIds[sourceKey]?.takeIf { it !in occupied }?.let {
            v2SourceKeysByRowId[it] = sourceKey
            v2StorageSourceId(sourceKey)?.let { storageId -> v2SourceKeysByStorageId[storageId] = sourceKey }
            return it
        }
        var candidate = Long.MIN_VALUE
        val allocated = v2SourceRowIds.values.toHashSet()
        while (candidate in occupied || candidate in allocated) {
            check(candidate != Long.MAX_VALUE) { "Unable to allocate an extension v2 UI row id" }
            candidate++
        }
        v2SourceRowIds[sourceKey] = candidate
        v2SourceKeysByRowId[candidate] = sourceKey
        v2StorageSourceId(sourceKey)?.let { v2SourceKeysByStorageId[it] = sourceKey }
        return candidate
    }

    /** ShuYue v2 keeps the same protected PluginStorage jar, addressed by its host-local scope. */
    private fun v2StorageSourceId(sourceKey: SourceKey): Long? {
        val profile = ShuYueReviewedPluginCatalogV2.profiles.firstOrNull {
            it.identity.packageId == sourceKey.packageId
        } ?: return null
        return runCatching {
            BuiltInShuYueExecutionScopesV2.resolve(profile.identity, sourceKey)
        }.getOrNull()
    }

    private fun v2SourceKeyFor(sourceId: Long): SourceKey? =
        v2SourceKeysByRowId[sourceId] ?: v2SourceKeysByStorageId[sourceId]

    private fun requireV2SourceScope(sourceId: Long): Pair<SourceKey, Long>? {
        val sourceKey = v2SourceKeyFor(sourceId) ?: return null
        val storageId = v2StorageSourceId(sourceKey)
            ?: throw IllegalArgumentException("Source settings are unavailable for ${sourceKey.canonicalId}")
        return sourceKey to storageId
    }

    private suspend fun resolveReviewedCredentials(credentials: LoginCredentialsV2): LegacyLoginCredentialsV2? {
        if (!credentials.usernameReference.startsWith(V2_USERNAME_REFERENCE_PREFIX) ||
            !credentials.passwordReference.startsWith(V2_PASSWORD_REFERENCE_PREFIX)
        ) return null
        val usernameScope = credentials.usernameReference
            .removePrefix(V2_USERNAME_REFERENCE_PREFIX)
            .toLongOrNull()
        val passwordScope = credentials.passwordReference
            .removePrefix(V2_PASSWORD_REFERENCE_PREFIX)
            .toLongOrNull()
        if (usernameScope == null || usernameScope != passwordScope) return null
        // References are opaque to the extension and must never become an arbitrary lookup into
        // the host's credential store.  Only the fixed, reviewed ShuYue execution scopes may be
        // resolved here; all other references are treated as an invalid login attempt.
        if (usernameScope !in REVIEWED_CREDENTIAL_SCOPES) return null
        return pluginStorage.getCredential(usernameScope)?.let {
            LegacyLoginCredentialsV2(it.username, it.password)
        }
    }

    private fun v2LoginCredentials(scopeId: Long): LoginCredentialsV2 = LoginCredentialsV2(
        usernameReference = "$V2_USERNAME_REFERENCE_PREFIX$scopeId",
        passwordReference = "$V2_PASSWORD_REFERENCE_PREFIX$scopeId",
    )

    private fun pruneV2SourceMappings(liveSourceKeys: Set<SourceKey>) {
        v2SourceRowIds.keys.retainAll(liveSourceKeys)
        v2SourceKeysByRowId.entries.removeAll { it.value !in liveSourceKeys }
        v2SourceKeysByStorageId.entries.removeAll { it.value !in liveSourceKeys }
    }

    private fun toBrowseCookie(cookie: PluginCookie): BrowseSourceCookie = BrowseSourceCookie(
        name = cookie.name,
        value = cookie.value,
        domain = cookie.domain,
        path = cookie.path,
        expiresAtEpochMillis = cookie.expiresAtEpochMillis,
        secure = cookie.secure,
        httpOnly = cookie.httpOnly,
        hostOnly = cookie.hostOnly,
    )

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

    private suspend fun PreferenceV2.toBrowsePreference(sourceId: Long): BrowseSourcePreference {
        val stored = pluginStorage.getPreference(sourceId, key)
        return BrowseSourcePreference(
            key = key,
            title = label,
            value = stored ?: value.orEmpty(),
            kind = SourcePreferenceKind.Text,
        )
    }

    public companion object {
        private const val SHUYUE_REPOSITORY_ID_PREFIX: String = "shuyue:"
        private const val SHUYUE_REPOSITORY_URLS_KEY: String = "plugin.shuyue.v2.repository-urls"
        private const val UNIFIED_REPOSITORY_MIGRATION_KEY: String =
            "plugin.repositories.unified-reviewed-migration.v2"
        private const val V2_USERNAME_REFERENCE_PREFIX: String = "shinsou-v2-username-"
        private const val V2_PASSWORD_REFERENCE_PREFIX: String = "shinsou-v2-password-"
        private val REVIEWED_CREDENTIAL_SCOPES: Set<Long> =
            ShuYueReviewedPluginCatalogV2.profiles
                .flatMap { profile ->
                    profile.sourceIds.mapNotNull { sourceId ->
                        runCatching {
                            BuiltInShuYueExecutionScopesV2.resolve(
                                profile.identity,
                                SourceKey(2, profile.identity.packageId, sourceId),
                            )
                        }.getOrNull()
                    }
                }.toSet()
        private const val PORTABLE_REPOSITORY_MIGRATION_KEY: String =
            "plugin.repositories.portable-migration.v1"
        private val LEGACY_LOCAL_LIBRARY_PACKAGE_IDS: Set<String> = setOf(
            "zh.bilimanga",
            "zh.biquge.tw",
            "zh.wenku8.api",
        )

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
