package dev.shinsou.kmp.ui.reader

/** Whether this target exposes hardware volume buttons that the reader can intercept. */
internal expect val platformSupportsReaderVolumeKeys: Boolean


fun shouldShowReaderVolumeKeySetting(
    platformSupported: Boolean = platformSupportsReaderVolumeKeys,
): Boolean = platformSupported

fun effectiveReaderVolumeKeysEnabled(
    configured: Boolean,
    platformSupported: Boolean = platformSupportsReaderVolumeKeys,
): Boolean = configured && platformSupported

/** Enables interception only while a reader that consumes the event flow is actually visible. */
fun effectiveReaderVolumeKeyMonitoringEnabled(
    readerOpen: Boolean,
    configured: Boolean,
    platformSupported: Boolean = platformSupportsReaderVolumeKeys,
): Boolean = readerOpen && effectiveReaderVolumeKeysEnabled(configured, platformSupported)
