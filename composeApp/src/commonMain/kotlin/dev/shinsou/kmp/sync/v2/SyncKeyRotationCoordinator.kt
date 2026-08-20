package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.sync.crypto.DeterministicCbor
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.crypto.SodiumWrappedKey
import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.trust.DeviceDirectoryTrustContext
import dev.shinsou.kmp.sync.trust.DeviceDirectoryVerifier
import dev.shinsou.kmp.sync.trust.PinnedDeviceDirectory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class KeyRotationManifest(
    val rotationId: String,
    val workspaceId: String,
    val fromEpoch: Int,
    val toEpoch: Int,
    val proposerDeviceId: String,
    val proposerDeviceAuthEpoch: Long,
    val membershipAuthEpoch: Long,
    val keyCommitmentBase64Url: String,
    val recipientEnvelopeHashes: Map<String, String>,
    val recipientAuthEpochs: Map<String, Long>,
    val recoveryEnvelopeHashBase64Url: String,
    val recoveryAuthEpoch: Long,
    val expiresAtMillis: Long,
)

data class CompletedKeyRotation(
    val manifest: KeyRotationManifest,
    val session: SyncSession,
    val capability: WorkspaceCapability,
    val acknowledgement: RotationAcknowledgementReceipt,
)

private data class PendingRotationCommit(
    val manifest: KeyRotationManifest,
    val request: RotationCommitRequest,
)

data class InstalledRemoteRotations(
    val manifests: List<KeyRotationManifest>,
    val session: SyncSession,
    val rotationRequired: Boolean = false,
    val verifiedDeviceDirectory: PinnedDeviceDirectory? = null,
)

/** Server-current epoch and directory verified together before a device may request a lease. */
class VerifiedRotationPreflight internal constructor(
    val session: SyncSession,
    val capability: WorkspaceCapability,
    val rotationRequired: Boolean,
    val deviceDirectory: PinnedDeviceDirectory,
)

/** RFC 8949 deterministic representation shared with the Worker. */
internal object KeyRotationManifestCodec {
    private val expectedKeys = setOf(
        "rotationId",
        "workspaceId",
        "fromEpoch",
        "toEpoch",
        "proposerDeviceId",
        "proposerDeviceAuthEpoch",
        "membershipAuthEpoch",
        "keyCommitment",
        "recipientEnvelopeHashes",
        "recipientAuthEpochs",
        "recoveryEnvelopeHash",
        "recoveryAuthEpoch",
        "expiresAt",
    )

    fun encode(manifest: KeyRotationManifest): BinaryData {
        validate(manifest)
        return BinaryData.copyOf(
            DeterministicCbor.encode(
                JsonObject(
                    mapOf(
                        "rotationId" to JsonPrimitive(manifest.rotationId),
                        "workspaceId" to JsonPrimitive(manifest.workspaceId),
                        "fromEpoch" to JsonPrimitive(manifest.fromEpoch),
                        "toEpoch" to JsonPrimitive(manifest.toEpoch),
                        "proposerDeviceId" to JsonPrimitive(manifest.proposerDeviceId),
                        "proposerDeviceAuthEpoch" to JsonPrimitive(manifest.proposerDeviceAuthEpoch),
                        "membershipAuthEpoch" to JsonPrimitive(manifest.membershipAuthEpoch),
                        "keyCommitment" to JsonPrimitive(manifest.keyCommitmentBase64Url),
                        "recipientEnvelopeHashes" to JsonObject(
                            manifest.recipientEnvelopeHashes.mapValues { JsonPrimitive(it.value) },
                        ),
                        "recipientAuthEpochs" to JsonObject(
                            manifest.recipientAuthEpochs.mapValues { JsonPrimitive(it.value) },
                        ),
                        "recoveryEnvelopeHash" to JsonPrimitive(manifest.recoveryEnvelopeHashBase64Url),
                        "recoveryAuthEpoch" to JsonPrimitive(manifest.recoveryAuthEpoch),
                        "expiresAt" to JsonPrimitive(manifest.expiresAtMillis),
                    ),
                ),
            ),
        )
    }

    fun decode(bytes: BinaryData): KeyRotationManifest {
        val document = try {
            DeterministicCbor.decode(bytes.copyBytes()).jsonObject
        } catch (error: Throwable) {
            throw SyncControlPlaneException.Protocol("Rotation manifest is not deterministic CBOR", error)
        }
        if (document.keys != expectedKeys) {
            throw SyncControlPlaneException.Protocol("Rotation manifest fields do not match the protocol")
        }
        return KeyRotationManifest(
            rotationId = document.requiredString("rotationId"),
            workspaceId = document.requiredString("workspaceId"),
            fromEpoch = document.requiredPositiveInt("fromEpoch"),
            toEpoch = document.requiredPositiveInt("toEpoch"),
            proposerDeviceId = document.requiredString("proposerDeviceId"),
            proposerDeviceAuthEpoch = document.requiredPositiveLong("proposerDeviceAuthEpoch"),
            membershipAuthEpoch = document.requiredPositiveLong("membershipAuthEpoch"),
            keyCommitmentBase64Url = document.requiredHash("keyCommitment"),
            recipientEnvelopeHashes = document.requiredHashMap("recipientEnvelopeHashes"),
            recipientAuthEpochs = document.requiredPositiveLongMap("recipientAuthEpochs"),
            recoveryEnvelopeHashBase64Url = document.requiredHash("recoveryEnvelopeHash"),
            recoveryAuthEpoch = document.requiredPositiveLong("recoveryAuthEpoch"),
            expiresAtMillis = document.requiredPositiveLong("expiresAt"),
        ).also(::validate)
    }

