package dev.shinsou.kmp.sync.crypto

import dev.shinsou.kmp.sync.v2.BinaryData
import dev.shinsou.kmp.sync.v2.SyncCheckpointCompression
import dev.shinsou.kmp.sync.v2.SyncCheckpointHeader
import dev.shinsou.kmp.sync.v2.SyncEvent
import dev.shinsou.kmp.sync.v2.SyncEventCodec
import dev.shinsou.kmp.sync.v2.SyncEventHeader
import dev.shinsou.kmp.sync.v2.SyncState
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Small RFC 8949 deterministic-CBOR implementation used at the sync trust boundary.
 *
 * It deliberately accepts only the JSON data model used by the versioned sync serializers:
 * definite lengths, UTF-8 text map keys, finite numbers, arrays, booleans and null. Decoding
 * re-encodes the value and compares every byte, rejecting duplicate keys, non-minimal integers,
 * unsorted maps and non-preferred floating-point encodings.
 */
internal object DeterministicCbor {
    fun encode(element: JsonElement): ByteArray = Encoder().apply { write(element) }.toByteArray()

    fun decode(bytes: ByteArray): JsonElement {
        require(bytes.size <= MAX_DOCUMENT_BYTES) { "CBOR document is too large" }
        val decoder = Decoder(bytes)
        val decoded = decoder.read(depth = 0)
        require(decoder.isAtEnd()) { "Trailing CBOR bytes" }
        val canonical = encode(decoded)
        require(canonical.contentEquals(bytes)) { "CBOR input is not deterministic/canonical" }
        return decoded
    }

    private class Encoder {
        private val output = ByteAccumulator()

        fun toByteArray(): ByteArray = output.toByteArray()

        fun write(value: JsonElement) {
            when (value) {
                JsonNull -> output.write(0xf6)
                is JsonArray -> {
                    writeLength(4, value.size.toLong())
                    value.forEach(::write)
                }
                is JsonObject -> writeObject(value)
                is JsonPrimitive -> writePrimitive(value)
            }
        }

        private fun writeObject(value: JsonObject) {
            val entries = value.entries.map { (key, item) ->
                val encodedKey = Encoder().apply { write(JsonPrimitive(key)) }.toByteArray()
                EncodedEntry(encodedKey, item)
            }.sortedWith { left, right -> compareCanonical(left.key, right.key) }
            writeLength(5, entries.size.toLong())
            entries.forEach { entry ->
                output.write(entry.key)
                write(entry.value)
            }
        }

        private fun writePrimitive(value: JsonPrimitive) {
            if (value.isString) {
                val bytes = value.content.encodeToByteArray()
                writeLength(3, bytes.size.toLong())
                output.write(bytes)
                return
            }
            value.booleanOrNull?.let {
                output.write(if (it) 0xf5 else 0xf4)
                return
            }
            value.longOrNull?.let {
                if (it >= 0) writeLength(0, it) else writeLength(1, -1L - it)
                return
            }
            val double = value.doubleOrNull
                ?: throw IllegalArgumentException("Unsupported JSON primitive in CBOR payload")
            require(double.isFinite()) { "Non-finite numbers are not valid sync data" }
            writePreferredDouble(double)
        }

        private fun writePreferredDouble(value: Double) {
            val half = doubleToExactHalf(value)
            if (half != null) {
                output.write(0xf9)
                output.write((half ushr 8) and 0xff)
                output.write(half and 0xff)
                return
            }
            val float = value.toFloat()
            if (float.isFinite() && sameDouble(float.toDouble(), value)) {
                output.write(0xfa)
                output.writeInt(float.toRawBits())
                return
            }
            output.write(0xfb)
            output.writeLong(value.toRawBits())
        }

        private fun writeLength(major: Int, value: Long) {
            require(value >= 0) { "CBOR length/integer cannot be negative" }
            when {
                value < 24 -> output.write((major shl 5) or value.toInt())
                value <= 0xff -> {
                    output.write((major shl 5) or 24)
                    output.write(value.toInt())
                }
                value <= 0xffff -> {
                    output.write((major shl 5) or 25)
                    output.write((value ushr 8).toInt())
                    output.write(value.toInt())
                }
                value <= 0xffff_ffffL -> {
                    output.write((major shl 5) or 26)
                    output.writeInt(value.toInt())
                }
                else -> {
                    output.write((major shl 5) or 27)
                    output.writeLong(value)
                }
            }
        }
    }

