package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.trust.canonicalSyncJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Durable preparation for a DEK re-wrap.
 *
 * The envelope nonce is random, so callers must persist this value before calling
 * [BlobLifecycleCoordinatorV2.commitEnvelopeRewrap]. A retry then submits the exact same
 * envelope instead of creating a conflicting envelope for the same key epoch.
 */
@Serializable
public data class PreparedBlobEnvelopeRewrapV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val instanceId: String,
    val workspaceId: String,
    val blobId: String,
    val request: BlobEnvelopeRewrapRequestV2,
    val generation: Long = 1,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION) {
            "Unsupported prepared blob re-wrap version"
        }
        requireCanonicalContentUuid(instanceId, "Blob re-wrap instance id")
        requireCanonicalContentUuid(workspaceId, "Blob re-wrap workspace id")
        requireCanonicalContentUuid(blobId, "Prepared blob re-wrap id")
        require(generation > 0) { "Prepared blob re-wrap generation must be positive" }
        require(request.envelope.blobId == blobId) { "Prepared blob re-wrap identity mismatch" }
    }
}

/** Durable identity used to replay tombstone creation, acknowledgement, and server-side GC. */
@Serializable
public data class BlobTombstoneHandleV2(
    val protocolVersion: Int = BLOB_BODY_PROTOCOL_VERSION,
    val schemaVersion: Int = BLOB_BODY_SCHEMA_VERSION,
    val instanceId: String,
    val workspaceId: String,
    val tombstoneId: String,
    val blobId: String,
    val manifestId: String,
    val referenceThroughWorkspaceSeq: Long,
    val requestedCreatedAtEpochMillis: Long,
    /** Null only while this is a device-local provisional handle. */
    val executeAfterEpochMillis: Long? = null,
    /** Local authority boundary; legacy Worker responses omit it and are rebound by the caller. */
    val generation: Long = 1,
) {
    init {
        require(protocolVersion == BLOB_BODY_PROTOCOL_VERSION && schemaVersion == BLOB_BODY_SCHEMA_VERSION) {
            "Unsupported blob tombstone handle version"
        }
        requireCanonicalContentUuid(instanceId, "Blob tombstone instance id")
        requireCanonicalContentUuid(workspaceId, "Blob tombstone workspace id")
        requireCanonicalContentUuid(tombstoneId, "Blob tombstone id")
        requireCanonicalContentUuid(blobId, "Blob tombstone blob id")
        requireCanonicalContentUuid(manifestId, "Blob tombstone manifest id")
        require(generation > 0) { "Blob tombstone generation must be positive" }
        require(referenceThroughWorkspaceSeq in 1..MAX_BLOB_LIFECYCLE_SAFE_INTEGER) {
            "A blob tombstone must reference a committed metadata event"
        }
        require(requestedCreatedAtEpochMillis in 0..MAX_BLOB_LIFECYCLE_SAFE_INTEGER) {
            "Blob tombstone time is outside the Worker integer range"
        }
        executeAfterEpochMillis?.let { executeAfter ->
            require(executeAfter in 0..MAX_BLOB_LIFECYCLE_SAFE_INTEGER) {
                "Blob tombstone safety-window boundary is outside the Worker integer range"
            }
        }
    }

    internal fun request(): BlobReferenceTombstoneRequestV2 = BlobReferenceTombstoneRequestV2(
        tombstoneId = tombstoneId,
        blobId = blobId,
        manifestId = manifestId,
        throughWorkspaceSeq = referenceThroughWorkspaceSeq,
        createdAtEpochMillis = requestedCreatedAtEpochMillis,
    )
}

/**
 * Coordinates the small, control-plane portion of the encrypted blob lifecycle.
 *
 * This class deliberately handles one blob per call. Scheduling remains a low-priority concern,
 * while the returned prepared values can be made durable before any network request.
 */
