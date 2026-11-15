package dev.shinsou.kmp.manga

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Measures text in the translation's font, so Latin runs get their real width. */
public fun interface TextMeasure {
    public fun width(text: String, size: Int): Float
}

/** How a translation is set: upright in columns, in lines, or whichever suits the bubble. */
public enum class MangaOrientation {
    /** Follows the source: a wide text box holds horizontal text. */
    AUTO,
    VERTICAL,
    HORIZONTAL,

    /** Horizontal, unless the bubble (drawn for vertical text) would make it small or broken. */
    PREFER_HORIZONTAL,
}

public enum class GlyphKind {
    /** A CJK character (vertical forms already substituted in vertical text). */
    CJK,

    /** Upright across a column: a short number, two capitals, "!?" (縱中橫). */
    TATE_CHU_YOKO,

    /** A word or long number lying on its side in a column. */
    SIDEWAYS,

    /** Horizontal text as it is. */
    PLAIN,
}

/** A glyph (or a run drawn as one) centred on (x, y). */
public data class PlacedGlyph(val x: Float, val y: Float, val text: String, val kind: GlyphKind)

public data class TextLayout(val size: Int, val glyphs: List<PlacedGlyph>, val lines: Int, val vertical: Boolean)

/**
 * Typesets a translation into the space its source occupied (ADR-0005 §2): as large as the source
 * glyphs at most, shaped to the bubble (an oval gives short columns or lines at its edges), with
 * kinsoku (no closing punctuation at a line head, no opening bracket at a line end), balanced
 * lines (no lone last character), tate-chu-yoko and sideways Latin in vertical text.
 */
