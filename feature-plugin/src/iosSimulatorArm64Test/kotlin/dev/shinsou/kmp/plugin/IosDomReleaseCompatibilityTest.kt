package dev.shinsou.kmp.plugin

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class IosDomReleaseCompatibilityTest {
    @Test
    fun parsedListsSurviveDomReleaseAndRepeatedInvocations() = runTest {
        verifyDomReleaseListCompatibility(JavaScriptCoreScriptPluginRuntimeFactory.unsafeForTests())
    }
}
