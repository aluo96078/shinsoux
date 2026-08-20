package dev.shinsou.kmp.app

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.backup.SyncAwareSnapshotRestore
import dev.shinsou.kmp.download.DownloadManager
import dev.shinsou.kmp.domain.model.TrackerIds
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.local.LocalContentManager
import dev.shinsou.kmp.plugin.ExtensionRepositoryClient
import dev.shinsou.kmp.plugin.FilePluginPackageStore
import dev.shinsou.kmp.plugin.ConfiguredPluginProxyResolver
import dev.shinsou.kmp.plugin.ConfiguredPluginUserAgentProvider
import dev.shinsou.kmp.plugin.KeyValueExtensionRepositoryStore
import dev.shinsou.kmp.plugin.KeyValuePluginPackageStore
import dev.shinsou.kmp.plugin.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.KeyValuePluginTrustStore
import dev.shinsou.kmp.plugin.KtorPluginHttpTransport
import dev.shinsou.kmp.plugin.PluginBrowseAdapter
import dev.shinsou.kmp.plugin.PluginKeyValueStore
import dev.shinsou.kmp.plugin.PluginLoginRequestCoordinator
import dev.shinsou.kmp.plugin.PluginManager
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.PluginNetworkConfiguration
import dev.shinsou.kmp.plugin.PluginNetworkConfigurationProvider
import dev.shinsou.kmp.plugin.PluginRequestBuilder
import dev.shinsou.kmp.plugin.PluginVerifier
import dev.shinsou.kmp.plugin.RepositoryPluginCoordinator
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.ScriptPluginRuntimeFactory
import dev.shinsou.kmp.tracking.AniListAuthenticator
import dev.shinsou.kmp.tracking.AniListTracker
import dev.shinsou.kmp.tracking.AniListTrackerConfig
import dev.shinsou.kmp.tracking.KeyValueTokenStore
import dev.shinsou.kmp.tracking.TrackerDescriptor
import dev.shinsou.kmp.tracking.TrackerManager
import dev.shinsou.kmp.tracking.TrackerManagerAdapter
import dev.shinsou.kmp.tracking.TrackerScoreFormat
import dev.shinsou.kmp.tracking.TrackingCoordinator
import dev.shinsou.kmp.tracking.TrackingProvider
import dev.shinsou.kmp.ui.ContentCallbacks
import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import dev.shinsou.kmp.sync.crypto.SodiumSyncCrypto
import dev.shinsou.kmp.sync.network.KtorCloudflareProvisioningApi
import dev.shinsou.kmp.sync.network.KtorSyncControlPlaneApi
import dev.shinsou.kmp.sync.network.createSyncHttpClient
import dev.shinsou.kmp.sync.provisioning.CloudflareProvisioningConfiguration
import dev.shinsou.kmp.sync.provisioning.DefaultCloudflareSyncUiController
import dev.shinsou.kmp.sync.provisioning.DefaultSyncRecoveryUiDelegate
import dev.shinsou.kmp.sync.provisioning.SyncProvisioningCrypto
import dev.shinsou.kmp.sync.trust.PinnedDevicePublicKeyResolver
import dev.shinsou.kmp.sync.trust.RecoveryKitManager
import dev.shinsou.kmp.sync.v2.CloudflareSyncUiController
import dev.shinsou.kmp.sync.v2.ShinsouSyncRuntime
import dev.shinsou.kmp.sync.v2.SyncRecoveryCoordinator
import dev.shinsou.kmp.sync.v2.SyncPlatformInfrastructure
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Shared dependency graph used unchanged by Android, iOS and Desktop. */
public class ShinsouComposition(
    public val repository: ShinsouRepository,
    private val httpClient: HttpClient,
    private val pluginKeyValueStore: PluginKeyValueStore,
    fileSystem: AppFileSystem,
    runtimeFactory: ScriptPluginRuntimeFactory,
    autoBackupService: AutoBackupService? = null,
    private val syncInfrastructure: SyncPlatformInfrastructure? = null,
) {
    private val mutableSyncBoundaryReady = MutableStateFlow(syncInfrastructure == null)
    /** UI remains non-interactive until persisted provider ownership is enforced. */
    public val syncBoundaryReady: StateFlow<Boolean> = mutableSyncBoundaryReady.asStateFlow()

    /** Null only in previews/tests that intentionally omit platform sync storage. */
    public val syncRuntime: ShinsouSyncRuntime? = syncInfrastructure?.let { infrastructure ->
        ShinsouSyncRuntime(
            repository = repository,
            platformInfrastructure = infrastructure,
            platformHttpClient = httpClient,
        )
    }
    /** Single safe restore/reset entry point shared by foreground UI and automatic backup flows. */
    public val syncAwareSnapshotRestore: SyncAwareSnapshotRestore? = syncRuntime?.let { runtime ->
        SyncAwareSnapshotRestore(
            repository = repository,
            sessionStore = requireNotNull(syncInfrastructure).sessionStore,
            workspaceDeparture = runtime,
        )
    }
    private val provisioningHttpClient: HttpClient? = syncInfrastructure?.let {
        createSyncHttpClient(httpClient)
    }
    private val pluginHttpClient = httpClient.config { followRedirects = false }
    private val pluginStorage = KeyValuePluginStorage(pluginKeyValueStore)
    private val trustStore = KeyValuePluginTrustStore(pluginKeyValueStore)
    private val repositoryClient = ExtensionRepositoryClient(httpClient)
    private val repositoryStore = KeyValueExtensionRepositoryStore(pluginKeyValueStore)
    private val pluginLoginRequests = PluginLoginRequestCoordinator()
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
        userAgents = ConfiguredPluginUserAgentProvider(networkConfiguration),
        proxyResolver = ConfiguredPluginProxyResolver(pluginStorage, networkConfiguration),
    )
    private val pluginNetwork = PluginNetworkClient(
        transport = KtorPluginHttpTransport(pluginHttpClient),
        storage = pluginStorage,
        requestBuilder = requestBuilder,
    )
    private val pluginManager = PluginManager(
        repositoryClient = repositoryClient,
        packageStore = FilePluginPackageStore(
            fileSystem = fileSystem,
            legacyStore = KeyValuePluginPackageStore(pluginKeyValueStore),
        ),
        verifier = PluginVerifier(trustStore),
        runtimeFactory = runtimeFactory,
        environment = ScriptPluginEnvironment(
            network = pluginNetwork,
            storage = pluginStorage,
            loginRequester = pluginLoginRequests,
        ),
    )
    private val coordinator = RepositoryPluginCoordinator(
        repository = repository,
        manager = pluginManager,
        network = pluginNetwork,
        requestBuilder = requestBuilder,
        fileSystem = fileSystem,
    )
    private val trackingJob = SupervisorJob()
    private val trackingScope = CoroutineScope(trackingJob + Dispatchers.Default)
    /** Production provisioning/device/recovery facade shared by every platform settings screen. */
    public val cloudflareSync: CloudflareSyncUiController? = syncInfrastructure?.let { infrastructure ->
        val syncClient = requireNotNull(provisioningHttpClient)
        val codec = DeterministicSyncEventCodec()
        val resolver = PinnedDevicePublicKeyResolver(infrastructure.deviceDirectoryPinStore) {
            infrastructure.sessionStore.load()?.workspaceId
        }
        val crypto = SodiumSyncCrypto(
            secretStore = infrastructure.secretStore,
            codec = codec,
            devicePublicKeyResolver = resolver,
        )
        val recoveryKitManager = RecoveryKitManager(infrastructure.secretStore)
        val nowMillis = { Clock.System.now().toEpochMilliseconds() }
        val provisioningApi = KtorCloudflareProvisioningApi(
            client = syncClient,
            secretStore = infrastructure.secretStore,
            crypto = crypto,
            nowMillis = nowMillis,
        )
        val recoveryCoordinator = SyncRecoveryCoordinator(
            api = KtorSyncControlPlaneApi(syncClient, infrastructure.secretStore),
            crypto = crypto,
            secretStore = infrastructure.secretStore,
            recoveryKitManager = recoveryKitManager,
            nowMillis = nowMillis,
        )
        val runtime = requireNotNull(syncRuntime)
        DefaultCloudflareSyncUiController(
            scope = trackingScope,
            configuration = CloudflareProvisioningConfiguration(
                deployUrl = CLOUDFLARE_DEPLOY_URL,
                userDisplayName = DEFAULT_SYNC_USER_NAME,
                deviceDisplayName = infrastructure.deviceDisplayName,
                platform = infrastructure.platform,
            ),
            installationStore = infrastructure.installationStore,
            sessionStore = infrastructure.sessionStore,
            secretStore = infrastructure.secretStore,
            api = provisioningApi,
            provisioningCrypto = SyncProvisioningCrypto(
                secretStore = infrastructure.secretStore,
                crypto = crypto,
                recoveryKitManager = recoveryKitManager,
                nowMillis = nowMillis,
            ),
            activationGate = runtime,
            recoveryDelegate = DefaultSyncRecoveryUiDelegate(
                provisioningApi = provisioningApi,
                recoveryKitManager = recoveryKitManager,
                recoveryCoordinator = recoveryCoordinator,
                sessionStore = infrastructure.sessionStore,
                activationGate = runtime,
            ),
            nowMillis = nowMillis,
        )
    }
    private val aniListTracker = AniListTracker(
        client = httpClient,
        tokenStore = KeyValueTokenStore(pluginKeyValueStore),
        config = AniListTrackerConfig(clientId = ANI_LIST_CLIENT_ID),
    )
    private val trackerManager = TrackerManager(
        adapters = listOf(aniListTracker),
        repository = repository,
        scope = trackingScope,
    )
    private var proxySecretPersistenceJob: Job? = null
    private var portableRepositoryReconciliationJob: Job? = null
    /** Optional source/tracking hydration must never hold the first Compose frame hostage. */
    private var startupHydrationJob: Job? = null

    public val tracking: TrackingCoordinator = TrackingCoordinator(
        manager = TrackerManagerAdapter(trackerManager, repository),
        providers = listOf(
            TrackingProvider(
                descriptor = TrackerDescriptor(
                    id = TrackerIds.MY_ANIME_LIST,
                    name = "MyAnimeList",
                    scoreFormat = TrackerScoreFormat.POINT_10,
                ),
                configured = false,
                configurationMessage = "MyAnimeList client ID 尚未設定，暫時無法登入。",
            ),
            TrackingProvider(descriptor = aniListTracker.descriptor),
        ),
        authenticators = listOf(AniListAuthenticator(aniListTracker)),
    )

    public val downloads: DownloadManager = DownloadManager(
        repository = repository,
        fileSystem = fileSystem,
        pageProvider = coordinator,
        pageFetcher = coordinator,
    ).also(coordinator::attachDownloadManager)

    public val browse: PluginBrowseAdapter = PluginBrowseAdapter(
        manager = pluginManager,
        repositoryClient = repositoryClient,
        repositoryStore = repositoryStore,
        pluginStorage = pluginStorage,
        keyValueStore = pluginKeyValueStore,
        trustStore = trustStore,
        mangaResolver = coordinator,
        migrationHandler = coordinator,
        migrationProvider = coordinator,
        requestBuilder = requestBuilder,
        portableRepository = repository,
        loginRequestCoordinator = pluginLoginRequests,
    )

    public val localContent: LocalContentManager = LocalContentManager(
        repository = repository,
        fileSystem = fileSystem,
        remote = coordinator,
    )

    public val content: ContentCallbacks = localContent

    /** App-private, recoverable backups shared by foreground UI and platform schedulers. */
    public val autoBackups: AutoBackupService = autoBackupService ?: AutoBackupService(
        repository = repository,
        fileSystem = fileSystem,
        syncAwareRestore = syncAwareSnapshotRestore,
    )

    /**
     * Installs the sync boundary and returns as soon as the first frame is allowed to render.
     * Source discovery, secure proxy hydration, tracker auth and download queue restoration are
     * useful startup work, but none is required to draw or interact with the local library, so
     * they continue on the background tracking scope.
     */
    public suspend fun start() {
        // The v2 observer/guard must be installed before proxy hydration, source refresh,
        // tracking or downloads can publish repository mutations.
        syncRuntime?.start()
        mutableSyncBoundaryReady.value = true
        if (startupHydrationJob?.isActive == true) return
        startupHydrationJob = trackingScope.launch {
            try {
                hydrateAndPersistProxySecret()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Secure proxy hydration can be retried from settings when keychain access returns.
            }
            try {
                browse.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // PluginBrowseAdapter publishes its actionable error in BrowseSnapshot.
            }
            if (portableRepositoryReconciliationJob == null) {
                portableRepositoryReconciliationJob = trackingScope.launch {
                    repository.snapshot
                        .map { it.extensionRepositories }
                        .distinctUntilChanged()
                        .drop(1)
                        .collect {
                            try {
                                browse.refresh()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                // BrowseSnapshot already exposes the repository refresh failure.
                            }
                        }
                }
            }
            try {
                tracking.refreshAuthenticationState(TrackerIds.ANI_LIST)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Login can still be retried from the tracking sheet if secure storage is unavailable.
            }
            try {
                downloads.restoreQueue()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A corrupt queue entry must not prevent the rest of the app from opening.
            }
        }
    }

    /** Performs immediate catch-up and restores realtime delivery after a platform foreground edge. */
    public suspend fun onForeground() {
        syncRuntime?.onForeground()
    }

    /** Flushes reader state/outbox within the engine's bounded background window. */
    public suspend fun onBackground() {
        syncRuntime?.onBackground() ?: repository.flushPersistence()
    }

    /** A persisted v2 session owns the remote writer even while linking, revoked or unreadable. */
    public suspend fun isLegacySnapshotWriterAllowed(): Boolean =
        syncRuntime?.isLegacySnapshotWriterAllowed() ?: true

    /** Stable random installation identity used by legacy envelopes and control-plane setup. */
    public suspend fun installationDeviceId(): String =
        syncInfrastructure?.installationStore?.loadOrCreate()?.deviceId
            ?: error("Platform sync infrastructure is unavailable")

    public suspend fun close() {
        startupHydrationJob?.cancel()
        startupHydrationJob = null
        proxySecretPersistenceJob?.cancel()
        portableRepositoryReconciliationJob?.cancel()
        trackerManager.cancelAll()
        trackingJob.cancelAndJoin()
        downloads.close()
        pluginManager.close()
        // Stop every mutation producer before flushing and terminating the coalesced writer.
        try {
            syncRuntime?.close()
        } finally {
            try {
                repository.closePersistence()
            } finally {
                try {
                    provisioningHttpClient?.close()
                } finally {
                    pluginHttpClient.close()
                    httpClient.close()
                }
            }
        }
    }

    private suspend fun hydrateAndPersistProxySecret() {
        val legacyValue = repository.currentSnapshot.settings.advanced.proxyApiKey
        val secureValue = pluginKeyValueStore.getString(PROXY_API_KEY_STORAGE_KEY)
        val effectiveValue = secureValue ?: legacyValue
        if (secureValue == null && legacyValue.isNotEmpty()) {
            pluginKeyValueStore.putString(PROXY_API_KEY_STORAGE_KEY, legacyValue)
        }
        if (legacyValue.isNotEmpty() || legacyValue != effectiveValue) {
            repository.updateSettings { settings ->
                settings.copy(advanced = settings.advanced.copy(proxyApiKey = effectiveValue))
            }
        }
        if (proxySecretPersistenceJob == null) {
            proxySecretPersistenceJob = trackingScope.launch {
                repository.snapshot
                    .map { it.settings.advanced.proxyApiKey }
                    .distinctUntilChanged()
                    .collect { value ->
                        if (value.isEmpty()) pluginKeyValueStore.remove(PROXY_API_KEY_STORAGE_KEY)
                        else pluginKeyValueStore.putString(PROXY_API_KEY_STORAGE_KEY, value)
                    }
            }
        }
    }

    private companion object {
        const val ANI_LIST_CLIENT_ID = "16329"
        const val PROXY_API_KEY_STORAGE_KEY = "network.proxy.secret"
        const val DEFAULT_SYNC_USER_NAME = "Shinsou X user"
        const val CLOUDFLARE_DEPLOY_URL =
            "https://deploy.workers.cloudflare.com/?url=https://github.com/aluo96078/shinsoux/tree/master/syncWorker"
    }
}
