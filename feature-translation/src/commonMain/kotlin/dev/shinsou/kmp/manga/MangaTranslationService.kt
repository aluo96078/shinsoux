package dev.shinsou.kmp.manga

/**
 * What the app's screens use of [MangaTranslation] (0007 B4), as a bundle: the platform hosts
 * implement it and the composition root hands it over. 0008: code depends on the narrow
 * interfaces it is made of instead (ServiceInterfaceSizeTest lists the files still naming it):
 * the reader on [MangaChapterTranslation], the settings on [MangaProcessingSettings],
 * [MangaAccelerators] and [MangaResources].
 */
public interface MangaTranslationService : MangaChapterTranslation, MangaProcessingSettings, MangaAccelerators, MangaResources

/** This device's support for manga translation, how it shares the models and the last chapter's timings. */
public interface MangaDevice {
    /** How this device shares the models; null where unsupported. */
    public val deviceTuning: MangaTuning?

    /** The last chapter's timings (developer diagnostics). */
    public val lastRun: MangaRunStats?

    /** False on devices the inference and image libraries are not shipped for. */
    public val deviceSupported: Boolean
}

/** What a chapter needs on the device before it is translated: the models, a language's pack and the optional LaMa pack. */
public interface MangaDownloads {
    public fun modelsInstalled(): Boolean

    public fun missingModelBytes(): Long

    public suspend fun downloadModels(onProgress: (received: Long, total: Long) -> Unit)

    /** What a chapter from [from] into [to] still needs to download; zero when all is here. */
    public fun missingLanguageBytes(from: String, to: String): Long

    /** Downloads what [missingLanguageBytes] counts; chapters started from then on use it. */
    public suspend fun downloadLanguage(from: String, to: String, onProgress: (received: Long, total: Long) -> Unit)

    /** Whether the optional LaMa pack is here: text drawn over the artwork is then translated too. */
    public fun inpaintInstalled(): Boolean

    public fun missingInpaintBytes(): Long

    /** Downloads the LaMa pack; chapters started from then on use it. */
    public suspend fun downloadInpaint(onProgress: (received: Long, total: Long) -> Unit)

    /** Removes the LaMa pack; text over artwork stays as drawn again. */
    public suspend fun removeInpaint()
}

/** Translating a chapter, and the per-work language probes. */
public interface MangaChapterTranslator {
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
}

/** What the readers use: the device's support, the downloads a chapter needs and translating it. */
public interface MangaChapterTranslation : MangaDevice, MangaDownloads, MangaChapterTranslator

/** How chapters are translated and how hard the CPU works on pages. */
public interface MangaProcessingSettings {
    /** How chapters are translated: as a whole, or in parts as they are read. */
    public fun translationFlow(): MangaTranslationFlow

    public fun setTranslationFlow(flow: MangaTranslationFlow)

    /** How hard the CPU works on pages. */
    public fun cpuMode(): MangaCpuMode

    /** Chooses how hard the CPU works; chapters started from then on use it. */
    public suspend fun setCpuMode(mode: MangaCpuMode)
}

/** The ways this device can run detection. */
public interface MangaAccelerators {
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

/** Everything manga translation downloads on this device, for the settings to list and manage. */
public interface MangaResources {
    /** Everything manga translation downloads on this device, for the settings to list and manage. */
    public fun resources(): List<MangaResource>

    /** Downloads what [resource] still misses; an accelerator's download also chooses it. */
    public suspend fun downloadResource(resource: MangaResource, onProgress: (received: Long, total: Long) -> Unit, onPreparing: () -> Unit = {})

    /** Deletes [resource] from this device; chapters that need it offer the download again. */
    public suspend fun removeResource(resource: MangaResource)
}
