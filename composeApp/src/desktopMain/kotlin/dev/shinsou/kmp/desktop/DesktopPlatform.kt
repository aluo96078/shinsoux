package dev.shinsou.kmp.desktop

import androidx.compose.ui.input.key.Key
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

internal fun configureDesktopSystemProperties(
    platform: DesktopPlatform,
    setProperty: (String, String) -> Unit = System::setProperty,
) {
    if (platform != DesktopPlatform.MAC_OS) return

    setProperty("apple.awt.application.name", "Shinsou X")
    setProperty("apple.laf.useScreenMenuBar", "true")
    setProperty("apple.awt.application.appearance", "system")
}
