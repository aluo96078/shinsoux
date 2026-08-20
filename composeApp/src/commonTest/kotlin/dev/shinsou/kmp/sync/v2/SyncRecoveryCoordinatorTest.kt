package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.crypto.DeterministicCbor
import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import dev.shinsou.kmp.sync.crypto.InMemorySyncDevicePublicKeyResolver
import dev.shinsou.kmp.sync.crypto.SodiumSyncCrypto
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.trust.RecoveryKitManager
import dev.shinsou.kmp.sync.trust.RecoveryKitPublicMetadata
import dev.shinsou.kmp.sync.trust.canonicalSyncJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncRecoveryCoordinatorTest {
    @Test
    fun oldKitSignsClaimAndStagedReplacementActivatesOnlyAfterSuccess() = runTest {
        val fixture = fixture()
        val prepared = fixture.coordinator.prepare(fixture.metadata, "Recovered iPhone", "ios")

        assertSignatureUses(
            fixture.manager.signRecoveryClaimManifest("{}"),
            fixture.metadata.recoverySigningPublicKey,
            "{}",
        )
        assertIs<SyncSecretReadResult.Available>(
            fixture.secrets.read(SyncSecretKey.PendingRecoverySigningPrivateKey),
        )

        val completed = fixture.coordinator.submit(prepared)

        assertEquals(mapOf(WORKSPACE_ID to fixture.activeEpoch), completed.recoveredWorkspaceEpochs)
        assertEquals(listOf(WORKSPACE_ID), completed.receipt.rotationRequiredWorkspaceIds)
        assertSignatureUses(
            fixture.manager.signRecoveryClaimManifest("{}"),
            prepared.replacementKit.metadata.recoverySigningPublicKey,
            "{}",
        )
        assertEquals(
            SyncSecretReadResult.Missing,
            fixture.secrets.read(SyncSecretKey.PendingRecoverySigningPrivateKey),
        )
        assertEquals(
            SecretMaterial(fixture.workspaceKey.asList()),
            assertIs<SyncSecretReadResult.Available>(
                fixture.secrets.read(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, fixture.activeEpoch)),
            ).material,
        )
        assertTrue(fixture.api.claimSignatureVerified)
    }

    @Test
    fun prepareRewrapsEveryRetainedEpochToReplacementRecoveryRoot() = runTest {
        val fixture = fixture(withRetainedEpoch = true)

        val prepared = fixture.coordinator.prepare(fixture.metadata, "Recovered iPhone", "ios")

        assertEquals(listOf(2), prepared.request.workspaceEnvelopes.map { it.keyEpoch })
        assertEquals(
            listOf(1, 2),
            prepared.request.workspaceEnvelopes.single().replacementRecoveryEnvelopes.map { it.keyEpoch },
        )
        assertEquals(
            SecretMaterial(requireNotNull(fixture.retainedKey).asList()),
            assertIs<SyncSecretReadResult.Available>(
                fixture.secrets.read(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 1)),
            ).material,
        )
        assertEquals(
            SecretMaterial(fixture.workspaceKey.asList()),
            assertIs<SyncSecretReadResult.Available>(
                fixture.secrets.read(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 2)),
            ).material,
        )
        fixture.manager.activateStagedReplacement()
        val replacementWraps = prepared.request.workspaceEnvelopes.single().replacementRecoveryEnvelopes
        assertEquals(
            SecretMaterial(requireNotNull(fixture.retainedKey).asList()),
            fixture.manager.unwrapRecoveryWorkspaceKey(
                replacementWraps.first { it.keyEpoch == 1 }.recoveryWrappedKeyBase64Url,
                replacementWraps.first { it.keyEpoch == 1 }.keyCommitmentBase64Url,
            ),
        )
        assertEquals(
            SecretMaterial(fixture.workspaceKey.asList()),
            fixture.manager.unwrapRecoveryWorkspaceKey(
                replacementWraps.first { it.keyEpoch == 2 }.recoveryWrappedKeyBase64Url,
                replacementWraps.first { it.keyEpoch == 2 }.keyCommitmentBase64Url,
            ),
        )
    }

    @Test
    fun failedClaimKeepsOldKitAndPendingReplacementForSafeRetry() = runTest {
        val fixture = fixture(failClaim = true)
        val prepared = fixture.coordinator.prepare(fixture.metadata, "Recovered iPhone", "ios")

        assertFailsWith<IllegalStateException> { fixture.coordinator.submit(prepared) }

        assertSignatureUses(
            fixture.manager.signRecoveryClaimManifest("{}"),
            fixture.metadata.recoverySigningPublicKey,
            "{}",
        )
        assertIs<SyncSecretReadResult.Available>(
            fixture.secrets.read(SyncSecretKey.PendingRecoverySigningPrivateKey),
        )
    }

    private suspend fun fixture(
        failClaim: Boolean = false,
        withRetainedEpoch: Boolean = false,
    ): Fixture {
        SodiumSyncPrimitives.initialize()
        val secrets = InMemorySyncSecretStore()
        val manager = RecoveryKitManager(secrets)
        val generated = manager.generateAndInstall(
            endpoint = ENDPOINT,
            instanceId = INSTANCE_ID,
            userId = USER_ID,
            createdAt = 1,
        )
        val workspaceKey = ByteArray(32) { index -> (index + 1).toByte() }
        val (commitment, wrappedEncoded) = recoveryEnvelope(
            workspaceKey,
            generated.metadata.recoveryWrappingPublicKey,
        )
        val retainedKey = if (withRetainedEpoch) ByteArray(32) { index -> (index + 41).toByte() } else null
        val retained = retainedKey?.let { key ->
            val (retainedCommitment, retainedWrapped) = recoveryEnvelope(
                key,
                generated.metadata.recoveryWrappingPublicKey,
            )
            RecoveryEpochKeyEnvelope(1, retainedCommitment, retainedWrapped)
        }
        val activeEpoch = if (withRetainedEpoch) 2 else 1
        val challenge = RecoveryChallenge(
            challengeId = CHALLENGE_ID,
            challenge = SecretMaterial(encodeBase64Url(ByteArray(32) { 8 }).encodeToByteArray().asList()),
            expiresAtMillis = 10_000,
            workspaces = listOf(
                RecoveryWorkspaceChallenge(
                    WORKSPACE_ID,
                    activeEpoch,
                    commitment,
                    wrappedEncoded,
                    retainedKeyEnvelopes = listOfNotNull(retained),
                ),
            ),
        )
        val api = RecordingRecoveryApi(challenge, generated.metadata.recoverySigningPublicKey, failClaim)
        val crypto = SodiumSyncCrypto(
            secrets,
            DeterministicSyncEventCodec(),
            InMemorySyncDevicePublicKeyResolver(),
        )
        return Fixture(
            secrets = secrets,
            manager = manager,
            metadata = generated.metadata,
            api = api,
            coordinator = SyncRecoveryCoordinator(
                api,
                crypto,
                secrets,
                manager,
                nowMillis = { 100 },
                newDeviceId = { NEW_DEVICE_ID },
            ),
            workspaceKey = workspaceKey,
            retainedKey = retainedKey,
            activeEpoch = activeEpoch,
        )
    }

    private fun recoveryEnvelope(workspaceKey: ByteArray, recoveryPublicKey: String): Pair<String, String> {
        val commitment = encodeBase64Url(
            SodiumSyncPrimitives.sha256(
                "shinsou:workspace-key-commitment:v1\u0000".encodeToByteArray() + workspaceKey,
            ),
        )
        val wrapped = SodiumSyncPrimitives.wrapKey(
            workspaceKey,
            decodeBase64Url(recoveryPublicKey),
            "shinsou:workspace-key-envelope:v1".encodeToByteArray(),
        )
        return commitment to encodeBase64Url(
            DeterministicCbor.encode(
                JsonObject(
                    mapOf(
                        "cipherSuite" to JsonPrimitive("X25519_HKDF_SHA256_CHACHA20_POLY1305"),
                        "ephemeralPublicKey" to JsonPrimitive(encodeBase64Url(wrapped.ephemeralPublicKey)),
                        "nonce" to JsonPrimitive(encodeBase64Url(wrapped.nonce)),
                        "ciphertext" to JsonPrimitive(encodeBase64Url(wrapped.ciphertext)),
                    ),
                ),
            ),
        )
    }

    private inner class RecordingRecoveryApi(
        private val challenge: RecoveryChallenge,
        private val oldRecoverySigningPublicKey: String,
        private val failClaim: Boolean,
    ) : SyncControlPlaneApi {
        var claimSignatureVerified = false

        override suspend fun createRecoveryChallenge(endpoint: String, userId: String): RecoveryChallenge = challenge

        override suspend fun claimRecovery(endpoint: String, request: RecoveryClaimRequest): RecoveryClaimReceipt {
            val attestation = canonicalRecoveryAttestation(request)
            claimSignatureVerified = SodiumSyncPrimitives.verifyEd25519(
                "shinsou:recovery-claim:v1\u0000".encodeToByteArray() + attestation.encodeToByteArray(),
                decodeBase64Url(request.signatureBase64Url),
                decodeBase64Url(oldRecoverySigningPublicKey),
            )
            if (!claimSignatureVerified) error("claim signature did not match old Recovery Kit")
            if (failClaim) error("server rejected claim")
            return RecoveryClaimReceipt(
                CLAIM_ID,
                request.userId,
                request.device.deviceId,
                request.workspaceEnvelopes.map(RecoveryWorkspaceClaimEnvelope::workspaceId),
                request.workspaceEnvelopes.map {
                    RecoveryWorkspaceBinding(it.workspaceId, 1, 1, it.keyEpoch)
                },
            )
        }

        override suspend fun createRotationLease(
            session: SyncSession,
            capability: WorkspaceCapability,
            rotationId: String,
            fromEpoch: Int,
            signatureBase64Url: String,
        ): KeyRotationLease = error("not used")

        override suspend fun commitRotation(
            session: SyncSession,
            capability: WorkspaceCapability,
            rotationId: String,
            request: RotationCommitRequest,
        ): RotationCommitReceipt = error("not used")

        override suspend fun acknowledgeRotation(
            session: SyncSession,
            capability: WorkspaceCapability,
            rotationId: String,
            keyCommitmentBase64Url: String,
            signatureBase64Url: String,
        ): RotationAcknowledgementReceipt = error("not used")

        override suspend fun workspaceKeyBootstrap(
            session: SyncSession,
            capability: WorkspaceCapability,
        ): WorkspaceKeyBootstrap = error("not used")

    }

    private fun canonicalRecoveryAttestation(request: RecoveryClaimRequest): String {
        val challengeCommitment = hashDecodedSecret(request.challenge)
        val deviceTokenHash = hashDecodedSecret(request.device.deviceCredential)
        return canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(request.instanceId),
                    "userId" to JsonPrimitive(request.userId),
                    "challengeId" to JsonPrimitive(request.challengeId),
                    "challengeCommitment" to JsonPrimitive(challengeCommitment),
                    "device" to JsonObject(
                        mapOf(
                            "deviceId" to JsonPrimitive(request.device.deviceId),
                            "displayName" to JsonPrimitive(request.device.displayName),
                            "platform" to JsonPrimitive(request.device.platform),
                            "signingPublicKey" to JsonPrimitive(request.device.signingPublicKeyBase64Url),
                            "wrappingPublicKey" to JsonPrimitive(request.device.wrappingPublicKeyBase64Url),
                            "deviceTokenHash" to JsonPrimitive(deviceTokenHash),
                        ),
                    ),
                    "previousRecoverySigningPublicKey" to JsonPrimitive(
                        request.previousRecoverySigningPublicKeyBase64Url,
                    ),
                    "newRecoverySigningPublicKey" to JsonPrimitive(
                        request.newRecoverySigningPublicKeyBase64Url,
                    ),
                    "newRecoveryWrappingPublicKey" to JsonPrimitive(
                        request.newRecoveryWrappingPublicKeyBase64Url,
                    ),
                    "replacementRecoveryTrustSignature" to JsonPrimitive(
                        request.replacementRecoveryTrustSignatureBase64Url,
                    ),
                    "workspaceEnvelopes" to JsonArray(
                        request.workspaceEnvelopes.sortedBy(RecoveryWorkspaceClaimEnvelope::workspaceId).map { item ->
                            JsonObject(
                                mapOf(
                                    "workspaceId" to JsonPrimitive(item.workspaceId),
                                    "keyEpoch" to JsonPrimitive(item.keyEpoch),
                                    "keyCommitment" to JsonPrimitive(item.keyCommitmentBase64Url),
                                    "deviceWrappedKeyHash" to JsonPrimitive(hashUtf8(item.deviceWrappedKeyBase64Url)),
                                    "deviceEnvelopeSignature" to JsonPrimitive(
                                        item.deviceEnvelopeSignatureBase64Url,
                                    ),
                                    "recoveryKeyEnvelopes" to JsonArray(
                                        item.replacementRecoveryEnvelopes.sortedBy(RecoveryEpochKeyEnvelope::keyEpoch).map { envelope ->
                                            JsonObject(
                                                mapOf(
                                                    "keyEpoch" to JsonPrimitive(envelope.keyEpoch),
                                                    "keyCommitment" to JsonPrimitive(envelope.keyCommitmentBase64Url),
                                                    "recoveryWrappedKeyHash" to JsonPrimitive(
                                                        hashUtf8(envelope.recoveryWrappedKeyBase64Url),
                                                    ),
                                                ),
                                            )
                                        },
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            ),
        )
    }

    private fun hashDecodedSecret(secret: SecretMaterial): String {
        var encoded: String? = null
        secret.useBytes { encoded = it.decodeToString(throwOnInvalidSequence = true) }
        return encodeBase64Url(SodiumSyncPrimitives.sha256(decodeBase64Url(requireNotNull(encoded))))
    }

    private fun hashUtf8(value: String): String =
        encodeBase64Url(SodiumSyncPrimitives.sha256(value.encodeToByteArray()))

    private fun assertSignatureUses(signature: String, publicKey: String, canonical: String) {
        assertTrue(
            SodiumSyncPrimitives.verifyEd25519(
                "shinsou:recovery-claim:v1\u0000".encodeToByteArray() + canonical.encodeToByteArray(),
                decodeBase64Url(signature),
                decodeBase64Url(publicKey),
            ),
        )
    }

    private data class Fixture(
        val secrets: InMemorySyncSecretStore,
        val manager: RecoveryKitManager,
        val metadata: RecoveryKitPublicMetadata,
        val api: RecordingRecoveryApi,
        val coordinator: SyncRecoveryCoordinator,
        val workspaceKey: ByteArray,
        val retainedKey: ByteArray?,
        val activeEpoch: Int,
    )

    private companion object {
        const val ENDPOINT = "https://sync.example.test"
        const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
        const val USER_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val CHALLENGE_ID = "44444444-4444-4444-8444-444444444444"
        const val NEW_DEVICE_ID = "55555555-5555-4555-8555-555555555555"
        const val CLAIM_ID = "66666666-6666-4666-8666-666666666666"
    }
}
