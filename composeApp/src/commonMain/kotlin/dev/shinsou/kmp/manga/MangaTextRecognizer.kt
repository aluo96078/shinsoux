package dev.shinsou.kmp.manga

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Reads the text of a page's regions (docs/research/manga-multilingual.md). */
public interface TextRecognizer {
    /**
     * Part of what a page's reading is cached by; null for [MangaOcr], whose readings were cached
     * before there were other recognisers.
     */
    public val id: String?

    /** The text of each of [boxes] on [page], in their order. */
    public fun read(page: RgbImage, boxes: List<Box>): List<String>
}

/** manga-ocr reads each region whole, in fixed groups of [MangaOcr.BATCH] (ADR-0005 §2). */
internal class MangaOcrRecognizer(private val ocr: MangaOcr) : TextRecognizer {
    override val id: String? = null

    override fun read(page: RgbImage, boxes: List<Box>): List<String> = boxes.chunked(MangaOcr.BATCH).flatMap { group ->
        ocr.read(group.map { page.crop(it.x0.toInt(), it.y0.toInt(), it.x1.toInt(), it.y1.toInt()) })
    }
}

/**
 * PaddleOCR PP-OCRv5 mobile, for Chinese and English pages: its line detector finds the lines of
 * a region, lines found in pieces are joined, and each line is read by the CTC recogniser and the
 * lines joined in reading order. Chinese may be written in columns, read right to left; English
 * is always in rows. Every line is read on its own, so a page always reads the same.
 */
public class PaddleOcr(
    private val onnx: OnnxRuntime,
    private val ops: VisionOps,
    private val lines: OnnxSession,
    private val recognizer: OnnxSession,
    /** The recogniser's characters, as in its dictionary (the blank and the space are added here). */
    characters: List<String>,
    /** [OcrScript.CHINESE] or [OcrScript.LATIN]. */
    private val script: OcrScript,
) : TextRecognizer {
    private val chars = listOf("") + characters + " "
    override val id: String = "ppocr5-" + script.id

    override fun read(page: RgbImage, boxes: List<Box>): List<String> = boxes.map { readRegion(page, it) }

    private fun readRegion(page: RgbImage, box: Box): String {
        val x0 = max(0, box.x0.toInt() - PAD); val y0 = max(0, box.y0.toInt() - PAD)
        val crop = page.crop(x0, y0, box.x1.toInt() + PAD, box.y1.toInt() + PAD)
        val found = detectLines(crop)
        if (found.isEmpty()) return ""
        val vertical = script.columns && isVertical(found)
        val parts = groupLines(found, vertical).map { line ->
            val piece = crop.crop(floor(line.x0).toInt(), floor(line.y0).toInt(), ceil(line.x1).toInt(), ceil(line.y1).toInt())
            recognize(if (vertical) piece.rotatedLeft() else piece)
        }
        return joinLines(parts, script)
    }

    /** The text lines (or their pieces) of a region, in its pixels. */
    private fun detectLines(crop: RgbImage): List<Box> {
        val (w, h) = crop.width to crop.height
        var scale = 1.0
        // small regions are enlarged so the short side is 64, large ones fit in 960
        if (min(w, h) < 64) scale = 64.0 / min(w, h)
        if (max(w, h) * scale > 960) scale = 960.0 / max(w, h)
        val nw = max(32, round(w * scale / 32).toInt() * 32)
        val nh = max(32, round(h * scale / 32).toInt() * 32)
        val small = ops.resize(crop, nw, nh)
        val plane = nw * nh
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            input[i] = (small.r(i) / 255f - 0.485f) / 0.229f
            input[plane + i] = (small.g(i) / 255f - 0.456f) / 0.224f
            input[2 * plane + i] = (small.b(i) / 255f - 0.406f) / 0.225f
        }
        val prob = onnx.floats(input, longArrayOf(1, 3, nh.toLong(), nw.toLong())).use { tensor ->
            lines.run(mapOf(lines.inputNames.first() to tensor)).use { it[lines.outputNames.first()].floats() }
        }
        return lineBoxes(prob, nw, nh).mapNotNull { b ->
            val sx = w.toFloat() / nw; val sy = h.toFloat() / nh
            Box(max(0f, b.x0 * sx), max(0f, b.y0 * sy), min(w.toFloat(), b.x1 * sx), min(h.toFloat(), b.y1 * sy))
                .takeIf { it.width >= 2 && it.height >= 2 }
        }
    }

    private fun recognize(line: RgbImage): String {
        val width = (ceil(REC_HEIGHT.toDouble() * line.width / line.height).toInt()).coerceIn(16, 3200)
        val small = ops.resize(line, width, REC_HEIGHT)
        val plane = width * REC_HEIGHT
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            input[i] = small.r(i) / 127.5f - 1f
            input[plane + i] = small.g(i) / 127.5f - 1f
            input[2 * plane + i] = small.b(i) / 127.5f - 1f
        }
        return onnx.floats(input, longArrayOf(1, 3, REC_HEIGHT.toLong(), width.toLong())).use { tensor ->
            recognizer.run(mapOf(recognizer.inputNames.first() to tensor)).use { out ->
                val value = out[recognizer.outputNames.first()]
                ctcDecode(value.floats(), value.shape.last().toInt(), chars)
            }
        }
    }

    public companion object {
        /** Margin around a region, so its line detector sees where the text ends. */
        private const val PAD = 4
        private const val REC_HEIGHT = 48
    }
}

