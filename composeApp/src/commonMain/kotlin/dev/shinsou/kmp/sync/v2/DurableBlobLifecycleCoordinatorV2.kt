package dev.shinsou.kmp.sync.v2

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public enum class BlobLifecycleSliceStatusV2 {
    NO_ACTION,
    WAITING_FOR_STABLE_CHECKPOINT,
    WAITING_FOR_LOCAL_HEAD,
    STALE_DURABLE_INTENT,
    STALE_ENVELOPE_RECOVERED,
    RIGHTS_DENIED,
    REFERENCE_BECAME_LIVE,
    TOMBSTONE_REVIVAL_CANCELLED,
    BODY_REUPLOAD_REQUIRED,
    ENVELOPE_REWRAP_DRAFTED,
    TOMBSTONE_GC_COMPLETED,
}

public data class BlobLifecycleSliceResultV2(
    val status: BlobLifecycleSliceStatusV2,
    val blobId: String? = null,
)

/**
 * Restart-safe low-priority control-plane worker for encrypted blob lifecycle operations.
 *
 * Every call handles at most one blob. Exact random/signed requests are saved before their first
 * network side effect; exact Worker responses are saved before the corresponding metadata draft.
 * A stable checkpoint is downloaded and cryptographically opened before an absent reference can
 * become a body tombstone. The Worker remains authoritative for active-device ack quorum and the
 * GC safety window.
 */
