package dev.shinsou.kmp.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosAppFileSystemTest {
    @Test
    fun packageRestoresThroughDirectoryAlias() = runTest {
        val manager = NSFileManager.defaultManager
        val temporary = NSTemporaryDirectory() + "shinsou-files-" + NSUUID().UUIDString
        try {
            assertTrue(manager.createDirectoryAtPath("$temporary/actual", true, null, null))
            assertTrue(manager.createSymbolicLinkAtPath("$temporary/alias", "$temporary/actual", null))
            verifyPackageReconstruction { IosAppFileSystem("$temporary/alias") }
        } finally {
            manager.removeItemAtPath(temporary, null)
        }
    }
}
