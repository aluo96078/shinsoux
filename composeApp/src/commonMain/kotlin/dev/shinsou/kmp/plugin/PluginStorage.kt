package dev.shinsou.kmp.plugin

import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
public data class PluginCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAtEpochMillis: Long? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    /** Whether [domain] is an exact host rather than a subdomain-matching Domain attribute. */
    val hostOnly: Boolean = !domain.startsWith('.'),
) {
    public fun matches(url: Url, nowEpochMillis: Long): Boolean {
        if (expiresAtEpochMillis != null && expiresAtEpochMillis <= nowEpochMillis) return false
        if (secure && url.protocol.name != "https") return false
        val requestHost = url.host.lowercase()
        val cookieDomain = domain.trimStart('.').lowercase()
        if (cookieDomain.isEmpty()) return false
        if (hostOnly) {
            if (requestHost != cookieDomain) return false
        } else if (requestHost != cookieDomain && !requestHost.endsWith(".$cookieDomain")) {
            return false
        }
        val requestPath = url.encodedPath.ifEmpty { "/" }
        val cookiePath = path.takeIf { it.startsWith('/') } ?: "/"
        return requestPath == cookiePath ||
            requestPath.startsWith(cookiePath) &&
            (cookiePath.endsWith('/') || requestPath.getOrNull(cookiePath.length) == '/')
    }
}

public data class PluginCredential(val username: String, val password: String)

/** Per-source state consumed by the JavaScript bridge and source-settings UI. */
public interface PluginStorage {
    public suspend fun getPreference(sourceId: Long, key: String): String?
    public suspend fun setPreference(sourceId: Long, key: String, value: String)

    public suspend fun getCredential(sourceId: Long): PluginCredential?
    public suspend fun setCredential(sourceId: Long, credential: PluginCredential)
    public suspend fun clearCredential(sourceId: Long)

    public suspend fun getCookies(sourceId: Long): List<PluginCookie>
    public suspend fun setCookie(sourceId: Long, cookie: PluginCookie)
    public suspend fun deleteCookie(sourceId: Long, name: String, domain: String)
    /** Removes only one RFC cookie identity without deleting same-name cookies on other paths. */
    public suspend fun deleteCookieExact(sourceId: Long, name: String, domain: String, path: String) {
        deleteCookie(sourceId, name, domain)
    }
    public suspend fun clearCookies(sourceId: Long)
}

/** Adapter point for DataStore, NSUserDefaults, Keychain, or an encrypted desktop store. */
public interface PluginKeyValueStore {
    public suspend fun getString(key: String): String?
    public suspend fun putString(key: String, value: String)
    public suspend fun remove(key: String)
}

/** Shared routing rule so cookies and credentials receive platform protected storage everywhere. */
internal fun isSensitivePluginKey(key: String): Boolean {
    val normalized = key.lowercase()
    return SENSITIVE_PLUGIN_KEY_MARKERS.any(normalized::contains)
}

/**
 * Moves every legacy sensitive value out of one plain-state snapshot.
 *
 * A pre-existing secure value is authoritative. For a missing secure value, the write is read back
 * before the caller is allowed to persist [remainingPlainValues]. If any secure operation fails,
 * this function throws and the caller must leave the original plain snapshot untouched.
 */
internal fun migrateLegacySensitivePluginValues(
    plainValues: Map<String, String>,
    readSecure: (String) -> String?,
    writeSecure: (String, String) -> Unit,
): Map<String, String> {
    val remainingPlainValues = plainValues.toMutableMap()
    plainValues.forEach { (key, legacyValue) ->
        if (!isSensitivePluginKey(key)) return@forEach

        if (readSecure(key) == null) {
            writeSecure(key, legacyValue)
            check(readSecure(key) == legacyValue) {
                "Secure plugin-value migration could not verify $key"
            }
        }
        remainingPlainValues.remove(key)
    }
    return remainingPlainValues
}

private val SENSITIVE_PLUGIN_KEY_MARKERS = listOf(
    "credential",
    "password",
    "token",
    "oauth",
    "secret",
    "cookie",
)

public class InMemoryPluginKeyValueStore : PluginKeyValueStore {
    private val values = mutableMapOf<String, String>()
    private val mutex = Mutex()

    override suspend fun getString(key: String): String? = mutex.withLock { values[key] }
    override suspend fun putString(key: String, value: String): Unit = mutex.withLock { values[key] = value }
    override suspend fun remove(key: String): Unit = mutex.withLock { values.remove(key) }
}

