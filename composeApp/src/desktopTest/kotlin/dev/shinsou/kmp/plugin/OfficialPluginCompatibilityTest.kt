package dev.shinsou.kmp.plugin

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialPluginCompatibilityTest {
    @Test
    fun everyOfficialPluginCanInitializeInRhino() = runTest {
        val repository = locateRepository() ?: return@runTest
        val root = PluginJson.parseToJsonElement(Files.readString(repository.resolve("index.json"))).jsonObject
        val entries = root.getValue("packages").jsonArray.map { it.jsonObject }
        assertTrue(entries.isNotEmpty())

        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport { request ->
                PluginHttpResponse(200, "<html></html>".encodeToByteArray(), emptyMap())
            },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        val environment = ScriptPluginEnvironment(network, storage)

        entries.forEachIndexed { offset, entry ->
            val packageId = entry.getValue("id").jsonPrimitive.content
            val localScript = entry.getValue("scriptUrl").jsonPrimitive.content
                .substringBefore('?').substringBefore('#')
            val bytes = Files.readAllBytes(repository.resolve(localScript))
            val manifest = PluginManifest(
                id = packageId,
                name = entry.getValue("name").jsonPrimitive.content,
                version = entry.getValue("version").jsonPrimitive.content,
                versionCode = entry.getValue("versionCode").jsonPrimitive.intOrNull ?: 0,
                lang = entry.getValue("lang").jsonPrimitive.content,
                nsfw = entry["nsfw"]?.jsonPrimitive?.booleanOrNull == true,
                script = "$packageId.js",
                signature = Sha256.hex(bytes),
                sources = listOf(SourceIndexEntry(packageId, "all", 80_000L + offset, null)),
            )
            val runtime = RhinoScriptPluginRuntimeFactory().create(bytes.decodeToString(), manifest, environment)
            try {
                assertEquals(packageId, runtime.pluginId, packageId)
                assertTrue(runtime.name.isNotBlank(), packageId)
            } finally {
                runtime.close()
            }
        }
    }

    private fun locateRepository(): Path? {
        val candidates = listOf(
            Path.of("../shinsou_plugin"),
            System.getProperty("shinsou.pluginRepo")?.let(Path::of),
        ).filterNotNull()
        return candidates.map(Path::toAbsolutePath).firstOrNull { Files.isRegularFile(it.resolve("index.json")) }
    }
}
