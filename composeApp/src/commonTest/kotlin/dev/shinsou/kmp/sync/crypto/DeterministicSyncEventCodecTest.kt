package dev.shinsou.kmp.sync.crypto

import dev.shinsou.kmp.sync.v2.HlcTimestamp
import dev.shinsou.kmp.sync.v2.PortableSettingPatch
import dev.shinsou.kmp.sync.v2.SyncEvent
import dev.shinsou.kmp.sync.v2.SyncValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeterministicSyncEventCodecTest {
    @Test
    fun canonicalCborSortsKeysUsesPreferredNumbersAndRejectsAlternateEncoding() {
        val encoded = DeterministicCbor.encode(
            JsonObject(
                linkedMapOf(
                    "z" to JsonPrimitive(1),
                    "aa" to JsonPrimitive(2),
                    "a" to JsonPrimitive(3),
                    "half" to JsonPrimitive(1.5),
                ),
            ),
        )
        assertContentEquals(encoded, DeterministicCbor.encode(DeterministicCbor.decode(encoded)))
        // RFC 8949 map ordering uses encoded key length, then bytewise lexical ordering.
        assertEquals("a4616103617a01626161026468616c66f93e00", encoded.toHex())

        // Same semantic map, but z precedes a: decoder must reject non-deterministic bytes.
        assertFailsWith<IllegalArgumentException> {
            DeterministicCbor.decode("a2617a01616103".hexToBytes())
        }
    }

    @Test
    fun eventHasStableRoundTrip() {
        val codec = DeterministicSyncEventCodec()
        val event = SyncEvent(
            opId = "55555555-5555-4555-8555-555555555555",
            hlc = HlcTimestamp(1234, 2, "11111111-1111-4111-8111-111111111111"),
            mutations = listOf(
                PortableSettingPatch(
                    mapOf(
                        "general.dateFormat" to SyncValue.StringValue("yyyy-MM-dd"),
                        "reader.webtoonSidePadding" to SyncValue.LongValue(16),
                    ),
                ),
            ),
        )
        val first = codec.encodeEvent(event)
        assertEquals(first, codec.encodeEvent(event))
        assertEquals(event, codec.decodeEvent(first))
    }
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
