package dev.shinsou.kmp.concurrent

import platform.Foundation.NSThread

public actual fun isMainThread(): Boolean = NSThread.isMainThread
