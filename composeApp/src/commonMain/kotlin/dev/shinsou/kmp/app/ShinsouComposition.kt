package dev.shinsou.kmp.app

import dev.shinsou.kmp.acquisition.BoundedEpubArchiveExtractor
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.backup.BackupV2AttachmentCandidate
import dev.shinsou.kmp.backup.BackupV2CreatePolicy
import dev.shinsou.kmp.backup.BackupV2PortableState
import dev.shinsou.kmp.backup.PortableContentBackupV2RestoreCoordinator
import dev.shinsou.kmp.backup.PortableContentBackupV2Service
import dev.shinsou.kmp.backup.SyncAwareSnapshotRestore
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentBlobRecoveryCoordinator
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.HostContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.download.DownloadManager
import dev.shinsou.kmp.domain.model.TrackerIds
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.local.LocalContentManager
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.domain.model.InMemoryPortableAliasLedger
import dev.shinsou.kmp.domain.model.LegacyPublicationBundle
import dev.shinsou.kmp.migration.shuyue.ShuYueCompatibilityProjectionCoordinator
import dev.shinsou.kmp.migration.shuyue.ShuYueMigrationSecretStore
import dev.shinsou.kmp.migration.shuyue.ShuYueSyncV2OutboxFactory
import dev.shinsou.kmp.migration.shuyue.ShuYueTransactionalImporter
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
import dev.shinsou.kmp.plugin.PluginLogoutConfirmation
import dev.shinsou.kmp.plugin.PluginLogoutRequestCoordinator
import dev.shinsou.kmp.plugin.PluginManager
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.PluginNetworkConfiguration
import dev.shinsou.kmp.plugin.PluginNetworkConfigurationProvider
import dev.shinsou.kmp.plugin.PluginRequestBuilder
import dev.shinsou.kmp.plugin.PluginVerifier
import dev.shinsou.kmp.plugin.RepositoryPluginCoordinator
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.ScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.events.MutablePluginSystemEventAuthorizer
import dev.shinsou.kmp.plugin.events.PluginDiagnosticPort
import dev.shinsou.kmp.plugin.events.PluginEventOutcome
import dev.shinsou.kmp.plugin.events.PluginLoginIntentPort
import dev.shinsou.kmp.plugin.events.PluginLogoutPort
import dev.shinsou.kmp.plugin.events.PluginSourceRefreshPort
import dev.shinsou.kmp.plugin.events.PluginSystemEventGateway
import dev.shinsou.kmp.plugin.events.PluginSystemEventHandlerRegistry
import dev.shinsou.kmp.plugin.events.PluginSystemEventHostPorts
import dev.shinsou.kmp.plugin.events.registerV1HostHandlers
import dev.shinsou.kmp.plugin.events.BoundedPluginDiagnosticLog
import dev.shinsou.kmp.plugin.events.ExactSourceRefreshInvalidations
import dev.shinsou.kmp.plugin.events.KeyValuePluginEventGrantAdmission
import dev.shinsou.kmp.plugin.events.PluginEventObserver
import dev.shinsou.kmp.plugin.events.PluginEventObserverGroup
import dev.shinsou.kmp.plugin.events.PluginEventContextRegistry
import dev.shinsou.kmp.plugin.shuyue.KtorShuYueRepositoryTransport
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryIndexLoader
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryLimits
import dev.shinsou.kmp.plugin.shuyue.ShuYueReviewedRepositoryCoordinatorV2
import dev.shinsou.kmp.plugin.shuyue.BuiltInShuYueExecutionScopesV2
import dev.shinsou.kmp.plugin.shuyue.ShuYueArtifactIdentityV2
import dev.shinsou.kmp.plugin.v2.ExtensionBrowseContentGatewayV2
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerV2
import dev.shinsou.kmp.plugin.v2.ExtensionSourceResolverV2
import dev.shinsou.kmp.plugin.v2.PluginNetworkExtensionResourceFetcherV2
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
import dev.shinsou.kmp.sync.persistence.SqlDriverBlobLifecycleJournalV2
import dev.shinsou.kmp.sync.persistence.SqlDriverBlobTransferJournalV2
import dev.shinsou.kmp.sync.provisioning.CloudflareProvisioningConfiguration
import dev.shinsou.kmp.sync.provisioning.DefaultCloudflareSyncUiController
import dev.shinsou.kmp.sync.provisioning.DefaultSyncRecoveryUiDelegate
import dev.shinsou.kmp.sync.provisioning.SyncProvisioningCrypto
import dev.shinsou.kmp.sync.trust.PinnedDevicePublicKeyResolver
import dev.shinsou.kmp.sync.trust.RecoveryKitManager
import dev.shinsou.kmp.sync.v2.CloudflareSyncUiController
import dev.shinsou.kmp.sync.v2.BlobReuploadRecoveryCoordinatorV2
import dev.shinsou.kmp.sync.v2.ContentReplicaMaterializationStatusV2
import dev.shinsou.kmp.sync.v2.ShinsouSyncRuntime
import dev.shinsou.kmp.sync.v2.SyncDraft
import dev.shinsou.kmp.sync.v2.SyncProvider
import dev.shinsou.kmp.sync.v2.SyncRecoveryCoordinator
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.SyncPlatformInfrastructure
import dev.shinsou.kmp.tts.PlatformTextToSpeechEngine
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import dev.shinsou.kmp.ui.portability.DefaultPortableContentBackupV2UiController
import dev.shinsou.kmp.ui.portability.DefaultShuYueMigrationUiController
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2CreateAction
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2RestoreAction
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2UiController
import dev.shinsou.kmp.ui.portability.ShuYueContentImportAction
import dev.shinsou.kmp.ui.portability.ShuYueMigrationUiController
import dev.shinsou.kmp.ui.portability.ShuYueSecretImportAction
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.rights.RightsScope
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
    private val platformTextToSpeechEngine: PlatformTextToSpeechEngine? = null,
    private val shuYueMigrationSecretStore: ShuYueMigrationSecretStore? = null,
) {
    private val mutableSyncBoundaryReady = MutableStateFlow(syncInfrastructure == null)
    /** UI remains non-interactive until persisted provider ownership is enforced. */
    public val syncBoundaryReady: StateFlow<Boolean> = mutableSyncBoundaryReady.asStateFlow()

    /** Unified M1 storage graph. Platform infrastructure remains the single SQL driver owner. */
    public val contentFoundation: ContentFoundationRuntime? = syncInfrastructure?.let { infrastructure ->
        ContentFoundationRuntime(
            driver = infrastructure.contentDriver(),
            contentBlobDirectoryPath = infrastructure.contentBlobDirectory(),
        )
    }
    /** Null only in previews/tests that intentionally omit platform sync storage. */
    public val syncRuntime: ShinsouSyncRuntime? = syncInfrastructure?.let { infrastructure ->
        ShinsouSyncRuntime(
            repository = repository,
            platformInfrastructure = infrastructure,
            platformHttpClient = httpClient,
            contentStore = contentFoundation?.transactions,
        )
    }
    /** Search, annotation, speech, and named rights operations share M1's driver and authority. */
    public val contentFeatures: ContentFeatureRuntime? = contentFoundation?.let { foundation ->
        ContentFeatureRuntime(
            foundation = foundation,
            platformTextToSpeechEngine = platformTextToSpeechEngine,
            nowEpochMillis = { Clock.System.now().toEpochMilliseconds() },
            onAnnotationMutationCommitted = ::scheduleAnnotationReconciliation,
            onRightsInvalidated = ::scheduleSearchIndexReconciliation,
        )
    }
    /** Compatibility projection remains available in previews which intentionally omit SQLite. */
    public val legacyPublicationProjection: LegacyPublicationProjection =
        contentFoundation?.legacyProjection ?: LegacyPublicationProjection(InMemoryPortableAliasLedger())
    /** Versioned M1 migration runs after the first interactive frame on the background scope. */
    private val legacyPublicationStartupMigration: LegacyPublicationStartupMigration? =
        contentFoundation?.let(::LegacyPublicationStartupMigration)
    /** Derived ShuYue compatibility rows are restart-safe and never become content authority. */
    private val shuYueCompatibilityProjection: ShuYueCompatibilityProjectionCoordinator? =
        contentFoundation?.let { foundation ->
            ShuYueCompatibilityProjectionCoordinator(repository, foundation)
        }

    /** On-demand only: this never installs a competing repository/sync mutation observer. */
    public fun currentLegacyPublications(): List<LegacyPublicationBundle> =
        legacyPublicationProjection.project(repository.currentSnapshot)
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
    private val reviewedShuYueRepositoryLoader = ShuYueRepositoryIndexLoader(
        transport = KtorShuYueRepositoryTransport(pluginHttpClient),
        limits = ShuYueRepositoryLimits(
            allowedArtifactOrigins = setOf(
                ShuYueReviewedRepositoryCoordinatorV2.DEFAULT_REVIEWED_SHUYUE_ARTIFACT_ORIGIN,
            ),
            // The user may explicitly point the app at the local/LAN ShuYue development server.
            // Only loopback/private origins are added; public artifacts remain GitHub-pinned.
            allowLocalArtifactOrigins = true,
        ),
    )
    private val pluginStorage = KeyValuePluginStorage(pluginKeyValueStore)
    private val trustStore = KeyValuePluginTrustStore(pluginKeyValueStore)
    private val repositoryClient = ExtensionRepositoryClient(httpClient)
    private val repositoryStore = KeyValueExtensionRepositoryStore(pluginKeyValueStore)
    private val pluginLoginRequests = PluginLoginRequestCoordinator()
    private val pluginEventUiJob = SupervisorJob()
    private val pluginLogoutRequests = PluginLogoutRequestCoordinator(
        CoroutineScope(pluginEventUiJob + Dispatchers.Default),
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
        userAgents = ConfiguredPluginUserAgentProvider(networkConfiguration),
        proxyResolver = ConfiguredPluginProxyResolver(pluginStorage, networkConfiguration),
    )
    private val pluginNetwork = PluginNetworkClient(
        transport = KtorPluginHttpTransport(pluginHttpClient),
        storage = pluginStorage,
        requestBuilder = requestBuilder,
    )
    private val pluginEventAuthorizer: MutablePluginSystemEventAuthorizer = MutablePluginSystemEventAuthorizer()
    private val pluginEventContextRegistry: PluginEventContextRegistry = PluginEventContextRegistry()
    private val pluginEventGrantAdmission = KeyValuePluginEventGrantAdmission(
        store = pluginKeyValueStore,
        authorizer = pluginEventAuthorizer,
    )
    private val pluginDiagnosticLog = BoundedPluginDiagnosticLog()
    private val pluginSourceInvalidations = ExactSourceRefreshInvalidations()
    private val pluginLifecycleObserver = object : PluginEventObserver {
        override fun onCompleted(report: dev.shinsou.kmp.plugin.events.PluginEventExecutionReport) = Unit
        override fun onRuntimeClosed(scope: dev.shinsou.kmp.plugin.events.BoundPluginScope) {
            val target = dev.shinsou.kmp.plugin.events.ExactPluginSourceTarget(
                scope.artifactIdentity,
                scope.sourceKey,
            )
            pluginSourceInvalidations.clear(target)
            pluginLoginRequests.clearTarget(target)
            pluginLogoutRequests.clearSource(target)
        }
        override fun onArtifactInvalidated(identity: dev.shinsou.kmp.plugin.events.PluginArtifactIdentity) {
            pluginLoginRequests.clearArtifact(identity)
            pluginLogoutRequests.clearArtifact(identity)
        }
    }
    private val pluginEventRegistry: PluginSystemEventHandlerRegistry = PluginSystemEventHandlerRegistry().apply {
        registerV1HostHandlers(
            PluginSystemEventHostPorts(
                login = PluginLoginIntentPort { target, eventId, payload ->
                    if (pluginLogoutRequests.hasTarget(target)) {
                        return@PluginLoginIntentPort PluginEventOutcome.Suppressed
                    }
                    val sourceKey = target.sourceKey
                    val legacy = pluginManager.exactLegacySource(target)
                    val exactV2 = pluginManager.exactExtensionSource(target)
                    val sourceId = sourceKey.legacyLongId ?: runCatching {
                        val artifact = target.artifactIdentity
                        BuiltInShuYueExecutionScopesV2.resolve(
                            ShuYueArtifactIdentityV2(
                                artifact.packageId,
                                artifact.version,
                                artifact.versionCode,
                                artifact.sha256,
                            ),
                            sourceKey,
                        )
                    }.getOrNull() ?: return@PluginLoginIntentPort PluginEventOutcome.Suppressed
                    val sourceName = legacy?.name ?: exactV2?.descriptor?.displayName
                        ?: return@PluginLoginIntentPort PluginEventOutcome.Suppressed
                    if (pluginLoginRequests.requestEvent(eventId, target, sourceId, sourceName, payload.fallbackMessage)) {
                        PluginEventOutcome.Succeeded
                    } else {
                        PluginEventOutcome.Suppressed
                    }
                },
                refresh = PluginSourceRefreshPort { target, _, _ ->
                    val sourceKey = target.sourceKey
                    // This deliberately checks only the exact live source. It never calls the
                    // repository/global refresh coordinator.
                    val exactV2 = pluginManager.exactExtensionSource(target)
                    val legacy = pluginManager.exactLegacySource(target)
                    if (exactV2 != null || legacy != null) {
                        pluginSourceInvalidations.invalidate(target)
                        PluginEventOutcome.Succeeded
                    } else PluginEventOutcome.Suppressed
                },
                logout = PluginLogoutPort { target, eventId, payload ->
                    // Logout wins the exact-source modal conflict and expires only that login.
                    pluginLoginRequests.clearTarget(target)
                    val sourceKey = target.sourceKey
                    val exactName = pluginManager.exactExtensionSource(target)?.descriptor?.displayName
                    val legacyName = pluginManager.exactLegacySource(target)?.name
                    val sourceName = exactName ?: legacyName
                        ?: return@PluginLogoutPort PluginEventOutcome.Suppressed
                    if (pluginLogoutRequests.request(PluginLogoutConfirmation(
                            eventId = eventId,
                            target = target,
                            sourceName = sourceName,
                            message = payload.fallbackMessage,
                        ))) PluginEventOutcome.Succeeded else PluginEventOutcome.Suppressed
                },
                diagnostic = PluginDiagnosticPort { target, eventId, count, payload ->
                    // REPORT_DIAGNOSTIC is log-only. User projection additionally requires the
                    // separate REPORT_USER_MESSAGE exact grant and a host presenter.
                    pluginDiagnosticLog.report(target, eventId, payload, count)
                },
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
            legacyStore = KeyValuePluginPackageStore(pluginKeyValueStore),
        ),
        verifier = PluginVerifier(trustStore),
        runtimeFactory = runtimeFactory,
        environment = ScriptPluginEnvironment(
            network = pluginNetwork,
            storage = pluginStorage,
            loginRequester = pluginLoginRequests,
            systemEventSink = pluginEventGateway,
            systemEventContextRegistry = pluginEventContextRegistry,
        ),
        eventGrantAdmission = pluginEventGrantAdmission,
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

    /** Staged ShuYue migration. A configured-but-unready workspace rejects content before commit. */
    public val shuYueMigration: ShuYueMigrationUiController? = contentFoundation?.let { foundation ->
        val offlineStoreAuthorizer = HostContentBodyOfflineStoreAuthorizer(
            authority = foundation.rightsAuthority,
            durableGrant = foundation.rightsGrants::find,
            nowEpochMillis = { Clock.System.now().toEpochMilliseconds() },
        )
        val localImporter = ShuYueTransactionalImporter(
            blobStore = foundation.blobStore,
            transactionStore = foundation.transactions,
            syncActive = { false },
            // Drafts and an explicitly selected body intent remain durable while no workspace is
            // configured; a future v2 session can clock and drain them without re-reading backup.
            outboxFactory = ShuYueSyncV2OutboxFactory,
            offlineStoreAuthorizer = offlineStoreAuthorizer,
        )
        DefaultShuYueMigrationUiController(
            contentImportAction = ShuYueContentImportAction { prepared, selection ->
                val session = syncInfrastructure?.sessionStore?.load()
                val cloudflareConfigured = session?.provider == SyncProvider.CLOUDFLARE_V2
                val outboxFactory = when {
                    !cloudflareConfigured -> ShuYueSyncV2OutboxFactory
                    session?.status == SyncSessionStatus.READY -> ShuYueSyncV2OutboxFactory
                    else -> error(
                        "Active Cloudflare sync cannot journal a ShuYue import in its current state",
                    )
                }
                val result = ShuYueTransactionalImporter(
                    blobStore = foundation.blobStore,
                    transactionStore = foundation.transactions,
                    syncActive = { cloudflareConfigured },
                    outboxFactory = outboxFactory,
                    offlineStoreAuthorizer = offlineStoreAuthorizer,
                ).import(prepared, selection)
                requireNotNull(shuYueCompatibilityProjection).repair()
                scheduleSearchIndexReconciliation()
                result
            },
            secretImportAction = shuYueMigrationSecretStore?.let { protectedStore ->
                ShuYueSecretImportAction { prepared, consent ->
                    localImporter.importSecrets(prepared, consent, protectedStore)
                }
            },
        )
    }

    /** Checksummed binary export plus the only atomic, sync-aware content-v2 restore boundary. */
    public val portableContentBackupV2: PortableContentBackupV2UiController? =
        contentFoundation?.let { foundation ->
            val features = requireNotNull(contentFeatures)
            val infrastructure = requireNotNull(syncInfrastructure)
            val offlineStoreAuthorizer = HostContentBodyOfflineStoreAuthorizer(
                authority = foundation.rightsAuthority,
                durableGrant = foundation.rightsGrants::find,
                nowEpochMillis = { Clock.System.now().toEpochMilliseconds() },
            )
            val restoreCoordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
                sessionStore = infrastructure.sessionStore,
                workspaceDeparture = requireNotNull(syncRuntime),
                offlineStoreAuthorizer = offlineStoreAuthorizer,
            )
            DefaultPortableContentBackupV2UiController(
                createAction = PortableContentBackupV2CreateAction { includeContentBlobs ->
                    val publications = foundation.publications.all()
                    PortableContentBackupV2Service.create(
                        state = BackupV2PortableState(
                            legacySnapshot = repository.currentSnapshot,
                            publications = publications,
                            annotations = foundation.annotations.list(includeTombstones = true),
                            rightsGrants = foundation.rightsGrants.all(),
                            auxiliary = foundation.portableAuxiliaryState(),
                        ),
                        candidates = publications.backupV2Candidates(),
                        blobStore = foundation.blobStore,
                        operationGate = features.operationGate,
                        createdAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                        appVersion = CONTENT_BACKUP_APP_VERSION,
                        policy = BackupV2CreatePolicy(includeContentBlobs = includeContentBlobs),
                    )
                },
                restoreAction = PortableContentBackupV2RestoreAction { inspection, source, target ->
                    val result = restoreCoordinator.restore(inspection, source, target)
                    requireNotNull(shuYueCompatibilityProjection).repair()
                    scheduleSearchIndexReconciliation()
                    result
                },
            )
        }
    private val trackerManager = TrackerManager(
        adapters = emptyList(),
        repository = repository,
        scope = trackingScope,
    )
    private var proxySecretPersistenceJob: Job? = null
    private var portableRepositoryReconciliationJob: Job? = null
    private var contentOutboxDrainJob: Job? = null
    private var contentBlobDrainJob: Job? = null
    /** M1 orphan discovery/GC runs only after the app enters a background window. */
    private var contentBlobRecoveryJob: Job? = null
    /** Conflated derived-index invalidations; import/restore never waits for plaintext indexing. */
    private val searchIndexReconciliationSignals = Channel<Unit>(Channel.CONFLATED)
    private var searchIndexReconciliationJob: Job? = null
    /** Local edits and sync catch-up collapse into bounded, restart-safe reconciliation slices. */
    private val annotationReconciliationSignals = Channel<Unit>(Channel.CONFLATED)
    private var annotationReconciliationJob: Job? = null
    /** Created only for a background body slice; cold start never opens the transfer journal. */
    private val contentBlobTransferJournal by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        syncInfrastructure?.let { SqlDriverBlobTransferJournalV2(it.contentDriver()) }
    }
    /** Re-wrap/tombstone state is opened only inside the same cancellable background slice. */
    private val contentBlobLifecycleJournal by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        syncInfrastructure?.let { SqlDriverBlobLifecycleJournalV2(it.contentDriver()) }
    }
    /** A Worker-deleted body becomes durable upload work locally before its terminal intent clears. */
    private val contentBlobReuploadRecoveryCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val foundation = contentFoundation ?: return@lazy null
        val operationGate = contentFeatures?.operationGate ?: return@lazy null
        val journal = contentBlobLifecycleJournal ?: return@lazy null
        BlobReuploadRecoveryCoordinatorV2(
            contentStore = foundation.transactions,
            blobStore = foundation.blobStore,
            lifecycleJournal = journal,
            publications = foundation.publications::all,
            authorizeSync = { job ->
                operationGate.decide(
                    ContentAccessRequest(job.grantReference, job.accessScope),
                    ContentOperation.SYNC_BLOB,
                ) == RightsDecision.ALLOW
            },
        )
    }
    private val contentBlobRecoveryCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        contentFoundation?.let { foundation ->
            ContentBlobRecoveryCoordinator(
                blobStore = foundation.blobStore,
                minimumAgeMillis = CONTENT_BLOB_ORPHAN_MINIMUM_AGE_MILLIS,
            )
        }
    }
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
        ),
        authenticators = emptyList(),
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
        logoutRequestCoordinator = pluginLogoutRequests,
        exactSourceRefreshInvalidations = pluginSourceInvalidations,
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
                resourceFetcher = PluginNetworkExtensionResourceFetcherV2(pluginNetwork),
                nowEpochMillis = { Clock.System.now().toEpochMilliseconds() },
            )
        },
        reviewedShuYueRepositoryLoaderV2 = reviewedShuYueRepositoryLoader,
    )

    public val localContent: LocalContentManager = LocalContentManager(
        repository = repository,
        fileSystem = fileSystem,
        remote = coordinator,
        contentFoundation = contentFoundation,
        epubArchiveExtractor = BoundedEpubArchiveExtractor(),
        onTypedContentCommitted = ::scheduleSearchIndexReconciliation,
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
        scheduleContentOutboxDrain()
        if (startupHydrationJob?.isActive == true) return
        startupHydrationJob = trackingScope.launch {
            try {
                legacyPublicationStartupMigration?.migrate(repository.currentSnapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The transaction is atomic and restart-safe. A later launch retries the exact
                // deterministic projection without delaying this launch's interactive frame.
            }
            try {
                localContent.repairTypedContentLegacyProjection()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The typed Publication remains authoritative. Deterministic compatibility URLs
                // make this crash-gap repair safe to retry at the next launch.
            }
            try {
                shuYueCompatibilityProjection?.repair()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // ShuYue's typed transaction remains authoritative. The one-shot settings marker
                // and all compatibility rows share one CAS, so a later launch retries safely.
            }
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
        // Body bytes are strictly background-only. Cancellation is restart-safe because the
        // uploader persists its intent/receipt around every remote commit boundary.
        val previousBodyDrain = contentBlobDrainJob
        contentBlobDrainJob = null
        val previousRecovery = contentBlobRecoveryJob
        contentBlobRecoveryJob = null
        val previousSearchReconciliation = searchIndexReconciliationJob
        searchIndexReconciliationJob = null
        val previousAnnotationReconciliation = annotationReconciliationJob
        annotationReconciliationJob = null
        // Do not start foreground catch-up until local reads, crypto and R2 calls have observed
        // cancellation and released their resources. Search and annotation scans obey the same
        // boundary: no full-library derived-state work may compete with an interactive frame.
        previousBodyDrain?.cancel()
        previousRecovery?.cancel()
        previousSearchReconciliation?.cancel()
        previousAnnotationReconciliation?.cancel()
        previousBodyDrain?.join()
        previousRecovery?.join()
        previousSearchReconciliation?.join()
        previousAnnotationReconciliation?.join()
        syncRuntime?.onForeground()
        scheduleContentOutboxDrain()
    }

    /** Flushes reader state/outbox within the engine's bounded background window. */
    public suspend fun onBackground() {
        syncRuntime?.onBackground() ?: repository.flushPersistence()
        // Reader progress and the ordinary metadata journal always go first. The large encrypted
        // body is scheduled only after those foreground-sensitive writes have been flushed.
        scheduleContentOutboxDrain()
        scheduleContentBlobDrain()
        scheduleContentBlobRecovery()
        startSearchIndexReconciler()
        scheduleSearchIndexReconciliation()
        startAnnotationReconciler()
        scheduleAnnotationReconciliation()
    }

    private fun scheduleContentOutboxDrain() {
        val runtime = syncRuntime ?: return
        val store = contentFoundation?.transactions ?: return
        if (contentOutboxDrainJob?.isActive == true) return
        contentOutboxDrainJob = trackingScope.launch {
            try {
                repeat(MAX_CONTENT_OUTBOX_DRAIN_BATCHES) {
                    val result = runtime.drainContentOutbox(store) ?: return@launch
                    if (result.remaining == 0) return@launch
                    yield()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Sync persistence is a background concern. A stale/corrupt local shard must not
                // terminate the process before the library and reader can render; the next
                // foreground/background edge retries the durable outbox drain.
            }
        }
    }

    private fun scheduleContentBlobDrain() {
        val runtime = syncRuntime ?: return
        val foundation = contentFoundation ?: return
        val operationGate = contentFeatures?.operationGate ?: return
        if (syncInfrastructure == null) return
        if (contentBlobDrainJob?.isActive == true) return
        val precedingMetadataDrain = contentOutboxDrainJob
        contentBlobDrainJob = trackingScope.launch {
            try {
                precedingMetadataDrain?.join()
                yield()
                val materialized = runtime.drainContentReplicaMaterialization(
                    contentStore = foundation.transactions,
                    blobStore = foundation.blobStore,
                    operationGate = operationGate,
                    acquireValidatedRights = foundation::acquireProvisionalRightsAdmission,
                )
                // Download and upload share one low-priority budget. A slice that transferred a
                // destination body must yield until the next background edge.
                if (materialized?.status == ContentReplicaMaterializationStatusV2.DOWNLOADED_ONE_BLOB) {
                    return@launch
                }
                if (materialized?.status == ContentReplicaMaterializationStatusV2.COMMITTED) {
                    try {
                        shuYueCompatibilityProjection?.repair()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // A synchronized portable graph is already durable; compatibility rows
                        // remain derived and can be repaired on the next background/startup pass.
                    }
                    scheduleSearchIndexReconciliation()
                }
                val journal = contentBlobTransferJournal ?: return@launch
                val uploaded = runtime.drainContentBlobJobs(
                    contentStore = foundation.transactions,
                    blobStore = foundation.blobStore,
                    operationGate = operationGate,
                    journal = journal,
                    maxJobs = 1,
                )
                // One background edge owns at most one blob. Re-wrap/GC waits for the next edge
                // whenever upload recovery inspected a body job.
                if ((uploaded?.inspected ?: 0) > 0) return@launch
                val lifecycleJournal = contentBlobLifecycleJournal ?: return@launch
                try {
                    runtime.drainContentBlobLifecycle(
                        journal = lifecycleJournal,
                        authorizeBlobSync = { blobId ->
                            foundation.accessRequestsForBlob(blobId).any { access ->
                                operationGate.decide(access, ContentOperation.SYNC_BLOB) == RightsDecision.ALLOW
                            }
                        },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A retryable lifecycle request must not starve an older terminal recovery.
                }
                contentBlobReuploadRecoveryCoordinator?.let { recovery ->
                    runtime.drainContentBlobReuploadRecovery(recovery)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A body transfer is optional, resumable work. Its durable journal is retried on
                // the next background edge and must never make foreground interaction fail.
            }
        }
    }

    private fun scheduleContentBlobRecovery() {
        val foundation = contentFoundation ?: return
        val recovery = contentBlobRecoveryCoordinator ?: return
        if (contentBlobRecoveryJob?.isActive == true) return
        val precedingBodyDrain = contentBlobDrainJob
        // Capture the exact generation before enqueueing. New publications cannot enter this
        // slice even if the coroutine begins after their blob receipts have been released.
        val safetyCutoffGeneration = foundation.blobStore.currentGeneration
        contentBlobRecoveryJob = trackingScope.launch {
            try {
                precedingBodyDrain?.join()
                yield()
                recovery.runLowPrioritySlice(safetyCutoffGeneration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Recovery is conservative and retryable. A failure retains bytes for a later
                // background pass and never blocks foreground content access.
            }
        }
    }

    /** A persisted v2 session owns the remote writer even while linking, revoked or unreadable. */
    public suspend fun isLegacySnapshotWriterAllowed(): Boolean =
        syncRuntime?.isLegacySnapshotWriterAllowed() ?: true

    /** Starts one background-only consumer; conflation gives bursts of imports one eventual rescan. */
    private fun startSearchIndexReconciler() {
        val features = contentFeatures ?: return
        if (searchIndexReconciliationJob?.isActive == true) return
        searchIndexReconciliationJob = trackingScope.launch {
            for (ignored in searchIndexReconciliationSignals) {
                try {
                    yield()
                    // These complete-library scans are background-only, metadata-paged, and
                    // cancellation-safe. Targeted invalidations run first so a revoked grant does
                    // not wait behind unrelated library reconciliation.
                    features.reconcilePendingRightsInvalidations()
                    features.sweepExpiredRightsDerivedData()
                    features.sweepUnauthorizedDerivedData()
                    features.reconcileSearchIndex()
                } catch (cancelled: CancellationException) {
                    // The actor may already have consumed the conflated invalidation. Preserve one
                    // retry for the next background edge before releasing foreground resources.
                    searchIndexReconciliationSignals.trySend(Unit)
                    throw cancelled
                } catch (_: Throwable) {
                    // Derived rows remain rebuildable. A later background/import signal retries
                    // without making startup, restore, or reader interaction fail.
                }
            }
        }
    }

    private fun scheduleSearchIndexReconciliation() {
        if (contentFeatures == null) return
        searchIndexReconciliationSignals.trySend(Unit)
    }

    /**
     * One conflated actor owns the annotation cursor. Each wake processes only a small number of
     * pages and yields between them; large libraries therefore cannot monopolize foreground IO.
     */
    private fun startAnnotationReconciler() {
        val runtime = syncRuntime ?: return
        val foundation = contentFoundation ?: return
        if (annotationReconciliationJob?.isActive == true) return
        annotationReconciliationJob = trackingScope.launch {
            var cursor: String? = null
            for (ignored in annotationReconciliationSignals) {
                try {
                    var slices = 0
                    do {
                        yield()
                        val result = runtime.reconcileContentAnnotations(
                            annotationStore = foundation.annotations,
                            contentStore = foundation.transactions,
                            afterAnnotationId = cursor,
                        ) ?: run {
                            cursor = null
                            break
                        }
                        if (result.localAnnotationsStaged > 0) {
                            scheduleContentOutboxDrain()
                        }
                        cursor = result.nextAfterAnnotationId
                        slices++
                    } while (cursor != null && slices < MAX_ANNOTATION_RECONCILIATION_SLICES)
                    if (cursor != null) annotationReconciliationSignals.trySend(Unit)
                } catch (cancelled: CancellationException) {
                    annotationReconciliationSignals.trySend(Unit)
                    throw cancelled
                } catch (_: Throwable) {
                    // The SQLite rows and sync outbox remain durable. Restart from the first page
                    // on a later mutation/background edge instead of surfacing background work.
                    cursor = null
                }
            }
        }
    }

    private fun scheduleAnnotationReconciliation() {
        if (contentFeatures == null || syncRuntime == null) return
        annotationReconciliationSignals.trySend(Unit)
    }

    /** Stable random installation identity used by legacy envelopes and control-plane setup. */
    public suspend fun installationDeviceId(): String =
        syncInfrastructure?.installationStore?.loadOrCreate()?.deviceId
            ?: error("Platform sync infrastructure is unavailable")

    public suspend fun close() {
        startupHydrationJob?.cancel()
        startupHydrationJob = null
        contentOutboxDrainJob?.cancel()
        contentOutboxDrainJob = null
        contentBlobDrainJob?.cancel()
        contentBlobDrainJob = null
        contentBlobRecoveryJob?.cancel()
        contentBlobRecoveryJob = null
        searchIndexReconciliationJob?.cancel()
        searchIndexReconciliationJob = null
        searchIndexReconciliationSignals.close()
        annotationReconciliationJob?.cancel()
        annotationReconciliationJob = null
        annotationReconciliationSignals.close()
        proxySecretPersistenceJob?.cancel()
        portableRepositoryReconciliationJob?.cancel()
        trackerManager.cancelAll()
        trackingJob.cancelAndJoin()
        pluginEventUiJob.cancelAndJoin()
        downloads.close()
        pluginManager.close()
        pluginEventGateway.close()
        pluginDiagnosticLog.clear()
        if (contentFeatures != null) contentFeatures.close() else platformTextToSpeechEngine?.close()
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
        const val PROXY_API_KEY_STORAGE_KEY = "network.proxy.secret"
        const val MAX_CONTENT_OUTBOX_DRAIN_BATCHES = 8
        const val MAX_ANNOTATION_RECONCILIATION_SLICES = 4
        const val CONTENT_BLOB_ORPHAN_MINIMUM_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val CONTENT_BACKUP_APP_VERSION = "1.0.0"
        const val DEFAULT_SYNC_USER_NAME = "Shinsou X user"
        const val CLOUDFLARE_DEPLOY_URL =
            "https://deploy.workers.cloudflare.com/?url=https://github.com/aluo96078/shinsoux/tree/master/syncWorker"
    }
}

private fun List<Publication>.backupV2Candidates(): List<BackupV2AttachmentCandidate> =
    flatMap { publication ->
        publication.acquisitions.flatMap { acquisition ->
            acquisition.units.flatMap { unit ->
                unit.manifestRevisions.map { manifest ->
                    val attachment = ManifestAttachment(
                        owner = ContentManifestOwner(
                            publicationKey = publication.key,
                            acquisitionId = acquisition.id,
                            unitKey = unit.key,
                        ),
                        manifest = manifest,
                    )
                    BackupV2AttachmentCandidate(
                        attachment = attachment,
                        access = ContentAccessRequest(
                            grantReference = acquisition.rightsGrantRef,
                            scope = RightsScope(
                                publicationId = publication.key,
                                acquisitionId = acquisition.id,
                                unitId = unit.key,
                                manifestId = manifest.manifestId,
                                contentRevision = manifest.contentRevision,
                            ),
                        ),
                    )
                }
            }
        }
    }

/** Exact host-owned rights scopes that currently retain one immutable body. */
private fun ContentFoundationRuntime.accessRequestsForBlob(blobId: String): List<ContentAccessRequest> =
    publications.all().flatMap { publication ->
        publication.acquisitions.flatMap { acquisition ->
            acquisition.units.flatMap { unit ->
                unit.manifestRevisions
                    .filter { manifest -> manifest.referencedBlobs.any { it.blobId == blobId } }
                    .map { manifest ->
                        ContentAccessRequest(
                            grantReference = acquisition.rightsGrantRef,
                            scope = RightsScope(
                                publicationId = publication.key,
                                acquisitionId = acquisition.id,
                                unitId = unit.key,
                                manifestId = manifest.manifestId,
                                contentRevision = manifest.contentRevision,
                            ),
                        )
                    }
            }
        }
    }.distinct()
