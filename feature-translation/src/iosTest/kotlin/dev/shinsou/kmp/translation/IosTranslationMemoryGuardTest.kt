package dev.shinsou.kmp.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosTranslationMemoryGuardTest {
    private val required = OfflineTranslationProvider.MODEL_MEMORY_BYTES
    private val gb = 1024L * 1024 * 1024

    @Test
    fun smallDevicesAreRefusedAndBusyOnesAreAskedToWait() {
        assertEquals(TranslationMemoryStatus.DEVICE_TOO_SMALL, IosTranslationMemoryGuard.status(2 * gb, 1 * gb, required))
        assertEquals(TranslationMemoryStatus.LOW_NOW, IosTranslationMemoryGuard.status(4 * gb, 300L * 1024 * 1024, required))
        assertEquals(TranslationMemoryStatus.ENOUGH, IosTranslationMemoryGuard.status(4 * gb, 1 * gb, required))
        // Where the system reports nothing (the simulator), the device's size decides.
        assertEquals(TranslationMemoryStatus.ENOUGH, IosTranslationMemoryGuard.status(8 * gb, 0, required))
    }

    @Test
    fun theGuardAnswersOnThisDevice() {
        assertTrue(IosTranslationMemoryGuard().status(required) in TranslationMemoryStatus.entries)
    }
}
