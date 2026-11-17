package dev.shinsou.kmp.concurrent

import javax.swing.SwingUtilities

internal actual fun isMainThread(): Boolean = SwingUtilities.isEventDispatchThread()
