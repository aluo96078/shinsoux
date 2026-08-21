package dev.shinsou.kmp.sync.v2

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable

@Serializable
data class SyncDraft(
    val draftId: String,
    val event: SyncEvent,
    val coalescingKey: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
) {
    init {
        require(draftId.isNotBlank()) { "Draft id cannot be blank" }
        require(coalescingKey == null || coalescingKey.isNotBlank()) { "Coalescing key cannot be blank" }
        require(createdAtMillis >= 0 && updatedAtMillis >= createdAtMillis) { "Invalid draft timestamps" }
    }
}

@Serializable
enum class SyncCipherSuite {
    AES_256_GCM,
    CHACHA20_POLY1305,
}

/** Clear, authenticated event header. workspaceSeq is intentionally absent. */
@Serializable
data class SyncEventHeader(
    val envelopeVersion: Int = 1,
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val schemaVersion: Int = SYNC_STATE_SCHEMA_VERSION,
    val cipherSuite: SyncCipherSuite,
    val nonceBase64Url: String,
    val instanceId: String,
    val workspaceId: String,
    val eventId: String,
    val deviceId: String,
    val deviceSeq: Long,
    val keyEpoch: Int,
    val ciphertextSha256Base64Url: String,
) {
    init {
        require(envelopeVersion > 0 && protocolVersion > 0 && schemaVersion > 0) { "Invalid event versions" }
        require(nonceBase64Url.isNotBlank()) { "Event nonce cannot be blank" }
        require(instanceId.isNotBlank() && workspaceId.isNotBlank()) { "Event tenant ids cannot be blank" }
        require(eventId.isNotBlank() && deviceId.isNotBlank()) { "Event/device ids cannot be blank" }
        require(deviceSeq > 0) { "Device sequence must be positive" }
        require(keyEpoch > 0) { "Key epoch must be positive" }
        require(ciphertextSha256Base64Url.isNotBlank()) { "Ciphertext hash cannot be blank" }
    }
}

@Serializable
data class EncryptedSyncEvent(
    val header: SyncEventHeader,
    val authenticatedHeaderBase64Url: String,
    val ciphertextBase64Url: String,
    val signatureBase64Url: String,
) {
    init {
        require(authenticatedHeaderBase64Url.isNotBlank()) { "Canonical event header cannot be blank" }
        require(ciphertextBase64Url.isNotBlank()) { "Event ciphertext cannot be blank" }
        require(signatureBase64Url.isNotBlank()) { "Event signature cannot be blank" }
    }
}

@Serializable
data class SealedOutboxEvent(
    val draftId: String,
    val logicalEvent: SyncEvent,
    val envelope: EncryptedSyncEvent,
    val sealedAtMillis: Long,
    val attemptCount: Int = 0,
    val lastAttemptAtMillis: Long? = null,
) {
    init {
        require(draftId.isNotBlank()) { "Sealed event must retain its draft identity" }
        require(envelope.header.schemaVersion == logicalEvent.schemaVersion) { "Header/payload schema mismatch" }
        require(envelope.header.deviceId == logicalEvent.hlc.deviceId) { "Header/payload device mismatch" }
        require(sealedAtMillis >= 0 && attemptCount >= 0) { "Invalid outbox metadata" }
        lastAttemptAtMillis?.let { require(it >= sealedAtMillis) { "Attempt predates sealing" } }
    }

    val deviceSeq: Long get() = envelope.header.deviceSeq
    val eventId: String get() = envelope.header.eventId
    val keyEpoch: Int get() = envelope.header.keyEpoch
    val ciphertextSha256Base64Url: String get() = envelope.header.ciphertextSha256Base64Url
}

@Serializable
data class ArchivedSealedEvent(
    val event: SealedOutboxEvent,
    val reason: String,
    val archivedAtMillis: Long,
)

@Serializable
data class SyncReceipt(
    val eventId: String,
    val deviceSeq: Long,
    val workspaceSeq: Long,
    val ciphertextSha256Base64Url: String,
) {
    init {
        require(eventId.isNotBlank() && ciphertextSha256Base64Url.isNotBlank()) { "Invalid receipt identity" }
        require(deviceSeq > 0 && workspaceSeq > 0) { "Receipt sequences must be positive" }
    }
}

@Serializable
enum class KeyEpochStatus {
    ACTIVE,
    RETAINED,
}

/** Only key references and retention metadata live here; secret bytes belong in SyncSecretStore. */
@Serializable
data class KeyEpochMetadata(
    val epoch: Int,
    val secretKeyId: String,
    val status: KeyEpochStatus,
    val createdAtMillis: Long,
) {
    init {
        require(epoch > 0) { "Key epoch must be positive" }
        require(secretKeyId.isNotBlank()) { "Key epoch secret reference cannot be blank" }
        require(createdAtMillis >= 0) { "Invalid key epoch timestamp" }
    }
}

/**
 * Durable two-store deletion journal. An epoch remains here until its protected-store secret has
 * been deleted first and the matching LocalSyncStore metadata removal commits afterwards.
 */
@Serializable
data class KeyEpochPruningIntent(
    val targetRecoveryBaseKeyEpoch: Int,
    val pendingEpochs: List<Int>,
) {
    init {
        require(targetRecoveryBaseKeyEpoch > 0) { "Invalid pruning recovery-base epoch" }
        require(pendingEpochs.isNotEmpty()) { "A pruning intent must contain pending epochs" }
        require(pendingEpochs == pendingEpochs.distinct().sorted()) {
            "Pruning epochs must be unique and sorted"
        }
        require(pendingEpochs.all { it > 0 && it < targetRecoveryBaseKeyEpoch }) {
            "A pruning intent cannot remove its recovery base or a newer epoch"
        }
    }
}

@Serializable
data class SealingIntent(
    val draftId: String,
    val deviceSeq: Long,
    val keyEpoch: Int,
    val startedAtMillis: Long,
) {
    init {
        require(draftId.isNotBlank() && deviceSeq > 0 && keyEpoch > 0 && startedAtMillis >= 0) {
            "Invalid sealing intent"
        }
    }
}

