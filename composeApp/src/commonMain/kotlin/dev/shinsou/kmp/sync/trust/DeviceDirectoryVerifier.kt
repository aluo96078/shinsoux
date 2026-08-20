package dev.shinsou.kmp.sync.trust

import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.network.encodeBase64Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Verifies the Worker's signed device directory before any sender key becomes usable. */
class DeviceDirectoryVerifier(
    private val pinStore: DeviceDirectoryPinStore,
    private val json: Json = Json.Default,
) {
    suspend fun verifyAndPinFullDirectory(
        context: DeviceDirectoryTrustContext,
        directory: DeviceDirectoryWire,
    ): PinnedDeviceDirectory {
        repeat(MAX_PIN_ATTEMPTS) {
            val current = pinStore.load(context.workspaceId)
            val verified = verifyFullDirectory(context, directory, current)
            if (current != null && verified == current) return current
            if (pinStore.compareAndSet(context.workspaceId, current?.revision, verified)) return verified
        }
        throw DeviceDirectoryTrustException.ConcurrentUpdate(
            "Device directory changed repeatedly while the verified pin was being saved",
        )
    }

    /**
     * Catch-up pages carry only sender entries. A subset can authenticate senders at the already
     * pinned revision, but can never advance trust; a newer revision requires `/bootstrap` first.
     */
    suspend fun verifyPinnedSenderSubset(
        workspaceId: String,
        directory: DeviceDirectoryWire,
        expectedSenderDeviceIds: Set<String>,
    ): Map<String, PinnedDeviceIdentity> {
        val current = pinStore.load(workspaceId)
            ?: throw DeviceDirectoryTrustException.FullDirectoryRequired("No full device directory is pinned")
        when {
            directory.version < current.version -> throw DeviceDirectoryTrustException.Rollback(
                "Catch-up device directory version rolled back",
            )

            directory.version > current.version -> throw DeviceDirectoryTrustException.FullDirectoryRequired(
                "Catch-up references a newer device directory; bootstrap is required",
            )

            directory.hash != current.hash -> throw DeviceDirectoryTrustException.Equivocation(
                "Catch-up device directory hash conflicts with the pinned revision",
            )
        }
        if (directory.allDeviceCount != current.allDeviceCount) {
            throw DeviceDirectoryTrustException.Equivocation("Catch-up device count conflicts with the pinned revision")
        }
        validateUniqueSortedDevices(directory.devices, allowEmpty = expectedSenderDeviceIds.isEmpty())
        val actualIds = directory.devices.map(DeviceDirectoryEntryWire::deviceId).toSet()
        if (actualIds != expectedSenderDeviceIds) {
            throw DeviceDirectoryTrustException.Malformed("Catch-up sender directory is incomplete or contains extras")
        }
        SodiumSyncPrimitives.initialize()
        return directory.devices.associate { entry ->
            validateDirectoryEntry(entry)
            val pin = current.device(entry.deviceId)
                ?: throw DeviceDirectoryTrustException.UntrustedAttestation(
                    "Catch-up contains an unknown sender device",
                )
            requirePinnedIdentityUnchanged(pin, entry, attestationHash(entry.attestation))
            if (pin.status != entry.status || pin.authEpoch != entry.authEpoch || pin.revokedAt != entry.revokedAt) {
                throw DeviceDirectoryTrustException.Equivocation(
                    "Catch-up sender metadata changed without a full directory revision",
                )
            }
            entry.deviceId to pin
        }
    }

    private suspend fun verifyFullDirectory(
        context: DeviceDirectoryTrustContext,
        directory: DeviceDirectoryWire,
        current: PinnedDeviceDirectory?,
    ): PinnedDeviceDirectory {
        validateContext(context)
        if (directory.version <= 0) malformed("Device directory version is invalid")
        if (!directory.hash.isCanonicalSha256()) malformed("Device directory hash is invalid")
        if (directory.allDeviceCount != directory.devices.size || directory.devices.isEmpty()) {
            malformed("Full device directory count is inconsistent")
        }
        validateUniqueSortedDevices(directory.devices, allowEmpty = false)
        current?.let {
            when {
                directory.version < it.version -> throw DeviceDirectoryTrustException.Rollback(
                    "Device directory version rolled back",
                )

                directory.version == it.version && directory.hash != it.hash ->
                    throw DeviceDirectoryTrustException.Equivocation(
                        "Device directory hash changed without a version change",
                    )
            }
        }

        SodiumSyncPrimitives.initialize()
        val calculatedHash = calculateDeviceDirectoryHash(
            workspaceId = context.workspaceId,
            version = directory.version,
            devices = directory.devices,
        )
        if (calculatedHash != directory.hash) {
            throw DeviceDirectoryTrustException.Equivocation("Device directory contents do not match its hash")
        }

        val previous = current?.devices?.associateBy(PinnedDeviceIdentity::deviceId).orEmpty()
        val explicit = context.trustedDevices.associateBy(TrustedDeviceAnchor::deviceId)
        explicit.forEach { (deviceId, anchor) ->
            val old = previous[deviceId]
            if (old != null && (old.signingPublicKey != anchor.signingPublicKey ||
                    old.wrappingPublicKey != anchor.wrappingPublicKey)
            ) {
                throw DeviceDirectoryTrustException.KeySubstitution("Out-of-band anchor conflicts with a pinned key")
            }
        }
        val entriesById = directory.devices.associateBy(DeviceDirectoryEntryWire::deviceId)
        val parsed = mutableMapOf<String, ParsedAttestation>()
        val attestationHashes = mutableMapOf<String, String>()
        for (entry in directory.devices) {
            validateDirectoryEntry(entry)
            val attestation = parseAndVerifyAttestation(context, entry)
            parsed[entry.deviceId] = attestation
            val evidenceHash = attestationHash(entry.attestation)
            attestationHashes[entry.deviceId] = evidenceHash
            previous[entry.deviceId]?.let { requirePinnedIdentityUnchanged(it, entry, evidenceHash) }
        }
        val removed = previous.keys - entriesById.keys
        if (removed.isNotEmpty()) {
            throw DeviceDirectoryTrustException.KeySubstitution("A previously pinned device disappeared from the directory")
        }

        val trusted = previous.mapValues { (_, pin) ->
            TrustedDeviceAnchor(pin.deviceId, pin.signingPublicKey, pin.wrappingPublicKey)
        }.toMutableMap()
        explicit.forEach { (id, anchor) ->
            entriesById[id]?.let { entry -> requireAnchorMatches(anchor, entry) }
            trusted[id] = anchor
        }
        val trustedRecoveryKeys = context.trustedRecoverySigningPublicKeys.toMutableSet()
        val pending = directory.devices.associateBy(DeviceDirectoryEntryWire::deviceId).toMutableMap()
        while (pending.isNotEmpty()) {
            var progressed = false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val (deviceId, entry) = iterator.next()
                val attestation = requireNotNull(parsed[deviceId])
                if (!hasTrustPath(entry, attestation, trusted, trustedRecoveryKeys, entriesById)) continue
                trusted[deviceId] = TrustedDeviceAnchor(
                    deviceId = deviceId,
                    signingPublicKey = entry.signingPublicKey,
                    wrappingPublicKey = entry.wrappingPublicKey,
                )
                iterator.remove()
                progressed = true
            }
            if (!progressed) {
                throw DeviceDirectoryTrustException.UntrustedAttestation(
                    "Device directory contains an enrollment chain without a trusted root",
                )
            }
        }

        return PinnedDeviceDirectory(
            workspaceId = context.workspaceId,
            version = directory.version,
            hash = directory.hash,
            allDeviceCount = directory.allDeviceCount,
            devices = directory.devices.map { entry ->
                PinnedDeviceIdentity(
                    deviceId = entry.deviceId,
                    userId = entry.userId,
                    displayName = entry.displayName,
                    platform = entry.platform,
                    signingPublicKey = entry.signingPublicKey,
                    wrappingPublicKey = entry.wrappingPublicKey,
                    status = entry.status,
                    authEpoch = entry.authEpoch,
                    createdAt = entry.createdAt,
                    revokedAt = entry.revokedAt,
                    attestationSha256 = requireNotNull(attestationHashes[entry.deviceId]),
                )
            },
        ).also { requireMonotonicPin(current, it) }
    }

    private suspend fun parseAndVerifyAttestation(
        context: DeviceDirectoryTrustContext,
        entry: DeviceDirectoryEntryWire,
    ): ParsedAttestation {
        val evidence = entry.attestation
        if (evidence.manifestJson.encodeToByteArray().size > MAX_MANIFEST_BYTES) {
            malformed("Device attestation manifest exceeds its size limit")
        }
        val manifest = try {
            json.parseToJsonElement(evidence.manifestJson).jsonObject
        } catch (error: Throwable) {
            throw DeviceDirectoryTrustException.Malformed("Device attestation manifest is malformed")
        }
        if (canonicalSyncJson(manifest) != evidence.manifestJson) {
            malformed("Device attestation manifest is not canonical JSON")
        }
        val publicKey = decodeKey(evidence.attestorPublicKey, PUBLIC_KEY_BYTES, "attestor public key")
        val signature = decodeKey(evidence.signature, SIGNATURE_BYTES, "attestation signature")
        val expectedDomain = when (evidence.type) {
            "initial" -> "initial-workspace-claim"
            "pairing" -> "pairing-approval"
            "recovery" -> "recovery-claim"
            else -> malformed("Unknown device attestation type")
        }
        if (evidence.signatureDomain != expectedDomain) malformed("Device attestation domain is inconsistent")
        val message = "shinsou:$expectedDomain:v1\u0000".encodeToByteArray() + evidence.manifestJson.encodeToByteArray()
        if (!SodiumSyncPrimitives.verifyEd25519(message, signature, publicKey)) {
            throw DeviceDirectoryTrustException.UntrustedAttestation("Device enrollment signature is invalid")
        }
        return when (evidence.type) {
            "initial" -> validateInitialManifest(context, entry, manifest)
            "pairing" -> validatePairingManifest(context, entry, manifest)
            "recovery" -> validateRecoveryManifest(context, entry, manifest)
            else -> error("unreachable")
        }
    }

    private fun validateInitialManifest(
        context: DeviceDirectoryTrustContext,
        entry: DeviceDirectoryEntryWire,
        manifest: JsonObject,
    ): ParsedAttestation {
        manifest.requireExactKeys(INITIAL_MANIFEST_KEYS)
        val evidence = entry.attestation
        if (evidence.workspaceId != context.workspaceId ||
            evidence.attestorDeviceId != entry.deviceId ||
            evidence.attestorPublicKey != entry.signingPublicKey
        ) malformed("Initial device attestation binding is inconsistent")
        manifest.requireString("instanceId", context.instanceId)
        manifest.requireString("workspaceId", context.workspaceId)
        manifest.requireString("userId", entry.userId)
        manifest.requireString("deviceId", entry.deviceId)
        manifest.requireString("signingPublicKey", entry.signingPublicKey)
        manifest.requireString("wrappingPublicKey", entry.wrappingPublicKey)
        manifest.requireLong("keyEpoch", 1L)
        manifest.requireSha256("deviceTokenHash")
        manifest.requireSha256("keyCommitment")
        manifest.requireSha256("deviceWrappedKeyHash")
        manifest.requireSha256("recoveryWrappedKeyHash")
        val recoverySigningPublicKey = manifest.requireString("recoverySigningPublicKey")
        val recoveryWrappingPublicKey = manifest.requireString("recoveryWrappingPublicKey")
        if (recoverySigningPublicKey == entry.signingPublicKey) {
            malformed("Initial device and Recovery root reuse a signing key")
        }
        val recoverySigningKey = decodeKey(
            recoverySigningPublicKey,
            PUBLIC_KEY_BYTES,
            "recovery signing key",
        )
        decodeKey(recoveryWrappingPublicKey, PUBLIC_KEY_BYTES, "recovery wrapping key")
        val recoveryTrustSignature = decodeKey(
            manifest.requireString("recoveryDeviceTrustSignature"),
            SIGNATURE_BYTES,
            "initial device recovery trust signature",
        )
        val recoveryTrustManifest = canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(context.instanceId),
                    "userId" to JsonPrimitive(entry.userId),
                    "workspaceId" to JsonPrimitive(context.workspaceId),
                    "deviceId" to JsonPrimitive(entry.deviceId),
                    "signingPublicKey" to JsonPrimitive(entry.signingPublicKey),
                    "wrappingPublicKey" to JsonPrimitive(entry.wrappingPublicKey),
                    "recoverySigningPublicKey" to JsonPrimitive(recoverySigningPublicKey),
                    "recoveryWrappingPublicKey" to JsonPrimitive(recoveryWrappingPublicKey),
                ),
            ),
        )
        if (!SodiumSyncPrimitives.verifyEd25519(
                INITIAL_DEVICE_RECOVERY_TRUST_DOMAIN + recoveryTrustManifest.encodeToByteArray(),
                recoveryTrustSignature,
                recoverySigningKey,
            )
        ) {
            throw DeviceDirectoryTrustException.UntrustedAttestation(
                "Initial device is not co-signed by its Recovery root",
            )
        }
        return ParsedAttestation(
            kind = AttestationKind.INITIAL,
            attestorDeviceId = entry.deviceId,
            initialRecoverySigningPublicKey = recoverySigningPublicKey,
        )
    }

    private fun validatePairingManifest(
        context: DeviceDirectoryTrustContext,
        entry: DeviceDirectoryEntryWire,
        manifest: JsonObject,
    ): ParsedAttestation {
        manifest.requireExactKeys(PAIRING_MANIFEST_KEYS)
        val evidence = entry.attestation
        if (evidence.workspaceId != context.workspaceId || evidence.attestorDeviceId == null) {
            malformed("Pairing attestation workspace or sponsor is missing")
        }
        val transcript = manifest.requireObject("transcript")
        transcript.requireExactKeys(PAIRING_TRANSCRIPT_KEYS)
        transcript.requireString("workspaceId", context.workspaceId)
        transcript.requireString("sponsorDeviceId", evidence.attestorDeviceId)
        val sponsorSigningPublicKey = transcript.requireString("sponsorSigningPublicKey")
        val sponsorWrappingPublicKey = transcript.requireString("sponsorWrappingPublicKey")
        if (sponsorSigningPublicKey != evidence.attestorPublicKey) {
            malformed("Pairing transcript sponsor signing key conflicts with its attestation")
        }
        decodeKey(sponsorSigningPublicKey, PUBLIC_KEY_BYTES, "pairing sponsor signing key")
        decodeKey(sponsorWrappingPublicKey, PUBLIC_KEY_BYTES, "pairing sponsor wrapping key")
        transcript.requireString("candidateDeviceId", entry.deviceId)
        transcript.requireString("candidateDisplayName", entry.displayName)
        transcript.requireString("candidatePlatform", entry.platform)
        transcript.requireString("candidateSigningPublicKey", entry.signingPublicKey)
        transcript.requireString("candidateWrappingPublicKey", entry.wrappingPublicKey)
        val tokenHash = transcript.requireString("candidateTokenHash")
        requireSha256(tokenHash, "pairing token commitment")
        manifest.requireString("candidateTokenHash", tokenHash)
        manifest.requireLong("expiresAt", transcript.requireLong("expiresAt"))
        transcript.requireUuid("pairingId")
        transcript.requireString("transcriptNonce").also { requireMinimumBase64Url(it, 24, "pairing nonce") }
        val envelopes = manifest.requireArray("envelopes")
        if (envelopes.isEmpty() || envelopes.size > MAX_KEY_EPOCHS) malformed("Pairing keyring is invalid")
        var previousEpoch = 0L
        envelopes.forEach { element ->
            val envelope = element.asObject("pairing envelope")
            envelope.requireExactKeys(PAIRING_ENVELOPE_KEYS)
            val epoch = envelope.requirePositiveLong("keyEpoch")
            if (epoch <= previousEpoch) malformed("Pairing keyring is not sorted or contains duplicate epochs")
            previousEpoch = epoch
            envelope.requireSha256("keyCommitment")
            envelope.requireSha256("wrappedKeyHash")
            requireBase64Url(envelope.requireString("envelopeSignature"), SIGNATURE_BYTES, "envelope signature")
        }
        return ParsedAttestation(
            kind = AttestationKind.PAIRING,
            attestorDeviceId = evidence.attestorDeviceId,
            sponsorSigningPublicKey = sponsorSigningPublicKey,
            sponsorWrappingPublicKey = sponsorWrappingPublicKey,
        )
    }

    private fun validateRecoveryManifest(
        context: DeviceDirectoryTrustContext,
        entry: DeviceDirectoryEntryWire,
        manifest: JsonObject,
    ): ParsedAttestation {
        manifest.requireExactKeys(RECOVERY_MANIFEST_KEYS)
        val evidence = entry.attestation
        if (evidence.attestorDeviceId != null) malformed("Recovery attestation must be signed by a recovery key")
        manifest.requireString("instanceId", context.instanceId)
        manifest.requireString("userId", entry.userId)
        val challengeId = manifest.requireUuid("challengeId")
        manifest.requireSha256("challengeCommitment")
        val device = manifest.requireObject("device")
        device.requireExactKeys(RECOVERY_DEVICE_KEYS)
        device.requireString("deviceId", entry.deviceId)
        device.requireString("displayName", entry.displayName)
        device.requireString("platform", entry.platform)
        device.requireString("signingPublicKey", entry.signingPublicKey)
        device.requireString("wrappingPublicKey", entry.wrappingPublicKey)
        device.requireSha256("deviceTokenHash")
        val previousRecoverySigningPublicKey = manifest.requireString("previousRecoverySigningPublicKey")
        if (previousRecoverySigningPublicKey != evidence.attestorPublicKey) {
            malformed("Recovery predecessor key conflicts with its attestation")
        }
        decodeKey(previousRecoverySigningPublicKey, PUBLIC_KEY_BYTES, "previous recovery signing key")
        val newRecoverySigningPublicKey = manifest.requireString("newRecoverySigningPublicKey")
        val newRecoveryWrappingPublicKey = manifest.requireString("newRecoveryWrappingPublicKey")
        if (newRecoverySigningPublicKey == previousRecoverySigningPublicKey ||
            newRecoverySigningPublicKey == entry.signingPublicKey
        ) {
            malformed("Recovery successor did not rotate to a distinct signing key")
        }
        val newRecoverySigningKey = decodeKey(
            newRecoverySigningPublicKey,
            PUBLIC_KEY_BYTES,
            "new recovery signing key",
        )
        decodeKey(newRecoveryWrappingPublicKey, PUBLIC_KEY_BYTES, "new recovery wrapping key")
        val recoveryLineageSignature = decodeKey(
            manifest.requireString("replacementRecoveryTrustSignature"),
            SIGNATURE_BYTES,
            "replacement recovery trust signature",
        )
        val recoveryLineageManifest = canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(context.instanceId),
                    "userId" to JsonPrimitive(entry.userId),
                    "challengeId" to JsonPrimitive(challengeId),
                    "deviceId" to JsonPrimitive(entry.deviceId),
                    "deviceSigningPublicKey" to JsonPrimitive(entry.signingPublicKey),
                    "deviceWrappingPublicKey" to JsonPrimitive(entry.wrappingPublicKey),
                    "previousRecoverySigningPublicKey" to JsonPrimitive(previousRecoverySigningPublicKey),
                    "newRecoverySigningPublicKey" to JsonPrimitive(newRecoverySigningPublicKey),
                    "newRecoveryWrappingPublicKey" to JsonPrimitive(newRecoveryWrappingPublicKey),
                ),
            ),
        )
        if (!SodiumSyncPrimitives.verifyEd25519(
                RECOVERY_LINEAGE_DOMAIN + recoveryLineageManifest.encodeToByteArray(),
                recoveryLineageSignature,
                newRecoverySigningKey,
            )
        ) {
            throw DeviceDirectoryTrustException.UntrustedAttestation(
                "Recovery successor did not co-sign its predecessor and recovered device",
            )
        }
        val envelopes = manifest.requireArray("workspaceEnvelopes")
        if (envelopes.isEmpty() || envelopes.size > MAX_WORKSPACES) malformed("Recovery workspace keyring is invalid")
        var previousWorkspace = ""
        var containsWorkspace = false
        envelopes.forEach { element ->
            val envelope = element.asObject("recovery workspace envelope")
            envelope.requireExactKeys(RECOVERY_ENVELOPE_KEYS)
            val workspaceId = envelope.requireUuid("workspaceId")
            if (previousWorkspace.isNotEmpty() && workspaceId <= previousWorkspace) {
                malformed("Recovery workspace keyring is not sorted or contains duplicates")
            }
            previousWorkspace = workspaceId
            containsWorkspace = containsWorkspace || workspaceId == context.workspaceId
            val activeEpoch = envelope.requirePositiveLong("keyEpoch")
            val activeCommitment = envelope.requireString("keyCommitment")
            requireBase64Url(activeCommitment, SHA256_BYTES, "key commitment")
            envelope.requireSha256("deviceWrappedKeyHash")
            requireBase64Url(envelope.requireString("deviceEnvelopeSignature"), SIGNATURE_BYTES, "device envelope signature")
            val recoveryKeyEnvelopes = envelope.requireArray("recoveryKeyEnvelopes")
            if (recoveryKeyEnvelopes.isEmpty() || recoveryKeyEnvelopes.size > MAX_RECOVERY_EPOCHS) {
                malformed("Replacement recovery keyring is invalid")
            }
            var previousEpoch = 0L
            var containsActive = false
            recoveryKeyEnvelopes.forEach { recoveryElement ->
                val recovery = recoveryElement.asObject("replacement recovery key envelope")
                recovery.requireExactKeys(RECOVERY_KEY_ENVELOPE_KEYS)
                val epoch = recovery.requirePositiveLong("keyEpoch")
                if (epoch <= previousEpoch) malformed("Replacement recovery keyring is not sorted or contains duplicates")
                previousEpoch = epoch
                val commitment = recovery.requireString("keyCommitment")
                requireBase64Url(commitment, SHA256_BYTES, "key commitment")
                recovery.requireSha256("recoveryWrappedKeyHash")
                if (epoch == activeEpoch && commitment == activeCommitment) containsActive = true
            }
            if (!containsActive) malformed("Replacement recovery keyring omitted the active epoch")
        }
        if (!containsWorkspace) malformed("Recovery attestation does not cover this workspace")
        return ParsedAttestation(
            kind = AttestationKind.RECOVERY,
            attestorDeviceId = null,
            predecessorRecoverySigningPublicKey = previousRecoverySigningPublicKey,
            successorRecoverySigningPublicKey = newRecoverySigningPublicKey,
        )
    }

    private fun hasTrustPath(
        entry: DeviceDirectoryEntryWire,
        attestation: ParsedAttestation,
        trusted: Map<String, TrustedDeviceAnchor>,
        trustedRecoveryKeys: MutableSet<String>,
        entries: Map<String, DeviceDirectoryEntryWire>,
    ): Boolean {
        trusted[entry.deviceId]?.let {
            requireAnchorMatches(it, entry)
            attestation.authenticatedRecoveryKeys().let(trustedRecoveryKeys::addAll)
            return true
        }
        return when (attestation.kind) {
            AttestationKind.INITIAL -> attestation.initialRecoverySigningPublicKey in trustedRecoveryKeys
            AttestationKind.PAIRING -> {
                val sponsorId = requireNotNull(attestation.attestorDeviceId)
                val sponsor = entries[sponsorId]
                    ?: throw DeviceDirectoryTrustException.UntrustedAttestation(
                        "Pairing sponsor is absent from the full directory",
                    )
                val anchor = trusted[sponsorId] ?: return false
                requireAnchorMatches(anchor, sponsor)
                if (entry.attestation.attestorPublicKey != anchor.signingPublicKey) {
                    throw DeviceDirectoryTrustException.KeySubstitution("Pairing sponsor signing key was substituted")
                }
                if (attestation.sponsorSigningPublicKey != anchor.signingPublicKey ||
                    attestation.sponsorWrappingPublicKey != anchor.wrappingPublicKey
                ) {
                    throw DeviceDirectoryTrustException.KeySubstitution(
                        "Pairing transcript sponsor keys were substituted",
                    )
                }
                true
            }

            AttestationKind.RECOVERY -> {
                val predecessor = requireNotNull(attestation.predecessorRecoverySigningPublicKey)
                val successor = requireNotNull(attestation.successorRecoverySigningPublicKey)
                if (predecessor !in trustedRecoveryKeys && successor !in trustedRecoveryKeys) return false
                trustedRecoveryKeys += predecessor
                trustedRecoveryKeys += successor
                true
            }
        }
    }

    private suspend fun attestationHash(attestation: DeviceEnrollmentAttestationWire): String = encodeBase64Url(
        SodiumSyncPrimitives.sha256(canonicalSyncJson(attestationJson(attestation)).encodeToByteArray()),
    )
}

