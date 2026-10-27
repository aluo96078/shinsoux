package dev.shinsou.kmp.plugin

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

/**
 * ADR-0002 S0 baseline: runs official extensions on the current Rhino runtime against the HTTP
 * fixtures recorded by `spikes/sandbox-s0/bench/record.py`, with the same hook chain as
 * `plugin_bench.c` (popular -> details -> chapters -> last chapter's pages).
 *
 * Opt-in only: `SHINSOU_SANDBOX_BENCH=<spikes/sandbox-s0/build/bench/fixtures>` and
 * `SHINSOU_SANDBOX_BENCH_PLUGINS=<shinsou_plugin/plugins>`.
 */
class SandboxS0RhinoBaselineTest {
    @Test
    fun measureOfficialExtensionsOnRhino() {
        val fixtures = System.getenv("SHINSOU_SANDBOX_BENCH")?.let(Path::of) ?: return
        val plugins = System.getenv("SHINSOU_SANDBOX_BENCH_PLUGINS")?.let(Path::of) ?: return
        val runs = System.getenv("SHINSOU_SANDBOX_BENCH_RUNS")?.toIntOrNull() ?: 5
        Files.list(fixtures).use { dirs -> dirs.filter { Files.isRegularFile(it.resolve("index.json")) }.sorted().toList() }
            .forEach { dir -> runBlocking { measure(dir, plugins.resolve("${dir.fileName}.js"), runs) } }
    }

    private suspend fun measure(dir: Path, scriptPath: Path, runs: Int) {
        val packageId = dir.fileName.toString()
        val index = Json.parseToJsonElement(Files.readString(dir.resolve("index.json"))).jsonObject
        val responses = index.map { (key, value) ->
            val request = value.jsonObject
            val body = Json.parseToJsonElement(Files.readString(dir.resolve("$key.body.json"))).jsonPrimitive.content
            val status = Files.readString(dir.resolve("$key.status")).trim().toInt()
            requestKey(request) to PluginHttpResponse(status, body.encodeToByteArray(), emptyMap())
        }.toMap()
        val origins = index.values.map { pluginOrigin(io.ktor.http.Url(it.jsonObject["url"]!!.jsonPrimitive.content)) }.toSet()
        val script = Files.readString(scriptPath)
        val baseUrl = Regex("""baseUrl:\s*"([^"]+)"""").find(script)?.groupValues?.get(1) ?: origins.first()
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val environment = ScriptPluginEnvironment(
            network = PluginNetworkClient(
                transport = object : PluginHttpTransport {
                    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = error("resolved only")
                    override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution): PluginHttpResponse {
                        val key = "${if (request.method == "POST") "POST" else "GET"} ${request.url} ${if (request.method == "POST") request.body.decodeToString() else ""}"
                        return responses[key] ?: PluginHttpResponse(599, ByteArray(0), emptyMap())
                    }
                },
                storage = storage,
                policy = PluginNetworkPolicy(requestOrigins = origins + pluginOrigin(io.ktor.http.Url(baseUrl))),
                hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1_000, 0) }),
            ),
            storage = storage,
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
        )
        val manifest = PluginManifest(
            id = packageId, name = packageId, version = "0", versionCode = 1, lang = "zh",
            script = "$packageId.js", signature = Sha256.hex(script.encodeToByteArray()),
            sources = listOf(SourceIndexEntry(packageId, "zh", 1L, baseUrl)),
        )
        val samples = List(5) { mutableListOf<Long>() }
        var summary = ""
        repeat(runs + 1) { run ->
            val t0 = System.nanoTime()
            val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(script, manifest, environment)
            val init = System.nanoTime() - t0
            try {
                val t1 = System.nanoTime()
                val popular = runtime.getPopularManga(1)
                val t2 = System.nanoTime()
                val manga = popular.mangas.first()
                val details = runtime.getMangaDetails(manga)
                val t3 = System.nanoTime()
                val chapters = runtime.getChapterList(details)
                val t4 = System.nanoTime()
                val pages = runtime.getPageList(chapters.last())
                val t5 = System.nanoTime()
                if (run == 0) {
                    summary = "popular=${popular.mangas.size} chapters=${chapters.size} pages=${pages.size}"
                    return@repeat // JVM warm-up run, not sampled
                }
                listOf(init, t2 - t1, t3 - t2, t4 - t3, t5 - t4).forEachIndexed { i, ns -> samples[i] += ns }
            } finally {
                runtime.close()
            }
        }
        val names = listOf("init", "popular", "details", "chapters", "pages")
        println("RHINO $packageId $summary")
        samples.forEachIndexed { i, values ->
            val sorted = values.sorted()
            println("RHINO $packageId ${names[i].padEnd(8)} median=${"%.2f".format(sorted[sorted.size / 2] / 1e6)}ms min=${"%.2f".format(sorted.first() / 1e6)}ms")
        }
    }

    private fun requestKey(request: JsonObject): String {
        val method = request["method"]!!.jsonPrimitive.content
        val post = method.startsWith("httpPost")
        val body = request["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return "${if (post) "POST" else "GET"} ${request["url"]!!.jsonPrimitive.content} ${if (post) body else ""}"
    }
}
