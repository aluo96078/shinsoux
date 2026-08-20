package dev.shinsou.kmp.sync.network

import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import dev.shinsou.kmp.sync.trust.DeviceDirectoryEntryWire
import dev.shinsou.kmp.sync.trust.DeviceDirectoryWire
import dev.shinsou.kmp.sync.v2.AppendEventResult
import dev.shinsou.kmp.sync.v2.BinaryData
import dev.shinsou.kmp.sync.v2.BootstrapResponse
import dev.shinsou.kmp.sync.v2.CapabilityBinding
import dev.shinsou.kmp.sync.v2.CatchUpPage
import dev.shinsou.kmp.sync.v2.CheckpointAcknowledgementResult
import dev.shinsou.kmp.sync.v2.CheckpointCandidateDescriptor
import dev.shinsou.kmp.sync.v2.CheckpointLease
import dev.shinsou.kmp.sync.v2.CheckpointReplayAcknowledgement
import dev.shinsou.kmp.sync.v2.CheckpointRequiredException
import dev.shinsou.kmp.sync.v2.CloudflareSyncApi
import dev.shinsou.kmp.sync.v2.EncryptedSyncCheckpoint
import dev.shinsou.kmp.sync.v2.EncryptedSyncEvent
import dev.shinsou.kmp.sync.v2.RemoteCommittedEnvelope
import dev.shinsou.kmp.sync.v2.RemoteCheckpointVerificationException
import dev.shinsou.kmp.sync.v2.RemoteCheckpointStatus
import dev.shinsou.kmp.sync.v2.RetainedCheckpointDescriptor
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncCapabilities
import dev.shinsou.kmp.sync.v2.SyncCrypto
import dev.shinsou.kmp.sync.v2.SyncReceipt
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.WorkspaceCapability
import dev.shinsou.kmp.sync.v2.isAllowedSyncEndpoint
import dev.shinsou.kmp.sync.v2.requireSecret
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class SyncApiException(
    val statusCode: Int,
    val errorCode: String,
    val details: JsonObject = JsonObject(emptyMap()),
) : IllegalStateException("Sync API request failed ($statusCode/$errorCode)")