private data class ParsedAttestation(
    val kind: AttestationKind,
    val attestorDeviceId: String?,
    val sponsorSigningPublicKey: String? = null,
    val sponsorWrappingPublicKey: String? = null,
    val initialRecoverySigningPublicKey: String? = null,
    val predecessorRecoverySigningPublicKey: String? = null,
    val successorRecoverySigningPublicKey: String? = null,
) {
    fun authenticatedRecoveryKeys(): Set<String> = when (kind) {
        AttestationKind.INITIAL -> setOfNotNull(initialRecoverySigningPublicKey)
        AttestationKind.RECOVERY -> setOfNotNull(
            predecessorRecoverySigningPublicKey,
            successorRecoverySigningPublicKey,
        )
        AttestationKind.PAIRING -> emptySet()
    }
}

private enum class AttestationKind { INITIAL, PAIRING, RECOVERY }

private fun validateContext(context: DeviceDirectoryTrustContext) {
    requireUuid(context.instanceId, "instance id")
    requireUuid(context.workspaceId, "workspace id")
    context.trustedDevices.forEach { anchor ->
        requireUuid(anchor.deviceId, "trusted device id")
        decodeKey(anchor.signingPublicKey, PUBLIC_KEY_BYTES, "trusted signing key")
        decodeKey(anchor.wrappingPublicKey, PUBLIC_KEY_BYTES, "trusted wrapping key")
    }
    context.trustedRecoverySigningPublicKeys.forEach {
        decodeKey(it, PUBLIC_KEY_BYTES, "trusted recovery signing key")
    }
}

