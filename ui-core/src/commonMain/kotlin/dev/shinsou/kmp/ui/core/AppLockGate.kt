package dev.shinsou.kmp.ui.core

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the app is unlocked. A dialog is its own window, drawn above the lock overlay: dialogs
 * that grant or change anything (extension permission reviews, repository trust) are shown only
 * while this is true, so nobody can approve them without unlocking first. The work behind them
 * (a restored screen looking for a pending grant) still runs; its dialog waits for the unlock.
 */
public val LocalAppUnlocked: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }
