package dev.shinsou.kmp.plugin.sandbox

import java.io.File
import java.nio.file.Files

/**
 * Locates the `shinsou-sandbox` helper built from `sandbox-core` (or `SHINSOU_SANDBOX_BIN`).
 * Tests that need it skip with a printed reason when it is missing. They drive it through the
 * production [DesktopSandboxTransport].
 *
 * On Windows the helper refuses to run from a directory holding anything but itself (its
 * AppContainers may read that directory), so tests run a private copy, as the app does.
 */
internal fun sandboxHelperBinary(): File? {
    val built = (System.getenv("SHINSOU_SANDBOX_BIN")?.let(::File)
        ?: File("../sandbox-core/build/release/" + if (isWindows()) "shinsou-sandbox.exe" else "shinsou-sandbox"))
        .takeIf { it.isFile && it.canExecute() }
        ?: return null
    if (!isWindows()) return built
    return privateCopy ?: synchronized(SandboxHelperBinaryLock) {
        privateCopy ?: Files.createTempDirectory("shinsou-sandbox-test-").resolve(built.name).toFile().also {
            built.copyTo(it)
            it.parentFile.deleteOnExit()
            it.deleteOnExit()
            privateCopy = it
        }
    }
}

private object SandboxHelperBinaryLock

@Volatile
private var privateCopy: File? = null