public class MangaLayout(
    private val measure: TextMeasure,
    /** Distance of each interior pixel to the edge (OpenCV's on device; a chamfer transform in tests). */
    private val distance: (mask: ByteArray, width: Int, height: Int) -> FloatArray = Geometry::chamfer,
) {

    public fun layout(text: String, area: Region, box: Box, sourceSize: Int?, inBubble: Boolean, horizontalSource: Boolean, orientation: MangaOrientation): TextLayout? {
        val g = Geometry.of(area, distance) ?: return null
        fun h() = horizontal(text, g, box, sourceSize, inBubble, tight = false) ?: horizontal(text, g, box, sourceSize, inBubble, tight = true)
        fun v() = vertical(text, g, box, sourceSize, inBubble, tight = false) ?: vertical(text, g, box, sourceSize, inBubble, tight = true)
        return when (orientation) {
            MangaOrientation.VERTICAL -> v()
            MangaOrientation.HORIZONTAL -> h()
            MangaOrientation.AUTO -> if (horizontalSource) h() else v()
            MangaOrientation.PREFER_HORIZONTAL -> {
                val hl = h(); val vl = v()
                // lines of two or three characters read as broken, not as dialogue
                val roomy = hl != null && text.length / hl.lines.toFloat() >= min(4, text.length)
                if (hl != null && roomy && (vl == null || hl.size >= vl.size * 0.85)) hl else vl ?: hl
            }
        }
    }

    internal class Token(val text: String, val kind: GlyphKind, val adv: Float)

    /**
     * Splits text into layout units. Latin letters and digits stay together (a word, a number,
     * "Wi-Fi"); words are separate units, so Latin text wraps between them. In vertical text: numbers up to three digits and two capitals are set upright
     * across the column, three to five capitals one per cell, anything longer on its side.
     */
    internal fun tokens(text: String, s: Int, vertical: Boolean): List<Token> {
        val out = mutableListOf<Token>(); var i = 0
        fun latin(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ' || c in '０'..'９'
        while (i < text.length) {
            val c = text[i]
            if (latin(c)) {
                var j = i + 1
                // A word holds together through "Wi-Fi", "don't", "3.14" and "1,000", but a space
                // (or a comma between words) ends it: a sentence is many words, and must wrap.
                fun joins(k: Int): Boolean = k + 1 < text.length && when (text[k]) {
                    ',' -> text[k - 1].isDigit() && text[k + 1].isDigit()
                    '.', '-', ':', '\'', '/' -> latin(text[k + 1])
                    else -> false
                }
                while (j < text.length && (latin(text[j]) || joins(j))) j++
                if (j < text.length && text[j] == '%') j++
                val run = text.substring(i, j).map { if (it in 'Ａ'..'ｚ' || it in '０'..'９') it - 0xFEE0 else it }.joinToString("")
                val digits = run.all { it.isDigit() || it in ".,:%" }
                val caps = run.all { it in 'A'..'Z' }
                when {
                    !vertical -> out += Token(run, GlyphKind.PLAIN, measure.width(run, s))
                    digits && run.count { it.isDigit() } <= 3 && run.length <= 4 -> out += Token(run, GlyphKind.TATE_CHU_YOKO, s.toFloat())
                    caps && run.length <= 2 -> out += Token(run, GlyphKind.TATE_CHU_YOKO, s.toFloat())
                    caps && run.length <= 5 -> run.forEach { out += Token(it.toString(), GlyphKind.TATE_CHU_YOKO, s * 0.9f) }
                    else -> out += Token(run, GlyphKind.SIDEWAYS, measure.width(run, s) + s * 0.15f)
                }
                i = j
            } else if (c in "!?！？" && i + 1 < text.length && text[i + 1] in "!?！？") {
                // paired marks ("!?", "!!") share one cell
                val pair = text.substring(i, i + 2).map { when (it) { '！' -> '!'; '？' -> '?'; else -> it } }.joinToString("")
                out += if (vertical) Token(pair, GlyphKind.TATE_CHU_YOKO, s.toFloat()) else Token(pair, GlyphKind.PLAIN, measure.width(pair, s))
                i += 2
            } else {
                val g = if (vertical) VERTICAL_FORMS[c] ?: c.toString() else c.toString()
                val wide = vertical || c.code > 0x2E7F || c in "…—「」『』（）"
                out += Token(g, if (vertical) GlyphKind.CJK else GlyphKind.PLAIN, if (wide) s.toFloat() else measure.width(g, s))
                i++
            }
        }
        return out
    }

    /** Breaks tokens into lines (columns in vertical text) of the given capacities, balanced and with kinsoku. */
    internal fun breakLines(toks: List<Token>, caps: List<Float>, s: Int): List<List<Token>>? {
        val total = toks.sumOf { it.adv.toDouble() }.toFloat()
        val lines = mutableListOf<List<Token>>(); var i = 0
        for (r in caps.indices) {
            // a word space where the line breaks is not drawn: no line starts with one
            while (i < toks.size && toks[i].text == " ") i++
            if (i >= toks.size) break
            val restCap = caps.drop(r).sum()
            val restAdv = toks.drop(i).sumOf { it.adv.toDouble() }.toFloat()
            val target = restAdv * caps[r] / restCap
            var used = 0f; var j = i
            while (j < toks.size && used + toks[j].adv <= caps[r] + 0.5f && (used + toks[j].adv / 2 <= target + s * 0.5f || j == i)) { used += toks[j].adv; j++ }
            if (j == i) return null
            if (j < toks.size && toks[j].text in NO_START && used + toks[j].adv <= caps[r] + s * 0.6f) { used += toks[j].adv; j++ }   // hang it
            else if (j < toks.size && toks[j].text in NO_START && j - i > 1) j--
            if (j - i > 1 && toks[j - 1].text in NO_END) j--
            if (j - i > 1 && j < toks.size && toks[j].text == toks[j - 1].text && toks[j].text in PAIRED) j--   // keep …… together
            // nor ends with one (a blank cell, or a blank column on its own)
            var end = j
            while (end - i > 1 && toks[end - 1].text == " ") end--
            lines += toks.subList(i, end); i = j
        }
        if (i < toks.size) return null
        if (lines.size > 1 && lines.last().sumOf { it.adv.toDouble() } < s * 1.05 && total > s * 3.5) return null   // lone last unit
        return lines
    }

    internal class Geometry(val bx0: Int, val by0: Int, val rw: Int, val rh: Int, val dt: FloatArray) {
        companion object {
            fun of(area: Region, distance: (ByteArray, Int, Int) -> FloatArray): Geometry? {
                var bx0 = Int.MAX_VALUE; var bx1 = -1; var by0 = Int.MAX_VALUE; var by1 = -1
                for (y in 0 until area.h) for (x in 0 until area.w) if (area.m[y * area.w + x]) {
                    bx0 = min(bx0, x + area.x0); bx1 = max(bx1, x + area.x0); by0 = min(by0, y + area.y0); by1 = max(by1, y + area.y0)
                }
                if (bx1 < 0) return null
                val rw = bx1 - bx0 + 1; val rh = by1 - by0 + 1
                val mask = ByteArray(rw * rh) { if (area.at(it % rw + bx0, it / rw + by0)) 1 else 0 }
                val dt = distance(mask, rw, rh)
                return Geometry(bx0, by0, rw, rh, dt)
            }

            /** Two-pass 3×3 chamfer distance (1, √2): close to OpenCV's DIST_L2 / 3×3. */
            fun chamfer(mask: ByteArray, w: Int, h: Int): FloatArray {
                val big = 1e9f; val d1 = 0.955f; val d2 = 1.3693f
                val d = FloatArray(w * h) { if (mask[it] != 0.toByte()) big else 0f }
                fun at(x: Int, y: Int) = if (x < 0 || y < 0 || x >= w || y >= h) 0f else d[y * w + x]
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    if (d[i] == 0f) continue
                    d[i] = minOf(d[i], at(x - 1, y) + d1, at(x, y - 1) + d1, at(x - 1, y - 1) + d2, at(x + 1, y - 1) + d2)
                }
                for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
                    val i = y * w + x
                    if (d[i] == 0f) continue
                    d[i] = minOf(d[i], at(x + 1, y) + d1, at(x, y + 1) + d1, at(x + 1, y + 1) + d2, at(x - 1, y + 1) + d2)
                }
                return d
            }
        }
    }

    private fun startSize(box: Box, maxSize: Int?): Int {
        var start = (min(box.width, box.height) * 0.9f).toInt()
        if (maxSize != null) start = min(start, (maxSize * 1.05).toInt())
        return start
    }

    private fun margin(s: Int, inBubble: Boolean, tight: Boolean) =
        if (tight) 0f else if (inBubble) max(4f, s * 0.3f) else max(2f, s * 0.08f)

    /** Columns right to left, each limited to the interior's span at its position. */
    private fun vertical(text: String, g: Geometry, box: Box, maxSize: Int?, inBubble: Boolean, tight: Boolean): TextLayout? {
        val cx0 = g.bx0 + g.rw / 2f
        for (s in startSize(box, maxSize) downTo if (tight) 8 else 10) {
            val margin = margin(s, inBubble, tight); val colW = s * 1.18f
            val ncolsMax = ((g.rw - 2 * margin) / colW).toInt()
            if (ncolsMax < 1) continue
            val toks = tokens(text, s, vertical = true)
            val total = toks.sumOf { it.adv.toDouble() }
            for (ncols in 1..ncolsMax) {
                val rightX = cx0 + ncols * colW / 2 - colW / 2
                var top = -1; var bottom = Int.MAX_VALUE; var ok = true
                for (c in 0 until ncols) {
                    val cx = (rightX - c * colW).toInt() - g.bx0
                    val lo = max(0, cx - s / 2); val hi = min(g.rw - 1, cx + s / 2)
                    if (cx < 0 || lo > hi) { ok = false; break }
                    var first = -1; var last = -1
                    for (y in 0 until g.rh) {
                        var mn = Float.MAX_VALUE
                        for (x in lo..hi) mn = min(mn, g.dt[y * g.rw + x])
                        if (mn > margin) { if (first < 0) first = y; last = y }
                    }
                    if (first < 0) { ok = false; break }
                    top = max(top, first); bottom = min(bottom, last)
                }
                if (!ok || bottom - top < s) continue
                val cap = (bottom - top).toFloat()
                val need = ceil(total / cap).toInt()
                if (need > ncols) continue
                val cols = breakLines(toks, List(need) { cap }, s) ?: continue
                val used = cols.maxOf { col -> col.sumOf { it.adv.toDouble() } }.toFloat()
                val y0 = g.by0 + top + (cap - used) / 2f
                // fewer columns than reserved (word spaces at breaks take none): centred on what is
                // used, which lies within the columns just checked
                val right = cx0 + cols.size * colW / 2 - colW / 2
                val glyphs = cols.flatMapIndexed { c, col ->
                    var y = y0
                    col.map { t -> val p = PlacedGlyph(right - c * colW, y + t.adv / 2, t.text, t.kind); y += t.adv; p }
                }
                return TextLayout(s, glyphs, cols.size, vertical = true)
            }
        }
        return null
    }

    /** Lines top to bottom, each as wide as the interior allows at its height. */
    private fun horizontal(text: String, g: Geometry, box: Box, maxSize: Int?, inBubble: Boolean, tight: Boolean): TextLayout? {
        val cy0 = g.rh / 2f
        for (s in startSize(box, maxSize) downTo if (tight) 8 else 10) {
            val margin = margin(s, inBubble, tight)
            val lineH = s * 1.3f
            val nMax = ((g.rh - 2 * margin) / lineH).toInt()
            if (nMax < 1) continue
            val toks = tokens(text, s, vertical = false)
            val total = toks.sumOf { it.adv.toDouble() }.toFloat()
            for (nrows in 1..nMax) {
                val top = cy0 - nrows * lineH / 2
                val spans = (0 until nrows).map { r ->
                    val ya = (top + r * lineH + (lineH - s) / 2).toInt(); val yb = ya + s
                    if (ya < 0 || yb >= g.rh) null else {
                        var first = -1; var last = -1
                        for (x in 0 until g.rw) {
                            var mn = Float.MAX_VALUE
                            for (y in ya..yb) mn = min(mn, g.dt[y * g.rw + x])
                            if (mn > margin) { if (first < 0) first = x; last = x }
                        }
                        if (first < 0) null else first to last
                    }
                }
                if (spans.any { it == null }) continue
                val caps = spans.map { (it!!.second - it.first).toFloat() }
                if (caps.any { it < s } || caps.sum() < total) continue
                val lines = breakLines(toks, caps, s) ?: continue
                if (lines.size < nrows) continue
                val glyphs = lines.flatMapIndexed { r, line ->
                    val span = spans[r]!!
                    val width = line.sumOf { it.adv.toDouble() }.toFloat()
                    var x = g.bx0 + (span.first + span.second) / 2f - width / 2
                    val y = g.by0 + top + r * lineH + lineH / 2
                    line.map { t -> val p = PlacedGlyph(x + t.adv / 2, y, t.text, t.kind); x += t.adv; p }
                }
                return TextLayout(s, glyphs, lines.size, vertical = false)
            }
        }
        return null
    }

    public companion object {
        internal val VERTICAL_FORMS = mapOf(
            '，' to "︐", '、' to "︑", '。' to "︒", '：' to "︓", '；' to "︔", '…' to "︙", '—' to "︱",
            '「' to "﹁", '」' to "﹂", '『' to "﹃", '』' to "﹄", '（' to "︵", '）' to "︶", '～' to "︴", '〜' to "︴", 'ー' to "︱",
        )
        internal val PAIRED = setOf("…", "—", "︙", "︱")
        internal val NO_END = setOf("「", "『", "（", "﹁", "﹃", "︵")
        internal val NO_START = "，、。：；！？）」』…︐︑︒︓︔︙﹂﹄︶～".map { it.toString() }.toSet() +
            setOf("!", "?", ",", ".", ")", "!?", "!!", "?!", "??", " ")
    }
}