private fun validateUniqueSortedDevices(devices: List<DeviceDirectoryEntryWire>, allowEmpty: Boolean) {
    if (!allowEmpty && devices.isEmpty()) malformed("Device directory is empty")
    val ids = devices.map(DeviceDirectoryEntryWire::deviceId)
    if (ids.distinct().size != ids.size) malformed("Device directory contains duplicate devices")
    if (ids != ids.sorted()) malformed("Device directory devices are not in canonical order")
}

private fun validateDirectoryEntry(entry: DeviceDirectoryEntryWire) {
    requireUuid(entry.deviceId, "device id")
    requireUuid(entry.userId, "user id")
    if (entry.displayName.isBlank() || entry.displayName.length > 120) malformed("Device display name is invalid")
    if (entry.platform !in PLATFORMS) malformed("Device platform is invalid")
    decodeKey(entry.signingPublicKey, PUBLIC_KEY_BYTES, "device signing key")
    decodeKey(entry.wrappingPublicKey, PUBLIC_KEY_BYTES, "device wrapping key")
    if (entry.status !in DEVICE_STATUSES || entry.authEpoch <= 0 || entry.createdAt < 0) {
        malformed("Device lifecycle metadata is invalid")
    }
    if ((entry.status == "active") != (entry.revokedAt == null) || (entry.revokedAt ?: 0) < 0) {
        malformed("Device revocation metadata is inconsistent")
    }
    if (entry.attestation.createdAt < 0) malformed("Device attestation timestamp is invalid")
}

