package dev.shinsou.kmp.plugin.runtime

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class IosDomReleaseCompatibilityTest {
    @Test
    fun parsedListsSurviveDomReleaseAndRepeatedInvocations() = runTest {
        verifyDomReleaseListCompatibility(JavaScriptCoreScriptPluginRuntimeFactory.unsafeForTests())
    }
}
