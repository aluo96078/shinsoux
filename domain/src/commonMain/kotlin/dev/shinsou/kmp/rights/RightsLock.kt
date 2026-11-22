package dev.shinsou.kmp.rights

/**
 * The blocking lock of [InMemoryRightsAuthority]: the same platform locks as `:core`'s
 * `SynchronousLock` (a fair `ReentrantLock` on the JVM, `NSRecursiveLock` on iOS), kept here so that
 * `:domain` depends on no other module of the app (0007 §7, follow-up 3).
 */
internal interface RightsLock {
    fun lock()
    fun unlock()
}

/** A new [RightsLock] of this platform. */
internal expect fun rightsLock(): RightsLock

internal inline fun <T> RightsLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}