/** Durable proof that the local snapshot, rather than an event tail, seeded checkpoint zero. */
@Serializable
data class GenesisCheckpointSeed(
    val deviceId: String,
    val seededAtMillis: Long,
) {
    init {
        require(deviceId.isNotBlank()) { "Genesis seed device id cannot be blank" }
        require(seededAtMillis >= 0) { "Genesis seed timestamp is invalid" }
    }
}

@Serializable
data class LocalSyncStoreState(
    val replica: SyncState = SyncState(),
    val lastLocalHlc: HlcTimestamp? = null,
    val maxObservedRemoteHlc: HlcTimestamp? = null,
    val identityMap: SyncIdentityMap = SyncIdentityMap(),
    val drafts: Map<String, SyncDraft> = emptyMap(),
    val sealedOutbox: Map<Long, SealedOutboxEvent> = emptyMap(),
    val archivedSealedEvents: List<ArchivedSealedEvent> = emptyList(),
    val nextDeviceSeq: Long = 1,
    val committedDeviceSeq: Long = 0,
    val verifiedReceipts: Map<Long, SyncReceipt> = emptyMap(),
    val activeKeyEpoch: Int = 1,
    val keyEpochs: Map<Int, KeyEpochMetadata> = emptyMap(),
    val recoveryBaseKeyEpoch: Int = 1,
    /** Last authenticated bootstrap requirement set, stored in deterministic epoch order. */
    val serverRequiredKeyEpochs: List<Int> = emptyList(),
    val keyEpochPruningIntent: KeyEpochPruningIntent? = null,
    val sealingIntent: SealingIntent? = null,
    val genesisCheckpointSeed: GenesisCheckpointSeed? = null,
    /** Last report produced by a projection of this durable replica. */
    val materializationIssues: List<MaterializationIssue> = emptyList(),
    /** Exact device-local signing-key transitions awaiting or recording user review. */
    val repositoryTrustConfirmations: List<RepositoryTrustConfirmation> = emptyList(),
    /** One-shot accepted transitions retained across a crash until their projection is durable. */
    val repositoryTrustApprovals: List<RepositoryTrustConfirmation> = emptyList(),
    val materializationPending: Boolean = false,
) {
    init {
        require(nextDeviceSeq > 0) { "Next device sequence must be positive" }
        require(committedDeviceSeq >= 0 && committedDeviceSeq < nextDeviceSeq) { "Invalid committed device sequence" }
        require(activeKeyEpoch > 0 && recoveryBaseKeyEpoch > 0) { "Invalid key epoch" }
        require(serverRequiredKeyEpochs == serverRequiredKeyEpochs.distinct().sorted()) {
            "Server-required key epochs must be unique and sorted"
        }
        require(serverRequiredKeyEpochs.all { it > 0 }) { "Invalid server-required key epoch" }
        require(repositoryTrustConfirmations.map { it.baseUrl }.distinct().size == repositoryTrustConfirmations.size) {
            "Only one repository trust confirmation may exist per repository"
        }
        require(repositoryTrustConfirmations.none { it.status == RepositoryTrustConfirmationStatus.ACCEPTED }) {
            "Accepted repository trust belongs in the projection intent journal"
        }
        require(repositoryTrustApprovals.map { it.baseUrl }.distinct().size == repositoryTrustApprovals.size) {
            "Only one repository trust approval may exist per repository"
        }
        require(repositoryTrustApprovals.all { it.status == RepositoryTrustConfirmationStatus.ACCEPTED }) {
            "Repository trust approval journal contains a non-accepted decision"
        }
    }

    fun validate(): LocalSyncStoreState {
        sealedOutbox.forEach { (sequence, event) ->
            if (sequence != event.deviceSeq) throw SyncInvariantViolation("Outbox sequence key/header mismatch")
            if (sequence >= nextDeviceSeq) throw SyncInvariantViolation("Outbox sequence was not consumed atomically")
        }
        verifiedReceipts.forEach { (sequence, receipt) ->
            if (sequence != receipt.deviceSeq) throw SyncInvariantViolation("Receipt sequence key/body mismatch")
        }
        if (replica.keyEpoch > activeKeyEpoch) throw SyncInvariantViolation("Replica key epoch exceeds active key epoch")
        if (keyEpochs.isNotEmpty() && activeKeyEpoch !in keyEpochs) {
            throw SyncInvariantViolation("Active key epoch metadata is missing")
        }
        if (serverRequiredKeyEpochs.any { it !in keyEpochs }) {
            throw SyncInvariantViolation("Server-required key epoch metadata is missing")
        }
        keyEpochPruningIntent?.let { intent ->
            if (intent.targetRecoveryBaseKeyEpoch != recoveryBaseKeyEpoch) {
                throw SyncInvariantViolation("Key pruning intent is detached from the recovery base")
            }
            if (intent.pendingEpochs.any { it !in keyEpochs || it in serverRequiredKeyEpochs || it == activeKeyEpoch }) {
                throw SyncInvariantViolation("Key pruning intent contains a retained epoch")
            }
        }
        return this
    }

    fun materializationDiagnostics(): SyncMaterializationDiagnostics = SyncMaterializationDiagnostics(
        issues = materializationIssues,
        repositoryTrustConfirmations = repositoryTrustConfirmations,
    )

    /**
     * Repairs only states that can arise at documented crash boundaries. It never guesses whether
     * an unknown network append committed; those sealed rows remain until a verified receipt.
     */
    fun reconciledAfterCrash(): LocalSyncStoreState {
        var repaired = this
        val intent = repaired.sealingIntent
        if (intent != null) {
            val sealed = repaired.sealedOutbox[intent.deviceSeq]
            repaired = if (sealed != null) {
                if (sealed.draftId != intent.draftId) {
                    throw SyncInvariantViolation("Sealing intent conflicts with an immutable outbox row")
                }
                repaired.copy(
                    drafts = repaired.drafts - intent.draftId,
                    nextDeviceSeq = maxOf(repaired.nextDeviceSeq, intent.deviceSeq + 1),
                    sealingIntent = null,
                )
            } else {
                val maxSealed = repaired.sealedOutbox.keys.maxOrNull() ?: 0L
                if (maxSealed >= intent.deviceSeq) {
                    throw SyncInvariantViolation("A later sequence crossed an incomplete sealing intent")
                }
                repaired.copy(
                    nextDeviceSeq = intent.deviceSeq,
                    sealingIntent = null,
                )
            }
        }

        var replica = repaired.replica
        val pending = (repaired.sealedOutbox.values.map { it.logicalEvent } + repaired.drafts.values.map { it.event })
            .distinctBy { it.opId }
            .sortedWith(compareBy<SyncEvent> { it.hlc }.thenBy { it.opId })
        pending.forEach { event -> replica = SyncReducer.reduce(replica, event) }
        val pendingDeviceIds = pending.mapTo(mutableSetOf()) { it.hlc.deviceId }
        if (pendingDeviceIds.size > 1) throw SyncInvariantViolation("Local pending operations mix device identities")
        val inferredLocalHlc = pending.maxOfOrNull { it.hlc }
        val maxSequence = repaired.sealedOutbox.keys.maxOrNull() ?: 0L
        repaired = repaired.copy(
            replica = replica,
            lastLocalHlc = maxOfOrNull(repaired.lastLocalHlc, inferredLocalHlc),
            nextDeviceSeq = maxOf(repaired.nextDeviceSeq, maxSequence + 1),
            materializationPending = repaired.materializationPending || replica != repaired.replica,
        )
        return repaired.advanceCommittedHighWatermark().validate()
    }

    internal fun advanceCommittedHighWatermark(): LocalSyncStoreState {
        var high = committedDeviceSeq
        while (verifiedReceipts.containsKey(high + 1)) high += 1
        val uncommittedReceipts = verifiedReceipts.filterKeys { it > high }
        return if (high == committedDeviceSeq && uncommittedReceipts.size == verifiedReceipts.size) {
            this
        } else {
            copy(
                committedDeviceSeq = high,
                verifiedReceipts = uncommittedReceipts,
            )
        }
    }
}

