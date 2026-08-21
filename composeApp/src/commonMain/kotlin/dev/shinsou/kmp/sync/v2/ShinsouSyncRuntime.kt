package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.data.SnapshotMutationObserver
import dev.shinsou.kmp.annotation.ContentAnnotationStore
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentRightsAdmissionLease
import dev.shinsou.kmp.content.SharedContentTransactionStore
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import dev.shinsou.kmp.sync.crypto.SodiumBlobBodyCryptoV2
import dev.shinsou.kmp.sync.crypto.SodiumSyncCrypto
import dev.shinsou.kmp.sync.crypto.SyncDevicePublicKeyResolver
import dev.shinsou.kmp.sync.network.KtorCloudflareSyncApi
import dev.shinsou.kmp.sync.network.KtorCloudflareBlobBodyApiV2
import dev.shinsou.kmp.sync.network.KtorRealtimeWorkspaceClient
import dev.shinsou.kmp.sync.network.KtorSyncControlPlaneApi
import dev.shinsou.kmp.sync.network.SyncApiException
import dev.shinsou.kmp.sync.network.createSyncHttpClient
import dev.shinsou.kmp.sync.persistence.SqlDriverBlobAuthorityDepartureV2
import dev.shinsou.kmp.sync.provisioning.ProvisioningTrustContext
import dev.shinsou.kmp.sync.provisioning.ProvisioningDeviceRevocationReceipt
import dev.shinsou.kmp.sync.provisioning.ProvisioningRevocationWorkspaceBinding
import dev.shinsou.kmp.sync.provisioning.SyncProvisioningActivationGate
import dev.shinsou.kmp.sync.trust.DeviceDirectoryVerifier
import dev.shinsou.kmp.sync.trust.PinnedDevicePublicKeyResolver
import dev.shinsou.kmp.sync.trust.StoredDeviceTrustContextProvider
import dev.shinsou.kmp.sync.trust.DeviceDirectoryTrustContext
import dev.shinsou.kmp.sync.trust.TrustedDeviceAnchor
import dev.shinsou.kmp.sync.trust.TrustVerifyingCloudflareSyncApi
import io.ktor.client.HttpClient
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the complete Cloudflare v2 client graph without making UI or platform roots understand its
 * ordering constraints. A durable v2 session is the provider switch: no session means the legacy
 * snapshot writer may run; any v2 session reserves the provider even while linking or revoked.
 */
