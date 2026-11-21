package dev.shinsou.kmp.manga

/** What the detector found: 0 = speech bubble, 1 = text in a bubble, 2 = text over the artwork. */
public enum class RegionKind { BUBBLE, BUBBLE_TEXT, FREE_TEXT }

public data class Detection(val kind: RegionKind, val score: Float, val box: Box)

/** Finds the bubbles and text of a page, in page pixels. */
public interface PageDetector {
    /** Names what found the regions: pages read by different detectors are cached apart. */
    public val id: String

    public fun detect(page: RgbImage): List<Detection>
}

/**
 * The comic text and bubble detector (RT-DETR-v2, `detector-v4-s_int8`): the page is squashed to
 * 640×640, and the model returns boxes already scaled back to page pixels.
 */
public class MangaDetector(private val onnx: OnnxRuntime, private val ops: VisionOps, private val session: OnnxSession) : PageDetector {
    override val id: String get() = ID

    override fun detect(page: RgbImage): List<Detection> {
        val size = INPUT
        val small = ops.resize(page, size, size)
        val plane = size * size
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            data[i] = small.r(i) / 255f
            data[plane + i] = small.g(i) / 255f
            data[2 * plane + i] = small.b(i) / 255f
        }
        val labels: LongArray; val boxes: FloatArray; val scores: FloatArray
        onnx.floats(data, longArrayOf(1, 3, size.toLong(), size.toLong())).use { images ->
            onnx.longs(longArrayOf(page.width.toLong(), page.height.toLong()), longArrayOf(1, 2)).use { sizes ->
                session.run(mapOf("images" to images, "orig_target_sizes" to sizes)).use { out ->
                    labels = out["labels"].longs(); boxes = out["boxes"].floats(); scores = out["scores"].floats()
                }
            }
        }
        return select(labels, boxes, scores)
    }

    public companion object {
        public const val INPUT: Int = 640
        public const val BUBBLE_TEXT_THRESHOLD: Float = 0.45f

        /** Text over the artwork scores lower than text in bubbles (ADR-0005 §2). */
        public const val FREE_TEXT_THRESHOLD: Float = 0.35f
        public const val ID: String = "v4s"

        /**
         * The raw queries of a DETR-style detector (as the Core ML one returns them): per query
         * a score per kind ([scores], already sigmoid) and a box as centre and size relative to
         * the page ([boxes]). Each query keeps its likeliest kind; then as [select].
         */
        public fun fromQueries(scores: FloatArray, boxes: FloatArray, width: Int, height: Int, kinds: Int = 3): List<Detection> {
            val queries = boxes.size / 4
            val labels = LongArray(queries); val best = FloatArray(queries); val pixels = FloatArray(queries * 4)
            for (q in 0 until queries) {
                var kind = 0
                for (k in 1 until kinds) if (scores[q * kinds + k] > scores[q * kinds + kind]) kind = k
                labels[q] = kind.toLong(); best[q] = scores[q * kinds + kind]
                val cx = boxes[q * 4]; val cy = boxes[q * 4 + 1]; val w = boxes[q * 4 + 2]; val h = boxes[q * 4 + 3]
                pixels[q * 4] = (cx - w / 2) * width; pixels[q * 4 + 1] = (cy - h / 2) * height
                pixels[q * 4 + 2] = (cx + w / 2) * width; pixels[q * 4 + 3] = (cy + h / 2) * height
            }
            return select(labels, pixels, best)
        }

        /**
         * Applies the per-kind thresholds, then drops a text box that overlaps a surer text box by
         * more than half: the same text can come back both "in a bubble" and "over the art".
         */
        public fun select(labels: LongArray, boxes: FloatArray, scores: FloatArray): List<Detection> {
            val found = labels.indices.mapNotNull { i ->
                val kind = when (labels[i]) { 0L -> RegionKind.BUBBLE; 1L -> RegionKind.BUBBLE_TEXT; 2L -> RegionKind.FREE_TEXT; else -> return@mapNotNull null }
                val threshold = if (kind == RegionKind.FREE_TEXT) FREE_TEXT_THRESHOLD else BUBBLE_TEXT_THRESHOLD
                if (scores[i] <= threshold) null
                else Detection(kind, scores[i], Box(boxes[i * 4], boxes[i * 4 + 1], boxes[i * 4 + 2], boxes[i * 4 + 3]))
            }.sortedByDescending { it.score }
            val kept = mutableListOf<Detection>()
            for (d in found) {
                if (d.kind == RegionKind.BUBBLE || kept.none { it.kind != RegionKind.BUBBLE && it.box.iou(d.box) > 0.5f }) kept += d
            }
            return kept
        }
    }
}
