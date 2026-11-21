package dev.shinsou.kmp.sync.v3

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs the cross-language vectors shared with the sync server.
 *
 * The JSON files under `resources/sync-v3` are copies of `shinsou_sync_service/api/vectors/`; that repository is
 * the source of truth, so update the copies whenever the server's vectors change. The `merge`
 * section of lww.json is run against [LwwMerge] by SyncV3MergeVectorTest.
 */
class SyncV3VectorTest {
    @Test
    fun workKeyValidVectors() {
        val cases = load("work-key.json")["valid"]!!.jsonArray
        assertTrue(cases.isNotEmpty())
        cases.forEach { element ->
            val case = element.jsonObject
            val name = case.string("name")
            val key = case["input"]!!.jsonObject.toWorkKey()
            assertEquals(case.string("canonical"), key.canonical, name)
            assertEquals(case.string("hash"), key.hash, name)
            case["units"]!!.jsonArray.forEach { unitElement ->
                val unit = unitElement.jsonObject
                val unitId = unit.string("unitRemoteId")
                assertEquals(unit.string("canonical"), key.unitCanonical(unitId), "$name / unit")
                assertEquals(unit.string("hash"), key.unitHash(unitId), "$name / unit")
            }
        }
    }

    @Test
    fun workKeyInvalidVectors() {
        val cases = load("work-key.json")["invalid"]!!.jsonArray
        assertTrue(cases.isNotEmpty())
        cases.forEach { element ->
            val case = element.jsonObject
            val unitId = case["unitRemoteId"]?.jsonPrimitive?.content
            assertFailsWith<IllegalArgumentException>(case.string("name")) {
                val key = case["input"]!!.jsonObject.toWorkKey()
                if (unitId != null) key.unitHash(unitId)
            }
        }
    }

    @Test
    fun workKeyRejectsLoneSurrogates() {
        assertFailsWith<IllegalArgumentException> { WorkKey(1, "p", "s", "a\uD800b") }
        assertFailsWith<IllegalArgumentException> { WorkKey(1, "p", "s", "a\uDC00") }
        WorkKey(1, "p", "s", "📚") // a proper pair is fine
    }

    @Test
    fun hlcVectors() {
        val hlc = load("lww.json")["hlc"]!!.jsonObject
        hlc["valid"]!!.jsonArray.forEach {
            val value = it.jsonPrimitive.content
            assertEquals(value, SyncClock.format(SyncClock.parse(value)))
        }
        hlc["invalid"]!!.jsonArray.forEach {
            val value = it.jsonPrimitive.content
            assertFailsWith<IllegalArgumentException>(value) { SyncClock.parse(value) }
        }
        hlc["ordered"]!!.jsonArray.forEach { pair ->
            val (a, b) = pair.jsonArray.map { SyncClock.parse(it.jsonPrimitive.content) }
            assertTrue(a < b && b > a, "$a < $b")
        }
    }

    private fun load(name: String): JsonObject {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("sync-v3/$name")) {
            "Missing test resource sync-v3/$name"
        }.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.toWorkKey(): WorkKey = WorkKey(
        keyVersion = this["keyVersion"]!!.jsonPrimitive.int,
        packageId = string("packageId"),
        sourceId = string("sourceId"),
        remoteId = string("remoteId"),
    )
}
