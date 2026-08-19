package dev.shinsou.kmp.plugin

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialPluginCompatibilityTest {
    @Test
    fun everyOfficialPluginCanInitializeInRhino() = runTest {
        val repository = locateRepository() ?: return@runTest
        val entries = PluginJson.decodeFromString(
            ListSerializer(PluginIndexEntry.serializer()),
            Files.readString(repository.resolve("index.json")),
        )
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

        entries.forEach { entry ->
            val localScript = entry.scriptUrl.substringBefore('?').substringBefore('#')
            val bytes = Files.readAllBytes(repository.resolve(localScript))
            val manifest = PluginManifest(
                id = entry.id,
                name = entry.name,
                version = entry.version,
                versionCode = entry.versionCode,
                lang = entry.lang,
                nsfw = entry.nsfw == 1,
                script = "${entry.id}.js",
                signature = Sha256.hex(bytes),
                sources = entry.sources,
            )
            val runtime = RhinoScriptPluginRuntimeFactory().create(bytes.decodeToString(), manifest, environment)
            try {
                assertEquals(entry.sources?.firstOrNull()?.id, runtime.id, entry.id)
                assertTrue(runtime.name.isNotBlank(), entry.id)
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
