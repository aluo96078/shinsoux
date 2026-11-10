package dev.shinsou.kmp.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SodiumPrimitivesTest {
    @Test
    fun signsAndVerifiesWithAFreshKeyPair() = runTest {
        SodiumPrimitives.initialize()
        val signer = SodiumPrimitives.generateEd25519KeyPair()
        val message = "signed manifest".encodeToByteArray()
        val signature = SodiumPrimitives.signEd25519(message, signer.privateKey)

        assertTrue(SodiumPrimitives.verifyEd25519(message, signature, signer.publicKey))
        assertFalse(SodiumPrimitives.verifyEd25519("different".encodeToByteArray(), signature, signer.publicKey))
        assertFalse(SodiumPrimitives.verifyEd25519(message, signature.copyOf(63), signer.publicKey))
    }

    @Test
    fun rfc8032Ed25519KnownAnswerVector() = runTest {
        SodiumPrimitives.initialize()
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val expectedSignature = hex(
            "e5564300c360ac729086e2cc806e828a" +
                "84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46b" +
                "d25bf5f0595bbe24655141438e7a100b",
        )

        val signature = SodiumPrimitives.signEd25519(ByteArray(0), seed + publicKey)

        assertContentEquals(expectedSignature, signature)
        assertTrue(SodiumPrimitives.verifyEd25519(ByteArray(0), expectedSignature, publicKey))
    }

    @Test
    fun sha256AndBase64UrlKnownAnswers() = runTest {
        SodiumPrimitives.initialize()

        assertContentEquals(
            hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            SodiumPrimitives.sha256("abc".encodeToByteArray()),
        )
        assertEquals("-_8", SodiumPrimitives.base64UrlEncode(byteArrayOf(-5, -1)))
        assertTrue(SodiumPrimitives.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(SodiumPrimitives.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(SodiumPrimitives.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1)))
    }
}

private fun hex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