/** REST client with redirect refusal and strict response/header reconstruction. */
class KtorCloudflareSyncApi(
    private val client: HttpClient,
    private val secretStore: SyncSecretStore,
    private val crypto: SyncCrypto,
    private val codec: DeterministicSyncEventCodec,
    private val json: Json = SyncNetworkJson,
) : CloudflareSyncApi {
    override suspend fun capabilities(endpoint: String): SyncCapabilities {
        val response = client.get(url(endpoint, "/v1/capabilities")) { accept(ContentType.Application.Json) }
        val dto = response.decodeSuccess<CapabilitiesDto>()
        return SyncCapabilities(
            protocolVersion = dto.protocolVersion,
            minReaderVersion = dto.minReaderVersion,
            minWriterVersion = dto.minWriterVersion,
            schemaVersion = dto.schemaVersion,
            minSchemaReaderVersion = dto.minSchemaReaderVersion,
            minSchemaWriterVersion = dto.minSchemaWriterVersion,
            realtimeAvailable = dto.websocket && dto.profile == "realtime",
            maxEventBytes = dto.event.maximumBytes,
            maxBatchBytes = MAX_BATCH_BYTES,
            maxCheckpointBytes = dto.checkpoint.maximumBytes,
        )
    }

    override suspend fun obtainWorkspaceCapability(session: SyncSession): WorkspaceCapability {
        val challenge = client.post(url(session.endpoint, "/v1/auth/challenge")) {
            jsonBody(AuthChallengeRequest(session.deviceId))
        }.decodeSuccess<AuthChallengeResponse>()
        val challengeMessage = BinaryData.copyOf(
            (AUTH_CHALLENGE_DOMAIN + "${challenge.challengeId}\n${challenge.challenge}\n${session.deviceId}")
                .encodeToByteArray(),
        )
        val signature = encodeBase64Url(crypto.signDeviceMessage(challengeMessage).copyBytes())
        val deviceToken = secretText(SyncSecretKey.DeviceCredential)
        val access = client.post(url(session.endpoint, "/v1/auth/token")) {
            jsonBody(
                AuthTokenRequest(
                    deviceId = session.deviceId,
                    challengeId = challenge.challengeId,
                    challenge = challenge.challenge,
                    deviceToken = deviceToken,
                    signature = signature,
                ),
            )
        }.decodeSuccess<AuthTokenResponse>()
        secretStore.write(SyncSecretKey.AccessToken, SecretMaterial(access.accessToken.encodeToByteArray().asList()))
        val capability = client.post(url(session.endpoint, "/v1/auth/capability")) {
            bearerAuth(access.accessToken)
            jsonBody(CapabilityRequest(session.workspaceId))
        }.decodeSuccess<CapabilityResponse>()
        require(capability.deviceId == session.deviceId && capability.workspaceId == session.workspaceId) {
            "Workspace capability tenant binding mismatch"
        }
        secretStore.write(
            SyncSecretKey.WorkspaceCapability(session.workspaceId),
            SecretMaterial(capability.capability.encodeToByteArray().asList()),
        )
        return WorkspaceCapability(
            token = SecretMaterial(capability.capability.encodeToByteArray().asList()),
            binding = CapabilityBinding(
                deviceId = capability.deviceId,
                workspaceId = capability.workspaceId,
                deviceAuthEpoch = capability.deviceAuthEpoch,
                membershipAuthEpoch = capability.membershipAuthEpoch,
                keyEpoch = capability.keyEpoch,
                expiresAtMillis = capability.expiresAt,
            ),
        )
    }

    override suspend fun appendEvent(
        session: SyncSession,
        capability: WorkspaceCapability,
        event: EncryptedSyncEvent,
    ): AppendEventResult {
        val response = client.post(url(session.endpoint, "/v1/workspaces/${session.workspaceId}/events")) {
            bearerAuth(capability.token.asUtf8())
            jsonBody(
                EventUploadDto(
                    headerCbor = event.authenticatedHeaderBase64Url,
                    ciphertext = event.ciphertextBase64Url,
                    ciphertextSha256 = event.header.ciphertextSha256Base64Url,
                    signature = event.signatureBase64Url,
                ),
            )
        }
        if (response.status.value in 200..299) {
            val receipt = response.decodeSuccess<EventReceiptDto>()
            return AppendEventResult.Committed(
                receipt = SyncReceipt(
                    eventId = receipt.eventId,
                    deviceSeq = receipt.deviceSeq,
                    workspaceSeq = receipt.workspaceSeq,
                    ciphertextSha256Base64Url = receipt.ciphertextSha256,
                ),
                headSeq = receipt.headSeq,
            )
        }
        val failure = response.decodeFailure()
        return when {
            response.status.value == 409 && failure.errorCode == "stale_key_epoch" ->
                AppendEventResult.StaleKeyEpoch(
                    activeKeyEpoch = failure.details["expectedKeyEpoch"]?.jsonPrimitive?.longOrNull?.toInt()
                        ?: session.activeKeyEpoch,
                    expectedDeviceSeq = event.header.deviceSeq,
                )
            response.status.value == 409 && failure.errorCode == "key_rotation_required" ->
                AppendEventResult.KeyRotationRequired(
                    activeKeyEpoch = failure.details["expectedKeyEpoch"]?.jsonPrimitive?.longOrNull?.toInt()
                        ?: session.activeKeyEpoch,
                )
            response.status.value == 409 && failure.errorCode == "replay_or_corruption" ->
                AppendEventResult.ReplayOrCorruption(failure.errorCode)
            response.status.value == 409 && failure.errorCode == "device_sequence_gap" ->
                AppendEventResult.ReplayOrCorruption(failure.errorCode)
            response.status.value == 429 -> AppendEventResult.RateLimited(response.retryAfterMillis())
            response.status.value == 507 -> AppendEventResult.QuotaExceeded(failure.errorCode)
            response.status.value == 426 -> AppendEventResult.IncompatibleProtocol(
                minReaderVersion = failure.details["minReaderVersion"]?.jsonPrimitive?.longOrNull?.toInt() ?: 1,
                minWriterVersion = failure.details["minWriterVersion"]?.jsonPrimitive?.longOrNull?.toInt() ?: 1,
            )
            response.status.value == 403 && failure.errorCode == "device_revoked" -> AppendEventResult.DeviceRevoked
            else -> AppendEventResult.Retryable(failure.errorCode)
        }
    }

    override suspend fun eventReceipt(
        session: SyncSession,
        capability: WorkspaceCapability,
        deviceSeq: Long,
    ): SyncReceipt? {
        require(deviceSeq > 0)
        val response = client.get(
            url(
                session.endpoint,
                "/v1/workspaces/${session.workspaceId}/events/receipts/$deviceSeq",
            ),
        ) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
        }
        if (response.status.value == 404) {
            val failure = response.decodeFailure()
            if (failure.errorCode == "event_receipt_not_found") return null
            throw failure
        }
        val receipt = response.decodeSuccess<EventReceiptDto>()
        require(receipt.deviceSeq == deviceSeq) { "Event receipt sequence mismatch" }
        return SyncReceipt(
            eventId = receipt.eventId,
            deviceSeq = receipt.deviceSeq,
            workspaceSeq = receipt.workspaceSeq,
            ciphertextSha256Base64Url = receipt.ciphertextSha256,
        )
    }

    override suspend fun catchUp(
        session: SyncSession,
        capability: WorkspaceCapability,
        afterExclusive: Long,
        untilInclusive: Long?,
        limit: Int,
    ): CatchUpPage {
        require(afterExclusive >= 0 && limit in 1..500)
        val until = untilInclusive?.let { "&until=$it" }.orEmpty()
        val response = client.get(
            url(session.endpoint, "/v1/workspaces/${session.workspaceId}/events?after=$afterExclusive$until&limit=$limit"),
        ) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
        }
        if (response.status.value == 410) {
            val failure = response.decodeFailure()
            val descriptors = failure.details["retainedStableCheckpoints"]
                ?.let { json.decodeFromJsonElement<List<CheckpointDescriptorDto>>(it) }
                .orEmpty()
                .map(CheckpointDescriptorDto::toDomain)
            throw CheckpointRequiredException(
                headSeq = failure.details["headSeq"]?.jsonPrimitive?.longOrNull ?: afterExclusive,
                retainedCheckpoints = descriptors,
            )
        }
        val page = response.decodeSuccess<CatchUpDto>()
        val events = page.events.map { wire ->
            val headerBytes = decodeBase64Url(wire.headerCbor)
            val header = codec.decodeEventAssociatedData(BinaryData.copyOf(headerBytes), wire.ciphertextSha256)
            require(
                header.eventId == wire.eventId && header.deviceId == wire.deviceId &&
                    header.deviceSeq == wire.deviceSeq && header.keyEpoch == wire.keyEpoch,
            ) { "Catch-up visible fields do not match authenticated header" }
            RemoteCommittedEnvelope(
                workspaceSeq = wire.workspaceSeq,
                envelope = EncryptedSyncEvent(
                    header = header,
                    authenticatedHeaderBase64Url = wire.headerCbor,
                    ciphertextBase64Url = wire.ciphertext,
                    signatureBase64Url = wire.signature,
                ),
            )
        }
        return CatchUpPage(
            fromExclusive = page.fromExclusive,
            untilInclusive = page.untilInclusive,
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
            headSeq = page.headSeq,
            stableCheckpointSeq = page.stableCheckpointSeq,
            events = events,
            senderDeviceDirectory = page.senderDeviceDirectory(),
        )
    }

    override suspend fun bootstrap(
        session: SyncSession,
        capability: WorkspaceCapability,
    ): BootstrapResponse {
        val response = client.get(url(session.endpoint, "/v1/workspaces/${session.workspaceId}/bootstrap")) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
        }.decodeSuccess<BootstrapDto>()
        require(response.workspaceId == session.workspaceId)
        return BootstrapResponse(
            headSeq = response.headSeq,
            activeKeyEpoch = response.activeKeyEpoch,
            retainedStableCheckpoints = response.retainedStableCheckpoints.map(CheckpointDescriptorDto::toDomain),
            requiredKeyEpochs = response.requiredKeyEpochs.toSet(),
            candidateCheckpoint = response.candidateCheckpoint?.toDomain(),
            deviceDirectory = response.deviceDirectory,
        )
    }

    override suspend fun downloadCheckpoint(
        session: SyncSession,
        capability: WorkspaceCapability,
        descriptor: RetainedCheckpointDescriptor,
    ): EncryptedSyncCheckpoint {
        val response = client.get(
            url(session.endpoint, "/v1/workspaces/${session.workspaceId}/checkpoints/${descriptor.checkpointId}"),
        ) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.OctetStream)
        }
        if (response.status.value !in 200..299) throw response.decodeFailure()
        val ciphertext: ByteArray = response.body()
        if (ciphertext.size > MAX_CHECKPOINT_BYTES) {
            throw RemoteCheckpointVerificationException("Checkpoint response exceeds the client hard limit")
        }
        val hash = response.headers["X-Shinsou-Ciphertext-Sha256"]
            ?: throw RemoteCheckpointVerificationException("Checkpoint response omitted ciphertext hash")
        if (hash != descriptor.ciphertextSha256Base64Url) {
            throw RemoteCheckpointVerificationException("Checkpoint response hash differs from descriptor")
        }
        val headerCbor = response.headers["X-Shinsou-Header-Cbor"]
            ?: throw RemoteCheckpointVerificationException("Checkpoint response omitted authenticated header")
        val signature = response.headers["X-Shinsou-Device-Signature"]
            ?: throw RemoteCheckpointVerificationException("Checkpoint response omitted device signature")
        val header = try {
            codec.decodeCheckpointAssociatedData(BinaryData.copyOf(decodeBase64Url(headerCbor)), hash)
        } catch (failure: Exception) {
            throw RemoteCheckpointVerificationException("Checkpoint response header is invalid", failure)
        }
        if (header.checkpointId != descriptor.checkpointId) {
            throw RemoteCheckpointVerificationException("Checkpoint response identity mismatch")
        }
        return EncryptedSyncCheckpoint(
            header = header,
            authenticatedHeaderBase64Url = headerCbor,
            ciphertextBase64Url = encodeBase64Url(ciphertext),
            signatureBase64Url = signature,
        )
    }

    override suspend fun createCheckpointLease(
        session: SyncSession,
        capability: WorkspaceCapability,
        checkpointId: String,
        ciphertextSha256Base64Url: String,
        expectedByteSize: Int,
        throughWorkspaceSeq: Long,
    ): CheckpointLease {
        require(expectedByteSize in 1..MAX_CHECKPOINT_BYTES && throughWorkspaceSeq >= 0)
        val response = client.post(
            url(session.endpoint, "/v1/workspaces/${session.workspaceId}/checkpoint-leases"),
        ) {
            bearerAuth(capability.token.asUtf8())
            jsonBody(
                CheckpointLeaseRequestDto(
                    checkpointId = checkpointId,
                    ciphertextSha256 = ciphertextSha256Base64Url,
                    expectedByteSize = expectedByteSize,
                    throughWorkspaceSeq = throughWorkspaceSeq,
                ),
            )
        }.decodeSuccess<CheckpointLeaseDto>()
        require(
            response.checkpointId == checkpointId &&
                response.ciphertextSha256 == ciphertextSha256Base64Url &&
                response.throughWorkspaceSeq == throughWorkspaceSeq,
        ) { "Checkpoint lease binding mismatch" }
        return response.toDomain()
    }

    override suspend fun uploadCheckpoint(
        session: SyncSession,
        capability: WorkspaceCapability,
        lease: CheckpointLease,
        checkpoint: EncryptedSyncCheckpoint,
    ) {
        val header = checkpoint.header
        require(header.checkpointId == lease.checkpointId)
        require(header.ciphertextSha256Base64Url == lease.ciphertextSha256Base64Url)
        require(header.throughWorkspaceSeq == lease.throughWorkspaceSeq && header.keyEpoch == lease.keyEpoch)
        val ciphertext = decodeBase64Url(checkpoint.ciphertextBase64Url)
        require(ciphertext.size in 1..MAX_CHECKPOINT_BYTES)
        val response = client.put(
            url(session.endpoint, "/v1/workspaces/${session.workspaceId}/checkpoints/${lease.checkpointId}"),
        ) {
            bearerAuth(capability.token.asUtf8())
            contentType(ContentType.Application.OctetStream)
            header("X-Shinsou-Header-Cbor", checkpoint.authenticatedHeaderBase64Url)
            header("X-Shinsou-Ciphertext-Sha256", lease.ciphertextSha256Base64Url)
            header("X-Shinsou-Device-Signature", checkpoint.signatureBase64Url)
            setBody(ciphertext)
        }.decodeSuccess<CheckpointUploadDto>()
        require(
            response.checkpointId == lease.checkpointId &&
                response.ciphertextSha256 == lease.ciphertextSha256Base64Url &&
                response.byteSize == ciphertext.size && response.uploaded,
        ) { "Checkpoint upload receipt mismatch" }
    }

    override suspend fun commitCheckpoint(
        session: SyncSession,
        capability: WorkspaceCapability,
        lease: CheckpointLease,
    ): CheckpointCandidateDescriptor {
        val response = client.post(
            url(
                session.endpoint,
                "/v1/workspaces/${session.workspaceId}/checkpoints/${lease.checkpointId}/commit",
            ),
        ) {
            bearerAuth(capability.token.asUtf8())
            jsonBody(CheckpointCommitRequestDto(lease.ciphertextSha256Base64Url))
        }.decodeSuccess<CheckpointCandidateDto>()
        require(response.status == "candidate") { "Server did not commit a checkpoint candidate" }
        return response.toDomain().also { candidate ->
            require(
                candidate.checkpointId == lease.checkpointId &&
                    candidate.ciphertextSha256Base64Url == lease.ciphertextSha256Base64Url &&
                    candidate.throughWorkspaceSeq == lease.throughWorkspaceSeq &&
                    candidate.keyEpoch == lease.keyEpoch,
            ) { "Checkpoint commit receipt mismatch" }
        }
    }

    override suspend fun acknowledgeCheckpoint(
        session: SyncSession,
        capability: WorkspaceCapability,
        acknowledgement: CheckpointReplayAcknowledgement,
    ): CheckpointAcknowledgementResult {
        val response = client.post(
            url(
                session.endpoint,
                "/v1/workspaces/${session.workspaceId}/checkpoints/${acknowledgement.checkpointId}/ack",
            ),
        ) {
            bearerAuth(capability.token.asUtf8())
            jsonBody(
                CheckpointAckRequestDto(
                    ciphertextSha256 = acknowledgement.ciphertextSha256Base64Url,
                    validationVersion = acknowledgement.validationVersion,
                    replayFromSeq = acknowledgement.replayFromSeq,
                    replayedThroughSeq = acknowledgement.replayedThroughSeq,
                    replayedEventCount = acknowledgement.replayedEventCount,
                    previousStableCheckpointId = acknowledgement.previousStableCheckpointId,
                    previousStableSha256 = acknowledgement.previousStableCiphertextSha256Base64Url,
                    valid = acknowledgement.valid,
                    signature = acknowledgement.signatureBase64Url,
                ),
            )
        }.decodeSuccess<CheckpointAckResponseDto>()
        require(
            response.checkpointId == acknowledgement.checkpointId &&
                response.ciphertextSha256 == acknowledgement.ciphertextSha256Base64Url,
        ) { "Checkpoint acknowledgement receipt mismatch" }
        return CheckpointAcknowledgementResult(
            checkpointId = response.checkpointId,
            ciphertextSha256Base64Url = response.ciphertextSha256,
            status = response.status.toCheckpointStatus(),
            throughWorkspaceSeq = response.throughWorkspaceSeq,
        )
    }

    internal fun parseRealtimeEvent(workspaceSeq: Long, wire: EventUploadDto): RemoteCommittedEnvelope {
        val header = codec.decodeEventAssociatedData(
            BinaryData.copyOf(decodeBase64Url(wire.headerCbor)),
            wire.ciphertextSha256,
        )
        return RemoteCommittedEnvelope(
            workspaceSeq,
            EncryptedSyncEvent(
                header,
                wire.headerCbor,
                wire.ciphertext,
                wire.signature,
            ),
        )
    }

    private suspend fun secretText(key: SyncSecretKey): String = secretStore.requireSecret(key).asUtf8()

    private fun url(endpoint: String, path: String): String {
        val base = endpoint.trim().trimEnd('/')
        require(isAllowedSyncEndpoint(base)) {
            "Sync endpoint must use HTTPS (HTTP is only allowed for local tests)"
        }
        require(!path.startsWith("//"))
        return base + path
    }

    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(value: Any) {
        contentType(ContentType.Application.Json)
        val serializer = when (value) {
            is AuthChallengeRequest -> AuthChallengeRequest.serializer()
            is AuthTokenRequest -> AuthTokenRequest.serializer()
            is CapabilityRequest -> CapabilityRequest.serializer()
            is EventUploadDto -> EventUploadDto.serializer()
            is CheckpointLeaseRequestDto -> CheckpointLeaseRequestDto.serializer()
            is CheckpointCommitRequestDto -> CheckpointCommitRequestDto.serializer()
            is CheckpointAckRequestDto -> CheckpointAckRequestDto.serializer()
            else -> error("Unsupported sync request body")
        }
        @Suppress("UNCHECKED_CAST")
        setBody(json.encodeToString(serializer as kotlinx.serialization.KSerializer<Any>, value))
    }

    private suspend inline fun <reified T> HttpResponse.decodeSuccess(): T {
        if (status.value !in 200..299) throw decodeFailure()
        return json.decodeFromString(bodyAsText())
    }

    private suspend fun HttpResponse.decodeFailure(): SyncApiException {
        val parsed = runCatching { json.decodeFromString<ErrorEnvelope>(bodyAsText()) }.getOrNull()
        return SyncApiException(
            statusCode = status.value,
            errorCode = parsed?.error?.code ?: "http_${status.value}",
            details = parsed?.error?.details ?: JsonObject(emptyMap()),
        )
    }

    private fun HttpResponse.retryAfterMillis(): Long? = headers[HttpHeaders.RetryAfter]
        ?.toLongOrNull()
        ?.coerceAtLeast(0)
        ?.times(1_000)
}

