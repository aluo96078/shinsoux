package dev.shinsou.kmp.plugin

import io.ktor.http.Url
import kotlinx.coroutines.delay
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
        if (!isBrowserManagedHeader(name)) headers[name] = rawValue
    }

    return PreparedPluginBrowserSessionRequest(
        sourceOrigin = canonicalSourceOrigin,
        targetOrigin = targetOrigin,
        request = request.copy(method = method, headers = headers),
    )
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
          const results = globalThis.__shinsouBrowserSessionResults ||
            (globalThis.__shinsouBrowserSessionResults = Object.create(null));
          delete results[requestId];
          (async () => {
            try {
              const response = await fetch($encodedUrl, {
                method: $encodedMethod,
                headers: $encodedHeaders,
                body: $encodedMethod === "POST" ? $encodedBody : undefined,
                mode: "cors",
                credentials: "omit",
                cache: "no-store",
                redirect: "error"
              });
              const limit = $PLUGIN_BROWSER_SESSION_MAX_RESPONSE_BYTES;
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
              } else {
                text = await response.text();
                if (new TextEncoder().encode(text).byteLength > limit) {
                  throw new Error("response_too_large");
                }
              }
              results[requestId] = { status: Number(response.status) || 0, body: text };
            } catch (error) {
              results[requestId] = {
                error: error && error.message === "response_too_large" ?
                  "response_too_large" : "fetch_failed"
              };
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
          const results = globalThis.__shinsouBrowserSessionResults;
          const value = results && results[$encodedId];
          if (!value) return "";
          delete results[$encodedId];
          return JSON.stringify(value);
        })()
    """.trimIndent()
}

internal data class PluginBrowserSessionFetchResult(
    val status: Int = 0,
    val body: String = "",
    val error: String? = null,
)

/** Handles both Android's JSON-quoted evaluateJavascript result and WK/JavaFX raw strings. */
internal fun decodePluginBrowserSessionFetchResult(raw: String?): PluginBrowserSessionFetchResult? {
    val value = raw?.trim().orEmpty()
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
    require(body.encodeToByteArray().size <= PLUGIN_BROWSER_SESSION_MAX_RESPONSE_BYTES) {
        "Browser-session response is too large"
    }
    return PluginBrowserSessionFetchResult(status, body, error)
}

/** Shared bounded polling loop used by WebView/WKWebView/JavaFX platform adapters. */
internal suspend fun executePluginBrowserSessionFetch(
    prepared: PreparedPluginBrowserSessionRequest,
    evaluate: suspend (String) -> String?,
): PluginHttpResponse = withTimeout(PLUGIN_BROWSER_SESSION_TIMEOUT_MILLIS) {
    val requestId = buildString {
        append(Clock.System.now().toEpochMilliseconds().toString(36))
        append('-')
        append(Random.nextLong().toULong().toString(36))
    }
    evaluate(pluginBrowserSessionFetchStartScript(requestId, prepared))
    while (true) {
        delay(40)
        val result = decodePluginBrowserSessionFetchResult(
            evaluate(pluginBrowserSessionFetchPollScript(requestId)),
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
