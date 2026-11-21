package dev.shinsou.kmp.files

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopAppFileSystemTest {
    @Test
    fun packageRestoresFromRelativeAndUnnormalizedRoot() = runTest {
        val temporary = Files.createTempDirectory(Path.of("."), "shinsou-files-")
        try {
            val relative = temporary.resolve("unused/../content")
            verifyPackageReconstruction { DesktopAppFileSystem(relative) }
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test
    fun packageRestoresThroughDirectoryAlias() = runTest {
        assumeTrue("Windows symlink creation requires host privileges", !System.getProperty("os.name").startsWith("Windows"))
        val temporary = Files.createTempDirectory("shinsou-alias-")
        try {
            val actual = Files.createDirectory(temporary.resolve("actual"))
            val alias = Files.createSymbolicLink(temporary.resolve("alias"), actual)
            verifyPackageReconstruction { DesktopAppFileSystem(alias) }
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test
    fun aStreamedWriteReplacesTheFileOnlyWhenItCompletes() = runTest {
        val temporary = Files.createTempDirectory("shinsou-stream-")
        try {
            val files = DesktopAppFileSystem(temporary)
            files.writeAtomically("backups/a.json", "old".encodeToByteArray())

            val size = files.writeAtomically("backups/a.json") { sink -> sink.writeUtf8("new contents") }

            assertEquals(12L, size)
            assertContentEquals("new contents".encodeToByteArray(), files.read("backups/a.json"))
            assertFailsWith<IllegalStateException> {
                files.writeAtomically("backups/a.json") { sink ->
                    sink.writeUtf8("partial")
                    error("cut short")
                }
            }
            assertContentEquals("new contents".encodeToByteArray(), files.read("backups/a.json"))
            assertEquals(listOf("backups/a.json"), files.list("backups"))
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }
}
