package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.trust.DeviceDirectoryTrustContext
import dev.shinsou.kmp.sync.trust.DeviceDirectoryVerifier
import dev.shinsou.kmp.sync.trust.InMemoryDeviceDirectoryPinStore
import dev.shinsou.kmp.sync.trust.PinnedDeviceDirectory
import dev.shinsou.kmp.sync.trust.PinnedDeviceIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncKeyRotationCoordinatorTest {
    @Test
    fun manifestCborIsDeterministicAcrossRecipientMapOrder() {
        val first = manifest(
            hashes = linkedMapOf(DEVICE_B to HASH_B, DEVICE_ID to HASH_A),
            epochs = linkedMapOf(DEVICE_B to 3, DEVICE_ID to 1),
        )
        val second = manifest(
            hashes = linkedMapOf(DEVICE_ID to HASH_A, DEVICE_B to HASH_B),
            epochs = linkedMapOf(DEVICE_ID to 1, DEVICE_B to 3),
        )
        val firstBytes = KeyRotationManifestCodec.encode(first)
        val secondBytes = KeyRotationManifestCodec.encode(second)
        assertEquals(firstBytes, secondBytes)
        assertEquals(first, KeyRotationManifestCodec.decode(firstBytes))
    }

    @Test
    fun rotationCommitsFreshEpochRefreshesCapabilityAndAcknowledges() = runTest {
        val secrets = InMemorySyncSecretStore()
        val sessions = InMemorySyncSessionStore(session())
        val crypto = DeterministicControlCrypto()
        val api = RecordingControlApi()
        val coordinator = coordinator(api, crypto, secrets, sessions)

        val result = coordinator.rotate(preflight())

        assertEquals(listOf("lease", "commit", "ack"), api.calls)
        assertEquals(2, result.session.activeKeyEpoch)
        assertEquals(2, sessions.load()?.activeKeyEpoch)
        assertIs<SyncSecretReadResult.Available>(
            secrets.read(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 2)),
        )
        val committed = requireNotNull(api.commitRequest)
        val decoded = KeyRotationManifestCodec.decode(
            BinaryData.copyOf(dev.shinsou.kmp.sync.network.decodeBase64Url(committed.manifestCborBase64Url)),
        )
        assertEquals(setOf(DEVICE_ID, DEVICE_B), decoded.recipientEnvelopeHashes.keys)
        assertEquals(mapOf(DEVICE_ID to 1L, DEVICE_B to 4L), decoded.recipientAuthEpochs)
        assertEquals(2, decoded.toEpoch)
        assertTrue(result.acknowledgement.acknowledged)
    }

    @Test
    fun uncertainCommitPreservesPendingKeyAndPreventsCompetingRetry() = runTest {
        val secrets = InMemorySyncSecretStore()
        val sessions = InMemorySyncSessionStore(session())
        val crypto = DeterministicControlCrypto()
        val api = RecordingControlApi(failCommit = true)
        val coordinator = coordinator(api, crypto, secrets, sessions)

        assertFailsWith<IllegalStateException> { coordinator.rotate(preflight()) }
        assertIs<SyncSecretReadResult.Available>(
            secrets.read(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 2)),
        )
        assertFailsWith<SyncControlPlaneException.PendingOperation> {
            coordinator.rotate(preflight())
        }
        assertEquals(1, api.calls.count { it == "lease" })
    }

    @Test
    fun clearedServerGateCannotCreateAnotherEpoch() = runTest {
        val api = RecordingControlApi()
        val coordinator = coordinator(
            api,
            DeterministicControlCrypto(),
            InMemorySyncSecretStore(),
            InMemorySyncSessionStore(session()),
        )

        assertFailsWith<SyncControlPlaneException.Protocol> {
            coordinator.rotate(preflight(rotationRequired = false))
        }
        assertTrue(api.calls.isEmpty())
    }

    private fun coordinator(
        api: RecordingControlApi,
        crypto: SyncCrypto,
        secrets: SyncSecretStore,
        sessions: SyncSessionStore,
    ) = SyncKeyRotationCoordinator(
        api = api,
        crypto = crypto,
        secretStore = secrets,
        sessionStore = sessions,
        capabilityProvider = { capability(it.activeKeyEpoch) },
        envelopeInstaller = SyncWorkspaceKeyEnvelopeInstaller(
            secrets,
            crypto,
            DeviceDirectoryVerifier(InMemoryDeviceDirectoryPinStore()),
        ),
        trustContextProvider = {
            DeviceDirectoryTrustContext(instanceId = it.instanceId, workspaceId = it.workspaceId)
        },
        nowMillis = { 100 },
        newRotationId = { ROTATION_ID },
    )

    private class RecordingControlApi(
        private val failCommit: Boolean = false,
    ) : SyncControlPlaneApi {
        val calls = mutableListOf<String>()
        var commitRequest: RotationCommitRequest? = null

        override suspend fun createRotationLease(
            session: SyncSession,
            capability: WorkspaceCapability,
            rotationId: String,
            fromEpoch: Int,
            signatureBase64Url: String,
        ): KeyRotationLease {
            calls += "lease"
            return KeyRotationLease(
                rotationId,
                session.workspaceId,
                1,
                2,
                session.deviceId,
                1,
                1,
                recipients = listOf(
                    RotationRecipient(DEVICE_B, 4, encodeBase64Url(ByteArray(32) { 2 })),
                    RotationRecipient(DEVICE_ID, 1, encodeBase64Url(ByteArray(32) { 1 })),
                ),
                recovery = RotationRecoveryRecipient(5, encodeBase64Url(ByteArray(32) { 3 })),
                expiresAtMillis = 1_000,
            )
        }

        override suspend fun commitRotation(
            session: SyncSession,
            capability: WorkspaceCapability,
            rotationId: String,
            request: RotationCommitRequest,
        ): RotationCommitReceipt {
            calls += "commit"
            commitRequest = request
            if (failCommit) error("lost response")
            val manifest = KeyRotationManifestCodec.decode(
                BinaryData.copyOf(dev.shinsou.kmp.sync.network.decodeBase64Url(request.manifestCborBase64Url)),
            )
            return RotationCommitReceipt(
                rotationId,
                session.workspaceId,
                manifest.fromEpoch,
                manifest.toEpoch,
                manifest.keyCommitmentBase64Url,
                "committed",
            )
        }

        override suspend fun acknowledgeRotation(
            session: SyncSession,
            capability: WorkspaceCapability,
            rotationId: String,
            keyCommitmentBase64Url: String,
            signatureBase64Url: String,
        ): RotationAcknowledgementReceipt {
            calls += "ack"
            return RotationAcknowledgementReceipt(rotationId, session.deviceId, true)
        }

        override suspend fun workspaceKeyBootstrap(
            session: SyncSession,
            capability: WorkspaceCapability,
        ): WorkspaceKeyBootstrap = error("not used")

        override suspend fun createRecoveryChallenge(endpoint: String, userId: String): RecoveryChallenge =
            error("not used")

        override suspend fun claimRecovery(endpoint: String, request: RecoveryClaimRequest): RecoveryClaimReceipt =
            error("not used")
    }

    private class DeterministicControlCrypto : SyncCrypto {
        override suspend fun prepareEventSealer(session: SyncSession, keyEpoch: Int): PreparedSyncEventSealer =
            error("not used")

        override suspend fun openAndVerifyEvent(
            session: SyncSession,
            remote: RemoteCommittedEnvelope,
        ): OpenedRemoteEvent = error("not used")

        override suspend fun openAndVerifyCheckpoint(
            session: SyncSession,
            checkpoint: EncryptedSyncCheckpoint,
            descriptor: RetainedCheckpointDescriptor,
        ): VerifiedSyncCheckpoint = error("not used")

        override suspend fun sealCheckpoint(
            session: SyncSession,
            checkpointId: String,
            state: SyncState,
            previousStableCiphertextSha256Base64Url: String?,
        ): EncryptedSyncCheckpoint = error("not used")

        override suspend fun generateWorkspaceEpochKey(): SecretMaterial = SecretMaterial(ByteArray(32) { 7 }.asList())

        override suspend fun keyCommitment(material: SecretMaterial): BinaryData =
            BinaryData.copyOf(ByteArray(32) { 9 })

        override suspend fun wrapWorkspaceKey(material: SecretMaterial, recipientPublicKey: BinaryData): BinaryData =
            BinaryData.copyOf(byteArrayOf(recipientPublicKey.copyBytes().first(), 5, 6))

        override suspend fun signDeviceMessage(message: BinaryData): BinaryData =
            BinaryData.copyOf(ByteArray(64) { index -> (index + 1).toByte() })

        override suspend fun verifyDeviceSignature(
            message: BinaryData,
            signature: BinaryData,
            publicKey: BinaryData,
        ): Boolean = true
    }

    private fun manifest(
        hashes: Map<String, String>,
        epochs: Map<String, Long>,
    ) = KeyRotationManifest(
        rotationId = ROTATION_ID,
        workspaceId = WORKSPACE_ID,
        fromEpoch = 1,
        toEpoch = 2,
        proposerDeviceId = DEVICE_ID,
        proposerDeviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        keyCommitmentBase64Url = HASH_A,
        recipientEnvelopeHashes = hashes,
        recipientAuthEpochs = epochs,
        recoveryEnvelopeHashBase64Url = HASH_C,
        recoveryAuthEpoch = 2,
        expiresAtMillis = 10_000,
    )

    private fun session() = SyncSession(
        endpoint = "https://sync.example.test",
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

    private fun capability(epoch: Int) = WorkspaceCapability(
        token = SecretMaterial("capability-token-abcdefghijklmnopqrstuvwxyz".encodeToByteArray().asList()),
        binding = CapabilityBinding(DEVICE_ID, WORKSPACE_ID, 1, 1, epoch, 10_000),
    )

    private fun preflight(rotationRequired: Boolean = true) = VerifiedRotationPreflight(
        session = session(),
        capability = capability(1),
        rotationRequired = rotationRequired,
        deviceDirectory = PinnedDeviceDirectory(
            workspaceId = WORKSPACE_ID,
            version = 1,
            hash = HASH_A,
            allDeviceCount = 2,
            devices = listOf(
                pinnedDevice(DEVICE_ID, 1, encodeBase64Url(ByteArray(32) { 1 })),
                pinnedDevice(DEVICE_B, 4, encodeBase64Url(ByteArray(32) { 2 })),
            ),
        ),
    )

    private fun pinnedDevice(deviceId: String, authEpoch: Long, wrappingKey: String) = PinnedDeviceIdentity(
        deviceId = deviceId,
        userId = USER_ID,
        displayName = "Device",
        platform = "other",
        signingPublicKey = encodeBase64Url(ByteArray(32) { 3 }),
        wrappingPublicKey = wrappingKey,
        status = "active",
        authEpoch = authEpoch,
        createdAt = 1,
        attestationSha256 = HASH_C,
    )

    private companion object {
        const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
        const val USER_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val DEVICE_B = "55555555-5555-4555-8555-555555555555"
        const val ROTATION_ID = "66666666-6666-4666-8666-666666666666"
        val HASH_A = encodeBase64Url(ByteArray(32) { 1 })
        val HASH_B = encodeBase64Url(ByteArray(32) { 2 })
        val HASH_C = encodeBase64Url(ByteArray(32) { 3 })
    }
}
