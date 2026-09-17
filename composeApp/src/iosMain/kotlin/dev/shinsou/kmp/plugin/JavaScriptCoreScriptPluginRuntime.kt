@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.shinsou.kmp.plugin

import io.ktor.http.Url
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import platform.Foundation.NSLock
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSContextGetGlobalObject
import platform.JavaScriptCore.JSContextRef
import platform.JavaScriptCore.JSGlobalContextRef
import platform.JavaScriptCore.JSObjectMakeFunctionWithCallback
import platform.JavaScriptCore.JSObjectRef
import platform.JavaScriptCore.JSObjectSetProperty
import platform.JavaScriptCore.JSStringCreateWithUTF8CString
import platform.JavaScriptCore.JSStringGetMaximumUTF8CStringSize
import platform.JavaScriptCore.JSStringGetUTF8CString
import platform.JavaScriptCore.JSStringGetLength
import platform.JavaScriptCore.JSStringRef
import platform.JavaScriptCore.JSStringRelease
import platform.JavaScriptCore.JSValueMakeString
import platform.JavaScriptCore.JSValueRef
import platform.JavaScriptCore.JSValueRefVar
import platform.JavaScriptCore.JSValueToStringCopy
import platform.JavaScriptCore.JSValue
import platform.JavaScriptCore.kJSPropertyAttributeNone
import platform.posix.size_t
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import dev.shinsou.kmp.plugin.events.PluginEventDisposition
import dev.shinsou.kmp.plugin.events.PluginEventReceipt

