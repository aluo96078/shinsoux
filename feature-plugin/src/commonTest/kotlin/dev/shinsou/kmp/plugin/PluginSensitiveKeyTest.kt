package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginSensitiveKeyTest {
    @Test
    fun sourceCookiesAndAuthenticationValuesUseProtectedStorage() {
        assertTrue(isSensitivePluginKey("source.42.cookies"))
        assertTrue(isSensitivePluginKey("source.42.credential.password"))
        assertTrue(isSensitivePluginKey("tracker.oauth.token"))
        assertTrue(isSensitivePluginKey("source.secret"))
    }

    @Test
    fun ordinarySourcePreferencesRemainPlain() {
        assertFalse(isSensitivePluginKey("source.42.imageQuality"))
        assertFalse(isSensitivePluginKey("repository.manifest"))
    }
}
