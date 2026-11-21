package dev.shinsou.kmp.manga

/**
 * manga-ocr-2025 (ViT encoder + BERT decoder) with the merged past-key-values decoder: one encoder
 * run per batch, then greedy decoding one token per step. The key/value state stays in the
 * runtime's memory from step to step.
 *
 * Batches must be formed the same way every time (ADR-0005 §2): with dynamic int8 the activation
 * scales cover the whole batch, so a crop's text depends on what it is batched with.
 */
public class MangaOcr(
    private val onnx: OnnxRuntime,
    private val ops: VisionOps,
    private val encoder: OnnxSession,
    private val decoder: OnnxSession,
    private val vocab: List<String>,
) {
    private val pastNames = decoder.inputNames.filter { it.startsWith("past_key_values.") }

    /** Reads each crop (a text region cut from the page). */
    public fun read(crops: List<RgbImage>): List<String> {
        if (crops.isEmpty()) return emptyList()
        val n = crops.size
        val plane = SIZE * SIZE
        val pixels = FloatArray(n * 3 * plane)
        crops.forEachIndexed { b, crop ->
            val small = ops.resize(crop, SIZE, SIZE)
            val base = b * 3 * plane
            for (i in 0 until plane) {
                // grey, then normalised to [-1, 1] the way the ViT image processor does
                val g = (small.r(i) * 299 + small.g(i) * 587 + small.b(i) * 114) / 1000
                val v = (g / 255f - 0.5f) / 0.5f
                pixels[base + i] = v; pixels[base + plane + i] = v; pixels[base + 2 * plane + i] = v
            }
        }
        val ids = List(n) { mutableListOf(CLS) }
        onnx.floats(pixels, longArrayOf(n.toLong(), 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            encoder.run(mapOf("pixel_values" to input)).use { encoded ->
                decode(encoded[encoder.outputNames.first()], n, ids)
            }
        }
        return ids.map { text(it) }
    }

    private fun decode(hidden: OnnxValue, n: Int, ids: List<MutableList<Long>>) {
        val done = BooleanArray(n)
        val last = LongArray(n) { CLS }
        val empty = onnx.floats(FloatArray(0), longArrayOf(n.toLong(), HEADS, 0, HEAD_DIM))
        val past = HashMap<String, OnnxValue>().apply { pastNames.forEach { put(it, empty) } }
        // The first step's result owns the encoder key/values used by every later step, and the
        // latest result owns the decoder key/values the next step reads: keep both open.
        var first: OnnxOutputs? = null
        var previous: OnnxOutputs? = null
        try {
            for (step in 0 until MAX_STEPS) {
                if (done.all { it }) break
                val out = onnx.longs(last.copyOf(), longArrayOf(n.toLong(), 1)).use { input ->
                    onnx.bools(booleanArrayOf(first != null), longArrayOf(1)).use { branch ->
                        decoder.run(HashMap<String, OnnxValue>(past).apply {
                            put("input_ids", input); put("encoder_hidden_states", hidden); put("use_cache_branch", branch)
                        })
                    }
                }
                val logits = out["logits"].floats()
                val size = logits.size / n
                for (b in 0 until n) {
                    var best = 0; var bestValue = Float.NEGATIVE_INFINITY
                    val base = b * size
                    for (v in 0 until size) { val x = logits[base + v]; if (x > bestValue) { bestValue = x; best = v } }
                    last[b] = best.toLong()
                    if (!done[b]) { if (best.toLong() == SEP) done[b] = true else ids[b].add(best.toLong()) }
                }
                val isFirst = first == null
                for (name in pastNames) {
                    if (".decoder." in name || isFirst) past[name] = out["present" + name.removePrefix("past_key_values")]
                }
                if (isFirst) first = out
                else { previous?.close(); previous = out }
            }
        } finally {
            previous?.close(); first?.close(); empty.close()
        }
    }

    private fun text(ids: List<Long>): String =
        ids.drop(1).filter { it >= FIRST_TOKEN }.joinToString("") { vocab.getOrElse(it.toInt()) { "" } }.replace("##", "")

    public companion object {
        public const val SIZE: Int = 224

        /** Crops read together (ADR-0005 §2): fixed groups of eight, in detection order. */
        public const val BATCH: Int = 8
        private const val CLS = 2L
        private const val SEP = 3L
        private const val FIRST_TOKEN = 5L
        private const val HEADS = 12L
        private const val HEAD_DIM = 64L
        private const val MAX_STEPS = 300
    }
}
