package dev.shinsou.kmp.ui

import dev.shinsou.kmp.reader.ReaderTapAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
