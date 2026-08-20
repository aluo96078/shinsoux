package dev.shinsou.kmp.sync.provisioning

import dev.shinsou.kmp.sync.crypto.DeterministicCbor
import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import dev.shinsou.kmp.sync.crypto.InMemorySyncDevicePublicKeyResolver
import dev.shinsou.kmp.sync.crypto.SodiumSyncCrypto
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.persistence.SyncInstallationIdentity
import dev.shinsou.kmp.sync.trust.RecoveryKitManager
import dev.shinsou.kmp.sync.v2.EphemeralSyncPayload
import dev.shinsou.kmp.sync.v2.InMemorySyncSecretStore
import dev.shinsou.kmp.sync.v2.InMemorySyncSessionStore
import dev.shinsou.kmp.sync.v2.KeyRotationLease
import dev.shinsou.kmp.sync.v2.RecoveryChallenge
import dev.shinsou.kmp.sync.v2.RecoveryClaimReceipt
import dev.shinsou.kmp.sync.v2.RecoveryClaimRequest
import dev.shinsou.kmp.sync.v2.RecoveryWorkspaceBinding
import dev.shinsou.kmp.sync.v2.RecoveryWorkspaceChallenge
import dev.shinsou.kmp.sync.v2.RotationAcknowledgementReceipt
import dev.shinsou.kmp.sync.v2.RotationCommitReceipt
import dev.shinsou.kmp.sync.v2.RotationCommitRequest
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncControlPlaneApi
import dev.shinsou.kmp.sync.v2.SyncEngineState
import dev.shinsou.kmp.sync.v2.SyncRecoveryCoordinator
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.WorkspaceCapability
import dev.shinsou.kmp.sync.v2.WorkspaceKeyBootstrap
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultSyncRecoveryUiDelegateTest {
    @Test
    fun lostClaimResponseResumesAfterRestartAndReturnsReplacementKit() = runTest {
        val fixture = fixture()

        assertFailsWith<SyncProvisioningException> {
            fixture.delegate().recoverAndVerify(
                fixture.encodedOldKit,
                INSTALLATION,
                "Recovered iPhone",
                "ios",
            )
        }
        val linking = assertNotNull(fixture.sessions.load())
        assertEquals(SyncSessionStatus.LINKING, linking.status)
        assertNotNull(linking.pendingRecovery)
        assertTrue(fixture.control.claimObservedDurableLinking)

        // A new delegate instance represents a process restart. It must reconcile the committed
        // recovery claim rather than running the initial-workspace seed path.
        val activation = fixture.delegate().resumePendingRecovery(linking)
        val ready = assertNotNull(activation).readySession

        assertEquals(listOf("catch-up", "rotate", "sync"), fixture.activation.calls)
        assertEquals(SyncSessionStatus.READY, ready.status)
        assertEquals(2, ready.activeKeyEpoch)
        assertEquals(null, ready.pendingRecovery)
        assertEquals(fixture.oldRecoverySigningPublicKey, fixture.activation.recoveryAnchor?.recoverySigningPublicKey)
        assertEquals(linking.pendingRecovery?.deviceSigningPublicKey, fixture.activation.recoveryAnchor?.signingPublicKey)

        val replacementStore = InMemorySyncSecretStore()
        val replacementMetadata = RecoveryKitManager(replacementStore).importAndInstall(
            activation.replacementKit.toSecretMaterial(),
        )
        assertNotEquals(fixture.oldRecoverySigningPublicKey, replacementMetadata.recoverySigningPublicKey)
        assertEquals(linking.pendingRecovery?.replacementRecoverySigningPublicKey,
            replacementMetadata.recoverySigningPublicKey)
    }

    @Test
    fun reconciledBindingEpochMismatchFailsBeforeActivation() = runTest {
        val fixture = fixture(bindingEpochDelta = 1)
        assertFailsWith<SyncProvisioningException> {
            fixture.delegate().recoverAndVerify(fixture.encodedOldKit, INSTALLATION, "Recovered iPhone", "ios")
        }
        val linking = assertNotNull(fixture.sessions.load())

        val failure = assertFailsWith<SyncProvisioningException> {
            fixture.delegate().resumePendingRecovery(linking)
        }

        assertEquals("recovery_workspace_binding_mismatch", failure.safeCode)
        assertTrue(fixture.activation.calls.isEmpty())
        assertEquals(SyncSessionStatus.LINKING, fixture.sessions.load()?.status)
    }

    private suspend fun fixture(bindingEpochDelta: Int = 0): Fixture {
        SodiumSyncPrimitives.initialize()
        val sourceSecrets = InMemorySyncSecretStore()
        val generated = RecoveryKitManager(sourceSecrets).generateAndInstall(
            ENDPOINT,
            INSTANCE_ID,
            USER_ID,
            createdAt = 1,
        )
        val workspaceKey = ByteArray(32) { (it + 1).toByte() }
        val commitment = encodeBase64Url(
            SodiumSyncPrimitives.sha256(
                "shinsou:workspace-key-commitment:v1\u0000".encodeToByteArray() + workspaceKey,
            ),
        )
        val wrapped = SodiumSyncPrimitives.wrapKey(
            workspaceKey,
            decodeBase64Url(generated.metadata.recoveryWrappingPublicKey),
            "shinsou:workspace-key-envelope:v1".encodeToByteArray(),
        )
        val wrappedEncoded = encodeBase64Url(
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
        val sessions = InMemorySyncSessionStore()
        val secrets = InMemorySyncSecretStore()
        val control = LostResponseControlApi(
            sessions,
            RecoveryChallenge(
                challengeId = CHALLENGE_ID,
                challenge = SecretMaterial(encodeBase64Url(ByteArray(32) { 7 }).encodeToByteArray().asList()),
                expiresAtMillis = 10_000,
                workspaces = listOf(
                    RecoveryWorkspaceChallenge(WORKSPACE_ID, 1, commitment, wrappedEncoded),
                ),
            ),
            bindingEpochDelta,
        )
        val manager = RecoveryKitManager(secrets)
        val crypto = SodiumSyncCrypto(
            secrets,
            DeterministicSyncEventCodec(),
            InMemorySyncDevicePublicKeyResolver(),
        )
        val coordinator = SyncRecoveryCoordinator(
            control,
            crypto,
            secrets,
            manager,
            nowMillis = { 100 },
        )
        val activation = RecordingActivationGate(sessions)
        val provisioning = RecoveryProvisioningApi(control)
        return Fixture(
            sessions,
            manager,
            coordinator,
            provisioning,
            activation,
            control,
            generated.exportedKit.toPayload(),
            generated.metadata.recoverySigningPublicKey,
        )
    }

    private data class Fixture(
        val sessions: InMemorySyncSessionStore,
        val manager: RecoveryKitManager,
        val coordinator: SyncRecoveryCoordinator,
        val provisioning: CloudflareProvisioningApi,
        val activation: RecordingActivationGate,
        val control: LostResponseControlApi,
        val encodedOldKit: EphemeralSyncPayload,
        val oldRecoverySigningPublicKey: String,
    ) {
        fun delegate() = DefaultSyncRecoveryUiDelegate(
            provisioning,
            manager,
            coordinator,
            sessions,
            activation,
        )
    }

    private class LostResponseControlApi(
        private val sessions: InMemorySyncSessionStore,
        private val challenge: RecoveryChallenge,
        private val bindingEpochDelta: Int,
    ) : SyncControlPlaneApi {
        var receipt: RecoveryClaimReceipt? = null
        var claimObservedDurableLinking = false

        override suspend fun createRecoveryChallenge(endpoint: String, userId: String) = challenge

        override suspend fun claimRecovery(endpoint: String, request: RecoveryClaimRequest): RecoveryClaimReceipt {
            claimObservedDurableLinking = sessions.load()?.let {
                it.status == SyncSessionStatus.LINKING && it.pendingRecovery != null
            } == true
            receipt = RecoveryClaimReceipt(
                CLAIM_ID,
                request.userId,
                request.device.deviceId,
                listOf(WORKSPACE_ID),
                listOf(RecoveryWorkspaceBinding(WORKSPACE_ID, 1, 1, 1 + bindingEpochDelta)),
            )
            error("response lost after commit")
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

    private class RecoveryProvisioningApi(
        private val control: LostResponseControlApi,
    ) : CloudflareProvisioningApi {
        override suspend fun capabilities(endpoint: String) =
            ProvisioningCapabilities(INSTANCE_ID, 1, 1, 1, 1, 1, 1, realtime = true)

        override suspend fun reconcileRecoveryClaim(session: SyncSession) = control.receipt
        override suspend fun claimSetup(endpoint: String, claim: InitialWorkspaceClaim) = error("not used")
        override suspend fun redeemInvite(endpoint: String, claim: InitialWorkspaceClaim) = error("not used")
        override suspend fun reconcileInitialClaim(session: SyncSession) = error("initial reconciliation must not run")
        override suspend fun createInvite(session: SyncSession, ttlSeconds: Int) = error("not used")
        override suspend fun createPairing(session: SyncSession) = error("not used")
        override suspend fun submitPairingCandidate(
            endpoint: String,
            candidate: ProvisioningPairingCandidateInput,
        ) = error("not used")
        override suspend fun pairingAsCandidate(
            endpoint: String,
            pairingId: String,
            secret: EphemeralSyncPayload,
        ) = error("not used")
        override suspend fun pairingAsSponsor(session: SyncSession, pairingId: String) = error("not used")
        override suspend fun approvePairing(
            session: SyncSession,
            pairingId: String,
            approval: ProvisioningPairApproval,
        ) = Unit
        override suspend fun listDevices(session: SyncSession) = emptyList<ProvisioningDevice>()
    }

    private class RecordingActivationGate(
        private val sessions: InMemorySyncSessionStore,
    ) : SyncProvisioningActivationGate {
        override val engineState: StateFlow<SyncEngineState>? = null
        val calls = mutableListOf<String>()
        var recoveryAnchor: ProvisioningTrustContext.RecoveryAnchor? = null

        override suspend fun verifyRecoveredWorkspaceAndCatchUp(
            linkingSession: SyncSession,
            trustContext: ProvisioningTrustContext.RecoveryAnchor,
        ) {
            calls += "catch-up"
            recoveryAnchor = trustContext
        }

        override suspend fun rotateAfterRecovery(session: SyncSession) {
            calls += "rotate"
            val current = assertNotNull(sessions.load())
            sessions.save(current.copy(activeKeyEpoch = current.activeKeyEpoch + 1))
        }

        override suspend fun syncNow() {
            calls += "sync"
        }

        override suspend fun seedSnapshotAndVerifyInitialCheckpoint(
            linkingSession: SyncSession,
            trustContext: ProvisioningTrustContext.InitialSelfAnchor,
        ) = error("initial activation must not run")
        override suspend fun verifyPairedWorkspaceAndCatchUp(
            linkingSession: SyncSession,
            trustContext: ProvisioningTrustContext.PairingSponsorAnchor,
        ) = error("not used")
        override suspend fun rotateAfterRevocation(session: SyncSession, revokedDeviceId: String) = Unit
        override suspend fun leaveWorkspace() = Unit
    }

    private fun SecretMaterial.toPayload(): EphemeralSyncPayload {
        var text: String? = null
        useBytes { text = it.decodeToString(throwOnInvalidSequence = true) }
        return EphemeralSyncPayload(requireNotNull(text))
    }

    private suspend fun EphemeralSyncPayload.toSecretMaterial(): SecretMaterial {
        var material: SecretMaterial? = null
        useSuspending { value -> material = SecretMaterial(value.encodeToByteArray().asList()) }
        return requireNotNull(material)
    }

    private companion object {
        const val ENDPOINT = "https://sync.example.test"
        const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
        const val USER_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val INSTALLATION_ID = "55555555-5555-4555-8555-555555555555"
        const val CHALLENGE_ID = "66666666-6666-4666-8666-666666666666"
        const val CLAIM_ID = "77777777-7777-4777-8777-777777777777"
        val INSTALLATION = SyncInstallationIdentity(
            installationId = INSTALLATION_ID,
            deviceId = DEVICE_ID,
        )
    }
}