    private class Decoder(private val input: ByteArray) {
        private var offset = 0

        fun isAtEnd(): Boolean = offset == input.size

        fun read(depth: Int): JsonElement {
            require(depth <= MAX_NESTING) { "CBOR nesting is too deep" }
            val initial = take()
            val major = initial ushr 5
            val additional = initial and 0x1f
            if (major == 7) return readSimple(additional)
            val length = readLength(additional)
            return when (major) {
                0 -> JsonPrimitive(length)
                1 -> JsonPrimitive(-1L - length)
                3 -> JsonPrimitive(takeBytes(requireContainerSize(length)).decodeToString(throwOnInvalidSequence = true))
                4 -> JsonArray(List(requireContainerSize(length)) { read(depth + 1) })
                5 -> readObject(requireContainerSize(length), depth + 1)
                else -> throw IllegalArgumentException("Unsupported CBOR major type $major")
            }
        }

        private fun readObject(size: Int, depth: Int): JsonObject {
            val entries = LinkedHashMap<String, JsonElement>(size)
            repeat(size) {
                val key = read(depth) as? JsonPrimitive
                    ?: throw IllegalArgumentException("CBOR map key must be text")
                require(key.isString) { "CBOR map key must be text" }
                require(key.content !in entries) { "Duplicate CBOR map key" }
                entries[key.content] = read(depth)
            }
            return JsonObject(entries)
        }

        private fun readSimple(additional: Int): JsonElement = when (additional) {
            20 -> JsonPrimitive(false)
            21 -> JsonPrimitive(true)
            22 -> JsonNull
            25 -> JsonPrimitive(halfToFloat(readUnsigned(2).toInt()).toDouble())
            26 -> JsonPrimitive(Float.fromBits(readUnsigned(4).toInt()).toDouble())
            27 -> JsonPrimitive(Double.fromBits(readRawLong()))
            else -> throw IllegalArgumentException("Unsupported CBOR simple/float value")
        }.also { value ->
            if (value is JsonPrimitive && !value.isString && value.doubleOrNull != null) {
                require(requireNotNull(value.doubleOrNull).isFinite()) { "Non-finite CBOR number" }
            }
        }

        private fun readLength(additional: Int): Long = when {
            additional < 24 -> additional.toLong()
            additional == 24 -> readUnsigned(1)
            additional == 25 -> readUnsigned(2)
            additional == 26 -> readUnsigned(4)
            additional == 27 -> readRawLong().also { require(it >= 0) { "CBOR integer exceeds signed range" } }
            else -> throw IllegalArgumentException("Indefinite/reserved CBOR length is forbidden")
        }

        private fun readUnsigned(width: Int): Long {
            var value = 0L
            repeat(width) { value = (value shl 8) or take().toLong() }
            return value
        }

        private fun readRawLong(): Long {
            var value = 0L
            repeat(8) { value = (value shl 8) or take().toLong() }
            return value
        }

        private fun take(): Int {
            require(offset < input.size) { "Truncated CBOR input" }
            return input[offset++].toInt() and 0xff
        }

        private fun takeBytes(size: Int): ByteArray {
            require(size >= 0 && size <= input.size - offset) { "Truncated CBOR input" }
            return input.copyOfRange(offset, offset + size).also { offset += size }
        }

        private fun requireContainerSize(value: Long): Int {
            require(value <= MAX_CONTAINER_ITEMS && value <= Int.MAX_VALUE) { "CBOR container is too large" }
            return value.toInt()
        }
    }

    private data class EncodedEntry(val key: ByteArray, val value: JsonElement)

    private class ByteAccumulator(initialSize: Int = 256) {
        private var bytes = ByteArray(initialSize)
        private var size = 0

        fun write(value: Int) {
            ensure(1)
            bytes[size++] = value.toByte()
        }

