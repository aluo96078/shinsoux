package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.plugin.SandboxOnlyScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.ScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.sandbox.DesktopSandboxTransportProvider
import dev.shinsou.kmp.plugin.sandbox.SandboxRuntimeFactory
import dev.shinsou.kmp.plugin.sandbox.sandboxHelperBinary
import org.junit.Assume.assumeTrue
import kotlin.test.BeforeTest

/**
 * ADR-0002 §10.1: every package runs in the sandbox, the reviewed ShuYue catalogue included, so the
 * whole reviewed suite runs again in the OS-confined helper the app ships.
 */
class ProductionShuYueReviewedRuntimeV2SandboxTest : ProductionShuYueReviewedRuntimeV2Test() {
    private val helper = sandboxHelperBinary()

    override val largeResultsSupported: Boolean = false

    @BeforeTest
    fun requireHelper() = assumeTrue("shinsou-sandbox not built", helper != null)

    override fun reviewedEngine(): ScriptPluginRuntimeFactory =
        SandboxOnlyScriptPluginRuntimeFactory(SandboxRuntimeFactory(DesktopSandboxTransportProvider { helper!!.toPath() }))
}
