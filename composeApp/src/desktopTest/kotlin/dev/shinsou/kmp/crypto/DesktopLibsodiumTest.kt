package dev.shinsou.kmp.crypto

import com.ionspin.kotlin.crypto.hash.Hash
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalUnsignedTypes::class)
class DesktopLibsodiumTest {
    @Test
    fun loadsFromADirectoryWhosePathContainsASpaceAndReusesTheVerifiedCopy() {
        withTemporaryDirectory { root ->
            val directory = root.resolve("Shinsou X").resolve("native")

            DesktopLibsodium.load(directory)
            assertEquals(ABC_SHA256, Hash.sha256("abc".encodeToByteArray().asUByteArray()).asByteArray().toHex())
            val installed = installedLibraries(directory).single()
            val modified = Files.getLastModifiedTime(installed)

            DesktopLibsodium.load(directory)
            assertEquals(listOf(installed), installedLibraries(directory))
            assertEquals(modified, Files.getLastModifiedTime(installed))
        }
    }

    @Test
    fun aCopyThatNoLongerMatchesTheBundledLibraryIsReplacedBeforeLoading() {
        withTemporaryDirectory { root ->
            val directory = root.resolve("native")
            DesktopLibsodium.load(directory)
            val installed = installedLibraries(directory).single()
            val bundled = Files.readAllBytes(installed)
            Files.write(installed, byteArrayOf(0x4d, 0x5a, 0x00))

            DesktopLibsodium.load(directory)

            assertTrue(bundled.contentEquals(Files.readAllBytes(installed)))
            assertEquals(ABC_SHA256, Hash.sha256("abc".encodeToByteArray().asUByteArray()).asByteArray().toHex())
        }
    }

    @Test
    fun copiesLeftByEarlierVersionsAreRemoved() {
        withTemporaryDirectory { root ->
            val directory = Files.createDirectories(root.resolve("native"))
            val stale = Files.write(
                directory.resolve("0000000000000000-${DesktopLibsodium.bundledLibraryName()}"),
                byteArrayOf(1, 2, 3),
            )
            val unrelated = Files.write(directory.resolve("notes.txt"), byteArrayOf(4))

            DesktopLibsodium.load(directory)

            assertTrue(Files.notExists(stale))
            assertTrue(Files.exists(unrelated))
            assertEquals(1, installedLibraries(directory).size)
        }
    }

    private fun installedLibraries(directory: Path): List<Path> = Files.list(directory).use { files ->
        files.filter { it.fileName.toString().endsWith(DesktopLibsodium.bundledLibraryName()) }.toList()
    }

    private fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("shinsou libsodium test")
        try {
            block(directory)
        } finally {
            // Windows keeps a loaded DLL locked until the JVM exits; leave it for the OS temp cleanup.
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder<Path>()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