    private fun validate(manifest: KeyRotationManifest) {
        require(manifest.rotationId.matches(UUID_PATTERN) && manifest.workspaceId.matches(UUID_PATTERN))
        require(manifest.proposerDeviceId.matches(UUID_PATTERN))
        require(manifest.fromEpoch > 0 && manifest.toEpoch == manifest.fromEpoch + 1)
        require(manifest.proposerDeviceAuthEpoch > 0 && manifest.membershipAuthEpoch > 0)
        require(manifest.recoveryAuthEpoch > 0 && manifest.expiresAtMillis > 0)
        requireCanonicalHash(manifest.keyCommitmentBase64Url, "key commitment")
        requireCanonicalHash(manifest.recoveryEnvelopeHashBase64Url, "recovery envelope hash")
        require(manifest.recipientEnvelopeHashes.isNotEmpty())
        require(manifest.recipientEnvelopeHashes.keys == manifest.recipientAuthEpochs.keys)
        manifest.recipientEnvelopeHashes.forEach { (deviceId, hash) ->
            require(deviceId.matches(UUID_PATTERN))
            requireCanonicalHash(hash, "recipient envelope hash")
        }
        require(manifest.recipientAuthEpochs.values.all { it > 0 })
    }
}

/**
 * Verifies the full trust chain for committed rotation envelopes before any key is made durable.
 * The server-visible commitment is useful only after the signed manifest and pinned proposer key
 * have both been verified; checking a server-supplied commitment by itself is intentionally
 * rejected.
 */
