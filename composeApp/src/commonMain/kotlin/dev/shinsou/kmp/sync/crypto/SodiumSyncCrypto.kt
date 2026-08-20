package dev.shinsou.kmp.sync.crypto

import dev.shinsou.kmp.sync.v2.BinaryData
import dev.shinsou.kmp.sync.v2.EncryptedSyncCheckpoint
import dev.shinsou.kmp.sync.v2.EncryptedSyncEvent
import dev.shinsou.kmp.sync.v2.OpenedRemoteEvent
import dev.shinsou.kmp.sync.v2.PreparedSyncEventSealer
import dev.shinsou.kmp.sync.v2.RemoteCommittedEnvelope
import dev.shinsou.kmp.sync.v2.RemoteCheckpointVerificationException
import dev.shinsou.kmp.sync.v2.RetainedCheckpointDescriptor
import dev.shinsou.kmp.sync.v2.SealEventRequest
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncCheckpointHeader
import dev.shinsou.kmp.sync.v2.SyncCheckpointCompression
import dev.shinsou.kmp.sync.v2.SyncCipherSuite
import dev.shinsou.kmp.sync.v2.SyncCrypto
import dev.shinsou.kmp.sync.v2.SyncEventCodec
import dev.shinsou.kmp.sync.v2.SyncEventHeader
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretAccessException
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncState
import dev.shinsou.kmp.sync.v2.SYNC_CHECKPOINT_MAX_UNCOMPRESSED_BYTES
import dev.shinsou.kmp.sync.v2.VerifiedSyncCheckpoint
import dev.shinsou.kmp.sync.v2.requireSecret
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Resolves the immutable Ed25519 key registered for the envelope's sender device. */
fun interface SyncDevicePublicKeyResolver {
    suspend fun signingPublicKey(deviceId: String): BinaryData?
}

class InMemorySyncDevicePublicKeyResolver(
    initial: Map<String, BinaryData> = emptyMap(),
) : SyncDevicePublicKeyResolver {
    private val keys = initial.toMutableMap()

    override suspend fun signingPublicKey(deviceId: String): BinaryData? = keys[deviceId]

    fun put(deviceId: String, publicKey: BinaryData) {
        require(deviceId.isNotBlank())
        require(publicKey.size == ED25519_PUBLIC_KEY_BYTES)
        keys[deviceId] = publicKey
    }
}

/**
 * SyncCrypto backed by the same audited libsodium implementation on every KMP target.
 * Secret bytes are fetched only from the strict typed store and are wiped after each operation.
 */