public class BlobLifecycleCoordinatorV2(
    private val bodyApi: CloudflareBlobBodyApiV2,
    private val blobCrypto: BlobBodyCryptoV2,
    private val syncCrypto: SyncCrypto,
    private val nowEpochMillis: () -> Long,
    private val newTombstoneId: suspend () -> String = { syncCrypto.generateCheckpointId() },
) {
    /** Returns null when this live blob already has an envelope for the active key epoch. */
    public suspend fun prepareEnvelopeRewrap(
        session: SyncSession,
        reference: SyncedBlobReferenceRecord,
        stableCheckpoint: RetainedCheckpointDescriptor,
    ): PreparedBlobEnvelopeRewrapV2? {
        requireReadySession(session)
        require(reference.presence?.value == true) { "Only a live blob can have its DEK re-wrapped" }
        val manifest = requireNotNull(reference.remoteManifest?.value) {
            "A blob DEK cannot be re-wrapped before its remote manifest is committed"
        }
        require(manifest.blobId == reference.blobId)
        val envelopes = reference.dekEnvelopes.entries.sortedBy { it.key }
        require(envelopes.isNotEmpty()) { "A remotely available blob has no retained DEK envelope" }
        require(envelopes.all { (epoch, register) ->
            epoch == register.value.keyEpoch && register.value.blobId == reference.blobId
        }) { "Blob DEK envelope history is inconsistent" }
        require(envelopes.last().key <= session.activeKeyEpoch) {
            "Blob DEK envelope is ahead of the active workspace epoch"
        }
        if (session.activeKeyEpoch in reference.dekEnvelopes) return null

        val previous = envelopes.last().value.value
        require(previous.keyEpoch < session.activeKeyEpoch) {
            "A blob DEK re-wrap must advance to the active workspace epoch"
        }
        val evidence = stableCheckpoint.toBlobEvidence(session)
        val envelope = blobCrypto.rewrapDek(session, previous, session.activeKeyEpoch)
        require(
            envelope.blobId == reference.blobId &&
                envelope.keyEpoch == session.activeKeyEpoch &&
                envelope.previousEnvelopeSha256Base64Url == previous.envelopeSha256Base64Url,
        ) { "Blob crypto returned an invalid DEK envelope chain" }
        return PreparedBlobEnvelopeRewrapV2(
            instanceId = session.instanceId,
            workspaceId = session.workspaceId,
            blobId = reference.blobId,
            request = BlobEnvelopeRewrapRequestV2(
                manifestId = manifest.manifestId,
                envelope = envelope,
                checkpointEvidence = evidence,
            ),
            generation = reference.generation,
        )
    }

    /** Commits an exactly prepared envelope and returns the matching metadata-plane mutation. */
    public suspend fun commitEnvelopeRewrap(
        session: SyncSession,
        capability: WorkspaceCapability,
        prepared: PreparedBlobEnvelopeRewrapV2,
    ): BlobDekEnvelopeRewrappedV2 {
        requireBound(session, capability)
        prepared.requireBoundTo(session)
        require(prepared.request.envelope.keyEpoch == session.activeKeyEpoch) {
            "Prepared blob re-wrap targets a stale workspace epoch"
        }
        val committed = bodyApi.rewrapEnvelope(
            session = session,
            capability = capability,
            blobId = prepared.blobId,
            request = prepared.request,
        )
        require(committed == prepared.request.envelope) {
            "Worker returned a different blob DEK envelope"
        }
        return BlobDekEnvelopeRewrappedV2(
            blobId = prepared.blobId,
            manifestId = prepared.request.manifestId,
            envelope = committed,
            checkpointEvidence = prepared.request.checkpointEvidence,
            generation = prepared.generation,
        )
    }

    /** Fetches the exact Worker winner for a response-lost re-wrap from an older key epoch. */
    public suspend fun recoverEnvelope(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
        keyEpoch: Int,
    ): BlobEnvelopeRecoveryV2 {
        requireBound(session, capability)
        requireCanonicalContentUuid(blobId, "Recovered blob envelope id")
        require(keyEpoch in 1 until session.activeKeyEpoch) {
            "Only an older blob envelope can use the recovery path"
        }
        return bodyApi.recoverEnvelope(session, capability, blobId, keyEpoch).also { recovered ->
            require(recovered.envelope.blobId == blobId && recovered.envelope.keyEpoch == keyEpoch) {
                "Worker returned a different recovered blob envelope"
            }
            require(recovered.envelope.previousEnvelopeSha256Base64Url != null &&
                recovered.checkpointEvidence != null) {
                "Worker returned an initial envelope instead of a recovered re-wrap"
            }
        }
    }

    /**
     * Creates a durable tombstone identity after the metadata reference is absent and committed.
     * Persist the returned handle before [createTombstone] so request replay keeps the same ID.
     */
    public suspend fun prepareTombstone(
        session: SyncSession,
        reference: SyncedBlobReferenceRecord,
        referenceThroughWorkspaceSeq: Long,
    ): BlobTombstoneHandleV2 {
        requireReadySession(session)
        require(reference.presence?.value == false) {
            "Blob body tombstoning requires an absent metadata reference"
        }
        val manifest = requireNotNull(reference.remoteManifest?.value) {
            "A local-only blob has no remote body to tombstone"
        }
        require(session.activeKeyEpoch in reference.dekEnvelopes) {
            "Blob DEK must be re-wrapped to the active epoch before tombstoning"
        }
        return BlobTombstoneHandleV2(
            instanceId = session.instanceId,
            workspaceId = session.workspaceId,
            tombstoneId = newTombstoneId(),
            blobId = reference.blobId,
            manifestId = manifest.manifestId,
            referenceThroughWorkspaceSeq = referenceThroughWorkspaceSeq,
            requestedCreatedAtEpochMillis = nowEpochMillis(),
            generation = reference.generation,
        )
    }

    /**
     * Submits a provisional identity and returns the Worker's canonical winner for this exact
     * removal boundary. The caller must persist [BlobTombstoneCreationResultV2.handle] before it
     * signs an acknowledgement; another device may have won with a different random id.
     */
    public suspend fun createTombstone(
        session: SyncSession,
        capability: WorkspaceCapability,
        tombstone: BlobTombstoneHandleV2,
    ): BlobTombstoneCreationResultV2 {
        requireBound(session, capability)
        tombstone.requireBoundTo(session)
        require(tombstone.executeAfterEpochMillis == null) {
            "A canonical blob tombstone must be replayed through its original request"
        }
        return bodyApi.createTombstone(session, capability, tombstone.request()).let { result ->
            val canonical = result.handle.copy(generation = tombstone.generation)
            canonical.requireBoundTo(session)
            require(canonical.hasSameRemovalBoundary(tombstone)) {
                "Worker returned a tombstone for another removal boundary"
            }
            require(canonical.executeAfterEpochMillis != null) {
                "Worker returned a provisional tombstone handle"
            }
            result.copy(handle = canonical)
        }
    }

    /** Prepares exact signed proof that a newer stable checkpoint contains presence=true. */
    public suspend fun prepareReferenceRevival(
        session: SyncSession,
        tombstone: BlobTombstoneHandleV2,
        reference: SyncedBlobReferenceRecord,
        stableCheckpoint: RetainedCheckpointDescriptor,
    ): BlobReferenceRevivalRequestV2 {
        requireReadySession(session)
        tombstone.requireBoundTo(session)
        require(tombstone.executeAfterEpochMillis != null) {
            "A provisional tombstone cannot be revived on the Worker"
        }
        require(reference.blobId == tombstone.blobId && reference.presence?.value == true) {
            "Blob revival requires a newer live metadata reference"
        }
        require(reference.remoteManifest?.value?.manifestId == tombstone.manifestId) {
            "Blob revival manifest differs from the tombstone"
        }
        val evidence = stableCheckpoint.toBlobEvidence(session)
        require(evidence.throughWorkspaceSeq > tombstone.referenceThroughWorkspaceSeq) {
            "Blob revival checkpoint must be newer than the removal boundary"
        }
        val unsigned = BlobReferenceRevivalRequestV2(
            tombstoneId = tombstone.tombstoneId,
            blobId = tombstone.blobId,
            manifestId = tombstone.manifestId,
            checkpointId = evidence.checkpointId,
            checkpointCiphertextSha256Base64Url = evidence.checkpointCiphertextSha256Base64Url,
            throughWorkspaceSeq = evidence.throughWorkspaceSeq,
            signatureBase64Url = encodeBase64Url(ByteArray(64)),
        )
        val signature = syncCrypto.signDeviceMessage(
            blobReferenceRevivalMessageV2(session, unsigned),
        ).copyBytes()
        require(signature.size == ED25519_SIGNATURE_BYTES) {
            "Device signer returned an invalid Ed25519 signature"
        }
        return unsigned.copy(signatureBase64Url = encodeBase64Url(signature))
    }

    /** Idempotently submits previously persisted live-reference proof. */
    public suspend fun commitReferenceRevival(
        session: SyncSession,
        capability: WorkspaceCapability,
        tombstone: BlobTombstoneHandleV2,
        request: BlobReferenceRevivalRequestV2,
    ): BlobReferenceRevivalResultV2 {
        requireBound(session, capability)
        tombstone.requireBoundTo(session)
        require(
            request.tombstoneId == tombstone.tombstoneId &&
                request.blobId == tombstone.blobId &&
                request.manifestId == tombstone.manifestId,
        ) { "Blob revival request identity mismatch" }
        require(request.throughWorkspaceSeq > tombstone.referenceThroughWorkspaceSeq) {
            "Blob revival does not advance past the removal boundary"
        }
        return bodyApi.reviveBlobReference(
            session,
            capability,
            tombstone.blobId,
            request,
        ).also { result ->
            require(
                result.tombstoneId == tombstone.tombstoneId &&
                    result.blobId == tombstone.blobId &&
                    result.manifestId == tombstone.manifestId,
            ) { "Worker returned a blob revival result for another tombstone" }
        }
    }

    /**
     * Prepares exact signed acknowledgement bytes without making a network request.
     *
     * Callers persist the result before submission. A response-loss retry can then replay the
     * same checkpoint identity and signature even if a newer stable checkpoint appears meanwhile.
     */
    public suspend fun prepareTombstoneAcknowledgement(
        session: SyncSession,
        tombstone: BlobTombstoneHandleV2,
        stableCheckpoint: RetainedCheckpointDescriptor,
    ): BlobTombstoneAckRequestV2 {
        requireReadySession(session)
        tombstone.requireBoundTo(session)
        val evidence = stableCheckpoint.toBlobEvidence(session)
        require(evidence.throughWorkspaceSeq >= tombstone.referenceThroughWorkspaceSeq) {
            "Stable checkpoint does not cover the blob reference tombstone"
        }
        val message = blobTombstoneAcknowledgementMessageV2(session, tombstone, evidence)
        val signature = syncCrypto.signDeviceMessage(message).copyBytes()
        require(signature.size == ED25519_SIGNATURE_BYTES) {
            "Device signer returned an invalid Ed25519 signature"
        }
        return BlobTombstoneAckRequestV2(
            tombstoneId = tombstone.tombstoneId,
            checkpointId = evidence.checkpointId,
            checkpointCiphertextSha256Base64Url = evidence.checkpointCiphertextSha256Base64Url,
            throughWorkspaceSeq = evidence.throughWorkspaceSeq,
            signatureBase64Url = encodeBase64Url(signature),
        )
    }

    /** Idempotently submits previously persisted signed acknowledgement bytes. */
    public suspend fun commitTombstoneAcknowledgement(
        session: SyncSession,
        capability: WorkspaceCapability,
        tombstone: BlobTombstoneHandleV2,
        request: BlobTombstoneAckRequestV2,
    ): BlobTombstoneAckRequestV2 {
        requireBound(session, capability)
        tombstone.requireBoundTo(session)
        require(request.tombstoneId == tombstone.tombstoneId) {
            "Blob tombstone acknowledgement identity mismatch"
        }
        require(request.throughWorkspaceSeq >= tombstone.referenceThroughWorkspaceSeq) {
            "Blob tombstone acknowledgement does not cover the reference deletion"
        }
        bodyApi.acknowledgeTombstone(session, capability, tombstone.blobId, request)
        return request
    }

    /**
     * Convenience path retained for callers that do not own a durable lifecycle journal.
     * Production scheduling uses prepare -> durable save -> commit instead.
     */
    public suspend fun acknowledgeTombstone(
        session: SyncSession,
        capability: WorkspaceCapability,
        tombstone: BlobTombstoneHandleV2,
        stableCheckpoint: RetainedCheckpointDescriptor,
    ): BlobTombstoneAckRequestV2 = commitTombstoneAcknowledgement(
        session = session,
        capability = capability,
        tombstone = tombstone,
        request = prepareTombstoneAcknowledgement(session, tombstone, stableCheckpoint),
    )

    /** Requests server-authoritative GC; the Worker enforces ack quorum and its safety window. */
    public suspend fun garbageCollect(
        session: SyncSession,
        capability: WorkspaceCapability,
        tombstone: BlobTombstoneHandleV2,
    ): BlobGcReceiptV2 {
        requireBound(session, capability)
        tombstone.requireBoundTo(session)
        return bodyApi.garbageCollect(
            session,
            capability,
            BlobGcRequestV2(blobId = tombstone.blobId, tombstoneId = tombstone.tombstoneId),
        ).also { receipt ->
            require(receipt.blobId == tombstone.blobId) { "Blob GC receipt identity mismatch" }
        }
    }

    private fun requireBound(session: SyncSession, capability: WorkspaceCapability) {
        requireReadySession(session)
        val binding = capability.binding
        require(
            binding.workspaceId == session.workspaceId &&
                binding.deviceId == session.deviceId &&
                binding.deviceAuthEpoch == session.deviceAuthEpoch &&
                binding.membershipAuthEpoch == session.membershipAuthEpoch &&
                binding.keyEpoch == session.activeKeyEpoch,
        ) { "Blob lifecycle capability is not bound to the current sync session" }
        require(binding.expiresAtMillis > nowEpochMillis()) { "Blob lifecycle capability is expired" }
    }
}