private fun requirePinnedIdentityUnchanged(
    pin: PinnedDeviceIdentity,
    entry: DeviceDirectoryEntryWire,
    attestationHash: String,
) {
    if (pin.deviceId != entry.deviceId || pin.userId != entry.userId ||
        pin.displayName != entry.displayName || pin.platform != entry.platform ||
        pin.signingPublicKey != entry.signingPublicKey || pin.wrappingPublicKey != entry.wrappingPublicKey ||
        pin.createdAt != entry.createdAt || pin.attestationSha256 != attestationHash
    ) {
        throw DeviceDirectoryTrustException.KeySubstitution("A pinned device identity or attestation changed")
    }
    if (entry.authEpoch < pin.authEpoch || (pin.status == "revoked" && entry.status != "revoked") ||
        (pin.revokedAt != null && pin.revokedAt != entry.revokedAt)
    ) {
        throw DeviceDirectoryTrustException.Rollback("Device revocation or auth epoch rolled back")
    }
}

private fun requireAnchorMatches(anchor: TrustedDeviceAnchor, entry: DeviceDirectoryEntryWire) {
    if (anchor.deviceId != entry.deviceId || anchor.signingPublicKey != entry.signingPublicKey ||
        anchor.wrappingPublicKey != entry.wrappingPublicKey
    ) {
        throw DeviceDirectoryTrustException.KeySubstitution("Device keys conflict with an authenticated anchor")
    }
}

