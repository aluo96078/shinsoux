package dev.shinsou.kmp.sync.v2

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

enum class SyncEnginePhase {
    STOPPED,
    STARTING,
    CATCHING_UP,
    SEALING,
    UPLOADING,
    READY,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    KEY_ROTATION_REQUIRED,
    REVOKED,
    INCOMPATIBLE,
    ERROR,
    CLOSED,
}

data class SyncEngineState(
    val phase: SyncEnginePhase = SyncEnginePhase.STOPPED,
    val sessionStatus: SyncSessionStatus = SyncSessionStatus.NOT_CONFIGURED,
    val cursor: Long = 0,
    val remoteHead: Long = 0,
    val draftCount: Int = 0,
    val outboxCount: Int = 0,
    val realtimeConnected: Boolean = false,
    val lastSuccessfulSyncAtMillis: Long? = null,
    val retryAfterMillis: Long? = null,
    val diagnostic: String? = null,
)

fun interface SyncProjectionSink {
    /** Must validate/materialize the current replica before returning; throw to keep pending=true. */
    suspend fun flush(state: LocalSyncStoreState)
}

fun interface SyncBackgroundFlusher {
    /** Await reader mutation -> durable local commit -> projection flush -> draft seal. */
    suspend fun flushForBackground()
}

fun interface SyncLinkHandler {
    suspend fun handle(link: String): SyncSession?
}

/** Downloads/unwraps a server-announced epoch and returns its strict secret-store metadata. */
fun interface SyncKeyEpochResolver {
    suspend fun resolve(session: SyncSession, epoch: Int): KeyEpochMetadata
}

/**
 * Provider-neutral lifecycle facade for Cloudflare v2. All mutating work is serialized; a failed
 * network call never removes a draft or sealed outbox row.
 */
