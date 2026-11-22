package dev.shinsou.kmp.plugin.network

import dev.shinsou.kmp.logging.QuotesExternalText
import dev.shinsou.kmp.network.isBlockedNetworkAddress
import dev.shinsou.kmp.network.isCanonicalIpLiteral
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.utils.io.readAvailable
import kotlin.time.Clock
import dev.shinsou.kmp.plugin.model.PluginResourceLimitException
import dev.shinsou.kmp.plugin.model.pluginUtf8ByteCountAtMost
import dev.shinsou.kmp.plugin.runtime.stableSourceId
import dev.shinsou.kmp.plugin.storage.PluginCookie
import dev.shinsou.kmp.plugin.storage.PluginStorage
import dev.shinsou.kmp.plugin.storage.isValidCookieName

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

internal fun isPluginResponseHeaderNameCharacter(character: Char): Boolean =
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

public fun interface PluginRateLimitProvider {
    public fun limit(host: String): PluginRateLimit

    public companion object {
        public val Default: PluginRateLimitProvider = PluginRateLimitProvider { PluginRateLimit() }
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

/**
 * A content request reached a host family its package declared but the user has not granted
 * (plugin permissions → "Download from every site under …"). Still an [IllegalArgumentException],
 * as every other denied origin is. The host names a site the user browsed, so a log keeps only
 * [loggableSummary]; the permission prompt reads [hostSuffix].
 */
internal class PluginContentHostNotGrantedException(
    public val hostSuffix: String,
) : IllegalArgumentException("Content host family $hostSuffix is declared but not granted"), QuotesExternalText {
    override val loggableSummary: String get() = "PluginContentHostNotGranted"
}

/** The [PluginContentHostNotGrantedException] behind this failure, if any. */
public fun Throwable.ungrantedContentHostSuffix(): String? =
    generateSequence(this) { it.cause }
        .filterIsInstance<PluginContentHostNotGrantedException>()
        .firstOrNull()
        ?.hostSuffix

/** Exact-origin egress policy for one plugin runtime/source. Empty origins deny all requests. */
public data class PluginNetworkPolicy(
    val requestOrigins: Set<String> = emptySet(),
    /** Legacy spelling retained for callers being migrated to [requestOrigins]. */
    val allowedOrigins: Set<String> = emptySet(),
    val credentialOrigins: Set<String> = emptySet(),
    /** Content/image origins are metadata only and never authorize active requests. */
    val contentOrigins: Set<String> = emptySet(),
    /**
     * Dynamic content host families (ADR-0002 §6.2): suffixes a package declared and the user
     * granted. Only the credential-free content scope uses them
     * (GET/HEAD, no cookies), never the executable bridge's request origins.
     */
    internal val contentHostSuffixes: Set<String> = emptySet(),
    /**
     * Families the package declared but the user has not granted. They authorize nothing; a content
     * request into one fails with [PluginContentHostNotGrantedException] so the reader can say
     * which permission to switch on instead of a bare load failure.
     */
    internal val ungrantedContentHostSuffixes: Set<String> = emptySet(),
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
        require(contentHostSuffixes.size <= MAX_CONTENT_HOST_SUFFIXES) {
            "Too many content host suffixes"
        }
        require(contentHostSuffixes.all { isGrantableContentHostSuffix(it) }) {
            "Unsupported content host suffix"
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
        val allowed = origin in effectiveRequestOrigins() ||
            contentOnlyScope && credentialOrigins.isEmpty() &&
            contentHostSuffixes.any { suffix ->
                isContentHostSuffixMatch(url, suffix)
            }
        if (!allowed && contentOnlyScope) {
            ungrantedContentHostSuffixes.firstOrNull { isContentHostSuffixMatch(url, it) }
                ?.let { throw PluginContentHostNotGrantedException(it) }
        }
        require(allowed) {
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

    /**
     * The credential-free content scope of this policy: [contentOrigins] become its only exact
     * origins, plus the [contentHostSuffixes] families; no credentials, cookies or browser session.
     */
    internal fun contentScope(): PluginNetworkPolicy = PluginNetworkPolicy(
        requestOrigins = contentOrigins,
        credentialOrigins = emptySet(),
        contentOrigins = emptySet(),
        contentHostSuffixes = contentHostSuffixes,
        ungrantedContentHostSuffixes = ungrantedContentHostSuffixes,
        contentOnlyScope = true,
        browserSessionOrigins = emptySet(),
        resolver = resolver,
        allowDeveloperLocalNetwork = allowDeveloperLocalNetwork,
        allowDeveloperUnpinnedTransport = allowDeveloperUnpinnedTransport,
        maxResponseBytes = maxContentResponseBytes,
    )

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
    require(!isBlockedNetworkAddress(host)) { "Plugin network destination is local/private" }
    val answers = resolver.resolve(host)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    require(answers.isNotEmpty()) { "Plugin network host did not resolve" }
    require(answers.size <= PLUGIN_NETWORK_MAX_DNS_ANSWERS) {
        "Plugin network host returned too many DNS answers"
    }
    answers.forEach { answer ->
        require(isCanonicalIpLiteral(answer)) { "Plugin DNS resolver returned a non-address answer" }
        require(!isBlockedNetworkAddress(answer)) { "Plugin network DNS answer is local/private" }
    }
    return PluginHostResolution(host, answers)
}

/** Alias retained for callers that use the more explicit security terminology. */
internal typealias PluginEgressPolicy = PluginNetworkPolicy

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

public data class PluginProxyRoute(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/** Runtime network settings, read on every request so changes do not require restarting a source. */
public data class PluginNetworkConfiguration(
    val proxyEnabled: Boolean = false,
    val proxyWorkerUrl: String = "",
    val proxyApiKey: String = "",
    val customUserAgent: String = "",
)

internal enum class SourceNetworkOverride {
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
public fun interface PluginProxyResolver {
    public suspend fun route(sourceId: Long, targetUrl: String): PluginProxyRoute?

    public companion object {
        public val None: PluginProxyResolver = PluginProxyResolver { _, _ -> null }
    }
}
public fun interface PluginUserAgentProvider {
    public suspend fun userAgent(host: String): String
}
public fun interface PluginNetworkConfigurationProvider {
    public fun current(): PluginNetworkConfiguration
}

/** Uses the configured UA when present and otherwise mirrors this platform's browser runtime. */
internal class ConfiguredPluginUserAgentProvider(
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
internal fun buildPluginProxyUrl(workerUrl: String, targetUrl: String): String? = runCatching {
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
        // Bounded so every schedule computed from epoch milliseconds (spacing, burst tolerance,
        // theoretical arrival time) fits in a Long instead of wrapping and disabling the limit.
        require(periodMillis in 0..MAX_PERIOD_MILLIS) { "Plugin rate-limit period must be between 0 and one day" }
    }

    public companion object {
        public const val MAX_PERIOD_MILLIS: Long = 24L * 60 * 60 * 1_000
    }
}

/**
 * Rate-limits request starts per host (a burst of up to `permits`, then `permits` per period)
 * without holding the reservation lock during network transport.
 */
public class PerHostRequestGate(
    private val limits: PluginRateLimitProvider = PluginRateLimitProvider.Default,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val maxHostStates: Int = PLUGIN_NETWORK_MAX_HOST_STATES,
) {
    private data class HostState(
        val mutex: Mutex = Mutex(),
        var nextRequestAt: Long = 0L,
        /** Never-decreasing start time, so a delay that is not reflected by the clock still counts. */
        var lastStartAt: Long = 0L,
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
                // Round up (without overflow) so a period shorter than its permit count still
                // spaces starts by at least 1 ms instead of silently disabling the limit.
                val spacing = rate.periodMillis / rate.permits +
                    if (rate.periodMillis % rate.permits == 0L) 0L else 1L
                // GCRA: nextRequestAt is the theoretical arrival time. After an idle period up to
                // `permits` requests may start together (e.g. the covers of one visible grid),
                // while the sustained start rate still never exceeds permits per period.
                val burstTolerance = spacing * (rate.permits - 1)
                val now = maxOf(nowEpochMillis(), state.lastStartAt)
                val wait = state.nextRequestAt - burstTolerance - now
                if (wait > 0) delayMillis(wait)
                val startedAt = maxOf(nowEpochMillis(), now + maxOf(wait, 0L))
                state.lastStartAt = startedAt
                state.nextRequestAt = maxOf(state.nextRequestAt, startedAt) + spacing
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

internal class KtorPluginHttpTransport(private val client: HttpClient) : PluginHttpTransport {
    @Suppress("SuspendFunSwallowedCancellation") // Rethrows unchanged after cancelling the response body channel.
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

/**
 * Public-suffix guard shared by the response cookie parser and the sandbox bridge: a cookie must
 * never be scoped to a whole registry such as `com`, `co.uk` or `github.io`. Uses the bundled
 * Public Suffix List (ICANN and private sections).
 */
internal fun isPluginCookiePublicSuffix(domain: String): Boolean =
    dev.shinsou.kmp.plugin.network.PublicSuffixList.isPublicSuffix(domain)

/**
 * A `contentHostSuffixes` entry a package may declare (ADR-0002 §6.2, D11): a lowercase ASCII host
 * name at or below a registrable domain (`cdn.example.com`, `example.com`), never a public suffix
 * (`com`, `co.jp`, `github.io`) and never an IP address. It only ever widens the credential-free
 * content plane, on port 443.
 */
internal fun isGrantableContentHostSuffix(declared: String): Boolean {
    val suffix = declared.removeSuffix(CONTENT_HOST_SUFFIX_HIGH_PORTS)
    if (suffix != suffix.lowercase() || suffix.length > 253 || suffix.startsWith('.') || suffix.endsWith('.')) return false
    if (suffix.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.') }) return false
    val labels = suffix.split('.')
    if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return false
    if (labels.last().all(Char::isDigit)) return false // an IPv4 address, not a host name
    return dev.shinsou.kmp.plugin.network.PublicSuffixList.registrableDomain(suffix) != null
}

/**
 * Appended to a declared suffix (`hath.network:1024-65535`), the family may also use any
 * non-privileged port, like E-Hentai's image nodes. Never a privileged port other than 443.
 */
internal const val CONTENT_HOST_SUFFIX_HIGH_PORTS: String = ":1024-65535"

/** Most `contentHostSuffixes` one source may declare. */
internal const val MAX_CONTENT_HOST_SUFFIXES: Int = 8

/**
 * Whether [url] falls under a package's granted content-host [suffix]: the host itself or any
 * subdomain, on 443 unless the grant also spelled the non-privileged port range.
 */
private fun isContentHostSuffixMatch(url: Url, suffix: String): Boolean {
    val host = url.host.lowercase().trimEnd('.')
    val base = suffix.removeSuffix(CONTENT_HOST_SUFFIX_HIGH_PORTS)
    val portAllowed = if (base != suffix) isDynamicContentPort(url.port) else url.port == 443
    return isGrantableContentHostSuffix(suffix) && (host == base || host.endsWith(".$base")) && portAllowed
}

/**
 * Some image nodes (E-Hentai's) use a public, non-standard HTTPS port selected per node.
 *
 * The host family is a user-granted suffix and the content scope remains credential-free, GET/HEAD
 * only, DNS-pinned, and redirect-revalidated.  Restricting the port to the IANA non-privileged
 * range prevents a compromised page from turning the suffix exception into a probe of SSH,
 * database, or other well-known privileged services while still covering the ports emitted by
 * current E-Hentai viewer pages (for example 5,891, 8,443, 44,000, and 54,200).
 */
internal fun isDynamicContentPort(port: Int): Boolean = port == 443 || port in 1_024..65_535

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
