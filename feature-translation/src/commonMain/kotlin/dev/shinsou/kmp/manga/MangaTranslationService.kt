package dev.shinsou.kmp.manga

/**
 * What the app's screens use of [MangaTranslation] (0007 B4): the device's support and settings,
 * the downloads it manages, the per-work language probes and translating a chapter.
 */
public interface MangaTranslationService {
    /** How this device shares the models; null where unsupported. */
    public val deviceTuning: MangaTuning?

    /** The last chapter's timings (developer diagnostics). */
    public val lastRun: MangaRunStats?

    /** False on devices the inference and image libraries are not shipped for. */
    public val deviceSupported: Boolean

    /** How chapters are translated: as a whole, or in parts as they are read. */
    public fun translationFlow(): MangaTranslationFlow

    public fun setTranslationFlow(flow: MangaTranslationFlow)

    /** How hard the CPU works on pages. */
    public fun cpuMode(): MangaCpuMode

    /** Chooses how hard the CPU works; chapters started from then on use it. */
    public suspend fun setCpuMode(mode: MangaCpuMode)

    public fun modelsInstalled(): Boolean

    public fun missingModelBytes(): Long

    public suspend fun downloadModels(onProgress: (received: Long, total: Long) -> Unit)

    /** What a chapter from [from] into [to] still needs to download; zero when all is here. */
    public fun missingLanguageBytes(from: String, to: String): Long

    /** Downloads what [missingLanguageBytes] counts; chapters started from then on use it. */
    public suspend fun downloadLanguage(from: String, to: String, onProgress: (received: Long, total: Long) -> Unit)

    /** The ways this device can run detection, which are chosen, and what each still needs. */
    public fun acceleratorStates(): List<AcceleratorState>

    /** Downloads what [accelerator] needs, then readies it; [onPreparing] is told when the preparation starts. */
    public suspend fun downloadAccelerator(
        accelerator: MangaAccelerator,
        onProgress: (received: Long, total: Long) -> Unit,
        onPreparing: () -> Unit = {},
    )

    /** Removes what [accelerator] downloaded and stops using it. */
    public suspend fun removeAccelerator(accelerator: MangaAccelerator)

    /** Chooses where detection runs (at least one); chapters started from then on use the choice. */
    public suspend fun selectAccelerators(accelerators: Set<MangaAccelerator>)

    /** Everything manga translation downloads on this device, for the settings to list and manage. */
    public fun resources(): List<MangaResource>

    /** Downloads what [resource] still misses; an accelerator's download also chooses it. */
    public suspend fun downloadResource(resource: MangaResource, onProgress: (received: Long, total: Long) -> Unit, onPreparing: () -> Unit = {})

    /** Deletes [resource] from this device; chapters that need it offer the download again. */
    public suspend fun removeResource(resource: MangaResource)

    /** Whether the optional LaMa pack is here: text drawn over the artwork is then translated too. */
    public fun inpaintInstalled(): Boolean

    public fun missingInpaintBytes(): Long

    /** Downloads the LaMa pack; chapters started from then on use it. */
    public suspend fun downloadInpaint(onProgress: (received: Long, total: Long) -> Unit)

    /** Removes the LaMa pack; text over artwork stays as drawn again. */
    public suspend fun removeInpaint()

    /**
     * Translates [request]'s pages and hands each finished page to [onPage] as JPEG bytes (null
     * for a page that could not be read). Returns whether every line was translated and every page read.
     */
    public suspend fun translateChapter(
        request: MangaChapterRequest,
        onProgress: (MangaProgress) -> Unit = {},
        onPage: suspend (index: Int, jpeg: ByteArray?) -> Unit,
    ): Boolean

    /** Whether a work whose language its source does not say reads as Japanese; null when that could not be told. */
    public suspend fun readsJapanese(workKey: String, pageCount: Int, loadPage: suspend (Int) -> ByteArray?): Boolean?

    /** The script (`zh-Hans` or `zh-Hant`) of a Chinese work whose source does not say it; null when that could not be told. */
    public suspend fun readsChineseScript(workKey: String, pageCount: Int, loadPage: suspend (Int) -> ByteArray?): String?

    /** One way to run detection, as the settings list it. */
    public data class AcceleratorState(
        val support: AcceleratorSupport,
        val selected: Boolean,
        /** Its models (and runtime) are on the device. */
        val installed: Boolean,
        /** What its download would bring (its runtime as kept on the device). */
        val missingBytes: Long,
    )
}
