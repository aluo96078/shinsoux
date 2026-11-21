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
        val fit = Fit(text, g, box, sourceSize, inBubble)
        return when (orientation) {
            MangaOrientation.VERTICAL -> verticalLayout(fit)
            MangaOrientation.HORIZONTAL -> horizontalLayout(fit)
            MangaOrientation.AUTO -> if (horizontalSource) horizontalLayout(fit) else verticalLayout(fit)
            MangaOrientation.PREFER_HORIZONTAL -> preferHorizontal(fit)
        }
    }

    /** What is being set, and where: the arguments every attempt at a size shares. */
    private class Fit(val text: String, val g: Geometry, val box: Box, val maxSize: Int?, val inBubble: Boolean)

    private fun horizontalLayout(fit: Fit): TextLayout? = horizontal(fit, tight = false) ?: horizontal(fit, tight = true)

    private fun verticalLayout(fit: Fit): TextLayout? = vertical(fit, tight = false) ?: vertical(fit, tight = true)

    private fun preferHorizontal(fit: Fit): TextLayout? {
        val hl = horizontalLayout(fit); val vl = verticalLayout(fit)
        return if (hl != null && isRoomy(fit.text, hl) && beatsVertical(hl, vl)) hl else vl ?: hl
    }

    /** Lines of two or three characters read as broken, not as dialogue. */
    private fun isRoomy(text: String, hl: TextLayout): Boolean = text.length / hl.lines.toFloat() >= min(4, text.length)

    private fun beatsVertical(hl: TextLayout, vl: TextLayout?): Boolean = vl == null || hl.size >= vl.size * 0.85

    internal class Token(val text: String, val kind: GlyphKind, val adv: Float)

    /**
     * Splits text into layout units. Latin letters and digits stay together (a word, a number,
     * "Wi-Fi"); words are separate units, so Latin text wraps between them. In vertical text: numbers up to three digits and two capitals are set upright
     * across the column, three to five capitals one per cell, anything longer on its side.
     */
    internal fun tokens(text: String, s: Int, vertical: Boolean): List<Token> {
        val out = mutableListOf<Token>(); var i = 0
        while (i < text.length) {
            val c = text[i]
            i += when {
                isLatin(c) -> addLatinRun(out, text, i, s, vertical)
                startsMarkPair(text, i) -> addMarkPair(out, text, i, s, vertical)
                else -> addSingle(out, c, s, vertical)
            }
        }
        return out
    }

    private fun isLatin(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ' || c in '０'..'９'

    /** A word holds together through "Wi-Fi", "don't", "3.14" and "1,000" (the character at [k] joins the ones around it). */
    private fun joinsWord(text: String, k: Int): Boolean = k + 1 < text.length && when (text[k]) {
        ',' -> text[k - 1].isDigit() && text[k + 1].isDigit()
        '.', '-', ':', '\'', '/' -> isLatin(text[k + 1])
        else -> false
    }

    /** Where the Latin run starting at [i] ends: a space (or a comma between words) ends it, since a sentence is many words and must wrap. */
    private fun latinRunEnd(text: String, i: Int): Int {
        var j = i + 1
        while (j < text.length && (isLatin(text[j]) || joinsWord(text, j))) j++
        if (j < text.length && text[j] == '%') j++
        return j
    }

    /** Adds the Latin run starting at [i] as one or more tokens; returns how many characters it took. */
    private fun addLatinRun(out: MutableList<Token>, text: String, i: Int, s: Int, vertical: Boolean): Int {
        val j = latinRunEnd(text, i)
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
        return j - i
    }

    private fun startsMarkPair(text: String, i: Int): Boolean = text[i] in "!?！？" && i + 1 < text.length && text[i + 1] in "!?！？"

    /** Paired marks ("!?", "!!") share one cell; returns how many characters it took. */
    private fun addMarkPair(out: MutableList<Token>, text: String, i: Int, s: Int, vertical: Boolean): Int {
        val pair = text.substring(i, i + 2).map { when (it) { '！' -> '!'; '？' -> '?'; else -> it } }.joinToString("")
        out += if (vertical) Token(pair, GlyphKind.TATE_CHU_YOKO, s.toFloat()) else Token(pair, GlyphKind.PLAIN, measure.width(pair, s))
        return 2
    }

    private fun addSingle(out: MutableList<Token>, c: Char, s: Int, vertical: Boolean): Int {
        val g = if (vertical) VERTICAL_FORMS[c] ?: c.toString() else c.toString()
        val wide = vertical || c.code > 0x2E7F || c in "…—「」『』（）"
        out += Token(g, if (vertical) GlyphKind.CJK else GlyphKind.PLAIN, if (wide) s.toFloat() else measure.width(g, s))
        return 1
    }

    /** Breaks tokens into lines (columns in vertical text) of the given capacities, balanced and with kinsoku. */
    internal fun breakLines(toks: List<Token>, caps: List<Float>, s: Int): List<List<Token>>? {
        val lines = mutableListOf<List<Token>>()
        return if (fillLines(toks, caps, s, lines) < toks.size || hasLoneLast(lines, toks, s)) null else lines
    }

    /** Fills [lines] one per capacity; returns the first token not placed, or -1 when a line could not take even one. */
    private fun fillLines(toks: List<Token>, caps: List<Float>, s: Int, lines: MutableList<List<Token>>): Int {
        var i = 0
        for (r in caps.indices) {
            // a word space where the line breaks is not drawn: no line starts with one
            while (i < toks.size && toks[i].text == " ") i++
            if (i >= toks.size) break
            val target = lineTarget(toks, caps, r, i)
            val j = greedyEnd(toks, i, caps[r], target, s)
            if (j == i) return -1
            val end = kinsokuEnd(toks, i, j, caps[r], s)
            lines += toks.subList(i, trimmedEnd(toks, i, end)); i = end
        }
        return i
    }

    /** The share of the rest of the text that line [r] should take, by its share of the rest of the room. */
    private fun lineTarget(toks: List<Token>, caps: List<Float>, r: Int, i: Int): Float {
        val restCap = caps.drop(r).sum()
        val restAdv = toks.drop(i).sumOf { it.adv.toDouble() }.toFloat()
        return restAdv * caps[r] / restCap
    }

    /** Takes tokens from [i] while they fit [cap] and the balanced [target] (the first always goes in); returns where it stops. */
    private fun greedyEnd(toks: List<Token>, i: Int, cap: Float, target: Float, s: Int): Int {
        val room = cap + 0.5f; val want = target + s * 0.5f
        var used = 0f; var j = i
        while (j < toks.size && takes(toks[j].adv, used, room, want, first = j == i)) { used += toks[j].adv; j++ }
        return j
    }

    private fun takes(adv: Float, used: Float, room: Float, want: Float, first: Boolean): Boolean =
        used + adv <= room && (first || used + adv / 2 <= want)

    /** The width of tokens [i] until [j], added in order. */
    private fun spanOf(toks: List<Token>, i: Int, j: Int): Float {
        var used = 0f
        for (k in i until j) used += toks[k].adv
        return used
    }

    /** Moves the end of a line [i] until [j0] for kinsoku: no closing mark at a head, no opening one at an end. */
    private fun kinsokuEnd(toks: List<Token>, i: Int, j0: Int, cap: Float, s: Int): Int {
        var j = j0
        val used = spanOf(toks, i, j0)
        val headStarts = j < toks.size && toks[j].text in NO_START
        if (headStarts && used + toks[j].adv <= cap + s * 0.6f) j++   // hang it
        else if (headStarts && j - i > 1) j--
        if (j - i > 1 && toks[j - 1].text in NO_END) j--
        if (j - i > 1 && splitsPair(toks, j)) j--   // keep …… together
        return j
    }

    private fun splitsPair(toks: List<Token>, j: Int): Boolean = j < toks.size && toks[j].text == toks[j - 1].text && toks[j].text in PAIRED

    /** Nor does a line end with a space (a blank cell, or a blank column on its own). */
    private fun trimmedEnd(toks: List<Token>, i: Int, j: Int): Int {
        var end = j
        while (end - i > 1 && toks[end - 1].text == " ") end--
        return end
    }

    /** A lone last unit (the text is long enough that it should not be left on its own). */
    private fun hasLoneLast(lines: List<List<Token>>, toks: List<Token>, s: Int): Boolean {
        val total = toks.sumOf { it.adv.toDouble() }.toFloat()
        return lines.size > 1 && lines.last().sumOf { it.adv.toDouble() } < s * 1.05 && total > s * 3.5
    }

    internal class Geometry(val bx0: Int, val by0: Int, val rw: Int, val rh: Int, val dt: FloatArray) {
        companion object {
            private const val BIG = 1e9f
            private const val D1 = 0.955f
            private const val D2 = 1.3693f

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
                val d = FloatArray(w * h) { if (mask[it] != 0.toByte()) BIG else 0f }
                forwardPass(d, w, h)
                backwardPass(d, w, h)
                return d
            }

            /** The distance at (x, y), 0 outside the grid. */
            private fun cell(d: FloatArray, w: Int, h: Int, x: Int, y: Int) = if (x !in 0 until w || y !in 0 until h) 0f else d[y * w + x]

            private fun forwardPass(d: FloatArray, w: Int, h: Int) {
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    if (d[i] == 0f) continue
                    d[i] = minOf(d[i], cell(d, w, h, x - 1, y) + D1, cell(d, w, h, x, y - 1) + D1, cell(d, w, h, x - 1, y - 1) + D2, cell(d, w, h, x + 1, y - 1) + D2)
                }
            }

            private fun backwardPass(d: FloatArray, w: Int, h: Int) {
                for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
                    val i = y * w + x
                    if (d[i] == 0f) continue
                    d[i] = minOf(d[i], cell(d, w, h, x + 1, y) + D1, cell(d, w, h, x, y + 1) + D1, cell(d, w, h, x + 1, y + 1) + D2, cell(d, w, h, x - 1, y + 1) + D2)
                }
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

    /** One glyph size of vertical text: its margin, column width, tokens and their total advance. */
    private class Columns(val s: Int, val margin: Float, val colW: Float, val cx0: Float, val toks: List<Token>, val total: Double)

    /** The rows free in every column checked so far: from [top] to [bottom]. */
    private class Span(var top: Int = -1, var bottom: Int = Int.MAX_VALUE)

    /** Columns right to left, each limited to the interior's span at its position. */
    private fun vertical(fit: Fit, tight: Boolean): TextLayout? {
        val g = fit.g
        val cx0 = g.bx0 + g.rw / 2f
        for (s in startSize(fit.box, fit.maxSize) downTo if (tight) 8 else 10) {
            val margin = margin(s, fit.inBubble, tight); val colW = s * 1.18f
            val ncolsMax = ((g.rw - 2 * margin) / colW).toInt()
            if (ncolsMax < 1) continue
            val toks = tokens(fit.text, s, vertical = true)
            val z = Columns(s, margin, colW, cx0, toks, toks.sumOf { it.adv.toDouble() })
            for (ncols in 1..ncolsMax) {
                val l = fitColumns(g, z, ncols)
                if (l != null) return l
            }
        }
        return null
    }

    /** [ncols] columns at this size: null when the interior cannot hold them or the text does not fit. */
    private fun fitColumns(g: Geometry, z: Columns, ncols: Int): TextLayout? {
        val rightX = z.cx0 + ncols * z.colW / 2 - z.colW / 2
        val span = columnSpan(g, z, rightX, ncols) ?: return null
        val cap = (span.bottom - span.top).toFloat()
        val need = ceil(z.total / cap).toInt()
        val cols = if (need > ncols) null else breakLines(z.toks, List(need) { cap }, z.s)
        return cols?.let { columnGlyphs(g, z, span.top, cap, it) }
    }

    /** The rows free in all [ncols] columns, or null when one has no room or what is shared is shorter than a glyph. */
    private fun columnSpan(g: Geometry, z: Columns, rightX: Float, ncols: Int): Span? {
        val span = Span()
        for (c in 0 until ncols) if (!scanColumn(g, z, rightX, c, span)) return null
        return if (span.bottom - span.top < z.s) null else span
    }

    private fun scanColumn(g: Geometry, z: Columns, rightX: Float, c: Int, span: Span): Boolean {
        val cx = (rightX - c * z.colW).toInt() - g.bx0
        val lo = max(0, cx - z.s / 2); val hi = min(g.rw - 1, cx + z.s / 2)
        if (cx < 0 || lo > hi) return false
        return scanRows(g, z.margin, lo, hi, span)
    }

    /** Narrows [span] to the rows free (farther from the edge than [margin]) across columns [lo]..[hi]; false when there are none. */
    private fun scanRows(g: Geometry, margin: Float, lo: Int, hi: Int, span: Span): Boolean {
        var first = -1; var last = -1
        for (y in 0 until g.rh) {
            var mn = Float.MAX_VALUE
            for (x in lo..hi) mn = min(mn, g.dt[y * g.rw + x])
            if (mn > margin) { if (first < 0) first = y; last = y }
        }
        if (first >= 0) { span.top = max(span.top, first); span.bottom = min(span.bottom, last) }
        return first >= 0
    }

    private fun columnGlyphs(g: Geometry, z: Columns, top: Int, cap: Float, cols: List<List<Token>>): TextLayout {
        val used = cols.maxOf { col -> col.sumOf { it.adv.toDouble() } }.toFloat()
        val y0 = g.by0 + top + (cap - used) / 2f
        // fewer columns than reserved (word spaces at breaks take none): centred on what is
        // used, which lies within the columns just checked
        val right = z.cx0 + cols.size * z.colW / 2 - z.colW / 2
        val glyphs = cols.flatMapIndexed { c, col ->
            var y = y0
            col.map { t -> val p = PlacedGlyph(right - c * z.colW, y + t.adv / 2, t.text, t.kind); y += t.adv; p }
        }
        return TextLayout(z.s, glyphs, cols.size, vertical = true)
    }

    /** One glyph size of horizontal text: its margin, line height, tokens and their total advance. */
    private class Rows(val s: Int, val margin: Float, val lineH: Float, val cy0: Float, val toks: List<Token>, val total: Float)

    /** Lines top to bottom, each as wide as the interior allows at its height. */
    private fun horizontal(fit: Fit, tight: Boolean): TextLayout? {
        val g = fit.g
        val cy0 = g.rh / 2f
        for (s in startSize(fit.box, fit.maxSize) downTo if (tight) 8 else 10) {
            val margin = margin(s, fit.inBubble, tight)
            val lineH = s * 1.3f
            val nMax = ((g.rh - 2 * margin) / lineH).toInt()
            if (nMax < 1) continue
            val toks = tokens(fit.text, s, vertical = false)
            val z = Rows(s, margin, lineH, cy0, toks, toks.sumOf { it.adv.toDouble() }.toFloat())
            for (nrows in 1..nMax) {
                val l = fitRows(g, z, nrows)
                if (l != null) return l
            }
        }
        return null
    }

    /** [nrows] lines at this size: null when the interior cannot hold them or the text does not fit. */
    private fun fitRows(g: Geometry, z: Rows, nrows: Int): TextLayout? {
        val top = z.cy0 - nrows * z.lineH / 2
        val known = rowSpans(g, z, nrows, top) ?: return null
        val caps = known.map { (it.second - it.first).toFloat() }
        val lines = if (caps.any { it < z.s } || caps.sum() < z.total) null else breakLines(z.toks, caps, z.s)
        return lines?.takeIf { it.size >= nrows }?.let { rowGlyphs(g, z, top, known, it) }
    }

    /** The free span of every line, or null when one has no room. */
    private fun rowSpans(g: Geometry, z: Rows, nrows: Int, top: Float): List<Pair<Int, Int>>? {
        val out = ArrayList<Pair<Int, Int>>(nrows)
        for (r in 0 until nrows) {
            val span = rowSpan(g, z, top, r) ?: return null
            out += span
        }
        return out
    }

    private fun rowSpan(g: Geometry, z: Rows, top: Float, r: Int): Pair<Int, Int>? {
        val ya = (top + r * z.lineH + (z.lineH - z.s) / 2).toInt(); val yb = ya + z.s
        if (ya < 0 || yb >= g.rh) return null
        var first = -1; var last = -1
        for (x in 0 until g.rw) {
            var mn = Float.MAX_VALUE
            for (y in ya..yb) mn = min(mn, g.dt[y * g.rw + x])
            if (mn > z.margin) { if (first < 0) first = x; last = x }
        }
        return if (first < 0) null else first to last
    }

    private fun rowGlyphs(g: Geometry, z: Rows, top: Float, known: List<Pair<Int, Int>>, lines: List<List<Token>>): TextLayout {
        val glyphs = lines.flatMapIndexed { r, line ->
            val span = known[r]
            val width = line.sumOf { it.adv.toDouble() }.toFloat()
            var x = g.bx0 + (span.first + span.second) / 2f - width / 2
            val y = g.by0 + top + r * z.lineH + z.lineH / 2
            line.map { t -> val p = PlacedGlyph(x + t.adv / 2, y, t.text, t.kind); x += t.adv; p }
        }
        return TextLayout(z.s, glyphs, lines.size, vertical = false)
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