/** JSON-backed implementation preserving the original `source.<id>.*` key namespace. */
public class KeyValuePluginStorage(
    private val keyValueStore: PluginKeyValueStore,
    private val json: Json = PluginJson,
) : PluginStorage {
    private val mutex = Mutex()
    private val cookieCache = mutableMapOf<Long, List<PluginCookie>>()

    override suspend fun getPreference(sourceId: Long, key: String): String? =
        keyValueStore.getString("source.$sourceId.$key")

    override suspend fun setPreference(sourceId: Long, key: String, value: String) {
        keyValueStore.putString("source.$sourceId.$key", value)
    }

    override suspend fun getCredential(sourceId: Long): PluginCredential? {
        val username = keyValueStore.getString("source.$sourceId.credential.username") ?: return null
        val password = keyValueStore.getString("source.$sourceId.credential.password") ?: ""
        return PluginCredential(username, password)
    }

    override suspend fun setCredential(sourceId: Long, credential: PluginCredential) {
        keyValueStore.putString("source.$sourceId.credential.username", credential.username)
        keyValueStore.putString("source.$sourceId.credential.password", credential.password)
    }

    override suspend fun clearCredential(sourceId: Long) {
        keyValueStore.remove("source.$sourceId.credential.username")
        keyValueStore.remove("source.$sourceId.credential.password")
    }

    override suspend fun getCookies(sourceId: Long): List<PluginCookie> = mutex.withLock {
        cachedCookies(sourceId).toList()
    }

    override suspend fun setCookie(sourceId: Long, cookie: PluginCookie): Unit = mutex.withLock {
        val normalized = requireNotNull(normalizedPluginCookieOrNull(cookie)) { "Invalid cookie" }
        val cookies = cachedCookies(sourceId).toMutableList()
        cookies.removeAll {
            it.name == normalized.name &&
                it.domain.equals(normalized.domain, ignoreCase = true) &&
                it.path == normalized.path
        }
        cookies += normalized
        writeCookies(sourceId, cookies)
    }

    override suspend fun deleteCookie(sourceId: Long, name: String, domain: String): Unit = mutex.withLock {
        val canonicalDomain = canonicalCookieDomain(domain)
        val cookies = cachedCookies(sourceId).filterNot {
            it.name == name && it.domain.equals(canonicalDomain, ignoreCase = true)
        }
        writeCookies(sourceId, cookies)
    }

    override suspend fun deleteCookieExact(
        sourceId: Long,
        name: String,
        domain: String,
        path: String,
    ): Unit = mutex.withLock {
        val canonicalDomain = canonicalCookieDomain(domain)
        val canonicalPath = path.takeIf { it.startsWith('/') } ?: "/"
        val cookies = cachedCookies(sourceId).filterNot {
            it.name == name &&
                it.domain.equals(canonicalDomain, ignoreCase = true) &&
                it.path == canonicalPath
        }
        writeCookies(sourceId, cookies)
    }

    override suspend fun clearCookies(sourceId: Long): Unit = mutex.withLock {
        keyValueStore.remove(cookieKey(sourceId))
        cookieCache[sourceId] = emptyList()
    }

    /** Cookies are read for every image request; decode each source jar only once per process. */
    private suspend fun cachedCookies(sourceId: Long): List<PluginCookie> =
        cookieCache[sourceId] ?: readCookies(sourceId).also { cookieCache[sourceId] = it }

    private suspend fun readCookies(sourceId: Long): List<PluginCookie> {
        val encoded = keyValueStore.getString(cookieKey(sourceId)) ?: return emptyList()
        val decoded = runCatching { json.decodeFromString(ListSerializer(PluginCookie.serializer()), encoded) }
            .getOrDefault(emptyList())
        val normalized = LinkedHashMap<Triple<String, String, String>, PluginCookie>()
        decoded.forEach { cookie ->
            normalizedPluginCookieOrNull(cookie)?.let {
                normalized[Triple(it.name, it.domain.lowercase(), it.path)] = it
            }
        }
        return normalized.values.toList().takeLast(MAX_COOKIES_PER_SOURCE)
    }

    private suspend fun writeCookies(sourceId: Long, cookies: List<PluginCookie>) {
        val snapshot = cookies.takeLast(MAX_COOKIES_PER_SOURCE).toList()
        if (snapshot.isEmpty()) {
            keyValueStore.remove(cookieKey(sourceId))
        } else {
            keyValueStore.putString(
                cookieKey(sourceId),
                json.encodeToString(
                    ListSerializer(PluginCookie.serializer()),
                    snapshot,
                ),
            )
        }
        cookieCache[sourceId] = snapshot
    }

    private fun cookieKey(sourceId: Long): String = "source.$sourceId.cookies"
}

