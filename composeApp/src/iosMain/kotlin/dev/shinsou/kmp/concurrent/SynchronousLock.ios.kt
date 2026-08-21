package dev.shinsou.kmp.concurrent

import platform.Foundation.NSRecursiveLock

internal actual class SynchronousLock actual constructor() {
    private val delegate = NSRecursiveLock()

    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}
