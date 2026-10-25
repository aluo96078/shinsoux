package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
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

private fun PluginCredential.validated(): PluginCredential = also {
    require(pluginUtf8ByteCountAtMost(username, MAX_PLUGIN_CREDENTIAL_USERNAME_BYTES) != null) {
        "Plugin credential username is too large"
    }
    require(pluginUtf8ByteCountAtMost(password, MAX_PLUGIN_CREDENTIAL_PASSWORD_BYTES) != null) {
        "Plugin credential password is too large"
    }
}

/**
 * Preferences share a physical key-value store with credentials, cookies, and browser state.
 * Keep untrusted preference names bounded and prevent legacy numeric scopes from addressing those
 * host-owned records by choosing a colliding name such as `credential.password` or `cookies`.
 */
private fun requirePluginPreferenceShape(key: String, value: String? = null) {
    require(key.isNotBlank() && key.none(Char::isISOControl)) { "Invalid plugin preference key" }
    require(pluginUtf8ByteCountAtMost(key, MAX_PLUGIN_PREFERENCE_KEY_BYTES) != null) {
        "Plugin preference key is too large"
    }
    value?.let {
        require(pluginUtf8ByteCountAtMost(it, MAX_PLUGIN_PREFERENCE_VALUE_BYTES) != null) {
            "Plugin preference value is too large"
        }
    }
}

private fun requireLegacyPreferenceNameNotReserved(key: String) {
    val normalized = key.lowercase()
    require(
        normalized !in LEGACY_RESERVED_PREFERENCE_NAMES &&
            normalized != LEGACY_PREFERENCE_NAMESPACE &&
            !normalized.startsWith("$LEGACY_PREFERENCE_NAMESPACE."),
    ) {
        "Plugin preference key collides with host-owned source state"
    }
}

private fun validateStoredPluginPreference(value: String?): String? = value?.also {
    require(pluginUtf8ByteCountAtMost(it, MAX_PLUGIN_PREFERENCE_VALUE_BYTES) != null) {
        "Stored plugin preference value is too large"
    }
}

/** Per-source state consumed by the JavaScript bridge and source-settings UI. */
public interface PluginStorage {
    public suspend fun getPreference(sourceId: Long, key: String): String?
    public suspend fun setPreference(sourceId: Long, key: String, value: String)
    /** Restores true absence; callers must not emulate deletion with an empty sentinel value. */
    public suspend fun removePreference(sourceId: Long, key: String)

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

    /** UA captured with browser-bound anti-bot cookies; never shared across source scopes. */
    public suspend fun getWebChallengeUserAgent(sourceId: Long): String? = null
    public suspend fun setWebChallengeUserAgent(sourceId: Long, userAgent: String) = Unit
    public suspend fun clearWebChallengeUserAgent(sourceId: Long) = Unit
}

/** Internal capability implemented by stores which can address state by an exact V2 source key. */
internal fun interface SourceKeyPluginStorage {
    fun bindSource(sourceKey: SourceKey, allowLegacyMigration: Boolean): PluginStorage
}

/**
 * Runtime-facing storage which ignores no part of extension identity.
 *
 * The numeric argument remains in [PluginStorage] for UI/legacy compatibility, but a runtime can
 * only use the Long assigned by its host-owned [sourceKey]. The backing store receives the full
 * key and decides whether verified pre-V2 state may be migrated.
 */
public class BoundPluginStorage(
    delegate: PluginStorage,
    public val sourceKey: SourceKey,
    allowLegacyMigration: Boolean = false,
) : PluginStorage {
    init {
        requireExactStorageSourceKey(sourceKey)
    }

    private val legacySourceId: Long = requireNotNull(sourceKey.legacyLongId) {
        "A legacy runtime storage binding requires its host-assigned Long"
    }
    private val scoped: PluginStorage = (delegate as? SourceKeyPluginStorage)
        ?.bindSource(sourceKey, allowLegacyMigration)
        ?: throw IllegalArgumentException("Plugin storage does not support SourceKey binding")

    private fun requireBound(sourceId: Long) {
        require(sourceId == legacySourceId) { "Plugin storage source does not match its runtime binding" }
    }

    override suspend fun getPreference(sourceId: Long, key: String): String? {
        requireBound(sourceId)
        return scoped.getPreference(legacySourceId, key)
    }

    override suspend fun setPreference(sourceId: Long, key: String, value: String) {
        requireBound(sourceId)
        scoped.setPreference(legacySourceId, key, value)
    }

    override suspend fun removePreference(sourceId: Long, key: String) {
        requireBound(sourceId)
        scoped.removePreference(legacySourceId, key)
    }

    override suspend fun getCredential(sourceId: Long): PluginCredential? {
        requireBound(sourceId)
        return scoped.getCredential(legacySourceId)
    }

    override suspend fun setCredential(sourceId: Long, credential: PluginCredential) {
        requireBound(sourceId)
        scoped.setCredential(legacySourceId, credential)
    }

    override suspend fun clearCredential(sourceId: Long) {
        requireBound(sourceId)
        scoped.clearCredential(legacySourceId)
    }

    override suspend fun getCookies(sourceId: Long): List<PluginCookie> {
        requireBound(sourceId)
        return scoped.getCookies(legacySourceId)
    }

    override suspend fun setCookie(sourceId: Long, cookie: PluginCookie) {
        requireBound(sourceId)
        scoped.setCookie(legacySourceId, cookie)
    }

    override suspend fun deleteCookie(sourceId: Long, name: String, domain: String) {
        requireBound(sourceId)
        scoped.deleteCookie(legacySourceId, name, domain)
    }

    override suspend fun deleteCookieExact(sourceId: Long, name: String, domain: String, path: String) {
        requireBound(sourceId)
        scoped.deleteCookieExact(legacySourceId, name, domain, path)
    }

    override suspend fun clearCookies(sourceId: Long) {
        requireBound(sourceId)
        scoped.clearCookies(legacySourceId)
    }

    override suspend fun getWebChallengeUserAgent(sourceId: Long): String? {
        requireBound(sourceId)
        return scoped.getWebChallengeUserAgent(legacySourceId)
    }

    override suspend fun setWebChallengeUserAgent(sourceId: Long, userAgent: String) {
        requireBound(sourceId)
        scoped.setWebChallengeUserAgent(legacySourceId, userAgent)
    }

    override suspend fun clearWebChallengeUserAgent(sourceId: Long) {
        requireBound(sourceId)
        scoped.clearWebChallengeUserAgent(legacySourceId)
    }
}

