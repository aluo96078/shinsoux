package dev.shinsou.kmp.sync.crypto

import dev.shinsou.kmp.sync.v2.BinaryData
import dev.shinsou.kmp.sync.v2.EventSealContext
import dev.shinsou.kmp.sync.v2.HlcTimestamp
import dev.shinsou.kmp.sync.v2.InMemorySyncSecretStore
import dev.shinsou.kmp.sync.v2.PortableSettingPatch
import dev.shinsou.kmp.sync.v2.RemoteCommittedEnvelope
import dev.shinsou.kmp.sync.v2.RemoteCheckpointVerificationException
import dev.shinsou.kmp.sync.v2.RetainedCheckpointDescriptor
import dev.shinsou.kmp.sync.v2.SealEventRequest
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncCheckpointCompression
import dev.shinsou.kmp.sync.v2.SyncCheckpointHeader
import dev.shinsou.kmp.sync.v2.SyncCipherSuite
import dev.shinsou.kmp.sync.v2.SyncEvent
import dev.shinsou.kmp.sync.v2.SyncProvider
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.SyncState
import dev.shinsou.kmp.sync.v2.SyncValue
import dev.shinsou.kmp.sync.v2.SYNC_CHECKPOINT_MAX_UNCOMPRESSED_BYTES
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DeterministicSyncCryptoTest {
    @Test
    fun checkpointAssociatedDataBindsExactCompressionContract() {
        val codec = DeterministicSyncEventCodec()
        val header = SyncCheckpointHeader(
            cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
            nonceBase64Url = "AAECAwQFBgcICQoL",
            instanceId = INSTANCE_ID,
            workspaceId = WORKSPACE_ID,
            checkpointId = CHECKPOINT_ID,
            deviceId = DEVICE_ID,
            throughWorkspaceSeq = 7,
            keyEpoch = 1,
            previousStableCiphertextSha256Base64Url = null,
            compression = SyncCheckpointCompression.LZ4_BLOCK_V1,
            uncompressedSize = 123,
            ciphertextSha256Base64Url = "ciphertext-hash",
        )
        val associatedData = codec.canonicalCheckpointAssociatedData(header)

        assertEquals(
            header,
            codec.decodeCheckpointAssociatedData(associatedData, header.ciphertextSha256Base64Url),
        )
        val fields = DeterministicCbor.decode(associatedData.copyBytes()) as JsonObject
        assertFailsWith<IllegalArgumentException> {
            codec.decodeCheckpointAssociatedData(
                BinaryData.copyOf(
                    DeterministicCbor.encode(
                        JsonObject(fields + ("compression" to JsonPrimitive("UNKNOWN"))),
                    ),
                ),
                header.ciphertextSha256Base64Url,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decodeCheckpointAssociatedData(
                BinaryData.copyOf(
                    DeterministicCbor.encode(
                        JsonObject(
                            fields + (
                                "uncompressedSize" to JsonPrimitive(SYNC_CHECKPOINT_MAX_UNCOMPRESSED_BYTES + 1)
                            ),
                        ),
                    ),
                ),
                header.ciphertextSha256Base64Url,
            )
        }
    }

    @Test
    fun canonicalCborSortsKeysUsesPreferredNumbersAndRejectsAlternateEncoding() {
        val encoded = DeterministicCbor.encode(
            JsonObject(
                linkedMapOf(
                    "z" to JsonPrimitive(1),
                    "aa" to JsonPrimitive(2),
                    "a" to JsonPrimitive(3),
                    "half" to JsonPrimitive(1.5),
                ),
            ),
        )
        assertContentEquals(encoded, DeterministicCbor.encode(DeterministicCbor.decode(encoded)))
        // RFC 8949 map ordering uses encoded key length, then bytewise lexical ordering.
        assertEquals("a4616103617a01626161026468616c66f93e00", encoded.toHex())

        // Same semantic map, but z precedes a: decoder must reject non-deterministic bytes.
        assertFailsWith<IllegalArgumentException> {
            DeterministicCbor.decode("a2617a01616103".hexToBytes())
        }
    }

    @Test
    fun eventAndCheckpointStateHaveStableRoundTrips() {
        val codec = DeterministicSyncEventCodec()
        val event = sampleEvent()
        val first = codec.encodeEvent(event)
        val second = codec.encodeEvent(event)
        assertEquals(first, second)
        assertEquals(event, codec.decodeEvent(first))

        val state = SyncState(
            throughWorkspaceSeq = 4,
            appliedOpIds = setOf("z", "a"),
        )
        val checkpoint = codec.canonicalCheckpointState(state)
        assertEquals(state.normalized(), codec.decodeCheckpointState(checkpoint))
        assertEquals(checkpoint, codec.canonicalCheckpointState(codec.decodeCheckpointState(checkpoint)))
    }

    @Test
    fun eventEnvelopeAuthenticatesHeaderCiphertextAndSender() = runTest {
        SodiumSyncPrimitives.initialize()
        val fixture = fixture()
        val sealer = fixture.crypto.prepareEventSealer(fixture.session, 1)
        val event = sampleEvent()
        val envelope = sealer.seal(
            SealEventRequest(
                context = EventSealContext(
                    fixture.session.instanceId,
                    fixture.session.workspaceId,
                    fixture.session.deviceId,
                ),
                deviceSeq = 1,
                keyEpoch = 1,
                event = event,
                sealedAtMillis = 100,
            ),
        )
        sealer.close()

        val opened = fixture.crypto.openAndVerifyEvent(fixture.session, RemoteCommittedEnvelope(1, envelope))
        assertEquals(event, opened.event)

        val ciphertext = SodiumSyncPrimitives.base64UrlDecode(envelope.ciphertextBase64Url)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        val tampered = envelope.copy(ciphertextBase64Url = SodiumSyncPrimitives.base64UrlEncode(ciphertext))
        assertFailsWith<IllegalArgumentException> {
            fixture.crypto.openAndVerifyEvent(fixture.session, RemoteCommittedEnvelope(1, tampered))
        }

        val unsupportedSuite = envelope.copy(
            header = envelope.header.copy(cipherSuite = SyncCipherSuite.AES_256_GCM),
        )
        val suiteFailure = assertFailsWith<IllegalArgumentException> {
            fixture.crypto.openAndVerifyEvent(fixture.session, RemoteCommittedEnvelope(1, unsupportedSuite))
        }
        kotlin.test.assertTrue(suiteFailure.message.orEmpty().contains("Unsupported event cipher suite"))
    }

    @Test
    fun checkpointRoundTripVerifiesDescriptorAndChain() = runTest {
        SodiumSyncPrimitives.initialize()
        val fixture = fixture()
        val state = SyncState(keyEpoch = 1, throughWorkspaceSeq = 7, appliedOpIds = setOf("op-a"))
        val sealed = fixture.crypto.sealCheckpoint(
            session = fixture.session,
            checkpointId = CHECKPOINT_ID,
            state = state,
            previousStableCiphertextSha256Base64Url = null,
        )
        val descriptor = RetainedCheckpointDescriptor(
            checkpointId = CHECKPOINT_ID,
            throughWorkspaceSeq = 7,
            keyEpoch = 1,
            ciphertextSha256Base64Url = sealed.header.ciphertextSha256Base64Url,
        )
        val opened = fixture.crypto.openAndVerifyCheckpoint(fixture.session, sealed, descriptor)
        assertEquals(state.normalized(), opened.state)
        assertEquals(SyncCheckpointCompression.LZ4_BLOCK_V1, sealed.header.compression)
        assertEquals(opened.canonicalState.size, sealed.header.uncompressedSize)
        assertEquals(
            DeterministicLz4BlockV1.compress(opened.canonicalState.copyBytes()).size + 16,
            SodiumSyncPrimitives.base64UrlDecode(sealed.ciphertextBase64Url).size,
        )

        assertFailsWith<RemoteCheckpointVerificationException> {
            fixture.crypto.openAndVerifyCheckpoint(
                fixture.session,
                sealed,
                descriptor.copy(ciphertextSha256Base64Url = "wrong"),
            )
        }
        assertFailsWith<RemoteCheckpointVerificationException> {
            fixture.crypto.openAndVerifyCheckpoint(
                fixture.session,
                sealed.copy(header = sealed.header.copy(uncompressedSize = sealed.header.uncompressedSize + 1)),
                descriptor,
            )
        }
        assertFailsWith<RemoteCheckpointVerificationException> {
            fixture.crypto.openAndVerifyCheckpoint(
                fixture.session,
                sealed.copy(header = sealed.header.copy(cipherSuite = SyncCipherSuite.AES_256_GCM)),
                descriptor,
            )
        }
        assertFailsWith<RemoteCheckpointVerificationException> {
            fixture.crypto.openAndVerifyCheckpoint(
                fixture.session,
                sealed.copy(header = sealed.header.copy(protocolVersion = 2)),
                descriptor,
            )
        }
        assertFailsWith<RemoteCheckpointVerificationException> {
            fixture.crypto.openAndVerifyCheckpoint(
                fixture.session,
                sealed.copy(header = sealed.header.copy(envelopeVersion = 2)),
                descriptor,
            )
        }
    }

    @Test
    fun checkpointHeaderRejectsOversizeUncompressedPayload() = runTest {
        SodiumSyncPrimitives.initialize()
        val fixture = fixture()
        val state = SyncState(keyEpoch = 1)
        val sealed = fixture.crypto.sealCheckpoint(fixture.session, CHECKPOINT_ID, state, null)

        assertFailsWith<IllegalArgumentException> {
            sealed.header.copy(uncompressedSize = SYNC_CHECKPOINT_MAX_UNCOMPRESSED_BYTES + 1)
        }
    }

    @Test
    fun freshEpochKeysAreIndependent() = runTest {
        SodiumSyncPrimitives.initialize()
        val fixture = fixture()
        val first = fixture.crypto.generateWorkspaceEpochKey()
        val second = fixture.crypto.generateWorkspaceEpochKey()
        assertNotEquals(first, second)
        assertNotEquals(fixture.crypto.keyCommitment(first), fixture.crypto.keyCommitment(second))
    }

    private suspend fun fixture(): Fixture {
        val signer = SodiumSyncPrimitives.generateEd25519KeyPair()
        val workspaceKey = SodiumSyncPrimitives.randomBytes(32)
        val secrets = InMemorySyncSecretStore().apply {
            write(SyncSecretKey.DeviceSigningPrivateKey, SecretMaterial(signer.privateKey.asList()))
            write(SyncSecretKey.WorkspaceEpochKey(WORKSPACE_ID, 1), SecretMaterial(workspaceKey.asList()))
        }
        val resolver = dev.shinsou.kmp.sync.crypto.InMemorySyncDevicePublicKeyResolver(
            mapOf(DEVICE_ID to BinaryData.copyOf(signer.publicKey)),
        )
        val codec = DeterministicSyncEventCodec()
        return Fixture(
            session = SyncSession(
                endpoint = "https://sync.example.test",
                instanceId = INSTANCE_ID,
                userId = USER_ID,
                workspaceId = WORKSPACE_ID,
                deviceId = DEVICE_ID,
                deviceDisplayName = "Test device",
                platform = "test",
                status = SyncSessionStatus.READY,
                deviceAuthEpoch = 1,
                membershipAuthEpoch = 1,
                activeKeyEpoch = 1,
                provider = SyncProvider.CLOUDFLARE_V2,
            ),
            crypto = SodiumSyncCrypto(secrets, codec, resolver),
        )
    }

    private fun sampleEvent(): SyncEvent = SyncEvent(
        opId = "55555555-5555-4555-8555-555555555555",
        hlc = HlcTimestamp(1234, 2, DEVICE_ID),
        mutations = listOf(
            PortableSettingPatch(
                mapOf(
                    "general.dateFormat" to SyncValue.StringValue("yyyy-MM-dd"),
                    "reader.webtoonSidePadding" to SyncValue.LongValue(16),
                ),
            ),
        ),
    )

    private data class Fixture(val session: SyncSession, val crypto: SodiumSyncCrypto)

    private companion object {
        const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
        const val USER_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val CHECKPOINT_ID = "66666666-6666-4666-8666-666666666666"
    }
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
