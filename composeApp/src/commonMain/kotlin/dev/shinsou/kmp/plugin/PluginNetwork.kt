package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.parseServerSetCookieHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.utils.io.readAvailable
import kotlin.time.Clock

internal const val PLUGIN_NETWORK_MAX_BATCH_REQUESTS: Int = 32
/** Hard cap on a single bridge batch, in addition to the in-flight cap above. */
internal const val PLUGIN_NETWORK_MAX_BATCH_TOTAL_REQUESTS: Int = 128
/**
 * Aggregate response-body cap for one bridge batch.
 *
 * Four MiB matches the default JavaScript bridge-result ceiling while still accommodating the
 * small metadata responses for which batching exists. Without an aggregate cap, 128 individually
 * valid responses could retain hundreds of MiB before the bridge encoded them.
 */
internal const val PLUGIN_NETWORK_MAX_BATCH_RESPONSE_BYTES: Int = 4 * 1_024 * 1_024
/**
 * Decoded-body cap for each request made by [PluginNetworkClient.postBatch].
 *
 * Keep this tied to the in-flight and aggregate limits: even when every concurrent response
 * reaches its limit before aggregate accounting runs, transports can materialize at most four
 * MiB of not-yet-accounted decoded bodies. Together with at most four MiB already accepted from
 * earlier chunks, the deterministic decoded-body peak is eight MiB instead of the previous
 * roughly 128 MiB. Batch is intentionally a small-metadata API; callers that need a larger
 * individual document retain the ordinary single-request path.
 */
internal const val PLUGIN_NETWORK_MAX_BATCH_ITEM_RESPONSE_BYTES: Int =
    PLUGIN_NETWORK_MAX_BATCH_RESPONSE_BYTES / PLUGIN_NETWORK_MAX_BATCH_REQUESTS
internal const val PLUGIN_NETWORK_MAX_REQUEST_BODY_BYTES: Int = 512 * 1_024
internal const val PLUGIN_NETWORK_MAX_HEADERS: Int = 64
internal const val PLUGIN_NETWORK_MAX_HEADER_NAME_BYTES: Int = 256
internal const val PLUGIN_NETWORK_MAX_HEADER_VALUE_BYTES: Int = 16 * 1_024
internal const val PLUGIN_NETWORK_MAX_HEADER_BYTES: Int = 64 * 1_024
internal const val PLUGIN_NETWORK_MAX_HOST_STATES: Int = 256
internal const val PLUGIN_NETWORK_MAX_RESPONSE_BYTES: Int = 16 * 1_024 * 1_024
internal const val PLUGIN_NETWORK_MAX_RESPONSE_HEADER_FIELDS: Int = 128
internal const val PLUGIN_NETWORK_MAX_RESPONSE_HEADER_NAME_BYTES: Int = 256
internal const val PLUGIN_NETWORK_MAX_RESPONSE_HEADER_VALUE_BYTES: Int = 16 * 1_024
internal const val PLUGIN_NETWORK_MAX_RESPONSE_HEADER_BYTES: Int = 64 * 1_024
internal const val PLUGIN_NETWORK_MAX_SET_COOKIE_HEADERS: Int = 64
internal const val PLUGIN_NETWORK_MAX_REDIRECT_LOCATION_BYTES: Int = 4 * 1_024
private const val PLUGIN_NETWORK_MAX_SET_COOKIE_ATTRIBUTES: Int = 32

public data class PluginHttpRequest(
    val method: String,
    val url: String,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
    /**
     * Maximum decoded response bytes this individual request may materialize.
     *
     * The value travels with the immutable request through proxy and redirect rebuilding so the
     * transport can stop a stream (including a decompression stream) before a caller-specific
     * policy limit has already been buffered. Host policy may narrow this value but never widen it.
     */
    val maxResponseBytes: Int = PLUGIN_NETWORK_MAX_RESPONSE_BYTES,
) {
    init {
        require(maxResponseBytes in 1..PLUGIN_NETWORK_MAX_RESPONSE_BYTES) {
            "Invalid plugin request response byte limit"
        }
    }
}

public data class PluginHttpResponse(
    val status: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    public fun bodyText(): String = body.decodeToString()
}

/** Incrementally validates and copies response headers before they cross the plugin boundary. */
internal class BoundedPluginResponseHeaders {
    private val valuesByName = linkedMapOf<String, MutableList<String>>()
    private val canonicalNames = mutableMapOf<String, String>()
    private var fieldCount = 0
    private var totalBytes = 0
    private var setCookieCount = 0
    private var locationCount = 0

    fun add(name: String, value: String) {
        require(name.isNotEmpty() && name.all(::isPluginResponseHeaderNameCharacter)) {
            "Invalid plugin response header name"
        }
        val nameBytes = pluginUtf8ByteCountAtMost(name, PLUGIN_NETWORK_MAX_RESPONSE_HEADER_NAME_BYTES)
            ?: throw IllegalArgumentException("Plugin response header name is too large")
        val valueBytes = pluginUtf8ByteCountAtMost(value, PLUGIN_NETWORK_MAX_RESPONSE_HEADER_VALUE_BYTES)
            ?: throw IllegalArgumentException("Plugin response header value is too large")
        require(value.none { (it.code <= 31 && it != '\t') || it.code == 127 }) {
            "Invalid plugin response header value"
        }
        require(fieldCount < PLUGIN_NETWORK_MAX_RESPONSE_HEADER_FIELDS) {
            "Too many plugin response headers"
        }
        require(
            nameBytes <= PLUGIN_NETWORK_MAX_RESPONSE_HEADER_BYTES - totalBytes &&
                valueBytes <= PLUGIN_NETWORK_MAX_RESPONSE_HEADER_BYTES - totalBytes - nameBytes,
        ) { "Plugin response headers are too large" }

        val normalizedName = name.lowercase()
        if (normalizedName == "set-cookie") {
            require(setCookieCount < PLUGIN_NETWORK_MAX_SET_COOKIE_HEADERS) {
                "Too many plugin response cookies"
            }
            setCookieCount++
        }
        if (normalizedName == "location") {
            require(locationCount == 0) { "Plugin response contains ambiguous redirect locations" }
            require(pluginUtf8ByteCountAtMost(value, PLUGIN_NETWORK_MAX_REDIRECT_LOCATION_BYTES) != null) {
                "Plugin redirect location is too large"
            }
            locationCount++
        }

        fieldCount++
        totalBytes += nameBytes + valueBytes
        val canonicalName = canonicalNames.getOrPut(normalizedName) { name }
        valuesByName.getOrPut(canonicalName) { mutableListOf() }.add(value)
    }

    fun build(): Map<String, List<String>> = valuesByName.mapValues { (_, values) -> values.toList() }
}

private fun isPluginResponseHeaderNameCharacter(character: Char): Boolean =
    character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
        character in "!#$%&'*+-.^_`|~"

internal fun boundedPluginResponseHeaders(
    entries: Iterable<Map.Entry<String, List<String>>>,
): Map<String, List<String>> {
    val builder = BoundedPluginResponseHeaders()
    entries.forEach { (name, values) ->
        if (values.isEmpty()) builder.add(name, "") else values.forEach { builder.add(name, it) }
    }
    return builder.build()
}

internal fun Map<String, List<String>>.pluginHeaderValues(name: String): List<String> =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

/** Strictly parses the single HTTP message length; ambiguity or invalid syntax fails closed. */
internal fun Map<String, List<String>>.pluginDeclaredContentLength(): Long? {
    val values = pluginHeaderValues("Content-Length")
    if (values.isEmpty()) return null
    require(values.size == 1) { "Plugin response contains ambiguous content lengths" }
    val value = values.single()
    require(value.isNotEmpty() && value.all(Char::isDigit)) {
        "Plugin response contains an invalid content length"
    }
    return value.toLongOrNull()
        ?: throw IllegalArgumentException("Plugin response contains an invalid content length")
}

/** Validates Content-Encoding framing and returns its canonical lower-case token. */
internal fun Map<String, List<String>>.pluginContentEncoding(): String? {
    val values = pluginHeaderValues("Content-Encoding")
    require(values.size <= 1) { "Plugin response contains ambiguous content encodings" }
    val encoding = values.singleOrNull()?.trim()?.lowercase()
    require(encoding in setOf(null, "", "identity", "gzip", "deflate")) {
        "Unsupported plugin response encoding"
    }
    return encoding
}

private fun boundedPluginHttpResponse(
    response: PluginHttpResponse,
    maxResponseBytes: Int,
): PluginHttpResponse {
    require(maxResponseBytes in 1..PLUGIN_NETWORK_MAX_RESPONSE_BYTES) {
        "Invalid plugin response byte limit"
    }
    require(response.body.size <= maxResponseBytes) { "Plugin response is too large" }
    val headers = boundedPluginResponseHeaders(response.headers.entries)
    headers.pluginContentEncoding()
    val declaredLength = headers.pluginDeclaredContentLength()
    require(declaredLength == null || declaredLength <= maxResponseBytes) {
        "Plugin response is too large"
    }
    return response.copy(headers = headers)
}

