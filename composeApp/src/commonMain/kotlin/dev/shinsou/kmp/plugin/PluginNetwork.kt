package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlin.time.Clock

internal const val PLUGIN_NETWORK_MAX_BATCH_REQUESTS: Int = 32

public data class PluginHttpRequest(
    val method: String,
    val url: String,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
)

public data class PluginHttpResponse(
    val status: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    public fun bodyText(): String = body.decodeToString()
}

public fun interface PluginHttpTransport {
    public suspend fun execute(request: PluginHttpRequest): PluginHttpResponse
}

/** Optional resolver for transports that can preserve TLS SNI and the original hostname. */
public fun interface PluginHostResolver {
    public suspend fun resolve(host: String): List<String>
}

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

    public companion object {
        public fun fromStored(value: String?, default: SourceNetworkOverride): SourceNetworkOverride =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: default
    }
}

/** Uses the configured UA when present and otherwise retains per-host sticky rotation. */
public class ConfiguredPluginUserAgentProvider(
    private val configuration: PluginNetworkConfigurationProvider,
    private val fallback: PluginUserAgentProvider = StickyPluginUserAgentProvider(),
) : PluginUserAgentProvider {
    override suspend fun userAgent(host: String): String =
        configuration.current().customUserAgent.trim().takeIf(String::isNotEmpty)
            ?: fallback.userAgent(host)
}

/**
 * Cloudflare Worker routing with Shinsou's source opt-in semantics.
 *
 * An unset or invalid source override is deliberately [SourceNetworkOverride.OFF]. A source only
 * follows the global switch after the user selects `global` in that source's settings.
 */
public class ConfiguredPluginProxyResolver(
    private val storage: PluginStorage,
    private val configuration: PluginNetworkConfigurationProvider,
) : PluginProxyResolver {
    override suspend fun route(sourceId: Long, targetUrl: String): PluginProxyRoute? {
        val current = configuration.current()
        val sourceOverride = SourceNetworkOverride.fromStored(
            storage.getPreference(sourceId, SOURCE_PROXY_PREFERENCE),
            default = SourceNetworkOverride.OFF,
        )
        val enabled = when (sourceOverride) {
            SourceNetworkOverride.GLOBAL -> current.proxyEnabled
            SourceNetworkOverride.ON -> true
            SourceNetworkOverride.OFF -> false
        }
        if (!enabled) return null

        val proxyUrl = buildProxyUrl(current.proxyWorkerUrl, targetUrl) ?: return null
        val proxyHeaders = current.proxyApiKey.takeIf(String::isNotBlank)
            ?.let { mapOf(PROXY_KEY_HEADER to it) }
            .orEmpty()
        return PluginProxyRoute(proxyUrl, proxyHeaders)
    }

    private fun buildProxyUrl(workerUrl: String, targetUrl: String): String? = runCatching {
        val normalized = workerUrl.trim().trimEnd('/')
        if (normalized.isEmpty()) return null
        URLBuilder(normalized).apply {
            require(protocol.name == "http" || protocol.name == "https")
            require(host.isNotBlank())
            pathSegments = pathSegments.dropLastWhile(String::isEmpty) + ""
            fragment = ""
            parameters.append("url", targetUrl)
        }.buildString()
    }.getOrNull()

    public companion object {
        public const val SOURCE_PROXY_PREFERENCE: String = "network.proxy"
        public const val PROXY_KEY_HEADER: String = "X-Proxy-Key"
    }
}

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
) {
    private data class HostState(val mutex: Mutex = Mutex(), var nextRequestAt: Long = 0L)

    private val stateMutex = Mutex()
    private val states = mutableMapOf<String, HostState>()

    public suspend fun <T> run(host: String, block: suspend () -> T): T {
        val key = host.lowercase()
        val state = stateMutex.withLock { states.getOrPut(key, ::HostState) }
        state.mutex.withLock {
            val rate = limits.limit(key)
            val wait = state.nextRequestAt - nowEpochMillis()
            if (wait > 0) delayMillis(wait)
            val spacing = if (rate.periodMillis == 0L) 0L else rate.periodMillis / rate.permits
            state.nextRequestAt = nowEpochMillis() + spacing
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
    ): BuiltPluginRequest {
        val target = Url(request.url)
        val headers = linkedMapOf<String, String>()
        headers.putAll(sourceHeaders)
        request.headers.forEach { (name, value) -> putHeader(headers, name, value) }
        val browserBoundUserAgent = storage.getWebChallengeUserAgent(sourceId)
            ?.let(::normalizePluginUserAgent)
        if (browserBoundUserAgent != null) {
            // Cloudflare binds clearance to the browser's real UA. Once a verified browser
            // session is imported, it must take priority over plugin and request header hints.
            putHeader(headers, "User-Agent", browserBoundUserAgent)
        } else if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = userAgents.userAgent(target.host)
        }
        if (!referer.isNullOrBlank() && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            headers["Referer"] = referer
        }

        val matching = storage.getCookies(sourceId)
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

        val proxy = proxyResolver.route(sourceId, request.url)
        proxy?.headers?.forEach { (name, value) -> putHeader(headers, name, value) }
        return BuiltPluginRequest(
            originalUrl = target,
            transportRequest = request.copy(url = proxy?.url ?: request.url, headers = headers),
        )
    }

    private fun putHeader(headers: MutableMap<String, String>, name: String, value: String) {
        headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(headers::remove)
        headers[name] = value
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
        return PluginHttpResponse(
            status = response.status.value,
            body = response.body<ByteArray>(),
            headers = response.headers.entries().associate { it.key to it.value },
        )
    }
}

