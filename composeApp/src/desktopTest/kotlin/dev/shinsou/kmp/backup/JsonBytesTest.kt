package dev.shinsou.kmp.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** R3-5 (docs/architecture/0006): the bytes are the ones the `String` path wrote. */
class JsonBytesTest {
    @Serializable
    private data class Entry(val title: String, val pages: List<Int>)

    private val entry = Entry("書名   \"quoted\" 📖", listOf(1, 2, 3))

    @Test
    fun encodingMatchesTheStringPathSoDigestsStayTheSame() {
        val json = Json { prettyPrint = false }
        val expected = json.encodeToString(Entry.serializer(), entry).encodeToByteArray()
        val actual = json.encodeToUtf8(Entry.serializer(), entry)
        assertContentEquals(expected, actual)
        assertEquals(entry, json.decodeFromUtf8(Entry.serializer(), actual))
    }

    @Test
    fun anOversizedResultIsRefusedWithTheCallersMessage() {
        val error = assertFailsWith<IllegalArgumentException> {
            Json.encodeToUtf8(Entry.serializer(), entry, maximumBytes = 4) { "too large" }
        }
        assertEquals("too large", error.message)
    }
}