public fun interface PluginHttpTransport {
    /**
     * Executes [request] without buffering more decoded body bytes than
     * [PluginHttpRequest.maxResponseBytes]. Injectable transports must enforce that limit while
     * reading, not only after constructing [PluginHttpResponse]; callers verify the postcondition
     * defensively as well.
     */
    public suspend fun execute(request: PluginHttpRequest): PluginHttpResponse

    /**
     * Executes one request while preserving its URL hostname for HTTP Host/TLS SNI and limiting
     * the socket connection to [resolution]'s already-vetted addresses.
     *
     * A transport must override this method only when it can provide that complete guarantee.
     * The default deliberately fails: resolving before an ordinary transport call is vulnerable
     * to DNS rebinding because the transport performs a second, attacker-controlled lookup.
     */
    public suspend fun executeResolved(
        request: PluginHttpRequest,
        resolution: PluginHostResolution,
    ): PluginHttpResponse = throw IllegalStateException(
        "Plugin transport cannot bind the request to its validated DNS answers",
    )
}

/** Resolves every A/AAAA answer used by the plugin egress admission boundary. */
public fun interface PluginHostResolver {
    public suspend fun resolve(host: String): List<String>

    public companion object {
        /**
         * There is intentionally no fake common DNS implementation. Production compositions must
         * inject their platform resolver; tests and explicit developer compositions may inject a
         * deterministic resolver. An empty result is rejected before any transport is called.
         */
        public val Unavailable: PluginHostResolver = PluginHostResolver { emptyList() }

        /** Compatibility spelling retained with the new fail-closed behavior. */
        public val Default: PluginHostResolver = Unavailable
    }
}

/**
 * A complete, bounded set of public IP literals approved for one original hostname.
 *
 * This is intentionally not a data class: callers must not be able to use generated copy() to
 * forge an answer set after the admission boundary has validated it.
 */
public class PluginHostResolution internal constructor(
    public val host: String,
    addresses: List<String>,
) {
    private val approvedAddresses: List<String> = addresses.toList()
    public val addresses: List<String> get() = approvedAddresses
}

/**
 * Credential-free host fetcher for untrusted URLs returned as reader or download content.
 *
 * Instances can only be created from [PluginNetworkClient.scopedToContentPolicy], keeping
 * manifest content origins separate from the executable bridge request policy.
 */
public class PluginContentNetworkClient internal constructor(
    private val delegate: PluginNetworkClient,
    private val reviewedImage: (suspend (PluginHttpRequest) -> PluginHttpResponse?)? = null,
) {
    /** Host-only overlay, never available to a script bridge or manifest permission. */
    internal fun withReviewedImageTransport(
        fetch: suspend (PluginHttpRequest) -> PluginHttpResponse?,
    ): PluginContentNetworkClient = PluginContentNetworkClient(delegate, fetch)

    /** Validates and fetches a complete content body through the exact-origin redirect guard. */
    public suspend fun execute(
        sourceId: Long,
        request: PluginHttpRequest,
    ): PluginHttpResponse {
        require(request.method.equals("GET", ignoreCase = true) ||
            request.method.equals("HEAD", ignoreCase = true)) {
            "Plugin content requests permit only GET or HEAD"
        }
        require(request.body.isEmpty()) { "Plugin content requests cannot contain a body" }
        require(request.headers.keys.none(::isPluginContentCredentialHeader)) {
            "Plugin content requests cannot contain credentials"
        }
        reviewedImage?.invoke(request)?.let { return it }
        return delegate.execute(
            sourceId = sourceId,
            request = request.copy(headers = sanitizePluginContentHeaders(request.headers)),
        )
    }

    public suspend fun get(
        sourceId: Long,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): PluginHttpResponse = execute(sourceId, PluginHttpRequest("GET", url, headers = headers))

}

private fun isPluginContentCredentialHeader(name: String): Boolean = when (name.trim().lowercase()) {
    "authorization", "proxy-authorization", "cookie", "cookie2", "x-api-key", "x-auth-token" -> true
    else -> false
}

private fun isSafePluginContentHeader(name: String): Boolean = when (name.trim().lowercase()) {
    "accept", "accept-language", "cache-control", "if-modified-since", "if-none-match", "range",
    "referer", "user-agent", "x-image-ticket" -> true
    else -> false
}

/** Shared host-side projection for untrusted content metadata headers. */
internal fun sanitizePluginContentHeaders(headers: Map<String, String>): Map<String, String> =
    headers.filterKeys(::isSafePluginContentHeader)

/**
 * Headers whose meaning belongs to the HTTP transport, reverse proxy, or host configuration.
 *
 * A plugin-controlled value for any of these can make the URL/DNS/TLS admission describe one
 * destination while an HTTP server or Worker routes the request to another. Framing and
 * hop-by-hop headers are also transport-owned so an extension cannot create ambiguous requests.
 */
internal fun isForbiddenPluginControlledHeader(name: String): Boolean {
    val normalized = name.trim().lowercase()
    return normalized.startsWith(':') ||
        normalized == "authority" ||
        normalized == "proxy" || normalized.startsWith("proxy-") ||
        normalized.startsWith("x-forwarded-") ||
        normalized.startsWith("x-original-") ||
        normalized in FORBIDDEN_PLUGIN_CONTROLLED_HEADERS
}

private val FORBIDDEN_PLUGIN_CONTROLLED_HEADERS: Set<String> = setOf(
    "host",
    "connection",
    "keep-alive",
    "proxy-connection",
    "proxy-authorization",
    "transfer-encoding",
    "te",
    "trailer",
    "upgrade",
    "content-length",
    "forwarded",
    "x-real-ip",
    "via",
    "x-rewrite-url",
    ConfiguredPluginProxyResolver.PROXY_KEY_HEADER.lowercase(),
)

/** Exact-origin egress policy for one plugin runtime/source. Empty origins deny all requests. */
public data class PluginNetworkPolicy(
    val requestOrigins: Set<String> = emptySet(),
    /** Legacy spelling retained for callers being migrated to [requestOrigins]. */
    val allowedOrigins: Set<String> = emptySet(),
    val credentialOrigins: Set<String> = emptySet(),
    /** Content/image origins are metadata only and never authorize active requests. */
    val contentOrigins: Set<String> = emptySet(),
    /**
     * Reviewed-only dynamic content hosts. This is intentionally not a manifest field or a
     * general wildcard facility; only app-pinned suffixes for reviewed content CDNs are accepted.
     */
    internal val contentHostSuffixes: Set<String> = emptySet(),
    private val contentOnlyScope: Boolean = false,
    val browserSessionOrigins: Set<String> = emptySet(),
    /** Per-policy resolver override, primarily for deterministic tests/developer embeddings. */
    val resolver: PluginHostResolver? = null,
    val allowDeveloperLocalNetwork: Boolean = false,
    /** Explicit escape hatch for development transports that cannot bind DNS answers. */
    val allowDeveloperUnpinnedTransport: Boolean = false,
    val maxResponseBytes: Int = 4 * 1_024 * 1_024,
    /** Host content-only budget; never raises the executable bridge response budget. */
    internal val maxContentResponseBytes: Int = maxResponseBytes,
) {
    init {
        require(maxResponseBytes in 1..(16 * 1_024 * 1_024)) { "Invalid plugin response byte limit" }
        require(maxContentResponseBytes in 1..PLUGIN_NETWORK_MAX_RESPONSE_BYTES) { "Invalid content response byte limit" }
        val allowed = effectiveRequestOrigins()
        val credentials = normalizePluginOrigins(credentialOrigins)
        normalizePluginOrigins(contentOrigins)
        require(contentHostSuffixes.all { it in REVIEWED_DYNAMIC_CONTENT_SUFFIXES }) {
            "Unsupported reviewed content host suffix"
        }
        normalizePluginOrigins(browserSessionOrigins)
        require(credentials.all { it in allowed }) {
            "Credential origins must be allowed origins"
        }
    }

    public suspend fun validate(
        url: Url,
        fallbackResolver: PluginHostResolver = PluginHostResolver.Unavailable,
    ) {
        authorize(url, fallbackResolver)
    }

    internal suspend fun authorize(
        url: Url,
        fallbackResolver: PluginHostResolver,
    ): PluginHostResolution {
        require(url.protocol.name.equals("https", ignoreCase = true)) {
            "Plugin network requests must use HTTPS"
        }
        require(url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) { "Invalid plugin network URL" }
        val origin = pluginOrigin(url)
        require(origin in effectiveRequestOrigins() ||
            contentOnlyScope && credentialOrigins.isEmpty() &&
            contentHostSuffixes.any { suffix ->
                isReviewedDynamicContentHost(url, suffix)
            }) {
            "Plugin network origin was not declared"
        }
        return resolvePluginHost(
            url = url,
            resolver = resolver ?: fallbackResolver,
            allowLocalNetwork = allowDeveloperLocalNetwork,
        )
    }

    public fun maySendCredentials(url: Url, initialUrl: Url? = null): Boolean {
        val origin = pluginOrigin(url)
        // An empty credential allowlist is an explicit deny. Legacy compatibility, when needed,
        // is materialized by SourceIndexEntry.networkPolicy() and therefore does not make a
        // direct policy silently inherit request origins.
        return origin in normalizePluginOrigins(credentialOrigins)
    }

    public companion object {
        public val Default: PluginNetworkPolicy = PluginNetworkPolicy()
    }

    internal val isContentOnlyScope: Boolean get() = contentOnlyScope

    private fun effectiveRequestOrigins(): Set<String> = normalizePluginOrigins(requestOrigins + allowedOrigins)
}