class SyncWorkspaceKeyEnvelopeInstaller(
    private val secretStore: SyncSecretStore,
    private val crypto: SyncCrypto,
    private val directoryVerifier: DeviceDirectoryVerifier,
) {
    suspend fun verifyAndInstall(
        session: SyncSession,
        bootstrap: WorkspaceKeyBootstrap,
        trustContext: DeviceDirectoryTrustContext,
    ): InstalledRemoteRotations {
        if (bootstrap.workspaceId != session.workspaceId || trustContext.workspaceId != session.workspaceId) {
            throw SyncControlPlaneException.Trust("Workspace key bootstrap crossed a tenant boundary")
        }
        if (bootstrap.activeKeyEpoch < session.activeKeyEpoch) {
            throw SyncControlPlaneException.Trust("Workspace key epoch rolled back")
        }
        val directory = try {
            directoryVerifier.verifyAndPinFullDirectory(trustContext, bootstrap.deviceDirectory)
        } catch (error: Throwable) {
            throw SyncControlPlaneException.Trust("Workspace device directory could not be trusted", error)
        }
        if (bootstrap.activeKeyEpoch == session.activeKeyEpoch) {
            return InstalledRemoteRotations(
                manifests = emptyList(),
                session = session,
                rotationRequired = bootstrap.rotationRequired,
                verifiedDeviceDirectory = directory,
            )
        }

        val verified = mutableListOf<Pair<KeyRotationManifest, SecretMaterial>>()
        try {
            for (epoch in (session.activeKeyEpoch + 1)..bootstrap.activeKeyEpoch) {
                val candidates = bootstrap.envelopes.filter {
                    it.keyEpoch == epoch && it.rotationEvidence?.status == "committed"
                }
                if (candidates.size != 1) {
                    throw SyncControlPlaneException.Trust(
                        "Expected exactly one committed signed envelope for key epoch $epoch",
                    )
                }
                val envelope = candidates.single()
                val manifest = verifyRotationEvidence(session, directory, envelope, epoch)
                verified += manifest to unwrapDeviceEnvelope(envelope, manifest.keyCommitmentBase64Url)
            }
            verified.forEach { (manifest, key) ->
                installExactKey(session.workspaceId, manifest.toEpoch, key, manifest.keyCommitmentBase64Url)
            }
        } catch (error: Throwable) {
            if (error is SyncControlPlaneException) throw error
            throw SyncControlPlaneException.Trust("Workspace rotation envelope verification failed", error)
        }
        return InstalledRemoteRotations(
            manifests = verified.map { it.first },
            session = session.copy(activeKeyEpoch = bootstrap.activeKeyEpoch),
            rotationRequired = bootstrap.rotationRequired,
            verifiedDeviceDirectory = directory,
        )
    }

    private suspend fun verifyRotationEvidence(
        session: SyncSession,
        directory: PinnedDeviceDirectory,
        envelope: DeviceWorkspaceKeyEnvelope,
        expectedEpoch: Int,
    ): KeyRotationManifest {
        val evidence = envelope.rotationEvidence
            ?: throw SyncControlPlaneException.Trust("Committed rotation evidence is missing")
        val manifestBytes = decodeCanonical(evidence.manifestCborBase64Url, "rotation manifest")
        val manifest = KeyRotationManifestCodec.decode(BinaryData.copyOf(manifestBytes))
        if (manifest.rotationId != envelope.rotationId || manifest.workspaceId != session.workspaceId ||
            manifest.fromEpoch != expectedEpoch - 1 || manifest.toEpoch != expectedEpoch ||
            manifest.keyCommitmentBase64Url != envelope.keyCommitmentBase64Url ||
            manifest.proposerDeviceId != envelope.wrappedByDeviceId ||
            manifest.proposerDeviceId != evidence.proposerDeviceId ||
            evidence.manifestSignatureBase64Url != envelope.signatureBase64Url ||
            evidence.recipientEnvelopeHashes != manifest.recipientEnvelopeHashes ||
            evidence.recipientAuthEpochs != manifest.recipientAuthEpochs ||
            evidence.recoveryEnvelopeHashBase64Url != manifest.recoveryEnvelopeHashBase64Url
        ) {
            throw SyncControlPlaneException.Trust("Rotation envelope does not match its signed manifest")
        }
        if (manifest.membershipAuthEpoch > session.membershipAuthEpoch) {
            throw SyncControlPlaneException.Trust("Rotation membership epoch is ahead of the authenticated session")
        }
        val recipient = directory.device(session.deviceId)
            ?: throw SyncControlPlaneException.Trust("Current device is absent from the pinned directory")
        if (recipient.status != "active" || manifest.recipientAuthEpochs[session.deviceId] != recipient.authEpoch) {
            throw SyncControlPlaneException.Trust("Current device auth epoch is not bound by the rotation manifest")
        }
        val ownEnvelopeHash = hashUtf8(envelope.wrappedKeyBase64Url)
        if (manifest.recipientEnvelopeHashes[session.deviceId] != ownEnvelopeHash) {
            throw SyncControlPlaneException.Trust("Current device envelope hash was substituted")
        }
        val proposer = directory.device(manifest.proposerDeviceId)
            ?: throw SyncControlPlaneException.Trust("Rotation proposer is absent from the pinned directory")
        if (proposer.signingPublicKey != evidence.proposerSigningPublicKeyBase64Url ||
            manifest.proposerDeviceAuthEpoch > proposer.authEpoch
        ) {
            throw SyncControlPlaneException.Trust("Rotation proposer identity or auth epoch was substituted")
        }
        val signature = decodeExact(evidence.manifestSignatureBase64Url, SIGNATURE_BYTES, "rotation signature")
        val publicKey = decodeExact(proposer.signingPublicKey, PUBLIC_KEY_BYTES, "rotation proposer key")
        val signed = BinaryData.copyOf(ROTATION_MANIFEST_DOMAIN + manifestBytes)
        if (!crypto.verifyDeviceSignature(signed, BinaryData.copyOf(signature), BinaryData.copyOf(publicKey))) {
            throw SyncControlPlaneException.Trust("Rotation manifest signature is invalid")
        }
        return manifest
    }

    private suspend fun unwrapDeviceEnvelope(
        envelope: DeviceWorkspaceKeyEnvelope,
        expectedCommitment: String,
    ): SecretMaterial {
        SodiumSyncPrimitives.initialize()
        val privateKey = copySecret(secretStore.requireSecret(SyncSecretKey.DeviceWrappingPrivateKey))
        val publicKey = try {
            SodiumSyncPrimitives.x25519PublicKey(privateKey)
        } catch (error: Throwable) {
            SodiumSyncPrimitives.destroy(privateKey)
            throw SyncControlPlaneException.KeyMismatch("Device wrapping private key is invalid", error)
        }
        val wrapped = decodeWrappedKey(envelope.wrappedKeyBase64Url)
        val plaintext = try {
            SodiumSyncPrimitives.unwrapKey(wrapped, privateKey, publicKey, WORKSPACE_KEY_WRAP_CONTEXT)
        } catch (error: Throwable) {
            throw SyncControlPlaneException.KeyMismatch("Workspace key envelope could not be authenticated", error)
        } finally {
            SodiumSyncPrimitives.destroy(privateKey)
            SodiumSyncPrimitives.destroy(publicKey)
        }
        try {
            if (plaintext.size != WORKSPACE_KEY_BYTES) {
                throw SyncControlPlaneException.KeyMismatch("Unwrapped workspace key has an invalid size")
            }
            val commitment = encodeBase64Url(
                SodiumSyncPrimitives.sha256(WORKSPACE_KEY_COMMITMENT_DOMAIN + plaintext),
            )
            if (commitment != expectedCommitment) {
                throw SyncControlPlaneException.KeyMismatch("Workspace key does not match the signed commitment")
            }
            return SecretMaterial(plaintext.asList())
        } finally {
            SodiumSyncPrimitives.destroy(plaintext)
        }
    }

    private suspend fun installExactKey(
        workspaceId: String,
        epoch: Int,
        key: SecretMaterial,
        commitment: String,
    ) {
        val secretKey = SyncSecretKey.WorkspaceEpochKey(workspaceId, epoch)
        when (val current = secretStore.read(secretKey)) {
            SyncSecretReadResult.Missing -> {
                secretStore.write(secretKey, key)
                val installed = secretStore.requireSecret(secretKey)
                if (commitmentOf(installed) != commitment) {
                    runCatching { secretStore.delete(secretKey) }
                    throw SyncControlPlaneException.KeyMismatch("Workspace key failed protected-store read-back")
                }
            }

            is SyncSecretReadResult.Available -> if (commitmentOf(current.material) != commitment) {
                throw SyncControlPlaneException.KeyMismatch("A different key is already installed for epoch $epoch")
            }

            is SyncSecretReadResult.Unavailable -> throw SyncControlPlaneException.KeyMismatch(
                "Workspace key storage is unavailable: ${current.diagnostic}",
            )

            is SyncSecretReadResult.Corrupt -> throw SyncControlPlaneException.KeyMismatch(
                "Workspace key storage is corrupt: ${current.diagnostic}",
            )
        }
    }

    private suspend fun commitmentOf(material: SecretMaterial): String =
        encodeBase64Url(crypto.keyCommitment(material).copyBytes())
}

