package dev.shinsou.kmp.plugin.sandbox

/**
 * The strict CBOR subset spoken across the sandbox boundary (ADR-0002 §4.2, §16.2).
 *
 * The same bytes are produced and parsed by the C sandbox core, so the subset is small enough to
 * implement identically on both sides and to fuzz exhaustively:
 *
 * - major types 0 (unsigned integer), 2 (byte string), 3 (UTF-8 text string), 4 (array) and the
 *   simple values `false`, `true` and `null`;
 * - definite lengths only, and every integer and length in its shortest encoding;
 * - no negative integers, maps, tags, floats or undefined.
 *
 * Anything else is a [SandboxProtocolException]. Decoding never trusts a declared length before
 * checking it against the bytes actually present and the caller's cap.
 */
internal class SandboxProtocolException(message: String) : Exception(message)

internal class CborWriter(initialCapacity: Int = 64) {
    private var buf = ByteArray(initialCapacity)
    private var size = 0

    fun uint(value: Long): CborWriter {
        require(value >= 0) { "CBOR subset has no negative integers" }
        head(MAJOR_UINT, value)
        return this
    }

    fun bool(value: Boolean): CborWriter {
        put(if (value) SIMPLE_TRUE else SIMPLE_FALSE)
        return this
    }

    fun nul(): CborWriter {
        put(SIMPLE_NULL)
        return this
    }

    fun bytes(value: ByteArray): CborWriter {
        head(MAJOR_BYTES, value.size.toLong())
        put(value)
        return this
    }

    fun text(value: String): CborWriter {
        val utf8 = value.encodeToByteArray()
        head(MAJOR_TEXT, utf8.size.toLong())
        put(utf8)
        return this
    }

    fun textOrNull(value: String?): CborWriter = if (value == null) nul() else text(value)

    fun array(count: Int): CborWriter {
        head(MAJOR_ARRAY, count.toLong())
        return this
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    /** Shortest-form head: the only encoding the reader accepts. */
    private fun head(major: Int, value: Long) {
        val m = major shl 5
        when {
            value < 24 -> put((m or value.toInt()).toByte())
            value <= 0xff -> { put((m or 24).toByte()); put(value.toByte()) }
            value <= 0xffff -> { put((m or 25).toByte()); be(value, 2) }
            value <= 0xffff_ffffL -> { put((m or 26).toByte()); be(value, 4) }
            else -> { put((m or 27).toByte()); be(value, 8) }
        }
    }

    private fun be(value: Long, n: Int) {
        for (i in n - 1 downTo 0) put((value ushr (8 * i)).toByte())
    }

    private fun put(b: Byte) {
        ensure(1)
        buf[size++] = b
    }

    private fun put(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(buf, size)
        size += bytes.size
    }

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need < 0) throw SandboxProtocolException("CBOR value too large")
        if (need <= buf.size) return
        var cap = buf.size.coerceAtLeast(16)
        while (cap < need) cap = if (cap > Int.MAX_VALUE / 2) need else cap * 2
        buf = buf.copyOf(cap)
    }

    private companion object {
        const val SIMPLE_FALSE: Byte = 0xf4.toByte()
        const val SIMPLE_TRUE: Byte = 0xf5.toByte()
        const val SIMPLE_NULL: Byte = 0xf6.toByte()
    }
}

/** Reads one CBOR value tree from [data]; every accessor validates type, encoding and bounds. */
internal class CborReader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
    init {
        require(pos in 0..end && end <= data.size)
    }

    val remaining: Int get() = end - pos

    fun requireEnd() {
        if (pos != end) fail("trailing bytes after frame")
    }

    fun uint(max: Long = Long.MAX_VALUE): Long {
        val v = head(MAJOR_UINT)
        if (v > max) fail("integer $v above $max")
        return v
    }

    fun uintAsInt(max: Int = Int.MAX_VALUE): Int = uint(max.toLong()).toInt()

    fun bool(): Boolean = when (val b = next()) {
        SIMPLE_FALSE -> false
        SIMPLE_TRUE -> true
        else -> fail("expected boolean, got 0x${hex(b)}")
    }

    /** True (and consumed) when the next value is `null`. */
    fun nullNext(): Boolean {
        if (pos >= end) fail("truncated")
        if (data[pos] != SIMPLE_NULL) return false
        pos++
        return true
    }

    fun bytes(maxBytes: Int): ByteArray {
        val n = length(MAJOR_BYTES, maxBytes)
        return data.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun text(maxBytes: Int): String {
        val n = length(MAJOR_TEXT, maxBytes)
        val s = try {
            data.decodeToString(pos, pos + n, throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            fail("text is not valid UTF-8")
        }
        pos += n
        return s
    }

    fun textOrNull(maxBytes: Int): String? = if (nullNext()) null else text(maxBytes)

    fun array(maxCount: Int = Int.MAX_VALUE): Int {
        val n = head(MAJOR_ARRAY)
        if (n > maxCount) fail("array of $n elements above $maxCount")
        // Each element takes at least one byte: a count beyond the remaining bytes is a lie.
        if (n > remaining) fail("array count $n exceeds remaining bytes")
        return n.toInt()
    }

    private fun length(major: Int, maxBytes: Int): Int {
        val n = head(major)
        if (n > maxBytes) fail("string of $n bytes above $maxBytes")
        if (n > remaining) fail("string length $n exceeds remaining bytes")
        return n.toInt()
    }

    private fun head(expectedMajor: Int): Long {
        val initial = next().toInt() and 0xff
        val major = initial ushr 5
        if (major != expectedMajor) fail("expected major type $expectedMajor, got $major")
        val info = initial and 0x1f
        val v = when (info) {
            in 0..23 -> return info.toLong()
            24 -> be(1)
            25 -> be(2)
            26 -> be(4)
            27 -> be(8)
            else -> fail("unsupported additional info $info (indefinite or reserved)")
        }
        val minimal = when (info) {
            24 -> v >= 24
            25 -> v > 0xff
            26 -> v > 0xffff
            else -> v > 0xffff_ffffL
        }
        if (!minimal) fail("non-shortest integer encoding")
        if (v < 0) fail("integer above 2^63-1")
        return v
    }

    private fun be(n: Int): Long {
        if (remaining < n) fail("truncated")
        var v = 0L
        repeat(n) { v = (v shl 8) or (data[pos++].toLong() and 0xff) }
        return v
    }

    private fun next(): Byte {
        if (pos >= end) fail("truncated")
        return data[pos++]
    }

    private fun fail(message: String): Nothing = throw SandboxProtocolException(message)

    private fun hex(b: Byte): String = (b.toInt() and 0xff).toString(16).padStart(2, '0')

    private companion object {
        const val SIMPLE_FALSE: Byte = 0xf4.toByte()
        const val SIMPLE_TRUE: Byte = 0xf5.toByte()
        const val SIMPLE_NULL: Byte = 0xf6.toByte()
    }
}

private const val MAJOR_UINT = 0
private const val MAJOR_BYTES = 2
private const val MAJOR_TEXT = 3
private const val MAJOR_ARRAY = 4
