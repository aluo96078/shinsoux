package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.trust.DeviceDirectoryWire

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable

/** Small immutable byte container for crypto/codec boundaries. */
class BinaryData(bytes: Collection<Byte>) {
    private val value: List<Byte> = bytes.toList()

    val size: Int get() = value.size

    fun copyBytes(): ByteArray = value.toByteArray()

    override fun equals(other: Any?): Boolean = other is BinaryData && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "BinaryData(${value.size} bytes)"

    companion object {
        fun copyOf(bytes: ByteArray): BinaryData = BinaryData(bytes.asList())
        val Empty: BinaryData = BinaryData(emptyList())
    }
}

enum class SyncProvider {
    NONE,
    LEGACY_ICLOUD,
    CLOUDFLARE_V2,
}

@Serializable
enum class SyncSessionStatus {
    NOT_CONFIGURED,
    DEPLOYING,
    LINKING,
    READY,
    REVOKED,
    ERROR,
}

/**
 * Public, non-secret recovery state persisted with a LINKING session. It makes recovery
 * distinguishable from an initial claim after process death and binds reconciliation to the exact
 * old recovery root, replacement root, device keys and pre-rotation workspace epoch.
 */
@Serializable
data class PendingSyncRecovery(
    val recoverySigningPublicKey: String,
    val replacementRecoverySigningPublicKey: String,
    val replacementRecoveryWrappingPublicKey: String,
    val replacementCreatedAtMillis: Long,
    val deviceSigningPublicKey: String,
    val deviceWrappingPublicKey: String,
    val claimedKeyEpoch: Int,
    val claimedKeyCommitmentBase64Url: String,
) {
    init {
        require(
            listOf(
                recoverySigningPublicKey,
                replacementRecoverySigningPublicKey,
                replacementRecoveryWrappingPublicKey,
                deviceSigningPublicKey,
                deviceWrappingPublicKey,
                claimedKeyCommitmentBase64Url,
            ).all(String::isNotBlank),
        ) { "Pending recovery metadata is incomplete" }
        require(replacementCreatedAtMillis >= 0 && claimedKeyEpoch > 0) {
            "Pending recovery metadata is invalid"
        }
    }
}

/** Public operation identity persisted before a revoke request leaves the device. */
@Serializable
data class PendingDeviceRevocation(
    val revocationId: String,
    val targetDeviceId: String,
) {
    init {
        require(revocationId.matches(SYNC_IDENTITY_UUID) && revocationId == revocationId.lowercase()) {
            "Pending revocation id must be a canonical UUID"
        }
        require(targetDeviceId.matches(SYNC_IDENTITY_UUID) && targetDeviceId == targetDeviceId.lowercase()) {
            "Pending revocation target must be a canonical UUID"
        }
    }
}

