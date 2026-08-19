package dev.shinsou.kmp.app

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.backup.AutoBackupService
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Shared dependency graph used unchanged by Android, iOS and Desktop. */
public class ShinsouComposition(
    public val repository: ShinsouRepository,
    private val httpClient: HttpClient,
    private val pluginKeyValueStore: PluginKeyValueStore,
    fileSystem: AppFileSystem,
    runtimeFactory: ScriptPluginRuntimeFactory,
    autoBackupService: AutoBackupService? = null,
) {
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
    )

    /** Restores executable sources first, then resumes any persisted queue entries. */
    public suspend fun start() {
        hydrateAndPersistProxySecret()
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
        downloads.restoreQueue()
    }

    public suspend fun close() {
        proxySecretPersistenceJob?.cancel()
        portableRepositoryReconciliationJob?.cancel()
        trackerManager.cancelAll()
        trackingJob.cancelAndJoin()
        downloads.close()
        pluginManager.close()
        // Stop every mutation producer before flushing and terminating the coalesced writer.
        try {
            repository.closePersistence()
        } finally {
            pluginHttpClient.close()
            httpClient.close()
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
    }
}
