package dev.shinsou.kmp.plugin

import io.ktor.http.Url
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Browser-backed transport for endpoints that bind accepted traffic to a real browser network
 * stack. The exact destination origins come from the installed manifest, never from executable
 * JavaScript, and every platform implementation re-validates the request at this boundary.
 */
public interface PluginBrowserSessionTransport {
    public suspend fun execute(
        sourceId: Long,
        sourceOrigin: String,
        allowedOrigins: Set<String>,
        request: PluginHttpRequest,
    ): PluginHttpResponse

    /**
     * Executes while connecting only to [resolution]'s vetted addresses and preserving the URL
     * hostname for HTTP Host and TLS SNI/certificate verification. Browser implementations which
     * cannot provide that guarantee deliberately inherit this fail-closed default.
     */
    public suspend fun executeResolved(
        sourceId: Long,
        sourceOrigin: String,
        allowedOrigins: Set<String>,
        request: PluginHttpRequest,
        resolution: PluginHostResolution,
    ): PluginHttpResponse = throw IllegalStateException(
        "Browser-session transport cannot bind the request to its validated DNS answers",
    )

    public suspend fun close() = Unit

    public companion object {
        public val Unavailable: PluginBrowserSessionTransport = object : PluginBrowserSessionTransport {
            override suspend fun execute(
                sourceId: Long,
                sourceOrigin: String,
                allowedOrigins: Set<String>,
                request: PluginHttpRequest,
            ): PluginHttpResponse = throw IllegalStateException(
                "Browser-session transport is unavailable on this platform",
            )
        }
    }
}

internal const val PLUGIN_BROWSER_SESSION_MAX_REQUEST_BODY_BYTES: Int = 512 * 1_024
internal const val PLUGIN_BROWSER_SESSION_MAX_RESPONSE_BYTES: Int = 4 * 1_024 * 1_024
/** Allows JSON escaping plus Android's outer evaluateJavascript string without unbounded parsing. */
internal const val PLUGIN_BROWSER_SESSION_MAX_RESULT_WIRE_BYTES: Int = 24 * 1_024 * 1_024
internal const val PLUGIN_BROWSER_SESSION_TIMEOUT_MILLIS: Long = 30_000L
private const val PLUGIN_BROWSER_SESSION_MAX_URL_LENGTH: Int = 4_096
private const val PLUGIN_BROWSER_SESSION_MAX_ORIGINS: Int = 4
private const val PLUGIN_BROWSER_SESSION_MAX_HEADERS: Int = 48
private const val PLUGIN_BROWSER_SESSION_MAX_HEADER_VALUE_LENGTH: Int = 8_192
private const val PLUGIN_BROWSER_SESSION_MAX_HEADER_BYTES: Int = 32 * 1_024

internal data class PreparedPluginBrowserSessionRequest(
    val sourceOrigin: String,
    val targetOrigin: String,
    val request: PluginHttpRequest,
)

/** Canonicalizes a source URL to the HTTPS origin used to bootstrap a browser document. */
internal fun pluginBrowserSessionOrigin(value: String): String {
    val parsed = Url(value.trim())
    require(parsed.protocol.name == "https" && parsed.host.isNotBlank()) {
        "Browser-session origins must use HTTPS"
    }
    require('@' !in value.substringAfter("://", "").substringBefore('/').substringBefore('?')) {
        "Browser-session origins must not contain user information"
    }
    return buildString {
        append("https://")
        append(parsed.host.lowercase())
        if (parsed.port != 443) {
            append(':')
            append(parsed.port)
        }
    }
}

/** Validates and canonicalizes the manifest-owned destination declaration. */
internal fun normalizePluginBrowserSessionOrigins(values: Iterable<String>): Set<String> {
    val listed = values.toList()
    require(listed.size <= PLUGIN_BROWSER_SESSION_MAX_ORIGINS) {
        "Too many browser-session origins"
    }
    return listed.mapTo(linkedSetOf()) { raw ->
        val trimmed = raw.trim()
        val origin = pluginBrowserSessionOrigin(trimmed)
        require(!isBlockedPluginAddress(Url(trimmed).host)) {
            "Browser-session origins must not target local/private addresses"
        }
        require(trimmed == origin || trimmed == "$origin/") {
            "Browser-session declarations must be exact origins"
        }
        origin
    }
}

