package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.ui.BrowseSource
import dev.shinsou.kmp.ui.SourceCredential
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceSettingsSectionsTest {
    @Test
    fun staleCredentialDoesNotExposeLoginControlsWithoutLoginCapability() {
        val source = BrowseSource(
            id = 1L,
            name = "No Login",
            language = "all",
            supportsLogin = false,
            credential = SourceCredential("stale-user", "stale-password"),
        )

        assertFalse(sourceSettingsSections(source).credentials)
    }

    @Test
    fun loginCapabilityExposesCredentialControlsBeforeCredentialsExist() {
        val source = BrowseSource(
            id = 2L,
            name = "Login Source",
            language = "all",
            supportsLogin = true,
        )

        assertTrue(sourceSettingsSections(source).credentials)
    }
}