@Serializable
private data class AuthChallengeRequest(val deviceId: String)

@Serializable
private data class AuthChallengeResponse(val challengeId: String, val challenge: String, val expiresAt: Long)

@Serializable
private data class AuthTokenRequest(
    val deviceId: String,
    val challengeId: String,
    val challenge: String,
    val deviceToken: String,
    val signature: String,
)

@Serializable
private data class AuthTokenResponse(val accessToken: String, val expiresAt: Long)

@Serializable
private data class CapabilityRequest(val workspaceId: String)

@Serializable
private data class CapabilityResponse(
    val capability: String,
    val capabilityId: String,
    val workspaceId: String,
    val deviceId: String,
    val deviceAuthEpoch: Long,
    val membershipAuthEpoch: Long,
    val keyEpoch: Int,
    val expiresAt: Long,
)

@Serializable
private data class SizeLimitDto(val envelopeVersion: Int, val maximumBytes: Int)

@Serializable
private data class CapabilitiesDto(
    val protocolVersion: Int,
    val minReaderVersion: Int,
    val minWriterVersion: Int,
    val schemaVersion: Int,
    val minSchemaReaderVersion: Int,
    val minSchemaWriterVersion: Int,
    val profile: String,
    val websocket: Boolean,
    val checkpoint: SizeLimitDto,
    val event: SizeLimitDto,
)

