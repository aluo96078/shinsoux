package dev.shinsou.kmp.plugin

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import dev.shinsou.kmp.plugin.events.PluginEventDisposition
import dev.shinsou.kmp.plugin.events.PluginEventReceipt
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.mozilla.javascript.Context
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.Clock

/** Rhino is used instead of WebView so native HTTP bridge calls remain synchronous. */
public class RhinoScriptPluginRuntimeFactory : ScriptPluginRuntimeFactory {
    override suspend fun create(
        script: String,
        manifest: PluginManifest,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime = RhinoScriptPluginRuntime.create(
        script,
        manifest,
        manifest.requireLegacyExecutableSource(),
        environment,
    )

    override suspend fun createForSource(
        script: String,
        manifest: PluginManifest,
        source: SourceIndexEntry,
        environment: ScriptPluginEnvironment,
    ): ScriptPluginRuntime = RhinoScriptPluginRuntime.create(
        script,
        manifest,
        manifest.requireDeclaredExecutableSource(source),
        environment,
    )
}

private class RhinoScriptPluginRuntime private constructor(
    override val pluginId: String,
    private val manifest: PluginManifest,
    private val selectedSource: SourceIndexEntry?,
    private val environment: ScriptPluginEnvironment,
    private val dispatcher: ExecutorCoroutineDispatcher,
) : ScriptPluginRuntime {
    private lateinit var scope: Scriptable
    private lateinit var sourceObject: Scriptable
    private lateinit var bridge: RhinoPluginBridge
    private val logs = CopyOnWriteArrayList<String>()

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
    override val recentLogs: List<String> get() = logs.toList()

    private suspend fun initialize(script: String) = onEngine { context ->
        // Do not expose Rhino's Java/package gateways through the default standard-object
        // bootstrap.  The bridge is the only host surface; ClassShutter remains a second
        // defense for reflective access once the bridge is installed.
        scope = context.initSafeStandardObjects()
        bridge = RhinoPluginBridge(
            pluginId = manifest.id,
            sourceId = id,
            sourceName = name,
            environment = environment,
            scopeProvider = { scope },
            logs = logs,
        )
        // Do not pass the Kotlin bridge through Rhino's Java reflection adapter.  Besides
        // making every public method visible, that adapter makes `getClass()` a reflection
        // escape hatch even when the class shutter denies all Java packages.  The script gets a
        // native Scriptable facade whose methods are bound directly to the host object.
        ScriptableObject.putProperty(scope, "bridge", RhinoBridgeScriptable(scope, bridge))
        selectedSource?.let { source ->
            ScriptableObject.putProperty(scope, "__shinsouRequestedSourceId", source.id.toString())
            ScriptableObject.putProperty(scope, "__shinsouRequestedSourceName", source.name)
            ScriptableObject.putProperty(scope, "__shinsouRequestedSourceBaseUrl", source.baseUrl.orEmpty())
        }
        context.evaluateString(scope, RHINO_DOM_BOOTSTRAP, "shinsou-runtime.js", 1, null)
        context.evaluateString(
            scope,
            "var console={log:function(x){bridge.log(String(x));}," +
                "error:function(x){bridge.log(String(x));},warn:function(x){bridge.log(String(x));}," +
                "info:function(x){bridge.log(String(x));}};",
            "shinsou-console.js",
            1,
            null,
        )
        context.evaluateString(scope, RHINO_SYSTEM_EVENT_BOOTSTRAP, "shinsou-system-events.js", 1, null)
        context.evaluateString(scope, script, manifest.script, 1, null)
        sourceObject = selectSourceObject()

        // The repository's exact source declaration scopes host network/storage identity. A v1
        // script without source metadata can still supply its historical runtime baseUrl.
        baseUrl = selectedSource?.baseUrl ?: sourceObject.stringProperty("baseUrl").orEmpty()
        selectedSource?.let { source ->
            // String form avoids IEEE-754 loss for published 64-bit Tachiyomi ids.
            ScriptableObject.putProperty(sourceObject, "id", source.id.toString())
            ScriptableObject.putProperty(sourceObject, "name", source.name)
            ScriptableObject.putProperty(sourceObject, "lang", source.lang)
            ScriptableObject.putProperty(sourceObject, "baseUrl", baseUrl)
        }
        supportsLatest = sourceObject.booleanProperty("supportsLatest") ?: false
        supportsLogin = sourceObject.booleanProperty("supportsLogin") ?: false
        supportsFavorites = sourceObject.booleanProperty("supportsFavorites") ?: false
        bridge.supportsLogin = supportsLogin
        headers = sourceObject.property("headers").toStringMap()
        if (headers.keys.none { it.equals("Referer", ignoreCase = true) } && baseUrl.isNotEmpty()) {
            headers = headers + ("Referer" to baseUrl)
        }
        bridge.sourceHeaders = headers
        ScriptableObject.putProperty(scope, "baseUrl", baseUrl)
    }

    /**
     * v2-capable packages may export `sources` as an array or object keyed by source id.  A legacy
     * package may continue to export the single `source` object.  Selection is always by the exact
     * requested id; list order is never executable authority.
     */
    private fun selectSourceObject(): Scriptable {
        val requestedId = selectedSource?.id?.toString()
        val exported = scope.property("sources") as? Scriptable
        if (exported != null && requestedId != null) {
            val matches = exported.ids.mapNotNull { key ->
                val candidate = when (key) {
                    is Int -> exported.get(key, exported)
                    else -> exported.get(key.toString(), exported)
                } as? Scriptable ?: return@mapNotNull null
                val declaredId = candidate.stringProperty("id")
                    ?: candidate.stringProperty("sourceId")
                    ?: key.toString()
                candidate.takeIf { declaredId == requestedId }
            }
            require(matches.size == 1) {
                "Plugin '${manifest.id}' does not export exactly one source '$requestedId'"
            }
            return matches.single()
        }

        return scope.property("source") as? Scriptable
            ?: throw IllegalArgumentException("Plugin '${manifest.id}' does not export a source object")
    }

    override suspend fun getPopularManga(page: Int): MangasPage =
        invokeMangasPage("getPopularManga", listOf(page))

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        invokeMangasPage("getLatestUpdates", listOf(page))

    override suspend fun getFavoriteManga(page: Int): MangasPage =
        if (hasFunction("getFavoriteManga")) {
            invokeMangasPage("getFavoriteManga", listOf(page))
        } else {
            MangasPage(emptyList(), false)
        }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        invokeMangasPage(
            "getSearchManga",
            buildList {
                add(page)
                add(query)
                if (filters.isNotEmpty()) add(filters.map(::filterToMap))
            },
        )

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val value = invoke("getMangaDetails", listOf(manga.toMap()))
        return value.toStringAnyMap()?.toManga() ?: manga
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        invoke("getChapterList", listOf(manga.toMap())).toAnyList().mapNotNull { value ->
            value.toStringAnyMap()?.toChapter()
        }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        invoke("getPageList", listOf(mapOf("url" to chapter.url))).toAnyList().mapIndexedNotNull { index, value ->
            value.toStringAnyMap()?.toPage(index)
        }

    override suspend fun getFilterList(): FilterList {
        // HttpSource supplies an empty default in original Shinsou, and older JavaScript sources
        // commonly omit this optional hook entirely. Treat absence as "no filters" so global
        // search can still call getSearchManga instead of failing before the request starts.
        if (!hasFunction("getFilterList")) return emptyList()
        return invoke("getFilterList", emptyList()).toAnyList().mapNotNull { it.toStringAnyMap()?.toFilter() }
    }

    override suspend fun getPreferenceDefinitions(): List<SourcePreference> {
        val value = if (hasFunction("getPreferenceDefinitions")) {
            invoke("getPreferenceDefinitions", emptyList())
        } else {
            onEngine { sourceObject.property("preferences").fromRhino() }
        }
        return value.toAnyList().mapNotNull { it.toStringAnyMap()?.toSourcePreference() }
    }

    override suspend fun login(username: String, password: String): Boolean {
        if (!supportsLogin) return false
        val success = invoke("login", listOf(username, password)).toBooleanValue()
        if (success) environment.storage.setCredential(id, PluginCredential(username, password))
        return success
    }

    override suspend fun logout() {
        environment.storage.clearCredential(id)
        if (hasFunction("logout")) invoke("logout", emptyList())
    }

    override suspend fun close() {
        withContext(dispatcher) {
            bridge.releaseAll()
        }
        dispatcher.close()
    }

    private suspend fun invokeMangasPage(method: String, args: List<Any?>): MangasPage {
        logs.clear()
        bridge.releaseAll()
        val result = invoke(method, args).toStringAnyMap() ?: return MangasPage(emptyList(), false)
        val mangas = result["mangas"].toAnyList().mapNotNull { it.toStringAnyMap()?.toManga() }
        return MangasPage(mangas, result["hasNextPage"].toBooleanValue())
    }

    private suspend fun hasFunction(name: String): Boolean = onEngine {
        sourceObject.property(name) is Function
    }

    private suspend fun invoke(method: String, arguments: List<Any?>): Any? = onEngine { context ->
        bridge.releaseAll()
        val function = sourceObject.property(method) as? Function
            ?: throw IllegalArgumentException("Plugin '${manifest.id}' has no function '$method'")
        val args = arguments.map { it.toRhino(context, scope) }.toTypedArray()
        function.call(context, scope, sourceObject, args).fromRhino()
    }

    private suspend fun <T> onEngine(block: (Context) -> T): T = withContext(dispatcher) {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            context.languageVersion = Context.VERSION_ES6
            context.setClassShutter(ClassShutter { false })
            block(context)
        } finally {
            Context.exit()
        }
    }

