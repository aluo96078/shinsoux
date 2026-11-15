package dev.shinsou.kmp.manga

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A mask that covers only its own rectangle of the page. */
public class Region(public val x0: Int, public val y0: Int, public val w: Int, public val h: Int, public val m: BooleanArray) {
    public fun at(x: Int, y: Int): Boolean = x >= x0 && y >= y0 && x < x0 + w && y < y0 + h && m[(y - y0) * w + x - x0]

    public companion object {
        public fun rect(x0: Int, y0: Int, x1: Int, y1: Int): Region = Region(x0, y0, x1 - x0, y1 - y0, BooleanArray((x1 - x0) * (y1 - y0)) { true })
    }
}

/** How a region was erased, and what the replacement should look like. */
public class Erased(
    /** Background colour (also the outline colour for text drawn over artwork). */
    public val background: IntArray,
    /** The source's ink colour, which the translation is drawn in. */
    public val ink: IntArray,
    /** False when the background was not flat: the translation then gets an outline. */
    public val flat: Boolean,
    /** The source glyph size in pixels: the translation is set no larger. */
    public val sourceSize: Int?,
    /** Where the translation may go: the bubble interior, or the text box. */
    public val area: Region,
    public val inBubble: Boolean,
    public val horizontalSource: Boolean,
)

/**
 * Removes the source text from a page (ADR-0005 §2): in a bubble the whole interior is cleared and
 * its background restored (flat fill, or a local quadratic fit for gradients); text on a flat
 * background outside bubbles is cleared the same way. Text over artwork is redrawn by LaMa when the
 * optional pack is installed ([inpainter]), and otherwise left as drawn.
 */
public class MangaEraser(private val ops: VisionOps, private val inpainter: MangaInpainter? = null) {

