package dev.shinsou.kmp.ui.settings

internal actual val platformSupportsSilentAudioKeepAlive: Boolean
    get() = dev.shinsou.kmp.download.IosSilentAudioKeepAlive.available
