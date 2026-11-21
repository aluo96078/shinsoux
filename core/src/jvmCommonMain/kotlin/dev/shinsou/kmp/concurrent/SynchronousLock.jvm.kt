package dev.shinsou.kmp.concurrent

import java.util.concurrent.locks.ReentrantLock

public actual class SynchronousLock actual constructor() {
    private val delegate = ReentrantLock(true)

    public actual fun lock(): Unit = delegate.lock()
    public actual fun unlock(): Unit = delegate.unlock()
}