/** Adds source-isolated cookies around a raw transport. */
public class PluginNetworkClient(
    private val transport: PluginHttpTransport,
    private val storage: PluginStorage,
    private val requestBuilder: PluginRequestBuilder = PluginRequestBuilder(storage),
    private val requestGate: PerHostRequestGate = PerHostRequestGate(),
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** Shares transport/rate-limit policy but applies a capability-filtered storage view. */
    public fun scopedToStorage(storage: PluginStorage): PluginNetworkClient = PluginNetworkClient(
        transport = transport,
        storage = storage,
        requestBuilder = requestBuilder.scopedToStorage(storage),
        requestGate = requestGate,
        nowEpochMillis = nowEpochMillis,
    )

    public suspend fun execute(
        sourceId: Long,
        request: PluginHttpRequest,
        sourceHeaders: Map<String, String> = emptyMap(),
        referer: String? = null,
    ): PluginHttpResponse {
        var currentRequest = request
        val initialUrl = Url(request.url)
        var redirects = 0
        while (true) {
            val target = Url(currentRequest.url)
            val sameInitialOrigin = sameOrigin(initialUrl, target)
            val hopSourceHeaders = if (sameInitialOrigin) sourceHeaders else safeCrossOriginHeaders(sourceHeaders)
            val built = requestBuilder.build(sourceId, currentRequest, hopSourceHeaders, referer)
            val response = requestGate.run(built.originalUrl.host) {
                transport.execute(built.transportRequest)
            }
            storeResponseCookies(sourceId, response, built.originalUrl)

            val location = response.headerValues("Location").firstOrNull()?.trim().orEmpty()
            if (response.status !in REDIRECT_STATUSES || location.isEmpty()) return response
            check(redirects < MAX_REDIRECTS) { "Too many redirects while requesting ${request.url}" }

            val nextUrl = runCatching {
                Url(ViewerImageParser.resolveUrl(built.originalUrl.toString(), location))
            }.getOrElse { throw IllegalStateException("Invalid redirect location: $location", it) }
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
                safeCrossOriginHeaders(currentRequest.headers)
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

    private suspend fun storeResponseCookies(
        sourceId: Long,
        response: PluginHttpResponse,
        origin: Url,
    ) {
        response.headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
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
     * ordered chunks so a plugin cannot accidentally create an unbounded request fan-out.
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
        if (urls.isEmpty()) return emptyList()

        return coroutineScope {
            urls.indices.chunked(PLUGIN_NETWORK_MAX_BATCH_REQUESTS).flatMap { chunk ->
                chunk.map { index ->
                    // A runtime invokes this method synchronously on its JavaScript worker. Move
                    // each transport operation off that worker so the batch can overlap network
                    // waits without making the JS engine itself re-entrant.
                    async(Dispatchers.Default) {
                        post(
                            sourceId = sourceId,
                            url = urls[index],
                            body = bodies[index],
                            headers = headers,
                            sourceHeaders = sourceHeaders,
                            referer = referer,
                        )
                    }
                }.awaitAll()
            }
        }
    }

    private fun parseSetCookie(header: String, requestUrl: Url): PluginCookie? {
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

    private fun PluginHttpResponse.headerValues(name: String): List<String> =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

    private fun safeCrossOriginHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { it.lowercase() in SAFE_CROSS_ORIGIN_HEADERS }

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
