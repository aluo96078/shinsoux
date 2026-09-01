package dev.shinsou.kmp.plugin

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MigratingPluginStorageTest {
    @Test
    fun constructionAndCookieAccessDoNotReadCredentials() = runTest {
        val delegate = RecordingPluginStorage().apply {
            credentials[LEGACY_BILIMANGA_MANGA_STORAGE_ID] = PluginCredential("member", "secret")
            cookies[LEGACY_BILIMANGA_MANGA_STORAGE_ID] = mutableListOf(cookie("cf_clearance", "legacy"))
        }
        val storage = MigratingPluginStorage(delegate, InMemoryPluginKeyValueStore())

        assertEquals(listOf("cf_clearance"), storage.getCookies(BILIMANGA_MANGA_SOURCE_ID).map { it.name })
        assertEquals(emptyList(), delegate.credentialReads)

        assertEquals(PluginCredential("member", "secret"), storage.getCredential(BILIMANGA_MANGA_SOURCE_ID))
        assertEquals(
            listOf(BILIMANGA_MANGA_SOURCE_ID, LEGACY_BILIMANGA_MANGA_STORAGE_ID, BILIMANGA_MANGA_SOURCE_ID),
            delegate.credentialReads,
        )
    }

    @Test
    fun existingTargetStateWinsAndLegacyValuesRemainUntouched() = runTest {
        val delegate = RecordingPluginStorage().apply {
            credentials[LEGACY_BILIMANGA_MANGA_STORAGE_ID] = PluginCredential("old", "old-secret")
            credentials[BILIMANGA_MANGA_SOURCE_ID] = PluginCredential("new", "new-secret")
            cookies[LEGACY_BILIMANGA_MANGA_STORAGE_ID] = mutableListOf(cookie("session", "old"))
            cookies[BILIMANGA_MANGA_SOURCE_ID] = mutableListOf(cookie("session", "new"))
            preferences[LEGACY_BILIMANGA_MANGA_STORAGE_ID to ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE] = "on"
            preferences[BILIMANGA_MANGA_SOURCE_ID to ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE] = "off"
        }
        val storage = MigratingPluginStorage(delegate, InMemoryPluginKeyValueStore())

        assertEquals(PluginCredential("new", "new-secret"), storage.getCredential(BILIMANGA_MANGA_SOURCE_ID))
        assertEquals("new", storage.getCookies(BILIMANGA_MANGA_SOURCE_ID).single().value)
        assertEquals(
            "off",
            storage.getPreference(BILIMANGA_MANGA_SOURCE_ID, ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE),
        )
        assertEquals(PluginCredential("old", "old-secret"), delegate.credentials[LEGACY_BILIMANGA_MANGA_STORAGE_ID])
    }

    @Test
    fun explicitClearDoesNotResurrectLegacyState() = runTest {
        val delegate = RecordingPluginStorage().apply {
            credentials[LEGACY_BILIMANGA_MANGA_STORAGE_ID] = PluginCredential("member", "secret")
            cookies[LEGACY_BILIMANGA_MANGA_STORAGE_ID] = mutableListOf(cookie("session", "legacy"))
            userAgents[LEGACY_BILIMANGA_MANGA_STORAGE_ID] = "Legacy Browser"
        }
        val markers = InMemoryPluginKeyValueStore()
        val storage = MigratingPluginStorage(delegate, markers)

        storage.clearCredential(BILIMANGA_MANGA_SOURCE_ID)
        storage.clearCookies(BILIMANGA_MANGA_SOURCE_ID)

        assertNull(storage.getCredential(BILIMANGA_MANGA_SOURCE_ID))
        assertEquals(emptyList(), storage.getCookies(BILIMANGA_MANGA_SOURCE_ID))
        assertNull(storage.getWebChallengeUserAgent(BILIMANGA_MANGA_SOURCE_ID))

        val reconstructed = MigratingPluginStorage(delegate, markers)
        assertNull(reconstructed.getCredential(BILIMANGA_MANGA_SOURCE_ID))
        assertEquals(emptyList(), reconstructed.getCookies(BILIMANGA_MANGA_SOURCE_ID))
        assertNull(reconstructed.getWebChallengeUserAgent(BILIMANGA_MANGA_SOURCE_ID))
    }

    @Test
    fun proxyAndBrowserUserAgentMigrateOnTheirOwnFirstAccess() = runTest {
        val delegate = RecordingPluginStorage().apply {
            preferences[LEGACY_BILIMANGA_MANGA_STORAGE_ID to ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE] = "on"
            userAgents[LEGACY_BILIMANGA_MANGA_STORAGE_ID] = "Legacy Browser"
        }
        val storage = MigratingPluginStorage(delegate, InMemoryPluginKeyValueStore())

        assertEquals(
            "on",
            storage.getPreference(BILIMANGA_MANGA_SOURCE_ID, ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE),
        )
        assertEquals("Legacy Browser", storage.getWebChallengeUserAgent(BILIMANGA_MANGA_SOURCE_ID))
    }

    @Test
    fun persistentMigrationMarkersNeverEnterSensitiveStorage() = runTest {
        val delegate = RecordingPluginStorage().apply {
            credentials[LEGACY_BILIMANGA_MANGA_STORAGE_ID] = PluginCredential("member", "secret")
            cookies[LEGACY_BILIMANGA_MANGA_STORAGE_ID] = mutableListOf(cookie("session", "legacy"))
            preferences[
                LEGACY_BILIMANGA_MANGA_STORAGE_ID to ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE
            ] = "on"
            userAgents[LEGACY_BILIMANGA_MANGA_STORAGE_ID] = "Legacy Browser"
        }
        val markers = RecordingPluginKeyValueStore()
        val storage = MigratingPluginStorage(delegate, markers)

        storage.getCredential(BILIMANGA_MANGA_SOURCE_ID)
        storage.getCookies(BILIMANGA_MANGA_SOURCE_ID)
        storage.getPreference(BILIMANGA_MANGA_SOURCE_ID, ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE)
        storage.getWebChallengeUserAgent(BILIMANGA_MANGA_SOURCE_ID)

        assertFalse(markers.accessedKeys.isEmpty())
        assertEquals(emptyList(), markers.accessedKeys.filter(::isSensitivePluginKey))
    }

    private fun cookie(name: String, value: String): PluginCookie = PluginCookie(
        name = name,
        value = value,
        domain = "www.bilimanga.net",
        secure = true,
    )
}

