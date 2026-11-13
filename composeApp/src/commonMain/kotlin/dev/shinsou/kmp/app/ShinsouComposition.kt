package dev.shinsou.kmp.app

import dev.shinsou.kmp.acquisition.BoundedEpubArchiveExtractor
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.backup.BackupV2AttachmentCandidate
import dev.shinsou.kmp.backup.BackupV2CreatePolicy
import dev.shinsou.kmp.backup.BackupV2PortableState
import dev.shinsou.kmp.backup.PortableContentBackupV2RestoreCoordinator
import dev.shinsou.kmp.backup.PortableContentBackupV2Service
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentBlobRecoveryCoordinator
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.HostContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.download.DownloadManager
import dev.shinsou.kmp.domain.model.TrackerIds
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.sync.v3.SyncDeviceInfo
import dev.shinsou.kmp.sync.v3.SyncSourceInfo
import dev.shinsou.kmp.sync.v3.SyncV3Service
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.local.LocalContentManager
import dev.shinsou.kmp.local.typedLocalChapterUnitKey
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.domain.model.InMemoryPortableAliasLedger
import dev.shinsou.kmp.domain.model.LegacyPublicationBundle
import dev.shinsou.kmp.migration.shuyue.ShuYueCompatibilityProjectionCoordinator
import dev.shinsou.kmp.migration.shuyue.ShuYueMigrationSecretStore
import dev.shinsou.kmp.migration.shuyue.ShuYueTransactionalImporter
import dev.shinsou.kmp.plugin.ExtensionRepositoryClient
import dev.shinsou.kmp.plugin.BiliMangaStandaloneLibraryMigration
import dev.shinsou.kmp.plugin.BILIMANGA_MANGA_SOURCE_ID
import dev.shinsou.kmp.plugin.FilePluginPackageStore
import dev.shinsou.kmp.plugin.ConfiguredPluginProxyResolver
import dev.shinsou.kmp.plugin.ConfiguredPluginUserAgentProvider
import dev.shinsou.kmp.plugin.KeyValueExtensionRepositoryStore
import dev.shinsou.kmp.plugin.KeyValuePluginPackageStore
import dev.shinsou.kmp.plugin.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.MigratingPluginStorage
import dev.shinsou.kmp.plugin.KeyValuePluginTrustStore
import dev.shinsou.kmp.plugin.ExecutionTierResolver
import dev.shinsou.kmp.plugin.KeyValueRepositorySecurityStateStore
import dev.shinsou.kmp.plugin.LocalPluginBlocklist
import dev.shinsou.kmp.plugin.PluginPackageSecurity
import dev.shinsou.kmp.plugin.PublisherExecutionTierGate
import dev.shinsou.kmp.plugin.PublisherRepositoryTrustPolicy
import dev.shinsou.kmp.plugin.PublisherRevocationStore
import dev.shinsou.kmp.plugin.PublisherTrustStore
import dev.shinsou.kmp.plugin.KeyValueRepositoryTrustRootStore
import dev.shinsou.kmp.plugin.ConfiguredRepositoryTrustPolicy
import dev.shinsou.kmp.plugin.createPlatformPinnedPluginHttpTransport
import dev.shinsou.kmp.plugin.createPlatformPluginHostResolver
import dev.shinsou.kmp.plugin.inspectPlatformPluginNetwork
import dev.shinsou.kmp.plugin.RepositoryTrustRootStore
import dev.shinsou.kmp.plugin.KtorPluginHttpTransport
import dev.shinsou.kmp.plugin.AccountRepositoryDirectory
import dev.shinsou.kmp.plugin.PluginInstallKeys
import dev.shinsou.kmp.plugin.AccountPackageDirectory
import dev.shinsou.kmp.plugin.AccountPackage
import dev.shinsou.kmp.plugin.PluginBrowseAdapter
import dev.shinsou.kmp.plugin.PluginBrowserSessionTransport
import dev.shinsou.kmp.plugin.PluginKeyValueStore
import dev.shinsou.kmp.plugin.PluginLoginRequestCoordinator
import dev.shinsou.kmp.plugin.PluginLogoutConfirmation
import dev.shinsou.kmp.plugin.PluginLogoutRequestCoordinator
import dev.shinsou.kmp.plugin.PluginManager
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.PluginNetworkConfiguration
import dev.shinsou.kmp.plugin.PluginNetworkConfigurationProvider
import dev.shinsou.kmp.plugin.PluginRequestBuilder
import dev.shinsou.kmp.plugin.PluginUserAgentProvider
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
import dev.shinsou.kmp.plugin.v2.ExtensionBrowseContentGatewayV2
import dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerV2
import dev.shinsou.kmp.plugin.v2.ExtensionLocalUnitProgressV2
import dev.shinsou.kmp.plugin.v2.ExtensionSourceResolverV2
import dev.shinsou.kmp.plugin.v2.extensionUnitPageProgress
import dev.shinsou.kmp.plugin.v2.PluginNetworkExtensionResourceFetcherV2
import dev.shinsou.kmp.tracking.TrackerDescriptor
import dev.shinsou.kmp.tracking.TrackerManager
import dev.shinsou.kmp.tracking.TrackerManagerAdapter
import dev.shinsou.kmp.tracking.TrackerScoreFormat
import dev.shinsou.kmp.tracking.TrackingCoordinator
import dev.shinsou.kmp.tracking.TrackingProvider
import dev.shinsou.kmp.ui.ContentCallbacks
import dev.shinsou.kmp.content.PlatformContentInfrastructure
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
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.download.BackgroundDownloadCoordinator
import kotlin.time.Clock

