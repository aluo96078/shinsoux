package dev.shinsou.kmp.sync.crypto

/**
 * Deterministic raw LZ4 block codec used by sync checkpoints.
 *
 * The authenticated checkpoint header carries both the algorithm identifier and the exact
 * uncompressed byte count, so the block intentionally has no platform-specific container,
 * checksum, timestamp, or allocator-controlled metadata. Decompression always targets a caller
 * supplied, bounded output buffer and rejects trailing, truncated, or over-expanding input.
 */
internal object DeterministicLz4BlockV1 {
    fun compress(input: ByteArray): ByteArray {
        if (input.isEmpty()) return byteArrayOf()

        val output = ByteAccumulator(input.size + input.size / 255 + 16)
        val hashTable = IntArray(HASH_TABLE_SIZE) { -1 }
        val searchLimit = input.size - LAST_LITERALS - MIN_MATCH
        var anchor = 0
        var cursor = 0

        while (cursor <= searchLimit) {
            val hash = hash(input, cursor)
            val candidate = hashTable[hash]
            hashTable[hash] = cursor
            if (
                candidate < 0 ||
                cursor - candidate > MAX_DISTANCE ||
                !equalFour(input, candidate, cursor)
            ) {
                cursor += 1
                continue
            }

            val matchStart = cursor
            val matchLimit = input.size - LAST_LITERALS
            var matchLength = MIN_MATCH
            while (
                cursor + matchLength < matchLimit &&
                input[candidate + matchLength] == input[cursor + matchLength]
            ) {
                matchLength += 1
            }

            emitSequence(
                output = output,
                input = input,
                literalStart = anchor,
                literalLength = matchStart - anchor,
                offset = matchStart - candidate,
                matchLength = matchLength,
            )
            cursor += matchLength
            anchor = cursor

            // Populate every searchable position traversed by the match. This is deterministic,
            // improves compression of repetitive state, and still permits standard LZ4 overlap.
            var traversed = matchStart + 1
            while (traversed < cursor && traversed <= searchLimit) {
                hashTable[hash(input, traversed)] = traversed
                traversed += 1
            }
        }

        emitLastLiterals(output, input, anchor, input.size - anchor)
        return output.toByteArray()
    }

    fun decompress(
        input: ByteArray,
        expectedSize: Int,
        maxOutputSize: Int,
    ): ByteArray {
        require(maxOutputSize >= 0) { "LZ4 output limit cannot be negative" }
        require(expectedSize in 0..maxOutputSize) { "LZ4 output exceeds the configured limit" }
        if (expectedSize == 0) {
            require(input.isEmpty()) { "Non-empty LZ4 block cannot represent an empty payload" }
            return byteArrayOf()
        }
        require(input.isNotEmpty()) { "LZ4 block is truncated" }

        val output = ByteArray(expectedSize)
        var source = 0
        var destination = 0
        while (source < input.size) {
            val token = input[source++].toInt() and 0xff

            var literalLength = token ushr 4
            if (literalLength == LENGTH_NIBBLE_MAX) {
                val decoded = readExtendedLength(input, source, literalLength, expectedSize - destination)
                literalLength = decoded.length
                source = decoded.nextOffset
            }
            require(literalLength <= expectedSize - destination) { "LZ4 literals exceed expected output size" }
            require(literalLength <= input.size - source) { "LZ4 literal run is truncated" }
            input.copyInto(output, destination, source, source + literalLength)
            source += literalLength
            destination += literalLength

            if (source == input.size) {
                require(destination == expectedSize) { "LZ4 output size does not match authenticated size" }
                return output
            }

            require(input.size - source >= OFFSET_BYTES) { "LZ4 match offset is truncated" }
            val offset = (input[source].toInt() and 0xff) or
                ((input[source + 1].toInt() and 0xff) shl 8)
            source += OFFSET_BYTES
            require(offset in 1..minOf(destination, MAX_DISTANCE)) { "LZ4 match offset is invalid" }

            var matchLength = (token and LENGTH_NIBBLE_MAX) + MIN_MATCH
            if ((token and LENGTH_NIBBLE_MAX) == LENGTH_NIBBLE_MAX) {
                val decoded = readExtendedLength(
                    input = input,
                    startOffset = source,
                    baseLength = matchLength,
                    maximumLength = expectedSize - destination,
                )
                matchLength = decoded.length
                source = decoded.nextOffset
            }
            require(matchLength <= expectedSize - destination) { "LZ4 match exceeds expected output size" }

            val matchSource = destination - offset
            var copied = 0
            while (copied < matchLength) {
                output[destination + copied] = output[matchSource + copied]
                copied += 1
            }
            destination += matchLength
        }

        throw IllegalArgumentException("LZ4 block ended before the authenticated output size")
    }