private const val PLUGIN_NETWORK_MAX_DNS_ANSWERS: Int = 32

/** Validates a host-owned proxy endpoint without widening the plugin's declared origins. */
internal suspend fun resolvePluginTransportHost(
    url: Url,
    resolver: PluginHostResolver,
    allowLocalNetwork: Boolean,
): PluginHostResolution {
    require(url.protocol.name.equals("https", ignoreCase = true)) {
        "Plugin transport endpoints must use HTTPS"
    }
    require(url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) {
        "Invalid plugin transport URL"
    }
    return resolvePluginHost(url, resolver, allowLocalNetwork)
}

private suspend fun resolvePluginHost(
    url: Url,
    resolver: PluginHostResolver,
    allowLocalNetwork: Boolean,
): PluginHostResolution {
    val host = url.host.lowercase().trimEnd('.')
    if (allowLocalNetwork) return PluginHostResolution(host, listOf(host))
    require(!isBlockedPluginAddress(host)) { "Plugin network destination is local/private" }
    val answers = resolver.resolve(host)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    require(answers.isNotEmpty()) { "Plugin network host did not resolve" }
    require(answers.size <= PLUGIN_NETWORK_MAX_DNS_ANSWERS) {
        "Plugin network host returned too many DNS answers"
    }
    answers.forEach { answer ->
        require(isPluginIpLiteral(answer)) { "Plugin DNS resolver returned a non-address answer" }
        require(!isBlockedPluginAddress(answer)) { "Plugin network DNS answer is local/private" }
    }
    return PluginHostResolution(host, answers)
}

/** Alias retained for callers that use the more explicit security terminology. */
public typealias PluginEgressPolicy = PluginNetworkPolicy

internal fun pluginOrigin(url: Url): String = buildString {
    append(url.protocol.name.lowercase())
    append("://")
    append(url.host.lowercase().trimEnd('.'))
    val defaultPort = if (url.protocol.name.equals("https", true)) 443 else 80
    if (url.port != defaultPort) append(':').append(url.port)
}

internal fun normalizePluginOrigins(values: Iterable<String>): Set<String> = values.mapTo(linkedSetOf()) { value ->
    val parsed = Url(value.trim())
    require(parsed.protocol.name.equals("https", true) && parsed.host.isNotBlank()) {
        "Plugin origins must be HTTPS origins"
    }
    require(parsed.user.isNullOrEmpty() && parsed.password.isNullOrEmpty() && parsed.encodedPath in setOf("", "/") && parsed.parameters.isEmpty() &&
        parsed.fragment.isEmpty()) { "Plugin origins must be exact origins" }
    pluginOrigin(parsed)
}

/** Rejects IPv4 alternate spellings and IPv6 (including IPv4-mapped IPv6) without platform APIs. */
internal fun isBlockedPluginAddress(raw: String): Boolean {
    val host = raw.trim().trim('[', ']').trimEnd('.').lowercase()
    if (host == "localhost" || host.endsWith(".localhost") || host == "local" ||
        host.endsWith(".local") || host == "metadata.google.internal") return true
    parsePluginIpv4(host)?.let { value ->
        return value ushr 24 == 0x7f || value ushr 24 == 10 ||
            value ushr 24 == 172 && (value ushr 16 and 0xff) in 16..31 ||
            value ushr 16 == (192 shl 8 or 168) || value ushr 16 == (169 shl 8 or 254) ||
            value ushr 24 == 0 || value ushr 28 == 0xe || value == 0xffffffff.toInt() ||
            value ushr 22 == ((100 shl 2) or 1) ||
            value ushr 16 == (192 shl 8) ||
            value ushr 24 == 192 && (value ushr 16 and 0xff) == 0 ||
            value ushr 24 == 198 && (value ushr 16 and 0xff) == 51 ||
            value ushr 24 == 203 && (value ushr 16 and 0xff) == 0 ||
            value ushr 16 == (198 shl 8 or 18) ||
            value ushr 16 == (198 shl 8 or 19) ||
            value ushr 28 >= 0xe
    }
    if (':' !in host) return false
    val bytes = parsePluginIpv6(host) ?: return true
    val mapped = bytes.copyOfRange(12, 16).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
    if (bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
        bytes[10].toInt() and 0xff == 0xff && bytes[11].toInt() and 0xff == 0xff) {
        return isBlockedPluginAddress(mapped.toIpv4String())
    }
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    val compatible = bytes.copyOfRange(0, 12).all { it == 0.toByte() }
    val nat64 = bytes.copyOfRange(0, 12).contentEquals(byteArrayOf(
        0x00, 0x64, 0xff.toByte(), 0x9b.toByte(), 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
    )) || (first == 0x00 && second == 0x64 &&
        bytes[2].toInt() and 0xff == 0xff && bytes[3].toInt() and 0xff == 0x9b &&
        bytes[4].toInt() and 0xff == 0x00 && bytes[5].toInt() and 0xff == 0x01)
    if (compatible) {
        val compatibleIpv4 = bytes.copyOfRange(12, 16)
            .fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
        return isBlockedPluginAddress(compatibleIpv4.toIpv4String())
    }
    return nat64 ||
        (first == 0x20 && second == 0x02) ||
        (first == 0x20 && second == 0x01 && bytes[2].toInt() == 0) ||
        bytes[0].toInt() and 0xff == 0xfe && bytes[1].toInt() and 0xc0 == 0xc0 ||
        (first == 0xfe && second and 0xc0 == 0x80) ||
        (first == 0xff) || (first == 0xfc || first == 0xfd) ||
        (first == 0x20 && second == 0x01 && bytes[2].toInt() and 0xff == 0x0d &&
            bytes[3].toInt() and 0xff == 0xb8)
}

internal fun isPluginIpLiteral(raw: String): Boolean {
    val host = raw.trim().trim('[', ']').trimEnd('.')
    return isCanonicalPluginIpv4(host) ||
        (':' in host && parsePluginIpv6(host) != null && host.split(':').none { '.' in it && !isCanonicalPluginIpv4(it) })
}

private fun isCanonicalPluginIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && (part == "0" || !part.startsWith('0')) &&
            part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
    }
}

private fun Int.toIpv4String(): String = listOf(
    this ushr 24 and 0xff, this ushr 16 and 0xff, this ushr 8 and 0xff, this and 0xff,
).joinToString(".")

private fun parsePluginIpv4(value: String): Int? {
    if (value.isEmpty() || value.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == 'x' || it == 'X' || it == '.') }) return null
    val parts = value.split('.')
    if (parts.size !in 1..4) return null
    val nums = parts.map { part ->
        if (part.isEmpty()) return null
        val radix = if (part.startsWith("0x", true)) 16 else if (part.length > 1 && part.startsWith('0')) 8 else 10
        part.removePrefix("0x").removePrefix("0X").toLongOrNull(radix)?.takeIf { it >= 0 }
    }
    if (nums.any { it == null }) return null
    val n = nums.filterNotNull()
    val result = when (n.size) {
        1 -> n[0]
        2 -> (n[0] shl 24) or n[1]
        3 -> (n[0] shl 24) or (n[1] shl 16) or n[2]
        4 -> (n[0] shl 24) or (n[1] shl 16) or (n[2] shl 8) or n[3]
        else -> return null
    }
    val limits = when (n.size) { 1 -> listOf(0xffffffffL); 2 -> listOf(0xffL, 0xffffffL); 3 -> listOf(0xffL, 0xffL, 0xffffL); else -> List(4) { 0xffL } }
    if (n.zip(limits).any { (item, limit) -> item > limit }) return null
    return result.toInt()
}

private fun parsePluginIpv6(value: String): ByteArray? = runCatching {
    val halves = value.split("::")
    require(halves.size <= 2)
    fun parseSide(side: String): List<Int> = if (side.isEmpty()) emptyList() else side.split(':').flatMap { token ->
        if ('.' in token) listOfNotNull(parsePluginIpv4(token)?.let { it ushr 16 }, parsePluginIpv4(token)?.let { it and 0xffff })
        else listOf(token.toInt(16).also { require(it in 0..0xffff) })
    }
    val left = parseSide(halves[0]); val right = if (halves.size == 2) parseSide(halves[1]) else emptyList()
    val words = if (halves.size == 2) left + List(8 - left.size - right.size) { 0 } + right else left
    require(words.size == 8)
    ByteArray(16) { index -> (words[index / 2] ushr (if (index % 2 == 0) 8 else 0)).toByte() }
}.getOrNull()

