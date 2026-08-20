package dev.shinsou.kmp.sync.trust

import dev.shinsou.kmp.sync.crypto.SodiumKeyPair
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.network.encodeBase64Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class DeviceDirectoryVerifierTest {
    @Test
    fun directoryHashMatchesWorkerCanonicalJsonVector() = runTest {
        val entry = DeviceDirectoryEntryWire(
            deviceId = DEVICE_A,
            userId = USER,
            displayName = "Phone",
            platform = "ios",
            signingPublicKey = "A".repeat(43),
            wrappingPublicKey = "Q".repeat(43),
            status = "active",
            authEpoch = 1,
            createdAt = 1_000,
            attestation = DeviceEnrollmentAttestationWire(
                type = "initial",
                workspaceId = WORKSPACE,
                attestorDeviceId = DEVICE_A,
                attestorPublicKey = "A".repeat(43),
                signatureDomain = "initial-workspace-claim",
                manifestJson = "{}",
                signature = "A".repeat(86),
                createdAt = 1_000,
            ),
        )
        assertEquals(
            "04bdB07RAIHel3_86kThKQY4bvrojpHGambqI7s6OOw",
            calculateDeviceDirectoryHash(WORKSPACE, 1, listOf(entry)),
        )
    }

    @Test
    fun initialSelfSignatureNeedsOutOfBandAnchorAndPinsImmutableKeys() = runTest {
        val fixture = initialDevice()
        val store = InMemoryDeviceDirectoryPinStore()
        val verifier = DeviceDirectoryVerifier(store)
        val wire = directory(version = 1, fixture.entry)

        assertFailsWith<DeviceDirectoryTrustException.UntrustedAttestation> {
            verifier.verifyAndPinFullDirectory(context(), wire)
        }

        val pinned = verifier.verifyAndPinFullDirectory(
            context(
                trustedDevices = listOf(
                    TrustedDeviceAnchor(
                        fixture.entry.deviceId,
                        fixture.entry.signingPublicKey,
                        fixture.entry.wrappingPublicKey,
                    ),
                ),
            ),
            wire,
        )
        assertEquals(wire.hash, pinned.hash)
        assertEquals(fixture.entry.signingPublicKey, pinned.device(DEVICE_A)?.signingPublicKey)

        val resolver = PinnedDevicePublicKeyResolver(store, WORKSPACE)
        assertEquals(fixture.signing.publicKey.toList(), resolver.signingPublicKey(DEVICE_A)?.copyBytes()?.toList())
    }

    @Test
    fun missingOrForgedInitialRecoveryCoSignatureFailsClosedEvenWithValidDeviceSelfSignature() = runTest {
        val fixture = initialDevice()
        val parsed = Json.parseToJsonElement(fixture.entry.attestation.manifestJson).jsonObject
        val trusted = context(
            trustedDevices = listOf(
                TrustedDeviceAnchor(
                    DEVICE_A,
                    fixture.entry.signingPublicKey,
                    fixture.entry.wrappingPublicKey,
                ),
            ),
        )
        val verifier = DeviceDirectoryVerifier(InMemoryDeviceDirectoryPinStore())

        val missingManifest = canonicalSyncJson(
            JsonObject(parsed.filterKeys { it != "recoveryDeviceTrustSignature" }),
        )
        val missing = fixture.entry.copy(
            attestation = fixture.entry.attestation.copy(
                manifestJson = missingManifest,
                signature = sign("initial-workspace-claim", missingManifest, fixture.signing.privateKey),
            ),
        )
        assertFailsWith<DeviceDirectoryTrustException.Malformed> {
            verifier.verifyAndPinFullDirectory(trusted, directory(1, missing))
        }

        val forgedManifest = canonicalSyncJson(
            JsonObject(
                parsed + ("recoveryDeviceTrustSignature" to JsonPrimitive(
                    encodeBase64Url(ByteArray(64)),
                )),
            ),
        )
        val forged = fixture.entry.copy(
            attestation = fixture.entry.attestation.copy(
                manifestJson = forgedManifest,
                signature = sign("initial-workspace-claim", forgedManifest, fixture.signing.privateKey),
            ),
        )
        assertFailsWith<DeviceDirectoryTrustException.UntrustedAttestation> {
            verifier.verifyAndPinFullDirectory(trusted, directory(1, forged))
        }
    }

    @Test
    fun pairingEnrollmentChainsToPreviouslyPinnedSponsor() = runTest {
        val sponsor = initialDevice()
        val store = InMemoryDeviceDirectoryPinStore()
        val verifier = DeviceDirectoryVerifier(store)
        verifier.verifyAndPinFullDirectory(
            context(
                trustedDevices = listOf(
                    TrustedDeviceAnchor(DEVICE_A, sponsor.entry.signingPublicKey, sponsor.entry.wrappingPublicKey),
                ),
            ),
            directory(1, sponsor.entry),
        )

        val candidate = pairingDevice(sponsor)
        val next = verifier.verifyAndPinFullDirectory(context(), directory(2, sponsor.entry, candidate.entry))
        assertEquals(candidate.entry.signingPublicKey, next.device(DEVICE_B)?.signingPublicKey)
    }

    @Test
    fun pairingTranscriptCannotSubstituteSponsorSigningOrWrappingKey() = runTest {
        val sponsor = initialDevice()
        val store = InMemoryDeviceDirectoryPinStore()
        val verifier = DeviceDirectoryVerifier(store)
        verifier.verifyAndPinFullDirectory(
            context(
                trustedDevices = listOf(
                    TrustedDeviceAnchor(DEVICE_A, sponsor.entry.signingPublicKey, sponsor.entry.wrappingPublicKey),
                ),
            ),
            directory(1, sponsor.entry),
        )
        val substitutedSigning = encodeBase64Url(SodiumSyncPrimitives.generateEd25519KeyPair().publicKey)
        val signingCandidate = pairingDevice(sponsor, sponsorSigningPublicKey = substitutedSigning)
        assertFailsWith<DeviceDirectoryTrustException.Malformed> {
            verifier.verifyAndPinFullDirectory(context(), directory(2, sponsor.entry, signingCandidate.entry))
        }

        val substitutedWrapping = encodeBase64Url(SodiumSyncPrimitives.generateX25519KeyPair().publicKey)
        val wrappingCandidate = pairingDevice(sponsor, sponsorWrappingPublicKey = substitutedWrapping)
        assertFailsWith<DeviceDirectoryTrustException.KeySubstitution> {
            verifier.verifyAndPinFullDirectory(context(), directory(2, sponsor.entry, wrappingCandidate.entry))
        }
    }

    @Test
    fun recoveryEnrollmentChainsOnlyToImportedRecoveryKey() = runTest {
        SodiumSyncPrimitives.initialize()
        val recoverySigning = SodiumSyncPrimitives.generateEd25519KeyPair()
        val recovered = recoveryDevice(recoverySigning)
        val verifier = DeviceDirectoryVerifier(InMemoryDeviceDirectoryPinStore())
        val wire = directory(1, recovered.entry)

        assertFailsWith<DeviceDirectoryTrustException.UntrustedAttestation> {
            verifier.verifyAndPinFullDirectory(context(), wire)
        }
        val pinned = verifier.verifyAndPinFullDirectory(
            context(trustedRecoveryKeys = setOf(encodeBase64Url(recoverySigning.publicKey))),
            wire,
        )
        assertNotNull(pinned.device(DEVICE_B))
    }

    @Test
    fun recoveryBootstrapPinsRevokedInitialSenderAndKeepsItsHistoricalSigningKey() = runTest {
        SodiumSyncPrimitives.initialize()
        val oldRecoverySigning = SodiumSyncPrimitives.generateEd25519KeyPair()
        val initial = initialDevice(oldRecoverySigning)
        val recovered = recoveryDevice(oldRecoverySigning)
        val revokedInitial = initial.entry.copy(
            status = "revoked",
            authEpoch = 2,
            revokedAt = 3_000,
        )
        val store = InMemoryDeviceDirectoryPinStore()
        val pinned = DeviceDirectoryVerifier(store).verifyAndPinFullDirectory(
            context(trustedRecoveryKeys = setOf(encodeBase64Url(oldRecoverySigning.publicKey))),
            directory(2, revokedInitial, recovered.entry),
        )

        assertEquals("revoked", pinned.device(DEVICE_A)?.status)
        assertNotNull(pinned.device(DEVICE_B))
        assertEquals(
            initial.signing.publicKey.toList(),
            PinnedDevicePublicKeyResolver(store, WORKSPACE)
                .signingPublicKey(DEVICE_A)
                ?.copyBytes()
                ?.toList(),
        )
    }

    @Test
    fun successorRecoveryRootTraversesLineageBackToInitialHistoricalSender() = runTest {
        SodiumSyncPrimitives.initialize()
        val oldRecoverySigning = SodiumSyncPrimitives.generateEd25519KeyPair()
        val currentRecoverySigning = SodiumSyncPrimitives.generateEd25519KeyPair()
        val initial = initialDevice(oldRecoverySigning)
        val recovered = recoveryDevice(oldRecoverySigning, currentRecoverySigning)
        val revokedInitial = initial.entry.copy(status = "revoked", authEpoch = 2, revokedAt = 3_000)
        val store = InMemoryDeviceDirectoryPinStore()

        DeviceDirectoryVerifier(store).verifyAndPinFullDirectory(
            context(trustedRecoveryKeys = setOf(encodeBase64Url(currentRecoverySigning.publicKey))),
            directory(2, revokedInitial, recovered.entry),
        )

        assertEquals(
            initial.signing.publicKey.toList(),
            PinnedDevicePublicKeyResolver(store, WORKSPACE)
                .signingPublicKey(DEVICE_A)
                ?.copyBytes()
                ?.toList(),
        )
    }

    @Test
    fun rollbackEquivocationAndUnknownSubsetFailClosed() = runTest {
        val fixture = initialDevice()
        val store = InMemoryDeviceDirectoryPinStore()
        val verifier = DeviceDirectoryVerifier(store)
        val trusted = context(
            trustedDevices = listOf(
                TrustedDeviceAnchor(DEVICE_A, fixture.entry.signingPublicKey, fixture.entry.wrappingPublicKey),
            ),
        )
        val original = directory(2, fixture.entry)
        verifier.verifyAndPinFullDirectory(trusted, original)

        assertFailsWith<DeviceDirectoryTrustException.Rollback> {
            verifier.verifyAndPinFullDirectory(trusted, directory(1, fixture.entry))
        }
        assertFailsWith<DeviceDirectoryTrustException.Equivocation> {
            verifier.verifyAndPinFullDirectory(trusted, original.copy(hash = sha("different")))
        }
        assertFailsWith<DeviceDirectoryTrustException.FullDirectoryRequired> {
            verifier.verifyPinnedSenderSubset(
                WORKSPACE,
                original.copy(version = 3),
                expectedSenderDeviceIds = setOf(DEVICE_A),
            )
        }
        assertFailsWith<DeviceDirectoryTrustException.Malformed> {
            verifier.verifyPinnedSenderSubset(
                WORKSPACE,
                original.copy(devices = emptyList()),
                expectedSenderDeviceIds = setOf(DEVICE_B),
            )
        }
    }

    private suspend fun initialDevice(
        recoverySigning: SodiumKeyPair? = null,
    ): DeviceFixture {
        SodiumSyncPrimitives.initialize()
        val recoverySigningKeys = recoverySigning ?: SodiumSyncPrimitives.generateEd25519KeyPair()
        val signing = SodiumSyncPrimitives.generateEd25519KeyPair()
        val wrapping = SodiumSyncPrimitives.generateX25519KeyPair()
        val recoveryWrapping = SodiumSyncPrimitives.generateX25519KeyPair()
        val signingPublic = encodeBase64Url(signing.publicKey)
        val wrappingPublic = encodeBase64Url(wrapping.publicKey)
        val recoverySigningPublic = encodeBase64Url(recoverySigningKeys.publicKey)
        val recoveryWrappingPublic = encodeBase64Url(recoveryWrapping.publicKey)
        val recoveryTrustManifest = canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(INSTANCE),
                    "userId" to JsonPrimitive(USER),
                    "workspaceId" to JsonPrimitive(WORKSPACE),
                    "deviceId" to JsonPrimitive(DEVICE_A),
                    "signingPublicKey" to JsonPrimitive(signingPublic),
                    "wrappingPublicKey" to JsonPrimitive(wrappingPublic),
                    "recoverySigningPublicKey" to JsonPrimitive(recoverySigningPublic),
                    "recoveryWrappingPublicKey" to JsonPrimitive(recoveryWrappingPublic),
                ),
            ),
        )
        val recoveryTrustSignature = sign(
            "initial-device-recovery-trust",
            recoveryTrustManifest,
            recoverySigningKeys.privateKey,
        )
        val manifest = canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(INSTANCE),
                    "userId" to JsonPrimitive(USER),
                    "workspaceId" to JsonPrimitive(WORKSPACE),
                    "deviceId" to JsonPrimitive(DEVICE_A),
                    "signingPublicKey" to JsonPrimitive(signingPublic),
                    "wrappingPublicKey" to JsonPrimitive(wrappingPublic),
                    "deviceTokenHash" to JsonPrimitive(sha("device-token")),
                    "keyEpoch" to JsonPrimitive(1),
                    "keyCommitment" to JsonPrimitive(sha("epoch-key")),
                    "deviceWrappedKeyHash" to JsonPrimitive(sha("device-envelope")),
                    "recoverySigningPublicKey" to JsonPrimitive(recoverySigningPublic),
                    "recoveryWrappingPublicKey" to JsonPrimitive(recoveryWrappingPublic),
                    "recoveryWrappedKeyHash" to JsonPrimitive(sha("recovery-envelope")),
                    "recoveryDeviceTrustSignature" to JsonPrimitive(recoveryTrustSignature),
                ),
            ),
        )
        val signature = sign("initial-workspace-claim", manifest, signing.privateKey)
        return DeviceFixture(
            signing = signing,
            wrapping = wrapping,
            entry = DeviceDirectoryEntryWire(
                deviceId = DEVICE_A,
                userId = USER,
                displayName = "Phone A",
                platform = "ios",
                signingPublicKey = signingPublic,
                wrappingPublicKey = wrappingPublic,
                status = "active",
                authEpoch = 1,
                createdAt = 1_000,
                attestation = DeviceEnrollmentAttestationWire(
                    type = "initial",
                    workspaceId = WORKSPACE,
                    attestorDeviceId = DEVICE_A,
                    attestorPublicKey = signingPublic,
                    signatureDomain = "initial-workspace-claim",
                    manifestJson = manifest,
                    signature = signature,
                    createdAt = 1_000,
                ),
            ),
        )
    }

    private suspend fun pairingDevice(
        sponsor: DeviceFixture,
        sponsorSigningPublicKey: String = sponsor.entry.signingPublicKey,
        sponsorWrappingPublicKey: String = sponsor.entry.wrappingPublicKey,
    ): DeviceFixture {
        val signing = SodiumSyncPrimitives.generateEd25519KeyPair()
        val wrapping = SodiumSyncPrimitives.generateX25519KeyPair()
        val signingPublic = encodeBase64Url(signing.publicKey)
        val wrappingPublic = encodeBase64Url(wrapping.publicKey)
        val tokenHash = sha("candidate-token")
        val expiresAt = 300_000L
        val transcript = JsonObject(
            mapOf(
                "pairingId" to JsonPrimitive(PAIRING),
                "workspaceId" to JsonPrimitive(WORKSPACE),
                "sponsorDeviceId" to JsonPrimitive(DEVICE_A),
                "sponsorSigningPublicKey" to JsonPrimitive(sponsorSigningPublicKey),
                "sponsorWrappingPublicKey" to JsonPrimitive(sponsorWrappingPublicKey),
                "transcriptNonce" to JsonPrimitive(encodeBase64Url(ByteArray(32) { 9 })),
                "candidateDeviceId" to JsonPrimitive(DEVICE_B),
                "candidateDisplayName" to JsonPrimitive("Phone B"),
                "candidatePlatform" to JsonPrimitive("android"),
                "candidateSigningPublicKey" to JsonPrimitive(signingPublic),
                "candidateWrappingPublicKey" to JsonPrimitive(wrappingPublic),
                "candidateTokenHash" to JsonPrimitive(tokenHash),
                "expiresAt" to JsonPrimitive(expiresAt),
            ),
        )
        val manifest = canonicalSyncJson(
            JsonObject(
                mapOf(
                    "transcript" to transcript,
                    "candidateTokenHash" to JsonPrimitive(tokenHash),
                    "envelopes" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "keyEpoch" to JsonPrimitive(1),
                                    "keyCommitment" to JsonPrimitive(sha("epoch-key")),
                                    "wrappedKeyHash" to JsonPrimitive(sha("pair-envelope")),
                                    "envelopeSignature" to JsonPrimitive(encodeBase64Url(ByteArray(64) { 7 })),
                                ),
                            ),
                        ),
                    ),
                    "expiresAt" to JsonPrimitive(expiresAt),
                ),
            ),
        )
        return DeviceFixture(
            signing,
            wrapping,
            DeviceDirectoryEntryWire(
                deviceId = DEVICE_B,
                userId = USER,
                displayName = "Phone B",
                platform = "android",
                signingPublicKey = signingPublic,
                wrappingPublicKey = wrappingPublic,
                status = "active",
                authEpoch = 1,
                createdAt = 2_000,
                attestation = DeviceEnrollmentAttestationWire(
                    type = "pairing",
                    workspaceId = WORKSPACE,
                    attestorDeviceId = DEVICE_A,
                    attestorPublicKey = sponsor.entry.signingPublicKey,
                    signatureDomain = "pairing-approval",
                    manifestJson = manifest,
                    signature = sign("pairing-approval", manifest, sponsor.signing.privateKey),
                    createdAt = 2_000,
                ),
            ),
        )
    }

    private suspend fun recoveryDevice(
        recoverySigning: SodiumKeyPair,
        nextRecoverySigning: SodiumKeyPair? = null,
    ): DeviceFixture {
        SodiumSyncPrimitives.initialize()
        val nextRecoverySigningKeys = nextRecoverySigning ?: SodiumSyncPrimitives.generateEd25519KeyPair()
        val signing = SodiumSyncPrimitives.generateEd25519KeyPair()
        val wrapping = SodiumSyncPrimitives.generateX25519KeyPair()
        val nextRecoveryWrapping = SodiumSyncPrimitives.generateX25519KeyPair()
        val signingPublic = encodeBase64Url(signing.publicKey)
        val wrappingPublic = encodeBase64Url(wrapping.publicKey)
        val previousRecoverySigningPublic = encodeBase64Url(recoverySigning.publicKey)
        val nextRecoverySigningPublic = encodeBase64Url(nextRecoverySigningKeys.publicKey)
        val nextRecoveryWrappingPublic = encodeBase64Url(nextRecoveryWrapping.publicKey)
        val lineageManifest = canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(INSTANCE),
                    "userId" to JsonPrimitive(USER),
                    "challengeId" to JsonPrimitive(CHALLENGE),
                    "deviceId" to JsonPrimitive(DEVICE_B),
                    "deviceSigningPublicKey" to JsonPrimitive(signingPublic),
                    "deviceWrappingPublicKey" to JsonPrimitive(wrappingPublic),
                    "previousRecoverySigningPublicKey" to JsonPrimitive(previousRecoverySigningPublic),
                    "newRecoverySigningPublicKey" to JsonPrimitive(nextRecoverySigningPublic),
                    "newRecoveryWrappingPublicKey" to JsonPrimitive(nextRecoveryWrappingPublic),
                ),
            ),
        )
        val lineageSignature = sign("recovery-lineage", lineageManifest, nextRecoverySigningKeys.privateKey)
        val manifest = canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(INSTANCE),
                    "userId" to JsonPrimitive(USER),
                    "challengeId" to JsonPrimitive(CHALLENGE),
                    "challengeCommitment" to JsonPrimitive(sha("recovery-challenge")),
                    "device" to JsonObject(
                        mapOf(
                            "deviceId" to JsonPrimitive(DEVICE_B),
                            "displayName" to JsonPrimitive("Recovered Phone"),
                            "platform" to JsonPrimitive("ios"),
                            "signingPublicKey" to JsonPrimitive(signingPublic),
                            "wrappingPublicKey" to JsonPrimitive(wrappingPublic),
                            "deviceTokenHash" to JsonPrimitive(sha("new-token")),
                        ),
                    ),
                    "previousRecoverySigningPublicKey" to JsonPrimitive(previousRecoverySigningPublic),
                    "newRecoverySigningPublicKey" to JsonPrimitive(nextRecoverySigningPublic),
                    "newRecoveryWrappingPublicKey" to JsonPrimitive(nextRecoveryWrappingPublic),
                    "replacementRecoveryTrustSignature" to JsonPrimitive(lineageSignature),
                    "workspaceEnvelopes" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "workspaceId" to JsonPrimitive(WORKSPACE),
                                    "keyEpoch" to JsonPrimitive(1),
                                    "keyCommitment" to JsonPrimitive(sha("epoch-key")),
                                    "deviceWrappedKeyHash" to JsonPrimitive(sha("new-device-envelope")),
                                    "deviceEnvelopeSignature" to JsonPrimitive(encodeBase64Url(ByteArray(64) { 4 })),
                                    "recoveryKeyEnvelopes" to JsonArray(
                                        listOf(
                                            JsonObject(
                                                mapOf(
                                                    "keyEpoch" to JsonPrimitive(1),
                                                    "keyCommitment" to JsonPrimitive(sha("epoch-key")),
                                                    "recoveryWrappedKeyHash" to JsonPrimitive(sha("new-recovery-envelope")),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        return DeviceFixture(
            signing,
            wrapping,
            DeviceDirectoryEntryWire(
                deviceId = DEVICE_B,
                userId = USER,
                displayName = "Recovered Phone",
                platform = "ios",
                signingPublicKey = signingPublic,
                wrappingPublicKey = wrappingPublic,
                status = "active",
                authEpoch = 1,
                createdAt = 3_000,
                attestation = DeviceEnrollmentAttestationWire(
                    type = "recovery",
                    workspaceId = WORKSPACE,
                    attestorPublicKey = encodeBase64Url(recoverySigning.publicKey),
                    signatureDomain = "recovery-claim",
                    manifestJson = manifest,
                    signature = sign("recovery-claim", manifest, recoverySigning.privateKey),
                    createdAt = 3_000,
                ),
            ),
        )
    }

    private suspend fun directory(version: Long, vararg entries: DeviceDirectoryEntryWire): DeviceDirectoryWire {
        val devices = entries.sortedBy(DeviceDirectoryEntryWire::deviceId)
        return DeviceDirectoryWire(
            version = version,
            hash = calculateDeviceDirectoryHash(WORKSPACE, version, devices),
            allDeviceCount = devices.size,
            devices = devices,
        )
    }

    private suspend fun sha(value: String): String {
        SodiumSyncPrimitives.initialize()
        return encodeBase64Url(SodiumSyncPrimitives.sha256(value.encodeToByteArray()))
    }

    private fun sign(domain: String, manifest: String, privateKey: ByteArray): String = encodeBase64Url(
        SodiumSyncPrimitives.signEd25519(
            "shinsou:$domain:v1\u0000".encodeToByteArray() + manifest.encodeToByteArray(),
            privateKey,
        ),
    )

    private fun context(
        trustedDevices: List<TrustedDeviceAnchor> = emptyList(),
        trustedRecoveryKeys: Set<String> = emptySet(),
    ) = DeviceDirectoryTrustContext(
        instanceId = INSTANCE,
        workspaceId = WORKSPACE,
        trustedDevices = trustedDevices,
        trustedRecoverySigningPublicKeys = trustedRecoveryKeys,
    )

    private data class DeviceFixture(
        val signing: SodiumKeyPair,
        val wrapping: SodiumKeyPair,
        val entry: DeviceDirectoryEntryWire,
    )

    private companion object {
        const val INSTANCE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val USER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val WORKSPACE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val DEVICE_A = "11111111-1111-4111-8111-111111111111"
        const val DEVICE_B = "22222222-2222-4222-8222-222222222222"
        const val PAIRING = "33333333-3333-4333-8333-333333333333"
        const val CHALLENGE = "44444444-4444-4444-8444-444444444444"
    }
}