/**
 * Enforces the exact-origin grant and strips Fetch-forbidden browser identity headers. The
 * browser supplies Origin, Referer, Cookie, and User-Agent itself so an extension cannot forge a
 * different browser session while using this privileged transport.
 */
internal fun preparePluginBrowserSessionRequest(
    sourceOrigin: String,
    allowedOrigins: Set<String>,
    request: PluginHttpRequest,
): PreparedPluginBrowserSessionRequest {
    require(request.url.length in 1..PLUGIN_BROWSER_SESSION_MAX_URL_LENGTH) {
        "Invalid browser-session request URL"
    }
    require('#' !in request.url) { "Browser-session request URLs must not contain fragments" }
    val canonicalSourceOrigin = pluginBrowserSessionOrigin(sourceOrigin)
    val canonicalAllowedOrigins = normalizePluginBrowserSessionOrigins(allowedOrigins)
    require(canonicalAllowedOrigins.isNotEmpty()) {
        "This source did not declare any browser-session origins"
    }

    val target = Url(request.url)
    require(target.protocol.name == "https" && target.host.isNotBlank()) {
        "Browser-session requests must use HTTPS"
    }
    require('@' !in request.url.substringAfter("://", "").substringBefore('/').substringBefore('?')) {
        "Browser-session request URLs must not contain user information"
    }
    val targetOrigin = pluginBrowserSessionOrigin(request.url)
    require(targetOrigin in canonicalAllowedOrigins) {
        "Browser-session request origin was not declared by the installed source"
    }

    val method = request.method.trim().uppercase()
    require(method == "GET" || method == "POST") {
        "Browser-session transport supports only GET and POST"
    }
    require(request.body.size <= PLUGIN_BROWSER_SESSION_MAX_REQUEST_BODY_BYTES) {
        "Browser-session request body is too large"
    }
    require(method == "POST" || request.body.isEmpty()) {
        "GET browser-session requests cannot carry a body"
    }
    require(request.headers.size <= PLUGIN_BROWSER_SESSION_MAX_HEADERS) {
        "Too many browser-session request headers"
    }

    var headerBytes = 0
    val headers = linkedMapOf<String, String>()
    request.headers.forEach { (rawName, rawValue) ->
        val name = rawName.trim()
        require(BROWSER_HEADER_NAME.matches(name)) { "Invalid browser-session request header" }
        require(rawValue.length <= PLUGIN_BROWSER_SESSION_MAX_HEADER_VALUE_LENGTH) {
            "Browser-session request header is too large"
        }
        require(rawValue.none { it.code in 0..31 || it.code == 127 }) {
            "Invalid browser-session request header value"
        }
        headerBytes += name.encodeToByteArray().size + rawValue.encodeToByteArray().size
        require(headerBytes <= PLUGIN_BROWSER_SESSION_MAX_HEADER_BYTES) {
            "Browser-session request headers are too large"
        }
        require(!isForbiddenPluginControlledHeader(name)) {
            "Browser-session request contains a transport-owned header"
        }
        if (!isBrowserManagedHeader(name)) headers[name] = rawValue
    }

    return PreparedPluginBrowserSessionRequest(
        sourceOrigin = canonicalSourceOrigin,
        targetOrigin = targetOrigin,
        request = request.copy(
            method = method,
            headers = headers,
            maxResponseBytes = minOf(
                request.maxResponseBytes,
                PLUGIN_BROWSER_SESSION_MAX_RESPONSE_BYTES,
            ),
        ),
    )
}

/**
 * Final browser-session egress admission. A DNS preflight alone is not a defense against rebinding:
 * WebView performs another lookup. Current platform WebViews expose no safe TLS/SNI-preserving
 * address pinning API, so production calls fail closed after validating all answers. Tests and
 * developer embeddings may explicitly allow an unpinned transport.
 */
internal suspend fun PluginBrowserSessionTransport.executeWithNetworkPolicy(
    sourceId: Long,
    sourceOrigin: String,
    allowedOrigins: Set<String>,
    request: PluginHttpRequest,
    resolver: PluginHostResolver,
    allowDeveloperUnpinnedTransport: Boolean = false,
): PluginHttpResponse {
    val prepared = preparePluginBrowserSessionRequest(sourceOrigin, allowedOrigins, request)
    val policy = PluginNetworkPolicy(
        requestOrigins = allowedOrigins,
        resolver = resolver,
        allowDeveloperUnpinnedTransport = allowDeveloperUnpinnedTransport,
    )
    val resolution = policy.authorize(Url(prepared.request.url), resolver)
    return if (allowDeveloperUnpinnedTransport) {
        execute(
            sourceId = sourceId,
            sourceOrigin = prepared.sourceOrigin,
            allowedOrigins = setOf(prepared.targetOrigin),
            request = prepared.request,
        )
    } else {
        executeResolved(
            sourceId = sourceId,
            sourceOrigin = prepared.sourceOrigin,
            allowedOrigins = setOf(prepared.targetOrigin),
            request = prepared.request,
            resolution = resolution,
        )
    }
}

