package dev.shinsou.kmp.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationStoredSizeTest {
    @Test
    fun aFewChaptersAreShownInKilobytesNotAsZeroMegabytes() {
        assertEquals("0 KB", storedSize(0))
        assertEquals("1 KB", storedSize(200))
        assertEquals("17 KB", storedSize(17_356))
        assertEquals("1.0 MB", storedSize(1_048_576))
        assertEquals("24.5 MB", storedSize(24L * 1_048_576 + 524_288))
    }
}