/** How a [PaddleOcr] recogniser's text runs and joins. */
public enum class OcrScript(internal val id: String, internal val columns: Boolean, internal val spaced: Boolean) {
    /** Chinese: in rows or columns, lines joined directly. */
    CHINESE("zh", columns = true, spaced = false),

    /** English: in rows, lines joined by a space, a word broken by a hyphen joined again. */
    LATIN("en", columns = false, spaced = true),
}

/**
 * The text lines in the line detector's probability map ([width] × [height]): connected areas
 * above 0.3, at least three pixels across and sure on average, each grown by the margin the
 * detector shrinks lines by (area × 1.5 / perimeter).
 */
internal fun lineBoxes(prob: FloatArray, width: Int, height: Int): List<Box> {
    val label = IntArray(width * height) { -1 }
    val stack = IntArray(width * height)
    val boxes = mutableListOf<Box>()
    var next = 0
    for (start in prob.indices) {
        if (label[start] >= 0 || prob[start] <= 0.3f) continue
        // one 8-connected area, by a flood from its first pixel
        var top = 0
        stack[top++] = start; label[start] = next
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        while (top > 0) {
            val p = stack[--top]
            val x = p % width; val y = p / width
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                val q = ny * width + nx
                if (label[q] < 0 && prob[q] > 0.3f) { label[q] = next; stack[top++] = q }
            }
        }
        next++
        val w = (maxX - minX).toFloat(); val h = (maxY - minY).toFloat()
        if (min(w, h) < 3) continue
        var sum = 0.0
        for (y in minY..maxY) for (x in minX..maxX) sum += prob[y * width + x]
        if (sum / ((maxX - minX + 1) * (maxY - minY + 1)) < 0.6) continue
        val d = w * h * 1.5f / (2 * (w + h))
        boxes += Box(minX - d, minY - d, maxX + d, maxY + d)
    }
    return boxes
}

/** Whether a region's lines are columns: most pieces at least half again as tall as wide. */
internal fun isVertical(boxes: List<Box>): Boolean = boxes.count { it.height >= 1.5f * it.width } * 2 > boxes.size

/**
 * Pieces of one line (one column, when [vertical]) as one box: pieces overlapping by half the
 * smaller across the line belong together. Lines in reading order: columns right to left, rows
 * top to bottom.
 */
internal fun groupLines(boxes: List<Box>, vertical: Boolean): List<Box> {
    fun lo(b: Box) = if (vertical) b.x0 else b.y0
    fun hi(b: Box) = if (vertical) b.x1 else b.y1
    val lines = mutableListOf<Box>()
    for (b in boxes.sortedBy { (lo(it) + hi(it)) / 2 }) {
        val at = lines.indexOfFirst { ln -> min(hi(ln), hi(b)) - max(lo(ln), lo(b)) >= 0.5f * min(hi(ln) - lo(ln), hi(b) - lo(b)) }
        if (at < 0) lines += b
        else lines[at] = lines[at].let { ln -> Box(min(ln.x0, b.x0), min(ln.y0, b.y0), max(ln.x1, b.x1), max(ln.y1, b.y1)) }
    }
    return if (vertical) lines.sortedByDescending { it.x0 + it.x1 } else lines.sortedBy { it.y0 + it.y1 }
}

/** Greedy CTC: the likeliest class at each step, repeats collapsed, the blank (class 0) dropped. */
internal fun ctcDecode(scores: FloatArray, classes: Int, chars: List<String>): String {
    val text = StringBuilder()
    var previous = -1
    for (step in 0 until scores.size / classes) {
        val base = step * classes
        var best = 0
        for (c in 1 until classes) if (scores[base + c] > scores[base + best]) best = c
        if (best != previous && best != 0) text.append(chars.getOrElse(best) { "?" })
        previous = best
    }
    return text.toString()
}

/** A region's lines as one text: CJK lines run on; Latin ones take a space, or close a hyphenated word. */
internal fun joinLines(parts: List<String>, script: OcrScript): String {
    val out = StringBuilder()
    for (part in parts) {
        val t = part.trim()
        if (t.isEmpty()) continue
        when {
            out.isEmpty() || !script.spaced -> out.append(t)
            out.length >= 2 && out.last() == '-' && out[out.length - 2].isLetter() -> { out.setLength(out.length - 1); out.append(t) }
            else -> out.append(' ').append(t)
        }
    }
    return out.toString()
}

/** The image turned a quarter to the left (a column of text becomes a row read left to right). */
internal fun RgbImage.rotatedLeft(): RgbImage {
    val out = ByteArray(pixels.size)
    // pixel (x, y) moves to (y, width - 1 - x) in a height-wide image
    for (y in 0 until height) for (x in 0 until width) {
        val from = (y * width + x) * 3
        val to = ((width - 1 - x) * height + y) * 3
        out[to] = pixels[from]; out[to + 1] = pixels[from + 1]; out[to + 2] = pixels[from + 2]
    }
    return RgbImage(height, width, out)
}
