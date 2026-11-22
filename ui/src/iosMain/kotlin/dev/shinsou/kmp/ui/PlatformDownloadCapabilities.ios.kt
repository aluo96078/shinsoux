package dev.shinsou.kmp.ui

internal actual val platformSupportsSilentAudioKeepAlive: Boolean
    get() = dev.shinsou.kmp.download.IosSilentAudioKeepAlive.available
