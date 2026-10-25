package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.plugin.PluginHostResolver
import dev.shinsou.kmp.plugin.PluginHttpRequest
import dev.shinsou.kmp.plugin.PluginHttpTransport
import dev.shinsou.kmp.plugin.PluginNetworkPolicy
import dev.shinsou.kmp.plugin.PLUGIN_NETWORK_MAX_REDIRECT_LOCATION_BYTES
import dev.shinsou.kmp.plugin.boundedPluginResponseHeaders
import dev.shinsou.kmp.plugin.pluginHeaderValues
import dev.shinsou.kmp.plugin.pluginDeclaredContentLength
import dev.shinsou.kmp.plugin.pluginContentEncoding
import dev.shinsou.kmp.plugin.pluginUtf8ByteCountAtMost
import dev.shinsou.kmp.plugin.readBoundedPluginResponseBody
import dev.shinsou.kmp.plugin.ViewerImageParser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url

/**
 * Bounded, redirect-audited production transport for reviewed ShuYue artifacts.
 *
 * The supplied client must have automatic redirects disabled so every target can be checked
 * against the loader-issued origin capability before network I/O.
 */
public class KtorShuYueRepositoryTransport(
    private val client: HttpClient,
    private val hostResolver: PluginHostResolver = PluginHostResolver.Unavailable,
    private val pinnedTransport: PluginHttpTransport? = null,
    /** Explicit local/LAN development escape hatch; never applies to a public origin. */
    private val allowLocalDeveloperTransport: Boolean = false,
    private val reviewedLocalRepositoryPolicy: dev.shinsou.kmp.plugin.ReviewedLocalRepositoryPolicy =
        dev.shinsou.kmp.plugin.ReviewedLocalRepositoryPolicy.DISABLED,
    private val reviewedLocalRepositoryTransport: PluginHttpTransport? = null,
) : ShuYueRepositoryTransport {
    override suspend fun execute(request: ShuYueRepositoryRequest): ShuYueRepositoryResponse {
        require(request.maxBytes in 1..Int.MAX_VALUE.toLong()) {
            "ShuYue transport byte limit is not addressable"
        }
        var currentUrl = request.url
        val redirects = mutableListOf<String>()
        while (true) {
            val current = ShuYueUrlParser.parseAbsolute(currentUrl, "artifact URL")
            if (current.origin !in request.allowedArtifactOrigins) {
                throw ShuYueRepositoryException.OriginNotAllowed(current.origin.value)
            }
            val response = requestHop(currentUrl, current.origin, request.maxBytes)
            if (pluginUtf8ByteCountAtMost(response.location, PLUGIN_NETWORK_MAX_REDIRECT_LOCATION_BYTES) == null) {
                throw ShuYueRepositoryException.LimitExceeded(
                    "artifact redirect location",
                    (PLUGIN_NETWORK_MAX_REDIRECT_LOCATION_BYTES + 1).toLong(),
                    PLUGIN_NETWORK_MAX_REDIRECT_LOCATION_BYTES.toLong(),
                )
            }
            val location = response.location.trim()
            if (response.status in REDIRECT_STATUS_CODES && location.isNotEmpty()) {
                if (redirects.size >= request.maxRedirects) {
                    throw ShuYueRepositoryException.LimitExceeded(
                        "artifact redirects",
                        (redirects.size + 1).toLong(),
                        request.maxRedirects.toLong(),
                    )
                }
                val nextUrl = runCatching { ViewerImageParser.resolveUrl(currentUrl, location) }
                    .getOrElse {
                        throw ShuYueRepositoryException.InvalidUrl(location, "Invalid artifact redirect")
                    }
                val next = ShuYueUrlParser.parseAbsolute(nextUrl, "artifact redirect")
                if (next.origin !in request.allowedArtifactOrigins) {
                    throw ShuYueRepositoryException.OriginNotAllowed(next.origin.value)
                }
                if (current.origin.scheme == "https" && next.origin.scheme != "https") {
                    throw ShuYueRepositoryException.InvalidUrl(nextUrl, "Artifact redirect downgrades HTTPS")
                }
                if (nextUrl == request.url || nextUrl in redirects) {
                    throw ShuYueRepositoryException.InvalidMetadata("redirectChain", "artifact redirect loop")
                }
                redirects += nextUrl
                currentUrl = nextUrl
                continue
            }

            if (response.body.size.toLong() > request.maxBytes) {
                throw ShuYueRepositoryException.BodyTooLarge(
                    currentUrl,
                    response.body.size.toLong(),
                    request.maxBytes,
                )
            }
            return ShuYueRepositoryResponse(
                status = response.status,
                body = response.body,
                finalUrl = currentUrl,
                redirectChain = redirects.toList(),
            )
        }
    }

    private suspend fun requestHop(url: String, origin: ShuYueOrigin, maxBytes: Long): HopResponse {
        if (reviewedLocalRepositoryPolicy.admits(origin.value)) {
            require(reviewedLocalRepositoryPolicy.admitsRequestUrl(url)) {
                "Request is outside the reviewed local repository routes"
            }
            val transport = reviewedLocalRepositoryTransport
                ?: throw ShuYueRepositoryException.PinnedTransportUnavailable()
            val response = transport.execute(PluginHttpRequest("GET", url, maxResponseBytes = maxBytes.toInt()))
            val headers = boundedPluginResponseHeaders(response.headers.entries)
            require(response.status !in 300..399 && headers.pluginHeaderValues(HttpHeaders.Location).isEmpty()) {
                "Reviewed local repository redirects are forbidden"
            }
            headers.pluginContentEncoding()
            val declaredLength = headers.pluginDeclaredContentLength()
            if (response.body.size.toLong() > maxBytes || declaredLength != null && declaredLength > maxBytes) {
                throw ShuYueRepositoryException.BodyTooLarge(url, declaredLength ?: response.body.size.toLong(), maxBytes)
            }
            return HopResponse(response.status, "", response.body)
        }
        if (allowLocalDeveloperTransport && origin.isLocalNetworkOriginForTransport()) {
            val response = client.get(url)
            val channel = response.bodyAsChannel()
            try {
                val responseHeaders = boundedPluginResponseHeaders(response.headers.entries())
                responseHeaders.pluginContentEncoding()
                val declaredLength = responseHeaders.pluginDeclaredContentLength()
                if (declaredLength != null && declaredLength > maxBytes) {
                    throw ShuYueRepositoryException.BodyTooLarge(url, declaredLength, maxBytes)
                }
                val body = try {
                    readBoundedPluginResponseBody(channel, maxBytes.toInt())
                } catch (error: IllegalArgumentException) {
                    throw ShuYueRepositoryException.BodyTooLarge(url, maxBytes + 1, maxBytes)
                }
                return HopResponse(
                    response.status.value,
                    responseHeaders.pluginHeaderValues(HttpHeaders.Location).singleOrNull().orEmpty(),
                    body,
                )
            } catch (error: Throwable) {
                channel.cancel(null)
                throw error
            }
        }

        if (origin.scheme != "https") {
            throw ShuYueRepositoryException.InvalidUrl(url, "Production artifacts require HTTPS")
        }
        val transport = pinnedTransport ?: throw ShuYueRepositoryException.PinnedTransportUnavailable()
        val transportMaxBytes = maxBytes.coerceAtMost(16L * 1024 * 1024).toInt()
        val resolution = PluginNetworkPolicy(
            requestOrigins = setOf(origin.value),
            resolver = hostResolver,
            maxResponseBytes = transportMaxBytes,
        ).authorize(Url(url), hostResolver)
        val response = transport.executeResolved(
            PluginHttpRequest("GET", url, maxResponseBytes = transportMaxBytes),
            resolution,
        )
        val responseHeaders = boundedPluginResponseHeaders(response.headers.entries)
        return HopResponse(
            response.status,
            responseHeaders.pluginHeaderValues(HttpHeaders.Location).singleOrNull().orEmpty(),
            response.body,
        )
    }

    private data class HopResponse(val status: Int, val location: String, val body: ByteArray)

    private companion object {
        val REDIRECT_STATUS_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)
    }
}

private fun ShuYueOrigin.isLocalNetworkOriginForTransport(): Boolean {
    val host = authority.host.lowercase()
    if (host == "localhost" || host.endsWith(".local")) return true
    if (authority.isIpv6) {
        return host == "::1" || host.startsWith("fc") || host.startsWith("fd") ||
            host.startsWith("fe8") || host.startsWith("fe9") ||
            host.startsWith("fea") || host.startsWith("feb")
    }
    val octets = host.split('.').map(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
    val first = octets[0]!!
    val second = octets[1]!!
    return first == 0 || first == 10 || first == 127 || first == 169 && second == 254 ||
        first == 172 && second in 16..31 || first == 192 && second == 168
}
