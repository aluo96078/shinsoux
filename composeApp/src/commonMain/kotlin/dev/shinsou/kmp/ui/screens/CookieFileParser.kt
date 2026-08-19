package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.ui.SourceCookie
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Clock

internal const val MAX_COOKIE_FILE_BYTES: Int = 1_048_576

/** Parses the browser-export formats supported by the original mobile application. */
internal object CookieFileParser {
    private const val HTTP_ONLY_PREFIX = "#HttpOnly_"
    private const val MAX_IMPORTED_COOKIES = 500

    fun parse(
        content: String,
        sourceBaseUrl: String,
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): List<SourceCookie> {
        val origin = runCatching { Url(sourceBaseUrl.trim()) }.getOrNull()
            ?.takeIf { it.protocol.name in setOf("http", "https") && it.host.isNotBlank() }
            ?: return emptyList()
        val trimmed = content.trim()
        if (trimmed.isEmpty() || content.length > MAX_COOKIE_FILE_BYTES) return emptyList()

        val candidates = if (trimmed.startsWith('[') || trimmed.startsWith('{')) {
            parseJson(trimmed, origin.host)
        } else {
            parseNetscape(trimmed)
        }
        val normalized = linkedMapOf<Triple<String, String, String>, SourceCookie>()
        candidates.forEach { candidate ->
            val cookie = normalize(candidate, origin.host, nowEpochMillis) ?: return@forEach
            normalized[Triple(cookie.domain, cookie.path, cookie.name)] = cookie
        }
        return normalized.values.take(MAX_IMPORTED_COOKIES)
    }

    private fun parseNetscape(content: String): List<SourceCookie> = buildList {
        content.lineSequence().forEach { rawLine ->
            var line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val httpOnly = line.startsWith(HTTP_ONLY_PREFIX)
            if (httpOnly) line = line.removePrefix(HTTP_ONLY_PREFIX)
            else if (line.startsWith('#')) return@forEach

            val fields = line.split('\t')
            if (fields.size < 7) return@forEach
            val domain = fields[0].trim()
            val includeSubdomains = fields[1].trim().equals("TRUE", ignoreCase = true)
            val path = fields[2].trim().ifBlank { "/" }
            val secure = fields[3].trim().equals("TRUE", ignoreCase = true)
            val expiresAt = fields[4].trim().toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.secondsToEpochMillis()
            val name = fields[5].trim()
            val value = fields.drop(6).joinToString("\t").trim()
            add(
                SourceCookie(
                    name = name,
                    value = value,
                    domain = domain,
                    path = path,
                    expiresAtEpochMillis = expiresAt,
                    secure = secure,
                    httpOnly = httpOnly,
                    hostOnly = !includeSubdomains,
                ),
            )
        }
    }

    private fun parseJson(content: String, originHost: String): List<SourceCookie> {
        val root = runCatching { Json.parseToJsonElement(content) }.getOrNull() ?: return emptyList()
        val values = when (root) {
            is JsonArray -> root
            is JsonObject -> root["cookies"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return values.mapNotNull { element -> jsonCookie(element, originHost) }
    }

    private fun jsonCookie(element: JsonElement, originHost: String): SourceCookie? {
        val value = element as? JsonObject ?: return null
        val name = value.string("name") ?: return null
        val cookieValue = value.string("value") ?: return null
        val rawDomain = value.string("domain").orEmpty().ifBlank { originHost }
        val hostOnly = value.boolean("hostOnly") ?: !rawDomain.startsWith('.')
        val expiresAt = sequenceOf("expirationDate", "expires", "expiry")
            .mapNotNull { key -> value.number(key) }
            .firstOrNull()
            ?.takeIf { it > 0.0 }
            ?.secondsToEpochMillis()
        return SourceCookie(
            name = name,
            value = cookieValue,
            domain = rawDomain,
            path = value.string("path").orEmpty().ifBlank { "/" },
            expiresAtEpochMillis = expiresAt,
            secure = value.boolean("secure") ?: false,
            httpOnly = value.boolean("httpOnly") ?: false,
            hostOnly = hostOnly,
        )
    }

    private fun normalize(cookie: SourceCookie, originHost: String, nowEpochMillis: Long): SourceCookie? {
        if (!cookie.name.isCookieToken() || !cookie.value.isSafeCookieValue()) return null
        if (cookie.expiresAtEpochMillis?.let { it <= nowEpochMillis } == true) return null

        val host = originHost.lowercase().trimEnd('.')
        val domain = cookie.domain.trim().trimStart('.').trimEnd('.').lowercase()
        if (domain.isEmpty()) return null
        val matchesSource = if (cookie.hostOnly) host == domain else host == domain || host.endsWith(".$domain")
        if (!matchesSource) return null

        val path = cookie.path.ifBlank { "/" }
        if (!path.startsWith('/') || path.any { character -> character.isCookieControl() }) return null
        return cookie.copy(
            domain = if (cookie.hostOnly) domain else ".$domain",
            path = path,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.booleanOrNull ?: primitive.contentOrNull?.toBooleanStrictOrNull()
    }

    private fun JsonObject.number(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }

    /** Browser exports use seconds; tolerate millisecond exports without overflowing. */
    private fun Double.secondsToEpochMillis(): Long? {
        if (!isFinite() || this <= 0.0) return null
        val milliseconds = if (this >= 100_000_000_000.0) this else this * 1_000.0
        return milliseconds.takeIf { it <= Long.MAX_VALUE.toDouble() }?.toLong()
    }

    private fun String.isCookieToken(): Boolean = isNotEmpty() && all { character ->
        character.isLetterOrDigit() || character in "!#$%&'*+-.^_`|~"
    }

    private fun String.isSafeCookieValue(): Boolean = none { it.isCookieControl() || it == ';' }

    private fun Char.isCookieControl(): Boolean = code in 0..31 || code == 127
}