private fun canonicalDirectory(workspaceId: String, directory: DeviceDirectoryWire): String = canonicalSyncJson(
    JsonObject(
        mapOf(
            "workspaceId" to JsonPrimitive(workspaceId),
            "version" to JsonPrimitive(directory.version),
            "devices" to JsonArray(directory.devices.map(::deviceJson)),
        ),
    ),
)

internal suspend fun calculateDeviceDirectoryHash(
    workspaceId: String,
    version: Long,
    devices: List<DeviceDirectoryEntryWire>,
): String {
    SodiumSyncPrimitives.initialize()
    val canonical = canonicalDirectory(
        workspaceId,
        DeviceDirectoryWire(
            version = version,
            hash = "unused",
            allDeviceCount = devices.size,
            devices = devices,
        ),
    )
    return encodeBase64Url(SodiumSyncPrimitives.sha256(canonical.encodeToByteArray()))
}

private fun deviceJson(entry: DeviceDirectoryEntryWire): JsonObject = JsonObject(
    mapOf(
        "deviceId" to JsonPrimitive(entry.deviceId),
        "userId" to JsonPrimitive(entry.userId),
        "displayName" to JsonPrimitive(entry.displayName),
        "platform" to JsonPrimitive(entry.platform),
        "signingPublicKey" to JsonPrimitive(entry.signingPublicKey),
        "wrappingPublicKey" to JsonPrimitive(entry.wrappingPublicKey),
        "status" to JsonPrimitive(entry.status),
        "authEpoch" to JsonPrimitive(entry.authEpoch),
        "createdAt" to JsonPrimitive(entry.createdAt),
        "revokedAt" to (entry.revokedAt?.let(::JsonPrimitive) ?: JsonNull),
        "attestation" to attestationJson(entry.attestation),
    ),
)

