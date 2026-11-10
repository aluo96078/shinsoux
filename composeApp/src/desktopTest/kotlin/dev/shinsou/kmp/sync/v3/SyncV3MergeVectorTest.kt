package dev.shinsou.kmp.sync.v3

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the `merge` section of the server's `api/vectors/lww.json` against [LwwMerge]. */
class SyncV3MergeVectorTest {
    private val cases = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader.getResourceAsStream("sync-v3/lww.json")).bufferedReader().use { it.readText() },
    ).jsonObject["merge"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun everyMergeVectorMatches() {
        assertTrue(cases.size >= 28)
        cases.forEach { case ->
            val name = case["name"]!!.jsonPrimitive.content
            val now = case["now"]!!.jsonPrimitive.long
            val pushes = case["pushes"]!!.jsonArray.map { it.jsonObject.toPush() }
            val expectedResults = case["results"]!!.jsonArray.map { it.jsonPrimitive.content }
            var state = case["initial"].toState()
            val results = pushes.map { push ->
                when (val outcome = LwwMerge.merge(state, push, now)) {
                    is MergeOutcome.Applied -> { state = outcome.state; "applied" }
                    is MergeOutcome.Unchanged -> "unchanged"
                    is MergeOutcome.Rejected -> outcome.wire
                }
            }
            assertEquals(expectedResults, results, "$name: results")
            assertEquals(case["expect"].toState() ?: RecordState(), state ?: RecordState(), "$name: state")
        }
    }

    @Test
    fun anyOrderCasesConvergeForEveryPermutation() {
        val anyOrder = cases.filter { it["anyOrder"]?.jsonPrimitive?.content == "true" }
        assertTrue(anyOrder.size >= 3)
        anyOrder.forEach { case ->
            val name = case["name"]!!.jsonPrimitive.content
            val now = case["now"]!!.jsonPrimitive.long
            val pushes = case["pushes"]!!.jsonArray.map { it.jsonObject.toPush() }
            val expected = case["expect"].toState()
            permutations(pushes).forEach { order ->
                var state = case["initial"].toState()
                order.forEach { push ->
                    val outcome = LwwMerge.merge(state, push, now)
                    if (outcome is MergeOutcome.Applied) state = outcome.state
                }
                assertEquals(expected ?: RecordState(), state ?: RecordState(), "$name: permutation")
                // Re-sending everything again must not change anything (idempotence).
                order.forEach { push -> assertTrue(LwwMerge.merge(state, push, now) !is MergeOutcome.Applied, name) }
            }
        }
    }

    @Test
    fun clientApplyKeepsStrictlyNewerUnpushedFieldsAndPrefersServerOnTies() {
        val local = LwwMerge.LocalState(
            fields = mapOf(
                "a" to LwwMerge.LocalField(kotlinx.serialization.json.JsonPrimitive(1), "10:0:x", dirty = true),
                "b" to LwwMerge.LocalField(kotlinx.serialization.json.JsonPrimitive(2), "10:0:x", dirty = true),
                "c" to LwwMerge.LocalField(kotlinx.serialization.json.JsonPrimitive(3), "5:0:x", dirty = true),
            ),
        )
        val remote = RecordState(
            fields = mapOf(
                "a" to FieldState(kotlinx.serialization.json.JsonPrimitive(9), "9:0:y"), // older: local wins
                "b" to FieldState(kotlinx.serialization.json.JsonPrimitive(8), "10:0:x"), // equal: server wins
                "c" to FieldState(kotlinx.serialization.json.JsonPrimitive(7), "6:0:y"), // newer: server wins
            ),
        )
        val merged = LwwMerge.applyRemote(local, remote)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(1), merged.fields.getValue("a").value)
        assertTrue(merged.fields.getValue("a").dirty)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(8), merged.fields.getValue("b").value)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(7), merged.fields.getValue("c").value)
        assertTrue(!merged.fields.getValue("b").dirty && !merged.fields.getValue("c").dirty)
    }

    private fun JsonObject.toPush(): PushInput = PushInput(
        fields = this["fields"]?.jsonObject?.toMap().orEmpty(),
        clocks = this["clocks"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
        deleted = this["deleted"]?.jsonObject?.let {
            DeletedRegister(it["value"]!!.jsonPrimitive.content.toBoolean(), it["clock"]!!.jsonPrimitive.content)
        },
    )

    private fun JsonElement?.toState(): RecordState? {
        if (this == null || this is JsonNull) return null
        val obj = jsonObject
        val clocks = obj["clocks"]?.jsonObject.orEmpty()
        return RecordState(
            fields = obj["fields"]?.jsonObject.orEmpty().mapValues { (k, v) -> FieldState(v, clocks.getValue(k).jsonPrimitive.content) },
            deleted = obj["deleted"]?.jsonObject?.let {
                DeletedRegister(it["value"]!!.jsonPrimitive.content.toBoolean(), it["clock"]!!.jsonPrimitive.content)
            },
            cleared = obj["cleared"]?.jsonPrimitive?.content,
        )
    }

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.indices.flatMap { i ->
            permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(items[i]) + it }
        }
    }
}