@Serializable
internal data class EventUploadDto(
    val headerCbor: String,
    val ciphertext: String,
    val ciphertextSha256: String,
    val signature: String,
)

@Serializable
private data class EventReceiptDto(
    val duplicate: Boolean,
    val eventId: String,
    val deviceSeq: Long,
    val workspaceSeq: Long,
    val headSeq: Long,
    val ciphertextSha256: String,
)

@Serializable
private data class RemoteEventDto(
    val workspaceSeq: Long,
    val eventId: String,
    val deviceId: String,
    val deviceSeq: Long,
    val keyEpoch: Int,
    val headerCbor: String,
    val ciphertext: String,
    val ciphertextSha256: String,
    val signature: String,
)

@Serializable
private data class CatchUpDto(
    val fromExclusive: Long,
    val untilInclusive: Long,
    val nextCursor: Long,
    val hasMore: Boolean,
    val headSeq: Long,
    val stableCheckpointSeq: Long,
    val deviceDirectoryVersion: Long? = null,
    val deviceDirectoryHash: String? = null,
    val deviceDirectoryAllDeviceCount: Int? = null,
    val senderDevices: List<DeviceDirectoryEntryWire>? = null,
    val events: List<RemoteEventDto>,
) {
    fun senderDeviceDirectory(): DeviceDirectoryWire? {
        val values = listOf(
            deviceDirectoryVersion,
            deviceDirectoryHash,
            deviceDirectoryAllDeviceCount,
            senderDevices,
        )
        if (values.all { it == null }) return null
        require(values.none { it == null }) { "Catch-up device directory metadata is incomplete" }
        return DeviceDirectoryWire(
            version = requireNotNull(deviceDirectoryVersion),
            hash = requireNotNull(deviceDirectoryHash),
            allDeviceCount = requireNotNull(deviceDirectoryAllDeviceCount),
            devices = requireNotNull(senderDevices),
        )
    }
}