private class RecordingPluginKeyValueStore : PluginKeyValueStore {
    private val values = mutableMapOf<String, String>()
    val accessedKeys = mutableListOf<String>()

    override suspend fun getString(key: String): String? {
        accessedKeys += key
        return values[key]
    }

    override suspend fun putString(key: String, value: String) {
        accessedKeys += key
        values[key] = value
    }

    override suspend fun remove(key: String) {
        accessedKeys += key
        values.remove(key)
    }
}

private class RecordingPluginStorage : PluginStorage {
    val preferences = mutableMapOf<Pair<Long, String>, String>()
    val credentials = mutableMapOf<Long, PluginCredential>()
    val cookies = mutableMapOf<Long, MutableList<PluginCookie>>()
    val userAgents = mutableMapOf<Long, String>()
    val credentialReads = mutableListOf<Long>()

    override suspend fun getPreference(sourceId: Long, key: String): String? = preferences[sourceId to key]
    override suspend fun setPreference(sourceId: Long, key: String, value: String) {
        preferences[sourceId to key] = value
    }

    override suspend fun getCredential(sourceId: Long): PluginCredential? {
        credentialReads += sourceId
        return credentials[sourceId]
    }
    override suspend fun setCredential(sourceId: Long, credential: PluginCredential) {
        credentials[sourceId] = credential
    }
    override suspend fun clearCredential(sourceId: Long) {
        credentials.remove(sourceId)
    }

    override suspend fun getCookies(sourceId: Long): List<PluginCookie> = cookies[sourceId].orEmpty().toList()
    override suspend fun setCookie(sourceId: Long, cookie: PluginCookie) {
        val stored = cookies.getOrPut(sourceId) { mutableListOf() }
        stored.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
        stored += cookie
    }
    override suspend fun deleteCookie(sourceId: Long, name: String, domain: String) {
        cookies[sourceId]?.removeAll { it.name == name && it.domain == domain }
    }
    override suspend fun clearCookies(sourceId: Long) {
        cookies.remove(sourceId)
        userAgents.remove(sourceId)
    }

    override suspend fun getWebChallengeUserAgent(sourceId: Long): String? = userAgents[sourceId]
    override suspend fun setWebChallengeUserAgent(sourceId: Long, userAgent: String) {
        userAgents[sourceId] = userAgent
    }
    override suspend fun clearWebChallengeUserAgent(sourceId: Long) {
        userAgents.remove(sourceId)
    }
}
