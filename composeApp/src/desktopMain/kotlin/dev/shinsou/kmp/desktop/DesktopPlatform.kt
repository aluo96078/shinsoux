package dev.shinsou.kmp.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import java.util.Locale

internal enum class DesktopPlatform {
    MAC_OS,
    WINDOWS,
    LINUX,
    OTHER,
    ;

    val usesCommandShortcuts: Boolean
        get() = this == MAC_OS

    val usesControlShortcuts: Boolean
        get() = !usesCommandShortcuts

    val usesNativeMenuBar: Boolean
        get() = this == MAC_OS

    fun primaryShortcut(key: Key): KeyShortcut = KeyShortcut(
        key = key,
        ctrl = usesControlShortcuts,
        meta = usesCommandShortcuts,
    )

    companion object {
        val current: DesktopPlatform by lazy {
            fromOsName(System.getProperty("os.name"))
        }

        fun fromOsName(osName: String?): DesktopPlatform {
            val normalized = osName.orEmpty().lowercase(Locale.ROOT)
            return when {
                normalized.contains("mac") || normalized.contains("darwin") -> MAC_OS
                normalized.contains("windows") -> WINDOWS
                normalized.contains("linux") -> LINUX
                else -> OTHER
            }
        }
    }
}

internal enum class DesktopShortcutAction {
    OPEN_LIBRARY,
    OPEN_UPDATES,
    OPEN_HISTORY,
    OPEN_BROWSE,
    OPEN_MORE,
    OPEN_SETTINGS,
    MINIMIZE,
    QUIT,
}

internal fun desktopShortcutAction(
    platform: DesktopPlatform,
    key: Key,
    type: KeyEventType,
    ctrlPressed: Boolean,
    metaPressed: Boolean,
    altPressed: Boolean,
    shiftPressed: Boolean,
): DesktopShortcutAction? {
    if (platform.usesNativeMenuBar || type != KeyEventType.KeyDown) return null
    if (!ctrlPressed || metaPressed || altPressed || shiftPressed) return null

    return when (key) {
        Key.One -> DesktopShortcutAction.OPEN_LIBRARY
        Key.Two -> DesktopShortcutAction.OPEN_UPDATES
        Key.Three -> DesktopShortcutAction.OPEN_HISTORY
        Key.Four -> DesktopShortcutAction.OPEN_BROWSE
        Key.Five -> DesktopShortcutAction.OPEN_MORE
        Key.Comma -> DesktopShortcutAction.OPEN_SETTINGS
        Key.M -> DesktopShortcutAction.MINIMIZE
        Key.Q -> DesktopShortcutAction.QUIT
        else -> null
    }
}

internal fun configureDesktopSystemProperties(
    platform: DesktopPlatform,
    setProperty: (String, String) -> Unit = System::setProperty,
) {
    if (platform != DesktopPlatform.MAC_OS) return

    setProperty("apple.awt.application.name", "Shinsou X")
    setProperty("apple.laf.useScreenMenuBar", "true")
    setProperty("apple.awt.application.appearance", "system")
}
