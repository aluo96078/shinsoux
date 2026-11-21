package dev.shinsou.kmp.desktop

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DesktopShellStateStoreTest {
    @Test
    fun theStateWrittenIsReadBackByTheNextLaunch() {
        val directory = Files.createTempDirectory("shell-state")
        try {
            val file = directory.resolve("nested").resolve("shell-state.txt")
            assertNull(DesktopShellStateStore(file).read())
            DesktopShellStateStore(file).write("l2|sLIBRARY")
            DesktopShellStateStore(file).write("l2|sBROWSE")
            assertEquals("l2|sBROWSE", DesktopShellStateStore(file).read())
            assertFalse(file.resolveSibling("shell-state.txt.partial").exists())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
