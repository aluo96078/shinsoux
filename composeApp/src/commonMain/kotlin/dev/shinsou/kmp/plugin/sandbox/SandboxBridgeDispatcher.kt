package dev.shinsou.kmp.plugin.sandbox

import dev.shinsou.kmp.plugin.PluginCookie
import dev.shinsou.kmp.plugin.PluginCredential
import dev.shinsou.kmp.plugin.PluginExecutionLimits
import dev.shinsou.kmp.plugin.PluginHttpRequest
import dev.shinsou.kmp.plugin.PluginHttpResponse
import dev.shinsou.kmp.plugin.PluginJson
import dev.shinsou.kmp.plugin.PluginResourceLimitException
import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.SourceIndexEntry
import dev.shinsou.kmp.plugin.canonicalCookieDomain
import dev.shinsou.kmp.plugin.currentSystemEventContext
import dev.shinsou.kmp.plugin.encodeBoundedPluginBatchResponseBodies
import dev.shinsou.kmp.plugin.events.PluginEventDisposition
import dev.shinsou.kmp.plugin.events.PluginEventReceipt
import dev.shinsou.kmp.plugin.events.PluginSystemEventGateway
import dev.shinsou.kmp.plugin.executeWithNetworkPolicy
import dev.shinsou.kmp.plugin.isPluginCookiePublicSuffix
import dev.shinsou.kmp.plugin.isValidCookieDomainSyntax
import dev.shinsou.kmp.plugin.normalizePluginOrigins
import dev.shinsou.kmp.plugin.pluginJsonShapeWithin
import dev.shinsou.kmp.plugin.pluginUtf8ByteCountAtMost
import dev.shinsou.kmp.plugin.preparePluginBrowserSessionRequest
import dev.shinsou.kmp.plugin.submitLegacyLoginCompatibility
import io.ktor.http.Url
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Sandbox-tier policy that is stricter than the reviewed in-process engines (ADR-0002 §5.1, §6).
 * Values come from the host's permission records, never from the sandbox.
 */
internal data class SandboxBridgePolicy(
    /** `getCookie`/`getCookies` (the ADR's sensitive COOKIE_READ grant); off unless the user granted it. */
    val allowCookieRead: Boolean = false,
)

/**
 * Host-side state of one sandbox invocation. The dispatcher checks it on every bridge call, so a
 * call that arrives after revocation, an update or the end of the invocation is refused.
 */
internal class SandboxInvocation(
    val callId: Long,
    val generation: Long,
    /** True only for the host-started `login` hook: the one place passwords may be read or written (§6.4). */
    val loginInvocation: Boolean = false,
) {
    /** Guarded by [counterLock]: sync and async calls of one invocation run concurrently. */
    internal var bridgeCalls: Int = 0
    internal val counterLock = Mutex()

    @Volatile
    internal var closed: Boolean = false
}

/**
 * Executes the sandbox's `BridgeCall`/`AsyncBridgeCall` frames against host services
 * (ADR-0002 §5.1). Operation names and JSON argument arrays are the same as the existing
 * `bridge.*` API, so unmodified scripts keep working; the policy is the sandbox tier's:
 *
 * - every call counts towards [PluginExecutionLimits.maxBridgeCallsPerInvocation] and re-checks
 *   the runtime generation;
 * - failures are stable [SandboxErrorCode]s only, never host exception text;
 * - DOM operations and logging never reach the host (they run inside the sandbox), so they are
 *   [SandboxErrorCode.UNKNOWN_OPERATION] here;
 * - cookie values are readable only with [SandboxBridgePolicy.allowCookieRead] and only for
 *   `credentialOrigins`; cookies can only be written for those origins (or a registrable parent);
 * - passwords are reachable only during a [SandboxInvocation.loginInvocation];
 * - browser-session requests never use the developer unpinned transport;
 * - `net.fetch` responses never expose credential-bearing headers.
 */
