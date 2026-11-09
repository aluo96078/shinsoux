package dev.shinsou.kmp.sync.network

/** Canonical unpadded base64url (RFC 4648 section 5), used by the content sync draft factories. */
internal fun encodeBase64Url(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val result = StringBuilder((bytes.size * 4 + 2) / 3)
    var index = 0
    while (index + 2 < bytes.size) {
        val value = ((bytes[index].toInt() and 0xff) shl 16) or
            ((bytes[index + 1].toInt() and 0xff) shl 8) or (bytes[index + 2].toInt() and 0xff)
        result.append(alphabet[(value ushr 18) and 63])
        result.append(alphabet[(value ushr 12) and 63])
        result.append(alphabet[(value ushr 6) and 63])
        result.append(alphabet[value and 63])
        index += 3
    }
    val remaining = bytes.size - index
    if (remaining == 1) {
        val value = (bytes[index].toInt() and 0xff) shl 16
        result.append(alphabet[(value ushr 18) and 63])
        result.append(alphabet[(value ushr 12) and 63])
    } else if (remaining == 2) {
        val value = ((bytes[index].toInt() and 0xff) shl 16) or ((bytes[index + 1].toInt() and 0xff) shl 8)
        result.append(alphabet[(value ushr 18) and 63])
        result.append(alphabet[(value ushr 12) and 63])
        result.append(alphabet[(value ushr 6) and 63])
    }
    return result.toString()
}

/** Strict inverse of [encodeBase64Url]; rejects padding, foreign alphabets and non-canonical tails. */
internal fun decodeBase64Url(value: String): ByteArray {
    require(value.matches(Regex("^[A-Za-z0-9_-]*$")) && value.length % 4 != 1) { "Invalid base64url" }
    val reverse = IntArray(128) { -1 }
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".forEachIndexed { index, character ->
        reverse[character.code] = index
    }
    val output = ByteArray(value.length * 3 / 4)
    var inputIndex = 0
    var outputIndex = 0
    while (inputIndex + 4 <= value.length) {
        val bits = (reverse[value[inputIndex].code] shl 18) or
            (reverse[value[inputIndex + 1].code] shl 12) or
            (reverse[value[inputIndex + 2].code] shl 6) or reverse[value[inputIndex + 3].code]
        output[outputIndex++] = (bits ushr 16).toByte()
        output[outputIndex++] = (bits ushr 8).toByte()
        output[outputIndex++] = bits.toByte()
        inputIndex += 4
    }
    if (value.length - inputIndex == 2) {
        val bits = (reverse[value[inputIndex].code] shl 18) or (reverse[value[inputIndex + 1].code] shl 12)
        require(bits and 0xffff == 0) { "Non-canonical base64url tail" }
        output[outputIndex++] = (bits ushr 16).toByte()
    } else if (value.length - inputIndex == 3) {
        val bits = (reverse[value[inputIndex].code] shl 18) or
            (reverse[value[inputIndex + 1].code] shl 12) or (reverse[value[inputIndex + 2].code] shl 6)
        require(bits and 0xff == 0) { "Non-canonical base64url tail" }
        output[outputIndex++] = (bits ushr 16).toByte()
        output[outputIndex++] = (bits ushr 8).toByte()
    }
    return output.copyOf(outputIndex).also { require(encodeBase64Url(it) == value) { "Non-canonical base64url" } }
}
