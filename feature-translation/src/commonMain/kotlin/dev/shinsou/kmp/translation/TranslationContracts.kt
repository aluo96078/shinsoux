package dev.shinsou.kmp.translation

/**
 * One step of a translation: a single engine turning [from] into [to]. Offline models only pair
 * a language with English, so Japanese to Chinese is two hops; a cloud model is usually one.
 * [engine] names the exact engine and model version: a translation memory entry is only reused
 * for the engine that produced it.
 */
public data class TranslationHop(val from: String, val to: String, val engine: String) {
    init {
        require(from.isNotBlank() && to.isNotBlank() && engine.isNotBlank()) { "Incomplete translation hop" }
    }
}

/** Why a translation could not be produced; the reader shows these differently. */
public enum class TranslationFailure {
    /** The provider cannot translate this language pair at all. */
    UNSUPPORTED_PAIR,

    /** A model (or other local resource) has to be downloaded first. */
    MODEL_MISSING,

    /** The device cannot run the engine at all (too little memory, or no engine for its processor). */
    DEVICE_UNSUPPORTED,

    /** The device could run the engine, but too little memory is free right now. */
    LOW_MEMORY,

    /** The service could not be reached or refused for a reason of its own. */
    PROVIDER_UNAVAILABLE,

    /** No key is set for the chosen service, or the service rejected it. */
    INVALID_KEY,

    /** The service says the key's quota is used up: nothing sent with it will work until it resets. */
    QUOTA_EXCEEDED,

    /** The service asked to slow down, and kept asking after a short wait: later requests may work. */
    RATE_LIMITED,

    /** The user's own daily limit for paid translation. */
    DAILY_LIMIT,

    /** The engine ran and failed. */
    ENGINE_FAILED,

    /** The chosen service still needs its address or model set. */
    NOT_CONFIGURED,

    /** The model declined to translate this text under its provider's content rules. */
    REFUSED,

    /** The system's translation needs its languages downloaded (through the system's own prompt). */
    SYSTEM_LANGUAGE_MISSING,
}

public class TranslationException(
    public val failure: TranslationFailure,
    message: String,
    cause: Throwable? = null,
    /**
     * Answers for the first lines of the call that failed, when the provider had already got
     * them: they are kept, so a retry does not send (and pay for) those lines again.
     */
    public val completed: List<String> = emptyList(),
    /** How long the service asked to wait before trying again, when it said. */
    public val retryAfterMillis: Long? = null,
) : Exception(message, cause)

/**
 * A source of translations: the bundled offline engine, or (later) a service the user supplies a
 * key for. Providers know nothing about chapters, caching or glossaries; [TranslationService]
 * handles those, so every provider gets them.
 */
public interface TranslationProvider {
    /** Stable id, stored with chapter records (`offline`, `openai-compatible`, ...). */
    public val id: String

    /** True when using this provider costs the user money; such translations are never evicted. */
    public val paid: Boolean

    /**
     * The hops this provider would use for the pair, or null when it cannot translate it.
     * Must not do any work: availability problems are reported by [translate].
     */
    public fun route(from: String, to: String): List<TranslationHop>?

    /**
     * Translates [segments] through one [hop], one output per input, in order.
     * @throws TranslationException when the hop cannot be run.
     */
    public suspend fun translate(hop: TranslationHop, segments: List<String>): List<String>

    /**
     * Lines to hand over in one [translate] call, for a provider that sends several requests
     * of a call side by side; null leaves it to the caller.
     */
    public val linesPerCall: Int? get() = null

    /**
     * The engine every hop goes through, when it is one whatever the pair and may change under
     * the same [id] (another model, another system strategy). A chapter recorded under [id] is
     * then only done if its lines came from this engine. Null where the pair decides the engine.
     */
    public val currentEngine: String? get() = null
}

/** One line of a chapter with its translation, when there is one. */
public data class TranslatedSegment(
    val source: String,
    /** Null for blank lines, kept lines and lines not translated yet. */
    val translation: String?,
    /** True for a line that is shown as it is on purpose (an image marker): it needs no translation. */
    val kept: Boolean = false,
)

