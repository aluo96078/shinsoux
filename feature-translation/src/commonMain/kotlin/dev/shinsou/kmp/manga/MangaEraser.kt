package dev.shinsou.kmp.manga

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val ART_GROUPS = 4

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
     * [horizontal]: how the source runs, when [box] is not one region of it.
     */
    public fun erase(img: RgbImage, box: Box, bubble: Box?, overArt: Boolean = false, horizontal: Boolean? = null): Erased? {
        val r = clampedBox(img, box)
        val colours = groupColours(img, r)
        if (!colours.uniform) return if (overArt && inpainter != null) redrawArt(img, inpainter, r, horizontal) else null

        val ink = inkMask(img, r, colours)
        val interior = bubble?.let { bubbleInterior(img, it, r, colours.bg) }
        if (interior != null) limitToInterior(ink, interior)
        // [horizontal] when the box joins several regions: their joined shape says nothing of how
        // the text runs (short columns side by side make a wide box)
        val horizontalSource = horizontal ?: (r.w > r.h * 1.15)
        val pad = ink.pad
        val sourceSize = sourceGlyphSize(ink.mask, pad.pw, r.x0 - pad.px0, r.y0 - pad.py0, r.w, r.h, horizontalSource)
        clearMarks(img, r, ink, interior, colours)
        return Erased(
            background = IntArray(3) { colours.bg[it].toInt() }, ink = IntArray(3) { ink.fg[it].toInt() }, flat = true,
            sourceSize = sourceSize, area = interior ?: Region.rect(r.x0, r.y0, r.x1, r.y1), inBubble = interior != null,
            horizontalSource = horizontalSource,
        )
    }

    /** A rectangle of the page, clamped to it. */
    private class BoxRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val w: Int get() = x1 - x0
        val h: Int get() = y1 - y0
    }

    private fun clampedBox(img: RgbImage, box: Box): BoxRect {
        val w = img.width; val h = img.height
        val x0 = box.x0.toInt().coerceIn(0, w - 1); val y0 = box.y0.toInt().coerceIn(0, h - 1)
        val x1 = box.x1.toInt().coerceIn(x0 + 1, w); val y1 = box.y1.toInt().coerceIn(y0 + 1, h)
        return BoxRect(x0, y0, x1, y1)
    }

    /** The pixels of a box as floats, three per pixel. */
    private fun boxPixels(img: RgbImage, r: BoxRect): FloatArray {
        val wb = r.w; val hb = r.h
        val fl = FloatArray(wb * hb * 3)
        for (y in 0 until hb) for (x in 0 until wb) {
            val i = (r.y0 + y) * img.width + r.x0 + x; val o = (y * wb + x) * 3
            fl[o] = img.r(i).toFloat(); fl[o + 1] = img.g(i).toFloat(); fl[o + 2] = img.b(i).toFloat()
        }
        return fl
    }

    /** The [k] colour centres of [fl] (n pixels), clustered over a sample of about 4000. */
    private fun clusterCentres(fl: FloatArray, n: Int, k: Int): FloatArray {
        val stride = max(1, n / 4000); val ns = (n + stride - 1) / stride
        return ops.kmeans(FloatArray(ns * 3) { fl[(it / 3) * stride * 3 + it % 3] }, k, attempts = 2)
    }

    /** The colour groups of a box: [labels] gives each pixel its group, [counts] the size of each, [bgi] the largest. */
    private class Palette(val cen: FloatArray, val labels: IntArray, val counts: IntArray, val bgi: Int)

    /** The background and ink colours of a box, and whether the background is flat ([uniform]) or a gradient with spread [std]. */
    private class Colours(val bg: FloatArray, val fg: FloatArray, val uniform: Boolean, val std: Double)

    private class Spread(val meanDeviation: Double, val std: Double)

    /**
     * Three colour groups inside the box: the largest is the background; the ink is the group
     * most unlike it that is made of many small strokes (a frame or panel is one big blob).
     */
    private fun groupColours(img: RgbImage, r: BoxRect): Colours {
        val n = r.w * r.h
        val fl = boxPixels(img, r)
        val k = 3
        val cen = clusterCentres(fl, n, k)
        val labels = IntArray(n) { i -> nearest(fl, i, cen, k) }
        val cnt = IntArray(k).also { c -> labels.forEach { c[it]++ } }
        val palette = Palette(cen, labels, cnt, cnt.indices.maxBy { cnt[it] })
        val ink = inkGroup(palette, r.w, r.h)
        val bg = FloatArray(3) { cen[palette.bgi * 3 + it] }
        val spread = backgroundSpread(fl, palette, bg)
        return Colours(bg, FloatArray(3) { cen[ink * 3 + it] }, spread.meanDeviation < 30, spread.std)
    }

    /** The group most unlike the background that is made of many small strokes. */
    private fun inkGroup(p: Palette, wb: Int, hb: Int): Int {
        var ink = -1; var best = -1.0
        for (c in p.counts.indices) {
            val score = inkScore(p, c, wb, hb)
            if (score > best) { best = score; ink = c }
        }
        if (ink < 0) ink = p.counts.indices.first { it != p.bgi }
        return ink
    }

    /** How inky group [c] is (contrast times how broken up it is); -1 for the background and for groups too small to be ink. */
    private fun inkScore(p: Palette, c: Int, wb: Int, hb: Int): Double {
        if (c == p.bgi || p.counts[c] < 20) return -1.0
        val n = p.labels.size
        val areas = ops.componentAreas(ByteArray(n) { if (p.labels[it] == c) 1 else 0 }, wb, hb)
        val largest = max(1, areas.maxOrNull() ?: 1)
        val contrast = (0 until 3).sumOf { abs(p.cen[c * 3 + it] - p.cen[p.bgi * 3 + it]).toDouble() }
        return contrast * (1 - largest.toDouble() / p.counts[c])
    }

    private fun backgroundSpread(fl: FloatArray, p: Palette, bg: FloatArray): Spread {
        var dev = 0.0; val mean = DoubleArray(3); val sq = DoubleArray(3); var nb = 0
        for (i in p.labels.indices) if (p.labels[i] == p.bgi) {
            nb++
            for (c in 0 until 3) { val v = fl[i * 3 + c].toDouble(); dev += abs(v - bg[c]); mean[c] += v; sq[c] += v * v }
        }
        val std = (0 until 3).sumOf { c -> val m = mean[c] / max(1, nb); sqrt(max(0.0, sq[c] / max(1, nb) - m * m)) } / 3
        return Spread(dev / max(1, nb), std)
    }

    /** The box and a pad of 6 pixels around it (clamped to the page). */
    private class Pad(val px0: Int, val py0: Int, val pw: Int, val ph: Int)

    /** What looks like ink in a [Pad]: [mask] (pad coordinates), its distance to the background [dBg], and the true ink colour [fg]. */
    private class InkMask(val pad: Pad, val dBg: IntArray, val mask: BooleanArray, val fg: FloatArray)

    private fun padAround(img: RgbImage, r: BoxRect): Pad {
        val pad = 6
        val px0 = max(0, r.x0 - pad); val py0 = max(0, r.y0 - pad); val px1 = min(img.width, r.x1 + pad); val py1 = min(img.height, r.y1 + pad)
        return Pad(px0, py0, px1 - px0, py1 - py0)
    }

    /** The ink in the box (its true colour: the median of the pixels nearest the ink centre) and only what looks like ink around it, never the frame. */
    private fun inkMask(img: RgbImage, r: BoxRect, colours: Colours): InkMask {
        val pad = padAround(img, r)
        val pw = pad.pw; val ph = pad.ph
        val dBg = IntArray(pw * ph); val dFg = IntArray(pw * ph)
        for (y in 0 until ph) for (x in 0 until pw) {
            val i = (pad.py0 + y) * img.width + pad.px0 + x; val o = y * pw + x
            dBg[o] = dist(img, i, colours.bg); dFg[o] = dist(img, i, colours.fg)
        }
        val boxMask = BooleanArray(pw * ph) { dBg[it] > 60 && dFg[it] < dBg[it] * 1.2 }
        val fg = inkColour(img, img.width, pad.px0, pad.py0, pw, ph, boxMask, dFg) ?: colours.fg
        // outside the box but inside the pad: only what looks like ink, never the frame
        for (y in 0 until ph) for (x in 0 until pw) {
            val inside = x + pad.px0 in r.x0 until r.x1 && y + pad.py0 in r.y0 until r.y1
            if (!inside && dFg[y * pw + x] > dBg[y * pw + x]) boxMask[y * pw + x] = false
        }
        return InkMask(pad, dBg, boxMask, fg)
    }

    /** Within a bubble only what is inside it, and clearly unlike the background, is ink. */
    private fun limitToInterior(ink: InkMask, interior: Region) {
        val pad = ink.pad
        for (y in 0 until pad.ph) for (x in 0 until pad.pw) {
            ink.mask[y * pad.pw + x] = ink.dBg[y * pad.pw + x] > 45 && interior.at(x + pad.px0, y + pad.py0)
        }
    }

    /** The part of the page to clear, [mask] in its own coordinates. */
    private class EraseArea(val x0: Int, val y0: Int, val w: Int, val h: Int, val mask: BooleanArray)

    /**
     * In a bubble, erase every mark inside the whole interior, not just the detected box: glyphs
     * the detector left out (and hearts, dots) must not survive next to the new text.
     */
    private fun eraseArea(img: RgbImage, interior: Region?, ink: InkMask, bg: FloatArray): EraseArea {
        if (interior == null) return EraseArea(ink.pad.px0, ink.pad.py0, ink.pad.pw, ink.pad.ph, ink.mask)
        val ex0 = interior.x0; val ey0 = interior.y0; val ew = interior.w; val eh = interior.h
        val mask = BooleanArray(ew * eh) { i -> interior.m[i] && dist(img, (ey0 + i / ew) * img.width + ex0 + i % ew, bg) > 45 }
        return EraseArea(ex0, ey0, ew, eh, mask)
    }

    /** Clears the marks of the area, a little grown but never past the bubble interior, and restores the background. */
    private fun clearMarks(img: RgbImage, r: BoxRect, ink: InkMask, interior: Region?, colours: Colours) {
        val area = eraseArea(img, interior, ink, colours.bg)
        // grow the mask a little, but never past the bubble interior
        val grown = ops.morphology(ByteArray(area.w * area.h) { if (area.mask[it]) 1 else 0 }, area.w, area.h, Morph.DILATE, kernel = 5, iterations = 2)
        if (interior != null) for (i in grown.indices) if (!interior.m[i]) grown[i] = 0
        if (colours.std < 4) fillFlat(img, area, grown, colours.bg) else restoreGradient(img, area, grown, interior, r)
    }

    private fun fillFlat(img: RgbImage, area: EraseArea, grown: ByteArray, bg: FloatArray) {
        for (i in 0 until area.w * area.h) if (grown[i] != 0.toByte()) {
            val o = ((area.y0 + i / area.w) * img.width + area.x0 + i % area.w) * 3
            for (c in 0 until 3) img.pixels[o + c] = bg[c].toInt().toByte()
        }
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
     * Text over artwork: its glyphs and their outline are masked and LaMa redraws the art beneath.
     * The translation is then drawn in the source's own style, its ink outlined in its outline colour.
     */
    private fun redrawArt(img: RgbImage, inpainter: MangaInpainter, r: BoxRect, horizontal: Boolean? = null): Erased? {
        val wb = r.w; val hb = r.h
        val art = artMask(img, r) ?: return null
        // [horizontal] when the box joins several regions: their joined shape says nothing of how
        // the text runs (short columns side by side make a wide box)
        val horizontalSource = horizontal ?: (wb > hb * 1.15)
        val sourceSize = sourceGlyphSize(BooleanArray(wb * hb) { art.mask[it] != 0.toByte() }, wb, 0, 0, wb, hb, horizontalSource)
        inpainter.inpaint(img, r.x0, r.y0, r.x1, r.y1, art.mask)
        return Erased(
            background = art.outline, ink = art.ink, flat = false, sourceSize = sourceSize,
            area = Region.rect(r.x0, r.y0, r.x1, r.y1), inBubble = false, horizontalSource = horizontalSource,
        )
    }

    private class ArtMask(val mask: ByteArray, val ink: IntArray, val outline: IntArray)

    /** The four colour groups of a box over artwork: [lab] gives each pixel its group. */
    private class ArtGroups(val fl: FloatArray, val cen: FloatArray, val lab: IntArray, val wb: Int, val hb: Int) {
        fun maskOf(c: Int) = ByteArray(lab.size) { if (lab[it] == c) 1 else 0 }
    }

    /**
     * Mask of text drawn over artwork, in box coordinates: the glyph colour group (thin strokes, so
     * erosion wipes most of it out, unlike flat areas of art) plus the outline group hugging it.
     */
    private fun artMask(img: RgbImage, r: BoxRect): ArtMask? {
        val wb = r.w; val hb = r.h; val n = wb * hb
        if (n < 64) return null
        val fl = boxPixels(img, r)
        val k = ART_GROUPS
        val cen = clusterCentres(fl, n, k)
        val groups = ArtGroups(fl, cen, IntArray(n) { i -> nearest(fl, i, cen, k) }, wb, hb)
        val ink = artInkGroup(groups)
        val near = if (ink < 0) null else ops.morphology(groups.maskOf(ink), wb, hb, Morph.DILATE, kernel = 3, iterations = 6)
        val outline = if (near == null) -1 else outlineGroup(groups, ink, near)
        return if (near == null || outline < 0) null else buildArtMask(groups, ink, outline, near)
    }

    /** The group of thin strokes unlike the box as a whole, or -1 when there is none. */
    private fun artInkGroup(g: ArtGroups): Int {
        val n = g.lab.size
        val mean = FloatArray(3) { ch -> ((0 until n).sumOf { g.fl[it * 3 + ch].toDouble() } / n).toFloat() }
        var ink = -1; var best = -1.0
        for (c in 0 until ART_GROUPS) {
            val score = artInkScore(g, mean, c)
            if (score > best) { best = score; ink = c }
        }
        return ink
    }

    /** How much group [c] looks like glyph strokes; -1 when it is too small to be. */
    private fun artInkScore(g: ArtGroups, mean: FloatArray, c: Int): Double {
        val mk = g.maskOf(c); val total = mk.count { it != 0.toByte() }
        if (total < 30) return -1.0
        val eroded = ops.morphology(mk, g.wb, g.hb, Morph.ERODE, kernel = 3, iterations = 3).count { it != 0.toByte() }
        val contrast = (0 until 3).sumOf { abs(g.cen[c * 3 + it] - mean[it]).toDouble() }
        return (1 - eroded.toDouble() / total) * contrast
    }

    /** The group, besides [ink], that most hugs the ink ([near]) and differs from it; -1 when there is none. */
    private fun outlineGroup(g: ArtGroups, ink: Int, near: ByteArray): Int {
        var outline = -1; var bestO = -1.0
        for (c in 0 until ART_GROUPS) {
            val score = outlineScore(g, ink, near, c)
            if (score > bestO) { bestO = score; outline = c }
        }
        return outline
    }

    /** How much group [c] looks like the outline of the ink; -1 for the ink itself. */
    private fun outlineScore(g: ArtGroups, ink: Int, near: ByteArray, c: Int): Double {
        if (c == ink) return -1.0
        var inNear = 0; var total = 0
        for (i in g.lab.indices) if (g.lab[i] == c) { total++; if (near[i] != 0.toByte()) inNear++ }
        val contrast = (0 until 3).sumOf { abs(g.cen[c * 3 + it] - g.cen[ink * 3 + it]).toDouble() }
        return inNear.toDouble() / max(1, total) * contrast
    }

    private fun buildArtMask(g: ArtGroups, ink: Int, outline: Int, near: ByteArray): ArtMask {
        val lab = g.lab
        var m = ByteArray(lab.size) { if (lab[it] == ink || (lab[it] == outline && near[it] != 0.toByte())) 1 else 0 }
        m = ops.morphology(m, g.wb, g.hb, Morph.CLOSE, kernel = 3)
        m = ops.morphology(m, g.wb, g.hb, Morph.DILATE, kernel = 3, iterations = 2)
        return ArtMask(
            mask = m,
            ink = IntArray(3) { g.cen[ink * 3 + it].toInt().coerceIn(0, 255) },
            outline = IntArray(3) { g.cen[outline * 3 + it].toInt().coerceIn(0, 255) },
        )
    }

    /**
     * The source glyph size: the widest ink runs across the lines (x for vertical text, y for
     * horizontal text). Furigana runs are narrower and drop out of the median.
     */
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
    private fun bubbleInterior(img: RgbImage, bubble: Box, r: BoxRect, bg: FloatArray): Region? {
        val W = img.width; val H = img.height
        val x0 = r.x0; val y0 = r.y0; val x1 = r.x1; val y1 = r.y1
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

    /** The part of the page around the erased area that the fit looks at: [xa], [ya] its corner, [ww] × [wh] its size. */
    private class Window(val xa: Int, val ya: Int, val ww: Int, val wh: Int)

    /**
     * A quadratic surface u, v ↦ c0 + c1·u + c2·v + c3·u² + c4·uv + c5·v² over a [win] pixels grid,
     * per colour channel. [f] holds the basis at the pixel last given to [basis].
     */
    private class Surface(val win: RgbImage, val ww: Int, val wh: Int) {
        val f = DoubleArray(6)
        private val sx = ww / 2.0
        private val sy = wh / 2.0

        fun basis(i: Int) {
            val u = (i % ww - sx) / sx; val v = (i / ww - sy) / sy
            f[0] = 1.0; f[1] = u; f[2] = v; f[3] = u * u; f[4] = u * v; f[5] = v * v
        }

        fun channel(i: Int, c: Int) = when (c) { 0 -> win.r(i); 1 -> win.g(i); else -> win.b(i) }

        /** The surface of one channel with coefficients [coef] at the pixel last given to [basis]. */
        fun value(coef: DoubleArray): Double {
            var v = 0.0
            for (r in 0 until 6) v += coef[r] * f[r]
            return v
        }
    }

    /**
     * A gradient background: fit a quadratic surface per channel to the background close to the
     * glyphs and paint it where they were. Falls back to Telea when the fit is poor (not a smooth
     * background after all).
     */
    private fun restoreGradient(img: RgbImage, area: EraseArea, grown: ByteArray, interior: Region?, r: BoxRect) {
        val xa = max(0, area.x0 - 24); val ya = max(0, area.y0 - 24)
        val xb = min(img.width, area.x0 + area.w + 24); val yb = min(img.height, area.y0 + area.h + 24)
        val ww = xb - xa; val wh = yb - ya
        val win = Window(xa, ya, ww, wh)
        val hole = holeMask(area, grown, win)
        val keep = keepMask(hole, win, interior, r)
        val near = ops.morphology(ByteArray(ww * wh) { if (hole[it]) 1 else 0 }, ww, wh, Morph.DILATE, kernel = 25, ellipse = true)
        val patch = img.crop(xa, ya, xb, yb)
        val idx = (0 until ww * wh).filter { keep[it] && near[it] != 0.toByte() }
        val step = max(1, idx.size / 3000)
        val surface = Surface(patch, ww, wh)
        val coef = fitCoefficients(surface, idx, step)
        // robust check: the median residual (JPEG ringing next to strokes inflates the mean)
        val median = medianResidual(surface, coef, idx, step)
        val result = if (idx.size < 50 || median > 4) {
            ops.inpaint(patch, ByteArray(ww * wh) { if (hole[it]) 1 else 0 }, radius = 5.0)
        } else {
            paintSurface(surface, coef, hole)
        }
        img.paste(result, xa, ya)
    }

    /** The erased pixels, in window coordinates. */
    private fun holeMask(area: EraseArea, grown: ByteArray, win: Window): BooleanArray {
        val hole = BooleanArray(win.ww * win.wh)
        for (y in 0 until area.h) for (x in 0 until area.w) {
            if (grown[y * area.w + x] != 0.toByte()) hole[(y + area.y0 - win.ya) * win.ww + x + area.x0 - win.xa] = true
        }
        return hole
    }

    /** The background to fit to: inside the bubble, or else inside the box, but not in the hole. */
    private fun keepMask(hole: BooleanArray, win: Window, interior: Region?, r: BoxRect): BooleanArray =
        BooleanArray(win.ww * win.wh) { i ->
            val x = i % win.ww + win.xa; val y = i / win.ww + win.ya
            !hole[i] && (interior?.at(x, y) ?: (x in r.x0 until r.x1 && y in r.y0 until r.y1))
        }

    /** Least squares over every [step]th pixel of [idx], one coefficient set per channel. */
    private fun fitCoefficients(surface: Surface, idx: List<Int>, step: Int): Array<DoubleArray> {
        val ata = Array(6) { DoubleArray(6) }; val atb = Array(3) { DoubleArray(6) }
        val f = surface.f
        var s = 0
        while (s < idx.size) {
            val i = idx[s]; surface.basis(i)
            for (r in 0 until 6) { for (c in 0 until 6) ata[r][c] += f[r] * f[c]; for (ch in 0 until 3) atb[ch][r] += f[r] * surface.channel(i, ch) }
            s += step
        }
        return Array(3) { solve6(ata, atb[it]) }
    }

    /** The median (over every [step]th pixel of [idx]) of the mean absolute error of the fit, capped at 63. */
    private fun medianResidual(surface: Surface, coef: Array<DoubleArray>, idx: List<Int>, step: Int): Int {
        val residuals = IntArray(64); var count = 0; var s = 0
        while (s < idx.size) {
            val i = idx[s]; surface.basis(i)
            var e = 0.0
            for (ch in 0 until 3) e += abs(surface.value(coef[ch]) - surface.channel(i, ch))
            residuals[min(63, (e / 3).toInt())]++; count++; s += step
        }
        var acc = 0; var median = 0
        while (median < 63 && acc + residuals[median] <= count / 2) { acc += residuals[median]; median++ }
        return median
    }

    /** A copy of the window with the surface painted into the hole. */
    private fun paintSurface(surface: Surface, coef: Array<DoubleArray>, hole: BooleanArray): RgbImage {
        val out = surface.win.copy()
        for (i in 0 until surface.ww * surface.wh) if (hole[i]) {
            surface.basis(i)
            for (ch in 0 until 3) out.pixels[i * 3 + ch] = surface.value(coef[ch]).toInt().coerceIn(0, 255).toByte()
        }
        return out
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