@Serializable
private data class CheckpointDescriptorDto(
    val checkpointId: String,
    val throughWorkspaceSeq: Long,
    val keyEpoch: Int,
    @SerialName("ciphertextSha256") val ciphertextSha256: String,
    @SerialName("previousStableCheckpointHash") val previousStableCheckpointHash: String? = null,
) {
    fun toDomain(): RetainedCheckpointDescriptor = RetainedCheckpointDescriptor(
        checkpointId,
        throughWorkspaceSeq,
        keyEpoch,
        ciphertextSha256,
        previousStableCheckpointHash,
    )
}

@Serializable
private data class CheckpointCandidateDto(
    val checkpointId: String,
    val throughWorkspaceSeq: Long,
    val keyEpoch: Int,
    @SerialName("ciphertextSha256") val ciphertextSha256: String,
    val uploaderDeviceId: String,
    @SerialName("createdAt") val createdAt: Long,
    val previousStableCheckpointId: String? = null,
    val previousStableThroughWorkspaceSeq: Long = 0,
    @SerialName("previousStableCheckpointHash") val previousStableCheckpointHash: String? = null,
    val status: String = "candidate",
) {
    fun toDomain(): CheckpointCandidateDescriptor = CheckpointCandidateDescriptor(
        checkpointId = checkpointId,
        throughWorkspaceSeq = throughWorkspaceSeq,
        keyEpoch = keyEpoch,
        ciphertextSha256Base64Url = ciphertextSha256,
        uploaderDeviceId = uploaderDeviceId,
        createdAtMillis = createdAt,
        previousStableCheckpointId = previousStableCheckpointId,
        previousStableThroughWorkspaceSeq = previousStableThroughWorkspaceSeq,
        previousStableCiphertextSha256Base64Url = previousStableCheckpointHash,
    )
}