/** Implementations must replace the whole state atomically or throw without changing durable data. */
interface SyncStatePersistence {
    suspend fun load(): LocalSyncStoreState?
    suspend fun saveAtomically(state: LocalSyncStoreState)

    /** Drops any optimistic cache after an enclosing host transaction rolled the save back. */
    suspend fun reloadAfterExternalRollback(): LocalSyncStoreState? = load()
}

class InMemorySyncStatePersistence(initial: LocalSyncStoreState? = null) : SyncStatePersistence {
    private var persisted: LocalSyncStoreState? = initial
    var failNextSave: Throwable? = null

    override suspend fun load(): LocalSyncStoreState? = persisted

    override suspend fun saveAtomically(state: LocalSyncStoreState) {
        failNextSave?.let { failure ->
            failNextSave = null
            throw failure
        }
        persisted = state
    }

    fun current(): LocalSyncStoreState? = persisted
}

interface LocalSyncStore {
    suspend fun readState(): LocalSyncStoreState

    suspend fun <T> transaction(block: SyncStoreTransaction.() -> T): T

    /** Restores the in-memory projection from the durable state after an enclosing SQL rollback. */
    suspend fun reconcileAfterExternalRollback(): Unit = Unit
}

class PersistentLocalSyncStore private constructor(
    private val persistence: SyncStatePersistence,
    initial: LocalSyncStoreState,
) : LocalSyncStore {
    private val mutex = Mutex()
    private var state: LocalSyncStoreState = initial.reconciledAfterCrash()

    override suspend fun readState(): LocalSyncStoreState {
        mutex.lock()
        return try {
            state
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun <T> transaction(block: SyncStoreTransaction.() -> T): T {
        mutex.lock()
        try {
            val transaction = SyncStoreTransaction(state)
            val result = transaction.block()
            val candidate = transaction.build().validate()
            persistence.saveAtomically(candidate)
            state = candidate
            return result
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun reconcileAfterExternalRollback() {
        mutex.lock()
        try {
            state = (persistence.reloadAfterExternalRollback() ?: LocalSyncStoreState())
                .reconciledAfterCrash()
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        suspend fun open(
            persistence: SyncStatePersistence,
            initial: LocalSyncStoreState = LocalSyncStoreState(),
        ): PersistentLocalSyncStore {
            val loaded = (persistence.load() ?: initial).reconciledAfterCrash()
            persistence.saveAtomically(loaded)
            return PersistentLocalSyncStore(persistence, loaded)
        }

        internal fun fromLoaded(
            persistence: SyncStatePersistence,
            initial: LocalSyncStoreState,
        ): PersistentLocalSyncStore = PersistentLocalSyncStore(persistence, initial)
    }
}

class InMemoryLocalSyncStore(
    initial: LocalSyncStoreState = LocalSyncStoreState(),
) : LocalSyncStore {
    private val persistence = InMemorySyncStatePersistence(initial)
    private val delegate = PersistentLocalSyncStore.fromLoaded(persistence, initial)

    override suspend fun readState(): LocalSyncStoreState = delegate.readState()

    override suspend fun <T> transaction(block: SyncStoreTransaction.() -> T): T = delegate.transaction(block)

    override suspend fun reconcileAfterExternalRollback(): Unit =
        delegate.reconcileAfterExternalRollback()

    fun persistedState(): LocalSyncStoreState = requireNotNull(persistence.current())
}

data class EventSealContext(
    val instanceId: String,
    val workspaceId: String,
    val deviceId: String,
    val protocolVersion: Int = SYNC_PROTOCOL_VERSION,
    val envelopeVersion: Int = 1,
)

data class SealEventRequest(
    val context: EventSealContext,
    val deviceSeq: Long,
    val keyEpoch: Int,
    val event: SyncEvent,
    val sealedAtMillis: Long,
)

fun interface SyncEventSealer {
    /** Must return fresh event-id/nonce material and have no externally visible side effects. */
    fun seal(request: SealEventRequest): EncryptedSyncEvent
}

class SyncStoreTransaction internal constructor(initial: LocalSyncStoreState) {
    private var state: LocalSyncStoreState = initial
    private var allocatedLocalHlc: HlcTimestamp? = null

    fun state(): LocalSyncStoreState = state

    fun applyLocalEvent(
        event: SyncEvent,
        nowMillis: Long,
        coalescingKey: String? = null,
    ): SyncDraft {
        require(nowMillis >= 0) { "Draft timestamp cannot be negative" }
        state.lastLocalHlc?.let { last ->
            require(event.hlc.deviceId == last.deviceId) { "A LocalSyncStore cannot mix local device identities" }
            if (event.opId !in state.replica.appliedOpIds) {
                require(event.hlc > last || event.hlc == allocatedLocalHlc) {
                    "Local event HLC must increase monotonically"
                }
            }
        }
        val existing = coalescingKey?.let { key -> state.drafts.values.firstOrNull { it.coalescingKey == key } }
        val draft = if (existing == null) {
            SyncDraft(
                draftId = event.opId,
                event = event,
                coalescingKey = coalescingKey,
                createdAtMillis = nowMillis,
            )
        } else {
            existing.copy(event = mergeCoalescedEvents(existing.event, event), updatedAtMillis = nowMillis)
        }
        val drafts = state.drafts - listOfNotNull(existing?.draftId).toSet() + (draft.draftId to draft)
        state = state.copy(
            replica = SyncReducer.reduce(state.replica, event),
            lastLocalHlc = maxOfOrNull(state.lastLocalHlc, event.hlc),
            drafts = drafts,
            materializationPending = true,
        )
        if (allocatedLocalHlc == event.hlc) allocatedLocalHlc = null
        return draft
    }

    /**
     * Applies a batch while constructing checkpoint zero. It is deliberately unable to create a
     * draft or consume a device sequence, and it fails if any ordinary event journal exists.
     */
    fun applyGenesisSeedEvent(event: SyncEvent) {
        require(state.genesisCheckpointSeed == null) { "Genesis checkpoint seed is already complete" }
        require(state.replica.throughWorkspaceSeq == 0L) { "Genesis seed cannot replace remote history" }
        require(state.drafts.isEmpty() && state.sealedOutbox.isEmpty() && state.verifiedReceipts.isEmpty()) {
            "Genesis seed cannot discard an event journal"
        }
        require(event.hlc == allocatedLocalHlc) { "Genesis event must use the transaction's allocated HLC" }
        state.lastLocalHlc?.let { last ->
            require(event.hlc.deviceId == last.deviceId) {
                "A LocalSyncStore cannot mix local device identities"
            }
        }
        state = state.copy(
            replica = SyncReducer.reduce(state.replica, event),
            lastLocalHlc = maxOfOrNull(state.lastLocalHlc, event.hlc),
            materializationPending = false,
        )
        allocatedLocalHlc = null
    }

    /** Closes the one permitted genesis transaction after all snapshot batches were reduced. */
    fun completeGenesisCheckpointSeed(deviceId: String, seededAtMillis: Long) {
        require(state.genesisCheckpointSeed == null) { "Genesis checkpoint seed is already complete" }
        require(state.replica.throughWorkspaceSeq == 0L) { "Genesis checkpoint cursor must be zero" }
        require(state.drafts.isEmpty() && state.sealedOutbox.isEmpty() && state.verifiedReceipts.isEmpty()) {
            "Genesis checkpoint cannot contain an event journal"
        }
        state.lastLocalHlc?.let { require(it.deviceId == deviceId) { "Genesis seed device identity changed" } }
        state = state.copy(
            genesisCheckpointSeed = GenesisCheckpointSeed(deviceId, seededAtMillis),
            materializationPending = false,
        )
        allocatedLocalHlc = null
    }

    fun applyRemotePage(events: List<CommittedSyncEvent>, nextCursor: Long) {
        require(nextCursor >= state.replica.throughWorkspaceSeq) { "Cursor cannot move backwards" }
        var replica = state.replica
        events.forEach { committed -> replica = SyncReducer.reduceCommitted(replica, committed) }
        if (replica.throughWorkspaceSeq != nextCursor) {
            throw SyncInvariantViolation(
                "Page cursor $nextCursor does not match fully applied sequence ${replica.throughWorkspaceSeq}",
            )
        }
        val remoteMax = events.maxOfOrNull { it.event.hlc }
        state = state.copy(
            replica = replica,
            maxObservedRemoteHlc = maxOfOrNull(state.maxObservedRemoteHlc, remoteMax),
            materializationPending = true,
        )
    }

    /** Allocates and persists the next local HLC inside the caller's mutation transaction. */
    fun nextLocalHlc(deviceId: String, wallMillis: Long): HlcTimestamp {
        require(deviceId.isNotBlank() && wallMillis >= 0)
        val initial = state.lastLocalHlc ?: HlcTimestamp(0, 0, deviceId)
        require(initial.deviceId == deviceId) { "A LocalSyncStore cannot change its local device identity" }
        val clock = HybridLogicalClock(deviceId, initial) { wallMillis }
        val next = state.maxObservedRemoteHlc?.let { clock.observe(it, wallMillis) } ?: clock.tick(wallMillis)
        state = state.copy(lastLocalHlc = next)
        allocatedLocalHlc = next
        return next
    }

    fun sealDraftAtomically(
        draftId: String,
        context: EventSealContext,
        keyEpoch: Int,
        nowMillis: Long,
        sealer: SyncEventSealer,
    ): SealedOutboxEvent {
        require(state.sealingIntent == null) { "An earlier sealing intent must be reconciled first" }
        require(keyEpoch == state.activeKeyEpoch) { "Drafts must be sealed with the active key epoch" }
        val draft = state.drafts[draftId] ?: throw NoSuchElementException("Unknown draft: $draftId")
        val sequence = state.nextDeviceSeq
        val request = SealEventRequest(context, sequence, keyEpoch, draft.event, nowMillis)
        val envelope = sealer.seal(request)
        validateSealedEnvelope(request, envelope)
        val sealed = SealedOutboxEvent(draftId, draft.event, envelope, nowMillis)
        state = state.copy(
            drafts = state.drafts - draftId,
            sealedOutbox = state.sealedOutbox + (sequence to sealed),
            nextDeviceSeq = sequence + 1,
        )
        return sealed
    }

    /** Optional two-transaction adapter path for crypto APIs that cannot execute inside a DB transaction. */
    fun beginSealingIntent(draftId: String, keyEpoch: Int, nowMillis: Long): SealingIntent {
        require(state.sealingIntent == null) { "A sealing intent is already active" }
        require(draftId in state.drafts) { "Unknown draft: $draftId" }
        require(keyEpoch == state.activeKeyEpoch) { "Drafts must be sealed with the active key epoch" }
        val intent = SealingIntent(draftId, state.nextDeviceSeq, keyEpoch, nowMillis)
        state = state.copy(sealingIntent = intent)
        return intent
    }

    fun completeSealingIntent(envelope: EncryptedSyncEvent, nowMillis: Long): SealedOutboxEvent {
        val intent = state.sealingIntent ?: throw IllegalStateException("No sealing intent is active")
        val draft = state.drafts[intent.draftId]
            ?: throw SyncInvariantViolation("Sealing intent lost its durable draft")
        val request = SealEventRequest(
            context = EventSealContext(
                instanceId = envelope.header.instanceId,
                workspaceId = envelope.header.workspaceId,
                deviceId = envelope.header.deviceId,
                protocolVersion = envelope.header.protocolVersion,
                envelopeVersion = envelope.header.envelopeVersion,
            ),
            deviceSeq = intent.deviceSeq,
            keyEpoch = intent.keyEpoch,
            event = draft.event,
            sealedAtMillis = nowMillis,
        )
        validateSealedEnvelope(request, envelope)
        val sealed = SealedOutboxEvent(intent.draftId, draft.event, envelope, nowMillis)
        state = state.copy(
            drafts = state.drafts - intent.draftId,
            sealedOutbox = state.sealedOutbox + (intent.deviceSeq to sealed),
            nextDeviceSeq = intent.deviceSeq + 1,
            sealingIntent = null,
        )
        return sealed
    }

    fun markUploadAttempt(deviceSeq: Long, nowMillis: Long) {
        val sealed = state.sealedOutbox[deviceSeq] ?: throw NoSuchElementException("Unknown outbox sequence: $deviceSeq")
        state = state.copy(
            sealedOutbox = state.sealedOutbox + (
                deviceSeq to sealed.copy(
                    attemptCount = sealed.attemptCount + 1,
                    lastAttemptAtMillis = nowMillis,
                )
                ),
        )
    }

    fun recordReceipt(receipt: SyncReceipt) {
        val sealed = state.sealedOutbox[receipt.deviceSeq]
        // Contiguous receipts at or below the durable high-watermark were already authenticated
        // before their rows were compacted. A late duplicate must not grow the journal again.
        if (sealed == null && receipt.deviceSeq <= state.committedDeviceSeq) return
        val existing = state.verifiedReceipts[receipt.deviceSeq]
        if (existing != null && existing != receipt) {
            throw SyncInvariantViolation("Server returned conflicting receipts for one device sequence")
        }
        if (sealed != null) {
            if (sealed.eventId != receipt.eventId ||
                sealed.ciphertextSha256Base64Url != receipt.ciphertextSha256Base64Url
            ) {
                throw SyncInvariantViolation("Receipt does not authenticate the immutable outbox event")
            }
        } else if (existing == null && receipt.deviceSeq > state.committedDeviceSeq) {
            throw SyncInvariantViolation("Receipt refers to an unknown local event")
        }
        state = state.copy(
            sealedOutbox = state.sealedOutbox - receipt.deviceSeq,
            verifiedReceipts = state.verifiedReceipts + (receipt.deviceSeq to receipt),
        ).advanceCommittedHighWatermark()
    }

    fun resealAfterExplicitStaleKeyEpoch(
        deviceSeq: Long,
        context: EventSealContext,
        currentKeyEpoch: Int,
        nowMillis: Long,
        sealer: SyncEventSealer,
    ): SealedOutboxEvent {
        require(currentKeyEpoch == state.activeKeyEpoch) { "Stale event must be resealed with the active epoch" }
        val old = state.sealedOutbox[deviceSeq] ?: throw NoSuchElementException("Unknown outbox sequence: $deviceSeq")
        require(deviceSeq > state.committedDeviceSeq) { "A committed event cannot be resealed" }
        require(deviceSeq == state.sealedOutbox.keys.minOrNull()) {
            "Only the next uncommitted device sequence can be declared stale"
        }
        val request = SealEventRequest(context, deviceSeq, currentKeyEpoch, old.logicalEvent, nowMillis)
        val envelope = sealer.seal(request)
        validateSealedEnvelope(request, envelope)
        val replacement = SealedOutboxEvent(old.draftId, old.logicalEvent, envelope, nowMillis)
        state = state.copy(
            sealedOutbox = state.sealedOutbox + (deviceSeq to replacement),
            archivedSealedEvents = state.archivedSealedEvents + ArchivedSealedEvent(
                event = old,
                reason = "server_confirmed_stale_key_epoch",
                archivedAtMillis = nowMillis,
            ),
        )
        return replacement
    }

    fun installCheckpointAndRebase(remoteReplica: SyncState, cursor: Long) {
        require(remoteReplica.throughWorkspaceSeq == cursor) { "Checkpoint/tail cursor mismatch" }
        val checkpointMaxHlc = remoteReplica.maxRegisterHlc()
        var rebased = remoteReplica
        val pendingEvents = (state.sealedOutbox.values.map { it.logicalEvent } + state.drafts.values.map { it.event })
            .distinctBy { it.opId }
            .sortedWith(compareBy<SyncEvent> { it.hlc }.thenBy { it.opId })
        pendingEvents.forEach { event -> rebased = SyncReducer.reduce(rebased, event) }
        state = state.copy(
            replica = rebased,
            maxObservedRemoteHlc = maxOfOrNull(state.maxObservedRemoteHlc, checkpointMaxHlc),
            materializationPending = true,
        )
    }

    fun updateIdentityMap(identityMap: SyncIdentityMap) {
        state = state.copy(identityMap = identityMap)
    }

    /**
     * Commits projection diagnostics only for the exact replica/identity/approval inputs that
     * reached durable AppSnapshot storage. A concurrent remote page or user decision keeps the
     * journal pending and forces a fresh materialization instead of clearing stale issues.
     */
    fun completeMaterialization(
        expectedReplica: SyncState,
        expectedIdentityMap: SyncIdentityMap,
        expectedRepositoryTrustApprovals: List<RepositoryTrustConfirmation>,
        materializedIdentityMap: SyncIdentityMap,
        issues: List<MaterializationIssue>,
        repositoryTrustConfirmations: List<RepositoryTrustConfirmation>,
    ): Boolean {
        if (state.replica != expectedReplica ||
            state.identityMap != expectedIdentityMap ||
            state.repositoryTrustApprovals != expectedRepositoryTrustApprovals ||
            !state.materializationPending
        ) {
            return false
        }
        val rejected = state.repositoryTrustConfirmations
            .filter { it.status == RepositoryTrustConfirmationStatus.REJECTED }
        val mergedConfirmations = repositoryTrustConfirmations.map { current ->
            if (rejected.any(current::sameTransition)) {
                current.copy(status = RepositoryTrustConfirmationStatus.REJECTED)
            } else {
                current.copy(status = RepositoryTrustConfirmationStatus.PENDING)
            }
        }.sortedBy { it.repositoryKey }
        state = state.copy(
            identityMap = materializedIdentityMap,
            materializationIssues = issues
                .distinctBy { Triple(it.kind, it.key, it.message) }
                .sortedWith(compareBy<MaterializationIssue> { it.key }.thenBy { it.kind }.thenBy { it.message }),
            repositoryTrustConfirmations = mergedConfirmations,
            repositoryTrustApprovals = emptyList(),
            materializationPending = false,
        )
        return true
    }

    /** Accepts only the exact transition currently shown to the user. */
    fun acceptRepositoryTrust(baseUrl: String, proposedFingerprint: String) {
        val request = exactRepositoryTrustRequest(baseUrl, proposedFingerprint)
        val approval = request.copy(status = RepositoryTrustConfirmationStatus.ACCEPTED)
        state = state.copy(
            repositoryTrustConfirmations = state.repositoryTrustConfirmations.map {
                if (it.sameTransition(request)) request.copy(status = RepositoryTrustConfirmationStatus.PENDING) else it
            },
            repositoryTrustApprovals = (
                state.repositoryTrustApprovals.filterNot { it.baseUrl == request.baseUrl } + approval
                ).sortedBy { it.repositoryKey },
            materializationPending = true,
        )
    }

    /** Rejecting is durable and remains visible; a later exact accept is still possible. */
    fun rejectRepositoryTrust(baseUrl: String, proposedFingerprint: String) {
        val request = exactRepositoryTrustRequest(baseUrl, proposedFingerprint)
        state = state.copy(
            repositoryTrustConfirmations = state.repositoryTrustConfirmations.map {
                if (it.sameTransition(request)) it.copy(status = RepositoryTrustConfirmationStatus.REJECTED) else it
            },
            repositoryTrustApprovals = state.repositoryTrustApprovals.filterNot { it.baseUrl == request.baseUrl },
            // An accepted projection may already be racing this transaction. Keep a fail-closed
            // projection queued; its exact approval CAS will reject the stale completion.
            materializationPending = true,
        )
    }

    /** Re-runs orphan/invalid validation without erasing the last durable report first. */
    fun requestMaterializationRetry() {
        state = state.copy(materializationPending = true)
    }

    /**
     * Explicit collision repair detaches obsolete remap aliases and lets the materializer allocate
     * safely again. The report remains until the same replica has projected successfully.
     */
    fun repairIdentityCollision(key: SyncEntityKey) {
        require(state.materializationIssues.any {
            it.kind == MaterializationIssueKind.IDENTITY_COLLISION && it.key == key
        }) { "No durable identity collision exists for ${key.stableString()}" }
        val finalKey = state.replica.resolveKey(key)
        val aliases = state.replica.keyRemaps.keys.filterTo(mutableSetOf()) {
            it != finalKey && state.replica.resolveKey(it) == finalKey
        }
        val mappingsToDrop = aliases.takeIf { it.isNotEmpty() } ?: setOf(finalKey)
        val repairedMap = state.identityMap.copy(
            mappings = state.identityMap.mappings.filterNot { it.entityKey in mappingsToDrop },
            blockedKeys = state.identityMap.blockedKeys - (aliases + finalKey),
        )
        state = state.copy(identityMap = repairedMap, materializationPending = true)
    }

    /** Clears the journal bit only when the exact projection that was flushed is still current. */
    fun markMaterialized(
        expectedReplica: SyncState,
        expectedIdentityMap: SyncIdentityMap,
        expectedRepositoryTrustConfirmations: List<RepositoryTrustConfirmation>,
        expectedRepositoryTrustApprovals: List<RepositoryTrustConfirmation>,
    ): Boolean {
        if (state.replica != expectedReplica ||
            state.identityMap != expectedIdentityMap ||
            state.repositoryTrustConfirmations != expectedRepositoryTrustConfirmations ||
            state.repositoryTrustApprovals != expectedRepositoryTrustApprovals
        ) {
            return false
        }
        state = state.copy(materializationPending = false)
        return true
    }

    fun retainKeyEpoch(metadata: KeyEpochMetadata) {
        val epochs = state.keyEpochs.toMutableMap()
        if (metadata.status == KeyEpochStatus.ACTIVE) {
            epochs.replaceAllValues { existing ->
                if (existing.status == KeyEpochStatus.ACTIVE) existing.copy(status = KeyEpochStatus.RETAINED) else existing
            }
        }
        epochs[metadata.epoch] = metadata
        state = state.copy(
            keyEpochs = epochs.deterministicallySorted(),
            activeKeyEpoch = if (metadata.status == KeyEpochStatus.ACTIVE) metadata.epoch else state.activeKeyEpoch,
            replica = if (metadata.status == KeyEpochStatus.ACTIVE) {
                state.replica.copy(keyEpoch = metadata.epoch)
            } else {
                state.replica
            },
        )
    }

    fun moveRecoveryBaseTo(epoch: Int) {
        require(epoch in state.keyEpochs) { "Recovery base epoch is not retained" }
        require(epoch <= state.activeKeyEpoch) { "Recovery base cannot exceed active epoch" }
        require(epoch >= state.recoveryBaseKeyEpoch) { "Recovery base cannot move backwards" }
        state = state.copy(recoveryBaseKeyEpoch = epoch)
    }

    /**
     * Atomically records the authenticated server retention boundary before any protected-store
     * deletion. The second retained stable checkpoint supplies [recoveryBaseEpoch]; the exact
     * required set additionally protects older fallback/tail epochs below that boundary.
     */
    fun planKeyEpochPruning(
        recoveryBaseEpoch: Int,
        requiredKeyEpochs: Set<Int>,
    ) {
        require(recoveryBaseEpoch in state.keyEpochs) { "Recovery base epoch is not retained" }
        require(recoveryBaseEpoch <= state.activeKeyEpoch) { "Recovery base cannot exceed active epoch" }
        require(recoveryBaseEpoch >= state.recoveryBaseKeyEpoch) { "Recovery base cannot move backwards" }
        require(requiredKeyEpochs.isNotEmpty() && state.activeKeyEpoch in requiredKeyEpochs) {
            "The active key epoch must remain server-required"
        }
        require(requiredKeyEpochs.all { it > 0 && it <= state.activeKeyEpoch && it in state.keyEpochs }) {
            "Server-required key epoch metadata is incomplete"
        }

        val sortedRequired = requiredKeyEpochs.sorted()
        val pending = state.keyEpochs.keys.filter { epoch ->
            epoch < recoveryBaseEpoch && epoch !in requiredKeyEpochs && epoch != state.activeKeyEpoch
        }.sorted()
        state = state.copy(
            recoveryBaseKeyEpoch = recoveryBaseEpoch,
            serverRequiredKeyEpochs = sortedRequired,
            keyEpochPruningIntent = pending.takeIf { it.isNotEmpty() }?.let {
                KeyEpochPruningIntent(recoveryBaseEpoch, it)
            },
        )
    }

    fun prunableKeyEpochs(): Set<Int> {
        val outboxEpochs = state.sealedOutbox.values.mapTo(mutableSetOf()) { it.keyEpoch }
        return state.keyEpochs.keys.filterTo(mutableSetOf()) { epoch ->
            epoch < state.recoveryBaseKeyEpoch && epoch !in state.serverRequiredKeyEpochs &&
                epoch !in outboxEpochs && epoch != state.activeKeyEpoch
        }
    }

    fun removeKeyEpochMetadata(epoch: Int) {
        require(epoch in prunableKeyEpochs()) { "Key epoch is still required for recovery or an outbox event" }
        val pending = state.keyEpochPruningIntent?.pendingEpochs?.minus(epoch).orEmpty()
        state = state.copy(
            keyEpochs = state.keyEpochs - epoch,
            keyEpochPruningIntent = if (pending.isEmpty()) {
                null
            } else {
                KeyEpochPruningIntent(state.recoveryBaseKeyEpoch, pending)
            },
        )
    }

    /**
     * Drops all workspace-scoped replica/outbox metadata after producers have stopped and secrets
     * have been removed. Rejoining is a new device enrollment and therefore restarts deviceSeq.
     */
    fun resetForWorkspaceDeparture() {
        state = LocalSyncStoreState()
        allocatedLocalHlc = null
    }

    internal fun build(): LocalSyncStoreState = state

    private fun exactRepositoryTrustRequest(
        baseUrl: String,
        proposedFingerprint: String,
    ): RepositoryTrustConfirmation {
        val matches = state.repositoryTrustConfirmations.filter {
            it.baseUrl == baseUrl && it.proposedFingerprint == proposedFingerprint
        }
        require(matches.size == 1) { "Repository trust request is stale or ambiguous" }
        return matches.single()
    }

    private fun mergeCoalescedEvents(previous: SyncEvent, incoming: SyncEvent): SyncEvent {
        require(incoming.hlc >= previous.hlc) { "A coalesced event cannot move backwards" }
        val merged = previous.mutations.toMutableList()
        incoming.mutations.forEach { next ->
            val index = merged.indexOfFirst { current -> current.coalescingIdentity() == next.coalescingIdentity() }
            if (index < 0) {
                merged += next
            } else {
                merged[index] = mergeMutation(merged[index], next)
            }
        }
        return incoming.copy(mutations = merged)
    }

    private fun SyncMutation.coalescingIdentity(): String = when (this) {
        is LibraryEntryPatch -> "manga:${key.stableString()}"
        is ChapterStatePatch -> "chapter:${key.stableString()}"
        is CategoryPatch -> "category:${key.stableString()}"
        is CategoryMembershipSet -> "membership:${mangaKey.stableString()}:${categoryKey.stableString()}"
        is ExtensionRepositoryPatch -> "repository:${key.stableString()}"
        is ExtensionRepositoryPresenceSet -> "presence:${key.stableString()}"
        is EntityPresenceSet -> "presence:${key.stableString()}"
        is ReadingProgressSet -> "progress:${chapterKey.stableString()}"
        is ReadingProgressPresenceSet -> "progress-presence:${chapterKey.stableString()}"
        is PortableSettingPatch -> "portable-settings"
        is EntityKeyRemap -> "remap:${oldKey.stableString()}"
        is PublicationPatchV2 -> "publication:${key.stableString()}"
        is AcquisitionPatchV2 -> "acquisition:${key.stableString()}"
        is PublicationUnitPatchV2 -> "publication-unit:${key.stableString()}"
        is ContentManifestPatchV2 -> "content-manifest:${key.stableString()}"
        is ContentAnnotationPutV2 -> "content-annotation:${annotation.annotationId}"
        is ContentAnnotationPatchV2 -> "content-annotation-document:${key.stableString()}"
        is PublicationCategoryMembershipSetV2 ->
            "publication-membership:${publicationKey.stableString()}:${categoryKey.stableString()}"
        is ContentReadingProgressSetV2 ->
            "content-progress:${ContentProgressKeyV2.from(locator).stableString()}"
        is ContentReadingProgressPresenceSetV2 -> "content-progress-presence:${key.stableString()}"
        is BlobReferenceCommitV2 -> "blob-commit:${blob.blobId}:$generation"
        is BlobReferenceReincarnationCommitV2 -> "blob-reincarnation:${blob.blobId}:$generation"
        is BlobDekEnvelopeRewrappedV2 -> "blob-envelope:$blobId:$generation:${envelope.keyEpoch}"
        is BlobReferencePresenceSetV2 -> "blob-presence:$blobId"
    }

    private fun mergeMutation(previous: SyncMutation, incoming: SyncMutation): SyncMutation = when {
        previous is ReadingProgressSet && incoming is ReadingProgressSet -> incoming.copy(
            position = incoming.position ?: previous.position,
            readState = incoming.readState ?: previous.readState,
            historyTouchedAt = incoming.historyTouchedAt ?: previous.historyTouchedAt,
            sessionId = if (incoming.position != null) incoming.sessionId else previous.sessionId,
        )

        previous is LibraryEntryPatch && incoming is LibraryEntryPatch -> incoming.copy(
            fields = previous.fields + incoming.fields,
            ensurePresent = previous.ensurePresent || incoming.ensurePresent,
        )

        previous is ChapterStatePatch && incoming is ChapterStatePatch -> incoming.copy(
            fields = previous.fields + incoming.fields,
            ensurePresent = previous.ensurePresent || incoming.ensurePresent,
        )

        previous is CategoryPatch && incoming is CategoryPatch -> incoming.copy(
            fields = previous.fields + incoming.fields,
            ensurePresent = previous.ensurePresent || incoming.ensurePresent,
        )

        previous is ExtensionRepositoryPatch && incoming is ExtensionRepositoryPatch -> incoming.copy(
            fields = previous.fields + incoming.fields,
            ensurePresent = previous.ensurePresent || incoming.ensurePresent,
        )

        previous is PublicationPatchV2 && incoming is PublicationPatchV2 -> incoming.copy(
            fields = previous.fields + incoming.fields,
            ensurePresent = previous.ensurePresent || incoming.ensurePresent,
        )

        previous is AcquisitionPatchV2 && incoming is AcquisitionPatchV2 -> incoming.copy(
            fields = previous.fields + incoming.fields,
            ensurePresent = previous.ensurePresent || incoming.ensurePresent,
        )

        previous is PublicationUnitPatchV2 && incoming is PublicationUnitPatchV2 -> incoming.copy(
            fields = previous.fields + incoming.fields,
            ensurePresent = previous.ensurePresent || incoming.ensurePresent,
        )

        previous is ContentManifestPatchV2 && incoming is ContentManifestPatchV2 -> incoming.copy(
            fields = previous.fields + incoming.fields,
            ensurePresent = previous.ensurePresent || incoming.ensurePresent,
        )

        previous is ContentAnnotationPatchV2 && incoming is ContentAnnotationPatchV2 -> incoming.copy(
            fields = previous.fields + incoming.fields,
            ensurePresent = previous.ensurePresent || incoming.ensurePresent,
        )

        previous is ContentReadingProgressSetV2 && incoming is ContentReadingProgressSetV2 -> incoming.copy(
            readState = incoming.readState ?: previous.readState,
            historyTouchedAtEpochMillis = incoming.historyTouchedAtEpochMillis
                ?: previous.historyTouchedAtEpochMillis,
        )

        previous is PortableSettingPatch && incoming is PortableSettingPatch ->
            incoming.copy(fields = previous.fields + incoming.fields)

        else -> incoming
    }

    private fun validateSealedEnvelope(request: SealEventRequest, envelope: EncryptedSyncEvent) {
        val header = envelope.header
        require(header.instanceId == request.context.instanceId) { "Sealer changed the instance id" }
        require(header.workspaceId == request.context.workspaceId) { "Sealer changed the workspace id" }
        require(header.deviceId == request.context.deviceId) { "Sealer changed the device id" }
        require(header.deviceId == request.event.hlc.deviceId) { "Event HLC belongs to another device" }
        require(header.deviceSeq == request.deviceSeq) { "Sealer changed the device sequence" }
        require(header.keyEpoch == request.keyEpoch) { "Sealer changed the key epoch" }
        require(header.schemaVersion == request.event.schemaVersion) { "Sealer changed the schema version" }
        require(header.protocolVersion == request.context.protocolVersion) { "Sealer changed the protocol version" }
        require(header.envelopeVersion == request.context.envelopeVersion) { "Sealer changed the envelope version" }
    }

    private inline fun <K, V> MutableMap<K, V>.replaceAllValues(transform: (V) -> V) {
        keys.toList().forEach { key -> this[key] = transform(getValue(key)) }
    }
}

private fun maxOfOrNull(first: HlcTimestamp?, second: HlcTimestamp?): HlcTimestamp? = when {
    first == null -> second
    second == null -> first
    first >= second -> first
    else -> second
}

suspend fun LocalSyncStore.commitLocalEvent(
    event: SyncEvent,
    nowMillis: Long,
    coalescingKey: String? = null,
): SyncDraft = transaction { applyLocalEvent(event, nowMillis, coalescingKey) }

suspend fun LocalSyncStore.sealDraft(
    draftId: String,
    context: EventSealContext,
    keyEpoch: Int,
    nowMillis: Long,
    sealer: SyncEventSealer,
): SealedOutboxEvent = transaction { sealDraftAtomically(draftId, context, keyEpoch, nowMillis, sealer) }

suspend fun LocalSyncStore.nextOutboxEvent(): SealedOutboxEvent? =
    readState().sealedOutbox.minByOrNull { it.key }?.value
