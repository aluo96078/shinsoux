package dev.shinsou.kmp.plugin

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** Opt-in live diagnosis of bridge.httpPost for zh.shibashuwu (SHINSOU_LIVE_SHIBASHUWU=1). */
class LiveShibashuwuSearchDesktopTest {
    @Test
    fun searchThroughTheRealRuntime() {
        if (System.getenv("SHINSOU_LIVE_SHIBASHUWU") != "1") return
        val script = File(
            System.getenv("SHIBASHUWU_SCRIPT")
                ?: "/Users/aluoexpiry/project/shinsou_nsfw_plugin/src/plugins/zh.shibashuwu.js",
        ).readText()
        listOf(emptySet<String>(), setOf("https://www.shibashuwu.net")).forEach { credentialOrigins -> runBlocking {
            val real = requireNotNull(createPlatformPinnedPluginHttpTransport())
            val transport = object : PluginHttpTransport {
                override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse =
                    real.execute(request)

                override suspend fun executeResolved(
                    request: PluginHttpRequest,
                    resolution: PluginHostResolution,
                ): PluginHttpResponse = real.executeResolved(request, resolution).also { response ->
                    if (request.method == "POST") {
                        println("LIVE POST ${request.url} headers=${request.headers.keys} -> ${response.status} bytes=${response.body.size} location=${response.headers.pluginHeaderValues("Location")}")
                    }
                }
            }
            val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
            val entry = SourceIndexEntry(
                name = "十八书屋",
                lang = "zh",
                id = 8007953520123305759L,
                baseUrl = "https://www.shibashuwu.net",
                requestOrigins = setOf("https://www.shibashuwu.net"),
                credentialOrigins = credentialOrigins,
                contentOrigins = setOf("https://www.shibashuwu.net"),
            )
            val resolver = createPlatformPluginHostResolver()
            val network = PluginNetworkClient(
                transport = transport,
                storage = storage,
                hostResolver = resolver,
            ).scopedToPolicy(entry.networkPolicy())
            val manifest = PluginManifest(
                id = "zh.shibashuwu",
                name = "十八书屋",
                version = "1.0.1",
                versionCode = 2,
                lang = "zh",
                nsfw = true,
                script = "zh.shibashuwu.js",
                signature = "",
                sources = listOf(entry),
            )
            val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
                script,
                manifest,
                ScriptPluginEnvironment(
                    network = network,
                    storage = storage,
                    hostResolver = resolver,
                    runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
                ),
            )
            try {
                val page = runtime.getSearchManga(0, "总裁", emptyList())
                println("LIVE credentialOrigins=$credentialOrigins results=${page.mangas.size} first=${page.mangas.firstOrNull()?.title}")
            } finally {
                runtime.close()
            }
        } }
    }
}
