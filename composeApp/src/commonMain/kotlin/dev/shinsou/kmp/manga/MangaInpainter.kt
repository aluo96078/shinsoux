package dev.shinsou.kmp.manga

import kotlin.math.max
import kotlin.math.min

/**
 * Redraws artwork under text with LaMa (the optional `inpaint-v1` pack, ADR-0005 §2): the masked
 * pixels of a box are filled from the art around it. The model takes any size that is a multiple
 * of 8; the box is given [CONTEXT] pixels of surrounding art and padded by reflection.
 */
public class MangaInpainter(private val onnx: OnnxRuntime, private val session: OnnxSession) {

    /** Fills the pixels of [mask] (box coordinates, row-major, non-zero = fill) in place. */
    public fun inpaint(img: RgbImage, x0: Int, y0: Int, x1: Int, y1: Int, mask: ByteArray) {
        val W = img.width; val H = img.height
        val cx0 = max(0, x0 - CONTEXT); val cy0 = max(0, y0 - CONTEXT)
        val cx1 = min(W, x1 + CONTEXT); val cy1 = min(H, y1 + CONTEXT)
        val cw = cx1 - cx0; val ch = cy1 - cy0
        val iw = cw + (8 - cw % 8) % 8; val ih = ch + (8 - ch % 8) % 8
        val plane = iw * ih
        val image = FloatArray(3 * plane)
        for (y in 0 until ih) for (x in 0 until iw) {
            // padding mirrors the edge (OpenCV's BORDER_REFLECT), so it adds no new colours
            val sx = cx0 + reflect(x, cw); val sy = cy0 + reflect(y, ch)
            val s = sy * W + sx; val o = y * iw + x
            image[o] = img.r(s) / 255f; image[plane + o] = img.g(s) / 255f; image[2 * plane + o] = img.b(s) / 255f
        }
        val hole = FloatArray(plane)
        val bw = x1 - x0
        for (y in 0 until y1 - y0) for (x in 0 until bw) {
            if (mask[y * bw + x] != 0.toByte()) hole[(y + y0 - cy0) * iw + x + x0 - cx0] = 1f
        }
        onnx.floats(image, longArrayOf(1, 3, ih.toLong(), iw.toLong())).use { imageIn ->
            onnx.floats(hole, longArrayOf(1, 1, ih.toLong(), iw.toLong())).use { maskIn ->
                session.run(mapOf("image" to imageIn, "mask" to maskIn)).use { out ->
                    val result = out["inpainted"].floats()
                    for (y in 0 until ch) for (x in 0 until cw) {
                        val i = y * iw + x
                        if (hole[i] == 0f) continue
                        val o = ((cy0 + y) * W + cx0 + x) * 3
                        for (c in 0 until 3) img.pixels[o + c] = (result[c * plane + i] * 255f).toInt().coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }

    private fun reflect(i: Int, n: Int): Int = if (i < n) i else (2 * n - 1 - i).coerceIn(0, n - 1)

    public companion object {
        /** Art around the box given to the model, in pixels. */
        public const val CONTEXT: Int = 64
    }
}
