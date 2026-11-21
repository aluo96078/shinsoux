package dev.shinsou.kmp.manga

import kotlin.math.max
import kotlin.math.min

/**
 * How hard the CPU works on manga pages (recognition, and detection and redrawing when they run on
 * the CPU), chosen by the user; kept on the device.
 */
public enum class MangaCpuMode {
    /** One thread, on an efficiency core where the device has them: slowest, least power. */
    SAVER,
    /** About half the cores. */
    BALANCED,
    /** Every core: the fastest measured. */
    FULL,
}

/** What this device can give the page models (ADR-0005 §4). */
public data class MangaDeviceProfile(
    /** Cores of the fastest kind (Apple's performance cores, Android's big cores); the rest are [efficiencyCores]. */
    val performanceCores: Int,
    val efficiencyCores: Int = 0,
    val memoryMb: Long,
    /** Low power mode, or the device is hot: a third of the work at a time. */
    val constrained: Boolean = false,
)

/**
 * How the page models share the device (ADR-0005 §8). OCR decodes a character at a time, so more
 * threads for one reader barely help (on an M4 Pro four is best, eight slower), while several
 * readers of two threads each, on different pages, scale almost linearly and read exactly the
 * same; the same workers draw the pages. Detection gains nothing past two threads alongside them.
 */
public data class MangaTuning(
    val detectorThreads: Int,
    /** Readers working on different pages at once, each with its own models. */
    val ocrWorkers: Int,
    val ocrThreads: Int,
    /** Text over artwork is redrawn while nothing else runs: every core it may use. */
    val inpaintThreads: Int,
    /** Pages fetched ahead of detection (about a megabyte each, encoded). */
    val pageLoadsAhead: Int,
    /** Everything on one thread that the platform keeps on its efficiency cores ([MangaCpuMode.SAVER]). */
    val efficiencyThread: Boolean = false,
) {
    public companion object {
        public const val OCR_THREADS: Int = 2
        /** Measured best on an M4 Pro (8+4 cores), a phone with 6 big and 2 little cores and an iPhone with 2+4. */
        private const val MAX_OCR_WORKERS: Int = 3

        /** The tuning for [mode]: [MangaCpuMode.FULL] is the fastest measured, the others give up speed for power. */
        public fun of(profile: MangaDeviceProfile, mode: MangaCpuMode = MangaCpuMode.FULL): MangaTuning {
            val full = full(profile)
            return when (mode) {
                MangaCpuMode.FULL -> full
                // half the cores: at most two readers, the rest of the device left alone
                MangaCpuMode.BALANCED -> {
                    val all = max(1, profile.performanceCores + profile.efficiencyCores).let { if (profile.constrained) max(2, it / 3) else it }
                    val cores = (all + 1) / 2
                    full.copy(
                        detectorThreads = if (cores <= 2) 1 else 2,
                        ocrWorkers = (cores / 2).coerceIn(1, min(2, full.ocrWorkers)),
                        ocrThreads = if (cores <= 2) 1 else OCR_THREADS,
                        inpaintThreads = min(full.inpaintThreads, max(1, cores)),
                    )
                }
                // one thread for everything, on an efficiency core where the platform can ask for one
                MangaCpuMode.SAVER -> MangaTuning(
                    detectorThreads = 1, ocrWorkers = 1, ocrThreads = 1, inpaintThreads = 1,
                    pageLoadsAhead = min(full.pageLoadsAhead, 2), efficiencyThread = true,
                )
            }
        }

        private fun full(profile: MangaDeviceProfile): MangaTuning {
            // every core counts: an iPhone's four efficiency cores more than double its two performance
            // ones for this work; on a Mac the readers stay within the performance cores anyway
            val cores = max(1, profile.performanceCores + profile.efficiencyCores).let { if (profile.constrained) max(2, it / 3) else it }
            // each reader holds its own encoder and decoder (about 40 MB with their buffers)
            val memoryWorkers = when {
                profile.memoryMb < 4_000 -> 1
                profile.memoryMb < 8_000 -> 2
                else -> MAX_OCR_WORKERS
            }
            // detection gains nothing past two threads; readers of two threads each, one per two cores
            val workers = (cores / 2).coerceIn(1, min(MAX_OCR_WORKERS, memoryWorkers))
            val ocrThreads = if (cores <= 2) 1 else OCR_THREADS
            val detectorThreads = if (cores <= 2) 1 else 2
            val ahead = when {
                profile.memoryMb >= 6_000 -> 6
                profile.memoryMb >= 3_000 -> 3
                else -> 2
            }
            // LaMa's convolutions wait for their slowest thread: the fast cores only, unless there are few
            val inpaint = if (profile.performanceCores >= 4 && !profile.constrained) profile.performanceCores else cores
            return MangaTuning(detectorThreads, workers, ocrThreads, inpaintThreads = min(inpaint, 8), pageLoadsAhead = ahead)
        }
    }
}

/**
 * Big cores from each core's highest frequency (Android's cpufreq): those within 80% of the
 * fastest. Null when nothing could be read.
 */
public fun bigCores(maxFrequencies: List<Long>): Int? {
    val top = maxFrequencies.maxOrNull()?.takeIf { it > 0 } ?: return null
    return maxFrequencies.count { it >= top * 0.8 }
}
