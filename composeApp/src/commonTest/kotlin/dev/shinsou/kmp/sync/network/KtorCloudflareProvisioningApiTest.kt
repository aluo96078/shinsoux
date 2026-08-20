package dev.shinsou.kmp.sync.network

import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import dev.shinsou.kmp.sync.crypto.InMemorySyncDevicePublicKeyResolver
import dev.shinsou.kmp.sync.crypto.SodiumSyncCrypto
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.provisioning.InitialWorkspaceClaim
import dev.shinsou.kmp.sync.provisioning.ProvisioningDeviceRegistration
import dev.shinsou.kmp.sync.provisioning.ProvisioningInitialKeys
import dev.shinsou.kmp.sync.provisioning.ProvisioningPairingCandidateInput
import dev.shinsou.kmp.sync.provisioning.SyncProvisioningException
import dev.shinsou.kmp.sync.v2.EphemeralSyncPayload
import dev.shinsou.kmp.sync.v2.InMemorySyncSecretStore
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.SyncAdminQuota
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KtorCloudflareProvisioningApiTest {
    @Test
    fun capabilitiesSetupAndPairCandidateKeepSecretsOutOfUrlsAndDebugStrings() = runTest {
        SodiumSyncPrimitives.initialize()
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val response = when (request.url.encodedPath) {
                "/v1/capabilities" -> CAPABILITIES
                "/v1/setup/claim" -> INITIAL_CLAIM_RESPONSE
                "/v1/pairings/$PAIRING_ID/candidate" -> CANDIDATE_ACCEPTED
                "/v1/pairings/$PAIRING_ID" -> PAIRING_VIEW
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
            respond(response, HttpStatusCode.OK, JSON_HEADERS)
        }
        val secrets = InMemorySyncSecretStore()
        val crypto = SodiumSyncCrypto(
            secrets,
            DeterministicSyncEventCodec(),
            InMemorySyncDevicePublicKeyResolver(),
        )
        val api = KtorCloudflareProvisioningApi(
            HttpClient(engine) { expectSuccess = false },
            secrets,
            crypto,
            nowMillis = { NOW },
        )

        val capabilities = api.capabilities(ENDPOINT)
        assertEquals(INSTANCE_ID, capabilities.instanceId)
        assertEquals(2, capabilities.protocolVersion)
        assertEquals(7, capabilities.schemaVersion)
        val claimSecret = "Ym9vdHN0cmFwLXNlY3JldC0zMi1ieXRlcy1sb25nISEh"
        val claim = initialClaim(claimSecret)
        assertEquals(WORKSPACE_ID, api.claimSetup(ENDPOINT, claim).workspaceId)

        val pairSecret = "cGFpcmluZy1zZWNyZXQtMzItYnl0ZXMtbG9uZyEhISE"
        val view = api.submitPairingCandidate(
            ENDPOINT,
            ProvisioningPairingCandidateInput(PAIRING_ID, EphemeralSyncPayload(pairSecret), claim.device),
        )
        assertEquals("123456", view.confirmationCode)
        assertEquals(SPONSOR_SIGNING_KEY, view.sponsorSigningPublicKey)
        assertTrue(requests.none { claimSecret in it.url.toString() || pairSecret in it.url.toString() })
        assertEquals(pairSecret, requests.last().headers["X-Shinsou-Pairing-Secret"])
        assertTrue(claimSecret !in claim.toString())
        assertTrue(pairSecret !in view.toString())
    }

    @Test
    fun legacyCapabilitiesWithoutSchemaFieldsFailClosed() = runTest {
        val secrets = InMemorySyncSecretStore()
        val codec = DeterministicSyncEventCodec()
        val api = KtorCloudflareProvisioningApi(
            HttpClient(MockEngine {
                respond(
                    """{"instanceId":"$INSTANCE_ID","protocolVersion":1,"minReaderVersion":1,"minWriterVersion":1,"profile":"lite","websocket":false}""",
                    HttpStatusCode.OK,
                    JSON_HEADERS,
                )
            }) { expectSuccess = false },
            secrets,
            SodiumSyncCrypto(secrets, codec, InMemorySyncDevicePublicKeyResolver()),
            nowMillis = { NOW },
        )

        val failure = assertFailsWith<SyncProvisioningException> {
            api.capabilities(ENDPOINT)
        }
        assertEquals("malformed_provisioning_response", failure.safeCode)
    }

    @Test
    fun emergencyHandoffSendsResetAndSecretOnlyInJsonBody() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(EMERGENCY_CLAIM_RESPONSE, HttpStatusCode.OK, JSON_HEADERS)
        }
        val secrets = InMemorySyncSecretStore()
        val api = KtorCloudflareProvisioningApi(
            HttpClient(engine) { expectSuccess = false },
            secrets,
            SodiumSyncCrypto(
                secrets,
                DeterministicSyncEventCodec(),
                InMemorySyncDevicePublicKeyResolver(),
            ),
            nowMillis = { NOW },
        )
        val handoff = "ZW1lcmdlbmN5LWhhbmRvZmYtc2VjcmV0LTMyLWJ5dGVzISE"
        val claim = initialClaim(handoff)

        val result = api.claimEmergencyReset(ENDPOINT, RESET_ID, claim)

        assertEquals(WORKSPACE_ID, result.workspaceId)
        val request = requests.single()
        assertEquals("/v1/emergency-reset/handoff", request.url.encodedPath)
        assertTrue(handoff !in request.url.toString())
        val body = (request.body as io.ktor.http.content.TextContent).text
        assertTrue("\"resetId\":\"$RESET_ID\"" in body)
        assertTrue("\"handoffSecret\":\"$handoff\"" in body)
        assertTrue("\"bootstrapSecret\"" !in body)
        assertTrue("\"inviteSecret\"" !in body)
    }

    @Test
    fun inviteControlRequestUsesBearerAndSignedReplayProtectedHeaders() = runTest {
        SodiumSyncPrimitives.initialize()
        val signing = SodiumSyncPrimitives.generateEd25519KeyPair()
        val secrets = InMemorySyncSecretStore().apply {
            write(
                SyncSecretKey.DeviceCredential,
                SecretMaterial("ZGV2aWNlLXRva2VuLTMyLWJ5dGVzLWxvbmchISEhIQ".encodeToByteArray().asList()),
            )
            write(SyncSecretKey.DeviceSigningPrivateKey, SecretMaterial(signing.privateKey.asList()))
        }
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val response = when (request.url.encodedPath) {
                "/v1/auth/challenge" -> AUTH_CHALLENGE
                "/v1/auth/token" -> ACCESS_TOKEN
                "/v1/admin/invites" -> INVITE_RESPONSE
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
            respond(response, HttpStatusCode.OK, JSON_HEADERS)
        }
        val crypto = SodiumSyncCrypto(
            secrets,
            DeterministicSyncEventCodec(),
            InMemorySyncDevicePublicKeyResolver(),
        )
        val api = KtorCloudflareProvisioningApi(
            HttpClient(engine) { expectSuccess = false },
            secrets,
            crypto,
            nowMillis = { NOW },
        )

        val invite = api.createInvite(session())
        val control = requests.last()
        assertEquals("Bearer access-token-abcdefghijklmnopqrstuvwxyz", control.headers[HttpHeaders.Authorization])
        assertEquals(NOW.toString(), control.headers["X-Shinsou-Timestamp"])
        assertNotNull(control.headers["X-Shinsou-Nonce"])
        assertNotNull(control.headers["X-Shinsou-Signature"])
        invite.secret.use { raw ->
            assertTrue(raw !in control.url.toString())
            assertTrue(raw !in invite.toString())
        }
    }

    @Test
    fun adminUsageParsesMetadataAndQuotaUpdateUsesSignedPut() = runTest {
        SodiumSyncPrimitives.initialize()
        val signing = SodiumSyncPrimitives.generateEd25519KeyPair()
        val secrets = InMemorySyncSecretStore().apply {
            write(
                SyncSecretKey.DeviceCredential,
                SecretMaterial("ZGV2aWNlLXRva2VuLTMyLWJ5dGVzLWxvbmchISEhIQ".encodeToByteArray().asList()),
            )
            write(SyncSecretKey.DeviceSigningPrivateKey, SecretMaterial(signing.privateKey.asList()))
        }
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val response = when (request.url.encodedPath) {
                "/v1/auth/challenge" -> AUTH_CHALLENGE
                "/v1/auth/token" -> ACCESS_TOKEN
                "/v1/admin/usage" -> ADMIN_USAGE_RESPONSE
                "/v1/admin/quota" -> ADMIN_QUOTA_UPDATE_RESPONSE
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
            respond(response, HttpStatusCode.OK, JSON_HEADERS)
        }
        val api = KtorCloudflareProvisioningApi(
            HttpClient(engine) { expectSuccess = false },
            secrets,
            SodiumSyncCrypto(secrets, DeterministicSyncEventCodec(), InMemorySyncDevicePublicKeyResolver()),
            nowMillis = { NOW },
        )

        val initial = api.adminUsage(session())
        assertEquals(2, initial.totals.activeUsers)
        assertEquals(7L, initial.daily.single().eventsWritten)
        assertEquals(262_144_000L, initial.quota.maxWorkspaceBytes)
        val updated = api.updateAdminQuota(
            session(),
            SyncAdminQuota(30, 1, 12, 300_000_000, 32_768, 33_554_432),
        )
        assertEquals(262_144_000L, updated.quota.maxWorkspaceBytes)
        val put = requests.single { it.url.encodedPath == "/v1/admin/quota" }
        assertEquals("PUT", put.method.value)
        assertEquals("Bearer access-token-abcdefghijklmnopqrstuvwxyz", put.headers[HttpHeaders.Authorization])
        assertEquals(NOW.toString(), put.headers["X-Shinsou-Timestamp"])
        assertNotNull(put.headers["X-Shinsou-Nonce"])
        assertNotNull(put.headers["X-Shinsou-Signature"])
    }

    @Test
    fun revocationUsesSignedClientOperationIdAndLooksUpExactPermanentReceipt() = runTest {
        SodiumSyncPrimitives.initialize()
        val signing = SodiumSyncPrimitives.generateEd25519KeyPair()
        val secrets = InMemorySyncSecretStore().apply {
            write(
                SyncSecretKey.DeviceCredential,
                SecretMaterial("ZGV2aWNlLXRva2VuLTMyLWJ5dGVzLWxvbmchISEhIQ".encodeToByteArray().asList()),
            )
            write(SyncSecretKey.DeviceSigningPrivateKey, SecretMaterial(signing.privateKey.asList()))
        }
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val response = when (request.url.encodedPath) {
                "/v1/auth/challenge" -> AUTH_CHALLENGE
                "/v1/auth/token" -> ACCESS_TOKEN
                "/v1/devices/$TARGET_DEVICE_ID/revoke" -> DEVICE_REVOCATION_RECEIPT
                "/v1/device-revocations/$REVOCATION_ID" -> DEVICE_REVOCATION_RECEIPT
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
            respond(response, HttpStatusCode.OK, JSON_HEADERS)
        }
        val api = KtorCloudflareProvisioningApi(
            HttpClient(engine) { expectSuccess = false },
            secrets,
            SodiumSyncCrypto(secrets, DeterministicSyncEventCodec(), InMemorySyncDevicePublicKeyResolver()),
            nowMillis = { NOW },
        )

        val committed = api.revokeDevice(session(), TARGET_DEVICE_ID, REVOCATION_ID)
        val lookedUp = api.deviceRevocationReceipt(session(), REVOCATION_ID)

        assertEquals(REVOCATION_ID, committed.revocationId)
        assertEquals(committed, lookedUp)
        assertEquals(WORKSPACE_ID, committed.workspaceBindings.single().workspaceId)
        val post = requests.single { it.url.encodedPath.endsWith("/revoke") }
        assertEquals(HttpMethod.Post, post.method)
        assertEquals("{\"revocationId\":\"$REVOCATION_ID\"}", (post.body as io.ktor.http.content.TextContent).text)
        assertNotNull(post.headers["X-Shinsou-Signature"])
        val get = requests.single { it.url.encodedPath == "/v1/device-revocations/$REVOCATION_ID" }
        assertEquals(HttpMethod.Get, get.method)
        assertEquals("Bearer access-token-abcdefghijklmnopqrstuvwxyz", get.headers[HttpHeaders.Authorization])
    }

    private fun initialClaim(secret: String) = InitialWorkspaceClaim(
        bootstrapOrInviteSecret = EphemeralSyncPayload(secret),
        userId = USER_ID,
        workspaceId = WORKSPACE_ID,
        displayName = "User",
        device = ProvisioningDeviceRegistration(
            DEVICE_ID,
            "Phone",
            "ios",
            PUBLIC_KEY,
            PUBLIC_KEY,
            EphemeralSyncPayload("ZGV2aWNlLXRva2VuLTMyLWJ5dGVzLWxvbmchISEhIQ"),
        ),
        initialKeys = ProvisioningInitialKeys(
            HASH,
            "wrapped-device-key-abcdefghijklmnopqrstuvwxyz",
            SIGNATURE,
            PUBLIC_KEY,
            PUBLIC_KEY,
            "wrapped-recovery-key-abcdefghijklmnopqrstuvwxyz",
            SIGNATURE,
        ),
        claimSignature = SIGNATURE,
    )

    private fun session() = SyncSession(
        endpoint = ENDPOINT,
        instanceId = INSTANCE_ID,
        userId = USER_ID,
        workspaceId = WORKSPACE_ID,
        deviceId = DEVICE_ID,
        deviceDisplayName = "Phone",
        platform = "ios",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = 1,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val ENDPOINT = "https://sync.example.test"
        const val INSTANCE_ID = "00000000-0000-4000-8000-000000000001"
        const val USER_ID = "00000000-0000-4000-8000-000000000002"
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000004"
        const val WORKSPACE_ID = "00000000-0000-4000-8000-000000000007"
        const val PAIRING_ID = "00000000-0000-4000-8000-000000000013"
        const val TARGET_DEVICE_ID = "00000000-0000-4000-8000-000000000014"
        const val REVOCATION_ID = "00000000-0000-4000-8000-000000000015"
        const val RESET_ID = "00000000-0000-4000-8000-000000000020"
        const val PUBLIC_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val SPONSOR_SIGNING_KEY = PUBLIC_KEY
        const val HASH = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val SIGNATURE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
        const val CAPABILITIES = """
            {"instanceId":"$INSTANCE_ID","protocolVersion":2,"minReaderVersion":1,
             "minWriterVersion":1,"schemaVersion":7,"minSchemaReaderVersion":4,
             "minSchemaWriterVersion":5,"profile":"realtime","websocket":true}
        """
        const val INITIAL_CLAIM_RESPONSE = """
            {"instanceId":"$INSTANCE_ID","userId":"$USER_ID","workspaceId":"$WORKSPACE_ID",
             "deviceId":"$DEVICE_ID","keyEpoch":1}
        """
        const val EMERGENCY_CLAIM_RESPONSE = """
            {"instanceId":"$INSTANCE_ID","userId":"$USER_ID","workspaceId":"$WORKSPACE_ID",
             "deviceId":"$DEVICE_ID","keyEpoch":1,"resetId":"$RESET_ID"}
        """
        const val CANDIDATE_ACCEPTED = """
            {"pairingId":"$PAIRING_ID","status":"candidate","shortCode":"123456"}
        """
        const val PAIRING_VIEW = """
            {"pairingId":"$PAIRING_ID","workspaceId":"$WORKSPACE_ID","sponsorDeviceId":"$DEVICE_ID",
             "sponsorSigningPublicKey":"$SPONSOR_SIGNING_KEY","transcriptNonce":"nonce-value",
             "sponsorWrappingPublicKey":"$PUBLIC_KEY",
             "status":"candidate","expiresAt":1700000300000,
             "candidate":{"deviceId":"$DEVICE_ID","displayName":"Phone","platform":"ios",
             "signingPublicKey":"$PUBLIC_KEY","wrappingPublicKey":"$PUBLIC_KEY","tokenCommitment":"$HASH"},
             "shortCode":"123456"}
        """
        const val AUTH_CHALLENGE = """
            {"challengeId":"00000000-0000-4000-8000-000000000011",
             "challenge":"Y2hhbGxlbmdlLTMyLWJ5dGVzLWxvbmchISEhISE","expiresAt":1700000060000}
        """
        const val ACCESS_TOKEN = """{"accessToken":"access-token-abcdefghijklmnopqrstuvwxyz","expiresAt":1700000600000}"""
        const val INVITE_RESPONSE = """
            {"inviteId":"00000000-0000-4000-8000-000000000010",
             "inviteSecret":"aW52aXRlLXNlY3JldC0zMi1ieXRlcy1sb25nISEhIQ","expiresAt":1700086400000}
        """
        const val ADMIN_USAGE_RESPONSE = """
            {"generatedAt":1700000000000,
             "quota":{"quotaProfileId":"private-default","maxUsers":25,"maxWorkspacesPerUser":1,
               "maxDevicesPerUser":10,"maxWorkspaceBytes":262144000,"maxEventBytes":32768,
               "maxCheckpointBytes":33554432},
             "totals":{"activeUsers":2,"activeDevices":3,"activeWorkspaces":2,
               "committedBytes":1200,"reservedBytes":300},
             "workspaces":[{"workspaceId":"$WORKSPACE_ID","ownerUserId":"$USER_ID","status":"active",
               "headSeq":9,"committedBytes":1200,"reservedBytes":300,"maximumBytes":262144000}],
             "daily":[{"day":"2026-08-20","eventsWritten":7,"eventBytesWritten":700,
               "checkpointsWritten":2,"checkpointBytesWritten":2000}]}
        """
        const val ADMIN_QUOTA_UPDATE_RESPONSE = """
            {"updatedAt":1700000000000,
             "quota":{"quotaProfileId":"private-default","maxUsers":30,"maxWorkspacesPerUser":1,
               "maxDevicesPerUser":12,"maxWorkspaceBytes":300000000,"maxEventBytes":32768,
               "maxCheckpointBytes":33554432}}
        """
        const val DEVICE_REVOCATION_RECEIPT = """
            {"revocationId":"$REVOCATION_ID","actorDeviceId":"$DEVICE_ID",
             "revokedDeviceId":"$TARGET_DEVICE_ID","committedAt":1700000000000,
             "workspaceBindings":[{"workspaceId":"$WORKSPACE_ID","revokedAtKeyEpoch":1,
               "directoryEpochAfterRevocation":2,"currentActiveKeyEpoch":1,
               "currentRotationRequired":true,"coveringRotationId":null,
               "coveringProposerDeviceId":null}]}
        """
    }
}
