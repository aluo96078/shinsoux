package dev.shinsou.kmp.sync.network

import dev.shinsou.kmp.sync.v2.CapabilityBinding
import dev.shinsou.kmp.sync.v2.InMemorySyncSecretStore
import dev.shinsou.kmp.sync.v2.RecoveryClaimRequest
import dev.shinsou.kmp.sync.v2.RecoveryDeviceRegistration
import dev.shinsou.kmp.sync.v2.RecoveryEpochKeyEnvelope
import dev.shinsou.kmp.sync.v2.RecoveryWorkspaceClaimEnvelope
import dev.shinsou.kmp.sync.v2.RotationCommitRequest
import dev.shinsou.kmp.sync.v2.RotationDeviceEnvelope
import dev.shinsou.kmp.sync.v2.SecretMaterial
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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorSyncControlPlaneApiTest {
    @Test
    fun rotationAndBootstrapUseExactWorkerWireShape() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val body = when (request.url.encodedPath) {
                "/v1/workspaces/$WORKSPACE_ID/key-rotations" -> ROTATION_LEASE
                "/v1/workspaces/$WORKSPACE_ID/key-rotations/$ROTATION_ID/commit" -> ROTATION_COMMIT
                "/v1/workspaces/$WORKSPACE_ID/key-rotations/$ROTATION_ID/ack" -> ROTATION_ACK
                "/v1/workspaces/$WORKSPACE_ID/bootstrap" -> KEY_BOOTSTRAP
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
            respond(body, HttpStatusCode.OK, JSON_HEADERS)
        }
        val secrets = InMemorySyncSecretStore().apply {
            write(SyncSecretKey.AccessToken, secret(ACCESS_TOKEN))
        }
        val api = KtorSyncControlPlaneApi(HttpClient(engine) { expectSuccess = false }, secrets)

        val lease = api.createRotationLease(session(), capability(), ROTATION_ID, 1, "lease-signature")
        assertEquals(2, lease.toEpoch)
        api.commitRotation(
            session(),
            capability(),
            ROTATION_ID,
            RotationCommitRequest(
                manifestCborBase64Url = "manifest-cbor",
                manifestSignatureBase64Url = "manifest-signature",
                deviceEnvelopes = listOf(RotationDeviceEnvelope(DEVICE_ID, "device-wrapped-key")),
                recoveryWrappedKeyBase64Url = "recovery-wrapped-key",
            ),
        )
        api.acknowledgeRotation(session(), capability(2), ROTATION_ID, HASH, "ack-signature")
        val bootstrap = api.workspaceKeyBootstrap(session(), capability(2))
        assertEquals(2, bootstrap.activeKeyEpoch)
        assertTrue(bootstrap.rotationRequired)
        assertEquals(
            json("""{"rotationId":"$ROTATION_ID","fromEpoch":1,"signature":"lease-signature"}"""),
            json(requests[0].bodyText()),
        )
        assertEquals(
            json(
                """{
                  "manifestCbor":"manifest-cbor","manifestSignature":"manifest-signature",
                  "deviceEnvelopes":[{"deviceId":"$DEVICE_ID","wrappedKey":"device-wrapped-key"}],
                  "recoveryWrappedKey":"recovery-wrapped-key"
                }""",
            ),
            json(requests[1].bodyText()),
        )
        assertEquals(
            json("""{"keyCommitment":"$HASH","signature":"ack-signature"}"""),
            json(requests[2].bodyText()),
        )
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer $CAPABILITY_TOKEN" })
    }

    @Test
    fun recoveryChallengeAndClaimPreserveSecretAndManifestFieldsWithoutBearer() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val body = when (request.url.encodedPath) {
                "/v1/recovery/challenge" -> RECOVERY_CHALLENGE
                "/v1/recovery/claim" -> RECOVERY_CLAIM
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
            respond(body, HttpStatusCode.Created, JSON_HEADERS)
        }
        val api = KtorSyncControlPlaneApi(
            HttpClient(engine) { expectSuccess = false },
            InMemorySyncSecretStore(),
        )

        val challenge = api.createRecoveryChallenge(ENDPOINT, USER_ID)
        assertEquals(CHALLENGE_ID, challenge.challengeId)
        assertEquals(listOf(1), challenge.workspaces.single().retainedKeyEnvelopes.map { it.keyEpoch })
        val request = RecoveryClaimRequest(
            instanceId = INSTANCE_ID,
            userId = USER_ID,
            challengeId = CHALLENGE_ID,
            challenge = secret(CHALLENGE_SECRET),
            device = RecoveryDeviceRegistration(
                deviceId = NEW_DEVICE_ID,
                displayName = "Recovered iPhone",
                platform = "ios",
                signingPublicKeyBase64Url = PUBLIC_KEY,
                wrappingPublicKeyBase64Url = WRAPPING_KEY,
                deviceCredential = secret(DEVICE_TOKEN),
            ),
            previousRecoverySigningPublicKeyBase64Url = RECOVERY_SIGNING_KEY,
            newRecoverySigningPublicKeyBase64Url = NEW_RECOVERY_SIGNING_KEY,
            newRecoveryWrappingPublicKeyBase64Url = NEW_RECOVERY_WRAPPING_KEY,
            replacementRecoveryTrustSignatureBase64Url = "replacement-recovery-trust-signature",
            workspaceEnvelopes = listOf(
                RecoveryWorkspaceClaimEnvelope(
                    workspaceId = WORKSPACE_ID,
                    keyEpoch = 2,
                    keyCommitmentBase64Url = HASH,
                    deviceWrappedKeyBase64Url = "device-wrapped-key",
                    deviceEnvelopeSignatureBase64Url = "device-envelope-signature",
                    replacementRecoveryEnvelopes = listOf(
                        RecoveryEpochKeyEnvelope(1, HASH, "replacement-retained-wrapped-key"),
                        RecoveryEpochKeyEnvelope(2, HASH, "replacement-recovery-wrapped-key"),
                    ),
                ),
            ),
            signatureBase64Url = "recovery-claim-signature",
        )
        val receipt = api.claimRecovery(ENDPOINT, request)
        assertEquals(NEW_DEVICE_ID, receipt.deviceId)
        assertEquals(2, receipt.workspaceBindings.single().activeKeyEpoch)

        assertEquals(json("""{"userId":"$USER_ID"}"""), json(requests[0].bodyText()))
        assertEquals(
            json(
                """{
                  "manifest":{
                    "instanceId":"$INSTANCE_ID","userId":"$USER_ID",
                    "challengeId":"$CHALLENGE_ID","challenge":"$CHALLENGE_SECRET",
                    "device":{
                      "deviceId":"$NEW_DEVICE_ID","displayName":"Recovered iPhone","platform":"ios",
                      "signingPublicKey":"$PUBLIC_KEY","wrappingPublicKey":"$WRAPPING_KEY",
                      "deviceToken":"$DEVICE_TOKEN"
                    },
                    "previousRecoverySigningPublicKey":"$RECOVERY_SIGNING_KEY",
                    "newRecoverySigningPublicKey":"$NEW_RECOVERY_SIGNING_KEY",
                    "newRecoveryWrappingPublicKey":"$NEW_RECOVERY_WRAPPING_KEY",
                    "replacementRecoveryTrustSignature":"replacement-recovery-trust-signature",
                    "workspaceEnvelopes":[{
                      "workspaceId":"$WORKSPACE_ID","keyEpoch":2,"keyCommitment":"$HASH",
                      "deviceWrappedKey":"device-wrapped-key",
                      "deviceEnvelopeSignature":"device-envelope-signature",
                      "recoveryKeyEnvelopes":[
                        {"keyEpoch":1,"keyCommitment":"$HASH","recoveryWrappedKey":"replacement-retained-wrapped-key"},
                        {"keyEpoch":2,"keyCommitment":"$HASH","recoveryWrappedKey":"replacement-recovery-wrapped-key"}
                      ]
                    }]
                  },
                  "signature":"recovery-claim-signature"
                }""",
            ),
            json(requests[1].bodyText()),
        )
        assertTrue(
            requests.all { (it.body as TextContent).contentType.toString().startsWith("application/json") },
        )
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == null })
    }

    private fun session() = SyncSession(
        endpoint = ENDPOINT,
        instanceId = INSTANCE_ID,
        userId = USER_ID,
        workspaceId = WORKSPACE_ID,
        deviceId = DEVICE_ID,
        deviceDisplayName = "iPhone",
        platform = "ios",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = 1,
        provider = SyncProvider.CLOUDFLARE_V2,
    )

    private fun capability(epoch: Int = 1) = WorkspaceCapability(
        token = secret(CAPABILITY_TOKEN),
        binding = CapabilityBinding(DEVICE_ID, WORKSPACE_ID, 1, 1, epoch, 999_999),
    )

    private fun secret(value: String) = SecretMaterial(value.encodeToByteArray().asList())

    private fun HttpRequestData.bodyText(): String = (body as TextContent).text

    private fun json(value: String) = Json.parseToJsonElement(value)

    private companion object {
        const val ENDPOINT = "https://sync.example.test"
        const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
        const val USER_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val ROTATION_ID = "66666666-6666-4666-8666-666666666666"
        const val CHALLENGE_ID = "77777777-7777-4777-8777-777777777777"
        const val CLAIM_ID = "88888888-8888-4888-8888-888888888888"
        const val NEW_DEVICE_ID = "99999999-9999-4999-8999-999999999999"
        const val CAPABILITY_TOKEN = "capability-token-abcdefghijklmnopqrstuvwxyz"
        const val ACCESS_TOKEN = "access-token-abcdefghijklmnopqrstuvwxyz"
        const val CHALLENGE_SECRET = "challenge-secret-abcdefghijklmnopqrstuvwxyz"
        const val DEVICE_TOKEN = "device-token-abcdefghijklmnopqrstuvwxyz"
        const val PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val WRAPPING_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
        const val RECOVERY_SIGNING_KEY = "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU"
        const val NEW_RECOVERY_SIGNING_KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI"
        const val NEW_RECOVERY_WRAPPING_KEY = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM"
        const val HASH = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ"
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
        const val ROTATION_LEASE = """{
          "rotationId":"$ROTATION_ID","workspaceId":"$WORKSPACE_ID","fromEpoch":1,"toEpoch":2,
          "proposerDeviceId":"$DEVICE_ID","proposerDeviceAuthEpoch":1,"membershipAuthEpoch":1,
          "recipients":[{"deviceId":"$DEVICE_ID","authEpoch":1,"wrappingPublicKey":"$WRAPPING_KEY"}],
          "recovery":{"wrappingPublicKey":"$NEW_RECOVERY_WRAPPING_KEY","authEpoch":2},"expiresAt":999999
        }"""
        const val ROTATION_COMMIT = """{
          "rotationId":"$ROTATION_ID","workspaceId":"$WORKSPACE_ID","fromEpoch":1,
          "activeKeyEpoch":2,"keyCommitment":"$HASH","status":"committed"
        }"""
        const val ROTATION_ACK = """{
          "rotationId":"$ROTATION_ID","deviceId":"$DEVICE_ID","acknowledged":true
        }"""
        const val KEY_BOOTSTRAP = """{
          "workspaceId":"$WORKSPACE_ID","activeKeyEpoch":2,"rotationRequired":true,"deviceKeyEnvelopes":[],
          "deviceDirectory":{"version":1,"hash":"$HASH","allDeviceCount":1,"devices":[{
            "deviceId":"$DEVICE_ID","userId":"$USER_ID","displayName":"iPhone","platform":"ios",
            "signingPublicKey":"$PUBLIC_KEY","wrappingPublicKey":"$WRAPPING_KEY","status":"active",
            "authEpoch":1,"createdAt":1,"revokedAt":null,"attestation":{
              "type":"initial","workspaceId":"$WORKSPACE_ID","attestorDeviceId":"$DEVICE_ID",
              "attestorPublicKey":"$PUBLIC_KEY","signatureDomain":"initial-workspace-claim",
              "manifestJson":"{}","signature":"attestation-signature","createdAt":1
            }
          }]}
        }"""
        const val RECOVERY_CHALLENGE = """{
          "challengeId":"$CHALLENGE_ID","challenge":"$CHALLENGE_SECRET","expiresAt":999999,
          "workspaces":[{"workspaceId":"$WORKSPACE_ID","keyEpoch":2,
          "keyCommitment":"$HASH","recoveryWrappedKey":"recovery-wrapped-key",
          "retainedKeyEnvelopes":[{"keyEpoch":1,"keyCommitment":"$PUBLIC_KEY",
          "recoveryWrappedKey":"retained-recovery-wrapped-key"}]}]
        }"""
        const val RECOVERY_CLAIM = """{
          "claimId":"$CLAIM_ID","userId":"$USER_ID","deviceId":"$NEW_DEVICE_ID",
          "rotationRequiredWorkspaces":["$WORKSPACE_ID"],"workspaceBindings":[{
            "workspaceId":"$WORKSPACE_ID","deviceAuthEpoch":1,"membershipAuthEpoch":1,"activeKeyEpoch":2
          }]
        }"""
    }
}
