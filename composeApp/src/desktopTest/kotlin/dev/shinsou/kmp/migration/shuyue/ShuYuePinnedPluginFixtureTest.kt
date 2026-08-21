package dev.shinsou.kmp.migration.shuyue

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the pinned ShuYue repository snapshot using only Desktop classpath resources.
 *
 * These tests deliberately inspect JavaScript as text. They must not evaluate a downloaded
 * script, contact the live repository, or make a runtime compatibility check a substitute for
 * verifying that the exact bytes intended for migration are present.
 */
class ShuYuePinnedPluginFixtureTest {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    @Test
    fun pinnedIndexAndScriptsMatchProvenance() {
        val index = readJson("index.json").jsonArray.map { it.jsonObject }
        val provenance = readJson("provenance.json").jsonObject
        val expectedRepositoryUrl =
            "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/index.json"
        assertEquals(expectedRepositoryUrl, provenance.string("sourceUrl"))
        assertEquals("refs/heads/main", provenance.string("repositoryRef"))

        val indexRecord = provenance.objectValue("index")
        assertEquals("index.json", indexRecord.string("path"))
        val indexBytes = resourceBytes("index.json")
        assertEquals(indexRecord.long("size"), indexBytes.size.toLong())
        assertEquals(indexRecord.string("sha256"), sha256(indexBytes))

        val expectedIds = listOf("zh.wenku8", "zh.wenku8.api", "zh.biquge.tw")
        assertEquals(expectedIds, index.map { it.string("id") })
        val scriptRecords = provenance.arrayValue("scripts").map { it.jsonObject }
        assertEquals(expectedIds, scriptRecords.map { it.string("id") })
        assertEquals(expectedIds.size, index.size)

        index.zip(scriptRecords).forEach { (entry, record) ->
            val id = entry.string("id")
            assertEquals(id, record.string("id"))
            val scriptUrl = entry.string("scriptUrl")
            val path = scriptUrl.substringBefore('?').substringBefore('#')
            assertFalse(path.isEmpty(), "$id has an empty script path")
            assertFalse(path.startsWith("/"), "$id must use a relative script path")
            assertFalse(path.split('/').any { it == ".." }, "$id script path escapes fixture root")
            assertEquals(path, record.string("path"), id)
            assertTrue('?' in scriptUrl, "$id scriptUrl must retain its query-bearing version")
            assertEquals(
                entry.string("version"),
                scriptUrl.substringAfter("?v=").substringBefore('&'),
                "$id scriptUrl version",
            )
            assertEquals(entry.string("version"), record.string("version"), id)
            assertEquals(entry.long("versionCode"), record.long("versionCode"), id)

            val sourceIds = entry.arrayValue("sources").map { it.jsonObject.string("id") }
            // Source IDs are ShuYue's opaque strings. Keeping this assertion string-based
            // prevents an accidental Long/hash conversion from making migration lossy.
            assertEquals(listOf(id), sourceIds, "$id source id")

            val bytes = resourceBytes(path)
            assertEquals(record.long("size"), bytes.size.toLong(), "$id byte size")
            assertEquals(record.string("sha256"), sha256(bytes), "$id SHA-256")
            assertEquals(record.string("sourceUrl"), scriptSourceUrl(path), "$id source URL")
        }
    }

    @Test
    fun scriptsContainTheRealExportedMethodsWithoutEvaluation() {
        val index = readJson("index.json").jsonArray.map { it.jsonObject }
        val requiredMethods = setOf(
            "login",
            "search",
            "latest",
            "browseOptions",
            "browse",
            "favorite",
            "chapters",
            "chapterText",
        )

        index.forEach { entry ->
            val id = entry.string("id")
            val path = entry.string("scriptUrl").substringBefore('?').substringBefore('#')
            val source = resourceBytes(path).decodeToString()
            assertTrue(source.contains("var source = {"), "$id must export a source object")

            val methods = exportedMethodNames(source)
            assertTrue(
                methods.containsAll(requiredMethods),
                "$id is missing exported methods: ${requiredMethods - methods}",
            )
            // A version query is part of the pinned metadata, while the method list comes from
            // the actual script bytes above. No JS engine or dynamic code path is invoked here.
            assertTrue(methods.size >= requiredMethods.size, "$id exported too few methods")
        }
    }

    private fun readJson(path: String) = json.parseToJsonElement(resourceBytes(path).decodeToString())

    private fun resourceBytes(path: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream("shuyue-plugin/$path")) {
            "Classpath resource 'shuyue-plugin/$path' was not found"
        }.use { it.readBytes() }

    private fun scriptSourceUrl(path: String): String =
        "https://raw.githubusercontent.com/aluo96078/shuyue_plugin/refs/heads/main/$path"

    private fun exportedMethodNames(source: String): Set<String> {
        val methodPattern = Regex("(?m)^[ \\t]*([A-Za-z_][A-Za-z0-9_]*)[ \\t]*:[ \\t]*function[ \\t]*\\(")
        return methodPattern.findAll(source).map { it.groupValues[1] }.toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun JsonObject.string(name: String): String =
        requireNotNull(this[name]?.jsonPrimitive?.content) { "Missing JSON string '$name'" }

    private fun JsonObject.long(name: String): Long = string(name).toLong()

    private fun JsonObject.objectValue(name: String): JsonObject =
        requireNotNull(this[name]?.jsonObject) { "Missing JSON object '$name'" }

    private fun JsonObject.arrayValue(name: String) =
        requireNotNull(this[name]?.jsonArray) { "Missing JSON array '$name'" }
}
