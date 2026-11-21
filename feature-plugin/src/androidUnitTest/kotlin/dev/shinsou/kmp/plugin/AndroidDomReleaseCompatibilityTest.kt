package dev.shinsou.kmp.plugin

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AndroidDomReleaseCompatibilityTest {
    @Test
    fun parsedListsSurviveDomReleaseAndRepeatedInvocations() = runTest {
        verifyDomReleaseListCompatibility(RhinoScriptPluginRuntimeFactory.unsafeForTests())
    }
}