private fun attestationJson(attestation: DeviceEnrollmentAttestationWire): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive(attestation.type),
        "workspaceId" to JsonPrimitive(attestation.workspaceId),
        "attestorDeviceId" to (attestation.attestorDeviceId?.let(::JsonPrimitive) ?: JsonNull),
        "attestorPublicKey" to JsonPrimitive(attestation.attestorPublicKey),
        "signatureDomain" to JsonPrimitive(attestation.signatureDomain),
        "manifestJson" to JsonPrimitive(attestation.manifestJson),
        "signature" to JsonPrimitive(attestation.signature),
        "createdAt" to JsonPrimitive(attestation.createdAt),
    ),
)

private fun JsonObject.requireExactKeys(expected: Set<String>) {
    if (keys != expected) malformed("Signed manifest fields do not match the protocol")
}

private fun JsonObject.requireString(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: malformed("Signed manifest field '$name' is not a string")

private fun JsonObject.requireString(name: String, expected: String) {
    if (requireString(name) != expected) malformed("Signed manifest field '$name' has an unexpected value")
}

private fun JsonObject.requireLong(name: String): Long =
    (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull
        ?: malformed("Signed manifest field '$name' is not an integer")

private fun JsonObject.requireLong(name: String, expected: Long) {
    if (requireLong(name) != expected) malformed("Signed manifest field '$name' has an unexpected value")
}

private fun JsonObject.requirePositiveLong(name: String): Long = requireLong(name).also {
    if (it <= 0) malformed("Signed manifest field '$name' is not positive")
}

private fun JsonObject.requireObject(name: String): JsonObject =
    this[name] as? JsonObject ?: malformed("Signed manifest field '$name' is not an object")

private fun JsonObject.requireArray(name: String): JsonArray =
    this[name] as? JsonArray ?: malformed("Signed manifest field '$name' is not an array")

private fun JsonObject.requireSha256(name: String) {
    requireSha256(requireString(name), name)
}

private fun JsonObject.requireUuid(name: String): String = requireString(name).also { requireUuid(it, name) }

private fun JsonElement.asObject(label: String): JsonObject =
    this as? JsonObject ?: malformed("Signed $label is not an object")

private fun requireSha256(value: String, label: String) {
    if (!value.isCanonicalSha256()) malformed("Signed $label is not a canonical SHA-256 value")
    requireBase64Url(value, SHA256_BYTES, label)
}

private fun requireBase64Url(value: String, exactBytes: Int, label: String) {
    val decoded = try {
        decodeBase64Url(value)
    } catch (_: Throwable) {
        malformed("Signed $label is not canonical base64url")
    }
    if (decoded.size != exactBytes) malformed("Signed $label has an invalid size")
}

private fun requireMinimumBase64Url(value: String, minimumBytes: Int, label: String) {
    val decoded = try {
        decodeBase64Url(value)
    } catch (_: Throwable) {
        malformed("Signed $label is not canonical base64url")
    }
    if (decoded.size < minimumBytes) malformed("Signed $label is too short")
}

private fun decodeKey(value: String, size: Int, label: String): ByteArray {
    val decoded = try {
        decodeBase64Url(value)
    } catch (_: Throwable) {
        malformed("$label is not canonical base64url")
    }
    if (decoded.size != size) malformed("$label has an invalid size")
    return decoded
}

private fun requireUuid(value: String, label: String) {
    if (!UUID_PATTERN.matches(value)) malformed("$label is not a canonical UUID")
}

private fun malformed(message: String): Nothing = throw DeviceDirectoryTrustException.Malformed(message)

private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val PLATFORMS = setOf("android", "ios", "macos", "windows", "other")
private val DEVICE_STATUSES = setOf("active", "revoked")
private const val MAX_PIN_ATTEMPTS = 4
private const val MAX_MANIFEST_BYTES = 256 * 1024
private const val MAX_KEY_EPOCHS = 64
private const val MAX_WORKSPACES = 25
private const val PUBLIC_KEY_BYTES = 32
private const val SIGNATURE_BYTES = 64
private const val SHA256_BYTES = 32

private val INITIAL_MANIFEST_KEYS = setOf(
    "instanceId", "userId", "workspaceId", "deviceId", "signingPublicKey", "wrappingPublicKey",
    "deviceTokenHash", "keyEpoch", "keyCommitment", "deviceWrappedKeyHash",
    "recoverySigningPublicKey", "recoveryWrappingPublicKey", "recoveryWrappedKeyHash",
    "recoveryDeviceTrustSignature",
)
private val PAIRING_MANIFEST_KEYS = setOf("transcript", "candidateTokenHash", "envelopes", "expiresAt")
private val PAIRING_TRANSCRIPT_KEYS = setOf(
    "pairingId", "workspaceId", "sponsorDeviceId", "sponsorSigningPublicKey",
    "sponsorWrappingPublicKey", "transcriptNonce", "candidateDeviceId",
    "candidateDisplayName", "candidatePlatform", "candidateSigningPublicKey",
    "candidateWrappingPublicKey", "candidateTokenHash", "expiresAt",
)
private val PAIRING_ENVELOPE_KEYS = setOf(
    "keyEpoch", "keyCommitment", "wrappedKeyHash", "envelopeSignature",
)
private val RECOVERY_MANIFEST_KEYS = setOf(
    "instanceId", "userId", "challengeId", "challengeCommitment", "device",
    "previousRecoverySigningPublicKey", "newRecoverySigningPublicKey",
    "newRecoveryWrappingPublicKey", "replacementRecoveryTrustSignature", "workspaceEnvelopes",
)
private val RECOVERY_DEVICE_KEYS = setOf(
    "deviceId", "displayName", "platform", "signingPublicKey", "wrappingPublicKey", "deviceTokenHash",
)
private val RECOVERY_ENVELOPE_KEYS = setOf(
    "workspaceId", "keyEpoch", "keyCommitment", "deviceWrappedKeyHash",
    "deviceEnvelopeSignature", "recoveryKeyEnvelopes",
)
private val RECOVERY_KEY_ENVELOPE_KEYS = setOf(
    "keyEpoch", "keyCommitment", "recoveryWrappedKeyHash",
)
private const val MAX_RECOVERY_EPOCHS = 100
private val INITIAL_DEVICE_RECOVERY_TRUST_DOMAIN =
    "shinsou:initial-device-recovery-trust:v1\u0000".encodeToByteArray()
private val RECOVERY_LINEAGE_DOMAIN = "shinsou:recovery-lineage:v1\u0000".encodeToByteArray()
