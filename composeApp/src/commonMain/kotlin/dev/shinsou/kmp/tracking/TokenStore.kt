package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.plugin.PluginJson
import dev.shinsou.kmp.plugin.PluginKeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class OAuthToken(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val scopes: Set<String> = emptySet(),
) {
    init {
        require(accessToken.isNotBlank()) { "OAuth access token cannot be blank" }
    }

    fun isExpired(nowMillis: Long, leewayMillis: Long = 30_000): Boolean =
        expiresAt?.let { nowMillis + leewayMillis >= it } ?: false
}

interface TokenStore {
    suspend fun read(key: String): OAuthToken?
    suspend fun write(key: String, token: OAuthToken)
    suspend fun clear(key: String)
}

class InMemoryTokenStore(initial: Map<String, OAuthToken> = emptyMap()) : TokenStore {
    private val mutex = Mutex()
    private val tokens = initial.toMutableMap()

    override suspend fun read(key: String): OAuthToken? = mutex.withLock { tokens[key] }

    override suspend fun write(key: String, token: OAuthToken) {
        mutex.withLock { tokens[key] = token }
    }

    override suspend fun clear(key: String) {
        mutex.withLock { tokens.remove(key) }
    }
}

/**
 * Persists OAuth tokens through the same platform store used for extension secrets.
 *
 * The physical key deliberately contains `.token.` so Android/Desktop route the JSON payload
 * through their AES-GCM path and iOS routes it to Keychain. A malformed legacy value is removed
 * instead of repeatedly surfacing a broken authenticated state.
 */
class KeyValueTokenStore(
    private val keyValueStore: PluginKeyValueStore,
    private val json: Json = PluginJson,
) : TokenStore {
    private val mutex = Mutex()

    override suspend fun read(key: String): OAuthToken? = mutex.withLock {
        val physicalKey = persistedTrackerTokenKey(key)
        val encoded = keyValueStore.getString(physicalKey) ?: return@withLock null
        runCatching { json.decodeFromString(OAuthToken.serializer(), encoded) }
            .getOrElse {
                keyValueStore.remove(physicalKey)
                null
            }
    }

    override suspend fun write(key: String, token: OAuthToken): Unit = mutex.withLock {
        keyValueStore.putString(
            persistedTrackerTokenKey(key),
            json.encodeToString(OAuthToken.serializer(), token),
        )
    }

    override suspend fun clear(key: String): Unit = mutex.withLock {
        keyValueStore.remove(persistedTrackerTokenKey(key))
    }
}

fun trackerTokenKey(trackerId: Int): String = "tracker.oauth.$trackerId"

/** Visible for platform-independent persistence regression tests. */
fun persistedTrackerTokenKey(logicalKey: String): String = "tracking.token.$logicalKey"
