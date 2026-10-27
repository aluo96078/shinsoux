package dev.shinsou.kmp.plugin.sandbox

import dev.shinsou.kmp.plugin.FilterList
import dev.shinsou.kmp.plugin.LoginAttemptResult
import dev.shinsou.kmp.plugin.MangasPage
import dev.shinsou.kmp.plugin.Page
import dev.shinsou.kmp.plugin.PluginCredential
import dev.shinsou.kmp.plugin.PluginExecutionLimits
import dev.shinsou.kmp.plugin.PluginManifest
import dev.shinsou.kmp.plugin.PluginResourceLimitException
import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.SChapter
import dev.shinsou.kmp.plugin.SManga
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.ScriptPluginRuntime
import dev.shinsou.kmp.plugin.ScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.SourceIndexEntry
import dev.shinsou.kmp.plugin.SourcePreference
import dev.shinsou.kmp.plugin.boundedPluginLogMessage
import dev.shinsou.kmp.plugin.pluginUtf8ByteCountAtMost
import dev.shinsou.kmp.plugin.requireDeclaredExecutableSource
import dev.shinsou.kmp.plugin.requireLegacyExecutableSource
import dev.shinsou.kmp.plugin.requireRuntimePermission
import dev.shinsou.kmp.plugin.sandbox.SandboxScriptJson.bool
import dev.shinsou.kmp.plugin.sandbox.SandboxScriptJson.str
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.Volatile

/** Opens the platform sandbox for one package (isolated process, helper or WASM instance). */
internal fun interface SandboxTransportProvider {
    suspend fun open(packageId: String): SandboxTransport
}

/**
 * The sandbox tier's [ScriptPluginRuntimeFactory] (ADR-0002 §4.1). Every runtime gets its own
 * sandbox; scripts never run in the host process. An environment carrying in-process provenance
 * (a reviewed-only capability) is refused, so reviewed profiles cannot be enabled here.
 */
internal class SandboxRuntimeFactory(
    private val transports: SandboxTransportProvider,
    /** Host permission records → sandbox-tier policy (e.g. the COOKIE_READ grant). */
    private val policyFor: (PluginManifest, SourceIndexEntry) -> SandboxBridgePolicy = { _, _ -> SandboxBridgePolicy() },
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Test hook: scope for each session's transport pump (see [SandboxSession]). */
    private val pumpScope: kotlinx.coroutines.CoroutineScope? = null,
) : ScriptPluginRuntimeFactory {
    override suspend fun create(script: String, manifest: PluginManifest, environment: ScriptPluginEnvironment): ScriptPluginRuntime {
        val source = manifest.requireLegacyExecutableSource()
            ?: throw IllegalArgumentException("Plugin '${manifest.id}' declares no executable source")
        return createForSource(script, manifest, source, environment)
    }

    override suspend fun createForSource(
        script: String,
        manifest: PluginManifest,
        source: SourceIndexEntry,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime {
        require(environment.inProcessScriptProvenance == null) { "Sandbox tier refuses in-process script provenance" }
        manifest.requireDeclaredExecutableSource(source)
        environment.requireRuntimePermission(PluginRuntimePermission.EXECUTE_SCRIPT)
        val transport = transports.open(manifest.id)
        val runtime = SandboxScriptPluginRuntime(manifest, source, environment, transport, policyFor(manifest, source), nowEpochMillis, pumpScope)
        try {
            runtime.initialize(script)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runtime.close() }
            throw e
        }
        return runtime
    }
}

/** Host-owned sandbox limits derived from the source's execution limits (§7). */
internal fun PluginExecutionLimits.toSandboxLimits(): SandboxLimits = SandboxLimits(
    heapBytes = SANDBOX_HEAP_BYTES,
    stackBytes = SANDBOX_STACK_BYTES,
    initTimeoutMs = initializationWallTimeMillis,
    invokeTimeoutMs = invocationWallTimeMillis,
    maxBridgeCalls = maxBridgeCallsPerInvocation.toLong(),
    maxAsyncInflight = SANDBOX_MAX_ASYNC_INFLIGHT,
    maxAsyncQueue = SANDBOX_MAX_ASYNC_QUEUE,
    maxResultBytes = maxResultBytes.toLong(),
    domMaxNodes = maxDomNodes.toLong(),
    domMaxDepth = maxDomDepth.toLong(),
)

private const val SANDBOX_HEAP_BYTES = 64L shl 20
private const val SANDBOX_STACK_BYTES = 1L shl 20
private const val SANDBOX_MAX_ASYNC_INFLIGHT = 6L // §5.3
private const val SANDBOX_MAX_ASYNC_QUEUE = 64L

