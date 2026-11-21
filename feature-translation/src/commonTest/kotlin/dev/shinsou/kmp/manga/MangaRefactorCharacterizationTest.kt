package dev.shinsou.kmp.manga

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the exact output of [MangaLayout] and [MangaEraser] (positions, sizes, pixels, bit for bit)
 * so that restructuring either keeps results identical. Each case is digested to one FNV-1a hash.
 */
class MangaRefactorCharacterizationTest {
    private class Fnv {
        var h = -3750763034362895579L
        fun add(v: Long): Fnv { var x = v; repeat(8) { h = (h xor (x and 0xff)) * 1099511628211L; x = x ushr 8 }; return this }
        fun add(v: Int) = add(v.toLong())
        fun add(v: Float) = add(v.toBits())
        fun add(s: String): Fnv { add(s.length); s.forEach { add(it.code) }; return this }
        fun add(b: Boolean) = add(if (b) 1 else 0)
    }

    private val measure = TextMeasure { text, size -> text.sumOf { if (it.code < 0x2E80) (size * 0.5).toDouble() else size.toDouble() }.toFloat() }
    private val layout = MangaLayout(measure)

    private fun oval(w: Int, h: Int, x0: Int = 0, y0: Int = 0): Region {
        val cx = w / 2f; val cy = h / 2f
        return Region(x0, y0, w, h, BooleanArray(w * h) { i ->
            val dx = (i % w - cx) / cx; val dy = (i / w - cy) / cy
            dx * dx + dy * dy <= 1f
        })
    }

    private fun digest(l: TextLayout?): String {
        if (l == null) return "null"
        val f = Fnv().add(l.size).add(l.lines).add(l.vertical)
        l.glyphs.forEach { f.add(it.x).add(it.y).add(it.text).add(it.kind.ordinal) }
        return f.h.toULong().toString(16)
    }

    private val texts = listOf(
        "等一下，你到底在說什麼啊？",
        "這次絕對不會再讓你逃走！「沒關係」……我一個人也可以的",
        "Wait, where are you going with 1,000 Wi-Fi codes?",
        "第12話OK2026USB Thank you 啊!? 借我USB線",
        "Hello world",
        "好——！？……真的嗎。",
        "我",
    )

    @Test
    fun layoutsAreUnchanged() {
        val shapes = listOf(
            Triple(oval(220, 420), Box(40f, 60f, 180f, 360f), 30),
            Triple(oval(420, 240, 15, 7), Box(40f, 40f, 380f, 200f), 28),
            Triple(oval(90, 520), Box(10f, 20f, 80f, 500f), 28),
            Triple(Region.rect(3, 4, 203, 134), Box(0f, 0f, 200f, 130f), 40),
        )
        val sb = StringBuilder()
        for ((si, shape) in shapes.withIndex()) for ((ti, t) in texts.withIndex()) for (o in MangaOrientation.values()) for (bubble in listOf(true, false)) {
            val src = if (ti % 2 == 0) shape.third else null
            val l = layout.layout(t, shape.first, shape.second, src, bubble, horizontalSource = ti % 3 == 0, orientation = o)
            sb.append("$si/$ti/$o/$bubble=${digest(l)}\n")
        }
        assertEquals(EXPECTED_LAYOUT, Fnv().add(sb.toString()).h.toULong().toString(16), sb.toString())
    }

    @Test
    fun tokensAndLineBreaksAreUnchanged() {
        val sb = StringBuilder()
        for (t in texts + listOf("Ｗｉ-Ｆｉ １２３％ 4.5 a,b 7,000 !! ？！ 「」…… ー", "")) for (v in listOf(true, false)) {
            val toks = layout.tokens(t, 30, v)
            sb.append(Fnv().also { f -> toks.forEach { f.add(it.text).add(it.kind.ordinal).add(it.adv) } }.h).append(',')
            for (caps in listOf(listOf(150f, 150f, 150f), listOf(60f, 60f, 60f, 60f, 60f, 60f, 60f, 60f), List(12) { 33f }, listOf(400f), listOf(90f, 300f))) {
                val lines = layout.breakLines(toks, caps, 30)
                sb.append(if (lines == null) "n" else Fnv().also { f -> lines.forEach { l -> f.add(l.size); l.forEach { f.add(it.text) } } }.h.toString()).append(';')
            }
            sb.append('\n')
        }
        assertEquals(EXPECTED_TOKENS, Fnv().add(sb.toString()).h.toULong().toString(16), sb.toString())
    }

