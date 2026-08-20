package dev.shinsou.kmp.sync.crypto

import dev.shinsou.kmp.sync.v2.SYNC_CHECKPOINT_MAX_UNCOMPRESSED_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeterministicLz4BlockTest {
    @Test
    fun fixedLz4BlockV1VectorIsStableAndUsesAMatch() {
        val plaintext = "abcdabcdabcdXYZ12".encodeToByteArray()

        val compressed = DeterministicLz4BlockV1.compress(plaintext)

        assertEquals("446162636404005058595a3132", compressed.toHex())
        assertTrue(compressed.size < plaintext.size)
        assertContentEquals(
            plaintext,
            DeterministicLz4BlockV1.decompress(
                compressed,
                expectedSize = plaintext.size,
                maxOutputSize = SYNC_CHECKPOINT_MAX_UNCOMPRESSED_BYTES,
            ),
        )
    }

    @Test
    fun literalAndExtendedLengthVectorsRoundTripDeterministically() {
        assertEquals("30616263", DeterministicLz4BlockV1.compress("abc".encodeToByteArray()).toHex())
        val plaintext = ByteArray(4_096) { index -> ((index / 17) xor (index % 29)).toByte() }

        val first = DeterministicLz4BlockV1.compress(plaintext)
        val second = DeterministicLz4BlockV1.compress(plaintext)

        assertContentEquals(first, second)
        assertContentEquals(
            plaintext,
            DeterministicLz4BlockV1.decompress(first, plaintext.size, plaintext.size),
        )
    }

    @Test
    fun decoderRejectsTamperingTruncationAndAuthenticatedSizeMismatch() {
        val plaintext = "abcdabcdabcdXYZ12".encodeToByteArray()
        val compressed = DeterministicLz4BlockV1.compress(plaintext)
        val zeroOffset = compressed.copyOf().apply {
            this[5] = 0
            this[6] = 0
        }

        assertFailsWith<IllegalArgumentException> {
            DeterministicLz4BlockV1.decompress(zeroOffset, plaintext.size, plaintext.size)
        }
        assertFailsWith<IllegalArgumentException> {
            DeterministicLz4BlockV1.decompress(compressed.copyOf(compressed.size - 1), plaintext.size, plaintext.size)
        }
        assertFailsWith<IllegalArgumentException> {
            DeterministicLz4BlockV1.decompress(compressed, plaintext.size - 1, plaintext.size)
        }
    }

    @Test
    fun decoderRejectsOversizeBeforeAllocatingOutput() {
        assertFailsWith<IllegalArgumentException> {
            DeterministicLz4BlockV1.decompress(
                input = byteArrayOf(0),
                expectedSize = SYNC_CHECKPOINT_MAX_UNCOMPRESSED_BYTES + 1,
                maxOutputSize = SYNC_CHECKPOINT_MAX_UNCOMPRESSED_BYTES,
            )
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}
