package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosKeychainFallbackPolicyTest {
    @Test
    fun plaintextFallbackRequiresSimulatorAndDebugBinary() {
        assertTrue(allowsIosKeychainPlaintextFallback(isSimulatorBuild = true, isDebugBinary = true))
        assertFalse(allowsIosKeychainPlaintextFallback(isSimulatorBuild = false, isDebugBinary = true))
        assertFalse(allowsIosKeychainPlaintextFallback(isSimulatorBuild = true, isDebugBinary = false))
        assertFalse(allowsIosKeychainPlaintextFallback(isSimulatorBuild = false, isDebugBinary = false))
    }
}
