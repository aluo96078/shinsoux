package dev.shinsou.kmp.concurrent

import java.util.concurrent.locks.ReentrantLock

internal actual class SynchronousLock actual constructor() {
    private val delegate = ReentrantLock(true)

    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}