@Serializable
private data class BootstrapDto(
    val workspaceId: String,
    val headSeq: Long,
    val activeKeyEpoch: Int,
    val retainedStableCheckpoints: List<CheckpointDescriptorDto>,
    val requiredKeyEpochs: List<Int>,
    val candidateCheckpoint: CheckpointCandidateDto? = null,
    val deviceDirectory: DeviceDirectoryWire? = null,
)

@Serializable
private data class CheckpointLeaseRequestDto(
    val checkpointId: String,
    val ciphertextSha256: String,
    val expectedByteSize: Int,
    val throughWorkspaceSeq: Long,
)

@Serializable
private data class CheckpointLeaseDto(
    val leaseId: String,
    val checkpointId: String,
    val ciphertextSha256: String,
    val throughWorkspaceSeq: Long,
    val keyEpoch: Int,
    val expiresAt: Long,
) {
    fun toDomain(): CheckpointLease = CheckpointLease(
        leaseId,
        checkpointId,
        ciphertextSha256,
        throughWorkspaceSeq,
        keyEpoch,
        expiresAt,
    )
}

@Serializable
private data class CheckpointUploadDto(
    val checkpointId: String,
    val ciphertextSha256: String,
    val byteSize: Int,
    val uploaded: Boolean,
    val duplicate: Boolean = false,
)

