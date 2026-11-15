package dev.shinsou.kmp.manga

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The single thread of [MangaCpuMode.SAVER]: [setup] runs on it first, to ask the system for an
 * efficiency core (each platform has its own way); it is also of the lowest priority.
 */
internal fun efficiencyThreadDispatcher(setup: () -> Unit): CoroutineDispatcher =
    Executors.newSingleThreadExecutor { task ->
        Thread({ runCatching(setup); task.run() }, "manga-saver").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