internal class SandboxBridgeDispatcher(
    private val environment: ScriptPluginEnvironment,
    private val source: SourceIndexEntry,
    sourceHeaders: Map<String, String> = emptyMap(),
    private val limits: PluginExecutionLimits = environment.executionLimits,
    private val policy: SandboxBridgePolicy = SandboxBridgePolicy(),
    private val currentGeneration: () -> Long,
    private val nowEpochMillis: () -> Long,
) {
    /** The script's metadata headers, known only after init; set once before any hook runs. */
    @Volatile
    internal var sourceHeaders: Map<String, String> = sourceHeaders

    private val sourceId: Long get() = source.id
    private val credentialHosts: Set<String> by lazy {
        normalizePluginOrigins(source.credentialOrigins).mapNotNullTo(linkedSetOf()) {
            runCatching { Url(it).host.lowercase() }.getOrNull()?.takeIf(String::isNotEmpty)
        }
    }

    /** Synchronous bridge call (`BridgeCall`): the sandbox thread waits for this outcome. */
    suspend fun dispatch(invocation: SandboxInvocation, op: String, argsJson: String): BridgeOutcome =
        guarded(invocation, op, argsJson) { args -> executeSync(invocation, op, args) }

    /** Asynchronous bridge call (`AsyncBridgeCall`); only `net.fetch` exists in protocol v1 (§5.3). */
    suspend fun dispatchAsync(invocation: SandboxInvocation, op: String, argsJson: String): BridgeOutcome =
        guarded(invocation, op, argsJson) { args ->
            if (op != OP_NET_FETCH) throw UnknownOperation()
            fetch(args)
        }

    private suspend fun guarded(
        invocation: SandboxInvocation,
        op: String,
        argsJson: String,
        block: suspend (JsonArray) -> JsonElement,
    ): BridgeOutcome {
        if (invocation.closed || invocation.generation != currentGeneration()) return failed(SandboxErrorCode.REVOKED)
        val withinQuota = invocation.counterLock.withLock {
            invocation.bridgeCalls += 1
            invocation.bridgeCalls <= limits.maxBridgeCallsPerInvocation
        }
        if (!withinQuota) return failed(SandboxErrorCode.LIMIT_BRIDGE)
        if (pluginUtf8ByteCountAtMost(op, limits.maxBridgeMethodBytes) == null) return failed(SandboxErrorCode.BAD_ARGUMENT)
        if (pluginUtf8ByteCountAtMost(argsJson, limits.maxBridgeArgumentBytes) == null) return failed(SandboxErrorCode.LIMIT_BRIDGE)
        val args = parseArguments(argsJson) ?: return failed(SandboxErrorCode.BAD_ARGUMENT)
        val value = try {
            block(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownOperation) {
            return failed(SandboxErrorCode.UNKNOWN_OPERATION)
        } catch (e: Denied) {
            return failed(SandboxErrorCode.PERMISSION_DENIED)
        } catch (e: BadArgument) {
            return failed(SandboxErrorCode.BAD_ARGUMENT)
        } catch (e: PluginResourceLimitException) {
            return failed(SandboxErrorCode.LIMIT_BRIDGE)
        } catch (e: Throwable) {
            return failed(if (op in NETWORK_OPS) SandboxErrorCode.NETWORK_FAILED else SandboxErrorCode.HOST_FAILURE)
        }
        // A call can outlive its invocation (cancellation raced the host work): drop the result.
        if (invocation.closed || invocation.generation != currentGeneration()) return failed(SandboxErrorCode.REVOKED)
        val json = if (value is JsonNull) null else value.toString()
        if (json != null && pluginUtf8ByteCountAtMost(json, limits.maxBridgeResultBytes) == null) {
            return failed(SandboxErrorCode.LIMIT_RESULT)
        }
        return BridgeOutcome.Value(json)
    }

    private suspend fun executeSync(invocation: SandboxInvocation, op: String, args: JsonArray): JsonElement = when (op) {
        "httpGet", "httpGetWithHeaders", "httpGetResponse" -> {
            require(PluginRuntimePermission.NETWORK)
            val response = environment.network.get(
                sourceId, args.string(0), args.stringMap(1), sourceHeaders = sourceHeaders, referer = referer(),
            )
            if (op == "httpGetResponse") statusAndBody(response) else JsonPrimitive(response.bodyText())
        }
        "httpPost", "httpPostResponse" -> {
            require(PluginRuntimePermission.NETWORK)
            val response = environment.network.post(
                sourceId, args.string(0), args.string(1), args.stringMap(2), sourceHeaders = sourceHeaders, referer = referer(),
            )
            if (op == "httpPostResponse") statusAndBody(response) else JsonPrimitive(response.bodyText())
        }
        "httpPostBatch" -> {
            require(PluginRuntimePermission.NETWORK)
            val responses = environment.network.postBatch(
                sourceId = sourceId,
                urls = args.stringList(0),
                bodies = args.stringList(1),
                headers = args.stringMap(2),
                sourceHeaders = sourceHeaders,
                referer = referer(),
            )
            // Same JSON-string shape as the in-process engines (the reviewed ShuYue script parses it).
            JsonPrimitive(encodeBoundedPluginBatchResponseBodies(responses, limits.maxBridgeResultBytes, jsonStringWrapped = true))
        }
        "browserSessionRequest" -> {
            require(PluginRuntimePermission.BROWSER_CHALLENGE)
            val prepared = preparePluginBrowserSessionRequest(
                sourceOrigin = source.baseUrl.orEmpty(),
                allowedOrigins = source.browserSessionOrigins,
                request = PluginHttpRequest(
                    method = args.string(1),
                    url = args.string(0),
                    body = args.string(2).encodeToByteArray(),
                    headers = args.stringMap(3),
                ),
            )
            val response = environment.browserSessionTransport.executeWithNetworkPolicy(
                sourceId = sourceId,
                sourceOrigin = prepared.sourceOrigin,
                allowedOrigins = setOf(prepared.targetOrigin),
                request = prepared.request,
                resolver = environment.hostResolver,
                allowDeveloperUnpinnedTransport = false, // never for repository code (§5.1)
            )
            statusAndBody(response)
        }

        "getPreference" -> environment.storage.getPreference(sourceId, args.string(0))?.let(::JsonPrimitive) ?: JsonNull
        "setPreference" -> { environment.storage.setPreference(sourceId, args.string(0), args.string(1)); JsonNull }

        "getCredentialUsername" -> {
            require(PluginRuntimePermission.CREDENTIAL_ACCESS)
            environment.storage.getCredential(sourceId)?.username?.let(::JsonPrimitive) ?: JsonNull
        }
        "hasCredential" -> {
            require(PluginRuntimePermission.CREDENTIAL_ACCESS)
            JsonPrimitive(!environment.storage.getCredential(sourceId)?.username.isNullOrEmpty())
        }
        "getCredentialPassword" -> {
            requireLogin(invocation)
            environment.storage.getCredential(sourceId)?.password?.let(::JsonPrimitive) ?: JsonNull
        }
        "setCredential" -> {
            requireLogin(invocation)
            environment.storage.setCredential(sourceId, PluginCredential(args.string(0), args.string(1)))
            JsonNull
        }
        "clearCredential" -> { requireLogin(invocation); environment.storage.clearCredential(sourceId); JsonNull }

        "getCookie" -> {
            requireCookieRead()
            val target = credentialUrl(args.string(1))
            val now = nowEpochMillis()
            environment.storage.getCookies(sourceId).firstOrNull { it.name == args.string(0) && it.matches(target, now) }
                ?.value?.let(::JsonPrimitive) ?: JsonNull
        }
        "getCookies" -> {
            requireCookieRead()
            val target = credentialUrl(args.string(0))
            val now = nowEpochMillis()
            JsonObject(environment.storage.getCookies(sourceId).filter { it.matches(target, now) }.associate { it.name to JsonPrimitive(it.value) })
        }
        "setCookie" -> {
            require(PluginRuntimePermission.COOKIE_STORAGE)
            environment.storage.setCookie(
                sourceId,
                PluginCookie(
                    name = args.string(0),
                    value = args.string(1),
                    domain = credentialCookieDomain(args.string(2)),
                    path = args.string(3).ifBlank { "/" },
                    expiresAtEpochMillis = cookieExpiry(args.long(4)),
                ),
            )
            JsonPrimitive(true)
        }
        "deleteCookie" -> {
            require(PluginRuntimePermission.COOKIE_STORAGE)
            environment.storage.deleteCookie(sourceId, args.string(0), credentialCookieDomain(args.string(1)))
            JsonNull
        }
        "clearCookies" -> { require(PluginRuntimePermission.COOKIE_STORAGE); environment.storage.clearCookies(sourceId); JsonNull }

        "requestLogin" -> JsonPrimitive(
            // Boolean-valued compatibility API: false is the fail-closed answer.
            PluginRuntimePermission.LOGIN_PROMPT in environment.runtimePermissions &&
                submitLegacyLoginCompatibility(environment, supportsLogin = true, reason = args.string(0).takeIf(String::isNotBlank)),
        )
        "requestHostEvent" -> {
            val sink = environment.systemEventSink
            val scope = environment.boundPluginScope // host-injected; never read from the frame
            val receipt = if (sink == null || scope == null) {
                PluginEventReceipt(messageId = "", disposition = PluginEventDisposition.UNSUPPORTED)
            } else {
                try {
                    sink.submit(scope, args.string(0).encodeToByteArray())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    PluginEventReceipt(messageId = "", disposition = PluginEventDisposition.INVALID)
                }
            }
            JsonPrimitive(PluginJson.encodeToString(PluginEventReceipt.serializer(), receipt))
        }
        "getHostEventContext" -> environment.currentSystemEventContext()?.let(::JsonPrimitive) ?: JsonNull
        "getHostEventCapabilities" -> buildJsonObject {
            val negotiation = environment.systemEventDeclaration?.let { declaration ->
                val gateway = environment.systemEventSink as? PluginSystemEventGateway
                val scope = environment.boundPluginScope
                if (gateway != null && scope != null) gateway.negotiate(scope, declaration) else environment.systemEventNegotiation
            } ?: environment.systemEventNegotiation
            put("enabled", negotiation?.enabled == true)
            put("protocol", "dev.shinsou.system")
            val version = negotiation?.version
            if (version == null) put("version", JsonNull) else put("version", version)
            put("grantedCapabilities", PluginJson.encodeToJsonElement(negotiation?.grantedCapabilities.orEmpty()))
            put("hardLimits", PluginJson.encodeToJsonElement(negotiation?.hardLimits))
        }
        else -> throw UnknownOperation()
    }

    /** `bridge.net.fetch(url, {method, headers, body})` → `{status, headers, body}` (§5.3). */
    private suspend fun fetch(args: JsonArray): JsonElement {
        require(PluginRuntimePermission.NETWORK)
        val options = args.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())
        val method = (options["method"] as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "GET"
        if (method != "GET" && method != "POST") throw BadArgument()
        val body = (options["body"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull.orEmpty()
        if (method == "GET" && body.isNotEmpty()) throw BadArgument()
        val headers = (options["headers"] as? JsonObject).stringMap()
        val response = environment.network.execute(
            sourceId,
            PluginHttpRequest(method, args.string(0), body.encodeToByteArray(), headers),
            sourceHeaders,
            referer(),
        )
        return buildJsonObject {
            put("status", response.status)
            put("headers", JsonObject(publicResponseHeaders(response.headers)))
            put("body", response.bodyText())
        }
    }

    private fun statusAndBody(response: PluginHttpResponse): JsonObject = buildJsonObject {
        put("status", response.status)
        put("body", response.bodyText())
    }

    private fun referer(): String? = sourceHeaders.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value

    private fun require(permission: PluginRuntimePermission) {
        if (permission !in environment.runtimePermissions) throw Denied()
    }

    private fun requireLogin(invocation: SandboxInvocation) {
        require(PluginRuntimePermission.CREDENTIAL_ACCESS)
        if (!invocation.loginInvocation) throw Denied()
    }

    private fun requireCookieRead() {
        require(PluginRuntimePermission.COOKIE_STORAGE)
        if (!policy.allowCookieRead) throw Denied()
    }

    /** Cookie reads only target a declared credential origin. */
    private fun credentialUrl(raw: String): Url {
        val url = runCatching { Url(raw) }.getOrNull() ?: throw BadArgument()
        if (url.host.lowercase() !in credentialHosts) throw Denied()
        return url
    }

    /** A cookie domain must be a credential host or a registrable parent of one; never a public suffix. */
    private fun credentialCookieDomain(raw: String): String {
        val domain = canonicalCookieDomain(raw)
        if (!isValidCookieDomainSyntax(domain) || isPluginCookiePublicSuffix(domain)) throw BadArgument()
        if (credentialHosts.none { it == domain || it.endsWith(".$domain") }) throw Denied()
        return raw.trim().lowercase()
    }

    private fun cookieExpiry(seconds: Long): Long? {
        if (seconds <= 0) return null
        val now = nowEpochMillis()
        if (seconds > (Long.MAX_VALUE - now) / 1_000L) throw BadArgument()
        return now + seconds * 1_000L
    }

    /** Bounded parse of the argument array before any element is materialized. */
    private fun parseArguments(argsJson: String): JsonArray? {
        val shapeOk = pluginJsonShapeWithin(
            argsJson,
            maxTotalValues = limits.maxResultTotalElements,
            maxArrayElements = limits.maxResultArrayElements,
            maxObjectEntries = limits.maxResultObjectEntries,
            maxDepth = limits.maxResultDepth,
        )
        if (!shapeOk) return null
        return runCatching { PluginJson.parseToJsonElement(argsJson) as? JsonArray }.getOrNull()
    }

    private class UnknownOperation : Exception()
    private class Denied : Exception()
    private class BadArgument : Exception()

    companion object {
        const val OP_NET_FETCH = "net.fetch"
        private val NETWORK_OPS = setOf(
            "httpGet", "httpGetWithHeaders", "httpGetResponse", "httpPost", "httpPostResponse", "httpPostBatch",
            "browserSessionRequest", OP_NET_FETCH,
        )

        /** Headers that would let a script observe or replay credentials (§5.1). */
        private val CREDENTIAL_RESPONSE_HEADERS = setOf(
            "set-cookie", "set-cookie2", "cookie", "authorization", "proxy-authorization",
            "www-authenticate", "proxy-authenticate",
        )

        internal fun publicResponseHeaders(headers: Map<String, List<String>>): Map<String, JsonPrimitive> =
            headers.entries
                .filter { (name, _) -> name.lowercase() !in CREDENTIAL_RESPONSE_HEADERS }
                .associate { (name, values) -> name.lowercase() to JsonPrimitive(values.joinToString(", ")) }
    }
}

private fun JsonArray.string(index: Int): String = (getOrNull(index) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull.orEmpty()
private fun JsonArray.long(index: Int): Long = (getOrNull(index) as? JsonPrimitive)?.longOrNull ?: 0L
private fun JsonArray.stringList(index: Int): List<String> =
    (getOrNull(index) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.contentOrNull }.orEmpty()
private fun JsonArray.stringMap(index: Int): Map<String, String> = (getOrNull(index) as? JsonObject).stringMap()
private fun JsonObject?.stringMap(): Map<String, String> = this?.mapNotNull { (k, v) ->
    (v as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.let { k to it }
}?.toMap().orEmpty()

private fun failed(code: SandboxErrorCode): BridgeOutcome = BridgeOutcome.Error(code)
