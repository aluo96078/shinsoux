package dev.shinsou.kmp.app

import dev.shinsou.kmp.backup.AutoBackupService
import dev.shinsou.kmp.backup.BackupV2AttachmentCandidate
import dev.shinsou.kmp.backup.BackupV2CreatePolicy
import dev.shinsou.kmp.backup.BackupV2PortableState
import dev.shinsou.kmp.backup.PortableContentBackupV2RestoreCoordinator
import dev.shinsou.kmp.backup.PortableContentBackupV2Service
import dev.shinsou.kmp.backup.shuyue.ShuYueCompatibilityProjectionCoordinator
import dev.shinsou.kmp.backup.shuyue.ShuYueMigrationSecretStore
import dev.shinsou.kmp.backup.shuyue.ShuYueTransactionalImporter
import dev.shinsou.kmp.buildinfo.SHINSOU_APP_VERSION
import dev.shinsou.kmp.content.ContentBackgroundWork
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.PlatformContentInfrastructure
import dev.shinsou.kmp.content.access.HostContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.content.acquisition.BoundedEpubArchiveExtractor
import dev.shinsou.kmp.contract.AccountPackageDirectory
import dev.shinsou.kmp.contract.AccountRepositoryDirectory
import dev.shinsou.kmp.contract.ContentCallbacks
import dev.shinsou.kmp.data.LegacyPublicationProjection
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.InMemoryPortableAliasLedger
import dev.shinsou.kmp.domain.model.LegacyPublicationBundle
import dev.shinsou.kmp.download.BackgroundDownloadCoordinator
import dev.shinsou.kmp.download.DownloadManager
import dev.shinsou.kmp.download.DownloadManagerChapterDownloads
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.files.KeyValueStore
import dev.shinsou.kmp.local.LocalContentManager
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.manga.asMangaTranslator
import dev.shinsou.kmp.plugin.PlatformPluginNetworkCapability
import dev.shinsou.kmp.plugin.PluginBrowseAdapter
import dev.shinsou.kmp.plugin.PluginBrowserSessionTransport
import dev.shinsou.kmp.plugin.PluginSubsystem
import dev.shinsou.kmp.plugin.PluginUserAgentProvider
import dev.shinsou.kmp.plugin.ScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.v2.ExtensionSyncedReadingV2
import dev.shinsou.kmp.sync.v3.SyncDeviceInfo
import dev.shinsou.kmp.sync.v3.SyncSourceInfo
import dev.shinsou.kmp.sync.v3.SyncV3Service
import dev.shinsou.kmp.tracking.TrackingCoordinator
import dev.shinsou.kmp.tracking.TrackingSubsystem
import dev.shinsou.kmp.translation.OfflineTranslationPlatform
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.TranslationMemoryStore
import dev.shinsou.kmp.tts.PlatformTextToSpeechEngine
import dev.shinsou.kmp.ui.portability.DefaultPortableContentBackupV2UiController
import dev.shinsou.kmp.ui.portability.DefaultShuYueMigrationUiController
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2CreateAction
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2RestoreAction
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2UiController
import dev.shinsou.kmp.ui.portability.ShuYueContentImportAction
import dev.shinsou.kmp.ui.portability.ShuYueMigrationUiController
import dev.shinsou.kmp.ui.portability.ShuYueSecretImportAction
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import kotlin.time.Clock

/**
 * Shared dependency graph used unchanged by Android, iOS and Desktop.
 *
 * It only wires subsystems together (docs/architecture/0007 B2): the plugin graph is built by
 * [PluginSubsystem], tracking by [TrackingSubsystem], the content store's background maintenance
 * by [ContentBackgroundWork]. What stays here is what spans them: the shared background scope,
 * the startup order, the lifecycle edges and the close order.
 */
