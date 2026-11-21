package dev.shinsou.kmp.concurrent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for a block that suspends. Cancelling the calling coroutine is not a failure of
 * the block: it is rethrown instead of being captured, so the coroutine still stops. A
 * [CancellationException] that is not about this coroutine (a `withTimeout` inside the block, or
 * a job it awaited being cancelled) is captured like any other failure.
 */
suspend inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (error: Throwable) {
        if (error is CancellationException) currentCoroutineContext().ensureActive()
        Result.failure(error)
    }

/** The receiver form of [runCatchingCancellable], matching [runCatching] on a receiver. */
suspend inline fun <T, R> T.runCatchingCancellable(block: T.() -> R): Result<R> =
    try {
        Result.success(block())
    } catch (error: Throwable) {
        if (error is CancellationException) currentCoroutineContext().ensureActive()
        Result.failure(error)
    }
