package dev.shinsou.kmp.sync.provisioning

import dev.shinsou.kmp.sync.crypto.DeterministicCbor
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.crypto.SodiumWrappedKey
import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.trust.GeneratedRecoveryKit
import dev.shinsou.kmp.sync.trust.RecoveryKitManager
import dev.shinsou.kmp.sync.trust.RecoveryKitPublicMetadata
import dev.shinsou.kmp.sync.trust.canonicalSyncJson
import dev.shinsou.kmp.sync.v2.BinaryData
import dev.shinsou.kmp.sync.v2.EphemeralSyncPayload
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncCrypto
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretReadResult
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.requireSecret
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class PreparedInitialWorkspace(
    val claim: InitialWorkspaceClaim,
    val recoveryKit: EphemeralSyncPayload,
)

/** Generates, signs, wraps, verifies, and installs provisioning key material. */
class SyncProvisioningCrypto(
    private val secretStore: SyncSecretStore,
    private val crypto: SyncCrypto,
    private val recoveryKitManager: RecoveryKitManager,
    private val nowMillis: () -> Long,
    private val json: Json = Json.Default,
) {
    suspend fun generateUuid(): String {
        SodiumSyncPrimitives.initialize()
        val bytes = SodiumSyncPrimitives.randomBytes(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    suspend fun generateOneTimeSecret(): EphemeralSyncPayload {
        SodiumSyncPrimitives.initialize()
        val bytes = SodiumSyncPrimitives.randomBytes(32)
        return try {
            EphemeralSyncPayload(encodeBase64Url(bytes))
        } finally {
            SodiumSyncPrimitives.destroy(bytes)
        }
    }

    suspend fun importRecoveryKit(encoded: String): RecoveryKitPublicMetadata =
        recoveryKitManager.importAndInstall(SecretMaterial(encoded.encodeToByteArray().asList()))

    suspend fun prepareInitialWorkspace(
        session: SyncSession,
        userDisplayName: String,
        oneTimeSecret: EphemeralSyncPayload,
    ): PreparedInitialWorkspace {
        SodiumSyncPrimitives.initialize()
        val device = ensureDeviceIdentity(session.deviceId, session.deviceDisplayName, session.platform)
        val recovery = ensureRecoveryKit(session)
        val workspaceKey = ensureWorkspaceKey(session.workspaceId, 1)
        val keyCommitment = encodeBase64Url(crypto.keyCommitment(workspaceKey).copyBytes())
        val deviceWrappedKey = encodeBase64Url(
            crypto.wrapWorkspaceKey(workspaceKey, BinaryData.copyOf(decodePublicKey(device.wrappingPublicKey))).copyBytes(),
        )
        val recoveryWrappedKey = encodeBase64Url(
            crypto.wrapWorkspaceKey(
                workspaceKey,
                BinaryData.copyOf(decodePublicKey(recovery.metadata.recoveryWrappingPublicKey)),
            ).copyBytes(),
        )
        val envelopeManifest = workspaceEnvelopeManifest(
            workspaceId = session.workspaceId,
            keyEpoch = 1,
            recipientDeviceId = session.deviceId,
            keyCommitment = keyCommitment,
            wrappedKey = deviceWrappedKey,
        )
        val deviceEnvelopeSignature = signDomain("workspace-key-envelope", envelopeManifest)
        val tokenCommitment = device.deviceToken.use(::publicTokenCommitment)
        val recoveryDeviceTrustManifest = initialDeviceRecoveryTrustManifest(
            instanceId = session.instanceId,
            userId = session.userId,
            workspaceId = session.workspaceId,
            deviceId = session.deviceId,
            signingPublicKey = device.signingPublicKey,
            wrappingPublicKey = device.wrappingPublicKey,
            recoverySigningPublicKey = recovery.metadata.recoverySigningPublicKey,
            recoveryWrappingPublicKey = recovery.metadata.recoveryWrappingPublicKey,
        )
        val recoveryDeviceTrustSignature = recoveryKitManager.signInitialDeviceTrustManifest(
            recoveryDeviceTrustManifest,
        )
        val claimManifest = canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(session.instanceId),
                    "userId" to JsonPrimitive(session.userId),
                    "workspaceId" to JsonPrimitive(session.workspaceId),
                    "deviceId" to JsonPrimitive(session.deviceId),
                    "signingPublicKey" to JsonPrimitive(device.signingPublicKey),
                    "wrappingPublicKey" to JsonPrimitive(device.wrappingPublicKey),
                    "deviceTokenHash" to JsonPrimitive(tokenCommitment),
                    "keyEpoch" to JsonPrimitive(1),
                    "keyCommitment" to JsonPrimitive(keyCommitment),
                    "deviceWrappedKeyHash" to JsonPrimitive(sha256Utf8(deviceWrappedKey)),
                    "recoverySigningPublicKey" to JsonPrimitive(recovery.metadata.recoverySigningPublicKey),
                    "recoveryWrappingPublicKey" to JsonPrimitive(recovery.metadata.recoveryWrappingPublicKey),
                    "recoveryWrappedKeyHash" to JsonPrimitive(sha256Utf8(recoveryWrappedKey)),
                    "recoveryDeviceTrustSignature" to JsonPrimitive(recoveryDeviceTrustSignature),
                ),
            ),
        )
        return PreparedInitialWorkspace(
            claim = InitialWorkspaceClaim(
                bootstrapOrInviteSecret = oneTimeSecret,
                userId = session.userId,
                workspaceId = session.workspaceId,
                displayName = userDisplayName,
                device = ProvisioningDeviceRegistration(
                    session.deviceId,
                    session.deviceDisplayName,
                    session.platform,
                    device.signingPublicKey,
                    device.wrappingPublicKey,
                    device.deviceToken,
                ),
                initialKeys = ProvisioningInitialKeys(
                    keyCommitment,
                    deviceWrappedKey,
                    deviceEnvelopeSignature,
                    recovery.metadata.recoverySigningPublicKey,
                    recovery.metadata.recoveryWrappingPublicKey,
                    recoveryWrappedKey,
                    recoveryDeviceTrustSignature,
                ),
                claimSignature = signDomain("initial-workspace-claim", claimManifest),
            ),
            recoveryKit = EphemeralSyncPayload(recovery.exportedKit.secretUtf8()),
        )
    }

    private fun initialDeviceRecoveryTrustManifest(
        instanceId: String,
        userId: String,
        workspaceId: String,
        deviceId: String,
        signingPublicKey: String,
        wrappingPublicKey: String,
        recoverySigningPublicKey: String,
        recoveryWrappingPublicKey: String,
    ): String = canonicalSyncJson(
        JsonObject(
            mapOf(
                "instanceId" to JsonPrimitive(instanceId),
                "userId" to JsonPrimitive(userId),
                "workspaceId" to JsonPrimitive(workspaceId),
                "deviceId" to JsonPrimitive(deviceId),
                "signingPublicKey" to JsonPrimitive(signingPublicKey),
                "wrappingPublicKey" to JsonPrimitive(wrappingPublicKey),
                "recoverySigningPublicKey" to JsonPrimitive(recoverySigningPublicKey),
                "recoveryWrappingPublicKey" to JsonPrimitive(recoveryWrappingPublicKey),
            ),
        ),
    )

    suspend fun preparePairingCandidate(
        deviceId: String,
        deviceDisplayName: String,
        platform: String,
        pairingId: String,
        secret: EphemeralSyncPayload,
    ): ProvisioningPairingCandidateInput {
        val device = ensureDeviceIdentity(deviceId, deviceDisplayName, platform)
        return ProvisioningPairingCandidateInput(pairingId, secret, device)
    }

    suspend fun confirmationCode(view: ProvisioningPairingView): String {
        SodiumSyncPrimitives.initialize()
        val transcript = requireTranscript(view)
        val hash = SodiumSyncPrimitives.sha256(canonicalSyncJson(transcriptJson(transcript)).encodeToByteArray())
        val number = ((hash[0].toLong() and 0xff) shl 24) or
            ((hash[1].toLong() and 0xff) shl 16) or
            ((hash[2].toLong() and 0xff) shl 8) or (hash[3].toLong() and 0xff)
        val code = (number % 1_000_000).toString().padStart(6, '0')
        if (view.confirmationCode != null && view.confirmationCode != code) {
            throw SyncProvisioningException("pairing_transcript_code_mismatch")
        }
        return code
    }

    suspend fun preparePairApproval(
        session: SyncSession,
        view: ProvisioningPairingView,
    ): ProvisioningPairApproval {
        val candidate = view.candidate ?: throw SyncProvisioningException("pairing_candidate_missing")
        val requirements = view.keyRequirements ?: throw SyncProvisioningException("pairing_key_requirements_missing")
        confirmationCode(view)
        val ownIdentity = ensureDeviceIdentity(session.deviceId, session.deviceDisplayName, session.platform)
        if (view.sponsorDeviceId != session.deviceId ||
            view.sponsorSigningPublicKey != ownIdentity.signingPublicKey ||
            view.sponsorWrappingPublicKey != ownIdentity.wrappingPublicKey
        ) {
            throw SyncProvisioningException("pairing_sponsor_key_mismatch")
        }
        val epochs = requirements.requiredKeyEpochs.distinct().sorted()
        if (epochs.isEmpty() || epochs != requirements.requiredKeyEpochs || requirements.activeKeyEpoch !in epochs) {
            throw SyncProvisioningException("pairing_invalid_key_requirements")
        }
        val recipientKey = BinaryData.copyOf(decodePublicKey(candidate.wrappingPublicKey))
        val envelopes = epochs.map { epoch ->
            val material = secretStore.requireSecret(SyncSecretKey.WorkspaceEpochKey(session.workspaceId, epoch))
            val commitment = encodeBase64Url(crypto.keyCommitment(material).copyBytes())
            val wrapped = encodeBase64Url(crypto.wrapWorkspaceKey(material, recipientKey).copyBytes())
            ProvisioningKeyEnvelope(
                keyEpoch = epoch,
                keyCommitment = commitment,
                wrappedKey = wrapped,
                wrappedByDeviceId = session.deviceId,
                signature = signDomain(
                    "workspace-key-envelope",
                    workspaceEnvelopeManifest(session.workspaceId, epoch, candidate.deviceId, commitment, wrapped),
                ),
            )
        }
        val manifest = pairApprovalManifest(view, envelopes)
        return ProvisioningPairApproval(
            approved = true,
            envelopes = envelopes,
            approvalSignature = signDomain("pairing-approval", manifest),
        )
    }

    suspend fun installPairActivation(
        endpoint: String,
        instanceId: String,
        deviceDisplayName: String,
        platform: String,
        view: ProvisioningPairingView,
    ): SyncSession {
        val activation = view.activation ?: throw SyncProvisioningException("pairing_activation_missing")
        val candidate = view.candidate ?: throw SyncProvisioningException("pairing_candidate_missing")
        val device = ensureDeviceIdentity(activation.deviceId, deviceDisplayName, platform)
        if (candidate.deviceId != device.deviceId || candidate.signingPublicKey != device.signingPublicKey ||
            candidate.wrappingPublicKey != device.wrappingPublicKey ||
            candidate.tokenCommitment != device.deviceToken.use(::publicTokenCommitment)
        ) throw SyncProvisioningException("pairing_candidate_identity_mismatch")
        confirmationCode(view)
        verifyApprovalEvidence(view, activation)

        val oldValues = activation.keyEnvelopes.associate { envelope ->
            val key = SyncSecretKey.WorkspaceEpochKey(activation.workspaceId, envelope.keyEpoch)
            key to secretStore.read(key)
        }
        try {
            activation.keyEnvelopes.sortedBy { it.keyEpoch }.forEach { envelope ->
                val material = unwrapAndVerifyEnvelope(
                    workspaceId = activation.workspaceId,
                    recipientDeviceId = activation.deviceId,
                    envelope = envelope,
                    expectedSponsorId = view.sponsorDeviceId,
                    expectedSponsorPublicKey = view.sponsorSigningPublicKey,
                )
                secretStore.write(
                    SyncSecretKey.WorkspaceEpochKey(activation.workspaceId, envelope.keyEpoch),
                    material,
                )
            }
        } catch (failure: Throwable) {
            oldValues.forEach { (key, value) -> restore(key, value) }
            throw failure
        }
        if (activation.activeKeyEpoch !in activation.keyEnvelopes.map { it.keyEpoch }) {
            throw SyncProvisioningException("pairing_active_epoch_missing")
        }
        return SyncSession(
            endpoint = endpoint,
            instanceId = instanceId,
            userId = activation.userId,
            workspaceId = activation.workspaceId,
            deviceId = activation.deviceId,
            deviceDisplayName = deviceDisplayName,
            platform = platform,
            status = dev.shinsou.kmp.sync.v2.SyncSessionStatus.LINKING,
            deviceAuthEpoch = activation.deviceAuthEpoch,
            membershipAuthEpoch = activation.membershipAuthEpoch,
            activeKeyEpoch = activation.activeKeyEpoch,
        )
    }

    suspend fun exportRecoveryKit(session: SyncSession): EphemeralSyncPayload {
        val recovery = existingRecoveryMetadata(session)
        return EphemeralSyncPayload(recoveryKitManager.exportInstalled(recovery).secretUtf8())
    }

    suspend fun initialTrustContext(session: SyncSession): ProvisioningTrustContext.InitialSelfAnchor {
        val device = ensureDeviceIdentity(session.deviceId, session.deviceDisplayName, session.platform)
        val recovery = existingRecoveryMetadata(session)
        return ProvisioningTrustContext.InitialSelfAnchor(
            deviceId = device.deviceId,
            signingPublicKey = device.signingPublicKey,
            wrappingPublicKey = device.wrappingPublicKey,
            recoverySigningPublicKey = recovery.recoverySigningPublicKey,
        )
    }

    private suspend fun ensureDeviceIdentity(
        expectedDeviceId: String,
        displayName: String,
        platform: String,
    ): ProvisioningDeviceRegistration {
        SodiumSyncPrimitives.initialize()
        val signingResult = secretStore.read(SyncSecretKey.DeviceSigningPrivateKey)
        val wrappingResult = secretStore.read(SyncSecretKey.DeviceWrappingPrivateKey)
        val tokenResult = secretStore.read(SyncSecretKey.DeviceCredential)
        val allMissing = listOf(signingResult, wrappingResult, tokenResult).all { it is SyncSecretReadResult.Missing }
        if (allMissing) {
            val signing = SodiumSyncPrimitives.generateEd25519KeyPair()
            val wrapping = SodiumSyncPrimitives.generateX25519KeyPair()
            val tokenBytes = SodiumSyncPrimitives.randomBytes(32)
            val token = encodeBase64Url(tokenBytes)
            try {
                secretStore.write(SyncSecretKey.DeviceSigningPrivateKey, SecretMaterial(signing.privateKey.asList()))
                secretStore.write(SyncSecretKey.DeviceWrappingPrivateKey, SecretMaterial(wrapping.privateKey.asList()))
                secretStore.write(SyncSecretKey.DeviceCredential, SecretMaterial(token.encodeToByteArray().asList()))
            } catch (failure: Throwable) {
                runCatching { secretStore.delete(SyncSecretKey.DeviceSigningPrivateKey) }
                runCatching { secretStore.delete(SyncSecretKey.DeviceWrappingPrivateKey) }
                runCatching { secretStore.delete(SyncSecretKey.DeviceCredential) }
                throw SyncProvisioningException("device_secret_install_failed", failure)
            } finally {
                SodiumSyncPrimitives.destroy(signing.privateKey)
                SodiumSyncPrimitives.destroy(wrapping.privateKey)
                SodiumSyncPrimitives.destroy(tokenBytes)
            }
        } else if (listOf(signingResult, wrappingResult, tokenResult).any { it !is SyncSecretReadResult.Available }) {
            throw SyncProvisioningException("device_secrets_incomplete")
        }
        val signing = secretStore.requireSecret(SyncSecretKey.DeviceSigningPrivateKey).copySecret()
        val wrapping = secretStore.requireSecret(SyncSecretKey.DeviceWrappingPrivateKey).copySecret()
        val token = secretStore.requireSecret(SyncSecretKey.DeviceCredential).secretUtf8()
        try {
            if (signing.size != 64 || wrapping.size != 32) throw SyncProvisioningException("device_secrets_corrupt")
            val signingPublic = signing.copyOfRange(32, 64)
            val proof = SodiumSyncPrimitives.signEd25519(DEVICE_KEY_PROOF, signing)
            if (!SodiumSyncPrimitives.verifyEd25519(DEVICE_KEY_PROOF, proof, signingPublic)) {
                throw SyncProvisioningException("device_signing_key_mismatch")
            }
            val wrappingPublic = SodiumSyncPrimitives.x25519PublicKey(wrapping)
            decodeBase64Url(token).also {
                if (it.size != 32) throw SyncProvisioningException("device_token_corrupt")
                SodiumSyncPrimitives.destroy(it)
            }
            return ProvisioningDeviceRegistration(
                expectedDeviceId,
                displayName,
                platform,
                encodeBase64Url(signingPublic),
                encodeBase64Url(wrappingPublic),
                EphemeralSyncPayload(token),
            )
        } finally {
            SodiumSyncPrimitives.destroy(signing)
            SodiumSyncPrimitives.destroy(wrapping)
        }
    }

    private suspend fun ensureRecoveryKit(session: SyncSession): GeneratedRecoveryKit {
        val signing = secretStore.read(SyncSecretKey.RecoverySigningPrivateKey)
        val wrapping = secretStore.read(SyncSecretKey.RecoveryWrappingPrivateKey)
        if (signing is SyncSecretReadResult.Missing && wrapping is SyncSecretReadResult.Missing) {
            return recoveryKitManager.generateAndInstall(
                session.endpoint,
                session.instanceId,
                session.userId,
                nowMillis(),
            )
        }
        if (signing !is SyncSecretReadResult.Available || wrapping !is SyncSecretReadResult.Available) {
            throw SyncProvisioningException("recovery_secrets_incomplete")
        }
        val metadata = existingRecoveryMetadata(session)
        return GeneratedRecoveryKit(metadata, recoveryKitManager.exportInstalled(metadata))
    }

    private suspend fun existingRecoveryMetadata(session: SyncSession): RecoveryKitPublicMetadata {
        SodiumSyncPrimitives.initialize()
        val signing = secretStore.requireSecret(SyncSecretKey.RecoverySigningPrivateKey).copySecret()
        val wrapping = secretStore.requireSecret(SyncSecretKey.RecoveryWrappingPrivateKey).copySecret()
        try {
            if (signing.size != 64 || wrapping.size != 32) throw SyncProvisioningException("recovery_secrets_corrupt")
            return RecoveryKitPublicMetadata(
                session.endpoint,
                session.instanceId,
                session.userId,
                encodeBase64Url(signing.copyOfRange(32, 64)),
                encodeBase64Url(SodiumSyncPrimitives.x25519PublicKey(wrapping)),
                nowMillis(),
            )
        } finally {
            SodiumSyncPrimitives.destroy(signing)
            SodiumSyncPrimitives.destroy(wrapping)
        }
    }

    private suspend fun ensureWorkspaceKey(workspaceId: String, epoch: Int): SecretMaterial {
        val key = SyncSecretKey.WorkspaceEpochKey(workspaceId, epoch)
        return when (val result = secretStore.read(key)) {
            is SyncSecretReadResult.Available -> result.material
            SyncSecretReadResult.Missing -> crypto.generateWorkspaceEpochKey().also { secretStore.write(key, it) }
            is SyncSecretReadResult.Unavailable -> throw SyncProvisioningException("workspace_key_store_unavailable")
            is SyncSecretReadResult.Corrupt -> throw SyncProvisioningException("workspace_key_corrupt")
        }
    }

    private fun requireTranscript(view: ProvisioningPairingView): ProvisioningPairingTranscript {
        val candidate = view.candidate ?: throw SyncProvisioningException("pairing_candidate_missing")
        return ProvisioningPairingTranscript(
            view.pairingId,
            view.workspaceId,
            view.sponsorDeviceId,
            view.sponsorSigningPublicKey,
            view.sponsorWrappingPublicKey,
            view.transcriptNonce,
            candidate.deviceId,
            candidate.displayName,
            candidate.platform,
            candidate.signingPublicKey,
            candidate.wrappingPublicKey,
            candidate.tokenCommitment,
            view.expiresAtMillis,
        )
    }

    private fun transcriptJson(transcript: ProvisioningPairingTranscript): JsonObject = JsonObject(
        mapOf(
            "pairingId" to JsonPrimitive(transcript.pairingId),
            "workspaceId" to JsonPrimitive(transcript.workspaceId),
            "sponsorDeviceId" to JsonPrimitive(transcript.sponsorDeviceId),
            "sponsorSigningPublicKey" to JsonPrimitive(transcript.sponsorSigningPublicKey),
            "sponsorWrappingPublicKey" to JsonPrimitive(transcript.sponsorWrappingPublicKey),
            "transcriptNonce" to JsonPrimitive(transcript.transcriptNonce),
            "candidateDeviceId" to JsonPrimitive(transcript.candidateDeviceId),
            "candidateDisplayName" to JsonPrimitive(transcript.candidateDisplayName),
            "candidatePlatform" to JsonPrimitive(transcript.candidatePlatform),
            "candidateSigningPublicKey" to JsonPrimitive(transcript.candidateSigningPublicKey),
            "candidateWrappingPublicKey" to JsonPrimitive(transcript.candidateWrappingPublicKey),
            "candidateTokenHash" to JsonPrimitive(transcript.candidateTokenHash),
            "expiresAt" to JsonPrimitive(transcript.expiresAtMillis),
        ),
    )

    private fun pairApprovalManifest(
        view: ProvisioningPairingView,
        envelopes: List<ProvisioningKeyEnvelope>,
    ): String {
        val candidate = view.candidate ?: throw SyncProvisioningException("pairing_candidate_missing")
        val bindings = envelopes.sortedBy { it.keyEpoch }.map { envelope ->
            JsonObject(
                mapOf(
                    "keyEpoch" to JsonPrimitive(envelope.keyEpoch),
                    "keyCommitment" to JsonPrimitive(envelope.keyCommitment),
                    "wrappedKeyHash" to JsonPrimitive(sha256Utf8(envelope.wrappedKey)),
                    "envelopeSignature" to JsonPrimitive(envelope.signature),
                ),
            )
        }
        return canonicalSyncJson(
            JsonObject(
                mapOf(
                    "transcript" to transcriptJson(requireTranscript(view)),
                    "candidateTokenHash" to JsonPrimitive(candidate.tokenCommitment),
                    "envelopes" to JsonArray(bindings),
                    "expiresAt" to JsonPrimitive(view.expiresAtMillis),
                ),
            ),
        )
    }

    private suspend fun verifyApprovalEvidence(
        view: ProvisioningPairingView,
        activation: ProvisioningPairActivation,
    ) {
        val evidence = activation.approval
        if (evidence.attestorDeviceId != view.sponsorDeviceId ||
            evidence.attestorPublicKey != view.sponsorSigningPublicKey ||
            evidence.signatureDomain != "pairing-approval" ||
            pairApprovalManifest(view, activation.keyEnvelopes) != evidence.manifestJson
        ) throw SyncProvisioningException("pairing_approval_evidence_mismatch")
        val parsed = runCatching { json.parseToJsonElement(evidence.manifestJson).jsonObject }
            .getOrElse { throw SyncProvisioningException("pairing_approval_manifest_malformed") }
        if (canonicalSyncJson(parsed) != evidence.manifestJson) {
            throw SyncProvisioningException("pairing_approval_manifest_noncanonical")
        }
        val valid = crypto.verifyDeviceSignature(
            BinaryData.copyOf(domainMessage("pairing-approval", evidence.manifestJson)),
            BinaryData.copyOf(decodeSignature(evidence.signature)),
            BinaryData.copyOf(decodePublicKey(evidence.attestorPublicKey)),
        )
        if (!valid) throw SyncProvisioningException("pairing_approval_signature_invalid")
    }

    private suspend fun unwrapAndVerifyEnvelope(
        workspaceId: String,
        recipientDeviceId: String,
        envelope: ProvisioningKeyEnvelope,
        expectedSponsorId: String,
        expectedSponsorPublicKey: String,
    ): SecretMaterial {
        if (envelope.wrappedByDeviceId != expectedSponsorId) {
            throw SyncProvisioningException("pairing_envelope_sponsor_mismatch")
        }
        val envelopeManifest = workspaceEnvelopeManifest(
            workspaceId,
            envelope.keyEpoch,
            recipientDeviceId,
            envelope.keyCommitment,
            envelope.wrappedKey,
        )
        val validSignature = crypto.verifyDeviceSignature(
            BinaryData.copyOf(domainMessage("workspace-key-envelope", envelopeManifest)),
            BinaryData.copyOf(decodeSignature(envelope.signature)),
            BinaryData.copyOf(decodePublicKey(expectedSponsorPublicKey)),
        )
        if (!validSignature) throw SyncProvisioningException("pairing_envelope_signature_invalid")
        val wrapped = decodeWrappedKey(decodeBase64Url(envelope.wrappedKey))
        val privateKey = secretStore.requireSecret(SyncSecretKey.DeviceWrappingPrivateKey).copySecret()
        val publicKey = SodiumSyncPrimitives.x25519PublicKey(privateKey)
        val plaintext = try {
            SodiumSyncPrimitives.unwrapKey(wrapped, privateKey, publicKey, WORKSPACE_KEY_WRAP_CONTEXT)
        } catch (failure: Throwable) {
            throw SyncProvisioningException("pairing_envelope_authentication_failed", failure)
        } finally {
            SodiumSyncPrimitives.destroy(privateKey)
            SodiumSyncPrimitives.destroy(publicKey)
        }
        try {
            if (plaintext.size != 32 || publicCommitment(plaintext) != envelope.keyCommitment) {
                throw SyncProvisioningException("pairing_key_commitment_mismatch")
            }
            return SecretMaterial(plaintext.asList())
        } finally {
            SodiumSyncPrimitives.destroy(plaintext)
        }
    }

    private suspend fun restore(key: SyncSecretKey, result: SyncSecretReadResult) {
        when (result) {
            is SyncSecretReadResult.Available -> secretStore.write(key, result.material)
            SyncSecretReadResult.Missing -> secretStore.delete(key)
            is SyncSecretReadResult.Unavailable -> throw SyncProvisioningException("workspace_key_rollback_unavailable")
            is SyncSecretReadResult.Corrupt -> throw SyncProvisioningException("workspace_key_rollback_corrupt")
        }
    }

    private suspend fun signDomain(domain: String, canonicalManifest: String): String = encodeBase64Url(
        crypto.signDeviceMessage(BinaryData.copyOf(domainMessage(domain, canonicalManifest))).copyBytes(),
    )

    private fun domainMessage(domain: String, canonicalManifest: String): ByteArray =
        "shinsou:$domain:v1\u0000".encodeToByteArray() + canonicalManifest.encodeToByteArray()

    private fun publicTokenCommitment(token: String): String {
        val bytes = decodeBase64Url(token)
        if (bytes.size < 24) throw SyncProvisioningException("device_token_too_short")
        return try {
            encodeBase64Url(SodiumSyncPrimitives.sha256(bytes))
        } finally {
            SodiumSyncPrimitives.destroy(bytes)
        }
    }

    private fun publicCommitment(key: ByteArray): String =
        encodeBase64Url(SodiumSyncPrimitives.sha256(WORKSPACE_KEY_COMMITMENT_DOMAIN + key))

    private fun workspaceEnvelopeManifest(
        workspaceId: String,
        keyEpoch: Int,
        recipientDeviceId: String,
        keyCommitment: String,
        wrappedKey: String,
    ): String = canonicalSyncJson(
        JsonObject(
            mapOf(
                "workspaceId" to JsonPrimitive(workspaceId),
                "keyEpoch" to JsonPrimitive(keyEpoch),
                "recipientDeviceId" to JsonPrimitive(recipientDeviceId),
                "keyCommitment" to JsonPrimitive(keyCommitment),
                "wrappedKeyHash" to JsonPrimitive(sha256Utf8(wrappedKey)),
            ),
        ),
    )

    private fun sha256Utf8(value: String): String =
        encodeBase64Url(SodiumSyncPrimitives.sha256(value.encodeToByteArray()))

    private fun decodePublicKey(value: String): ByteArray = decodeBase64Url(value).also {
        if (it.size != 32) throw SyncProvisioningException("invalid_public_key")
    }

    private fun decodeSignature(value: String): ByteArray = decodeBase64Url(value).also {
        if (it.size != 64) throw SyncProvisioningException("invalid_signature")
    }
}

