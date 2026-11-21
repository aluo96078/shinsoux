package dev.shinsou.kmp.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformSecurityCapabilitiesTest {
    @Test
    fun unavailableCapabilityCannotEnablePersistedSetting() {
        assertFalse(
            securityFeatureEnabled(
                configured = true,
                capability = SecurityFeatureCapability.unavailable("Not implemented."),
            ),
        )
    }

    @Test
    fun availableCapabilityPreservesConfiguredSetting() {
        assertTrue(
            securityFeatureEnabled(
                configured = true,
                capability = SecurityFeatureCapability.Available,
            ),
        )
        assertFalse(
            securityFeatureEnabled(
                configured = false,
                capability = SecurityFeatureCapability.Available,
            ),
        )
    }

    @Test
    fun mobileCapabilityRequiresDeviceOwnerAuthenticationOnlyForAppLock() {
        val capabilities = mobileSecurityCapabilities(
            deviceOwnerAuthenticationAvailable = false,
            unavailableReason = "No device authentication.",
        )

        assertFalse(capabilities.appLock.available)
        assertEquals("No device authentication.", capabilities.appLock.unavailableReason)
        assertTrue(capabilities.secureScreen.available)
    }

    @Test
    fun mobileCapabilityEnablesBothFeaturesWhenAuthenticationIsAvailable() {
        val capabilities = mobileSecurityCapabilities(deviceOwnerAuthenticationAvailable = true)

        assertTrue(capabilities.appLock.available)
        assertTrue(capabilities.secureScreen.available)
    }

    @Test
    fun existingAppLockReengagesOnlyWhenAuthenticationBecomesAvailable() {
        assertTrue(
            shouldLockOnAuthenticationAvailabilityChange(
                appLockConfigured = true,
                wasAvailable = false,
                isAvailable = true,
            ),
        )
        assertFalse(
            shouldLockOnAuthenticationAvailabilityChange(
                appLockConfigured = false,
                wasAvailable = false,
                isAvailable = true,
            ),
        )
        assertFalse(
            shouldLockOnAuthenticationAvailabilityChange(
                appLockConfigured = true,
                wasAvailable = true,
                isAvailable = false,
            ),
        )
    }
}
