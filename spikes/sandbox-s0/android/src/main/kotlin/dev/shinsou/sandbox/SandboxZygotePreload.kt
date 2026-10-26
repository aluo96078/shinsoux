package dev.shinsou.sandbox

import android.app.ZygotePreload
import android.content.pm.ApplicationInfo

/**
 * Runs once in the app zygote. Loading the .so here means each isolated process forks with the
 * library already mapped; S0 measures how much that saves on cold start.
 */
class SandboxZygotePreload : ZygotePreload {
    override fun doPreload(appInfo: ApplicationInfo) {
        try {
            NativeCore.loadLibrary()
        } catch (_: UnsatisfiedLinkError) {
            // Fall back to loading in the service; preloading is an optimisation only.
        }
    }
}
