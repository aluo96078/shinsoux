package dev.shinsou.kmp.plugin.sandbox

import java.io.File

/**
 * Locates the `shinsou-sandbox` helper built from `sandbox-core` (or `SHINSOU_SANDBOX_BIN`).
 * Tests that need it skip with a printed reason when it is missing. They drive it through the
 * production [DesktopSandboxTransport].
 */
internal fun sandboxHelperBinary(): File? =
    (System.getenv("SHINSOU_SANDBOX_BIN")?.let(::File)
        ?: File("../sandbox-core/build/release/" + if (isWindows()) "shinsou-sandbox.exe" else "shinsou-sandbox"))
        .takeIf { it.isFile && it.canExecute() }
