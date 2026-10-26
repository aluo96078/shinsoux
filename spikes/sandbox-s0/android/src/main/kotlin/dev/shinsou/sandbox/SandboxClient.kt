package dev.shinsou.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** What S1's SandboxBridgeDispatcher will implement; kept tiny here so the transport is testable. */
interface HostBridgeHandler {
    fun bridgeCall(method: String, argsJson: ByteArray): ByteArray?
    fun asyncStart(opId: Int, requestJson: ByteArray, complete: (ok: Boolean, payload: ByteArray) -> Unit)
    fun asyncCancel(opId: Int)
    fun log(message: ByteArray) {}
}

class SandboxResult(val status: Int, val json: ByteArray) {
    companion object {
        /** Synthetic status for "the sandbox process died" (not an ssc_status). */
        const val CRASHED = 100
        const val DISCONNECTED = 101
        /** ssc_status LIMIT_RESULT: also used when a result payload exceeds the transport cap. */
        const val LIMIT_RESULT = 6
        /** ssc_status BAD_ARGUMENT: a sandbox-supplied payload could not be decoded. */
        const val BAD_PAYLOAD = 11
    }
}

/** Host-side handle to one package's isolated process. */
class SandboxClient(
    private val context: Context,
    val packageKey: String,
    private val handler: HostBridgeHandler,
    private val executor: Executor,
    private val codec: PayloadCodec,
    private val onDead: (SandboxClient, crashed: Boolean) -> Unit,
) {
    private val pending = ConcurrentHashMap<Long, CompletableFuture<SandboxResult>>()
    private val callIds = AtomicLong()
    private val connected = CompletableFuture<ISandbox>()
    private val ops = ConcurrentHashMap<Int, Boolean>()
    @Volatile private var ready: CompletableFuture<Int> = CompletableFuture.failedFuture(IllegalStateException("not started"))

    @Volatile var lastUsedMs = 0L
    @Volatile private var sandbox: ISandbox? = null
    @Volatile private var bound = false
    @Volatile private var dead = false

    private val deathRecipient = IBinder.DeathRecipient { fail(crashed = true) }

    // This binder object is the package's identity capability (ADR-0002 §4.3).
    private val hostBinder = object : IHostBridge.Stub() {
        // Payloads come from the untrusted sandbox: a decode failure must fail that call, never throw
        // out of the binder thread (which would leave the caller waiting forever).
        override fun bridgeCall(method: String, argsJson: Payload): Payload? {
            val args = runCatching { codec.decode(argsJson) }.getOrNull() ?: return null
            return handler.bridgeCall(method, args)?.let { codec.encode(it) }
        }

        override fun asyncStart(opId: Int, requestJson: Payload) {
            ops[opId] = true
            val request = runCatching { codec.decode(requestJson) }.getOrNull()
            if (request == null) {
                ops.remove(opId)
                sandbox?.asyncResult(opId, false, codec.encode(ByteArray(0)))
                return
            }
            handler.asyncStart(opId, request) { ok, bytes ->
                if (ops.remove(opId) != null) sandbox?.asyncResult(opId, ok, codec.encode(bytes))
            }
        }

        override fun asyncCancel(opId: Int) {
            ops.remove(opId)
            handler.asyncCancel(opId)
        }

        override fun onResult(callId: Long, status: Int, resultJson: Payload) {
            val future = pending.remove(callId) ?: return
            val bytes = try {
                codec.decode(resultJson)
            } catch (e: IllegalArgumentException) { // over the transport cap
                future.complete(SandboxResult(SandboxResult.LIMIT_RESULT, ByteArray(0)))
                return
            } catch (e: Exception) {
                future.complete(SandboxResult(SandboxResult.BAD_PAYLOAD, ByteArray(0)))
                return
            }
            future.complete(SandboxResult(status, bytes))
        }

        override fun onLog(message: ByteArray) = handler.log(message)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            service.linkToDeath(deathRecipient, 0)
            sandbox = ISandbox.Stub.asInterface(service)
            connected.complete(sandbox)
        }

        // The process died while still bound; with BIND_AUTO_CREATE Android would restart it, but a
        // dead runtime has lost its JS state, so we surface it instead of silently reusing it.
        override fun onServiceDisconnected(name: ComponentName) = fail(crashed = true)
        override fun onBindingDied(name: ComponentName) = fail(crashed = true)
        override fun onNullBinding(name: ComponentName) = fail(crashed = false)
    }

    /** Binds (one isolated process per [packageKey]) and loads the script. Returns the ssc_status. */
    fun start(bootstrap: ByteArray, script: ByteArray, limits: LongArray): CompletableFuture<Int> {
        val intent = Intent(context, SandboxService::class.java)
            .putExtra(SandboxService.EXTRA_STRATEGY, codec.strategy.name)
        // instanceName must be unique per package; it is what separates the processes.
        bound = context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, instanceName(packageKey), executor, connection)
        if (!bound) return CompletableFuture.completedFuture(SandboxResult.DISCONNECTED)
        ready = connected.thenApplyAsync({ it.load(codec.encode(bootstrap), codec.encode(script), limits, hostBinder) }, executor)
        return ready
    }

    /** Waits for bind + load, then invokes; a load failure completes with that ssc_status. */
    fun invoke(method: String, argsJson: ByteArray): CompletableFuture<SandboxResult> =
        ready.handle { st, err -> st to err }.thenCompose { (st, err) ->
            when {
                err != null -> CompletableFuture.completedFuture(SandboxResult(SandboxResult.DISCONNECTED, ByteArray(0)))
                st != 0 -> CompletableFuture.completedFuture(SandboxResult(st, ByteArray(0)))
                else -> invokeNow(method, argsJson)
            }
        }

    private fun invokeNow(method: String, argsJson: ByteArray): CompletableFuture<SandboxResult> {
        val f = CompletableFuture<SandboxResult>()
        val s = sandbox
        if (dead || s == null) { f.complete(SandboxResult(SandboxResult.DISCONNECTED, ByteArray(0))); return f }
        val id = callIds.incrementAndGet()
        pending[id] = f
        lastUsedMs = System.nanoTime() / 1_000_000
        try {
            s.invoke(id, method, codec.encode(argsJson))
        } catch (e: android.os.RemoteException) {
            fail(crashed = true)
        }
        return f
    }

    fun cancel() { sandbox?.cancel(0) }

    // S0 measurement/diagnostic hooks.
    fun probe(): String = sandbox!!.probe()
    /** {pid, uid, art heap bytes, quickjs bytes} of the sandbox process. */
    fun stats(): LongArray = sandbox!!.stats()
    fun crashForTest() { runCatching { sandbox!!.crash() } }

    /** Idle reclaim / LRU eviction / package revocation. */
    fun stop() {
        if (!dead) fail(crashed = false)
    }

    private fun fail(crashed: Boolean) {
        if (dead) return
        dead = true
        val s = sandbox
        runCatching { s?.asBinder()?.unlinkToDeath(deathRecipient, 0) }
        if (bound) { bound = false; runCatching { context.unbindService(connection) } }
        val code = if (crashed) SandboxResult.CRASHED else SandboxResult.DISCONNECTED
        pending.values.forEach { it.complete(SandboxResult(code, ByteArray(0))) }
        pending.clear()
        ops.clear()
        connected.completeExceptionally(IllegalStateException("sandbox gone"))
        onDead(this, crashed)
    }

    companion object {
        /** Instance names are limited in charset; hash-free sanitising keeps them debuggable. */
        fun instanceName(packageKey: String): String = packageKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
