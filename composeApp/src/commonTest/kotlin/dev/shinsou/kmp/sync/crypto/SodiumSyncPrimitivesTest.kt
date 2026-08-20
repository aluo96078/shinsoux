@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shinsou.kmp.sync.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SodiumSyncPrimitivesTest {
    @Test
    fun rfc5869HkdfSha256Vector() = runTest {
        SodiumSyncPrimitives.initialize()

        val output = SodiumSyncPrimitives.hkdfSha256(
            inputKeyMaterial = hex("0b".repeat(22)),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            outputSize = 42,
        )

        assertContentEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"),
            output,
        )
    }

    @Test
    fun signsEncryptsAndWrapsAcrossIndependentKeyPairs() = runTest {
        SodiumSyncPrimitives.initialize()
        val signer = SodiumSyncPrimitives.generateEd25519KeyPair()
        val recipient = SodiumSyncPrimitives.generateX25519KeyPair()
        val message = "encrypted event".encodeToByteArray()
        val signature = SodiumSyncPrimitives.signEd25519(message, signer.privateKey)
        assertTrue(SodiumSyncPrimitives.verifyEd25519(message, signature, signer.publicKey))
        assertFalse(
            SodiumSyncPrimitives.verifyEd25519(
                "different".encodeToByteArray(),
                signature,
                signer.publicKey,
            ),
        )

        val workspaceKey = SodiumSyncPrimitives.randomBytes(32)
        val context = "workspace/epoch/1".encodeToByteArray()
        val envelope = SodiumSyncPrimitives.wrapKey(workspaceKey, recipient.publicKey, context)
        assertContentEquals(
            workspaceKey,
            SodiumSyncPrimitives.unwrapKey(envelope, recipient.privateKey, recipient.publicKey, context),
        )
    }

    @Test
    fun aeadAuthenticatesHeaderAndCiphertext() = runTest {
        SodiumSyncPrimitives.initialize()
        val key = SodiumSyncPrimitives.randomBytes(32)
        val nonce = SodiumSyncPrimitives.randomBytes(12)
        val header = "canonical-cbor-header".encodeToByteArray()
        val ciphertext = SodiumSyncPrimitives.aeadEncrypt("payload".encodeToByteArray(), key, nonce, header)

        assertContentEquals(
            "payload".encodeToByteArray(),
            SodiumSyncPrimitives.aeadDecrypt(ciphertext, key, nonce, header),
        )
        assertTrue(
            runCatching {
                SodiumSyncPrimitives.aeadDecrypt(ciphertext, key, nonce, "other-header".encodeToByteArray())
            }.isFailure,
        )
    }

    @Test
    fun rfc8439ChaCha20Poly1305KnownAnswerVector() = runTest {
        SodiumSyncPrimitives.initialize()
        val key = hex(
            "808182838485868788898a8b8c8d8e8f" +
                "909192939495969798999a9b9c9d9e9f",
        )
        val nonce = hex("070000004041424344454647")
        val associatedData = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = hex(
            "4c616469657320616e642047656e746c" +
                "656d656e206f662074686520636c6173" +
                "73206f66202739393a20496620492063" +
                "6f756c64206f6666657220796f75206f" +
                "6e6c79206f6e652074697020666f7220" +
                "746865206675747572652c2073756e73" +
                "637265656e20776f756c642062652069" +
                "742e",
        )
        val expectedCiphertextAndTag = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2" +
                "a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b" +
                "1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58" +
                "fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691",
        )

        val encrypted = SodiumSyncPrimitives.aeadEncrypt(plaintext, key, nonce, associatedData)

        assertContentEquals(expectedCiphertextAndTag, encrypted)
        assertContentEquals(
            plaintext,
            SodiumSyncPrimitives.aeadDecrypt(expectedCiphertextAndTag, key, nonce, associatedData),
        )
    }

    @Test
    fun rfc8032Ed25519KnownAnswerVector() = runTest {
        SodiumSyncPrimitives.initialize()
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val privateKey = seed + publicKey
        val expectedSignature = hex(
            "e5564300c360ac729086e2cc806e828a" +
                "84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46b" +
                "d25bf5f0595bbe24655141438e7a100b",
        )

        val signature = SodiumSyncPrimitives.signEd25519(ByteArray(0), privateKey)

        assertContentEquals(publicKey, SodiumSyncPrimitives.ed25519PublicKey(privateKey))
        assertContentEquals(expectedSignature, signature)
        assertTrue(SodiumSyncPrimitives.verifyEd25519(ByteArray(0), expectedSignature, publicKey))
    }
}

private fun hex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
