package dev.shinsou.kmp.plugin

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The macOS WKWebView helper process (DesktopWebHelperProcess.kt): its bounded IPC reader, launch
 * command and locator. R5-1: from the challenge dialog's DesktopWebChallengeCookieSessionTest (:ui),
 * which keeps the dialog's own launch protocol.
 */
class DesktopWebHelperProcessTest {
    @Test
    fun boundedIpcReaderAcceptsNormalLfAndCrLfFrames() {
        val reader = BoundedUtf8LineReader(ByteArrayInputStream("first\nsecond\r\n".encodeToByteArray()))

        assertEquals("first", reader.readLine(16))
        assertEquals("second", reader.readLine(16))
        assertNull(reader.readLine(16))
    }

    @Test
    fun boundedIpcReaderRejectsOverlongFrameBeforeNewline() {
        val reader = BoundedUtf8LineReader(ByteArrayInputStream("12345".encodeToByteArray()))

        assertFailsWith<IOException> { reader.readLine(4) }
    }

    @Test
    fun boundedIpcReaderRejectsPartialEofAndInvalidUtf8() {
        assertFailsWith<EOFException> {
            BoundedUtf8LineReader(ByteArrayInputStream("partial".encodeToByteArray())).readLine(16)
        }
        assertFailsWith<IOException> {
            BoundedUtf8LineReader(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28, 0x0a))).readLine(16)
        }
    }

    @Test
    fun helperLaunchCommandNeverContainsSecretsOrRequestMetadata() {
        val helper = Path.of("/private/tmp/shinsou-web-challenge")
        val command = webChallengeProcessCommand(helper)
        val rendered = command.joinToString(" ")

        assertEquals(listOf(helper.toAbsolutePath().toString()), command)
        assertFalse(rendered.contains("bilimanga"))
        assertFalse(rendered.contains("cookie"))
        assertFalse(rendered.contains("password"))
        assertFalse(rendered.contains("http"))
    }

    @Test
    fun locatorPrefersPackagedHelperAndMakesPrivateExecutableCopy() {
        val root = Files.createTempDirectory("shinsou-helper-test-")
        val packaged = root.resolve("resources")
        val fallback = root.resolve("fallback-helper")
        Files.createDirectories(packaged)
        Files.write(packaged.resolve("shinsou-web-challenge"), byteArrayOf(1, 2, 3))
        Files.write(fallback, byteArrayOf(9, 8, 7))

        try {
            val locator = MacOsWebChallengeHelperLocator(
                osName = "Mac OS X",
                resourcesDirectory = packaged.toString(),
                developmentCandidates = listOf(fallback),
            )
            assertEquals(packaged.resolve("shinsou-web-challenge"), locator.resolve())

            val copied = locator.prepareExecutableCopy()
            try {
                assertTrue(Files.isExecutable(copied))
                assertTrue(Files.readAllBytes(copied).contentEquals(byteArrayOf(1, 2, 3)))
                assertFalse(copied.startsWith(root), "Runtime copy must not modify packaged resources")
            } finally {
                Files.deleteIfExists(copied)
                Files.deleteIfExists(copied.parent)
            }
        } finally {
            Files.deleteIfExists(packaged.resolve("shinsou-web-challenge"))
            Files.deleteIfExists(packaged)
            Files.deleteIfExists(fallback)
            Files.deleteIfExists(root)
        }
    }
}