internal fun blobTombstoneAcknowledgementMessageV2(
    session: SyncSession,
    tombstone: BlobTombstoneHandleV2,
    evidence: BlobRewrapCheckpointEvidenceV2,
): BinaryData {
    val canonical = canonicalSyncJson(
        JsonObject(
            mapOf(
                "protocolVersion" to JsonPrimitive(BLOB_BODY_PROTOCOL_VERSION),
                "schemaVersion" to JsonPrimitive(BLOB_BODY_SCHEMA_VERSION),
                "instanceId" to JsonPrimitive(session.instanceId),
                "workspaceId" to JsonPrimitive(session.workspaceId),
                "blobId" to JsonPrimitive(tombstone.blobId),
                "tombstoneId" to JsonPrimitive(tombstone.tombstoneId),
                "checkpointId" to JsonPrimitive(evidence.checkpointId),
                "checkpointCiphertextSha256Base64Url" to
                    JsonPrimitive(evidence.checkpointCiphertextSha256Base64Url),
                "throughWorkspaceSeq" to JsonPrimitive(evidence.throughWorkspaceSeq),
                "validatorDeviceId" to JsonPrimitive(session.deviceId),
            ),
        ),
    )
    return BinaryData.copyOf(
        versionedDomainSeparatedMessage(
            BLOB_BODY_PROTOCOL_VERSION,
            "blob-tombstone-ack",
            canonical.encodeToByteArray(),
        ),
    )
}

