package dev.shinsou.kmp.translation

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSProcessInfo
import dev.shinsou.interop.translation.stc_available_memory

/**
 * iOS's answer to "is there room for a model": how much this app may still take before the
 * system ends it. That figure is zero where it is not reported (the simulator); the device's
 * memory size alone decides there.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosTranslationMemoryGuard : TranslationMemoryGuard {
    override fun status(requiredBytes: Long): TranslationMemoryStatus = status(
        totalBytes = NSProcessInfo.processInfo.physicalMemory.toLong(),
        availableBytes = stc_available_memory().toLong(),
        requiredBytes = requiredBytes,
    )

    internal companion object {
        /** iPhones and iPads with 2 GB give one app well under what a model and the reader need together. */
        const val MINIMUM_DEVICE_BYTES: Long = 2_800L * 1024 * 1024

        fun status(totalBytes: Long, availableBytes: Long, requiredBytes: Long): TranslationMemoryStatus = when {
            totalBytes < MINIMUM_DEVICE_BYTES -> TranslationMemoryStatus.DEVICE_TOO_SMALL
            availableBytes in 1 until requiredBytes -> TranslationMemoryStatus.LOW_NOW
            else -> TranslationMemoryStatus.ENOUGH
        }
    }
}
