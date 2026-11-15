package dev.shinsou.kmp.manga

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * The RT-DETRv2 R50 detector (`vision-qnn-v1`: a 640 by 640 page in, each query's kind scores and
 * normalised box out) on an ONNX Runtime session set up for an accelerator: Qualcomm's NPU on
 * Android, Core ML on a Mac (docs/research/manga-gpu-npu.md).
 */
internal class OrtQueryDetector(
    private val ops: VisionOps,
    private val session: OrtSession,
    override val id: String,
) : PageDetector, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()

    override fun detect(page: RgbImage): List<Detection> {
        val small = ops.resize(page, SIZE, SIZE)
        val plane = SIZE * SIZE
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) { data[i] = small.r(i) / 255f; data[plane + i] = small.g(i) / 255f; data[2 * plane + i] = small.b(i) / 255f }
        val (scores, boxes) = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            session.run(mapOf("pixels" to input)).use { out ->
                fun floats(name: String) = (out.get(name).get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                floats("scores") to floats("boxes")
            }
        }
        return MangaDetector.fromQueries(scores, boxes, page.width, page.height)
    }

    override fun close() = session.close()

    internal companion object {
        const val SIZE: Int = 640
    }
}