    /**
     * Erases [box] (with its bubble, if any) in place. Null when the text should stay as drawn.
     * [overArt]: the detector saw this text outside any bubble, drawn over the artwork.
     */
    public fun erase(img: RgbImage, box: Box, bubble: Box?, overArt: Boolean = false): Erased? {
        val W = img.width; val H = img.height
        val x0 = box.x0.toInt().coerceIn(0, W - 1); val y0 = box.y0.toInt().coerceIn(0, H - 1)
        val x1 = box.x1.toInt().coerceIn(x0 + 1, W); val y1 = box.y1.toInt().coerceIn(y0 + 1, H)
        val wb = x1 - x0; val hb = y1 - y0; val n = wb * hb

        // Three colour groups inside the box: the largest is the background; the ink is the group
        // most unlike it that is made of many small strokes (a frame or panel is one big blob).
        val fl = FloatArray(n * 3)
        for (y in 0 until hb) for (x in 0 until wb) {
            val i = (y0 + y) * W + x0 + x; val o = (y * wb + x) * 3
            fl[o] = img.r(i).toFloat(); fl[o + 1] = img.g(i).toFloat(); fl[o + 2] = img.b(i).toFloat()
        }
        val k = 3
        val stride = max(1, n / 4000); val ns = (n + stride - 1) / stride
        val cen = ops.kmeans(FloatArray(ns * 3) { fl[(it / 3) * stride * 3 + it % 3] }, k, attempts = 2)
        val labels = IntArray(n) { i -> nearest(fl, i, cen, k) }
        val cnt = IntArray(k).also { c -> labels.forEach { c[it]++ } }
        val bgi = cnt.indices.maxBy { cnt[it] }
        var ink = -1; var best = -1.0
        for (c in 0 until k) {
            if (c == bgi || cnt[c] < 20) continue
            val areas = ops.componentAreas(ByteArray(n) { if (labels[it] == c) 1 else 0 }, wb, hb)
            val largest = max(1, areas.maxOrNull() ?: 1)
            val contrast = (0 until 3).sumOf { abs(cen[c * 3 + it] - cen[bgi * 3 + it]).toDouble() }
            val score = contrast * (1 - largest.toDouble() / cnt[c])
            if (score > best) { best = score; ink = c }
        }
        if (ink < 0) ink = (0 until k).first { it != bgi }
        val bg = FloatArray(3) { cen[bgi * 3 + it] }
        var fg = FloatArray(3) { cen[ink * 3 + it] }
        var dev = 0.0; val mean = DoubleArray(3); val sq = DoubleArray(3); var nb = 0
        for (i in 0 until n) if (labels[i] == bgi) {
            nb++
            for (c in 0 until 3) { val v = fl[i * 3 + c].toDouble(); dev += abs(v - bg[c]); mean[c] += v; sq[c] += v * v }
        }
        val uniform = dev / max(1, nb) < 30
        val std = (0 until 3).sumOf { c -> val m = mean[c] / max(1, nb); sqrt(max(0.0, sq[c] / max(1, nb) - m * m)) } / 3
        if (!uniform) return if (overArt && inpainter != null) redrawArt(img, inpainter, x0, y0, x1, y1) else null

        // the true ink colour: the median of the pixels nearest the ink centre
        val pad = 6
        val px0 = max(0, x0 - pad); val py0 = max(0, y0 - pad); val px1 = min(W, x1 + pad); val py1 = min(H, y1 + pad)
        val pw = px1 - px0; val ph = py1 - py0
        val dBg = IntArray(pw * ph); val dFg = IntArray(pw * ph)
        for (y in 0 until ph) for (x in 0 until pw) {
            val i = (py0 + y) * W + px0 + x; val o = y * pw + x
            dBg[o] = dist(img, i, bg); dFg[o] = dist(img, i, fg)
        }
        val boxMask = BooleanArray(pw * ph) { dBg[it] > 60 && dFg[it] < dBg[it] * 1.2 }
        fg = inkColour(img, W, px0, py0, pw, ph, boxMask, dFg) ?: fg
        // outside the box but inside the pad: only what looks like ink, never the frame
        for (y in 0 until ph) for (x in 0 until pw) {
            val inside = x + px0 in x0 until x1 && y + py0 in y0 until y1
            if (!inside && dFg[y * pw + x] > dBg[y * pw + x]) boxMask[y * pw + x] = false
        }
        val interior = bubble?.let { bubbleInterior(img, it, x0, y0, x1, y1, bg) }
        if (interior != null) for (y in 0 until ph) for (x in 0 until pw) {
            boxMask[y * pw + x] = dBg[y * pw + x] > 45 && interior.at(x + px0, y + py0)
        }
        val horizontalSource = wb > hb * 1.15
        val sourceSize = sourceGlyphSize(boxMask, pw, x0 - px0, y0 - py0, wb, hb, horizontalSource)

        // In a bubble, erase every mark inside the whole interior, not just the detected box: glyphs
        // the detector left out (and hearts, dots) must not survive next to the new text.
        val ex0: Int; val ey0: Int; val ew: Int; val eh: Int; val mask: BooleanArray
        if (interior != null) {
            ex0 = interior.x0; ey0 = interior.y0; ew = interior.w; eh = interior.h
            mask = BooleanArray(ew * eh) { i -> interior.m[i] && dist(img, (ey0 + i / ew) * W + ex0 + i % ew, bg) > 45 }
        } else {
            ex0 = px0; ey0 = py0; ew = pw; eh = ph; mask = boxMask
        }
        // grow the mask a little, but never past the bubble interior
        val grown = ops.morphology(ByteArray(ew * eh) { if (mask[it]) 1 else 0 }, ew, eh, Morph.DILATE, kernel = 5, iterations = 2)
        if (interior != null) for (i in grown.indices) if (!interior.m[i]) grown[i] = 0

        if (std < 4) {
            for (i in 0 until ew * eh) if (grown[i] != 0.toByte()) {
                val o = ((ey0 + i / ew) * W + ex0 + i % ew) * 3
                for (c in 0 until 3) img.pixels[o + c] = bg[c].toInt().toByte()
            }
        } else {
            restoreGradient(img, ex0, ey0, ew, eh, grown, interior, x0, y0, x1, y1)
        }
        return Erased(
            background = IntArray(3) { bg[it].toInt() }, ink = IntArray(3) { fg[it].toInt() }, flat = true,
            sourceSize = sourceSize, area = interior ?: Region.rect(x0, y0, x1, y1), inBubble = interior != null,
            horizontalSource = horizontalSource,
        )
    }

    private fun nearest(fl: FloatArray, i: Int, cen: FloatArray, k: Int): Int {
        var bc = 0; var bd = Float.MAX_VALUE
        for (c in 0 until k) {
            var d = 0f
            for (ch in 0 until 3) { val v = fl[i * 3 + ch] - cen[c * 3 + ch]; d += v * v }
            if (d < bd) { bd = d; bc = c }
        }
        return bc
    }

