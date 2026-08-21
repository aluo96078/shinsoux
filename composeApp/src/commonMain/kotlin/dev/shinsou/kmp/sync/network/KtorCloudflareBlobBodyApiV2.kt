package dev.shinsou.kmp.sync.network

import dev.shinsou.kmp.sync.v2.BinaryData
import dev.shinsou.kmp.sync.v2.BlobBodyCommitReceiptV2
import dev.shinsou.kmp.sync.v2.BlobChunkReceiptV2
import dev.shinsou.kmp.sync.v2.BlobDekEnvelopeV2
import dev.shinsou.kmp.sync.v2.BlobEnvelopeRecoveryV2
import dev.shinsou.kmp.sync.v2.BlobEnvelopeRewrapRequestV2
import dev.shinsou.kmp.sync.v2.BlobGcReceiptV2
import dev.shinsou.kmp.sync.v2.BlobGcRequestV2
import dev.shinsou.kmp.sync.v2.BlobManifestCommitRequestV2
import dev.shinsou.kmp.sync.v2.BlobReferenceTombstoneRequestV2
import dev.shinsou.kmp.sync.v2.BlobReferenceRevivalRequestV2
import dev.shinsou.kmp.sync.v2.BlobReferenceRevivalResultV2
import dev.shinsou.kmp.sync.v2.BlobTombstoneCreationResultV2
import dev.shinsou.kmp.sync.v2.BlobTombstoneAckRequestV2
import dev.shinsou.kmp.sync.v2.BlobUploadReservationRequestV2
import dev.shinsou.kmp.sync.v2.BlobUploadSessionV2
import dev.shinsou.kmp.sync.v2.CloudflareBlobBodyApiV2
import dev.shinsou.kmp.sync.v2.CommittedEncryptedBlobManifestV2
import dev.shinsou.kmp.sync.v2.EncryptedBlobChunkPlanV2
import dev.shinsou.kmp.sync.v2.EncryptedBlobChunkV2
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.WorkspaceCapability
import dev.shinsou.kmp.sync.v2.isAllowedSyncEndpoint
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
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A successful body response did not match the authenticated metadata requested by the client. */
public class RemoteBlobBodyVerificationException(message: String) : IllegalStateException(message)

/**
 * Strict v2 encrypted-body transport. JSON field names are the serial names of the protocol
 * domain DTOs; chunk ciphertext alone uses an octet-stream body and an authenticated hash header.
 */
