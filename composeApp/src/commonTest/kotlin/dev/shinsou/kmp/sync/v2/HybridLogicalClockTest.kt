package dev.shinsou.kmp.sync.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HybridLogicalClockTest {
    @Test
    fun tickSurvivesWallClockRollbackAndCounterOverflow() {
        val clock = HybridLogicalClock("device-a", HlcTimestamp(100, 2, "device-a")) { 90 }

        assertEquals(HlcTimestamp(100, 3, "device-a"), clock.tick())

        val overflow = HybridLogicalClock(
            "device-a",
            HlcTimestamp(100, Int.MAX_VALUE, "device-a"),
        ) { 99 }
        assertEquals(HlcTimestamp(101, 0, "device-a"), overflow.tick())
    }

    @Test
    fun observeUsesBothLogicalCountersAndThenLocalDeviceIdentity() {
        val clock = HybridLogicalClock("device-b", HlcTimestamp(10, 4, "device-b")) { 8 }

        assertEquals(
            HlcTimestamp(10, 8, "device-b"),
            clock.observe(HlcTimestamp(10, 7, "device-a")),
        )
        assertEquals(
            HlcTimestamp(20, 3, "device-b"),
            clock.observe(HlcTimestamp(20, 2, "device-a"), wallMillis = 15),
        )
    }

    @Test
    fun lwwOrderingHasDeterministicDeviceTieBreak() {
        val a = HlcTimestamp(5, 1, "a")
        val b = HlcTimestamp(5, 1, "b")

        assertTrue(b > a)
        assertEquals("remote", LwwRegister("local", a).merge(LwwRegister("remote", b)).value)
        assertFailsWith<SyncInvariantViolation> {
            LwwRegister("one", a).merge(LwwRegister("different", a))
        }
    }
}