public data class PluginProxyRoute(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

public fun interface PluginProxyResolver {
    public suspend fun route(sourceId: Long, targetUrl: String): PluginProxyRoute?

    public companion object {
        public val None: PluginProxyResolver = PluginProxyResolver { _, _ -> null }
    }
}

public fun interface PluginUserAgentProvider {
    public suspend fun userAgent(host: String): String
}

/** Runtime network settings, read on every request so changes do not require restarting a source. */
public data class PluginNetworkConfiguration(
    val proxyEnabled: Boolean = false,
    val proxyWorkerUrl: String = "",
    val proxyApiKey: String = "",
    val customUserAgent: String = "",
)

public fun interface PluginNetworkConfigurationProvider {
    public fun current(): PluginNetworkConfiguration
}

public enum class SourceNetworkOverride {
    GLOBAL,
    ON,
    OFF;

    /** Stable storage values retained for compatibility with existing source preferences. */
    public val storedValue: String
        get() = name.lowercase()

    public fun resolve(globalEnabled: Boolean): Boolean = when (this) {
        GLOBAL -> globalEnabled
        ON -> true
        OFF -> false
    }

    public companion object {
        public fun fromStored(value: String?, default: SourceNetworkOverride): SourceNetworkOverride =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: default
    }
}

/** Uses the configured UA when present and otherwise mirrors this platform's browser runtime. */
public class ConfiguredPluginUserAgentProvider(
    private val configuration: PluginNetworkConfigurationProvider,
    private val fallback: PluginUserAgentProvider = StickyPluginUserAgentProvider(),
) : PluginUserAgentProvider {
    override suspend fun userAgent(host: String): String =
        configuration.current().customUserAgent.trim().takeIf(String::isNotEmpty)
            ?: fallback.userAgent(host)
}

/**
 * Cloudflare Worker routing with per-source overrides.
 *
 * An unset source follows the global switch. Explicit `on` and `off` values remain authoritative.
 */
public class ConfiguredPluginProxyResolver(
    private val storage: PluginStorage,
    private val configuration: PluginNetworkConfigurationProvider,
) : PluginProxyResolver {
    override suspend fun route(sourceId: Long, targetUrl: String): PluginProxyRoute? {
        val current = configuration.current()
        val sourceOverride = SourceNetworkOverride.fromStored(
            storage.getPreference(sourceId, SOURCE_PROXY_PREFERENCE),
            default = SourceNetworkOverride.GLOBAL,
        )
        val enabled = sourceOverride.resolve(current.proxyEnabled)
        if (!enabled) return null

        val proxyUrl = buildPluginProxyUrl(current.proxyWorkerUrl, targetUrl) ?: return null
        val proxyHeaders = current.proxyApiKey.takeIf(String::isNotBlank)
            ?.let { mapOf(PROXY_KEY_HEADER to it) }
            .orEmpty()
        return PluginProxyRoute(proxyUrl, proxyHeaders)
    }

    public companion object {
        public const val SOURCE_PROXY_PREFERENCE: String = "network.proxy"
        public const val PROXY_KEY_HEADER: String = "X-Proxy-Key"
    }
}

/** Normalizes the user-entered Worker endpoint and encodes one target as its `url` query value. */
public fun buildPluginProxyUrl(workerUrl: String, targetUrl: String): String? = runCatching {
    val entered = workerUrl.trim().trimEnd('/')
    if (entered.isEmpty()) return null
    // A workers.dev hostname is commonly copied without a scheme on mobile keyboards. Treat that
    // as HTTPS instead of silently disabling an otherwise valid proxy configuration.
    val normalized = if (PLUGIN_PROXY_SCHEME_PREFIX.containsMatchIn(entered)) {
        entered
    } else {
        require(!entered.startsWith("//"))
        "https://$entered"
    }
    URLBuilder(normalized).apply {
        require(protocol.name == "http" || protocol.name == "https")
        require(host.isNotBlank())
        pathSegments = pathSegments.dropLastWhile(String::isEmpty) + ""
        fragment = ""
        parameters.append("url", targetUrl)
    }.buildString()
}.getOrNull()

private val PLUGIN_PROXY_SCHEME_PREFIX: Regex = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/** Per-host sticky UA, matching Shinsou while remaining deterministic in a process. */
public class StickyPluginUserAgentProvider(
    private val candidates: List<String> = listOf(
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Safari/605.1.15",
    ),
) : PluginUserAgentProvider {
    private val mutex = Mutex()
    private val byHost = mutableMapOf<String, String>()

    init {
        require(candidates.isNotEmpty())
    }

    override suspend fun userAgent(host: String): String = mutex.withLock {
        byHost.getOrPut(host.lowercase()) {
            candidates[(stableSourceId(host).toULong() % candidates.size.toUInt()).toInt()]
        }
    }
}

public data class PluginRateLimit(val permits: Int = 5, val periodMillis: Long = 1_000L) {
    init {
        require(permits > 0)
        require(periodMillis >= 0)
    }
}

public fun interface PluginRateLimitProvider {
    public fun limit(host: String): PluginRateLimit

    public companion object {
        public val Default: PluginRateLimitProvider = PluginRateLimitProvider { PluginRateLimit() }
    }
}

/** Spaces request starts per host without holding the reservation lock during network transport. */
public class PerHostRequestGate(
    private val limits: PluginRateLimitProvider = PluginRateLimitProvider.Default,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val maxHostStates: Int = PLUGIN_NETWORK_MAX_HOST_STATES,
) {
    private data class HostState(
        val mutex: Mutex = Mutex(),
        var nextRequestAt: Long = 0L,
        var users: Int = 0,
        var lastUsed: Long = 0L,
    )

    private val stateMutex = Mutex()
    private val states = mutableMapOf<String, HostState>()
    private var useSequence = 0L

    init {
        require(maxHostStates > 0) { "Plugin network host-state limit must be positive" }
    }

    public suspend fun <T> run(host: String, block: suspend () -> T): T {
        val key = host.lowercase()
        val state = stateMutex.withLock {
            (states[key] ?: run {
                if (states.size >= maxHostStates) {
                    val eviction = states.entries
                        .asSequence()
                        .filter { it.value.users == 0 && it.value.nextRequestAt <= nowEpochMillis() }
                        .minByOrNull { it.value.lastUsed }
                    checkNotNull(eviction) {
                        "Too many active plugin network host states"
                    }
                    states.remove(eviction.key)
                }
                HostState().also { states[key] = it }
            }).also {
                it.users += 1
                it.lastUsed = ++useSequence
            }
        }
        try {
            state.mutex.withLock {
                val rate = limits.limit(key)
                val wait = state.nextRequestAt - nowEpochMillis()
                if (wait > 0) delayMillis(wait)
                val spacing = if (rate.periodMillis == 0L) 0L else rate.periodMillis / rate.permits
                state.nextRequestAt = nowEpochMillis() + spacing
            }
        } finally {
            stateMutex.withLock {
                state.users -= 1
                state.lastUsed = ++useSequence
            }
        }
        return block()
    }
}

public data class BuiltPluginRequest(
    val originalUrl: Url,
    val transportRequest: PluginHttpRequest,
)

/** Shared by the JS bridge, reader, and downloader so request semantics cannot drift. */
public class PluginRequestBuilder(
    private val storage: PluginStorage,
    private val userAgents: PluginUserAgentProvider = StickyPluginUserAgentProvider(),
    private val proxyResolver: PluginProxyResolver = PluginProxyResolver.None,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /**
     * Retains the host's user-agent/proxy policy while replacing only the state view used for
     * cookies. Reviewed runtimes use this to make their granted storage permissions effective for
     * automatic request/response cookie handling as well as direct JavaScript bridge calls.
     */
    public fun scopedToStorage(storage: PluginStorage): PluginRequestBuilder = PluginRequestBuilder(
        storage = storage,
        userAgents = userAgents,
        proxyResolver = proxyResolver,
        nowEpochMillis = nowEpochMillis,
    )


    public suspend fun build(
        sourceId: Long,
        request: PluginHttpRequest,
        sourceHeaders: Map<String, String> = emptyMap(),
        referer: String? = null,
        includeStoredCookies: Boolean = true,
        includeHostCredentials: Boolean = true,
    ): BuiltPluginRequest {
        // Fragments are client-side identifiers and never belong on an HTTP request target.
        // Legacy image metadata is decoded by its content adapter before this final boundary;
        // stripping here is defense in depth for every direct JS/network call and redirect.
        val target = Url(request.url.substringBefore('#'))
        val targetUrl = target.toString()
        requirePluginControlledHeaders(request.headers)
        requirePluginControlledHeaders(sourceHeaders)
        val headers = linkedMapOf<String, String>()
        headers.putAll(sourceHeaders)
        request.headers.forEach { (name, value) -> putHeader(headers, name, value) }
        // Host proxy secrets and a challenge-bound UA are credentials too. Content-plane calls
        // disable both, in addition to cookies, so attacker-controlled media URLs cannot exfiltrate
        // them or make an undeclared proxy perform the request on their behalf.
        val proxy = if (includeHostCredentials) proxyResolver.route(sourceId, targetUrl) else null
        val browserBoundUserAgent = if (includeHostCredentials) storage.getWebChallengeUserAgent(sourceId) else null
            ?.let(::normalizePluginUserAgent)
        if (browserBoundUserAgent != null) {
            // Cloudflare binds clearance to the browser's real UA. Once a verified browser
            // session is imported, it must take priority over plugin and request header hints.
            putHeader(headers, "User-Agent", browserBoundUserAgent)
        } else if (proxy != null) {
            // A Worker receives the app's request headers and forwards them to the destination.
            // Always give proxied traffic the current platform browser identity; otherwise a
            // source's hard-coded iPhone/desktop hint can make every device look like the wrong
            // browser. ConfiguredPluginUserAgentProvider still permits an explicit manual override.
            putHeader(headers, "User-Agent", userAgents.userAgent(target.host))
        } else if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = userAgents.userAgent(target.host)
        }
        if (!referer.isNullOrBlank() && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            headers["Referer"] = referer
        }

        val matching = (if (includeStoredCookies) storage.getCookies(sourceId) else emptyList<PluginCookie>())
            .filter { it.matches(target, nowEpochMillis()) }
            .sortedWith(compareByDescending<PluginCookie> { it.path.length }.thenBy { it.name })
        if (matching.isNotEmpty()) {
            val existingKey = headers.keys.firstOrNull { it.equals("Cookie", ignoreCase = true) }
            val existingValue = existingKey?.let(headers::getValue).orEmpty()
            val explicitCookieNames = existingValue.split(';').mapNotNullTo(hashSetOf()) { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNullTo null
                pair.substring(0, separator).trim().takeIf(::isValidCookieName)
            }
            // Explicit source/request cookies take precedence over the persisted jar. Keep
            // same-name jar cookies on different paths when no explicit value was supplied,
            // because RFC cookie ordering permits those entries to coexist.
            val cookieHeader = matching
                .filterNot { it.name in explicitCookieNames }
                .joinToString("; ") { "${it.name}=${it.value}" }
            if (cookieHeader.isNotEmpty()) {
                if (existingKey == null) headers["Cookie"] = cookieHeader
                else headers[existingKey] = listOf(existingValue, cookieHeader)
                    .filter(String::isNotBlank)
                    .joinToString("; ")
            }
        }

        proxy?.headers?.forEach { (name, value) -> putHeader(headers, name, value) }
        return BuiltPluginRequest(
            originalUrl = target,
            transportRequest = request.copy(url = proxy?.url ?: targetUrl, headers = headers),
        )
    }

    private fun putHeader(headers: MutableMap<String, String>, name: String, value: String) {
        headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(headers::remove)
        headers[name] = value
    }

    private fun requirePluginControlledHeaders(headers: Map<String, String>) {
        require(headers.keys.none(::isForbiddenPluginControlledHeader)) {
            "Plugin request contains a transport-owned header"
        }
    }
}

