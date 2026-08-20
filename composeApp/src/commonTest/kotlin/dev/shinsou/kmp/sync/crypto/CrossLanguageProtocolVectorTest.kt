package dev.shinsou.kmp.sync.crypto

import dev.shinsou.kmp.sync.trust.canonicalSyncJson
import dev.shinsou.kmp.sync.v2.SyncCheckpointCompression
import dev.shinsou.kmp.sync.v2.SyncCheckpointHeader
import dev.shinsou.kmp.sync.v2.SyncCipherSuite
import dev.shinsou.kmp.sync.v2.SyncEventHeader
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The same fixture is asserted by syncWorker/test/cross-language-vector.test.ts. */
class CrossLanguageProtocolVectorTest {
    @Test
    fun recoveryTrustManifestsMatchWorkerCanonicalJsonVectors() {
        val initialTrust = canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(INSTANCE_ID),
                    "userId" to JsonPrimitive(USER_ID),
                    "workspaceId" to JsonPrimitive(WORKSPACE_ID),
                    "deviceId" to JsonPrimitive(DEVICE_ID),
                    "signingPublicKey" to JsonPrimitive(DEVICE_SIGNING_KEY),
                    "wrappingPublicKey" to JsonPrimitive(DEVICE_WRAPPING_KEY),
                    "recoverySigningPublicKey" to JsonPrimitive(PREVIOUS_RECOVERY_SIGNING_KEY),
                    "recoveryWrappingPublicKey" to JsonPrimitive(RECOVERY_WRAPPING_KEY),
                ),
            ),
        )
        val lineage = canonicalSyncJson(
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive(INSTANCE_ID),
                    "userId" to JsonPrimitive(USER_ID),
                    "challengeId" to JsonPrimitive(RECOVERY_CHALLENGE_ID),
                    "deviceId" to JsonPrimitive(DEVICE_ID),
                    "deviceSigningPublicKey" to JsonPrimitive(DEVICE_SIGNING_KEY),
                    "deviceWrappingPublicKey" to JsonPrimitive(DEVICE_WRAPPING_KEY),
                    "previousRecoverySigningPublicKey" to JsonPrimitive(PREVIOUS_RECOVERY_SIGNING_KEY),
                    "newRecoverySigningPublicKey" to JsonPrimitive(NEXT_RECOVERY_SIGNING_KEY),
                    "newRecoveryWrappingPublicKey" to JsonPrimitive(RECOVERY_WRAPPING_KEY),
                ),
            ),
        )

        assertEquals(INITIAL_RECOVERY_TRUST_CANONICAL_JSON, initialTrust)
        assertEquals(RECOVERY_LINEAGE_CANONICAL_JSON, lineage)
    }

    @Test
    fun checkpointHeaderMatchesWorkerCanonicalCborBytes() {
        val codec = DeterministicSyncEventCodec()
        val header = SyncCheckpointHeader(
            envelopeVersion = 1,
            protocolVersion = 1,
            schemaVersion = 1,
            cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
            nonceBase64Url = "AAAAAAAAAAAAAAAA",
            instanceId = "11111111-1111-4111-8111-111111111111",
            workspaceId = "33333333-3333-4333-8333-333333333333",
            checkpointId = "66666666-6666-4666-8666-666666666666",
            deviceId = "44444444-4444-4444-8444-444444444444",
            throughWorkspaceSeq = 42,
            keyEpoch = 3,
            previousStableCiphertextSha256Base64Url = null,
            compression = SyncCheckpointCompression.LZ4_BLOCK_V1,
            uncompressedSize = 1_024,
            ciphertextSha256Base64Url = "not-part-of-associated-data",
        )

        val encoded = codec.canonicalCheckpointAssociatedData(header)

        assertEquals(CHECKPOINT_HEADER_HEX, encoded.copyBytes().toHex())
        assertEquals(
            header.copy(ciphertextSha256Base64Url = "transported-ciphertext-hash"),
            codec.decodeCheckpointAssociatedData(encoded, "transported-ciphertext-hash"),
        )
    }

    @Test
    fun envelopeSignaturesAndDomainBytesMatchWorkerFixedVectors() = runTest {
        SodiumSyncPrimitives.initialize()
        val codec = DeterministicSyncEventCodec()
        val publicKey = PUBLIC_KEY_HEX.hexToBytes()
        val privateKey = PRIVATE_KEY_SEED_HEX.hexToBytes() + publicKey
        assertContentEquals(publicKey, SodiumSyncPrimitives.ed25519PublicKey(privateKey))

        val eventHeader = SyncEventHeader(
            envelopeVersion = 1,
            protocolVersion = 1,
            schemaVersion = 1,
            cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
            nonceBase64Url = "AAECAwQFBgcICQoL",
            instanceId = INSTANCE_ID,
            workspaceId = WORKSPACE_ID,
            eventId = EVENT_ID,
            deviceId = DEVICE_ID,
            deviceSeq = 1,
            keyEpoch = 3,
            ciphertextSha256Base64Url = EVENT_CIPHERTEXT_SHA256_BASE64_URL,
        )
        val checkpointHeader = SyncCheckpointHeader(
            envelopeVersion = 1,
            protocolVersion = 1,
            schemaVersion = 1,
            cipherSuite = SyncCipherSuite.CHACHA20_POLY1305,
            nonceBase64Url = "AAAAAAAAAAAAAAAA",
            instanceId = INSTANCE_ID,
            workspaceId = WORKSPACE_ID,
            checkpointId = CHECKPOINT_ID,
            deviceId = DEVICE_ID,
            throughWorkspaceSeq = 42,
            keyEpoch = 3,
            previousStableCiphertextSha256Base64Url = null,
            compression = SyncCheckpointCompression.LZ4_BLOCK_V1,
            uncompressedSize = 1_024,
            ciphertextSha256Base64Url = CHECKPOINT_CIPHERTEXT_SHA256_BASE64_URL,
        )

        assertEnvelopeVector(
            domainHex = EVENT_ENVELOPE_DOMAIN_HEX,
            canonicalHeader = codec.canonicalEventAssociatedData(eventHeader).copyBytes(),
            expectedHeaderHex = EVENT_HEADER_HEX,
            ciphertextHex = EVENT_CIPHERTEXT_HEX,
            expectedHashHex = EVENT_CIPHERTEXT_SHA256_HEX,
            expectedSignatureBase64Url = EVENT_SIGNATURE_BASE64_URL,
            privateKey = privateKey,
            publicKey = publicKey,
        )
        assertEnvelopeVector(
            domainHex = CHECKPOINT_ENVELOPE_DOMAIN_HEX,
            canonicalHeader = codec.canonicalCheckpointAssociatedData(checkpointHeader).copyBytes(),
            expectedHeaderHex = CHECKPOINT_HEADER_HEX,
            ciphertextHex = CHECKPOINT_CIPHERTEXT_HEX,
            expectedHashHex = CHECKPOINT_CIPHERTEXT_SHA256_HEX,
            expectedSignatureBase64Url = CHECKPOINT_SIGNATURE_BASE64_URL,
            privateKey = privateKey,
            publicKey = publicKey,
        )
        privateKey.fill(0)
    }

    private fun assertEnvelopeVector(
        domainHex: String,
        canonicalHeader: ByteArray,
        expectedHeaderHex: String,
        ciphertextHex: String,
        expectedHashHex: String,
        expectedSignatureBase64Url: String,
        privateKey: ByteArray,
        publicKey: ByteArray,
    ) {
        val ciphertext = ciphertextHex.hexToBytes()
        val hash = SodiumSyncPrimitives.sha256(ciphertext)
        val message = domainHex.hexToBytes() + canonicalHeader + hash

        assertEquals(expectedHeaderHex, canonicalHeader.toHex())
        assertEquals(expectedHashHex, hash.toHex())
        assertContentEquals(
            (domainHex + expectedHeaderHex + expectedHashHex).hexToBytes(),
            message,
        )
        val signature = SodiumSyncPrimitives.signEd25519(message, privateKey)
        assertEquals(expectedSignatureBase64Url, SodiumSyncPrimitives.base64UrlEncode(signature))
        assertTrue(SodiumSyncPrimitives.verifyEd25519(message, signature, publicKey))
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private const val CHECKPOINT_HEADER_HEX =
    "af656e6f6e63657041414141414141414141414141414141686465766963654964782434343434343434342d343434342d" +
        "343434342d383434342d343434343434343434343434686b657945706f6368036a696e7374616e63654964782431313131" +
        "313131312d313131312d343131312d383131312d3131313131313131313131316b63697068657253756974657143484143" +
        "484132305f504f4c59313330356b636f6d7072657373696f6e6c4c5a345f424c4f434b5f56316b7374617465466f726d" +
        "61746d73796e632d73746174652d76316b776f726b73706163654964782433333333333333332d333333332d343333332d" +
        "383333332d3333333333333333333333336c636865636b706f696e744964782436363636363636362d363636362d343636" +
        "362d383636362d3636363636363636363636366d736368656d6156657273696f6e016f656e76656c6f706556657273696f" +
        "6e016f70726f746f636f6c56657273696f6e0170756e636f6d7072657373656453697a65190400737468726f756768576f" +
        "726b7370616365536571182a781c70726576696f7573537461626c65436865636b706f696e744861736860"

private const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
private const val USER_ID = "22222222-2222-4222-8222-222222222222"
private const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
private const val DEVICE_ID = "44444444-4444-4444-8444-444444444444"
private const val RECOVERY_CHALLENGE_ID = "77777777-7777-4777-8777-777777777777"
private const val DEVICE_SIGNING_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
private const val DEVICE_WRAPPING_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
private const val PREVIOUS_RECOVERY_SIGNING_KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI"
private const val NEXT_RECOVERY_SIGNING_KEY = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM"
private const val RECOVERY_WRAPPING_KEY = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ"
private const val INITIAL_RECOVERY_TRUST_CANONICAL_JSON =
    "{\"deviceId\":\"$DEVICE_ID\",\"instanceId\":\"$INSTANCE_ID\"," +
        "\"recoverySigningPublicKey\":\"$PREVIOUS_RECOVERY_SIGNING_KEY\"," +
        "\"recoveryWrappingPublicKey\":\"$RECOVERY_WRAPPING_KEY\"," +
        "\"signingPublicKey\":\"$DEVICE_SIGNING_KEY\",\"userId\":\"$USER_ID\"," +
        "\"workspaceId\":\"$WORKSPACE_ID\",\"wrappingPublicKey\":\"$DEVICE_WRAPPING_KEY\"}"
private const val RECOVERY_LINEAGE_CANONICAL_JSON =
    "{\"challengeId\":\"$RECOVERY_CHALLENGE_ID\",\"deviceId\":\"$DEVICE_ID\"," +
        "\"deviceSigningPublicKey\":\"$DEVICE_SIGNING_KEY\"," +
        "\"deviceWrappingPublicKey\":\"$DEVICE_WRAPPING_KEY\",\"instanceId\":\"$INSTANCE_ID\"," +
        "\"newRecoverySigningPublicKey\":\"$NEXT_RECOVERY_SIGNING_KEY\"," +
        "\"newRecoveryWrappingPublicKey\":\"$RECOVERY_WRAPPING_KEY\"," +
        "\"previousRecoverySigningPublicKey\":\"$PREVIOUS_RECOVERY_SIGNING_KEY\"," +
        "\"userId\":\"$USER_ID\"}"
private const val EVENT_ID = "55555555-5555-4555-8555-555555555555"
private const val CHECKPOINT_ID = "66666666-6666-4666-8666-666666666666"

private const val PRIVATE_KEY_SEED_HEX =
    "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
private const val PUBLIC_KEY_HEX =
    "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

private const val EVENT_ENVELOPE_DOMAIN_HEX =
    "7368696e736f753a6576656e742d656e76656c6f70653a763100"
private const val CHECKPOINT_ENVELOPE_DOMAIN_HEX =
    "7368696e736f753a636865636b706f696e742d656e76656c6f70653a763100"

private const val EVENT_CIPHERTEXT_HEX =
    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
private const val EVENT_CIPHERTEXT_SHA256_HEX =
    "630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd"
private const val EVENT_CIPHERTEXT_SHA256_BASE64_URL =
    "Yw3NKWbEM2aRElRIu7JbT_QSpJxzLbLIq8G4WBvXEN0"
private const val EVENT_SIGNATURE_BASE64_URL =
    "QYN_YY-ikZTBPoDT-BsRebwhCYMVgAr9IykNFb4_Owk5bV3XKmiCbiY0XDo1nWc-YIheROBT80T_CngcAv_-DQ"

private const val CHECKPOINT_CIPHERTEXT_HEX =
    "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
        "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf"
private const val CHECKPOINT_CIPHERTEXT_SHA256_HEX =
    "1fba3652b85f13ce1e7ff5eb1971ae6519afd809a0578cb3285daecd3e16b216"
private const val CHECKPOINT_CIPHERTEXT_SHA256_BASE64_URL =
    "H7o2UrhfE84ef_XrGXGuZRmv2AmgV4yzKF2uzT4WshY"
private const val CHECKPOINT_SIGNATURE_BASE64_URL =
    "Wcl5eV6h9mSAZVRaqHv_RGVBi6bLPf9raVJBHxsPT8yRAKpPqp0vMhidS3S1H3j8TT7UjdKdcm7J8FDOUUbvCQ"

private const val EVENT_HEADER_HEX =
    "ab656e6f6e63657041414543417751464267634943516f4c676576656e744964782435353535353535352d" +
        "353535352d343535352d383535352d353535353535353535353535686465766963654964782434343434" +
        "343434342d343434342d343434342d383434342d343434343434343434343434686b657945706f636803" +
        "69646576696365536571016a696e7374616e63654964782431313131313131312d313131312d343131312d" +
        "383131312d3131313131313131313131316b63697068657253756974657143484143484132305f504f4c59" +
        "313330356b776f726b73706163654964782433333333333333332d333333332d343333332d383333332d33" +
        "33333333333333333333336d736368656d6156657273696f6e016f656e76656c6f706556657273696f6e" +
        "016f70726f746f636f6c56657273696f6e01"

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
