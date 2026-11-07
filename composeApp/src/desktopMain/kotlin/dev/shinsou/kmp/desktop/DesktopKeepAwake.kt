package dev.shinsou.kmp.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps the process running at full speed while background downloads are in progress: no App Nap
 * throttling once the window is hidden, and no idle system sleep. Closing the lid (or choosing
 * Sleep) still sleeps.
 */
internal interface DesktopKeepAwake {
    fun hold(reason: String)
    fun release()

    companion object {
        fun forPlatform(platform: DesktopPlatform): DesktopKeepAwake = when (platform) {
            DesktopPlatform.MAC_OS -> runCatching { MacOsActivityKeepAwake() }.getOrElse { NoKeepAwake }
            DesktopPlatform.WINDOWS -> runCatching { WindowsExecutionStateKeepAwake() }.getOrElse { NoKeepAwake }
            else -> NoKeepAwake
        }
    }
}

private object NoKeepAwake : DesktopKeepAwake {
    override fun hold(reason: String) = Unit
    override fun release() = Unit
}

/** `NSProcessInfo.beginActivityWithOptions:reason:` with NSActivityUserInitiated. */
private class MacOsActivityKeepAwake : DesktopKeepAwake {
    private val objc: ObjC = Native.load("objc", ObjC::class.java)
    private var activity: Pointer? = null

    @Synchronized
    override fun hold(reason: String) {
        if (activity != null) return
        withAutoreleasePool {
            val processInfo = processInfo() ?: return
            val text = objc.objc_msgSend(objc.objc_getClass("NSString"), objc.sel_registerName("alloc"))
            val reasonString = objc.objc_msgSend(text, objc.sel_registerName("initWithUTF8String:"), reason)
            try {
                val token = objc.objc_msgSend(
                    processInfo,
                    objc.sel_registerName("beginActivityWithOptions:reason:"),
                    NS_ACTIVITY_USER_INITIATED,
                    reasonString,
                ) ?: return
                // Returned autoreleased (drained with the pool below); keep our own reference.
                activity = objc.objc_msgSend(token, objc.sel_registerName("retain"))
            } finally {
                if (reasonString != null) objc.objc_msgSend(reasonString, objc.sel_registerName("release"))
            }
        }
    }

    @Synchronized
    override fun release() {
        val token = activity ?: return
        activity = null
        withAutoreleasePool {
            processInfo()?.let { objc.objc_msgSend(it, objc.sel_registerName("endActivity:"), token) }
            objc.objc_msgSend(token, objc.sel_registerName("release"))
        }
    }

    /** JVM threads have no Cocoa pool; autoreleased results are drained here instead of leaking. */
    private inline fun withAutoreleasePool(block: () -> Unit) {
        val pool = runCatching { objc.objc_autoreleasePoolPush() }.getOrNull()
        try {
            runCatching(block)
        } finally {
            if (pool != null) runCatching { objc.objc_autoreleasePoolPop(pool) }
        }
    }

    private fun processInfo(): Pointer? =
        objc.objc_msgSend(objc.objc_getClass("NSProcessInfo"), objc.sel_registerName("processInfo"))

    /**
     * objc_msgSend must be called with the method's own, non-variadic prototype (arm64 passes
     * variadic arguments on the stack), so each shape used here is its own overload.
     */
    @Suppress("FunctionName")
    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer?
        fun objc_autoreleasePoolPush(): Pointer?
        fun objc_autoreleasePoolPop(pool: Pointer)
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?): Pointer?
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, utf8: String): Pointer?
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, value: Pointer?): Pointer?
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, options: Long, value: Pointer?): Pointer?
    }

    private companion object {
        /** NSActivityUserInitiated: no App Nap, no idle system sleep. */
        const val NS_ACTIVITY_USER_INITIATED = 0x00FFFFFFL
    }
}

/**
 * `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)`. The state belongs to the calling
 * thread and lapses when that thread ends, so every call runs on one thread kept for the
 * process's lifetime rather than on a pooled thread that may be retired.
 */
private class WindowsExecutionStateKeepAwake : DesktopKeepAwake {
    private val kernel32: Kernel32 = Native.load("Kernel32", Kernel32::class.java)
    private val thread = Executors.newSingleThreadExecutor { task ->
        Thread(task, "shinsou-keep-awake").apply { isDaemon = true }
    }

    override fun hold(reason: String) = run(ES_CONTINUOUS or ES_SYSTEM_REQUIRED)

    override fun release() = run(ES_CONTINUOUS)

    private fun run(state: Int) {
        // Best effort: downloads go on regardless; a failure only means the PC may idle-sleep.
        runCatching { thread.submit<Int> { kernel32.SetThreadExecutionState(state) }.get(5, TimeUnit.SECONDS) }
            .onSuccess { previous ->
                if (previous == 0) System.err.println("Shinsou X: SetThreadExecutionState(0x${Integer.toHexString(state)}) failed")
            }
            .onFailure { System.err.println("Shinsou X: could not change the sleep state: $it") }
    }

    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun SetThreadExecutionState(flags: Int): Int
    }

    private companion object {
        const val ES_CONTINUOUS = 0x80000000.toInt()
        const val ES_SYSTEM_REQUIRED = 0x00000001
    }
}