/** Fail-closed view of sensitive storage ports for one admitted runtime artifact. */
public class PermissionFilteredPluginStorage(
    private val delegate: PluginStorage,
    permissions: Set<PluginRuntimePermission>,
) : PluginStorage {
    private val credentialsAllowed = PluginRuntimePermission.CREDENTIAL_ACCESS in permissions
    internal val cookiesAllowedForNetwork = PluginRuntimePermission.COOKIE_STORAGE in permissions

    override suspend fun getPreference(sourceId: Long, key: String): String? =
        delegate.getPreference(sourceId, key)

    override suspend fun setPreference(sourceId: Long, key: String, value: String) =
        delegate.setPreference(sourceId, key, value)

    override suspend fun removePreference(sourceId: Long, key: String) =
        delegate.removePreference(sourceId, key)

    override suspend fun getCredential(sourceId: Long): PluginCredential? {
        if (!credentialsAllowed) return null
        return delegate.getCredential(sourceId)
    }

    override suspend fun setCredential(sourceId: Long, credential: PluginCredential) {
        require(credentialsAllowed) { "Plugin runtime lacks CREDENTIAL_ACCESS permission" }
        delegate.setCredential(sourceId, credential)
    }

    override suspend fun clearCredential(sourceId: Long) {
        require(credentialsAllowed) { "Plugin runtime lacks CREDENTIAL_ACCESS permission" }
        delegate.clearCredential(sourceId)
    }

    override suspend fun getCookies(sourceId: Long): List<PluginCookie> {
        if (!cookiesAllowedForNetwork) return emptyList()
        return delegate.getCookies(sourceId)
    }

    override suspend fun setCookie(sourceId: Long, cookie: PluginCookie) {
        require(cookiesAllowedForNetwork) { "Plugin runtime lacks COOKIE_STORAGE permission" }
        delegate.setCookie(sourceId, cookie)
    }

    override suspend fun deleteCookie(sourceId: Long, name: String, domain: String) {
        require(cookiesAllowedForNetwork) { "Plugin runtime lacks COOKIE_STORAGE permission" }
        delegate.deleteCookie(sourceId, name, domain)
    }

    override suspend fun deleteCookieExact(sourceId: Long, name: String, domain: String, path: String) {
        require(cookiesAllowedForNetwork) { "Plugin runtime lacks COOKIE_STORAGE permission" }
        delegate.deleteCookieExact(sourceId, name, domain, path)
    }

    override suspend fun clearCookies(sourceId: Long) {
        require(cookiesAllowedForNetwork) { "Plugin runtime lacks COOKIE_STORAGE permission" }
        delegate.clearCookies(sourceId)
    }

    override suspend fun getWebChallengeUserAgent(sourceId: Long): String? {
        if (!cookiesAllowedForNetwork) return null
        return delegate.getWebChallengeUserAgent(sourceId)
    }

    override suspend fun setWebChallengeUserAgent(sourceId: Long, userAgent: String) {
        require(cookiesAllowedForNetwork) { "Plugin runtime lacks COOKIE_STORAGE permission" }
        delegate.setWebChallengeUserAgent(sourceId, userAgent)
    }

    override suspend fun clearWebChallengeUserAgent(sourceId: Long) {
        require(cookiesAllowedForNetwork) { "Plugin runtime lacks COOKIE_STORAGE permission" }
        delegate.clearWebChallengeUserAgent(sourceId)
    }
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
) : PluginStorage, SourceKeyPluginStorage {
    private val mutex = Mutex()
    private val cookieCache = mutableMapOf<Long, List<PluginCookie>>()
    private val exactCookieCache = mutableMapOf<String, List<PluginCookie>>()

    override fun bindSource(sourceKey: SourceKey, allowLegacyMigration: Boolean): PluginStorage {
        requireExactStorageSourceKey(sourceKey)
        return ExactSourceStorage(sourceKey, allowLegacyMigration)
    }

    override suspend fun getPreference(sourceId: Long, key: String): String? {
        requirePluginPreferenceShape(key)
        requireLegacyPreferenceNameNotReserved(key)
        return mutex.withLock {
            readPreferenceLocked(
                namespace = legacyPreferenceNamespace(sourceId),
                key = key,
                legacyKeys = listOf("source.$sourceId.$key"),
            )
        }
    }

    override suspend fun setPreference(sourceId: Long, key: String, value: String) {
        requirePluginPreferenceShape(key, value)
        requireLegacyPreferenceNameNotReserved(key)
        mutex.withLock {
            setPreferenceLocked(
                namespace = legacyPreferenceNamespace(sourceId),
                key = key,
                value = value,
                obsoleteKeys = listOf("source.$sourceId.$key"),
            )
        }
    }

    override suspend fun removePreference(sourceId: Long, key: String) {
        requirePluginPreferenceShape(key)
        requireLegacyPreferenceNameNotReserved(key)
        mutex.withLock {
            removePreferenceLocked(
                namespace = legacyPreferenceNamespace(sourceId),
                key = key,
                legacyKeys = listOf("source.$sourceId.$key"),
            )
        }
    }

    override suspend fun getCredential(sourceId: Long): PluginCredential? = mutex.withLock {
        readOrMigrateCredentialLocked("source.$sourceId")?.credentialOrNull()
    }

    override suspend fun setCredential(sourceId: Long, credential: PluginCredential) {
        mutex.withLock {
            writeCredentialLocked("source.$sourceId", StoredPluginCredential.present(credential))
        }
    }

    override suspend fun clearCredential(sourceId: Long) {
        mutex.withLock {
            writeCredentialLocked("source.$sourceId", StoredPluginCredential.cleared())
        }
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
        keyValueStore.remove(webChallengeUserAgentKey(sourceId))
        cookieCache[sourceId] = emptyList()
    }

    override suspend fun getWebChallengeUserAgent(sourceId: Long): String? =
        keyValueStore.getString(webChallengeUserAgentKey(sourceId))
            ?.let(::normalizePluginUserAgent)

    override suspend fun setWebChallengeUserAgent(sourceId: Long, userAgent: String) {
        keyValueStore.putString(
            webChallengeUserAgentKey(sourceId),
            requireNotNull(normalizePluginUserAgent(userAgent)) { "Invalid web challenge User-Agent" },
        )
    }

    override suspend fun clearWebChallengeUserAgent(sourceId: Long) {
        keyValueStore.remove(webChallengeUserAgentKey(sourceId))
    }

    /** Cookies are read for every image request; decode each source jar only once per process. */
    private suspend fun cachedCookies(sourceId: Long): List<PluginCookie> =
        cookieCache[sourceId] ?: readCookies(sourceId).also { cookieCache[sourceId] = it }

    private suspend fun readCookies(sourceId: Long): List<PluginCookie> {
        val encoded = keyValueStore.getString(cookieKey(sourceId)) ?: return emptyList()
        return decodeCookieJar(encoded)
    }

    private suspend fun writeCookies(sourceId: Long, cookies: List<PluginCookie>) {
        val (snapshot, encoded) = encodeCookieJar(cookies)
        if (snapshot.isEmpty()) {
            keyValueStore.remove(cookieKey(sourceId))
        } else {
            keyValueStore.putString(cookieKey(sourceId), requireNotNull(encoded))
        }
        cookieCache[sourceId] = snapshot
    }

    private fun cookieKey(sourceId: Long): String = "source.$sourceId.cookies"
    private fun webChallengeUserAgentKey(sourceId: Long): String =
        "source.$sourceId.webChallenge.userAgent"

    /**
     * Exact SourceKey namespace. A persistent owner claim makes legacy read-through one-way:
     * uninstall intentionally leaves it behind, so a different package can never recycle the
     * numeric id and recover the previous extension's credentials or cookies.
     */
    private inner class ExactSourceStorage(
        private val sourceKey: SourceKey,
        private val allowLegacyMigration: Boolean,
    ) : PluginStorage {
        private val legacySourceId = requireNotNull(sourceKey.legacyLongId)
        private val namespace = "source.v2.${sourceKey.canonicalId.hexMarkerComponent()}"
        private val owner = sourceKey.canonicalId

        override suspend fun getPreference(sourceId: Long, key: String): String? {
            requireSource(sourceId)
            requirePluginPreferenceShape(key)
            return mutex.withLock {
                val legacyKeys = buildList {
                    add(exactPreferenceKey(key))
                    if (claimOwnerLocked(allowLegacyMigration)) {
                        requireLegacyPreferenceNameNotReserved(key)
                        add("source.$legacySourceId.$key")
                    }
                }
                readPreferenceLocked(exactPreferenceNamespace(), key, legacyKeys)
            }
        }

        override suspend fun setPreference(sourceId: Long, key: String, value: String) {
            requireSource(sourceId)
            requirePluginPreferenceShape(key, value)
            mutex.withLock {
                claimOwnerLocked(false)
                setPreferenceLocked(
                    namespace = exactPreferenceNamespace(),
                    key = key,
                    value = value,
                    obsoleteKeys = listOf(exactPreferenceKey(key)),
                )
            }
        }

        override suspend fun removePreference(sourceId: Long, key: String) {
            requireSource(sourceId)
            requirePluginPreferenceShape(key)
            mutex.withLock {
                val mayReadLegacy = claimOwnerLocked(allowLegacyMigration)
                removePreferenceLocked(
                    namespace = exactPreferenceNamespace(),
                    key = key,
                    legacyKeys = buildList {
                        add(exactPreferenceKey(key))
                        if (mayReadLegacy) add("source.$legacySourceId.$key")
                    },
                )
            }
        }

        override suspend fun getCredential(sourceId: Long): PluginCredential? {
            requireSource(sourceId)
            return mutex.withLock {
                readOrMigrateCredentialLocked(namespace)?.let { return@withLock it.credentialOrNull() }
                if (!claimOwnerLocked(allowLegacyMigration)) return@withLock null
                val legacy = readOrMigrateCredentialLocked("source.$legacySourceId") ?: return@withLock null
                writeCredentialLocked(namespace, legacy)
                legacy.credentialOrNull()
            }
        }

        override suspend fun setCredential(sourceId: Long, credential: PluginCredential) {
            requireSource(sourceId)
            mutex.withLock {
                claimOwnerLocked(false)
                writeCredentialLocked(namespace, StoredPluginCredential.present(credential))
            }
        }

        override suspend fun clearCredential(sourceId: Long) {
            requireSource(sourceId)
            mutex.withLock {
                claimOwnerLocked(false)
                writeCredentialLocked(namespace, StoredPluginCredential.cleared())
            }
        }

        override suspend fun getCookies(sourceId: Long): List<PluginCookie> {
            requireSource(sourceId)
            return mutex.withLock {
                exactCookieCache[namespace] ?: readExactCookies().also { exactCookieCache[namespace] = it }
            }.toList()
        }

        override suspend fun setCookie(sourceId: Long, cookie: PluginCookie) {
            requireSource(sourceId)
            mutex.withLock {
                claimOwnerLocked(false)
                val normalized = requireNotNull(normalizedPluginCookieOrNull(cookie)) { "Invalid cookie" }
                val cookies = (exactCookieCache[namespace] ?: readExactCookies()).toMutableList()
                cookies.removeAll {
                    it.name == normalized.name && it.domain.equals(normalized.domain, true) &&
                        it.path == normalized.path
                }
                cookies += normalized
                writeExactCookies(cookies)
            }
        }

        override suspend fun deleteCookie(sourceId: Long, name: String, domain: String) {
            requireSource(sourceId)
            mutex.withLock {
                claimOwnerLocked(false)
                val canonicalDomain = canonicalCookieDomain(domain)
                writeExactCookies((exactCookieCache[namespace] ?: readExactCookies()).filterNot {
                    it.name == name && it.domain.equals(canonicalDomain, true)
                })
            }
        }

        override suspend fun deleteCookieExact(sourceId: Long, name: String, domain: String, path: String) {
            requireSource(sourceId)
            mutex.withLock {
                claimOwnerLocked(false)
                val canonicalDomain = canonicalCookieDomain(domain)
                val canonicalPath = path.takeIf { it.startsWith('/') } ?: "/"
                writeExactCookies((exactCookieCache[namespace] ?: readExactCookies()).filterNot {
                    it.name == name && it.domain.equals(canonicalDomain, true) && it.path == canonicalPath
                })
            }
        }

        override suspend fun clearCookies(sourceId: Long) {
            requireSource(sourceId)
            mutex.withLock {
                claimOwnerLocked(false)
                keyValueStore.remove("$namespace.cookies")
                keyValueStore.remove("$namespace.webChallenge.userAgent")
                exactCookieCache[namespace] = emptyList()
            }
        }

        override suspend fun getWebChallengeUserAgent(sourceId: Long): String? {
            requireSource(sourceId)
            val exactKey = "$namespace.webChallenge.userAgent"
            keyValueStore.getString(exactKey)?.let { return normalizePluginUserAgent(it) }
            if (!claimOwner(allowLegacyMigration)) return null
            return keyValueStore.getString(webChallengeUserAgentKey(legacySourceId))
                ?.let(::normalizePluginUserAgent)
                ?.also { keyValueStore.putString(exactKey, it) }
        }

        override suspend fun setWebChallengeUserAgent(sourceId: Long, userAgent: String) {
            requireSource(sourceId)
            claimOwner(false)
            keyValueStore.putString(
                "$namespace.webChallenge.userAgent",
                requireNotNull(normalizePluginUserAgent(userAgent)) { "Invalid web challenge User-Agent" },
            )
        }

        override suspend fun clearWebChallengeUserAgent(sourceId: Long) {
            requireSource(sourceId)
            claimOwner(false)
            keyValueStore.remove("$namespace.webChallenge.userAgent")
        }

        private suspend fun readExactCookies(): List<PluginCookie> {
            val encoded = keyValueStore.getString("$namespace.cookies")
            if (encoded == null && claimOwnerLocked(allowLegacyMigration)) {
                keyValueStore.getString(cookieKey(legacySourceId))?.let { legacyEncoded ->
                    // Validate and normalize before creating an exact-scope durable copy. In
                    // particular, an oversized legacy jar must not be copied first and rejected
                    // only after it has already consumed another source's storage allocation.
                    val legacyCookies = decodeCookieJar(legacyEncoded)
                    writeExactCookies(legacyCookies)
                    return legacyCookies
                }
            }
            return encoded?.let(::decodeCookieJar).orEmpty()
        }

        private suspend fun writeExactCookies(cookies: List<PluginCookie>) {
            val (snapshot, encoded) = encodeCookieJar(cookies)
            if (snapshot.isEmpty()) keyValueStore.remove("$namespace.cookies")
            else keyValueStore.putString("$namespace.cookies", requireNotNull(encoded))
            exactCookieCache[namespace] = snapshot
        }

        private suspend fun claimOwner(mayReadLegacy: Boolean): Boolean = mutex.withLock {
            claimOwnerLocked(mayReadLegacy)
        }

        private suspend fun claimOwnerLocked(mayReadLegacy: Boolean): Boolean {
            val key = legacyOwnerKey(legacySourceId)
            val existing = keyValueStore.getString(key)
            if (existing == null) {
                keyValueStore.putString(key, owner)
                check(keyValueStore.getString(key) == owner) { "Could not verify plugin storage owner" }
            }
            return mayReadLegacy && keyValueStore.getString(key) == owner
        }

        private fun requireSource(sourceId: Long) {
            require(sourceId == legacySourceId) { "Plugin storage source does not match its runtime binding" }
        }

        private fun exactPreferenceNamespace(): String = "$namespace.preferences.v2"
        private fun exactPreferenceKey(key: String): String =
            "$namespace.preference.${key.hexMarkerComponent()}"
    }

    /**
     * Preference metadata makes the aggregate quota durable without requiring a key enumeration
     * primitive from every platform store. Values remain in per-key records so the platform KV
     * layer can route token/password/secret preferences through protected storage.
     */
    private suspend fun readPreferenceLocked(
        namespace: String,
        key: String,
        legacyKeys: List<String>,
    ): String? {
        val metadataKey = preferenceMetadataKey(namespace)
        val snapshot = readPreferenceSnapshotLocked(metadataKey)
        if (key in snapshot.legacyTombstones) return null
        val valueKey = preferenceValueKey(namespace, key)
        keyValueStore.getString(valueKey)?.let { stored ->
            val value = validateStoredPluginPreference(stored)!!
            val actualBytes = value.encodeToByteArray().size.toLong()
            val reservedBytes = snapshot.valueBytes[key]
                ?: throw IllegalArgumentException("Stored plugin preference is missing quota metadata")
            require(actualBytes <= reservedBytes) { "Stored plugin preference exceeds its quota reservation" }
            return value
        }
        val legacyValue = legacyKeys.firstNotNullOfOrNull { legacyKey ->
            validateStoredPluginPreference(keyValueStore.getString(legacyKey))
        } ?: return null
        val valueBytes = legacyValue.encodeToByteArray().size.toLong()
        writePreferenceSnapshotLocked(metadataKey, snapshot.withReservation(key, valueBytes))
        keyValueStore.putString(valueKey, legacyValue)
        legacyKeys.forEach { keyValueStore.remove(it) }
        writePreferenceSnapshotLocked(
            metadataKey,
            readPreferenceSnapshotLocked(metadataKey).withReservation(key, valueBytes),
        )
        return legacyValue
    }

    private suspend fun setPreferenceLocked(
        namespace: String,
        key: String,
        value: String,
        obsoleteKeys: List<String>,
    ) {
        val metadataKey = preferenceMetadataKey(namespace)
        val snapshot = readPreferenceSnapshotLocked(metadataKey)
        val valueBytes = value.encodeToByteArray().size.toLong()
        val reservation = maxOf(snapshot.valueBytes[key] ?: 0L, valueBytes)
        writePreferenceSnapshotLocked(metadataKey, snapshot.withReservation(key, reservation))
        keyValueStore.putString(preferenceValueKey(namespace, key), value)
        obsoleteKeys.forEach { keyValueStore.remove(it) }
        writePreferenceSnapshotLocked(
            metadataKey,
            readPreferenceSnapshotLocked(metadataKey).withReservation(key, valueBytes),
        )
    }

    private suspend fun removePreferenceLocked(
        namespace: String,
        key: String,
        legacyKeys: List<String>,
    ) {
        val metadataKey = preferenceMetadataKey(namespace)
        val snapshot = readPreferenceSnapshotLocked(metadataKey)
        val valueKey = preferenceValueKey(namespace, key)
        val hasStoredValue = keyValueStore.getString(valueKey) != null ||
            legacyKeys.any { keyValueStore.getString(it) != null }
        if (hasStoredValue) {
            // Persist the tombstone first: a crash between physical deletes cannot resurrect a
            // legacy preference through exact-source read-through.
            writePreferenceSnapshotLocked(metadataKey, snapshot.withTombstone(key))
        }
        keyValueStore.remove(valueKey)
        legacyKeys.forEach { keyValueStore.remove(it) }
        writePreferenceSnapshotLocked(
            metadataKey,
            readPreferenceSnapshotLocked(metadataKey).withoutValue(key),
        )
    }

    private suspend fun readPreferenceSnapshotLocked(key: String): PluginPreferenceSnapshot {
        val encoded = keyValueStore.getString(key) ?: return PluginPreferenceSnapshot()
        require(pluginUtf8ByteCountAtMost(encoded, MAX_PLUGIN_PREFERENCE_SNAPSHOT_ENCODED_BYTES) != null) {
            "Stored plugin preference snapshot is too large"
        }
        return runCatching { json.decodeFromString(PluginPreferenceSnapshot.serializer(), encoded) }
            .getOrElse { throw IllegalArgumentException("Stored plugin preference snapshot is invalid", it) }
            .validated()
    }

    private suspend fun writePreferenceSnapshotLocked(key: String, snapshot: PluginPreferenceSnapshot) {
        val validated = snapshot.validated()
        if (validated.valueBytes.isEmpty() && validated.legacyTombstones.isEmpty()) {
            keyValueStore.remove(key)
            return
        }
        val encoded = json.encodeToString(PluginPreferenceSnapshot.serializer(), validated)
        check(pluginUtf8ByteCountAtMost(encoded, MAX_PLUGIN_PREFERENCE_SNAPSHOT_ENCODED_BYTES) != null) {
            "Encoded plugin preference snapshot exceeded its storage bound"
        }
        keyValueStore.putString(key, encoded)
    }

    private fun preferenceMetadataKey(namespace: String): String = "$namespace.metadata"

    private fun preferenceValueKey(namespace: String, key: String): String {
        val protectedMarker = if (isSensitivePluginKey(key)) ".secret" else ""
        return "$namespace$protectedMarker.value.${key.hexMarkerComponent()}"
    }

    private fun legacyPreferenceNamespace(sourceId: Long): String = "source.$sourceId.preferences.v2"
    private fun legacyOwnerKey(sourceId: Long): String = "source.$sourceId.v2.owner"

    /**
     * One versioned sensitive record is the credential commit point. A failed individual KV write
     * can therefore expose either the complete old credential or the complete new credential,
     * never a username from one update paired with a password from another. A durable cleared
     * record also prevents legacy two-key data from being resurrected after an interrupted clear.
     */
    private suspend fun readOrMigrateCredentialLocked(namespace: String): StoredPluginCredential? {
        val recordKey = credentialRecordKey(namespace)
        keyValueStore.getString(recordKey)?.let { encoded ->
            require(pluginUtf8ByteCountAtMost(encoded, MAX_PLUGIN_CREDENTIAL_RECORD_ENCODED_BYTES) != null) {
                "Stored plugin credential record is too large"
            }
            return runCatching { json.decodeFromString(StoredPluginCredential.serializer(), encoded) }
                .getOrElse { throw IllegalArgumentException("Stored plugin credential record is invalid", it) }
                .validated()
        }

        val usernameKey = "$namespace.credential.username"
        val passwordKey = "$namespace.credential.password"
        val username = keyValueStore.getString(usernameKey)
        val password = keyValueStore.getString(passwordKey)
        if (username == null && password == null) return null
        require(username != null && password != null) {
            "Stored legacy plugin credential is incomplete"
        }
        val migrated = StoredPluginCredential.present(PluginCredential(username, password))
        writeCredentialLocked(namespace, migrated)
        return migrated
    }

    private suspend fun writeCredentialLocked(namespace: String, record: StoredPluginCredential) {
        val validated = record.validated()
        val encoded = json.encodeToString(StoredPluginCredential.serializer(), validated)
        check(pluginUtf8ByteCountAtMost(encoded, MAX_PLUGIN_CREDENTIAL_RECORD_ENCODED_BYTES) != null) {
            "Encoded plugin credential record exceeded its storage bound"
        }

        // This single sensitive write is authoritative. Obsolete two-key values are removed only
        // after it succeeds; interruption during cleanup cannot create a mixed credential.
        keyValueStore.putString(credentialRecordKey(namespace), encoded)
        keyValueStore.remove("$namespace.credential.username")
        keyValueStore.remove("$namespace.credential.password")
    }

    private fun credentialRecordKey(namespace: String): String = "$namespace.credential.record.v2"

    private fun decodeCookieJar(encoded: String): List<PluginCookie> {
        require(pluginUtf8ByteCountAtMost(encoded, MAX_PLUGIN_COOKIE_JAR_ENCODED_BYTES) != null) {
            "Stored plugin cookie jar is too large"
        }
        val decoded = runCatching {
            json.decodeFromString(ListSerializer(PluginCookie.serializer()), encoded)
        }.getOrElse { throw IllegalArgumentException("Stored plugin cookie jar is invalid", it) }
        require(decoded.size <= MAX_COOKIES_PER_SOURCE) { "Stored plugin cookie count quota exceeded" }
        val normalized = LinkedHashMap<Triple<String, String, String>, PluginCookie>()
        decoded.forEach { cookie ->
            val valid = requireNotNull(normalizedPluginCookieOrNull(cookie)) {
                "Stored plugin cookie jar contains an invalid cookie"
            }
            normalized[Triple(valid.name, valid.domain.lowercase(), valid.path)] = valid
        }
        return normalized.values.toList()
    }

    private fun encodeCookieJar(cookies: List<PluginCookie>): Pair<List<PluginCookie>, String?> {
        require(cookies.size <= MAX_COOKIES_PER_SOURCE) { "Plugin cookie count quota exceeded" }
        val snapshot = cookies.toList()
        if (snapshot.isEmpty()) return snapshot to null
        val encoded = json.encodeToString(ListSerializer(PluginCookie.serializer()), snapshot)
        require(pluginUtf8ByteCountAtMost(encoded, MAX_PLUGIN_COOKIE_JAR_ENCODED_BYTES) != null) {
            "Plugin cookie jar byte quota exceeded"
        }
        return snapshot to encoded
    }
}