        fun write(value: ByteArray) {
            ensure(value.size)
            value.copyInto(bytes, destinationOffset = size)
            size += value.size
        }

        fun writeInt(value: Int) {
            write(value ushr 24)
            write(value ushr 16)
            write(value ushr 8)
            write(value)
        }

        fun writeLong(value: Long) {
            for (shift in 56 downTo 0 step 8) write((value ushr shift).toInt())
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        private fun ensure(additional: Int) {
            require(additional >= 0 && size <= MAX_DOCUMENT_BYTES - additional) { "CBOR document is too large" }
            if (size + additional <= bytes.size) return
            var next = bytes.size.coerceAtLeast(1)
            while (next < size + additional) next = (next * 2).coerceAtMost(MAX_DOCUMENT_BYTES)
            bytes = bytes.copyOf(next)
        }
    }

    private fun compareCanonical(left: ByteArray, right: ByteArray): Int {
        if (left.size != right.size) return left.size.compareTo(right.size)
        left.indices.forEach { index ->
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun sameDouble(left: Double, right: Double): Boolean = left.toRawBits() == right.toRawBits()

    private fun doubleToExactHalf(value: Double): Int? {
        val float = value.toFloat()
        if (!float.isFinite() || !sameDouble(float.toDouble(), value)) return null
        val half = floatToHalfBits(float)
        return half.takeIf { sameDouble(halfToFloat(it).toDouble(), value) }
    }

    private fun floatToHalfBits(value: Float): Int {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exponent = (bits ushr 23) and 0xff
        val mantissa = bits and 0x7fffff
        if (exponent == 0xff) return sign or if (mantissa == 0) 0x7c00 else 0x7e00
        var halfExponent = exponent - 127 + 15
        if (halfExponent >= 0x1f) return sign or 0x7c00
        if (halfExponent <= 0) {
            if (halfExponent < -10) return sign
            val significand = mantissa or 0x800000
            val shift = 14 - halfExponent
            var rounded = significand ushr shift
            val remainder = significand and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (remainder > halfway || (remainder == halfway && (rounded and 1) != 0)) rounded += 1
            return sign or rounded
        }
        var roundedMantissa = mantissa ushr 13
        val remainder = mantissa and 0x1fff
        if (remainder > 0x1000 || (remainder == 0x1000 && (roundedMantissa and 1) != 0)) {
            roundedMantissa += 1
            if (roundedMantissa == 0x400) {
                roundedMantissa = 0
                halfExponent += 1
                if (halfExponent >= 0x1f) return sign or 0x7c00
            }
        }
        return sign or (halfExponent shl 10) or roundedMantissa
    }

    private fun halfToFloat(bits: Int): Float {
        val sign = (bits and 0x8000) shl 16
        val exponent = (bits ushr 10) and 0x1f
        val mantissa = bits and 0x3ff
        val floatBits = when (exponent) {
            0 -> {
                if (mantissa == 0) {
                    sign
                } else {
                    var normalized = mantissa
                    var shift = 0
                    while ((normalized and 0x400) == 0) {
                        normalized = normalized shl 1
                        shift += 1
                    }
                    normalized = normalized and 0x3ff
                    sign or ((127 - 15 - shift) shl 23) or (normalized shl 13)
                }
            }
            0x1f -> sign or 0x7f800000 or (mantissa shl 13)
            else -> sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
        }
        return Float.fromBits(floatBits)
    }

    private const val MAX_NESTING = 64
    private const val MAX_CONTAINER_ITEMS = 1_000_000L
    private const val MAX_DOCUMENT_BYTES = 64 * 1024 * 1024
}

/** Versioned event/checkpoint codec shared by every KMP target. */
class DeterministicSyncEventCodec(
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        allowStructuredMapKeys = true
        classDiscriminator = "type"
    },
) : SyncEventCodec {
    override fun encodeEvent(event: SyncEvent): BinaryData = encode(SyncEvent.serializer(), event)

    override fun decodeEvent(payload: BinaryData): SyncEvent = decode(SyncEvent.serializer(), payload)

    override fun canonicalEventAssociatedData(headerWithoutCiphertextHash: SyncEventHeader): BinaryData =
        BinaryData.copyOf(
            DeterministicCbor.encode(
                JsonObject(
                    mapOf(
                        "envelopeVersion" to JsonPrimitive(headerWithoutCiphertextHash.envelopeVersion),
                        "protocolVersion" to JsonPrimitive(headerWithoutCiphertextHash.protocolVersion),
                        "schemaVersion" to JsonPrimitive(headerWithoutCiphertextHash.schemaVersion),
                        "cipherSuite" to JsonPrimitive(headerWithoutCiphertextHash.cipherSuite.name),
                        "nonce" to JsonPrimitive(headerWithoutCiphertextHash.nonceBase64Url),
                        "instanceId" to JsonPrimitive(headerWithoutCiphertextHash.instanceId),
                        "workspaceId" to JsonPrimitive(headerWithoutCiphertextHash.workspaceId),
                        "eventId" to JsonPrimitive(headerWithoutCiphertextHash.eventId),
                        "deviceId" to JsonPrimitive(headerWithoutCiphertextHash.deviceId),
                        "deviceSeq" to JsonPrimitive(headerWithoutCiphertextHash.deviceSeq),
                        "keyEpoch" to JsonPrimitive(headerWithoutCiphertextHash.keyEpoch),
                    ),
                ),
            ),
        )

    override fun canonicalCheckpointAssociatedData(headerWithoutCiphertextHash: SyncCheckpointHeader): BinaryData =
        BinaryData.copyOf(
        DeterministicCbor.encode(
            JsonObject(
                mapOf(
                    "envelopeVersion" to JsonPrimitive(headerWithoutCiphertextHash.envelopeVersion),
                    "protocolVersion" to JsonPrimitive(headerWithoutCiphertextHash.protocolVersion),
                    "schemaVersion" to JsonPrimitive(headerWithoutCiphertextHash.schemaVersion),
                    "cipherSuite" to JsonPrimitive(headerWithoutCiphertextHash.cipherSuite.name),
                    "nonce" to JsonPrimitive(headerWithoutCiphertextHash.nonceBase64Url),
                    "instanceId" to JsonPrimitive(headerWithoutCiphertextHash.instanceId),
                    "workspaceId" to JsonPrimitive(headerWithoutCiphertextHash.workspaceId),
                    "checkpointId" to JsonPrimitive(headerWithoutCiphertextHash.checkpointId),
                    "deviceId" to JsonPrimitive(headerWithoutCiphertextHash.deviceId),
                    "throughWorkspaceSeq" to JsonPrimitive(headerWithoutCiphertextHash.throughWorkspaceSeq),
                    "keyEpoch" to JsonPrimitive(headerWithoutCiphertextHash.keyEpoch),
                    "previousStableCheckpointHash" to JsonPrimitive(
                        headerWithoutCiphertextHash.previousStableCiphertextSha256Base64Url.orEmpty(),
                    ),
                    "compression" to JsonPrimitive(headerWithoutCiphertextHash.compression.name),
                    "uncompressedSize" to JsonPrimitive(headerWithoutCiphertextHash.uncompressedSize),
                    "stateFormat" to JsonPrimitive("sync-state-v1"),
                ),
            ),
        ),
    )

    override fun canonicalCheckpointState(state: SyncState): BinaryData = encode(SyncState.serializer(), state.normalized())

    override fun decodeCheckpointState(payload: BinaryData): SyncState = decode(SyncState.serializer(), payload).normalized()

    fun decodeEventAssociatedData(payload: BinaryData, ciphertextSha256Base64Url: String): SyncEventHeader {
        val map = decodeExactMap(
            payload,
            setOf(
                "envelopeVersion", "protocolVersion", "schemaVersion", "cipherSuite", "nonce",
                "instanceId", "workspaceId", "eventId", "deviceId", "deviceSeq", "keyEpoch",
            ),
        )
        return SyncEventHeader(
            envelopeVersion = map.requiredInt("envelopeVersion"),
            protocolVersion = map.requiredInt("protocolVersion"),
            schemaVersion = map.requiredInt("schemaVersion"),
            cipherSuite = map.requiredCipherSuite(),
            nonceBase64Url = map.requiredString("nonce"),
            instanceId = map.requiredString("instanceId"),
            workspaceId = map.requiredString("workspaceId"),
            eventId = map.requiredString("eventId"),
            deviceId = map.requiredString("deviceId"),
            deviceSeq = map.requiredLong("deviceSeq"),
            keyEpoch = map.requiredInt("keyEpoch"),
            ciphertextSha256Base64Url = ciphertextSha256Base64Url,
        )
    }

    fun decodeCheckpointAssociatedData(
        payload: BinaryData,
        ciphertextSha256Base64Url: String,
    ): SyncCheckpointHeader {
        val map = decodeExactMap(
            payload,
            setOf(
                "envelopeVersion", "protocolVersion", "schemaVersion", "cipherSuite", "nonce",
                "instanceId", "workspaceId", "checkpointId", "deviceId", "throughWorkspaceSeq",
                "keyEpoch", "previousStableCheckpointHash", "compression", "uncompressedSize", "stateFormat",
            ),
        )
        require(map.requiredString("stateFormat") == "sync-state-v1") { "Unsupported checkpoint state format" }
        val compression = runCatching {
            SyncCheckpointCompression.valueOf(map.requiredString("compression"))
        }.getOrElse { throw IllegalArgumentException("Unsupported checkpoint compression", it) }
        return SyncCheckpointHeader(
            envelopeVersion = map.requiredInt("envelopeVersion"),
            protocolVersion = map.requiredInt("protocolVersion"),
            schemaVersion = map.requiredInt("schemaVersion"),
            cipherSuite = map.requiredCipherSuite(),
            nonceBase64Url = map.requiredString("nonce"),
            instanceId = map.requiredString("instanceId"),
            workspaceId = map.requiredString("workspaceId"),
            checkpointId = map.requiredString("checkpointId"),
            deviceId = map.requiredString("deviceId"),
            throughWorkspaceSeq = map.requiredLong("throughWorkspaceSeq"),
            keyEpoch = map.requiredInt("keyEpoch"),
            previousStableCiphertextSha256Base64Url = map.requiredString("previousStableCheckpointHash")
                .ifEmpty { null },
            compression = compression,
            uncompressedSize = map.requiredInt("uncompressedSize"),
            ciphertextSha256Base64Url = ciphertextSha256Base64Url,
        )
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): BinaryData = BinaryData.copyOf(
        DeterministicCbor.encode(json.encodeToJsonElement(serializer, value)),
    )

    private fun <T> decode(serializer: KSerializer<T>, value: BinaryData): T =
        json.decodeFromJsonElement(serializer, DeterministicCbor.decode(value.copyBytes()))

    private fun decodeExactMap(payload: BinaryData, expectedKeys: Set<String>): JsonObject {
        val decoded = DeterministicCbor.decode(payload.copyBytes()) as? JsonObject
            ?: throw IllegalArgumentException("Canonical sync header must be a CBOR map")
        require(decoded.keys == expectedKeys) { "Canonical sync header contains unexpected fields" }
        return decoded
    }

    private fun JsonObject.requiredString(name: String): String {
        val primitive = this[name] as? JsonPrimitive ?: throw IllegalArgumentException("Missing header field $name")
        require(primitive.isString) { "Header field $name must be text" }
        return primitive.content
    }

    private fun JsonObject.requiredLong(name: String): Long {
        val primitive = this[name] as? JsonPrimitive ?: throw IllegalArgumentException("Missing header field $name")
        return primitive.longOrNull ?: throw IllegalArgumentException("Header field $name must be an integer")
    }

    private fun JsonObject.requiredInt(name: String): Int {
        val value = requiredLong(name)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Header field $name is out of range" }
        return value.toInt()
    }

    private fun JsonObject.requiredCipherSuite(): dev.shinsou.kmp.sync.v2.SyncCipherSuite =
        runCatching { dev.shinsou.kmp.sync.v2.SyncCipherSuite.valueOf(requiredString("cipherSuite")) }
            .getOrElse { throw IllegalArgumentException("Unsupported sync cipher suite", it) }
}
