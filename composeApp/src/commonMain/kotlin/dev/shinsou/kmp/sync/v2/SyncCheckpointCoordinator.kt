package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.network.SyncApiException
import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.trust.canonicalSyncJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class CheckpointCoordinatorOutcome {
    NO_ACTION,
    PROPOSED,
    VALIDATED,
    REJECTED,
    DEFERRED,
}

/**
 * Creates immutable checkpoints and independently validates candidates against a fixed event
 * watermark. It never installs a candidate into the live replica while validating it.
 */
class SyncCheckpointCoordinator(
    private val api: CloudflareSyncApi,
    private val crypto: SyncCrypto,
    private val codec: SyncEventCodec,
    private val localStore: LocalSyncStore,
    private val checkpointIntervalEvents: Long = 500,
    private val catchUpPageSize: Int = 200,
    private val keyRetentionCoordinator: SyncKeyRetentionCoordinator? = null,
) {
    init {
        require(checkpointIntervalEvents > 0 && catchUpPageSize > 0)
    }

    suspend fun coordinate(
        session: SyncSession,
        capability: WorkspaceCapability,
    ): CheckpointCoordinatorOutcome = try {
        val bootstrap = api.bootstrap(session, capability)
        requireBootstrapIsMonotonic(bootstrap)
        keyRetentionCoordinator?.reconcile(session, bootstrap)
        val candidate = bootstrap.candidateCheckpoint
        if (candidate != null) {
            validateCandidate(session, capability, bootstrap, candidate)
        } else {
            proposeIfNeeded(session, capability, bootstrap)
        }
    } catch (failure: SyncApiException) {
        if (failure.errorCode == "checkpoint_rotation_required") {
            throw KeyRotationRequiredException()
        }
        throw failure
    }

    private suspend fun validateCandidate(
        session: SyncSession,
        capability: WorkspaceCapability,
        bootstrap: BootstrapResponse,
        candidate: CheckpointCandidateDescriptor,
    ): CheckpointCoordinatorOutcome {
        if (candidate.throughWorkspaceSeq > bootstrap.headSeq || candidate.keyEpoch != bootstrap.activeKeyEpoch) {
            throw SyncInvariantViolation("Checkpoint candidate exceeds the authenticated workspace boundary")
        }
        // Fetch and authenticate the event tail outside checkpoint-failure handling. A transport,
        // key-store, or event-verification failure is not evidence that this candidate is bad. The
        // completed tail also gives an authoritative replay count for a signed invalid ack when a
        // downloaded candidate (or its retained base) is structurally/cryptographically corrupt.
        val tail = downloadTail(
            session = session,
            capability = capability,
            afterExclusive = candidate.previousStableThroughWorkspaceSeq,
            untilInclusive = candidate.throughWorkspaceSeq,
        )
        val valid = try {
            val previousDescriptor = resolvePreviousStable(bootstrap, candidate)
            val previous = previousDescriptor?.let { descriptor ->
                crypto.openAndVerifyCheckpoint(
                    session,
                    api.downloadCheckpoint(session, capability, descriptor),
                    descriptor,
                )
            }
            if ((previous?.state?.throughWorkspaceSeq ?: 0L) != candidate.previousStableThroughWorkspaceSeq) {
                throw SyncInvariantViolation("Candidate previous-stable replay boundary is inconsistent")
            }
            val descriptor = candidate.asDownloadDescriptor()
            val encrypted = api.downloadCheckpoint(session, capability, descriptor)
            val verified = crypto.openAndVerifyCheckpoint(session, encrypted, descriptor)
            if (candidate.isGenesisRoot()) {
                verifyLocalGenesisCandidate(session, bootstrap, candidate, verified, tail)
            } else {
                CheckpointInstaller.verifyCandidateByReplay(previous, verified, tail, codec)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RemoteCheckpointVerificationException) {
            false
        } catch (_: SyncInvariantViolation) {
            false
        }
        val acknowledgement = signedAcknowledgement(session, candidate, tail.size, valid)
        val result = try {
            api.acknowledgeCheckpoint(session, capability, acknowledgement)
        } catch (failure: SyncApiException) {
            if (failure.errorCode in DEFERRED_ACK_ERRORS) return CheckpointCoordinatorOutcome.DEFERRED
            throw failure
        }
        return when (result.status) {
            RemoteCheckpointStatus.STABLE -> CheckpointCoordinatorOutcome.VALIDATED
            RemoteCheckpointStatus.REJECTED -> CheckpointCoordinatorOutcome.REJECTED
            RemoteCheckpointStatus.CANDIDATE -> CheckpointCoordinatorOutcome.DEFERRED
        }
    }

    /**
     * Checkpoint zero is the sole state that has no preceding event history. It may therefore be
     * validated against the durable local seed, but only by the device that atomically created
     * that seed. Every non-zero or chained candidate stays on the independent replay path above.
     */
    private suspend fun verifyLocalGenesisCandidate(
        session: SyncSession,
        bootstrap: BootstrapResponse,
        candidate: CheckpointCandidateDescriptor,
        verified: VerifiedSyncCheckpoint,
        tail: List<CommittedSyncEvent>,
    ) {
        val local = localStore.readState()
        val seed = local.genesisCheckpointSeed
            ?: throw SyncInvariantViolation("Checkpoint zero has no durable local genesis seed")
        if (bootstrap.headSeq != 0L || bootstrap.retainedStableCheckpoints.isNotEmpty() || tail.isNotEmpty() ||
            local.replica.throughWorkspaceSeq != 0L || local.drafts.isNotEmpty() || local.sealedOutbox.isNotEmpty()
        ) {
            throw SyncInvariantViolation("Genesis checkpoint boundary is not pristine")
        }
        if (seed.deviceId != session.deviceId || candidate.uploaderDeviceId != seed.deviceId ||
            verified.header.deviceId != candidate.uploaderDeviceId || candidate.keyEpoch != bootstrap.activeKeyEpoch
        ) {
            throw SyncInvariantViolation("Genesis checkpoint is not bound to its local seed device")
        }
        val expected = local.replica.copy(
            keyEpoch = bootstrap.activeKeyEpoch,
            throughWorkspaceSeq = 0,
            previousStableCheckpointHash = null,
        ).normalized()
        if (codec.canonicalCheckpointState(expected) != verified.canonicalState) {
            throw SyncInvariantViolation("Genesis checkpoint differs from the durable local snapshot seed")
        }
    }

    private suspend fun proposeIfNeeded(
        session: SyncSession,
        capability: WorkspaceCapability,
        bootstrap: BootstrapResponse,
    ): CheckpointCoordinatorOutcome {
        val local = localStore.readState()
        if (local.replica.throughWorkspaceSeq != bootstrap.headSeq) {
            throw SyncInvariantViolation("Cannot checkpoint a replica that is not at the fixed workspace head")
        }
        if (local.drafts.isNotEmpty() || local.sealedOutbox.isNotEmpty()) {
            return CheckpointCoordinatorOutcome.DEFERRED
        }
        val newestStable = newestRetainedStable(bootstrap.retainedStableCheckpoints)
        val shouldCreate = newestStable == null ||
            newestStable.keyEpoch != bootstrap.activeKeyEpoch ||
            bootstrap.headSeq - newestStable.throughWorkspaceSeq >= checkpointIntervalEvents
        if (!shouldCreate) return CheckpointCoordinatorOutcome.NO_ACTION
        if (session.activeKeyEpoch != bootstrap.activeKeyEpoch || local.activeKeyEpoch != bootstrap.activeKeyEpoch) {
            throw SyncInvariantViolation("Cannot checkpoint without the active workspace epoch")
        }

        val checkpointId = crypto.generateCheckpointId()
        val previousHash = newestStable?.ciphertextSha256Base64Url
        val state = local.replica.copy(
            keyEpoch = bootstrap.activeKeyEpoch,
            previousStableCheckpointHash = previousHash,
        ).normalized()
        val encrypted = crypto.sealCheckpoint(session, checkpointId, state, previousHash)
        val ciphertextSize = decodeBase64Url(encrypted.ciphertextBase64Url).size
        val lease = try {
            api.createCheckpointLease(
                session = session,
                capability = capability,
                checkpointId = checkpointId,
                ciphertextSha256Base64Url = encrypted.header.ciphertextSha256Base64Url,
                expectedByteSize = ciphertextSize,
                throughWorkspaceSeq = bootstrap.headSeq,
            )
        } catch (failure: SyncApiException) {
            if (failure.errorCode in DEFERRED_PROPOSAL_ERRORS) return CheckpointCoordinatorOutcome.DEFERRED
            throw failure
        }
        api.uploadCheckpoint(session, capability, lease, encrypted)
        api.commitCheckpoint(session, capability, lease)
        return CheckpointCoordinatorOutcome.PROPOSED
    }

    private fun resolvePreviousStable(
        bootstrap: BootstrapResponse,
        candidate: CheckpointCandidateDescriptor,
    ): RetainedCheckpointDescriptor? {
        if (candidate.previousStableCheckpointId == null) return null
        return bootstrap.retainedStableCheckpoints.singleOrNull { stable ->
            stable.checkpointId == candidate.previousStableCheckpointId &&
                stable.ciphertextSha256Base64Url == candidate.previousStableCiphertextSha256Base64Url &&
                stable.throughWorkspaceSeq == candidate.previousStableThroughWorkspaceSeq
        } ?: throw SyncInvariantViolation("Candidate references an unavailable previous stable checkpoint")
    }

    /**
     * Bootstrap preserves the server's promotion order (newest first). The sequence remains the
     * primary boundary, but checkpoint UUIDs are random and must never be used to break a tie:
     * rotation can legitimately promote multiple stable objects at the same watermark.
     */
    private fun newestRetainedStable(
        retained: List<RetainedCheckpointDescriptor>,
    ): RetainedCheckpointDescriptor? {
        val highestSequence = retained.maxOfOrNull { it.throughWorkspaceSeq } ?: return null
        return retained.first { it.throughWorkspaceSeq == highestSequence }
    }

    private suspend fun downloadTail(
        session: SyncSession,
        capability: WorkspaceCapability,
        afterExclusive: Long,
        untilInclusive: Long,
    ): List<CommittedSyncEvent> {
        if (afterExclusive == untilInclusive) return emptyList()
        var cursor = afterExclusive
        val events = mutableListOf<CommittedSyncEvent>()
        while (cursor < untilInclusive) {
            val page = api.catchUp(session, capability, cursor, untilInclusive, catchUpPageSize)
            if (page.fromExclusive != cursor || page.untilInclusive != untilInclusive || page.headSeq < untilInclusive) {
                throw SyncInvariantViolation("Checkpoint replay watermark changed")
            }
            var expected = cursor + 1
            page.events.forEach { remote ->
                if (remote.workspaceSeq != expected) throw SyncSequenceGapException(expected, remote.workspaceSeq)
                events += CommittedSyncEvent(
                    workspaceSeq = remote.workspaceSeq,
                    event = crypto.openAndVerifyEvent(session, remote).event,
                )
                expected += 1
            }
            val next = if (page.events.isEmpty()) cursor else expected - 1
            if (page.nextCursor != next || page.nextCursor <= cursor || page.nextCursor > untilInclusive) {
                throw SyncInvariantViolation("Checkpoint replay page has an invalid cursor")
            }
            if (page.hasMore != (page.nextCursor < untilInclusive)) {
                throw SyncInvariantViolation("Checkpoint replay pagination is inconsistent")
            }
            cursor = page.nextCursor
        }
        return events
    }

    private suspend fun signedAcknowledgement(
        session: SyncSession,
        candidate: CheckpointCandidateDescriptor,
        replayedEventCount: Int,
        valid: Boolean,
    ): CheckpointReplayAcknowledgement {
        val manifest = JsonObject(
            mapOf(
                "workspaceId" to JsonPrimitive(session.workspaceId),
                "checkpointId" to JsonPrimitive(candidate.checkpointId),
                "ciphertextSha256" to JsonPrimitive(candidate.ciphertextSha256Base64Url),
                "keyEpoch" to JsonPrimitive(candidate.keyEpoch),
                "validationVersion" to JsonPrimitive(VALIDATION_VERSION),
                "replayFromSeq" to JsonPrimitive(candidate.previousStableThroughWorkspaceSeq),
                "replayedThroughSeq" to JsonPrimitive(candidate.throughWorkspaceSeq),
                "replayedEventCount" to JsonPrimitive(replayedEventCount),
                "previousStableCheckpointId" to (
                    candidate.previousStableCheckpointId?.let(::JsonPrimitive) ?: JsonNull
                    ),
                "previousStableSha256" to (
                    candidate.previousStableCiphertextSha256Base64Url?.let(::JsonPrimitive) ?: JsonNull
                    ),
                "valid" to JsonPrimitive(valid),
            ),
        )
        val message = BinaryData.copyOf(
            CHECKPOINT_ACK_DOMAIN + canonicalSyncJson(manifest).encodeToByteArray(),
        )
        val signature = encodeBase64Url(crypto.signDeviceMessage(message).copyBytes())
        return CheckpointReplayAcknowledgement(
            checkpointId = candidate.checkpointId,
            ciphertextSha256Base64Url = candidate.ciphertextSha256Base64Url,
            validationVersion = VALIDATION_VERSION,
            replayFromSeq = candidate.previousStableThroughWorkspaceSeq,
            replayedThroughSeq = candidate.throughWorkspaceSeq,
            replayedEventCount = replayedEventCount,
            previousStableCheckpointId = candidate.previousStableCheckpointId,
            previousStableCiphertextSha256Base64Url = candidate.previousStableCiphertextSha256Base64Url,
            valid = valid,
            signatureBase64Url = signature,
        )
    }

    private suspend fun requireBootstrapIsMonotonic(bootstrap: BootstrapResponse) {
        val localCursor = localStore.readState().replica.throughWorkspaceSeq
        if (bootstrap.headSeq < localCursor || bootstrap.activeKeyEpoch <= 0) {
            throw SyncInvariantViolation("Checkpoint bootstrap attempted to roll back local state")
        }
        if (bootstrap.retainedStableCheckpoints.any { it.throughWorkspaceSeq > bootstrap.headSeq }) {
            throw SyncInvariantViolation("Retained checkpoint exceeds the workspace head")
        }
    }

    private companion object {
        const val VALIDATION_VERSION = 1
        val CHECKPOINT_ACK_DOMAIN = "shinsou:checkpoint-ack:v1\u0000".encodeToByteArray()
        val DEFERRED_ACK_ERRORS = setOf(
            "independent_validator_required",
            "checkpoint_self_ack_grace",
            "checkpoint_candidate_not_found",
            "checkpoint_promotion_conflict",
        )
        val DEFERRED_PROPOSAL_ERRORS = setOf(
            "checkpoint_watermark_changed",
            "checkpoint_candidate_pending",
            "checkpoint_lease_unavailable",
        )
    }
}

private fun CheckpointCandidateDescriptor.isGenesisRoot(): Boolean =
    throughWorkspaceSeq == 0L &&
        previousStableCheckpointId == null &&
        previousStableThroughWorkspaceSeq == 0L &&
        previousStableCiphertextSha256Base64Url == null
