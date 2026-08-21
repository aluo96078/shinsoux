package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.plugin.ViewerImageParser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * Bounded, redirect-audited production transport for reviewed ShuYue artifacts.
 *
 * The supplied client must have automatic redirects disabled so every target can be checked
 * against the loader-issued origin capability before network I/O.
 */
public class KtorShuYueRepositoryTransport(
    private val client: HttpClient,
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
            val response = client.get(currentUrl)
            val location = response.headers[HttpHeaders.Location]?.trim().orEmpty()
            if (response.status.value in REDIRECT_STATUS_CODES && location.isNotEmpty()) {
                response.bodyAsChannel().cancel(null)
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

            val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > request.maxBytes) {
                response.bodyAsChannel().cancel(null)
                throw ShuYueRepositoryException.BodyTooLarge(
                    currentUrl,
                    declaredLength,
                    request.maxBytes,
                )
            }
            val bounded = response.bodyAsChannel().readRemaining(request.maxBytes + 1).readByteArray()
            if (bounded.size.toLong() > request.maxBytes) {
                throw ShuYueRepositoryException.BodyTooLarge(
                    currentUrl,
                    bounded.size.toLong(),
                    request.maxBytes,
                )
            }
            return ShuYueRepositoryResponse(
                status = response.status.value,
                body = bounded,
                finalUrl = currentUrl,
                redirectChain = redirects.toList(),
            )
        }
    }

    private companion object {
        val REDIRECT_STATUS_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)
    }
}