    private fun dist(img: RgbImage, i: Int, c: FloatArray): Int =
        (abs(img.r(i) - c[0]) + abs(img.g(i) - c[1]) + abs(img.b(i) - c[2])).toInt()

    /** Median colour of the 30% of [mask] pixels nearest the ink centre. */
    private fun inkColour(img: RgbImage, W: Int, px0: Int, py0: Int, pw: Int, ph: Int, mask: BooleanArray, dFg: IntArray): FloatArray? {
        val hist = IntArray(766); var count = 0
        for (i in 0 until pw * ph) if (mask[i]) { hist[min(765, dFg[i])]++; count++ }
        if (count == 0) return null
        var acc = 0; var cut = 0
        while (cut < 765 && acc + hist[cut] <= count * 0.3) { acc += hist[cut]; cut++ }
        val ch = Array(3) { IntArray(256) }; var near = 0
        for (i in 0 until pw * ph) if (mask[i] && dFg[i] <= cut) {
            near++
            val p = (py0 + i / pw) * W + px0 + i % pw
            ch[0][img.r(p)]++; ch[1][img.g(p)]++; ch[2][img.b(p)]++
        }
        return FloatArray(3) { c -> var a = 0; var v = 0; while (v < 255 && a + ch[c][v] <= near / 2) { a += ch[c][v]; v++ }; v.toFloat() }
    }

    /**
     * The source glyph size: the widest ink runs across the lines (x for vertical text, y for
     * horizontal text). Furigana runs are narrower and drop out of the median.
     */
    /**
     * Text over artwork: its glyphs and their outline are masked and LaMa redraws the art beneath.
     * The translation is then drawn in the source's own style, its ink outlined in its outline colour.
     */
    private fun redrawArt(img: RgbImage, inpainter: MangaInpainter, x0: Int, y0: Int, x1: Int, y1: Int): Erased? {
        val wb = x1 - x0; val hb = y1 - y0
        val art = artMask(img, x0, y0, x1, y1) ?: return null
        val horizontalSource = wb > hb * 1.15
        val sourceSize = sourceGlyphSize(BooleanArray(wb * hb) { art.mask[it] != 0.toByte() }, wb, 0, 0, wb, hb, horizontalSource)
        inpainter.inpaint(img, x0, y0, x1, y1, art.mask)
        return Erased(
            background = art.outline, ink = art.ink, flat = false, sourceSize = sourceSize,
            area = Region.rect(x0, y0, x1, y1), inBubble = false, horizontalSource = horizontalSource,
        )
    }

    private class ArtMask(val mask: ByteArray, val ink: IntArray, val outline: IntArray)

