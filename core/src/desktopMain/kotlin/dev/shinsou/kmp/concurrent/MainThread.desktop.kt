package dev.shinsou.kmp.concurrent

import javax.swing.SwingUtilities

public actual fun isMainThread(): Boolean = SwingUtilities.isEventDispatchThread()
