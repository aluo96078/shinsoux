package dev.shinsou.kmp.sync.network

import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import dev.shinsou.kmp.sync.crypto.InMemorySyncDevicePublicKeyResolver
import dev.shinsou.kmp.sync.crypto.SodiumSyncCrypto
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.v2.AppendEventResult
import dev.shinsou.kmp.sync.v2.CapabilityBinding
import dev.shinsou.kmp.sync.v2.CheckpointReplayAcknowledgement
import dev.shinsou.kmp.sync.v2.EncryptedSyncCheckpoint
import dev.shinsou.kmp.sync.v2.EncryptedSyncEvent
import dev.shinsou.kmp.sync.v2.InMemorySyncSecretStore
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncCipherSuite
import dev.shinsou.kmp.sync.v2.SyncCheckpointHeader
import dev.shinsou.kmp.sync.v2.SyncCheckpointCompression
import dev.shinsou.kmp.sync.v2.SyncEventHeader
import dev.shinsou.kmp.sync.v2.SyncProvider
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.WorkspaceCapability
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KtorCloudflareSyncApiTest {
    @Test
    fun authCapabilityAppendAndCatchUpUseExactWorkerWireShape() = runTest {
        SodiumSyncPrimitives.initialize()
        val codec = DeterministicSyncEventCodec()
        val signer = SodiumSyncPrimitives.generateEd25519KeyPair()
        val secrets = InMemorySyncSecretStore().apply {
            write(SyncSecretKey.DeviceCredential, SecretMaterial("device-token-abcdefghijklmnopqrstuvwxyz".encodeToByteArray().asList()))
            write(SyncSecretKey.DeviceSigningPrivateKey, SecretMaterial(signer.privateKey.asList()))
        }
        val header = eventHeader()
        val headerCbor = encodeBase64Url(codec.canonicalEventAssociatedData(header).copyBytes())
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            val body = when (path) {
                "/v1/capabilities" -> CAPABILITIES
                "/v1/auth/challenge" -> AUTH_CHALLENGE
                "/v1/auth/token" -> ACCESS_TOKEN
                "/v1/auth/capability" -> CAPABILITY
                "/v1/workspaces/$WORKSPACE_ID/events/receipts/1" -> RECEIPT
                "/v1/workspaces/$WORKSPACE_ID/events" -> if (request.method.value == "POST") {
                    RECEIPT
                } else {
                    catchUpJson(headerCbor)
                }
                else -> error("Unexpected path $path")
            }
            respond(body, HttpStatusCode.OK, JSON_HEADERS)
        }
        val http = HttpClient(engine) { expectSuccess = false }
        val crypto = SodiumSyncCrypto(secrets, codec, InMemorySyncDevicePublicKeyResolver())
        val api = KtorCloudflareSyncApi(http, secrets, crypto, codec)

        val capabilities = api.capabilities(ENDPOINT)
        assertTrue(capabilities.realtimeAvailable)
        assertEquals(2, capabilities.protocolVersion)
        assertEquals(7, capabilities.schemaVersion)
        assertEquals(32 * 1024, capabilities.maxEventBytes)
        val capability = api.obtainWorkspaceCapability(session())
        assertEquals(WORKSPACE_ID, capability.binding.workspaceId)
        assertTrue(requests.none { "access-token" in it.url.toString() || "capability-token" in it.url.toString() })
        assertEquals("Bearer access-token-abcdefghijklmnopqrstuvwxyz", requests[3].headers[HttpHeaders.Authorization])

        val envelope = EncryptedSyncEvent(header, headerCbor, "AA", "signature")
        val append = api.appendEvent(session(), capability, envelope)
        assertIs<AppendEventResult.Committed>(append)
        assertEquals(9, append.receipt.workspaceSeq)
        val recoveredReceipt = api.eventReceipt(session(), capability, 1)
        assertEquals(9, recoveredReceipt?.workspaceSeq)
        val page = api.catchUp(session(), capability, 0, null, 100)
        assertEquals(1, page.events.size)
        assertEquals(header, page.events.single().envelope.header)
        assertEquals("Bearer capability-token-abcdefghijklmnopqrstuvwxyz", requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun legacyCapabilitiesWithoutSchemaFieldsFailClosed() = runTest {
        val codec = DeterministicSyncEventCodec()
        val secrets = InMemorySyncSecretStore()
        val api = KtorCloudflareSyncApi(
            HttpClient(MockEngine {
                respond(
                    """{"protocolVersion":1,"minReaderVersion":1,"minWriterVersion":1,"profile":"lite","websocket":false,"checkpoint":{"envelopeVersion":1,"maximumBytes":33554432},"event":{"envelopeVersion":1,"maximumBytes":32768}}""",
                    HttpStatusCode.OK,
                    JSON_HEADERS,
                )
            }) { expectSuccess = false },
            secrets,
            SodiumSyncCrypto(secrets, codec, InMemorySyncDevicePublicKeyResolver()),
            codec,
        )

        assertFailsWith<kotlinx.serialization.SerializationException> {
            api.capabilities(ENDPOINT)
        }
    }

    @Test
    fun base64UrlCodecRejectsNonCanonicalTailBits() {
        val bytes = ByteArray(32) { it.toByte() }
        val encoded = encodeBase64Url(bytes)
        assertContentEquals(bytes, decodeBase64Url(encoded))
        kotlin.test.assertFailsWith<IllegalArgumentException> { decodeBase64Url("AB") }
    }

    @Test
    fun appendNormalizesRotationGateIntoTypedResult() = runTest {
        val codec = DeterministicSyncEventCodec()
        val secrets = InMemorySyncSecretStore()
        val engine = MockEngine {
            respond(
                """{"error":{"code":"key_rotation_required","details":{"expectedKeyEpoch":2}}}""",
                HttpStatusCode.Conflict,
                JSON_HEADERS,
            )
        }
        val api = KtorCloudflareSyncApi(
            HttpClient(engine) { expectSuccess = false },
            secrets,
            SodiumSyncCrypto(secrets, codec, InMemorySyncDevicePublicKeyResolver()),
            codec,
        )
        val capability = WorkspaceCapability(
            SecretMaterial("capability-token-abcdefghijklmnopqrstuvwxyz".encodeToByteArray().asList()),
            CapabilityBinding(DEVICE_ID, WORKSPACE_ID, 1, 1, 1, 999_999),
        )
        val header = eventHeader()

        val result = api.appendEvent(
            session(),
            capability,
            EncryptedSyncEvent(header, "header", "AA", "signature"),
        )

        assertEquals(2, assertIs<AppendEventResult.KeyRotationRequired>(result).activeKeyEpoch)
    }

    @Test
    fun checkpointLeaseUploadCommitAndAckPreserveExactIdentity() = runTest {
        SodiumSyncPrimitives.initialize()
        val codec = DeterministicSyncEventCodec()
        val signer = SodiumSyncPrimitives.generateEd25519KeyPair()
        val secrets = InMemorySyncSecretStore().apply {
            write(SyncSecretKey.DeviceSigningPrivateKey, SecretMaterial(signer.privateKey.asList()))
        }
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/checkpoint-leases") -> CHECKPOINT_LEASE
                request.method.value == "PUT" && path.endsWith("/checkpoints/$CHECKPOINT_ID") -> CHECKPOINT_UPLOAD
                path.endsWith("/checkpoints/$CHECKPOINT_ID/commit") -> CHECKPOINT_CANDIDATE
                path.endsWith("/checkpoints/$CHECKPOINT_ID/ack") -> CHECKPOINT_STABLE
                else -> error("Unexpected path $path")
            }
            respond(body, if (request.method.value == "PUT") HttpStatusCode.Created else HttpStatusCode.OK, JSON_HEADERS)
        }
        val http = HttpClient(engine) { expectSuccess = false }
        val crypto = SodiumSyncCrypto(secrets, codec, InMemorySyncDevicePublicKeyResolver())
        val api = KtorCloudflareSyncApi(http, secrets, crypto, codec)
        val capability = WorkspaceCapability(
            SecretMaterial("capability-token-abcdefghijklmnopqrstuvwxyz".encodeToByteArray().asList()),
            CapabilityBinding(DEVICE_ID, WORKSPACE_ID, 1, 1, 1, 999_999),
        )

        val lease = api.createCheckpointLease(
            session(),
            capability,
            CHECKPOINT_ID,
            CIPHERTEXT_HASH,
            3,
            9,
        )
        assertEquals(CHECKPOINT_ID, lease.checkpointId)
        val checkpoint = EncryptedSyncCheckpoint(
            SyncCheckpointHeader(
                cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
                nonceBase64Url = "AAECAwQFBgcICQoL",
                instanceId = INSTANCE_ID,
                workspaceId = WORKSPACE_ID,
                checkpointId = CHECKPOINT_ID,
                deviceId = DEVICE_ID,
                throughWorkspaceSeq = 9,
                keyEpoch = 1,
                previousStableCiphertextSha256Base64Url = null,
                compression = SyncCheckpointCompression.LZ4_BLOCK_V1,
                uncompressedSize = 1,
                ciphertextSha256Base64Url = CIPHERTEXT_HASH,
            ),
            authenticatedHeaderBase64Url = "AA",
            ciphertextBase64Url = encodeBase64Url(byteArrayOf(1, 2, 3)),
            signatureBase64Url = "signature",
        )
        api.uploadCheckpoint(session(), capability, lease, checkpoint)
        val candidate = api.commitCheckpoint(session(), capability, lease)
        assertEquals(CHECKPOINT_ID, candidate.checkpointId)
        val result = api.acknowledgeCheckpoint(
            session(),
            capability,
            CheckpointReplayAcknowledgement(
                checkpointId = CHECKPOINT_ID,
                ciphertextSha256Base64Url = CIPHERTEXT_HASH,
                replayFromSeq = 0,
                replayedThroughSeq = 9,
                replayedEventCount = 9,
                previousStableCheckpointId = null,
                previousStableCiphertextSha256Base64Url = null,
                valid = true,
                signatureBase64Url = "signature",
            ),
        )

        assertEquals("STABLE", result.status.name)
        // Ktor keeps an outgoing content type on the body until the engine serializes it; the
        // MockEngine request headers therefore do not necessarily contain Content-Type yet.
        assertEquals("application/octet-stream", requests[1].body.contentType?.toString())
        assertEquals(CIPHERTEXT_HASH, requests[1].headers["X-Shinsou-Ciphertext-Sha256"])
        assertEquals("signature", requests[1].headers["X-Shinsou-Device-Signature"])
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer capability-token-abcdefghijklmnopqrstuvwxyz" })
    }

    private fun eventHeader() = SyncEventHeader(
        cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
        nonceBase64Url = "AAECAwQFBgcICQoL",
        instanceId = INSTANCE_ID,
        workspaceId = WORKSPACE_ID,
        eventId = EVENT_ID,
        deviceId = DEVICE_ID,
        deviceSeq = 1,
        keyEpoch = 1,
        ciphertextSha256Base64Url = CIPHERTEXT_HASH,
    )

    private fun session() = SyncSession(
        endpoint = ENDPOINT,
        instanceId = INSTANCE_ID,
        userId = USER_ID,
        workspaceId = WORKSPACE_ID,
        deviceId = DEVICE_ID,
        deviceDisplayName = "Desktop",
        platform = "macos",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = 1,
        provider = SyncProvider.CLOUDFLARE_V2,
    )

    private fun catchUpJson(headerCbor: String): String = """
        {
          "fromExclusive":0,"untilInclusive":1,"nextCursor":1,"hasMore":false,
          "headSeq":1,"stableCheckpointSeq":0,
          "events":[{
            "workspaceSeq":1,"eventId":"$EVENT_ID","deviceId":"$DEVICE_ID",
            "deviceSeq":1,"keyEpoch":1,"headerCbor":"$headerCbor","ciphertext":"AA",
            "ciphertextSha256":"$CIPHERTEXT_HASH","signature":"signature"
          }]
        }
    """.trimIndent()

    private companion object {
        const val ENDPOINT = "https://sync.example.test"
        const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
        const val USER_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val EVENT_ID = "55555555-5555-4555-8555-555555555555"
        const val CHECKPOINT_ID = "88888888-8888-4888-8888-888888888888"
        const val CIPHERTEXT_HASH = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
        const val CAPABILITIES = """{
          "protocolVersion":2,"minReaderVersion":1,"minWriterVersion":1,
          "schemaVersion":7,"minSchemaReaderVersion":4,"minSchemaWriterVersion":5,
          "profile":"realtime","websocket":true,
          "checkpoint":{"envelopeVersion":1,"maximumBytes":33554432,"retainedStable":3},
          "event":{"envelopeVersion":1,"maximumBytes":32768}
        }"""
        const val AUTH_CHALLENGE = """{
          "challengeId":"66666666-6666-4666-8666-666666666666",
          "challenge":"challenge-abcdefghijklmnopqrstuvwxyz","expiresAt":999999
        }"""
        const val ACCESS_TOKEN = """{
          "accessToken":"access-token-abcdefghijklmnopqrstuvwxyz","expiresAt":999999
        }"""
        const val CAPABILITY = """{
          "capability":"capability-token-abcdefghijklmnopqrstuvwxyz",
          "capabilityId":"77777777-7777-4777-8777-777777777777",
          "workspaceId":"$WORKSPACE_ID","deviceId":"$DEVICE_ID",
          "deviceAuthEpoch":1,"membershipAuthEpoch":1,"keyEpoch":1,"expiresAt":999999
        }"""
        const val RECEIPT = """{
          "duplicate":false,"eventId":"$EVENT_ID","deviceSeq":1,
          "workspaceSeq":9,"headSeq":9,"ciphertextSha256":"$CIPHERTEXT_HASH"
        }"""
        const val CHECKPOINT_LEASE = """{
          "leaseId":"99999999-9999-4999-8999-999999999999",
          "checkpointId":"$CHECKPOINT_ID","ciphertextSha256":"$CIPHERTEXT_HASH",
          "throughWorkspaceSeq":9,"keyEpoch":1,"expiresAt":999999
        }"""
        const val CHECKPOINT_UPLOAD = """{
          "checkpointId":"$CHECKPOINT_ID","ciphertextSha256":"$CIPHERTEXT_HASH",
          "byteSize":3,"uploaded":true,"duplicate":false
        }"""
        const val CHECKPOINT_CANDIDATE = """{
          "checkpointId":"$CHECKPOINT_ID","ciphertextSha256":"$CIPHERTEXT_HASH",
          "throughWorkspaceSeq":9,"keyEpoch":1,"uploaderDeviceId":"$DEVICE_ID",
          "createdAt":1,"previousStableCheckpointId":null,
          "previousStableThroughWorkspaceSeq":0,"previousStableCheckpointHash":null,
          "status":"candidate"
        }"""
        const val CHECKPOINT_STABLE = """{
          "checkpointId":"$CHECKPOINT_ID","ciphertextSha256":"$CIPHERTEXT_HASH",
          "throughWorkspaceSeq":9,"status":"stable"
        }"""
    }
}
