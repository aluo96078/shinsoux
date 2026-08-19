package dev.shinsou.kmp.ui.challenge

import dev.shinsou.kmp.ui.SourceCookie
import io.ktor.http.Url
import kotlin.time.Clock

/** Browser integration available on the current target. */
internal enum class PlatformWebChallengeMode {
    Embedded,
    ExternalBrowserOnly,
}

internal expect val platformWebChallengeMode: PlatformWebChallengeMode

/**
 * Parses Android WebView's Cookie header. Since that API omits cookie attributes, imported
 * cookies are deliberately narrowed to the exact origin host and current source path.
 */
internal fun parseWebViewCookieHeader(
    header: String?,
    requestUrl: String,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
): List<SourceCookie> {
    val origin = challengeOrigin(requestUrl) ?: return emptyList()
    if (header.isNullOrBlank()) return emptyList()
    val capturePath = origin.encodedPath.ifEmpty { "/" }.let { if (it.startsWith('/')) it else "/" }
    val cookies = header.split(';').mapNotNull { part ->
        val nameValue = part.trim()
        val separator = nameValue.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        SourceCookie(
            name = nameValue.substring(0, separator).trim(),
            value = nameValue.substring(separator + 1).trim(),
            domain = origin.host.lowercase(),
            path = capturePath,
            secure = origin.protocol.name == "https",
            hostOnly = true,
        )
    }
    return normalizeWebChallengeCookies(requestUrl, cookies, nowEpochMillis)
}

/**
 * Rejects expired, malformed, unrelated-domain, or unrelated-path cookies before they enter the
 * source jar. Native cookie stores already enforce these rules; this is a second trust boundary.
 */
internal fun normalizeWebChallengeCookies(
    requestUrl: String,
    cookies: List<SourceCookie>,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
): List<SourceCookie> {
    val origin = challengeOrigin(requestUrl) ?: return emptyList()
    val requestHost = origin.host.lowercase().trimEnd('.')
    val requestPath = origin.encodedPath.ifEmpty { "/" }
    val normalized = linkedMapOf<Triple<String, String, String>, SourceCookie>()
    cookies.forEach { cookie ->
        if (!isCookieToken(cookie.name) || !isSafeCookieValue(cookie.value)) return@forEach
        if (cookie.expiresAtEpochMillis?.let { it <= nowEpochMillis } == true) return@forEach
        if (cookie.secure && origin.protocol.name != "https") return@forEach

        val domain = cookie.domain.trim().trimStart('.').trimEnd('.').lowercase()
        if (domain.isEmpty()) return@forEach
        val domainMatches = if (cookie.hostOnly) {
            requestHost == domain
        } else {
            requestHost == domain || requestHost.endsWith(".$domain")
        }
        if (!domainMatches) return@forEach

        val path = cookie.path.ifBlank { "/" }
        if (!path.startsWith('/') || path.any(::isCookieControl) || !cookiePathMatches(requestPath, path)) {
            return@forEach
        }
        val safeCookie = cookie.copy(
            domain = if (cookie.hostOnly) domain else ".$domain",
            path = path,
        )
        normalized[Triple(safeCookie.domain, safeCookie.path, safeCookie.name)] = safeCookie
    }
    return normalized.values.toList()
}

internal fun cookiePathMatches(requestPath: String, cookiePath: String): Boolean =
    requestPath == cookiePath ||
        requestPath.startsWith(cookiePath) &&
        (cookiePath.endsWith('/') || requestPath.getOrNull(cookiePath.length) == '/')

/** Deterministic Set-Cookie value used when seeding an isolated platform WebView jar. */
internal fun webChallengeSetCookieValue(
    cookie: SourceCookie,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
): String = buildString {
    append(cookie.name)
    append('=')
    append(cookie.value)
    append("; Path=")
    append(cookie.path.ifBlank { "/" })
    if (!cookie.hostOnly) {
        append("; Domain=")
        append(cookie.domain)
    }
    cookie.expiresAtEpochMillis?.let { expires ->
        append("; Max-Age=")
        append(((expires - nowEpochMillis) / 1_000L).coerceAtLeast(0L))
    }
    if (cookie.secure) append("; Secure")
    if (cookie.httpOnly) append("; HttpOnly")
    append("; SameSite=Lax")
}

private fun challengeOrigin(value: String): Url? = runCatching { Url(value.trim()) }
    .getOrNull()
    ?.takeIf { it.protocol.name in setOf("http", "https") && it.host.isNotBlank() }

private fun isCookieToken(value: String): Boolean = value.isNotEmpty() && value.all { character ->
    character.isLetterOrDigit() || character in "!#$%&'*+-.^_`|~"
}

private fun isSafeCookieValue(value: String): Boolean =
    value.none { isCookieControl(it) || it == ';' }

private fun isCookieControl(value: Char): Boolean = value.code in 0..31 || value.code == 127