class SyncEngine(
    private val scope: CoroutineScope,
    private val sessionStore: SyncSessionStore,
    private val localStore: LocalSyncStore,
    private val api: CloudflareSyncApi,
    private val realtimeClient: RealtimeWorkspaceClient?,
    private val crypto: SyncCrypto,
    private val projectionSink: SyncProjectionSink,
    private val nowMillis: () -> Long,
    private val backgroundFlusher: SyncBackgroundFlusher? = null,
    private val linkHandler: SyncLinkHandler? = null,
    private val keyEpochResolver: SyncKeyEpochResolver? = null,
    private val catchUpPageSize: Int = 200,
    private val backgroundTimeoutMillis: Long = 4_000,
    private val eventCodec: SyncEventCodec? = null,
    private val checkpointCoordinator: SyncCheckpointCoordinator? = null,
    private val checkpointRetryDelayMillis: Long = 60_000,
    private val litePollingInitialDelayMillis: Long = 2_000,
    private val litePollingMaxDelayMillis: Long = 30_000,
) {
    private val operationMutex = Mutex()
    /** Serializes duplicate platform lifecycle callbacks without closing realtime under operationMutex. */
    private val backgroundMutex = Mutex()
    private val mutableState = MutableStateFlow(SyncEngineState())
    private var realtimeJob: Job? = null
    private var deliveryRefreshJob: Job? = null
    private var litePollingJob: Job? = null
    /** Number of realtime interruptions in the current foreground session. */
    private var realtimeReconnectFailures = 0
    private var checkpointRetryJob: Job? = null
    private var foregroundCheckpointRetriesEnabled = false
    @Volatile
    private var foregroundDeliveryEnabled = false
    private var backgroundFlushCompleted = false
    private var closed = false

    val state: StateFlow<SyncEngineState> = mutableState.asStateFlow()

    init {
        require(catchUpPageSize > 0) { "Catch-up page size must be positive" }
        require(backgroundTimeoutMillis > 0) { "Background timeout must be positive" }
        require(checkpointRetryDelayMillis > 0) { "Checkpoint retry delay must be positive" }
        require(litePollingInitialDelayMillis > 0) { "Lite polling delay must be positive" }
        require(litePollingMaxDelayMillis >= litePollingInitialDelayMillis) {
            "Lite polling maximum must not be shorter than its initial delay"
        }
    }

    suspend fun start() = serialized {
        ensureOpen()
        backgroundFlushCompleted = false
        foregroundCheckpointRetriesEnabled = true
        foregroundDeliveryEnabled = true
        realtimeReconnectFailures = 0
        mutableState.value = mutableState.value.copy(phase = SyncEnginePhase.STARTING, diagnostic = null)
        val session = sessionStore.load()
        if (session == null) {
            publishState(SyncEnginePhase.STOPPED, SyncSessionStatus.NOT_CONFIGURED)
            return@serialized
        }
        if (session.status == SyncSessionStatus.REVOKED) {
            publishState(SyncEnginePhase.REVOKED, SyncSessionStatus.REVOKED)
            return@serialized
        }
        try {
            val capabilities = api.capabilities(session.endpoint)
            capabilities.requireCompatible(
                SYNC_PROTOCOL_VERSION,
                SYNC_PROTOCOL_VERSION,
                SYNC_STATE_SCHEMA_VERSION,
                SYNC_STATE_SCHEMA_VERSION,
            )
            syncNowLocked(session)
            val deliverySession = sessionStore.load() ?: session
            if (deliverySession.status == SyncSessionStatus.READY) {
                updateForegroundDelivery(deliverySession, capabilities)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            publishFailure(failure)
        }
    }

    suspend fun onForeground() = serialized {
        ensureOpen()
        backgroundFlushCompleted = false
        foregroundCheckpointRetriesEnabled = true
        // A new foreground session is the explicit retry boundary after the realtime circuit
        // breaker has opened. Repeated SwiftUI/iOS callbacks while already foreground do not
        // reset the counter and therefore cannot recreate a socket storm.
        if (!foregroundDeliveryEnabled) realtimeReconnectFailures = 0
        foregroundDeliveryEnabled = true
        val session = readySessionOrReturn() ?: return@serialized
        try {
            syncNowLocked(session)
            val capabilities = api.capabilities(session.endpoint)
            capabilities.requireCompatible(
                SYNC_PROTOCOL_VERSION,
                SYNC_PROTOCOL_VERSION,
                SYNC_STATE_SCHEMA_VERSION,
                SYNC_STATE_SCHEMA_VERSION,
            )
            updateForegroundDelivery(sessionStore.load() ?: session, capabilities)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            publishFailure(failure)
        }
    }

    suspend fun onBackground() {
        backgroundMutex.lock()
        try {
            if (backgroundFlushCompleted) return
            foregroundCheckpointRetriesEnabled = false
            foregroundDeliveryEnabled = false
            realtimeReconnectFailures = 0
            cancelCheckpointRetry()
            cancelLitePolling()
            deliveryRefreshJob?.cancel()
            deliveryRefreshJob = null
            realtimeJob?.cancel()
            realtimeJob = null
            realtimeClient?.close()
            mutableState.value = mutableState.value.copy(realtimeConnected = false)
            backgroundFlushCompleted = withTimeoutOrNull(backgroundTimeoutMillis) {
                backgroundFlusher?.flushForBackground()
                syncNow()
                true
            } == true
        } finally {
            backgroundMutex.unlock()
        }
    }

    suspend fun syncNow() = serialized {
        ensureOpen()
        val session = readySessionOrReturn() ?: return@serialized
        try {
            syncNowLocked(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            publishFailure(failure)
        }
    }

    /**
     * Uploads only reader-progress events that are currently at the device-sequence head. This is
     * intentionally a small path: it does not run catch-up/checkpoint work and never seals or
     * uploads unrelated repository mutations. The ordinary sync loop remains responsible for
     * repairing sequence gaps and draining the rest of the journal in the background.
     */
    suspend fun syncReaderProgress() = serialized {
        ensureOpen()
        val session = readySessionOrReturn() ?: return@serialized
        try {
            val access = obtainValidatedAccess(session)
            reconcileAttemptedOutbox(access.session, access.capability, priorityOnly = true)
            sealPendingDrafts(access.session) { draft -> draft.isReaderProgressDraft() }
            val uploaded = flushOutbox(
                initialSession = access.session,
                initialCapability = access.capability,
                priorityOnly = true,
            )
            if (uploaded) publishReady()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Reader progress is already durable locally. Leave the ordinary background loop to
            // retry transport/sequence failures instead of surfacing them from a page callback.
            publishFailure(failure)
        }
    }

    suspend fun handleLink(link: String) = serialized {
        ensureOpen()
        require(link.isNotBlank()) { "Sync link cannot be blank" }
        val handler = linkHandler ?: throw IllegalStateException("No setup/pair link handler is installed")
        mutableState.value = mutableState.value.copy(
            phase = SyncEnginePhase.STARTING,
            sessionStatus = SyncSessionStatus.LINKING,
            diagnostic = null,
        )
        val session = handler.handle(link)
        if (session != null) {
            sessionStore.save(session)
            syncNowLocked(session)
        }
    }

    suspend fun close() {
        operationMutex.lock()
        try {
            if (closed) return
            closed = true
            foregroundCheckpointRetriesEnabled = false
            foregroundDeliveryEnabled = false
            realtimeReconnectFailures = 0
            cancelCheckpointRetry()
            cancelLitePolling()
            deliveryRefreshJob?.cancel()
            deliveryRefreshJob = null
            realtimeJob?.cancel()
            realtimeJob = null
            realtimeClient?.close()
            publishState(SyncEnginePhase.CLOSED, mutableState.value.sessionStatus)
        } finally {
            operationMutex.unlock()
        }
    }

    private suspend fun syncNowLocked(initialSession: SyncSession) {
        var access = obtainValidatedAccess(initialSession)
        reconcileAttemptedOutbox(access.session, access.capability)
        access = catchUp(access)
        val postCatchUpSession = sessionStore.load() ?: access.session
        val postCatchUpAccess = if (access.capability.binding.keyEpoch == postCatchUpSession.activeKeyEpoch) {
            SyncAccess(postCatchUpSession, access.capability)
        } else {
            obtainValidatedAccess(postCatchUpSession)
        }
        var session = postCatchUpAccess.session
        var capability = postCatchUpAccess.capability
        sealPendingDrafts(session)
        val uploadsCompleted = flushOutbox(session, capability)
        var finalAccess = SyncAccess(session, capability)
        if (uploadsCompleted) {
            val refreshedSession = sessionStore.load() ?: session
            val refreshedAccess = if (refreshedSession.activeKeyEpoch == capability.binding.keyEpoch) {
                SyncAccess(refreshedSession, capability)
            } else {
                obtainValidatedAccess(refreshedSession)
            }
            finalAccess = catchUp(refreshedAccess)
        }
        val local = localStore.readState()
        if (local.materializationPending) flushProjection(local)
        if (!uploadsCompleted) {
            val current = localStore.readState()
            mutableState.value = mutableState.value.copy(
                cursor = current.replica.throughWorkspaceSeq,
                draftCount = current.drafts.size,
                outboxCount = current.sealedOutbox.size,
            )
            return
        }
        val checkpointOutcome = try {
            checkpointCoordinator?.coordinate(finalAccess.session, finalAccess.capability)
        } catch (_: KeyRotationRequiredException) {
            mutableState.value = mutableState.value.copy(
                phase = SyncEnginePhase.KEY_ROTATION_REQUIRED,
                diagnostic = null,
            )
            return
        }
        when (checkpointOutcome) {
            CheckpointCoordinatorOutcome.PROPOSED,
            CheckpointCoordinatorOutcome.DEFERRED,
            -> scheduleCheckpointRetry()

            CheckpointCoordinatorOutcome.NO_ACTION,
            CheckpointCoordinatorOutcome.VALIDATED,
            CheckpointCoordinatorOutcome.REJECTED,
            null,
            -> cancelCheckpointRetry()
        }
        publishReady()
    }

    /**
     * An append whose request crossed the network boundary has an ambiguous outcome after a crash
     * or transport failure. Resolve those immutable rows before checkpoint recovery can rebase
     * them. All receipts are fetched first and committed together, so a lookup failure or a
     * conflicting receipt leaves the durable journal untouched.
     */
    private suspend fun reconcileAttemptedOutbox(
        session: SyncSession,
        capability: WorkspaceCapability,
        priorityOnly: Boolean = false,
    ) {
        val attempted = localStore.readState().sealedOutbox.values
            .asSequence()
            .filter { it.attemptCount > 0 }
            .filterNot { priorityOnly && !it.isReaderProgressEvent() }
            .sortedBy { it.deviceSeq }
            .toList()
        if (attempted.isEmpty()) return

        val receipts = attempted.mapNotNull { sealed ->
            api.eventReceipt(session, capability, sealed.deviceSeq)
        }
        if (receipts.isEmpty()) return

        localStore.transaction {
            receipts.forEach { receipt -> recordReceipt(receipt) }
        }
        mutableState.value = mutableState.value.copy(
            remoteHead = maxOf(
                mutableState.value.remoteHead,
                receipts.maxOf { it.workspaceSeq },
            ),
        )
    }

    /**
     * Candidate promotion can require another device or the single-device self-verification
     * grace period. Keep retrying while the app is foregrounded so a checkpoint does not remain
     * a candidate until the next user mutation/lifecycle edge.
     */
    private fun scheduleCheckpointRetry() {
        if (!foregroundCheckpointRetriesEnabled || closed || checkpointRetryJob?.isActive == true) return
        checkpointRetryJob = scope.launch {
            delay(checkpointRetryDelayMillis)
            serialized {
                checkpointRetryJob = null
                if (closed || !foregroundCheckpointRetriesEnabled) return@serialized
                val session = readySessionOrReturn() ?: return@serialized
                try {
                    syncNowLocked(session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    publishFailure(failure)
                }
            }
        }
    }

    private fun cancelCheckpointRetry() {
        checkpointRetryJob?.cancel()
        checkpointRetryJob = null
    }

    private suspend fun catchUp(initialAccess: SyncAccess): SyncAccess {
        var access = initialAccess
        var checkpointRecoveryCount = 0
        while (true) {
            try {
                catchUpFromCurrentCursor(access.session, access.capability)
                return access
            } catch (required: CheckpointRequiredException) {
                checkpointRecoveryCount += 1
                if (checkpointRecoveryCount > MAX_CHECKPOINT_RECOVERY_ATTEMPTS) {
                    throw SyncInvariantViolation("Checkpoint recovery repeatedly returned a compacted cursor")
                }
                access = recoverFromRetainedCheckpoint(access, required)
            }
        }
    }

    private suspend fun catchUpFromCurrentCursor(
        session: SyncSession,
        capability: WorkspaceCapability,
    ) {
        mutableState.value = mutableState.value.copy(phase = SyncEnginePhase.CATCHING_UP, diagnostic = null)
        var cursor = localStore.readState().replica.throughWorkspaceSeq
        var fixedUntil: Long? = null
        var observedHead = cursor
        var hasMore: Boolean
        do {
            val page = api.catchUp(
                session = session,
                capability = capability,
                afterExclusive = cursor,
                untilInclusive = fixedUntil,
                limit = catchUpPageSize,
            )
            if (page.fromExclusive != cursor) {
                throw SyncInvariantViolation("Catch-up response starts at the wrong cursor")
            }
            if (fixedUntil == null) fixedUntil = page.untilInclusive
            if (page.untilInclusive != fixedUntil) {
                throw SyncInvariantViolation("Catch-up server changed the fixed watermark")
            }
            if (page.nextCursor < cursor || page.nextCursor > requireNotNull(fixedUntil)) {
                throw SyncInvariantViolation("Catch-up response contains an invalid cursor")
            }
            if (page.nextCursor == cursor && cursor < requireNotNull(fixedUntil)) {
                throw SyncInvariantViolation("Catch-up server returned an empty non-terminal page")
            }
            validatePageEventSequences(page, cursor)
            observedHead = maxOf(observedHead, page.headSeq)
            val opened = page.events.map { remote ->
                validateRemoteEnvelope(session, remote)
                val event = crypto.openAndVerifyEvent(session, remote).event
                CommittedSyncEvent(remote.workspaceSeq, event)
            }
            localStore.transaction { applyRemotePage(opened, page.nextCursor) }
            cursor = page.nextCursor
            flushProjection(localStore.readState())
            hasMore = page.hasMore
            if (hasMore != (cursor < requireNotNull(fixedUntil))) {
                throw SyncInvariantViolation("Catch-up pagination flag disagrees with its fixed watermark")
            }
        } while (hasMore)

        mutableState.value = mutableState.value.copy(cursor = cursor, remoteHead = observedHead)
    }

    /**
     * Recovers a cursor that has fallen behind D1 retention. Every candidate is decrypted and its
     * complete fixed-watermark tail is replayed in memory before LocalSyncStore is changed.
     */
    private suspend fun recoverFromRetainedCheckpoint(
        initialAccess: SyncAccess,
        required: CheckpointRequiredException,
    ): SyncAccess {
        val codec = eventCodec
            ?: throw IllegalStateException("Checkpoint recovery requires a deterministic sync event codec")
        mutableState.value = mutableState.value.copy(
            phase = SyncEnginePhase.CATCHING_UP,
            remoteHead = maxOf(mutableState.value.remoteHead, required.headSeq),
            diagnostic = "Recovering from retained checkpoint",
        )

        val bootstrap = api.bootstrap(initialAccess.session, initialAccess.capability)
        validateBootstrap(bootstrap, required)
        val access = prepareBootstrapAccess(initialAccess, bootstrap)
        val retained = bootstrap.retainedStableCheckpoints
            .distinctBy { it.checkpointId to it.ciphertextSha256Base64Url }
        if (retained.isEmpty()) {
            throw SyncInvariantViolation("Server requires a checkpoint but retained no stable checkpoint")
        }

        val failures = mutableListOf<String>()
        for (descriptor in retained) {
            if (descriptor.throughWorkspaceSeq > bootstrap.headSeq) {
                failures += "${descriptor.checkpointId}: cursor exceeds bootstrap head"
                continue
            }
            var installed = false
            try {
                ensureEpochAvailable(access.session, descriptor.keyEpoch, bootstrap.activeKeyEpoch)
                val encrypted = api.downloadCheckpoint(access.session, access.capability, descriptor)
                val verified = crypto.openAndVerifyCheckpoint(access.session, encrypted, descriptor)
                validateCheckpointAgainstBootstrap(verified, descriptor, bootstrap)
                val tail = downloadVerifiedTail(
                    session = access.session,
                    capability = access.capability,
                    afterExclusive = descriptor.throughWorkspaceSeq,
                    fixedHead = bootstrap.headSeq,
                )
                CheckpointInstaller.install(
                    store = localStore,
                    checkpoint = verified,
                    tail = tail,
                    fixedRemoteHead = bootstrap.headSeq,
                    codec = codec,
                )
                installed = true
                flushProjection(localStore.readState())
                mutableState.value = mutableState.value.copy(
                    cursor = bootstrap.headSeq,
                    remoteHead = maxOf(mutableState.value.remoteHead, bootstrap.headSeq),
                    diagnostic = null,
                )
                return access
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Once the atomic install has committed, falling back would roll the replica back.
                if (installed) throw failure
                failures += "${descriptor.checkpointId}: ${failure.message ?: failure::class.simpleName}"
            }
        }
        throw SyncInvariantViolation(
            "No retained stable checkpoint could be verified (${failures.joinToString("; ")})",
        )
    }

    private suspend fun prepareBootstrapAccess(
        initialAccess: SyncAccess,
        bootstrap: BootstrapResponse,
    ): SyncAccess {
        val requiredEpochs = buildSet {
            add(bootstrap.activeKeyEpoch)
            addAll(bootstrap.requiredKeyEpochs)
            bootstrap.retainedStableCheckpoints.forEach { add(it.keyEpoch) }
        }
        requiredEpochs.sorted().forEach { epoch ->
            ensureEpochAvailable(initialAccess.session, epoch, bootstrap.activeKeyEpoch)
        }

        if (localStore.readState().activeKeyEpoch != bootstrap.activeKeyEpoch) {
            throw IllegalStateException("The active workspace key epoch is unavailable on this device")
        }

        if (initialAccess.session.activeKeyEpoch == bootstrap.activeKeyEpoch) return initialAccess
        val updated = initialAccess.session.copy(activeKeyEpoch = bootstrap.activeKeyEpoch)
        sessionStore.save(updated)
        return obtainValidatedAccess(updated)
    }

    private suspend fun ensureEpochAvailable(session: SyncSession, epoch: Int, activeEpoch: Int) {
        require(epoch > 0) { "Bootstrap returned an invalid key epoch" }
        val expectedStatus = if (epoch == activeEpoch) KeyEpochStatus.ACTIVE else KeyEpochStatus.RETAINED
        val existing = localStore.readState().keyEpochs[epoch]
        if (existing != null) {
            if (existing.status != expectedStatus) {
                localStore.transaction { retainKeyEpoch(existing.copy(status = expectedStatus)) }
            }
            return
        }
        val resolver = keyEpochResolver ?: return // SyncCrypto still fails closed if its strict secret is absent.
        val metadata = resolver.resolve(session, epoch)
        if (metadata.epoch != epoch) throw SyncInvariantViolation("Key resolver returned the wrong epoch")
        if (metadata.status != expectedStatus) {
            throw SyncInvariantViolation("Key resolver returned the wrong retention status for epoch $epoch")
        }
        localStore.transaction { retainKeyEpoch(metadata) }
    }

    private suspend fun downloadVerifiedTail(
        session: SyncSession,
        capability: WorkspaceCapability,
        afterExclusive: Long,
        fixedHead: Long,
    ): List<CommittedSyncEvent> {
        if (afterExclusive == fixedHead) return emptyList()
        var cursor = afterExclusive
        val tail = mutableListOf<CommittedSyncEvent>()
        do {
            val page = api.catchUp(
                session = session,
                capability = capability,
                afterExclusive = cursor,
                untilInclusive = fixedHead,
                limit = catchUpPageSize,
            )
            if (page.fromExclusive != cursor || page.untilInclusive != fixedHead) {
                throw SyncInvariantViolation("Checkpoint tail changed its fixed watermark")
            }
            if (page.headSeq < fixedHead || page.nextCursor < cursor || page.nextCursor > fixedHead) {
                throw SyncInvariantViolation("Checkpoint tail returned an invalid cursor/head")
            }
            if (page.nextCursor == cursor && cursor < fixedHead) {
                throw SyncInvariantViolation("Checkpoint tail returned an empty non-terminal page")
            }
            validatePageEventSequences(page, cursor)
            page.events.forEach { remote ->
                validateRemoteEnvelope(session, remote)
                tail += CommittedSyncEvent(
                    workspaceSeq = remote.workspaceSeq,
                    event = crypto.openAndVerifyEvent(session, remote).event,
                )
            }
            cursor = page.nextCursor
            if (page.hasMore != (cursor < fixedHead)) {
                throw SyncInvariantViolation("Checkpoint tail pagination flag disagrees with its watermark")
            }
        } while (cursor < fixedHead)
        return tail
    }

    private suspend fun validateBootstrap(
        bootstrap: BootstrapResponse,
        required: CheckpointRequiredException,
    ) {
        if (bootstrap.headSeq < 0 || bootstrap.activeKeyEpoch <= 0) {
            throw SyncInvariantViolation("Server returned invalid bootstrap metadata")
        }
        val localCursor = localStore.readState().replica.throughWorkspaceSeq
        if (bootstrap.headSeq < required.headSeq || bootstrap.headSeq < localCursor) {
            throw SyncInvariantViolation("Server bootstrap attempted to roll back the workspace head")
        }
        if (bootstrap.requiredKeyEpochs.any { it <= 0 }) {
            throw SyncInvariantViolation("Server returned an invalid required key epoch")
        }
        val byId = bootstrap.retainedStableCheckpoints.groupBy { it.checkpointId }
        if (byId.values.any { descriptors -> descriptors.map { it.ciphertextSha256Base64Url }.distinct().size > 1 }) {
            throw SyncInvariantViolation("Server returned conflicting retained checkpoint identities")
        }
        if (bootstrap.retainedStableCheckpoints.zipWithNext().any { (newer, older) ->
                newer.throughWorkspaceSeq < older.throughWorkspaceSeq
            }
        ) {
            throw SyncInvariantViolation("Server returned retained checkpoints outside promotion order")
        }
    }

    private fun validateCheckpointAgainstBootstrap(
        checkpoint: VerifiedSyncCheckpoint,
        descriptor: RetainedCheckpointDescriptor,
        bootstrap: BootstrapResponse,
    ) {
        if (checkpoint.state.throughWorkspaceSeq != descriptor.throughWorkspaceSeq ||
            checkpoint.state.keyEpoch != descriptor.keyEpoch ||
            checkpoint.state.schemaVersion > SYNC_STATE_SCHEMA_VERSION ||
            checkpoint.state.throughWorkspaceSeq > bootstrap.headSeq
        ) {
            throw SyncInvariantViolation("Checkpoint does not match authenticated bootstrap metadata")
        }
    }

    private fun validatePageEventSequences(page: CatchUpPage, fromExclusive: Long) {
        var expected = fromExclusive + 1
        page.events.forEach { remote ->
            if (remote.workspaceSeq != expected) {
                throw SyncSequenceGapException(expected, remote.workspaceSeq)
            }
            if (remote.workspaceSeq > page.nextCursor) {
                throw SyncInvariantViolation("Catch-up event exceeds the advertised page cursor")
            }
            expected += 1
        }
        val appliedThrough = if (page.events.isEmpty()) fromExclusive else expected - 1
        if (appliedThrough != page.nextCursor) {
            throw SyncInvariantViolation("Catch-up page cursor does not cover exactly its returned events")
        }
    }

    private suspend fun sealPendingDrafts(
        session: SyncSession,
        select: (SyncDraft) -> Boolean = { true },
    ) {
        val snapshot = localStore.readState()
        val drafts = snapshot.drafts.values.filter(select)
        if (drafts.isEmpty()) return
        mutableState.value = mutableState.value.copy(phase = SyncEnginePhase.SEALING)
        val sealer = crypto.prepareEventSealer(session, snapshot.activeKeyEpoch)
        try {
            val context = EventSealContext(session.instanceId, session.workspaceId, session.deviceId)
            drafts.sortedWith(compareBy<SyncDraft> { it.createdAtMillis }.thenBy { it.draftId }).forEach { draft ->
                val current = localStore.readState()
                if (draft.draftId in current.drafts) {
                    localStore.sealDraft(
                        draftId = draft.draftId,
                        context = context,
                        keyEpoch = current.activeKeyEpoch,
                        nowMillis = nowMillis(),
                        sealer = sealer,
                    )
                }
            }
        } finally {
            sealer.close()
        }
    }

    private suspend fun flushOutbox(
        initialSession: SyncSession,
        initialCapability: WorkspaceCapability,
        priorityOnly: Boolean = false,
    ): Boolean {
        var session = initialSession
        var capability = initialCapability
        while (true) {
            val sealed = localStore.nextOutboxEvent() ?: return true
            // Device sequences are contiguous, so a reader event cannot jump over an already
            // sealed ordinary event. Stop here and let the normal background drain repair it.
            if (priorityOnly && !sealed.isReaderProgressEvent()) return false
            mutableState.value = mutableState.value.copy(phase = SyncEnginePhase.UPLOADING)
            localStore.transaction { markUploadAttempt(sealed.deviceSeq, nowMillis()) }
            when (val result = api.appendEvent(session, capability, sealed.envelope)) {
                is AppendEventResult.Committed -> {
                    localStore.transaction { recordReceipt(result.receipt) }
                    mutableState.value = mutableState.value.copy(remoteHead = maxOf(mutableState.value.remoteHead, result.headSeq))
                }

                is AppendEventResult.StaleKeyEpoch -> {
                    val receipt = api.eventReceipt(session, capability, sealed.deviceSeq)
                    if (receipt != null) {
                        localStore.transaction { recordReceipt(receipt) }
                        continue
                    }
                    if (result.expectedDeviceSeq != sealed.deviceSeq) {
                        throw SyncInvariantViolation("Stale-key response has an unexpected device high-watermark")
                    }
                    val resolver = keyEpochResolver
                        ?: throw IllegalStateException("Server rotated keys but no key epoch resolver is installed")
                    val metadata = resolver.resolve(session, result.activeKeyEpoch)
                    require(metadata.epoch == result.activeKeyEpoch && metadata.status == KeyEpochStatus.ACTIVE)
                    localStore.transaction { retainKeyEpoch(metadata) }
                    session = session.copy(activeKeyEpoch = result.activeKeyEpoch)
                    sessionStore.save(session)
                    capability = obtainValidatedAccess(session).capability
                    val sealer = crypto.prepareEventSealer(session, result.activeKeyEpoch)
                    try {
                        localStore.transaction {
                            resealAfterExplicitStaleKeyEpoch(
                                deviceSeq = sealed.deviceSeq,
                                context = EventSealContext(session.instanceId, session.workspaceId, session.deviceId),
                                currentKeyEpoch = result.activeKeyEpoch,
                                nowMillis = nowMillis(),
                                sealer = sealer,
                            )
                        }
                    } finally {
                        sealer.close()
                    }
                }

                is AppendEventResult.Retryable -> {
                    mutableState.value = mutableState.value.copy(
                        phase = SyncEnginePhase.ERROR,
                        diagnostic = result.diagnostic,
                    )
                    return false
                }

                is AppendEventResult.RateLimited -> {
                    mutableState.value = mutableState.value.copy(
                        phase = SyncEnginePhase.RATE_LIMITED,
                        retryAfterMillis = result.retryAfterMillis,
                    )
                    return false
                }

                is AppendEventResult.QuotaExceeded -> {
                    mutableState.value = mutableState.value.copy(
                        phase = SyncEnginePhase.QUOTA_EXCEEDED,
                        diagnostic = result.diagnostic,
                    )
                    return false
                }

                is AppendEventResult.KeyRotationRequired -> {
                    if (result.activeKeyEpoch < session.activeKeyEpoch) {
                        throw SyncInvariantViolation("Required-rotation response rolled back the key epoch")
                    }
                    mutableState.value = mutableState.value.copy(
                        phase = SyncEnginePhase.KEY_ROTATION_REQUIRED,
                        diagnostic = null,
                    )
                    return false
                }

                is AppendEventResult.ReplayOrCorruption ->
                    throw SyncInvariantViolation("Server rejected immutable event: ${result.diagnostic}")

                AppendEventResult.DeviceRevoked -> {
                    sessionStore.save(session.copy(status = SyncSessionStatus.REVOKED))
                    publishState(SyncEnginePhase.REVOKED, SyncSessionStatus.REVOKED)
                    return false
                }

                is AppendEventResult.IncompatibleProtocol -> {
                    mutableState.value = mutableState.value.copy(
                        phase = SyncEnginePhase.INCOMPATIBLE,
                        diagnostic = "Requires reader ${result.minReaderVersion}, writer ${result.minWriterVersion}",
                    )
                    return false
                }
            }
        }
    }

    private suspend fun flushProjection(local: LocalSyncStoreState) {
        projectionSink.flush(local)
        localStore.transaction {
            markMaterialized(
                expectedReplica = local.replica,
                expectedIdentityMap = local.identityMap,
                expectedRepositoryTrustConfirmations = local.repositoryTrustConfirmations,
                expectedRepositoryTrustApprovals = local.repositoryTrustApprovals,
            )
        }
    }

    private suspend fun updateForegroundDelivery(session: SyncSession, capabilities: SyncCapabilities) {
        if (!foregroundDeliveryEnabled || closed) return
        if (capabilities.realtimeAvailable && realtimeClient != null &&
            realtimeReconnectFailures < MAX_REALTIME_RECONNECT_FAILURES
        ) {
            connectRealtimeIfAvailable(session, capabilities)
        } else {
            realtimeJob?.cancel()
            realtimeJob = null
            realtimeClient?.close()
            mutableState.value = mutableState.value.copy(realtimeConnected = false)
            scheduleLitePolling()
        }
    }

    private fun connectRealtimeIfAvailable(session: SyncSession, capabilities: SyncCapabilities) {
        val realtime = realtimeClient ?: return
        if (!foregroundDeliveryEnabled || !capabilities.realtimeAvailable ||
            realtimeJob?.isActive == true || mutableState.value.realtimeConnected
        ) return
        realtimeJob = scope.launch {
            try {
                val access = obtainValidatedAccess(session)
                val cursor = localStore.readState().replica.throughWorkspaceSeq
                realtime.connect(access.session, access.capability, cursor) { message ->
                    handleRealtimeMessage(message)
                }
                mutableState.value = mutableState.value.copy(realtimeConnected = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runCatching { realtime.close() }
                markRealtimeUnavailable()
            }
        }
    }

    private suspend fun handleRealtimeMessage(message: RealtimeWorkspaceMessage) = serialized {
        if (closed) return@serialized
        when (message) {
            is RealtimeWorkspaceMessage.Event,
            is RealtimeWorkspaceMessage.Hello,
            -> readySessionOrReturn()?.let { syncNowLocked(it) }

            is RealtimeWorkspaceMessage.CheckpointAvailable -> readySessionOrReturn()?.let { syncNowLocked(it) }
            is RealtimeWorkspaceMessage.ResyncRequired -> readySessionOrReturn()?.let { syncNowLocked(it) }

            RealtimeWorkspaceMessage.ReauthRequired -> {
                markRealtimeUnavailable()
                realtimeJob = null
                readySessionOrReturn()?.let { syncNowLocked(it) }
                if (realtimeReconnectFailures < MAX_REALTIME_RECONNECT_FAILURES) {
                    scheduleDeliveryRefresh()
                }
            }
        }
    }

    /** Runs outside the receiver callback so closing the old socket can await that callback safely. */
    private fun scheduleDeliveryRefresh() {
        if (!foregroundDeliveryEnabled || closed || deliveryRefreshJob?.isActive == true) return
        deliveryRefreshJob = scope.launch {
            try {
                realtimeClient?.close()
                serialized {
                    if (!foregroundDeliveryEnabled || closed) return@serialized
                    val session = readySessionOrReturn() ?: return@serialized
                    val capabilities = api.capabilities(session.endpoint)
                    capabilities.requireCompatible(
                        SYNC_PROTOCOL_VERSION,
                        SYNC_PROTOCOL_VERSION,
                        SYNC_STATE_SCHEMA_VERSION,
                        SYNC_STATE_SCHEMA_VERSION,
                    )
                    updateForegroundDelivery(session, capabilities)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                markRealtimeUnavailable()
            } finally {
                deliveryRefreshJob = null
            }
        }
    }

    private fun scheduleLitePolling() {
        if (!foregroundDeliveryEnabled || closed || litePollingJob?.isActive == true) return
        litePollingJob = scope.launch {
            var nextDelay = litePollingInitialDelayMillis
            while (foregroundDeliveryEnabled && !closed) {
                delay(nextDelay)
                if (!foregroundDeliveryEnabled || closed) break
                val before = mutableState.value
                serialized {
                    if (!foregroundDeliveryEnabled || closed) return@serialized
                    val session = readySessionOrReturn() ?: return@serialized
                    try {
                        syncNowLocked(session)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        publishFailure(failure)
                    }
                }
                val after = mutableState.value
                val activityObserved = after.cursor != before.cursor ||
                    after.remoteHead != before.remoteHead ||
                    after.draftCount > 0 || after.outboxCount > 0
                nextDelay = if (activityObserved) {
                    litePollingInitialDelayMillis
                } else {
                    (nextDelay * 2).coerceAtMost(litePollingMaxDelayMillis)
                }
            }
        }
    }

    /**
     * Realtime is an optimization; REST catch-up remains the source of truth. A socket failure
     * must therefore never move a healthy engine into ERROR or expose a transport exception as a
     * sync failure. Keep polling while allowing only a small number of reconnects per foreground
     * session so an iOS networking stack cannot churn through sockets indefinitely.
     */
    private fun markRealtimeUnavailable() {
        realtimeReconnectFailures =
            (realtimeReconnectFailures + 1).coerceAtMost(MAX_REALTIME_RECONNECT_FAILURES)
        val current = mutableState.value
        mutableState.value = current.copy(
            realtimeConnected = false,
            // A realtime interruption is not an engine diagnostic. Clear a stale diagnostic only
            // while the durable sync state is healthy; preserve genuine catch-up/upload failures.
            diagnostic = current.diagnostic.takeUnless { current.phase == SyncEnginePhase.READY },
        )
        scheduleLitePolling()
    }

    private fun cancelLitePolling() {
        litePollingJob?.cancel()
        litePollingJob = null
    }

    private fun validatedCapability(session: SyncSession, capability: WorkspaceCapability): WorkspaceCapability {
        val binding = capability.binding
        if (binding.deviceId != session.deviceId || binding.workspaceId != session.workspaceId) {
            throw SyncInvariantViolation("Workspace capability is bound to another tenant/device")
        }
        if (binding.deviceAuthEpoch != session.deviceAuthEpoch ||
            binding.membershipAuthEpoch != session.membershipAuthEpoch ||
            binding.keyEpoch != session.activeKeyEpoch
        ) {
            throw SyncInvariantViolation("Workspace capability epochs do not match the durable session")
        }
        if (binding.expiresAtMillis <= nowMillis()) throw IllegalStateException("Workspace capability is expired")
        return capability
    }

    /**
     * A valid device can receive a capability for a newly committed key epoch before its durable
     * session has installed that epoch. Tenant/auth bindings remain strict; only a monotonic key
     * advance is handed to the signed-envelope resolver before final capability validation.
     */
    private suspend fun obtainValidatedAccess(initialSession: SyncSession): SyncAccess {
        var session = initialSession
        val capability = api.obtainWorkspaceCapability(session)
        val binding = capability.binding
        if (binding.deviceId != session.deviceId || binding.workspaceId != session.workspaceId ||
            binding.deviceAuthEpoch != session.deviceAuthEpoch ||
            binding.membershipAuthEpoch != session.membershipAuthEpoch
        ) {
            throw SyncInvariantViolation("Workspace capability auth binding does not match the durable session")
        }
        if (binding.keyEpoch < session.activeKeyEpoch) {
            throw SyncInvariantViolation("Workspace capability attempted to roll back the key epoch")
        }
        if (binding.keyEpoch > session.activeKeyEpoch) {
            val resolver = keyEpochResolver
                ?: throw IllegalStateException("Server rotated keys but no key epoch resolver is installed")
            val metadata = resolver.resolve(session, binding.keyEpoch)
            if (metadata.epoch != binding.keyEpoch || metadata.status != KeyEpochStatus.ACTIVE) {
                throw SyncInvariantViolation("Key resolver did not install the capability's active epoch")
            }
            localStore.transaction { retainKeyEpoch(metadata) }
            val persisted = sessionStore.load()
            session = if (persisted != null &&
                persisted.instanceId == session.instanceId &&
                persisted.workspaceId == session.workspaceId &&
                persisted.deviceId == session.deviceId &&
                persisted.activeKeyEpoch == binding.keyEpoch
            ) {
                persisted
            } else {
                session.copy(activeKeyEpoch = binding.keyEpoch).also { sessionStore.save(it) }
            }
        }
        return SyncAccess(session, validatedCapability(session, capability))
    }

    private fun validateRemoteEnvelope(session: SyncSession, remote: RemoteCommittedEnvelope) {
        val header = remote.envelope.header
        if (header.instanceId != session.instanceId || header.workspaceId != session.workspaceId) {
            throw SyncInvariantViolation("Remote event crossed a tenant boundary")
        }
        if (header.protocolVersion > SYNC_PROTOCOL_VERSION || header.schemaVersion > SYNC_STATE_SCHEMA_VERSION) {
            throw SyncInvariantViolation("Remote event requires a newer client")
        }
    }

    private suspend fun readySessionOrReturn(): SyncSession? {
        val session = sessionStore.load()
        if (session == null) {
            publishState(SyncEnginePhase.STOPPED, SyncSessionStatus.NOT_CONFIGURED)
            return null
        }
        if (session.status != SyncSessionStatus.READY) {
            val phase = if (session.status == SyncSessionStatus.REVOKED) {
                SyncEnginePhase.REVOKED
            } else {
                SyncEnginePhase.STOPPED
            }
            publishState(phase, session.status)
            return null
        }
        return session
    }

    private suspend fun publishReady() {
        val local = localStore.readState()
        mutableState.value = mutableState.value.copy(
            phase = SyncEnginePhase.READY,
            sessionStatus = SyncSessionStatus.READY,
            cursor = local.replica.throughWorkspaceSeq,
            draftCount = local.drafts.size,
            outboxCount = local.sealedOutbox.size,
            lastSuccessfulSyncAtMillis = nowMillis(),
            retryAfterMillis = null,
            diagnostic = null,
        )
    }

    private suspend fun publishState(phase: SyncEnginePhase, status: SyncSessionStatus) {
        val local = localStore.readState()
        mutableState.value = mutableState.value.copy(
            phase = phase,
            sessionStatus = status,
            cursor = local.replica.throughWorkspaceSeq,
            draftCount = local.drafts.size,
            outboxCount = local.sealedOutbox.size,
            realtimeConnected = false,
        )
    }

    private suspend fun publishFailure(failure: Throwable) {
        val local = localStore.readState()
        mutableState.value = mutableState.value.copy(
            phase = SyncEnginePhase.ERROR,
            sessionStatus = SyncSessionStatus.ERROR,
            cursor = local.replica.throughWorkspaceSeq,
            draftCount = local.drafts.size,
            outboxCount = local.sealedOutbox.size,
            diagnostic = failure.message ?: failure::class.simpleName,
        )
    }

    private fun ensureOpen() {
        check(!closed) { "Sync engine is closed" }
    }

    private fun SyncDraft.isReaderProgressDraft(): Boolean =
        coalescingKey?.startsWith("reader|") == true &&
            event.mutations.isNotEmpty() && event.mutations.all { it is ReadingProgressSet }

    private fun SealedOutboxEvent.isReaderProgressEvent(): Boolean =
        logicalEvent.mutations.isNotEmpty() && logicalEvent.mutations.all { it is ReadingProgressSet }

    private companion object {
        const val MAX_CHECKPOINT_RECOVERY_ATTEMPTS = 2
        const val MAX_REALTIME_RECONNECT_FAILURES = 3
    }

    private suspend fun <T> serialized(block: suspend () -> T): T {
        operationMutex.lock()
        return try {
            block()
        } finally {
            operationMutex.unlock()
        }
    }

    private data class SyncAccess(
        val session: SyncSession,
        val capability: WorkspaceCapability,
    )

}
