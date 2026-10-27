package dev.shinsou.kmp.plugin.sandbox

import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.PerHostRequestGate
import dev.shinsou.kmp.plugin.PluginExecutionLimits
import dev.shinsou.kmp.plugin.PluginHostResolution
import dev.shinsou.kmp.plugin.PluginHostResolver
import dev.shinsou.kmp.plugin.PluginHttpRequest
import dev.shinsou.kmp.plugin.PluginHttpResponse
import dev.shinsou.kmp.plugin.PluginHttpTransport
import dev.shinsou.kmp.plugin.PluginManifest
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.PluginNetworkPolicy
import dev.shinsou.kmp.plugin.PluginRateLimit
import dev.shinsou.kmp.plugin.PluginRateLimitProvider
import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.RhinoScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.ScriptPluginRuntime
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.plugin.SourceIndexEntry
import dev.shinsou.kmp.plugin.localIndexEntries
import dev.shinsou.kmp.plugin.pluginOrigin
import dev.shinsou.kmp.plugin.sourceFailureMarker
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0002 S1 (e): official extensions must behave the same in the sandbox tier as on Rhino.
 *
 * - Every installable official source (from `../shinsou_plugin`, or `-Dshinsou.pluginRepo`):
 *   metadata, filter list, preference definitions and a popular-page call against a stub network.
 * - The S0 hook chain popular → details → chapters → last chapter's pages, compared result by
 *   result and request by request, on synthetic scenarios (always) and on the S0 recordings
 *   (`SHINSOU_SANDBOX_BENCH=<spikes/sandbox-s0/build/bench/fixtures>`, local only).
 * - The `bridge.net.fetch` rewrites in `sandbox-netfetch/` (S1 acceptance: at least two official
 *   extensions) on the same inputs: same results, same requests.
 *
 * Needs `shinsou-sandbox` (see [SandboxNativeInteropTest]); each part skips with a printed reason
 * when its inputs are missing.
 */
class SandboxOfficialParityTest {
    private val binary: File? = sandboxHelperBinary()