/** Single-proposer rotation, recipient acknowledgement, and revoke-then-rotate orchestration. */
class SyncKeyRotationCoordinator(
    private val api: SyncControlPlaneApi,
    private val crypto: SyncCrypto,
    private val secretStore: SyncSecretStore,
    private val sessionStore: SyncSessionStore,
    private val capabilityProvider: suspend (SyncSession) -> WorkspaceCapability,
    private val envelopeInstaller: SyncWorkspaceKeyEnvelopeInstaller,
    /** Supplies the pinned/out-of-band trust root needed to reconcile a pending key after restart. */
    private val trustContextProvider: suspend (SyncSession) -> DeviceDirectoryTrustContext,
    /** Persists the active epoch in the transactional local replica before session metadata advances. */
    private val epochActivationSink: suspend (KeyEpochMetadata) -> Unit = {},
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newRotationId: suspend () -> String = { crypto.generateCheckpointId() },
) {
    private val mutex = Mutex()

    suspend fun rotate(preflight: VerifiedRotationPreflight): CompletedKeyRotation = mutex.withLock {
        if (!preflight.rotationRequired) {
            throw SyncControlPlaneException.Protocol("Server did not require a workspace key rotation")
        }
        val session = preflight.session
        val capability = preflight.capability
        requireReadyBinding(session, capability, session.activeKeyEpoch)
        requireVerifiedRotationDirectory(session, preflight.deviceDirectory)
        val targetSecretKey = SyncSecretKey.WorkspaceEpochKey(session.workspaceId, session.activeKeyEpoch + 1)
        if (secretStore.read(targetSecretKey) != SyncSecretReadResult.Missing) {
            return@withLock reconcilePendingRotation(session, targetSecretKey, expected = null)
        }
        val rotationId = newRotationId()
        require(rotationId.matches(UUID_PATTERN)) { "Rotation id must be a canonical UUID" }
        val leaseSignature = signCanonicalJson(
            "rotation-lease",
            JsonObject(
                mapOf(
                    "workspaceId" to JsonPrimitive(session.workspaceId),
                    "rotationId" to JsonPrimitive(rotationId),
                    "fromEpoch" to JsonPrimitive(session.activeKeyEpoch),
                    "proposerDeviceId" to JsonPrimitive(session.deviceId),
                ),
            ),
        )
        val lease = api.createRotationLease(
            session,
            capability,
            rotationId,
            session.activeKeyEpoch,
            leaseSignature,
        )
        validateLease(session, lease, preflight.deviceDirectory)
        if (lease.expiresAtMillis <= nowMillis()) {
            throw SyncControlPlaneException.Protocol("Rotation lease is already expired")
        }

        val epochKey = crypto.generateWorkspaceEpochKey()
        val keyCommitment = encodeBase64Url(crypto.keyCommitment(epochKey).copyBytes())
        val deviceEnvelopes = lease.recipients.sortedBy(RotationRecipient::deviceId).map { recipient ->
            val wrapped = crypto.wrapWorkspaceKey(
                epochKey,
                BinaryData.copyOf(decodeExact(recipient.wrappingPublicKeyBase64Url, PUBLIC_KEY_BYTES, "recipient key")),
            )
            RotationDeviceEnvelope(recipient.deviceId, encodeBase64Url(wrapped.copyBytes()))
        }
        val recoveryWrapped = encodeBase64Url(
            crypto.wrapWorkspaceKey(
                epochKey,
                BinaryData.copyOf(
                    decodeExact(lease.recovery.wrappingPublicKeyBase64Url, PUBLIC_KEY_BYTES, "recovery key"),
                ),
            ).copyBytes(),
        )
        val recipientEnvelopeHashes = mutableMapOf<String, String>()
        for (envelope in deviceEnvelopes) {
            recipientEnvelopeHashes[envelope.deviceId] = hashUtf8(envelope.wrappedKeyBase64Url)
        }
        val manifest = KeyRotationManifest(
            rotationId = lease.rotationId,
            workspaceId = lease.workspaceId,
            fromEpoch = lease.fromEpoch,
            toEpoch = lease.toEpoch,
            proposerDeviceId = lease.proposerDeviceId,
            proposerDeviceAuthEpoch = lease.proposerDeviceAuthEpoch,
            membershipAuthEpoch = lease.membershipAuthEpoch,
            keyCommitmentBase64Url = keyCommitment,
            recipientEnvelopeHashes = recipientEnvelopeHashes,
            recipientAuthEpochs = lease.recipients.associate { it.deviceId to it.authEpoch },
            recoveryEnvelopeHashBase64Url = hashUtf8(recoveryWrapped),
            recoveryAuthEpoch = lease.recovery.authEpoch,
            expiresAtMillis = lease.expiresAtMillis,
        )
        val manifestBytes = KeyRotationManifestCodec.encode(manifest)
        val manifestSignature = encodeBase64Url(
            crypto.signDeviceMessage(BinaryData.copyOf(ROTATION_MANIFEST_DOMAIN + manifestBytes.copyBytes())).copyBytes(),
        )

        // Preinstall before the server CAS. A lost response leaves a detectable pending key rather
        // than committing an epoch the device can no longer decrypt.
        secretStore.write(targetSecretKey, epochKey)
        val commitRequest = RotationCommitRequest(
                manifestCborBase64Url = encodeBase64Url(manifestBytes.copyBytes()),
                manifestSignatureBase64Url = manifestSignature,
                deviceEnvelopes = deviceEnvelopes,
                recoveryWrappedKeyBase64Url = recoveryWrapped,
            )
        val receipt = try {
            api.commitRotation(session, capability, rotationId, commitRequest)
        } catch (ambiguous: Throwable) {
            try {
                return@withLock reconcilePendingRotation(
                    session = session,
                    targetSecretKey = targetSecretKey,
                    expected = PendingRotationCommit(manifest, commitRequest),
                )
            } catch (pending: SyncControlPlaneException.PendingOperation) {
                ambiguous.addSuppressed(pending)
                throw ambiguous
            }
        }
        if (receipt.status != "committed" || receipt.activeKeyEpoch != lease.toEpoch ||
            receipt.fromEpoch != lease.fromEpoch || receipt.keyCommitmentBase64Url != keyCommitment
        ) {
            throw SyncControlPlaneException.Protocol("Rotation commit receipt does not match the signed manifest")
        }
        val updatedSession = session.copy(activeKeyEpoch = lease.toEpoch)
        epochActivationSink(
            KeyEpochMetadata(
                epoch = lease.toEpoch,
                secretKeyId = targetSecretKey.redactedName,
                status = KeyEpochStatus.ACTIVE,
                createdAtMillis = nowMillis(),
            ),
        )
        sessionStore.save(updatedSession)
        val updatedCapability = capabilityProvider(updatedSession)
        requireReadyBinding(updatedSession, updatedCapability, lease.toEpoch)
        val acknowledgement = acknowledge(updatedSession, updatedCapability, manifest)
        CompletedKeyRotation(manifest, updatedSession, updatedCapability, acknowledgement)
    }

    /**
     * Resolves the only safe meaning of a preinstalled next-epoch key. A server-current capability
     * at the old epoch proves that no commit is visible and leaves the operation pending. A
     * capability at exactly +1 must be backed by the exact committed signed bootstrap evidence
     * before local epoch metadata/session state can advance and acknowledgement can be retried.
     */
    private suspend fun reconcilePendingRotation(
        session: SyncSession,
        targetSecretKey: SyncSecretKey.WorkspaceEpochKey,
        expected: PendingRotationCommit?,
    ): CompletedKeyRotation {
        val serverCapability = capabilityProvider(session)
        val binding = serverCapability.binding
        if (binding.deviceId != session.deviceId || binding.workspaceId != session.workspaceId ||
            binding.deviceAuthEpoch != session.deviceAuthEpoch ||
            binding.membershipAuthEpoch != session.membershipAuthEpoch ||
            binding.expiresAtMillis <= nowMillis()
        ) {
            throw SyncControlPlaneException.Trust(
                "Pending rotation capability crossed an identity or authorization boundary",
            )
        }
        if (binding.keyEpoch == session.activeKeyEpoch) {
            throw SyncControlPlaneException.PendingOperation(
                "The preinstalled next-epoch key has no committed server rotation",
            )
        }
        if (binding.keyEpoch != session.activeKeyEpoch + 1) {
            throw SyncControlPlaneException.Trust("Pending rotation skipped or rolled back a key epoch")
        }
        val bootstrap = api.workspaceKeyBootstrap(session, serverCapability)
        if (bootstrap.workspaceId != session.workspaceId || bootstrap.activeKeyEpoch != binding.keyEpoch) {
            throw SyncControlPlaneException.Trust("Pending rotation bootstrap disagrees with its capability")
        }

        val manifest = if (expected != null) {
            verifyExactCommittedEvidence(session, bootstrap, targetSecretKey, expected)
        } else {
            val installed = envelopeInstaller.verifyAndInstall(
                session,
                bootstrap,
                trustContextProvider(session),
            )
            installed.manifests.singleOrNull()?.takeIf { candidate ->
                candidate.workspaceId == session.workspaceId &&
                    candidate.fromEpoch == session.activeKeyEpoch &&
                    candidate.toEpoch == binding.keyEpoch &&
                    candidate.proposerDeviceId == session.deviceId
            } ?: throw SyncControlPlaneException.Trust(
                "Pending rotation bootstrap did not contain one self-proposed committed manifest",
            )
        }
        val installedCommitment = encodeBase64Url(
            crypto.keyCommitment(secretStore.requireSecret(targetSecretKey)).copyBytes(),
        )
        if (installedCommitment != manifest.keyCommitmentBase64Url) {
            throw SyncControlPlaneException.KeyMismatch("Pending rotation key conflicts with committed evidence")
        }

        val updatedSession = session.copy(activeKeyEpoch = manifest.toEpoch)
        epochActivationSink(
            KeyEpochMetadata(
                epoch = manifest.toEpoch,
                secretKeyId = targetSecretKey.redactedName,
                status = KeyEpochStatus.ACTIVE,
                createdAtMillis = nowMillis(),
            ),
        )
        sessionStore.save(updatedSession)
        val refreshedCapability = capabilityProvider(updatedSession)
        requireReadyBinding(updatedSession, refreshedCapability, manifest.toEpoch)
        val acknowledgement = acknowledge(updatedSession, refreshedCapability, manifest)
        return CompletedKeyRotation(manifest, updatedSession, refreshedCapability, acknowledgement)
    }

    private suspend fun verifyExactCommittedEvidence(
        session: SyncSession,
        bootstrap: WorkspaceKeyBootstrap,
        targetSecretKey: SyncSecretKey.WorkspaceEpochKey,
        expected: PendingRotationCommit,
    ): KeyRotationManifest {
        val ownExpectedEnvelope = expected.request.deviceEnvelopes.singleOrNull {
            it.deviceId == session.deviceId
        } ?: throw SyncControlPlaneException.Protocol("Own rotation envelope was absent from the commit request")
        val candidates = bootstrap.envelopes.filter {
            it.keyEpoch == expected.manifest.toEpoch && it.rotationId == expected.manifest.rotationId &&
                it.rotationEvidence?.status == "committed"
        }
        val envelope = candidates.singleOrNull()
            ?: throw SyncControlPlaneException.Trust("Exact committed rotation evidence is absent from bootstrap")
        val evidence = requireNotNull(envelope.rotationEvidence)
        val manifestBytes = decodeCanonical(evidence.manifestCborBase64Url, "rotation manifest")
        val decoded = KeyRotationManifestCodec.decode(BinaryData.copyOf(manifestBytes))
        val directoryDevice = bootstrap.deviceDirectory.devices.singleOrNull { it.deviceId == session.deviceId }
            ?: throw SyncControlPlaneException.Trust("Rotation proposer is absent from the bootstrap directory")
        if (decoded != expected.manifest ||
            evidence.manifestCborBase64Url != expected.request.manifestCborBase64Url ||
            evidence.manifestSignatureBase64Url != expected.request.manifestSignatureBase64Url ||
            evidence.proposerDeviceId != session.deviceId || envelope.wrappedByDeviceId != session.deviceId ||
            envelope.signatureBase64Url != expected.request.manifestSignatureBase64Url ||
            envelope.wrappedKeyBase64Url != ownExpectedEnvelope.wrappedKeyBase64Url ||
            envelope.keyCommitmentBase64Url != expected.manifest.keyCommitmentBase64Url ||
            evidence.recipientEnvelopeHashes != expected.manifest.recipientEnvelopeHashes ||
            evidence.recipientAuthEpochs != expected.manifest.recipientAuthEpochs ||
            evidence.recoveryEnvelopeHashBase64Url != expected.manifest.recoveryEnvelopeHashBase64Url ||
            evidence.recoveryEnvelopeHashBase64Url != hashUtf8(expected.request.recoveryWrappedKeyBase64Url) ||
            directoryDevice.status != "active" || directoryDevice.authEpoch != session.deviceAuthEpoch ||
            directoryDevice.signingPublicKey != evidence.proposerSigningPublicKeyBase64Url
        ) {
            throw SyncControlPlaneException.Trust("Committed rotation evidence differs from the submitted request")
        }
        val validSignature = crypto.verifyDeviceSignature(
            BinaryData.copyOf(ROTATION_MANIFEST_DOMAIN + manifestBytes),
            BinaryData.copyOf(decodeExact(evidence.manifestSignatureBase64Url, SIGNATURE_BYTES, "rotation signature")),
            BinaryData.copyOf(
                decodeExact(evidence.proposerSigningPublicKeyBase64Url, PUBLIC_KEY_BYTES, "rotation proposer key"),
            ),
        )
        if (!validSignature) {
            throw SyncControlPlaneException.Trust("Committed rotation evidence signature is invalid")
        }
        val installedCommitment = encodeBase64Url(
            crypto.keyCommitment(secretStore.requireSecret(targetSecretKey)).copyBytes(),
        )
        if (installedCommitment != decoded.keyCommitmentBase64Url) {
            throw SyncControlPlaneException.KeyMismatch("Committed rotation does not use the preinstalled key")
        }
        return decoded
    }

    suspend fun installRemoteRotations(
        session: SyncSession,
        capability: WorkspaceCapability,
        trustContext: DeviceDirectoryTrustContext,
        verifiedRotationValidator: (InstalledRemoteRotations) -> Unit = {},
    ): InstalledRemoteRotations = mutex.withLock {
        val effectiveSession = sessionForServerCapability(session, capability)
        val bootstrap = api.workspaceKeyBootstrap(effectiveSession, capability)
        if (capability.binding.keyEpoch != bootstrap.activeKeyEpoch) {
            throw SyncControlPlaneException.Trust("Capability and bootstrap active key epochs disagree")
        }
        val installed = envelopeInstaller.verifyAndInstall(effectiveSession, bootstrap, trustContext)
        // Callers reconciling a durable control-plane receipt must be able to bind that receipt
        // to the exact signed manifest chain before the advanced session epoch is persisted.
        verifiedRotationValidator(installed)
        if (installed.manifests.isEmpty()) {
            if (effectiveSession != session) sessionStore.save(effectiveSession)
            return@withLock installed
        }
        installed.manifests.forEach { manifest ->
            epochActivationSink(
                KeyEpochMetadata(
                    epoch = manifest.toEpoch,
                    secretKeyId = SyncSecretKey.WorkspaceEpochKey(
                        installed.session.workspaceId,
                        manifest.toEpoch,
                    ).redactedName,
                    status = if (manifest.toEpoch == installed.session.activeKeyEpoch) {
                        KeyEpochStatus.ACTIVE
                    } else {
                        KeyEpochStatus.RETAINED
                    },
                    createdAtMillis = nowMillis(),
                ),
            )
        }
        sessionStore.save(installed.session)
        val latest = installed.manifests.last()
        acknowledge(installed.session, capability, latest)
        installed
    }

    /**
     * Preflight for normal sync. It intentionally accepts a server-current capability whose key
     * epoch is ahead of the durable session, installs the signed envelopes, then advances the
     * session. Call this before SyncEngine's strict capability validation.
     */
    suspend fun refreshKeyEpochBeforeSync(
        session: SyncSession,
        trustContext: DeviceDirectoryTrustContext,
        verifiedRotationValidator: (InstalledRemoteRotations) -> Unit = {},
    ): SyncSession {
        val currentCapability = capabilityProvider(session)
        return installRemoteRotations(
            session,
            currentCapability,
            trustContext,
            verifiedRotationValidator,
        ).session
    }

    /**
     * Reads the server-current rotation gate, then verifies/pins the complete device directory and
     * every intervening signed manifest before returning a value that can authorize [rotate].
     */
    suspend fun preflightRequiredRotation(
        session: SyncSession,
        trustContext: DeviceDirectoryTrustContext,
    ): VerifiedRotationPreflight {
        val capability = capabilityProvider(session)
        val installed = installRemoteRotations(session, capability, trustContext)
        val directory = installed.verifiedDeviceDirectory
            ?: throw SyncControlPlaneException.Trust("Rotation preflight did not verify a device directory")
        return VerifiedRotationPreflight(
            session = installed.session,
            capability = capability,
            rotationRequired = installed.rotationRequired,
            deviceDirectory = directory,
        )
    }

    fun asKeyEpochResolver(
        trustContext: suspend (SyncSession) -> DeviceDirectoryTrustContext,
    ): SyncKeyEpochResolver = SyncKeyEpochResolver { session, epoch ->
        require(epoch > 0) { "Requested key epoch is invalid" }
        val key = SyncSecretKey.WorkspaceEpochKey(session.workspaceId, epoch)
        var effectiveSession = session
        if (secretStore.read(key) == SyncSecretReadResult.Missing || epoch > session.activeKeyEpoch) {
            effectiveSession = refreshKeyEpochBeforeSync(session, trustContext(session))
        }
        secretStore.requireSecret(key)
        if (epoch > effectiveSession.activeKeyEpoch) {
            throw SyncControlPlaneException.Trust("Server did not provide requested key epoch $epoch")
        }
        KeyEpochMetadata(
            epoch = epoch,
            secretKeyId = key.redactedName,
            status = if (epoch == effectiveSession.activeKeyEpoch) KeyEpochStatus.ACTIVE else KeyEpochStatus.RETAINED,
            createdAtMillis = nowMillis(),
        )
    }

    private suspend fun acknowledge(
        session: SyncSession,
        capability: WorkspaceCapability,
        manifest: KeyRotationManifest,
    ): RotationAcknowledgementReceipt {
        val signature = signCanonicalJson(
            "rotation-ack",
            JsonObject(
                mapOf(
                    "workspaceId" to JsonPrimitive(session.workspaceId),
                    "rotationId" to JsonPrimitive(manifest.rotationId),
                    "deviceId" to JsonPrimitive(session.deviceId),
                    "keyEpoch" to JsonPrimitive(manifest.toEpoch),
                    "keyCommitment" to JsonPrimitive(manifest.keyCommitmentBase64Url),
                ),
            ),
        )
        return api.acknowledgeRotation(
            session,
            capability,
            manifest.rotationId,
            manifest.keyCommitmentBase64Url,
            signature,
        ).also { require(it.acknowledged) { "Rotation acknowledgement was not accepted" } }
    }

    private suspend fun signCanonicalJson(domain: String, value: JsonObject): String {
        val canonical = dev.shinsou.kmp.sync.trust.canonicalSyncJson(value)
        return encodeBase64Url(
            crypto.signDeviceMessage(
                BinaryData.copyOf("shinsou:$domain:v1\u0000".encodeToByteArray() + canonical.encodeToByteArray()),
            ).copyBytes(),
        )
    }

    private fun validateLease(
        session: SyncSession,
        lease: KeyRotationLease,
        directory: PinnedDeviceDirectory,
    ) {
        val activeRecipients = directory.devices.filter { it.status == "active" }.associateBy { it.deviceId }
        val leasedRecipients = lease.recipients.associateBy { it.deviceId }
        if (lease.workspaceId != session.workspaceId || lease.proposerDeviceId != session.deviceId ||
            lease.fromEpoch != session.activeKeyEpoch || lease.toEpoch != session.activeKeyEpoch + 1 ||
            lease.proposerDeviceAuthEpoch != session.deviceAuthEpoch ||
            lease.membershipAuthEpoch != session.membershipAuthEpoch ||
            leasedRecipients.size != lease.recipients.size || leasedRecipients.keys != activeRecipients.keys ||
            leasedRecipients.any { (deviceId, recipient) ->
                val pinned = activeRecipients.getValue(deviceId)
                recipient.authEpoch != pinned.authEpoch ||
                    recipient.wrappingPublicKeyBase64Url != pinned.wrappingPublicKey
            }
        ) {
            throw SyncControlPlaneException.Trust(
                "Rotation lease does not match the verified active-device directory",
            )
        }
    }

    private fun requireVerifiedRotationDirectory(
        session: SyncSession,
        directory: PinnedDeviceDirectory,
    ) {
        val current = directory.device(session.deviceId)
        if (directory.workspaceId != session.workspaceId || current?.status != "active" ||
            current.authEpoch != session.deviceAuthEpoch
        ) {
            throw SyncControlPlaneException.Trust(
                "Rotation preflight directory does not bind the active session device",
            )
        }
    }

    private fun requireReadyBinding(session: SyncSession, capability: WorkspaceCapability, epoch: Int) {
        require(capability.binding.deviceId == session.deviceId && capability.binding.workspaceId == session.workspaceId)
        require(capability.binding.deviceAuthEpoch == session.deviceAuthEpoch)
        require(capability.binding.membershipAuthEpoch == session.membershipAuthEpoch)
        require(capability.binding.keyEpoch == epoch)
    }

    private fun sessionForServerCapability(
        session: SyncSession,
        capability: WorkspaceCapability,
    ): SyncSession {
        val binding = capability.binding
        if (binding.deviceId != session.deviceId || binding.workspaceId != session.workspaceId ||
            binding.expiresAtMillis <= nowMillis() || binding.deviceAuthEpoch < session.deviceAuthEpoch ||
            binding.membershipAuthEpoch < session.membershipAuthEpoch || binding.keyEpoch < session.activeKeyEpoch
        ) {
            throw SyncControlPlaneException.Trust("Server-current capability rolled back or crossed an identity boundary")
        }
        return session.copy(
            deviceAuthEpoch = binding.deviceAuthEpoch,
            membershipAuthEpoch = binding.membershipAuthEpoch,
        )
    }
}