    /**
     * Mask of text drawn over artwork, in box coordinates: the glyph colour group (thin strokes, so
     * erosion wipes most of it out, unlike flat areas of art) plus the outline group hugging it.
     */
    private fun artMask(img: RgbImage, x0: Int, y0: Int, x1: Int, y1: Int): ArtMask? {
        val W = img.width; val wb = x1 - x0; val hb = y1 - y0; val n = wb * hb
        if (n < 64) return null
        val fl = FloatArray(n * 3)
        for (y in 0 until hb) for (x in 0 until wb) {
            val i = (y0 + y) * W + x0 + x; val o = (y * wb + x) * 3
            fl[o] = img.r(i).toFloat(); fl[o + 1] = img.g(i).toFloat(); fl[o + 2] = img.b(i).toFloat()
        }
        val k = 4
        val stride = max(1, n / 4000); val ns = (n + stride - 1) / stride
        val cen = ops.kmeans(FloatArray(ns * 3) { fl[(it / 3) * stride * 3 + it % 3] }, k, attempts = 2)
        val lab = IntArray(n) { i -> nearest(fl, i, cen, k) }
        val mean = FloatArray(3) { ch -> ((0 until n).sumOf { fl[it * 3 + ch].toDouble() } / n).toFloat() }
        fun maskOf(c: Int) = ByteArray(n) { if (lab[it] == c) 1 else 0 }
        var ink = -1; var best = -1.0
        for (c in 0 until k) {
            val mk = maskOf(c); val total = mk.count { it != 0.toByte() }
            if (total < 30) continue
            val eroded = ops.morphology(mk, wb, hb, Morph.ERODE, kernel = 3, iterations = 3).count { it != 0.toByte() }
            val contrast = (0 until 3).sumOf { abs(cen[c * 3 + it] - mean[it]).toDouble() }
            val score = (1 - eroded.toDouble() / total) * contrast
            if (score > best) { best = score; ink = c }
        }
        if (ink < 0) return null
        val near = ops.morphology(maskOf(ink), wb, hb, Morph.DILATE, kernel = 3, iterations = 6)
        var outline = -1; var bestO = -1.0
        for (c in 0 until k) {
            if (c == ink) continue
            var inNear = 0; var total = 0
            for (i in 0 until n) if (lab[i] == c) { total++; if (near[i] != 0.toByte()) inNear++ }
            val contrast = (0 until 3).sumOf { abs(cen[c * 3 + it] - cen[ink * 3 + it]).toDouble() }
            val score = inNear.toDouble() / max(1, total) * contrast
            if (score > bestO) { bestO = score; outline = c }
        }
        if (outline < 0) return null
        var m = ByteArray(n) { if (lab[it] == ink || (lab[it] == outline && near[it] != 0.toByte())) 1 else 0 }
        m = ops.morphology(m, wb, hb, Morph.CLOSE, kernel = 3)
        m = ops.morphology(m, wb, hb, Morph.DILATE, kernel = 3, iterations = 2)
        return ArtMask(
            mask = m,
            ink = IntArray(3) { cen[ink * 3 + it].toInt().coerceIn(0, 255) },
            outline = IntArray(3) { cen[outline * 3 + it].toInt().coerceIn(0, 255) },
        )
    }

    private fun sourceGlyphSize(mask: BooleanArray, pw: Int, bx: Int, by: Int, wb: Int, hb: Int, horizontal: Boolean): Int? {
        val runs = mutableListOf<Int>(); var r = 0
        val across = if (horizontal) hb else wb
        val along = if (horizontal) wb else hb
        for (a in 0 until across) {
            var line = 0
            for (b in 0 until along) {
                val x = bx + if (horizontal) b else a; val y = by + if (horizontal) a else b
                if (mask[y * pw + x]) line++
            }
            if (line > max(2, (along * 0.04).toInt())) r++ else if (r > 0) { runs.add(r); r = 0 }
        }
        if (r > 0) runs.add(r)
        val m = runs.maxOrNull() ?: return null
        return runs.filter { it >= 0.6 * m }.sorted().let { it[it.size / 2] }
    }

    /**
     * Bubble interior: the background flood-filled from inside the box (fixed range, so it cannot
     * creep through the anti-aliased outline), its holes (the glyphs) filled, and closed so glyphs
     * touching the outline count as inside too.
     */
    private fun bubbleInterior(img: RgbImage, bubble: Box, x0: Int, y0: Int, x1: Int, y1: Int, bg: FloatArray): Region? {
        val W = img.width; val H = img.height
        val bx0 = max(0, bubble.x0.toInt()); val by0 = max(0, bubble.y0.toInt())
        val bx1 = min(W, bubble.x1.toInt()); val by1 = min(H, bubble.y1.toInt())
        if (bx1 - bx0 < 4 || by1 - by0 < 4) return null
        val sub = img.crop(bx0, by0, bx1, by1)
        val sw = sub.width; val sh = sub.height
        var seed = -1; var seeds = 0
        // the middle background pixel inside the text box
        val candidates = ArrayList<Int>()
        for (y in max(0, y0 - by0) until min(sh, y1 - by0)) for (x in max(0, x0 - bx0) until min(sw, x1 - bx0)) {
            val i = y * sw + x
            if (dist(sub, i, bg) < 20) candidates += i
        }
        if (candidates.isEmpty()) return null
        seed = candidates[candidates.size / 2]; seeds = candidates.size
        val reg = ops.floodFill(sub, seed % sw, seed / sw, tolerance = 40)
        var filled = ops.fillLargestContour(reg, sw, sh)
        val kernel = max(15, (min(sw, sh) * 0.15).toInt()) or 1
        filled = ops.morphology(filled, sw, sh, Morph.CLOSE, kernel, ellipse = true)
        val m = BooleanArray(sw * sh) { filled[it] != 0.toByte() }
        return if (seeds == 0 || m.none { it }) null else Region(bx0, by0, sw, sh, m)
    }

