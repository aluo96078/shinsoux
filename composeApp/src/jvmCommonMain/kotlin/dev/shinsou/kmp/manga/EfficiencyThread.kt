package dev.shinsou.kmp.manga

import dev.shinsou.kmp.logging.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * The single thread of [MangaCpuMode.SAVER]: [setup] runs on it first, to ask the system for an
 * efficiency core (each platform has its own way); it is also of the lowest priority. A [setup]
 * that fails (a native library missing or refusing the call) is reported to [onSetupFailure] and
 * the thread runs anyway, at the lowest priority only.
 */
internal fun efficiencyThreadDispatcher(
    onSetupFailure: (Throwable) -> Unit = { Log.warn("MangaSaver", it) { "could not move the manga saver to efficiency cores" } },
    setup: () -> Unit,
): CoroutineDispatcher =
    Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching(setup).onFailure { runCatching { onSetupFailure(it) } }
            task.run()
        }, "manga-saver").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