/** Shared dependency graph used unchanged by Android, iOS and Desktop. */
public class ShinsouComposition(
    public val repository: ShinsouRepository,
    private val httpClient: HttpClient,
    private val pluginKeyValueStore: PluginKeyValueStore,
    fileSystem: AppFileSystem,
    runtimeFactory: ScriptPluginRuntimeFactory,
    autoBackupService: AutoBackupService? = null,
    private val contentInfrastructure: PlatformContentInfrastructure? = null,
    private val platformTextToSpeechEngine: PlatformTextToSpeechEngine? = null,
    private val shuYueMigrationSecretStore: ShuYueMigrationSecretStore? = null,
    private val pluginBrowserSessionTransport: PluginBrowserSessionTransport =
        PluginBrowserSessionTransport.Unavailable,
    private val platformBrowserUserAgentProvider: PluginUserAgentProvider =
        dev.shinsou.kmp.plugin.StickyPluginUserAgentProvider(),
    private val syncDeviceInfo: SyncDeviceInfo = SyncDeviceInfo("This device", "desktop", ""),
) {
    /**
     * Non-secret platform capability exposed for support/diagnostics. This does not weaken the
     * network admission boundary: unavailable pinned transport still rejects public egress.
     */
    public val pluginNetworkCapability = inspectPlatformPluginNetwork()
    /** Unified M1 storage graph. Platform infrastructure remains the single SQL driver owner. */
    public val contentFoundation: ContentFoundationRuntime? = contentInfrastructure?.let { infrastructure ->
        ContentFoundationRuntime(
            driver = infrastructure.contentDriver(),
            contentBlobDirectoryPath = infrastructure.contentBlobDirectory(),
        )
    }
    /** Search, annotation, speech, and named rights operations share M1's driver and authority. */
    public val contentFeatures: ContentFeatureRuntime? = contentFoundation?.let { foundation ->
        ContentFeatureRuntime(
            foundation = foundation,
            platformTextToSpeechEngine = platformTextToSpeechEngine,
            nowEpochMillis = { Clock.System.now().toEpochMilliseconds() },
            onAnnotationMutationCommitted = ::scheduleContentOutboxDiscard,
            onRightsInvalidated = ::scheduleSearchIndexReconciliation,
        )
    }
    /** Compatibility projection remains available in previews which intentionally omit SQLite. */
    public val legacyPublicationProjection: LegacyPublicationProjection =
        contentFoundation?.legacyProjection ?: LegacyPublicationProjection(InMemoryPortableAliasLedger())
    /** Versioned M1 migration runs after the first interactive frame on the background scope. */
    private val legacyPublicationStartupMigration: LegacyPublicationStartupMigration? =
        contentFoundation?.let(::LegacyPublicationStartupMigration)
    private val biliMangaStandaloneLibraryMigration: BiliMangaStandaloneLibraryMigration? =
        contentFoundation?.let { BiliMangaStandaloneLibraryMigration(repository, it) }
    /** Derived ShuYue compatibility rows are restart-safe and never become content authority. */
    private val shuYueCompatibilityProjection: ShuYueCompatibilityProjectionCoordinator? =
        contentFoundation?.let { foundation ->
            ShuYueCompatibilityProjectionCoordinator(repository, foundation)
        }

    /** On-demand only: this never installs a competing repository mutation observer. */
    public fun currentLegacyPublications(): List<LegacyPublicationBundle> =
        legacyPublicationProjection.project(repository.currentSnapshot)
    private val pluginHttpClient = httpClient.config { followRedirects = false }
    private val platformPluginHostResolver = createPlatformPluginHostResolver()
    private val platformPinnedPluginTransport = createPlatformPinnedPluginHttpTransport()
    private val pluginStorage = MigratingPluginStorage(
        delegate = KeyValuePluginStorage(pluginKeyValueStore),
        migrationState = pluginKeyValueStore,
    )
    private val trustStore = KeyValuePluginTrustStore(pluginKeyValueStore)
    /** Out-of-band/admin configuration boundary; key replacement requires its explicit API. */
    public val repositoryTrustRootStore: RepositoryTrustRootStore =
        KeyValueRepositoryTrustRootStore(pluginKeyValueStore)
    private val repositorySecurityState = KeyValueRepositorySecurityStateStore(pluginKeyValueStore)

    /** ADR-0002 S5: trusted publisher keys, their trust mode, rotation and self-revocation. */
    public val publisherTrustStore: PublisherTrustStore = PublisherTrustStore(pluginKeyValueStore, repositorySecurityState)

    /** ADR-0002 S5: artifacts publishers withdrew, and when their signed documents expire. */
    public val publisherRevocationStore: PublisherRevocationStore =
        PublisherRevocationStore(pluginKeyValueStore, publisherTrustStore, repositorySecurityState)

    /** ADR-0002 D5: the user's own block list, device-local. */
    public val pluginBlocklist: LocalPluginBlocklist = LocalPluginBlocklist(pluginKeyValueStore)

    /** ADR-0002 §8.1: device-local developer mode for loading unsigned packages from a dev server. */
    public val pluginDeveloperMode: dev.shinsou.kmp.plugin.PluginDeveloperModeStore =
        dev.shinsou.kmp.plugin.PluginDeveloperModeStore(pluginKeyValueStore)

    /** ADR-0002 §10.1: the formal basis for where a package's code runs. */
    public val executionTierResolver: ExecutionTierResolver = ExecutionTierResolver(
        publishers = publisherTrustStore,
        revocations = publisherRevocationStore,
        blocklist = pluginBlocklist,
        developerMode = { pluginDeveloperMode.state.value.enabled },
        nowEpochSeconds = { kotlin.time.Clock.System.now().epochSeconds },
    )
    private val repositoryClient = ExtensionRepositoryClient(
        client = httpClient,
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
        userAgents = ConfiguredPluginUserAgentProvider(
            configuration = networkConfiguration,
            fallback = platformBrowserUserAgentProvider,
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
                    val sourceId = sourceKey.legacyLongId
                        ?: return@PluginLoginIntentPort PluginEventOutcome.Suppressed
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
            hostResolver = platformPluginHostResolver,
            browserSessionTransport = pluginBrowserSessionTransport,
            loginRequester = pluginLoginRequests,
            systemEventSink = pluginEventGateway,
            systemEventContextRegistry = pluginEventContextRegistry,
        ),
        eventGrantAdmission = pluginEventGrantAdmission,
        reviewedImageTransport = dev.shinsou.kmp.plugin.createPlatformReviewedImageTransport(),
        executionTierGate = PublisherExecutionTierGate(publisherTrustStore, executionTierResolver),
        developerRepository = pluginDeveloperMode::admitsRepository,
    )
    private val coordinator = RepositoryPluginCoordinator(
        repository = repository,
        manager = pluginManager,
        fileSystem = fileSystem,
    )
    private val trackingJob = SupervisorJob()
    private val trackingScope = CoroutineScope(trackingJob + Dispatchers.Default)

    /**
     * Sync v3 (ADR-0003): account, dirty tracking and push/pull against a user-chosen server.
     * Null only when no shared SQLite foundation is supplied (previews); the repository observer
     * is installed by [SyncV3Service.start] once an account is signed in.
     */
    public val sync: SyncV3Service? = contentInfrastructure?.let { infrastructure ->
        SyncV3Service.create(
            repository = repository,
            driver = infrastructure.contentDriver(),
            keyValues = pluginKeyValueStore,
            http = httpClient,
            scope = trackingScope,
            device = syncDeviceInfo,
            publications = { key -> contentFoundation?.publications?.find(key) },
            resolveLegacySource = { id -> pluginManager.exactSourceKeyForLegacyId(id) },
            resolveCreatableSource = { packageId, sourceId ->
                pluginManager.extensionSourceV2(SourceKey(packageId = packageId, sourceId = sourceId))?.descriptor?.sourceKey
                    // A package installed from another device's list waits for its permission review and
                    // has no runtime yet; its stored manifest still names the source, so the title can be
                    // created and the review is asked for when it is first opened.
                    ?: pluginManager.installedSourceKey(packageId, sourceId)
            },
            sourceInfo = {
                // Native packages have no stored manifest; what they have loaded describes them.
                val loaded = pluginManager.loadedExtensionDescriptorsV2().flatMap { extension ->
                    extension.sources.map { source ->
                        (source.sourceKey.packageId to source.sourceKey.sourceId) to
                            SyncSourceInfo(name = source.displayName, language = source.languageTag, repositoryUrl = null)
                    }
                }
                val stored = pluginManager.installedPlugins().flatMap { stored ->
                    stored.manifest.sources.orEmpty().map { source ->
                        (stored.manifest.id to (source.canonicalSourceId ?: source.id.toString())) to SyncSourceInfo(
                            name = source.name,
                            language = source.lang,
                            repositoryUrl = stored.metadata.repositoryBaseUrl,
                        )
                    }
                }
                // A title synced from another device may belong to a package that is not installed
                // here. The added repositories still say which of them publishes it; that describes
                // the package as a whole, not one source of it.
                val published = pluginManager.knownRepositoryPackages().mapNotNull { known ->
                    val repository = known.repositoryBaseUrl ?: return@mapNotNull null
                    (known.id to SyncSourceInfo.WHOLE_PACKAGE) to
                        SyncSourceInfo(name = known.name, language = known.lang, repositoryUrl = repository)
                }
                (published + loaded + stored).toMap()
            },
        )
    }
    /** Staged ShuYue migration. */
    public val shuYueMigration: ShuYueMigrationUiController? = contentFoundation?.let { foundation ->
        val offlineStoreAuthorizer = HostContentBodyOfflineStoreAuthorizer(
            authority = foundation.rightsAuthority,
            durableGrant = foundation.rightsGrants::find,
            nowEpochMillis = { Clock.System.now().toEpochMilliseconds() },
        )
        val localImporter = ShuYueTransactionalImporter(
            blobStore = foundation.blobStore,
            transactionStore = foundation.transactions,
            offlineStoreAuthorizer = offlineStoreAuthorizer,
        )
        DefaultShuYueMigrationUiController(
            contentImportAction = ShuYueContentImportAction { prepared, selection ->
                val result = localImporter.import(prepared, selection)
                requireNotNull(shuYueCompatibilityProjection).repair()
                scheduleSearchIndexReconciliation()
                scheduleContentOutboxDiscard()
                result
            },
            secretImportAction = shuYueMigrationSecretStore?.let { protectedStore ->
                ShuYueSecretImportAction { prepared, consent ->
                    localImporter.importSecrets(prepared, consent, protectedStore)
                }
            },
        )
    }

    /** Checksummed binary export plus the only atomic content-v2 restore boundary. */
    public val portableContentBackupV2: PortableContentBackupV2UiController? =
        contentFoundation?.let { foundation ->
            val features = requireNotNull(contentFeatures)
            val offlineStoreAuthorizer = HostContentBodyOfflineStoreAuthorizer(
                authority = foundation.rightsAuthority,
                durableGrant = foundation.rightsGrants::find,
                nowEpochMillis = { Clock.System.now().toEpochMilliseconds() },
            )
            val restoreCoordinator = PortableContentBackupV2RestoreCoordinator(
                repository = repository,
                foundation = foundation,
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
                restoreAction = PortableContentBackupV2RestoreAction { inspection, source ->
                    val result = restoreCoordinator.restore(inspection, source)
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
    private var installedSourcesSyncJob: Job? = null
    private var syncKindsJob: Job? = null
    /** M1 orphan discovery/GC runs only after the app enters a background window. */
    private var contentBlobRecoveryJob: Job? = null
    /** Conflated derived-index invalidations; import/restore never waits for plaintext indexing. */
    private val searchIndexReconciliationSignals = Channel<Unit>(Channel.CONFLATED)
    private var searchIndexReconciliationJob: Job? = null
    /** Conflated requests to drop legacy outbox drafts and the blob-sync jobs nothing drains. */
    private val contentOutboxDiscardSignals = Channel<Unit>(Channel.CONFLATED)
    private var contentOutboxDiscardJob: Job? = null
    private val contentOutboxDiscarder: ContentOutboxDiscarder? =
        contentFoundation?.let { ContentOutboxDiscarder(it.transactions) }
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
        packageSecurity = PluginPackageSecurity(publisherTrustStore, publisherRevocationStore, pluginBlocklist, repositoryClient),
        developerMode = pluginDeveloperMode,
        accountRepositories = sync?.let { service ->
            object : AccountRepositoryDirectory {
                override val repositories = service.accountRepositories

                override suspend fun forget(url: String) = service.forgetRepository(url)
            }
        },
        accountPackages = sync?.let { service ->
            object : AccountPackageDirectory {
                override val packages = service.accountPackages
            }
        },
        suggestionScope = trackingScope,
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
                localUnitProgress = { publicationKey ->
                    val snapshot = repository.currentSnapshot
                    val chapters = snapshot.chapters.filter { chapter ->
                        typedLocalChapterUnitKey(chapter.url)?.publicationKey == publicationKey
                    }
                    val chapterById = chapters.associateBy { it.id }
                    val completed = chapters.asSequence()
                        .filter { it.read }
                        .mapNotNull { typedLocalChapterUnitKey(it.url) }
                        .toSet()
                    val histories = snapshot.histories.filter { it.chapterId in chapterById }
                    val inProgress = histories.mapNotNull { history ->
                        val chapter = chapterById[history.chapterId] ?: return@mapNotNull null
                        if (chapter.read) return@mapNotNull null
                        val unitKey = typedLocalChapterUnitKey(chapter.url) ?: return@mapNotNull null
                        unitKey to extensionUnitPageProgress(chapter.lastPageRead, history.lastPageCount)
                    }.toMap()
                    val latest = histories
                        .asSequence()
                        .maxWithOrNull(
                            compareBy<History> { it.lastRead }
                                .thenBy { it.id },
                        )
                        ?.chapterId
                        ?.let(chapterById::get)
                        ?.url
                        ?.let(::typedLocalChapterUnitKey)
                    ExtensionLocalUnitProgressV2(
                        completedUnitKeys = completed,
                        lastReadUnitKey = latest,
                        inProgressUnits = inProgress,
                    )
                },
            )
        },
        fileSystem = fileSystem,
    )

    public val localContent: LocalContentManager = LocalContentManager(
        repository = repository,
        fileSystem = fileSystem,
        remote = coordinator,
        contentFoundation = contentFoundation,
        epubArchiveExtractor = BoundedEpubArchiveExtractor(),
        onTypedContentCommitted = ::scheduleSearchIndexReconciliation,
        remoteAssetScopeFor = { sourceKey -> pluginManager.contentNetworkScopeForSource(sourceKey) },
    )

    public val content: ContentCallbacks = localContent

    /** Both download queues as one piece of background work for the platform keep-alives. */
    public val backgroundDownloads: BackgroundDownloadCoordinator = BackgroundDownloadCoordinator(
        snapshot = repository.snapshot,
        novels = browse.extensionDownloadsV2,
        // Each change is on disk before the coordinator updates its own mark, so the two never
        // disagree after the process is killed.
        pauseManga = { ids ->
            downloads.pauseForSystem(ids)
            repository.flushPersistence()
        },
        resumeManga = { ids ->
            downloads.resumeAfterSystem(ids)
            repository.flushPersistence()
        },
        pauseNovels = { ids ->
            browse.pauseExtensionDownloadsForSystemV2(ids)
            browse.flushExtensionDownloadsV2()
        },
        resumeNovels = { ids ->
            browse.resumeExtensionDownloadsAfterSystemV2(ids)
            browse.flushExtensionDownloadsV2()
        },
        fileSystem = fileSystem,
        scope = trackingScope,
    )

    /** App-private, recoverable backups shared by foreground UI and platform schedulers. */
    public val autoBackups: AutoBackupService = autoBackupService ?: AutoBackupService(
        repository = repository,
        fileSystem = fileSystem,
    )

    /**
     * Returns as soon as the first frame is allowed to render. Source discovery, secure proxy hydration, tracker auth and download queue restoration are
     * useful startup work, but none is required to draw or interact with the local library, so
     * they continue on the background tracking scope.
     */
    public suspend fun start() {
        if (startupHydrationJob?.isActive == true) return
        startupHydrationJob = trackingScope.launch {
            try {
                // Deferred out of construction (the first frame); load it here, off the main thread.
                contentFoundation?.warmAttachmentLedger()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Stays pending; the next content operation loads it and reports the failure.
            }
            try {
                // Restores a stored sync session (observer, stream) once content storage is ready.
                sync?.start()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The Sync settings page shows the error; startup continues.
            }
            // Before any refresh: repository trust and install keys read it synchronously, and
            // until it is loaded developer mode counts as off.
            try {
                pluginDeveloperMode.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Unreadable: developer mode stays off.
            }
            try {
                // Installed sources appear now; the migrations below can take a while.
                browse.showInstalledSources()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // browse.refresh() below loads them again and reports the error.
            }
            val stopsAtStart = backgroundDownloads.stopCount()
            try {
                // Shown (and kept alive by the platform download service) right away; the
                // downloads themselves start after browse.refresh() below has loaded the sources.
                browse.restoreExtensionDownloadsV2()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Same as the manga queue: a damaged saved queue just starts empty.
            }
            // Its downloads are still held back here; a system stop that came while it loaded
            // pauses them before they can start.
            backgroundDownloads.novelQueueRestored()
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
            // The repository lists say which repository publishes a synced title's package.
            sync?.sourcesChanged()
            browse.openExtensionDownloadsV2()
            try {
                // Do not turn a readable local reviewed favorite into an unavailable remote row.
                // Installed-source hydration happens at the start of browse.refresh(), even when
                // optional repository discovery later times out or fails.
                if (pluginManager.source(BILIMANGA_MANGA_SOURCE_ID) != null) {
                    biliMangaStandaloneLibraryMigration?.migrate()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Migration is an idempotent source/url rewrite. A missing standalone package or
                // incomplete typed chapter graph leaves the old row intact for a later launch.
            }
            if (sync != null && installedSourcesSyncJob == null) {
                // Titles synced from another device wait for their source; installing or removing a
                // package is what lets them join the library, so sync re-checks them right away.
                installedSourcesSyncJob = trackingScope.launch {
                    var first = true
                    browse.state
                        .map { snapshot -> snapshot.extensions.filter { it.installed }.mapTo(HashSet()) { it.id } }
                        .distinctUntilChanged()
                        .collect {
                            // The account also records which packages were installed (the package kind).
                            sync.packagesChanged(installedAccountPackages())
                            if (!first) sync.sourcesChanged()
                            first = false
                        }
                }
                // Switching a sync item on or off: what waited while it was off is sent or applied now.
                syncKindsJob = trackingScope.launch {
                    repository.snapshot
                        .map { it.settings.sync.kinds }
                        .distinctUntilChanged()
                        .drop(1)
                        .collect { sync.kindsChanged() }
                }
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
                            sync?.sourcesChanged()
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
            // Queues the system stopped before the process died pick up where they left off,
            // unless the system stopped them again while this startup was running.
            backgroundDownloads.resumeAfterSystem(unlessStoppedSince = stopsAtStart)
            // Drafts left by earlier launches (and by this startup's migrations) are dropped once
            // the startup work above is done.
            startContentOutboxDiscarder()
            scheduleContentOutboxDiscard()
        }
    }

    /**
     * The packages installed from a repository, for the account's package list. Developer builds and
     * packages without a repository are left out: another device could not install them anyway.
     */
    private suspend fun installedAccountPackages(): List<AccountPackage> {
        val fingerprints = repository.currentSnapshot.extensionRepositories
            .associate { it.baseUrl.trim().trimEnd('/') to it.signingKeyFingerprint }
        return pluginManager.installedPlugins().mapNotNull { stored ->
            val url = stored.metadata.repositoryBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (PluginInstallKeys.isDeveloperKey(stored.manifest.id)) return@mapNotNull null
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

    /** True once start() has run and both download queues are restored; before that an empty queue means nothing. */
    public fun isStartupComplete(): Boolean = startupHydrationJob?.isCompleted == true

    /** Suspends until start()'s background hydration (both download queues restored) has finished. */
    public suspend fun awaitStartup() {
        startupHydrationJob?.join()
    }

    /** Resumes paused work and cancels background-only slices after a platform foreground edge. */
    public suspend fun onForeground() {
        // Returning to the app resumes what the system paused. During startup the queues are not
        // restored yet, so the resume waits for it; a system stop after this moment still wins.
        val startup = startupHydrationJob
        if (startup == null || startup.isCompleted) {
            backgroundDownloads.resumeAfterSystem()
        } else {
            val stopsNow = backgroundDownloads.stopCount()
            trackingScope.launch {
                startup.join()
                backgroundDownloads.resumeAfterSystem(unlessStoppedSince = stopsNow)
            }
        }
        try {
            browse.resumeExtensionDownloadsAfterFailuresV2()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The Downloads screen still offers retry.
        }
        val previousRecovery = contentBlobRecoveryJob
        contentBlobRecoveryJob = null
        val previousSearchReconciliation = searchIndexReconciliationJob
        searchIndexReconciliationJob = null
        // No full-library derived-state work may compete with an interactive frame: wait until
        // the background slices have observed cancellation and released their resources.
        previousRecovery?.cancel()
        previousSearchReconciliation?.cancel()
        previousRecovery?.join()
        previousSearchReconciliation?.join()
        scheduleContentOutboxDiscard()
        try {
            sync?.onForeground()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Sync problems surface on the Sync settings page.
        }
    }

    /** Flushes the snapshot, then schedules background-only maintenance. */
    public suspend fun onBackground() {
        repository.flushPersistence()
        try {
            // Pushes what is pending and closes the realtime stream.
            sync?.onBackground()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Retried by the next foreground sync or the periodic background worker.
        }
        scheduleContentOutboxDiscard()
        scheduleContentBlobRecovery()
        startSearchIndexReconciler()
        scheduleSearchIndexReconciliation()
    }

    /** Starts the single consumer that discards the content outbox; idempotent. */
    private fun startContentOutboxDiscarder() {
        val discarder = contentOutboxDiscarder ?: return
        if (contentOutboxDiscardJob?.isActive == true) return
        contentOutboxDiscardJob = trackingScope.launch(Dispatchers.Default) {
            for (ignored in contentOutboxDiscardSignals) {
                try {
                    yield()
                    discarder.discardPending()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The drafts stay durable and harmless; the next commit or lifecycle edge
                    // retries the discard.
                }
            }
        }
    }

    /** Requests a discard pass; conflated, and buffered until the startup pass starts the consumer. */
    private fun scheduleContentOutboxDiscard() {
        if (contentOutboxDiscarder == null) return
        contentOutboxDiscardSignals.trySend(Unit)
    }

    private fun scheduleContentBlobRecovery() {
        val foundation = contentFoundation ?: return
        val recovery = contentBlobRecoveryCoordinator ?: return
        if (contentBlobRecoveryJob?.isActive == true) return
        // Capture the exact generation before enqueueing. New publications cannot enter this
        // slice even if the coroutine begins after their blob receipts have been released.
        val safetyCutoffGeneration = foundation.blobStore.currentGeneration
        contentBlobRecoveryJob = trackingScope.launch {
            try {
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
                    features.reconcileSearchIndexIfChanged()
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

    /** [stage] names each shutdown step before it runs (the desktop startup probe records them). */
    public suspend fun close(stage: (String) -> Unit = {}) {
        sync?.close()
        startupHydrationJob?.cancel()
        startupHydrationJob = null
        contentOutboxDiscardJob?.cancel()
        contentOutboxDiscardJob = null
        contentOutboxDiscardSignals.close()
        contentBlobRecoveryJob?.cancel()
        contentBlobRecoveryJob = null
        searchIndexReconciliationJob?.cancel()
        searchIndexReconciliationJob = null
        searchIndexReconciliationSignals.close()
        proxySecretPersistenceJob?.cancel()
        portableRepositoryReconciliationJob?.cancel()
        installedSourcesSyncJob?.cancel()
        syncKindsJob?.cancel()
        stage("trackerManager.cancelAll")
        trackerManager.cancelAll()
        stage("trackingJob.cancelAndJoin")
        trackingJob.cancelAndJoin()
        stage("pluginEventUiJob.cancelAndJoin")
        pluginEventUiJob.cancelAndJoin()
        stage("downloads.close")
        downloads.close()
        stage("pluginManager.close")
        pluginManager.close()
        stage("pluginBrowserSessionTransport.close")
        pluginBrowserSessionTransport.close()
        stage("pluginEventGateway.close")
        pluginEventGateway.close()
        stage("pluginDiagnosticLog.clear")
        pluginDiagnosticLog.clear()
        stage("contentFeatures.close")
        if (contentFeatures != null) contentFeatures.close() else platformTextToSpeechEngine?.close()
        // Every mutation producer is stopped; flush and terminate the coalesced writer.
        try {
            stage("repository.closePersistence")
            repository.closePersistence()
        } finally {
            stage("pluginHttpClient.close")
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
        const val PROXY_API_KEY_STORAGE_KEY = "network.proxy.secret"
        const val CONTENT_BLOB_ORPHAN_MINIMUM_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val CONTENT_BACKUP_APP_VERSION = "1.0.1-beta.24"
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
