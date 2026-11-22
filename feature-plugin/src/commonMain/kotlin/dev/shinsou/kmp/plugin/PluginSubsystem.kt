package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.access.HostContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.contract.AccountPackage
import dev.shinsou.kmp.contract.AccountPackageDirectory
import dev.shinsou.kmp.contract.AccountRepositoryDirectory
import dev.shinsou.kmp.contract.ChapterDownloads
import dev.shinsou.kmp.contract.ChapterPageProvider
import dev.shinsou.kmp.contract.ContentCallbacks
import dev.shinsou.kmp.contract.DownloadPageFetcher
import dev.shinsou.kmp.contract.ReaderRemoteImageFetcher
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.files.KeyValueStore
import dev.shinsou.kmp.plugin.events.BoundedPluginDiagnosticLog
import dev.shinsou.kmp.plugin.events.ExactSourceRefreshInvalidations
import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.plugin.events.PluginEventContextRegistry
import dev.shinsou.kmp.plugin.events.PluginEventObserverGroup
import dev.shinsou.kmp.plugin.events.PluginSystemEventGateway
import dev.shinsou.kmp.plugin.events.PluginSystemEventHandlerRegistry
import dev.shinsou.kmp.plugin.events.registerV1HostHandlers
import dev.shinsou.kmp.plugin.v2.ExtensionBrowseContentGatewayV2
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerV2
import dev.shinsou.kmp.plugin.v2.ExtensionSourceResolverV2
import dev.shinsou.kmp.plugin.v2.ExtensionSyncedReadingV2
import dev.shinsou.kmp.plugin.v2.PluginNetworkExtensionResourceFetcherV2
import dev.shinsou.kmp.plugin.v2.extensionLocalUnitProgress
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import dev.shinsou.kmp.plugin.browse.PluginBrowseAdapter
import dev.shinsou.kmp.plugin.browse.RepositoryPluginCoordinator
import dev.shinsou.kmp.plugin.browser.PluginBrowserSessionTransport
import dev.shinsou.kmp.plugin.browser.createPlatformReviewedImageTransport
import dev.shinsou.kmp.plugin.manager.PluginLifecycleObserver
import dev.shinsou.kmp.plugin.manager.PluginLoginRequestCoordinator
import dev.shinsou.kmp.plugin.manager.PluginLogoutRequestCoordinator
import dev.shinsou.kmp.plugin.manager.PluginManager
import dev.shinsou.kmp.plugin.manager.pluginSystemEventHostPorts
import dev.shinsou.kmp.plugin.model.isDeveloperInstallKey
import dev.shinsou.kmp.plugin.network.ConfiguredPluginProxyResolver
import dev.shinsou.kmp.plugin.network.ConfiguredPluginUserAgentProvider
import dev.shinsou.kmp.plugin.network.KtorPluginHttpTransport
import dev.shinsou.kmp.plugin.network.PlatformPluginNetworkCapability
import dev.shinsou.kmp.plugin.network.PluginNetworkClient
import dev.shinsou.kmp.plugin.network.PluginNetworkConfiguration
import dev.shinsou.kmp.plugin.network.PluginNetworkConfigurationProvider
import dev.shinsou.kmp.plugin.network.PluginRequestBuilder
import dev.shinsou.kmp.plugin.network.PluginUserAgentProvider
import dev.shinsou.kmp.plugin.network.createPlatformPinnedPluginHttpTransport
import dev.shinsou.kmp.plugin.network.createPlatformPluginHostResolver
import dev.shinsou.kmp.plugin.network.inspectPlatformPluginNetwork
import dev.shinsou.kmp.plugin.repository.ExtensionRepositoryClient
import dev.shinsou.kmp.plugin.repository.KeyValueExtensionRepositoryStore
import dev.shinsou.kmp.plugin.runtime.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.runtime.ScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.storage.BILIMANGA_MANGA_SOURCE_ID
import dev.shinsou.kmp.plugin.storage.BiliMangaStandaloneLibraryMigration
import dev.shinsou.kmp.plugin.storage.FilePluginPackageStore
import dev.shinsou.kmp.plugin.storage.KeyValuePluginPackageStore
import dev.shinsou.kmp.plugin.storage.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.storage.KeyValuePluginTrustStore
import dev.shinsou.kmp.plugin.storage.MigratingPluginStorage
import dev.shinsou.kmp.plugin.trust.ConfiguredRepositoryTrustPolicy
import dev.shinsou.kmp.plugin.trust.ExecutionTierResolver
import dev.shinsou.kmp.plugin.trust.KeyValueRepositorySecurityStateStore
import dev.shinsou.kmp.plugin.trust.KeyValueRepositoryTrustRootStore
import dev.shinsou.kmp.plugin.trust.LocalPluginBlocklist
import dev.shinsou.kmp.plugin.trust.PluginDeveloperModeStore
import dev.shinsou.kmp.plugin.trust.PluginPackageSecurity
import dev.shinsou.kmp.plugin.trust.PluginVerifier
import dev.shinsou.kmp.plugin.trust.PublisherExecutionTierGate
import dev.shinsou.kmp.plugin.trust.PublisherRepositoryTrustPolicy
import dev.shinsou.kmp.plugin.trust.PublisherRevocationStore
import dev.shinsou.kmp.plugin.trust.PublisherTrustStore
import dev.shinsou.kmp.plugin.trust.RepositoryTrustRootStore