/** Starts one bounded Fetch request and stores only its status/body in a random result slot. */
internal fun pluginBrowserSessionFetchStartScript(
    requestId: String,
    prepared: PreparedPluginBrowserSessionRequest,
): String {
    val request = prepared.request
    val encodedId = JsonPrimitive(requestId).toString()
    val encodedUrl = JsonPrimitive(request.url).toString()
    val encodedMethod = JsonPrimitive(request.method).toString()
    val encodedBody = JsonPrimitive(request.body.decodeToString()).toString()
    val encodedHeaders = buildJsonObject {
        request.headers.forEach { (name, value) -> put(name, value) }
    }.toString()
    return """
        (() => {
          const requestId = $encodedId;
          const slots = globalThis.__shinsouBrowserSessionSlots ||
            (globalThis.__shinsouBrowserSessionSlots = Object.create(null));
          const previous = slots[requestId];
          if (previous) {
            previous.cancelled = true;
            try { previous.controller?.abort(); } catch (_) {}
            delete slots[requestId];
          }
          const controller = typeof AbortController === "function" ? new AbortController() : null;
          const slot = { controller, cancelled: false, value: null };
          slots[requestId] = slot;
          (async () => {
            try {
              const response = await fetch($encodedUrl, {
                method: $encodedMethod,
                headers: $encodedHeaders,
                body: $encodedMethod === "POST" ? $encodedBody : undefined,
                mode: "cors",
                credentials: "omit",
                cache: "no-store",
                redirect: "error",
                signal: controller ? controller.signal : undefined
              });
              const limit = ${request.maxResponseBytes};
              let text = "";
              if (response.body && typeof response.body.getReader === "function") {
                const reader = response.body.getReader();
                const decoder = new TextDecoder("utf-8");
                let received = 0;
                while (true) {
                  const part = await reader.read();
                  if (part.done) break;
                  received += part.value.byteLength;
                  if (received > limit) {
                    try { await reader.cancel(); } catch (_) {}
                    throw new Error("response_too_large");
                  }
                  text += decoder.decode(part.value, { stream: true });
                }
                text += decoder.decode();
              } else if (response.body) {
                // A whole-body text conversion would allocate attacker-controlled bytes before
                // the Host could enforce its cap. Older engines without streaming fail closed.
                throw new Error("stream_unavailable");
              }
              if (!slot.cancelled && slots[requestId] === slot) {
                slot.value = { status: Number(response.status) || 0, body: text };
              }
            } catch (error) {
              if (!slot.cancelled && slots[requestId] === slot) {
                slot.value = {
                  error: error && error.message === "response_too_large" ?
                    "response_too_large" : "fetch_failed"
                };
              }
            }
          })();
          return "started";
        })()
    """.trimIndent()
}

/** Polls and consumes one Fetch result without exposing any reusable browser object to scripts. */
internal fun pluginBrowserSessionFetchPollScript(requestId: String): String {
    val encodedId = JsonPrimitive(requestId).toString()
    return """
        (() => {
          const slots = globalThis.__shinsouBrowserSessionSlots;
          const slot = slots && slots[$encodedId];
          if (!slot || !slot.value) return "";
          const value = slot.value;
          slot.cancelled = true;
          delete slots[$encodedId];
          return JSON.stringify(value);
        })()
    """.trimIndent()
}

/** Idempotently aborts a timed-out/cancelled Fetch and removes its page-global result slot. */
internal fun pluginBrowserSessionFetchCleanupScript(requestId: String): String {
    val encodedId = JsonPrimitive(requestId).toString()
    return """
        (() => {
          const slots = globalThis.__shinsouBrowserSessionSlots;
          const slot = slots && slots[$encodedId];
          if (!slot) return "clean";
          slot.cancelled = true;
          try { slot.controller?.abort(); } catch (_) {}
          delete slots[$encodedId];
          return "clean";
        })()
    """.trimIndent()
}

