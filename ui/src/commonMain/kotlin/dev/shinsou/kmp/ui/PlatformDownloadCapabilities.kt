package dev.shinsou.kmp.ui

// C1 (0007): from PlatformReaderCapabilities, which moved to :ui-reader; this one is the settings'.
/** Whether background downloads can be kept running with silent audio (iOS only). */
internal expect val platformSupportsSilentAudioKeepAlive: Boolean