    private fun emitSequence(
        output: ByteAccumulator,
        input: ByteArray,
        literalStart: Int,
        literalLength: Int,
        offset: Int,
        matchLength: Int,
    ) {
        require(offset in 1..MAX_DISTANCE && matchLength >= MIN_MATCH)
        val tokenOffset = output.size
        output.writeByte(0)

        val literalNibble = minOf(literalLength, LENGTH_NIBBLE_MAX)
        if (literalLength >= LENGTH_NIBBLE_MAX) {
            writeExtendedLength(output, literalLength - LENGTH_NIBBLE_MAX)
        }
        output.writeBytes(input, literalStart, literalLength)
        output.writeByte(offset and 0xff)
        output.writeByte((offset ushr 8) and 0xff)

        val matchRemainder = matchLength - MIN_MATCH
        val matchNibble = minOf(matchRemainder, LENGTH_NIBBLE_MAX)
        if (matchRemainder >= LENGTH_NIBBLE_MAX) {
            writeExtendedLength(output, matchRemainder - LENGTH_NIBBLE_MAX)
        }
        output[tokenOffset] = (literalNibble shl 4) or matchNibble
    }

    private fun emitLastLiterals(
        output: ByteAccumulator,
        input: ByteArray,
        literalStart: Int,
        literalLength: Int,
    ) {
        val token = minOf(literalLength, LENGTH_NIBBLE_MAX) shl 4
        output.writeByte(token)
        if (literalLength >= LENGTH_NIBBLE_MAX) {
            writeExtendedLength(output, literalLength - LENGTH_NIBBLE_MAX)
        }
        output.writeBytes(input, literalStart, literalLength)
    }

    private fun writeExtendedLength(output: ByteAccumulator, extraLength: Int) {
        require(extraLength >= 0)
        var remaining = extraLength
        while (remaining >= 255) {
            output.writeByte(255)
            remaining -= 255
        }
        output.writeByte(remaining)
    }

    private fun readExtendedLength(
        input: ByteArray,
        startOffset: Int,
        baseLength: Int,
        maximumLength: Int,
    ): DecodedLength {
        var offset = startOffset
        var length = baseLength
        while (true) {
            require(offset < input.size) { "LZ4 extended length is truncated" }
            val next = input[offset++].toInt() and 0xff
            require(next <= maximumLength - length) { "LZ4 run exceeds expected output size" }
            length += next
            if (next != 255) return DecodedLength(length, offset)
        }
    }

    private fun equalFour(input: ByteArray, first: Int, second: Int): Boolean =
        input[first] == input[second] &&
            input[first + 1] == input[second + 1] &&
            input[first + 2] == input[second + 2] &&
            input[first + 3] == input[second + 3]

    private fun hash(input: ByteArray, offset: Int): Int {
        val value = (input[offset].toInt() and 0xff) or
            ((input[offset + 1].toInt() and 0xff) shl 8) or
            ((input[offset + 2].toInt() and 0xff) shl 16) or
            ((input[offset + 3].toInt() and 0xff) shl 24)
        return (value * HASH_MULTIPLIER ushr HASH_SHIFT) and (HASH_TABLE_SIZE - 1)
    }

    private data class DecodedLength(val length: Int, val nextOffset: Int)

    private class ByteAccumulator(initialCapacity: Int) {
        private var bytes = ByteArray(maxOf(initialCapacity, 16))
        var size: Int = 0
            private set

        operator fun set(index: Int, value: Int) {
            require(index in 0 until size)
            bytes[index] = value.toByte()
        }

        fun writeByte(value: Int) {
            ensureCapacity(size + 1)
            bytes[size++] = value.toByte()
        }

        fun writeBytes(source: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= source.size - length)
            ensureCapacity(size + length)
            source.copyInto(bytes, size, offset, offset + length)
            size += length
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        private fun ensureCapacity(required: Int) {
            if (required <= bytes.size) return
            var newCapacity = bytes.size
            while (newCapacity < required) {
                val grown = newCapacity + maxOf(newCapacity / 2, 16)
                require(grown > newCapacity) { "LZ4 output is too large" }
                newCapacity = grown
            }
            bytes = bytes.copyOf(newCapacity)
        }
    }

    private const val MIN_MATCH = 4
    private const val LAST_LITERALS = 5
    private const val MAX_DISTANCE = 0xffff
    private const val OFFSET_BYTES = 2
    private const val LENGTH_NIBBLE_MAX = 15
    private const val HASH_TABLE_SIZE = 1 shl 16
    private const val HASH_SHIFT = 16
    private const val HASH_MULTIPLIER = -1640531535
}
