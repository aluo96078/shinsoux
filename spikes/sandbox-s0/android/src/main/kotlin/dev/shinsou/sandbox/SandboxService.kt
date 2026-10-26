package dev.shinsou.sandbox

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs in an isolated process (one per package). Everything here is untrusted-adjacent: it holds
 * the QuickJS runtime, so the only thing it can reach is the IHostBridge binder it was given.
 */
class SandboxService : Service() {
    override fun onBind(intent: Intent): IBinder =
        Impl(PayloadCodec.Strategy.valueOf(intent.getStringExtra(EXTRA_STRATEGY) ?: PayloadCodec.Strategy.PIPE.name))

    private class AsyncResult(val opId: Int, val ok: Boolean, val payload: ByteArray)

    private inner class Impl(strategy: PayloadCodec.Strategy) : ISandbox.Stub() {
        // One thread owns the JS runtime (ssc threading contract); binder threads only enqueue.
        private val runtimeThread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ssc-runtime") }
        private val ioThread: ExecutorService = Executors.newCachedThreadPool()
        private val codec = PayloadCodec(ioThread, strategy)
        private val inbox = LinkedBlockingQueue<AsyncResult>()

        @Volatile private var core: NativeCore? = null
        @Volatile private var lockdownStatus: Int = Int.MIN_VALUE
        @Volatile private var host: IHostBridge? = null

        override fun load(bootstrap: Payload, script: Payload, limits: LongArray, host: IHostBridge): Int {
            this.host = host
            return runtimeThread.submit<Int> {
                NativeCore.loadLibrary()
                // Before any untrusted code runs: no exec, no new processes (threads still allowed).
                lockdownStatus = NativeCore.lockdown()
                val lim = NativeCore.Limits().apply {
                    heapBytes = limits[0]; stackBytes = limits[1]; initTimeoutMs = limits[2]; invokeTimeoutMs = limits[3]
                    maxBridgeCalls = limits[4]; maxAsyncInflight = limits[5]; maxAsyncQueue = limits[6]
                    maxResultBytes = limits[7]; domMaxNodes = limits[8]; domMaxDepth = limits[9]
                }
                val c = NativeCore(BinderHost(host), lim)
                core = c
                c.init(codec.decode(bootstrap), codec.decode(script))
            }.get()
        }

        override fun invoke(callId: Long, method: String, argsJson: Payload) {
            runtimeThread.execute {
                val c = core ?: return@execute
                val h = host ?: return@execute
                val args = codec.decode(argsJson)
                inbox.clear() // results from a previous invocation must never leak into this one
                val r = c.invoke(method, args)
                h.onResult(callId, r.status, codec.encode(r.json))
            }
        }

        override fun cancel(callId: Long) {
            core?.cancel() // thread-safe by contract; runs on a binder thread
        }

        override fun asyncResult(opId: Int, ok: Boolean, payload: Payload) {
            inbox.put(AsyncResult(opId, ok, codec.decode(payload)))
        }

        override fun stats(): LongArray {
            val rt = Runtime.getRuntime()
            return longArrayOf(Process.myPid().toLong(), Process.myUid().toLong(), rt.totalMemory() - rt.freeMemory(), core?.memoryUsed() ?: 0)
        }

        override fun probe(): String = buildString {
            append("lockdown=").append(if (lockdownStatus == 0) "seccomp" else "failed($lockdownStatus)").append('\n')
            fun attempt(name: String, block: () -> Unit) {
                val r = try { block(); "OK(unexpected)" } catch (e: ErrnoException) { "errno=${e.errno}" } catch (e: Exception) { e.javaClass.simpleName }
                append(name).append('=').append(r).append('\n')
            }
            attempt("socket(AF_INET)") { Os.close(Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)) }
            attempt("socket(AF_INET6)") { Os.close(Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_DGRAM, 0)) }
            attempt("open(app data dir)") { Os.close(Os.open(applicationInfo.dataDir + "/shared_prefs", OsConstants.O_RDONLY, 0)) }
            attempt("open(apk)") { Os.close(Os.open(applicationInfo.sourceDir, OsConstants.O_RDONLY, 0)) }
            attempt("open(/sdcard)") { Os.close(Os.open("/sdcard/Download", OsConstants.O_RDONLY, 0)) }
            attempt("open(/proc/net/tcp)") { Os.close(Os.open("/proc/net/tcp", OsConstants.O_RDONLY, 0)) }
            // Never execv() in-process: success would replace this process image and look like a crash.
            attempt("spawn(/system/bin/sh)") {
                val p = ProcessBuilder("/system/bin/sh", "-c", "true").start()
                p.waitFor()
            }
            append("thread=").append(runCatching { Thread { }.apply { start(); join() }; "OK" }.getOrElse { it.javaClass.simpleName }).append('\n')
            attempt("spawn(/system/bin/toybox)") {
                val p = ProcessBuilder("/system/bin/toybox", "true").start()
                p.waitFor()
            }
        }

        override fun crash() {
            Process.killProcess(Process.myPid())
        }

        /** Runs on the runtime thread; blocking waits are fine there. */
        private inner class BinderHost(private val h: IHostBridge) : NativeHost {
            override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

            override fun bridgeCall(method: String, argsJson: ByteArray): ByteArray? =
                h.bridgeCall(method, codec.encode(argsJson))?.let { codec.decode(it) }

            override fun asyncStart(opId: Int, requestJson: ByteArray): Int {
                h.asyncStart(opId, codec.encode(requestJson))
                return 0
            }

            override fun asyncCancel(opId: Int) = h.asyncCancel(opId)

            override fun asyncWait(deadlineMs: Long): Int {
                val wait = (deadlineMs - nowMs()).coerceAtLeast(0)
                var first = inbox.poll(wait, TimeUnit.MILLISECONDS) ?: return 0
                var n = 0
                while (true) {
                    if (core!!.deliverAsync(first.opId, first.ok, first.payload) == 0) n++
                    first = inbox.poll() ?: return n
                }
            }

            override fun log(message: ByteArray) = h.onLog(message)
        }
    }

    companion object {
        const val EXTRA_STRATEGY = "ssc.strategy"
    }
}