/** Non-secret metadata only. Bearer/capability/device credentials are forbidden here. */
@Serializable
data class SyncSession(
    val endpoint: String,
    val instanceId: String,
    val userId: String,
    val workspaceId: String,
    val deviceId: String,
    val deviceDisplayName: String,
    val platform: String,
    val status: SyncSessionStatus,
    val deviceAuthEpoch: Long,
    val membershipAuthEpoch: Long,
    val activeKeyEpoch: Int,
    val capabilityExpiresAtMillis: Long? = null,
    val provider: SyncProvider = SyncProvider.CLOUDFLARE_V2,
    val pendingRecovery: PendingSyncRecovery? = null,
    val pendingDeviceRevocation: PendingDeviceRevocation? = null,
) {
    init {
        require(endpoint.isNotBlank()) { "Sync endpoint cannot be blank" }
        require(isAllowedSyncEndpoint(endpoint)) {
            "Cloudflare sync requires HTTPS (HTTP is allowed only for localhost tests)"
        }
        require(listOf(instanceId, userId, workspaceId, deviceId, deviceDisplayName, platform).all { it.isNotBlank() }) {
            "Sync session identity is incomplete"
        }
        require(deviceAuthEpoch >= 0 && membershipAuthEpoch >= 0 && activeKeyEpoch > 0) {
            "Sync session epochs are invalid"
        }
        capabilityExpiresAtMillis?.let { require(it >= 0) { "Capability expiry is invalid" } }
        require(provider == SyncProvider.CLOUDFLARE_V2) { "A v2 session must use the Cloudflare v2 provider" }
        pendingRecovery?.let { pending ->
            require(status == SyncSessionStatus.LINKING) {
                "Pending recovery metadata is valid only while linking"
            }
            require(activeKeyEpoch in pending.claimedKeyEpoch..pending.claimedKeyEpoch + 1) {
                "Pending recovery epoch advanced outside the recovery state machine"
            }
        }
        pendingDeviceRevocation?.let { pending ->
            require(status == SyncSessionStatus.READY) {
                "Pending device revocation metadata is valid only for a ready session"
            }
            require(pending.targetDeviceId != deviceId) {
                "A device cannot persist a revocation against itself"
            }
            require(pendingRecovery == null) {
                "Recovery and device revocation cannot be pending together"
            }
        }
    }
}

private val SYNC_IDENTITY_UUID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

interface SyncSessionStore {
    suspend fun load(): SyncSession?
    suspend fun save(session: SyncSession)
    suspend fun clear()
}

class InMemorySyncSessionStore(initial: SyncSession? = null) : SyncSessionStore {
    private val mutex = Mutex()
    private var session = initial

    override suspend fun load(): SyncSession? = locked { session }
    override suspend fun save(session: SyncSession) = locked { this.session = session }
    override suspend fun clear() = locked { session = null }

