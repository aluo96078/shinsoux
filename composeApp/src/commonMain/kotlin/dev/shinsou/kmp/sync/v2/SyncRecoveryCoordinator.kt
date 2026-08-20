package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.trust.GeneratedRecoveryKit
import dev.shinsou.kmp.sync.trust.RecoveryKitManager
import dev.shinsou.kmp.sync.trust.RecoveryKitPublicMetadata
import dev.shinsou.kmp.sync.trust.canonicalSyncJson
import kotlin.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class PreparedRecoveryClaim(
    val challenge: RecoveryChallenge,
    val replacementKit: GeneratedRecoveryKit,
    val request: RecoveryClaimRequest,
)

data class CompletedRecoveryClaim(
    val receipt: RecoveryClaimReceipt,
    val replacementKit: GeneratedRecoveryKit,
    val recoveredWorkspaceEpochs: Map<String, Int>,
)

/**
 * Recovery is deliberately two-stage: [prepare] keeps the old Recovery Kit active while creating
 * the replacement and new device material; [submit] activates the staged Kit only after the
 * server atomically accepts the old Kit's claim signature.
 */
class SyncRecoveryCoordinator(
    private val api: SyncControlPlaneApi,
    private val crypto: SyncCrypto,
    private val secretStore: SyncSecretStore,
    private val recoveryKitManager: RecoveryKitManager,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newDeviceId: suspend () -> String = { crypto.generateCheckpointId() },
) {
    suspend fun prepare(
        metadata: RecoveryKitPublicMetadata,
        deviceDisplayName: String,
        platform: String,
        deviceId: String? = null,
    ): PreparedRecoveryClaim {
        require(deviceDisplayName.isNotBlank() && deviceDisplayName.length <= 120)
        require(platform in SUPPORTED_PLATFORMS)
        val challenge = api.createRecoveryChallenge(metadata.endpoint, metadata.userId)
        if (challenge.expiresAtMillis <= nowMillis()) {
            throw SyncControlPlaneException.Protocol("Recovery challenge is already expired")
        }
        val replacement = recoveryKitManager.stageReplacement(
            endpoint = metadata.endpoint,
            instanceId = metadata.instanceId,
            userId = metadata.userId,
            createdAt = nowMillis(),
        )
        try {
            val device = generateAndInstallDevice(deviceDisplayName, platform, deviceId)
            val claims = mutableListOf<RecoveryWorkspaceClaimEnvelope>()
            for (workspace in challenge.workspaces.sortedBy(RecoveryWorkspaceChallenge::workspaceId)) {
                var activeWorkspaceKey: SecretMaterial? = null
                val replacementRecoveryEnvelopes = (
                    workspace.retainedKeyEnvelopes + RecoveryEpochKeyEnvelope(
                        workspace.keyEpoch,
                        workspace.keyCommitmentBase64Url,
                        workspace.recoveryWrappedKeyBase64Url,
                    )
                ).sortedBy(RecoveryEpochKeyEnvelope::keyEpoch).map { retained ->
                    val recoveredKey = recoveryKitManager.unwrapRecoveryWorkspaceKey(
                        retained.recoveryWrappedKeyBase64Url,
                        retained.keyCommitmentBase64Url,
                    )
                    installRecoveredWorkspaceKey(
                        workspaceId = workspace.workspaceId,
                        keyEpoch = retained.keyEpoch,
                        keyCommitment = retained.keyCommitmentBase64Url,
                        key = recoveredKey,
                    )
                    if (retained.keyEpoch == workspace.keyEpoch) activeWorkspaceKey = recoveredKey
                    RecoveryEpochKeyEnvelope(
                        keyEpoch = retained.keyEpoch,
                        keyCommitmentBase64Url = retained.keyCommitmentBase64Url,
                        recoveryWrappedKeyBase64Url = encodeBase64Url(
                            crypto.wrapWorkspaceKey(
                                recoveredKey,
                                BinaryData.copyOf(
                                    decodeExactRecovery(
                                        replacement.metadata.recoveryWrappingPublicKey,
                                        RECOVERY_PUBLIC_KEY_BYTES,
                                        "replacement recovery wrapping key",
                                    ),
                                ),
                            ).copyBytes(),
                        ),
                    )
                }
                val workspaceKey = activeWorkspaceKey
                    ?: throw SyncControlPlaneException.Protocol("Recovery challenge omitted the active epoch")
                val deviceWrappedKey = encodeBase64Url(
                    crypto.wrapWorkspaceKey(
                        workspaceKey,
                        BinaryData.copyOf(
                            decodeExactRecovery(
                                device.wrappingPublicKeyBase64Url,
                                RECOVERY_PUBLIC_KEY_BYTES,
                                "new device wrapping key",
                            ),
                        ),
                    ).copyBytes(),
                )
                val deviceEnvelopeSignature = signDeviceEnvelope(
                    workspace,
                    device.deviceId,
                    deviceWrappedKey,
                )
                claims += RecoveryWorkspaceClaimEnvelope(
                    workspaceId = workspace.workspaceId,
                    keyEpoch = workspace.keyEpoch,
                    keyCommitmentBase64Url = workspace.keyCommitmentBase64Url,
                    deviceWrappedKeyBase64Url = deviceWrappedKey,
                    deviceEnvelopeSignatureBase64Url = deviceEnvelopeSignature,
                    replacementRecoveryEnvelopes = replacementRecoveryEnvelopes,
                )
            }
            val replacementRecoveryTrustSignature = recoveryKitManager.signStagedRecoveryLineageManifest(
                recoveryLineageManifest(
                    instanceId = metadata.instanceId,
                    userId = metadata.userId,
                    challengeId = challenge.challengeId,
                    device = device,
                    previousRecoverySigningPublicKey = metadata.recoverySigningPublicKey,
                    replacement = replacement.metadata,
                ),
            )
            val canonicalAttestation = recoveryAttestation(
                instanceId = metadata.instanceId,
                userId = metadata.userId,
                challenge = challenge,
                device = device,
                previousRecoverySigningPublicKey = metadata.recoverySigningPublicKey,
                replacement = replacement.metadata,
                replacementRecoveryTrustSignature = replacementRecoveryTrustSignature,
                claims = claims,
            )
            // RecoveryKitManager always reads the active (old) signing key, never the staged one.
            val signature = recoveryKitManager.signRecoveryClaimManifest(canonicalAttestation)
            return PreparedRecoveryClaim(
                challenge = challenge,
                replacementKit = replacement,
                request = RecoveryClaimRequest(
                    instanceId = metadata.instanceId,
                    userId = metadata.userId,
                    challengeId = challenge.challengeId,
                    challenge = challenge.challenge,
                    device = device,
                    previousRecoverySigningPublicKeyBase64Url = metadata.recoverySigningPublicKey,
                    newRecoverySigningPublicKeyBase64Url = replacement.metadata.recoverySigningPublicKey,
                    newRecoveryWrappingPublicKeyBase64Url = replacement.metadata.recoveryWrappingPublicKey,
                    replacementRecoveryTrustSignatureBase64Url = replacementRecoveryTrustSignature,
                    workspaceEnvelopes = claims,
                    signatureBase64Url = signature,
                ),
            )
        } catch (error: Throwable) {
            runCatching { recoveryKitManager.discardStagedReplacement() }
            runCatching { deleteGeneratedDeviceSecrets() }
            throw error
        }
    }

    suspend fun submit(prepared: PreparedRecoveryClaim): CompletedRecoveryClaim {
        val request = prepared.request
        val receipt = api.claimRecovery(prepared.replacementKit.metadata.endpoint, request)
        val expectedWorkspaces = request.workspaceEnvelopes.map(RecoveryWorkspaceClaimEnvelope::workspaceId).toSet()
        if (receipt.rotationRequiredWorkspaceIds.toSet() != expectedWorkspaces) {
            // Do not activate the staged signer on an incomplete or tenant-confused receipt.
            throw SyncControlPlaneException.Protocol("Recovery receipt omitted a workspace that requires rotation")
        }
        val expectedEpochs = request.workspaceEnvelopes.associate {
            it.workspaceId to it.keyEpoch
        }
        if (receipt.workspaceBindings.map(RecoveryWorkspaceBinding::workspaceId).toSet() != expectedWorkspaces ||
            receipt.workspaceBindings.any { binding ->
                expectedEpochs[binding.workspaceId] != binding.activeKeyEpoch
            }
        ) {
            throw SyncControlPlaneException.Protocol("Recovery receipt workspace binding mismatch")
        }
        recoveryKitManager.activateStagedReplacement()
        return CompletedRecoveryClaim(
            receipt = receipt,
            replacementKit = prepared.replacementKit,
            recoveredWorkspaceEpochs = request.workspaceEnvelopes.associate {
                it.workspaceId to it.keyEpoch
            },
        )
    }

    suspend fun recover(
        metadata: RecoveryKitPublicMetadata,
        deviceDisplayName: String,
        platform: String,
        deviceId: String? = null,
    ): CompletedRecoveryClaim = submit(prepare(metadata, deviceDisplayName, platform, deviceId))

    private suspend fun generateAndInstallDevice(
        displayName: String,
        platform: String,
        requestedDeviceId: String?,
    ): RecoveryDeviceRegistration {
        val keys = listOf(
            SyncSecretKey.DeviceCredential,
            SyncSecretKey.DeviceSigningPrivateKey,
            SyncSecretKey.DeviceWrappingPrivateKey,
        )
        if (keys.any { secretStore.read(it) != SyncSecretReadResult.Missing }) {
            throw SyncControlPlaneException.PendingOperation(
                "Recovery requires a fresh device identity or explicit pending-claim reconciliation",
            )
        }
        SodiumSyncPrimitives.initialize()
        val signing = SodiumSyncPrimitives.generateEd25519KeyPair()
        val wrapping = SodiumSyncPrimitives.generateX25519KeyPair()
        val credentialBytes = SodiumSyncPrimitives.randomBytes(DEVICE_CREDENTIAL_BYTES)
        val credential = encodeBase64Url(credentialBytes)
        try {
            val deviceId = requestedDeviceId ?: newDeviceId()
            if (!deviceId.matches(RECOVERY_UUID_PATTERN)) {
                throw SyncControlPlaneException.Protocol("Recovered device id is not a canonical UUID")
            }
            installDeviceSecrets(
                SecretMaterial(credential.encodeToByteArray().asList()),
                SecretMaterial(signing.privateKey.asList()),
                SecretMaterial(wrapping.privateKey.asList()),
            )
            return RecoveryDeviceRegistration(
                deviceId = deviceId,
                displayName = displayName,
                platform = platform,
                signingPublicKeyBase64Url = encodeBase64Url(signing.publicKey),
                wrappingPublicKeyBase64Url = encodeBase64Url(wrapping.publicKey),
                deviceCredential = SecretMaterial(credential.encodeToByteArray().asList()),
            )
        } finally {
            SodiumSyncPrimitives.destroy(signing.privateKey)
            SodiumSyncPrimitives.destroy(wrapping.privateKey)
            SodiumSyncPrimitives.destroy(credentialBytes)
        }
    }

    private suspend fun installDeviceSecrets(
        credential: SecretMaterial,
        signingPrivateKey: SecretMaterial,
        wrappingPrivateKey: SecretMaterial,
    ) {
        try {
            secretStore.write(SyncSecretKey.DeviceCredential, credential)
            secretStore.write(SyncSecretKey.DeviceSigningPrivateKey, signingPrivateKey)
            secretStore.write(SyncSecretKey.DeviceWrappingPrivateKey, wrappingPrivateKey)
            secretStore.requireSecret(SyncSecretKey.DeviceCredential)
            secretStore.requireSecret(SyncSecretKey.DeviceSigningPrivateKey)
            secretStore.requireSecret(SyncSecretKey.DeviceWrappingPrivateKey)
        } catch (error: Throwable) {
            runCatching { deleteGeneratedDeviceSecrets() }
            throw SyncControlPlaneException.KeyMismatch("Recovered device secrets could not be installed", error)
        }
    }

    private suspend fun deleteGeneratedDeviceSecrets() {
        secretStore.delete(SyncSecretKey.DeviceCredential)
        secretStore.delete(SyncSecretKey.DeviceSigningPrivateKey)
        secretStore.delete(SyncSecretKey.DeviceWrappingPrivateKey)
    }

    private suspend fun installRecoveredWorkspaceKey(
        workspaceId: String,
        keyEpoch: Int,
        keyCommitment: String,
        key: SecretMaterial,
    ) {
        val secretKey = SyncSecretKey.WorkspaceEpochKey(workspaceId, keyEpoch)
        when (val existing = secretStore.read(secretKey)) {
            SyncSecretReadResult.Missing -> secretStore.write(secretKey, key)
            is SyncSecretReadResult.Available -> {
                val commitment = encodeBase64Url(crypto.keyCommitment(existing.material).copyBytes())
                if (commitment != keyCommitment) {
                    throw SyncControlPlaneException.KeyMismatch(
                        "A different recovered key is already installed for $workspaceId epoch $keyEpoch",
                    )
                }
            }
            is SyncSecretReadResult.Unavailable -> throw SyncControlPlaneException.KeyMismatch(
                "Recovered workspace key storage is unavailable: ${existing.diagnostic}",
            )
            is SyncSecretReadResult.Corrupt -> throw SyncControlPlaneException.KeyMismatch(
                "Recovered workspace key storage is corrupt: ${existing.diagnostic}",
            )
        }
        val installedCommitment = encodeBase64Url(crypto.keyCommitment(secretStore.requireSecret(secretKey)).copyBytes())
        if (installedCommitment != keyCommitment) {
            throw SyncControlPlaneException.KeyMismatch("Recovered workspace key failed protected-store read-back")
        }
    }

    private suspend fun signDeviceEnvelope(
        workspace: RecoveryWorkspaceChallenge,
        deviceId: String,
        wrappedKey: String,
    ): String {
        val manifest = JsonObject(
            mapOf(
                "workspaceId" to JsonPrimitive(workspace.workspaceId),
                "keyEpoch" to JsonPrimitive(workspace.keyEpoch),
                "keyCommitment" to JsonPrimitive(workspace.keyCommitmentBase64Url),
                "recipientDeviceId" to JsonPrimitive(deviceId),
                "wrappedKeyHash" to JsonPrimitive(hashUtf8Recovery(wrappedKey)),
            ),
        )
        val canonical = canonicalSyncJson(manifest)
        return encodeBase64Url(
            crypto.signDeviceMessage(
                BinaryData.copyOf(DEVICE_KEY_ENVELOPE_DOMAIN + canonical.encodeToByteArray()),
            ).copyBytes(),
        )
    }

    private suspend fun recoveryAttestation(
        instanceId: String,
        userId: String,
        challenge: RecoveryChallenge,
        device: RecoveryDeviceRegistration,
        previousRecoverySigningPublicKey: String,
        replacement: RecoveryKitPublicMetadata,
        replacementRecoveryTrustSignature: String,
        claims: List<RecoveryWorkspaceClaimEnvelope>,
    ): String {
        val challengeCommitment = hashDecodedSecret(challenge.challenge)
        val deviceCredentialCommitment = hashDecodedSecret(device.deviceCredential)
        val workspaceEnvelopes = claims.sortedBy(RecoveryWorkspaceClaimEnvelope::workspaceId).map { claim ->
            JsonObject(
                mapOf(
                    "workspaceId" to JsonPrimitive(claim.workspaceId),
                    "keyEpoch" to JsonPrimitive(claim.keyEpoch),
                    "keyCommitment" to JsonPrimitive(claim.keyCommitmentBase64Url),
                    "deviceWrappedKeyHash" to JsonPrimitive(hashUtf8Recovery(claim.deviceWrappedKeyBase64Url)),
                    "deviceEnvelopeSignature" to JsonPrimitive(claim.deviceEnvelopeSignatureBase64Url),
                    "recoveryKeyEnvelopes" to JsonArray(
                        claim.replacementRecoveryEnvelopes.sortedBy(RecoveryEpochKeyEnvelope::keyEpoch).map { envelope ->
                            JsonObject(
                                mapOf(
                                    "keyEpoch" to JsonPrimitive(envelope.keyEpoch),
                                    "keyCommitment" to JsonPrimitive(envelope.keyCommitmentBase64Url),
                                    "recoveryWrappedKeyHash" to JsonPrimitive(
                                        hashUtf8Recovery(envelope.recoveryWrappedKeyBase64Url),
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            )
        }
        return canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(instanceId),
                    "userId" to JsonPrimitive(userId),
                    "challengeId" to JsonPrimitive(challenge.challengeId),
                    "challengeCommitment" to JsonPrimitive(challengeCommitment),
                    "device" to JsonObject(
                        mapOf(
                            "deviceId" to JsonPrimitive(device.deviceId),
                            "displayName" to JsonPrimitive(device.displayName),
                            "platform" to JsonPrimitive(device.platform),
                            "signingPublicKey" to JsonPrimitive(device.signingPublicKeyBase64Url),
                            "wrappingPublicKey" to JsonPrimitive(device.wrappingPublicKeyBase64Url),
                            "deviceTokenHash" to JsonPrimitive(deviceCredentialCommitment),
                        ),
                    ),
                    "previousRecoverySigningPublicKey" to JsonPrimitive(previousRecoverySigningPublicKey),
                    "newRecoverySigningPublicKey" to JsonPrimitive(replacement.recoverySigningPublicKey),
                    "newRecoveryWrappingPublicKey" to JsonPrimitive(replacement.recoveryWrappingPublicKey),
                    "replacementRecoveryTrustSignature" to JsonPrimitive(replacementRecoveryTrustSignature),
                    "workspaceEnvelopes" to JsonArray(workspaceEnvelopes),
                ),
            ),
        )
    }

    private fun recoveryLineageManifest(
        instanceId: String,
        userId: String,
        challengeId: String,
        device: RecoveryDeviceRegistration,
        previousRecoverySigningPublicKey: String,
        replacement: RecoveryKitPublicMetadata,
    ): String = canonicalSyncJson(
        JsonObject(
            mapOf(
                "instanceId" to JsonPrimitive(instanceId),
                "userId" to JsonPrimitive(userId),
                "challengeId" to JsonPrimitive(challengeId),
                "deviceId" to JsonPrimitive(device.deviceId),
                "deviceSigningPublicKey" to JsonPrimitive(device.signingPublicKeyBase64Url),
                "deviceWrappingPublicKey" to JsonPrimitive(device.wrappingPublicKeyBase64Url),
                "previousRecoverySigningPublicKey" to JsonPrimitive(previousRecoverySigningPublicKey),
                "newRecoverySigningPublicKey" to JsonPrimitive(replacement.recoverySigningPublicKey),
                "newRecoveryWrappingPublicKey" to JsonPrimitive(replacement.recoveryWrappingPublicKey),
            ),
        ),
    )

    private suspend fun hashDecodedSecret(material: SecretMaterial): String {
        SodiumSyncPrimitives.initialize()
        var text: String? = null
        material.useBytes { text = it.decodeToString(throwOnInvalidSequence = true) }
        val decoded = try {
            decodeBase64Url(requireNotNull(text))
        } catch (error: Throwable) {
            throw SyncControlPlaneException.Protocol("Recovery secret is not canonical base64url", error)
        }
        return encodeBase64Url(SodiumSyncPrimitives.sha256(decoded))
    }
}

private suspend fun hashUtf8Recovery(value: String): String {
    SodiumSyncPrimitives.initialize()
    return encodeBase64Url(SodiumSyncPrimitives.sha256(value.encodeToByteArray()))
}

private fun decodeExactRecovery(value: String, size: Int, label: String): ByteArray {
    val decoded = try {
        decodeBase64Url(value)
    } catch (error: Throwable) {
        throw SyncControlPlaneException.Protocol("$label is not canonical base64url", error)
    }
    if (decoded.size != size) throw SyncControlPlaneException.Protocol("$label has an invalid size")
    return decoded
}

private val DEVICE_KEY_ENVELOPE_DOMAIN = "shinsou:workspace-key-envelope-attestation:v1\u0000".encodeToByteArray()
private val RECOVERY_UUID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
private val SUPPORTED_PLATFORMS = setOf("android", "ios", "macos", "windows", "other")
private const val RECOVERY_PUBLIC_KEY_BYTES = 32
private const val DEVICE_CREDENTIAL_BYTES = 32
