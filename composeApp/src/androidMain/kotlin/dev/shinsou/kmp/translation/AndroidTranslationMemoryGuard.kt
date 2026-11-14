package dev.shinsou.kmp.translation

import android.app.ActivityManager
import android.content.Context

/**
 * Android's answer to "is there room for a model": the system ends the app that takes memory it
 * cannot give, so a model is only loaded when the device reports enough free memory above its
 * own low-memory threshold.
 */
public class AndroidTranslationMemoryGuard(context: Context) : TranslationMemoryGuard {
    private val activityManager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    override fun status(requiredBytes: Long): TranslationMemoryStatus {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return status(info.totalMem, info.availMem, info.threshold, info.lowMemory, requiredBytes)
    }

    internal companion object {
        /** Devices below this (sold as "2 GB" and less) keep too little for one app once the system has its share. */
        const val MINIMUM_DEVICE_BYTES: Long = 2_800L * 1024 * 1024

        fun status(totalBytes: Long, availableBytes: Long, lowThresholdBytes: Long, lowMemory: Boolean, requiredBytes: Long): TranslationMemoryStatus = when {
            totalBytes < MINIMUM_DEVICE_BYTES -> TranslationMemoryStatus.DEVICE_TOO_SMALL
            lowMemory || availableBytes - lowThresholdBytes < requiredBytes -> TranslationMemoryStatus.LOW_NOW
            else -> TranslationMemoryStatus.ENOUGH
        }
    }
}
