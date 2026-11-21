package dev.shinsou.kmp.translation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The memory guard's decision table (R5-1: from OfflineTranslationDeviceTest in the app, which keeps
 * the check against the device it runs on; the table is this module's internal API).
 */
class AndroidTranslationMemoryGuardTest {
    @Test
    fun theMemoryGuardRefusesSmallOrBusyDevices() {
        val required = OfflineTranslationProvider.MODEL_MEMORY_BYTES
        val gb = 1024L * 1024 * 1024
        fun status(total: Long, available: Long, threshold: Long = 200L * 1024 * 1024, low: Boolean = false) =
            AndroidTranslationMemoryGuard.status(total, available, threshold, low, required)
        assertEquals(TranslationMemoryStatus.DEVICE_TOO_SMALL, status(total = 2 * gb, available = 1 * gb))
        assertEquals(TranslationMemoryStatus.LOW_NOW, status(total = 6 * gb, available = 500L * 1024 * 1024))
        assertEquals(TranslationMemoryStatus.LOW_NOW, status(total = 6 * gb, available = 3 * gb, low = true))
        assertEquals(TranslationMemoryStatus.ENOUGH, status(total = 6 * gb, available = 2 * gb))
    }
}
