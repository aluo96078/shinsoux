package dev.shinsou.kmp.tracking

import io.ktor.http.decodeURLQueryComponent

/** Parses AniList's implicit-flow fragment, or an explicitly pasted access token. */
object AniListOAuthCallbackParser {
    fun parse(input: String, nowEpochMillis: Long): OAuthToken {
        val value = input.trim()
        if (value.isEmpty()) throw TrackerAuthenticationException("Paste the AniList callback URL or access token")

        val parameterPayload = callbackParameters(value)
        if (parameterPayload == null) {
            if (looksLikeUrl(value)) {
                throw TrackerAuthenticationException("The AniList callback URL does not contain an access_token fragment")
            }
            return OAuthToken(accessToken = value)
        }

        val parameters = parameterPayload
            .split('&')
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = pair.substring(0, separator).decodeURLQueryComponent(plusIsSpace = true)
                val decodedValue = pair.substring(separator + 1).decodeURLQueryComponent(plusIsSpace = true)
                key to decodedValue
            }
            .toMap()

        parameters["error"]?.let { error ->
            val description = parameters["error_description"]?.takeIf(String::isNotBlank) ?: error
            throw TrackerAuthenticationException("AniList authorization failed: $description")
        }

        val accessToken = parameters["access_token"]?.trim().orEmpty()
        if (accessToken.isEmpty()) {
            throw TrackerAuthenticationException("The AniList callback does not contain an access token")
        }

        val expiresAt = parameters["expires_in"]
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { seconds -> saturatingExpiry(nowEpochMillis, seconds) }
        val scopes = parameters["scope"]
            ?.split(' ', ',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()

        return OAuthToken(
            accessToken = accessToken,
            tokenType = parameters["token_type"]?.takeIf(String::isNotBlank) ?: "Bearer",
            expiresAt = expiresAt,
            scopes = scopes,
        )
    }

    private fun callbackParameters(value: String): String? {
        val fragmentIndex = value.indexOf('#')
        if (fragmentIndex >= 0) return value.substring(fragmentIndex + 1)

        val queryIndex = value.indexOf('?')
        if (queryIndex >= 0) {
            val query = value.substring(queryIndex + 1)
            if (query.contains("access_token=") || query.contains("error=")) return query
        }

        val trimmed = value.trimStart('#', '?')
        return trimmed.takeIf { it.contains("access_token=") || it.contains("error=") }
    }

    private fun looksLikeUrl(value: String): Boolean =
        "://" in value || value.startsWith("shinsou:", ignoreCase = true) ||
            value.startsWith("anilist:", ignoreCase = true)

    private fun saturatingExpiry(nowEpochMillis: Long, expiresInSeconds: Long): Long {
        val remaining = (Long.MAX_VALUE - nowEpochMillis.coerceAtLeast(0L)) / 1_000L
        return nowEpochMillis.coerceAtLeast(0L) + expiresInSeconds.coerceAtMost(remaining) * 1_000L
    }
}