/**
 * [ScriptPluginRuntime] whose script runs in a sandbox. Hook arguments and results have the same
 * JSON shapes as the JavaScriptCore engine. Reserved bootstrap entry points, invoked like hooks:
 * `__shinsouSelectSource(id)`, `__shinsouMetadata()`, `__shinsouPreferences()` and
 * `__shinsouHasFunction(name)` (ADR-0002 §17.3).
 */
internal class SandboxScriptPluginRuntime(
    private val manifest: PluginManifest,
    private val source: SourceIndexEntry,
    private val environment: ScriptPluginEnvironment,
    transport: SandboxTransport,
    policy: SandboxBridgePolicy,
    nowEpochMillis: () -> Long,
    pumpScope: kotlinx.coroutines.CoroutineScope? = null,
) : ScriptPluginRuntime {
    private val limits = environment.executionLimits

    /** Bumped by [close]: bridge calls of an older generation are refused (§5.1). */
    @Volatile
    private var generation = 1L
    /**
     * Immutable snapshot replaced on every change: logs are appended by the invocation's coroutine
     * and read from any thread, and common code has no lock primitive.
     */
    @Volatile
    private var logs: List<String> = emptyList()

    private val dispatcher = SandboxBridgeDispatcher(
        environment = environment,
        source = source,
        limits = limits,
        policy = policy,
        currentGeneration = { generation },
        nowEpochMillis = nowEpochMillis,
    )

    private val session = SandboxSession(
        transport = transport,
        dispatcher = dispatcher,
        limits = limits.toSandboxLimits(),
        generation = { generation },
        onLog = ::appendLog,
        pumpScope = pumpScope,
    )

    override val pluginId: String = manifest.id
    override val id: Long = source.id
    override val name: String = source.name
    override val lang: String = source.lang
    override var baseUrl: String = source.baseUrl.orEmpty()
        private set
    override var supportsLatest: Boolean = false
        private set
    override var supportsLogin: Boolean = false
        private set
    override var supportsFavorites: Boolean = false
        private set
    override var headers: Map<String, String> = emptyMap()
        private set
    override var webChallengeUrl: String? = null
        private set
    override val browserSessionOrigins: Set<String> = source.browserSessionOrigins
    override var webChallengeLocalStorageKeys: Set<String> = emptySet()
        private set
    override var requiredWebChallengeLocalStorageKeys: Set<String> = emptySet()
        private set
    override val recentLogs: List<String> get() = logs

    internal suspend fun initialize(script: String) {
        val requestedId = source.canonicalSourceId ?: source.id.toString()
        val prelude = "globalThis.__shinsouRequestedSourceId=${JsonPrimitive(requestedId)};" +
            "globalThis.__shinsouRequestedSourceName=${JsonPrimitive(source.name)};" +
            "globalThis.__shinsouRequestedSourceLang=${JsonPrimitive(source.lang)};" +
            "globalThis.__shinsouRequestedSourceBaseUrl=${JsonPrimitive(source.baseUrl.orEmpty())};\n"
        session.initialize(prelude + script)
        call("__shinsouSelectSource", buildJsonArray { add(JsonPrimitive(requestedId)) })
        val metadata = unwrapEncoded(call("__shinsouMetadata", JsonArray(emptyList()))) as? JsonObject ?: JsonObject(emptyMap())
        baseUrl = source.baseUrl ?: metadata.str("baseUrl").orEmpty()
        supportsLatest = metadata["supportsLatest"].bool()
        supportsLogin = metadata["supportsLogin"].bool()
        supportsFavorites = metadata["supportsFavorites"].bool()
        var h = SandboxScriptJson.stringMap(metadata["headers"])
        if (h.keys.none { it.equals("Referer", ignoreCase = true) } && baseUrl.isNotBlank()) h = h + ("Referer" to baseUrl)
        headers = h
        dispatcher.sourceHeaders = h
        webChallengeUrl = metadata.str("webChallengeUrl")?.takeIf(String::isNotBlank)
        webChallengeLocalStorageKeys = SandboxScriptJson.stringList(metadata["webChallengeLocalStorageKeys"]).toSet()
        requiredWebChallengeLocalStorageKeys = SandboxScriptJson.stringList(metadata["requiredWebChallengeLocalStorageKeys"]).toSet()
    }

    override suspend fun getPopularManga(page: Int): MangasPage = mangasPage("getPopularManga", buildJsonArray { add(JsonPrimitive(page)) })

    override suspend fun getLatestUpdates(page: Int): MangasPage = mangasPage("getLatestUpdates", buildJsonArray { add(JsonPrimitive(page)) })

    override suspend fun getFavoriteManga(page: Int): MangasPage =
        if (hasFunction("getFavoriteManga")) mangasPage("getFavoriteManga", buildJsonArray { add(JsonPrimitive(page)) })
        else MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        mangasPage(
            "getSearchManga",
            buildJsonArray {
                add(JsonPrimitive(page))
                add(JsonPrimitive(query))
                if (filters.isNotEmpty()) add(JsonArray(filters.map(SandboxScriptJson::filterJson)))
            },
        )

    override suspend fun getMangaDetails(manga: SManga): SManga =
        (call("getMangaDetails", JsonArray(listOf(SandboxScriptJson.mangaJson(manga)))) as? JsonObject)?.let(SandboxScriptJson::manga)
            ?: throw IllegalStateException("Plugin '$pluginId' returned no manga details")

    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        SandboxScriptJson.chapters(call("getChapterList", JsonArray(listOf(SandboxScriptJson.mangaJson(manga)))))

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        SandboxScriptJson.pages(call("getPageList", JsonArray(listOf(SandboxScriptJson.chapterJson(chapter)))))

    override suspend fun resolveImageUrl(pageUrl: String): String? {
        if (!hasFunction("resolveImageUrl")) return null
        return (call("resolveImageUrl", JsonArray(listOf(JsonPrimitive(pageUrl)))) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }

    override suspend fun getFilterList(): FilterList {
        if (!hasFunction("getFilterList")) return emptyList()
        return SandboxScriptJson.filters(call("getFilterList", JsonArray(emptyList())))
    }

    override suspend fun getPreferenceDefinitions(): List<SourcePreference> =
        SandboxScriptJson.preferences(unwrapEncoded(call("__shinsouPreferences", JsonArray(emptyList()))))

    /**
     * The bootstrap's `__shinsouMetadata`/`__shinsouPreferences` return JSON *text* (as on
     * JavaScriptCore); the inner document gets the same bounded decode as any result.
     */
    private fun unwrapEncoded(value: JsonElement): JsonElement =
        if (value is JsonPrimitive && value.isString) SandboxScriptJson.parseBoundedResult(value.content, limits, pluginId) else value

    /** S0/S1 tests: invokes any hook with no arguments. */
    internal suspend fun invokeForTest(method: String): JsonElement = call(method, JsonArray(emptyList()))

    override suspend fun login(username: String, password: String): Boolean = loginResult(username, password).loggedIn

    override suspend fun loginResult(username: String, password: String): LoginAttemptResult {
        if (!supportsLogin) return LoginAttemptResult(false)
        environment.requireRuntimePermission(PluginRuntimePermission.CREDENTIAL_ACCESS)
        // The one host-started invocation in which the script may read or store passwords (§6.4).
        val result = SandboxScriptJson.loginResult(
            call("login", JsonArray(listOf(JsonPrimitive(username), JsonPrimitive(password))), loginInvocation = true),
        )
        if (result.loggedIn) environment.storage.setCredential(id, PluginCredential(username, password))
        return result
    }

    override suspend fun logout() {
        environment.requireRuntimePermission(PluginRuntimePermission.CREDENTIAL_ACCESS)
        environment.storage.clearCredential(id)
        if (hasFunction("logout")) call("logout", JsonArray(emptyList()))
    }

    override suspend fun close() {
        generation += 1
        session.close()
    }

    private suspend fun hasFunction(method: String): Boolean =
        call("__shinsouHasFunction", JsonArray(listOf(JsonPrimitive(method)))).bool()

    private suspend fun mangasPage(method: String, args: JsonArray): MangasPage {
        logs = emptyList()
        return SandboxScriptJson.mangasPage(call(method, args))
    }

    private suspend fun call(method: String, args: JsonArray, loginInvocation: Boolean = false): JsonElement {
        val encoded = args.toString()
        if (pluginUtf8ByteCountAtMost(encoded, limits.maxInvocationInputBytes) == null) {
            throw PluginResourceLimitException("Plugin '$pluginId' exceeded the ${limits.maxInvocationInputBytes}-byte invocation input limit")
        }
        val json = try {
            session.invoke(method, encoded, loginInvocation)
        } catch (e: SandboxInvocationException) {
            throw when (e.code) {
                SandboxErrorCode.LIMIT_MEMORY, SandboxErrorCode.LIMIT_STACK, SandboxErrorCode.LIMIT_TIME,
                SandboxErrorCode.LIMIT_BRIDGE, SandboxErrorCode.LIMIT_RESULT,
                -> PluginResourceLimitException("Plugin '$pluginId' exceeded its ${e.code.name} limit")
                else -> e
            }
        }
        return SandboxScriptJson.parseBoundedResult(json, limits, pluginId)
    }

    private fun appendLog(bytes: ByteArray) {
        val next = ArrayDeque(logs)
        next.addLast(boundedPluginLogMessage(bytes.decodeToString(), limits.maxLogEntryBytes))
        var total = next.sumOf { it.encodeToByteArray().size }
        while (next.size > limits.maxLogEntries || total > limits.maxLogBytes) total -= next.removeFirst().encodeToByteArray().size
        logs = next.toList()
    }
}
