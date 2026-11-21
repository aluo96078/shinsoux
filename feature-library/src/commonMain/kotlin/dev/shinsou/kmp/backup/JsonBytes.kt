package dev.shinsou.kmp.backup

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.json.okio.encodeToBufferedSink
import okio.Buffer

/**
 * UTF-8 JSON without a `String` in between (R3-5, docs/architecture/0006): a whole library held
 * as UTF-16 text took twice the memory of its bytes, on top of the bytes themselves. Encoding
 * fails with [IllegalArgumentException] when the result is over [maximumBytes], before the byte
 * array is allocated.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun <T> Json.encodeToUtf8(
    serializer: SerializationStrategy<T>,
    value: T,
    maximumBytes: Long = Long.MAX_VALUE,
    tooLarge: () -> String = { "Encoded JSON is too large" },
): ByteArray {
    val buffer = Buffer()
    encodeToBufferedSink(serializer, value, buffer)
    if (buffer.size > maximumBytes) {
        buffer.clear()
        throw IllegalArgumentException(tooLarge())
    }
    return buffer.readByteArray()
}

@OptIn(ExperimentalSerializationApi::class)
internal fun <T> Json.decodeFromUtf8(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
    decodeFromBufferedSource(deserializer, Buffer().write(bytes))