public data class ChapterTranslation(
    val segments: List<TranslatedSegment>,
    /** Segments whose translation came from the translation memory, not from the provider. */
    val reusedSegments: Int,
    /** Segments the provider translated in this call. */
    val translatedSegments: Int,
) {
    val complete: Boolean get() = segments.all { it.translation != null || it.kept || it.source.isBlank() }

    /** The translated chapter as text, line for line with the source. */
    public fun text(): String = segments.joinToString("\n") { it.translation ?: it.source }
}

public data class ChapterTranslationRequest(
    /** Cross-device identity of the work and chapter (the sync identity), for the chapter record. */
    val workKey: String,
    val chapterKey: String,
    val text: String,
    val from: String,
    val to: String,
    /** Lines that are kept as they are (image markers, for example) rather than translated. */
    val keep: (String) -> Boolean = { false },
    /** Whether the content's grant allows export: only then may its translation go into a backup. */
    val exportable: Boolean = false,
    /** What the reader calls this work and chapter, so stored translations can be listed by name. */
    val labels: TranslationLabels? = null,
    /**
     * [text] is only part of the chapter (a manga page could not be loaded): its lines are kept,
     * but the chapter is not recorded as translated, so translating ahead does not pass over it.
     */
    val partial: Boolean = false,
    /**
     * [text] is one part of a chapter translated ahead of the rest, to show its pages sooner:
     * its lines are added to the chapter's, which is recorded as incomplete when nothing is
     * recorded yet; the request for the whole chapter records it as it is.
     */
    val segment: Boolean = false,
    /**
     * The lines before [text] in the chapter with their translations, for a provider that reads
     * context (a language model keeps names, speakers and tone consistent with them). Not translated.
     */
    val context: List<Pair<String, String>> = emptyList(),
)

/** The [ChapterTranslationRequest.context] of the request being translated, for the provider. */
public class TranslationContext(public val earlier: List<Pair<String, String>>) :
    kotlin.coroutines.AbstractCoroutineContextElement(TranslationContext) {
    public companion object Key : kotlin.coroutines.CoroutineContext.Key<TranslationContext>
}

/** Names shown beside a stored translation; the keys themselves are opaque identities. */
public data class TranslationLabels(
    val workTitle: String,
    val chapterTitle: String,
    /** The source the work was read from; null when the reader does not know it. */
    val sourceName: String? = null,
)

/** One chapter with a stored translation, as the settings page lists it. */
public data class StoredChapterTranslation(
    val workKey: String,
    val chapterKey: String,
    val to: String,
    val provider: String,
    val lineCount: Int,
    val complete: Boolean,
    val paid: Boolean,
    val updatedAt: Long,
    /** Null for a chapter translated before names were recorded, until it is opened again. */
    val workTitle: String?,
    val chapterTitle: String?,
    val sourceName: String?,
)

/** How a translated chapter is shown. */
public enum class TranslationDisplayMode {
    /** The translation in place of the original. */
    TRANSLATION,

    /** Each original line followed by its translation. */
    BILINGUAL,

    /** The original; translation stays switched on for the work but is not shown. */
    ORIGINAL,
}

/** What the reader remembers per work. */
public data class WorkTranslationSettings(
    val enabled: Boolean,
    val from: String,
    val to: String,
    val mode: TranslationDisplayMode = TranslationDisplayMode.TRANSLATION,
    /**
     * The reader switched the translation off for this work: it no longer starts by itself. Settings
     * saved while it was off for another reason (a language picked, the display changed) leave it
     * free to start.
     */
    val turnedOff: Boolean = false,
    /**
     * The reader chose [from] for this work, rather than it being the default or the language its
     * source or pages were taken to be in: it is kept over what the source says, Japanese included.
     */
    val fromPicked: Boolean = false,
) {
    /** The translation is on and shown, not only kept on behind the original. */
    public val shown: Boolean get() = enabled && mode != TranslationDisplayMode.ORIGINAL

    /** The reader's toolbar button: off when shown, else on and shown (the original-only display left). */
    public fun toggled(): WorkTranslationSettings =
        if (shown) copy(enabled = false)
        else copy(enabled = true, mode = if (mode == TranslationDisplayMode.ORIGINAL) TranslationDisplayMode.TRANSLATION else mode)

    /** A work with these settings (or none) may still start translating by itself. */
    public companion object {
        public fun startsByItself(stored: WorkTranslationSettings?): Boolean = stored == null || !stored.enabled && !stored.turnedOff
    }
}
