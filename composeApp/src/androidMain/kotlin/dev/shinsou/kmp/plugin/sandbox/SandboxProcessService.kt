package dev.shinsou.kmp.plugin.sandbox

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/** JNI entry points of libshinsou_sandbox.so (`sandbox-core/src/ssc_android_jni.c`). */
internal object SandboxNative {
    @JvmStatic external fun nativeLockdown(): Int

    @JvmStatic external fun nativeRun(inFd: Int, outFd: Int): Int

    @JvmStatic external fun nativeProbe(path: String?): Int
}

/** Why [ISandboxProcess.start] refused; lockdown failures are a negative errno instead. */
internal object SandboxStartError {
    const val NOT_ISOLATED = -10_001
    const val ALREADY_STARTED = -10_002
    const val NO_LIBRARY = -10_003
}

/**
 * The sandbox side on Android (ADR-0002 §4.3): runs in an isolated process, one per sandboxed
 * package (the host binds each with its own instance name). It only bootstraps: after the seccomp
 * lockdown, sandbox-core's driver speaks SandboxProtocol v1 on the two pipes from the host until the
 * host closes them, then the process exits. Nothing here trusts the script; the host trusts nothing
 * that comes out of the pipes.
 */
class SandboxProcessService : Service() {
    private val started = AtomicBoolean(false)

    @Volatile
    private var lockdown = Int.MIN_VALUE

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : ISandboxProcess.Stub() {
        override fun start(fromHost: ParcelFileDescriptor, toHost: ParcelFileDescriptor): Int {
            val status = when {
                // A manifest mistake must not run scripts inside the app's own uid.
                !Process.isIsolated() -> SandboxStartError.NOT_ISOLATED
                !started.compareAndSet(false, true) -> SandboxStartError.ALREADY_STARTED
                else -> try {
                    System.loadLibrary("shinsou_sandbox")
                    SandboxNative.nativeLockdown().also { lockdown = it }
                } catch (_: UnsatisfiedLinkError) {
                    SandboxStartError.NO_LIBRARY // an ABI sandbox-core is not built for: fail closed
                }
            }
            if (status != 0) {
                fromHost.close()
                toHost.close()
                return if (status < 0) status else -status
            }
            val inFd = fromHost.detachFd()
            val outFd = toHost.detachFd()
            Thread({
                SandboxNative.nativeRun(inFd, outFd)
                Process.killProcess(Process.myPid()) // the host is gone or closed the session
            }, "ssc-driver").start()
            return Process.myPid()
        }

        override fun probe(hostPath: String?): Int =
            if (lockdown == 0) SandboxNative.nativeProbe(hostPath) else -1
    }
}
