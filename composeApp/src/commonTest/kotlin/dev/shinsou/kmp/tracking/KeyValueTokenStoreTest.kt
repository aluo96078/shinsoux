package dev.shinsou.kmp.tracking

import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class KeyValueTokenStoreTest {
    @Test
    fun serializesRoundTripsAndClearsOAuthToken() = runTest {
        val backing = InMemoryPluginKeyValueStore()
        val store = KeyValueTokenStore(backing)
        val logicalKey = trackerTokenKey(2)
        val token = OAuthToken(
            accessToken = "secret-token",
            tokenType = "Bearer",
            refreshToken = "refresh-token",
            expiresAt = 123_456,
            scopes = setOf("read", "write"),
        )

        store.write(logicalKey, token)

        val encoded = backing.getString(persistedTrackerTokenKey(logicalKey))
        assertNotNull(encoded)
        assertTrue(encoded.contains("secret-token"))
        assertEquals(token, store.read(logicalKey))

        store.clear(logicalKey)
        assertNull(store.read(logicalKey))
        assertNull(backing.getString(persistedTrackerTokenKey(logicalKey)))
    }

    @Test
    fun malformedPersistedTokenIsQuarantinedByRemoval() = runTest {
        val backing = InMemoryPluginKeyValueStore()
        val logicalKey = trackerTokenKey(2)
        backing.putString(persistedTrackerTokenKey(logicalKey), "not-json")
        val store = KeyValueTokenStore(backing)

        assertNull(store.read(logicalKey))
        assertNull(backing.getString(persistedTrackerTokenKey(logicalKey)))
    }
}
