package dev.shinsou.kmp.translation

/** A fixed rendering of a name or term for one work and target language. */
public data class GlossaryEntry(
    /** Stable within the work; it is the number inside the placeholder, so it must not be reused. */
    val id: Int,
    val source: String,
    val target: String,
) {
    init {
        require(id in 1..MAX_ID) { "Glossary entry id out of range" }
        require(source.isNotBlank()) { "Glossary source term is blank" }
    }

    public companion object {
        public const val MAX_ID: Int = 9_999
    }
}

/**
 * Keeps names consistent in machine translation, which otherwise renders one name several ways
 * within a page. Each term is swapped for a code (`N7X`) before translating and swapped for its
 * rendering afterwards.
 *
 * The code form was measured against the offline engine (ADR-0004): Japanese to English keeps
 * such codes in every line; English to Chinese keeps them in about nine lines out of ten and
 * otherwise repeats or drops one. Text is stored with the codes still in it, so changing a
 * rendering needs no new translation, only [restore].
 */
public class TranslationGlossary(entries: Collection<GlossaryEntry>) {
    // Longest first: "山田太郎" must win over "山田".
    private val ordered = entries.filter { it.source.isNotBlank() }.sortedWith(
        compareByDescending<GlossaryEntry> { it.source.length }.thenBy { it.id },
    )
    private val byId = entries.associateBy { it.id }

    public val isEmpty: Boolean get() = ordered.isEmpty()

    /**
     * [segment] with every glossary term replaced by its code. A segment that already contains
     * something shaped like a code is returned unchanged: restoring would corrupt it.
     */
    public fun protect(segment: String): String {
        if (ordered.isEmpty() || CODE.containsMatchIn(segment)) return segment
        // One pass, so a term can never match inside a code that was just written.
        var out: StringBuilder? = null
        var index = 0
        while (index < segment.length) {
            val entry = ordered.firstOrNull { segment.startsWith(it.source, index) }
            if (entry == null) {
                out?.append(segment[index])
                index++
            } else {
                val builder = out ?: StringBuilder(segment.length).append(segment, 0, index).also { out = it }
                builder.append(code(entry.id))
                index += entry.source.length
            }
        }
        return out?.toString() ?: segment
    }

    /** [translated] with codes replaced by their renderings; a code with no entry is left as it is. */
    public fun restore(translated: String): String {
        if (!CODE.containsMatchIn(translated)) return translated
        val out = StringBuilder(translated.length)
        var cursor = 0
        for (match in CODE.findAll(translated)) {
            val entry = match.groupValues[1].toIntOrNull()?.let(byId::get) ?: continue
            var start = match.range.first
            var end = match.range.last + 1
            // Drop the spaces the engine puts around a code that sits next to CJK text.
            val before = translated.getOrNull(start - 2)
            if (start > cursor && translated[start - 1] == ' ' && (before == null || TranslationText.isCjk(before))) start--
            val after = translated.getOrNull(end + 1)
            if (end < translated.length && translated[end] == ' ' && (after == null || TranslationText.isCjk(after))) end++
            out.append(translated, cursor, start).append(entry.target.ifBlank { entry.source })
            cursor = end
        }
        out.append(translated, cursor, translated.length)
        return out.toString()
    }

    public companion object {
        public val EMPTY: TranslationGlossary = TranslationGlossary(emptyList())
        private val CODE = Regex("N([1-9][0-9]{0,3})X")

        internal fun code(id: Int): String = "N${id}X"

        /** True when [translated] carries each code of [protectedSource] the same number of times. */
        public fun codesPreserved(protectedSource: String, translated: String): Boolean =
            codeCounts(protectedSource) == codeCounts(translated)

        private fun codeCounts(text: String): Map<String, Int> = CODE.findAll(text).groupingBy { it.value }.eachCount()
    }
}