    @Test
    fun chamferIsUnchanged() {
        val f = Fnv()
        for ((w, h) in listOf(1 to 1, 7 to 5, 40 to 31, 3 to 60)) {
            val mask = ByteArray(w * h) { if ((it % w) in 1 until w - 1 && (it / w) in 1 until h - 1 || (it * 7) % 11 == 0) 1 else 0 }
            MangaLayout.Geometry.chamfer(mask, w, h).forEach { f.add(it) }
        }
        assertEquals(EXPECTED_CHAMFER, f.h.toULong().toString(16))
    }

    // ---- eraser ----

    private class FakeOps : VisionOps {
        override fun resize(image: RgbImage, width: Int, height: Int, area: Boolean) = error("unused")

        override fun kmeans(samples: FloatArray, k: Int, attempts: Int): FloatArray {
            val n = samples.size / 3
            val cen = FloatArray(k * 3)
            fun d(i: Int, c: Int): Float { var s = 0f; for (ch in 0 until 3) { val v = samples[i * 3 + ch] - cen[c * 3 + ch]; s += v * v }; return s }
            for (ch in 0 until 3) cen[ch] = samples[ch]
            for (c in 1 until k) {
                var bi = 0; var bd = -1f
                for (i in 0 until n) { var m = Float.MAX_VALUE; for (p in 0 until c) m = minOf(m, d(i, p)); if (m > bd) { bd = m; bi = i } }
                for (ch in 0 until 3) cen[c * 3 + ch] = samples[bi * 3 + ch]
            }
            repeat(8) {
                val sum = FloatArray(k * 3); val cnt = IntArray(k)
                for (i in 0 until n) {
                    var bc = 0; var bd = Float.MAX_VALUE
                    for (c in 0 until k) { val dd = d(i, c); if (dd < bd) { bd = dd; bc = c } }
                    cnt[bc]++; for (ch in 0 until 3) sum[bc * 3 + ch] += samples[i * 3 + ch]
                }
                for (c in 0 until k) if (cnt[c] > 0) for (ch in 0 until 3) cen[c * 3 + ch] = sum[c * 3 + ch] / cnt[c]
            }
            return cen
        }

        override fun componentAreas(mask: ByteArray, width: Int, height: Int): IntArray {
            val seen = BooleanArray(mask.size); val out = ArrayList<Int>()
            for (s in mask.indices) if (mask[s] != 0.toByte() && !seen[s]) {
                var area = 0; val stack = ArrayDeque<Int>(); stack.add(s); seen[s] = true
                while (stack.isNotEmpty()) {
                    val p = stack.removeLast(); area++
                    for (dy in -1..1) for (dx in -1..1) {
                        val x = p % width + dx; val y = p / width + dy
                        if (x < 0 || y < 0 || x >= width || y >= height) continue
                        val q = y * width + x
                        if (mask[q] != 0.toByte() && !seen[q]) { seen[q] = true; stack.add(q) }
                    }
                }
                out += area
            }
            return out.toIntArray()
        }