internal fun blobReferenceRevivalMessageV2(
    session: SyncSession,
    request: BlobReferenceRevivalRequestV2,
): BinaryData {
    val canonical = canonicalSyncJson(
        JsonObject(
            mapOf(
                "protocolVersion" to JsonPrimitive(BLOB_BODY_PROTOCOL_VERSION),
                "schemaVersion" to JsonPrimitive(BLOB_BODY_SCHEMA_VERSION),
                "instanceId" to JsonPrimitive(session.instanceId),
                "workspaceId" to JsonPrimitive(session.workspaceId),
                "blobId" to JsonPrimitive(request.blobId),
                "manifestId" to JsonPrimitive(request.manifestId),
                "tombstoneId" to JsonPrimitive(request.tombstoneId),
                "checkpointId" to JsonPrimitive(request.checkpointId),
                "checkpointCiphertextSha256Base64Url" to
                    JsonPrimitive(request.checkpointCiphertextSha256Base64Url),
                "throughWorkspaceSeq" to JsonPrimitive(request.throughWorkspaceSeq),
                "validatorDeviceId" to JsonPrimitive(session.deviceId),
            ),
        ),
    )
    return BinaryData.copyOf(
        versionedDomainSeparatedMessage(
            BLOB_BODY_PROTOCOL_VERSION,
            "blob-tombstone-revival",
            canonical.encodeToByteArray(),
        ),
    )
}