private fun SecretMaterial.copySecret(): ByteArray {
    var copy: ByteArray? = null
    useBytes { copy = it.copyOf() }
    return requireNotNull(copy)
}

private fun SecretMaterial.secretUtf8(): String {
    var result: String? = null
    useBytes { result = it.decodeToString(throwOnInvalidSequence = true) }
    return requireNotNull(result)
}

private fun decodeWrappedKey(bytes: ByteArray): SodiumWrappedKey {
    val objectValue = runCatching { DeterministicCbor.decode(bytes) as? JsonObject }
        .getOrNull() ?: throw SyncProvisioningException("pairing_envelope_malformed")
    if (objectValue.keys != WRAPPED_KEY_FIELDS ||
        (objectValue["cipherSuite"] as? JsonPrimitive)?.content != "X25519_HKDF_SHA256_CHACHA20_POLY1305"
    ) throw SyncProvisioningException("pairing_envelope_malformed")
    fun field(name: String): ByteArray {
        val value = (objectValue[name] as? JsonPrimitive)?.content
            ?: throw SyncProvisioningException("pairing_envelope_malformed")
        return runCatching { decodeBase64Url(value) }
            .getOrElse { throw SyncProvisioningException("pairing_envelope_malformed") }
    }
    return SodiumWrappedKey(field("ephemeralPublicKey"), field("nonce"), field("ciphertext")).also {
        if (it.ephemeralPublicKey.size != 32 || it.nonce.size != 12 || it.ciphertext.size != 48) {
            throw SyncProvisioningException("pairing_envelope_malformed")
        }
    }
}

private val DEVICE_KEY_PROOF = "shinsou:device-key-proof:v1\u0000".encodeToByteArray()
private val WORKSPACE_KEY_WRAP_CONTEXT = "shinsou:workspace-key-envelope:v1".encodeToByteArray()
private val WORKSPACE_KEY_COMMITMENT_DOMAIN = "shinsou:workspace-key-commitment:v1\u0000".encodeToByteArray()
private val WRAPPED_KEY_FIELDS = setOf("cipherSuite", "ephemeralPublicKey", "nonce", "ciphertext")