@Serializable
private data class CheckpointCommitRequestDto(val ciphertextSha256: String)

@Serializable
private data class CheckpointAckRequestDto(
    val ciphertextSha256: String,
    val validationVersion: Int,
    val replayFromSeq: Long,
    val replayedThroughSeq: Long,
    val replayedEventCount: Int,
    val previousStableCheckpointId: String?,
    val previousStableSha256: String?,
    val valid: Boolean,
    val signature: String,
)

@Serializable
private data class CheckpointAckResponseDto(
    val checkpointId: String,
    val ciphertextSha256: String,
    val status: String,
    val throughWorkspaceSeq: Long,
)

@Serializable
private data class ErrorEnvelope(val error: ErrorBody)

@Serializable
private data class ErrorBody(
    val code: String,
    val message: String = code,
    @SerialName("details") private val nestedDetails: JsonObject? = null,
    private val expectedKeyEpoch: JsonElement? = null,
    private val expectedDeviceSeq: JsonElement? = null,
    private val minReaderVersion: JsonElement? = null,
    private val minWriterVersion: JsonElement? = null,
    private val headSeq: JsonElement? = null,
    private val retainedStableCheckpoints: JsonElement? = null,
) {
    val details: JsonObject
        get() = nestedDetails ?: JsonObject(
            listOfNotNull(
                expectedKeyEpoch?.let { "expectedKeyEpoch" to it },
                expectedDeviceSeq?.let { "expectedDeviceSeq" to it },
                minReaderVersion?.let { "minReaderVersion" to it },
                minWriterVersion?.let { "minWriterVersion" to it },
                headSeq?.let { "headSeq" to it },
                retainedStableCheckpoints?.let { "retainedStableCheckpoints" to it },
            ).toMap(),
        )
}