private fun versionedDomainSeparatedMessage(
    version: Int,
    domain: String,
    vararg parts: ByteArray,
): ByteArray {
    require(version > 0 && DOMAIN_PATTERN.matches(domain)) { "Invalid signature domain" }
    var message = "shinsou:$domain:v$version\u0000".encodeToByteArray()
    parts.forEach { part -> message += part }
    return message
}

private fun RetainedCheckpointDescriptor.toBlobEvidence(session: SyncSession): BlobRewrapCheckpointEvidenceV2 {
    require(keyEpoch == session.activeKeyEpoch) {
        "Blob lifecycle evidence must use the active workspace key epoch"
    }
    require(throughWorkspaceSeq in 0..MAX_BLOB_LIFECYCLE_SAFE_INTEGER) {
        "Stable checkpoint sequence is outside the Worker integer range"
    }
    return BlobRewrapCheckpointEvidenceV2(
        checkpointId = checkpointId,
        checkpointCiphertextSha256Base64Url = ciphertextSha256Base64Url,
        throughWorkspaceSeq = throughWorkspaceSeq,
    )
}

private fun PreparedBlobEnvelopeRewrapV2.requireBoundTo(session: SyncSession) {
    require(instanceId == session.instanceId && workspaceId == session.workspaceId) {
        "Prepared blob re-wrap belongs to another sync tenant"
    }
}