    companion object {
        suspend fun create(
            script: String,
            manifest: PluginManifest,
            selectedSource: SourceIndexEntry?,
            environment: ScriptPluginEnvironment,
        ): RhinoScriptPluginRuntime {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "shinsou-plugin-${manifest.id}").apply { isDaemon = true }
            }
            val runtime = RhinoScriptPluginRuntime(
                manifest.id,
                manifest,
                selectedSource,
                environment,
                executor.asCoroutineDispatcher(),
            )
            try {
                runtime.initialize(script)
            } catch (error: Throwable) {
                runtime.dispatcher.close()
                throw error
            }
            return runtime
        }
    }
}

/** Host-side bridge; scripts receive only the native whitelist facade below. */
public class RhinoPluginBridge internal constructor(
    private val pluginId: String,
    private val sourceId: Long,
    private val sourceName: String,
    private val environment: ScriptPluginEnvironment,
    private val scopeProvider: () -> Scriptable,
    private val logs: MutableList<String>,
) {
    @Volatile
    internal var sourceHeaders: Map<String, String> = emptyMap()

    @Volatile
    internal var supportsLogin: Boolean = false

    private val nodes = linkedMapOf<Int, Node>()
    private var nextHandle = 0

    public fun httpGet(url: String): Any? = httpGetWithHeaders(url, null)

    public fun httpGetWithHeaders(url: String, headers: Any?): Any? = try {
        runBlocking {
            environment.network.get(
                sourceId,
                url,
                headers.toStringMap(),
                sourceHeaders = sourceHeaders,
                referer = sourceHeaders.header("Referer"),
            ).bodyText()
        }
    } catch (error: Throwable) {
        mapOf("error" to (error.message ?: error::class.simpleName.orEmpty()))
    }

    public fun httpPost(url: String, body: String, headers: Any?): Any? = try {
        runBlocking {
            environment.network.post(
                sourceId,
                url,
                body,
                headers.toStringMap(),
                sourceHeaders = sourceHeaders,
                referer = sourceHeaders.header("Referer"),
            ).bodyText()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    /** Returns a JSON string because the reviewed ShuYue script parses the bridge result itself. */
    public fun httpPostBatch(urls: Any?, bodies: Any?, headers: Any?): Any? = try {
        val urlList = urls.toStringList()
        val bodyList = bodies.toStringList()
        runBlocking {
            val responses = environment.network.postBatch(
                sourceId = sourceId,
                urls = urlList,
                bodies = bodyList,
                headers = headers.toStringMap(),
                sourceHeaders = sourceHeaders,
                referer = sourceHeaders.header("Referer"),
            )
            PluginJson.encodeToString(JsonArray(responses.map { JsonPrimitive(it.bodyText()) }))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    public fun htmlParse(html: String): Int = store(Jsoup.parse(html))
    public fun htmlParseFragment(html: String, baseUri: String): Int = store(Jsoup.parse(html, baseUri))

    public fun domSelect(handleId: Number, selector: String): IntArray =
        element(handleId)?.select(selector)?.map(::store)?.toIntArray() ?: IntArray(0)

    public fun domFirst(handleId: Number, selector: String): Int =
        element(handleId)?.selectFirst(selector)?.let(::store) ?: -1

    public fun domText(handleId: Number): String = element(handleId)?.text().orEmpty()
    public fun domOwnText(handleId: Number): String = element(handleId)?.ownText().orEmpty()
    public fun domHtml(handleId: Number): String = element(handleId)?.html().orEmpty()
    public fun domOuterHtml(handleId: Number): String = node(handleId)?.outerHtml().orEmpty()
    public fun domAttr(handleId: Number, name: String): String = element(handleId)?.attr(name).orEmpty()
    public fun domHasAttr(handleId: Number, name: String): Boolean = element(handleId)?.hasAttr(name) == true
    public fun domAbsUrl(handleId: Number, name: String): String = element(handleId)?.absUrl(name).orEmpty()
    public fun domTagName(handleId: Number): String = element(handleId)?.tagName().orEmpty()
    public fun domClassName(handleId: Number): String = element(handleId)?.className().orEmpty()
    public fun domId(handleId: Number): String = element(handleId)?.id().orEmpty()
    public fun domChildren(handleId: Number): IntArray =
        element(handleId)?.children()?.map(::store)?.toIntArray() ?: IntArray(0)
    public fun domParent(handleId: Number): Int = element(handleId)?.parent()?.let(::store) ?: -1
    public fun domNextSibling(handleId: Number): Int = element(handleId)?.nextElementSibling()?.let(::store) ?: -1
    public fun domPrevSibling(handleId: Number): Int = element(handleId)?.previousElementSibling()?.let(::store) ?: -1
    public fun domRemove(handleId: Number) { element(handleId)?.remove() }
    public fun domRelease(handleId: Number) { nodes.remove(handleId.toInt()) }
    public fun domReleaseAll() { releaseAll() }

    public fun parseHtml(html: String, selector: String): Any = try {
        Jsoup.parse(html).select(selector).map { element ->
            mapOf(
                "text" to element.text(),
                "html" to element.html(),
                "outerHtml" to element.outerHtml(),
                "attr_href" to element.attr("href"),
                "attr_src" to element.attr("src"),
                "tagName" to element.tagName(),
            )
        }
    } catch (_: Throwable) {
        html
    }

    public fun log(message: String) {
        logs += message
        environment.logger.log(pluginId, message)
    }

    /** Reviewed ShuYue scripts retain their historical `bridge.log(sourceId, message)` call. */
    public fun log(ignoredSourceId: String, message: Any?) {
        log(message?.toString().orEmpty())
    }

    public fun getPreference(key: String): String? = runBlocking {
        environment.storage.getPreference(sourceId, key)
    }

    public fun setPreference(key: String, value: String) = runBlocking {
        environment.storage.setPreference(sourceId, key, value)
    }

    public fun getCredentialUsername(): String? = runBlocking {
        environment.storage.getCredential(sourceId)?.username
    }

    public fun getCredentialPassword(): String? = runBlocking {
        environment.storage.getCredential(sourceId)?.password
    }

    public fun setCredential(username: String, password: String) = runBlocking {
        environment.storage.setCredential(sourceId, PluginCredential(username, password))
    }

    public fun clearCredential() = runBlocking { environment.storage.clearCredential(sourceId) }
    public fun hasCredential(): Boolean = runBlocking {
        !environment.storage.getCredential(sourceId)?.username.isNullOrEmpty()
    }

    /** Explicit zero-argument overload is required for Rhino's Java reflection bridge. */
    public fun requestLogin(): Boolean = requestLogin("")

    public fun requestLogin(reason: String?): Boolean {
        return submitLegacyLoginCompatibility(environment, supportsLogin, reason)
    }

    /** Single bounded native transport for every negotiated system-v1 message. */
    public fun requestHostEvent(envelope: String): String {
        val sink = environment.systemEventSink
        val boundScope = environment.boundPluginScope
        val receipt = if (sink == null || boundScope == null) {
            PluginEventReceipt(
                messageId = "",
                disposition = PluginEventDisposition.UNSUPPORTED,
            )
        } else {
            try {
                sink.submit(boundScope, envelope.encodeToByteArray())
            } catch (_: Throwable) {
                PluginEventReceipt(messageId = "", disposition = PluginEventDisposition.INVALID)
            }
        }
        return PluginJson.encodeToString(receipt)
    }

    /** Returns only the opaque host-issued handle for the current visible V2 invocation. */
    public fun getHostEventContext(): String? = environment.currentSystemEventContext()

    /** Returns a native JS object rather than a Java Map so Rhino cannot expose map methods. */
    public fun getHostEventCapabilities(): Scriptable {
        val context = Context.getCurrentContext()
            ?: throw IllegalStateException("Host event capabilities require an active Rhino context")
        val result = context.newObject(scopeProvider())
        val negotiation = hostEventNegotiation()
        ScriptableObject.putProperty(result, "enabled", negotiation?.enabled == true)
        ScriptableObject.putProperty(result, "protocol", "dev.shinsou.system")
        ScriptableObject.putProperty(result, "version", negotiation?.version ?: null)
        ScriptableObject.putProperty(
            result,
            "grantedCapabilities",
            context.newArray(
                scopeProvider(),
                rhinoObjectArray(negotiation?.grantedCapabilities.orEmpty()),
            ),
        )
        val limits = context.newObject(scopeProvider())
        negotiation?.hardLimits?.let { hardLimits ->
            ScriptableObject.putProperty(limits, "maxEnvelopeBytes", hardLimits.maxEnvelopeBytes)
            ScriptableObject.putProperty(limits, "maxNestingDepth", hardLimits.maxNestingDepth)
            ScriptableObject.putProperty(limits, "maxReasonBytes", hardLimits.maxReasonBytes)
            ScriptableObject.putProperty(limits, "maxDiagnosticBytes", hardLimits.maxDiagnosticBytes)
            ScriptableObject.putProperty(limits, "maxMapEntries", hardLimits.maxMapEntries)
            ScriptableObject.putProperty(limits, "maxListEntries", hardLimits.maxListEntries)
            ScriptableObject.putProperty(limits, "maxIdentifierBytes", hardLimits.maxIdentifierBytes)
            ScriptableObject.putProperty(limits, "maxEventNameBytes", hardLimits.maxEventNameBytes)
            ScriptableObject.putProperty(limits, "maxPerSourcePending", hardLimits.maxPerSourcePending)
            ScriptableObject.putProperty(limits, "maxGlobalPending", hardLimits.maxGlobalPending)
            ScriptableObject.putProperty(limits, "tokenBurst", hardLimits.tokenBurst)
            ScriptableObject.putProperty(limits, "tokenPerMinute", hardLimits.tokenPerMinute)
            ScriptableObject.putProperty(limits, "ttlMillis", hardLimits.ttlMillis)
            ScriptableObject.putProperty(limits, "loginCooldownMillis", hardLimits.loginCooldownMillis)
            ScriptableObject.putProperty(limits, "diagnosticAggregationMillis", hardLimits.diagnosticAggregationMillis)
            ScriptableObject.putProperty(limits, "maxDiagnosticAggregations", hardLimits.maxDiagnosticAggregations)
        }
        ScriptableObject.putProperty(result, "hardLimits", limits)
        return result
    }

    private fun hostEventNegotiation(): dev.shinsou.kmp.plugin.events.PluginSystemEventNegotiation? =
        environment.systemEventDeclaration?.let { declaration ->
            val gateway = environment.systemEventSink as? dev.shinsou.kmp.plugin.events.PluginSystemEventGateway
            val boundScope = environment.boundPluginScope
            if (gateway != null && boundScope != null) gateway.negotiate(boundScope, declaration)
            else environment.systemEventNegotiation
        } ?: environment.systemEventNegotiation

    public fun getCookie(name: String, url: String): String? = runBlocking {
        val target = Url(url)
        val now = Clock.System.now().toEpochMilliseconds()
        environment.storage.getCookies(sourceId).firstOrNull { it.name == name && it.matches(target, now) }?.value
    }

    public fun getCookies(url: String): Map<String, String> = runBlocking {
        val target = Url(url)
        val now = Clock.System.now().toEpochMilliseconds()
        environment.storage.getCookies(sourceId).filter { it.matches(target, now) }.associate { it.name to it.value }
    }

    public fun setCookie(
        name: String,
        value: String,
        domain: String,
        path: String,
        expirySeconds: Number,
    ): Boolean = runBlocking {
        val seconds = expirySeconds.toLong()
        environment.storage.setCookie(
            sourceId,
            PluginCookie(
                name = name,
                value = value,
                domain = domain,
                path = path.ifEmpty { "/" },
                expiresAtEpochMillis = if (seconds > 0) Clock.System.now().toEpochMilliseconds() + seconds * 1_000 else null,
            ),
        )
        true
    }

    public fun deleteCookie(name: String, domain: String) = runBlocking {
        environment.storage.deleteCookie(sourceId, name, domain)
    }

    public fun clearCookies() = runBlocking { environment.storage.clearCookies(sourceId) }

    internal fun releaseAll() {
        nodes.clear()
        nextHandle = 0
    }

    private fun store(node: Node): Int {
        val id = ++nextHandle
        nodes[id] = node
        return id
    }

    private fun node(id: Number): Node? = nodes[id.toInt()]
    private fun element(id: Number): Element? = node(id) as? Element
}

/**
 * Native Rhino view of [RhinoPluginBridge].
 *
 * The Java reflection adapter must not be used here: it exposes inherited methods such as
 * `getClass()` and lets a script turn an otherwise harmless host object into a reflection
 * starting point.  Each function below calls the bridge directly from host code and converts
 * collection results into native Rhino values before returning them to the script.
 */
private class RhinoBridgeScriptable(
    scope: Scriptable,
    private val bridge: RhinoPluginBridge,
) : ScriptableObject() {
    init {
        setParentScope(scope)
        setPrototype(ScriptableObject.getObjectPrototype(scope))

        bind("httpGet") { _, _, args -> bridge.httpGet(stringArg(args, 0)) }
        bind("httpGetWithHeaders") { _, _, args ->
            bridge.httpGetWithHeaders(stringArg(args, 0), rawArg(args, 1))
        }
        bind("httpPost") { _, _, args ->
            bridge.httpPost(stringArg(args, 0), stringArg(args, 1), rawArg(args, 2))
        }
        bind("httpPostBatch") { _, _, args ->
            bridge.httpPostBatch(rawArg(args, 0), rawArg(args, 1), rawArg(args, 2))
        }

        bind("htmlParse") { _, _, args -> bridge.htmlParse(stringArg(args, 0)) }
        bind("htmlParseFragment") { _, _, args ->
            bridge.htmlParseFragment(stringArg(args, 0), stringArg(args, 1))
        }
        bind("parseHtml") { _, _, args ->
            bridge.parseHtml(stringArg(args, 0), stringArg(args, 1))
        }

        bind("domSelect") { _, _, args -> bridge.domSelect(numberArg(args, 0), stringArg(args, 1)) }
        bind("domFirst") { _, _, args -> bridge.domFirst(numberArg(args, 0), stringArg(args, 1)) }
        bind("domText") { _, _, args -> bridge.domText(numberArg(args, 0)) }
        bind("domOwnText") { _, _, args -> bridge.domOwnText(numberArg(args, 0)) }
        bind("domHtml") { _, _, args -> bridge.domHtml(numberArg(args, 0)) }
        bind("domOuterHtml") { _, _, args -> bridge.domOuterHtml(numberArg(args, 0)) }
        bind("domAttr") { _, _, args -> bridge.domAttr(numberArg(args, 0), stringArg(args, 1)) }
        bind("domHasAttr") { _, _, args -> bridge.domHasAttr(numberArg(args, 0), stringArg(args, 1)) }
        bind("domAbsUrl") { _, _, args -> bridge.domAbsUrl(numberArg(args, 0), stringArg(args, 1)) }
        bind("domTagName") { _, _, args -> bridge.domTagName(numberArg(args, 0)) }
        bind("domClassName") { _, _, args -> bridge.domClassName(numberArg(args, 0)) }
        bind("domId") { _, _, args -> bridge.domId(numberArg(args, 0)) }
        bind("domChildren") { _, _, args -> bridge.domChildren(numberArg(args, 0)) }
        bind("domParent") { _, _, args -> bridge.domParent(numberArg(args, 0)) }
        bind("domNextSibling") { _, _, args -> bridge.domNextSibling(numberArg(args, 0)) }
        bind("domPrevSibling") { _, _, args -> bridge.domPrevSibling(numberArg(args, 0)) }
        bind("domRemove") { _, _, args -> bridge.domRemove(numberArg(args, 0)) }
        bind("domRelease") { _, _, args -> bridge.domRelease(numberArg(args, 0)) }
        bind("domReleaseAll") { _, _, _ -> bridge.domReleaseAll() }

        bind("log") { _, _, args ->
            if (args.size >= 2) bridge.log(stringArg(args, 0), rawArg(args, 1))
            else bridge.log(stringArg(args, 0))
        }
        bind("getPreference") { _, _, args -> bridge.getPreference(stringArg(args, 0)) }
        bind("setPreference") { _, _, args ->
            bridge.setPreference(stringArg(args, 0), stringArg(args, 1))
        }
        bind("getCredentialUsername") { _, _, _ -> bridge.getCredentialUsername() }
        bind("getCredentialPassword") { _, _, _ -> bridge.getCredentialPassword() }
        bind("setCredential") { _, _, args ->
            bridge.setCredential(stringArg(args, 0), stringArg(args, 1))
        }
        bind("clearCredential") { _, _, _ -> bridge.clearCredential() }
        bind("hasCredential") { _, _, _ -> bridge.hasCredential() }
        bind("requestLogin") { _, _, args ->
            if (args.isEmpty()) bridge.requestLogin() else bridge.requestLogin(nullableStringArg(args, 0))
        }

        bind("requestHostEvent") { _, _, args -> bridge.requestHostEvent(stringArg(args, 0)) }
        bind("getHostEventContext") { _, _, _ -> bridge.getHostEventContext() }
        bind("getHostEventCapabilities") { _, _, _ -> bridge.getHostEventCapabilities() }

        bind("getCookie") { _, _, args ->
            bridge.getCookie(stringArg(args, 0), stringArg(args, 1))
        }
        bind("getCookies") { _, _, args -> bridge.getCookies(stringArg(args, 0)) }
        bind("setCookie") { _, _, args ->
            bridge.setCookie(
                name = stringArg(args, 0),
                value = stringArg(args, 1),
                domain = stringArg(args, 2),
                path = stringArg(args, 3),
                expirySeconds = numberArg(args, 4),
            )
        }
        bind("deleteCookie") { _, _, args ->
            bridge.deleteCookie(stringArg(args, 0), stringArg(args, 1))
        }
        bind("clearCookies") { _, _, _ -> bridge.clearCookies() }
    }

    override fun getClassName(): String = "ShinsouBridge"

    private fun bind(
        name: String,
        handler: (Context, Scriptable, Array<Any?>) -> Any?,
    ) {
        val fn = BridgeFunction(handler).also {
            val parent = getParentScope() ?: this
            it.setParentScope(parent)
            it.setPrototype(ScriptableObject.getFunctionPrototype(parent))
        }
        ScriptableObject.putProperty(this, name, fn)
    }

    private class BridgeFunction(
        private val handler: (Context, Scriptable, Array<Any?>) -> Any?,
    ) : BaseFunction() {
        override fun call(
            context: Context,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<Any?>,
        ): Any? = bridgeResult(context, scope, handler(context, scope, args))
    }
}

private fun rawArg(args: Array<Any?>, index: Int): Any? = args.getOrNull(index)

private fun stringArg(args: Array<Any?>, index: Int): String =
    Context.toString(rawArg(args, index) ?: "")

private fun nullableStringArg(args: Array<Any?>, index: Int): String? =
    rawArg(args, index)?.takeUnless { it === Undefined.instance }?.let(Context::toString)

private fun numberArg(args: Array<Any?>, index: Int): Number = when (val value = rawArg(args, index)) {
    is Number -> value
    null, Undefined.instance -> 0
    else -> Context.toNumber(value)
}

private fun bridgeResult(context: Context, scope: Scriptable, value: Any?): Any? = when (value) {
    null -> null
    is Unit -> Undefined.instance
    is Undefined, is Scriptable -> value
    is Map<*, *> -> context.newObject(scope).also { objectValue ->
        value.forEach { (key, nested) ->
            if (key != null) {
                ScriptableObject.putProperty(objectValue, key.toString(), bridgeResult(context, scope, nested))
            }
        }
    }
    is Iterable<*> -> context.newArray(
        scope,
        rhinoObjectArray(value.map { nested -> bridgeResult(context, scope, nested) }),
    )
    is Array<*> -> context.newArray(
        scope,
        rhinoObjectArray(value.map { nested -> bridgeResult(context, scope, nested) }),
    )
    is IntArray -> context.newArray(scope, rhinoObjectArray(value.map(Int::toDouble)))
    is LongArray -> context.newArray(scope, rhinoObjectArray(value.map(Long::toDouble)))
    is DoubleArray -> context.newArray(scope, rhinoObjectArray(value.asIterable()))
    is FloatArray -> context.newArray(scope, rhinoObjectArray(value.map(Float::toDouble)))
    is Boolean, is Number, is String -> value
    // Never return an arbitrary host object through the facade.  The bridge methods above only
    // use the explicitly handled shapes; stringify an unexpected value instead of creating a
    // new Java reflection surface.
    else -> Context.toString(value)
}

private const val RHINO_SYSTEM_EVENT_BOOTSTRAP: String = """
(function(){
  function submit(name,kind,payload,options){
    options=options||{};
    var envelope={protocol:'dev.shinsou.system',version:1,kind:String(kind),name:String(name),
      id:String(options.id||('event-'+Date.now())),payloadVersion:1,payload:payload||{}};
    if(options.idempotencyKey!=null)envelope.idempotencyKey=String(options.idempotencyKey);
    if(options.contextRef!=null)envelope.contextRef=String(options.contextRef);
    var receipt=bridge.requestHostEvent(JSON.stringify(envelope));
    try{return JSON.parse(String(receipt));}catch(e){return null;}
  }
  bridge.system={
    submit:submit,
    requestLogin:function(reason){return submit('auth.login.request','command',{reasonCode:null,fallbackMessage:reason==null?null:String(reason)});},
    requestRefresh:function(scope,contextRef){scope=String(scope||'SELF');if(scope==='ACTIVE_CONTEXT'&&contextRef==null)contextRef=bridge.getHostEventContext();return submit('source.refresh.request','command',{scope:scope,reasonCode:null},{contextRef:contextRef});},
    requestLogout:function(reason){return submit('auth.logout.request','command',{reasonCode:null,fallbackMessage:reason==null?null:String(reason)});},
    reportMessage:function(message){return submit('diagnostic.message.report','event',message||{});}
  };
})();
"""

private fun Scriptable.property(name: String): Any? = ScriptableObject.getProperty(this, name)
    .takeUnless { it === Scriptable.NOT_FOUND || it is Undefined }

private fun Scriptable.stringProperty(name: String): String? = property(name)?.let(Context::toString)
private fun Scriptable.booleanProperty(name: String): Boolean? = property(name)?.let(Context::toBoolean)

private fun Any?.toRhino(context: Context, scope: Scriptable): Any? = when (this) {
    null -> null
    is Map<*, *> -> context.newObject(scope).also { objectValue ->
        forEach { (key, value) ->
            if (key != null) ScriptableObject.putProperty(objectValue, key.toString(), value.toRhino(context, scope))
        }
    }
    is Iterable<*> -> context.newArray(scope, rhinoObjectArray(map { it.toRhino(context, scope) }))
    is Array<*> -> context.newArray(scope, rhinoObjectArray(map { it.toRhino(context, scope) }))
    else -> Context.javaToJS(this, scope)
}

/** Rhino's Object[] overload rejects arrays retaining a Kotlin/JVM component type. */
private fun rhinoObjectArray(values: Iterable<*>): Array<Any?> = values.map { it }.toTypedArray()

private fun Any?.fromRhino(depth: Int = 0): Any? {
    if (depth > 32) return null
    return when (this) {
        null, is Undefined -> null
        is NativeJavaObject -> unwrap().fromRhino(depth + 1)
        is Wrapper -> unwrap().fromRhino(depth + 1)
        is NativeArray -> (0 until length.toInt()).map { index -> get(index, this).fromRhino(depth + 1) }
        is Scriptable -> ids.associate { id ->
            val key = id.toString()
            val value = when (id) {
                is Int -> get(id, this)
                else -> get(key, this)
            }
            key to value.fromRhino(depth + 1)
        }
        else -> this
    }
}

private fun Any?.toStringMap(): Map<String, String> = when (val value = this) {
    null, is Undefined -> emptyMap()
    is NativeJavaObject -> value.unwrap().toStringMap()
    is Wrapper -> value.unwrap().toStringMap()
    is Scriptable -> value.ids.associateNotNull { id ->
        val key = id.toString()
        val raw = if (id is Int) value.get(id, value) else value.get(key, value)
        if (raw == null || raw is Undefined || raw === Scriptable.NOT_FOUND) null else key to Context.toString(raw)
    }
    is Map<*, *> -> value.entries.mapNotNull { (key, raw) ->
        if (key == null || raw == null) null else key.toString() to raw.toString()
    }.toMap()
    else -> emptyMap()
}

private inline fun <T, K, V> Array<T>.associateNotNull(transform: (T) -> Pair<K, V>?): Map<K, V> =
    buildMap { for (item in this@associateNotNull) transform(item)?.let { put(it.first, it.second) } }

private fun Map<String, String>.header(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun Any?.toStringAnyMap(): Map<String, Any?>? = when (this) {
    is Map<*, *> -> entries.associate { it.key.toString() to it.value }
    else -> null
}

private fun Any?.toAnyList(): List<Any?> = when (this) {
    is List<*> -> this
    is Array<*> -> toList()
    is NativeArray -> (0 until length.toInt()).map { index -> get(index, this).fromRhino() }
    else -> emptyList()
}

private fun Any?.toBooleanValue(): Boolean = when (this) {
    is Boolean -> this
    is Number -> toInt() != 0
    is String -> equals("true", ignoreCase = true) || this == "1"
    else -> false
}

private fun Any?.toIntValue(default: Int = 0): Int = when (this) {
    is Number -> toInt()
    is String -> toDoubleOrNull()?.toInt() ?: default
    else -> default
}

private fun Any?.toLongValue(default: Long = 0): Long = when (this) {
    is Number -> toLong()
    is String -> toDoubleOrNull()?.toLong() ?: default
    else -> default
}

private fun Any?.toDoubleValue(default: Double = -1.0): Double = when (this) {
    is Number -> toDouble()
    is String -> toDoubleOrNull() ?: default
    else -> default
}

private fun Any?.toStringList(): List<String> = toAnyList().mapNotNull { it?.toString() }

private fun SManga.toMap(): Map<String, Any?> = mapOf(
    "url" to url,
    "title" to title,
    "artist" to artist,
    "author" to author,
    "description" to description,
    "genre" to genre,
    "status" to status.value,
    "thumbnailUrl" to thumbnailUrl,
    "initialized" to initialized,
)

private fun Map<String, Any?>.toManga(): SManga = SManga(
    url = this["url"]?.toString().orEmpty(),
    title = this["title"]?.toString().orEmpty(),
    artist = this["artist"]?.toString(),
    author = this["author"]?.toString(),
    description = this["description"]?.toString(),
    genre = this["genre"]?.toStringList()?.takeIf { it.isNotEmpty() },
    status = MangaStatus.fromValue(this["status"].toIntValue()),
    thumbnailUrl = (this["thumbnailUrl"] ?: this["thumbnail_url"])?.toString(),
    initialized = this["initialized"].toBooleanValue(),
)

private fun Map<String, Any?>.toChapter(): SChapter = SChapter(
    url = this["url"]?.toString().orEmpty(),
    name = this["name"]?.toString().orEmpty(),
    scanlator = this["scanlator"]?.toString(),
    dateUpload = this["dateUpload"].toLongValue(),
    chapterNumber = this["chapterNumber"].toDoubleValue(),
)

private fun Map<String, Any?>.toPage(fallbackIndex: Int): Page = Page(
    index = this["index"].toIntValue(fallbackIndex),
    url = this["url"]?.toString().orEmpty(),
    imageUrl = this["imageUrl"]?.toString(),
)

private fun filterToMap(filter: Filter): Map<String, Any?> = when (filter) {
    is Filter.Header -> mapOf("type" to "header", "name" to filter.name)
    Filter.Separator -> mapOf("type" to "separator", "name" to "")
    is Filter.Select -> mapOf("type" to "select", "name" to filter.name, "values" to filter.values, "state" to filter.state)
    is Filter.Text -> mapOf("type" to "text", "name" to filter.name, "state" to filter.state)
    is Filter.CheckBox -> mapOf("type" to "checkBox", "name" to filter.name, "state" to filter.state)
    is Filter.TriState -> mapOf("type" to "triState", "name" to filter.name, "state" to filter.state.value)
    is Filter.Group -> mapOf("type" to "group", "name" to filter.name, "filters" to filter.filters.map(::filterToMap))
    is Filter.Sort -> mapOf(
        "type" to "sort",
        "name" to filter.name,
        "values" to filter.values,
        "selection" to filter.selection?.let { mapOf("index" to it.index, "ascending" to it.ascending) },
    )
}

private fun Map<String, Any?>.toFilter(): Filter? {
    val type = this["type"]?.toString() ?: return null
    val name = this["name"]?.toString().orEmpty()
    return when (type) {
        "header" -> Filter.Header(name)
        "separator" -> Filter.Separator
        "select" -> Filter.Select(name, this["values"].toStringList(), this["state"].toIntValue())
        "text" -> Filter.Text(name, this["state"]?.toString().orEmpty())
        "checkBox" -> Filter.CheckBox(name, this["state"].toBooleanValue())
        "triState" -> Filter.TriState(name, TriStateValue.fromValue(this["state"].toIntValue()))
        "group" -> Filter.Group(name, this["filters"].toAnyList().mapNotNull { it.toStringAnyMap()?.toFilter() })
        "sort" -> {
            val selectionMap = this["selection"].toStringAnyMap()
            Filter.Sort(
                name,
                this["values"].toStringList(),
                selectionMap?.let { SortSelection(it["index"].toIntValue(), it["ascending"].toBooleanValue()) },
            )
        }
        else -> null
    }
}

private fun Map<String, Any?>.toSourcePreference(): SourcePreference? {
    val type = this["type"]?.toString()?.lowercase() ?: return null
    val key = this["key"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
    val title = this["title"]?.toString()?.takeIf { it.isNotBlank() } ?: key
    val summary = this["summary"]?.toString().orEmpty()
    return when (type) {
        "text", "textfield", "text_field" -> SourcePreference.TextField(
            key,
            title,
            summary,
            this["defaultValue"]?.toString().orEmpty(),
        )
        "toggle", "switch" -> SourcePreference.Toggle(
            key,
            title,
            summary,
            this["defaultValue"].toBooleanValue(),
        )
        "select", "choice" -> {
            val entries = this["entries"].toStringList().ifEmpty { this["values"].toStringList() }
            val entryValues = this["entryValues"].toStringList().ifEmpty { entries }
            SourcePreference.Select(
                key,
                title,
                entries,
                entryValues,
                this["defaultValue"]?.toString().orEmpty(),
            )
        }
        "multiselect", "multi_select", "multichoice" -> {
            val entries = this["entries"].toStringList().ifEmpty { this["values"].toStringList() }
            val entryValues = this["entryValues"].toStringList().ifEmpty { entries }
            SourcePreference.MultiSelect(
                key,
                title,
                entries,
                entryValues,
                this["defaultValues"].toStringList().toSet(),
            )
        }
        else -> null
    }
}

private const val RHINO_DOM_BOOTSTRAP: String = """
var Jsoup={parse:function(html,baseUri){var h=baseUri?bridge.htmlParseFragment(String(html),String(baseUri)):bridge.htmlParse(String(html));return h<0?null:new Document(h);}};
function Element(h){this._hid=h;}
function Document(h){this._hid=h;} Document.prototype=Object.create(Element.prototype); Document.prototype.constructor=Document;
Element.prototype.select=function(css){var ids=bridge.domSelect(this._hid,String(css));var a=[];if(ids)for(var i=0;i<ids.length;i++)a.push(new Element(ids[i]));return new Elements(a);};
Element.prototype.selectFirst=function(css){var h=bridge.domFirst(this._hid,String(css));return h<0?null:new Element(h);};
Element.prototype.text=function(){return String(bridge.domText(this._hid));};
Element.prototype.ownText=function(){return String(bridge.domOwnText(this._hid));};
Element.prototype.html=function(){return String(bridge.domHtml(this._hid));};
Element.prototype.outerHtml=function(){return String(bridge.domOuterHtml(this._hid));};
Element.prototype.attr=function(n){return String(bridge.domAttr(this._hid,String(n)));};
Element.prototype.hasAttr=function(n){return !!bridge.domHasAttr(this._hid,String(n));};
Element.prototype.absUrl=function(n){return String(bridge.domAbsUrl(this._hid,String(n)));};
Element.prototype.tagName=function(){return String(bridge.domTagName(this._hid));};
Element.prototype.className=function(){return String(bridge.domClassName(this._hid));};
Element.prototype.id=function(){return String(bridge.domId(this._hid));};
Element.prototype.children=function(){var ids=bridge.domChildren(this._hid);var a=[];if(ids)for(var i=0;i<ids.length;i++)a.push(new Element(ids[i]));return new Elements(a);};
Element.prototype.parent=function(){var h=bridge.domParent(this._hid);return h<0?null:new Element(h);};
Element.prototype.nextElementSibling=function(){var h=bridge.domNextSibling(this._hid);return h<0?null:new Element(h);};
Element.prototype.previousElementSibling=function(){var h=bridge.domPrevSibling(this._hid);return h<0?null:new Element(h);};
Element.prototype.remove=function(){bridge.domRemove(this._hid);}; Element.prototype.release=function(){bridge.domRelease(this._hid);};
Element.prototype.getElementsByTag=function(t){return this.select(t);}; Element.prototype.getElementsByClass=function(c){return this.select('.'+c);}; Element.prototype.getElementById=function(i){return this.selectFirst('#'+i);}; Element.prototype.toString=function(){return this.outerHtml();};
function Elements(a){this._arr=a||[];this.length=this._arr.length;for(var i=0;i<this.length;i++)this[i]=this._arr[i];}
Elements.prototype.get=function(i){return this._arr[i]||null;}; Elements.prototype.first=function(){return this.get(0);}; Elements.prototype.last=function(){return this.length?this._arr[this.length-1]:null;}; Elements.prototype.size=function(){return this.length;}; Elements.prototype.isEmpty=function(){return this.length===0;};
Elements.prototype.text=function(){var a=[];for(var i=0;i<this.length;i++){var t=this._arr[i].text();if(t)a.push(t);}return a.join(' ');}; Elements.prototype.attr=function(n){return this.length?this._arr[0].attr(n):'';}; Elements.prototype.hasAttr=function(n){return this.length?this._arr[0].hasAttr(n):false;}; Elements.prototype.html=function(){return this.length?this._arr[0].html():'';};
Elements.prototype.select=function(css){var a=[];for(var i=0;i<this.length;i++){var s=this._arr[i].select(css);for(var j=0;j<s.length;j++)a.push(s[j]);}return new Elements(a);};
Elements.prototype.forEach=function(f){for(var i=0;i<this.length;i++)f(this._arr[i],i);}; Elements.prototype.map=function(f){var a=[];for(var i=0;i<this.length;i++)a.push(f(this._arr[i],i));return a;}; Elements.prototype.filter=function(f){var a=[];for(var i=0;i<this.length;i++)if(f(this._arr[i],i))a.push(this._arr[i]);return new Elements(a);}; Elements.prototype.eachAttr=function(n){return this.map(function(e){return e.attr(n);});}; Elements.prototype.eachText=function(){return this.map(function(e){return e.text();});}; Elements.prototype.releaseAll=function(){for(var i=0;i<this.length;i++)this._arr[i].release();};
function fetchAndParse(url,baseUri){var h=bridge.httpGet(String(url));return !h||h.error?null:Jsoup.parse(h,baseUri||url);}
var SManga={create:function(){return{url:'',title:'',author:null,artist:null,description:null,genre:null,status:0,thumbnailUrl:null,initialized:false};},UNKNOWN:0,ONGOING:1,COMPLETED:2,LICENSED:3,PUBLISHING_FINISHED:4,CANCELLED:5,ON_HIATUS:6};
var SChapter={create:function(){return{url:'',name:'',scanlator:null,dateUpload:0,chapterNumber:-1};}};
function Page(index,url,imageUrl){this.index=index||0;this.url=url||'';this.imageUrl=imageUrl||null;} function MangasPage(mangas,hasNextPage){this.mangas=mangas||[];this.hasNextPage=!!hasNextPage;}
if(!String.prototype.substringAfter)String.prototype.substringAfter=function(d){var i=this.indexOf(d);return i>=0?this.substring(i+d.length):String(this);};
if(!String.prototype.substringBefore)String.prototype.substringBefore=function(d){var i=this.indexOf(d);return i>=0?this.substring(0,i):String(this);};
if(!String.prototype.substringAfterLast)String.prototype.substringAfterLast=function(d){var i=this.lastIndexOf(d);return i>=0?this.substring(i+d.length):String(this);};
if(!String.prototype.substringBeforeLast)String.prototype.substringBeforeLast=function(d){var i=this.lastIndexOf(d);return i>=0?this.substring(0,i):String(this);};
function setUrlWithoutDomain(o,u){var m=String(u).match(/^https?:\/\/[^\/]+(\/[^#]*)/i);o.url=m?m[1]:String(u);}
"""
