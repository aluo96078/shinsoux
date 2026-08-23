package dev.shinsou.kmp.plugin

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies legacy Shinsou scripts through JVM classpath resources, independent of the test cwd. */
class WorkspacePluginCompatibilityTest {
    @Test
    fun everyRepositoryScriptInitializesInRhino() = runTest {
        val root = PluginJson.parseToJsonElement(resourceText("index.json")).jsonObject
        val packages = root.getValue("packages").jsonArray.map { it.jsonObject }
        val indexedScripts = packages.associateBy { entry ->
            entry.getValue("scriptUrl").jsonPrimitive.content
                .substringBefore('?').substringBefore('#').substringAfterLast('/')
        }
        val pluginFiles = resourceDirectory("plugins")
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "js" }
            .map { it.name }
            .sorted()

        assertEquals(
            indexedScripts.size,
            pluginFiles.size,
            "The compatibility fixture should cover every v2 repository plugin",
        )
        assertTrue(packages.isNotEmpty())
        assertTrue(
            indexedScripts.keys.all(pluginFiles::contains),
            "Every index.json script must be present in the plugins resource directory",
        )

        // Reviewed ShuYue and reference-only packages use opaque SourceKey identities and are
        // exercised by their admission/runtime suites; Rhino's legacy manifest only accepts a
        // numeric SourceIndexEntry and cannot represent those packages faithfully.
        val legacyExecutableFiles = pluginFiles.filter { fileName ->
            val entry = indexedScripts[fileName] ?: return@filter false
            entry["contract"]?.jsonPrimitive?.content == "shinsou" &&
                entry["installable"]?.jsonPrimitive?.booleanOrNull != false &&
                entry["referenceOnly"]?.jsonPrimitive?.booleanOrNull != true
        }

        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport {
                PluginHttpResponse(503, "offline fixture".encodeToByteArray(), emptyMap())
            },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        legacyExecutableFiles.forEachIndexed { offset, fileName ->
            val entry = indexedScripts[fileName]
            val pluginId = entry?.getValue("id")?.jsonPrimitive?.content ?: fileName.removeSuffix(".js")
            val script = resourceText("plugins/$fileName")
            val loginRequests = mutableListOf<Triple<Long, String, String?>>()
            val manifest = PluginManifest(
                id = pluginId,
                name = entry?.getValue("name")?.jsonPrimitive?.content ?: pluginId,
                version = entry?.getValue("version")?.jsonPrimitive?.content ?: "0.0.0",
                versionCode = entry?.getValue("versionCode")?.jsonPrimitive?.intOrNull ?: 0,
                lang = entry?.getValue("lang")?.jsonPrimitive?.content ?: "all",
                nsfw = entry?.get("nsfw")?.jsonPrimitive?.booleanOrNull == true,
                script = fileName,
                signature = "",
                sources = listOf(SourceIndexEntry(pluginId, "all", 80_000L + offset, null)),
            )
            val runtime = RhinoScriptPluginRuntimeFactory().create(
                script,
                manifest,
                ScriptPluginEnvironment(
                    network = network,
                    storage = storage,
                    loginRequester = PluginLoginRequester { sourceId, sourceName, reason ->
                        loginRequests += Triple(sourceId, sourceName, reason)
                        true
                    },
                ),
            )
            try {
                assertEquals(pluginId, runtime.pluginId)
                assertTrue(runtime.baseUrl.isNotBlank(), "$fileName did not export source.baseUrl")
                if (pluginId == "zh.bika") {
                    val page = runtime.getPopularManga(0)
                    assertTrue(page.mangas.isEmpty(), "Bika offline fixture should return an empty catalogue")
                    // A raw compatibility runtime has no host-bound artifact/source admission.
                    // Even a newer workspace script that calls bridge.requestLogin must fail closed.
                    assertEquals(emptyList(), loginRequests)
                }
            } finally {
                runtime.close()
            }
        }
    }

    private fun resourceText(path: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "Classpath resource '$path' was not found"
        }.bufferedReader().use { it.readText() }

    private fun resourceDirectory(path: String): File =
        requireNotNull(javaClass.classLoader.getResource(path)) {
            "Classpath resource directory '$path' was not found"
        }.let { url ->
            require(url.protocol == "file") { "Resource directory '$path' is not file-backed: $url" }
            File(url.toURI())
        }
}