internal val SyncNetworkJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

internal fun SecretMaterial.asUtf8(): String {
    var result: String? = null
    useBytes { result = it.decodeToString(throwOnInvalidSequence = true) }
    return requireNotNull(result)
}

internal fun String.toCheckpointStatus(): RemoteCheckpointStatus = when (this) {
    "candidate" -> RemoteCheckpointStatus.CANDIDATE
    "stable" -> RemoteCheckpointStatus.STABLE
    "rejected" -> RemoteCheckpointStatus.REJECTED
    else -> throw IllegalArgumentException("Unknown checkpoint status")
}

internal fun encodeBase64Url(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val result = StringBuilder((bytes.size * 4 + 2) / 3)
    var index = 0
    while (index + 2 < bytes.size) {
        val value = ((bytes[index].toInt() and 0xff) shl 16) or
            ((bytes[index + 1].toInt() and 0xff) shl 8) or (bytes[index + 2].toInt() and 0xff)
        result.append(alphabet[(value ushr 18) and 63])
        result.append(alphabet[(value ushr 12) and 63])
        result.append(alphabet[(value ushr 6) and 63])
        result.append(alphabet[value and 63])
        index += 3
    }
    val remaining = bytes.size - index
    if (remaining == 1) {
        val value = (bytes[index].toInt() and 0xff) shl 16
        result.append(alphabet[(value ushr 18) and 63])
        result.append(alphabet[(value ushr 12) and 63])
    } else if (remaining == 2) {
        val value = ((bytes[index].toInt() and 0xff) shl 16) or ((bytes[index + 1].toInt() and 0xff) shl 8)
        result.append(alphabet[(value ushr 18) and 63])
        result.append(alphabet[(value ushr 12) and 63])
        result.append(alphabet[(value ushr 6) and 63])
    }
    return result.toString()
}

internal fun decodeBase64Url(value: String): ByteArray {
    require(value.matches(Regex("^[A-Za-z0-9_-]*$")) && value.length % 4 != 1) { "Invalid base64url" }
    val reverse = IntArray(128) { -1 }
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".forEachIndexed { index, character ->
        reverse[character.code] = index
    }
    val output = ByteArray(value.length * 3 / 4)
    var inputIndex = 0
    var outputIndex = 0
    while (inputIndex + 4 <= value.length) {
        val bits = (reverse[value[inputIndex].code] shl 18) or
            (reverse[value[inputIndex + 1].code] shl 12) or
            (reverse[value[inputIndex + 2].code] shl 6) or reverse[value[inputIndex + 3].code]
        output[outputIndex++] = (bits ushr 16).toByte()
        output[outputIndex++] = (bits ushr 8).toByte()
        output[outputIndex++] = bits.toByte()
        inputIndex += 4
    }
    if (value.length - inputIndex == 2) {
        val bits = (reverse[value[inputIndex].code] shl 18) or (reverse[value[inputIndex + 1].code] shl 12)
        require(bits and 0xffff == 0) { "Non-canonical base64url tail" }
        output[outputIndex++] = (bits ushr 16).toByte()
    } else if (value.length - inputIndex == 3) {
        val bits = (reverse[value[inputIndex].code] shl 18) or
            (reverse[value[inputIndex + 1].code] shl 12) or (reverse[value[inputIndex + 2].code] shl 6)
        require(bits and 0xff == 0) { "Non-canonical base64url tail" }
        output[outputIndex++] = (bits ushr 16).toByte()
        output[outputIndex++] = (bits ushr 8).toByte()
    }
    return output.copyOf(outputIndex).also { require(encodeBase64Url(it) == value) { "Non-canonical base64url" } }
}

private const val AUTH_CHALLENGE_DOMAIN = "shinsou:auth-challenge:v1\u0000"
private const val MAX_BATCH_BYTES = 256 * 1024
private const val MAX_CHECKPOINT_BYTES = 32 * 1024 * 1024
