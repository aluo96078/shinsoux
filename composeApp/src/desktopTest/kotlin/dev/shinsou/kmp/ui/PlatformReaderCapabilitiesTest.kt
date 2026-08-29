package dev.shinsou.kmp.ui

import kotlin.test.Test
import kotlin.test.assertFalse

class PlatformReaderCapabilitiesTest {
    @Test
    fun desktopDoesNotOfferOrEnableVolumeKeyPaging() {
        assertFalse(platformSupportsReaderVolumeKeys)
        assertFalse(shouldShowReaderVolumeKeySetting())
        assertFalse(effectiveReaderVolumeKeysEnabled(configured = true))
    }
}
