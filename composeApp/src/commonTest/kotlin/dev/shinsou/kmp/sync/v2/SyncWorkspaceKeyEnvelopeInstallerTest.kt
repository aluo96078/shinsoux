package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.crypto.DeterministicCbor
import dev.shinsou.kmp.sync.crypto.DeterministicSyncEventCodec
import dev.shinsou.kmp.sync.crypto.InMemorySyncDevicePublicKeyResolver
import dev.shinsou.kmp.sync.crypto.SodiumSyncCrypto
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.trust.DeviceDirectoryEntryWire
import dev.shinsou.kmp.sync.trust.DeviceDirectoryTrustContext
import dev.shinsou.kmp.sync.trust.DeviceDirectoryVerifier
import dev.shinsou.kmp.sync.trust.DeviceDirectoryWire
import dev.shinsou.kmp.sync.trust.DeviceEnrollmentAttestationWire
import dev.shinsou.kmp.sync.trust.InMemoryDeviceDirectoryPinStore
import dev.shinsou.kmp.sync.trust.TrustedDeviceAnchor
import dev.shinsou.kmp.sync.trust.calculateDeviceDirectoryHash
import dev.shinsou.kmp.sync.trust.canonicalSyncJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SyncWorkspaceKeyEnvelopeInstallerTest {
    @Test
    fun validPinnedManifestEnvelopeAndCommitmentInstallExactEpoch() = runTest {
        val fixture = fixture()
        val installed = fixture.installer.verifyAndInstall(
            fixture.session,
            fixture.bootstrap,
            fixture.trustContext,
        )
        assertEquals(2, installed.session.activeKeyEpoch)
        assertEquals(listOf(ROTATION_ID), installed.manifests.map(KeyRotationManifest::rotationId))
        val stored = assertIs<SyncSecretReadResult.Available>(
            fixture.secrets.read(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 2)),
        )
        assertEquals(SecretMaterial(fixture.epochKey.asList()), stored.material)
    }

    @Test
    fun substitutedEnvelopeAndCommitmentAreRejectedBeforeSecretWrite() = runTest {
        val fixture = fixture()
        val otherCommitment = encodeBase64Url(ByteArray(32) { 99 })
        val substituted = fixture.bootstrap.copy(
            envelopes = listOf(
                fixture.bootstrap.envelopes.single().copy(
                    wrappedKeyBase64Url = encodeBase64Url(ByteArray(64) { 42 }),
                    keyCommitmentBase64Url = otherCommitment,
                ),
            ),
        )
        assertFailsWith<SyncControlPlaneException.Trust> {
            fixture.installer.verifyAndInstall(fixture.session, substituted, fixture.trustContext)
        }
        assertEquals(
            SyncSecretReadResult.Missing,
            fixture.secrets.read(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 2)),
        )
    }

    @Test
    fun signedManifestMissingCurrentRecipientIsRejected() = runTest {
        val fixture = fixture(
            manifestTransform = { manifest ->
                manifest.copy(
                    recipientEnvelopeHashes = mapOf(DEVICE_B to hash(ByteArray(32) { 5 })),
                    recipientAuthEpochs = mapOf(DEVICE_B to 1),
                )
            },
        )
        assertFailsWith<SyncControlPlaneException.Trust> {
            fixture.installer.verifyAndInstall(fixture.session, fixture.bootstrap, fixture.trustContext)
        }
        assertEquals(
            SyncSecretReadResult.Missing,
            fixture.secrets.read(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 2)),
        )
    }

    @Test
    fun activeEpochRollbackIsRejectedBeforeDirectoryOrKeyMutation() = runTest {
        val fixture = fixture()
        val current = fixture.session.copy(activeKeyEpoch = 2)
        assertFailsWith<SyncControlPlaneException.Trust> {
            fixture.installer.verifyAndInstall(
                current,
                fixture.bootstrap.copy(activeKeyEpoch = 1),
                fixture.trustContext,
            )
        }
        assertEquals(
            SyncSecretReadResult.Missing,
            fixture.secrets.read(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 2)),
        )
    }

    private suspend fun fixture(
        manifestTransform: suspend (KeyRotationManifest) -> KeyRotationManifest = { it },
    ): Fixture {
        SodiumSyncPrimitives.initialize()
        val signing = SodiumSyncPrimitives.generateEd25519KeyPair()
        val wrapping = SodiumSyncPrimitives.generateX25519KeyPair()
        val recoverySigning = SodiumSyncPrimitives.generateEd25519KeyPair()
        val recoveryWrapping = SodiumSyncPrimitives.generateX25519KeyPair()
        val secrets = InMemorySyncSecretStore().apply {
            write(SyncSecretKey.DeviceSigningPrivateKey, SecretMaterial(signing.privateKey.asList()))
            write(SyncSecretKey.DeviceWrappingPrivateKey, SecretMaterial(wrapping.privateKey.asList()))
        }
        val crypto = SodiumSyncCrypto(
            secrets,
            DeterministicSyncEventCodec(),
            InMemorySyncDevicePublicKeyResolver(
                mapOf(DEVICE_ID to BinaryData.copyOf(signing.publicKey)),
            ),
        )
        val directoryEntry = initialDirectoryEntry(
            signingPublic = encodeBase64Url(signing.publicKey),
            signingPrivate = signing.privateKey,
            wrappingPublic = encodeBase64Url(wrapping.publicKey),
            recoverySigningPublic = encodeBase64Url(recoverySigning.publicKey),
            recoverySigningPrivate = recoverySigning.privateKey,
            recoveryWrappingPublic = encodeBase64Url(recoveryWrapping.publicKey),
        )
        val directory = DeviceDirectoryWire(
            version = 1,
            hash = calculateDeviceDirectoryHash(WORKSPACE_ID, 1, listOf(directoryEntry)),
            allDeviceCount = 1,
            devices = listOf(directoryEntry),
        )
        val epochKey = ByteArray(32) { index -> (index + 10).toByte() }
        val wrapped = SodiumSyncPrimitives.wrapKey(
            epochKey,
            wrapping.publicKey,
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
        val commitment = hash(
            "shinsou:workspace-key-commitment:v1\u0000".encodeToByteArray() + epochKey,
        )
        val recoveryHash = hash("recovery-envelope".encodeToByteArray())
        val originalManifest = KeyRotationManifest(
            rotationId = ROTATION_ID,
            workspaceId = WORKSPACE_ID,
            fromEpoch = 1,
            toEpoch = 2,
            proposerDeviceId = DEVICE_ID,
            proposerDeviceAuthEpoch = 1,
            membershipAuthEpoch = 1,
            keyCommitmentBase64Url = commitment,
            recipientEnvelopeHashes = mapOf(DEVICE_ID to hash(wrappedEncoded.encodeToByteArray())),
            recipientAuthEpochs = mapOf(DEVICE_ID to 1),
            recoveryEnvelopeHashBase64Url = recoveryHash,
            recoveryAuthEpoch = 1,
            expiresAtMillis = 10_000,
        )
        val manifest = manifestTransform(originalManifest)
        val manifestBytes = KeyRotationManifestCodec.encode(manifest).copyBytes()
        val signature = encodeBase64Url(
            SodiumSyncPrimitives.signEd25519(
                "shinsou:rotation-manifest:v1\u0000".encodeToByteArray() + manifestBytes,
                signing.privateKey,
            ),
        )
        val evidence = CommittedRotationEvidence(
            manifestCborBase64Url = encodeBase64Url(manifestBytes),
            manifestSignatureBase64Url = signature,
            proposerDeviceId = DEVICE_ID,
            proposerSigningPublicKeyBase64Url = encodeBase64Url(signing.publicKey),
            recipientEnvelopeHashes = manifest.recipientEnvelopeHashes,
            recipientAuthEpochs = manifest.recipientAuthEpochs,
            recoveryEnvelopeHashBase64Url = manifest.recoveryEnvelopeHashBase64Url,
            status = "committed",
        )
        val envelope = DeviceWorkspaceKeyEnvelope(
            keyEpoch = 2,
            rotationId = ROTATION_ID,
            keyCommitmentBase64Url = commitment,
            wrappedKeyBase64Url = wrappedEncoded,
            wrappedByDeviceId = DEVICE_ID,
            signatureBase64Url = signature,
            rotationEvidence = evidence,
        )
        return Fixture(
            secrets = secrets,
            installer = SyncWorkspaceKeyEnvelopeInstaller(
                secrets,
                crypto,
                DeviceDirectoryVerifier(InMemoryDeviceDirectoryPinStore()),
            ),
            session = session(),
            bootstrap = WorkspaceKeyBootstrap(WORKSPACE_ID, 2, listOf(envelope), directory),
            trustContext = DeviceDirectoryTrustContext(
                instanceId = INSTANCE_ID,
                workspaceId = WORKSPACE_ID,
                trustedDevices = listOf(
                    TrustedDeviceAnchor(
                        DEVICE_ID,
                        encodeBase64Url(signing.publicKey),
                        encodeBase64Url(wrapping.publicKey),
                    ),
                ),
            ),
            epochKey = epochKey,
        )
    }

    private fun initialDirectoryEntry(
        signingPublic: String,
        signingPrivate: ByteArray,
        wrappingPublic: String,
        recoverySigningPublic: String,
        recoverySigningPrivate: ByteArray,
        recoveryWrappingPublic: String,
    ): DeviceDirectoryEntryWire {
        val placeholderHash = hash(ByteArray(32) { 7 })
        val recoveryTrustManifest = canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(INSTANCE_ID),
                    "userId" to JsonPrimitive(USER_ID),
                    "workspaceId" to JsonPrimitive(WORKSPACE_ID),
                    "deviceId" to JsonPrimitive(DEVICE_ID),
                    "signingPublicKey" to JsonPrimitive(signingPublic),
                    "wrappingPublicKey" to JsonPrimitive(wrappingPublic),
                    "recoverySigningPublicKey" to JsonPrimitive(recoverySigningPublic),
                    "recoveryWrappingPublicKey" to JsonPrimitive(recoveryWrappingPublic),
                ),
            ),
        )
        val recoveryTrustSignature = encodeBase64Url(
            SodiumSyncPrimitives.signEd25519(
                "shinsou:initial-device-recovery-trust:v1\u0000".encodeToByteArray() +
                    recoveryTrustManifest.encodeToByteArray(),
                recoverySigningPrivate,
            ),
        )
        val manifest = canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(INSTANCE_ID),
                    "userId" to JsonPrimitive(USER_ID),
                    "workspaceId" to JsonPrimitive(WORKSPACE_ID),
                    "deviceId" to JsonPrimitive(DEVICE_ID),
                    "signingPublicKey" to JsonPrimitive(signingPublic),
                    "wrappingPublicKey" to JsonPrimitive(wrappingPublic),
                    "deviceTokenHash" to JsonPrimitive(placeholderHash),
                    "keyEpoch" to JsonPrimitive(1),
                    "keyCommitment" to JsonPrimitive(placeholderHash),
                    "deviceWrappedKeyHash" to JsonPrimitive(placeholderHash),
                    "recoverySigningPublicKey" to JsonPrimitive(recoverySigningPublic),
                    "recoveryWrappingPublicKey" to JsonPrimitive(recoveryWrappingPublic),
                    "recoveryWrappedKeyHash" to JsonPrimitive(placeholderHash),
                    "recoveryDeviceTrustSignature" to JsonPrimitive(recoveryTrustSignature),
                ),
            ),
        )
        val signature = encodeBase64Url(
            SodiumSyncPrimitives.signEd25519(
                "shinsou:initial-workspace-claim:v1\u0000".encodeToByteArray() + manifest.encodeToByteArray(),
                signingPrivate,
            ),
        )
        return DeviceDirectoryEntryWire(
            deviceId = DEVICE_ID,
            userId = USER_ID,
            displayName = "Phone",
            platform = "ios",
            signingPublicKey = signingPublic,
            wrappingPublicKey = wrappingPublic,
            status = "active",
            authEpoch = 1,
            createdAt = 1,
            attestation = DeviceEnrollmentAttestationWire(
                type = "initial",
                workspaceId = WORKSPACE_ID,
                attestorDeviceId = DEVICE_ID,
                attestorPublicKey = signingPublic,
                signatureDomain = "initial-workspace-claim",
                manifestJson = manifest,
                signature = signature,
                createdAt = 1,
            ),
        )
    }

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

    private fun hash(bytes: ByteArray): String =
        encodeBase64Url(SodiumSyncPrimitives.sha256(bytes))

    private data class Fixture(
        val secrets: InMemorySyncSecretStore,
        val installer: SyncWorkspaceKeyEnvelopeInstaller,
        val session: SyncSession,
        val bootstrap: WorkspaceKeyBootstrap,
        val trustContext: DeviceDirectoryTrustContext,
        val epochKey: ByteArray,
    )

    private companion object {
        const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
        const val USER_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val DEVICE_B = "55555555-5555-4555-8555-555555555555"
        const val ROTATION_ID = "66666666-6666-4666-8666-666666666666"
    }
}