class ShinsouSyncRuntime(
    private val repository: ShinsouRepository,
    private val platformInfrastructure: SyncPlatformInfrastructure,
    private val platformHttpClient: HttpClient,
    private val devicePublicKeyResolver: SyncDevicePublicKeyResolver? = null,
    private val contentStore: SharedContentTransactionStore<SyncDraft>? = null,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : SyncWorkspaceDeparture, SyncProvisioningActivationGate {
    private val lifecycleMutex = Mutex()
    private val bodyDrainMutex = Mutex()
    private val annotationReconciliationMutex = Mutex()
    private val scopeJob: Job = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Default)
    private val mutableFailure = MutableStateFlow<Throwable?>(null)
    private val mutableEngineState = MutableStateFlow(SyncEngineState())
    private val mutableMaterializationDiagnostics = MutableStateFlow(SyncMaterializationDiagnostics())
    private val mutableReaderProgressReporter = MutableStateFlow<ReaderProgressReporter?>(null)
    private val replacementGuard = CloudflareSnapshotReplacementGuard(platformInfrastructure.sessionStore)
    private var components: RuntimeComponents? = null
    private var sharedLocalStore: PersistentLocalSyncStore? = null
    private var engineStateForwarder: Job? = null
    /** Startup catch-up is deliberately detached from the composition's first frame. */
    private var startupSyncJob: Job? = null
    /**
     * A ready workspace still needs its local SQLite journal before the v2 bridge can accept a
     * mutation. Keep that small safety boundary installed while the journal is opening, but do
     * not make Compose wait for SQLite/WAL setup before drawing the first frame.
     */
    private var startupMutationBoundary: CompletableDeferred<SnapshotMutationObserver>? = null
    private var requiredRotationWakeJob: Job? = null
    private var provisioningTrustContext: DeviceDirectoryTrustContext? = null
    private var closed = false

    val failure: StateFlow<Throwable?> = mutableFailure.asStateFlow()
    override val engineState: StateFlow<SyncEngineState> = mutableEngineState.asStateFlow()
    override val materializationDiagnostics: StateFlow<SyncMaterializationDiagnostics> =
        mutableMaterializationDiagnostics.asStateFlow()
    val readerProgressReporter: StateFlow<ReaderProgressReporter?> = mutableReaderProgressReporter.asStateFlow()

    /**
     * Installs a local mutation boundary and schedules all journal/network setup without blocking
     * the first usable frame. A ready workspace still reserves the provider synchronously, while
     * SQLite/WAL opening, key rotation, catch-up and realtime setup all run in [scope]. Mutations
     * arriving during that short window suspend on [DeferredSyncMutationObserver] rather than
     * being allowed to bypass the journal.
     */
    suspend fun start(): Unit = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            check(!closed) { "Sync runtime is closed" }
            repository.setSnapshotReplacementGuard(replacementGuard)
            val session = try {
                platformInfrastructure.sessionStore.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // A corrupt local WAL or secure store must not strand the first frame behind an
                // exception. Reserve the provider and expose the failure through runtime state;
                // the repair/retry UI can act after the app is usable.
                mutableFailure.value = failure
                repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
                teardownComponentsLocked()
                return@withLock
            }
            if (!isReadyCloudflareSession(session)) {
                configureInactiveMutationBoundaryLocked(session)
                teardownComponentsLocked()
                mutableFailure.value = null
                return@withLock
            }

            if (startupSyncJob?.isActive == true) return@withLock

            // Keep the provider fail-closed while the persistent journal is opening. The
            // deferred observer is replaced with the real RepositorySyncBridge before any
            // mutation is allowed to commit.
            val mutationBoundary = CompletableDeferred<SnapshotMutationObserver>()
            startupMutationBoundary?.completeExceptionally(
                CancellationException("A newer sync startup superseded the previous startup"),
            )
            startupMutationBoundary = mutationBoundary
            repository.configureSyncMutationBoundary(
                observer = DeferredSyncMutationObserver(mutationBoundary),
                guard = replacementGuard,
            )
            startupSyncJob = scope.launch {
                lifecycleMutex.withLock {
                    try {
                        runLifecycleStepLocked {
                            // Re-read the session after composition setup. Provisioning or a
                            // foreground edge may have changed ownership while this job waited.
                            val currentSession = platformInfrastructure.sessionStore.load()
                            if (isReadyCloudflareSession(currentSession)) {
                                // This is the only potentially expensive local step before the
                                // repository bridge is usable. Complete the boundary immediately
                                // after it, then keep catch-up/realtime work in the background.
                                check(prepareProviderLocked()) { "Cloudflare provider became inactive during startup" }
                                mutationBoundary.complete(
                                    requireNotNull(components).repositoryBridge,
                                )
                                reconcileProviderLocked(
                                    startEngine = true,
                                    foreground = false,
                                    preserveMutationBoundary = true,
                                )
                            } else {
                                configureInactiveMutationBoundaryLocked(currentSession)
                                teardownComponentsLocked()
                                mutationBoundary.completeExceptionally(
                                    SyncMutationBoundaryUnavailableException(),
                                )
                            }
                        }
                    } finally {
                        if (!mutationBoundary.isCompleted) {
                            mutationBoundary.completeExceptionally(
                                SyncMutationBoundaryUnavailableException(),
                            )
                        }
                        if (startupMutationBoundary === mutationBoundary) {
                            startupMutationBoundary = null
                        }
                    }
                }
            }
        }
    }

    suspend fun onForeground(): Unit = onForegroundInternal(allowDuringStartup = false)

    private suspend fun onForegroundInternal(allowDuringStartup: Boolean): Unit = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            // The initial catch-up already owns the engine operation. A platform foreground edge
            // arriving during that window must not start a duplicate 20-second sync pass.
            if (!allowDuringStartup && startupSyncJob?.isActive == true) return@withLock
            runLifecycleStepLocked {
                repository.setSnapshotReplacementGuard(replacementGuard)
                reconcileProviderLocked(startEngine = false, foreground = true)
            }
        }
    }

    suspend fun onBackground(): Unit = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            if (closed) return@withLock
            val current = components
            if (current == null) {
                repository.flushPersistence()
                configureInactiveMutationBoundaryLocked(loadSessionFailClosed())
                return@withLock
            }
            var engineFailure: Throwable? = null
            try {
                current.engine.onBackground()
                mutableFailure.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableFailure.value = failure
                engineFailure = failure
            }
            val session = loadSessionFailClosed()
            if (engineFailure != null || !isReadyCloudflareSession(session)) {
                configureInactiveMutationBoundaryLocked(session)
                teardownComponentsLocked()
            }
        }
    }

    /**
     * Moves a bounded content-import batch into the authoritative sync journal. This is invoked
     * only from composition-owned background jobs, never from the first-frame or reader path.
     */
    suspend fun drainContentOutbox(
        contentStore: SharedContentTransactionStore<SyncDraft>,
        maxDrafts: Int = ContentSyncOutboxDrainBridge.DEFAULT_MAX_DRAFTS,
    ): ContentSyncOutboxDrainResult? = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            if (closed) return@withLock null
            val session = platformInfrastructure.sessionStore.load()
            if (!isReadyCloudflareSession(session)) return@withLock null
            ContentSyncOutboxDrainBridge(
                contentStore = contentStore,
                localStore = ensureLocalStoreLocked(),
                deviceId = requireNotNull(session).deviceId,
                nowMillis = nowMillis,
            ).drain(maxDrafts)
        }
    }

    /**
     * Reconciles one bounded annotation page after startup catch-up has finished. This work never
     * runs on the first-frame or reader mutation path: composition owns a conflated background
     * actor and passes the returned cursor back on its next slice.
     */
    internal suspend fun reconcileContentAnnotations(
        annotationStore: ContentAnnotationStore,
        contentStore: SharedContentTransactionStore<SyncDraft>,
        maxAnnotations: Int = ContentAnnotationSyncReconciliationBridge.DEFAULT_MAX_ANNOTATIONS,
        afterAnnotationId: String? = null,
    ): ContentAnnotationSyncReconciliationResult? = withContext(Dispatchers.Default) {
        val startup = lifecycleMutex.withLock {
            if (closed) return@withContext null
            startupSyncJob?.takeIf(Job::isActive)
        }
        // Catch-up can be slow, but this await belongs only to the detached annotation actor.
        // Waiting here prevents a pre-catch-up replica snapshot from being mistaken for absence.
        startup?.join()

        annotationReconciliationMutex.withLock annotation@{
            val localStore = lifecycleMutex.withLock lifecycle@{
                if (closed) return@lifecycle null
                val session = platformInfrastructure.sessionStore.load()
                if (!isReadyCloudflareSession(session)) null else ensureLocalStoreLocked()
            } ?: return@annotation null
            ContentAnnotationSyncReconciliationBridge(
                annotationStore = annotationStore,
                contentStore = contentStore,
                localStore = localStore,
            ).reconcileSlice(
                maxAnnotations = maxAnnotations,
                afterAnnotationId = afterAnnotationId,
            )
        }
    }

    /**
     * Runs at most one low-priority encrypted body slice outside the lifecycle mutex. The caller
     * schedules this only after the reader/background flush, so large local reads, encryption and
     * R2 traffic cannot delay startup, navigation, or progress durability.
     */
    suspend fun drainContentBlobJobs(
        contentStore: SharedContentTransactionStore<SyncDraft>,
        blobStore: ContentBlobStore,
        operationGate: ContentOperationGate,
        journal: BlobTransferJournalV2,
        maxJobs: Int = ContentBlobSyncCoordinatorV2.DEFAULT_MAX_JOBS,
    ): ContentBlobSyncDrainResultV2? = withContext(Dispatchers.Default) {
        bodyDrainMutex.withLock bodyDrain@{
            val access = lifecycleMutex.withLock lifecycle@{
                if (closed) return@lifecycle null
                val session = platformInfrastructure.sessionStore.load()
                if (!isReadyCloudflareSession(session)) return@lifecycle null
                val current = components ?: return@lifecycle null
                BodyDrainAccess(requireNotNull(session), current)
            } ?: return@bodyDrain null

            // Capability refresh is intentionally outside lifecycleMutex. A slow Worker or R2
            // request must never serialize foreground catch-up or a reader background flush.
            val capability = obtainValidatedCapability(access.components, access.session)
            val uploader = EncryptedBlobUploaderV2(
                blobStore = blobStore,
                bodyApi = KtorCloudflareBlobBodyApiV2(access.components.syncHttpClient),
                crypto = SodiumBlobBodyCryptoV2(platformInfrastructure.secretStore),
                journal = journal,
                operationGate = operationGate,
                nowEpochMillis = nowMillis,
            )
            ContentBlobSyncCoordinatorV2(
                contentStore = contentStore,
                localStore = access.components.localStore,
                uploader = uploader,
                journal = journal,
                nowEpochMillis = nowMillis,
            ).drain(access.session, capability, maxJobs)
        }
    }

    /**
     * Runs one restart-safe DEK re-wrap or tombstone/ack/GC item after ordinary body work.
     * Composition calls this only from its cancellable background window; bootstrap/checkpoint
     * downloads and Worker requests stay outside [lifecycleMutex].
     */
    internal suspend fun drainContentBlobLifecycle(
        journal: BlobLifecycleJournalV2,
        authorizeBlobSync: suspend (String) -> Boolean,
    ): BlobLifecycleSliceResultV2? = withContext(Dispatchers.Default) {
        bodyDrainMutex.withLock bodyDrain@{
            val access = lifecycleMutex.withLock lifecycle@{
                if (closed) return@lifecycle null
                val session = platformInfrastructure.sessionStore.load()
                if (!isReadyCloudflareSession(session)) return@lifecycle null
                val current = components ?: return@lifecycle null
                BodyDrainAccess(requireNotNull(session), current)
            } ?: return@bodyDrain null

            val capability = obtainValidatedCapability(access.components, access.session)
            val bodyApi = KtorCloudflareBlobBodyApiV2(access.components.syncHttpClient)
            val bodyCoordinator = BlobLifecycleCoordinatorV2(
                bodyApi = bodyApi,
                blobCrypto = SodiumBlobBodyCryptoV2(platformInfrastructure.secretStore),
                syncCrypto = access.components.crypto,
                nowEpochMillis = nowMillis,
            )
            DurableBlobLifecycleCoordinatorV2(
                metadataApi = access.components.api,
                bodyCoordinator = bodyCoordinator,
                syncCrypto = access.components.crypto,
                localStore = access.components.localStore,
                journal = journal,
                authorizeBlobSync = authorizeBlobSync,
                nowEpochMillis = nowMillis,
            ).drainSlice(access.session, capability)
        }
    }

    /**
     * Converts one terminal `reupload_required` lifecycle record into durable local upload work.
     * No network or crypto runs here; the job is intentionally consumed by the next background
     * edge, after metadata and foreground-sensitive work have had another chance to run first.
     */
    internal suspend fun drainContentBlobReuploadRecovery(
        coordinator: BlobReuploadRecoveryCoordinatorV2,
    ): BlobReuploadRecoveryResultV2? = withContext(Dispatchers.Default) {
        bodyDrainMutex.withLock bodyDrain@{
            val session = lifecycleMutex.withLock lifecycle@{
                if (closed) return@lifecycle null
                platformInfrastructure.sessionStore.load()
                    ?.takeIf(::isReadyCloudflareSession)
            } ?: return@bodyDrain null
            coordinator.drainSlice(session.instanceId, session.workspaceId)
        }
    }

    /**
     * Runs one destination body/materialization slice. Like uploads, this is called only by the
     * composition's cancellable background worker and never from startup or foreground catch-up.
     */
    internal suspend fun drainContentReplicaMaterialization(
        contentStore: SharedContentTransactionStore<SyncDraft>,
        blobStore: ContentBlobStore,
        operationGate: ContentOperationGate,
        acquireValidatedRights: (List<RightsGrant>) -> ContentRightsAdmissionLease,
    ): ContentReplicaMaterializationResultV2? = withContext(Dispatchers.Default) {
        bodyDrainMutex.withLock bodyDrain@{
            val access = lifecycleMutex.withLock lifecycle@{
                if (closed) return@lifecycle null
                val session = platformInfrastructure.sessionStore.load()
                if (!isReadyCloudflareSession(session)) return@lifecycle null
                val current = components ?: return@lifecycle null
                BodyDrainAccess(requireNotNull(session), current)
            } ?: return@bodyDrain null

            val capability = obtainValidatedCapability(access.components, access.session)
            val downloader = EncryptedBlobDownloaderV2(
                blobStore = blobStore,
                bodyApi = KtorCloudflareBlobBodyApiV2(access.components.syncHttpClient),
                crypto = SodiumBlobBodyCryptoV2(platformInfrastructure.secretStore),
                operationGate = operationGate,
            )
            ContentReplicaMaterializerV2(
                localStore = access.components.localStore,
                blobStore = blobStore,
                contentStore = contentStore,
                downloader = downloader,
                acquireValidatedRights = acquireValidatedRights,
            ).drainSlice(access.session, capability)
        }
    }

    override suspend fun syncNow(): Unit = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            try {
                check(!closed) { "Sync runtime is closed" }
                val session = platformInfrastructure.sessionStore.load()
                if (!isReadyCloudflareSession(session)) {
                    configureInactiveMutationBoundaryLocked(session)
                    return@withLock
                }
                val readySession = requireNotNull(session)
                val current = components ?: createComponentsLocked().also { components = it }
                repository.setSnapshotReplacementGuard(replacementGuard)
                repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
                ensureRequiredServerRotationLocked(current, readySession)
                repository.configureSyncMutationBoundary(current.repositoryBridge, replacementGuard)
                current.engine.onForeground()
                requireEngineCompleted(current)
                mutableFailure.value = null
            } catch (cancelled: CancellationException) {
                repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
                teardownComponentsLocked()
                throw cancelled
            } catch (failure: Throwable) {
                mutableFailure.value = failure
                repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
                teardownComponentsLocked()
                throw failure
            }
        }
    }

    override suspend fun retryMaterialization(): Unit = updateMaterializationReview {
        requestMaterializationRetry()
    }

    override suspend fun repairIdentityCollision(key: SyncEntityKey): Unit = updateMaterializationReview {
        repairIdentityCollision(key)
    }

    override suspend fun acceptRepositoryTrust(
        baseUrl: String,
        proposedFingerprint: String,
    ): Unit = updateMaterializationReview {
        acceptRepositoryTrust(baseUrl, proposedFingerprint)
    }

    override suspend fun rejectRepositoryTrust(
        baseUrl: String,
        proposedFingerprint: String,
    ): Unit = updateMaterializationReview {
        rejectRepositoryTrust(baseUrl, proposedFingerprint)
    }

    /**
     * Production entry point for a plugin canonical-ID or URL-normalization version migration.
     * Success means the remap is already a durable local draft; upload follows the ordinary sync
     * engine journal. A missing/not-ready session or identity collision is reported, never ignored.
     */
    suspend fun remapEntityKey(
        oldKey: SyncEntityKey,
        newKey: SyncEntityKey,
    ): SyncEntityKeyRemapResult = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            check(!closed) { "Sync runtime is closed" }
            val session = platformInfrastructure.sessionStore.load()
                ?: throw SyncEntityKeyRemapConflictException("Sync is not configured for an identity remap")
            if (!isReadyCloudflareSession(session)) {
                throw SyncEntityKeyRemapConflictException("Sync session is not ready for an identity remap")
            }
            val current = components ?: createComponentsLocked().also { components = it }
            current.repositoryBridge.remapEntityKey(oldKey, newKey)
        }
    }

    suspend fun upgradeContentIdentity(
        oldKey: SyncEntityKey,
        sourceIdentity: String,
        urlOrCanonicalId: String,
        newVersion: Int,
    ): SyncEntityKeyRemapResult {
        val newKey = when (oldKey.entityType) {
            SyncEntityType.MANGA -> SyncEntityKey.manga(sourceIdentity, urlOrCanonicalId, newVersion)
            SyncEntityType.CHAPTER -> SyncEntityKey.chapter(sourceIdentity, urlOrCanonicalId, newVersion)
            else -> throw IllegalArgumentException("Only manga/chapter identities use the content normalizer")
        }
        return remapEntityKey(oldKey, newKey)
    }

    override suspend fun seedSnapshotAndVerifyInitialCheckpoint(
        linkingSession: SyncSession,
        trustContext: ProvisioningTrustContext.InitialSelfAnchor,
    ): Unit = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            val directoryContext = DeviceDirectoryTrustContext(
                instanceId = linkingSession.instanceId,
                workspaceId = linkingSession.workspaceId,
                trustedDevices = listOf(
                    TrustedDeviceAnchor(
                        trustContext.deviceId,
                        trustContext.signingPublicKey,
                        trustContext.wrappingPublicKey,
                    ),
                ),
                trustedRecoverySigningPublicKeys = setOf(trustContext.recoverySigningPublicKey),
            )
            withProvisioningComponentsLocked(linkingSession, directoryContext) { current, durable ->
                require(trustContext.deviceId == durable.deviceId) {
                    "Initial trust anchor belongs to another device"
                }
                val created = current.repositoryBridge.initializeReplicaForInitialCheckpoint(
                    repository.currentSnapshot,
                    durable.deviceId,
                )
                current.api.capabilities(durable.endpoint)
                    .requireCompatible(
                        SYNC_PROTOCOL_VERSION,
                        SYNC_PROTOCOL_VERSION,
                        SYNC_STATE_SCHEMA_VERSION,
                        SYNC_STATE_SCHEMA_VERSION,
                    )
                val capability = obtainValidatedCapability(current, durable)
                val before = current.api.bootstrap(durable, capability)
                require(before.headSeq == 0L && before.activeKeyEpoch == durable.activeKeyEpoch) {
                    "Initial workspace is not at checkpoint-zero boundary"
                }
                if (created) {
                    require(before.retainedStableCheckpoints.isEmpty() && before.candidateCheckpoint == null) {
                        "A new genesis seed conflicts with an existing checkpoint"
                    }
                }
                val first = current.checkpointCoordinator.coordinate(durable, capability)
                if (created && first != CheckpointCoordinatorOutcome.PROPOSED) {
                    throw SyncInvariantViolation("Initial checkpoint was not proposed from the durable genesis seed")
                }
                val validation = if (first == CheckpointCoordinatorOutcome.PROPOSED) {
                    current.checkpointCoordinator.coordinate(durable, capability)
                } else {
                    first
                }
                if (validation == CheckpointCoordinatorOutcome.REJECTED ||
                    (created && validation !in setOf(
                        CheckpointCoordinatorOutcome.VALIDATED,
                        CheckpointCoordinatorOutcome.DEFERRED,
                    ))
                ) {
                    throw SyncInvariantViolation("Initial checkpoint failed independent verification")
                }
                verifyInitialCheckpointRoundTrip(current, durable, capability)
                requirePinnedDevice(
                    durable,
                    trustContext.deviceId,
                    trustContext.signingPublicKey,
                    trustContext.wrappingPublicKey,
                )
            }
        }
    }

    override suspend fun verifyPairedWorkspaceAndCatchUp(
        linkingSession: SyncSession,
        trustContext: ProvisioningTrustContext.PairingSponsorAnchor,
    ): Unit = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            val directoryContext = DeviceDirectoryTrustContext(
                instanceId = linkingSession.instanceId,
                workspaceId = linkingSession.workspaceId,
                trustedDevices = listOf(
                    TrustedDeviceAnchor(
                        trustContext.sponsorDeviceId,
                        trustContext.sponsorSigningPublicKey,
                        trustContext.sponsorWrappingPublicKey,
                    ),
                ),
            )
            withProvisioningComponentsLocked(linkingSession, directoryContext) { current, durable ->
                installStableBootstrapBaseIfNeeded(current, durable)
                current.engine.start()
                requireEngineCompleted(current)
                requireCaughtUpAndDrained(current, durable)
                requirePinnedDevice(
                    durable,
                    trustContext.sponsorDeviceId,
                    trustContext.sponsorSigningPublicKey,
                    trustContext.sponsorWrappingPublicKey,
                )
            }
        }
    }

    override suspend fun verifyRecoveredWorkspaceAndCatchUp(
        linkingSession: SyncSession,
        trustContext: ProvisioningTrustContext.RecoveryAnchor,
    ): Unit = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            // Recovery trust must chain through the old offline recovery root. The newly generated
            // device keys are checked against the resulting pin only after that chain verifies.
            val directoryContext = DeviceDirectoryTrustContext(
                instanceId = linkingSession.instanceId,
                workspaceId = linkingSession.workspaceId,
                trustedRecoverySigningPublicKeys = setOf(trustContext.recoverySigningPublicKey),
            )
            withProvisioningComponentsLocked(linkingSession, directoryContext) { current, durable ->
                require(trustContext.deviceId == durable.deviceId) {
                    "Recovered device anchor belongs to another device"
                }
                installStableBootstrapBaseIfNeeded(current, durable)
                current.engine.start()
                requireEngineCompleted(current)
                requireCaughtUpAndDrained(current, durable)
                requirePinnedDevice(
                    durable,
                    trustContext.deviceId,
                    trustContext.signingPublicKey,
                    trustContext.wrappingPublicKey,
                )
            }
        }
    }

    override suspend fun rotateAfterRevocation(
        session: SyncSession,
        revokedDeviceId: String,
    ): Unit = withContext(Dispatchers.Default) {
        require(revokedDeviceId.isNotBlank() && revokedDeviceId != session.deviceId) {
            "The current device cannot revoke itself"
        }
        lifecycleMutex.withLock { rotateAndVerifyCheckpointLocked(session) }
    }

    /**
     * Reconciles the permanent revoke receipt without using a stale local epoch as evidence. The
     * controller owns and retains the pending marker until this method returns successfully.
     */
    override suspend fun reconcileDeviceRevocation(
        session: SyncSession,
        receipt: ProvisioningDeviceRevocationReceipt,
    ): Unit = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            try {
                check(!closed) { "Sync runtime is closed" }
                val durable = platformInfrastructure.sessionStore.load()
                    ?: throw SyncInvariantViolation("Device revocation session disappeared")
                requireSameSessionIdentity(session, durable)
                val pending = durable.pendingDeviceRevocation
                    ?: throw SyncInvariantViolation("Device revocation is missing its durable operation marker")
                val binding = validateRevocationReceipt(durable, pending, receipt)
                val action = revocationReconciliationAction(durable.activeKeyEpoch, binding)

                val current = components ?: createComponentsLocked().also { components = it }
                val refreshed = current.rotationCoordinator.refreshKeyEpochBeforeSync(
                    durable,
                    current.trustContextProvider(durable),
                ) { installed ->
                    validateRevocationRotationEvidence(
                        localEpochBeforeRefresh = durable.activeKeyEpoch,
                        binding = binding,
                        installed = installed,
                    )
                }
                requireSameSessionIdentity(durable, refreshed)
                if (refreshed.activeKeyEpoch != binding.currentActiveKeyEpoch) {
                    throw SyncInvariantViolation("Device revocation receipt no longer matches the server key epoch")
                }
                requireRevokedDirectoryBinding(refreshed, binding, receipt)

                when (action) {
                    RevocationReconciliationAction.ROTATE_LATEST_ONCE ->
                        rotateAndVerifyCheckpointLocked(refreshed)
                    RevocationReconciliationAction.VERIFY_COVERING_ROTATION ->
                        verifyPostRotationCheckpointLocked(refreshed, current)
                }
                mutableFailure.value = null
            } catch (cancelled: CancellationException) {
                repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
                teardownComponentsLocked()
                throw cancelled
            } catch (failure: Throwable) {
                mutableFailure.value = failure
                repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
                teardownComponentsLocked()
                throw failure
            }
        }
    }

    override suspend fun rotateAfterRecovery(session: SyncSession): Unit = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            val durable = platformInfrastructure.sessionStore.load()
                ?: throw SyncInvariantViolation("Recovery session disappeared before rotation")
            requireSameSessionIdentity(session, durable)
            val pending = durable.pendingRecovery
                ?: throw SyncInvariantViolation("Recovery rotation is missing its durable claim marker")
            when (recoveryRotationAction(durable.activeKeyEpoch, pending.claimedKeyEpoch)) {
                RecoveryRotationAction.ROTATE_ONCE -> rotateAndVerifyCheckpointLocked(durable)
                RecoveryRotationAction.VERIFY_EXISTING_ROTATION -> verifyPostRotationCheckpointLocked(durable)
            }
        }
    }

    /** Detaches every producer before deleting the workspace state, keys, credentials and pin. */
    override suspend fun leaveWorkspace(): Unit = withContext(Dispatchers.Default) {
        // Body transfer and annotation reconciliation deliberately release lifecycleMutex while
        // doing bounded work. Take their outer locks first (the same order as their own paths)
        // so neither can retain a now-departed local store or HTTP client after keys are erased.
        bodyDrainMutex.withLock {
            annotationReconciliationMutex.withLock {
                lifecycleMutex.withLock {
                    check(!closed) { "Sync runtime is closed" }
                    repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
                    teardownComponentsLocked()
                    val localStore = ensureLocalStoreLocked()
                    LocalSyncWorkspaceDeparture(
                        sessionStore = platformInfrastructure.sessionStore,
                        secretStore = platformInfrastructure.secretStore,
                        localStore = localStore,
                        deviceDirectoryPinStore = platformInfrastructure.deviceDirectoryPinStore,
                        contentStore = contentStore,
                        clearBlobAuthorityState = { authority ->
                            SqlDriverBlobAuthorityDepartureV2(platformInfrastructure.contentDriver())
                                .clearAuthority(authority.instanceId, authority.workspaceId)
                        },
                    ).leaveWorkspace()
                    repository.configureSyncMutationBoundary(observer = null, guard = replacementGuard)
                    mutableFailure.value = null
                    mutableMaterializationDiagnostics.value = SyncMaterializationDiagnostics()
                }
            }
        }
    }

    /** Fail closed: unreadable session metadata must not enable a second remote writer. */
    suspend fun isLegacySnapshotWriterAllowed(): Boolean = try {
        platformInfrastructure.sessionStore.load() == null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.Default) {
        // Do not make shutdown wait for a startup catch-up that is intentionally allowed to be
        // slow. Cancellation also releases the lifecycle mutex before the final engine flush.
        startupSyncJob?.cancel()
        bodyDrainMutex.withLock {
            annotationReconciliationMutex.withLock {
                lifecycleMutex.withLock {
                    if (closed) return@withLock
                    closed = true
                    val session = loadSessionFailClosed()
                    repository.configureSyncMutationBoundary(
                        observer = if (session == null) null else FailClosedSyncMutationObserver,
                        guard = if (session == null) null else replacementGuard,
                    )
                    val current = components
                    if (current != null) {
                        try {
                            current.engine.onBackground()
                        } catch (failure: Throwable) {
                            mutableFailure.value = failure
                        }
                    }
                    teardownComponentsLocked()
                    platformInfrastructure.close()
                }
            }
        }
        scopeJob.cancelAndJoin()
    }

    private suspend fun <T> withProvisioningComponentsLocked(
        linkingSession: SyncSession,
        trustContext: DeviceDirectoryTrustContext,
        block: suspend (RuntimeComponents, SyncSession) -> T,
    ): T {
        check(!closed) { "Sync runtime is closed" }
        require(linkingSession.status == SyncSessionStatus.LINKING) {
            "Provisioning activation requires a linking session"
        }
        val durable = platformInfrastructure.sessionStore.load()
            ?: throw IllegalStateException("Provisioning session is not durable")
        requireSameSessionIdentity(linkingSession, durable)
        require(durable.status == SyncSessionStatus.LINKING) {
            "Durable provisioning session is no longer linking"
        }
        repository.setSnapshotReplacementGuard(replacementGuard)
        repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
        teardownComponentsLocked()
        provisioningTrustContext = trustContext
        val current = try {
            createComponentsLocked().also { components = it }
        } catch (failure: Throwable) {
            provisioningTrustContext = null
            throw failure
        }
        return try {
            block(current, durable).also { mutableFailure.value = null }
        } catch (cancelled: CancellationException) {
            repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
            teardownComponentsLocked()
            throw cancelled
        } catch (failure: Throwable) {
            mutableFailure.value = failure
            repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
            teardownComponentsLocked()
            throw failure
        } finally {
            provisioningTrustContext = null
        }
    }

    private suspend fun obtainValidatedCapability(
        current: RuntimeComponents,
        session: SyncSession,
    ): WorkspaceCapability {
        val capability = current.api.obtainWorkspaceCapability(session)
        val binding = capability.binding
        if (binding.deviceId != session.deviceId || binding.workspaceId != session.workspaceId ||
            binding.deviceAuthEpoch != session.deviceAuthEpoch ||
            binding.membershipAuthEpoch != session.membershipAuthEpoch ||
            binding.keyEpoch != session.activeKeyEpoch || binding.expiresAtMillis <= nowMillis()
        ) {
            throw SyncInvariantViolation("Workspace capability does not match the provisioning session")
        }
        return capability
    }

    private suspend fun verifyInitialCheckpointRoundTrip(
        current: RuntimeComponents,
        session: SyncSession,
        capability: WorkspaceCapability,
    ) {
        val bootstrap = current.api.bootstrap(session, capability)
        if (bootstrap.headSeq != 0L || bootstrap.activeKeyEpoch != session.activeKeyEpoch) {
            throw SyncInvariantViolation("Initial checkpoint bootstrap boundary changed")
        }
        val candidate = bootstrap.candidateCheckpoint?.takeIf { it.throughWorkspaceSeq == 0L }
        val descriptor = candidate?.asDownloadDescriptor()
            ?: bootstrap.retainedStableCheckpoints.singleOrNull {
                it.throughWorkspaceSeq == 0L && it.keyEpoch == bootstrap.activeKeyEpoch &&
                    it.previousStableCiphertextSha256Base64Url == null
            }
            ?: throw SyncInvariantViolation("Verified initial checkpoint is absent from bootstrap")
        val encrypted = current.api.downloadCheckpoint(session, capability, descriptor)
        val verified = current.crypto.openAndVerifyCheckpoint(session, encrypted, descriptor)
        val local = current.localStore.readState()
        val seed = local.genesisCheckpointSeed
            ?: throw SyncInvariantViolation("Durable initial checkpoint seed disappeared")
        if (seed.deviceId != session.deviceId || verified.header.deviceId != session.deviceId ||
            local.replica.throughWorkspaceSeq != 0L || local.drafts.isNotEmpty() ||
            local.sealedOutbox.isNotEmpty()
        ) {
            throw SyncInvariantViolation("Initial checkpoint is not bound to a pristine local seed")
        }
        if (candidate != null && candidate.uploaderDeviceId != session.deviceId) {
            throw SyncInvariantViolation("Initial checkpoint uploader was substituted")
        }
        val expected = local.replica.copy(
            keyEpoch = bootstrap.activeKeyEpoch,
            throughWorkspaceSeq = 0,
            previousStableCheckpointHash = null,
        ).normalized()
        if (current.codec.canonicalCheckpointState(expected) != verified.canonicalState) {
            throw SyncInvariantViolation("Downloaded initial checkpoint differs from the local snapshot")
        }
    }

    private suspend fun installStableBootstrapBaseIfNeeded(
        current: RuntimeComponents,
        session: SyncSession,
    ) {
        current.api.capabilities(session.endpoint).requireCompatible(
            SYNC_PROTOCOL_VERSION,
            SYNC_PROTOCOL_VERSION,
            SYNC_STATE_SCHEMA_VERSION,
            SYNC_STATE_SCHEMA_VERSION,
        )
        val capability = obtainValidatedCapability(current, session)
        val bootstrap = current.api.bootstrap(session, capability)
        if (bootstrap.headSeq < 0 || bootstrap.activeKeyEpoch != session.activeKeyEpoch) {
            throw SyncInvariantViolation("Linking bootstrap returned an inconsistent workspace epoch/head")
        }
        installBootstrapKeyMetadata(current, session, bootstrap)
        val local = current.localStore.readState()
        if (!needsStableBootstrapBase(local)) return
        val retained = bootstrap.retainedStableCheckpoints
            .distinctBy { it.checkpointId to it.ciphertextSha256Base64Url }
        if (retained.zipWithNext().any { (newer, older) ->
                newer.throughWorkspaceSeq < older.throughWorkspaceSeq
            }
        ) {
            throw SyncInvariantViolation("Linked bootstrap checkpoints are outside server promotion order")
        }
        if (retained.isEmpty()) {
            throw SyncInvariantViolation("Linked workspace has no stable checkpoint base")
        }
        val failures = mutableListOf<String>()
        retained.forEach { descriptor ->
            try {
                if (descriptor.throughWorkspaceSeq > bootstrap.headSeq) {
                    throw SyncInvariantViolation("Checkpoint exceeds the fixed workspace head")
                }
                val encrypted = current.api.downloadCheckpoint(session, capability, descriptor)
                val verified = current.crypto.openAndVerifyCheckpoint(session, encrypted, descriptor)
                if (verified.state.throughWorkspaceSeq != descriptor.throughWorkspaceSeq ||
                    verified.state.keyEpoch != descriptor.keyEpoch
                ) {
                    throw SyncInvariantViolation("Checkpoint plaintext conflicts with bootstrap metadata")
                }
                val tail = downloadVerifiedTail(
                    current,
                    session,
                    capability,
                    descriptor.throughWorkspaceSeq,
                    bootstrap.headSeq,
                )
                val installed = CheckpointInstaller.install(
                    current.localStore,
                    verified,
                    tail,
                    bootstrap.headSeq,
                    current.codec,
                ).state
                flushProjection(current.localStore, installed)
                val projected = current.localStore.readState()
                current.localStore.transaction {
                    if (!markMaterialized(
                            expectedReplica = projected.replica,
                            expectedIdentityMap = projected.identityMap,
                            expectedRepositoryTrustConfirmations = projected.repositoryTrustConfirmations,
                            expectedRepositoryTrustApprovals = projected.repositoryTrustApprovals,
                        )
                    ) {
                        throw SyncInvariantViolation("Linked projection changed before it was marked durable")
                    }
                }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failures += "${descriptor.checkpointId}: ${failure.message ?: failure::class.simpleName}"
            }
        }
        throw SyncInvariantViolation(
            "No retained checkpoint could initialize the linked workspace (${failures.joinToString("; ")})",
        )
    }

    private suspend fun installBootstrapKeyMetadata(
        current: RuntimeComponents,
        session: SyncSession,
        bootstrap: BootstrapResponse,
    ) {
        val epochs = buildSet {
            add(bootstrap.activeKeyEpoch)
            addAll(bootstrap.requiredKeyEpochs)
            bootstrap.retainedStableCheckpoints.forEach { add(it.keyEpoch) }
        }
        if (epochs.any { it <= 0 || it > bootstrap.activeKeyEpoch }) {
            throw SyncInvariantViolation("Bootstrap contains an invalid workspace key epoch")
        }
        epochs.sorted().filter { it != bootstrap.activeKeyEpoch }.forEach { epoch ->
            val key = SyncSecretKey.WorkspaceEpochKey(session.workspaceId, epoch)
            platformInfrastructure.secretStore.requireSecret(key)
            current.localStore.transaction {
                retainKeyEpoch(KeyEpochMetadata(epoch, key.redactedName, KeyEpochStatus.RETAINED, nowMillis()))
            }
        }
        val active = SyncSecretKey.WorkspaceEpochKey(session.workspaceId, bootstrap.activeKeyEpoch)
        platformInfrastructure.secretStore.requireSecret(active)
        current.localStore.transaction {
            retainKeyEpoch(
                KeyEpochMetadata(
                    bootstrap.activeKeyEpoch,
                    active.redactedName,
                    KeyEpochStatus.ACTIVE,
                    nowMillis(),
                ),
            )
        }
    }

    private suspend fun downloadVerifiedTail(
        current: RuntimeComponents,
        session: SyncSession,
        capability: WorkspaceCapability,
        afterExclusive: Long,
        fixedHead: Long,
    ): List<CommittedSyncEvent> {
        var cursor = afterExclusive
        val result = mutableListOf<CommittedSyncEvent>()
        while (cursor < fixedHead) {
            val page = current.api.catchUp(session, capability, cursor, fixedHead, LINKING_CATCH_UP_PAGE_SIZE)
            if (page.fromExclusive != cursor || page.untilInclusive != fixedHead || page.headSeq < fixedHead ||
                page.nextCursor <= cursor || page.nextCursor > fixedHead ||
                page.hasMore != (page.nextCursor < fixedHead)
            ) {
                throw SyncInvariantViolation("Checkpoint tail pagination changed its fixed boundary")
            }
            var expected = cursor + 1
            page.events.forEach { remote ->
                if (remote.workspaceSeq != expected || remote.workspaceSeq > page.nextCursor) {
                    throw SyncSequenceGapException(expected, remote.workspaceSeq)
                }
                result += CommittedSyncEvent(
                    remote.workspaceSeq,
                    current.crypto.openAndVerifyEvent(session, remote).event,
                )
                expected += 1
            }
            if (expected - 1 != page.nextCursor) {
                throw SyncInvariantViolation("Checkpoint tail cursor does not cover exactly its events")
            }
            cursor = page.nextCursor
        }
        return result
    }

    private suspend fun requireCaughtUpAndDrained(current: RuntimeComponents, expected: SyncSession) {
        val session = platformInfrastructure.sessionStore.load()
            ?: throw SyncInvariantViolation("Linking session disappeared during catch-up")
        requireSameSessionIdentity(expected, session)
        val capability = obtainValidatedCapability(current, session)
        val bootstrap = current.api.bootstrap(session, capability)
        val local = current.localStore.readState()
        if (local.replica.throughWorkspaceSeq != bootstrap.headSeq ||
            local.drafts.isNotEmpty() || local.sealedOutbox.isNotEmpty() || local.materializationPending
        ) {
            throw SyncInvariantViolation("Linked workspace did not reach a durable, drained remote head")
        }
    }

    private suspend fun requirePinnedDevice(
        session: SyncSession,
        deviceId: String,
        signingPublicKey: String,
        wrappingPublicKey: String,
    ) {
        val pin = platformInfrastructure.deviceDirectoryPinStore.load(session.workspaceId)?.device(deviceId)
            ?: throw SyncInvariantViolation("Authenticated device is absent from the pinned directory")
        if (pin.signingPublicKey != signingPublicKey || pin.wrappingPublicKey != wrappingPublicKey) {
            throw SyncInvariantViolation("Pinned device keys conflict with the activation anchor")
        }
    }

    private fun validateRevocationReceipt(
        session: SyncSession,
        pending: PendingDeviceRevocation,
        receipt: ProvisioningDeviceRevocationReceipt,
    ): ProvisioningRevocationWorkspaceBinding {
        if (session.status != SyncSessionStatus.READY ||
            session.pendingDeviceRevocation != pending ||
            receipt.revocationId != pending.revocationId ||
            receipt.actorDeviceId != session.deviceId ||
            receipt.revokedDeviceId != pending.targetDeviceId ||
            receipt.committedAtMillis <= 0 ||
            receipt.workspaceBindings.isEmpty() ||
            receipt.workspaceBindings.map { it.workspaceId }.distinct().size != receipt.workspaceBindings.size
        ) {
            throw SyncInvariantViolation("Device revocation receipt crossed its durable operation binding")
        }
        receipt.workspaceBindings.forEach { candidate ->
            if (!candidate.workspaceId.matches(REVOCATION_RUNTIME_UUID) ||
                candidate.revokedAtKeyEpoch <= 0 ||
                candidate.directoryEpochAfterRevocation <= 0 ||
                candidate.currentActiveKeyEpoch < candidate.revokedAtKeyEpoch
            ) {
                throw SyncInvariantViolation("Device revocation receipt contains an invalid workspace binding")
            }
            validateRevocationCoverageShape(candidate)
        }
        return receipt.workspaceBindings.singleOrNull { it.workspaceId == session.workspaceId }
            ?: throw SyncInvariantViolation("Device revocation receipt does not bind the active workspace exactly once")
    }

    private suspend fun requireRevokedDirectoryBinding(
        session: SyncSession,
        binding: ProvisioningRevocationWorkspaceBinding,
        receipt: ProvisioningDeviceRevocationReceipt,
    ) {
        val directory = platformInfrastructure.deviceDirectoryPinStore.load(session.workspaceId)
            ?: throw SyncInvariantViolation("Device revocation has no authenticated directory pin")
        if (directory.workspaceId != session.workspaceId ||
            directory.version < binding.directoryEpochAfterRevocation
        ) {
            throw SyncInvariantViolation("Pinned device directory does not cover the revocation receipt")
        }
        val target = directory.device(receipt.revokedDeviceId)
        if (target?.status != "revoked" || target.revokedAt == null) {
            throw SyncInvariantViolation("Revoked device remains active in the pinned directory")
        }
        val actor = directory.device(receipt.actorDeviceId)
        if (actor?.status != "active" || actor.revokedAt != null) {
            throw SyncInvariantViolation("Revocation actor is not active in the pinned directory")
        }
    }

    private suspend fun rotateAndVerifyCheckpointLocked(expected: SyncSession) {
        check(!closed) { "Sync runtime is closed" }
        val durable = platformInfrastructure.sessionStore.load()
            ?: throw IllegalStateException("Sync session disappeared before key rotation")
        requireSameSessionIdentity(expected, durable)
        require(durable.status == SyncSessionStatus.READY || durable.status == SyncSessionStatus.LINKING) {
            "Key rotation requires a ready or recovery-linking session"
        }
        val current = components ?: createComponentsLocked().also { components = it }
        val preflight = current.rotationCoordinator.preflightRequiredRotation(
            durable,
            current.trustContextProvider(durable),
        )
        requireSameSessionIdentity(durable, preflight.session)
        if (preflight.session.activeKeyEpoch > durable.activeKeyEpoch) {
            verifyPostRotationCheckpointLocked(preflight.session, current)
            return
        }
        if (!preflight.rotationRequired) {
            throw SyncInvariantViolation("Server cleared the rotation requirement without advancing the key epoch")
        }
        rotateFromVerifiedPreflightLocked(current, preflight)
    }

    private suspend fun rotateFromVerifiedPreflightLocked(
        current: RuntimeComponents,
        preflight: VerifiedRotationPreflight,
    ) {
        val completed = current.rotationCoordinator.rotate(preflight)
        current.engine.start()
        requireEngineCompleted(current)
        val updated = platformInfrastructure.sessionStore.load()
            ?: throw SyncInvariantViolation("Rotated session was not persisted")
        requireSameSessionIdentity(preflight.session, updated)
        verifyPostRotationCheckpointLocked(updated, current)
        require(completed.session.activeKeyEpoch == updated.activeKeyEpoch) {
            "Rotation completion did not persist the new key epoch"
        }
    }

    /**
     * Lets any surviving active device finish a server-required rotation. The preflight verifies
     * the current signed directory and installs any winning manifest before a lease is attempted.
     */
    private suspend fun ensureRequiredServerRotationLocked(
        current: RuntimeComponents,
        expected: SyncSession,
    ): SyncSession {
        var durable = expected
        repeat(MAX_AUTO_ROTATION_RACE_ATTEMPTS) {
            val preflight = current.rotationCoordinator.preflightRequiredRotation(
                durable,
                current.trustContextProvider(durable),
            )
            requireSameSessionIdentity(durable, preflight.session)
            if (!preflight.rotationRequired) return preflight.session
            try {
                rotateFromVerifiedPreflightLocked(current, preflight)
                return platformInfrastructure.sessionStore.load()
                    ?: throw SyncInvariantViolation("Auto-rotated session disappeared")
            } catch (failure: SyncApiException) {
                if (failure.errorCode !in AUTO_ROTATION_RACE_CODES) throw failure
                val refreshed = platformInfrastructure.sessionStore.load()
                    ?: throw SyncInvariantViolation("Rotation-race session disappeared")
                val winner = current.rotationCoordinator.preflightRequiredRotation(
                    refreshed,
                    current.trustContextProvider(refreshed),
                )
                requireSameSessionIdentity(refreshed, winner.session)
                when (requiredRotationRaceAction(
                    attemptedEpoch = preflight.session.activeKeyEpoch,
                    refreshedEpoch = winner.session.activeKeyEpoch,
                    rotationRequired = winner.rotationRequired,
                )) {
                    RequiredRotationRaceAction.WINNER_COMPLETED -> return winner.session
                    RequiredRotationRaceAction.RETRY_LATEST_REQUIRED -> durable = winner.session
                    RequiredRotationRaceAction.WAIT_FOR_WINNER -> throw SyncControlPlaneException.PendingOperation(
                        "Another active device is completing the required key rotation",
                    )
                }
            }
        }
        throw SyncControlPlaneException.PendingOperation(
            "Required key rotation changed repeatedly; retry after the winning lease commits",
        )
    }

    /** Idempotent response-loss path: never create a second epoch, only finish its checkpoint. */
    private suspend fun verifyPostRotationCheckpointLocked(
        expected: SyncSession,
        existingComponents: RuntimeComponents? = null,
    ) {
        check(!closed) { "Sync runtime is closed" }
        val updated = platformInfrastructure.sessionStore.load()
            ?: throw SyncInvariantViolation("Rotated session disappeared before checkpoint verification")
        requireSameSessionIdentity(expected, updated)
        require(updated.status == SyncSessionStatus.READY || updated.status == SyncSessionStatus.LINKING) {
            "Post-rotation checkpoint requires a ready or recovery-linking session"
        }
        val current = existingComponents ?: components ?: createComponentsLocked().also { components = it }
        current.engine.start()
        requireEngineCompleted(current)
        // Engine catch-up may install a verified remote epoch. Never request a capability or
        // checkpoint using the session object captured before that durable installation.
        val durableAfterEngine = platformInfrastructure.sessionStore.load()
            ?: throw SyncInvariantViolation("Rotated session disappeared after engine catch-up")
        requireSameSessionIdentity(updated, durableAfterEngine)
        if (durableAfterEngine.activeKeyEpoch < updated.activeKeyEpoch) {
            throw SyncInvariantViolation("Post-rotation engine rolled back the durable key epoch")
        }
        val updatedCapability = obtainValidatedCapability(current, durableAfterEngine)
        val outcome = current.checkpointCoordinator.coordinate(durableAfterEngine, updatedCapability)
        val validation = if (outcome == CheckpointCoordinatorOutcome.PROPOSED) {
            current.checkpointCoordinator.coordinate(durableAfterEngine, updatedCapability)
        } else {
            outcome
        }
        if (validation == CheckpointCoordinatorOutcome.REJECTED) {
            throw SyncInvariantViolation("Post-rotation checkpoint was rejected")
        }
        requireCaughtUpAndDrained(current, durableAfterEngine)
    }

    private fun requireEngineCompleted(current: RuntimeComponents) {
        val state = current.engine.state.value
        if (state.phase == SyncEnginePhase.KEY_ROTATION_REQUIRED) {
            scheduleRequiredRotationWakeup()
        }
        if (state.phase != SyncEnginePhase.READY || state.draftCount != 0 || state.outboxCount != 0) {
            throw SyncInvariantViolation(
                "Sync engine did not complete: ${state.diagnostic ?: state.phase.name}",
            )
        }
    }

    /** Re-enters lifecycle only after the engine's serialized callback has yielded. */
    private fun scheduleRequiredRotationWakeup() {
        if (closed || requiredRotationWakeJob?.isActive == true) return
        requiredRotationWakeJob = scope.launch {
            delay(AUTO_ROTATION_WAKE_INITIAL_DELAY_MILLIS)
            repeat(MAX_AUTO_ROTATION_WAKE_ATTEMPTS) { attempt ->
                onForegroundInternal(allowDuringStartup = true)
                val failure = mutableFailure.value
                if (failure == null && mutableEngineState.value.phase != SyncEnginePhase.KEY_ROTATION_REQUIRED) {
                    return@launch
                }
                if (failure !is SyncControlPlaneException.PendingOperation) return@launch
                if (attempt + 1 < MAX_AUTO_ROTATION_WAKE_ATTEMPTS) {
                    delay(AUTO_ROTATION_WAKE_RETRY_MILLIS)
                }
            }
        }
    }

    private fun requireSameSessionIdentity(expected: SyncSession, actual: SyncSession) {
        require(expected.endpoint == actual.endpoint && expected.instanceId == actual.instanceId &&
            expected.userId == actual.userId && expected.workspaceId == actual.workspaceId &&
            expected.deviceId == actual.deviceId && expected.provider == actual.provider
        ) { "Sync session identity changed during a protected operation" }
    }

    private fun needsStableBootstrapBase(local: LocalSyncStoreState): Boolean =
        local.genesisCheckpointSeed == null &&
            local.replica.throughWorkspaceSeq == 0L &&
            local.replica.appliedOpIds.isEmpty() &&
            local.replica.entities.isEmpty() &&
            local.replica.categoryMemberships.isEmpty() &&
            local.replica.readingProgress.isEmpty() &&
            local.replica.portableSettings.isEmpty() &&
            local.replica.keyRemaps.isEmpty() &&
            local.drafts.isEmpty() && local.sealedOutbox.isEmpty()

    /**
     * Performs the cheap, local half of startup. A ready session gets a fully wired repository
     * bridge and reader reporter; no network call is made here.
     */
    private suspend fun prepareProviderLocked(): Boolean {
        check(!closed) { "Sync runtime is closed" }
        val session = platformInfrastructure.sessionStore.load()
        if (!isReadyCloudflareSession(session)) {
            configureInactiveMutationBoundaryLocked(session)
            teardownComponentsLocked()
            mutableFailure.value = null
            return false
        }
        val current = components ?: createComponentsLocked().also { components = it }
        repository.configureSyncMutationBoundary(
            observer = current.repositoryBridge,
            guard = replacementGuard,
        )
        return true
    }

    private suspend fun reconcileProviderLocked(
        startEngine: Boolean,
        foreground: Boolean,
        preserveMutationBoundary: Boolean = false,
    ) {
        check(!closed) { "Sync runtime is closed" }
        val session = platformInfrastructure.sessionStore.load()
        if (!isReadyCloudflareSession(session)) {
            configureInactiveMutationBoundaryLocked(session)
            teardownComponentsLocked()
            mutableFailure.value = null
            return
        }
        val readySession = requireNotNull(session)

        val current = components ?: createComponentsLocked().also { components = it }
        if (!preserveMutationBoundary) {
            repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
        }
        ensureRequiredServerRotationLocked(current, readySession)
        repository.configureSyncMutationBoundary(
            observer = current.repositoryBridge,
            guard = replacementGuard,
        )
        when {
            startEngine -> current.engine.start()
            foreground -> current.engine.onForeground()
        }
        requireEngineCompleted(current)

        // A start/catch-up can persist revocation. Never leave the writer bridge installed after it.
        val updatedSession = platformInfrastructure.sessionStore.load()
        if (!isReadyCloudflareSession(updatedSession)) {
            configureInactiveMutationBoundaryLocked(updatedSession)
            teardownComponentsLocked()
        }
        mutableFailure.value = null
    }

    private suspend fun configureInactiveMutationBoundaryLocked(session: SyncSession?) {
        repository.configureSyncMutationBoundary(
            observer = if (session == null) null else FailClosedSyncMutationObserver,
            guard = replacementGuard,
        )
    }

    private suspend fun createComponentsLocked(): RuntimeComponents {
        val localStore = ensureLocalStoreLocked()
        val codec = DeterministicSyncEventCodec()
        val keyRetentionCoordinator = SyncKeyRetentionCoordinator(
            localStore = localStore,
            secretStore = platformInfrastructure.secretStore,
            nowMillis = nowMillis,
        )
        platformInfrastructure.sessionStore.load()?.let { durableSession ->
            keyRetentionCoordinator.resumePendingPruning(durableSession.workspaceId)
        }
        val resolver = devicePublicKeyResolver ?: PinnedDevicePublicKeyResolver(
            platformInfrastructure.deviceDirectoryPinStore,
        ) { platformInfrastructure.sessionStore.load()?.workspaceId }
        val crypto = SodiumSyncCrypto(
            secretStore = platformInfrastructure.secretStore,
            codec = codec,
            devicePublicKeyResolver = resolver,
        )
        val syncHttpClient = createSyncHttpClient(platformHttpClient)
        try {
            val networkApi = KtorCloudflareSyncApi(
                client = syncHttpClient,
                secretStore = platformInfrastructure.secretStore,
                crypto = crypto,
                codec = codec,
            )
            val directoryVerifier = DeviceDirectoryVerifier(platformInfrastructure.deviceDirectoryPinStore)
            val storedTrustContext = StoredDeviceTrustContextProvider(platformInfrastructure.secretStore)
            val effectiveTrustContext: suspend (SyncSession) -> DeviceDirectoryTrustContext = { session ->
                provisioningTrustContext
                    ?.takeIf { it.instanceId == session.instanceId && it.workspaceId == session.workspaceId }
                    ?: storedTrustContext(session)
            }
            val api = TrustVerifyingCloudflareSyncApi(
                delegate = networkApi,
                directoryVerifier = directoryVerifier,
                trustContext = effectiveTrustContext,
            )
            val realtime = KtorRealtimeWorkspaceClient(syncHttpClient, scope, networkApi)
            val rotationCoordinator = SyncKeyRotationCoordinator(
                api = KtorSyncControlPlaneApi(syncHttpClient, platformInfrastructure.secretStore),
                crypto = crypto,
                secretStore = platformInfrastructure.secretStore,
                sessionStore = platformInfrastructure.sessionStore,
                capabilityProvider = api::obtainWorkspaceCapability,
                envelopeInstaller = SyncWorkspaceKeyEnvelopeInstaller(
                    secretStore = platformInfrastructure.secretStore,
                    crypto = crypto,
                    directoryVerifier = directoryVerifier,
                ),
                trustContextProvider = effectiveTrustContext,
                epochActivationSink = { metadata ->
                    localStore.transaction { retainKeyEpoch(metadata) }
                },
            )
            val projectionSink = SyncProjectionSink { requested ->
                flushProjection(localStore, requested)
            }
            val idGenerator = SyncPortableIdGenerator(::randomSyncUuid)
            val repositoryBridge = RepositorySyncBridge(
                localStore = localStore,
                sessionStore = platformInfrastructure.sessionStore,
                idGenerator = idGenerator,
                nowMillis = nowMillis,
                eventCodec = codec,
            )
            lateinit var engine: SyncEngine
            val checkpointCoordinator = SyncCheckpointCoordinator(
                api = api,
                crypto = crypto,
                codec = codec,
                localStore = localStore,
                keyRetentionCoordinator = keyRetentionCoordinator,
            )
            val readerProgressReporter = ReaderProgressReporter(
                localStore = localStore,
                sessionStore = platformInfrastructure.sessionStore,
                crypto = crypto,
                projectionSink = projectionSink,
                // Reader callbacks use the priority path so they never wait for the full
                // catch-up/checkpoint cycle or contend on the runtime lifecycle mutex.
                remoteOutboxFlusher = RemoteOutboxFlusher { engine.syncReaderProgress() },
                operationIdGenerator = SyncOperationIdGenerator(::randomSyncUuid),
                nowMillis = nowMillis,
                isIncognito = { repository.currentSnapshot.settings.security.incognitoMode },
                beforeBackgroundSeal = repository::flushPersistence,
                scope = scope,
            )
            engine = SyncEngine(
                scope = scope,
                sessionStore = platformInfrastructure.sessionStore,
                localStore = localStore,
                api = api,
                realtimeClient = realtime,
                crypto = crypto,
                projectionSink = projectionSink,
                nowMillis = nowMillis,
                backgroundFlusher = readerProgressReporter,
                keyEpochResolver = rotationCoordinator.asKeyEpochResolver(effectiveTrustContext),
                eventCodec = codec,
                checkpointCoordinator = checkpointCoordinator,
            )
            engineStateForwarder?.cancel()
            engineStateForwarder = scope.launch {
                engine.state.collectLatest {
                    mutableEngineState.value = it
                    if (it.phase == SyncEnginePhase.KEY_ROTATION_REQUIRED) {
                        scheduleRequiredRotationWakeup()
                    }
                }
            }
            mutableReaderProgressReporter.value = readerProgressReporter
            return RuntimeComponents(
                syncHttpClient = syncHttpClient,
                engine = engine,
                repositoryBridge = repositoryBridge,
                readerProgressReporter = readerProgressReporter,
                localStore = localStore,
                api = api,
                crypto = crypto,
                codec = codec,
                checkpointCoordinator = checkpointCoordinator,
                rotationCoordinator = rotationCoordinator,
                trustContextProvider = effectiveTrustContext,
            )
        } catch (failure: Throwable) {
            syncHttpClient.close()
            throw failure
        }
    }

    /** Runtime and provisioning share this exact wrapper; a second writer is never opened. */
    private suspend fun ensureLocalStoreLocked(): PersistentLocalSyncStore {
        val store = sharedLocalStore ?: withContext(Dispatchers.Default) {
            PersistentLocalSyncStore.open(platformInfrastructure.statePersistence())
        }.also { sharedLocalStore = it }
        publishMaterializationDiagnostics(store.readState())
        return store
    }

    /** Materialization retries against repository revision changes and never re-enters the bridge. */
    private suspend fun flushProjection(
        localStore: LocalSyncStore,
        requestedState: LocalSyncStoreState,
    ) {
        var durable = requestedState
        repeat(MAX_PROJECTION_ATTEMPTS) {
            durable = localStore.readState()
            if (!durable.materializationPending) {
                publishMaterializationDiagnostics(durable)
                return
            }

            val current = repository.currentSnapshot
            val materialized = SnapshotMaterializer.materialize(
                replica = durable.replica,
                currentDeviceSnapshot = current,
                initialIdentityMap = durable.identityMap,
                acceptedRepositoryTrustChanges = durable.repositoryTrustApprovals,
            )
            val installed = repository.replaceSnapshotFromSyncIfRevision(
                expectedRevision = current.revision,
                imported = materialized.snapshot,
            )
            if (installed == null) {
                durable = localStore.readState()
                return@repeat
            }
            // Projection durability is part of the reader/background ordering contract. The
            // caller may seal a draft immediately after this function returns.
            repository.flushPersistence()
            val completed = localStore.transaction {
                completeMaterialization(
                    expectedReplica = durable.replica,
                    expectedIdentityMap = durable.identityMap,
                    expectedRepositoryTrustApprovals = durable.repositoryTrustApprovals,
                    materializedIdentityMap = materialized.identityMap,
                    issues = materialized.issues,
                    repositoryTrustConfirmations = materialized.repositoryTrustConfirmations,
                )
            }
            val latest = localStore.readState()
            publishMaterializationDiagnostics(latest)
            if (completed || !latest.materializationPending) return
        }
        throw SnapshotMaterializationException("Local data kept changing while applying the sync projection")
    }

    private suspend fun updateMaterializationReview(block: SyncStoreTransaction.() -> Unit) =
        withContext(Dispatchers.Default) {
            lifecycleMutex.withLock {
                check(!closed) { "Sync runtime is closed" }
                val session = platformInfrastructure.sessionStore.load()
                if (!isReadyCloudflareSession(session)) {
                    throw SyncMutationBoundaryUnavailableException()
                }
                val store = ensureLocalStoreLocked()
                try {
                    store.transaction(block)
                    publishMaterializationDiagnostics(store.readState())
                    repository.setSnapshotReplacementGuard(replacementGuard)
                    flushProjection(store, store.readState())
                    mutableFailure.value = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    mutableFailure.value = failure
                    publishMaterializationDiagnostics(store.readState())
                    throw failure
                }
            }
        }

    private fun publishMaterializationDiagnostics(state: LocalSyncStoreState) {
        mutableMaterializationDiagnostics.value = state.materializationDiagnostics()
    }

    private suspend fun runLifecycleStepLocked(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
            teardownComponentsLocked()
            throw cancelled
        } catch (failure: Throwable) {
            mutableFailure.value = failure
            repository.configureSyncMutationBoundary(FailClosedSyncMutationObserver, replacementGuard)
            teardownComponentsLocked()
        }
    }

    private suspend fun teardownComponentsLocked() {
        val current = components
        components = null
        engineStateForwarder?.cancel()
        engineStateForwarder = null
        mutableReaderProgressReporter.value = null
        if (current != null) {
            try {
                current.readerProgressReporter.close()
                current.engine.close()
            } finally {
                current.syncHttpClient.close()
            }
        }
    }

    private suspend fun loadSessionFailClosed(): SyncSession? = try {
        platformInfrastructure.sessionStore.load()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // A corrupt/unavailable store reserves Cloudflare ownership until explicitly repaired.
        CORRUPT_SESSION_SENTINEL
    }

    private fun isReadyCloudflareSession(session: SyncSession?): Boolean =
        session?.status == SyncSessionStatus.READY && session.provider == SyncProvider.CLOUDFLARE_V2

    private data class RuntimeComponents(
        val syncHttpClient: HttpClient,
        val engine: SyncEngine,
        val repositoryBridge: RepositorySyncBridge,
        val readerProgressReporter: ReaderProgressReporter,
        val localStore: PersistentLocalSyncStore,
        val api: CloudflareSyncApi,
        val crypto: SyncCrypto,
        val codec: SyncEventCodec,
        val checkpointCoordinator: SyncCheckpointCoordinator,
        val rotationCoordinator: SyncKeyRotationCoordinator,
        val trustContextProvider: suspend (SyncSession) -> DeviceDirectoryTrustContext,
    )

    private data class BodyDrainAccess(
        val session: SyncSession,
        val components: RuntimeComponents,
    )

    /**
     * Preserves the repository's fail-closed mutation contract while the local journal is being
     * opened off the first-frame path. It deliberately delegates only after the real bridge has
     * been installed, so no snapshot mutation can be lost during startup.
     */
    private class DeferredSyncMutationObserver(
        private val delegate: Deferred<SnapshotMutationObserver>,
    ) : SnapshotMutationObserver {
        override suspend fun beforeCommit(previous: AppSnapshot, next: AppSnapshot) {
            delegate.await().beforeCommit(previous, next)
        }

        override suspend fun beforeAtomicHostCommit(previous: AppSnapshot, next: AppSnapshot) {
            delegate.await().beforeAtomicHostCommit(previous, next)
        }

        override suspend fun afterAtomicHostRollback(previous: AppSnapshot, next: AppSnapshot) {
            delegate.await().afterAtomicHostRollback(previous, next)
        }
    }

    private companion object {
        const val MAX_PROJECTION_ATTEMPTS = 3
        const val LINKING_CATCH_UP_PAGE_SIZE = 200
        const val MAX_AUTO_ROTATION_RACE_ATTEMPTS = 2
        const val MAX_AUTO_ROTATION_WAKE_ATTEMPTS = 10
        const val AUTO_ROTATION_WAKE_INITIAL_DELAY_MILLIS = 1L
        const val AUTO_ROTATION_WAKE_RETRY_MILLIS = 500L
        val AUTO_ROTATION_RACE_CODES = setOf("rotation_lease_unavailable", "stale_key_epoch")

        // Used only as a non-null, non-ready fail-closed marker; it never reaches an engine/API.
        val CORRUPT_SESSION_SENTINEL = SyncSession(
            endpoint = "https://invalid.local",
            instanceId = "unavailable",
            userId = "unavailable",
            workspaceId = "unavailable",
            deviceId = "unavailable",
            deviceDisplayName = "Unavailable",
            platform = "unavailable",
            status = SyncSessionStatus.ERROR,
            deviceAuthEpoch = 0,
            membershipAuthEpoch = 0,
            activeKeyEpoch = 1,
        )
    }
}

