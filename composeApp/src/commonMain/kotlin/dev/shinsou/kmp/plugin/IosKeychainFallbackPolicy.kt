package dev.shinsou.kmp.plugin

/**
 * Plaintext compatibility is permitted only for an iOS Simulator debug binary. Both inputs come
 * from non-user-controllable platform/build facts; device and release builds always fail closed.
 */
internal fun allowsIosKeychainPlaintextFallback(
    isSimulatorBuild: Boolean,
    isDebugBinary: Boolean,
): Boolean = isSimulatorBuild && isDebugBinary
