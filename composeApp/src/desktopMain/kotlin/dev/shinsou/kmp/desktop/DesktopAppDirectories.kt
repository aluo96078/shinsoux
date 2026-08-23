package dev.shinsou.kmp.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

internal object DesktopAppDirectories {
    private const val WINDOWS_DATA_DIRECTORY = "ShinsouXData"
    private const val LEGACY_WINDOWS_DATA_DIRECTORY = "Shinsou X"

    /**
     * Keep Windows data outside both AppData trees. jpackage's per-user MSI uninstaller has
     * removed user-profile application-data trees on some runner images, so neither LocalAppData
     * nor Roaming AppData is a safe persistence boundary for the library and secrets. Existing
     * AppData locations are retained as one-time migration sources below.
     */
    val dataRoot: Path by lazy {
        val platform = DesktopPlatform.current
        resolveDataRoot(platform).also { root ->
            if (platform == DesktopPlatform.WINDOWS) {
                migrateLegacyWindowsData(root, resolveLegacyRoamingWindowsDataRoot())
                migrateLegacyWindowsData(root, resolveLegacyWindowsDataRoot())
            }
        }
    }

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
                usablePath(environment("USERPROFILE"))
                    ?: userHome
                    ?: temporaryDirectory
                    ?: Path.of(".")
                ).resolve(WINDOWS_DATA_DIRECTORY)

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

    internal fun resolveLegacyWindowsDataRoot(
        environment: (String) -> String? = System::getenv,
        property: (String) -> String? = System::getProperty,
    ): Path {
        val userHome = usablePath(property("user.home"))
        val temporaryDirectory = usablePath(property("java.io.tmpdir"))
        return (
            usablePath(environment("LOCALAPPDATA"))
                ?: userHome?.resolve("AppData")?.resolve("Local")
                ?: temporaryDirectory
                ?: Path.of(".")
            ).resolve(LEGACY_WINDOWS_DATA_DIRECTORY).normalize().toAbsolutePath()
    }

    internal fun resolveLegacyRoamingWindowsDataRoot(
        environment: (String) -> String? = System::getenv,
        property: (String) -> String? = System::getProperty,
    ): Path {
        val userHome = usablePath(property("user.home"))
        val temporaryDirectory = usablePath(property("java.io.tmpdir"))
        return (
            usablePath(environment("APPDATA"))
                ?: userHome?.resolve("AppData")?.resolve("Roaming")
                ?: temporaryDirectory
                ?: Path.of(".")
            ).resolve(WINDOWS_DATA_DIRECTORY).normalize().toAbsolutePath()
    }

    /**
     * Move data created by versions that used `%APPDATA%\\ShinsouXData` or
     * `%LOCALAPPDATA%\\Shinsou X` before the Windows installer cleanup behavior was diagnosed.
     * If the new directory already exists, leave the legacy directory untouched so a failed or
     * partial migration cannot destroy user data.
     */
    internal fun migrateLegacyWindowsData(dataRoot: Path) {
        val legacyRoot = dataRoot.toAbsolutePath().normalize().parent
            ?.resolve(LEGACY_WINDOWS_DATA_DIRECTORY)
            ?: return
        migrateLegacyWindowsData(dataRoot, legacyRoot)
    }

    internal fun migrateLegacyWindowsData(dataRoot: Path, legacyRoot: Path) {
        val normalizedRoot = dataRoot.toAbsolutePath().normalize()
        val normalizedLegacyRoot = legacyRoot.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalizedLegacyRoot) || Files.exists(normalizedRoot)) return

        runCatching {
            Files.createDirectories(requireNotNull(normalizedRoot.parent))
            Files.move(normalizedLegacyRoot, normalizedRoot, StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching {
            // ATOMIC_MOVE is not available across all Windows filesystems. A recursive copy is
            // still safe because the source remains in place until every file has been copied.
            copyDirectoryWithoutOverwriting(normalizedLegacyRoot, normalizedRoot)
            deleteEmptyDirectoryTree(normalizedLegacyRoot)
        }
    }

    private fun copyDirectoryWithoutOverwriting(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val relative = source.relativize(path)
                val destination = target.resolve(relative)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else if (!Files.exists(destination)) {
                    Files.copy(path, destination)
                }
            }
        }
    }

    private fun deleteEmptyDirectoryTree(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                if (Files.isDirectory(path)) {
                    runCatching { Files.delete(path) }
                }
            }
        }
    }

    private fun usablePath(value: String?): Path? = value
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { Path.of(it) }.getOrNull() }
}
