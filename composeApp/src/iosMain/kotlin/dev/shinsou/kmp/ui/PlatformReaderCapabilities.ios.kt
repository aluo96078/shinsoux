package dev.shinsou.kmp.ui

internal actual val platformSupportsReaderVolumeKeys: Boolean = true

internal actual val platformSupportsSilentAudioKeepAlive: Boolean
    get() = dev.shinsou.kmp.IosSilentAudioKeepAlive.available