public class DurableBlobLifecycleCoordinatorV2(
    private val metadataApi: CloudflareSyncApi,
    private val bodyCoordinator: BlobLifecycleCoordinatorV2,
    private val syncCrypto: SyncCrypto,
    private val localStore: LocalSyncStore,
    private val journal: BlobLifecycleJournalV2,
    /** Live host-owned SYNC_BLOB lookup; every body-plane side effect re-evaluates it. */
    private val authorizeBlobSync: suspend (String) -> Boolean = { false },
    private val nowEpochMillis: () -> Long,
) {
    private val mutex = Mutex()

    public suspend fun drainSlice(
        session: SyncSession,
        capability: WorkspaceCapability,
    ): BlobLifecycleSliceResultV2 = mutex.withLock {
        val durable = journal.entries(session.instanceId, session.workspaceId)
        durable.asSequence()
            .filter { intent ->
                intent !is DurableBlobLifecycleIntentV2.ReferenceTombstone || !intent.completed
            }
            .sortedWith(compareBy({ it.attemptCount }, { it.blobId }))
            .firstOrNull()
            ?.let { original ->
                val intent = original.nextAttempt()
                journal.save(intent)
                if (!authorizeBlobSync(intent.blobId)) {
                    return@withLock BlobLifecycleSliceResultV2(
                        BlobLifecycleSliceStatusV2.RIGHTS_DENIED,
                        intent.blobId,
                    )
                }
                val bootstrap = bootstrap(session, capability)
                return@withLock when (intent) {
                    is DurableBlobLifecycleIntentV2.EnvelopeRewrap ->
                        resumeEnvelopeRewrap(session, capability, intent)
                    is DurableBlobLifecycleIntentV2.ReferenceTombstone ->
                        resumeTombstone(session, capability, bootstrap, intent)
                }
            }

        val bootstrap = bootstrap(session, capability)

        val local = localStore.readState()
        if (local.replica.throughWorkspaceSeq != bootstrap.headSeq) {
            return@withLock BlobLifecycleSliceResultV2(BlobLifecycleSliceStatusV2.WAITING_FOR_LOCAL_HEAD)
        }
        val activeStable = bootstrap.newestStableFor(session.activeKeyEpoch)
            ?: return@withLock BlobLifecycleSliceResultV2(
                BlobLifecycleSliceStatusV2.WAITING_FOR_STABLE_CHECKPOINT,
            )
        val completedTombstones = durable.asSequence()
            .filterIsInstance<DurableBlobLifecycleIntentV2.ReferenceTombstone>()
            .filter { tombstone ->
                tombstone.gcReceipt != null ||
                    tombstone.creationDisposition == BlobTombstoneDispositionV2.REUPLOAD_REQUIRED ||
                    tombstone.revivalResult?.disposition == BlobTombstoneDispositionV2.REUPLOAD_REQUIRED
            }
            .mapTo(hashSetOf(), DurableBlobLifecycleIntentV2.ReferenceTombstone::blobId)

        val rewrapCandidates = local.replica.blobReferences.values
            .asSequence()
            .filter { it.blobId !in completedTombstones }
            .filter { it.presence?.value == true && it.remoteManifest != null && it.dekEnvelopes.isNotEmpty() }
            .filter { session.activeKeyEpoch !in it.dekEnvelopes }
            .filterNot { local.hasPendingBlobMutation(it.blobId) }
            .sortedBy(SyncedBlobReferenceRecord::blobId)
            .toList()
        var firstDeniedBlobId: String? = null
        var rewrap: SyncedBlobReferenceRecord? = null
        for (candidate in rewrapCandidates) {
            if (authorizeBlobSync(candidate.blobId)) {
                rewrap = candidate
                break
            }
            if (firstDeniedBlobId == null) firstDeniedBlobId = candidate.blobId
        }
        if (rewrap != null) {
            val prepared = bodyCoordinator.prepareEnvelopeRewrap(session, rewrap, activeStable)
                ?: return@withLock BlobLifecycleSliceResultV2(BlobLifecycleSliceStatusV2.NO_ACTION)
            val intent = DurableBlobLifecycleIntentV2.EnvelopeRewrap(prepared)
            journal.save(intent)
            return@withLock resumeEnvelopeRewrap(session, capability, intent)
        }

        val locallyAbsentBlobIds = local.replica.blobReferences.values
            .asSequence()
            .filter { it.blobId !in completedTombstones }
            .filter { it.presence?.value == false && it.remoteManifest != null }
            .filterNot { local.hasPendingBlobMutation(it.blobId) }
            .mapTo(hashSetOf(), SyncedBlobReferenceRecord::blobId)
        if (locallyAbsentBlobIds.isEmpty()) {
            return@withLock BlobLifecycleSliceResultV2(
                if (firstDeniedBlobId == null) {
                    BlobLifecycleSliceStatusV2.NO_ACTION
                } else {
                    BlobLifecycleSliceStatusV2.RIGHTS_DENIED
                },
                firstDeniedBlobId,
            )
        }

        val verified = verifyStableCheckpoint(session, capability, activeStable)
        val absentCandidates = verified.state.blobReferences.values
            .asSequence()
            .filter { it.blobId in locallyAbsentBlobIds }
            .filter { it.blobId !in completedTombstones }
            .filter { record ->
                record.presence?.value == false &&
                    record.blob != null &&
                    record.remoteManifest != null &&
                    session.activeKeyEpoch in record.dekEnvelopes
            }
            .filter { checkpointRecord ->
                val current = local.replica.blobReferences[checkpointRecord.blobId]
                current?.presence?.value == false &&
                    current.remoteManifest?.value == checkpointRecord.remoteManifest?.value &&
                    !local.hasPendingBlobMutation(checkpointRecord.blobId)
            }
            .sortedBy(SyncedBlobReferenceRecord::blobId)
            .toList()
        var allowedAbsent: SyncedBlobReferenceRecord? = null
        for (candidate in absentCandidates) {
            if (authorizeBlobSync(candidate.blobId)) {
                allowedAbsent = candidate
                break
            }
            if (firstDeniedBlobId == null) firstDeniedBlobId = candidate.blobId
        }
        // Persist a provisional intent even when every current host grant denies. This retains
        // the exact removal boundary without making a Worker call and lets a later grant change
        // resume safely. Prefer an allowed candidate so one denial cannot starve other bodies.
        val absent = allowedAbsent ?: absentCandidates.firstOrNull()
            ?: return@withLock BlobLifecycleSliceResultV2(
                BlobLifecycleSliceStatusV2.NO_ACTION,
            )

        val handle = bodyCoordinator.prepareTombstone(
            session = session,
            reference = absent,
            referenceThroughWorkspaceSeq = activeStable.throughWorkspaceSeq,
        )
        val intent = DurableBlobLifecycleIntentV2.ReferenceTombstone(
            handle = handle,
            referenceCheckpoint = activeStable,
        )
        journal.save(intent)
        resumeTombstone(session, capability, bootstrap, intent)
    }

    private suspend fun bootstrap(
        session: SyncSession,
        capability: WorkspaceCapability,
    ): BootstrapResponse = metadataApi.bootstrap(session, capability).also { bootstrap ->
        require(bootstrap.activeKeyEpoch == session.activeKeyEpoch) {
            "Blob lifecycle session is behind the authenticated workspace epoch"
        }
        require(bootstrap.headSeq >= 0) { "Blob lifecycle bootstrap returned an invalid head" }
    }

    private suspend fun resumeEnvelopeRewrap(
        session: SyncSession,
        capability: WorkspaceCapability,
        initial: DurableBlobLifecycleIntentV2.EnvelopeRewrap,
    ): BlobLifecycleSliceResultV2 {
        val reference = localStore.readState().replica.blobReferences[initial.blobId]
        if (reference == null || !authorizeBlobSync(initial.blobId)) {
            return BlobLifecycleSliceResultV2(BlobLifecycleSliceStatusV2.RIGHTS_DENIED, initial.blobId)
        }
        require(reference.generation == initial.generation) {
            "Durable blob re-wrap belongs to another remote-body generation"
        }

        var durable = initial
        val preparedEpoch = durable.prepared.request.envelope.keyEpoch
        val isStale = preparedEpoch != session.activeKeyEpoch
        if (isStale && preparedEpoch > session.activeKeyEpoch) {
            return BlobLifecycleSliceResultV2(
                BlobLifecycleSliceStatusV2.STALE_DURABLE_INTENT,
                initial.blobId,
            )
        }
        if (isStale && durable.committedMutation == null && preparedEpoch in reference.dekEnvelopes) {
            journal.remove(durable)
            return BlobLifecycleSliceResultV2(
                BlobLifecycleSliceStatusV2.STALE_ENVELOPE_RECOVERED,
                durable.blobId,
            )
        }
        val mutation = durable.committedMutation ?: if (isStale) {
            if (!authorizeBlobSync(durable.blobId)) {
                return BlobLifecycleSliceResultV2(
                    BlobLifecycleSliceStatusV2.RIGHTS_DENIED,
                    durable.blobId,
                )
            }
            val recovered = bodyCoordinator.recoverEnvelope(
                session = session,
                capability = capability,
                blobId = durable.blobId,
                keyEpoch = preparedEpoch,
            )
            require(recovered.manifestId == durable.prepared.request.manifestId) {
                "Recovered blob envelope targets another manifest"
            }
            require(recovered.envelope.previousEnvelopeSha256Base64Url ==
                durable.prepared.request.envelope.previousEnvelopeSha256Base64Url) {
                "Recovered blob envelope is detached from the prepared chain"
            }
            BlobDekEnvelopeRewrappedV2(
                blobId = durable.blobId,
                manifestId = recovered.manifestId,
                envelope = recovered.envelope,
                checkpointEvidence = requireNotNull(recovered.checkpointEvidence) {
                    "Recovered re-wrap envelope omitted checkpoint evidence"
                },
                generation = durable.generation,
            )
        } else {
            if (!authorizeBlobSync(durable.blobId)) {
                return BlobLifecycleSliceResultV2(
                    BlobLifecycleSliceStatusV2.RIGHTS_DENIED,
                    durable.blobId,
                )
            }
            bodyCoordinator.commitEnvelopeRewrap(
                session,
                capability,
                durable.prepared,
            )
        }.also { committed ->
            durable = durable.copy(committedMutation = committed)
            // Persist the exact Worker winner before publishing it through the metadata plane.
            journal.save(durable)
        }
        val operationId = rewrapOperationId(mutation)
        localStore.transaction {
            if (operationId !in state().replica.appliedOpIds) {
                val wallMillis = nowEpochMillis()
                val hlc = nextLocalHlc(session.deviceId, wallMillis)
                applyLocalEvent(
                    event = SyncEvent(operationId, hlc, listOf(mutation)),
                    nowMillis = wallMillis,
                )
            }
        }
        journal.remove(durable)
        return BlobLifecycleSliceResultV2(
            if (isStale) {
                BlobLifecycleSliceStatusV2.STALE_ENVELOPE_RECOVERED
            } else {
                BlobLifecycleSliceStatusV2.ENVELOPE_REWRAP_DRAFTED
            },
            durable.blobId,
        )
    }

    private suspend fun resumeTombstone(
        session: SyncSession,
        capability: WorkspaceCapability,
        bootstrap: BootstrapResponse,
        initial: DurableBlobLifecycleIntentV2.ReferenceTombstone,
    ): BlobLifecycleSliceResultV2 {
        val local = localStore.readState()
        if (local.replica.throughWorkspaceSeq != bootstrap.headSeq) {
            return BlobLifecycleSliceResultV2(
                BlobLifecycleSliceStatusV2.WAITING_FOR_LOCAL_HEAD,
                initial.blobId,
            )
        }
        val current = local.replica.blobReferences[initial.blobId]
        if (!authorizeBlobSync(initial.blobId)) {
            return BlobLifecycleSliceResultV2(BlobLifecycleSliceStatusV2.RIGHTS_DENIED, initial.blobId)
        }
        if (current != null && current.generation != initial.generation) {
            return BlobLifecycleSliceResultV2(
                BlobLifecycleSliceStatusV2.STALE_DURABLE_INTENT,
                initial.blobId,
            )
        }
        val hasPendingMutation = local.hasPendingBlobMutation(initial.blobId)
        if (current?.presence?.value == true) {
            if (!initial.createdOnWorker) {
                journal.remove(initial)
                return BlobLifecycleSliceResultV2(
                    BlobLifecycleSliceStatusV2.REFERENCE_BECAME_LIVE,
                    initial.blobId,
                )
            }
            if (hasPendingMutation) {
                return BlobLifecycleSliceResultV2(
                    BlobLifecycleSliceStatusV2.WAITING_FOR_STABLE_CHECKPOINT,
                    initial.blobId,
                )
            }
            return resumeReferenceRevival(session, capability, bootstrap, initial, current)
        }
        if (current?.presence?.value != false || hasPendingMutation) {
            return BlobLifecycleSliceResultV2(
                BlobLifecycleSliceStatusV2.REFERENCE_BECAME_LIVE,
                initial.blobId,
            )
        }

        var durable = initial
        if (!durable.createdOnWorker) {
            if (!authorizeBlobSync(durable.blobId)) {
                return BlobLifecycleSliceResultV2(
                    BlobLifecycleSliceStatusV2.RIGHTS_DENIED,
                    durable.blobId,
                )
            }
            val creation = bodyCoordinator.createTombstone(session, capability, durable.handle)
            durable = durable.copy(
                handle = creation.handle,
                createdOnWorker = true,
                creationDisposition = creation.disposition,
            )
            // The Worker winner is durable before any acknowledgement is prepared or signed.
            journal.save(durable)
            when (creation.disposition) {
                BlobTombstoneDispositionV2.ACTIVE -> Unit
                BlobTombstoneDispositionV2.CANCELLED -> {
                    journal.remove(durable)
                    return BlobLifecycleSliceResultV2(
                        BlobLifecycleSliceStatusV2.REFERENCE_BECAME_LIVE,
                        durable.blobId,
                    )
                }
                BlobTombstoneDispositionV2.REUPLOAD_REQUIRED ->
                    return BlobLifecycleSliceResultV2(
                        BlobLifecycleSliceStatusV2.BODY_REUPLOAD_REQUIRED,
                        durable.blobId,
                    )
            }
        }

        if (durable.acknowledgement == null) {
            val checkpoint = bootstrap.retainedStableCheckpoints
                .firstOrNull { descriptor ->
                    descriptor.keyEpoch == session.activeKeyEpoch &&
                        descriptor.throughWorkspaceSeq >= durable.handle.referenceThroughWorkspaceSeq
                }
                ?: return BlobLifecycleSliceResultV2(
                    BlobLifecycleSliceStatusV2.WAITING_FOR_STABLE_CHECKPOINT,
                    durable.blobId,
                )
            val verified = verifyStableCheckpoint(session, capability, checkpoint)
            requireCheckpointContainsAbsentReference(verified, durable)
            val acknowledgement = bodyCoordinator.prepareTombstoneAcknowledgement(
                session,
                durable.handle,
                checkpoint,
            )
            durable = durable.copy(acknowledgement = acknowledgement)
            // The exact checkpoint/signature is durable before the first ack request.
            journal.save(durable)
        }

        if (!durable.acknowledgementCommitted) {
            if (!authorizeBlobSync(durable.blobId)) {
                return BlobLifecycleSliceResultV2(
                    BlobLifecycleSliceStatusV2.RIGHTS_DENIED,
                    durable.blobId,
                )
            }
            bodyCoordinator.commitTombstoneAcknowledgement(
                session,
                capability,
                durable.handle,
                requireNotNull(durable.acknowledgement),
            )
            durable = durable.copy(acknowledgementCommitted = true)
            journal.save(durable)
        }

        if (durable.gcReceipt == null) {
            if (!authorizeBlobSync(durable.blobId)) {
                return BlobLifecycleSliceResultV2(
                    BlobLifecycleSliceStatusV2.RIGHTS_DENIED,
                    durable.blobId,
                )
            }
            val receipt = bodyCoordinator.garbageCollect(session, capability, durable.handle)
            durable = durable.copy(gcReceipt = receipt)
            // Completed receipts remain durable, preventing a deleted body from receiving a new
            // tombstone identity after restart while its metadata tombstone is retained.
            journal.save(durable)
        }
        return BlobLifecycleSliceResultV2(
            BlobLifecycleSliceStatusV2.TOMBSTONE_GC_COMPLETED,
            durable.blobId,
        )
    }

    private suspend fun resumeReferenceRevival(
        session: SyncSession,
        capability: WorkspaceCapability,
        bootstrap: BootstrapResponse,
        initial: DurableBlobLifecycleIntentV2.ReferenceTombstone,
        current: SyncedBlobReferenceRecord,
    ): BlobLifecycleSliceResultV2 {
        var durable = initial
        require(durable.creationDisposition == BlobTombstoneDispositionV2.ACTIVE) {
            "Only an active Worker tombstone can be revived"
        }
        if (durable.revival == null) {
            val checkpoint = bootstrap.retainedStableCheckpoints
                .asSequence()
                .filter { descriptor ->
                    descriptor.keyEpoch == session.activeKeyEpoch &&
                        descriptor.throughWorkspaceSeq > durable.handle.referenceThroughWorkspaceSeq
                }
                .maxByOrNull(RetainedCheckpointDescriptor::throughWorkspaceSeq)
                ?: return BlobLifecycleSliceResultV2(
                    BlobLifecycleSliceStatusV2.WAITING_FOR_STABLE_CHECKPOINT,
                    durable.blobId,
                )
            val verified = verifyStableCheckpoint(session, capability, checkpoint)
            requireCheckpointContainsLiveReference(verified, durable)
            val revival = bodyCoordinator.prepareReferenceRevival(
                session = session,
                tombstone = durable.handle,
                reference = current,
                stableCheckpoint = checkpoint,
            )
            durable = durable.copy(revival = revival)
            journal.save(durable)
        }
        if (durable.revivalResult == null) {
            if (!authorizeBlobSync(durable.blobId)) {
                return BlobLifecycleSliceResultV2(
                    BlobLifecycleSliceStatusV2.RIGHTS_DENIED,
                    durable.blobId,
                )
            }
            val result = bodyCoordinator.commitReferenceRevival(
                session = session,
                capability = capability,
                tombstone = durable.handle,
                request = requireNotNull(durable.revival),
            )
            durable = durable.copy(revivalResult = result)
            journal.save(durable)
        }
        return when (requireNotNull(durable.revivalResult).disposition) {
            BlobTombstoneDispositionV2.CANCELLED -> {
                journal.remove(durable)
                BlobLifecycleSliceResultV2(
                    BlobLifecycleSliceStatusV2.TOMBSTONE_REVIVAL_CANCELLED,
                    durable.blobId,
                )
            }
            BlobTombstoneDispositionV2.REUPLOAD_REQUIRED -> BlobLifecycleSliceResultV2(
                BlobLifecycleSliceStatusV2.BODY_REUPLOAD_REQUIRED,
                durable.blobId,
            )
            BlobTombstoneDispositionV2.ACTIVE -> error("A revival cannot leave its tombstone active")
        }
    }

    private suspend fun verifyStableCheckpoint(
        session: SyncSession,
        capability: WorkspaceCapability,
        descriptor: RetainedCheckpointDescriptor,
    ): VerifiedSyncCheckpoint {
        require(descriptor.keyEpoch == session.activeKeyEpoch) {
            "Blob lifecycle checkpoint does not use the active workspace epoch"
        }
        val encrypted = metadataApi.downloadCheckpoint(session, capability, descriptor)
        val verified = syncCrypto.openAndVerifyCheckpoint(session, encrypted, descriptor)
        require(
            verified.header.instanceId == session.instanceId &&
                verified.header.workspaceId == session.workspaceId &&
                verified.header.checkpointId == descriptor.checkpointId &&
                verified.header.throughWorkspaceSeq == descriptor.throughWorkspaceSeq &&
                verified.header.ciphertextSha256Base64Url == descriptor.ciphertextSha256Base64Url,
        ) { "Verified blob lifecycle checkpoint does not match bootstrap evidence" }
        return verified
    }

    private fun requireCheckpointContainsAbsentReference(
        checkpoint: VerifiedSyncCheckpoint,
        durable: DurableBlobLifecycleIntentV2.ReferenceTombstone,
    ) {
        val reference = requireNotNull(checkpoint.state.blobReferences[durable.blobId]) {
            "Stable checkpoint does not contain the blob reference tombstone"
        }
        require(reference.presence?.value == false) {
            "Stable checkpoint still contains a live blob reference"
        }
        require(reference.remoteManifest?.value?.manifestId == durable.handle.manifestId) {
            "Stable checkpoint blob manifest differs from the durable tombstone"
        }
        require(reference.generation == durable.generation) {
            "Stable checkpoint blob generation differs from the durable tombstone"
        }
    }

    private fun requireCheckpointContainsLiveReference(
        checkpoint: VerifiedSyncCheckpoint,
        durable: DurableBlobLifecycleIntentV2.ReferenceTombstone,
    ) {
        val reference = requireNotNull(checkpoint.state.blobReferences[durable.blobId]) {
            "Stable checkpoint does not contain the revived blob reference"
        }
        require(reference.presence?.value == true) {
            "Stable checkpoint does not contain a live blob reference"
        }
        require(reference.remoteManifest?.value?.manifestId == durable.handle.manifestId) {
            "Stable checkpoint revived a different blob manifest"
        }
        require(reference.generation == durable.generation) {
            "Stable checkpoint revived a different blob generation"
        }
    }

    private fun rewrapOperationId(mutation: BlobDekEnvelopeRewrappedV2): String =
        "blob-rewrap-v2:${mutation.blobId}:${mutation.envelope.keyEpoch}:" +
            mutation.envelope.envelopeSha256Base64Url
}

private fun BootstrapResponse.newestStableFor(keyEpoch: Int): RetainedCheckpointDescriptor? {
    val matching = retainedStableCheckpoints.filter { it.keyEpoch == keyEpoch }
    val highest = matching.maxOfOrNull(RetainedCheckpointDescriptor::throughWorkspaceSeq) ?: return null
    return matching.first { it.throughWorkspaceSeq == highest }
}

private fun LocalSyncStoreState.hasPendingBlobMutation(blobId: String): Boolean =
    (drafts.values.asSequence().map(SyncDraft::event) +
        sealedOutbox.values.asSequence().map(SealedOutboxEvent::logicalEvent))
        .flatMap { it.mutations.asSequence() }
        .any { mutation ->
            when (mutation) {
                is BlobReferenceCommitV2 -> mutation.blob.blobId == blobId
                is BlobReferenceReincarnationCommitV2 -> mutation.blob.blobId == blobId
                is BlobDekEnvelopeRewrappedV2 -> mutation.blobId == blobId
                is BlobReferencePresenceSetV2 -> mutation.blobId == blobId
                else -> false
            }
        }