internal enum class RecoveryRotationAction {
    ROTATE_ONCE,
    VERIFY_EXISTING_ROTATION,
}

/**
 * Selects the only valid recovery continuation from durable epochs. A process may lose the
 * rotation response after the new epoch is persisted; that state must resume checkpoint
 * verification instead of creating another epoch.
 */
internal fun recoveryRotationAction(
    activeKeyEpoch: Int,
    claimedKeyEpoch: Int,
): RecoveryRotationAction = when (activeKeyEpoch) {
    claimedKeyEpoch -> RecoveryRotationAction.ROTATE_ONCE
    claimedKeyEpoch + 1 -> RecoveryRotationAction.VERIFY_EXISTING_ROTATION
    else -> throw SyncInvariantViolation("Recovered workspace advanced beyond its bound rotation epoch")
}

internal enum class RevocationReconciliationAction {
    ROTATE_LATEST_ONCE,
    VERIFY_COVERING_ROTATION,
}

internal enum class RequiredRotationRaceAction {
    WINNER_COMPLETED,
    RETRY_LATEST_REQUIRED,
    WAIT_FOR_WINNER,
}

/** Bounded lease-race decision: a cleared gate can never trigger another epoch. */
internal fun requiredRotationRaceAction(
    attemptedEpoch: Int,
    refreshedEpoch: Int,
    rotationRequired: Boolean,
): RequiredRotationRaceAction {
    if (attemptedEpoch <= 0 || refreshedEpoch < attemptedEpoch) {
        throw SyncInvariantViolation("Required-rotation race rolled back the server key epoch")
    }
    if (!rotationRequired) {
        if (refreshedEpoch == attemptedEpoch) {
            throw SyncInvariantViolation("Rotation gate cleared without a committed epoch advance")
        }
        return RequiredRotationRaceAction.WINNER_COMPLETED
    }
    return if (refreshedEpoch > attemptedEpoch) {
        RequiredRotationRaceAction.RETRY_LATEST_REQUIRED
    } else {
        RequiredRotationRaceAction.WAIT_FOR_WINNER
    }
}

