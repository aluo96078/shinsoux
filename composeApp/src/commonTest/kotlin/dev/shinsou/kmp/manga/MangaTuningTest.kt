package dev.shinsou.kmp.manga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangaTuningTest {
    @Test
    fun theDeviceIsSharedByDetectionAndSeveralReaders() {
        // measured best on both: two detection threads and three readers of two threads each
        val mac = MangaTuning.of(MangaDeviceProfile(8, 4, 24_000))
        assertEquals(MangaTuning(detectorThreads = 2, ocrWorkers = 3, ocrThreads = 2, inpaintThreads = 8, pageLoadsAhead = 6), mac)
        val phone = MangaTuning.of(MangaDeviceProfile(6, 2, 12_000))
        assertEquals(3, phone.ocrWorkers); assertEquals(2, phone.detectorThreads); assertEquals(6, phone.inpaintThreads)
        // an iPhone 17 Pro Max: 2 performance and 4 efficiency cores, which help here
        val iphone = MangaTuning.of(MangaDeviceProfile(2, 4, 12_000))
        assertEquals(3, iphone.ocrWorkers); assertEquals(2, iphone.detectorThreads); assertEquals(6, iphone.inpaintThreads)
        assertEquals(2, MangaTuning.of(MangaDeviceProfile(2, 2, 8_000)).ocrWorkers)
        val single = MangaTuning.of(MangaDeviceProfile(2, 0, 8_000))
        assertEquals(1, single.ocrWorkers); assertEquals(1, single.ocrThreads); assertEquals(1, single.detectorThreads)
        for (cores in 1..16) for (memory in listOf(2_000L, 5_000L, 16_000L)) {
            val t = MangaTuning.of(MangaDeviceProfile(cores, 0, memory))
            assertTrue(t.ocrWorkers in 1..3 && t.detectorThreads >= 1 && t.ocrThreads >= 1, "$cores cores: $t")
        }
    }

    @Test
    fun littleMemoryOrLowPowerDoLess() {
        assertEquals(1, MangaTuning.of(MangaDeviceProfile(8, 0, 3_000)).ocrWorkers)
        assertEquals(2, MangaTuning.of(MangaDeviceProfile(8, 0, 2_500)).pageLoadsAhead)
        val saving = MangaTuning.of(MangaDeviceProfile(8, 4, 24_000, constrained = true))
        assertEquals(2, saving.ocrWorkers); assertTrue(saving.inpaintThreads <= 4)
        assertEquals(1, MangaTuning.of(MangaDeviceProfile(6, 2, 12_000, constrained = true)).ocrWorkers)
    }

    @Test
    fun bigCoresAreThoseNearTheFastest() {
        // SM-S9280: 1 at 3.39 GHz, 3 at 3.15, 2 at 2.96, 2 at 2.27
        assertEquals(6, bigCores(listOf(2265600, 2265600, 2956800, 2956800, 3148800, 3148800, 3148800, 3398400)))
        assertEquals(4, bigCores(listOf(1800000, 1800000, 1800000, 1800000, 2400000, 2400000, 2400000, 2400000)))
        assertNull(bigCores(emptyList()))
    }
}