@Serializable
private data class StoredPluginCredential(
    val version: Int = CURRENT_VERSION,
    val username: String? = null,
    val password: String? = null,
    val isCleared: Boolean = false,
) {
    fun credentialOrNull(): PluginCredential? =
        if (isCleared) null else PluginCredential(requireNotNull(username), requireNotNull(password))

    fun validated(): StoredPluginCredential = also {
        require(version == CURRENT_VERSION) { "Unsupported plugin credential record version" }
        require(
            if (isCleared) username == null && password == null
            else username != null && password != null,
        ) {
            "Plugin credential record has conflicting state"
        }
        if (!isCleared) credentialOrNull()?.validated()
    }

    companion object {
        const val CURRENT_VERSION: Int = 1

        fun present(credential: PluginCredential): StoredPluginCredential =
            credential.validated().let { StoredPluginCredential(username = it.username, password = it.password) }

        fun cleared(): StoredPluginCredential = StoredPluginCredential(isCleared = true)
    }
}

@Serializable
private data class PluginPreferenceSnapshot(
    val valueBytes: Map<String, Long> = emptyMap(),
    val legacyTombstones: Set<String> = emptySet(),
) {
    fun withReservation(key: String, bytes: Long): PluginPreferenceSnapshot = copy(
        valueBytes = valueBytes + (key to bytes),
        legacyTombstones = legacyTombstones - key,
    ).validated()

    fun withTombstone(key: String): PluginPreferenceSnapshot = copy(
        valueBytes = valueBytes - key,
        legacyTombstones = legacyTombstones + key,
    ).validated()

    fun withoutValue(key: String): PluginPreferenceSnapshot = copy(
        valueBytes = valueBytes - key,
        legacyTombstones = legacyTombstones - key,
    ).validated()

    fun validated(): PluginPreferenceSnapshot {
        require(valueBytes.keys.intersect(legacyTombstones).isEmpty()) {
            "Plugin preference metadata has conflicting states"
        }
        val allKeys = valueBytes.keys + legacyTombstones
        require(allKeys.size <= MAX_PLUGIN_PREFERENCES_PER_SOURCE) {
            "Plugin preference key quota exceeded"
        }
        var totalBytes = 0L
        allKeys.forEach { key ->
            requirePluginPreferenceShape(key)
            totalBytes += key.encodeToByteArray().size
        }
        valueBytes.values.forEach { bytes ->
            require(bytes in 0..MAX_PLUGIN_PREFERENCE_VALUE_BYTES.toLong()) {
                "Plugin preference byte reservation is invalid"
            }
            totalBytes += bytes
        }
        require(totalBytes <= MAX_PLUGIN_PREFERENCE_TOTAL_BYTES_PER_SOURCE) {
            "Plugin preference byte quota exceeded"
        }
        return copy(valueBytes = valueBytes.toMap(), legacyTombstones = legacyTombstones.toSet())
    }
}