/** Decides only from an exact receipt binding; the caller still verifies server epoch/evidence. */
internal fun revocationReconciliationAction(
    localActiveKeyEpoch: Int,
    binding: ProvisioningRevocationWorkspaceBinding,
): RevocationReconciliationAction {
    validateRevocationCoverageShape(binding)
    if (localActiveKeyEpoch < binding.revokedAtKeyEpoch) {
        throw SyncInvariantViolation("Local key epoch predates the device revocation")
    }
    if (localActiveKeyEpoch > binding.currentActiveKeyEpoch) {
        throw SyncInvariantViolation("Device revocation receipt rolled back the server key epoch")
    }
    return if (binding.currentRotationRequired) {
        RevocationReconciliationAction.ROTATE_LATEST_ONCE
    } else {
        RevocationReconciliationAction.VERIFY_COVERING_ROTATION
    }
}

internal fun validateRevocationRotationEvidence(
    localEpochBeforeRefresh: Int,
    binding: ProvisioningRevocationWorkspaceBinding,
    installed: InstalledRemoteRotations,
) {
    if (installed.session.workspaceId != binding.workspaceId ||
        installed.session.activeKeyEpoch != binding.currentActiveKeyEpoch
    ) {
        throw SyncInvariantViolation("Remote rotation installation crossed the revocation workspace or epoch")
    }
    val expectedCount = binding.currentActiveKeyEpoch - localEpochBeforeRefresh
    if (expectedCount < 0 || installed.manifests.size != expectedCount) {
        throw SyncInvariantViolation("Remote rotation installation skipped or rolled back a key epoch")
    }
    var expectedFrom = localEpochBeforeRefresh
    installed.manifests.forEach { manifest ->
        if (manifest.workspaceId != binding.workspaceId || manifest.fromEpoch != expectedFrom ||
            manifest.toEpoch != expectedFrom + 1
        ) {
            throw SyncInvariantViolation("Remote rotation manifest chain is discontinuous")
        }
        expectedFrom = manifest.toEpoch
    }
    if (expectedFrom != binding.currentActiveKeyEpoch) {
        throw SyncInvariantViolation("Remote rotation manifests do not reach the receipt epoch")
    }

    // If this device had not previously installed the first covering epoch, its exact committed,
    // pinned-proposer manifest must be present in this refresh. A later durable local epoch can
    // only have been persisted by the same verified installer/rotation coordinator.
    if (binding.currentActiveKeyEpoch > binding.revokedAtKeyEpoch &&
        localEpochBeforeRefresh == binding.revokedAtKeyEpoch
    ) {
        val cover = installed.manifests.firstOrNull()
        if (cover?.fromEpoch != binding.revokedAtKeyEpoch ||
            cover.toEpoch != binding.revokedAtKeyEpoch + 1 ||
            cover.rotationId != binding.coveringRotationId ||
            cover.proposerDeviceId != binding.coveringProposerDeviceId
        ) {
            throw SyncInvariantViolation("Signed rotation manifest does not cover the device revocation receipt")
        }
    }
}

private fun validateRevocationCoverageShape(binding: ProvisioningRevocationWorkspaceBinding) {
    val covered = binding.currentActiveKeyEpoch > binding.revokedAtKeyEpoch
    if (covered) {
        if (binding.coveringRotationId?.matches(REVOCATION_RUNTIME_UUID) != true ||
            binding.coveringProposerDeviceId?.matches(REVOCATION_RUNTIME_UUID) != true
        ) {
            throw SyncInvariantViolation("Covered device revocation is missing exact rotation evidence")
        }
    } else if (!binding.currentRotationRequired || binding.coveringRotationId != null ||
        binding.coveringProposerDeviceId != null
    ) {
        throw SyncInvariantViolation("Uncovered device revocation has inconsistent rotation evidence")
    }
}

private val REVOCATION_RUNTIME_UUID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

@OptIn(ExperimentalUuidApi::class)
private fun randomSyncUuid(): String = Uuid.random().toString()
