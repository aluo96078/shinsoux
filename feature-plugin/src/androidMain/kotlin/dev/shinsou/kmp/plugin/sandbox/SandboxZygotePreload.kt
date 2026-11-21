package dev.shinsou.kmp.plugin.sandbox

import android.app.ZygotePreload
import android.content.pm.ApplicationInfo

/**
 * Runs once in the app zygote that sandbox processes fork from (`useAppZygote`, ADR-0002 §4.3):
 * loading sandbox-core here means every sandbox starts with it mapped. Nothing else happens here,
 * and the lockdown is still installed per sandbox process after the fork.
 */
class SandboxZygotePreload : ZygotePreload {
    override fun doPreload(appInfo: ApplicationInfo) {
        try {
            System.loadLibrary("shinsou_sandbox")
        } catch (_: UnsatisfiedLinkError) {
            // An ABI without sandbox-core: SandboxProcessService fails closed when it tries again.
        }
    }
}
