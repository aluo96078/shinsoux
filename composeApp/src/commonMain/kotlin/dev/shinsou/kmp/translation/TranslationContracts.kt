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

    /** The service's own quota or rate limit. */
    QUOTA_EXCEEDED,

    /** The user's own daily limit for paid translation. */
    DAILY_LIMIT,

    /** The engine ran and failed. */
    ENGINE_FAILED,

    /** The chosen service still needs its address or model set. */
    NOT_CONFIGURED,

    /** The model declined to translate this text under its provider's content rules. */
    REFUSED,
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
    fun text(): String = segments.joinToString("\n") { it.translation ?: it.source }
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
)

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
)