        override fun floodFill(image: RgbImage, x: Int, y: Int, tolerance: Int): ByteArray {
            val w = image.width; val h = image.height
            val out = ByteArray(w * h); val s = y * w + x
            val stack = ArrayDeque<Int>(); stack.add(s); out[s] = 1
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = p % w + dx; val ny = p / w + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (out[q] != 0.toByte()) continue
                    if (kotlin.math.abs(image.r(q) - image.r(s)) <= tolerance && kotlin.math.abs(image.g(q) - image.g(s)) <= tolerance && kotlin.math.abs(image.b(q) - image.b(s)) <= tolerance) { out[q] = 1; stack.add(q) }
                }
            }
            return out
        }

        override fun fillLargestContour(mask: ByteArray, width: Int, height: Int): ByteArray = mask.copyOf()

        override fun morphology(mask: ByteArray, width: Int, height: Int, op: Morph, kernel: Int, ellipse: Boolean, iterations: Int): ByteArray {
            fun pass(src: ByteArray, dilate: Boolean): ByteArray {
                val r = kernel / 2
                return ByteArray(src.size) { i ->
                    val x = i % width; val y = i / width
                    var any = false; var all = true
                    for (yy in maxOf(0, y - r)..minOf(height - 1, y + r)) for (xx in maxOf(0, x - r)..minOf(width - 1, x + r)) {
                        if (src[yy * width + xx] != 0.toByte()) any = true else all = false
                    }
                    if (if (dilate) any else all) 1 else 0
                }
            }
            var m = mask
            when (op) {
                Morph.DILATE -> repeat(iterations) { m = pass(m, true) }
                Morph.ERODE -> repeat(iterations) { m = pass(m, false) }
                Morph.CLOSE -> { repeat(iterations) { m = pass(m, true) }; repeat(iterations) { m = pass(m, false) } }
            }
            return m
        }

        override fun distanceTransform(mask: ByteArray, width: Int, height: Int): FloatArray = error("unused")

        override fun inpaint(image: RgbImage, mask: ByteArray, radius: Double): RgbImage {
            val out = image.copy()
            for (i in mask.indices) if (mask[i] != 0.toByte()) { out.pixels[i * 3] = 17; out.pixels[i * 3 + 1] = 99; out.pixels[i * 3 + 2] = 3 }
            return out
        }
    }

    private class FakeValue(val data: FloatArray) : OnnxValue {
        override val shape = LongArray(0)
        override fun floats() = data
        override fun longs() = LongArray(0)
        override fun close() = Unit
    }

    private val onnx = object : OnnxRuntime {
        override fun open(path: String, threads: Int, variableShapes: Boolean, accelerator: Accelerator): OnnxSession = error("unused")
        override fun floats(data: FloatArray, shape: LongArray): OnnxValue = FakeValue(data)
        override fun longs(data: LongArray, shape: LongArray): OnnxValue = FakeValue(FloatArray(0))
        override fun bools(data: BooleanArray, shape: LongArray): OnnxValue = FakeValue(FloatArray(0))
    }

    private val session = object : OnnxSession {
        override val inputNames = listOf("image", "mask")
        override val outputNames = listOf("inpainted")
        override fun run(inputs: Map<String, OnnxValue>): OnnxOutputs = object : OnnxOutputs {
            override fun get(name: String): OnnxValue {
                val img = inputs.getValue("image").floats()
                return FakeValue(FloatArray(img.size) { img[it] * 0.5f + 0.25f })
            }
            override fun close() = Unit
        }
        override fun close() = Unit
    }

    private fun page(w: Int, h: Int, paint: (x: Int, y: Int) -> Int): RgbImage {
        val px = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val c = paint(x, y)
            px[(y * w + x) * 3] = (c shr 16).toByte(); px[(y * w + x) * 3 + 1] = (c shr 8).toByte(); px[(y * w + x) * 3 + 2] = c.toByte()
        }
        return RgbImage(w, h, px)
    }

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    /** Strokes: vertical bars and a few dots, standing in for glyphs. */
    private fun isInk(x: Int, y: Int, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        if (x !in x0 until x1 || y !in y0 until y1) return false
        val lx = x - x0; val ly = y - y0
        return (lx % 9 in 2..3 && ly % 24 in 2..18) || (ly % 11 == 5 && lx % 5 < 2 && lx % 9 != 0)
    }

    private fun eraserDigest(img: RgbImage, e: Erased?): String {
        val f = Fnv()
        img.pixels.forEach { f.add(it.toInt()) }
        if (e == null) return "null:${f.h.toULong().toString(16)}"
        e.background.forEach { f.add(it) }; e.ink.forEach { f.add(it) }
        f.add(e.flat).add(e.sourceSize ?: -1).add(e.area.x0).add(e.area.y0).add(e.area.w).add(e.area.h)
        e.area.m.forEach { f.add(it) }
        f.add(e.inBubble).add(e.horizontalSource)
        return f.h.toULong().toString(16)
    }

    @Test
    fun erasingIsUnchanged() {
        val ops = FakeOps()
        val plain = MangaEraser(ops)
        val withLama = MangaEraser(ops, MangaInpainter(onnx, session))
        val sb = StringBuilder()
        fun case(name: String, eraser: MangaEraser, img: RgbImage, box: Box, bubble: Box?, overArt: Boolean = false, horizontal: Boolean? = null) {
            val e = eraser.erase(img, box, bubble, overArt, horizontal)
            sb.append("$name=${eraserDigest(img, e)}\n")
        }
        val white = rgb(250, 250, 250); val black = rgb(10, 12, 14)
        // flat background, text outside any bubble, across both source directions
        for (horizontal in listOf(null, true, false)) {
            case("flat/$horizontal", plain, page(160, 120) { x, y -> if (isInk(x, y, 30, 20, 120, 90)) black else white }, Box(30f, 20f, 120f, 90f), null, horizontal = horizontal)
        }
        case("flat-wide", plain, page(200, 100) { x, y -> if (isInk(x, y, 20, 30, 170, 60)) black else white }, Box(20f, 30f, 170f, 60f), null)
        case("flat-clamped", plain, page(80, 60) { x, y -> if (isInk(x, y, 0, 0, 80, 60)) black else white }, Box(-5f, -5f, 90f, 70f), null)
        // inside a bubble (a white oval on grey)
        for (horizontal in listOf(null, true)) {
            val bubbleBox = Box(20f, 10f, 180f, 130f)
            val img = page(200, 140) { x, y ->
                val dx = (x - 100) / 80f; val dy = (y - 70) / 60f
                if (dx * dx + dy * dy > 1f) rgb(120, 120, 120) else if (isInk(x, y, 60, 30, 140, 110)) black else white
            }
            case("bubble/$horizontal", plain, img, Box(60f, 30f, 140f, 110f), bubbleBox, horizontal = horizontal)
        }
        case("bubble-tiny", plain, page(60, 60) { x, y -> if (isInk(x, y, 10, 10, 40, 40)) black else white }, Box(10f, 10f, 40f, 40f), Box(30f, 30f, 32f, 32f))
        // a smooth gradient: quadratic fit
        val grad = { x: Int, y: Int, ink: Boolean -> if (ink) rgb(15, 15, 20) else rgb(100 + x / 2, 120 + y / 3, 180 - x / 4) }
        case("gradient", plain, page(220, 150) { x, y -> grad(x, y, isInk(x, y, 50, 30, 170, 120)) }, Box(50f, 30f, 170f, 120f), null)
        case("gradient-bubble", plain, page(220, 150) { x, y ->
            val dx = (x - 110) / 95f; val dy = (y - 75) / 65f
            if (dx * dx + dy * dy > 1f) rgb(30, 30, 30) else grad(x, y, isInk(x, y, 50, 30, 170, 120))
        }, Box(50f, 30f, 170f, 120f), Box(15f, 10f, 205f, 140f))
        // a bumpy background: the fit is poor, Telea instead
        case("bumpy", plain, page(220, 150) { x, y -> if (isInk(x, y, 50, 30, 170, 120)) rgb(15, 15, 20) else { val v = 120 + ((x * 31 + y * 17) % 9) * 3 + (if ((x / 6 + y / 6) % 2 == 0) 8 else 0); rgb(v, v, v) } }, Box(50f, 30f, 170f, 120f), null)
        case("sparse-fit", plain, page(50, 40) { x, y -> if (isInk(x, y, 5, 5, 45, 35)) rgb(15, 15, 20) else rgb(100 + x, 110, 120) }, Box(5f, 5f, 45f, 35f), null)
        // over art: not uniform
        val art = { x: Int, y: Int -> if (isInk(x, y, 40, 30, 150, 110)) rgb(250, 250, 250) else if (isInk(x + 1, y + 1, 40, 30, 150, 110) || isInk(x - 1, y - 1, 40, 30, 150, 110)) rgb(5, 5, 5) else rgb((x * 5) % 200, (y * 3 + x) % 200, (x + y * 7) % 256) }
        case("art-no-lama", plain, page(200, 140, art), Box(40f, 30f, 150f, 110f), null, overArt = true)
        case("art-not-over", withLama, page(200, 140, art), Box(40f, 30f, 150f, 110f), null, overArt = false)
        for (horizontal in listOf(null, true, false)) case("art/$horizontal", withLama, page(200, 140, art), Box(40f, 30f, 150f, 110f), null, overArt = true, horizontal = horizontal)
        case("art-small", withLama, page(200, 140, art), Box(40f, 30f, 47f, 38f), null, overArt = true)
        case("art-flat-ink", withLama, page(200, 140) { x, y -> if (isInk(x, y, 40, 30, 150, 110)) rgb(250, 250, 250) else rgb(60 + (x % 7) * 20, 30 + (y % 5) * 40, 90) }, Box(40f, 30f, 150f, 110f), null, overArt = true)
        assertEquals(EXPECTED_ERASER, Fnv().add(sb.toString()).h.toULong().toString(16), sb.toString())
    }

    private companion object {
        const val EXPECTED_LAYOUT = "1908ea717c2caf02"
        const val EXPECTED_TOKENS = "9c1dc3f16f9dbc7c"
        const val EXPECTED_CHAMFER = "9a6c49d74c3a12b7"
        const val EXPECTED_ERASER = "6d24b3159485a94d"
    }
}