private fun JsonObject.requiredString(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw SyncControlPlaneException.Protocol("Rotation manifest field '$name' is not a string")

private fun JsonObject.requiredPositiveLong(name: String): Long =
    (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull
        ?.takeIf { it > 0 }
        ?: throw SyncControlPlaneException.Protocol("Rotation manifest field '$name' is not a positive integer")

private fun JsonObject.requiredPositiveInt(name: String): Int = requiredPositiveLong(name).toInt().also {
    if (it.toLong() != requiredPositiveLong(name)) {
        throw SyncControlPlaneException.Protocol("Rotation manifest field '$name' exceeds the integer range")
    }
}

private fun JsonObject.requiredHash(name: String): String = requiredString(name).also {
    requireCanonicalHash(it, "rotation manifest field '$name'")
}

private fun JsonObject.requiredHashMap(name: String): Map<String, String> =
    (this[name] as? JsonObject)?.mapValues { (_, value) ->
        (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?.also { requireCanonicalHash(it, "rotation recipient envelope hash") }
            ?: throw SyncControlPlaneException.Protocol("Rotation recipient hash is malformed")
    } ?: throw SyncControlPlaneException.Protocol("Rotation manifest field '$name' is not a map")

private fun JsonObject.requiredPositiveLongMap(name: String): Map<String, Long> =
    (this[name] as? JsonObject)?.mapValues { (_, value) ->
        (value as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull?.takeIf { it > 0 }
            ?: throw SyncControlPlaneException.Protocol("Rotation recipient auth epoch is malformed")
    } ?: throw SyncControlPlaneException.Protocol("Rotation manifest field '$name' is not a map")

private fun decodeWrappedKey(encoded: String): SodiumWrappedKey {
    val bytes = decodeCanonical(encoded, "workspace key envelope")
    val value = try {
        DeterministicCbor.decode(bytes).jsonObject
    } catch (error: Throwable) {
        throw SyncControlPlaneException.KeyMismatch("Workspace key envelope is not deterministic CBOR", error)
    }
    if (value.keys != setOf("cipherSuite", "ephemeralPublicKey", "nonce", "ciphertext") ||
        value.requiredString("cipherSuite") != "X25519_HKDF_SHA256_CHACHA20_POLY1305"
    ) {
        throw SyncControlPlaneException.KeyMismatch("Workspace key envelope fields or cipher suite are invalid")
    }
    return SodiumWrappedKey(
        ephemeralPublicKey = decodeExact(value.requiredString("ephemeralPublicKey"), PUBLIC_KEY_BYTES, "ephemeral key"),
        nonce = decodeExact(value.requiredString("nonce"), NONCE_BYTES, "workspace key nonce"),
        ciphertext = decodeCanonical(value.requiredString("ciphertext"), "workspace key ciphertext")
            .also { if (it.size !in MIN_WRAPPED_CIPHERTEXT_BYTES..MAX_WRAPPED_CIPHERTEXT_BYTES) {
                throw SyncControlPlaneException.KeyMismatch("Workspace key ciphertext size is invalid")
            } },
    )
}

private fun decodeExact(value: String, size: Int, label: String): ByteArray =
    decodeCanonical(value, label).also {
        if (it.size != size) throw SyncControlPlaneException.Protocol("$label has an invalid size")
    }

private fun decodeCanonical(value: String, label: String): ByteArray = try {
    decodeBase64Url(value)
} catch (error: Throwable) {
    throw SyncControlPlaneException.Protocol("$label is not canonical base64url", error)
}

private suspend fun hashUtf8(value: String): String {
    SodiumSyncPrimitives.initialize()
    return encodeBase64Url(SodiumSyncPrimitives.sha256(value.encodeToByteArray()))
}

private suspend fun hashBytes(value: ByteArray): String {
    SodiumSyncPrimitives.initialize()
    return encodeBase64Url(SodiumSyncPrimitives.sha256(value))
}

private fun requireCanonicalHash(value: String, label: String) {
    val decoded = try {
        decodeBase64Url(value)
    } catch (error: Throwable) {
        throw SyncControlPlaneException.Protocol("$label is not canonical base64url", error)
    }
    if (decoded.size != HASH_BYTES) throw SyncControlPlaneException.Protocol("$label has an invalid size")
}

private fun copySecret(material: SecretMaterial): ByteArray {
    var bytes: ByteArray? = null
    material.useBytes { bytes = it.copyOf() }
    return requireNotNull(bytes)
}

private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val ROTATION_MANIFEST_DOMAIN = "shinsou:rotation-manifest:v1\u0000".encodeToByteArray()
private val WORKSPACE_KEY_COMMITMENT_DOMAIN = "shinsou:workspace-key-commitment:v1\u0000".encodeToByteArray()
private val WORKSPACE_KEY_WRAP_CONTEXT = "shinsou:workspace-key-envelope:v1".encodeToByteArray()
private const val PUBLIC_KEY_BYTES = 32
private const val SIGNATURE_BYTES = 64
private const val HASH_BYTES = 32
private const val NONCE_BYTES = 12
private const val WORKSPACE_KEY_BYTES = 32
private const val MIN_WRAPPED_CIPHERTEXT_BYTES = 16
private const val MAX_WRAPPED_CIPHERTEXT_BYTES = 1024
