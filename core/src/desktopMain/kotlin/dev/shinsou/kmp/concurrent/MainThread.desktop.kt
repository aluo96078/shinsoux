package dev.shinsou.kmp.concurrent

import javax.swing.SwingUtilities

actual fun isMainThread(): Boolean = SwingUtilities.isEventDispatchThread()