internal const val MAX_COOKIES_PER_SOURCE: Int = 500
internal const val MAX_COOKIE_BYTES: Int = 4_096

internal fun canonicalCookieDomain(value: String): String = value.trim().trimStart('.').lowercase()

/** Shared jar boundary for imported, UI-entered and response cookies. */
internal fun normalizedPluginCookieOrNull(cookie: PluginCookie): PluginCookie? {
    val domain = canonicalCookieDomain(cookie.domain)
    val path = cookie.path.takeIf { it.startsWith('/') } ?: "/"
    if (!isValidCookieName(cookie.name) || !isValidCookieValue(cookie.value)) return null
    if ((!isValidCookieDomainSyntax(domain) && !isCookieIpAddress(domain)) || path.length > 1_024) return null
    if (cookie.name.encodeToByteArray().size + cookie.value.encodeToByteArray().size > MAX_COOKIE_BYTES) return null
    return cookie.copy(domain = domain, path = path)
}

internal fun isValidCookieName(value: String): Boolean = value.isNotEmpty() && value.all { character ->
    character.code in 0x21..0x7E && character !in COOKIE_NAME_SEPARATORS
}

internal fun isValidCookieValue(value: String): Boolean = value.all { character ->
    character.code in 0x20..0x7E && character != ';' && character != '\u007f'
}

internal fun isCookieIpAddress(host: String): Boolean =
    ':' in host && host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' } ||
        host.split('.').let { labels ->
            labels.size == 4 && labels.all { label -> label.toIntOrNull() in 0..255 }
        }

internal fun isValidCookieDomainSyntax(value: String): Boolean {
    if (value.isEmpty() || value.length > 253 || value.endsWith('.')) return false
    return value.split('.').all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
            label.all { it.isLetterOrDigit() || it == '-' } && label.all { it.code < 128 }
    }
}

private const val COOKIE_NAME_SEPARATORS = "()<>@,;:\\\"/[]?={} \t"

public interface PluginTrustStore {
    public suspend fun isTrusted(pluginId: String, versionCode: Int, sha256: String): Boolean
    public suspend fun trust(pluginId: String, versionCode: Int, sha256: String)
    public suspend fun revoke(pluginId: String, versionCode: Int, sha256: String)
    public suspend fun revokeAll(pluginId: String)
}

public class KeyValuePluginTrustStore(
    private val keyValueStore: PluginKeyValueStore,
    private val json: Json = PluginJson,
) : PluginTrustStore {
    private val mutex = Mutex()
    private val key = "plugin.trustStore.trustedTokens"

    override suspend fun isTrusted(pluginId: String, versionCode: Int, sha256: String): Boolean =
        mutex.withLock { token(pluginId, versionCode, sha256) in readTokens() }

    override suspend fun trust(pluginId: String, versionCode: Int, sha256: String): Unit = mutex.withLock {
        writeTokens(readTokens() + token(pluginId, versionCode, sha256))
    }

    override suspend fun revoke(pluginId: String, versionCode: Int, sha256: String): Unit = mutex.withLock {
        writeTokens(readTokens() - token(pluginId, versionCode, sha256))
    }

    override suspend fun revokeAll(pluginId: String): Unit = mutex.withLock {
        writeTokens(readTokens().filterNot { it.startsWith("$pluginId:") }.toSet())
    }

    private suspend fun readTokens(): Set<String> {
        val encoded = keyValueStore.getString(key) ?: return emptySet()
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), encoded).toSet() }
            .getOrDefault(emptySet())
    }

    private suspend fun writeTokens(tokens: Set<String>) {
        if (tokens.isEmpty()) keyValueStore.remove(key)
        else keyValueStore.putString(key, json.encodeToString(ListSerializer(String.serializer()), tokens.sorted()))
    }

    private fun token(pluginId: String, versionCode: Int, sha256: String): String =
        "$pluginId:$versionCode:${sha256.lowercase()}"
}

public val PluginJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = false
}
