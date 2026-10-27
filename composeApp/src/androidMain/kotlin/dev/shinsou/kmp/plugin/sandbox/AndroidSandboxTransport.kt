package dev.shinsou.kmp.plugin.sandbox

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android's [SandboxTransportProvider] (ADR-0002 §4.3): every [open] binds a new isolated process
 * ([SandboxProcessService]) under a unique instance name, so packages never share a process and a
 * restarted sandbox never reuses a dead or closed one.
 */
internal class AndroidSandboxTransportProvider(
    context: Context,
    private val bindTimeoutMs: Long = 10_000,
) : SandboxTransportProvider {
    private val context = context.applicationContext
    private val instances = AtomicLong()

    override suspend fun open(packageId: String): SandboxTransport = withContext(Dispatchers.IO) {
        // The system accepts only letters, digits, '_' and '.' in instance names.
        val label = packageId.replace(Regex("[^A-Za-z0-9._]"), "_").take(64)
        AndroidSandboxTransport.open(context, "s${instances.incrementAndGet()}_$label", bindTimeoutMs)
    }
}

/**
 * The §4.6/§7 lifecycle with Android's values: at most 4 live sandboxes, and time-limit strikes
 * only while the app is in the foreground with the screen on. OEM freezers (Samsung Freecess and
 * the like) stop background processes mid-call, and that must not count against the package.
 */
internal fun androidSandboxLifecycle(context: Context, scope: CoroutineScope): SandboxLifecycle {
    val power = context.getSystemService(PowerManager::class.java)
    return SandboxLifecycle(
        maxLive = 4,
        nowMs = SystemClock::elapsedRealtime,
        countTimeouts = {
            val state = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(state)
            power.isInteractive && state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        },
        scope = scope,
    )
}

/**
 * Frames to one isolated sandbox process over two pipes. The sandbox holds only its own ends, so
 * it can neither read what the host reads nor change the host ends' file status flags. Reads and
 * writes poll in short slices, which lets [close] stop them without closing a descriptor that
 * another thread is using; the descriptors are closed afterwards under the same locks.
 */
internal class AndroidSandboxTransport private constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val process: ISandboxProcess,
    /** The sandbox process's pid (diagnostics and tests). */
    val pid: Int,
    private val input: ParcelFileDescriptor,
    private val output: ParcelFileDescriptor,
) : SandboxTransport {
    @Volatile
    private var closed = false
    private val readLock = Any()
    private val writeLock = Any()
    private val buffer = ByteArray(64 * 1024)

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        synchronized(readLock) {
            while (!closed) {
                if (!ready(input, OsConstants.POLLIN)) continue
                val n = try {
                    Os.read(input.fileDescriptor, buffer, 0, buffer.size)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue
                    return@synchronized null
                }
                return@synchronized if (n <= 0) null else buffer.copyOf(n)
            }
            null
        }
    }

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            var offset = 0
            while (offset < bytes.size) {
                if (closed) throw java.io.IOException("sandbox transport closed")
                if (!ready(output, OsConstants.POLLOUT)) continue
                // After POLLOUT a pipe has a free buffer slot, so a write of at most PIPE_BUF bytes
                // completes without blocking (the descriptor is ours alone).
                val n = try {
                    Os.write(output.fileDescriptor, bytes, offset, minOf(PIPE_BUF, bytes.size - offset))
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue
                    throw java.io.IOException("sandbox pipe write failed", e)
                }
                offset += n
            }
        }
    }

    /** True when [events] (or an error/hang-up, which the next read or write reports) is pending. */
    private fun ready(pfd: ParcelFileDescriptor, events: Int): Boolean {
        val poll = StructPollfd().apply {
            fd = pfd.fileDescriptor
            this.events = events.toShort()
        }
        return try {
            Os.poll(arrayOf(poll), POLL_SLICE_MS) > 0
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EINTR) throw java.io.IOException("sandbox pipe poll failed", e)
            false
        }
    }

    /** Asks the sandbox which forbidden operations it could perform (0 = none); see `ISandboxProcess.probe`. */
    fun probe(hostPath: String?): Int = process.probe(hostPath)

    override fun close() {
        if (closed) return
        closed = true
        // The isolated process is killed once its last binding goes; it also exits when it sees
        // end of stream on its input.
        runCatching { context.unbindService(connection) }
        closer.execute {
            synchronized(readLock) { runCatching { input.close() } }
            synchronized(writeLock) { runCatching { output.close() } }
        }
    }

    internal companion object {
        private const val POLL_SLICE_MS = 200
        private const val PIPE_BUF = 4096

        private val binderCallbacks: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sandbox-binder").apply { isDaemon = true } }
        private val closer: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sandbox-closer").apply { isDaemon = true } }

        suspend fun open(context: Context, instanceName: String, timeoutMs: Long): AndroidSandboxTransport {
            val connected = CompletableDeferred<IBinder>()
            var transport: AndroidSandboxTransport? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    connected.complete(service)
                }

                // The process died: the pipes report end of stream as well; close() makes it prompt.
                override fun onServiceDisconnected(name: ComponentName) {
                    transport?.close()
                }

                override fun onBindingDied(name: ComponentName) {
                    connected.completeExceptionally(SandboxBrokenException("sandbox binding died"))
                    transport?.close()
                }

                override fun onNullBinding(name: ComponentName) {
                    connected.completeExceptionally(SandboxBrokenException("sandbox service returned no binder"))
                }
            }
            val intent = Intent(context, SandboxProcessService::class.java)
            if (!context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, instanceName, binderCallbacks, connection)) {
                runCatching { context.unbindService(connection) }
                throw SandboxBrokenException("could not bind the sandbox service")
            }
            var toSandbox: Array<ParcelFileDescriptor>? = null
            var fromSandbox: Array<ParcelFileDescriptor>? = null
            try {
                val binder = withTimeoutOrNull(timeoutMs) { connected.await() }
                    ?: throw SandboxBrokenException("sandbox did not start within $timeoutMs ms")
                val process = ISandboxProcess.Stub.asInterface(binder)
                toSandbox = ParcelFileDescriptor.createPipe()
                fromSandbox = ParcelFileDescriptor.createPipe()
                val pid = try {
                    process.start(toSandbox[0], fromSandbox[1])
                } catch (e: RemoteException) {
                    throw SandboxBrokenException("sandbox died while starting")
                } finally {
                    // Only the sandbox keeps these ends from now on.
                    toSandbox[0].close()
                    fromSandbox[1].close()
                }
                if (pid <= 0) throw SandboxBrokenException("sandbox refused to start ($pid)")
                return AndroidSandboxTransport(context, connection, process, pid, fromSandbox[0], toSandbox[1]).also {
                    transport = it
                    try {
                        binder.linkToDeath({ it.close() }, 0)
                    } catch (_: RemoteException) {
                        it.close() // already dead: the session sees end of stream
                    }
                }
            } catch (e: Throwable) {
                runCatching { context.unbindService(connection) }
                fromSandbox?.get(0)?.let { runCatching { it.close() } }
                toSandbox?.get(1)?.let { runCatching { it.close() } }
                throw e
            }
        }
    }
}