/**
 * Lazily copies the former reviewed BiliManga session into its standalone numeric source scope.
 *
 * Each state kind migrates only when that kind is first requested. In particular, constructing the
 * storage graph and reading cookies never decrypts credentials, so an app launch cannot trigger a
 * keychain prompt merely because the package identity changed. Empty new state is marked after an
 * explicit clear to prevent stale legacy values from being restored on a later request.
 */
public class MigratingPluginStorage(
    private val delegate: PluginStorage,
    private val migrationState: PluginKeyValueStore,
    private val legacySourceId: Long = LEGACY_BILIMANGA_MANGA_STORAGE_ID,
    private val targetSourceId: Long = BILIMANGA_MANGA_SOURCE_ID,
) : PluginStorage, SourceKeyPluginStorage {
    private val mutex = Mutex()

    override fun bindSource(sourceKey: SourceKey, allowLegacyMigration: Boolean): PluginStorage =
        (delegate as? SourceKeyPluginStorage)?.bindSource(sourceKey, allowLegacyMigration)
            ?: throw IllegalArgumentException("Plugin storage does not support SourceKey binding")

    override suspend fun getPreference(sourceId: Long, key: String): String? {
        migratePreferenceIfNeeded(sourceId, key)
        return delegate.getPreference(sourceId, key)
    }

    override suspend fun setPreference(sourceId: Long, key: String, value: String) {
        delegate.setPreference(sourceId, key, value)
        markMigrated(sourceId, preferenceKind(key))
    }

    override suspend fun removePreference(sourceId: Long, key: String) {
        // Mark first: if deletion succeeds but marker persistence does not, a later read could
        // otherwise resurrect the legacy value that this explicit removal was meant to suppress.
        markMigrated(sourceId, preferenceKind(key))
        delegate.removePreference(sourceId, key)
    }

    override suspend fun getCredential(sourceId: Long): PluginCredential? {
        migrateCredentialIfNeeded(sourceId)
        return delegate.getCredential(sourceId)
    }

    override suspend fun setCredential(sourceId: Long, credential: PluginCredential) {
        delegate.setCredential(sourceId, credential)
        markMigrated(sourceId, CREDENTIAL_KIND)
    }

    override suspend fun clearCredential(sourceId: Long) {
        markMigrated(sourceId, CREDENTIAL_KIND)
        delegate.clearCredential(sourceId)
    }

    override suspend fun getCookies(sourceId: Long): List<PluginCookie> {
        migrateCookiesIfNeeded(sourceId)
        return delegate.getCookies(sourceId)
    }

    override suspend fun setCookie(sourceId: Long, cookie: PluginCookie) {
        migrateCookiesIfNeeded(sourceId)
        delegate.setCookie(sourceId, cookie)
    }

    override suspend fun deleteCookie(sourceId: Long, name: String, domain: String) {
        migrateCookiesIfNeeded(sourceId)
        delegate.deleteCookie(sourceId, name, domain)
    }

    override suspend fun deleteCookieExact(sourceId: Long, name: String, domain: String, path: String) {
        migrateCookiesIfNeeded(sourceId)
        delegate.deleteCookieExact(sourceId, name, domain, path)
    }

    override suspend fun clearCookies(sourceId: Long) {
        markMigrated(sourceId, COOKIES_KIND)
        markMigrated(sourceId, USER_AGENT_KIND)
        delegate.clearCookies(sourceId)
    }

    override suspend fun getWebChallengeUserAgent(sourceId: Long): String? {
        migrateUserAgentIfNeeded(sourceId)
        return delegate.getWebChallengeUserAgent(sourceId)
    }

    override suspend fun setWebChallengeUserAgent(sourceId: Long, userAgent: String) {
        delegate.setWebChallengeUserAgent(sourceId, userAgent)
        markMigrated(sourceId, USER_AGENT_KIND)
    }

    override suspend fun clearWebChallengeUserAgent(sourceId: Long) {
        markMigrated(sourceId, USER_AGENT_KIND)
        delegate.clearWebChallengeUserAgent(sourceId)
    }

    private suspend fun migratePreferenceIfNeeded(sourceId: Long, key: String) {
        migrateIfNeeded(sourceId, preferenceKind(key)) {
            val current = delegate.getPreference(targetSourceId, key)
            if (current == null) {
                delegate.getPreference(legacySourceId, key)?.let { delegate.setPreference(targetSourceId, key, it) }
            }
        }
    }

    private suspend fun migrateCredentialIfNeeded(sourceId: Long) {
        migrateIfNeeded(sourceId, CREDENTIAL_KIND) {
            val current = delegate.getCredential(targetSourceId)
            if (current == null) {
                delegate.getCredential(legacySourceId)?.let { delegate.setCredential(targetSourceId, it) }
            }
        }
    }

    private suspend fun migrateCookiesIfNeeded(sourceId: Long) {
        migrateIfNeeded(sourceId, COOKIES_KIND) {
            if (delegate.getCookies(targetSourceId).isEmpty()) {
                delegate.getCookies(legacySourceId).forEach { delegate.setCookie(targetSourceId, it) }
            }
        }
    }

    private suspend fun migrateUserAgentIfNeeded(sourceId: Long) {
        migrateIfNeeded(sourceId, USER_AGENT_KIND) {
            if (delegate.getWebChallengeUserAgent(targetSourceId) == null) {
                delegate.getWebChallengeUserAgent(legacySourceId)
                    ?.let { delegate.setWebChallengeUserAgent(targetSourceId, it) }
            }
        }
    }

    private suspend fun migrateIfNeeded(sourceId: Long, kind: String, block: suspend () -> Unit) {
        if (sourceId != targetSourceId || isMigrated(kind)) return
        mutex.withLock {
            if (isMigrated(kind)) return@withLock
            block()
            migrationState.putString(markerKey(kind), "1")
        }
    }

    private suspend fun markMigrated(sourceId: Long, kind: String) {
        if (sourceId == targetSourceId) mutex.withLock {
            migrationState.putString(markerKey(kind), "1")
        }
    }

    private suspend fun isMigrated(kind: String): Boolean = migrationState.getString(markerKey(kind)) == "1"
    private fun preferenceKind(key: String): String = "preference:$key"
    private fun markerKey(kind: String): String =
        "source.$targetSourceId.migration.bilimanga-reviewed-v1.${kind.hexMarkerComponent()}"

    private companion object {
        const val CREDENTIAL_KIND: String = "credential"
        const val COOKIES_KIND: String = "cookies"
        const val USER_AGENT_KIND: String = "webChallenge.userAgent"
    }
}