/** JavaScriptCore-backed synchronous plugin runtime for both iOS device and simulator targets. */
public class JavaScriptCoreScriptPluginRuntimeFactory private constructor(
    private val allowUninterruptibleScriptsForTests: Boolean,
) : ScriptPluginRuntimeFactory {
    public constructor() : this(false)

    internal companion object {
        /** Tests only: production callers must establish reviewed artifact provenance instead. */
        fun unsafeForTests(): JavaScriptCoreScriptPluginRuntimeFactory =
            JavaScriptCoreScriptPluginRuntimeFactory(allowUninterruptibleScriptsForTests = true)
    }
    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime {
        environment.requireRuntimePermission(PluginRuntimePermission.EXECUTE_SCRIPT)
        val source = manifest.requireLegacyExecutableSource()
        val effectiveEnvironment = requireSafeInProcessProvenance(script, manifest, source, environment)
        return JavaScriptCoreScriptPluginRuntime.create(
            script,
            manifest,
            source,
            effectiveEnvironment,
        )
    }

    override suspend fun createForSource(
        script: String,
        manifest: PluginManifest,
        source: SourceIndexEntry,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime {
        environment.requireRuntimePermission(PluginRuntimePermission.EXECUTE_SCRIPT)
        val declaredSource = manifest.requireDeclaredExecutableSource(source)
        val effectiveEnvironment = requireSafeInProcessProvenance(
            script,
            manifest,
            declaredSource,
            environment,
        )
        return JavaScriptCoreScriptPluginRuntime.create(
            script,
            manifest,
            declaredSource,
            effectiveEnvironment,
        )
    }

    private fun requireSafeInProcessProvenance(
        script: String,
        manifest: PluginManifest,
        source: SourceIndexEntry?,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginEnvironment {
        if (allowUninterruptibleScriptsForTests) return environment
        val provenance = environment.inProcessScriptProvenance
        if (provenance == null) {
            throw ScriptRuntimeUnavailableException(
                "Plugin '${manifest.id}' cannot run in-process on iOS: public JavaScriptCore APIs " +
                    "cannot forcibly interrupt non-cooperative JavaScript. Only exact host-reviewed " +
                "artifacts are enabled.",
            )
        }
        val reviewedLimits = provenance.requireExactMatch(script, manifest, source)
        return reviewedLimits?.let { environment.copy(executionLimits = it) } ?: environment
    }
}

private class JavaScriptCoreScriptPluginRuntime private constructor(
    private val manifest: PluginManifest,
    private val selectedSource: SourceIndexEntry?,
    private val environment: ScriptPluginEnvironment,
    private val engineDispatcher: CloseableCoroutineDispatcher,
) : ScriptPluginRuntime {
    private val mutex = Mutex()
    private var context: JSContext? = null
    private lateinit var contextRef: JSContextRef
    private val stateLock = NSLock()
    private val logsLock = NSLock()
    private val logs = mutableListOf<String>()
    private val limits: PluginExecutionLimits = environment.executionLimits
    private var activeInvocationJob: Job? = null
    /** Set only on the engine worker, then consumed immediately after JS evaluation returns. */
    private var bridgeCancellation: CancellationException? = null
    private var closing = false
    private var closed = false
    private var bridgeCalls = 0
    private var logBytes = 0
    private var limitFailure: PluginResourceLimitException? = null
    private var poisonedBy: PluginResourceLimitException? = null

    override val pluginId: String = manifest.id
    override var id: Long = selectedSource?.id ?: stableSourceId(manifest.id)
        private set
    override var name: String = selectedSource?.name ?: manifest.name
        private set
    override var lang: String = selectedSource?.lang ?: manifest.lang
        private set
    override var baseUrl: String = selectedSource?.baseUrl.orEmpty()
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
    override var browserSessionOrigins: Set<String> = selectedSource?.browserSessionOrigins.orEmpty()
        private set
    override var webChallengeLocalStorageKeys: Set<String> = emptySet()
        private set
    override var requiredWebChallengeLocalStorageKeys: Set<String> = emptySet()
        private set
    override val recentLogs: List<String> get() = withLogsLock { logs.toList() }

    private suspend fun initialize(script: String): Unit = mutex.withLock {
        var registered = false
        try {
            withEngineInvocation {
                val engineContext = JSContext()
                context = engineContext
                contextRef = requireNotNull(engineContext.JSGlobalContextRef())
                IosBridgeRegistry.register(contextRef, this@JavaScriptCoreScriptPluginRuntime)
                registered = true
                installNativeBridgeCallback()
                evaluate(IOS_JAVASCRIPTCORE_BOOTSTRAP, "shinsou-ios-runtime.js")
                selectedSource?.let { source ->
                    val requestedSourceId = source.canonicalSourceId ?: source.id.toString()
                    evaluate(
                        "globalThis.__shinsouRequestedSourceId=${JsonPrimitive(requestedSourceId)};" +
                            "globalThis.__shinsouRequestedSourceName=${JsonPrimitive(source.name)};" +
                            "globalThis.__shinsouRequestedSourceLang=${JsonPrimitive(source.lang)};" +
                            "globalThis.__shinsouRequestedSourceBaseUrl=${JsonPrimitive(source.baseUrl.orEmpty())};",
                        "source-selection-context.js",
                    )
                }
                evaluate(script, manifest.script)
                val sourceSelectionId: JsonElement = selectedSource?.let {
                    JsonPrimitive(it.canonicalSourceId ?: it.id.toString())
                } ?: JsonNull
                evaluate(
                    "__shinsouSelectSource($sourceSelectionId)",
                    "source-selection.js",
                )
                val metadata = parseBoundedJsonResult(
                    evaluate("__shinsouMetadata()", "metadata.js").toString_(),
                    "metadata.js",
                )
                    .jsonObject
                baseUrl = selectedSource?.baseUrl ?: metadata.string("baseUrl").orEmpty()
                supportsLatest = metadata.boolean("supportsLatest")
                supportsLogin = metadata.boolean("supportsLogin")
                supportsFavorites = metadata.boolean("supportsFavorites")
                headers = metadata["headers"].stringMap()
                if (headers.keys.none { it.equals("Referer", ignoreCase = true) } && baseUrl.isNotBlank()) {
                    headers = headers + ("Referer" to baseUrl)
                }
                webChallengeUrl = metadata.string("webChallengeUrl")?.toString()?.takeIf(String::isNotBlank)
                browserSessionOrigins = selectedSource?.browserSessionOrigins.orEmpty()
                webChallengeLocalStorageKeys = metadata["webChallengeLocalStorageKeys"]
                    .stringList()
                    .toSet()
                requiredWebChallengeLocalStorageKeys = metadata["requiredWebChallengeLocalStorageKeys"]
                    .stringList()
                    .toSet()
                evaluate("globalThis.baseUrl=${JsonPrimitive(baseUrl)};", "base-url.js")
            }
        } catch (error: Throwable) {
            // withContext has prompt cancellation: it can throw after the engine block itself
            // returned. Cleanup therefore belongs outside that block or a cancelled installation
            // could leave this runtime retained forever by IosBridgeRegistry.
            withContext(engineDispatcher + NonCancellable) {
                if (registered) IosBridgeRegistry.unregister(contextRef)
                context?.exception = null
                context = null
                withStateLock { closed = true }
            }
            throw error
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage =
        invokeMangasPage("getPopularManga", buildJsonArray { add(JsonPrimitive(page)) })

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        invokeMangasPage("getLatestUpdates", buildJsonArray { add(JsonPrimitive(page)) })

    override suspend fun getFavoriteManga(page: Int): MangasPage =
        if (hasFunction("getFavoriteManga")) {
            invokeMangasPage("getFavoriteManga", buildJsonArray { add(JsonPrimitive(page)) })
        } else {
            MangasPage(emptyList(), false)
        }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        invokeMangasPage(
            "getSearchManga",
            buildJsonArray {
                add(JsonPrimitive(page))
                add(JsonPrimitive(query))
                if (filters.isNotEmpty()) add(JsonArray(filters.map(::filterToJson)))
            },
        )

    override suspend fun getMangaDetails(manga: SManga): SManga = invokeMapped(
        "getMangaDetails",
        JsonArray(listOf(manga.toJson())),
    ) { it.jsonObject.toManga() }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = invokeMapped(
        "getChapterList",
        JsonArray(listOf(manga.toJson())),
    ) { result ->
        result.arrayOrEmpty().mapNotNull {
            (it as? JsonObject)?.toChapter()
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = invokeMapped(
        "getPageList",
        JsonArray(listOf(chapter.toJson())),
    ) { result ->
        result.arrayOrEmpty().mapIndexedNotNull { index, value ->
            (value as? JsonObject)?.toPage(index)
        }
    }

    override suspend fun resolveImageUrl(pageUrl: String): String? {
        if (!hasFunction("resolveImageUrl")) return null
        return invoke(
            "resolveImageUrl",
            JsonArray(listOf(JsonPrimitive(pageUrl))),
        ).jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
    }

    override suspend fun getFilterList(): FilterList {
        // Match original Shinsou's JSSourceProxy: getFilterList is optional and a missing method
        // means that the source accepts an empty FilterList. MangaCopy is one such source.
        if (!hasFunction("getFilterList")) return emptyList()
        return invokeMapped("getFilterList", JsonArray(emptyList())) { result ->
            result.arrayOrEmpty().mapNotNull { (it as? JsonObject)?.toFilter() }
        }
    }

    override suspend fun getPreferenceDefinitions(): List<SourcePreference> = onEngine {
        parseBoundedJsonResult(
            evaluate("__shinsouPreferences()", "preferences.js").toString_(),
            "preferences.js",
        )
            .arrayOrEmpty()
            .mapNotNull { (it as? JsonObject)?.toSourcePreference() }
    }

    override suspend fun login(username: String, password: String): Boolean =
        loginResult(username, password).loggedIn

    override suspend fun loginResult(username: String, password: String): LoginAttemptResult {
        if (!supportsLogin) return LoginAttemptResult(false)
        environment.requireRuntimePermission(PluginRuntimePermission.CREDENTIAL_ACCESS)
        val result = invoke(
            "login",
            JsonArray(listOf(JsonPrimitive(username), JsonPrimitive(password))),
        ).toLoginAttemptResult()
        if (result.loggedIn) environment.storage.setCredential(id, PluginCredential(username, password))
        return result
    }

    override suspend fun logout() {
        environment.requireRuntimePermission(PluginRuntimePermission.CREDENTIAL_ACCESS)
        environment.storage.clearCredential(id)
        if (hasFunction("logout")) invoke("logout", JsonArray(emptyList()))
    }

    override suspend fun close() {
        // Closing an obsolete runtime must also release a synchronous bridge request. Otherwise an
        // uninstall/update can wait for the transport timeout while the runtime owns its mutex.
        val invocation = withStateLock {
            closing = true
            activeInvocationJob
        }
        invocation?.cancel(CancellationException("Plugin runtime '$pluginId' is closing"))

        // Teardown is a commit: once requested, unregister the native callback, release the
        // JSContext and its heap, and close the worker even if the caller is cancelled.
        withContext(NonCancellable) {
            mutex.withLock {
                if (withStateLock { closed }) return@withLock
                try {
                    withContext(engineDispatcher) {
                        withStateLock { closed = true }
                        IosBridgeRegistry.unregister(contextRef)
                        clearLogs()
                        context?.exception = null
                        context = null
                    }
                } finally {
                    engineDispatcher.close()
                }
            }
        }
    }

    private suspend fun invokeMangasPage(method: String, arguments: JsonArray): MangasPage {
        return invokeMapped(method, arguments, resetLogs = true) { value ->
            val result = value as? JsonObject ?: return@invokeMapped MangasPage(emptyList(), false)
            MangasPage(
                result["mangas"].arrayOrEmpty().mapNotNull { (it as? JsonObject)?.toManga() },
                result["hasNextPage"].booleanValue(),
            )
        }
    }

    private suspend fun hasFunction(method: String): Boolean = onEngine {
        evaluate("typeof source[${JsonPrimitive(method)}] === 'function'", "has-function.js").toBool()
    }

    private suspend fun invoke(
        method: String,
        arguments: JsonArray,
        resetLogs: Boolean = false,
    ): JsonElement = invokeMapped(method, arguments, resetLogs) { it }

    /** Keeps response JSON parsing and potentially large result mapping off the caller/UI thread. */
    private suspend fun <T> invokeMapped(
        method: String,
        arguments: JsonArray,
        resetLogs: Boolean = false,
        transform: (JsonElement) -> T,
    ): T = onEngine {
        if (resetLogs) clearLogs()
        requireUtf8Bound(
            arguments.toString(),
            limits.maxInvocationInputBytes,
            "invocation input",
        )
        val result = evaluate(
            "__shinsouInvoke(${JsonPrimitive(method)},${JsonPrimitive(arguments.toString())})",
            "invoke-$method.js",
        ).toString_()
        transform(parseBoundedJsonResult(result, "invoke-$method.js"))
    }

    /** Keeps JavaScriptCore and its synchronous native bridge confined to one background worker. */
    private suspend fun <T> onEngine(block: () -> T): T = mutex.withLock {
        withEngineInvocation(block)
    }

    /**
     * Gives every JS evaluation its own child job. The native bridge's [runBlocking] joins this
     * job, so cancelling a search can cancel an in-flight Ktor request instead of leaving the old
     * request holding this runtime's single engine worker.
     */
    private suspend fun <T> withEngineInvocation(block: () -> T): T {
        poisonedBy?.let { throw it }
        val invocationJob = Job(currentCoroutineContext()[Job])
        withStateLock {
            check(!closed && !closing) { "Plugin runtime '$pluginId' is closed" }
            check(activeInvocationJob == null) { "Plugin runtime '$pluginId' is already executing" }
            activeInvocationJob = invocationJob
            bridgeCalls = 0
            domHandles = 0
            domNodes = 0
            limitFailure = null
        }
        return try {
            withContext(engineDispatcher + invocationJob) {
                bridgeCancellation = null
                try {
                    block().also { limitFailure?.let { throw it } }
                } catch (limited: PluginResourceLimitException) {
                    poisonedBy = limited
                    throw limited
                } finally {
                    bridgeCancellation = null
                }
            }
        } finally {
            withStateLock {
                if (activeInvocationJob === invocationJob) activeInvocationJob = null
            }
            invocationJob.complete()
        }
    }

    private fun evaluate(script: String, label: String): platform.JavaScriptCore.JSValue {
        val engineContext = requireNotNull(context) { "Plugin runtime '$pluginId' has no JSContext" }
        engineContext.exception = null
        // JSContext may stringify an uncaught object before exposing `exception`, which can
        // execute its attacker-defined toString. Catch inside the evaluation first and throw
        // only a primitive. A top-level try preserves `var source` and statement completion
        // values (unlike a function wrapper); the legacy contract exports var source/sources.
        val result = engineContext.evaluateScript(
            "try {\n$script\n} catch (__shinsouCaughtException) {\n" +
                "throw typeof __shinsouCaughtException === 'string' ? __shinsouCaughtException : 'SHINSOU_JS_REDACTED';\n}",
        )
        // Kotlin exceptions must never cross the C callback boundary. bridgeCall records
        // cancellation and returns an error value to JS; rethrow it safely once JSC returns here.
        bridgeCancellation?.let { cancellation ->
            engineContext.exception = null
            throw cancellation
        }
        limitFailure?.let { limited ->
            engineContext.exception = null
            throw limited
        }
        val exception = engineContext.exception
        if (exception != null && !exception.isUndefined && !exception.isNull) {
            // The exception text is plugin-controlled and may include credentials or response
            // bodies. Keep it out of host logs and public failures; resource-limit diagnostics
            // take the separate, host-authored path above.
            // Never coerce exception objects: user-defined toString/getters can execute more
            // JavaScript. Sources may throw a primitive marker string; every other value stays
            // redacted. Checking the primitive type and bounding its copy invokes no plugin code.
            val marker = if (exception.isString) {
                jsValueToBoundedString(contextRef, exception.JSValueRef(), 512)?.let(::sourceFailureMarker)
            } else null
            appendLog("$label: JavaScript execution failed")
            engineContext.exception = null
            throw IllegalArgumentException(marker ?: "Plugin '$pluginId' JavaScript execution failed in $label")
        }
        val value = requireNotNull(result) { "Plugin '$pluginId' returned no value while evaluating $label" }
        return boundEvaluationResult(value, label)
    }

    private fun boundEvaluationResult(value: JSValue, label: String): JSValue {
        if (value.isString) {
            val bounded = jsValueToBoundedString(
                contextRef,
                value.JSValueRef(),
                limits.maxResultBytes,
            ) ?: failLimit("Plugin '$pluginId' exceeded the ${limits.maxResultBytes}-byte result limit in $label")
            // The caller already expects a string; returning a fresh bounded value avoids a second
            // conversion/allocation of the original attacker-controlled JS string.
            return requireNotNull(JSValue.valueWithObject(bounded, requireNotNull(context)))
        }
        return value
    }

    private fun installNativeBridgeCallback() {
        val name = JSStringCreateWithUTF8CString("__shinsouBridge")
        try {
            val function = JSObjectMakeFunctionWithCallback(
                contextRef,
                name,
                staticCFunction(::iosBridgeCallback),
            )
            JSObjectSetProperty(
                contextRef,
                JSContextGetGlobalObject(contextRef),
                name,
                function,
                kJSPropertyAttributeNone,
                null,
            )
        } finally {
            JSStringRelease(name)
        }
    }

    fun bridgeCall(method: String, encodedArguments: String): String {
        val invocationContext = withStateLock { activeInvocationJob } ?: EmptyCoroutineContext
        val result = try {
            beforeBridgeCall(encodedArguments)
            runBlocking(invocationContext) {
                val arguments = runCatching { parseJson(encodedArguments).jsonArray }
                    .getOrDefault(JsonArray(emptyList()))
                executeBridgeCall(method, arguments)
            }
        } catch (cancelled: CancellationException) {
            // Do not throw through iosBridgeCallback/C. evaluate() observes this marker as soon as
            // JavaScriptCore yields back to Kotlin and restores structured cancellation.
            bridgeCancellation = cancelled
            bridgeError(cancelled)
        } catch (limited: PluginResourceLimitException) {
            resourceLimitBridgeError(limited)
        }
        return try {
            result.toString().also {
                requireUtf8Bound(it, limits.maxBridgeResultBytes, "bridge result")
            }
        } catch (limited: PluginResourceLimitException) {
            resourceLimitBridgeError(limited).toString()
        }
    }

    internal val bridgeMethodByteLimit: Int get() = limits.maxBridgeMethodBytes
    internal val bridgeArgumentByteLimit: Int get() = limits.maxBridgeArgumentBytes

    internal fun rejectOversizedBridgeMethod(): String {
        val failure = recordLimit(
            "Plugin '$pluginId' exceeded the ${limits.maxBridgeMethodBytes}-byte bridge method limit",
        )
        return resourceLimitBridgeError(failure).toString()
    }

    internal fun rejectOversizedBridgeArguments(): String {
        val failure = recordLimit(
            "Plugin '$pluginId' exceeded the ${limits.maxBridgeArgumentBytes}-byte bridge arguments limit",
        )
        return resourceLimitBridgeError(failure).toString()
    }

    private suspend fun executeBridgeCall(method: String, arguments: JsonArray): JsonElement =
        try {
            when (method) {
                "httpGet", "httpGetWithHeaders", "httpGetResponse" -> {
                    environment.requireRuntimePermission(PluginRuntimePermission.NETWORK)
                    val url = arguments.string(0)
                    val customHeaders = arguments.getOrNull(1).stringMap()
                    val response = environment.network.get(
                        id,
                        url,
                        customHeaders,
                        sourceHeaders = headers,
                        referer = headers.header("Referer"),
                    )
                    if (method == "httpGetResponse") {
                        buildJsonObject {
                            put("status", response.status)
                            put("body", response.bodyText())
                        }
                    } else {
                        JsonPrimitive(response.bodyText())
                    }
                }

                "httpPost", "httpPostResponse" -> {
                    environment.requireRuntimePermission(PluginRuntimePermission.NETWORK)
                    val response = environment.network.post(
                        id,
                        arguments.string(0),
                        arguments.string(1),
                        arguments.getOrNull(2).stringMap(),
                        sourceHeaders = headers,
                        referer = headers.header("Referer"),
                    )
                    if (method == "httpPostResponse") {
                        buildJsonObject {
                            put("status", response.status)
                            put("body", response.bodyText())
                        }
                    } else {
                        JsonPrimitive(response.bodyText())
                    }
                }

                "httpPostBatch" -> {
                    environment.requireRuntimePermission(PluginRuntimePermission.NETWORK)
                    val urls = arguments.getOrNull(0).stringList()
                    val bodies = arguments.getOrNull(1).stringList()
                    val responses = environment.network.postBatch(
                        sourceId = id,
                        urls = urls,
                        bodies = bodies,
                        headers = arguments.getOrNull(2).stringMap(),
                        sourceHeaders = headers,
                        referer = headers.header("Referer"),
                    )
                    // The reviewed ShuYue script expects bridge.httpPostBatch to return a JSON
                    // string and parses it itself. Keep the native bridge value a string rather
                    // than exposing a platform-specific JavaScript array representation.
                    JsonPrimitive(
                        encodeBoundedPluginBatchResponseBodies(
                            responses,
                            limits.maxBridgeResultBytes,
                            jsonStringWrapped = true,
                        ),
                    )
                }

                "browserSessionRequest" -> {
                    environment.requireRuntimePermission(PluginRuntimePermission.BROWSER_CHALLENGE)
                    val prepared = preparePluginBrowserSessionRequest(
                        sourceOrigin = selectedSource?.baseUrl.orEmpty(),
                        allowedOrigins = selectedSource?.browserSessionOrigins.orEmpty(),
                        request = PluginHttpRequest(
                            method = arguments.string(1),
                            url = arguments.string(0),
                            body = arguments.string(2).encodeToByteArray(),
                            headers = arguments.getOrNull(3).stringMap(),
                        ),
                    )
                    val response = environment.browserSessionTransport.executeWithNetworkPolicy(
                        sourceId = id,
                        sourceOrigin = prepared.sourceOrigin,
                        allowedOrigins = setOf(prepared.targetOrigin),
                        request = prepared.request,
                        resolver = environment.hostResolver,
                        allowDeveloperUnpinnedTransport = environment.allowDeveloperUnpinnedBrowserSession,
                    )
                    buildJsonObject {
                        put("status", response.status)
                        put("body", response.bodyText())
                    }
                }

                "log" -> {
                    val message = arguments.string(0)
                    appendLog(message)
                    JsonNull
                }

                "getPreference" -> environment.storage.getPreference(id, arguments.string(0))?.let(::JsonPrimitive)
                    ?: JsonNull
                "setPreference" -> {
                    environment.storage.setPreference(id, arguments.string(0), arguments.string(1)); JsonNull
                }
                "getCredentialUsername" -> {
                    environment.requireRuntimePermission(PluginRuntimePermission.CREDENTIAL_ACCESS)
                    environment.storage.getCredential(id)?.username?.let(::JsonPrimitive)
                    ?: JsonNull
                }
                "getCredentialPassword" -> {
                    environment.requireRuntimePermission(PluginRuntimePermission.CREDENTIAL_ACCESS)
                    environment.storage.getCredential(id)?.password?.let(::JsonPrimitive)
                    ?: JsonNull
                }
                "setCredential" -> {
                    environment.requireRuntimePermission(PluginRuntimePermission.CREDENTIAL_ACCESS)
                    environment.storage.setCredential(id, PluginCredential(arguments.string(0), arguments.string(1)))
                    JsonNull
                }
                "clearCredential" -> {
                    environment.requireRuntimePermission(PluginRuntimePermission.CREDENTIAL_ACCESS)
                    environment.storage.clearCredential(id); JsonNull
                }
                "hasCredential" -> {
                    environment.requireRuntimePermission(PluginRuntimePermission.CREDENTIAL_ACCESS)
                    JsonPrimitive(!environment.storage.getCredential(id)?.username.isNullOrEmpty())
                }
                "requestLogin" -> JsonPrimitive(
                    run {
                        if (PluginRuntimePermission.LOGIN_PROMPT !in environment.runtimePermissions) {
                            // The JS compatibility API is boolean-valued. Returning false is
                            // fail-closed; returning bridgeError would coerce to true in `!!`.
                            false
                        } else {
                            submitLegacyLoginCompatibility(
                                environment,
                                supportsLogin,
                                arguments.string(0).takeIf(String::isNotBlank),
                            )
                        }
                    },
                )
                "requestHostEvent" -> {
                    val sink = environment.systemEventSink
                    val boundScope = environment.boundPluginScope
                    val receipt = if (sink == null || boundScope == null) {
                        PluginEventReceipt(messageId = "", disposition = PluginEventDisposition.UNSUPPORTED)
                    } else {
                        try {
                            sink.submit(boundScope, arguments.string(0).encodeToByteArray())
                        } catch (_: Throwable) {
                            PluginEventReceipt(messageId = "", disposition = PluginEventDisposition.INVALID)
                        }
                    }
                    JsonPrimitive(PluginJson.encodeToString(receipt))
                }
                "getHostEventContext" -> environment.currentSystemEventContext()?.let(::JsonPrimitive) ?: JsonNull
                "getHostEventCapabilities" -> buildJsonObject {
                    val negotiation = environment.systemEventDeclaration?.let { declaration ->
                        val gateway = environment.systemEventSink as? dev.shinsou.kmp.plugin.events.PluginSystemEventGateway
                        val boundScope = environment.boundPluginScope
                        if (gateway != null && boundScope != null) gateway.negotiate(boundScope, declaration)
                        else environment.systemEventNegotiation
                    } ?: environment.systemEventNegotiation
                    put("enabled", negotiation?.enabled == true)
                    put("protocol", "dev.shinsou.system")
                    val negotiatedVersion = negotiation?.version
                    if (negotiatedVersion == null) put("version", JsonNull)
                    else put("version", negotiatedVersion)
                    put("grantedCapabilities", PluginJson.encodeToJsonElement(negotiation?.grantedCapabilities.orEmpty()))
                    put("hardLimits", PluginJson.encodeToJsonElement(negotiation?.hardLimits))
                }
                "getCookie" -> {
                    if (PluginRuntimePermission.COOKIE_STORAGE !in environment.runtimePermissions) JsonNull
                    else {
                        val target = Url(arguments.string(1))
                        val now = Clock.System.now().toEpochMilliseconds()
                        environment.storage.getCookies(id).firstOrNull {
                            it.name == arguments.string(0) && it.matches(target, now)
                        }?.value?.let(::JsonPrimitive) ?: JsonNull
                    }
                }
                "getCookies" -> {
                    if (PluginRuntimePermission.COOKIE_STORAGE !in environment.runtimePermissions) JsonObject(emptyMap())
                    else {
                        val target = Url(arguments.string(0))
                        val now = Clock.System.now().toEpochMilliseconds()
                        JsonObject(environment.storage.getCookies(id).filter { it.matches(target, now) }
                            .associate { it.name to JsonPrimitive(it.value) })
                    }
                }
                "setCookie" -> {
                    environment.requireRuntimePermission(PluginRuntimePermission.COOKIE_STORAGE)
                    val seconds = arguments.long(4)
                    environment.storage.setCookie(
                        id,
                        PluginCookie(
                            name = arguments.string(0),
                            value = arguments.string(1),
                            domain = arguments.string(2),
                            path = arguments.string(3).ifBlank { "/" },
                            expiresAtEpochMillis = boundedCookieExpiryMillis(seconds),
                        ),
                    )
                    JsonPrimitive(true)
                }
                "deleteCookie" -> {
                    environment.requireRuntimePermission(PluginRuntimePermission.COOKIE_STORAGE)
                    environment.storage.deleteCookie(id, arguments.string(0), arguments.string(1)); JsonNull
                }
                "clearCookies" -> {
                    environment.requireRuntimePermission(PluginRuntimePermission.COOKIE_STORAGE)
                    environment.storage.clearCookies(id); JsonNull
                }
                // The parser is JavaScript-side, but every parse/selection allocation is admitted
                // by these native quota calls so script code cannot bypass host-owned limits.
                // JavaScriptCore object reachability is controlled by GC, not this compatibility
                // hint. Do not decrement accounting for a forgeable/double-releasable handle.
                "domRelease", "domReleaseAll" -> JsonNull
                "domParse" -> {
                    requireDomInput(arguments.string(0))
                    buildJsonObject {
                        put("maxNodes", limits.maxDomNodes - domNodes)
                        put("maxDepth", limits.maxDomDepth)
                    }
                }
                "domParsed" -> {
                    val requested = arguments.long(0)
                    if (requested < 0 || requested > limits.maxDomNodes.toLong() - domNodes) {
                        failLimit("Plugin '$pluginId' exceeded ${limits.maxDomNodes} parsed DOM nodes")
                    }
                    domNodes += requested.toInt()
                    JsonNull
                }
                "domSelect" -> {
                    requireSelector(arguments.string(0))
                    reserveDomHandles(arguments.long(1))
                    JsonNull
                }
                "domDepth" -> {
                    failLimit("Plugin '$pluginId' exceeded DOM depth ${limits.maxDomDepth}")
                }
                "parseHtml" -> {
                    requireDomInput(arguments.string(0))
                    requireSelector(arguments.string(1))
                    arguments.getOrNull(0) ?: JsonNull
                }
                else -> buildJsonObject { put("error", "Unknown bridge method '$method'") }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (method in setOf("httpGet", "httpGetWithHeaders", "httpGetResponse")) {
                // Diagnose host failures without logging URLs, headers, bodies or credentials.
                println("SHINSOU_IOS_NETWORK_FAILURE " + iosPluginNetworkFailureCode(error))
            }
            bridgeError(error)
        }

    private fun bridgeError(error: Throwable): JsonObject = buildJsonObject {
        put("error", safeBridgeError(error))
    }

    private fun safeBridgeError(error: Throwable): String =
        error.message?.takeIf {
            it.startsWith("Plugin runtime lacks ") && it.endsWith(" permission")
        } ?: "Host operation failed"

    private fun boundedCookieExpiryMillis(seconds: Long): Long? {
        if (seconds <= 0) return null
        val now = Clock.System.now().toEpochMilliseconds()
        if (seconds > (Long.MAX_VALUE - now) / 1_000L) {
            failLimit("Plugin '$pluginId' supplied an overflowing cookie expiry")
        }
        return now + seconds * 1_000L
    }

    private fun resourceLimitBridgeError(error: PluginResourceLimitException): JsonObject = buildJsonObject {
        put("error", error.message.orEmpty())
        put("__shinsouResourceLimit", true)
    }

    private fun clearLogs() {
        withLogsLock {
            logs.clear()
            logBytes = 0
        }
    }

    private fun appendLog(message: String) {
        val bounded = boundedPluginLogMessage(message, limits.maxLogEntryBytes)
        val bytes = bounded.encodeToByteArray().size
        withLogsLock {
            while (logs.isNotEmpty() && (logs.size >= limits.maxLogEntries || logBytes + bytes > limits.maxLogBytes)) {
                logBytes -= logs.removeAt(0).encodeToByteArray().size
            }
            if (bytes <= limits.maxLogBytes) {
                logs += bounded
                logBytes += bytes
                environment.logger.log(pluginId, bounded)
            }
        }
    }

    private var domHandles = 0
    private var domNodes = 0

    private fun beforeBridgeCall(encodedArguments: String) {
        limitFailure?.let { throw it }
        bridgeCalls += 1
        if (bridgeCalls > limits.maxBridgeCallsPerInvocation) {
            failLimit("Plugin '$pluginId' exceeded ${limits.maxBridgeCallsPerInvocation} bridge calls")
        }
        requireUtf8Bound(encodedArguments, limits.maxBridgeArgumentBytes, "bridge arguments")
    }

    private fun requireDomInput(html: String) {
        requireUtf8Bound(html, limits.maxDomInputBytes, "DOM input")
    }

    private fun requireSelector(selector: String) {
        if (selector.length > limits.maxSelectorChars) {
            failLimit("Plugin '$pluginId' exceeded the ${limits.maxSelectorChars}-character selector limit")
        }
    }

    private fun reserveDomHandles(requested: Long) {
        if (requested < 0 || requested > limits.maxParsedElements.toLong()) {
            failLimit("Plugin '$pluginId' exceeded ${limits.maxParsedElements} parsed selector elements")
        }
        if (requested > limits.maxDomHandles.toLong() - domHandles) {
            failLimit("Plugin '$pluginId' exceeded ${limits.maxDomHandles} live DOM handles")
        }
        domHandles += requested.toInt()
    }

    private fun parseBoundedJsonResult(value: String?, label: String): JsonElement {
        val encoded = value.orEmpty()
        requireUtf8Bound(encoded, limits.maxResultBytes, "result")
        val parsed = parseJson(encoded)
        JsonResultLimitValidator(limits, pluginId).validate(parsed)
        return parsed
    }

    private fun requireUtf8Bound(value: String, maximum: Int, kind: String) {
        if (pluginUtf8ByteCountAtMost(value, maximum) == null) {
            failLimit("Plugin '$pluginId' exceeded the $maximum-byte $kind limit")
        }
    }

    private fun failLimit(message: String): Nothing {
        throw recordLimit(message)
    }

    private fun recordLimit(message: String): PluginResourceLimitException =
        limitFailure ?: PluginResourceLimitException(message).also { limitFailure = it }

    private inline fun <T> withLogsLock(block: () -> T): T {
        logsLock.lock()
        return try {
            block()
        } finally {
            logsLock.unlock()
        }
    }

    private inline fun <T> withStateLock(block: () -> T): T {
        stateLock.lock()
        return try {
            block()
        } finally {
            stateLock.unlock()
        }
    }

    companion object {
        suspend fun create(
            script: String,
            manifest: PluginManifest,
            selectedSource: SourceIndexEntry?,
            environment: ScriptPluginEnvironment,
        ): JavaScriptCoreScriptPluginRuntime {
            val dispatcher = newSingleThreadContext(
                "shinsou-jsc-${manifest.id}-${selectedSource?.id ?: "legacy"}",
            )
            val runtime = JavaScriptCoreScriptPluginRuntime(manifest, selectedSource, environment, dispatcher)
            try {
                runtime.initialize(script)
            } catch (error: Throwable) {
                dispatcher.close()
                throw error
            }
            return runtime
        }
    }
}

private object IosBridgeRegistry {
    private val lock = NSLock()
    private val runtimes = mutableMapOf<Long, JavaScriptCoreScriptPluginRuntime>()

    fun register(context: JSContextRef, runtime: JavaScriptCoreScriptPluginRuntime) = locked {
        runtimes[context.key()] = runtime
    }

    fun unregister(context: JSContextRef) = locked {
        runtimes.remove(context.key())
    }

    fun runtime(context: JSContextRef?): JavaScriptCoreScriptPluginRuntime? = locked {
        context?.let { runtimes[it.key()] }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try { block() } finally { lock.unlock() }
    }

    private fun JSContextRef.key(): Long = rawValue.toLong()
}

private fun iosBridgeCallback(
    context: JSContextRef?,
    function: JSObjectRef?,
    thisObject: JSObjectRef?,
    argumentCount: size_t,
    arguments: CPointer<CPointerVarOf<JSValueRef>>?,
    exception: CPointer<JSValueRefVar>?,
): JSValueRef? {
    val runtime = IosBridgeRegistry.runtime(context)
    val method = if (argumentCount > 0u) {
        jsValueToBoundedString(context, arguments?.get(0), runtime?.bridgeMethodByteLimit ?: 256)
    } else {
        ""
    }
    if (method == null) {
        val failure = runtime?.rejectOversizedBridgeMethod()
            ?: "{\"error\":\"JavaScriptCore bridge method exceeded host limit\"}"
        return jsStringValue(context, failure)
    }
    val encodedArguments = if (argumentCount > 1u) {
        jsValueToBoundedString(context, arguments?.get(1), runtime?.bridgeArgumentByteLimit ?: 4 * 1_024 * 1_024)
    } else {
        "[]"
    }
    if (encodedArguments == null && runtime != null) {
        return jsStringValue(context, runtime.rejectOversizedBridgeArguments())
    }
    val result = runtime?.bridgeCall(method, encodedArguments ?: "[]")
        ?: "{\"error\":\"JavaScriptCore runtime is unavailable\"}"
    return jsStringValue(context, result)
}

private fun jsValueToBoundedString(
    context: JSContextRef?,
    value: JSValueRef?,
    maximumUtf8Bytes: Int,
): String? {
    val string = JSValueToStringCopy(context, value, null) ?: return ""
    return try {
        val capacity = JSStringGetMaximumUTF8CStringSize(string)
        val utf16Length = JSStringGetLength(string)
        // Every non-empty UTF-16 string needs at least ceil(codeUnits / 2) UTF-8 bytes (one
        // four-byte scalar may occupy two code units). This cheap lower bound prevents an
        // attacker-sized native allocation; the exact UTF-8 bound is checked after decoding.
        if (utf16Length > maximumUtf8Bytes.toULong() * 2uL || capacity > Int.MAX_VALUE.toULong()) return null
        kotlinx.cinterop.memScoped {
            val buffer = allocArray<kotlinx.cinterop.ByteVar>(capacity.toInt())
            JSStringGetUTF8CString(string, buffer, capacity)
            buffer.toKString().takeIf { decoded ->
                pluginUtf8ByteCountAtMost(decoded, maximumUtf8Bytes) != null
            }
        }
    } finally {
        JSStringRelease(string)
    }
}

private fun jsStringValue(context: JSContextRef?, value: String): JSValueRef? {
    val string: JSStringRef? = JSStringCreateWithUTF8CString(value)
    return try { JSValueMakeString(context, string) } finally { JSStringRelease(string) }
}

private fun parseJson(value: String?): JsonElement =
    value?.let { runCatching { PluginJson.parseToJsonElement(it) }.getOrNull() } ?: JsonNull

private class JsonResultLimitValidator(
    private val limits: PluginExecutionLimits,
    private val pluginId: String,
) {
    private var totalElements = 0

    fun validate(value: JsonElement) {
        visit(value, 0)
    }

    private fun visit(value: JsonElement, depth: Int) {
        requireLimit(depth <= limits.maxResultDepth) {
            "Plugin '$pluginId' exceeded result nesting depth ${limits.maxResultDepth}"
        }
        totalElements += 1
        requireLimit(totalElements <= limits.maxResultTotalElements) {
            "Plugin '$pluginId' exceeded ${limits.maxResultTotalElements} total result elements"
        }
        when (value) {
            is JsonArray -> {
                requireLimit(value.size <= limits.maxResultArrayElements) {
                    "Plugin '$pluginId' exceeded ${limits.maxResultArrayElements} result array elements"
                }
                value.forEach { visit(it, depth + 1) }
            }
            is JsonObject -> {
                requireLimit(value.size <= limits.maxResultObjectEntries) {
                    "Plugin '$pluginId' exceeded ${limits.maxResultObjectEntries} result object entries"
                }
                value.forEach { (key, child) ->
                    requireString(key)
                    visit(child, depth + 1)
                }
            }
            is JsonPrimitive -> if (value.isString) requireString(value.content)
            JsonNull -> Unit
        }
    }

    private fun requireString(value: String) {
        requireLimit(pluginUtf8ByteCountAtMost(value, limits.maxResultStringBytes) != null) {
            "Plugin '$pluginId' exceeded the ${limits.maxResultStringBytes}-byte result string limit"
        }
    }

    private inline fun requireLimit(condition: Boolean, message: () -> String) {
        if (!condition) throw PluginResourceLimitException(message())
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false
private fun JsonArray.string(index: Int): String = getOrNull(index)?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonArray.long(index: Int): Long = getOrNull(index)?.jsonPrimitive?.longOrNull ?: 0L
private fun JsonElement?.stringList(): List<String> = (this as? JsonArray)
    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
    .orEmpty()
private fun JsonElement?.booleanValue(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonElement?.toLoginAttemptResult(): LoginAttemptResult {
    val objectValue = this as? JsonObject
    if (objectValue != null) {
        return LoginAttemptResult(
            loggedIn = objectValue.boolean("loggedIn"),
            errorMessage = objectValue.string("errorMessage")
                ?.filterNot(Char::isISOControl)
                ?.trim()
                ?.take(512)
                ?.takeIf(String::isNotBlank),
        )
    }
    // Older scripts returned a bare Boolean. Keep accepting that shape while new scripts may
    // return { loggedIn, errorMessage }.
    return LoginAttemptResult(loggedIn = booleanValue())
}
private fun JsonElement?.arrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonElement?.stringMap(): Map<String, String> = (this as? JsonObject)?.mapNotNull { (key, value) ->
    value.jsonPrimitive.contentOrNull?.let { key to it }
}?.toMap().orEmpty()

private fun SManga.toJson(): JsonObject = buildJsonObject {
    put("url", url); put("title", title); put("artist", artist); put("author", author)
    put("description", description)
    genre?.let { values -> put("genre", JsonArray(values.map(::JsonPrimitive))) }
    put("status", status.value); put("thumbnailUrl", thumbnailUrl); put("initialized", initialized)
}

private fun SChapter.toJson(): JsonObject = buildJsonObject {
    put("url", url); put("name", name); put("scanlator", scanlator)
    put("dateUpload", dateUpload); put("chapterNumber", chapterNumber)
}

private fun JsonObject.toManga(): SManga = SManga(
    url = string("url").orEmpty(),
    title = string("title").orEmpty(),
    artist = string("artist"),
    author = string("author"),
    description = string("description"),
    genre = this["genre"].arrayOrEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.takeIf { it.isNotEmpty() },
    status = MangaStatus.fromValue(this["status"]?.jsonPrimitive?.intOrNull ?: 0),
    thumbnailUrl = string("thumbnailUrl") ?: string("thumbnail_url"),
    initialized = this["initialized"].booleanValue(),
)

private fun JsonObject.toChapter(): SChapter = SChapter(
    url = string("url").orEmpty(),
    name = string("name").orEmpty(),
    scanlator = string("scanlator"),
    dateUpload = this["dateUpload"]?.jsonPrimitive?.longOrNull ?: 0L,
    chapterNumber = this["chapterNumber"]?.jsonPrimitive?.doubleOrNull ?: -1.0,
)

private fun JsonObject.toPage(fallbackIndex: Int): Page = Page(
    index = this["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex,
    url = string("url").orEmpty(),
    imageUrl = string("imageUrl"),
)

private fun filterToJson(filter: Filter): JsonObject = buildJsonObject {
    when (filter) {
        is Filter.Header -> { put("type", "header"); put("name", filter.name) }
        Filter.Separator -> { put("type", "separator"); put("name", "") }
        is Filter.Select -> {
            put("type", "select"); put("name", filter.name)
            put("values", JsonArray(filter.values.map(::JsonPrimitive))); put("state", filter.state)
        }
        is Filter.Text -> { put("type", "text"); put("name", filter.name); put("state", filter.state) }
        is Filter.CheckBox -> { put("type", "checkBox"); put("name", filter.name); put("state", filter.state) }
        is Filter.TriState -> { put("type", "triState"); put("name", filter.name); put("state", filter.state.value) }
        is Filter.Group -> {
            put("type", "group"); put("name", filter.name)
            put("filters", JsonArray(filter.filters.map(::filterToJson)))
        }
        is Filter.Sort -> {
            put("type", "sort"); put("name", filter.name)
            put("values", JsonArray(filter.values.map(::JsonPrimitive)))
            filter.selection?.let { selected ->
                putJsonObject("selection") {
                    put("index", selected.index); put("ascending", selected.ascending)
                }
            }
        }
    }
}

private fun JsonObject.toFilter(): Filter? {
    val type = string("type") ?: return null
    val name = string("name").orEmpty()
    return when (type) {
        "header" -> Filter.Header(name)
        "separator" -> Filter.Separator
        "select" -> Filter.Select(name, this["values"].strings(), this["state"]?.jsonPrimitive?.intOrNull ?: 0)
        "text" -> Filter.Text(name, string("state").orEmpty())
        "checkBox" -> Filter.CheckBox(name, this["state"].booleanValue())
        "triState" -> Filter.TriState(name, TriStateValue.fromValue(this["state"]?.jsonPrimitive?.intOrNull ?: 0))
        "group" -> Filter.Group(name, this["filters"].arrayOrEmpty().mapNotNull { (it as? JsonObject)?.toFilter() })
        "sort" -> {
            val selection = this["selection"] as? JsonObject
            Filter.Sort(
                name,
                this["values"].strings(),
                selection?.let {
                    SortSelection(it["index"]?.jsonPrimitive?.intOrNull ?: 0, it["ascending"].booleanValue())
                },
            )
        }
        else -> null
    }
}

private fun JsonObject.toSourcePreference(): SourcePreference? {
    val type = string("type")?.lowercase() ?: return null
    val key = string("key")?.takeIf { it.isNotBlank() } ?: return null
    val title = string("title")?.takeIf { it.isNotBlank() } ?: key
    val summary = string("summary").orEmpty()
    return when (type) {
        "text", "textfield", "text_field" -> SourcePreference.TextField(
            key,
            title,
            summary,
            string("defaultValue").orEmpty(),
        )
        "toggle", "switch" -> SourcePreference.Toggle(
            key,
            title,
            summary,
            this["defaultValue"].booleanValue(),
        )
        "select", "choice" -> {
            val entries = this["entries"].strings().ifEmpty { this["values"].strings() }
            val entryValues = this["entryValues"].strings().ifEmpty { entries }
            SourcePreference.Select(key, title, entries, entryValues, string("defaultValue").orEmpty())
        }
        "multiselect", "multi_select", "multichoice" -> {
            val entries = this["entries"].strings().ifEmpty { this["values"].strings() }
            val entryValues = this["entryValues"].strings().ifEmpty { entries }
            SourcePreference.MultiSelect(
                key,
                title,
                entries,
                entryValues,
                this["defaultValues"].strings().toSet(),
            )
        }
        else -> null
    }
}

private fun JsonElement?.strings(): List<String> = arrayOrEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
private fun Map<String, String>.header(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
