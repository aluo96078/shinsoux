package dev.shinsou.kmp.concurrent

/** Cross-platform blocking lock for store APIs that are intentionally synchronous. */
internal expect class SynchronousLock() {
    fun lock()
    fun unlock()
}

internal inline fun <T> SynchronousLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}