internal fun normalizePluginUserAgent(value: String?): String? = value
    ?.trim()
    ?.takeIf { candidate ->
        candidate.isNotEmpty() && candidate.length <= 512 && candidate.none { it.code in 0..31 || it.code == 127 }
    }

public class KtorPluginHttpTransport(private val client: HttpClient) : PluginHttpTransport {
    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse {
        val response: HttpResponse = client.request(request.url) {
            method = HttpMethod.parse(request.method)
            request.headers.forEach { (name, value) -> headers.append(name, value) }
            if (request.body.isNotEmpty()) setBody(request.body)
        }
        val channel = response.bodyAsChannel()
        try {
            val responseHeaders = boundedPluginResponseHeaders(response.headers.entries())
            responseHeaders.pluginContentEncoding()
            val declaredLength = responseHeaders.pluginDeclaredContentLength()
            // Apply the same cap to wire framing and to the decoded stream. The stream check is
            // still required because compressed framing describes encoded, not expanded, bytes.
            require(declaredLength == null || declaredLength <= request.maxResponseBytes) {
                "Plugin response is too large"
            }
            val body = readBoundedPluginResponseBody(channel, request.maxResponseBytes)
            return PluginHttpResponse(
                status = response.status.value,
                body = body,
                headers = responseHeaders,
            )
        } catch (error: Throwable) {
            // Stop the engine/decompression pipeline immediately on a limit or header failure.
            // In particular this prevents a gzip bomb from continuing in the background after
            // the decoded-byte admission boundary has rejected it.
            channel.cancel(null)
            throw error
        }
    }
}

/** Reads at most one sentinel byte beyond [maxResponseBytes], then fails before further input. */
internal suspend fun readBoundedPluginResponseBody(
    channel: io.ktor.utils.io.ByteReadChannel,
    maxResponseBytes: Int,
): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    var total = 0
    while (true) {
        // Once exactly at the boundary, request only one byte to distinguish EOF from overflow.
        // This also keeps a very small per-request budget from causing a fixed 16 KiB read-ahead.
        val readCapacity = minOf(16 * 1_024, maxResponseBytes - total + 1)
        val chunk = ByteArray(readCapacity)
        val read = channel.readAvailable(chunk)
        if (read < 0) break
        // Ktor's suspending readAvailable waits for content and returns -1 on EOF. Treat a
        // zero-byte result as a broken transport rather than spinning a CPU core indefinitely.
        check(read != 0) { "Plugin response transport made no read progress" }
        require(read <= maxResponseBytes - total) { "Plugin response is too large" }
        total += read
        chunks += if (read == chunk.size) chunk else chunk.copyOf(read)
    }
    val body = ByteArray(total)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(body, offset)
        offset += chunk.size
    }
    return body
}

