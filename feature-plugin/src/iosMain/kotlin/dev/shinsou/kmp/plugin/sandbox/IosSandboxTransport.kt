package dev.shinsou.kmp.plugin.sandbox

import dev.shinsou.interop.sandbox.SSC_WASM_TIMEOUT
import cnames.structs.ssc_wasm_session
import dev.shinsou.interop.sandbox.ssc_wasm_session_close_input
import dev.shinsou.interop.sandbox.ssc_wasm_session_free
import dev.shinsou.interop.sandbox.ssc_wasm_session_open
import dev.shinsou.interop.sandbox.ssc_wasm_session_receive
import dev.shinsou.interop.sandbox.ssc_wasm_session_send
import dev.shinsou.kmp.plugin.ScriptPluginRuntimeFactory
import kotlin.concurrent.AtomicInt
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime_nsec_np

/**
 * The iOS [SandboxTransportProvider] (ADR-0002 §4.5): every [open] starts a new instance of the
 * sandbox-core WASM guest in the WAMR interpreter (`sandbox-core/ios`), on its own thread. iOS has
 * no child processes for apps; the boundary is the guest's bounded linear memory, which the
 * interpreter checks on every access, and its four imports (protocol bytes in and out, clocks,
 * time zone): no network, files or system calls of its own.
 */
internal class IosSandboxTransportProvider(
    /** Linear memory ceiling per sandbox; it can only lower the guest's own 160 MiB maximum. */
    private val linearMemoryBytes: Long = 160L shl 20,
) : SandboxTransportProvider {
    override suspend fun open(packageId: String): SandboxTransport = withContext(Dispatchers.IO) {
        IosSandboxTransport.open(linearMemoryBytes)
    }
}

/** The app's sandbox engine on iOS. */
public fun iosSandboxRuntimeFactory(scope: CoroutineScope): ScriptPluginRuntimeFactory = SandboxRuntimeFactory(
    transports = IosSandboxTransportProvider(),
    lifecycle = iosSandboxLifecycle(scope),
)

/**
 * The §4.6/§7 lifecycle with iOS's values: the sandboxes share the app's own memory, so fewer stay
 * live than on Android (each may grow to its linear memory ceiling).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun iosSandboxLifecycle(scope: CoroutineScope): SandboxLifecycle = SandboxLifecycle(
    maxLive = 3,
    nowMs = { (clock_gettime_nsec_np(CLOCK_MONOTONIC.convert()) / 1_000_000u).toLong() },
    scope = scope,
)

/**
 * Frames to one WASM sandbox. [close] ends its input at once (the guest stops even mid-hook within
 * the driver's poll interval, or is terminated after a grace period) and frees the instance once
 * no send or receive is still inside it. A receive waits at most one slice before it sees a close.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosSandboxTransport private constructor(
    private val session: CPointer<ssc_wasm_session>,
) : SandboxTransport {
    /**
     * One word for the whole lifetime, so no call can slip in between a close and the free: the
     * number of calls inside the session, plus [CLOSED] once closed, and [FREED] (-1) once freed.
     * The session is freed by whoever sees it closed with nobody inside.
     */
    private val state = AtomicInt(0)
    private val buffer = ByteArray(64 * 1024)

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val rc = using { bytes.usePinned { ssc_wasm_session_send(session, it.addressOf(0).reinterpret(), bytes.size.convert()) } }
        if (rc != 0) throw IllegalStateException("The plugin sandbox has ended")
    }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        while (true) {
            val n = using {
                buffer.usePinned { ssc_wasm_session_receive(session, it.addressOf(0).reinterpret(), buffer.size.convert(), RECEIVE_SLICE_MS) }
            } ?: return@withContext null
            if (n == SSC_WASM_TIMEOUT) continue
            return@withContext if (n <= 0) null else buffer.copyOf(n.toInt())
        }
        @Suppress("UNREACHABLE_CODE")
        null as ByteArray?
    }

    override fun close() {
        while (true) {
            val current = state.value
            if (current < 0 || current and CLOSED != 0) return
            // Close counts itself in, like a call: nothing can free the session under it.
            if (state.compareAndSet(current, (current + 1) or CLOSED)) break
        }
        try {
            ssc_wasm_session_close_input(session)
        } finally {
            if (state.decrementAndGet() == CLOSED) freeIfIdle()
        }
    }

    /** Runs [block] inside the session unless it is closed; null when it is. */
    private inline fun <T> using(block: () -> T): T? {
        while (true) {
            val current = state.value
            if (current < 0 || current and CLOSED != 0) return null
            if (state.compareAndSet(current, current + 1)) break
        }
        try {
            return block()
        } finally {
            if (state.decrementAndGet() == CLOSED) freeIfIdle()
        }
    }

    private fun freeIfIdle() {
        if (state.compareAndSet(CLOSED, FREED)) ssc_wasm_session_free(session)
    }

    companion object {
        private const val RECEIVE_SLICE_MS = 200u
        private const val CLOSED = 1 shl 30
        private const val FREED = -1

        fun open(linearMemoryBytes: Long): IosSandboxTransport = memScoped {
            val err = allocArray<ByteVar>(256)
            val session = ssc_wasm_session_open(linearMemoryBytes.toUInt(), err, 256u)
                ?: throw IllegalStateException("The plugin sandbox could not start: ${err.toKString()}")
            IosSandboxTransport(session)
        }
    }
}