    /**
     * A gradient background: fit a quadratic surface per channel to the background close to the
     * glyphs and paint it where they were. Falls back to Telea when the fit is poor (not a smooth
     * background after all).
     */
    private fun restoreGradient(img: RgbImage, ex0: Int, ey0: Int, ew: Int, eh: Int, grown: ByteArray, interior: Region?, x0: Int, y0: Int, x1: Int, y1: Int) {
        val W = img.width; val H = img.height
        val xa = max(0, ex0 - 24); val ya = max(0, ey0 - 24); val xb = min(W, ex0 + ew + 24); val yb = min(H, ey0 + eh + 24)
        val ww = xb - xa; val wh = yb - ya
        val hole = BooleanArray(ww * wh)
        for (y in 0 until eh) for (x in 0 until ew) if (grown[y * ew + x] != 0.toByte()) hole[(y + ey0 - ya) * ww + x + ex0 - xa] = true
        val keep = BooleanArray(ww * wh) { i ->
            val x = i % ww + xa; val y = i / ww + ya
            !hole[i] && (interior?.at(x, y) ?: (x in x0 until x1 && y in y0 until y1))
        }
        val near = ops.morphology(ByteArray(ww * wh) { if (hole[it]) 1 else 0 }, ww, wh, Morph.DILATE, kernel = 25, ellipse = true)
        val win = img.crop(xa, ya, xb, yb)
        val idx = (0 until ww * wh).filter { keep[it] && near[it] != 0.toByte() }
        val step = max(1, idx.size / 3000)
        val ata = Array(6) { DoubleArray(6) }; val atb = Array(3) { DoubleArray(6) }
        val sx = ww / 2.0; val sy = wh / 2.0
        val f = DoubleArray(6)
        fun basis(i: Int) {
            val u = (i % ww - sx) / sx; val v = (i / ww - sy) / sy
            f[0] = 1.0; f[1] = u; f[2] = v; f[3] = u * u; f[4] = u * v; f[5] = v * v
        }
        fun channel(i: Int, c: Int) = when (c) { 0 -> win.r(i); 1 -> win.g(i); else -> win.b(i) }
        var s = 0
        while (s < idx.size) {
            val i = idx[s]; basis(i)
            for (r in 0 until 6) { for (c in 0 until 6) ata[r][c] += f[r] * f[c]; for (ch in 0 until 3) atb[ch][r] += f[r] * channel(i, ch) }
            s += step
        }
        val coef = Array(3) { solve6(ata, atb[it]) }
        // robust check: the median residual (JPEG ringing next to strokes inflates the mean)
        val residuals = IntArray(64); var count = 0; s = 0
        while (s < idx.size) {
            val i = idx[s]; basis(i)
            var e = 0.0
            for (ch in 0 until 3) { var v = 0.0; for (r in 0 until 6) v += coef[ch][r] * f[r]; e += abs(v - channel(i, ch)) }
            residuals[min(63, (e / 3).toInt())]++; count++; s += step
        }
        var acc = 0; var median = 0
        while (median < 63 && acc + residuals[median] <= count / 2) { acc += residuals[median]; median++ }
        val result = if (idx.size < 50 || median > 4) {
            ops.inpaint(win, ByteArray(ww * wh) { if (hole[it]) 1 else 0 }, radius = 5.0)
        } else {
            val out = win.copy()
            for (i in 0 until ww * wh) if (hole[i]) {
                basis(i)
                for (ch in 0 until 3) { var v = 0.0; for (r in 0 until 6) v += coef[ch][r] * f[r]; out.pixels[i * 3 + ch] = v.toInt().coerceIn(0, 255).toByte() }
            }
            out
        }
        img.paste(result, xa, ya)
    }

    private fun solve6(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray {
        val n = 6
        val a = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a0[r][c] + (if (r == c) 1e-6 else 0.0) else b0[r] } }
        for (c in 0 until n) {
            val p = (c until n).maxBy { abs(a[it][c]) }; val t = a[c]; a[c] = a[p]; a[p] = t
            for (r in 0 until n) if (r != c) { val m = a[r][c] / a[c][c]; for (k in c..n) a[r][k] -= m * a[c][k] }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }
}
