package dev.shinsou.kmp.ui

import dev.shinsou.kmp.reader.ReaderTapAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderVolumeKeyPolicyTest {
    @Test
    fun volumeButtonsMapToReaderPageDirection() {
        assertEquals(
            ReaderTapAction.PREVIOUS_PAGE,
            readerVolumeKeyAction(ReaderVolumeKeyEvent.VOLUME_UP, readerOpen = true, volumeKeysEnabled = true),
        )
        assertEquals(
            ReaderTapAction.NEXT_PAGE,
            readerVolumeKeyAction(ReaderVolumeKeyEvent.VOLUME_DOWN, readerOpen = true, volumeKeysEnabled = true),
        )
    }

    @Test
    fun volumeButtonsAreIgnoredOutsideAnEnabledReader() {
        ReaderVolumeKeyEvent.entries.forEach { event ->
            assertNull(readerVolumeKeyAction(event, readerOpen = false, volumeKeysEnabled = true))
            assertNull(readerVolumeKeyAction(event, readerOpen = true, volumeKeysEnabled = false))
            assertNull(readerVolumeKeyAction(event, readerOpen = false, volumeKeysEnabled = false))
        }
    }

    @Test
    fun platformCapabilityControlsSettingVisibilityAndActivation() {
        assertTrue(shouldShowReaderVolumeKeySetting(platformSupported = true))
        assertFalse(shouldShowReaderVolumeKeySetting(platformSupported = false))
        assertTrue(effectiveReaderVolumeKeysEnabled(configured = true, platformSupported = true))
        assertFalse(effectiveReaderVolumeKeysEnabled(configured = false, platformSupported = true))
        assertFalse(effectiveReaderVolumeKeysEnabled(configured = true, platformSupported = false))
    }

    @Test
    fun monitoringRequiresAnOpenReaderAndAnEnabledSupportedPlatform() {
        assertTrue(
            effectiveReaderVolumeKeyMonitoringEnabled(
                readerOpen = true,
                configured = true,
                platformSupported = true,
            ),
        )
        assertFalse(
            effectiveReaderVolumeKeyMonitoringEnabled(
                readerOpen = false,
                configured = true,
                platformSupported = true,
            ),
        )
        assertFalse(
            effectiveReaderVolumeKeyMonitoringEnabled(
                readerOpen = true,
                configured = false,
                platformSupported = true,
            ),
        )
        assertFalse(
            effectiveReaderVolumeKeyMonitoringEnabled(
                readerOpen = true,
                configured = true,
                platformSupported = false,
            ),
        )
    }
}
