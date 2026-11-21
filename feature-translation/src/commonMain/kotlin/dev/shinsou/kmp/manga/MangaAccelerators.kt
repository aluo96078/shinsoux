package dev.shinsou.kmp.manga

/**
 * Where page detection runs (docs/research/manga-gpu-npu.md). Several may be chosen at once:
 * they then share a chapter's pages. Recognition, translation and drawing stay on the CPU.
 */
public enum class MangaAccelerator { CPU, GPU, NPU }

/**
 * A native library set fetched from its publisher rather than shipped with the app: Qualcomm's
 * QNN runtime from Maven Central (the app does not redistribute it). The archive is checked
 * against [sha256], the [entries] for this device are taken out of it and the archive is deleted.
 */
public data class NativeRuntime(
    val id: String,
    val url: String,
    val size: Long,
    val sha256: String,
    /** Paths inside the archive to keep, for this device. */
    val entries: List<String>,
    /** What the kept entries take on disk, for the download size shown to the user. */
    val installedBytes: Long,
)

/** One way to run detection on this device, and what it needs. */
public data class AcceleratorSupport(
    val accelerator: MangaAccelerator,
    /** Null when this device can use it; otherwise the reason it cannot (an English string key). */
    val unsupportedReason: String? = null,
    /** Model packs it needs besides the recognition models every choice needs. */
    val packs: List<ModelPack> = emptyList(),
    val runtime: NativeRuntime? = null,
    /** How it compares (string keys): speed, accuracy, power. */
    val notes: List<String> = emptyList(),
    /** Relative pages per second, for sharing a chapter between several detectors. */
    val weight: Int = 1,
) {
    val supported: Boolean get() = unsupportedReason == null
}

/**
 * Which detector reads which page when several are chosen: by page number and each detector's
 * [AcceleratorSupport.weight], always the same for the same choice, so a page reads the same every
 * time and its cached reading is found again. A smooth weighted round robin: no detector waits
 * long for its next page.
 */
public fun assignPages(pageCount: Int, weights: List<Int>): IntArray {
    require(weights.isNotEmpty() && weights.all { it > 0 })
    val total = weights.sum()
    val current = IntArray(weights.size)
    val cycle = IntArray(total) {
        for (k in weights.indices) current[k] += weights[k]
        val chosen = current.indices.maxBy { current[it] }
        current[chosen] -= total
        chosen
    }
    return IntArray(pageCount) { cycle[it % total] }
}

/** The notes shown for each kind of detector (English string keys, translated in the UI). */
public object AcceleratorNotes {
    public val CPU: List<String> = listOf(
        "Works on any device.",
        "Slower, and uses more power.",
    )
    public val COREML_GPU: List<String> = listOf(
        "Faster, and finds more text drawn over the artwork.",
        "Reads exactly like the full model.",
    )
    public val COREML_NPU: List<String> = listOf(
        "Faster, and uses the least power.",
        "Boxes are slightly less precise.",
    )
    public val MAC_GPU: List<String> = listOf(
        "Faster, and finds more text drawn over the artwork.",
        "Reads like the full model. Prepared once on this device the first time.",
    )
    public val MAC_NPU: List<String> = listOf(
        "Uses less power, but slower.",
        "Boxes are less precise, and a few extra may be found.",
    )
    public val QNN_NPU: List<String> = listOf(
        "Faster, uses less power, and finds more text drawn over the artwork.",
        "Reads exactly like the full model. Prepared once on this device the first time.",
    )
}

/**
 * Qualcomm's NPU (the Hexagon HTP) by chip: which generation of the QNN runtime's device-side
 * libraries a Snapdragon needs. Only v73 and later run the detector (Snapdragon 8 Gen 2 onward,
 * and the 7+ Gen 3 / 8s Gen 3 that share its NPU); older chips are left to the CPU.
 */
public object QnnSoc {
    private val ARCH = mapOf(
        "SM8550" to 73, // Snapdragon 8 Gen 2
        "SM8635" to 73, // 8s Gen 3
        "SM7675" to 73, // 7+ Gen 3
        "SM8650" to 75, // 8 Gen 3
        "SM8750" to 79, // 8 Elite
        "SM8850" to 81, // 8 Elite Gen 5
    )

    /** The NPU generation of the chip [socModel] (Android's `Build.SOC_MODEL`), or null if unsupported. */
    public fun htpArch(socModel: String?): Int? {
        val model = socModel?.trim()?.uppercase() ?: return null
        return ARCH.entries.firstOrNull { model.startsWith(it.key) }?.value
    }

    private const val DIR = "jni/arm64-v8a/"
    private val COMMON = mapOf("libQnnSystem.so" to 2_689_368L, "libQnnHtp.so" to 2_642_248L, "libQnnHtpPrepare.so" to 83_686_128L)
    private val DEVICE = mapOf(
        73 to (638_216L + 16_316_880L),
        75 to (638_216L + 16_320_864L),
        79 to (638_216L + 16_141_624L),
        81 to (714_272L + 17_148_408L),
    )

    /** What to keep of the QNN runtime archive for an NPU of generation [arch]. */
    public fun runtime(arch: Int): NativeRuntime {
        val device = DEVICE[arch] ?: throw IllegalArgumentException("No QNN libraries for HTP v$arch")
        return NativeRuntime(
            id = "qnn-runtime-${MangaModelCatalog.QNN_RUNTIME_VERSION}-v$arch",
            url = MangaModelCatalog.QNN_RUNTIME_URL,
            size = MangaModelCatalog.QNN_RUNTIME_SIZE,
            sha256 = MangaModelCatalog.QNN_RUNTIME_SHA256,
            entries = COMMON.keys.map { DIR + it } + listOf("${DIR}libQnnHtpV${arch}Stub.so", "${DIR}libQnnHtpV${arch}Skel.so"),
            installedBytes = COMMON.values.sum() + device,
        )
    }
}