    private suspend fun <T> locked(block: () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

sealed interface SyncSecretKey {
    val redactedName: String

    data object DeviceCredential : SyncSecretKey {
        override val redactedName: String = "device-credential"
    }

    /** Deployment bootstrap secret staged until the single-use setup claim succeeds. */
    data object PendingBootstrapSecret : SyncSecretKey {
        override val redactedName: String = "pending-bootstrap-secret"
    }

    /** Full one-time invite link encrypted by the strict store until claim reconciliation ends. */
    data object PendingInvitePayload : SyncSecretKey {
        override val redactedName: String = "pending-invite-payload"
    }

    /** Full one-time pairing link encrypted by the strict store until activation reconciliation ends. */
    data object PendingPairingPayload : SyncSecretKey {
        override val redactedName: String = "pending-pairing-payload"
    }

    data object DeviceSigningPrivateKey : SyncSecretKey {
        override val redactedName: String = "device-signing-private-key"
    }

    data object DeviceWrappingPrivateKey : SyncSecretKey {
        override val redactedName: String = "device-wrapping-private-key"
    }

    data class WorkspaceEpochKey(val workspaceId: String, val epoch: Int) : SyncSecretKey {
        init {
            require(workspaceId.isNotBlank() && epoch > 0)
        }

        override val redactedName: String = "workspace-epoch-$epoch"
    }

    data object RecoverySigningPrivateKey : SyncSecretKey {
        override val redactedName: String = "recovery-signing-private-key"
    }

    data object RecoveryWrappingPrivateKey : SyncSecretKey {
        override val redactedName: String = "recovery-wrapping-private-key"
    }

    /** Staged until a recovery claim commits; the old recovery key must sign that claim. */
    data object PendingRecoverySigningPrivateKey : SyncSecretKey {
        override val redactedName: String = "pending-recovery-signing-private-key"
    }

    data object PendingRecoveryWrappingPrivateKey : SyncSecretKey {
        override val redactedName: String = "pending-recovery-wrapping-private-key"
    }

    data object AccessToken : SyncSecretKey {
        override val redactedName: String = "short-lived-access-token"
    }

    data class WorkspaceCapability(val workspaceId: String) : SyncSecretKey {
        init {
            require(workspaceId.isNotBlank())
        }

        override val redactedName: String = "short-lived-workspace-capability"
    }
}

class SecretMaterial(bytes: Collection<Byte>) {
    private val value = bytes.toList()

    init {
        require(value.isNotEmpty()) { "Secret material cannot be empty" }
    }

    fun useBytes(block: (ByteArray) -> Unit) {
        val copy = value.toByteArray()
        try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    override fun equals(other: Any?): Boolean = other is SecretMaterial && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "SecretMaterial(REDACTED)"
}

sealed interface SyncSecretReadResult {
    data class Available(val material: SecretMaterial) : SyncSecretReadResult
    data object Missing : SyncSecretReadResult
    data class Unavailable(val diagnostic: String) : SyncSecretReadResult
    data class Corrupt(val diagnostic: String) : SyncSecretReadResult
}

interface SyncSecretStore {
    suspend fun read(key: SyncSecretKey): SyncSecretReadResult
    suspend fun write(key: SyncSecretKey, material: SecretMaterial)
    suspend fun delete(key: SyncSecretKey)
}

sealed class SyncSecretAccessException(message: String) : IllegalStateException(message) {
    class Missing(key: SyncSecretKey) : SyncSecretAccessException("Required ${key.redactedName} is missing")
    class Unavailable(key: SyncSecretKey, diagnostic: String) :
        SyncSecretAccessException("Required ${key.redactedName} is unavailable: $diagnostic")

    class Corrupt(key: SyncSecretKey, diagnostic: String) :
        SyncSecretAccessException("Required ${key.redactedName} is corrupt: $diagnostic")
}

/**
 * A checkpoint object was downloaded successfully, but its transported structure, authenticated
 * envelope, ciphertext, signature, or plaintext failed verification. This is deliberately
 * distinct from transport and local secret-store failures so callers may reject only a bad remote
 * candidate without misclassifying an offline client or an unavailable device key as corruption.
 */
class RemoteCheckpointVerificationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

suspend fun SyncSecretStore.requireSecret(key: SyncSecretKey): SecretMaterial = when (val result = read(key)) {
    is SyncSecretReadResult.Available -> result.material
    SyncSecretReadResult.Missing -> throw SyncSecretAccessException.Missing(key)
    is SyncSecretReadResult.Unavailable -> throw SyncSecretAccessException.Unavailable(key, result.diagnostic)
    is SyncSecretReadResult.Corrupt -> throw SyncSecretAccessException.Corrupt(key, result.diagnostic)
}

class InMemorySyncSecretStore : SyncSecretStore {
    private val mutex = Mutex()
    private val values = mutableMapOf<SyncSecretKey, SyncSecretReadResult>()

    override suspend fun read(key: SyncSecretKey): SyncSecretReadResult = locked {
        values[key] ?: SyncSecretReadResult.Missing
    }

    override suspend fun write(key: SyncSecretKey, material: SecretMaterial) = locked {
        values[key] = SyncSecretReadResult.Available(material)
    }

    override suspend fun delete(key: SyncSecretKey) {
        locked { values.remove(key) }
    }

    suspend fun forceResult(key: SyncSecretKey, result: SyncSecretReadResult) = locked { values[key] = result }

    private suspend fun <T> locked(block: () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

@Serializable
data class SyncCapabilities(
    val protocolVersion: Int,
    val minReaderVersion: Int,
    val minWriterVersion: Int,
    val schemaVersion: Int,
    val minSchemaReaderVersion: Int,
    val minSchemaWriterVersion: Int,
    val realtimeAvailable: Boolean,
    val maxEventBytes: Int,
    val maxBatchBytes: Int,
    val maxCheckpointBytes: Int,
) {
    fun requireCompatible(
        protocolReaderVersion: Int,
        protocolWriterVersion: Int,
        schemaReaderVersion: Int,
        schemaWriterVersion: Int,
    ) {
        require(
            protocolVersion > 0 && minReaderVersion in 1..protocolVersion &&
                minWriterVersion in 1..protocolVersion,
        ) { "Server returned an invalid protocol compatibility range" }
        require(
            schemaVersion > 0 && minSchemaReaderVersion in 1..schemaVersion &&
                minSchemaWriterVersion in 1..schemaVersion,
        ) { "Server returned an invalid schema compatibility range" }
        require(protocolReaderVersion > 0 && protocolWriterVersion > 0) {
            "Client protocol versions must be positive"
        }
        require(schemaReaderVersion > 0 && schemaWriterVersion > 0) {
            "Client schema versions must be positive"
        }
        require(protocolReaderVersion >= minReaderVersion && protocolReaderVersion >= protocolVersion) {
            "Client reader protocol is too old"
        }
        require(protocolWriterVersion in minWriterVersion..protocolVersion) {
            "Client writer protocol is outside the server compatibility range"
        }
        require(schemaReaderVersion >= minSchemaReaderVersion && schemaReaderVersion >= schemaVersion) {
            "Client reader schema is too old"
        }
        require(schemaWriterVersion in minSchemaWriterVersion..schemaVersion) {
            "Client writer schema is outside the server compatibility range"
        }
        require(maxEventBytes > 0 && maxBatchBytes >= maxEventBytes && maxCheckpointBytes > 0) {
            "Server returned invalid size limits"
        }
    }
}

data class CapabilityBinding(
    val deviceId: String,
    val workspaceId: String,
    val deviceAuthEpoch: Long,
    val membershipAuthEpoch: Long,
    val keyEpoch: Int,
    val expiresAtMillis: Long,
)

data class WorkspaceCapability(
    val token: SecretMaterial,
    val binding: CapabilityBinding,
)

@Serializable
data class RemoteCommittedEnvelope(
    val workspaceSeq: Long,
    val envelope: EncryptedSyncEvent,
) {
    init {
        require(workspaceSeq > 0)
    }
}

data class CatchUpPage(
    val fromExclusive: Long,
    val untilInclusive: Long,
    val nextCursor: Long,
    val hasMore: Boolean,
    val headSeq: Long,
    val stableCheckpointSeq: Long,
    val events: List<RemoteCommittedEnvelope>,
    /** Attested entries for exactly the devices that sent [events] at the pinned revision. */
    val senderDeviceDirectory: DeviceDirectoryWire? = null,
)

sealed interface AppendEventResult {
    data class Committed(val receipt: SyncReceipt, val headSeq: Long) : AppendEventResult
    data class Retryable(val diagnostic: String) : AppendEventResult
    data class RateLimited(val retryAfterMillis: Long?) : AppendEventResult
    data class QuotaExceeded(val diagnostic: String) : AppendEventResult
    data class KeyRotationRequired(val activeKeyEpoch: Int) : AppendEventResult
    data class StaleKeyEpoch(val activeKeyEpoch: Int, val expectedDeviceSeq: Long) : AppendEventResult
    data class ReplayOrCorruption(val diagnostic: String) : AppendEventResult
    data object DeviceRevoked : AppendEventResult
    data class IncompatibleProtocol(val minReaderVersion: Int, val minWriterVersion: Int) : AppendEventResult
}

class KeyRotationRequiredException : IllegalStateException("Workspace key rotation is required")

@Serializable
data class RetainedCheckpointDescriptor(
    val checkpointId: String,
    val throughWorkspaceSeq: Long,
    val keyEpoch: Int,
    val ciphertextSha256Base64Url: String,
    val previousStableCiphertextSha256Base64Url: String? = null,
) {
    init {
        require(checkpointId.isNotBlank() && throughWorkspaceSeq >= 0 && keyEpoch > 0)
        require(ciphertextSha256Base64Url.isNotBlank())
    }
}

@Serializable
enum class RemoteCheckpointStatus {
    CANDIDATE,
    STABLE,
    REJECTED,
}

/** Exact immutable identity and replay boundary of a server-side checkpoint candidate. */
@Serializable
data class CheckpointCandidateDescriptor(
    val checkpointId: String,
    val throughWorkspaceSeq: Long,
    val keyEpoch: Int,
    val ciphertextSha256Base64Url: String,
    val uploaderDeviceId: String,
    val createdAtMillis: Long,
    val previousStableCheckpointId: String? = null,
    val previousStableThroughWorkspaceSeq: Long = 0,
    val previousStableCiphertextSha256Base64Url: String? = null,
) {
    init {
        require(checkpointId.isNotBlank() && uploaderDeviceId.isNotBlank())
        require(throughWorkspaceSeq >= 0 && previousStableThroughWorkspaceSeq >= 0)
        require(previousStableThroughWorkspaceSeq <= throughWorkspaceSeq)
        require(keyEpoch > 0 && ciphertextSha256Base64Url.isNotBlank() && createdAtMillis >= 0)
        require((previousStableCheckpointId == null) == (previousStableCiphertextSha256Base64Url == null)) {
            "Previous stable checkpoint identity is incomplete"
        }
        if (previousStableCheckpointId == null) {
            require(previousStableThroughWorkspaceSeq == 0L) { "Genesis candidate must replay from zero" }
        }
    }

    fun asDownloadDescriptor(): RetainedCheckpointDescriptor = RetainedCheckpointDescriptor(
        checkpointId = checkpointId,
        throughWorkspaceSeq = throughWorkspaceSeq,
        keyEpoch = keyEpoch,
        ciphertextSha256Base64Url = ciphertextSha256Base64Url,
        previousStableCiphertextSha256Base64Url = previousStableCiphertextSha256Base64Url,
    )
}

data class CheckpointLease(
    val leaseId: String,
    val checkpointId: String,
    val ciphertextSha256Base64Url: String,
    val throughWorkspaceSeq: Long,
    val keyEpoch: Int,
    val expiresAtMillis: Long,
) {
    init {
        require(leaseId.isNotBlank() && checkpointId.isNotBlank() && ciphertextSha256Base64Url.isNotBlank())
        require(throughWorkspaceSeq >= 0 && keyEpoch > 0 && expiresAtMillis >= 0)
    }
}

data class CheckpointReplayAcknowledgement(
    val checkpointId: String,
    val ciphertextSha256Base64Url: String,
    val validationVersion: Int = 1,
    val replayFromSeq: Long,
    val replayedThroughSeq: Long,
    val replayedEventCount: Int,
    val previousStableCheckpointId: String?,
    val previousStableCiphertextSha256Base64Url: String?,
    val valid: Boolean,
    val signatureBase64Url: String,
) {
    init {
        require(checkpointId.isNotBlank() && ciphertextSha256Base64Url.isNotBlank())
        require(validationVersion == 1 && replayFromSeq >= 0 && replayedThroughSeq >= replayFromSeq)
        require(replayedEventCount >= 0 && signatureBase64Url.isNotBlank())
        require((previousStableCheckpointId == null) == (previousStableCiphertextSha256Base64Url == null))
    }
}

data class CheckpointAcknowledgementResult(
    val checkpointId: String,
    val ciphertextSha256Base64Url: String,
    val status: RemoteCheckpointStatus,
    val throughWorkspaceSeq: Long,
)

data class CheckpointAnnouncement(
    val checkpointId: String,
    val throughWorkspaceSeq: Long,
    val keyEpoch: Int,
    val ciphertextSha256Base64Url: String,
    val uploaderDeviceId: String,
    val createdAtMillis: Long,
    val previousStableCheckpointId: String?,
    val previousStableThroughWorkspaceSeq: Long,
    val previousStableCiphertextSha256Base64Url: String?,
    val status: RemoteCheckpointStatus,
) {
    fun candidateOrNull(): CheckpointCandidateDescriptor? = if (status == RemoteCheckpointStatus.CANDIDATE) {
        CheckpointCandidateDescriptor(
            checkpointId = checkpointId,
            throughWorkspaceSeq = throughWorkspaceSeq,
            keyEpoch = keyEpoch,
            ciphertextSha256Base64Url = ciphertextSha256Base64Url,
            uploaderDeviceId = uploaderDeviceId,
            createdAtMillis = createdAtMillis,
            previousStableCheckpointId = previousStableCheckpointId,
            previousStableThroughWorkspaceSeq = previousStableThroughWorkspaceSeq,
            previousStableCiphertextSha256Base64Url = previousStableCiphertextSha256Base64Url,
        )
    } else {
        null
    }
}

data class BootstrapResponse(
    val headSeq: Long,
    val activeKeyEpoch: Int,
    val retainedStableCheckpoints: List<RetainedCheckpointDescriptor>,
    val requiredKeyEpochs: Set<Int>,
    val candidateCheckpoint: CheckpointCandidateDescriptor? = null,
    /** Full attested directory. Production clients reject bootstrap responses that omit it. */
    val deviceDirectory: DeviceDirectoryWire? = null,
)

class CheckpointRequiredException(
    val headSeq: Long,
    val retainedCheckpoints: List<RetainedCheckpointDescriptor>,
) : IllegalStateException("The local sync cursor requires a retained checkpoint")

interface CloudflareSyncApi {
    suspend fun capabilities(endpoint: String): SyncCapabilities

    suspend fun obtainWorkspaceCapability(session: SyncSession): WorkspaceCapability

    suspend fun appendEvent(
        session: SyncSession,
        capability: WorkspaceCapability,
        event: EncryptedSyncEvent,
    ): AppendEventResult

    suspend fun eventReceipt(
        session: SyncSession,
        capability: WorkspaceCapability,
        deviceSeq: Long,
    ): SyncReceipt?

    suspend fun catchUp(
        session: SyncSession,
        capability: WorkspaceCapability,
        afterExclusive: Long,
        untilInclusive: Long?,
        limit: Int,
    ): CatchUpPage

    suspend fun bootstrap(session: SyncSession, capability: WorkspaceCapability): BootstrapResponse

    suspend fun downloadCheckpoint(
        session: SyncSession,
        capability: WorkspaceCapability,
        descriptor: RetainedCheckpointDescriptor,
    ): EncryptedSyncCheckpoint

    suspend fun createCheckpointLease(
        session: SyncSession,
        capability: WorkspaceCapability,
        checkpointId: String,
        ciphertextSha256Base64Url: String,
        expectedByteSize: Int,
        throughWorkspaceSeq: Long,
    ): CheckpointLease = throw UnsupportedOperationException("Checkpoint upload is unavailable")

    suspend fun uploadCheckpoint(
        session: SyncSession,
        capability: WorkspaceCapability,
        lease: CheckpointLease,
        checkpoint: EncryptedSyncCheckpoint,
    ) {
        throw UnsupportedOperationException("Checkpoint upload is unavailable")
    }

    suspend fun commitCheckpoint(
        session: SyncSession,
        capability: WorkspaceCapability,
        lease: CheckpointLease,
    ): CheckpointCandidateDescriptor = throw UnsupportedOperationException("Checkpoint commit is unavailable")

    suspend fun acknowledgeCheckpoint(
        session: SyncSession,
        capability: WorkspaceCapability,
        acknowledgement: CheckpointReplayAcknowledgement,
    ): CheckpointAcknowledgementResult = throw UnsupportedOperationException("Checkpoint validation is unavailable")
}

sealed interface RealtimeWorkspaceMessage {
    data class Hello(val headSeq: Long, val stableCheckpointSeq: Long) : RealtimeWorkspaceMessage
    data class Event(val event: RemoteCommittedEnvelope) : RealtimeWorkspaceMessage
    data class CheckpointAvailable(val checkpoint: CheckpointAnnouncement) : RealtimeWorkspaceMessage
    data class ResyncRequired(val stableCheckpointSeq: Long, val headSeq: Long) : RealtimeWorkspaceMessage
    data object ReauthRequired : RealtimeWorkspaceMessage
}

interface RealtimeWorkspaceClient {
    suspend fun connect(
        session: SyncSession,
        capability: WorkspaceCapability,
        cursor: Long,
        onMessage: suspend (RealtimeWorkspaceMessage) -> Unit,
    )

    suspend fun close()
}

/** Strict origin policy shared by session metadata, provisioning links and network clients. */
fun isAllowedSyncEndpoint(value: String): Boolean {
    val endpoint = value.trim().trimEnd('/')
    if (endpoint != value.trim().trimEnd('/')) return false
    if (HTTPS_SYNC_ENDPOINT.matches(endpoint)) return true
    return LOOPBACK_HTTP_SYNC_ENDPOINT.matches(endpoint)
}

private val HTTPS_SYNC_ENDPOINT = Regex(
    "^https://(?:\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)(?::(?:[1-9][0-9]{0,4}))?(?:/[^?#\\s]*)?$",
)
private val LOOPBACK_HTTP_SYNC_ENDPOINT = Regex(
    "^http://(?:localhost|127\\.0\\.0\\.1)(?::(?:[1-9][0-9]{0,4}))?(?:/[^?#\\s]*)?$",
)

interface PreparedSyncEventSealer : SyncEventSealer {
    fun close()
}

interface SyncEventCodec {
    /** RFC 8949 deterministic CBOR payload bytes. */
    fun encodeEvent(event: SyncEvent): BinaryData
    fun decodeEvent(payload: BinaryData): SyncEvent
    fun canonicalEventAssociatedData(headerWithoutCiphertextHash: SyncEventHeader): BinaryData
    fun canonicalCheckpointAssociatedData(headerWithoutCiphertextHash: SyncCheckpointHeader): BinaryData
    fun canonicalCheckpointState(state: SyncState): BinaryData
    fun decodeCheckpointState(payload: BinaryData): SyncState
}

data class OpenedRemoteEvent(
    val event: SyncEvent,
    val authenticatedHeaderBytes: BinaryData,
)

/** Platform crypto implementation must use strict SyncSecretStore keys and audited primitives. */
interface SyncCrypto {
    suspend fun prepareEventSealer(session: SyncSession, keyEpoch: Int): PreparedSyncEventSealer

    suspend fun openAndVerifyEvent(
        session: SyncSession,
        remote: RemoteCommittedEnvelope,
    ): OpenedRemoteEvent

    suspend fun openAndVerifyCheckpoint(
        session: SyncSession,
        checkpoint: EncryptedSyncCheckpoint,
        descriptor: RetainedCheckpointDescriptor,
    ): VerifiedSyncCheckpoint

    suspend fun sealCheckpoint(
        session: SyncSession,
        checkpointId: String,
        state: SyncState,
        previousStableCiphertextSha256Base64Url: String?,
    ): EncryptedSyncCheckpoint

    /** Random RFC 4122 UUID used as immutable checkpoint identity. */
    suspend fun generateCheckpointId(): String = throw UnsupportedOperationException("Checkpoint id generation unavailable")

    /** Must produce a new independent 256-bit key, never a value derived from an old epoch. */
    suspend fun generateWorkspaceEpochKey(): SecretMaterial

    suspend fun keyCommitment(material: SecretMaterial): BinaryData
    suspend fun wrapWorkspaceKey(material: SecretMaterial, recipientPublicKey: BinaryData): BinaryData
    suspend fun signDeviceMessage(message: BinaryData): BinaryData
    suspend fun verifyDeviceSignature(message: BinaryData, signature: BinaryData, publicKey: BinaryData): Boolean
}