/**
 * The assembly entry point of :feature-plugin (docs/architecture/0007 B2).
 *
 * The plugin graph (storage, trust and publisher stores, the repository client, the pinned plugin
 * network, the system-event gateway, the [PluginManager] and the chapter coordinator) used to be
 * built by hand in the app's composition root, which forced every one of those classes to be
 * public. It is built here instead, from the platform inputs alone, and the rest of the app only
 * sees what it uses: the [browse] facade for the UI, the chapter/download ports, the source
 * lookups sync needs, a few startup steps and the close steps.
 *
 * Building happens in two steps because sync sits between them: sync resolves sources through
 * this subsystem, and the browse facade offers sync's account lists. [create] builds everything
 * but the facade; [openBrowse] builds the facade once sync exists.
 *
 * Closing is done by the composition root, step by step, because its order interleaves other
 * subsystems: [cancelEventUi], `browse.close()`, [closeRuntimes], [closeBrowserSessions],
 * [closeEventGateway], [clearDiagnostics] and, after the snapshot's last flush, [closeHttpClient].
 */
public class PluginSubsystem private constructor(
    private val repository: ShinsouRepository,
    platform: PlatformInputs,
    private val contentFoundation: ContentFoundationRuntime?,
    private val backgroundScope: CoroutineScope,
    defaultDispatcher: CoroutineDispatcher,
) {
    /**
     * What each platform supplies: [httpClient] (repositories; the plugin network derives its own
     * non-redirecting client from it), [keyValueStore] (the protected store), [fileSystem] (the
     * package store), the platform [runtimeFactory], [browserSessionTransport] and
     * [browserUserAgentProvider].
     */
    public class PlatformInputs(
        public val httpClient: HttpClient,
        public val keyValueStore: KeyValueStore,
        public val fileSystem: AppFileSystem,
        public val runtimeFactory: ScriptPluginRuntimeFactory,
        public val browserSessionTransport: PluginBrowserSessionTransport,
        public val browserUserAgentProvider: PluginUserAgentProvider,
    )

    private val keyValueStore: KeyValueStore = platform.keyValueStore
    private val fileSystem: AppFileSystem = platform.fileSystem
    private val browserSessionTransport: PluginBrowserSessionTransport = platform.browserSessionTransport

    /**
     * Non-secret platform capability exposed for support/diagnostics. This does not weaken the
     * network admission boundary: unavailable pinned transport still rejects public egress.
     */
    public val networkCapability: PlatformPluginNetworkCapability = inspectPlatformPluginNetwork()

    private val biliMangaStandaloneLibraryMigration: BiliMangaStandaloneLibraryMigration? =
        contentFoundation?.let { BiliMangaStandaloneLibraryMigration(repository, it) }
    private val pluginHttpClient = platform.httpClient.config { followRedirects = false }
    private val platformPluginHostResolver = createPlatformPluginHostResolver()
    private val platformPinnedPluginTransport = createPlatformPinnedPluginHttpTransport()
    private val pluginStorage = MigratingPluginStorage(
        delegate = KeyValuePluginStorage(keyValueStore),
        migrationState = keyValueStore,
    )
    private val trustStore = KeyValuePluginTrustStore(keyValueStore)

    /** Out-of-band/admin configuration boundary; key replacement requires its explicit API. */
    private val repositoryTrustRootStore: RepositoryTrustRootStore = KeyValueRepositoryTrustRootStore(keyValueStore)
    private val repositorySecurityState = KeyValueRepositorySecurityStateStore(keyValueStore)

    /** ADR-0002 S5: trusted publisher keys, their trust mode, rotation and self-revocation. */
    private val publisherTrustStore: PublisherTrustStore = PublisherTrustStore(keyValueStore, repositorySecurityState)

    /** ADR-0002 S5: artifacts publishers withdrew, and when their signed documents expire. */
    private val publisherRevocationStore: PublisherRevocationStore =
        PublisherRevocationStore(keyValueStore, publisherTrustStore, repositorySecurityState)

    /** ADR-0002 D5: the user's own block list, device-local. */
    private val pluginBlocklist: LocalPluginBlocklist = LocalPluginBlocklist(keyValueStore)

    /** ADR-0002 §8.1: device-local developer mode for loading unsigned packages from a dev server. */
    private val pluginDeveloperMode: PluginDeveloperModeStore = PluginDeveloperModeStore(keyValueStore)

    /** ADR-0002 §10.1: the formal basis for where a package's code runs. */
    private val executionTierResolver: ExecutionTierResolver = ExecutionTierResolver(
        publishers = publisherTrustStore,
        revocations = publisherRevocationStore,
        blocklist = pluginBlocklist,
        developerMode = { pluginDeveloperMode.state.value.enabled },
        nowEpochSeconds = { Clock.System.now().epochSeconds },
    )
    private val repositoryClient = ExtensionRepositoryClient(
        client = platform.httpClient,
        // A repository mapped to a trusted publisher requires that publisher's current key; any
        // other URL keeps the configured pins.
        repositoryTrustPolicy = PublisherRepositoryTrustPolicy(
            publishers = publisherTrustStore,
            blocklist = pluginBlocklist,
            fallback = ConfiguredRepositoryTrustPolicy(
                roots = repositoryTrustRootStore,
                // Every unpinned repository is rejected before transport, except what developer
                // mode admits: this device and the LAN addresses the user entered.
                allowUnsignedDeveloperCompatibility = { url -> pluginDeveloperMode.admitsRepository(url) },
            ),
        ),
        repositorySecurityState = repositorySecurityState,
        repositoryHostResolver = platformPluginHostResolver,
        repositoryTransport = platformPinnedPluginTransport,
        developerRepository = pluginDeveloperMode::admitsRepository,
    )
    private val repositoryStore = KeyValueExtensionRepositoryStore(keyValueStore)
    private val pluginLoginRequests = PluginLoginRequestCoordinator()
    private val pluginEventUiJob = SupervisorJob()
    private val pluginLogoutRequests = PluginLogoutRequestCoordinator(
        CoroutineScope(pluginEventUiJob + defaultDispatcher),
    )
    private val networkConfiguration = PluginNetworkConfigurationProvider {
        repository.currentSnapshot.settings.advanced.let { settings ->
            PluginNetworkConfiguration(
                proxyEnabled = settings.proxyEnabled,
                proxyWorkerUrl = settings.proxyWorkerUrl,
                proxyApiKey = settings.proxyApiKey,
                customUserAgent = settings.customUserAgent,
            )
        }
    }
    private val requestBuilder = PluginRequestBuilder(
        storage = pluginStorage,
        userAgents = ConfiguredPluginUserAgentProvider(
            configuration = networkConfiguration,
            fallback = platform.browserUserAgentProvider,
        ),
        proxyResolver = ConfiguredPluginProxyResolver(pluginStorage, networkConfiguration),
    )
    private val pluginNetwork = PluginNetworkClient(
        transport = platformPinnedPluginTransport ?: KtorPluginHttpTransport(pluginHttpClient),
        storage = pluginStorage,
        requestBuilder = requestBuilder,
        hostResolver = platformPluginHostResolver,
    )
    private val pluginEventAuthorizer: MutablePluginSystemEventAuthorizer = MutablePluginSystemEventAuthorizer()
    private val pluginEventContextRegistry: PluginEventContextRegistry = PluginEventContextRegistry()
    private val pluginEventGrantAdmission = KeyValuePluginEventGrantAdmission(
        store = keyValueStore,
        authorizer = pluginEventAuthorizer,
    )
    private val pluginDiagnosticLog = BoundedPluginDiagnosticLog()
    private val pluginSourceInvalidations = ExactSourceRefreshInvalidations()
    private val pluginLifecycleObserver = PluginLifecycleObserver(
        sourceInvalidations = pluginSourceInvalidations,
        loginRequests = pluginLoginRequests,
        logoutRequests = pluginLogoutRequests,
    )
    private val pluginEventRegistry: PluginSystemEventHandlerRegistry = PluginSystemEventHandlerRegistry().apply {
        registerV1HostHandlers(
            pluginSystemEventHostPorts(
                manager = { pluginManager },
                loginRequests = pluginLoginRequests,
                logoutRequests = pluginLogoutRequests,
                sourceInvalidations = pluginSourceInvalidations,
                diagnosticLog = pluginDiagnosticLog,
            ),
        )
    }
    private val pluginEventGateway: PluginSystemEventGateway = PluginSystemEventGateway(
        registry = pluginEventRegistry,
        authorizer = pluginEventAuthorizer,
        observer = PluginEventObserverGroup(pluginDiagnosticLog, pluginLifecycleObserver),
        contextRegistry = pluginEventContextRegistry,
    )
    private val pluginManager: PluginManager = PluginManager(
        repositoryClient = repositoryClient,
        packageStore = FilePluginPackageStore(
            fileSystem = fileSystem,
            legacyStore = KeyValuePluginPackageStore(keyValueStore),
        ),
        verifier = PluginVerifier(trustStore),
        runtimeFactory = platform.runtimeFactory,
        environment = ScriptPluginEnvironment(
            network = pluginNetwork,
            storage = pluginStorage,
            hostResolver = platformPluginHostResolver,
            browserSessionTransport = browserSessionTransport,
            loginRequester = pluginLoginRequests,
            systemEventSink = pluginEventGateway,
            systemEventContextRegistry = pluginEventContextRegistry,
        ),
        eventGrantAdmission = pluginEventGrantAdmission,
        reviewedImageTransport = createPlatformReviewedImageTransport(),
        executionTierGate = PublisherExecutionTierGate(publisherTrustStore, executionTierResolver),
        developerRepository = pluginDeveloperMode::admitsRepository,
    )
    private val coordinator = RepositoryPluginCoordinator(
        repository = repository,
        manager = pluginManager,
        // [browse] is opened later; this runs only when a chapter is loaded.
        awaitInstalledSources = { browse.awaitInstalledSources() },
    )
    private var browseAdapter: PluginBrowseAdapter? = null
    private var proxySecretPersistenceJob: Job? = null

    /** The source catalogue, extension management and trust facade the UI binds to; see [openBrowse]. */
    public val browse: PluginBrowseAdapter
        get() = checkNotNull(browseAdapter) { "PluginSubsystem.openBrowse has not run" }

    /** Chapter pages of installed sources, for the manga download queue. */
    public val chapterPages: ChapterPageProvider get() = coordinator

    /** Fetches a page of an installed source for the manga download queue. */
    public val downloadPages: DownloadPageFetcher get() = coordinator

    /** Remote chapters (lists, pages, migrations), behind local content. */
    public val remoteContent: ContentCallbacks get() = coordinator

    /** Lets chapter actions of installed sources queue downloads; attached once. */
    public fun attachDownloads(downloads: ChapterDownloads) {
        coordinator.attachDownloads(downloads)
    }

    /**
     * Builds the [browse] facade, once. [accountRepositories] and [accountPackages] are the sync
     * account's lists (null without sync); [syncedReading] is reading other devices synced for the
     * units of a publication not stored here.
     */
    public fun openBrowse(
        accountRepositories: AccountRepositoryDirectory?,
        accountPackages: AccountPackageDirectory?,
        syncedReading: (SourceKey, remotePublicationId: String) -> ExtensionSyncedReadingV2?,
    ): PluginBrowseAdapter {
        check(browseAdapter == null) { "The browse facade is already open" }
        return PluginBrowseAdapter(
            manager = pluginManager,
            repositoryClient = repositoryClient,
            repositoryStore = repositoryStore,
            pluginStorage = pluginStorage,
            keyValueStore = keyValueStore,
            trustStore = trustStore,
            mangaResolver = coordinator,
            migrationHandler = coordinator,
            migrationProvider = coordinator,
            requestBuilder = requestBuilder,
            portableRepository = repository,
            loginRequestCoordinator = pluginLoginRequests,
            logoutRequestCoordinator = pluginLogoutRequests,
            exactSourceRefreshInvalidations = pluginSourceInvalidations,
            packageSecurity = PluginPackageSecurity(publisherTrustStore, publisherRevocationStore, pluginBlocklist, repositoryClient),
            developerMode = pluginDeveloperMode,
            accountRepositories = accountRepositories,
            accountPackages = accountPackages,
            suggestionScope = backgroundScope,
            extensionContentConsumerV2 = contentFoundation?.let { foundation ->
                ExtensionContentConsumerV2(
                    gateway = ExtensionBrowseContentGatewayV2(
                        ExtensionSourceResolverV2 { sourceKey -> pluginManager.extensionSourceV2(sourceKey) },
                    ),
                    foundation = foundation,
                    offlineStoreAuthorizer = HostContentBodyOfflineStoreAuthorizer(
                        authority = foundation.rightsAuthority,
                        durableGrant = foundation.rightsGrants::find,
                        nowEpochMillis = { Clock.System.now().toEpochMilliseconds() },
                    ),
                    resourceFetcher = PluginNetworkExtensionResourceFetcherV2(
                        contentScope = pluginManager::contentNetworkScopeForSource,
                    ),
                    nowEpochMillis = { Clock.System.now().toEpochMilliseconds() },
                    localUnitProgress = repository::extensionLocalUnitProgress,
                    syncedReading = syncedReading,
                )
            },
            fileSystem = fileSystem,
        ).also { browseAdapter = it }
    }

    /** Network scope for the inline images of chapters [sourceKey] provided. */
    public suspend fun contentNetworkScope(sourceKey: SourceKey): ReaderRemoteImageFetcher? =
        pluginManager.contentNetworkScopeForSource(sourceKey)

    /** The exact source a legacy numeric source id stands for, for sync. */
    public suspend fun exactSourceKeyForLegacyId(sourceId: Long): SourceKey? =
        pluginManager.exactSourceKeyForLegacyId(sourceId)

    /** The source a synced title of [packageId]/[sourceId] can be created under here, for sync. */
    public suspend fun creatableSourceKey(packageId: String, sourceId: String): SourceKey? =
        pluginManager.extensionSourceV2(SourceKey(packageId = packageId, sourceId = sourceId))?.descriptor?.sourceKey
            // A package installed from another device's list waits for its permission review and
            // has no runtime yet; its stored manifest still names the source, so the title can be
            // created and the review is asked for when it is first opened.
            ?: pluginManager.installedSourceKey(packageId, sourceId)

    /**
     * Name, language and repository of every source known here, keyed by package and source id,
     * for sync. A package only a repository lists is described as a whole, under [wholePackage].
     */
    public suspend fun <T> describeSources(
        wholePackage: String,
        describe: (name: String?, language: String?, repositoryUrl: String?) -> T,
    ): Map<Pair<String, String>, T> {
        // Native packages have no stored manifest; what they have loaded describes them.
        val loaded = pluginManager.loadedExtensionDescriptorsV2().flatMap { extension ->
            extension.sources.map { source ->
                (source.sourceKey.packageId to source.sourceKey.sourceId) to
                    describe(source.displayName, source.languageTag, null)
            }
        }
        val stored = pluginManager.installedPlugins().flatMap { stored ->
            stored.manifest.sources.orEmpty().map { source ->
                (stored.manifest.id to (source.canonicalSourceId ?: source.id.toString())) to
                    describe(source.name, source.lang, stored.metadata.repositoryBaseUrl)
            }
        }
        // A title synced from another device may belong to a package that is not installed
        // here. The added repositories still say which of them publishes it; that describes
        // the package as a whole, not one source of it.
        val published = pluginManager.knownRepositoryPackages().mapNotNull { known ->
            val repository = known.repositoryBaseUrl ?: return@mapNotNull null
            (known.id to wholePackage) to describe(known.name, known.lang, repository)
        }
        return (published + loaded + stored).toMap()
    }

    /**
     * The packages installed from a repository, for the account's package list. Developer builds and
     * packages without a repository are left out: another device could not install them anyway.
     */
    public suspend fun installedAccountPackages(): List<AccountPackage> {
        val fingerprints = repository.currentSnapshot.extensionRepositories
            .associate { it.baseUrl.trim().trimEnd('/') to it.signingKeyFingerprint }
        return pluginManager.installedPlugins().mapNotNull { stored ->
            val url = stored.metadata.repositoryBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (isDeveloperInstallKey(stored.manifest.id)) return@mapNotNull null
            AccountPackage(
                packageId = stored.manifest.id,
                repositoryUrl = url,
                name = stored.manifest.name,
                version = stored.manifest.version,
                versionCode = (stored.manifest.versionCode ?: PluginVerifier.versionInt(stored.manifest.version)).toLong(),
                signingKeyFingerprint = fingerprints[url].orEmpty(),
            )
        }
    }

    /** Startup: reads developer mode. Until it is loaded it counts as off. */
    public suspend fun loadDeveloperMode() {
        pluginDeveloperMode.load()
    }

    /**
     * Startup: moves BiliManga library rows to the standalone package once that package's source
     * is installed. Never turns a readable local reviewed favorite into an unavailable remote row.
     */
    public suspend fun migrateBiliMangaStandaloneLibrary() {
        if (pluginManager.source(BILIMANGA_MANGA_SOURCE_ID) != null) {
            biliMangaStandaloneLibraryMigration?.migrate()
        }
    }

    /**
     * Startup: moves a proxy key still held in the settings snapshot to the protected store, puts
     * the protected value back into the settings (where the plugin network reads it), then keeps
     * the store in step with later edits on [backgroundScope].
     */
    public suspend fun hydrateProxySecret() {
        val legacyValue = repository.currentSnapshot.settings.advanced.proxyApiKey
        val secureValue = keyValueStore.getString(PROXY_API_KEY_STORAGE_KEY)
        val effectiveValue = secureValue ?: legacyValue
        if (secureValue == null && legacyValue.isNotEmpty()) {
            keyValueStore.putString(PROXY_API_KEY_STORAGE_KEY, legacyValue)
        }
        if (legacyValue.isNotEmpty() || legacyValue != effectiveValue) {
            repository.updateSettings { settings ->
                settings.copy(advanced = settings.advanced.copy(proxyApiKey = effectiveValue))
            }
        }
        if (proxySecretPersistenceJob == null) {
            proxySecretPersistenceJob = backgroundScope.launch {
                repository.snapshot
                    .map { it.settings.advanced.proxyApiKey }
                    .distinctUntilChanged()
                    .collect { value ->
                        if (value.isEmpty()) keyValueStore.remove(PROXY_API_KEY_STORAGE_KEY)
                        else keyValueStore.putString(PROXY_API_KEY_STORAGE_KEY, value)
                    }
            }
        }
    }

    /** Close: stops keeping the proxy key in step (with the other background jobs). */
    public fun cancelProxySecretPersistence() {
        proxySecretPersistenceJob?.cancel()
    }

    /** Close: stops the logout confirmations' scope and waits for it. */
    public suspend fun cancelEventUi() {
        pluginEventUiJob.cancelAndJoin()
    }

    /** Close: closes the package runtimes. */
    public suspend fun closeRuntimes() {
        pluginManager.close()
    }

    /** Close: closes the platform browser sessions. */
    public suspend fun closeBrowserSessions() {
        browserSessionTransport.close()
    }

    /** Close: closes the system-event gateway. */
    public fun closeEventGateway() {
        pluginEventGateway.close()
    }

    /** Close: drops the diagnostic log. */
    public fun clearDiagnostics() {
        pluginDiagnosticLog.clear()
    }

    /** Close: closes the plugin HTTP client (after the snapshot's last flush). */
    public fun closeHttpClient() {
        pluginHttpClient.close()
    }

    public companion object {
        private const val PROXY_API_KEY_STORAGE_KEY = "network.proxy.secret"

        /**
         * Builds the plugin graph from the [platform] inputs, the shared [contentFoundation] (null
         * in previews) and [backgroundScope], the app's background scope, which runs the account
         * suggestions and the proxy key persistence and stays owned (and cancelled) by the app.
         */
        public fun create(
            repository: ShinsouRepository,
            platform: PlatformInputs,
            contentFoundation: ContentFoundationRuntime?,
            backgroundScope: CoroutineScope,
            defaultDispatcher: CoroutineDispatcher,
        ): PluginSubsystem = PluginSubsystem(repository, platform, contentFoundation, backgroundScope, defaultDispatcher)
    }
}
