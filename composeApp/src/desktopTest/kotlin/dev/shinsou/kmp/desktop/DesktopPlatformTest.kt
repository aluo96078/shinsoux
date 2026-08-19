package dev.shinsou.kmp.desktop

import androidx.compose.ui.input.key.Key
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlatformTest {
    @Test
    fun operatingSystemDetectionKeepsDarwinOutOfWindowsBranch() {
        assertEquals(DesktopPlatform.MAC_OS, DesktopPlatform.fromOsName("Mac OS X"))
        assertEquals(DesktopPlatform.MAC_OS, DesktopPlatform.fromOsName("Darwin"))
        assertEquals(DesktopPlatform.WINDOWS, DesktopPlatform.fromOsName("Windows 11"))
        assertEquals(DesktopPlatform.LINUX, DesktopPlatform.fromOsName("Linux"))
        assertEquals(DesktopPlatform.OTHER, DesktopPlatform.fromOsName("FreeBSD"))
    }

    @Test
    fun primaryShortcutUsesCommandOnMacAndControlOnWindows() {
        assertTrue(DesktopPlatform.MAC_OS.usesCommandShortcuts)
        assertFalse(DesktopPlatform.MAC_OS.usesControlShortcuts)

        assertFalse(DesktopPlatform.WINDOWS.usesCommandShortcuts)
        assertTrue(DesktopPlatform.WINDOWS.usesControlShortcuts)

        // Construct both shortcuts as an integration check against Compose's desktop API.
        DesktopPlatform.MAC_OS.primaryShortcut(Key.Q)
        DesktopPlatform.WINDOWS.primaryShortcut(Key.Q)
    }

    @Test
    fun applePropertiesAreConfiguredOnlyOnMac() {
        val windowsProperties = mutableMapOf<String, String>()
        configureDesktopSystemProperties(DesktopPlatform.WINDOWS, windowsProperties::put)
        assertTrue(windowsProperties.isEmpty())

        val macProperties = mutableMapOf<String, String>()
        configureDesktopSystemProperties(DesktopPlatform.MAC_OS, macProperties::put)
        assertEquals("Shinsou X", macProperties["apple.awt.application.name"])
        assertEquals("true", macProperties["apple.laf.useScreenMenuBar"])
        assertEquals("system", macProperties["apple.awt.application.appearance"])
    }

    @Test
    fun macDataRootPreservesLegacyApplicationSupportLocation() {
        val userHome = testPath("mac-home")
        val root = DesktopAppDirectories.resolveDataRoot(
            platform = DesktopPlatform.MAC_OS,
            environment = { null },
            property = testProperties(userHome = userHome.toString()),
        )

        assertEquals(
            userHome.resolve("Library").resolve("Application Support").resolve("Shinsou"),
            root,
        )
    }

    @Test
    fun windowsDataRootUsesLocalAppDataAndProductName() {
        val localAppData = testPath("windows-local-app-data")
        val root = DesktopAppDirectories.resolveDataRoot(
            platform = DesktopPlatform.WINDOWS,
            environment = { name -> if (name == "LOCALAPPDATA") localAppData.toString() else null },
            property = testProperties(userHome = testPath("unused-windows-home").toString()),
        )

        assertEquals(localAppData.resolve("Shinsou X"), root)
    }

    @Test
    fun windowsDataRootFallsBackToUserLocalDirectory() {
        val userHome = testPath("windows-home")
        val root = DesktopAppDirectories.resolveDataRoot(
            platform = DesktopPlatform.WINDOWS,
            environment = { null },
            property = testProperties(userHome = userHome.toString()),
        )

        assertEquals(
            userHome.resolve("AppData").resolve("Local").resolve("Shinsou X"),
            root,
        )
    }

    @Test
    fun linuxDataRootHonorsXdgDataHome() {
        val xdgDataHome = testPath("xdg-data")
        val root = DesktopAppDirectories.resolveDataRoot(
            platform = DesktopPlatform.LINUX,
            environment = { name -> if (name == "XDG_DATA_HOME") xdgDataHome.toString() else null },
            property = testProperties(userHome = testPath("unused-linux-home").toString()),
        )

        assertEquals(xdgDataHome.resolve("Shinsou X"), root)
    }

    private fun testPath(name: String): Path = Path.of("build", "desktop-platform-test", name)
        .toAbsolutePath()
        .normalize()

    private fun testProperties(
        userHome: String,
        temporaryDirectory: String = testPath("temporary").toString(),
    ): (String) -> String? = { name ->
        when (name) {
            "user.home" -> userHome
            "java.io.tmpdir" -> temporaryDirectory
            else -> null
        }
    }
}