public class ShinsouComposition(
    public val repository: ShinsouRepository,
    private val httpClient: HttpClient,
    private val pluginKeyValueStore: KeyValueStore,
    fileSystem: AppFileSystem,
    runtimeFactory: ScriptPluginRuntimeFactory,
    autoBackupService: AutoBackupService? = null,
    private val contentInfrastructure: PlatformContentInfrastructure? = null,
    private val platformTextToSpeechEngine: PlatformTextToSpeechEngine? = null,
    private val shuYueMigrationSecretStore: ShuYueMigrationSecretStore? = null,
    pluginBrowserSessionTransport: PluginBrowserSessionTransport =
        PluginBrowserSessionTransport.Unavailable,
    platformBrowserUserAgentProvider: PluginUserAgentProvider =
        dev.shinsou.kmp.plugin.StickyPluginUserAgentProvider(),
    private val syncDeviceInfo: SyncDeviceInfo = SyncDeviceInfo("This device", "desktop", ""),
    /** The platform's offline translation engine and model directory (ADR-0004); null disables translation. */
    private val offlineTranslation: OfflineTranslationPlatform? = null,
    /** The operating system's own translation, where the platform has one. */
    private val systemTranslation: dev.shinsou.kmp.translation.SystemTranslationEngine? = null,
    /** On-device manga text detection, OCR and redrawing (ADR-0005); null where it is not shipped. */
    private val mangaPlatform: dev.shinsou.kmp.manga.MangaPlatform? = null,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** The app's background scope: startup work, sync, tracking, suggestions and maintenance. */
    private val trackingJob = SupervisorJob()
    private val trackingScope = CoroutineScope(trackingJob + defaultDispatcher)
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
            translation = offlineTranslation?.let { platform ->
                contentInfrastructure?.let { infrastructure ->
                    ReaderTranslation(
                        memory = TranslationMemoryStore(infrastructure.contentDriver()),
                        platform = platform,
                        http = httpClient,
                        // The same protected storage as the proxy key and tracker tokens.
                        secrets = pluginKeyValueStore,
                        system = systemTranslation,
                    )
                }
            },
        )
    }
    /** Outbox discard, blob recovery and the derived-data pass; absent without the shared foundation. */
    private val contentBackgroundWork: ContentBackgroundWork? = contentFoundation?.let { foundation ->
        ContentBackgroundWork(
            foundation = foundation,
            scope = trackingScope,
            dispatcher = defaultDispatcher,
            derivedDataPass = {
                val features = requireNotNull(contentFeatures)
                // These complete-library scans are background-only, metadata-paged, and
                // cancellation-safe. Targeted invalidations run first so a revoked grant does
                // not wait behind unrelated library reconciliation.
                features.reconcilePendingRightsInvalidations()
                features.sweepExpiredRightsDerivedData()
                features.sweepUnauthorizedDerivedData()
                features.reconcileSearchIndexIfChanged()
            },
        )
    }
    /**
     * Manga translation (ADR-0005): its models and rendered pages sit beside the offline text
     * models (storage kept out of device backups); OCR results share the translation database.
     * Present wherever translation is, so the reader can say when this device cannot run it.
     */
    /** Checks GitHub for a newer version of the app, when the user has switched checks on. */
    public val appUpdates: dev.shinsou.kmp.update.AppUpdateChecker by lazy {
        dev.shinsou.kmp.update.AppUpdateChecker(httpClient, SHINSOU_APP_VERSION)
    }

    /**
     * Opens, on the calling thread, what the shell's first frames would otherwise open on the UI
     * thread: the manga translation store, whose tables are created on first use. Called off the
     * UI thread while the loading screen shows; true when manga translation is available.
     */
    public fun openBeforeShowing(): Boolean = mangaTranslation != null

    public val mangaTranslation: dev.shinsou.kmp.manga.MangaTranslation? by lazy {
        val translation = contentFeatures?.translation ?: return@lazy null
        val directory = offlineTranslation?.modelDirectory ?: return@lazy null
        val infrastructure = contentInfrastructure ?: return@lazy null
        dev.shinsou.kmp.manga.MangaTranslation(
            platform = mangaPlatform,
            models = dev.shinsou.kmp.manga.MangaModelStore("$directory/manga", httpClient),
            pages = dev.shinsou.kmp.manga.MangaPageStore(infrastructure.contentDriver(), now = { Clock.System.now().toEpochMilliseconds() }),
            renders = dev.shinsou.kmp.manga.MangaRenderCache("$directory/manga-pages"),
            translator = translation.asMangaTranslator(),
            readVocab = { path -> okio.FileSystem.SYSTEM.read(path.toPath()) { readUtf8() }.split("\n") },
        )
    }

    /** Compatibility projection remains available in previews which intentionally omit SQLite. */
    public val legacyPublicationProjection: LegacyPublicationProjection =
        LegacyPublicationProjection(contentFoundation?.aliases ?: InMemoryPortableAliasLedger())
    /** Versioned M1 migration runs after the first interactive frame on the background scope. */
    private val legacyPublicationStartupMigration: LegacyPublicationStartupMigration? =
        contentFoundation?.let(::LegacyPublicationStartupMigration)
    /** Derived ShuYue compatibility rows are restart-safe and never become content authority. */
    private val shuYueCompatibilityProjection: ShuYueCompatibilityProjectionCoordinator? =
        contentFoundation?.let { foundation ->
            ShuYueCompatibilityProjectionCoordinator(repository, foundation)
        }

    /** On-demand only: this never installs a competing repository mutation observer. */
    public fun currentLegacyPublications(): List<LegacyPublicationBundle> =
        legacyPublicationProjection.project(repository.currentSnapshot)
    /** Plugins: repositories, trust, the plugin network, runtimes, and the browse facade below. */
    private val plugins: PluginSubsystem = PluginSubsystem.create(
        repository = repository,
        platform = PluginSubsystem.PlatformInputs(
            httpClient = httpClient,
            keyValueStore = pluginKeyValueStore,
            fileSystem = fileSystem,
            runtimeFactory = runtimeFactory,
            browserSessionTransport = pluginBrowserSessionTransport,
            browserUserAgentProvider = platformBrowserUserAgentProvider,
        ),
        contentFoundation = contentFoundation,
        backgroundScope = trackingScope,
        defaultDispatcher = defaultDispatcher,
    )

    /** Non-secret plugin network capability, for support/diagnostics. */
    public val pluginNetworkCapability: PlatformPluginNetworkCapability get() = plugins.networkCapability

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
            resolveLegacySource = { id -> plugins.exactSourceKeyForLegacyId(id) },
            resolveCreatableSource = { packageId, sourceId -> plugins.creatableSourceKey(packageId, sourceId) },
            sourceInfo = { plugins.describeSources(SyncSourceInfo.WHOLE_PACKAGE, ::SyncSourceInfo) },
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
                        candidates = BackupV2AttachmentCandidate.allOf(publications),
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
    private val trackingSubsystem = TrackingSubsystem(repository, trackingScope)
    private var portableRepositoryReconciliationJob: Job? = null
    private var installedSourcesSyncJob: Job? = null
    private var syncKindsJob: Job? = null
    /** Optional source/tracking hydration must never hold the first Compose frame hostage. */
    private var startupHydrationJob: Job? = null

    public val tracking: TrackingCoordinator = trackingSubsystem.coordinator

    public val downloads: DownloadManager = DownloadManager(
        repository = repository,
        fileSystem = fileSystem,
        pageProvider = plugins.chapterPages,
        pageFetcher = plugins.downloadPages,
    ).also { manager -> plugins.attachDownloads(DownloadManagerChapterDownloads(manager, repository, fileSystem)) }

    public val browse: PluginBrowseAdapter = plugins.openBrowse(
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
        syncedReading = { sourceKey, remotePublicationId ->
            sync?.pendingReading(sourceKey, remotePublicationId)?.let { pending ->
                ExtensionSyncedReadingV2(
                    lastReadRemoteUnitId = pending.lastReadUnitId,
                    lastReadAtMillis = pending.lastReadAtMillis,
                    completedRemoteUnitIds = pending.readUnitIds,
                    pageIndexByRemoteUnit = pending.lastPageIndexByUnit,
                    locatorOf = pending::locator,
                )
            }
        },
    )

    public val localContent: LocalContentManager = LocalContentManager(
        repository = repository,
        fileSystem = fileSystem,
        remote = plugins.remoteContent,
        contentFoundation = contentFoundation,
        epubArchiveExtractor = BoundedEpubArchiveExtractor(),
        onTypedContentCommitted = ::scheduleSearchIndexReconciliation,
        remoteAssetScopeFor = { sourceKey -> plugins.contentNetworkScope(sourceKey) },
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
    public val autoBackups: AutoBackupService = (autoBackupService ?: AutoBackupService(
        repository = repository,
        fileSystem = fileSystem,
    )).also { service ->
        contentFeatures?.translation?.let { service.translations = dev.shinsou.kmp.translation.ReaderTranslationBackups(it) }
    }

    /**
     * Returns as soon as the first frame is allowed to render. Source discovery, secure proxy hydration, tracker auth and download queue restoration are
     * useful startup work, but none is required to draw or interact with the local library, so
     * they continue on the background tracking scope.
     */
    public suspend fun start() {
        if (startupHydrationJob?.isActive == true) return
        startupHydrationJob = trackingScope.launch {
            // On failure: stays pending; the next content operation loads it and reports the failure.
            startupStep("Could not load the attachment ledger at startup") {
                // Deferred out of construction (the first frame); load it here, off the main thread.
                contentFoundation?.warmAttachmentLedger()
            }
            // On failure: the Sync settings page shows the logged; startup continues.
            startupStep("Could not start sync") {
                // Restores a stored sync session (observer, stream) once content storage is ready.
                sync?.start()
            }
            // Before any refresh: repository trust and install keys read it synchronously, and
            // until it is loaded developer mode counts as off.
            // On failure: unreadable, developer mode stays off.
            startupStep("Could not read developer mode; it stays off") {
                plugins.loadDeveloperMode()
            }
            // On failure: browse.refresh() below loads them again and reports the logged.
            startupStep("Could not show the installed sources") {
                // Installed sources appear now; the migrations below can take a while.
                browse.showInstalledSources()
            }
            val stopsAtStart = backgroundDownloads.stopCount()
            // On failure: same as the manga queue: a damaged saved queue just starts empty.
            startupStep("Could not restore the extension download queue; it starts empty") {
                // Shown (and kept alive by the platform download service) right away; the
                // downloads themselves start after browse.refresh() below has loaded the sources.
                browse.restoreExtensionDownloadsV2()
            }
            // Its downloads are still held back here; a system stop that came while it loaded
            // pauses them before they can start.
            backgroundDownloads.novelQueueRestored()
            // On failure: the transaction is atomic and restart-safe. A later launch retries the exact
            // deterministic projection without delaying this launch's interactive frame.
            startupStep("The legacy publication migration failed; the next launch retries it") {
                legacyPublicationStartupMigration?.migrate(repository.currentSnapshot)
            }
            // On failure: the typed Publication remains authoritative. Deterministic compatibility URLs
            // make this crash-gap repair safe to retry at the next launch.
            startupStep("Could not repair the typed content projection") {
                localContent.repairTypedContentLegacyProjection()
            }
            // On failure: ShuYue's typed transaction remains authoritative. The one-shot settings marker
            // and all compatibility rows share one CAS, so a later launch retries safely.
            startupStep("Could not repair the ShuYue compatibility projection") {
                shuYueCompatibilityProjection?.repair()
            }
            // On failure: secure proxy hydration can be retried from settings when keychain access returns.
            startupStep("Could not load the proxy secret") {
                plugins.hydrateProxySecret()
            }
            // On failure: PluginBrowseAdapter publishes its actionable logged in BrowseSnapshot.
            startupStep("Could not refresh the sources at startup") {
                browse.refresh()
            }
            // The repository lists say which repository publishes a synced title's package.
            sync?.sourcesChanged()
            browse.openExtensionDownloadsV2()
            // On failure: migration is an idempotent source/url rewrite. A missing standalone package or
            // incomplete typed chapter graph leaves the old row intact for a later launch.
            startupStep("The standalone library migration failed; the next launch retries it") {
                // Do not turn a readable local reviewed favorite into an unavailable remote row.
                // Installed-source hydration happens at the start of browse.refresh(), even when
                // optional repository discovery later times out or fails.
                plugins.migrateBiliMangaStandaloneLibrary()
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
                            sync.packagesChanged(plugins.installedAccountPackages())
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
                            // On failure: BrowseSnapshot already exposes the repository refresh failure.
                            startupStep("Could not refresh the sources after the repositories changed") {
                                browse.refresh()
                            }
                            sync?.sourcesChanged()
                        }
                }
            }
            // On failure: a corrupt queue entry must not prevent the rest of the app from opening.
            startupStep("Could not restore the download queue") {
                downloads.restoreQueue()
            }
            // Queues the system stopped before the process died pick up where they left off,
            // unless the system stopped them again while this startup was running.
            backgroundDownloads.resumeAfterSystem(unlessStoppedSince = stopsAtStart)
            // Drafts left by earlier launches (and by this startup's migrations) are dropped once
            // the startup work above is done.
            contentBackgroundWork?.startOutboxDiscarder()
            scheduleContentOutboxDiscard()
        }
    }

    /**
     * One step of [start]: a failure is logged as [failure] and startup goes on without it;
     * cancellation still ends startup.
     */
    private inline fun startupStep(failure: String, step: () -> Unit) {
        try {
            step()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Throwable) {
            Log.warn("Startup", logged) { failure }
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
        } catch (logged: Throwable) {
            Log.warn("Startup", logged) { "Could not resume the extension downloads" }
            // The Downloads screen still offers retry.
        }
        // No full-library derived-state work may compete with an interactive frame: wait until
        // the background slices have observed cancellation and released their resources.
        contentBackgroundWork?.stopBackgroundSlices()
        scheduleContentOutboxDiscard()
        try {
            sync?.onForeground()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Throwable) {
            Log.warn("Startup", logged) { "Sync failed on coming to the foreground" }
            // Sync problems surface on the Sync settings page.
        }
    }

    /**
     * Flushes the snapshot, then schedules background-only maintenance. The flush writes the whole
     * JSON, hot fields included (C3): the copy an older version, or a backup of the app's files,
     * would read is then the state the app was left in.
     */
    public suspend fun onBackground() {
        repository.flushPersistence(wholeSnapshot = true)
        try {
            // Pushes what is pending and closes the realtime stream.
            sync?.onBackground()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (logged: Throwable) {
            Log.warn("Startup", logged) { "Sync failed on going to the background" }
            // Retried by the next foreground sync or the periodic background worker.
        }
        scheduleContentOutboxDiscard()
        contentBackgroundWork?.scheduleBlobRecovery()
        contentBackgroundWork?.startDerivedDataReconciler()
        scheduleSearchIndexReconciliation()
    }

    /** Requests an outbox discard pass; buffered until the startup pass starts the consumer. */
    private fun scheduleContentOutboxDiscard() {
        contentBackgroundWork?.scheduleOutboxDiscard()
    }

    private fun scheduleSearchIndexReconciliation() {
        contentBackgroundWork?.scheduleDerivedDataReconciliation()
    }

    /** [stage] names each shutdown step before it runs (the desktop startup probe records them). */
    public suspend fun close(stage: (String) -> Unit = {}) {
        // Each step runs even when one before it failed: the snapshot's last flush and the HTTP
        // clients must not depend on every component closing cleanly. The first failure is
        // thrown at the end, with the later ones attached to it.
        val closing = Closing(stage)
        // A confirmed content restore runs to the end once started; the stores it commits to stay
        // open until it has.
        closing.step("portableContentBackupV2.awaitRestore") { portableContentBackupV2?.awaitRestore() }
        closing.step("sync.close") { sync?.close() }
        closing.step("backgroundJobs.cancel") {
            startupHydrationJob?.cancel()
            startupHydrationJob = null
            contentBackgroundWork?.cancel()
            plugins.cancelProxySecretPersistence()
            portableRepositoryReconciliationJob?.cancel()
            installedSourcesSyncJob?.cancel()
            syncKindsJob?.cancel()
        }
        closing.step("trackerManager.cancelAll") { trackingSubsystem.cancelPendingUpdates() }
        closing.step("trackingJob.cancelAndJoin") { trackingJob.cancelAndJoin() }
        closing.step("pluginEventUiJob.cancelAndJoin") { plugins.cancelEventUi() }
        closing.step("browse.close") { browse.close() }
        closing.step("downloads.close") { downloads.close() }
        closing.step("pluginManager.close") { plugins.closeRuntimes() }
        closing.step("pluginBrowserSessionTransport.close") { plugins.closeBrowserSessions() }
        closing.step("pluginEventGateway.close") { plugins.closeEventGateway() }
        closing.step("pluginDiagnosticLog.clear") { plugins.clearDiagnostics() }
        closing.step("contentFeatures.close") {
            if (contentFeatures != null) contentFeatures.close() else platformTextToSpeechEngine?.close()
        }
        // Every mutation producer is stopped; flush and terminate the coalesced writer.
        closing.step("repository.closePersistence") { repository.closePersistence() }
        closing.step("pluginHttpClient.close") { plugins.closeHttpClient() }
        closing.step("httpClient.close") { httpClient.close() }
        closing.throwFirstFailure()
    }

    /** Runs the steps of [close] one after another, whatever the ones before did. */
    private class Closing(private val stage: (String) -> Unit) {
        private var failure: Throwable? = null

        // A step that fails, cancellation included, is kept and thrown by throwFirstFailure once the rest ran.
        @Suppress("SuspendFunSwallowedCancellation")
        suspend fun step(name: String, block: suspend () -> Unit) {
            try {
                stage(name)
                // The caller going away must not leave the rest open or the snapshot unflushed.
                withContext(NonCancellable) { block() }
            } catch (logged: Throwable) {
                Log.warn("Shutdown", logged) { "Closing failed at $name" }
                failure?.addSuppressed(logged) ?: run { failure = logged }
            }
        }

        fun throwFirstFailure() {
            failure?.let { throw it }
        }
    }

    private companion object {
        const val CONTENT_BACKUP_APP_VERSION = SHINSOU_APP_VERSION
    }
}
