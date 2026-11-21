package dev.shinsou.kmp.concurrent

import platform.Foundation.NSThread

actual fun isMainThread(): Boolean = NSThread.isMainThread
