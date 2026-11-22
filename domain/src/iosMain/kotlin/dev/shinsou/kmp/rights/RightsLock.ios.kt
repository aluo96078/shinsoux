package dev.shinsou.kmp.rights

import platform.Foundation.NSRecursiveLock

internal actual fun rightsLock(): RightsLock = RecursiveLock()

private class RecursiveLock : RightsLock {
    private val delegate = NSRecursiveLock()

    override fun lock(): Unit = delegate.lock()
    override fun unlock(): Unit = delegate.unlock()
}
