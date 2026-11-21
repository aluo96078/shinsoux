package dev.shinsou.kmp.desktop

import dev.shinsou.kmp.plugin.MacOsWebChallengeHelperLocator
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The WKWebView helper this module builds for the packaged app (compileMacOsWebChallengeHelper).
 * R5-1: from DesktopWebChallengeCookieSessionTest (:ui); the helper's build output is this module's.
 */
class PackagedWebChallengeHelperTest {
    @Test
    fun packagedWkWebViewHelperIsPresentAndNative() {
        assumeTrue(
            "The packaged WKWebView helper is a macOS-only artifact",
            System.getProperty("os.name").contains("Mac", ignoreCase = true),
        )
        val helper = MacOsWebChallengeHelperLocator().resolve()

        assertTrue(Files.isRegularFile(helper), "WKWebView helper is missing")
        val magic = Files.newInputStream(helper).use { input ->
            ByteArray(4).also { bytes -> assertEquals(bytes.size, input.read(bytes)) }
        }
        assertTrue(
            magic.contentEquals(byteArrayOf(0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte())),
            "WKWebView helper is not a 64-bit Mach-O executable",
        )
    }
}