public class KtorCloudflareBlobBodyApiV2(
    private val client: HttpClient,
    private val json: Json = BlobBodyNetworkJson,
) : CloudflareBlobBodyApiV2 {
    override suspend fun reserveUpload(
        session: SyncSession,
        capability: WorkspaceCapability,
        request: BlobUploadReservationRequestV2,
    ): BlobUploadSessionV2 {
        requireBoundCapability(session, capability)
        requireNotNull(request.transferKey) { "Blob upload request omitted its local authority binding" }
            .requireBoundTo(session)
        require(request.transferKey.blobId == request.blobId) {
            "Blob upload request authority binding has another blob id"
        }
        require(request.keyEpoch == session.activeKeyEpoch) {
            "Blob upload reservation requires the current workspace key epoch"
        }
        val response = client.post(workspaceUrl(session, "/blob-upload-sessions")) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
            jsonBody(request)
        }.decodeSuccess<BlobUploadSessionV2>()
        require(
            response.blobId == request.blobId &&
                response.manifestId == request.manifestId &&
                response.keyEpoch == request.keyEpoch &&
                response.reservedBytes == request.totalReservedBytes &&
                response.receivedChunks.all { receipt ->
                    request.chunks.getOrNull(receipt.index)?.let(receipt::matches) == true
                },
        ) { "Blob upload reservation response mismatch" }
        return response
    }

    override suspend fun uploadStatus(
        session: SyncSession,
        capability: WorkspaceCapability,
        uploadSessionId: String,
    ): BlobUploadSessionV2 {
        requireBoundCapability(session, capability)
        requireCanonicalUuid(uploadSessionId, "Blob upload session id")
        return client.get(workspaceUrl(session, "/blob-upload-sessions/$uploadSessionId")) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
        }.decodeSuccess<BlobUploadSessionV2>().also { response ->
            require(response.sessionId == uploadSessionId) { "Blob upload status identity mismatch" }
        }
    }

    override suspend fun uploadChunk(
        session: SyncSession,
        capability: WorkspaceCapability,
        uploadSessionId: String,
        chunk: EncryptedBlobChunkV2,
    ): BlobChunkReceiptV2 {
        requireBoundCapability(session, capability)
        requireCanonicalUuid(uploadSessionId, "Blob upload session id")
        val response = client.put(
            workspaceUrl(session, "/blob-upload-sessions/$uploadSessionId/chunks/${chunk.plan.index}"),
        ) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.OctetStream)
            header(CIPHERTEXT_SHA256_HEADER, chunk.plan.ciphertextSha256Base64Url)
            setBody(chunk.ciphertext.copyBytes())
        }.decodeSuccess<BlobChunkReceiptV2>()
        require(response.matches(chunk.plan)) { "Blob chunk upload receipt mismatch" }
        return response
    }

    override suspend fun commitUpload(
        session: SyncSession,
        capability: WorkspaceCapability,
        uploadSessionId: String,
        request: BlobManifestCommitRequestV2,
    ): BlobBodyCommitReceiptV2 {
        requireBoundCapability(session, capability)
        requireCanonicalUuid(uploadSessionId, "Blob upload session id")
        val response = client.post(workspaceUrl(session, "/blob-upload-sessions/$uploadSessionId/commit")) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
            jsonBody(request)
        }.decodeSuccess<BlobBodyCommitReceiptV2>()
        require(
            response.sessionId == uploadSessionId &&
                response.manifest.manifestCiphertextSha256Base64Url ==
                request.encryptedPrivateManifest.ciphertextSha256Base64Url &&
                response.manifest.manifestCiphertextByteSize ==
                request.encryptedPrivateManifest.ciphertextByteSize.toLong(),
        ) { "Blob upload commit receipt mismatch" }
        return response
    }

    override suspend fun downloadManifest(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
    ): CommittedEncryptedBlobManifestV2 {
        requireBoundCapability(session, capability)
        requireCanonicalUuid(blobId, "Blob id")
        return client.get(workspaceUrl(session, "/blobs/$blobId/manifest")) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
        }.decodeSuccess<CommittedEncryptedBlobManifestV2>().also { response ->
            if (response.remote.blobId != blobId || response.dekEnvelopes.any { it.blobId != blobId }) {
                throw RemoteBlobBodyVerificationException("Blob manifest response identity mismatch")
            }
        }
    }

    override suspend fun downloadChunk(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
        chunk: EncryptedBlobChunkPlanV2,
    ): BinaryData {
        requireBoundCapability(session, capability)
        requireCanonicalUuid(blobId, "Blob id")
        val response = client.get(workspaceUrl(session, "/blobs/$blobId/chunks/${chunk.index}")) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.OctetStream)
        }
        response.requireSuccess()
        val hash = response.headers[CIPHERTEXT_SHA256_HEADER]
            ?: throw RemoteBlobBodyVerificationException("Blob chunk response omitted ciphertext hash")
        if (hash != chunk.ciphertextSha256Base64Url) {
            throw RemoteBlobBodyVerificationException("Blob chunk response hash differs from its plan")
        }
        val ciphertext: ByteArray = response.body()
        if (ciphertext.size != chunk.ciphertextByteSize) {
            throw RemoteBlobBodyVerificationException("Blob chunk response size differs from its plan")
        }
        return BinaryData.copyOf(ciphertext)
    }

    override suspend fun rewrapEnvelope(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
        request: BlobEnvelopeRewrapRequestV2,
    ): BlobDekEnvelopeV2 {
        requireBoundCapability(session, capability)
        requireCanonicalUuid(blobId, "Blob id")
        require(request.envelope.blobId == blobId) { "Blob re-wrap request identity mismatch" }
        require(request.envelope.keyEpoch == session.activeKeyEpoch) {
            "Blob re-wrap must target the current workspace key epoch"
        }
        return client.post(workspaceUrl(session, "/blobs/$blobId/envelopes")) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
            jsonBody(request)
        }.decodeSuccess<BlobDekEnvelopeV2>().also { response ->
            if (response != request.envelope) {
                throw RemoteBlobBodyVerificationException("Blob re-wrap response differs from the submitted envelope")
            }
        }
    }

    override suspend fun recoverEnvelope(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
        keyEpoch: Int,
    ): BlobEnvelopeRecoveryV2 {
        requireBoundCapability(session, capability)
        requireCanonicalUuid(blobId, "Blob id")
        require(keyEpoch in 1..session.activeKeyEpoch) {
            "Recovered blob envelope epoch must not be ahead of the workspace"
        }
        return client.get(workspaceUrl(session, "/blobs/$blobId/envelopes/$keyEpoch")) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
        }.decodeSuccess<BlobEnvelopeRecoveryV2>().also { response ->
            if (response.envelope.blobId != blobId || response.envelope.keyEpoch != keyEpoch) {
                throw RemoteBlobBodyVerificationException("Recovered blob envelope identity mismatch")
            }
        }
    }

    override suspend fun createTombstone(
        session: SyncSession,
        capability: WorkspaceCapability,
        request: BlobReferenceTombstoneRequestV2,
    ): BlobTombstoneCreationResultV2 {
        requireBoundCapability(session, capability)
        return client.post(workspaceUrl(session, "/blobs/${request.blobId}/tombstone")) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
            jsonBody(request)
        }.decodeSuccess<BlobTombstoneCreationResultV2>().also { response ->
            val handle = response.handle
            if (
                handle.instanceId != session.instanceId ||
                handle.workspaceId != session.workspaceId ||
                handle.blobId != request.blobId ||
                handle.manifestId != request.manifestId ||
                handle.referenceThroughWorkspaceSeq != request.throughWorkspaceSeq
            ) {
                throw RemoteBlobBodyVerificationException(
                    "Blob tombstone creation response boundary mismatch",
                )
            }
        }
    }

    override suspend fun reviveBlobReference(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
        request: BlobReferenceRevivalRequestV2,
    ): BlobReferenceRevivalResultV2 {
        requireBoundCapability(session, capability)
        requireCanonicalUuid(blobId, "Blob id")
        require(request.blobId == blobId) { "Blob revival request identity mismatch" }
        return client.post(workspaceUrl(session, "/blobs/$blobId/tombstone/revival")) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
            jsonBody(request)
        }.decodeSuccess<BlobReferenceRevivalResultV2>().also { response ->
            if (
                response.blobId != blobId ||
                response.tombstoneId != request.tombstoneId ||
                response.manifestId != request.manifestId
            ) {
                throw RemoteBlobBodyVerificationException("Blob revival response identity mismatch")
            }
        }
    }

    override suspend fun acknowledgeTombstone(
        session: SyncSession,
        capability: WorkspaceCapability,
        blobId: String,
        request: BlobTombstoneAckRequestV2,
    ) {
        requireBoundCapability(session, capability)
        requireCanonicalUuid(blobId, "Blob id")
        client.post(workspaceUrl(session, "/blobs/$blobId/tombstone/acks")) {
            bearerAuth(capability.token.asUtf8())
            jsonBody(request)
        }.requireSuccess()
    }

    override suspend fun garbageCollect(
        session: SyncSession,
        capability: WorkspaceCapability,
        request: BlobGcRequestV2,
    ): BlobGcReceiptV2 {
        requireBoundCapability(session, capability)
        return client.post(workspaceUrl(session, "/blob-gc")) {
            bearerAuth(capability.token.asUtf8())
            accept(ContentType.Application.Json)
            jsonBody(request)
        }.decodeSuccess<BlobGcReceiptV2>().also { response ->
            if (response.blobId != request.blobId) {
                throw RemoteBlobBodyVerificationException("Blob GC receipt identity mismatch")
            }
        }
    }

    private fun workspaceUrl(session: SyncSession, suffix: String): String {
        require(suffix.startsWith('/') && !suffix.startsWith("//"))
        val base = session.endpoint.trim().trimEnd('/')
        require(isAllowedSyncEndpoint(base)) {
            "Sync endpoint must use HTTPS (HTTP is only allowed for local tests)"
        }
        return "$base/v2/workspaces/${session.workspaceId}$suffix"
    }

    private fun requireBoundCapability(session: SyncSession, capability: WorkspaceCapability) {
        val binding = capability.binding
        require(session.status == SyncSessionStatus.READY) { "Blob body transport requires a ready sync session" }
        require(
            binding.workspaceId == session.workspaceId &&
                binding.deviceId == session.deviceId &&
                binding.deviceAuthEpoch == session.deviceAuthEpoch &&
                binding.membershipAuthEpoch == session.membershipAuthEpoch &&
                binding.keyEpoch == session.activeKeyEpoch,
        ) { "Blob body capability is not bound to the current sync session" }
    }

    private inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.jsonBody(value: T) {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(value))
    }

    private suspend inline fun <reified T> HttpResponse.decodeSuccess(): T {
        requireSuccess()
        return json.decodeFromString(bodyAsText())
    }

    private suspend fun HttpResponse.requireSuccess() {
        if (status.value !in 200..299) throw decodeFailure()
    }

    private suspend fun HttpResponse.decodeFailure(): SyncApiException {
        val error = runCatching {
            json.parseToJsonElement(bodyAsText()).jsonObject["error"]?.jsonObject
        }.getOrNull()
        val code = runCatching { error?.get("code")?.jsonPrimitive?.content }.getOrNull()
        val nestedDetails = runCatching { error?.get("details")?.jsonObject }.getOrNull()
        val flatDetails = error?.filterKeys { it != "code" && it != "message" && it != "details" }.orEmpty()
        return SyncApiException(
            statusCode = status.value,
            errorCode = code ?: "http_${status.value}",
            details = nestedDetails ?: JsonObject(flatDetails),
        )
    }
}

private fun requireCanonicalUuid(value: String, label: String) {
    require(CANONICAL_UUID.matches(value)) { "$label must be a canonical UUID" }
}

private const val CIPHERTEXT_SHA256_HEADER: String = "X-Shinsou-Ciphertext-Sha256"
private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val BlobBodyNetworkJson = Json(SyncNetworkJson) {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}
