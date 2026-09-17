package dev.shinsou.kmp.files

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test

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
}
