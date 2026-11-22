package dev.shinsou.kmp.rights

import java.util.concurrent.locks.ReentrantLock

internal actual fun rightsLock(): RightsLock = FairReentrantLock()

private class FairReentrantLock : RightsLock {
    private val delegate = ReentrantLock(true)

    override fun lock(): Unit = delegate.lock()
    override fun unlock(): Unit = delegate.unlock()
}
