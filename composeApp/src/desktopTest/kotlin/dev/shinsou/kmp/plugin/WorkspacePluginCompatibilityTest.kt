package dev.shinsou.kmp.plugin

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies the sibling repository through JVM classpath resources, independent of the test cwd. */
class WorkspacePluginCompatibilityTest {
    @Test
    fun everyRepositoryScriptInitializesInRhino() = runTest {
        val index = PluginJson.decodeFromString<List<PluginIndexEntry>>(resourceText("index.json"))
        val indexedScripts = index.associateBy { entry ->
            entry.scriptUrl.substringBefore('?').substringBefore('#').substringAfterLast('/')
        }
        val pluginFiles = resourceDirectory("plugins")
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "js" }
            .map { it.name }
            .sorted()

        assertEquals(14, pluginFiles.size, "The compatibility fixture should cover every repository plugin")
        assertTrue(index.isNotEmpty())
        assertTrue(
            indexedScripts.keys.all(pluginFiles::contains),
            "Every index.json script must be present in the plugins resource directory",
        )

        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(
            transport = PluginHttpTransport {
                PluginHttpResponse(503, "offline fixture".encodeToByteArray(), emptyMap())
            },
            storage = storage,
            requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1, 0) }),
        )
        pluginFiles.forEachIndexed { offset, fileName ->
            val entry = indexedScripts[fileName]
            val pluginId = entry?.id ?: fileName.removeSuffix(".js")
            val script = resourceText("plugins/$fileName")
            val loginRequests = mutableListOf<Triple<Long, String, String?>>()
            val manifest = PluginManifest(
                id = pluginId,
                name = entry?.name ?: pluginId,
                version = entry?.version ?: "0.0.0",
                versionCode = entry?.versionCode ?: 0,
                lang = entry?.lang ?: "all",
                nsfw = entry?.nsfw == 1,
                script = fileName,
                signature = "",
                sources = entry?.sources ?: listOf(
                    SourceIndexEntry(pluginId, "all", 80_000L + offset, null),
                ),
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
