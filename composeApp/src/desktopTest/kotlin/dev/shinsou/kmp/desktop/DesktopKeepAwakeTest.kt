package dev.shinsou.kmp.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopKeepAwakeTest {
    @Test
    fun macOsActivityPreventsIdleSleepUntilReleased() {
        if (DesktopPlatform.current != DesktopPlatform.MAC_OS) return
        val reason = "Shinsou keep-awake test ${ProcessHandle.current().pid()}"
        val keepAwake = DesktopKeepAwake.forPlatform(DesktopPlatform.MAC_OS)
        try {
            keepAwake.hold(reason)
            keepAwake.hold(reason) // a second hold is a no-op, not a second assertion
            assertTrue(assertions().count { reason in it } == 1, "the activity should hold one idle-sleep assertion")
        } finally {
            keepAwake.release()
        }
        assertFalse(assertions().any { reason in it }, "releasing should end the activity")
        keepAwake.release() // releasing twice is harmless
        // Repeated download cycles keep working and leave nothing behind.
        repeat(50) {
            keepAwake.hold(reason)
            keepAwake.release()
        }
        assertFalse(assertions().any { reason in it }, "no activity should outlive its cycle")
    }

    private fun assertions(): List<String> {
        val process = ProcessBuilder("pmset", "-g", "assertions").redirectErrorStream(true).start()
        return process.inputStream.bufferedReader().readLines().also { process.waitFor() }
    }
}
