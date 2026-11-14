package dev.shinsou.kmp.translation

import kotlinx.serialization.Serializable

/**
 * Translation data inside a `.shinsoubackup` (ADR-0004 §4.2). Glossaries and per-work settings
 * are always included: they are the user's own work. Stored translations are included by choice,
 * separately for those that were paid for and those the device can make again for free.
 */
@Serializable
public data class TranslationBackup(
    val works: List<Work> = emptyList(),
    val glossaries: List<Glossary> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val segments: List<Segment> = emptyList(),
    /** True when stored translations were left out because they exceeded the backup's size budget. */
    val segmentsTruncated: Boolean = false,
) {
    @Serializable
    public data class Work(val workKey: String, val enabled: Boolean, val from: String, val to: String, val mode: String)

    /** One work's glossary for one target language, with what stored translations of its lines are keyed by. */
    @Serializable
    public data class Glossary(
        val workKey: String,
        val to: String,
        val salt: String,
        val nextId: Int,
        val terms: List<Term>,
    )

    @Serializable
    public data class Term(val id: Int, val source: String, val target: String)

    @Serializable
    public data class Chapter(
        val workKey: String,
        val chapterKey: String,
        val to: String,
        val provider: String,
        val sourceHash: String,
        val segmentCount: Int,
        val complete: Boolean,
        val paid: Boolean,
        val updatedAt: Long,
        /** Positions in [segments] of the stored lines this chapter's translation uses, so it can be removed after a restore. */
        val segmentIndexes: List<Int> = emptyList(),
        val workTitle: String? = null,
        val chapterTitle: String? = null,
        val sourceName: String? = null,
    )

    @Serializable
    public data class Segment(
        val sourceHash: String,
        val from: String,
        val to: String,
        val engine: String,
        val text: String,
        val paid: Boolean,
    )

    public fun validate(): TranslationBackup {
        require(works.size <= MAX_ROWS && glossaries.size <= MAX_ROWS && chapters.size <= MAX_ROWS * 10 && segments.size <= MAX_ROWS * 100) {
            "Translation backup is too large"
        }
        glossaries.forEach { glossary ->
            require(glossary.salt.length in 1..128 && glossary.nextId in 1..GlossaryEntry.MAX_ID + 1) { "Invalid glossary in the backup" }
            glossary.terms.forEach { require(it.id in 1 until glossary.nextId && it.source.isNotBlank()) { "Invalid glossary term in the backup" } }
            require(glossary.terms.map { it.id }.toSet().size == glossary.terms.size) { "Duplicate glossary term id in the backup" }
        }
        segments.forEach { require(HASH.matches(it.sourceHash) && it.text.isNotEmpty()) { "Invalid stored translation in the backup" } }
        chapters.forEach { chapter ->
            require(chapter.segmentIndexes.all { it in segments.indices }) { "Invalid chapter line reference in the backup" }
        }
        return this
    }

    public val isEmpty: Boolean get() = works.isEmpty() && glossaries.isEmpty() && chapters.isEmpty() && segments.isEmpty()

    private companion object {
        const val MAX_ROWS = 10_000
        val HASH = Regex("[0-9a-f]{64}")
    }
}
