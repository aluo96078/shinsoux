package dev.shinsou.kmp.sync.network

import dev.shinsou.kmp.sync.trust.DeviceDirectoryWire
import dev.shinsou.kmp.sync.v2.CommittedRotationEvidence
import dev.shinsou.kmp.sync.v2.DeviceWorkspaceKeyEnvelope
import dev.shinsou.kmp.sync.v2.KeyRotationLease
import dev.shinsou.kmp.sync.v2.RecoveryChallenge
import dev.shinsou.kmp.sync.v2.RecoveryClaimReceipt
import dev.shinsou.kmp.sync.v2.RecoveryClaimRequest
import dev.shinsou.kmp.sync.v2.RecoveryEpochKeyEnvelope
import dev.shinsou.kmp.sync.v2.RecoveryWorkspaceChallenge
import dev.shinsou.kmp.sync.v2.RecoveryWorkspaceBinding
import dev.shinsou.kmp.sync.v2.RotationAcknowledgementReceipt
import dev.shinsou.kmp.sync.v2.RotationCommitReceipt
import dev.shinsou.kmp.sync.v2.RotationCommitRequest
import dev.shinsou.kmp.sync.v2.RotationRecoveryRecipient
import dev.shinsou.kmp.sync.v2.RotationRecipient
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncControlPlaneApi
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.WorkspaceCapability
import dev.shinsou.kmp.sync.v2.WorkspaceKeyBootstrap
import dev.shinsou.kmp.sync.v2.isAllowedSyncEndpoint
import dev.shinsou.kmp.sync.v2.requireSecret
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Ktor transport for signed identity, rotation, revocation and recovery control operations. */
class KtorSyncControlPlaneApi(
    private val client: HttpClient,
    private val secretStore: SyncSecretStore,
    private val json: Json = SyncNetworkJson,
) : SyncControlPlaneApi {
    override suspend fun createRotationLease(
        session: SyncSession,
        capability: WorkspaceCapability,
        rotationId: String,
        fromEpoch: Int,
        signatureBase64Url: String,
    ): KeyRotationLease {
        val dto = client.post(url(session.endpoint, "/v1/workspaces/${session.workspaceId}/key-rotations")) {
            bearerAuth(capability.token.asUtf8())
            jsonBody(
                RotationLeaseRequestDto(
                    rotationId = rotationId,
                    fromEpoch = fromEpoch,
                    signature = signatureBase64Url,
                ),
            )
        }.decodeSuccess<RotationLeaseResponseDto>()
        require(dto.workspaceId == session.workspaceId && dto.rotationId == rotationId) {
            "Rotation lease tenant or identity mismatch"
        }
        return dto.toDomain()
    }

    override suspend fun commitRotation(
        session: SyncSession,
        capability: WorkspaceCapability,
        rotationId: String,
        request: RotationCommitRequest,
    ): RotationCommitReceipt {
        val dto = client.post(
            url(session.endpoint, "/v1/workspaces/${session.workspaceId}/key-rotations/$rotationId/commit"),
        ) {
            bearerAuth(capability.token.asUtf8())
            jsonBody(
                RotationCommitRequestDto(
                    manifestCbor = request.manifestCborBase64Url,
                    manifestSignature = request.manifestSignatureBase64Url,
                    deviceEnvelopes = request.deviceEnvelopes.map {
                        RotationEnvelopeDto(it.deviceId, it.wrappedKeyBase64Url)
                    },
                    recoveryWrappedKey = request.recoveryWrappedKeyBase64Url,
                ),
            )
        }.decodeSuccess<RotationCommitResponseDto>()
        require(dto.rotationId == rotationId && dto.workspaceId == session.workspaceId) {
            "Rotation commit receipt identity mismatch"
        }
        return RotationCommitReceipt(
            rotationId = dto.rotationId,
            workspaceId = dto.workspaceId,
            fromEpoch = dto.fromEpoch,
            activeKeyEpoch = dto.activeKeyEpoch,
            keyCommitmentBase64Url = dto.keyCommitment,
            status = dto.status,
        )
    }

    override suspend fun acknowledgeRotation(
        session: SyncSession,
        capability: WorkspaceCapability,
        rotationId: String,
        keyCommitmentBase64Url: String,
        signatureBase64Url: String,
    ): RotationAcknowledgementReceipt {
        val dto = client.post(
            url(session.endpoint, "/v1/workspaces/${session.workspaceId}/key-rotations/$rotationId/ack"),
        ) {
            bearerAuth(capability.token.asUtf8())
            jsonBody(RotationAckRequestDto(keyCommitmentBase64Url, signatureBase64Url))
        }.decodeSuccess<RotationAckResponseDto>()
        require(dto.rotationId == rotationId && dto.deviceId == session.deviceId) {
            "Rotation acknowledgement identity mismatch"
        }
        return RotationAcknowledgementReceipt(dto.rotationId, dto.deviceId, dto.acknowledged)
    }

    override suspend fun workspaceKeyBootstrap(
        session: SyncSession,
        capability: WorkspaceCapability,
    ): WorkspaceKeyBootstrap {
        val dto = client.get(url(session.endpoint, "/v1/workspaces/${session.workspaceId}/bootstrap")) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
        }.decodeSuccess<KeyBootstrapDto>()
        require(dto.workspaceId == session.workspaceId) { "Key bootstrap workspace mismatch" }
        return WorkspaceKeyBootstrap(
            workspaceId = dto.workspaceId,
            activeKeyEpoch = dto.activeKeyEpoch,
            envelopes = dto.deviceKeyEnvelopes.map(DeviceKeyEnvelopeDto::toDomain),
            deviceDirectory = dto.deviceDirectory,
            rotationRequired = dto.rotationRequired,
        )
    }

    override suspend fun createRecoveryChallenge(endpoint: String, userId: String): RecoveryChallenge {
        val dto = client.post(url(endpoint, "/v1/recovery/challenge")) {
            jsonBody(RecoveryChallengeRequestDto(userId))
        }.decodeSuccess<RecoveryChallengeResponseDto>()
        return RecoveryChallenge(
            challengeId = dto.challengeId,
            challenge = SecretMaterial(dto.challenge.encodeToByteArray().asList()),
            expiresAtMillis = dto.expiresAt,
            workspaces = dto.workspaces.map {
                RecoveryWorkspaceChallenge(
                    workspaceId = it.workspaceId,
                    keyEpoch = it.keyEpoch,
                    keyCommitmentBase64Url = it.keyCommitment,
                    recoveryWrappedKeyBase64Url = it.recoveryWrappedKey,
                    retainedKeyEnvelopes = it.retainedKeyEnvelopes.map { retained ->
                        RecoveryEpochKeyEnvelope(
                            keyEpoch = retained.keyEpoch,
                            keyCommitmentBase64Url = retained.keyCommitment,
                            recoveryWrappedKeyBase64Url = retained.recoveryWrappedKey,
                        )
                    },
                )
            },
        )
    }

    override suspend fun claimRecovery(endpoint: String, request: RecoveryClaimRequest): RecoveryClaimReceipt {
        val body = recoveryClaimBody(request)
        val dto = client.post(url(endpoint, "/v1/recovery/claim")) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), body))
        }.decodeSuccess<RecoveryClaimResponseDto>()
        require(dto.userId == request.userId && dto.deviceId == request.device.deviceId) {
            "Recovery claim receipt identity mismatch"
        }
        return RecoveryClaimReceipt(
            dto.claimId,
            dto.userId,
            dto.deviceId,
            dto.rotationRequiredWorkspaces,
            dto.workspaceBindings.map {
                RecoveryWorkspaceBinding(
                    workspaceId = it.workspaceId,
                    deviceAuthEpoch = it.deviceAuthEpoch,
                    membershipAuthEpoch = it.membershipAuthEpoch,
                    activeKeyEpoch = it.activeKeyEpoch,
                )
            },
        )
    }

    private fun recoveryClaimBody(request: RecoveryClaimRequest): JsonObject {
        val challenge = request.challenge.asUtf8()
        val deviceCredential = request.device.deviceCredential.asUtf8()
        return buildJsonObject {
            put(
                "manifest",
                buildJsonObject {
                    put("instanceId", request.instanceId)
                    put("userId", request.userId)
                    put("challengeId", request.challengeId)
                    put("challenge", challenge)
                    put(
                        "device",
                        buildJsonObject {
                            put("deviceId", request.device.deviceId)
                            put("displayName", request.device.displayName)
                            put("platform", request.device.platform)
                            put("signingPublicKey", request.device.signingPublicKeyBase64Url)
                            put("wrappingPublicKey", request.device.wrappingPublicKeyBase64Url)
                            put("deviceToken", deviceCredential)
                        },
                    )
                    put(
                        "previousRecoverySigningPublicKey",
                        request.previousRecoverySigningPublicKeyBase64Url,
                    )
                    put("newRecoverySigningPublicKey", request.newRecoverySigningPublicKeyBase64Url)
                    put("newRecoveryWrappingPublicKey", request.newRecoveryWrappingPublicKeyBase64Url)
                    put(
                        "replacementRecoveryTrustSignature",
                        request.replacementRecoveryTrustSignatureBase64Url,
                    )
                    put(
                        "workspaceEnvelopes",
                        buildJsonArray {
                            request.workspaceEnvelopes.forEach { envelope ->
                                add(
                                    buildJsonObject {
                                        put("workspaceId", envelope.workspaceId)
                                        put("keyEpoch", envelope.keyEpoch)
                                        put("keyCommitment", envelope.keyCommitmentBase64Url)
                                        put("deviceWrappedKey", envelope.deviceWrappedKeyBase64Url)
                                        put("deviceEnvelopeSignature", envelope.deviceEnvelopeSignatureBase64Url)
                                        put(
                                            "recoveryKeyEnvelopes",
                                            buildJsonArray {
                                                envelope.replacementRecoveryEnvelopes.forEach { replacement ->
                                                    add(
                                                        buildJsonObject {
                                                            put("keyEpoch", replacement.keyEpoch)
                                                            put("keyCommitment", replacement.keyCommitmentBase64Url)
                                                            put("recoveryWrappedKey", replacement.recoveryWrappedKeyBase64Url)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                },
            )
            put("signature", request.signatureBase64Url)
        }
    }

    private fun url(endpoint: String, path: String): String {
        val base = endpoint.trim().trimEnd('/')
        require(isAllowedSyncEndpoint(base)) {
            "Sync endpoint must use HTTPS (HTTP is only allowed for local tests)"
        }
        require(!path.startsWith("//"))
        return base + path
    }

    private inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.jsonBody(value: T) {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(value))
    }

    private suspend inline fun <reified T> HttpResponse.decodeSuccess(): T {
        if (status.value !in 200..299) throw decodeFailure()
        return json.decodeFromString(bodyAsText())
    }

    private suspend fun HttpResponse.decodeFailure(): SyncApiException {
        val parsed = runCatching { json.decodeFromString<ControlErrorEnvelope>(bodyAsText()) }.getOrNull()
        return SyncApiException(
            statusCode = status.value,
            errorCode = parsed?.error?.code ?: "http_${status.value}",
            details = parsed?.error?.details ?: JsonObject(emptyMap()),
        )
    }
}

@Serializable
private data class RotationLeaseRequestDto(val rotationId: String, val fromEpoch: Int, val signature: String)

@Serializable
private data class RotationRecipientDto(
    val deviceId: String,
    val authEpoch: Long,
    val wrappingPublicKey: String,
)

@Serializable
private data class RotationRecoveryRecipientDto(val wrappingPublicKey: String, val authEpoch: Long)

@Serializable
private data class RotationLeaseResponseDto(
    val rotationId: String,
    val workspaceId: String,
    val fromEpoch: Int,
    val toEpoch: Int,
    val proposerDeviceId: String,
    val proposerDeviceAuthEpoch: Long,
    val membershipAuthEpoch: Long,
    val recipients: List<RotationRecipientDto>,
    val recovery: RotationRecoveryRecipientDto,
    val expiresAt: Long,
) {
    fun toDomain() = KeyRotationLease(
        rotationId = rotationId,
        workspaceId = workspaceId,
        fromEpoch = fromEpoch,
        toEpoch = toEpoch,
        proposerDeviceId = proposerDeviceId,
        proposerDeviceAuthEpoch = proposerDeviceAuthEpoch,
        membershipAuthEpoch = membershipAuthEpoch,
        recipients = recipients.map { RotationRecipient(it.deviceId, it.authEpoch, it.wrappingPublicKey) },
        recovery = RotationRecoveryRecipient(recovery.authEpoch, recovery.wrappingPublicKey),
        expiresAtMillis = expiresAt,
    )
}

@Serializable
private data class RotationEnvelopeDto(val deviceId: String, val wrappedKey: String)

@Serializable
private data class RotationCommitRequestDto(
    val manifestCbor: String,
    val manifestSignature: String,
    val deviceEnvelopes: List<RotationEnvelopeDto>,
    val recoveryWrappedKey: String,
)

@Serializable
private data class RotationCommitResponseDto(
    val rotationId: String,
    val workspaceId: String,
    val fromEpoch: Int,
    val activeKeyEpoch: Int,
    val keyCommitment: String,
    val status: String,
)

@Serializable
private data class RotationAckRequestDto(val keyCommitment: String, val signature: String)

@Serializable
private data class RotationAckResponseDto(val rotationId: String, val deviceId: String, val acknowledged: Boolean)

@Serializable
private data class RotationEvidenceDto(
    val manifestCbor: String,
    val manifestSignature: String,
    val proposerDeviceId: String,
    val proposerSigningPublicKey: String,
    val recipientEnvelopeHashes: Map<String, String>,
    val recipientAuthEpochs: Map<String, Long>,
    val recoveryEnvelopeHash: String,
    val status: String,
) {
    fun toDomain() = CommittedRotationEvidence(
        manifestCbor,
        manifestSignature,
        proposerDeviceId,
        proposerSigningPublicKey,
        recipientEnvelopeHashes,
        recipientAuthEpochs,
        recoveryEnvelopeHash,
        status,
    )
}

@Serializable
private data class DeviceKeyEnvelopeDto(
    val keyEpoch: Int,
    val rotationId: String,
    val keyCommitment: String,
    val wrappedKey: String,
    val wrappedByDeviceId: String,
    val signature: String,
    val rotationEvidence: RotationEvidenceDto? = null,
) {
    fun toDomain() = DeviceWorkspaceKeyEnvelope(
        keyEpoch,
        rotationId,
        keyCommitment,
        wrappedKey,
        wrappedByDeviceId,
        signature,
        rotationEvidence?.toDomain(),
    )
}

@Serializable
private data class KeyBootstrapDto(
    val workspaceId: String,
    val activeKeyEpoch: Int,
    val deviceKeyEnvelopes: List<DeviceKeyEnvelopeDto>,
    val deviceDirectory: DeviceDirectoryWire,
    val rotationRequired: Boolean,
)

@Serializable
private data class RecoveryChallengeRequestDto(val userId: String)

@Serializable
private data class RecoveryWorkspaceDto(
    val workspaceId: String,
    val keyEpoch: Int,
    val keyCommitment: String,
    val recoveryWrappedKey: String,
    val retainedKeyEnvelopes: List<RecoveryEpochKeyEnvelopeDto> = emptyList(),
)

@Serializable
private data class RecoveryEpochKeyEnvelopeDto(
    val keyEpoch: Int,
    val keyCommitment: String,
    val recoveryWrappedKey: String,
)

@Serializable
private data class RecoveryChallengeResponseDto(
    val challengeId: String,
    val challenge: String,
    val expiresAt: Long,
    val workspaces: List<RecoveryWorkspaceDto>,
)

@Serializable
private data class RecoveryClaimResponseDto(
    val claimId: String,
    val userId: String,
    val deviceId: String,
    val rotationRequiredWorkspaces: List<String>,
    val workspaceBindings: List<RecoveryWorkspaceBindingDto>,
)

@Serializable
private data class RecoveryWorkspaceBindingDto(
    val workspaceId: String,
    val deviceAuthEpoch: Long,
    val membershipAuthEpoch: Long,
    val activeKeyEpoch: Int,
)

@Serializable
private data class ControlErrorEnvelope(val error: ControlErrorBody)

@Serializable
private data class ControlErrorBody(
    val code: String,
    val message: String = code,
    val details: JsonObject = JsonObject(emptyMap()),
)
