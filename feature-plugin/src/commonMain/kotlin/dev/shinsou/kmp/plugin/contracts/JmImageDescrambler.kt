// 0007 A3: one source's image scrambling, kept beside the plugin contracts that apply it rather than in :domain.
package dev.shinsou.kmp.plugin.contracts

import dev.shinsou.kmp.domain.model.ReaderImageTransform

/**
 * Port of Shinsou's `JMImageDescrambler` contract.
 *
 * JM intentionally uses the ASCII value of the final lowercase MD5 character, not its numeric
 * hexadecimal value. Keeping the rule here prevents the plugin, reader, and downloader from
 * developing subtly different implementations.
 */
public object JmImageDescrambler {
    public const val SOURCE_ID: Long = 1_817_081L
    public const val SCRAMBLE_THRESHOLD_268850: Int = 268_850
    public const val SCRAMBLE_THRESHOLD_421926: Int = 421_926

    public const val SCRAMBLE_ID_KEY: String = "Shinsou-JM-Scramble-Id"
    public const val PHOTO_ID_KEY: String = "Shinsou-JM-Photo-Id"
    public const val FILENAME_KEY: String = "Shinsou-JM-Filename"

    /** [canonicalSourceId] is the published id, not a publisher-scoped projected runtime id. */
    public fun transform(
        canonicalSourceId: String,
        metadata: Map<String, String>,
    ): ReaderImageTransform? {
        if (canonicalSourceId != SOURCE_ID.toString()) return null
        val scrambleId = metadata[SCRAMBLE_ID_KEY]?.toIntOrNull()
        val photoId = metadata[PHOTO_ID_KEY]?.toIntOrNull()
        val filename = metadata[FILENAME_KEY]?.takeIf(String::isNotBlank)
        val count = if (scrambleId != null && photoId != null && filename != null) {
            segmentationCount(scrambleId, photoId, filename)
        } else {
            0
        }
        return count.takeIf { it > 1 }
            ?.let { ReaderImageTransform.ReverseVerticalSegments(it) }
    }

    public fun segmentationCount(scrambleId: Int, photoId: Int, filename: String): Int {
        val moduloBase = if (photoId < SCRAMBLE_THRESHOLD_421926) 10 else 8
        return when {
            photoId < scrambleId -> 0
            photoId < SCRAMBLE_THRESHOLD_268850 -> 10
            else -> md5Hex("$photoId$filename").lastOrNull()?.let { (it.code % moduloBase) * 2 + 2 } ?: 0
        }
    }
}

private fun md5Hex(value: String): String {
    val input = value.encodeToByteArray()
    val paddedSize = ((input.size + 9 + 63) / 64) * 64
    val message = ByteArray(paddedSize)
    input.copyInto(message)
    message[input.size] = 0x80.toByte()
    val bitLength = input.size.toLong() * 8L
    repeat(8) { byteIndex ->
        message[paddedSize - 8 + byteIndex] = (bitLength ushr (byteIndex * 8)).toByte()
    }

    var a0 = 0x67452301
    var b0 = -0x10325477
    var c0 = -0x67452302
    var d0 = 0x10325476
    val words = IntArray(16)

    for (blockStart in message.indices step 64) {
        for (wordIndex in words.indices) {
            val offset = blockStart + wordIndex * 4
            words[wordIndex] =
                (message[offset].toInt() and 0xff) or
                    ((message[offset + 1].toInt() and 0xff) shl 8) or
                    ((message[offset + 2].toInt() and 0xff) shl 16) or
                    ((message[offset + 3].toInt() and 0xff) shl 24)
        }

        var a = a0
        var b = b0
        var c = c0
        var d = d0
        repeat(64) { index ->
            val (function, wordIndex) = when (index) {
                in 0..15 -> ((b and c) or (b.inv() and d)) to index
                in 16..31 -> ((d and b) or (d.inv() and c)) to ((5 * index + 1) % 16)
                in 32..47 -> (b xor c xor d) to ((3 * index + 5) % 16)
                else -> (c xor (b or d.inv())) to ((7 * index) % 16)
            }
            val next = b + rotateLeft(a + function + MD5_CONSTANTS[index] + words[wordIndex], MD5_SHIFTS[index])
            a = d
            d = c
            c = b
            b = next
        }
        a0 += a
        b0 += b
        c0 += c
        d0 += d
    }

    val output = StringBuilder(32)
    intArrayOf(a0, b0, c0, d0).forEach { word ->
        repeat(4) { index ->
            val byte = (word ushr (index * 8)) and 0xff
            output.append(HEX[byte ushr 4]).append(HEX[byte and 0x0f])
        }
    }
    return output.toString()
}

private fun rotateLeft(value: Int, count: Int): Int =
    (value shl count) or (value ushr (32 - count))

private const val HEX = "0123456789abcdef"

private val MD5_SHIFTS = intArrayOf(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
)

private val MD5_CONSTANTS = intArrayOf(
    -680876936, -389564586, 606105819, -1044525330, -176418897, 1200080426, -1473231341, -45705983,
    1770035416, -1958414417, -42063, -1990404162, 1804603682, -40341101, -1502002290, 1236535329,
    -165796510, -1069501632, 643717713, -373897302, -701558691, 38016083, -660478335, -405537848,
    568446438, -1019803690, -187363961, 1163531501, -1444681467, -51403784, 1735328473, -1926607734,
    -378558, -2022574463, 1839030562, -35309556, -1530992060, 1272893353, -155497632, -1094730640,
    681279174, -358537222, -722521979, 76029189, -640364487, -421815835, 530742520, -995338651,
    -198630844, 1126891415, -1416354905, -57434055, 1700485571, -1894986606, -1051523, -2054922799,
    1873313359, -30611744, -1560198380, 1309151649, -145523070, -1120210379, 718787259, -343485551,
)