private fun BlobTombstoneHandleV2.requireBoundTo(session: SyncSession) {
    require(instanceId == session.instanceId && workspaceId == session.workspaceId) {
        "Blob tombstone belongs to another sync tenant"
    }
}

internal fun BlobTombstoneHandleV2.hasSameRemovalBoundary(other: BlobTombstoneHandleV2): Boolean =
    instanceId == other.instanceId &&
        workspaceId == other.workspaceId &&
        blobId == other.blobId &&
        manifestId == other.manifestId &&
        generation == other.generation &&
        referenceThroughWorkspaceSeq == other.referenceThroughWorkspaceSeq

private fun requireReadySession(session: SyncSession) {
    require(session.status == SyncSessionStatus.READY) { "Blob lifecycle requires a ready sync session" }
    requireCanonicalContentUuid(session.instanceId, "Blob lifecycle instance id")
    requireCanonicalContentUuid(session.workspaceId, "Blob lifecycle workspace id")
    requireCanonicalContentUuid(session.deviceId, "Blob lifecycle device id")
}

private const val ED25519_SIGNATURE_BYTES: Int = 64
private const val MAX_BLOB_LIFECYCLE_SAFE_INTEGER: Long = 9_007_199_254_740_991L
private val DOMAIN_PATTERN: Regex = Regex("^[a-z0-9-]+$")