    @Test
    fun everyOfficialSourceMatchesRhinoOnAStubNetwork() = runBlocking {
        val bin = binary ?: return@runBlocking println("SKIPPED: shinsou-sandbox not built")
        val repository = locateRepository() ?: return@runBlocking println("SKIPPED: official plugin repository not found")
        val entries = localIndexEntries(repository).filter { it.installable && !it.referenceOnly }
        assertTrue(entries.isNotEmpty())
        val mismatches = ArrayList<String>()
        var compared = 0
        for (entry in entries) {
            val bytes = Files.readAllBytes(repository.resolve(entry.scriptUrl.substringBefore('?').substringBefore('#')))
            val manifest = PluginManifest(
                id = entry.id, name = entry.name, version = entry.version, versionCode = entry.versionCode,
                lang = entry.lang, nsfw = entry.nsfw == 1, script = "${entry.id}.js",
                signature = Sha256.hex(bytes), sources = entry.sources,
            )
            for (source in entry.sources.orEmpty()) {
                val stub = { _: PluginHttpRequest -> PluginHttpResponse(200, "<html><body></body></html>".encodeToByteArray(), emptyMap()) }
                val rhino = snapshot { RhinoScriptPluginRuntimeFactory.unsafeForTests().createForSource(bytes.decodeToString(), manifest, source, environment(source, stub)) }
                val sandbox = snapshot { sandboxFactory(bin).createForSource(bytes.decodeToString(), manifest, source, environment(source, stub)) }
                compared++
                if (rhino != sandbox) mismatches += "${entry.id}/${source.id}:\n  rhino   = $rhino\n  sandbox = $sandbox"
            }
        }
        println("parity: $compared official sources compared, ${mismatches.size} mismatches")
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    /**
     * Synthetic API responses (`sandbox-netfetch/fixtures/<package>.json`, no recorded site content) for
     * the packages with a `bridge.net.fetch` rewrite, including failure scenarios. Runs wherever the
     * helper and the plugin repository exist, CI included.
     */
    @Test
    fun syntheticScenariosMatchRhino() = runBlocking {
        val bin = binary ?: return@runBlocking println("SKIPPED: shinsou-sandbox not built")
        val repository = locateRepository() ?: return@runBlocking println("SKIPPED: official plugin repository not found")
        val packages = listOf("zh.komiic", "zh.mangacopy")
        for (packageId in packages) {
            val fixture = Json.parseToJsonElement(resource("/sandbox-netfetch/fixtures/$packageId.json")).jsonObject
            assertEquals(packageId, fixture["package"]!!.jsonPrimitive.content)
            val scenarios = fixture["scenarios"]!!.jsonArray
            assertTrue(scenarios.any { it.jsonObject["complete"]!!.jsonPrimitive.boolean }, "$packageId has no complete scenario")
            for (scenario in scenarios.map { it.jsonObject }) {
                val routes = Routes(scenario["routes"]!!.jsonArray.map { it.jsonObject })
                val label = "$packageId/${scenario["name"]!!.jsonPrimitive.content}"
                compareTiers(bin, repository, packageId, label, routes.origins, routes::respond,
                    requireVariant = true, expectComplete = scenario["complete"]!!.jsonPrimitive.boolean)
            }
        }
    }

    /** The S0 recordings (`SHINSOU_SANDBOX_BENCH`); local only, the responses are real site content. */
    @Test
    fun recordedFixturesMatchRhino() = runBlocking {
        val bin = binary ?: return@runBlocking println("SKIPPED: shinsou-sandbox not built")
        val fixtures = System.getenv("SHINSOU_SANDBOX_BENCH")?.let(Path::of)
            ?: return@runBlocking println("SKIPPED (local only): SHINSOU_SANDBOX_BENCH not set")
        val repository = locateRepository() ?: return@runBlocking println("SKIPPED: official plugin repository not found")
        val dirs = Files.list(fixtures).use { s -> s.filter { Files.isRegularFile(it.resolve("index.json")) }.sorted().toList() }
        assertTrue(dirs.isNotEmpty())
        for (dir in dirs) {
            val packageId = dir.fileName.toString()
            val responses = recordedResponses(dir)
            compareTiers(bin, repository, packageId, packageId, responses.origins, responses::respond,
                requireVariant = false, expectComplete = true)
        }
    }

    /**
     * Rhino (synchronous original) vs the sandbox (same script) vs the sandbox running the
     * `bridge.net.fetch` rewrite: same hook results and the same requests, in the same order for
     * the synchronous tiers.
     */
    private suspend fun compareTiers(
        bin: File,
        repository: Path,
        packageId: String,
        label: String,
        responseOrigins: Set<String>,
        respond: (PluginHttpRequest) -> PluginHttpResponse,
        requireVariant: Boolean,
        expectComplete: Boolean,
    ) {
        val script = Files.readString(repository.resolve("plugins/$packageId.js"))
        val baseUrl = Regex("""baseUrl:\s*"([^"]+)"""").find(script)!!.groupValues[1]
        // Every origin the script may try (mirrors included), so policy denials cannot mask differences.
        val scriptOrigins = Regex("""https://[A-Za-z0-9.-]+""").findAll(script).map { it.value }.toSet()
        val source = SourceIndexEntry(packageId, "zh", 1L, baseUrl, requestOrigins = responseOrigins + scriptOrigins + pluginOrigin(io.ktor.http.Url(baseUrl)))
        val manifest = PluginManifest(
            id = packageId, name = packageId, version = "0", versionCode = 1, lang = "zh",
            script = "$packageId.js", signature = Sha256.hex(script.encodeToByteArray()), sources = listOf(source),
        )
        val rhinoRequests = ArrayList<String>()
        val rhino = hookChain { RhinoScriptPluginRuntimeFactory.unsafeForTests().createForSource(script, manifest, source, environment(source, logging(respond, rhinoRequests))) }
        val sandboxRequests = ArrayList<String>()
        val sandbox = hookChain { sandboxFactory(bin).createForSource(script, manifest, source, environment(source, logging(respond, sandboxRequests))) }
        println("parity $label: ${rhino.summary}")
        assertEquals(expectComplete, rhino.completed, "$label Rhino chain: ${rhino.steps.last()}")
        assertSameChain(rhino, sandbox, label)
        assertEquals(rhinoRequests, sandboxRequests, "$label requests")

        val variant = javaClass.getResource("/sandbox-netfetch/$packageId.js")?.readText()
        if (variant == null) {
            assertTrue(!requireVariant, "$packageId has no net.fetch variant")
            return
        }
        assertTrue(!Regex("""bridge\.http\w+\(""").containsMatchIn(variant), "$packageId variant still uses the synchronous bridge")
        val variantRequests = ArrayList<String>()
        val variantManifest = manifest.copy(signature = Sha256.hex(variant.encodeToByteArray()))
        val fetched = hookChain { sandboxFactory(bin).createForSource(variant, variantManifest, source, environment(source, logging(respond, variantRequests))) }
        println("parity $label (net.fetch): ${fetched.summary}")
        assertSameChain(rhino, fetched, "$label (net.fetch)")
        assertEquals(rhinoRequests, variantRequests, "$label (net.fetch) requests")
    }

    private fun assertSameChain(expected: Chain, actual: Chain, label: String) {
        assertEquals(expected.completed, actual.completed, "$label completion: ${actual.steps.lastOrNull()}")
        expected.steps.zip(actual.steps).forEach { (r, s) -> assertEquals(normalizeNow(r), normalizeNow(s), label) }
        assertEquals(expected.steps.size, actual.steps.size, label)
    }

    private fun logging(respond: (PluginHttpRequest) -> PluginHttpResponse, into: MutableList<String>): (PluginHttpRequest) -> PluginHttpResponse = { request ->
        synchronized(into) { into += key(request.method == "POST", request.url, request.body.decodeToString()) }
        respond(request)
    }

    private fun resource(path: String): String = requireNotNull(javaClass.getResource(path)) { "missing test resource $path" }.readText()

    /** Synthetic routes: first match by method, exact URL and optional body substring; else 599. */
    private class Routes(private val routes: List<JsonObject>) {
        val origins: Set<String> = routes.map { pluginOrigin(io.ktor.http.Url(it["url"]!!.jsonPrimitive.content)) }.toSet()

        fun respond(request: PluginHttpRequest): PluginHttpResponse {
            val body = request.body.decodeToString()
            val route = routes.firstOrNull { route ->
                route["method"]!!.jsonPrimitive.content == request.method &&
                    route["url"]!!.jsonPrimitive.content == request.url &&
                    route["bodyContains"]?.jsonPrimitive?.content?.let(body::contains) != false
            } ?: return PluginHttpResponse(599, ByteArray(0), emptyMap())
            val payload = route["json"]?.toString() ?: route["text"]!!.jsonPrimitive.content
            return PluginHttpResponse(route["status"]!!.jsonPrimitive.int, payload.encodeToByteArray(), emptyMap())
        }
    }

    private fun sandboxFactory(bin: File) = SandboxRuntimeFactory(transports = { DesktopSandboxTransport(listOf(bin.path)) })

    private fun environment(source: SourceIndexEntry, respond: (PluginHttpRequest) -> PluginHttpResponse): ScriptPluginEnvironment {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        return ScriptPluginEnvironment(
            network = PluginNetworkClient(
                transport = object : PluginHttpTransport {
                    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse = respond(request)
                    override suspend fun executeResolved(request: PluginHttpRequest, resolution: PluginHostResolution) = respond(request)
                },
                storage = storage,
                policy = PluginNetworkPolicy(requestOrigins = source.requestOrigins, credentialOrigins = source.credentialOrigins),
                hostResolver = PluginHostResolver { listOf("93.184.216.34") },
                requestGate = PerHostRequestGate(PluginRateLimitProvider { PluginRateLimit(1_000, 0) }),
            ),
            storage = storage,
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
            executionLimits = PluginExecutionLimits(invocationWallTimeMillis = 10_000),
        )
    }

    /** Everything observable without content: metadata, filters, preferences, one stubbed call. */
    private suspend fun snapshot(create: suspend () -> ScriptPluginRuntime): String {
        val runtime = try { create() } catch (e: Throwable) { return "init failed: ${outcome(e)}" }
        return try {
            withTimeout(30_000) {
                listOf(
                    "id=${runtime.id} name=${runtime.name} lang=${runtime.lang} baseUrl=${runtime.baseUrl}",
                    "latest=${runtime.supportsLatest} favorites=${runtime.supportsFavorites} login=${runtime.supportsLogin}",
                    "headers=${runtime.headers.toSortedMap()}",
                    "challenge=${runtime.webChallengeUrl} origins=${runtime.browserSessionOrigins.sorted()}",
                    "localStorage=${runtime.webChallengeLocalStorageKeys.sorted()} required=${runtime.requiredWebChallengeLocalStorageKeys.sorted()}",
                    "filters=${runCatching { runtime.getFilterList() }.fold({ it.toString() }, ::outcome)}",
                    "preferences=${runCatching { runtime.getPreferenceDefinitions() }.fold({ it.toString() }, ::outcome)}",
                    "popular=${runCatching { runtime.getPopularManga(1) }.fold({ it.toString() }, ::outcome)}",
                ).joinToString("\n    ")
            }
        } finally {
            runtime.close()
        }
    }

    /** Hook results in order; [completed] only when all four hooks returned. */
    private class Chain(val steps: List<String>, val completed: Boolean, val summary: String)

    private suspend fun hookChain(create: suspend () -> ScriptPluginRuntime): Chain {
        val runtime = create()
        val steps = ArrayList<String>()
        try {
            return withTimeout(60_000) {
                val popular = runtime.getPopularManga(1).also { steps += it.toString() }
                val details = runtime.getMangaDetails(popular.mangas.first()).also { steps += it.toString() }
                val chapters = runtime.getChapterList(details).also { steps += it.toString() }
                val pages = runtime.getPageList(chapters.last()).also { steps += it.toString() }
                Chain(steps, true, "popular=${popular.mangas.size} chapters=${chapters.size} pages=${pages.size}")
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) throw e
            steps += "failed: ${outcome(e)}"
            return Chain(steps, false, "stopped after ${steps.size - 1} hooks: ${steps.last()}")
        } finally {
            runtime.close()
        }
    }

    /**
     * Failure category only: the engines word script errors differently (Rhino's
     * JavaScriptException/EcmaError vs the sandbox's SCRIPT_ERROR code), but the category and any
     * host-owned source-failure marker (the app's guidance, SourceFailureMarker.kt) must match.
     */
    private fun outcome(e: Throwable): String = when {
        e is SandboxInvocationException && e.code == SandboxErrorCode.SCRIPT_ERROR -> "error(script${e.sourceFailureMarker()?.let { ":$it" }.orEmpty()})"
        e is org.mozilla.javascript.RhinoException -> "error(script${e.sourceFailureMarker()?.let { ":$it" }.orEmpty()})"
        e is SandboxInvocationException -> "error(${e.code})"
        else -> "error(${e::class.simpleName})"
    }

    /** `Date.now()` fallbacks (e.g. wnacg's chapter date) differ by the milliseconds between runs. */
    private fun normalizeNow(text: String): String {
        val now = System.currentTimeMillis()
        return Regex("""dateUpload=(\d{13})""").replace(text) { m ->
            if (kotlin.math.abs(m.groupValues[1].toLong() - now) < 600_000) "dateUpload=NOW" else m.value
        }
    }

    private class Recorded(val byKey: Map<String, PluginHttpResponse>, val origins: Set<String>) {
        fun respond(request: PluginHttpRequest): PluginHttpResponse =
            byKey[key(request.method == "POST", request.url, request.body.decodeToString())] ?: PluginHttpResponse(599, ByteArray(0), emptyMap())
    }

    private fun recordedResponses(dir: Path): Recorded {
        val index = Json.parseToJsonElement(Files.readString(dir.resolve("index.json"))).jsonObject
        val byKey = index.map { (name, value) ->
            val request: JsonObject = value.jsonObject
            val post = request["method"]!!.jsonPrimitive.content.startsWith("httpPost")
            val body = Json.parseToJsonElement(Files.readString(dir.resolve("$name.body.json"))).jsonPrimitive.content
            val status = Files.readString(dir.resolve("$name.status")).trim().toInt()
            key(post, request["url"]!!.jsonPrimitive.content, request["body"]?.jsonPrimitive?.contentOrNull.orEmpty()) to
                PluginHttpResponse(status, body.encodeToByteArray(), emptyMap())
        }.toMap()
        val origins = index.values.map { pluginOrigin(io.ktor.http.Url(it.jsonObject["url"]!!.jsonPrimitive.content)) }.toSet()
        return Recorded(byKey, origins)
    }

    private fun locateRepository(): Path? =
        listOfNotNull(System.getProperty("shinsou.pluginRepo"), "../shinsou_plugin", "../../shinsou_plugin")
            .map { Path.of(it).toAbsolutePath() }
            .firstOrNull { Files.isRegularFile(it.resolve("index.json")) }
}

private fun key(post: Boolean, url: String, body: String) = "${if (post) "POST" else "GET"} $url ${if (post) body else ""}"
