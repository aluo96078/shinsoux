package dev.shinsou.kmp.concurrent

import platform.Foundation.NSThread

internal actual fun isMainThread(): Boolean = NSThread.isMainThread
