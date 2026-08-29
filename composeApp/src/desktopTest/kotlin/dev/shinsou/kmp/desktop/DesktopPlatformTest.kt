package dev.shinsou.kmp.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import java.nio.file.Files
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
        assertTrue(DesktopPlatform.MAC_OS.usesNativeMenuBar)

        assertFalse(DesktopPlatform.WINDOWS.usesCommandShortcuts)
        assertTrue(DesktopPlatform.WINDOWS.usesControlShortcuts)
        assertFalse(DesktopPlatform.WINDOWS.usesNativeMenuBar)

        // Construct both shortcuts as an integration check against Compose's desktop API.
        DesktopPlatform.MAC_OS.primaryShortcut(Key.Q)
        DesktopPlatform.WINDOWS.primaryShortcut(Key.Q)
    }

    @Test
    fun windowsShortcutsRemainAvailableWithoutNativeMenuBar() {
        val expectedActions = mapOf(
            Key.One to DesktopShortcutAction.OPEN_LIBRARY,
            Key.Two to DesktopShortcutAction.OPEN_UPDATES,
            Key.Three to DesktopShortcutAction.OPEN_HISTORY,
            Key.Four to DesktopShortcutAction.OPEN_BROWSE,
            Key.Five to DesktopShortcutAction.OPEN_MORE,
            Key.Comma to DesktopShortcutAction.OPEN_SETTINGS,
            Key.M to DesktopShortcutAction.MINIMIZE,
            Key.Q to DesktopShortcutAction.QUIT,
        )

        expectedActions.forEach { (key, expectedAction) ->
            assertEquals(
                expectedAction,
                desktopShortcutAction(
                    platform = DesktopPlatform.WINDOWS,
                    key = key,
                    type = KeyEventType.KeyDown,
                    ctrlPressed = true,
                    metaPressed = false,
                    altPressed = false,
                    shiftPressed = false,
                ),
            )
        }
    }

    @Test
    fun desktopShortcutRouterRejectsMenuOwnedAndModifiedEvents() {
        fun resolve(
            platform: DesktopPlatform = DesktopPlatform.WINDOWS,
            type: KeyEventType = KeyEventType.KeyDown,
            ctrlPressed: Boolean = true,
            metaPressed: Boolean = false,
            altPressed: Boolean = false,
            shiftPressed: Boolean = false,
        ) = desktopShortcutAction(
            platform = platform,
            key = Key.Q,
            type = type,
            ctrlPressed = ctrlPressed,
            metaPressed = metaPressed,
            altPressed = altPressed,
            shiftPressed = shiftPressed,
        )

        assertEquals(null, resolve(platform = DesktopPlatform.MAC_OS))
        assertEquals(null, resolve(type = KeyEventType.KeyUp))
        assertEquals(null, resolve(ctrlPressed = false))
        assertEquals(null, resolve(metaPressed = true))
        assertEquals(null, resolve(altPressed = true))
        assertEquals(null, resolve(shiftPressed = true))
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
    fun windowsDataRootUsesInstallerSafeUserProfileDirectory() {
        val userProfile = testPath("windows-user-profile")
        val appData = testPath("windows-app-data")
        val localAppData = testPath("windows-local-app-data")
        val root = DesktopAppDirectories.resolveDataRoot(
            platform = DesktopPlatform.WINDOWS,
            environment = {
                when (it) {
                    "USERPROFILE" -> userProfile.toString()
                    "APPDATA" -> appData.toString()
                    "LOCALAPPDATA" -> localAppData.toString()
                    else -> null
                }
            },
            property = testProperties(userHome = testPath("unused-windows-home").toString()),
        )

        assertEquals(userProfile.resolve("ShinsouXData"), root)
    }

    @Test
    fun windowsDataRootFallsBackToUserProfileProperty() {
        val userHome = testPath("windows-home")
        val root = DesktopAppDirectories.resolveDataRoot(
            platform = DesktopPlatform.WINDOWS,
            environment = { null },
            property = testProperties(userHome = userHome.toString()),
        )

        assertEquals(userHome.resolve("ShinsouXData"), root)
    }

    @Test
    fun windowsDataRootDoesNotFallBackIntoAppDataTrees() {
        val temporaryDirectory = testPath("windows-temporary")
        val root = DesktopAppDirectories.resolveDataRoot(
            platform = DesktopPlatform.WINDOWS,
            environment = {
                when (it) {
                    "APPDATA" -> testPath("windows-roaming").toString()
                    "LOCALAPPDATA" -> testPath("windows-local").toString()
                    else -> null
                }
            },
            property = testProperties(
                userHome = " ",
                temporaryDirectory = temporaryDirectory.toString(),
            ),
        )

        assertEquals(temporaryDirectory.resolve("ShinsouXData"), root)
    }

    @Test
    fun legacyWindowsDataRootUsesLocalAppData() {
        val localAppData = testPath("legacy-windows-local-app-data")
        assertEquals(
            localAppData.resolve("Shinsou X"),
            DesktopAppDirectories.resolveLegacyWindowsDataRoot(
                environment = { name -> if (name == "LOCALAPPDATA") localAppData.toString() else null },
                property = testProperties(userHome = testPath("unused-windows-home").toString()),
            ),
        )
    }

    @Test
    fun legacyWindowsRoamingDataRootUsesAppData() {
        val appData = testPath("legacy-windows-app-data")
        assertEquals(
            appData.resolve("ShinsouXData"),
            DesktopAppDirectories.resolveLegacyRoamingWindowsDataRoot(
                environment = { name -> if (name == "APPDATA") appData.toString() else null },
                property = testProperties(userHome = testPath("unused-windows-home").toString()),
            ),
        )
    }

    @Test
    fun legacyWindowsRoamingDataRootFallsBackToUserHomeRoamingDirectory() {
        val userHome = testPath("legacy-windows-home")
        assertEquals(
            userHome.resolve("AppData").resolve("Roaming").resolve("ShinsouXData"),
            DesktopAppDirectories.resolveLegacyRoamingWindowsDataRoot(
                environment = { null },
                property = testProperties(userHome = userHome.toString()),
            ),
        )
    }

    @Test
    fun legacyWindowsDataDirectoryIsMovedOnlyWhenNewDirectoryIsAbsent() {
        val parent = testPath("windows-migration")
        parent.toFile().deleteRecursively()
        val legacy = parent.resolve("Shinsou X")
        val current = parent.resolve("ShinsouXData")
        Files.createDirectories(legacy)
        Files.writeString(legacy.resolve("shinsou-state.json"), "legacy")

        DesktopAppDirectories.migrateLegacyWindowsData(current, legacy)

        assertTrue(Files.isRegularFile(current.resolve("shinsou-state.json")))
        assertFalse(Files.exists(legacy))

        Files.createDirectories(legacy)
        Files.writeString(legacy.resolve("keep-me.txt"), "legacy")
        Files.writeString(current.resolve("current.txt"), "current")
        DesktopAppDirectories.migrateLegacyWindowsData(current, legacy)

        assertTrue(Files.isRegularFile(legacy.resolve("keep-me.txt")))
        assertTrue(Files.isRegularFile(current.resolve("current.txt")))
    }

    @Test
    fun legacyWindowsRoamingDirectoryMigratesIntoUserProfileDataRoot() {
        val userProfile = testPath("windows-migration-profile")
        val appData = testPath("windows-migration-app-data")
        val current = userProfile.resolve("ShinsouXData")
        val legacy = appData.resolve("ShinsouXData")
        current.toFile().deleteRecursively()
        legacy.toFile().deleteRecursively()
        Files.createDirectories(legacy)
        Files.writeString(legacy.resolve("shinsou-state.json"), "legacy")

        DesktopAppDirectories.migrateLegacyWindowsData(current, legacy)

        assertTrue(Files.isRegularFile(current.resolve("shinsou-state.json")))
        assertFalse(Files.exists(legacy))
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