/** Keeps migration bookkeeping in ordinary storage even when the migrated state is sensitive. */
private fun String.hexMarkerComponent(): String = buildString(length * 2) {
    encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xff
        append(HEX_MARKER_DIGITS[value ushr 4])
        append(HEX_MARKER_DIGITS[value and 0x0f])
    }
}

private const val HEX_MARKER_DIGITS: String = "0123456789abcdef"

public const val LEGACY_BILIMANGA_MANGA_STORAGE_ID: Long = -9_110_000_000_000_005L
public const val BILIMANGA_MANGA_SOURCE_ID: Long = 7_289_707_411_592_168_382L

internal const val MAX_COOKIES_PER_SOURCE: Int = 500
internal const val MAX_COOKIE_BYTES: Int = 4_096
internal const val MAX_PLUGIN_COOKIE_JAR_ENCODED_BYTES: Int = 512 * 1_024
internal const val MAX_PLUGIN_SOURCE_PACKAGE_ID_BYTES: Int = 256
internal const val MAX_PLUGIN_SOURCE_ID_BYTES: Int = 512
internal const val MAX_PLUGIN_SOURCE_CANONICAL_ID_BYTES: Int = 768
internal const val MAX_PLUGIN_PREFERENCE_KEY_BYTES: Int = 256
internal const val MAX_PLUGIN_PREFERENCE_VALUE_BYTES: Int = 64 * 1_024
internal const val MAX_PLUGIN_PREFERENCES_PER_SOURCE: Int = 256
internal const val MAX_PLUGIN_PREFERENCE_TOTAL_BYTES_PER_SOURCE: Long = 64L * 1_024
internal const val MAX_PLUGIN_CREDENTIAL_USERNAME_BYTES: Int = 4 * 1_024
internal const val MAX_PLUGIN_CREDENTIAL_PASSWORD_BYTES: Int = 16 * 1_024
private const val MAX_PLUGIN_CREDENTIAL_RECORD_ENCODED_BYTES: Int = 128 * 1_024
private const val MAX_PLUGIN_PREFERENCE_SNAPSHOT_ENCODED_BYTES: Int = 256 * 1_024

