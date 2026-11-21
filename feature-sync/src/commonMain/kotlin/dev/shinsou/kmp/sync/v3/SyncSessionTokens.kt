package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.files.KeyValueStore
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.KEY_ACCESS_TOKEN
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.KEY_REFRESH_TOKEN
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_ACCESS_EXPIRES
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_SERVER_URL
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_SIGNED_IN
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.TOKEN_REFRESH_MARGIN_MILLIS
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one owner of the session's tokens: the cached access token, its expiry and the refresh
 * token in [keyValues]. [onRefreshed] runs after a refresh (the event stream follows the new
 * token); [onExpired] after the server rejected the refresh token.
 */
internal class SyncSessionTokens(
    context: SyncV3Context,
    private val keyValues: KeyValueStore,
    private val onRefreshed: () -> Unit,
    private val onExpired: () -> Unit,
) {
    private val store = context.store
    private val api = context.api
    private val nowMillis = context.nowMillis
    private val authMutex = Mutex()
    private var accessToken: String? = null
    private var accessExpiresAt: Long = 0

    /** The cached access token, possibly expired; empty when there is none. */
    val cachedAccessToken: String get() = accessToken.orEmpty()

    suspend fun saveTokens(access: String, expiresAt: Long, refresh: String) {
        // The refresh token rotates on every use: persist it before anything else can fail.
        keyValues.putString(KEY_REFRESH_TOKEN, refresh)
        keyValues.putString(KEY_ACCESS_TOKEN, access)
        store.setMeta(META_ACCESS_EXPIRES, expiresAt.toString())
        accessToken = access
        accessExpiresAt = expiresAt
    }

    /** Signing out: both tokens are forgotten here and on disk. */
    suspend fun forgetTokens() {
        keyValues.remove(KEY_ACCESS_TOKEN)
        keyValues.remove(KEY_REFRESH_TOKEN)
        accessToken = null
    }

    suspend fun accessTokenOrRefresh(): String = authMutex.withLock {
        val cached = accessToken ?: keyValues.getString(KEY_ACCESS_TOKEN)?.also {
            accessToken = it
            accessExpiresAt = store.meta(META_ACCESS_EXPIRES)?.toLongOrNull() ?: 0L
        }
        if (cached != null && accessExpiresAt - TOKEN_REFRESH_MARGIN_MILLIS > nowMillis()) cached else refreshLocked()
    }

    private suspend fun refreshLocked(): String {
        val url = store.meta(META_SERVER_URL) ?: throw SyncApiException(401, "unauthorized", "Not signed in")
        val refreshToken = keyValues.getString(KEY_REFRESH_TOKEN)
        if (refreshToken == null) {
            expireSession()
            throw SyncApiException(401, "unauthorized", "Signed out")
        }
        val tokens = try {
            api.refresh(url, refreshToken)
        } catch (error: SyncApiException) {
            if (error.status == 401 || error.code == "refresh_token_reused") expireSession()
            throw error
        }
        saveTokens(tokens.accessToken, tokens.accessTokenExpiresAt, tokens.refreshToken)
        // The stream authenticates once when it opens; reconnect so it follows the new token.
        onRefreshed()
        return tokens.accessToken
    }

    suspend fun forceRefresh(rejectedToken: String): String = authMutex.withLock {
        // Another caller may already have refreshed while this one waited for the lock.
        val current = accessToken
        if (current != null && current != rejectedToken && accessExpiresAt > nowMillis()) current else refreshLocked()
    }

    suspend fun <T> authed(block: suspend (String) -> T): T {
        val token = accessTokenOrRefresh()
        return try {
            block(token)
        } catch (error: SyncApiException) {
            if (!error.isUnauthorized) throw error
            block(forceRefresh(token))
        }
    }

    /** The refresh token is dead (reused or revoked): keep local data, ask for a new sign-in. */
    private suspend fun expireSession() {
        keyValues.remove(KEY_REFRESH_TOKEN)
        keyValues.remove(KEY_ACCESS_TOKEN)
        accessToken = null
        store.setMeta(META_SIGNED_IN, "0")
        onExpired()
    }
}
