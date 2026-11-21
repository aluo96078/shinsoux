package dev.shinsou.kmp.concurrent

/** Cross-platform blocking lock for store APIs that are intentionally synchronous. */
public expect class SynchronousLock() {
    public fun lock()
    public fun unlock()
}

public inline fun <T> SynchronousLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}