internal data class PluginBrowserSessionFetchResult(
    val status: Int = 0,
    val body: String = "",
    val error: String? = null,
)

/** Handles both Android's JSON-quoted evaluateJavascript result and WK/JavaFX raw strings. */
internal fun decodePluginBrowserSessionFetchResult(
    raw: String?,
    maxResponseBytes: Int = PLUGIN_BROWSER_SESSION_MAX_RESPONSE_BYTES,
): PluginBrowserSessionFetchResult? {
    require(maxResponseBytes in 1..PLUGIN_BROWSER_SESSION_MAX_RESPONSE_BYTES) {
        "Invalid browser-session response byte limit"
    }
    val received = raw ?: return null
    require(pluginUtf8ByteCountAtMost(received, PLUGIN_BROWSER_SESSION_MAX_RESULT_WIRE_BYTES) != null) {
        "Browser-session result frame is too large"
    }
    val value = received.trim()
    if (value.isEmpty() || value == "null" || value == "undefined") return null
    val first = runCatching { PluginJson.parseToJsonElement(value) }.getOrNull()
    val payload = when (first) {
        is JsonObject -> first
        is JsonPrimitive -> first.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { PluginJson.parseToJsonElement(it) }.getOrNull() as? JsonObject }
        else -> null
    } ?: return null
    val status = payload["status"]?.jsonPrimitive?.intOrNull ?: 0
    val body = payload["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val error = payload["error"]?.jsonPrimitive?.contentOrNull
    require(body.encodeToByteArray().size <= maxResponseBytes) {
        "Browser-session response is too large"
    }
    return PluginBrowserSessionFetchResult(status, body, error)
}

/** Shared bounded polling loop used by WebView/WKWebView/JavaFX platform adapters. */
internal suspend fun executePluginBrowserSessionFetch(
    prepared: PreparedPluginBrowserSessionRequest,
    evaluate: suspend (String) -> String?,
): PluginHttpResponse {
    val requestId = buildString {
        append(Clock.System.now().toEpochMilliseconds().toString(36))
        append('-')
        append(Random.nextLong().toULong().toString(36))
    }
    try {
        return withTimeout(PLUGIN_BROWSER_SESSION_TIMEOUT_MILLIS) {
            evaluate(pluginBrowserSessionFetchStartScript(requestId, prepared))
            while (true) {
                delay(40)
                val result = decodePluginBrowserSessionFetchResult(
                    evaluate(pluginBrowserSessionFetchPollScript(requestId)),
                    prepared.request.maxResponseBytes,
                ) ?: continue
                when (result.error) {
                    null -> {
                        require(result.status in 100..599) { "Browser-session fetch returned no HTTP status" }
                        return@withTimeout PluginHttpResponse(
                            status = result.status,
                            body = result.body.encodeToByteArray(),
                        )
                    }
                    "response_too_large" -> error("Browser-session response is too large")
                    else -> error("Browser-session fetch failed")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Browser-session fetch did not finish")
        }
    } finally {
        // Cancellation makes ordinary suspend cleanup skip immediately. Use a separate short,
        // non-cancellable budget so the page cannot retain a pending Fetch/result indefinitely.
        withContext(NonCancellable) {
            runCatching {
                withTimeout(PLUGIN_BROWSER_SESSION_CLEANUP_TIMEOUT_MILLIS) {
                    evaluate(pluginBrowserSessionFetchCleanupScript(requestId))
                }
            }
        }
    }
}

private const val PLUGIN_BROWSER_SESSION_CLEANUP_TIMEOUT_MILLIS: Long = 1_000L

private fun isBrowserManagedHeader(name: String): Boolean {
    val lower = name.lowercase()
    return lower in BROWSER_MANAGED_HEADERS ||
        lower.startsWith("proxy-") ||
        lower.startsWith("sec-") ||
        lower.startsWith("access-control-request-")
}

private val BROWSER_HEADER_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")
private val BROWSER_MANAGED_HEADERS = setOf(
    "accept-charset",
    "accept-encoding",
    "connection",
    "content-length",
    "cookie",
    "cookie2",
    "date",
    "dnt",
    "expect",
    "host",
    "keep-alive",
    "origin",
    "referer",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    "user-agent",
    "via",
)