class SodiumSyncCrypto(
    private val secretStore: SyncSecretStore,
    private val codec: SyncEventCodec,
    private val devicePublicKeyResolver: SyncDevicePublicKeyResolver,
) : SyncCrypto {
    override suspend fun prepareEventSealer(session: SyncSession, keyEpoch: Int): PreparedSyncEventSealer {
        SodiumSyncPrimitives.initialize()
        require(keyEpoch > 0)
        val workspaceKey = copyRequiredSecret(SyncSecretKey.WorkspaceEpochKey(session.workspaceId, keyEpoch))
        var signingKey: ByteArray? = null
        var eventKey: ByteArray? = null
        try {
            require(workspaceKey.size == KEY_BYTES) { "Workspace epoch key is corrupt" }
            signingKey = copyRequiredSecret(SyncSecretKey.DeviceSigningPrivateKey)
            require(signingKey.size == ED25519_PRIVATE_KEY_BYTES) { "Device signing key is corrupt" }
            eventKey = deriveEventKey(workspaceKey, session.workspaceId, session.deviceId, keyEpoch)
            return SodiumPreparedEventSealer(
                session = session,
                keyEpoch = keyEpoch,
                eventKey = requireNotNull(eventKey),
                signingKey = requireNotNull(signingKey),
                codec = codec,
            ).also {
                // Ownership transfers to the prepared sealer, whose close() wipes both arrays.
                eventKey = null
                signingKey = null
            }
        } finally {
            SodiumSyncPrimitives.destroy(workspaceKey)
            eventKey?.let(SodiumSyncPrimitives::destroy)
            signingKey?.let(SodiumSyncPrimitives::destroy)
        }
    }

    override suspend fun openAndVerifyEvent(
        session: SyncSession,
        remote: RemoteCommittedEnvelope,
    ): OpenedRemoteEvent {
        SodiumSyncPrimitives.initialize()
        val envelope = remote.envelope
        val header = envelope.header
        validateTenantHeader(session, header.instanceId, header.workspaceId)
        require(header.protocolVersion == 1 && header.envelopeVersion == 1) { "Unsupported event envelope version" }
        require(header.cipherSuite == SyncCipherSuite.CHACHA20_POLY1305) {
            "Unsupported event cipher suite: ${header.cipherSuite}"
        }
        val canonicalHeader = codec.canonicalEventAssociatedData(header)
        val transportedHeader = decodeBase64UrlCanonical(envelope.authenticatedHeaderBase64Url, "event header")
        require(canonicalHeader.copyBytes().contentEquals(transportedHeader)) { "Event header is not canonical" }
        val ciphertext = decodeBase64UrlCanonical(envelope.ciphertextBase64Url, "event ciphertext")
        require(ciphertext.size <= MAX_EVENT_CIPHERTEXT_BYTES) { "Event ciphertext exceeds the client hard limit" }
        val ciphertextHash = SodiumSyncPrimitives.sha256(ciphertext)
        require(base64Url(ciphertextHash) == header.ciphertextSha256Base64Url) { "Event ciphertext hash mismatch" }
        val signature = decodeBase64UrlCanonical(envelope.signatureBase64Url, "event signature")
        val publicKey = devicePublicKeyResolver.signingPublicKey(header.deviceId)
            ?: throw IllegalStateException("No trusted signing key for sender device")
        val signedMessage = signatureMessage(EVENT_ENVELOPE_DOMAIN, transportedHeader, ciphertextHash)
        require(
            SodiumSyncPrimitives.verifyEd25519(signedMessage, signature, publicKey.copyBytes()),
        ) { "Event device signature is invalid" }

        val nonce = decodeBase64UrlCanonical(header.nonceBase64Url, "event nonce")
        require(nonce.size == NONCE_BYTES) { "Event nonce has the wrong size" }
        val workspaceKey = copyRequiredSecret(SyncSecretKey.WorkspaceEpochKey(session.workspaceId, header.keyEpoch))
        val eventKey = try {
            require(workspaceKey.size == KEY_BYTES) { "Workspace epoch key is corrupt" }
            deriveEventKey(workspaceKey, session.workspaceId, header.deviceId, header.keyEpoch)
        } finally {
            SodiumSyncPrimitives.destroy(workspaceKey)
        }
        val plaintext = try {
            SodiumSyncPrimitives.aeadDecrypt(ciphertext, eventKey, nonce, transportedHeader)
        } finally {
            SodiumSyncPrimitives.destroy(eventKey)
        }
        val event = try {
            codec.decodeEvent(BinaryData.copyOf(plaintext))
        } finally {
            SodiumSyncPrimitives.destroy(plaintext)
        }
        require(event.schemaVersion == header.schemaVersion) { "Event schema header/payload mismatch" }
        require(event.hlc.deviceId == header.deviceId) { "Event sender header/payload mismatch" }
        return OpenedRemoteEvent(event, canonicalHeader)
    }

    override suspend fun openAndVerifyCheckpoint(
        session: SyncSession,
        checkpoint: EncryptedSyncCheckpoint,
        descriptor: RetainedCheckpointDescriptor,
    ): VerifiedSyncCheckpoint {
        SodiumSyncPrimitives.initialize()
        val header = checkpoint.header
        val canonicalHeader = verifyRemoteCheckpointPart("Checkpoint metadata is invalid") {
            validateTenantHeader(session, header.instanceId, header.workspaceId)
            require(header.protocolVersion == 1 && header.envelopeVersion == 1) {
                "Unsupported checkpoint envelope version"
            }
            require(header.cipherSuite == SyncCipherSuite.CHACHA20_POLY1305) {
                "Unsupported checkpoint cipher suite: ${header.cipherSuite}"
            }
            require(header.checkpointId == descriptor.checkpointId) { "Checkpoint identity mismatch" }
            require(header.throughWorkspaceSeq == descriptor.throughWorkspaceSeq) { "Checkpoint cursor mismatch" }
            require(header.keyEpoch == descriptor.keyEpoch) { "Checkpoint key epoch mismatch" }
            require(header.ciphertextSha256Base64Url == descriptor.ciphertextSha256Base64Url) {
                "Checkpoint descriptor hash mismatch"
            }
            require(
                header.previousStableCiphertextSha256Base64Url == descriptor.previousStableCiphertextSha256Base64Url,
            ) { "Checkpoint chain descriptor mismatch" }
            codec.canonicalCheckpointAssociatedData(header)
        }
        val transportedHeader = verifyRemoteCheckpointPart("Checkpoint header is invalid") {
            decodeBase64UrlCanonical(checkpoint.authenticatedHeaderBase64Url, "checkpoint header")
        }
        verifyRemoteCheckpointPart("Checkpoint header is invalid") {
            require(canonicalHeader.copyBytes().contentEquals(transportedHeader)) {
                "Checkpoint header is not canonical"
            }
        }
        val ciphertext = verifyRemoteCheckpointPart("Checkpoint ciphertext is invalid") {
            decodeBase64UrlCanonical(checkpoint.ciphertextBase64Url, "checkpoint ciphertext").also {
                require(it.size <= MAX_CHECKPOINT_CIPHERTEXT_BYTES) { "Checkpoint exceeds the client hard limit" }
            }
        }
        val hash = SodiumSyncPrimitives.sha256(ciphertext)
        verifyRemoteCheckpointPart("Checkpoint ciphertext is invalid") {
            require(base64Url(hash) == header.ciphertextSha256Base64Url) {
                "Checkpoint ciphertext hash mismatch"
            }
        }
        val signature = verifyRemoteCheckpointPart("Checkpoint signature is invalid") {
            decodeBase64UrlCanonical(checkpoint.signatureBase64Url, "checkpoint signature")
        }
        val publicKey = devicePublicKeyResolver.signingPublicKey(header.deviceId)
            ?: throw RemoteCheckpointVerificationException("No trusted signing key for checkpoint uploader")
        verifyRemoteCheckpointPart("Checkpoint signature is invalid") {
            require(
                SodiumSyncPrimitives.verifyEd25519(
                    signatureMessage(CHECKPOINT_ENVELOPE_DOMAIN, transportedHeader, hash),
                    signature,
                    publicKey.copyBytes(),
                ),
            ) { "Checkpoint device signature is invalid" }
        }

        val workspaceSecretKey = SyncSecretKey.WorkspaceEpochKey(session.workspaceId, header.keyEpoch)
        val workspaceKey = copyRequiredSecret(workspaceSecretKey)
        if (workspaceKey.size != KEY_BYTES) {
            SodiumSyncPrimitives.destroy(workspaceKey)
            throw SyncSecretAccessException.Corrupt(workspaceSecretKey, "wrong byte length")
        }
        val checkpointKey = try {
            deriveCheckpointKey(workspaceKey, session.workspaceId, header.keyEpoch)
        } finally {
            SodiumSyncPrimitives.destroy(workspaceKey)
        }
        val nonce = verifyRemoteCheckpointPart("Checkpoint nonce is invalid") {
            decodeBase64UrlCanonical(header.nonceBase64Url, "checkpoint nonce").also {
                require(it.size == NONCE_BYTES) { "Checkpoint nonce has the wrong size" }
            }
        }
        val compressedPlaintext = try {
            verifyRemoteCheckpointPart("Checkpoint authentication failed") {
                SodiumSyncPrimitives.aeadDecrypt(ciphertext, checkpointKey, nonce, transportedHeader)
            }
        } finally {
            SodiumSyncPrimitives.destroy(checkpointKey)
        }
        val plaintext = try {
            verifyRemoteCheckpointPart("Checkpoint compression is invalid") {
                when (header.compression) {
                    SyncCheckpointCompression.LZ4_BLOCK_V1 -> DeterministicLz4BlockV1.decompress(
                        input = compressedPlaintext,
                        expectedSize = header.uncompressedSize,
                        maxOutputSize = SYNC_CHECKPOINT_MAX_UNCOMPRESSED_BYTES,
                    )
                }
            }
        } finally {
            SodiumSyncPrimitives.destroy(compressedPlaintext)
        }
        val canonicalState = BinaryData.copyOf(plaintext)
        val state = try {
            verifyRemoteCheckpointPart("Checkpoint plaintext is invalid") {
                codec.decodeCheckpointState(canonicalState)
            }
        } finally {
            SodiumSyncPrimitives.destroy(plaintext)
        }
        verifyRemoteCheckpointPart("Checkpoint plaintext is invalid") {
            require(codec.canonicalCheckpointState(state) == canonicalState) { "Checkpoint state is not canonical" }
        }
        return VerifiedSyncCheckpoint(header, state, canonicalState)
    }

    override suspend fun sealCheckpoint(
        session: SyncSession,
        checkpointId: String,
        state: SyncState,
        previousStableCiphertextSha256Base64Url: String?,
    ): EncryptedSyncCheckpoint {
        SodiumSyncPrimitives.initialize()
        require(state.keyEpoch == session.activeKeyEpoch) { "Checkpoint must use the active workspace key epoch" }
        val workspaceKey = copyRequiredSecret(
            SyncSecretKey.WorkspaceEpochKey(session.workspaceId, session.activeKeyEpoch),
        )
        val signingKey = try {
            copyRequiredSecret(SyncSecretKey.DeviceSigningPrivateKey)
        } catch (failure: Throwable) {
            SodiumSyncPrimitives.destroy(workspaceKey)
            throw failure
        }
        val checkpointKey = try {
            require(workspaceKey.size == KEY_BYTES) { "Workspace epoch key is corrupt" }
            require(signingKey.size == ED25519_PRIVATE_KEY_BYTES) { "Device signing key is corrupt" }
            deriveCheckpointKey(workspaceKey, session.workspaceId, session.activeKeyEpoch)
        } catch (failure: Throwable) {
            SodiumSyncPrimitives.destroy(signingKey)
            throw failure
        } finally {
            SodiumSyncPrimitives.destroy(workspaceKey)
        }
        try {
            val nonce = SodiumSyncPrimitives.randomBytes(NONCE_BYTES)
            val nonceEncoded = base64Url(nonce)
            val plaintext = codec.canonicalCheckpointState(
                state.copy(previousStableCheckpointHash = previousStableCiphertextSha256Base64Url).normalized(),
            ).copyBytes()
            val uncompressedSize = plaintext.size
            val compressedPlaintext = try {
                require(uncompressedSize in 1..SYNC_CHECKPOINT_MAX_UNCOMPRESSED_BYTES) {
                    "Checkpoint plaintext exceeds the client hard limit"
                }
                DeterministicLz4BlockV1.compress(plaintext)
            } finally {
                SodiumSyncPrimitives.destroy(plaintext)
            }
            val provisional: SyncCheckpointHeader
            val headerBytes: ByteArray
            val ciphertext = try {
                provisional = SyncCheckpointHeader(
                    schemaVersion = state.schemaVersion,
                    cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
                    nonceBase64Url = nonceEncoded,
                    instanceId = session.instanceId,
                    workspaceId = session.workspaceId,
                    checkpointId = checkpointId,
                    deviceId = session.deviceId,
                    throughWorkspaceSeq = state.throughWorkspaceSeq,
                    keyEpoch = state.keyEpoch,
                    previousStableCiphertextSha256Base64Url = previousStableCiphertextSha256Base64Url,
                    compression = SyncCheckpointCompression.LZ4_BLOCK_V1,
                    uncompressedSize = uncompressedSize,
                    ciphertextSha256Base64Url = HASH_PLACEHOLDER,
                )
                headerBytes = codec.canonicalCheckpointAssociatedData(provisional).copyBytes()
                SodiumSyncPrimitives.aeadEncrypt(compressedPlaintext, checkpointKey, nonce, headerBytes)
            } finally {
                SodiumSyncPrimitives.destroy(compressedPlaintext)
            }
            require(ciphertext.size <= MAX_CHECKPOINT_CIPHERTEXT_BYTES) {
                "Checkpoint exceeds the client hard limit"
            }
            val hash = SodiumSyncPrimitives.sha256(ciphertext)
            val signature = SodiumSyncPrimitives.signEd25519(
                signatureMessage(CHECKPOINT_ENVELOPE_DOMAIN, headerBytes, hash),
                signingKey,
            )
            return EncryptedSyncCheckpoint(
                header = provisional.copy(ciphertextSha256Base64Url = base64Url(hash)),
                authenticatedHeaderBase64Url = base64Url(headerBytes),
                ciphertextBase64Url = base64Url(ciphertext),
                signatureBase64Url = base64Url(signature),
            )
        } finally {
            SodiumSyncPrimitives.destroy(checkpointKey)
            SodiumSyncPrimitives.destroy(signingKey)
        }
    }

    override suspend fun generateCheckpointId(): String {
        SodiumSyncPrimitives.initialize()
        return randomUuid()
    }

    override suspend fun generateWorkspaceEpochKey(): SecretMaterial {
        SodiumSyncPrimitives.initialize()
        val bytes = SodiumSyncPrimitives.randomBytes(KEY_BYTES)
        return try {
            SecretMaterial(bytes.asList())
        } finally {
            SodiumSyncPrimitives.destroy(bytes)
        }
    }

    override suspend fun keyCommitment(material: SecretMaterial): BinaryData {
        SodiumSyncPrimitives.initialize()
        val secret = copySecret(material)
        return try {
            BinaryData.copyOf(SodiumSyncPrimitives.sha256(KEY_COMMITMENT_DOMAIN + secret))
        } finally {
            SodiumSyncPrimitives.destroy(secret)
        }
    }

    override suspend fun wrapWorkspaceKey(
        material: SecretMaterial,
        recipientPublicKey: BinaryData,
    ): BinaryData {
        SodiumSyncPrimitives.initialize()
        require(recipientPublicKey.size == X25519_PUBLIC_KEY_BYTES) { "Invalid recipient wrapping key" }
        val secret = copySecret(material)
        val wrapped = try {
            SodiumSyncPrimitives.wrapKey(secret, recipientPublicKey.copyBytes(), KEY_WRAP_CONTEXT)
        } finally {
            SodiumSyncPrimitives.destroy(secret)
        }
        val encoded = DeterministicCbor.encode(
            JsonObject(
                mapOf(
                    "cipherSuite" to JsonPrimitive("X25519_HKDF_SHA256_CHACHA20_POLY1305"),
                    "ephemeralPublicKey" to JsonPrimitive(base64Url(wrapped.ephemeralPublicKey)),
                    "nonce" to JsonPrimitive(base64Url(wrapped.nonce)),
                    "ciphertext" to JsonPrimitive(base64Url(wrapped.ciphertext)),
                ),
            ),
        )
        return BinaryData.copyOf(encoded)
    }

    override suspend fun signDeviceMessage(message: BinaryData): BinaryData {
        SodiumSyncPrimitives.initialize()
        val signingKey = copyRequiredSecret(SyncSecretKey.DeviceSigningPrivateKey)
        return try {
            require(signingKey.size == ED25519_PRIVATE_KEY_BYTES) { "Device signing key is corrupt" }
            BinaryData.copyOf(SodiumSyncPrimitives.signEd25519(message.copyBytes(), signingKey))
        } finally {
            SodiumSyncPrimitives.destroy(signingKey)
        }
    }

    override suspend fun verifyDeviceSignature(
        message: BinaryData,
        signature: BinaryData,
        publicKey: BinaryData,
    ): Boolean {
        SodiumSyncPrimitives.initialize()
        return SodiumSyncPrimitives.verifyEd25519(
            message.copyBytes(),
            signature.copyBytes(),
            publicKey.copyBytes(),
        )
    }

    private suspend fun copyRequiredSecret(key: SyncSecretKey): ByteArray = copySecret(secretStore.requireSecret(key))

    private fun copySecret(material: SecretMaterial): ByteArray {
        var result: ByteArray? = null
        material.useBytes { result = it.copyOf() }
        return requireNotNull(result)
    }

    private fun validateTenantHeader(session: SyncSession, instanceId: String, workspaceId: String) {
        require(instanceId == session.instanceId) { "Envelope belongs to another sync instance" }
        require(workspaceId == session.workspaceId) { "Envelope belongs to another workspace" }
    }
}

