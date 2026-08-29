package dev.shinsou.kmp.ui

/** Whether this target exposes hardware volume buttons that the reader can intercept. */
internal expect val platformSupportsReaderVolumeKeys: Boolean

internal fun shouldShowReaderVolumeKeySetting(
    platformSupported: Boolean = platformSupportsReaderVolumeKeys,
): Boolean = platformSupported

internal fun effectiveReaderVolumeKeysEnabled(
    configured: Boolean,
    platformSupported: Boolean = platformSupportsReaderVolumeKeys,
): Boolean = configured && platformSupported
