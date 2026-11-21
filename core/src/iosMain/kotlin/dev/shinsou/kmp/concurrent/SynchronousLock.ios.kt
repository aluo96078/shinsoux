package dev.shinsou.kmp.concurrent

import platform.Foundation.NSRecursiveLock

public actual class SynchronousLock actual constructor() {
    private val delegate = NSRecursiveLock()

    public actual fun lock(): Unit = delegate.lock()
    public actual fun unlock(): Unit = delegate.unlock()
}
