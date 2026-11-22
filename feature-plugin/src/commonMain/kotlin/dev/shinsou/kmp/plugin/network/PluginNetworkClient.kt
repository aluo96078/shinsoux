package dev.shinsou.kmp.plugin.network

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.logging.Log
import io.ktor.client.request.request
import io.ktor.http.Url
import io.ktor.http.parseServerSetCookieHeader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import dev.shinsou.kmp.plugin.model.PluginResourceLimitException
import dev.shinsou.kmp.plugin.model.pluginUtf8ByteCountAtMost
import dev.shinsou.kmp.plugin.storage.MAX_COOKIE_BYTES
import dev.shinsou.kmp.plugin.storage.PermissionFilteredPluginStorage
import dev.shinsou.kmp.plugin.storage.PluginCookie
import dev.shinsou.kmp.plugin.storage.PluginStorage
import dev.shinsou.kmp.plugin.storage.canonicalCookieDomain
import dev.shinsou.kmp.plugin.storage.isCookieIpAddress
import dev.shinsou.kmp.plugin.storage.isValidCookieDomainSyntax
import dev.shinsou.kmp.plugin.storage.isValidCookieName
import dev.shinsou.kmp.plugin.storage.isValidCookieValue
import dev.shinsou.kmp.plugin.storage.normalizedPluginCookieOrNull

// R4-4 (docs/architecture/0006): moved out of PluginNetwork.kt without changing it.

private const val PLUGIN_NETWORK_MAX_SET_COOKIE_ATTRIBUTES: Int = 32

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
    private val failureBackoff: PluginSourceFailureBackoff = PluginSourceFailureBackoff(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
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
        failureBackoff = failureBackoff,
    )

    /** Binds a manifest/runtime-specific exact-origin policy while preserving this client setup. */
    /**
     * ADR-0002 §6.4: the network of a one-shot login sandbox. Only this client's credential
     * origins stay reachable (as request and credential origins); no content plane, no CDN
     * families and no browser session, so a password can only travel to where the permission page
     * said it would.
     */
    internal fun scopedToCredentialOriginsOnly(): PluginNetworkClient {
        val credentials = policy.credentialOrigins
        return scopedToPolicy(
            policy.copy(
                requestOrigins = credentials,
                allowedOrigins = emptySet(),
                credentialOrigins = credentials,
                contentOrigins = emptySet(),
                contentHostSuffixes = emptySet(),
                browserSessionOrigins = emptySet(),
            ),
        )
    }

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
        failureBackoff = failureBackoff,
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
                policy = policy.contentScope(),
                hostResolver = hostResolver,
                credentialsEnabled = false,
                explicitRefererWithoutCredentials = true,
                failureBackoff = failureBackoff,
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
                    // Content-Type describes the body this hop carries; it is not a credential.
                    // Dropping it turned form POSTs to non-credential origins into untyped
                    // bodies that servers (PHP $_POST, form APIs) silently ignore.
                    includeContentType = currentRequest.body.isNotEmpty(),
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
            failureBackoff.check(sourceId, built.originalUrl.host)
            val response = boundedPluginHttpResponse(requestGate.run(built.originalUrl.host) {
                failureBackoff.run(sourceId, built.originalUrl.host) {
                    when {
                        policy.allowDeveloperUnpinnedTransport ->
                            transport.execute(built.transportRequest)
                        else -> transport.executeResolved(built.transportRequest, transportResolution)
                    }
                }
            }, built.transportRequest.maxResponseBytes)
            // Set-Cookie is a credential-plane write. An origin that is allowed only for ordinary
            // requests must not plant a parent-domain cookie which will later be sent to a
            // credential origin (for example evil.api.example -> Domain=api.example).
            if (credentialOrigin) storeResponseCookies(sourceId, response, built.originalUrl)

            val location = response.headers.pluginHeaderValues("Location").singleOrNull()?.trim().orEmpty()
            if (response.status !in REDIRECT_STATUSES || location.isEmpty()) return response
            check(redirects < MAX_REDIRECTS) { "Too many redirects while requesting ${request.url}" }

            val nextUrl = runCatchingCancellable {
                Url(ViewerImageParser.resolveUrl(built.originalUrl.toString(), location))
            }.getOrElse { throw IllegalStateException("Invalid plugin redirect location", it) }
            policy.validate(nextUrl, hostResolver)
            check(nextUrl.protocol.name == "http" || nextUrl.protocol.name == "https") {
                "Unsupported redirect scheme: ${nextUrl.protocol.name}"
            }
            check(!(built.originalUrl.protocol.name == "https" && nextUrl.protocol.name == "http")) {
                "Refusing an HTTPS to HTTP redirect"
            }

            currentRequest = redirectedRequest(currentRequest, response.status, built.originalUrl, nextUrl, initialUrl)
            redirects++
        }
    }

    /**
     * The request that follows a redirect. A cross-origin hop drops credentials and may not carry
     * a body, and 303 (or a 301/302 after POST) turns the request into a bodiless GET.
     */
    private fun redirectedRequest(
        currentRequest: PluginHttpRequest,
        status: Int,
        from: Url,
        nextUrl: Url,
        initialUrl: Url,
    ): PluginHttpRequest {
        val crossOrigin = !sameOrigin(from, nextUrl)
        val switchToGet = status == 303 ||
            status in setOf(301, 302) && currentRequest.method.equals("POST", ignoreCase = true)
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
        return currentRequest.copy(
            method = if (switchToGet) "GET" else currentRequest.method,
            url = nextUrl.toString(),
            body = if (switchToGet) ByteArray(0) else currentRequest.body,
            headers = redirectedHeaders,
        )
    }

    /**
     * `Connection` is hop-by-hop and owned by the transport. Older scripts set it (`keep-alive`);
     * it is dropped for every package rather than failing the request (ADR-0002 A7: no ability is
     * tier-specific). Every other transport-owned header still fails the request.
     */
    private fun reviewedCompatibleHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { !it.equals("Connection", ignoreCase = true) }

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
                    storeCookieWithinQuota(sourceId, cookie)
                }
            }
    }

    /**
     * A full cookie jar must not fail a request whose response already arrived. Expired cookies
     * are only removed when the site replaces them, so a site that keeps issuing new names fills
     * the jar: drop the expired ones and try once more, then give up on this cookie only.
     */
    private suspend fun storeCookieWithinQuota(sourceId: Long, cookie: PluginCookie) {
        try {
            storage.setCookie(sourceId, cookie)
            return
        } catch (_: IllegalArgumentException) {
            // Quota (or an invalid cookie): fall through.
        }
        storage.deleteExpiredCookies(sourceId, nowEpochMillis())
        try {
            storage.setCookie(sourceId, cookie)
        } catch (error: IllegalArgumentException) {
            // Still over quota with live cookies only: the request itself succeeded.
            Log.warn("Plugin", error) {
                "Failed to store a response cookie; continuing without it"
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
                    async(defaultDispatcher) {
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
        includeContentType: Boolean = false,
    ): Map<String, String> = headers.filterKeys {
        (it.lowercase() in SAFE_CROSS_ORIGIN_HEADERS ||
            includeImageTicket && it.equals("x-image-ticket", ignoreCase = true) ||
            includeContentType && it.equals("content-type", ignoreCase = true)) &&
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

    private fun isPublicSuffix(domain: String): Boolean = isPluginCookiePublicSuffix(domain)

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
    }
}

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
