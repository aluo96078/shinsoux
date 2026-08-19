package dev.shinsou.kmp.desktop

import java.nio.file.Path

internal object DesktopAppDirectories {
    val dataRoot: Path by lazy { resolveDataRoot(DesktopPlatform.current) }

    val contentRoot: Path by lazy { dataRoot.resolve("Content") }

    internal fun resolveDataRoot(
        platform: DesktopPlatform,
        environment: (String) -> String? = System::getenv,
        property: (String) -> String? = System::getProperty,
    ): Path {
        val userHome = usablePath(property("user.home"))
        val temporaryDirectory = usablePath(property("java.io.tmpdir"))

        return when (platform) {
            DesktopPlatform.MAC_OS -> (userHome ?: temporaryDirectory ?: Path.of("."))
                .resolve("Library")
                .resolve("Application Support")
                .resolve("Shinsou")

            DesktopPlatform.WINDOWS -> (
                usablePath(environment("LOCALAPPDATA"))
                    ?: userHome?.resolve("AppData")?.resolve("Local")
                    ?: temporaryDirectory
                    ?: Path.of(".")
                ).resolve("Shinsou X")

            DesktopPlatform.LINUX,
            DesktopPlatform.OTHER,
            -> (
                usablePath(environment("XDG_DATA_HOME"))
                    ?: userHome?.resolve(".local")?.resolve("share")
                    ?: temporaryDirectory
                    ?: Path.of(".")
                ).resolve("Shinsou X")
        }.normalize().toAbsolutePath()
    }

    private fun usablePath(value: String?): Path? = value
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { Path.of(it) }.getOrNull() }
}
