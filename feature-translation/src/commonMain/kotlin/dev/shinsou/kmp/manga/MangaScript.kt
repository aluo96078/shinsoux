package dev.shinsou.kmp.manga

import kotlinx.serialization.Serializable

/** One text region of a page: where it is, what kind, and what the OCR read. */
@Serializable
public data class TextRegion(
    val kind: RegionKind,
    val x0: Int, val y0: Int, val x1: Int, val y1: Int,
    val text: String,
) {
    val box: Box get() = Box(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
}

/** What detection and OCR found on a page; cached by the page's image hash (ADR-0005 §4). */
@Serializable
public data class PageAnalysis(
    val width: Int,
    val height: Int,
    val regions: List<TextRegion>,
    /** Speech bubbles, used to find each text's bubble when erasing and typesetting. */
    val bubbles: List<IntArray>,
) {
    override fun equals(other: Any?): Boolean =
        other is PageAnalysis && width == other.width && height == other.height && regions == other.regions &&
            bubbles.size == other.bubbles.size && bubbles.indices.all { bubbles[it].contentEquals(other.bubbles[it]) }

    override fun hashCode(): Int = (width * 31 + height) * 31 + regions.hashCode()
}

/** How panels are read: right to left (manga) or left to right (webtoons, western comics). */
public enum class ReadingDirection { RIGHT_TO_LEFT, LEFT_TO_RIGHT }

/** Where a line of the chapter script came from. */
public data class ScriptLine(val page: Int, val region: Int)

/**
 * The chapter script handed to the translation service (ADR-0005 §4): one line per text region,
 * in reading order, so each translation maps back to its region by line number. Page markers and
 * regions that need no translation ("……", "!?") are kept lines the service leaves alone.
 */
public class MangaScript private constructor(
    public val text: String,
    /** For each line of [text]: the region it came from, or null for a kept line. */
    public val lines: List<ScriptLine?>,
) {
    /** Lines the translation service must keep as they are. */
    public fun keep(line: String): Boolean = line.startsWith(PAGE_MARK) || !needsTranslation(line)

    /** Maps the translated lines (one for one with [text]) back to page → region → translation. */
    public fun translations(translated: List<String?>): Map<Int, Map<Int, String>> {
        val out = HashMap<Int, HashMap<Int, String>>()
        lines.forEachIndexed { i, at ->
            val t = translated.getOrNull(i)
            if (at != null && t != null) out.getOrPut(at.page) { HashMap() }[at.region] = t
        }
        return out
    }

    public companion object {
        private const val PAGE_MARK = "⁣p"

        /**
         * Whether [page] has anything to translate: a region with letters (kana, kanji or any
         * other). A page with none, or only page numbers and punctuation, is shown as it is.
         */
        public fun hasText(page: PageAnalysis): Boolean = page.regions.any { region -> region.text.any { it.isLetter() } }

        /** A region only of punctuation and symbols reads the same in any language. */
        public fun needsTranslation(text: String): Boolean = text.any { it.isLetterOrDigit() }

        public fun build(pages: List<PageAnalysis>, direction: ReadingDirection = ReadingDirection.RIGHT_TO_LEFT): MangaScript {
            val text = StringBuilder(); val lines = mutableListOf<ScriptLine?>()
            pages.forEachIndexed { p, page ->
                if (lines.isNotEmpty()) text.append('\n')
                text.append(PAGE_MARK).append(p + 1); lines += null
                for (r in readingOrder(page, direction)) {
                    // the service splits on line breaks: a region is one line
                    val line = page.regions[r].text.replace(Regex("\\s+"), " ").trim()
                    text.append('\n').append(line)
                    lines += if (line.isEmpty() || !needsTranslation(line)) null else ScriptLine(p, r)
                }
            }
            return MangaScript(text.toString(), lines)
        }

        /**
         * Region indices in reading order: bands down the page (a sixth of its height each), and
         * within a band right to left for manga or left to right otherwise. Panels are not
         * detected; this order only gives the translation its context.
         */
        public fun readingOrder(page: PageAnalysis, direction: ReadingDirection): List<Int> {
            val band = maxOf(1f, page.height / 6f)
            return page.regions.indices.sortedWith(compareBy<Int> { (page.regions[it].y0 / band).toInt() }.thenBy {
                val r = page.regions[it]
                if (direction == ReadingDirection.RIGHT_TO_LEFT) -r.x1 else r.x0
            })
        }
    }
}
