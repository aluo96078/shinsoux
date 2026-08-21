package dev.shinsou.kmp.sync.network

import dev.shinsou.kmp.sync.v2.BinaryData
import dev.shinsou.kmp.sync.v2.BlobBodyCommitReceiptV2
import dev.shinsou.kmp.sync.v2.BlobChunkReceiptV2
import dev.shinsou.kmp.sync.v2.BlobDekEnvelopeV2
import dev.shinsou.kmp.sync.v2.BlobEnvelopeRecoveryV2
import dev.shinsou.kmp.sync.v2.BlobEnvelopeRewrapRequestV2
import dev.shinsou.kmp.sync.v2.BlobEnvelopeRetentionStatusV2
import dev.shinsou.kmp.sync.v2.BlobGcReceiptV2
import dev.shinsou.kmp.sync.v2.BlobGcRequestV2
import dev.shinsou.kmp.sync.v2.BlobManifestCommitRequestV2
import dev.shinsou.kmp.sync.v2.BlobReferenceRevivalRequestV2
import dev.shinsou.kmp.sync.v2.BlobReferenceRevivalResultV2
import dev.shinsou.kmp.sync.v2.BlobReferenceTombstoneRequestV2
import dev.shinsou.kmp.sync.v2.BlobRewrapCheckpointEvidenceV2
import dev.shinsou.kmp.sync.v2.BlobTombstoneAckRequestV2
import dev.shinsou.kmp.sync.v2.BlobTombstoneCreationResultV2
import dev.shinsou.kmp.sync.v2.BlobTombstoneDispositionV2
import dev.shinsou.kmp.sync.v2.BlobTombstoneHandleV2
import dev.shinsou.kmp.sync.v2.BlobTransferKeyV2
import dev.shinsou.kmp.sync.v2.BlobUploadReservationRequestV2
import dev.shinsou.kmp.sync.v2.BlobUploadSessionV2
import dev.shinsou.kmp.sync.v2.CapabilityBinding
import dev.shinsou.kmp.sync.v2.CommittedEncryptedBlobManifestV2
import dev.shinsou.kmp.sync.v2.EncryptedBlobChunkPlanV2
import dev.shinsou.kmp.sync.v2.EncryptedBlobChunkV2
import dev.shinsou.kmp.sync.v2.EncryptedBlobPrivateManifestV2
import dev.shinsou.kmp.sync.v2.RemoteBlobBodyManifestRefV2
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncProvider
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.WorkspaceCapability
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KtorCloudflareBlobBodyApiV2Test {
    @Test
    fun uploadLifecycleUsesExactV2RoutesDomainDtosAndCiphertextHeaders() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val reservation = reservation()
        val uploadSession = uploadSession()
        val chunk = encryptedChunk()
        val commitRequest = BlobManifestCommitRequestV2(encryptedPrivateManifest = encryptedManifest())
        val commitReceipt = commitReceipt()
        val engine = MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/v2/workspaces/$WORKSPACE_ID/blob-upload-sessions" -> respondJson(
                    SyncNetworkJson.encodeToString(uploadSession),
                    HttpStatusCode.Created,
                )
                "/v2/workspaces/$WORKSPACE_ID/blob-upload-sessions/$UPLOAD_SESSION_ID" ->
                    respondJson(SyncNetworkJson.encodeToString(uploadSession))
                "/v2/workspaces/$WORKSPACE_ID/blob-upload-sessions/$UPLOAD_SESSION_ID/chunks/0" ->
                    respondJson(SyncNetworkJson.encodeToString(chunkReceipt()), HttpStatusCode.Created)
                "/v2/workspaces/$WORKSPACE_ID/blob-upload-sessions/$UPLOAD_SESSION_ID/commit" ->
                    respondJson(SyncNetworkJson.encodeToString(commitReceipt), HttpStatusCode.Created)
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }
        val api = api(engine)

        assertEquals(uploadSession, api.reserveUpload(session(), capability(), reservation))
        assertEquals(uploadSession, api.uploadStatus(session(), capability(), UPLOAD_SESSION_ID))
        assertEquals(chunkReceipt(), api.uploadChunk(session(), capability(), UPLOAD_SESSION_ID, chunk))
        assertEquals(commitReceipt, api.commitUpload(session(), capability(), UPLOAD_SESSION_ID, commitRequest))

        assertEquals(
            listOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Put, HttpMethod.Post),
            requests.map(HttpRequestData::method),
        )
        assertTrue(requests.all {
            it.headers[HttpHeaders.Authorization] == "Bearer capability-token-abcdefghijklmnopqrstuvwxyz"
        })
        // The authority/generation transfer key is device-local journal state, never Worker wire
        // data. The transport validates it before sending and serialization must omit it.
        assertEquals(
            reservation.copy(transferKey = null),
            requests[0].decodeJsonBody<BlobUploadReservationRequestV2>(),
        )
        assertTrue("\"transferKey\"" !in requests[0].decodeTextBody())
        assertTrue("\"previousEnvelopeSha256Base64Url\":null" in requests[0].decodeTextBody())
        assertEquals(commitRequest, requests[3].decodeJsonBody<BlobManifestCommitRequestV2>())
        assertEquals(HASH_A, requests[2].headers["X-Shinsou-Ciphertext-Sha256"])
        assertEquals(ContentType.Application.OctetStream, requests[2].body.contentType)
        assertContentEquals(CHUNK_BYTES, (requests[2].body as OutgoingContent.ByteArrayContent).bytes())
    }

    @Test
    fun downloadRewrapTombstoneAckAndGcUseExactV2Contract() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val committed = committedManifest()
        val rewrap = rewrapRequest()
        val recovery = BlobEnvelopeRecoveryV2(
            manifestId = MANIFEST_ID,
            envelope = rewrap.envelope,
            checkpointEvidence = rewrap.checkpointEvidence,
            status = BlobEnvelopeRetentionStatusV2.CURRENT,
        )
        val tombstone = tombstoneRequest()
        val canonicalTombstone = BlobTombstoneCreationResultV2(
            handle = BlobTombstoneHandleV2(
                instanceId = INSTANCE_ID,
                workspaceId = WORKSPACE_ID,
                tombstoneId = TOMBSTONE_ID,
                blobId = BLOB_ID,
                manifestId = MANIFEST_ID,
                referenceThroughWorkspaceSeq = tombstone.throughWorkspaceSeq,
                requestedCreatedAtEpochMillis = tombstone.createdAtEpochMillis,
                executeAfterEpochMillis = 2_000,
            ),
            disposition = BlobTombstoneDispositionV2.ACTIVE,
        )
        val acknowledgement = tombstoneAck()
        val revival = BlobReferenceRevivalRequestV2(
            tombstoneId = TOMBSTONE_ID,
            blobId = BLOB_ID,
            manifestId = MANIFEST_ID,
            checkpointId = CHECKPOINT_ID,
            checkpointCiphertextSha256Base64Url = HASH_A,
            throughWorkspaceSeq = 13,
            signatureBase64Url = SIGNATURE,
        )
        val revivalResult = BlobReferenceRevivalResultV2(
            tombstoneId = TOMBSTONE_ID,
            blobId = BLOB_ID,
            manifestId = MANIFEST_ID,
            disposition = BlobTombstoneDispositionV2.CANCELLED,
            cancelledAtEpochMillis = 901,
        )
        val gcRequest = BlobGcRequestV2(blobId = BLOB_ID, tombstoneId = TOMBSTONE_ID)
        val gcReceipt = BlobGcReceiptV2(GC_RECEIPT_ID, BLOB_ID, 2, 32, 900)
        val engine = MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/v2/workspaces/$WORKSPACE_ID/blobs/$BLOB_ID/manifest" ->
                    respondJson(SyncNetworkJson.encodeToString(committed))
                "/v2/workspaces/$WORKSPACE_ID/blobs/$BLOB_ID/chunks/0" -> respond(
                    content = CHUNK_BYTES,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.OctetStream.toString()),
                        "X-Shinsou-Ciphertext-Sha256" to listOf(HASH_A),
                    ),
                )
                "/v2/workspaces/$WORKSPACE_ID/blobs/$BLOB_ID/envelopes" ->
                    respondJson(SyncNetworkJson.encodeToString(rewrap.envelope), HttpStatusCode.Created)
                "/v2/workspaces/$WORKSPACE_ID/blobs/$BLOB_ID/envelopes/2" ->
                    respondJson(SyncNetworkJson.encodeToString(recovery))
                "/v2/workspaces/$WORKSPACE_ID/blobs/$BLOB_ID/tombstone" ->
                    respondJson(SyncNetworkJson.encodeToString(canonicalTombstone), HttpStatusCode.Created)
                "/v2/workspaces/$WORKSPACE_ID/blobs/$BLOB_ID/tombstone/acks" ->
                    respond("", HttpStatusCode.NoContent)
                "/v2/workspaces/$WORKSPACE_ID/blobs/$BLOB_ID/tombstone/revival" ->
                    respondJson(SyncNetworkJson.encodeToString(revivalResult))
                "/v2/workspaces/$WORKSPACE_ID/blob-gc" ->
                    respondJson(SyncNetworkJson.encodeToString(gcReceipt), HttpStatusCode.Created)
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }
        val api = api(engine)

        assertEquals(committed, api.downloadManifest(session(), capability(), BLOB_ID))
        assertContentEquals(
            CHUNK_BYTES,
            api.downloadChunk(session(), capability(), BLOB_ID, chunkPlan()).copyBytes(),
        )
        assertEquals(rewrap.envelope, api.rewrapEnvelope(session(2), capability(2), BLOB_ID, rewrap))
        assertEquals(recovery, api.recoverEnvelope(session(2), capability(2), BLOB_ID, 2))
        assertEquals(canonicalTombstone, api.createTombstone(session(), capability(), tombstone))
        api.acknowledgeTombstone(session(), capability(), BLOB_ID, acknowledgement)
        assertEquals(
            revivalResult,
            api.reviveBlobReference(session(), capability(), BLOB_ID, revival),
        )
        assertEquals(gcReceipt, api.garbageCollect(session(), capability(), gcRequest))

        assertEquals(
            listOf(
                HttpMethod.Get,
                HttpMethod.Get,
                HttpMethod.Post,
                HttpMethod.Get,
                HttpMethod.Post,
                HttpMethod.Post,
                HttpMethod.Post,
                HttpMethod.Post,
            ),
            requests.map(HttpRequestData::method),
        )
        assertTrue(requests.all {
            it.headers[HttpHeaders.Authorization] == "Bearer capability-token-abcdefghijklmnopqrstuvwxyz"
        })
        assertEquals(rewrap, requests[2].decodeJsonBody<BlobEnvelopeRewrapRequestV2>())
        assertEquals(tombstone, requests[4].decodeJsonBody<BlobReferenceTombstoneRequestV2>())
        assertEquals(acknowledgement, requests[5].decodeJsonBody<BlobTombstoneAckRequestV2>())
        assertEquals(revival, requests[6].decodeJsonBody<BlobReferenceRevivalRequestV2>())
        assertEquals(gcRequest, requests[7].decodeJsonBody<BlobGcRequestV2>())
    }

    @Test
    fun mismatchedChunkMetadataWorkerErrorsAndForeignCapabilitiesFailClosed() = runTest {
        val hashMismatch = api(
            MockEngine {
                respond(
                    content = CHUNK_BYTES,
                    status = HttpStatusCode.OK,
                    headers = headersOf("X-Shinsou-Ciphertext-Sha256", HASH_B),
                )
            },
        )
        assertFailsWith<RemoteBlobBodyVerificationException> {
            hashMismatch.downloadChunk(session(), capability(), BLOB_ID, chunkPlan())
        }

        val expired = api(
            MockEngine {
                respondJson(
                    """{"error":{"code":"blob_upload_session_expired","message":"expired","sessionId":"$UPLOAD_SESSION_ID"}}""",
                    HttpStatusCode.Conflict,
                )
            },
        )
        val failure = assertFailsWith<SyncApiException> {
            expired.uploadStatus(session(), capability(), UPLOAD_SESSION_ID)
        }
        assertEquals(409, failure.statusCode)
        assertEquals("blob_upload_session_expired", failure.errorCode)
        assertEquals(UPLOAD_SESSION_ID, failure.details["sessionId"]?.toString()?.trim('"'))

        var networkCalls = 0
        val guarded = api(MockEngine {
            networkCalls++
            respond("", HttpStatusCode.InternalServerError)
        })
        val foreign = capability().copy(
            binding = capability().binding.copy(workspaceId = FOREIGN_WORKSPACE_ID),
        )
        assertFailsWith<IllegalArgumentException> {
            guarded.downloadManifest(session(), foreign, BLOB_ID)
        }
        assertEquals(0, networkCalls)
    }

    private fun api(engine: MockEngine): KtorCloudflareBlobBodyApiV2 = KtorCloudflareBlobBodyApiV2(
        HttpClient(engine) { expectSuccess = false },
    )

    private fun reservation() = BlobUploadReservationRequestV2(
        blobId = BLOB_ID,
        manifestId = MANIFEST_ID,
        keyEpoch = 1,
        chunkSizeBytes = 64 * 1024,
        expectedBodyCiphertextBytes = CHUNK_BYTES.size.toLong(),
        expectedManifestCiphertextBytes = MANIFEST_BYTES.size,
        manifestCiphertextSha256Base64Url = HASH_A,
        chunks = listOf(chunkPlan()),
        initialDekEnvelope = initialEnvelope(),
        transferKey = BlobTransferKeyV2(INSTANCE_ID, WORKSPACE_ID, BLOB_ID, 1),
    )

    private fun uploadSession() = BlobUploadSessionV2(
        sessionId = UPLOAD_SESSION_ID,
        blobId = BLOB_ID,
        manifestId = MANIFEST_ID,
        keyEpoch = 1,
        expiresAtEpochMillis = 99_999,
        reservedBytes = (CHUNK_BYTES.size + MANIFEST_BYTES.size).toLong(),
    )

    private fun chunkPlan() = EncryptedBlobChunkPlanV2(0, CHUNK_BYTES.size, HASH_A)

    private fun encryptedChunk() = EncryptedBlobChunkV2(chunkPlan(), BinaryData.copyOf(CHUNK_BYTES))

    private fun chunkReceipt() = BlobChunkReceiptV2(0, CHUNK_BYTES.size, HASH_A)

    private fun encryptedManifest() = EncryptedBlobPrivateManifestV2(
        nonceBase64Url = NONCE,
        ciphertextBase64Url = encodeBase64Url(MANIFEST_BYTES),
        ciphertextSha256Base64Url = HASH_A,
        ciphertextByteSize = MANIFEST_BYTES.size,
    )

    private fun initialEnvelope() = BlobDekEnvelopeV2(
        blobId = BLOB_ID,
        keyEpoch = 1,
        nonceBase64Url = NONCE,
        wrappedDekBase64Url = WRAPPED_DEK,
        envelopeSha256Base64Url = HASH_A,
    )

    private fun rewrapRequest(): BlobEnvelopeRewrapRequestV2 {
        val envelope = BlobDekEnvelopeV2(
            blobId = BLOB_ID,
            keyEpoch = 2,
            nonceBase64Url = NONCE,
            wrappedDekBase64Url = WRAPPED_DEK,
            envelopeSha256Base64Url = HASH_B,
            previousEnvelopeSha256Base64Url = HASH_A,
        )
        return BlobEnvelopeRewrapRequestV2(
            manifestId = MANIFEST_ID,
            envelope = envelope,
            checkpointEvidence = BlobRewrapCheckpointEvidenceV2(CHECKPOINT_ID, HASH_A, 12),
        )
    }

    private fun remoteManifest() = RemoteBlobBodyManifestRefV2(
        manifestId = MANIFEST_ID,
        blobId = BLOB_ID,
        manifestCiphertextSha256Base64Url = HASH_A,
        manifestCiphertextByteSize = MANIFEST_BYTES.size.toLong(),
        bodyCiphertextByteSize = CHUNK_BYTES.size.toLong(),
        chunkCount = 1,
        chunkSizeBytes = 64 * 1024,
        committedAtEpochMillis = 800,
        commitReceiptId = COMMIT_RECEIPT_ID,
    )

    private fun commitReceipt() = BlobBodyCommitReceiptV2(
        receiptId = COMMIT_RECEIPT_ID,
        sessionId = UPLOAD_SESSION_ID,
        manifest = remoteManifest(),
    )

    private fun committedManifest() = CommittedEncryptedBlobManifestV2(
        remote = remoteManifest(),
        chunks = listOf(chunkPlan()),
        encryptedPrivateManifest = encryptedManifest(),
        dekEnvelopes = listOf(initialEnvelope()),
    )

    private fun tombstoneRequest() = BlobReferenceTombstoneRequestV2(
        tombstoneId = TOMBSTONE_ID,
        blobId = BLOB_ID,
        manifestId = MANIFEST_ID,
        throughWorkspaceSeq = 12,
        createdAtEpochMillis = 850,
    )

    private fun tombstoneAck() = BlobTombstoneAckRequestV2(
        tombstoneId = TOMBSTONE_ID,
        checkpointId = CHECKPOINT_ID,
        checkpointCiphertextSha256Base64Url = HASH_A,
        throughWorkspaceSeq = 12,
        signatureBase64Url = SIGNATURE,
    )

    private fun session(activeKeyEpoch: Int = 1) = SyncSession(
        endpoint = ENDPOINT,
        instanceId = INSTANCE_ID,
        userId = USER_ID,
        workspaceId = WORKSPACE_ID,
        deviceId = DEVICE_ID,
        deviceDisplayName = "Desktop",
        platform = "macos",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 3,
        membershipAuthEpoch = 4,
        activeKeyEpoch = activeKeyEpoch,
        provider = SyncProvider.CLOUDFLARE_V2,
    )

    private fun capability(keyEpoch: Int = 1) = WorkspaceCapability(
        token = SecretMaterial("capability-token-abcdefghijklmnopqrstuvwxyz".encodeToByteArray().asList()),
        binding = CapabilityBinding(
            deviceId = DEVICE_ID,
            workspaceId = WORKSPACE_ID,
            deviceAuthEpoch = 3,
            membershipAuthEpoch = 4,
            keyEpoch = keyEpoch,
            expiresAtMillis = 99_999,
        ),
    )

    private fun HttpRequestData.decodeTextBody(): String = (body as TextContent).text

    private inline fun <reified T> HttpRequestData.decodeJsonBody(): T =
        SyncNetworkJson.decodeFromString(decodeTextBody())

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        value: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(value, status, JSON_HEADERS)

    private companion object {
        const val ENDPOINT = "https://sync.example.test"
        const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
        const val USER_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val FOREIGN_WORKSPACE_ID = "33333333-3333-4333-9333-333333333333"
        const val DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val BLOB_ID = "55555555-5555-4555-8555-555555555555"
        const val MANIFEST_ID = "66666666-6666-4666-8666-666666666666"
        const val UPLOAD_SESSION_ID = "77777777-7777-4777-8777-777777777777"
        const val COMMIT_RECEIPT_ID = "88888888-8888-4888-8888-888888888888"
        const val TOMBSTONE_ID = "99999999-9999-4999-8999-999999999999"
        const val CHECKPOINT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val GC_RECEIPT_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val CHUNK_BYTES = ByteArray(16) { (it + 1).toByte() }
        val MANIFEST_BYTES = ByteArray(16) { (it + 17).toByte() }
        val HASH_A = encodeBase64Url(ByteArray(32))
        val HASH_B = encodeBase64Url(ByteArray(32) { 1 })
        val NONCE = encodeBase64Url(ByteArray(12) { 2 })
        val WRAPPED_DEK = encodeBase64Url(ByteArray(48) { 3 })
        val SIGNATURE = encodeBase64Url(ByteArray(64) { 4 })
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
