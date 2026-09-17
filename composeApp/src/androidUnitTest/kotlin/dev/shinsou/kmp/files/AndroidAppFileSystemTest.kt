package dev.shinsou.kmp.files

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidAppFileSystemTest {
    @Test
    fun aliasedDataDirectoryListsTheSamePackagePathsAfterReconstruction() = runTest {
        val temporary = Files.createTempDirectory("shinsou-files-test")
        try {
            val actual = Files.createDirectory(temporary.resolve("actual"))
            val alias = Files.createSymbolicLink(temporary.resolve("alias"), actual)
            val path = "plugins/packages/zh.bika/package.json"
            AndroidAppFileSystem(alias.toFile()).write(path, "fixture".encodeToByteArray())
            val reconstructed = AndroidAppFileSystem(alias.toFile())
            assertEquals(listOf(path), reconstructed.list("plugins/packages"))
            assertEquals(listOf(path), reconstructed.list("plugins/packages", 8))
            assertEquals("fixture", reconstructed.read(path)?.decodeToString())
            assertEquals(listOf(path), AndroidAppFileSystem(actual.toFile()).list("plugins/packages", 8))
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }
}