/** Adds source-isolated cookies around a raw transport. */
public class PluginNetworkClient(
    private val transport: PluginHttpTransport,
    private val storage: PluginStorage,
    private val requestBuilder: PluginRequestBuilder = PluginRequestBuilder(storage),
    private val requestGate: PerHostRequestGate = PerHostRequestGate(),
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val policy: PluginNetworkPolicy = PluginNetworkPolicy.Default,
    private val hostResolver: PluginHostResolver = PluginHostResolver.Unavailable,
    private val credentialsEnabled: Boolean = true,
    private val explicitRefererWithoutCredentials: Boolean = false,
    /** Host-reviewed code may use the platform stack without granting this to repository code. */
    private val trustedReviewedTransport: Boolean = false,
    /** Exact reviewed ShuYue artifacts may carry this one legacy transport-owned header. */
    private val stripReviewedLegacyConnectionHeader: Boolean = false,
) {
    /** Shares transport/rate-limit policy but applies a capability-filtered storage view. */
    public fun scopedToStorage(storage: PluginStorage): PluginNetworkClient = PluginNetworkClient(
        transport = transport,
        storage = storage,
        requestBuilder = requestBuilder.scopedToStorage(storage),
        requestGate = requestGate,
        nowEpochMillis = nowEpochMillis,
        policy = policy,
        hostResolver = hostResolver,
        credentialsEnabled = credentialsEnabled,
        explicitRefererWithoutCredentials = explicitRefererWithoutCredentials,
        trustedReviewedTransport = trustedReviewedTransport,
        stripReviewedLegacyConnectionHeader = stripReviewedLegacyConnectionHeader,
    )

    /** Binds a manifest/runtime-specific exact-origin policy while preserving this client setup. */
    public fun scopedToPolicy(policy: PluginNetworkPolicy): PluginNetworkClient = PluginNetworkClient(
        transport = transport,
        storage = storage,
        requestBuilder = requestBuilder,
        requestGate = requestGate,
        nowEpochMillis = nowEpochMillis,
        policy = policy,
        hostResolver = hostResolver,
        credentialsEnabled = credentialsEnabled,
        explicitRefererWithoutCredentials = explicitRefererWithoutCredentials,
        trustedReviewedTransport = trustedReviewedTransport,
        stripReviewedLegacyConnectionHeader = stripReviewedLegacyConnectionHeader,
    )

    /**
     * Host-only scope for built-in, digest-reviewed ShuYue code. Repository plugin metadata cannot
     * reach this method through a bridge, and generic source policies remain pinned/fail-closed.
     */
    public fun forReviewedInProcessArtifact(): PluginNetworkClient = PluginNetworkClient(
        transport = transport,
        storage = storage,
        requestBuilder = requestBuilder,
        requestGate = requestGate,
        nowEpochMillis = nowEpochMillis,
        policy = policy,
        hostResolver = hostResolver,
        credentialsEnabled = credentialsEnabled,
        explicitRefererWithoutCredentials = explicitRefererWithoutCredentials,
        trustedReviewedTransport = false,
        stripReviewedLegacyConnectionHeader = true,
    )

    /**
     * Creates the host content plane for one already-admitted source declaration.
     *
     * [PluginNetworkPolicy.contentOrigins] is deliberately copied into a new request allowlist;
     * it never widens the executable bridge's POST/request origins. The resulting client permits
     * only credential-free GET/HEAD requests and ignores every Set-Cookie response.
     */
    public fun scopedToContentPolicy(policy: PluginNetworkPolicy): PluginContentNetworkClient =
        PluginContentNetworkClient(
            PluginNetworkClient(
                transport = transport,
                storage = storage,
                requestBuilder = requestBuilder,
                requestGate = requestGate,
                nowEpochMillis = nowEpochMillis,
                policy = PluginNetworkPolicy(
                    requestOrigins = policy.contentOrigins,
                    credentialOrigins = emptySet(),
                    contentOrigins = emptySet(),
                    contentHostSuffixes = policy.contentHostSuffixes,
                    contentOnlyScope = true,
                    browserSessionOrigins = emptySet(),
                    resolver = policy.resolver,
                    allowDeveloperLocalNetwork = policy.allowDeveloperLocalNetwork,
                    allowDeveloperUnpinnedTransport = policy.allowDeveloperUnpinnedTransport,
                    maxResponseBytes = policy.maxContentResponseBytes,
                ),
                hostResolver = hostResolver,
                credentialsEnabled = false,
                explicitRefererWithoutCredentials = true,
                trustedReviewedTransport = trustedReviewedTransport,
                stripReviewedLegacyConnectionHeader = stripReviewedLegacyConnectionHeader,
            ),
        )

    /** Reviewed browser images share the same per-host request-start spacing as native images. */
    internal suspend fun <T> withContentRequestGate(host: String, block: suspend () -> T): T =
        requestGate.run(host, block)

    public suspend fun execute(
        sourceId: Long,
        request: PluginHttpRequest,
        sourceHeaders: Map<String, String> = emptyMap(),
        referer: String? = null,
    ): PluginHttpResponse {
        require(request.body.size <= PLUGIN_NETWORK_MAX_REQUEST_BODY_BYTES) {
            "Plugin request body is too large"
        }
        val compatibleRequestHeaders = reviewedCompatibleHeaders(request.headers)
        val compatibleSourceHeaders = reviewedCompatibleHeaders(sourceHeaders)
        validatePluginControlledHeaderMap(compatibleRequestHeaders, "request")
        validatePluginControlledHeaderMap(compatibleSourceHeaders, "source")
        // A caller may voluntarily request a smaller cap, while repository/runtime policy owns
        // the maximum. Carry the effective value through every subsequent request reconstruction.
        var currentRequest = request.copy(
            headers = compatibleRequestHeaders,
            maxResponseBytes = minOf(request.maxResponseBytes, policy.maxResponseBytes),
        )
        val initialUrl = Url(request.url)
        var redirects = 0
        while (true) {
            val target = Url(currentRequest.url)
            val targetResolution = policy.authorize(target, hostResolver)
            val sameInitialOrigin = sameOrigin(initialUrl, target)
            val credentialOrigin = credentialsEnabled && policy.maySendCredentials(target, initialUrl)
            val hopSourceHeaders = if (credentialOrigin) compatibleSourceHeaders else emptyMap()
            val requestHeaders = if (credentialOrigin) {
                currentRequest.headers
            } else {
                safeCrossOriginHeaders(
                    currentRequest.headers,
                    includeReferer = explicitRefererWithoutCredentials,
                    includeImageTicket = policy.isContentOnlyScope && sameInitialOrigin,
                )
            }
            val built = requestBuilder.build(
                sourceId,
                currentRequest.copy(headers = requestHeaders),
                hopSourceHeaders,
                referer = referer.takeIf { credentialOrigin },
                includeStoredCookies = credentialOrigin,
                includeHostCredentials = credentialOrigin,
            )
            validatePluginRequestLimits(built.transportRequest)
            val transportUrl = Url(built.transportRequest.url)
            val transportResolution = if (sameOrigin(target, transportUrl)) {
                targetResolution
            } else {
                // A configured Worker is host-owned, not extension-declared, but is still subject
                // to the same DNS/private-address boundary before receiving plugin credentials.
                resolvePluginTransportHost(
                    transportUrl,
                    policy.resolver ?: hostResolver,
                    policy.allowDeveloperLocalNetwork,
                )
            }
            val response = boundedPluginHttpResponse(requestGate.run(built.originalUrl.host) {
                when {
                    policy.allowDeveloperUnpinnedTransport ->
                        transport.execute(built.transportRequest)
                    else -> transport.executeResolved(built.transportRequest, transportResolution)
                }
            }, built.transportRequest.maxResponseBytes)
            // Set-Cookie is a credential-plane write. An origin that is allowed only for ordinary
            // requests must not plant a parent-domain cookie which will later be sent to a
            // credential origin (for example evil.api.example -> Domain=api.example).
            if (credentialOrigin) storeResponseCookies(sourceId, response, built.originalUrl)

            val location = response.headers.pluginHeaderValues("Location").singleOrNull()?.trim().orEmpty()
            if (response.status !in REDIRECT_STATUSES || location.isEmpty()) return response
            check(redirects < MAX_REDIRECTS) { "Too many redirects while requesting ${request.url}" }

            val nextUrl = runCatching {
                Url(ViewerImageParser.resolveUrl(built.originalUrl.toString(), location))
            }.getOrElse { throw IllegalStateException("Invalid plugin redirect location", it) }
            policy.validate(nextUrl, hostResolver)
            check(nextUrl.protocol.name == "http" || nextUrl.protocol.name == "https") {
                "Unsupported redirect scheme: ${nextUrl.protocol.name}"
            }
            check(!(built.originalUrl.protocol.name == "https" && nextUrl.protocol.name == "http")) {
                "Refusing an HTTPS to HTTP redirect"
            }

            val crossOrigin = !sameOrigin(built.originalUrl, nextUrl)
            val switchToGet = response.status == 303 ||
                response.status in setOf(301, 302) && currentRequest.method.equals("POST", ignoreCase = true)
            check(
                !crossOrigin || switchToGet || currentRequest.body.isEmpty() &&
                    (currentRequest.method.equals("GET", ignoreCase = true) ||
                        currentRequest.method.equals("HEAD", ignoreCase = true)),
            ) { "Refusing to redirect a request body to a different origin" }
            val redirectedHeaders = if (crossOrigin) {
                safeCrossOriginHeaders(
                    currentRequest.headers,
                    includeReferer = credentialsEnabled && policy.maySendCredentials(nextUrl, initialUrl),
                )
            } else {
                currentRequest.headers
            }.filterKeys { name ->
                !switchToGet || !name.equals("Content-Length", ignoreCase = true) &&
                    !name.equals("Content-Type", ignoreCase = true) &&
                    !name.equals("Transfer-Encoding", ignoreCase = true)
            }
            currentRequest = currentRequest.copy(
                method = if (switchToGet) "GET" else currentRequest.method,
                url = nextUrl.toString(),
                body = if (switchToGet) ByteArray(0) else currentRequest.body,
                headers = redirectedHeaders,
            )
            redirects++
        }
    }

    private fun reviewedCompatibleHeaders(headers: Map<String, String>): Map<String, String> =
        if (!stripReviewedLegacyConnectionHeader) {
            headers
        } else {
            headers.filterKeys { !it.equals("Connection", ignoreCase = true) }
        }

    private suspend fun storeResponseCookies(
        sourceId: Long,
        response: PluginHttpResponse,
        origin: Url,
    ) {
        // Automatic cookie persistence is an optional side effect of NETWORK. A runtime that
        // lacks COOKIE_STORAGE must still be able to make ordinary network requests; direct
        // bridge cookie mutation remains fail-closed in PermissionFilteredPluginStorage.
        if (storage is PermissionFilteredPluginStorage && !storage.cookiesAllowedForNetwork) return
        response.headers.pluginHeaderValues("Set-Cookie")
            .mapNotNull { parseSetCookie(it, origin) }
            .forEach { cookie ->
                if (cookie.expiresAtEpochMillis != null && cookie.expiresAtEpochMillis <= nowEpochMillis()) {
                    storage.deleteCookieExact(sourceId, cookie.name, cookie.domain, cookie.path)
                } else {
                    storage.setCookie(sourceId, cookie)
                }
            }
    }

    public suspend fun get(
        sourceId: Long,
        url: String,
        headers: Map<String, String>,
        sourceHeaders: Map<String, String> = emptyMap(),
        referer: String? = null,
    ): PluginHttpResponse = execute(
        sourceId,
        PluginHttpRequest("GET", url, headers = headers),
        sourceHeaders,
        referer,
    )

    public suspend fun post(
        sourceId: Long,
        url: String,
        body: String,
        headers: Map<String, String>,
        sourceHeaders: Map<String, String> = emptyMap(),
        referer: String? = null,
    ): PluginHttpResponse = execute(
        sourceId,
        PluginHttpRequest("POST", url, body.encodeToByteArray(), headers),
        sourceHeaders,
        referer,
    )

    /**
     * Executes several POST requests concurrently while retaining the normal source isolation,
     * cookie, redirect, proxy, user-agent, and per-host rate-limit policies for every request.
     *
     * The caller receives responses in the same order as [urls]. At most
     * [PLUGIN_NETWORK_MAX_BATCH_REQUESTS] requests are in flight at once; larger batches are processed in
     * ordered chunks so a plugin cannot accidentally create an unbounded request fan-out. Each
     * immutable transport request carries [PLUGIN_NETWORK_MAX_BATCH_ITEM_RESPONSE_BYTES] as its
     * decoded-body limit. Consequently, not-yet-accounted allocation is bounded by
     * `in-flight * per-item <= 4 MiB`; the separate aggregate budget bounds retained responses
     * across chunks to four MiB. The combined decoded-body peak is therefore at most eight MiB.
     *
     * Compatibility note: batching remains concurrent for small metadata, but an individual
     * batch response larger than the per-item limit is rejected during transport streaming. Use
     * the ordinary single-request path for larger response documents.
     *
     * Failures are intentionally propagated as a batch failure. Synchronous plugin bridges can
     * then fall back to their existing one-request path instead of silently dropping individual
     * books.
     */
    public suspend fun postBatch(
        sourceId: Long,
        urls: List<String>,
        bodies: List<String>,
        headers: Map<String, String> = emptyMap(),
        sourceHeaders: Map<String, String> = emptyMap(),
        referer: String? = null,
    ): List<PluginHttpResponse> {
        require(urls.size == bodies.size) { "Batch URL/body count mismatch" }
        require(urls.size <= PLUGIN_NETWORK_MAX_BATCH_TOTAL_REQUESTS) {
            "Plugin batch exceeds total request limit"
        }
        if (urls.isEmpty()) return emptyList()

        val responseBudget = PluginBatchResponseBudget(PLUGIN_NETWORK_MAX_BATCH_RESPONSE_BYTES)
        return coroutineScope {
            urls.indices.chunked(PLUGIN_NETWORK_MAX_BATCH_REQUESTS).flatMap { chunk ->
                chunk.map { index ->
                    // A runtime invokes this method synchronously on its JavaScript worker. Move
                    // each transport operation off that worker so the batch can overlap network
                    // waits without making the JS engine itself re-entrant.
                    async(Dispatchers.Default) {
                        execute(
                            sourceId = sourceId,
                            request = PluginHttpRequest(
                                method = "POST",
                                url = urls[index],
                                body = bodies[index].encodeToByteArray(),
                                headers = headers,
                                maxResponseBytes = PLUGIN_NETWORK_MAX_BATCH_ITEM_RESPONSE_BYTES,
                            ),
                            sourceHeaders = sourceHeaders,
                            referer = referer,
                        ).also { response ->
                            // Responses finish concurrently, so admission must be atomic. Throwing
                            // from one child cancels the remaining children through coroutineScope;
                            // no partial batch is returned to plugin code.
                            responseBudget.account(response.body.size)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun parseSetCookie(header: String, requestUrl: Url): PluginCookie? {
        if (pluginUtf8ByteCountAtMost(header, PLUGIN_NETWORK_MAX_RESPONSE_HEADER_VALUE_BYTES) == null ||
            header.count { it == ';' } > PLUGIN_NETWORK_MAX_SET_COOKIE_ATTRIBUTES) return null
        val parts = header.split(';').map(String::trim)
        val nameValue = parts.firstOrNull() ?: return null
        val separator = nameValue.indexOf('=')
        if (separator <= 0) return null
        val name = nameValue.substring(0, separator).trim()
        val value = nameValue.substring(separator + 1)
        if (!isValidCookieName(name) || !isValidCookieValue(value)) return null
        if (name.encodeToByteArray().size + value.encodeToByteArray().size > MAX_COOKIE_BYTES) return null
        var domain = requestUrl.host
        var hostOnly = true
        var path = defaultCookiePath(requestUrl.encodedPath)
        var expiresValue: String? = null
        var maxAgeValue: String? = null
        var secure = false
        var httpOnly = false
        for (attribute in parts.drop(1)) {
            val attributeSeparator = attribute.indexOf('=')
            val key = (if (attributeSeparator < 0) attribute else attribute.substring(0, attributeSeparator))
                .trim().lowercase()
            val attributeValue = if (attributeSeparator < 0) "" else attribute.substring(attributeSeparator + 1).trim()
            when (key) {
                "domain" -> {
                    if (attributeValue.isEmpty() || attributeValue.endsWith('.')) return null
                    val candidate = canonicalCookieDomain(attributeValue)
                    val requestHost = canonicalCookieDomain(requestUrl.host)
                    if (!isValidCookieDomainSyntax(candidate) || isCookieIpAddress(requestHost)) return null
                    if (requestHost != candidate && !requestHost.endsWith(".$candidate")) return null
                    if (isPublicSuffix(candidate)) return null
                    domain = candidate
                    hostOnly = false
                }
                "path" -> if (attributeValue.startsWith('/') && attributeValue.length <= 1_024) path = attributeValue
                "max-age" -> if (maxAgeValue == null) maxAgeValue = attributeValue
                "expires" -> if (expiresValue == null) expiresValue = attributeValue
                "secure" -> secure = true
                "httponly" -> httpOnly = true
            }
        }
        val maxAge = maxAgeValue?.let(::parseSaturatedSeconds)
        val expiresAt = when {
            maxAge != null -> maxAgeExpiry(nowEpochMillis(), maxAge)
            expiresValue != null -> parseCookieExpires(expiresValue.orEmpty())
            else -> null
        }
        return normalizedPluginCookieOrNull(
            PluginCookie(name, value, domain, path, expiresAt, secure, httpOnly, hostOnly),
        )
    }

    private fun defaultCookiePath(encodedPath: String): String {
        if (!encodedPath.startsWith('/') || encodedPath == "/") return "/"
        val slash = encodedPath.lastIndexOf('/')
        return if (slash <= 0) "/" else encodedPath.substring(0, slash)
    }

    private fun validatePluginRequestLimits(request: PluginHttpRequest) {
        require(request.body.size <= PLUGIN_NETWORK_MAX_REQUEST_BODY_BYTES) {
            "Plugin request body is too large"
        }
        validatePluginControlledHeaderMap(request.headers, "request", forbidTransportOwned = false)
    }

    private fun validatePluginControlledHeaderMap(
        headers: Map<String, String>,
        kind: String,
        forbidTransportOwned: Boolean = true,
    ) {
        require(headers.size <= PLUGIN_NETWORK_MAX_HEADERS) { "Too many plugin $kind headers" }
        var totalBytes = 0
        headers.forEach { (name, value) ->
            require(name.isNotEmpty() && name.all(::isPluginResponseHeaderNameCharacter)) {
                "Invalid plugin $kind header"
            }
            if (forbidTransportOwned) {
                require(!isForbiddenPluginControlledHeader(name)) {
                    "Plugin $kind contains a transport-owned header"
                }
            }
            val nameBytes = pluginUtf8ByteCountAtMost(name, PLUGIN_NETWORK_MAX_HEADER_NAME_BYTES)
                ?: throw IllegalArgumentException("Plugin $kind header name is too large")
            val valueBytes = pluginUtf8ByteCountAtMost(value, PLUGIN_NETWORK_MAX_HEADER_VALUE_BYTES)
                ?: throw IllegalArgumentException("Plugin $kind header value is too large")
            require(value.none { it.code <= 31 || it.code == 127 }) {
                "Invalid plugin $kind header value"
            }
            require(nameBytes <= PLUGIN_NETWORK_MAX_HEADER_BYTES - totalBytes &&
                valueBytes <= PLUGIN_NETWORK_MAX_HEADER_BYTES - totalBytes - nameBytes) {
                "Plugin $kind headers are too large"
            }
            totalBytes += nameBytes + valueBytes
        }
    }

    private fun safeCrossOriginHeaders(
        headers: Map<String, String>,
        includeReferer: Boolean,
        includeImageTicket: Boolean = false,
    ): Map<String, String> = headers.filterKeys {
        (it.lowercase() in SAFE_CROSS_ORIGIN_HEADERS ||
            includeImageTicket && it.equals("x-image-ticket", ignoreCase = true)) &&
            (includeReferer || !it.equals("referer", true))
    }

    private fun sameOrigin(left: Url, right: Url): Boolean =
        left.protocol.name.equals(right.protocol.name, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            left.port == right.port

    private fun parseCookieExpires(value: String): Long? = runCatching {
        parseServerSetCookieHeader("expires-probe=x; Expires=$value").expires?.timestamp
    }.getOrNull()

    private fun parseSaturatedSeconds(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val negative = trimmed.startsWith('-')
        val digits = trimmed.removePrefix("-").removePrefix("+")
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return null
        var result = 0L
        digits.forEach { character ->
            val digit = character.digitToInt()
            if (result > (Long.MAX_VALUE - digit) / 10L) {
                return if (negative) Long.MIN_VALUE else Long.MAX_VALUE
            }
            result = result * 10L + digit
        }
        return if (negative) -result else result
    }

    private fun maxAgeExpiry(now: Long, seconds: Long): Long {
        if (seconds <= 0L) return now
        val maximumSeconds = (Long.MAX_VALUE - now.coerceAtLeast(0L)) / 1_000L
        return if (seconds >= maximumSeconds) Long.MAX_VALUE else now + seconds * 1_000L
    }

    private fun isPublicSuffix(domain: String): Boolean {
        if ('.' !in domain || domain in COMMON_MULTI_LABEL_PUBLIC_SUFFIXES) return true
        val labels = domain.split('.')
        return labels.size == 2 && labels.last().length == 2 &&
            labels.first() in COMMON_COUNTRY_SECOND_LEVEL_SUFFIXES
    }

    private companion object {
        /** Maximum number of batch requests that may overlap. */
        const val MAX_REDIRECTS = 10
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val SAFE_CROSS_ORIGIN_HEADERS = setOf(
            "accept",
            "accept-language",
            "range",
            "referer",
            "user-agent",
        )
        val COMMON_MULTI_LABEL_PUBLIC_SUFFIXES = setOf(
            "ac.uk", "co.uk", "gov.uk", "ltd.uk", "me.uk", "net.uk", "org.uk", "plc.uk",
            "asn.au", "com.au", "edu.au", "gov.au", "id.au", "net.au", "org.au",
            "ac.jp", "co.jp", "go.jp", "ne.jp", "or.jp",
            "ac.kr", "co.kr", "go.kr", "ne.kr", "or.kr",
            "com.br", "com.cn", "com.hk", "com.mx", "com.sg", "com.tw",
            "co.in", "firm.in", "gen.in", "ind.in", "net.in", "org.in",
            "co.nz", "co.za", "com.ar", "com.tr", "com.ua",
        )
        val COMMON_COUNTRY_SECOND_LEVEL_SUFFIXES = setOf(
            "ac", "co", "com", "edu", "firm", "gen", "go", "gov", "id", "ind", "ltd",
            "me", "mil", "ne", "net", "nom", "or", "org", "plc",
        )
    }
}

/**
 * Reviewed dynamic content host families used by the official readers.
 *
 * These values are app-pinned implementation policy, never repository-provided wildcards. A
 * suffix is usable only by the host-created credential-free content scope, and each family has
 * its own port rule below. This lets MangaDex use its rotating At-Home nodes without granting a
 * generic extension the ability to probe arbitrary subdomains or services.
 */
internal const val REVIEWED_DYNAMIC_CONTENT_SUFFIX: String = "hath.network"
internal const val REVIEWED_MANGADEX_CONTENT_SUFFIX: String = "mangadex.network"
internal val REVIEWED_DYNAMIC_CONTENT_SUFFIXES: Set<String> = setOf(
    REVIEWED_DYNAMIC_CONTENT_SUFFIX,
    REVIEWED_MANGADEX_CONTENT_SUFFIX,
)

private fun isReviewedDynamicContentHost(url: Url, suffix: String): Boolean {
    val host = url.host.lowercase().trimEnd('.')
    if (!host.endsWith(".$suffix")) return false
    return when (suffix) {
        REVIEWED_DYNAMIC_CONTENT_SUFFIX -> isReviewedDynamicContentPort(url.port)
        // MangaDex At-Home servers are HTTPS endpoints on the standard TLS port. Keeping this
        // family at 443 prevents an otherwise valid CDN hostname from becoming a port scanner.
        REVIEWED_MANGADEX_CONTENT_SUFFIX -> url.port == 443
        else -> false
    }
}

/**
 * E-Hentai's reviewed image nodes use a public, non-standard HTTPS port selected per node.
 *
 * The host family is already app-pinned and the content scope remains credential-free, GET/HEAD
 * only, DNS-pinned, and redirect-revalidated.  Restricting the port to the IANA non-privileged
 * range prevents a compromised page from turning the suffix exception into a probe of SSH,
 * database, or other well-known privileged services while still covering the ports emitted by
 * current E-Hentai viewer pages (for example 5,891, 8,443, 44,000, and 54,200).
 */
internal fun isReviewedDynamicContentPort(port: Int): Boolean = port == 443 || port in 1_024..65_535

private class PluginBatchResponseBudget(
    private val maximumBytes: Int,
) {
    private val mutex = Mutex()
    private var consumedBytes = 0L

    suspend fun account(bytes: Int) {
        require(bytes >= 0) { "Invalid plugin batch response size" }
        mutex.withLock {
            val next = consumedBytes + bytes.toLong()
            if (next > maximumBytes.toLong()) {
                throw PluginResourceLimitException(
                    "Plugin batch responses exceeded the $maximumBytes-byte aggregate limit",
                )
            }
            consumedBytes = next
        }
    }
}

/**
 * Encodes batch bodies without first retaining a second list of decoded strings/JSON elements.
 * The byte budget is checked as characters are appended, including JSON escaping expansion.
 * [jsonStringWrapped] also budgets the outer JSON string used by the JavaScriptCore bridge RPC.
 */
internal fun encodeBoundedPluginBatchResponseBodies(
    responses: List<PluginHttpResponse>,
    maximumBytes: Int,
    jsonStringWrapped: Boolean = false,
): String {
    require(maximumBytes > 0) { "Invalid plugin batch encoding limit" }
    return BoundedPluginBatchJsonWriter(maximumBytes, jsonStringWrapped).encode(responses)
}

private class BoundedPluginBatchJsonWriter(
    private val maximumBytes: Int,
    private val jsonStringWrapped: Boolean,
) {
    private val output = StringBuilder()
    private var directBytes = 0L
    private var wrappedBytes = if (jsonStringWrapped) 2L else 0L

    fun encode(responses: List<PluginHttpResponse>): String {
        appendAscii('[')
        responses.forEachIndexed { index, response ->
            if (index > 0) appendAscii(',')
            appendAscii('"')
            appendJsonString(response.body.decodeToString())
            appendAscii('"')
        }
        appendAscii(']')
        return output.toString()
    }

    private fun appendJsonString(value: String) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when (character) {
                '"' -> appendAsciiSequence("\\\"")
                '\\' -> appendAsciiSequence("\\\\")
                '\b' -> appendAsciiSequence("\\b")
                '\u000c' -> appendAsciiSequence("\\f")
                '\n' -> appendAsciiSequence("\\n")
                '\r' -> appendAsciiSequence("\\r")
                '\t' -> appendAsciiSequence("\\t")
                else -> when {
                    character.code < 0x20 || character.isSurrogate() &&
                        !(character.isHighSurrogate() && index + 1 < value.length &&
                            value[index + 1].isLowSurrogate()) -> appendUnicodeEscape(character)
                    character.isHighSurrogate() -> {
                        appendText(character.toString() + value[index + 1], directWidth = 4)
                        index += 1
                    }
                    else -> appendText(
                        character.toString(),
                        directWidth = when {
                            character.code < 0x80 -> 1
                            character.code < 0x800 -> 2
                            else -> 3
                        },
                    )
                }
            }
            index += 1
        }
    }

    private fun appendUnicodeEscape(character: Char) {
        val hex = "0123456789abcdef"
        appendAsciiSequence(
            "\\u" +
                hex[(character.code ushr 12) and 0xf] +
                hex[(character.code ushr 8) and 0xf] +
                hex[(character.code ushr 4) and 0xf] +
                hex[character.code and 0xf],
        )
    }

    private fun appendAscii(character: Char) {
        appendText(character.toString(), directWidth = 1)
    }

    private fun appendAsciiSequence(value: String) {
        appendText(value, directWidth = value.length)
    }

    private fun appendText(value: String, directWidth: Int) {
        directBytes += directWidth.toLong()
        if (directBytes > maximumBytes.toLong()) failEncodingLimit()
        if (jsonStringWrapped) {
            // The outer bridge protocol escapes quotes and backslashes from the inner JSON array.
            wrappedBytes += directWidth.toLong() + value.count { it == '"' || it == '\\' }
            if (wrappedBytes > maximumBytes.toLong()) failEncodingLimit()
        }
        output.append(value)
    }

    private fun failEncodingLimit(): Nothing = throw PluginResourceLimitException(
        "Plugin batch response encoding exceeded the $maximumBytes-byte bridge result limit",
    )
}