private class SodiumPreparedEventSealer(
    private val session: SyncSession,
    private val keyEpoch: Int,
    private val eventKey: ByteArray,
    private val signingKey: ByteArray,
    private val codec: SyncEventCodec,
) : PreparedSyncEventSealer {
    private var closed = false

    override fun seal(request: SealEventRequest): EncryptedSyncEvent {
        check(!closed) { "Prepared event sealer is closed" }
        require(request.context.instanceId == session.instanceId)
        require(request.context.workspaceId == session.workspaceId)
        require(request.context.deviceId == session.deviceId)
        require(request.keyEpoch == keyEpoch)
        val nonce = SodiumSyncPrimitives.randomBytes(NONCE_BYTES)
        val provisional = SyncEventHeader(
            envelopeVersion = request.context.envelopeVersion,
            protocolVersion = request.context.protocolVersion,
            schemaVersion = request.event.schemaVersion,
            cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
            nonceBase64Url = base64Url(nonce),
            instanceId = session.instanceId,
            workspaceId = session.workspaceId,
            eventId = randomUuid(),
            deviceId = session.deviceId,
            deviceSeq = request.deviceSeq,
            keyEpoch = request.keyEpoch,
            ciphertextSha256Base64Url = HASH_PLACEHOLDER,
        )
        val headerBytes = codec.canonicalEventAssociatedData(provisional).copyBytes()
        val plaintext = codec.encodeEvent(request.event).copyBytes()
        val ciphertext = try {
            SodiumSyncPrimitives.aeadEncrypt(plaintext, eventKey, nonce, headerBytes)
        } finally {
            SodiumSyncPrimitives.destroy(plaintext)
        }
        require(ciphertext.size <= MAX_EVENT_CIPHERTEXT_BYTES) { "Event ciphertext exceeds the client hard limit" }
        val hash = SodiumSyncPrimitives.sha256(ciphertext)
        val signature = SodiumSyncPrimitives.signEd25519(
            signatureMessage(EVENT_ENVELOPE_DOMAIN, headerBytes, hash),
            signingKey,
        )
        return EncryptedSyncEvent(
            header = provisional.copy(ciphertextSha256Base64Url = base64Url(hash)),
            authenticatedHeaderBase64Url = base64Url(headerBytes),
            ciphertextBase64Url = base64Url(ciphertext),
            signatureBase64Url = base64Url(signature),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        SodiumSyncPrimitives.destroy(eventKey)
        SodiumSyncPrimitives.destroy(signingKey)
    }
}

private fun deriveEventKey(workspaceKey: ByteArray, workspaceId: String, deviceId: String, epoch: Int): ByteArray =
    SodiumSyncPrimitives.hkdfSha256(
        inputKeyMaterial = workspaceKey,
        salt = SodiumSyncPrimitives.sha256("workspace:$workspaceId:epoch:$epoch".encodeToByteArray()),
        info = "shinsou-sync-v1:event-key:device:$deviceId".encodeToByteArray(),
        outputSize = KEY_BYTES,
    )

private fun deriveCheckpointKey(workspaceKey: ByteArray, workspaceId: String, epoch: Int): ByteArray =
    SodiumSyncPrimitives.hkdfSha256(
        inputKeyMaterial = workspaceKey,
        salt = SodiumSyncPrimitives.sha256("workspace:$workspaceId:epoch:$epoch".encodeToByteArray()),
        info = "shinsou-sync-v1:checkpoint-key".encodeToByteArray(),
        outputSize = KEY_BYTES,
    )

private fun signatureMessage(domain: ByteArray, header: ByteArray, hash: ByteArray): ByteArray = domain + header + hash

private fun base64Url(bytes: ByteArray): String = SodiumSyncPrimitives.base64UrlEncode(bytes)

private inline fun <T> verifyRemoteCheckpointPart(message: String, block: () -> T): T = try {
    block()
} catch (failure: RemoteCheckpointVerificationException) {
    throw failure
} catch (failure: Exception) {
    throw RemoteCheckpointVerificationException(message, failure)
}

private fun decodeBase64UrlCanonical(value: String, label: String): ByteArray {
    val decoded = runCatching { SodiumSyncPrimitives.base64UrlDecode(value) }
        .getOrElse { throw IllegalArgumentException("Invalid $label", it) }
    require(base64Url(decoded) == value) { "$label is not canonical base64url" }
    return decoded
}

private fun randomUuid(): String {
    val bytes = SodiumSyncPrimitives.randomBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

private val EVENT_ENVELOPE_DOMAIN = "shinsou:event-envelope:v1\u0000".encodeToByteArray()
private val CHECKPOINT_ENVELOPE_DOMAIN = "shinsou:checkpoint-envelope:v1\u0000".encodeToByteArray()
private val KEY_COMMITMENT_DOMAIN = "shinsou:workspace-key-commitment:v1\u0000".encodeToByteArray()
private val KEY_WRAP_CONTEXT = "shinsou:workspace-key-envelope:v1".encodeToByteArray()
private const val HASH_PLACEHOLDER = "pending"
private const val KEY_BYTES = 32
private const val NONCE_BYTES = 12
private const val ED25519_PUBLIC_KEY_BYTES = 32
private const val ED25519_PRIVATE_KEY_BYTES = 64
private const val X25519_PUBLIC_KEY_BYTES = 32
private const val MAX_EVENT_CIPHERTEXT_BYTES = 32 * 1024
private const val MAX_CHECKPOINT_CIPHERTEXT_BYTES = 32 * 1024 * 1024
