package dev.shinsou.kmp.files

import java.util.Locale

// R4-1 (docs/architecture/0006): in files, so the data directories need nothing from the desktop entry point.
public enum class DesktopPlatform {
    MAC_OS,
    WINDOWS,
    LINUX,
    OTHER,
    ;

    public val usesCommandShortcuts: Boolean
        get() = this == MAC_OS

    public val usesControlShortcuts: Boolean
        get() = !usesCommandShortcuts

    public val usesNativeMenuBar: Boolean
        get() = this == MAC_OS

    public companion object {
        public val current: DesktopPlatform by lazy {
            fromOsName(System.getProperty("os.name"))
        }

        public fun fromOsName(osName: String?): DesktopPlatform {
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