private val LEGACY_RESERVED_PREFERENCE_NAMES: Set<String> = setOf(
    "credential.username",
    "credential.password",
    "cookies",
    "webchallenge.useragent",
    "v2.owner",
)
private const val LEGACY_PREFERENCE_NAMESPACE: String = "preferences.v2"

/**
 * Exact storage turns identity text into durable physical keys, so validate before constructing
 * the canonical id or its hex representation. The component limits also bound that intermediate
 * allocation; the combined limit prevents individually-valid parts from creating an oversized
 * namespace.
 */
private fun requireExactStorageSourceKey(sourceKey: SourceKey) {
    require(pluginUtf8ByteCountAtMost(sourceKey.packageId, MAX_PLUGIN_SOURCE_PACKAGE_ID_BYTES) != null) {
        "Plugin source package id is too large for exact storage"
    }
    require(pluginUtf8ByteCountAtMost(sourceKey.sourceId, MAX_PLUGIN_SOURCE_ID_BYTES) != null) {
        "Plugin source id is too large for exact storage"
    }
    require(pluginUtf8ByteCountAtMost(sourceKey.canonicalId, MAX_PLUGIN_SOURCE_CANONICAL_ID_BYTES) != null) {
        "Plugin source canonical identity is too large for exact storage"
    }
}

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
        mutex.withLock {
            val token = token(pluginId, versionCode, sha256)
            val tokens = readTokens()
            token in tokens || legacyToken(pluginId, versionCode, sha256) in tokens
        }

    override suspend fun trust(pluginId: String, versionCode: Int, sha256: String): Unit = mutex.withLock {
        writeTokens(readTokens() + token(pluginId, versionCode, sha256))
    }

    override suspend fun revoke(pluginId: String, versionCode: Int, sha256: String): Unit = mutex.withLock {
        val exact = token(pluginId, versionCode, sha256)
        val legacy = legacyToken(pluginId, versionCode, sha256)
        val current = readTokens()
        writeTokens(current - exact - legacy)
    }

    override suspend fun revokeAll(pluginId: String): Unit = mutex.withLock {
        requireTrustIdentity(pluginId, 0, "")
        val exactPrefix = "${pluginId.encodeToByteArray().size}:$pluginId|"
        writeTokens(readTokens().filterNot {
            it.startsWith(exactPrefix) || legacyTokenBelongsToPlugin(it, pluginId)
        }.toSet())
    }

    private suspend fun readTokens(): Set<String> {
        val encoded = keyValueStore.getString(key) ?: return emptySet()
        if (pluginUtf8ByteCountAtMost(encoded, MAX_PLUGIN_TRUST_STORE_BYTES) == null) return emptySet()
        if (!pluginJsonArrayCountsAtMost(encoded, MAX_PLUGIN_TRUST_TOKENS)) return emptySet()
        return runCatching {
            val decoded = json.decodeFromString(ListSerializer(String.serializer()), encoded)
            require(decoded.size <= MAX_PLUGIN_TRUST_TOKENS)
            require(decoded.all { pluginUtf8ByteCountAtMost(it, MAX_PLUGIN_TRUST_TOKEN_BYTES) != null })
            decoded.toSet()
        }
            .getOrDefault(emptySet())
    }

    private suspend fun writeTokens(tokens: Set<String>) {
        check(tokens.size <= MAX_PLUGIN_TRUST_TOKENS) { "Too many durable plugin trust grants" }
        val encoded = json.encodeToString(ListSerializer(String.serializer()), tokens.sorted())
        check(pluginUtf8ByteCountAtMost(encoded, MAX_PLUGIN_TRUST_STORE_BYTES) != null) {
            "Plugin trust store exceeds its durable size limit"
        }
        keyValueStore.putString(key, encoded)
        check(keyValueStore.getString(key) == encoded) { "Could not verify plugin trust-store write" }
    }

    private fun token(pluginId: String, versionCode: Int, sha256: String): String {
        requireTrustIdentity(pluginId, versionCode, sha256)
        return "${pluginId.encodeToByteArray().size}:$pluginId|$versionCode|${sha256.lowercase()}"
    }

    private fun legacyToken(pluginId: String, versionCode: Int, sha256: String): String {
        requireTrustIdentity(pluginId, versionCode, sha256)
        return "$pluginId:$versionCode:${sha256.lowercase()}"
    }

    private fun legacyTokenBelongsToPlugin(token: String, pluginId: String): Boolean {
        val digestSeparator = token.lastIndexOf(':')
        if (digestSeparator <= 0) return false
        val versionSeparator = token.lastIndexOf(':', digestSeparator - 1)
        if (versionSeparator <= 0) return false
        return token.substring(0, versionSeparator) == pluginId &&
            token.substring(versionSeparator + 1, digestSeparator).toIntOrNull() != null &&
            TRUST_SHA256.matches(token.substring(digestSeparator + 1))
    }

    private fun requireTrustIdentity(pluginId: String, versionCode: Int, sha256: String) {
        PluginVerifier.validateSafeFileComponent(pluginId)
        require(pluginUtf8ByteCountAtMost(pluginId, 256) != null) { "Plugin trust package id is too large" }
        require(versionCode >= 0) { "Plugin trust version code must be non-negative" }
        require(sha256.isEmpty() || TRUST_SHA256.matches(sha256.lowercase())) { "Invalid plugin trust digest" }
    }

    private companion object {
        const val MAX_PLUGIN_TRUST_TOKENS: Int = 1_024
        const val MAX_PLUGIN_TRUST_TOKEN_BYTES: Int = 384
        const val MAX_PLUGIN_TRUST_STORE_BYTES: Int = 512 * 1_024
        val TRUST_SHA256: Regex = Regex("^[0-9a-f]{64}$")
    }
}

public val PluginJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = false
}
