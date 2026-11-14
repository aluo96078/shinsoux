package dev.shinsou.kmp.translation

/** Where the files of an installed offline model are on this device. */
public data class OfflineModelPaths(
    val model: String,
    val sourceVocab: String,
    val targetVocab: String,
    val shortlist: String,
)

/** The installed offline models and their download. Implemented per platform. */
public interface OfflineModelFiles {
    /** Paths of [model]'s files when every one is installed and has its pinned size; null otherwise. */
    public fun installed(model: OfflineModel): OfflineModelPaths?

    /**
     * Downloads the files of [model] that are missing, checking each against its pinned SHA-256
     * before it is put in place. [onProgress] reports bytes of this model fetched and in total.
     */
    public suspend fun download(model: OfflineModel, onProgress: (received: Long, total: Long) -> Unit = { _, _ -> })

    public fun remove(model: OfflineModel)
}

/** The native engine: one model in memory at a time. Implemented per platform. */
public interface OfflineTranslationEngine {
    /** Translates [lines] one for one. Loading a different model releases the previous one. */
    public suspend fun translate(model: OfflineModelPaths, lines: List<String>): List<String>

    /** Releases the loaded model and its memory. */
    public suspend fun unload()
}

/** Whether the device has room for a model right now. */
public enum class TranslationMemoryStatus {
    ENOUGH,

    /** Other apps hold the memory at the moment; closing them or trying later can help. */
    LOW_NOW,

    /** The device never has this much to give one app. */
    DEVICE_TOO_SMALL,
}

/**
 * Asked before a model is loaded. A loaded model takes about 350 MB (ADR-0004 §3.4); on a phone
 * the system ends an app that takes more than it can give, so the reader must refuse first.
 */
public fun interface TranslationMemoryGuard {
    public fun status(requiredBytes: Long): TranslationMemoryStatus

    public companion object {
        /** Desktop: the helper is a process of its own and memory is not the scarce resource. */
        public val Unrestricted: TranslationMemoryGuard = TranslationMemoryGuard { TranslationMemoryStatus.ENOUGH }
    }
}

/**
 * The free provider: translation on the device, nothing leaves it. Any pair that is not paired
 * with English runs as two hops, one model at a time.
 */
public class OfflineTranslationProvider(
    private val files: OfflineModelFiles,
    private val engine: OfflineTranslationEngine,
    private val memory: TranslationMemoryGuard = TranslationMemoryGuard.Unrestricted,
) : TranslationProvider {
    override val id: String get() = ID
    override val paid: Boolean get() = false

    override fun route(from: String, to: String): List<TranslationHop>? =
        OfflineModelCatalog.route(from, to)?.map { TranslationHop(it.from, it.to, it.engine) }

    /** The models [from] to [to] needs that are not installed yet. */
    public fun missingModels(from: String, to: String): List<OfflineModel> =
        OfflineModelCatalog.route(from, to).orEmpty().filter { files.installed(it) == null }

    override suspend fun translate(hop: TranslationHop, segments: List<String>): List<String> {
        val model = OfflineModelCatalog.models.firstOrNull { it.engine == hop.engine }
            ?: throw TranslationException(TranslationFailure.UNSUPPORTED_PAIR, "No offline model for ${hop.engine}")
        val paths = files.installed(model)
            ?: throw TranslationException(TranslationFailure.MODEL_MISSING, "The ${model.from} to ${model.to} model is not downloaded")
        // The model the engine already holds costs nothing more; another one is let go first,
        // so the check sees the memory that loading will really have.
        if (loaded != paths) {
            if (loaded != null) unload()
            when (memory.status(MODEL_MEMORY_BYTES)) {
                TranslationMemoryStatus.ENOUGH -> Unit
                TranslationMemoryStatus.LOW_NOW ->
                    throw TranslationException(TranslationFailure.LOW_MEMORY, "Not enough free memory to load the translation model")
                TranslationMemoryStatus.DEVICE_TOO_SMALL ->
                    throw TranslationException(TranslationFailure.DEVICE_UNSUPPORTED, "This device has too little memory for offline translation")
            }
        }
        // Set before the call: a failed or cancelled translation may still have loaded the model.
        loaded = paths
        // A paragraph longer than the engine takes in one piece goes sentence by sentence.
        val pieces = segments.map { splitLongLine(it) }
        if (pieces.all { it.size == 1 }) return engine.translate(paths, segments)
        val answers = engine.translate(paths, pieces.flatten())
        val joiner = if (model.to == "en" || model.to == "ko") " " else ""
        var next = 0
        return pieces.map { parts -> answers.subList(next, next + parts.size).joinToString(joiner) { it.trim() }.also { next += parts.size } }
    }

    @kotlin.concurrent.Volatile
    private var loaded: OfflineModelPaths? = null

    /** Lets go of the loaded model's memory. */
    public suspend fun unload() {
        loaded = null
        engine.unload()
    }

    /** Whether this device can run the engine at all, whatever is free at the moment. */
    public val deviceSupported: Boolean
        get() = memory.status(MODEL_MEMORY_BYTES) != TranslationMemoryStatus.DEVICE_TOO_SMALL

    public companion object {
        public const val ID: String = "offline"

        /** Well under the engine's 64 KiB a line, whatever the script. */
        internal const val MAX_LINE_CHARACTERS: Int = 4_000
        private const val SENTENCE_ENDS = "。！？!?.…\n"

        /** [line] in pieces of at most [MAX_LINE_CHARACTERS], cut after a sentence where there is one. */
        internal fun splitLongLine(line: String): List<String> {
            if (line.length <= MAX_LINE_CHARACTERS) return listOf(line)
            val pieces = ArrayList<String>()
            var start = 0
            while (line.length - start > MAX_LINE_CHARACTERS) {
                val limit = start + MAX_LINE_CHARACTERS
                var cut = limit
                for (index in limit - 1 downTo start + MAX_LINE_CHARACTERS / 2) {
                    if (line[index] in SENTENCE_ENDS) { cut = index + 1; break }
                }
                // Never between the halves of a surrogate pair.
                if (cut < line.length && line[cut].isLowSurrogate()) cut--
                pieces += line.substring(start, cut)
                start = cut
            }
            pieces += line.substring(start)
            return pieces.filter { it.isNotBlank() }.ifEmpty { listOf(line) }
        }

        /** What loading and running one model needs, with headroom over the measured peak of about 400 MB. */
        public const val MODEL_MEMORY_BYTES: Long = 512L * 1024 * 1024
    }
}
