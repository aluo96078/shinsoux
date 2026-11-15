package dev.shinsou.kmp.translation

/** Whether the operating system's translation can serve a language pair on this device. */
public enum class SystemLanguageStatus {
    /** Its languages are on the device: it translates offline at once. */
    INSTALLED,

    /** The system supports the pair, but its languages have to be downloaded first. */
    DOWNLOADABLE,

    /** The system cannot translate between these languages. */
    UNSUPPORTED,
}

/**
 * The translation built into the operating system (Apple's Translation framework on iOS):
 * on the device, free, with its language packs managed and kept by the system rather than
 * by the app.
 */
public interface SystemTranslationEngine {
    /** A short, stable name for the engine (part of what stored translations are keyed by). */
    public val name: String

    /** False where this system has no translation to offer (an older version, for example). */
    public val available: Boolean get() = true

    public suspend fun status(from: String, to: String): SystemLanguageStatus

    /** Translates [lines] one for one. Throws when the languages are not installed. */
    public suspend fun translate(from: String, to: String, lines: List<String>): List<String>

    /**
     * Asks the system to download the pair's languages, showing its own prompt. True once they
     * are installed; false when the user declined or the download failed.
     */
    public suspend fun download(from: String, to: String): Boolean
}

/** [SystemTranslationEngine] as a provider of the translation service. */
internal class SystemTranslationProvider(private val engine: SystemTranslationEngine) : TranslationProvider {
    override val id: String get() = ID
    override val paid: Boolean get() = false

    override fun route(from: String, to: String): List<TranslationHop>? {
        val source = OfflineModelCatalog.language(from) ?: return null
        val target = OfflineModelCatalog.language(to) ?: return null
        if (source == target) return null
        // The system translates directly: no pivot through English.
        return listOf(TranslationHop(source, target, currentEngine))
    }

    // Upgrading the system can switch its models (high fidelity to low latency): the older
    // translations are another engine's, and their chapters are not done for this one.
    override val currentEngine: String get() = "system:${engine.name}"

    override suspend fun translate(hop: TranslationHop, segments: List<String>): List<String> {
        when (engine.status(hop.from, hop.to)) {
            SystemLanguageStatus.INSTALLED -> Unit
            SystemLanguageStatus.DOWNLOADABLE ->
                throw TranslationException(TranslationFailure.SYSTEM_LANGUAGE_MISSING, "The system's ${hop.from} to ${hop.to} languages are not downloaded")
            SystemLanguageStatus.UNSUPPORTED ->
                throw TranslationException(TranslationFailure.UNSUPPORTED_PAIR, "The system cannot translate ${hop.from} to ${hop.to}")
        }
        val answers = try {
            engine.translate(hop.from, hop.to, segments)
        } catch (error: TranslationException) {
            throw error
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw TranslationException(TranslationFailure.ENGINE_FAILED, "The system translation failed: ${error.message}", error)
        }
        if (answers.size != segments.size) {
            throw TranslationException(TranslationFailure.ENGINE_FAILED, "The system returned ${answers.size} lines for ${segments.size}")
        }
        return answers
    }

    companion object {
        const val ID: String = "system"
    }
}
