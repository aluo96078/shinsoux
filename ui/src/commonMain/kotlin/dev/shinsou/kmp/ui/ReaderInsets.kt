package dev.shinsou.kmp.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier

/** Whether the native host already lays Compose out inside the platform safe area. */
internal expect val readerHostProvidesSafeAreaInsets: Boolean

internal fun Modifier.readerStatusBarsPadding(): Modifier =
    if (readerHostProvidesSafeAreaInsets) this else statusBarsPadding()

internal fun Modifier.readerNavigationBarsPadding(): Modifier =
    if (readerHostProvidesSafeAreaInsets) this else navigationBarsPadding()
